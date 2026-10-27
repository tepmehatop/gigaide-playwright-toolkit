package dev.pwtoolkit.tests;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.ui.UIUtil;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens test reports from the IDE: playwright-extended-report (served by its own `show` command, straight on the
 * test page via #?testId=) or, if it is not used, the standard Playwright HTML report (`show-report`).
 * Report servers are started once per report folder and stopped with the project.
 */
@Service(Service.Level.PROJECT)
public final class PwReports implements Disposable {
    private static final Pattern SERVING = Pattern.compile("Serving (?:extended|HTML) report at (\\S+?)\\.?\\s");
    private final Project project;
    private final Map<String, Server> servers = new ConcurrentHashMap<>();

    private record Server(@NotNull OSProcessHandler process, @Nullable String url) {
    }

    public PwReports(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PwReports getInstance(@NotNull Project project) {
        return project.getService(PwReports.class);
    }

    /** Opens the report of the project in workDir, on the given test when the report supports it. Runs off the EDT. */
    public void open(@NotNull File workDir, @Nullable String testId) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                doOpen(workDir, testId);
            } catch (Exception e) {
                notify("Cannot open the report: " + e.getMessage(), NotificationType.ERROR);
            }
        });
    }

    private void doOpen(@NotNull File workDir, @Nullable String testId) throws Exception {
        String node = NodeLocator.findNode(null);
        if (node == null) throw new IllegalStateException("Node.js was not found");
        File extended = new File(workDir, "extended-report");
        String extendedCli = findExtendedReportCli(workDir.toPath());
        if (new File(extended, "index.html").isFile() && extendedCli != null) {
            String url = serve(extended, new GeneralCommandLine(node, extendedCli, "show", extended.getPath(), "--no-open"), workDir);
            if (url == null) throw new IllegalStateException("pw-extended-report did not start");
            BrowserUtil.browse(testId == null ? url : url + "#?testId=" + testId);
            return;
        }
        File html = new File(workDir, "playwright-report");
        String cli = PwLaunchSpec.findCli(workDir.toPath());
        if (new File(html, "index.html").isFile() && cli != null) {
            // show-report always opens the report start page itself
            String url = serve(html, new GeneralCommandLine(node, cli, "show-report", html.getPath()), workDir);
            if (url != null && testId != null) {
                notify("The standard HTML report is open at its start page (" + url + "). Test pages open directly with playwright-extended-report.", NotificationType.INFORMATION);
            }
            return;
        }
        notify("No report in " + workDir + ": add the 'html' or playwright-extended-report reporter to playwright.config and run the tests.",
                NotificationType.WARNING);
    }

    /** Starts the report server for the folder (or reuses the running one) and returns its URL. */
    private @Nullable String serve(@NotNull File reportDir, @NotNull GeneralCommandLine command, @NotNull File workDir) throws Exception {
        Server running = servers.get(reportDir.getPath());
        if (running != null && !running.process().isProcessTerminated() && running.url() != null) {
            if (command.getParametersList().getParameters().contains("show-report")) BrowserUtil.browse(running.url());
            return running.url();
        }
        CountDownLatch started = new CountDownLatch(1);
        String[] url = new String[1];
        OSProcessHandler process = new OSProcessHandler(command.withWorkDirectory(workDir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE));
        process.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                Matcher m = SERVING.matcher(event.getText().replaceAll("\u001b\\[[0-9;]*m", "") + " ");
                if (m.find()) {
                    url[0] = m.group(1);
                    started.countDown();
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                started.countDown();
            }
        });
        process.startNotify();
        started.await(30, TimeUnit.SECONDS);
        servers.put(reportDir.getPath(), new Server(process, url[0]));
        return url[0];
    }

    /** node_modules/.bin/pw-extended-report (any package name, e.g. @qa/playwright-extended-report) → its JS file. */
    static @Nullable String findExtendedReportCli(@NotNull Path workDir) {
        for (Path dir = workDir; dir != null; dir = dir.getParent()) {
            Path bin = dir.resolve("node_modules/.bin/pw-extended-report");
            if (Files.exists(bin)) {
                try {
                    return bin.toRealPath().toString();
                } catch (Exception e) {
                    return bin.toString();
                }
            }
        }
        return null;
    }

    private void notify(@NotNull String message, @NotNull NotificationType type) {
        UIUtil.invokeLaterIfNeeded(() -> NotificationGroupManager.getInstance().getNotificationGroup("Playwright Toolkit")
                .createNotification("Playwright report", message, type).notify(project));
    }

    @Override
    public void dispose() {
        servers.values().forEach(s -> s.process().destroyProcess());
        servers.clear();
    }
}
