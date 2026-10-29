package dev.pwtoolkit.run;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The browser of Pick Locator (like IDEA's Web Inspector): a Chromium of the project's Playwright with a profile kept in
 * the IDE system folder, so that logins survive between picks. A click on an element gives locator candidates ranked
 * like an autotest engineer would: unique on the page first, then test attributes (data-qa..., data-testid), id,
 * name / short class, role and name, XPath by text, scoped and positional ones last.
 */
@Service(Service.Level.PROJECT)
public final class PwLocatorPicker implements Disposable {
    private static final Logger LOG = Logger.getInstance(PwLocatorPicker.class);
    private static final Gson GSON = new Gson();
    static final String SCRIPT = "pwtk-picker.cjs";

    /** A locator candidate: code for TS ({@code getByRole(...)}, {@code locator('...')}), the selector for strings, matches on the page. */
    public record Candidate(int rank, @NotNull String kind, @NotNull String selector, @NotNull String code, int count) {
    }

    private KillableProcessHandler process;
    private OutputStream input;
    private File workDir;
    /** Guards {@code pending}: output events arrive on the process reader thread. */
    private final Object lock = new Object();
    private CompletableFuture<List<Candidate>> pending;
    private volatile CompletableFuture<Void> ready;
    private final StringBuilder partial = new StringBuilder();
    private volatile String lastUrl;
    private volatile String lastError;

    public static @NotNull PwLocatorPicker getInstance(@NotNull Project project) {
        return project.getService(PwLocatorPicker.class);
    }

    public synchronized boolean isRunning() {
        return process != null && !process.isProcessTerminated();
    }

    public @Nullable String lastUrl() {
        return lastUrl;
    }

    /** Starts the picker browser of this Playwright project if needed; true when it was started now. */
    public boolean ensureStarted(@NotNull File workDir, @NotNull String node, boolean headless) throws ExecutionException {
        CompletableFuture<Void> starting;
        synchronized (this) {
            if (isRunning() && workDir.equals(this.workDir)) return false;
            starting = start(workDir, node, headless);
        }
        try {
            // outside of the lock: the "ready" event is handled on the output reader thread
            starting.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            stop();
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new ExecutionException("Cannot start the picker browser: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), e);
        }
        return true;
    }

    private @NotNull CompletableFuture<Void> start(@NotNull File workDir, @NotNull String node, boolean headless) throws ExecutionException {
        stop();
        File profile = PathManager.getSystemDir().resolve("playwright-toolkit").resolve("picker-profile").toFile();
        //noinspection ResultOfMethodCallIgnored
        profile.mkdirs();
        GeneralCommandLine command = new GeneralCommandLine(node, PwResources.install(SCRIPT).toString(), workDir.getPath(), profile.getPath(),
                String.join(",", attributes(workDir)), headless ? "1" : "0")
                .withWorkDirectory(workDir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        ready = new CompletableFuture<>();
        process = new KillableProcessHandler(command);
        process.setShouldKillProcessSoftly(true);
        process.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (outputType == ProcessOutputTypes.STDOUT) lines(event.getText());
                else if (outputType == ProcessOutputTypes.STDERR) lastError = event.getText().strip();
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                fail(new IOException("The picker browser was closed" + (lastError == null ? "" : ": " + lastError)));
                CompletableFuture<Void> starting = ready;
                if (starting != null) starting.completeExceptionally(new IOException("The picker browser exited: " + lastError));
            }
        });
        process.startNotify();
        input = process.getProcessInput();
        this.workDir = workDir;
        return ready;
    }

    public void navigate(@NotNull String url) {
        lastUrl = url;
        send(Map.of("cmd", "navigate", "url", url));
    }

    /** Highlighting under the mouse in the picker browser; completes with the candidates of the clicked element (empty: cancelled). */
    public @NotNull CompletableFuture<List<Candidate>> pick() {
        CompletableFuture<List<Candidate>> result = new CompletableFuture<>();
        synchronized (lock) {
            if (pending != null) pending.cancel(false);
            pending = result;
        }
        send(Map.of("cmd", "pick"));
        return result;
    }

    public void cancel() {
        send(Map.of("cmd", "cancel"));
    }

    /** For tests: the candidates of the first element matching a selector, as if it was clicked. */
    @NotNull CompletableFuture<List<Candidate>> candidatesFor(@NotNull String selector) {
        CompletableFuture<List<Candidate>> result = new CompletableFuture<>();
        synchronized (lock) {
            pending = result;
        }
        send(Map.of("cmd", "candidatesFor", "selector", selector));
        return result;
    }

    /** For tests: a real mouse click on the first element matching a selector. */
    void testClick(@NotNull String selector) {
        send(Map.of("cmd", "testClick", "selector", selector));
    }

    /** Test attributes from Settings (best first), with testIdAttribute of playwright.config before data-testid. */
    static @NotNull List<String> attributes(@NotNull File workDir) {
        Set<String> attributes = new LinkedHashSet<>();
        for (String a : PwSettings.getInstance().getState().locatorAttributes.split(",")) {
            if (!a.isBlank()) attributes.add(a.trim());
        }
        String configured = PwPickLocatorSupport.testIdAttribute(workDir);
        if (configured != null && !attributes.contains(configured)) {
            List<String> list = new ArrayList<>(attributes);
            int testId = list.indexOf("data-testid");
            list.add(testId < 0 ? list.size() : testId, configured);
            return list;
        }
        return new ArrayList<>(attributes);
    }

    private void send(@NotNull Map<String, ?> command) {
        OutputStream input = this.input;
        if (input == null) return;
        try {
            synchronized (input) {
                input.write((GSON.toJson(command) + "\n").getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    private void lines(@NotNull String text) {
        List<String> lines = new ArrayList<>();
        synchronized (partial) {
            partial.append(text);
            int newline;
            while ((newline = partial.indexOf("\n")) >= 0) {
                lines.add(partial.substring(0, newline));
                partial.delete(0, newline + 1);
            }
        }
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                handle(JsonParser.parseString(line).getAsJsonObject());
            } catch (Exception e) {
                LOG.debug("Picker output: " + line, e);
            }
        }
    }

    private void handle(@NotNull JsonObject message) {
        String event = message.get("event").getAsString();
        switch (event) {
            case "ready" -> {
                CompletableFuture<Void> starting = ready;
                if (starting != null) starting.complete(null);
            }
            case "navigated" -> lastUrl = message.get("url").getAsString();
            case "picked" -> {
                if (message.has("url")) lastUrl = message.get("url").getAsString();
                List<Candidate> candidates = new ArrayList<>();
                for (JsonElement element : message.getAsJsonArray("candidates")) {
                    JsonObject c = element.getAsJsonObject();
                    candidates.add(new Candidate(c.get("rank").getAsInt(), c.get("kind").getAsString(), c.get("selector").getAsString(),
                            c.get("code").getAsString(), c.get("count").getAsInt()));
                }
                CompletableFuture<List<Candidate>> waiting = takePending();
                if (waiting != null) waiting.complete(candidates);
            }
            case "cancelled" -> {
                CompletableFuture<List<Candidate>> waiting = takePending();
                if (waiting != null) waiting.complete(List.of());
            }
            case "error" -> {
                lastError = message.get("message").getAsString();
                CompletableFuture<List<Candidate>> waiting = takePending();
                if (waiting != null) waiting.completeExceptionally(new IOException(lastError));
                LOG.info("[pwtoolkit] locator picker: " + lastError);
            }
            default -> {
            }
        }
    }

    private @Nullable CompletableFuture<List<Candidate>> takePending() {
        synchronized (lock) {
            CompletableFuture<List<Candidate>> waiting = pending;
            pending = null;
            return waiting;
        }
    }

    private void fail(@NotNull Throwable error) {
        CompletableFuture<List<Candidate>> waiting = takePending();
        if (waiting != null) waiting.completeExceptionally(error);
    }

    public synchronized void stop() {
        if (process != null && !process.isProcessTerminated()) {
            send(Map.of("cmd", "exit"));
            process.destroyProcess();
        }
        process = null;
        input = null;
    }

    @Override
    public void dispose() {
        stop();
    }
}
