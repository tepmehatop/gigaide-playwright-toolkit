package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one Playwright browser per project folder open between test runs, like "Show browser" in the VS Code
 * extension: a background `playwright run-server` (in extension mode it keeps the launched browser alive), and the
 * tests connect to it with PW_TEST_CONNECT_WS_ENDPOINT + PW_TEST_REUSE_CONTEXT=1.
 */
@Service(Service.Level.PROJECT)
public final class PwBrowserServer implements Disposable {
    private static final Pattern LISTENING = Pattern.compile("Listening on (ws://\\S+)");
    private final Map<String, Server> servers = new ConcurrentHashMap<>();
    private final Map<String, PwDebugController> controllers = new ConcurrentHashMap<>();

    private record Server(@NotNull KillableProcessHandler process, @NotNull String wsEndpoint) {
    }

    public static @NotNull PwBrowserServer getInstance(@NotNull Project project) {
        return project.getService(PwBrowserServer.class);
    }

    /** Endpoint of the running server for the folder, starting it if needed (with that project's Playwright). */
    public synchronized @NotNull String ensureStarted(@NotNull File workDir, @NotNull String node, @NotNull String cli) throws ExecutionException {
        Server running = servers.get(workDir.getPath());
        if (running != null && !running.process().isProcessTerminated()) return running.wsEndpoint();

        GeneralCommandLine command = new GeneralCommandLine(node, cli, "run-server", "--path=/" + UUID.randomUUID(), "--host", "127.0.0.1")
                .withWorkDirectory(workDir)
                .withEnvironment("PW_EXTENSION_MODE", "1")
                .withEnvironment("PW_CODEGEN_NO_INSPECTOR", "1")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        KillableProcessHandler process = new KillableProcessHandler(command);
        // SIGINT first: run-server closes its browser instead of leaving it orphaned
        process.setShouldKillProcessSoftly(true);
        CountDownLatch started = new CountDownLatch(1);
        String[] endpoint = new String[1];
        StringBuilder output = new StringBuilder();
        process.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                output.append(event.getText());
                Matcher m = LISTENING.matcher(event.getText());
                if (m.find()) {
                    endpoint[0] = m.group(1);
                    started.countDown();
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                started.countDown();
            }
        });
        process.startNotify();
        try {
            started.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (endpoint[0] == null) {
            process.destroyProcess();
            throw new ExecutionException("Cannot start the Playwright browser server: " + output.toString().trim());
        }
        servers.put(workDir.getPath(), new Server(process, endpoint[0]));
        return endpoint[0];
    }

    public @Nullable String endpoint(@NotNull File workDir) {
        Server server = servers.get(workDir.getPath());
        return server == null || server.process().isProcessTerminated() ? null : server.wsEndpoint();
    }

    public boolean isRunning() {
        return servers.values().stream().anyMatch(s -> !s.process().isProcessTerminated());
    }

    /**
     * The debug controller of a running server (connected on first use), for highlighting locators in its browser.
     * Null when no browser is kept open.
     */
    public synchronized @Nullable PwDebugController controller() {
        for (Map.Entry<String, Server> entry : servers.entrySet()) {
            if (entry.getValue().process().isProcessTerminated()) continue;
            PwDebugController controller = controllers.get(entry.getKey());
            if (controller == null || controller.isClosed()) {
                try {
                    controller = PwDebugController.connect(entry.getValue().wsEndpoint());
                    controllers.put(entry.getKey(), controller);
                } catch (Exception e) {
                    continue;
                }
            }
            return controller;
        }
        return null;
    }

    /** Closes the kept browsers (the next run starts a new one). */
    public synchronized void stopAll() {
        controllers.values().forEach(PwDebugController::close);
        controllers.clear();
        servers.values().forEach(s -> s.process().destroyProcess());
        servers.clear();
    }

    @Override
    public void dispose() {
        stopAll();
    }
}
