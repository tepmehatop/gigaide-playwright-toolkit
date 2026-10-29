package dev.pwtoolkit.nav;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TypeScript 5/6 {@code lib/tsserver.js} run by Node.js: pure JavaScript, so it works where security policies forbid
 * executing native binaries such as TypeScript 7's (e.g. "Exec failed, error=1, Operation not permitted" on SberOS).
 * Same requests as the VS Code TypeScript extension: open/close, definition, encodedSemanticClassifications-full.
 */
final class TsserverBackend implements TsBackend {
    private static final Logger LOG = Logger.getInstance(TsserverBackend.class);
    private static final Gson GSON = new Gson();
    /** TypeScript's SemanticClassificationFormat "2020": classification = (type + 1) << 8 | modifiers. */
    private static final String[] TOKEN_TYPES = {"class", "enum", "interface", "namespace", "typeParameter", "type",
            "parameter", "variable", "enumMember", "property", "function", "member"};
    private static final String[] TOKEN_MODIFIERS = {"declaration", "static", "async", "readonly", "defaultLibrary", "local"};

    private final Path root;
    private final Path tsserver;
    private final String version;
    private final Process process;
    private final OutputStream out;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final StringBuilder stderrTail = new StringBuilder();
    private final CompletableFuture<JsonObject> ready;

    TsserverBackend(@NotNull Path root, @NotNull String node, @NotNull Path tsserver, @NotNull String version) throws IOException {
        this.root = root;
        this.tsserver = tsserver;
        this.version = version;
        this.process = new ProcessBuilder(node, tsserver.toString(), "--disableAutomaticTypingAcquisition",
                "--suppressDiagnosticEvents", "--locale", "en").directory(root.toFile()).start();
        this.out = process.getOutputStream();
        startThread("reader", this::readLoop);
        startThread("stderr", this::drainStderr);
        this.ready = request("configure", Map.of("preferences", Map.of("includeCompletionsForModuleExports", true,
                "includeCompletionsWithInsertText", true)));
    }

    @Override
    public @NotNull CompletableFuture<?> ready() {
        return ready;
    }

    @Override
    public synchronized void sync(@NotNull List<DocumentChange> changes) {
        for (DocumentChange change : changes) {
            String file = change.file().toString();
            if (change.text() == null || !change.open()) send("close", Map.of("file", file));
            if (change.text() != null) {
                // close + open with the full text: simple and exact (no incremental edits needed)
                send("open", Map.of("file", file, "fileContent", change.text(), "scriptKindName", scriptKind(file),
                        "projectRootPath", root.toString()));
            }
        }
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Location>> definition(@NotNull Path file, int line, int character) {
        return request("definition", Map.of("file", file.toString(), "line", line + 1, "offset", character + 1)).thenApply(response -> {
            List<TsLanguageService.Location> locations = new ArrayList<>();
            JsonElement body = response.get("body");
            if (body == null || !body.isJsonArray()) return locations;
            for (JsonElement element : body.getAsJsonArray()) {
                JsonObject definition = element.getAsJsonObject();
                JsonObject start = definition.getAsJsonObject("start");
                locations.add(new TsLanguageService.Location(Path.of(definition.get("file").getAsString()),
                        start.get("line").getAsInt() - 1, start.get("offset").getAsInt() - 1));
            }
            return locations;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.SemanticToken>> semanticTokens(@NotNull Path file, @NotNull CharSequence text) {
        return request("encodedSemanticClassifications-full",
                Map.of("file", file.toString(), "start", 0, "length", text.length(), "format", "2020")).thenApply(response -> {
            List<TsLanguageService.SemanticToken> tokens = new ArrayList<>();
            JsonObject body = response.has("body") && response.get("body").isJsonObject() ? response.getAsJsonObject("body") : null;
            JsonArray spans = body == null ? null : body.getAsJsonArray("spans");
            if (spans == null) return tokens;
            for (int i = 0; i + 2 < spans.size(); i += 3) {
                int start = spans.get(i).getAsInt();
                int length = spans.get(i + 1).getAsInt();
                int classification = spans.get(i + 2).getAsInt();
                int type = (classification >> 8) - 1;
                if (type < 0 || type >= TOKEN_TYPES.length || start + length > text.length()) continue;
                Set<String> modifiers = new HashSet<>();
                for (int bit = 0; bit < TOKEN_MODIFIERS.length; bit++) {
                    if ((classification & (1 << bit)) != 0) modifiers.add(TOKEN_MODIFIERS[bit]);
                }
                // tsserver says "member" for methods, the LSP legend says "method"
                String name = TOKEN_TYPES[type].equals("member") ? "method" : TOKEN_TYPES[type];
                tokens.add(new TsLanguageService.SemanticToken(start, length, name, modifiers));
            }
            return tokens;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Completion>> completions(@NotNull Path file, int line, int character,
                                                                                      @Nullable String trigger) {
        Map<String, Object> arguments = new java.util.HashMap<>(Map.of("file", file.toString(), "line", line + 1, "offset", character + 1,
                "includeExternalModuleExports", true, "includeInsertTextCompletions", true));
        if (trigger != null) arguments.put("triggerCharacter", trigger);
        return request("completionInfo", arguments).thenApply(response -> {
            List<TsLanguageService.Completion> completions = new ArrayList<>();
            JsonObject body = response.has("body") && response.get("body").isJsonObject() ? response.getAsJsonObject("body") : null;
            JsonArray entries = body == null ? null : body.getAsJsonArray("entries");
            if (entries == null) return completions;
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("name").getAsString();
                boolean autoImport = entry.has("hasAction") && entry.get("hasAction").getAsBoolean() && entry.has("source");
                completions.add(new TsLanguageService.Completion(name, completionKind(entry.has("kind") ? entry.get("kind").getAsString() : ""),
                        entry.has("sortText") ? entry.get("sortText").getAsString() : name,
                        entry.has("insertText") ? entry.get("insertText").getAsString() : null,
                        autoImport ? entry.get("source").getAsString() : null, autoImport ? entry : null));
            }
            return completions;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Edit>> completionEdits(@NotNull Path file, int line, int character,
                                                                                    @NotNull TsLanguageService.Completion completion) {
        if (!(completion.data() instanceof JsonObject entry)) return CompletableFuture.completedFuture(List.of());
        Map<String, Object> name = new java.util.HashMap<>();
        name.put("name", entry.get("name").getAsString());
        if (entry.has("source")) name.put("source", entry.get("source").getAsString());
        if (entry.has("data")) name.put("data", entry.get("data"));
        return request("completionEntryDetails", Map.of("file", file.toString(), "line", line + 1, "offset", character + 1,
                "entryNames", List.of(name))).thenApply(response -> {
            List<TsLanguageService.Edit> edits = new ArrayList<>();
            JsonElement body = response.get("body");
            if (body == null || !body.isJsonArray() || body.getAsJsonArray().isEmpty()) return edits;
            JsonObject details = body.getAsJsonArray().get(0).getAsJsonObject();
            if (!details.has("codeActions")) return edits;
            for (JsonElement action : details.getAsJsonArray("codeActions")) edits.addAll(changes(action.getAsJsonObject().getAsJsonArray("changes")));
            return edits;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Diagnostic>> diagnostics(@NotNull Path file) {
        Map<String, Object> arguments = Map.of("file", file.toString(), "includeLinePosition", true);
        CompletableFuture<JsonObject> syntactic = request("syntacticDiagnosticsSync", arguments);
        CompletableFuture<JsonObject> semantic = request("semanticDiagnosticsSync", arguments);
        return syntactic.thenCombine(semantic, (a, b) -> {
            List<TsLanguageService.Diagnostic> result = new ArrayList<>();
            for (JsonObject response : List.of(a, b)) {
                JsonElement body = response.get("body");
                if (body == null || !body.isJsonArray()) continue;
                for (JsonElement element : body.getAsJsonArray()) {
                    JsonObject d = element.getAsJsonObject();
                    JsonObject start = d.getAsJsonObject("startLocation");
                    JsonObject end = d.getAsJsonObject("endLocation");
                    String category = d.has("category") ? d.get("category").getAsString() : "error";
                    result.add(new TsLanguageService.Diagnostic(new TsLanguageService.Range(start.get("line").getAsInt() - 1,
                            start.get("offset").getAsInt() - 1, end.get("line").getAsInt() - 1, end.get("offset").getAsInt() - 1),
                            category.equals("error") || category.equals("warning") ? category : "suggestion",
                            d.has("code") ? d.get("code").getAsInt() : 0, d.get("message").getAsString()));
                }
            }
            return result;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Fix>> fixes(@NotNull Path file, @NotNull TsLanguageService.Diagnostic diagnostic) {
        TsLanguageService.Range r = diagnostic.range();
        return request("getCodeFixes", Map.of("file", file.toString(), "startLine", r.line() + 1, "startOffset", r.character() + 1,
                "endLine", r.endLine() + 1, "endOffset", r.endCharacter() + 1, "errorCodes", List.of(diagnostic.code()))).thenApply(response -> {
            List<TsLanguageService.Fix> fixes = new ArrayList<>();
            JsonElement body = response.get("body");
            if (body == null || !body.isJsonArray()) return fixes;
            for (JsonElement element : body.getAsJsonArray()) {
                JsonObject fix = element.getAsJsonObject();
                List<TsLanguageService.Edit> edits = changes(fix.getAsJsonArray("changes"));
                if (!edits.isEmpty()) fixes.add(new TsLanguageService.Fix(fix.get("description").getAsString(), edits));
            }
            return fixes;
        });
    }

    /** FileCodeEdits[]: [{fileName, textChanges: [{start: {line, offset}, end, newText}]}], 1-based. */
    private static @NotNull List<TsLanguageService.Edit> changes(@Nullable JsonArray changes) {
        List<TsLanguageService.Edit> edits = new ArrayList<>();
        if (changes == null) return edits;
        for (JsonElement change : changes) {
            JsonObject fileEdits = change.getAsJsonObject();
            Path path = Path.of(fileEdits.get("fileName").getAsString());
            for (JsonElement element : fileEdits.getAsJsonArray("textChanges")) {
                JsonObject textChange = element.getAsJsonObject();
                JsonObject start = textChange.getAsJsonObject("start");
                JsonObject end = textChange.getAsJsonObject("end");
                edits.add(new TsLanguageService.Edit(path, new TsLanguageService.Range(start.get("line").getAsInt() - 1,
                        start.get("offset").getAsInt() - 1, end.get("line").getAsInt() - 1, end.get("offset").getAsInt() - 1),
                        textChange.get("newText").getAsString()));
            }
        }
        return edits;
    }

    /** TypeScript's ScriptElementKind to our kind names. */
    private static @NotNull String completionKind(@NotNull String kind) {
        return switch (kind) {
            case "method", "construct", "call", "index", "constructor" -> "method";
            case "function", "local function" -> "function";
            case "property", "getter", "setter", "accessor" -> "property";
            case "var", "let", "local var", "parameter", "alias", "using", "await using" -> "variable";
            case "const" -> "constant";
            case "class", "local class" -> "class";
            case "interface", "type" -> "interface";
            case "enum" -> "enum";
            case "enum member" -> "enumMember";
            case "module", "external module name", "directory", "script" -> "module";
            case "keyword" -> "keyword";
            case "type parameter" -> "typeParameter";
            case "warning" -> "word"; // names from the file, offered for receivers of type any
            default -> "other";
        };
    }

    @Override
    public void filesChanged(@NotNull List<Path> files, @NotNull List<Integer> types) {
        // tsserver watches the project files itself
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public @NotNull String state() {
        if (process.isAlive()) return "running";
        try {
            return "exited with code " + process.exitValue();
        } catch (IllegalThreadStateException e) {
            return "exiting";
        }
    }

    @Override
    public @NotNull String stderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString();
        }
    }

    @Override
    public @NotNull String description() {
        return "tsserver.js (TypeScript " + version + ") via node: " + tsserver;
    }

    @Override
    public void shutdown() {
        if (process.isAlive()) send("exit", null);
        process.destroy();
        failPending(new IOException("tsserver stopped"));
    }

    private static @NotNull String scriptKind(@NotNull String file) {
        String name = StringUtil.toLowerCase(file);
        if (name.endsWith(".tsx")) return "TSX";
        if (name.endsWith(".jsx")) return "JSX";
        if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) return "JS";
        return "TS";
    }

    private @NotNull CompletableFuture<JsonObject> request(@NotNull String command, @Nullable Object arguments) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        int id = send(command, arguments);
        if (id < 0) {
            future.completeExceptionally(new IOException("tsserver is not running: " + state()));
        } else {
            pending.put(id, future);
        }
        return future;
    }

    /** One JSON request per line on stdin. Returns the request seq, or -1 when the process is gone. */
    private synchronized int send(@NotNull String command, @Nullable Object arguments) {
        if (!process.isAlive()) return -1;
        int id = seq.incrementAndGet();
        JsonObject message = new JsonObject();
        message.addProperty("seq", id);
        message.addProperty("type", "request");
        message.addProperty("command", command);
        if (arguments != null) message.add("arguments", GSON.toJsonTree(arguments));
        try {
            out.write((GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return id;
        } catch (IOException e) {
            LOG.debug("tsserver write failed", e);
            return -1;
        }
    }

    /** Responses and events: "Content-Length: N\r\n\r\n{json}". */
    private void readLoop() {
        try (InputStream in = new BufferedInputStream(process.getInputStream())) {
            while (true) {
                int length = readContentLength(in);
                if (length < 0) break;
                byte[] body = in.readNBytes(length);
                if (body.length < length) break;
                JsonObject message = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!"response".equals(message.has("type") ? message.get("type").getAsString() : null)) continue;
                CompletableFuture<JsonObject> future = pending.remove(message.get("request_seq").getAsInt());
                if (future == null) continue;
                if (message.has("success") && !message.get("success").getAsBoolean()) {
                    future.completeExceptionally(new IOException(message.has("message") ? message.get("message").getAsString() : "tsserver error"));
                } else {
                    future.complete(message);
                }
            }
        } catch (Exception e) {
            LOG.debug("tsserver reader stopped", e);
        }
        if (!process.isAlive()) LOG.info("[pwtoolkit] tsserver for " + root + " exited: " + state() + " " + stderrTail().strip());
        failPending(new IOException("tsserver exited: " + state()));
    }

    private static int readContentLength(@NotNull InputStream in) throws IOException {
        int length = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) return -1;
            if (b == '\n') {
                String header = line.toString(StandardCharsets.US_ASCII).trim();
                line.reset();
                if (header.isEmpty()) {
                    if (length >= 0) return length;
                    continue;
                }
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    length = Integer.parseInt(header.substring(colon + 1).trim());
                }
            } else {
                line.write(b);
            }
        }
    }

    private void drainStderr() {
        try (InputStream err = process.getErrorStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = err.read(buffer)) >= 0) {
                synchronized (stderrTail) {
                    stderrTail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    if (stderrTail.length() > 2000) stderrTail.delete(0, stderrTail.length() - 2000);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void failPending(@NotNull Throwable error) {
        pending.values().forEach(f -> f.completeExceptionally(error));
        pending.clear();
    }

    private void startThread(@NotNull String kind, @NotNull Runnable body) {
        Thread thread = new Thread(body, "Playwright Toolkit tsserver " + kind + " (" + root.getFileName() + ")");
        thread.setDaemon(true);
        thread.start();
    }
}
