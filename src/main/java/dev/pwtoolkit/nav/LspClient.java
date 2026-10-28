package dev.pwtoolkit.nav;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal JSON-RPC client for a language server talking LSP over stdio
 * (the native TypeScript 7 compiler: {@code tsc --lsp --stdio}).
 */
final class LspClient {
    private static final Logger LOG = Logger.getInstance(LspClient.class);
    /** serializeNulls: a response must carry "result": null, servers reject it otherwise. */
    private static final Gson GSON = new com.google.gson.GsonBuilder().serializeNulls().create();

    private final Process process;
    private final OutputStream out;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final String name;
    /** Last lines of the server's stderr, for diagnostics (crashes, missing libraries...). */
    private final StringBuilder stderrTail = new StringBuilder();
    /** Server notifications (e.g. $/progress), method -> params. */
    private volatile java.util.function.BiConsumer<String, JsonElement> notificationListener = (method, params) -> {
    };

    private LspClient(@NotNull Process process, @NotNull String name) {
        this.process = process;
        this.out = process.getOutputStream();
        this.name = name;
        startThread("reader", this::readLoop);
        startThread("stderr", this::drainStderr);
    }

    static @NotNull LspClient start(@NotNull List<String> command, @NotNull File workDir) throws IOException {
        Process process = new ProcessBuilder(command).directory(workDir).start();
        return new LspClient(process, workDir.getName());
    }

    void onNotification(@NotNull java.util.function.BiConsumer<String, JsonElement> listener) {
        this.notificationListener = listener;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    @NotNull String state() {
        if (process.isAlive()) return "running";
        try {
            return "exited with code " + process.exitValue();
        } catch (IllegalThreadStateException e) {
            return "exiting";
        }
    }

    @NotNull String stderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString();
        }
    }

    @NotNull CompletableFuture<JsonElement> request(@NotNull String method, @Nullable Object params) {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) message.add("params", GSON.toJsonTree(params));
        if (!write(message)) {
            pending.remove(id);
            future.completeExceptionally(new IOException("Language server is not running"));
        }
        return future;
    }

    void notify(@NotNull String method, @Nullable Object params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        if (params != null) message.add("params", GSON.toJsonTree(params));
        write(message);
    }

    void shutdown() {
        if (process.isAlive()) {
            try {
                request("shutdown", null).get(1, java.util.concurrent.TimeUnit.SECONDS);
                notify("exit", null);
            } catch (Exception ignored) {
            }
        }
        process.destroy();
        failPending(new IOException("Language server stopped"));
    }

    private synchronized boolean write(@NotNull JsonObject message) {
        if (!process.isAlive()) return false;
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
        try (InputStream in = new BufferedInputStream(process.getInputStream())) {
            while (true) {
                int length = readContentLength(in);
                if (length < 0) break;
                byte[] body = in.readNBytes(length);
                if (body.length < length) break;
                dispatch(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (Exception e) {
            LOG.debug("[" + name + "] reader stopped", e);
        }
        if (!process.isAlive()) {
            LOG.info("[pwtoolkit] TypeScript server for " + name + " exited: " + state() + " " + stderrTail().strip());
        }
        failPending(new IOException("Language server exited: " + state()));
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
        JsonElement id = message.get("id");
        boolean hasMethod = message.has("method");
        if (id != null && !hasMethod) {
            CompletableFuture<JsonElement> future = pending.remove(id.getAsInt());
            if (future == null) return;
            if (message.has("error")) {
                future.completeExceptionally(new IOException(message.get("error").toString()));
            } else {
                JsonElement result = message.get("result");
                future.complete(result == null ? JsonNull.INSTANCE : result);
            }
        } else if (id != null) {
            answerServerRequest(id, message.get("method").getAsString(), message.get("params"));
        } else if (hasMethod) {
            notificationListener.accept(message.get("method").getAsString(), message.get("params"));
        }
    }

    /** The server asks the client for things like configuration; we have nothing special to say. */
    private void answerServerRequest(@NotNull JsonElement id, @NotNull String method, @Nullable JsonElement params) {
        JsonElement result = JsonNull.INSTANCE;
        if ("workspace/configuration".equals(method) && params != null && params.isJsonObject()) {
            JsonArray items = params.getAsJsonObject().getAsJsonArray("items");
            JsonArray answer = new JsonArray();
            for (int i = 0; items != null && i < items.size(); i++) answer.add(JsonNull.INSTANCE);
            result = answer;
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        write(response);
    }

    private void drainStderr() {
        try (InputStream err = process.getErrorStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = err.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                if (LOG.isDebugEnabled()) LOG.debug("[" + name + "] " + chunk);
                synchronized (stderrTail) {
                    stderrTail.append(chunk);
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
        Thread thread = new Thread(body, "Playwright Toolkit LSP " + kind + " (" + name + ")");
        thread.setDaemon(true);
        thread.start();
    }
}
