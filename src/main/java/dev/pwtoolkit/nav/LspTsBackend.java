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
    /** typescript-language-server forwards raw tsserver requests; the native TypeScript 7 server does not. */
    private final boolean tsserverRequests;
    private final LspClient client;
    private final CompletableFuture<JsonElement> ready;
    /**
     * typescript-language-server answers from syntax only (no definition, or the import instead of the class) while its
     * tsserver still loads the project after the first didOpen. The end of the load: work-done progress "Initializing
     * JS/TS language features" ends, or the first diagnostics arrive. Completed at once for the native server.
     */
    private final CompletableFuture<Void> projectLoaded = new CompletableFuture<>();
    /** Diagnostics pushed by the server (typescript-language-server has no pull diagnostics), by URI. */
    private final Map<String, JsonArray> pushedDiagnostics = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Path> diagnosticsListener = path -> {
    };

    /** Parameter name hints like IntelliJ: before literals only, not when the argument has the parameter's name. */
    static final Map<String, Object> INLAY_PREFERENCES = Map.of("includeInlayParameterNameHints", "literals",
            "includeInlayParameterNameHintsWhenArgumentMatchesName", false);

    /** The same in the settings format of VS Code / TypeScript 7 ("typescript.inlayHints.parameterNames.enabled"). */
    private static @NotNull JsonElement languageSettings() {
        Map<String, Object> inlayHints = new HashMap<>(INLAY_PREFERENCES);
        inlayHints.put("parameterNames", Map.of("enabled", "literals", "suppressWhenArgumentMatchesName", true));
        return new com.google.gson.Gson().toJsonTree(Map.of("inlayHints", inlayHints));
    }

    static @NotNull LspTsBackend nativeServer(@NotNull Path root, @NotNull Path exe) throws Exception {
        LspTsBackend backend = new LspTsBackend(root, List.of(exe.toString(), "--lsp", "--stdio"), null, "native TypeScript 7: " + exe, false);
        backend.projectLoaded.complete(null);
        return backend;
    }

    static @NotNull LspTsBackend typescriptLanguageServer(@NotNull Path root, @NotNull String node, @NotNull Path cli,
                                                          @NotNull TsServerLocator.Tsserver tsserver) throws Exception {
        Map<String, Object> options = Map.of("tsserver", Map.of("path", tsserver.path().toString()),
                "hostInfo", "playwright-toolkit", "disableAutomaticTypingAcquisition", true, "preferences", INLAY_PREFERENCES);
        return new LspTsBackend(root, List.of(node, cli.toString(), "--stdio"), options,
                "typescript-language-server + TypeScript " + tsserver.version() + " via node: " + tsserver.path(), true);
    }

    private LspTsBackend(@NotNull Path root, @NotNull List<String> command, @Nullable Map<String, Object> initializationOptions,
                         @NotNull String description, boolean tsserverRequests) throws Exception {
        this.description = description;
        this.tsserverRequests = tsserverRequests;
        this.client = LspClient.start(command, root.toFile());
        JsonElement settings = languageSettings();
        client.onConfiguration(section -> section.equals("typescript") || section.equals("javascript") || section.equals("js/ts") ? settings : null);
        client.onNotification((method, params) -> {
            // diagnostics come after the semantic check, i.e. with the project loaded
            if ("textDocument/publishDiagnostics".equals(method)) {
                projectLoaded.complete(null);
                if (params != null && params.isJsonObject() && params.getAsJsonObject().has("diagnostics")) {
                    String uri = params.getAsJsonObject().get("uri").getAsString();
                    pushedDiagnostics.put(uri, params.getAsJsonObject().getAsJsonArray("diagnostics"));
                    try {
                        diagnosticsListener.accept(Path.of(new URI(uri)));
                    } catch (Exception e) {
                        LOG.debug("Unsupported diagnostics uri " + uri, e);
                    }
                }
            }
            if ("$/progress".equals(method) && params != null && params.isJsonObject() && params.getAsJsonObject().has("value")
                    && "end".equals(String.valueOf(params.getAsJsonObject().getAsJsonObject("value").get("kind")).replace("\"", ""))) {
                projectLoaded.complete(null);
            }
        });
        String rootUri = root.toUri().toString();
        Map<String, Object> capabilities = Map.of(
                "textDocument", Map.ofEntries(
                        Map.entry("definition", Map.of("linkSupport", true)),
                        Map.entry("typeDefinition", Map.of("linkSupport", true)),
                        Map.entry("completion", Map.of("completionItem", Map.of("snippetSupport", false,
                                "resolveSupport", Map.of("properties", List.of("additionalTextEdits", "detail"))), "contextSupport", true)),
                        Map.entry("publishDiagnostics", Map.of()),
                        Map.entry("signatureHelp", Map.of("signatureInformation", Map.of("parameterInformation", Map.of("labelOffsetSupport", true),
                                "documentationFormat", List.of("plaintext", "markdown"), "activeParameterSupport", true))),
                        Map.entry("hover", Map.of("contentFormat", List.of("markdown", "plaintext"))),
                        Map.entry("references", Map.of()),
                        Map.entry("implementation", Map.of("linkSupport", true)),
                        Map.entry("callHierarchy", Map.of()),
                        Map.entry("inlayHint", Map.of()),
                        Map.entry("documentSymbol", Map.of("hierarchicalDocumentSymbolSupport", true)),
                        Map.entry("diagnostic", Map.of("dynamicRegistration", false)),
                        Map.entry("codeAction", Map.of("codeActionLiteralSupport", Map.of("codeActionKind", Map.of("valueSet", List.of("quickfix"))))),
                        Map.entry("synchronization", Map.of("didSave", false, "dynamicRegistration", false)),
                        Map.entry("semanticTokens", Map.of("requests", Map.of("full", true), "formats", List.of("relative"),
                                "tokenTypes", List.of("namespace", "class", "enum", "interface", "typeParameter", "type",
                                        "parameter", "variable", "property", "enumMember", "function", "method"),
                                "tokenModifiers", List.of("declaration", "readonly", "static", "async", "defaultLibrary", "local")))),
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
            client.notify("workspace/didChangeConfiguration", Map.of("settings", Map.of("typescript", languageSettings(), "javascript", languageSettings())));
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
    public @NotNull CompletableFuture<List<TsLanguageService.Location>> typeDefinition(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/typeDefinition", params)).thenApply(LspTsBackend::parseLocations);
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
    public @NotNull CompletableFuture<List<TsLanguageService.Completion>> completions(@NotNull Path file, int line, int character,
                                                                                      @Nullable String trigger) {
        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", uri(file)));
        params.put("position", Map.of("line", line, "character", character));
        params.put("context", trigger == null ? Map.of("triggerKind", 1) : Map.of("triggerKind", 2, "triggerCharacter", trigger));
        return loaded().thenCompose(ignored -> client.request("textDocument/completion", params)).thenApply(LspTsBackend::parseCompletions);
    }

    /** LSP CompletionItemKind (1-based) to our kind names. */
    private static final String[] COMPLETION_KINDS = {"other", "other", "method", "function", "method", "property", "variable", "class",
            "interface", "module", "property", "other", "other", "enum", "keyword", "other", "other", "other", "other", "other",
            "enumMember", "constant", "class", "other", "other", "typeParameter"};

    static @NotNull List<TsLanguageService.Completion> parseCompletions(@Nullable JsonElement result) {
        List<TsLanguageService.Completion> completions = new ArrayList<>();
        if (result == null || result.isJsonNull()) return completions;
        JsonArray items = result.isJsonArray() ? result.getAsJsonArray()
                : result.getAsJsonObject().has("items") ? result.getAsJsonObject().getAsJsonArray("items") : new JsonArray();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String label = item.get("label").getAsString();
            int kind = item.has("kind") ? item.get("kind").getAsInt() : 1;
            String insert = item.has("textEdit") && item.get("textEdit").isJsonObject() && item.getAsJsonObject("textEdit").has("newText")
                    ? item.getAsJsonObject("textEdit").get("newText").getAsString()
                    : item.has("insertText") ? item.get("insertText").getAsString() : null;
            // typescript-language-server replaces the dot too: newText ".name" over the range of "."
            if (insert != null && insert.equals("." + label)) insert = label;
            // auto-imports: TS 7 says where from in labelDetails.description, typescript-language-server in detail
            String source = item.has("labelDetails") && item.getAsJsonObject("labelDetails").has("description")
                    ? item.getAsJsonObject("labelDetails").get("description").getAsString()
                    : item.has("data") && item.get("data").isJsonObject() && item.getAsJsonObject("data").has("cacheId")
                    && item.has("detail") && item.get("detail").getAsString().matches("[.@\\w/-]+") ? item.get("detail").getAsString() : null;
            completions.add(new TsLanguageService.Completion(label, kind < COMPLETION_KINDS.length ? COMPLETION_KINDS[kind] : "other",
                    item.has("sortText") ? item.get("sortText").getAsString() : label, insert, source, item));
        }
        return completions;
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Edit>> completionEdits(@NotNull Path file, int line, int character,
                                                                                    @NotNull TsLanguageService.Completion completion) {
        if (completion.data() == null) return CompletableFuture.completedFuture(List.of());
        return client.request("completionItem/resolve", completion.data()).thenApply(result -> {
            List<TsLanguageService.Edit> edits = new ArrayList<>();
            if (result != null && result.isJsonObject() && result.getAsJsonObject().has("additionalTextEdits")) {
                for (JsonElement edit : result.getAsJsonObject().getAsJsonArray("additionalTextEdits")) edits.add(edit(file, edit.getAsJsonObject()));
            }
            return edits;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Diagnostic>> diagnostics(@NotNull Path file) {
        boolean pull = ready.isDone() && !ready.isCompletedExceptionally() && ready.join().isJsonObject()
                && ready.join().getAsJsonObject().getAsJsonObject("capabilities").has("diagnosticProvider");
        if (!pull) {
            JsonArray pushed = pushedDiagnostics.get(uri(file));
            return CompletableFuture.completedFuture(pushed == null ? List.of() : parseDiagnostics(pushed));
        }
        return loaded().thenCompose(ignored -> client.request("textDocument/diagnostic", Map.of("textDocument", Map.of("uri", uri(file)))))
                .thenApply(result -> result != null && result.isJsonObject() && result.getAsJsonObject().has("items")
                        ? parseDiagnostics(result.getAsJsonObject().getAsJsonArray("items")) : List.of());
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Fix>> fixes(@NotNull Path file, @NotNull TsLanguageService.Diagnostic diagnostic) {
        JsonObject lspDiagnostic = new JsonObject();
        lspDiagnostic.add("range", range(diagnostic.range()));
        lspDiagnostic.addProperty("code", diagnostic.code());
        lspDiagnostic.addProperty("message", diagnostic.message());
        lspDiagnostic.addProperty("severity", diagnostic.severity().equals("error") ? 1 : diagnostic.severity().equals("warning") ? 2 : 4);
        lspDiagnostic.addProperty("source", "ts");
        JsonArray diagnostics = new JsonArray();
        diagnostics.add(lspDiagnostic);
        JsonObject context = new JsonObject();
        context.add("diagnostics", diagnostics);
        JsonArray only = new JsonArray();
        only.add("quickfix");
        context.add("only", only);
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "range", range(diagnostic.range()), "context", context);
        return client.request("textDocument/codeAction", params).thenApply(result -> {
            List<TsLanguageService.Fix> fixes = new ArrayList<>();
            if (result == null || !result.isJsonArray()) return fixes;
            for (JsonElement element : result.getAsJsonArray()) {
                JsonObject action = element.getAsJsonObject();
                if (!action.has("title") || !action.has("edit")) continue; // bare commands need the client to run them
                List<TsLanguageService.Edit> edits = workspaceEdit(action.getAsJsonObject("edit"));
                if (!edits.isEmpty()) fixes.add(new TsLanguageService.Fix(action.get("title").getAsString(), edits));
            }
            return fixes;
        });
    }

    @Override
    public @NotNull CompletableFuture<TsLanguageService.SignatureHelp> signatureHelp(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/signatureHelp", params)).thenApply(result -> {
            if (result == null || !result.isJsonObject() || !result.getAsJsonObject().has("signatures")) return TsLanguageService.SignatureHelp.NONE;
            JsonObject help = result.getAsJsonObject();
            List<TsLanguageService.Signature> signatures = new ArrayList<>();
            for (JsonElement element : help.getAsJsonArray("signatures")) {
                JsonObject signature = element.getAsJsonObject();
                String label = signature.get("label").getAsString();
                List<int[]> parameters = new ArrayList<>();
                int searchFrom = label.indexOf('(') + 1;
                if (signature.has("parameters")) {
                    for (JsonElement p : signature.getAsJsonArray("parameters")) {
                        JsonElement parameterLabel = p.getAsJsonObject().get("label");
                        if (parameterLabel.isJsonArray()) {
                            parameters.add(new int[]{parameterLabel.getAsJsonArray().get(0).getAsInt(), parameterLabel.getAsJsonArray().get(1).getAsInt()});
                        } else {
                            int start = label.indexOf(parameterLabel.getAsString(), Math.max(searchFrom, 0));
                            if (start < 0) start = searchFrom;
                            parameters.add(new int[]{start, start + parameterLabel.getAsString().length()});
                            searchFrom = start + parameterLabel.getAsString().length();
                        }
                    }
                }
                signatures.add(new TsLanguageService.Signature(label, parameters, text(signature.get("documentation"))));
            }
            int active = help.has("activeSignature") && !help.get("activeSignature").isJsonNull() ? help.get("activeSignature").getAsInt() : 0;
            int parameter = help.has("activeParameter") && !help.get("activeParameter").isJsonNull() ? help.get("activeParameter").getAsInt() : 0;
            return new TsLanguageService.SignatureHelp(signatures, active, parameter);
        });
    }

    @Override
    public @NotNull CompletableFuture<TsLanguageService.QuickInfo> quickInfo(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/hover", params)).thenApply(result -> {
            if (result == null || !result.isJsonObject() || !result.getAsJsonObject().has("contents")) return TsLanguageService.QuickInfo.NONE;
            JsonElement contents = result.getAsJsonObject().get("contents");
            StringBuilder markdown = new StringBuilder();
            if (contents.isJsonArray()) {
                for (JsonElement part : contents.getAsJsonArray()) markdown.append(part.isJsonObject() && part.getAsJsonObject().has("language")
                        ? "```\n" + part.getAsJsonObject().get("value").getAsString() + "\n```\n" : text(part)).append('\n');
            } else {
                markdown.append(text(contents));
            }
            return TsQuickInfoText.fromMarkdown(markdown.toString());
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Reference>> references(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character),
                "context", Map.of("includeDeclaration", true));
        return loaded().thenCompose(ignored -> client.request("textDocument/references", params)).thenApply(result -> {
            List<TsLanguageService.Reference> references = new ArrayList<>();
            if (result == null || !result.isJsonArray()) return references;
            for (JsonElement element : result.getAsJsonArray()) {
                JsonObject location = element.getAsJsonObject();
                try {
                    references.add(new TsLanguageService.Reference(Path.of(new URI(location.get("uri").getAsString())),
                            range(location.getAsJsonObject("range")), false));
                } catch (Exception e) {
                    LOG.debug("Unsupported location " + location, e);
                }
            }
            return references;
        });
    }

    /** LSP SymbolKind (1-based) to our kind names. */
    private static final String[] SYMBOL_KINDS = {"other", "module", "module", "module", "module", "class", "method", "property", "property",
            "method", "enum", "interface", "function", "variable", "constant", "other", "other", "other", "other", "other", "other", "other",
            "enumMember", "class", "other", "other", "typeParameter"};

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Symbol>> symbols(@NotNull Path file) {
        return loaded().thenCompose(ignored -> client.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", uri(file)))))
                .thenApply(result -> result != null && result.isJsonArray() ? symbols(result.getAsJsonArray()) : List.of());
    }

    /** DocumentSymbol[] (hierarchical) or SymbolInformation[] (flat). */
    private static @NotNull List<TsLanguageService.Symbol> symbols(@NotNull JsonArray items) {
        List<TsLanguageService.Symbol> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            int kind = item.has("kind") ? item.get("kind").getAsInt() : 0;
            TsLanguageService.Range range = range(item.has("range") ? item.getAsJsonObject("range") : item.getAsJsonObject("location").getAsJsonObject("range"));
            TsLanguageService.Range selection = item.has("selectionRange") ? range(item.getAsJsonObject("selectionRange")) : range;
            List<TsLanguageService.Symbol> children = item.has("children") ? symbols(item.getAsJsonArray("children")) : List.of();
            result.add(new TsLanguageService.Symbol(item.get("name").getAsString(), kind < SYMBOL_KINDS.length ? SYMBOL_KINDS[kind] : "other",
                    range, selection, children));
        }
        return result;
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.InlayHint>> inlayHints(@NotNull Path file, @NotNull CharSequence text) {
        int[] lineStarts = lineStarts(text);
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)),
                "range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", lineStarts.length, "character", 0)));
        return loaded().thenCompose(ignored -> client.request("textDocument/inlayHint", params)).thenApply(result -> {
            List<TsLanguageService.InlayHint> hints = new ArrayList<>();
            if (result == null || !result.isJsonArray()) return hints;
            for (JsonElement element : result.getAsJsonArray()) {
                JsonObject hint = element.getAsJsonObject();
                if (hint.has("kind") && hint.get("kind").getAsInt() != 2) continue; // 2: parameter
                JsonObject position = hint.getAsJsonObject("position");
                int line = position.get("line").getAsInt();
                if (line >= lineStarts.length) continue;
                StringBuilder label = new StringBuilder();
                JsonElement value = hint.get("label");
                if (value.isJsonArray()) {
                    for (JsonElement part : value.getAsJsonArray()) label.append(part.getAsJsonObject().get("value").getAsString());
                } else {
                    label.append(value.getAsString());
                }
                hints.add(new TsLanguageService.InlayHint(lineStarts[line] + position.get("character").getAsInt(), label.toString().strip()));
            }
            return hints;
        });
    }

    boolean supportsTsserverRequests() {
        return tsserverRequests;
    }

    @Override
    public @NotNull CompletableFuture<JsonObject> tsserverRequest(@NotNull String command, @NotNull Map<String, Object> arguments) {
        if (!tsserverRequests) return CompletableFuture.failedFuture(new UnsupportedOperationException("not supported by " + description));
        return loaded().thenCompose(ignored -> client.request("workspace/executeCommand",
                Map.of("command", "typescript.tsserverRequest", "arguments", List.of(command, arguments)))).thenApply(result -> {
            if (result == null || !result.isJsonObject()) throw new IllegalStateException("no response to " + command);
            JsonObject response = result.getAsJsonObject();
            if (response.has("success") && !response.get("success").getAsBoolean()) {
                throw new IllegalStateException(response.has("message") ? response.get("message").getAsString() : command + " failed");
            }
            return response;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.Location>> implementation(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/implementation", params)).thenApply(LspTsBackend::parseLocations);
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.CallItem>> prepareCallHierarchy(@NotNull Path file, int line, int character) {
        Map<String, Object> params = Map.of("textDocument", Map.of("uri", uri(file)), "position", Map.of("line", line, "character", character));
        return loaded().thenCompose(ignored -> client.request("textDocument/prepareCallHierarchy", params)).thenApply(result -> {
            List<TsLanguageService.CallItem> items = new ArrayList<>();
            if (result != null && result.isJsonArray()) {
                for (JsonElement element : result.getAsJsonArray()) {
                    TsLanguageService.CallItem item = callItem(element.getAsJsonObject());
                    if (item != null) items.add(item);
                }
            }
            return items;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<TsLanguageService.CallItem>> calls(@NotNull TsLanguageService.CallItem item, boolean incoming) {
        if (item.raw() == null) return CompletableFuture.completedFuture(List.of());
        return client.request(incoming ? "callHierarchy/incomingCalls" : "callHierarchy/outgoingCalls", Map.of("item", item.raw())).thenApply(result -> {
            List<TsLanguageService.CallItem> items = new ArrayList<>();
            if (result == null || !result.isJsonArray()) return items;
            for (JsonElement element : result.getAsJsonArray()) {
                JsonObject call = element.getAsJsonObject();
                TsLanguageService.CallItem other = callItem(call.getAsJsonObject(incoming ? "from" : "to"));
                if (other != null) items.add(other);
            }
            return items;
        });
    }

    private static @Nullable TsLanguageService.CallItem callItem(@NotNull JsonObject item) {
        try {
            int kind = item.has("kind") ? item.get("kind").getAsInt() : 0;
            return new TsLanguageService.CallItem(item.get("name").getAsString(), kind < SYMBOL_KINDS.length ? SYMBOL_KINDS[kind] : "other",
                    item.has("detail") ? item.get("detail").getAsString() : "", Path.of(new URI(item.get("uri").getAsString())),
                    range(item.getAsJsonObject("range")), range(item.getAsJsonObject("selectionRange")), item);
        } catch (Exception e) {
            LOG.debug("Unsupported call hierarchy item " + item, e);
            return null;
        }
    }

    /** A string or a MarkupContent {kind, value}. */
    private static @NotNull String text(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject() && element.getAsJsonObject().has("value")) return element.getAsJsonObject().get("value").getAsString();
        return "";
    }

    @Override
    public void onDiagnosticsPushed(@NotNull java.util.function.Consumer<Path> listener) {
        diagnosticsListener = listener;
    }

    private static @NotNull List<TsLanguageService.Diagnostic> parseDiagnostics(@NotNull JsonArray items) {
        List<TsLanguageService.Diagnostic> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            int severity = item.has("severity") ? item.get("severity").getAsInt() : 1;
            int code = item.has("code") && item.get("code").isJsonPrimitive() && item.get("code").getAsJsonPrimitive().isNumber()
                    ? item.get("code").getAsInt() : 0;
            result.add(new TsLanguageService.Diagnostic(range(item.getAsJsonObject("range")),
                    severity == 1 ? "error" : severity == 2 ? "warning" : "suggestion", code, item.get("message").getAsString()));
        }
        return result;
    }

    /** WorkspaceEdit: "changes" {uri: TextEdit[]} or "documentChanges" [{textDocument, edits}]. */
    private static @NotNull List<TsLanguageService.Edit> workspaceEdit(@NotNull JsonObject edit) {
        List<TsLanguageService.Edit> result = new ArrayList<>();
        try {
            if (edit.has("changes")) {
                for (Map.Entry<String, JsonElement> entry : edit.getAsJsonObject("changes").entrySet()) {
                    Path file = Path.of(new URI(entry.getKey()));
                    for (JsonElement e : entry.getValue().getAsJsonArray()) result.add(edit(file, e.getAsJsonObject()));
                }
            }
            if (edit.has("documentChanges")) {
                for (JsonElement change : edit.getAsJsonArray("documentChanges")) {
                    JsonObject document = change.getAsJsonObject();
                    if (!document.has("textDocument") || !document.has("edits")) continue; // create/rename/delete file
                    Path file = Path.of(new URI(document.getAsJsonObject("textDocument").get("uri").getAsString()));
                    for (JsonElement e : document.getAsJsonArray("edits")) result.add(edit(file, e.getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            LOG.debug("Unsupported workspace edit " + edit, e);
        }
        return result;
    }

    private static @NotNull TsLanguageService.Edit edit(@NotNull Path file, @NotNull JsonObject textEdit) {
        return new TsLanguageService.Edit(file, range(textEdit.getAsJsonObject("range")), textEdit.get("newText").getAsString());
    }

    private static @NotNull TsLanguageService.Range range(@NotNull JsonObject range) {
        JsonObject start = range.getAsJsonObject("start");
        JsonObject end = range.getAsJsonObject("end");
        return new TsLanguageService.Range(start.get("line").getAsInt(), start.get("character").getAsInt(),
                end.get("line").getAsInt(), end.get("character").getAsInt());
    }

    private static @NotNull JsonObject range(@NotNull TsLanguageService.Range range) {
        JsonObject start = new JsonObject();
        start.addProperty("line", range.line());
        start.addProperty("character", range.character());
        JsonObject end = new JsonObject();
        end.addProperty("line", range.endLine());
        end.addProperty("character", range.endCharacter());
        JsonObject result = new JsonObject();
        result.add("start", start);
        result.add("end", end);
        return result;
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
