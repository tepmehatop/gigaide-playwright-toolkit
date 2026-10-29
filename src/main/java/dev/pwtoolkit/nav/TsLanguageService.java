package dev.pwtoolkit.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.components.Service;
import dev.pwtoolkit.run.PwResources;
import dev.pwtoolkit.util.JsFiles;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one TypeScript language server ({@link TsBackend}) per project root (directory with tsconfig/jsconfig/package.json)
 * and mirrors editor contents into it, so definitions reflect unsaved changes.
 */
@Service(Service.Level.PROJECT)
public final class TsLanguageService implements Disposable {
    private static final Logger LOG = Logger.getInstance(TsLanguageService.class);
    private static final String NOTIFICATION_GROUP = "Playwright Toolkit";
    private static final AtomicBoolean INSTALL_SUGGESTED = new AtomicBoolean();
    /** typescript-language-server 5.1.3 (Apache-2.0), bundled: one file, no dependencies, uses the project's TypeScript. */
    private static final String TSLS_CLI = "tsls/lib/cli.mjs";
    private static final String TSLS_PACKAGE = "tsls/package.json";

    private final Project project;
    private final Map<Path, Server> servers = new ConcurrentHashMap<>();
    /** Why a server could not be started, per root (diagnostics). */
    private final Map<Path, String> startFailures = new ConcurrentHashMap<>();

    public TsLanguageService(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                onFilesChanged(events);
            }
        });
    }

    public static @NotNull TsLanguageService getInstance(@NotNull Project project) {
        return project.getService(TsLanguageService.class);
    }

    /** A definition target reported by the language server (0-based line/character, UTF-16). */
    public record Location(@NotNull Path file, int line, int character) {
    }

    /** Must be called inside a read action: captures document texts synchronously. */
    public @Nullable CompletableFuture<List<Location>> definition(@NotNull VirtualFile file, @NotNull Document document, int offset) {
        Server server = serverFor(file);
        if (server == null) return null;
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);
        return server.update(file, document).thenCompose(ignored -> server.backend.definition(file.toNioPath(), line, character));
    }

    /**
     * A completion item. kind: method, function, property, variable, constant, class, interface, enum, enumMember,
     * module, keyword, typeParameter or other. sortText orders the items like the TypeScript server does.
     */
    public record Completion(@NotNull String name, @NotNull String kind, @NotNull String sortText, @Nullable String insertText,
                             @Nullable String source, @Nullable com.google.gson.JsonElement data) {
        public Completion(@NotNull String name, @NotNull String kind, @NotNull String sortText, @Nullable String insertText) {
            this(name, kind, sortText, insertText, null, null);
        }

        /** An auto-import: the item comes from another module, inserting it adds an import. */
        public boolean isAutoImport() {
            return source != null;
        }
    }

    /** A signature: its label and the [start, end) of every parameter in it. */
    public record Signature(@NotNull String label, @NotNull List<int[]> parameters, @NotNull String documentation) {
    }

    /** Empty signatures: no call around the position. */
    public record SignatureHelp(@NotNull List<Signature> signatures, int activeSignature, int activeParameter) {
        public static final SignatureHelp NONE = new SignatureHelp(List.of(), 0, 0);
    }

    /** signature: e.g. "function selectFromDropDown(page: any, name: string): Promise<void>"; null when nothing is known. */
    public record QuickInfo(@Nullable String signature, @NotNull String documentation) {
        public static final QuickInfo NONE = new QuickInfo(null, "");
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<SignatureHelp> signatureHelp(@NotNull VirtualFile file, @NotNull Document document, int offset) {
        Server server = serverFor(file);
        if (server == null) return null;
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);
        return server.update(file, document).thenCompose(ignored -> server.backend.signatureHelp(file.toNioPath(), line, character));
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<QuickInfo> quickInfo(@NotNull VirtualFile file, @NotNull Document document, int offset) {
        Server server = serverFor(file);
        if (server == null) return null;
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);
        return server.update(file, document).thenCompose(ignored -> server.backend.quickInfo(file.toNioPath(), line, character));
    }

    /** 0-based lines, UTF-16 characters. */
    public record Range(int line, int character, int endLine, int endCharacter) {
    }

    /** severity: error, warning or suggestion. */
    public record Diagnostic(@NotNull Range range, @NotNull String severity, int code, @NotNull String message) {
    }

    public record Edit(@NotNull Path file, @NotNull Range range, @NotNull String newText) {
    }

    public record Fix(@NotNull String title, @NotNull List<Edit> edits) {
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<Edit>> completionEdits(@NotNull VirtualFile file, @NotNull Document document, int offset,
                                                                   @NotNull Completion completion) {
        Server server = serverFor(file);
        if (server == null) return null;
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);
        return server.update(file, document).thenCompose(ignored -> server.backend.completionEdits(file.toNioPath(), line, character, completion));
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<Diagnostic>> diagnostics(@NotNull VirtualFile file, @NotNull Document document) {
        Server server = serverFor(file);
        if (server == null) return null;
        return server.update(file, document).thenCompose(ignored -> server.backend.diagnostics(file.toNioPath()));
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<Fix>> fixes(@NotNull VirtualFile file, @NotNull Document document, @NotNull Diagnostic diagnostic) {
        Server server = serverFor(file);
        if (server == null) return null;
        return server.update(file, document).thenCompose(ignored -> server.backend.fixes(file.toNioPath(), diagnostic));
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<Completion>> completions(@NotNull VirtualFile file, @NotNull Document document, int offset,
                                                                    @Nullable String trigger) {
        Server server = serverFor(file);
        if (server == null) return null;
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);
        return server.update(file, document).thenCompose(ignored -> server.backend.completions(file.toNioPath(), line, character, trigger));
    }

    /** A semantic token: absolute offsets in the document text that was sent with the request. */
    public record SemanticToken(int start, int length, @NotNull String type, @NotNull Set<String> modifiers) {
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<SemanticToken>> semanticTokens(@NotNull VirtualFile file, @NotNull Document document) {
        Server server = serverFor(file);
        if (server == null) return null;
        CharSequence text = document.getImmutableCharSequence();
        return server.update(file, document)
                .thenCompose(ignored -> server.backend.semanticTokens(file.toNioPath(), text))
                .whenComplete((tokens, error) -> server.lastTokens = error != null
                        ? "error: " + error.getMessage()
                        : tokens.size() + " tokens for " + file.getName());
    }

    /** What happens with the TypeScript server for this file: for Diagnose Highlighting / bug reports. */
    public @NotNull List<String> describe(@NotNull VirtualFile file) {
        List<String> lines = new ArrayList<>();
        Path root = findRoot(file);
        if (root == null) {
            lines.add("TS root: none (no tsconfig.json / package.json above " + file.getPath() + ")");
            return lines;
        }
        lines.add("TS root: " + root);
        Server server = servers.get(root);
        if (server == null) {
            lines.add("TS server: not started" + (startFailures.containsKey(root) ? " - " + startFailures.get(root) : ""));
        } else {
            CompletableFuture<?> ready = server.backend.ready();
            String init = !ready.isDone() ? "initializing" : ready.isCompletedExceptionally() ? "init FAILED" : "initialized";
            lines.add("TS server: " + server.backend.description() + " | " + server.backend.state() + " | " + init + " | last: " + server.lastTokens);
            if (startFailures.containsKey(root)) lines.add("TS skipped: " + startFailures.get(root));
            String stderr = server.backend.stderrTail().strip();
            if (!stderr.isEmpty()) lines.add("TS stderr: " + stderr.substring(Math.max(0, stderr.length() - 300)).replace('\n', ' '));
        }
        TsServerLocator.explain(root).forEach(line -> lines.add("TS search: " + line));
        return lines;
    }

    public void warmUp(@NotNull VirtualFile file) {
        Server server = serverFor(file);
        if (server == null) return;
        Document document = ApplicationManager.getApplication().runReadAction((Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(file));
        if (document == null) return;
        ApplicationManager.getApplication().runReadAction((Computable<CompletableFuture<?>>) () -> server.update(file, document));
    }

    public void restartAll() {
        servers.values().forEach(Server::stop);
        servers.clear();
    }

    @Override
    public void dispose() {
        restartAll();
    }

    private @Nullable Server serverFor(@NotNull VirtualFile file) {
        Path root = findRoot(file);
        if (root == null) return null;
        Server existing = servers.get(root);
        if (existing != null && existing.backend.isAlive()) return existing;
        synchronized (servers) {
            existing = servers.get(root);
            if (existing != null && existing.backend.isAlive()) return existing;
            if (existing != null) existing.stop();
            TsBackend backend = startBackend(root);
            if (backend == null) {
                servers.remove(root);
                return null;
            }
            backend.onDiagnosticsPushed(path -> {
                VirtualFile pushed = LocalFileSystem.getInstance().findFileByNioFile(path);
                if (pushed == null || project.isDisposed()) return;
                // typescript-language-server pushes diagnostics later than the IDE asked for them: analyze the file again
                ApplicationManager.getApplication().runReadAction(() -> {
                    com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(pushed);
                    if (psiFile != null) com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
                });
            });
            Server server = new Server(root, backend);
            servers.put(root, server);
            return server;
        }
    }

    /**
     * Starts the configured server; in auto mode the next one when a server is missing or cannot be started
     * (e.g. native binaries forbidden by a security policy: "Exec failed, error=1, Operation not permitted").
     */
    private @Nullable TsBackend startBackend(@NotNull Path root) {
        TsServerLocator.Backend mode = TsServerLocator.backend();
        boolean auto = mode == TsServerLocator.Backend.AUTO;
        List<String> problems = new ArrayList<>();
        if (auto || mode == TsServerLocator.Backend.NATIVE) {
            Path exe = TsServerLocator.find(root);
            if (exe == null) {
                problems.add("native: no TypeScript 7");
            } else {
                try {
                    return remember(root, problems, LspTsBackend.nativeServer(root, exe));
                } catch (Exception e) {
                    LOG.warn("Cannot start TypeScript language server " + exe, e);
                    problems.add("native: " + exe + ": " + e.getMessage());
                }
            }
        }
        if (auto || mode == TsServerLocator.Backend.TSSERVER || mode == TsServerLocator.Backend.TSLS) {
            TsServerLocator.Tsserver tsserver = TsServerLocator.findTsserver(root);
            String node = NodeLocator.findNode(null);
            if (tsserver == null) {
                problems.add("node: no TypeScript 5/6 (lib/tsserver.js) in node_modules");
            } else if (node == null) {
                problems.add("node: Node.js not found");
            } else {
                if (auto || mode == TsServerLocator.Backend.TSSERVER) {
                    try {
                        return remember(root, problems, new TsserverBackend(root, node, tsserver.path(), tsserver.version()));
                    } catch (Exception e) {
                        LOG.warn("Cannot start " + tsserver.path(), e);
                        problems.add("tsserver: " + node + " " + tsserver.path() + ": " + e.getMessage());
                    }
                }
                if (auto || mode == TsServerLocator.Backend.TSLS) {
                    try {
                        Path cli = PwResources.install(TSLS_CLI);
                        PwResources.install(TSLS_PACKAGE);
                        return remember(root, problems, LspTsBackend.typescriptLanguageServer(root, node, cli, tsserver));
                    } catch (Exception e) {
                        LOG.warn("Cannot start typescript-language-server", e);
                        problems.add("tsls: " + node + ": " + e.getMessage());
                    }
                }
            }
        }
        startFailures.put(root, "mode " + mode.name().toLowerCase() + ": " + String.join("; ", problems));
        if (auto || mode == TsServerLocator.Backend.NATIVE) suggestInstall();
        return null;
    }

    private @NotNull TsBackend remember(@NotNull Path root, @NotNull List<String> problems, @NotNull TsBackend backend) {
        if (problems.isEmpty()) startFailures.remove(root);
        else startFailures.put(root, String.join("; ", problems));
        LOG.info("[pwtoolkit] TypeScript server for " + root + ": " + backend.description());
        return backend;
    }

    /** Closest directory with tsconfig.json/jsconfig.json, otherwise closest package.json. */
    static @Nullable Path findRoot(@NotNull VirtualFile file) {
        if (!file.isInLocalFileSystem()) return null;
        VirtualFile packageRoot = null;
        for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.findChild("tsconfig.json") != null || dir.findChild("jsconfig.json") != null) {
                return dir.toNioPath();
            }
            if (packageRoot == null && dir.findChild("package.json") != null) packageRoot = dir;
        }
        return packageRoot == null ? null : packageRoot.toNioPath();
    }

    private void onFilesChanged(@NotNull List<? extends VFileEvent> events) {
        if (servers.isEmpty()) return;
        for (Server server : servers.values()) {
            List<Path> files = new ArrayList<>();
            List<Integer> types = new ArrayList<>();
            for (VFileEvent event : events) {
                String path = event.getPath();
                if (!path.startsWith(server.root.toString())) continue;
                if (!JsFiles.isScriptName(path) && !path.endsWith(".json")) continue;
                if (event.isFromSave() && server.opened.containsKey(path)) continue;
                files.add(Path.of(path));
                types.add(event instanceof VFileCreateEvent ? 1 : event instanceof VFileDeleteEvent ? 3 : 2);
            }
            if (!files.isEmpty()) server.backend.ready().thenRun(() -> server.backend.filesChanged(files, types));
        }
    }

    private void suggestInstall() {
        if (!INSTALL_SUGGESTED.compareAndSet(false, true)) return;
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Playwright Toolkit: TypeScript 7 language server not found",
                        "Navigation (Ctrl+Click) for TypeScript/JavaScript uses the native TypeScript 7 server. " +
                                "Add <code>typescript@7</code> to the project devDependencies, or install a shared copy for the IDE.",
                        NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Install TypeScript 7 for the IDE", this::installManagedTypeScript))
                .notify(project);
    }

    private void installManagedTypeScript() {
        new Task.Backgroundable(project, "Installing TypeScript 7 language server", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String node = NodeLocator.findNode(null);
                if (node == null) {
                    notifyError("Node.js was not found. Configure it in Settings | Tools | Playwright Toolkit.");
                    return;
                }
                Path dir = TsServerLocator.managedInstallDir();
                try {
                    Files.createDirectories(dir);
                    File nodeDir = new File(node).getParentFile();
                    File npm = new File(nodeDir, SystemInfo.isWindows ? "npm.cmd" : "npm");
                    GeneralCommandLine cmd = new GeneralCommandLine(npm.getPath(), "install", "--prefix", dir.toString(),
                            "--no-audit", "--no-fund", "typescript@^7")
                            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                            .withWorkingDirectory(dir);
                    cmd.withEnvironment("PATH", nodeDir.getPath() + File.pathSeparator + cmd.getParentEnvironment().getOrDefault("PATH", ""));
                    ProcessOutput output = new CapturingProcessHandler(cmd).runProcess(5 * 60 * 1000);
                    if (output.getExitCode() != 0) {
                        notifyError("npm install failed:<br>" + output.getStderr());
                        return;
                    }
                    Path nodeModules = dir.resolve("node_modules");
                    if (TsServerLocator.find(nodeModules.getParent()) == null) {
                        // npm skips optional platform packages silently when the registry does not serve them
                        String version = TsServerLocator.explain(dir).stream().filter(l -> l.contains("typescript 7"))
                                .map(l -> l.replaceAll(".*typescript (7[^,]*),.*", "$1")).findFirst().orElse("7");
                        String platformPackage = "@typescript/typescript-" + TsServerLocator.platform() + "@" + version;
                        indicator.setText("Installing " + platformPackage);
                        GeneralCommandLine platformCmd = new GeneralCommandLine(npm.getPath(), "install", "--prefix", dir.toString(),
                                "--no-audit", "--no-fund", "--no-save", platformPackage)
                                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                                .withWorkingDirectory(dir);
                        platformCmd.withEnvironment("PATH", nodeDir.getPath() + File.pathSeparator + platformCmd.getParentEnvironment().getOrDefault("PATH", ""));
                        ProcessOutput platformOutput = new CapturingProcessHandler(platformCmd).runProcess(5 * 60 * 1000);
                        if (TsServerLocator.find(dir) == null) {
                            notifyError("TypeScript 7 was installed, but its binary for " + TsServerLocator.platform() + " is missing: "
                                    + platformPackage + " could not be installed from your npm registry.<br>"
                                    + String.join("<br>", TsServerLocator.explain(dir)) + "<br>" + platformOutput.getStderr());
                            return;
                        }
                    }
                    INSTALL_SUGGESTED.set(false);
                    restartAll();
                    TsSemanticHighlighter.getInstance(project).scheduleOpenFiles();
                    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                            .createNotification("TypeScript 7 language server installed", String.valueOf(TsServerLocator.find(dir)),
                                    NotificationType.INFORMATION)
                            .notify(project);
                } catch (Exception e) {
                    notifyError(e.getMessage());
                }
            }
        }.queue();
    }

    private void notifyError(@Nullable String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Playwright Toolkit", String.valueOf(message), NotificationType.ERROR)
                .notify(project);
    }

    private final class Server {
        final Path root;
        final TsBackend backend;
        volatile String lastTokens = "no request yet";
        /** path -> document modification stamp last sent to the server. */
        final Map<String, Long> opened = new ConcurrentHashMap<>();
        final Map<String, Integer> versions = new ConcurrentHashMap<>();
        /** Document updates are sent strictly in order, and each request only after its own update. */
        private CompletableFuture<?> synced;

        Server(@NotNull Path root, @NotNull TsBackend backend) {
            this.root = root;
            this.backend = backend;
            this.synced = backend.ready();
        }

        /** Must be called inside a read action. Completes once the server has seen the current editor state. */
        synchronized @NotNull CompletableFuture<?> update(@NotNull VirtualFile current, @NotNull Document currentDocument) {
            List<TsBackend.DocumentChange> changes = collectChanges(current, currentDocument);
            if (!changes.isEmpty()) synced = synced.thenRun(() -> backend.sync(changes));
            return synced;
        }

        /**
         * Computes what has to be sent so that the server sees the current editor state:
         * the requested document plus every document we opened earlier.
         */
        private @NotNull List<TsBackend.DocumentChange> collectChanges(@NotNull VirtualFile current, @NotNull Document currentDocument) {
            FileDocumentManager fdm = FileDocumentManager.getInstance();
            List<TsBackend.DocumentChange> result = new ArrayList<>();
            Map<String, VirtualFile> candidates = new HashMap<>();
            candidates.put(current.getPath(), current);
            for (String path : opened.keySet()) {
                candidates.putIfAbsent(path, LocalFileSystem.getInstance().findFileByNioFile(Path.of(path)));
            }
            for (Document unsaved : fdm.getUnsavedDocuments()) {
                VirtualFile vf = fdm.getFile(unsaved);
                if (vf != null && JsFiles.isScript(vf) && vf.getPath().startsWith(root.toString())) candidates.putIfAbsent(vf.getPath(), vf);
            }
            for (Map.Entry<String, VirtualFile> entry : candidates.entrySet()) {
                String path = entry.getKey();
                VirtualFile vf = entry.getValue();
                Document document = vf == null ? null : vf.equals(current) ? currentDocument : fdm.getCachedDocument(vf);
                Long sentStamp = opened.get(path);
                if (document == null) {
                    if (sentStamp != null) {
                        opened.remove(path);
                        result.add(new TsBackend.DocumentChange(Path.of(path), "", null, 0, false));
                    }
                    continue;
                }
                long stamp = document.getModificationStamp();
                if (sentStamp != null && sentStamp == stamp) continue;
                opened.put(path, stamp);
                int version = versions.merge(path, 1, Integer::sum);
                result.add(new TsBackend.DocumentChange(Path.of(path), JsFiles.languageId(vf.getName()), document.getText(), version, sentStamp == null));
            }
            return result;
        }

        void stop() {
            backend.shutdown();
        }
    }
}
