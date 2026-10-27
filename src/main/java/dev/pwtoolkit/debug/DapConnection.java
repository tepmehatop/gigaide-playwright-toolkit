package dev.pwtoolkit.debug;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Debug Adapter Protocol connection to the js-debug server (one per debug session/target).
 * Events and reverse requests are delivered sequentially on a dedicated executor, so a listener may
 * block on its own requests without stalling the socket reader.
 */
final class DapConnection {
    private static final Logger LOG = Logger.getInstance(DapConnection.class);
    private static final Gson GSON = new Gson();

    interface Listener {
        void onEvent(@NotNull DapConnection connection, @NotNull String event, @NotNull JsonObject body);

        /** Returns the response body, or throws to report failure. */
        @Nullable JsonObject onReverseRequest(@NotNull DapConnection connection, @NotNull String command, @NotNull JsonObject arguments) throws Exception;

        void onClosed(@NotNull DapConnection connection);
    }

    static final class DapException extends Exception {
        DapException(String message) {
            super(message);
        }
    }

    private final Socket socket;
    private final OutputStream out;
    private final Listener listener;
    private final String name;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher;
    private volatile boolean closed;

    private DapConnection(@NotNull Socket socket, @NotNull Listener listener, @NotNull String name) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.listener = listener;
        this.name = name;
        this.dispatcher = AppExecutorUtil.createBoundedApplicationPoolExecutor("Playwright DAP " + name, 1);
        Thread reader = new Thread(this::readLoop, "Playwright DAP reader (" + name + ")");
        reader.setDaemon(true);
        reader.start();
    }

    static @NotNull DapConnection connect(int port, @NotNull Listener listener, @NotNull String name) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setTcpNoDelay(true);
        return new DapConnection(socket, listener, name);
    }

    @NotNull String getName() {
        return name;
    }

    boolean isClosed() {
        return closed;
    }

    @NotNull CompletableFuture<JsonObject> request(@NotNull String command, @Nullable Object arguments) {
        int id = seq.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject message = new JsonObject();
        message.addProperty("seq", id);
        message.addProperty("type", "request");
        message.addProperty("command", command);
        if (arguments != null) message.add("arguments", GSON.toJsonTree(arguments));
        if (!write(message)) {
            pending.remove(id);
            future.completeExceptionally(new DapException("Debug connection is closed"));
        }
        return future;
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        pending.values().forEach(f -> f.completeExceptionally(new DapException("Debug connection is closed")));
        pending.clear();
    }

    private synchronized boolean write(@NotNull JsonObject message) {
        if (closed) return false;
        message.addProperty("seq", message.has("seq") ? message.get("seq").getAsInt() : seq.incrementAndGet());
        try {
            byte[] body = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            return true;
        } catch (IOException e) {
            LOG.debug("[" + name + "] write failed", e);
            return false;
        }
    }

    private void readLoop() {
        try (InputStream in = new BufferedInputStream(socket.getInputStream())) {
            while (true) {
                int length = readContentLength(in);
                if (length < 0) break;
                byte[] body = in.readNBytes(length);
                if (body.length < length) break;
                dispatch(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (Exception e) {
            if (!closed) LOG.debug("[" + name + "] reader stopped", e);
        }
        dispatcher.execute(() -> {
            close();
            listener.onClosed(this);
        });
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

    private void dispatch(@NotNull JsonObject message) {
        String type = message.get("type").getAsString();
        switch (type) {
            case "response" -> {
                CompletableFuture<JsonObject> future = pending.remove(message.get("request_seq").getAsInt());
                if (future == null) return;
                if (message.get("success").getAsBoolean()) {
                    JsonElement body = message.get("body");
                    future.complete(body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject());
                } else {
                    String error = message.has("message") ? message.get("message").getAsString() : "request failed";
                    JsonElement body = message.get("body");
                    if (body != null && body.isJsonObject() && body.getAsJsonObject().has("error")) {
                        JsonObject details = body.getAsJsonObject().getAsJsonObject("error");
                        if (details.has("format")) error = details.get("format").getAsString();
                    }
                    future.completeExceptionally(new DapException(error));
                }
            }
            case "event" -> {
                String event = message.get("event").getAsString();
                JsonObject body = message.has("body") && message.get("body").isJsonObject() ? message.getAsJsonObject("body") : new JsonObject();
                dispatcher.execute(() -> safely(() -> listener.onEvent(this, event, body)));
            }
            case "request" -> dispatcher.execute(() -> answerReverseRequest(message));
            default -> LOG.debug("[" + name + "] unknown message " + message);
        }
    }

    private void answerReverseRequest(@NotNull JsonObject request) {
        String command = request.get("command").getAsString();
        JsonObject arguments = request.has("arguments") ? request.getAsJsonObject("arguments") : new JsonObject();
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("request_seq", request.get("seq").getAsInt());
        response.addProperty("command", command);
        try {
            JsonObject body = listener.onReverseRequest(this, command, arguments);
            response.addProperty("success", true);
            if (body != null) response.add("body", body);
        } catch (Exception e) {
            response.addProperty("success", false);
            response.addProperty("message", String.valueOf(e.getMessage()));
        }
        write(response);
    }

    private void safely(@NotNull Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            LOG.warn("[" + name + "] DAP event handling failed", t);
        }
    }
}
