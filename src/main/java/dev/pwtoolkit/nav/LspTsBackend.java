package dev.pwtoolkit.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A language server talking LSP: the native TypeScript 7 server ({@code tsc --lsp --stdio}, or tsgo of
 * @typescript/native-preview), or typescript-language-server run by Node.js on top of a TypeScript 5/6 tsserver.js.
 */
final class LspTsBackend implements TsBackend {
    private static final Logger LOG = Logger.getInstance(LspTsBackend.class);

    private final String description;
    private final LspClient client;
    private final CompletableFuture<JsonElement> ready;
    /**
     * typescript-language-server answers from syntax only (no definition, or the import instead of the class) while its
     * tsserver still loads the project after the first didOpen. The end of the load: work-done progress "Initializing
     * JS/TS language features" ends, or the first diagnostics arrive. Completed at once for the native server.
     */
    private final CompletableFuture<Void> projectLoaded = new CompletableFuture<>();

    static @NotNull LspTsBackend nativeServer(@NotNull Path root, @NotNull Path exe) throws Exception {
        LspTsBackend backend = new LspTsBackend(root, List.of(exe.toString(), "--lsp", "--stdio"), null, "native TypeScript 7: " + exe);
        backend.projectLoaded.complete(null);
        return backend;
    }

    static @NotNull LspTsBackend typescriptLanguageServer(@NotNull Path root, @NotNull String node, @NotNull Path cli,
                                                          @NotNull TsServerLocator.Tsserver tsserver) throws Exception {
        Map<String, Object> options = Map.of("tsserver", Map.of("path", tsserver.path().toString()),
                "hostInfo", "playwright-toolkit", "disableAutomaticTypingAcquisition", true);
        return new LspTsBackend(root, List.of(node, cli.toString(), "--stdio"), options,
                "typescript-language-server + TypeScript " + tsserver.version() + " via node: " + tsserver.path());
    }

    private LspTsBackend(@NotNull Path root, @NotNull List<String> command, @Nullable Map<String, Object> initializationOptions,
                         @NotNull String description) throws Exception {
        this.description = description;
        this.client = LspClient.start(command, root.toFile());
        client.onNotification((method, params) -> {
            // diagnostics come after the semantic check, i.e. with the project loaded
            if ("textDocument/publishDiagnostics".equals(method)) projectLoaded.complete(null);
            if ("$/progress".equals(method) && params != null && params.isJsonObject() && params.getAsJsonObject().has("value")
                    && "end".equals(String.valueOf(params.getAsJsonObject().getAsJsonObject("value").get("kind")).replace("\"", ""))) {
                projectLoaded.complete(null);
            }
        });
        String rootUri = root.toUri().toString();
        Map<String, Object> capabilities = Map.of(
                "textDocument", Map.of("definition", Map.of("linkSupport", true),
                        "synchronization", Map.of("didSave", false, "dynamicRegistration", false),
                        "semanticTokens", Map.of("requests", Map.of("full", true), "formats", List.of("relative"),
                                "tokenTypes", List.of("namespace", "class", "enum", "interface", "typeParameter", "type",
                                        "parameter", "variable", "property", "enumMember", "function", "method"),
                                "tokenModifiers", List.of("declaration", "readonly", "static", "async", "defaultLibrary", "local"))),
                "window", Map.of("workDoneProgress", true),
                "workspace", Map.of("didChangeWatchedFiles", Map.of("dynamicRegistration", true),
                        "workspaceFolders", true, "configuration", true));
        Map<String, Object> params = new HashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", rootUri);
        params.put("capabilities", capabilities);
        params.put("workspaceFolders", List.of(Map.of("uri", rootUri, "name", root.getFileName().toString())));
        if (initializationOptions != null) params.put("initializationOptions", initializationOptions);
        this.ready = client.request("initialize", params).thenApply(result -> {
            client.notify("initialized", Map.of());
            return result;
        });
    }

    @Override
    public @NotNull CompletableFuture<?> ready() {
        return ready;
    }

    @Override
    public synchronized void sync(@NotNull List<DocumentChange> changes) {
        for (DocumentChange change : changes) {
            String uri = uri(change.file());
            if (change.text() == null) {
                client.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
            } else if (change.open()) {
                client.notify("textDocument/didOpen", Map.of("textDocument",
                        Map.of("uri", uri, "languageId", change.languageId(), "version", change.version(), "text", change.text())));
            } else {
                client.notify("textDocument/didChange", Map.of(
                        "textDocument", Map.of("uri", uri, "version", change.version()),
                        "contentChanges", List.of(Map.of("text", change.text()))));
            }
        }
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Location>> definition(@NotNull Path file, int line, int character) {
        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", uri(file)));
        params.put("position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/definition", params)).thenApply(LspTsBackend::parseLocations);
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.SemanticToken>> semanticTokens(@NotNull Path file, @NotNull CharSequence text) {
        return loaded().thenCompose(ignored -> client.request("textDocument/semanticTokens/full", Map.of("textDocument", Map.of("uri", uri(file)))))
                .thenApply(result -> decodeTokens(result, ready.join(), text));
    }

    /** The project load, but no longer than 20 s: a server that reports nothing still gets the requests. */
    private @NotNull CompletableFuture<Void> loaded() {
        return projectLoaded.isDone() ? projectLoaded : projectLoaded.copy().completeOnTimeout(null, 20, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void filesChanged(@NotNull List<Path> files, @NotNull List<Integer> types) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) changes.add(Map.of("uri", uri(files.get(i)), "type", types.get(i)));
        client.notify("workspace/didChangeWatchedFiles", Map.of("changes", changes));
    }

    @Override
    public boolean isAlive() {
        return client.isAlive();
    }

    @Override
    public @NotNull String state() {
        return client.state();
    }

    @Override
    public @NotNull String stderrTail() {
        return client.stderrTail();
    }

    @Override
    public @NotNull String description() {
        return description;
    }

    @Override
    public void shutdown() {
        client.shutdown();
    }

    private static @NotNull String uri(@NotNull Path file) {
        return file.toUri().toString();
    }

    static @NotNull List<TsLanguageService.Location> parseLocations(@Nullable JsonElement result) {
        List<TsLanguageService.Location> locations = new ArrayList<>();
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
                locations.add(new TsLanguageService.Location(Path.of(new URI(uri)), start.get("line").getAsInt(), start.get("character").getAsInt()));
            } catch (Exception e) {
                LOG.debug("Unsupported location " + uri, e);
            }
        }
        return locations;
    }

    private static @NotNull List<TsLanguageService.SemanticToken> decodeTokens(@Nullable JsonElement result, @NotNull JsonElement initResult,
                                                                              @NotNull CharSequence text) {
        List<TsLanguageService.SemanticToken> tokens = new ArrayList<>();
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
        int[] lineStarts = lineStarts(text);
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
            if (line >= lineStarts.length || typeIndex >= types.size()) continue;
            int start = lineStarts[line] + character;
            if (start + length > text.length()) continue;
            Set<String> mods = new HashSet<>();
            for (int bit = 0; bit < modifiers.size(); bit++) {
                if ((modifierBits & (1 << bit)) != 0) mods.add(modifiers.get(bit).getAsString());
            }
            // typescript-language-server passes tsserver's names through: "member" is a method
            String type = types.get(typeIndex).getAsString();
            tokens.add(new TsLanguageService.SemanticToken(start, length, type.equals("member") ? "method" : type, mods));
        }
        return tokens;
    }

    static int @NotNull [] lineStarts(@NotNull CharSequence text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
