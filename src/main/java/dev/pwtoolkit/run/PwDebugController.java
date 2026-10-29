package dev.pwtoolkit.run;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client of Playwright's debug controller ({@code run-server} endpoint + {@code ?debug-controller}), the same channel
 * the VS Code extension uses to highlight locators in the browser kept open between runs.
 */
public final class PwDebugController implements WebSocket.Listener {
    private static final Logger LOG = Logger.getInstance(PwDebugController.class);
    private static final Gson GSON = new Gson();

    private final Map<Integer, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket socket;
    private volatile int pageCount;
    private volatile boolean closed;

    private PwDebugController() {
    }

    static @NotNull PwDebugController connect(@NotNull String wsEndpoint) throws Exception {
        PwDebugController controller = new PwDebugController();
        controller.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(wsEndpoint + "?debug-controller"), controller)
                .get(10, TimeUnit.SECONDS);
        controller.send("initialize", Map.of("codegenId", "playwright-test", "sdkLanguage", "javascript")).get(10, TimeUnit.SECONDS);
        // makes the server report the number of open pages (stateChanged events)
        controller.send("setReportStateChanged", Map.of("enabled", true)).get(10, TimeUnit.SECONDS);
        return controller;
    }

    /** The element the user clicks in the browser while picking (inspectRequested: selector, locator, ariaSnapshot). */
    private volatile java.util.function.Consumer<JsonObject> inspectListener;

    /** A picked element: the locator code for the language (getByRole('button', { name: 'Sign in' })) and its selector. */
    public record Picked(@NotNull String locator, @NotNull String selector) {
    }

    /**
     * Pick Locator: the browser highlights the element under the mouse, a click picks it. Completes with the locator of
     * the clicked element; the picking mode is switched off afterwards (also when the future is cancelled).
     */
    public @NotNull CompletableFuture<Picked> pickLocator(@Nullable String testIdAttribute) {
        CompletableFuture<Picked> picked = new CompletableFuture<>();
        inspectListener = params -> {
            if (params.has("locator")) {
                picked.complete(new Picked(params.get("locator").getAsString(),
                        params.has("selector") ? params.get("selector").getAsString() : params.get("locator").getAsString()));
            } else if (params.has("mode") && "none".equals(params.get("mode").getAsString())) {
                picked.cancel(false); // switched off in the browser's own toolbar
            }
        };
        Map<String, Object> mode = new java.util.HashMap<>();
        mode.put("mode", "inspecting");
        if (testIdAttribute != null) mode.put("testIdAttributeName", testIdAttribute);
        send("setRecorderMode", mode).whenComplete((result, error) -> {
            if (error != null) picked.completeExceptionally(error);
        });
        picked.whenComplete((result, error) -> {
            inspectListener = null;
            if (!closed) send("setRecorderMode", Map.of("mode", "none"));
        });
        return picked;
    }

    /** For tests: what the server sends when an element is clicked while picking. */
    void onEvent(@NotNull String method, @NotNull JsonObject params) {
        java.util.function.Consumer<JsonObject> listener = inspectListener;
        if (listener != null && ("inspectRequested".equals(method) || "setModeRequested".equals(method))) listener.accept(params);
    }

    public int pageCount() {
        return pageCount;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Highlights the elements of a locator expression ({@code locator('h1')}, {@code getByRole('button')}) or a selector. */
    public @NotNull CompletableFuture<JsonElement> highlight(@NotNull String locator) {
        return send("highlight", Map.of("selector", locator));
    }

    public @NotNull CompletableFuture<JsonElement> hideHighlight() {
        return send("hideHighlight", Map.of());
    }

    synchronized @NotNull CompletableFuture<JsonElement> send(@NotNull String method, @NotNull Object params) {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonElement> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IOException("debug controller is closed"));
            return result;
        }
        pending.put(id, result);
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("guid", "DebugController");
        message.addProperty("method", method);
        message.add("params", GSON.toJsonTree(params));
        JsonObject metadata = new JsonObject();
        metadata.addProperty("timeout", 0);
        message.add("metadata", metadata);
        // the JDK WebSocket allows one outstanding send at a time
        socket.sendText(GSON.toJson(message), true).join();
        return result;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                dispatch(JsonParser.parseString(text).getAsJsonObject());
            } catch (Exception e) {
                LOG.debug("Bad debug controller message " + text, e);
            }
        }
        webSocket.request(1);
        return null;
    }

    private void dispatch(@NotNull JsonObject message) {
        if (!message.has("id")) {
            String method = message.has("method") ? message.get("method").getAsString() : null;
            JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
            if ("stateChanged".equals(method) && params.has("pageCount")) pageCount = params.get("pageCount").getAsInt();
            if (method != null) onEvent(method, params);
            return;
        }
        CompletableFuture<JsonElement> future = pending.remove(message.get("id").getAsInt());
        if (future == null) return;
        if (message.has("error")) future.completeExceptionally(new IOException(message.get("error").toString()));
        else future.complete(message.has("result") ? message.get("result") : JsonNull.INSTANCE);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        close();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        close();
    }

    void close() {
        if (closed) return;
        closed = true;
        pending.values().forEach(f -> f.completeExceptionally(new IOException("debug controller closed")));
        pending.clear();
        WebSocket ws = socket;
        if (ws != null) ws.abort();
    }

    /** {@code h1} → {@code locator('h1')}, quoted as JavaScript (the form the debug controller parses). */
    public static @NotNull String locatorFor(@NotNull String selector) {
        return "locator('" + selector.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "')";
    }
}
