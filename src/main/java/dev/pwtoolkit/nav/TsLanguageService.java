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
import dev.pwtoolkit.util.JsFiles;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
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
 * Keeps one TypeScript 7 language server per project root (directory with tsconfig/jsconfig/package.json)
 * and mirrors editor contents into it, so definitions reflect unsaved changes.
 */
@Service(Service.Level.PROJECT)
public final class TsLanguageService implements Disposable {
    private static final Logger LOG = Logger.getInstance(TsLanguageService.class);
    private static final String NOTIFICATION_GROUP = "Playwright Toolkit";
    private static final AtomicBoolean INSTALL_SUGGESTED = new AtomicBoolean();

    private final Project project;
    private final Map<Path, Server> servers = new ConcurrentHashMap<>();

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
        List<Snapshot> snapshots = server.collectChanges(file, document);
        int line = document.getLineNumber(offset);
        int character = offset - document.getLineStartOffset(line);

        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", uri(file)));
        params.put("position", Map.of("line", line, "character", character));
        return server.ready
                .thenCompose(ignored -> {
                    server.send(snapshots);
                    return server.client.request("textDocument/definition", params);
                })
                .thenApply(TsLanguageService::parseLocations);
    }

    /** Starts the server for the file's root ahead of time, so the first Ctrl+Click is fast. */
    /** A semantic token: absolute offsets in the document text that was sent with the request. */
    public record SemanticToken(int start, int length, @NotNull String type, @NotNull Set<String> modifiers) {
    }

    /** Must be called inside a read action. Null when no server is available for the file. */
    public @Nullable CompletableFuture<List<SemanticToken>> semanticTokens(@NotNull VirtualFile file, @NotNull Document document) {
        Server server = serverFor(file);
        if (server == null) return null;
        List<Snapshot> snapshots = server.collectChanges(file, document);
        CharSequence text = document.getImmutableCharSequence();
        return server.ready
                .thenCompose(initResult -> {
                    server.send(snapshots);
                    return server.client.request("textDocument/semanticTokens/full", Map.of("textDocument", Map.of("uri", uri(file))));
                })
                .thenApply(result -> decodeTokens(result, server.ready.join(), text));
    }

    private static @NotNull List<SemanticToken> decodeTokens(@Nullable JsonElement result, @NotNull JsonElement initResult, @NotNull CharSequence text) {
        List<SemanticToken> tokens = new ArrayList<>();
        if (result == null || !result.isJsonObject() || !result.getAsJsonObject().has("data")) return tokens;
        JsonObject legend;
        try {
            legend = initResult.getAsJsonObject().getAsJsonObject("capabilities")
                    .getAsJsonObject("semanticTokensProvider").getAsJsonObject("legend");
        } catch (Exception e) {
            return tokens;
        }
        JsonArray types = legend.getAsJsonArray("tokenTypes");
        JsonArray modifiers = legend.getAsJsonArray("tokenModifiers");

        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lineStarts.add(i + 1);
        }
        JsonArray data = result.getAsJsonObject().getAsJsonArray("data");
        int line = 0;
        int character = 0;
        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = data.get(i).getAsInt();
            line += deltaLine;
            character = deltaLine == 0 ? character + data.get(i + 1).getAsInt() : data.get(i + 1).getAsInt();
            int length = data.get(i + 2).getAsInt();
            int typeIndex = data.get(i + 3).getAsInt();
            int modifierBits = data.get(i + 4).getAsInt();
            if (line >= lineStarts.size() || typeIndex >= types.size()) continue;
            int start = lineStarts.get(line) + character;
            if (start + length > text.length()) continue;
            Set<String> mods = new java.util.HashSet<>();
            for (int bit = 0; bit < modifiers.size(); bit++) {
                if ((modifierBits & (1 << bit)) != 0) mods.add(modifiers.get(bit).getAsString());
            }
            tokens.add(new SemanticToken(start, length, types.get(typeIndex).getAsString(), mods));
        }
        return tokens;
    }

    public void warmUp(@NotNull VirtualFile file) {
        Server server = serverFor(file);
        if (server == null) return;
        Document document = ApplicationManager.getApplication().runReadAction((Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(file));
        if (document == null) return;
        List<Snapshot> snapshots = ApplicationManager.getApplication().runReadAction((Computable<List<Snapshot>>) () -> server.collectChanges(file, document));
        server.ready.thenRun(() -> server.send(snapshots));
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
        if (existing != null && existing.client.isAlive()) return existing;
        synchronized (servers) {
            existing = servers.get(root);
            if (existing != null && existing.client.isAlive()) return existing;
            Path exe = TsServerLocator.find(root);
            if (exe == null) {
                suggestInstall();
                return null;
            }
            try {
                Server server = new Server(root, exe);
                servers.put(root, server);
                return server;
            } catch (Exception e) {
                LOG.warn("Cannot start TypeScript language server " + exe, e);
                return null;
            }
        }
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
            List<Map<String, Object>> changes = new ArrayList<>();
            for (VFileEvent event : events) {
                String path = event.getPath();
                if (!path.startsWith(server.root.toString())) continue;
                if (!JsFiles.isScriptName(path) && !path.endsWith(".json")) continue;
                if (event.isFromSave() && server.opened.containsKey(uri(path))) continue;
                int type = event instanceof VFileCreateEvent ? 1 : event instanceof VFileDeleteEvent ? 3 : 2;
                changes.add(Map.of("uri", uri(path), "type", type));
            }
            if (!changes.isEmpty()) {
                server.ready.thenRun(() -> server.client.notify("workspace/didChangeWatchedFiles", Map.of("changes", changes)));
            }
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
                    INSTALL_SUGGESTED.set(false);
                    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                            .createNotification("TypeScript 7 language server installed", NotificationType.INFORMATION)
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

    private static @NotNull List<Location> parseLocations(@Nullable JsonElement result) {
        List<Location> locations = new ArrayList<>();
        if (result == null || result.isJsonNull()) return locations;
        JsonArray array;
        if (result.isJsonArray()) {
            array = result.getAsJsonArray();
        } else {
            array = new JsonArray();
            array.add(result);
        }
        for (JsonElement element : array) {
            JsonObject location = element.getAsJsonObject();
            String uri = location.has("targetUri") ? location.get("targetUri").getAsString() : location.get("uri").getAsString();
            JsonObject range = location.has("targetSelectionRange")
                    ? location.getAsJsonObject("targetSelectionRange")
                    : location.getAsJsonObject("range");
            JsonObject start = range.getAsJsonObject("start");
            try {
                locations.add(new Location(Path.of(new URI(uri)), start.get("line").getAsInt(), start.get("character").getAsInt()));
            } catch (Exception e) {
                LOG.debug("Unsupported location " + uri, e);
            }
        }
        return locations;
    }

    static @NotNull String uri(@NotNull VirtualFile file) {
        return uri(file.getPath());
    }

    static @NotNull String uri(@NotNull String path) {
        return Path.of(path).toUri().toString();
    }

    private record Snapshot(@NotNull String uri, @NotNull String languageId, @Nullable String text, int version, boolean open) {
    }

    private final class Server {
        final Path root;
        final LspClient client;
        final CompletableFuture<JsonElement> ready;
        /** uri -> document modification stamp last sent to the server. */
        final Map<String, Long> opened = new ConcurrentHashMap<>();
        final Map<String, Integer> versions = new ConcurrentHashMap<>();

        Server(@NotNull Path root, @NotNull Path exe) throws Exception {
            this.root = root;
            this.client = LspClient.start(List.of(exe.toString(), "--lsp", "--stdio"), root.toFile());
            String rootUri = root.toUri().toString();
            Map<String, Object> capabilities = Map.of(
                    "textDocument", Map.of("definition", Map.of("linkSupport", true),
                            "synchronization", Map.of("didSave", false, "dynamicRegistration", false),
                            "semanticTokens", Map.of("requests", Map.of("full", true), "formats", List.of("relative"),
                                    "tokenTypes", List.of("namespace", "class", "enum", "interface", "typeParameter", "type",
                                            "parameter", "variable", "property", "enumMember", "function", "method"),
                                    "tokenModifiers", List.of("declaration", "readonly", "static", "async", "defaultLibrary", "local"))),
                    "workspace", Map.of("didChangeWatchedFiles", Map.of("dynamicRegistration", true),
                            "workspaceFolders", true, "configuration", true));
            Map<String, Object> params = new HashMap<>();
            params.put("processId", ProcessHandle.current().pid());
            params.put("rootUri", rootUri);
            params.put("capabilities", capabilities);
            params.put("workspaceFolders", List.of(Map.of("uri", rootUri, "name", root.getFileName().toString())));
            this.ready = client.request("initialize", params).thenApply(result -> {
                client.notify("initialized", Map.of());
                return result;
            });
        }

        /**
         * Computes what has to be sent so that the server sees the current editor state:
         * the requested document plus every document we opened earlier.
         */
        synchronized @NotNull List<Snapshot> collectChanges(@NotNull VirtualFile current, @NotNull Document currentDocument) {
            FileDocumentManager fdm = FileDocumentManager.getInstance();
            List<Snapshot> result = new ArrayList<>();
            Map<String, VirtualFile> candidates = new HashMap<>();
            candidates.put(uri(current), current);
            for (String uri : opened.keySet()) {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(URI.create(uri)));
                candidates.putIfAbsent(uri, vf);
            }
            for (Document unsaved : fdm.getUnsavedDocuments()) {
                VirtualFile vf = fdm.getFile(unsaved);
                if (vf != null && JsFiles.isScript(vf) && vf.getPath().startsWith(root.toString())) candidates.putIfAbsent(uri(vf), vf);
            }
            for (Map.Entry<String, VirtualFile> entry : candidates.entrySet()) {
                String uri = entry.getKey();
                VirtualFile vf = entry.getValue();
                Document document = vf == null ? null : vf.equals(current) ? currentDocument : fdm.getCachedDocument(vf);
                Long sentStamp = opened.get(uri);
                if (document == null) {
                    if (sentStamp != null) {
                        opened.remove(uri);
                        result.add(new Snapshot(uri, "", null, 0, false));
                    }
                    continue;
                }
                long stamp = document.getModificationStamp();
                if (sentStamp != null && sentStamp == stamp) continue;
                opened.put(uri, stamp);
                int version = versions.merge(uri, 1, Integer::sum);
                result.add(new Snapshot(uri, JsFiles.languageId(vf.getName()), document.getText(), version, sentStamp == null));
            }
            return result;
        }

        synchronized void send(@NotNull List<Snapshot> snapshots) {
            for (Snapshot s : snapshots) {
                if (s.text == null) {
                    client.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", s.uri)));
                } else if (s.open) {
                    client.notify("textDocument/didOpen", Map.of("textDocument",
                            Map.of("uri", s.uri, "languageId", s.languageId, "version", s.version, "text", s.text)));
                } else {
                    client.notify("textDocument/didChange", Map.of(
                            "textDocument", Map.of("uri", s.uri, "version", s.version),
                            "contentChanges", List.of(Map.of("text", s.text))));
                }
            }
        }

        void stop() {
            client.shutdown();
        }
    }
}
