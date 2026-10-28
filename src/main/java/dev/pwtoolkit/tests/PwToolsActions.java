package dev.pwtoolkit.tests;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import dev.pwtoolkit.debug.JsDebugLocator;
import dev.pwtoolkit.nav.TsServerLocator;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.run.PwTestDetector;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tools | Playwright: report, codegen, browsers, environment check. */
public final class PwToolsActions {
    private PwToolsActions() {
    }

    /**
     * The Playwright project to act on: the folder with playwright.config of the file in the editor or
     * selected in the Project view, otherwise the first one in the project root or its direct subfolders.
     */
    static @Nullable File workDir(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file != null) {
            VirtualFile dir = PwTestDetector.findConfigDir(file);
            if (dir != null) return new File(dir.getPath());
        }
        Project project = e.getProject();
        String base = project == null ? null : project.getBasePath();
        if (base == null) return null;
        File root = new File(base);
        if (hasConfig(root)) return root;
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (hasConfig(child)) return child;
            }
        }
        return null;
    }

    private static boolean hasConfig(@NotNull File dir) {
        String[] names = dir.list((d, name) -> name.startsWith("playwright.config."));
        return names != null && names.length > 0;
    }

    abstract static class Base extends AnAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(e.getProject() != null && workDir(e) != null);
        }

        @Nullable Command command(@NotNull AnActionEvent e) {
            File dir = workDir(e);
            String node = NodeLocator.findNode(null);
            String cli = dir == null ? null : PwLaunchSpec.findCli(dir.toPath());
            if (dir == null || node == null || cli == null) {
                Messages.showErrorDialog(e.getProject(), "Node.js or @playwright/test (npm install) was not found.", "Playwright");
                return null;
            }
            return new Command(dir, node, cli);
        }
    }

    record Command(@NotNull File workDir, @NotNull String node, @NotNull String cli) {
        @NotNull GeneralCommandLine line(@NotNull String... args) {
            return new GeneralCommandLine(node, cli).withParameters(args).withWorkDirectory(workDir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        }
    }

    public static final class ShowReport extends Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            File dir = workDir(e);
            if (dir != null && e.getProject() != null) PwReports.getInstance(e.getProject()).open(dir, null);
        }
    }

    /** `playwright codegen <url>`: records actions in a browser and generates test code in the Inspector window. */
    public static final class Codegen extends Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Command command = command(e);
            if (command == null) return;
            String url = Messages.showInputDialog(e.getProject(), "Page to open (optional):", "Record New Test (Codegen)", null);
            if (url == null) return;
            try {
                GeneralCommandLine line = url.isBlank() ? command.line("codegen") : command.line("codegen", url.trim());
                new OSProcessHandler(line).startNotify();
            } catch (ExecutionException ex) {
                Messages.showErrorDialog(e.getProject(), ex.getMessage(), "Codegen");
            }
        }
    }

    /** `playwright install` (browsers of the project's Playwright version) with output in the Run window. */
    public static final class InstallBrowsers extends Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Command command = command(e);
            if (command == null || e.getProject() == null) return;
            try {
                KillableColoredProcessHandler handler = new KillableColoredProcessHandler(command.line("install"));
                new RunContentExecutor(e.getProject(), handler).withTitle("Install Playwright Browsers").withActivateToolWindow(true).run();
            } catch (ExecutionException ex) {
                Messages.showErrorDialog(e.getProject(), ex.getMessage(), "Install Browsers");
            }
        }
    }

    /** Node, Playwright, browsers, TypeScript 7 and js-debug: what the plugin will use, with problems highlighted. */
    public static final class CheckEnvironment extends Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            File dir = workDir(e);
            Project project = e.getProject();
            if (dir == null || project == null) return;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String report = String.join("\n", check(dir));
                ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(project, report, "Playwright Environment"));
            });
        }
    }

    private static final Pattern INSTALL_LOCATION = Pattern.compile("^(.+?)\\n\\s+Install location:\\s+(\\S.*)$", Pattern.MULTILINE);

    /** One line per check, "✓"/"✗" first. */
    public static @NotNull List<String> check(@NotNull File workDir) {
        List<String> lines = new ArrayList<>();
        lines.add("Project: " + workDir);
        String node = NodeLocator.findNode(null);
        lines.add(mark(node != null) + " Node.js: " + (node == null ? "not found (Settings | Tools | Playwright Toolkit)" : node + " " + run(node, workDir, "--version")));
        String cli = PwLaunchSpec.findCli(workDir.toPath());
        lines.add(mark(cli != null) + " Playwright: " + (cli == null || node == null ? "not installed, run npm install" : run(node, workDir, cli, "--version")));
        if (node != null && cli != null) {
            String dryRun = run(node, workDir, cli, "install", "--dry-run", "chromium");
            Matcher m = INSTALL_LOCATION.matcher(dryRun);
            boolean any = false;
            while (m.find()) {
                any = true;
                boolean installed = Files.isDirectory(Path.of(m.group(2).trim()));
                lines.add(mark(installed) + " " + m.group(1).trim() + (installed ? "" : " — not installed: Tools | Playwright | Install Browsers"));
            }
            if (!any) lines.add("? Browsers: cannot check (" + dryRun.lines().findFirst().orElse("") + ")");
        }
        Path ts = TsServerLocator.find(workDir.toPath());
        lines.add(mark(ts != null) + " TypeScript 7 (navigation, highlighting): " + (ts == null ? "not found, add typescript@^7 to devDependencies" : ts));
        TsServerLocator.explain(workDir.toPath()).forEach(line -> lines.add("    " + line));
        Path jsDebug = JsDebugLocator.findServerScript();
        lines.add(mark(jsDebug != null) + " vscode-js-debug (debugging): " + (jsDebug == null ? "not found" : jsDebug));
        // optional: without it the standard HTML report is opened
        lines.add("• playwright-extended-report: " + (PwReports.findExtendedReportCli(workDir.toPath()) == null
                ? "not installed (the standard HTML report is used)" : "installed"));
        return lines;
    }

    private static @NotNull String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private static @NotNull String run(@NotNull String node, @NotNull File workDir, @NotNull String... args) {
        try {
            GeneralCommandLine line = args.length > 0 && args[0].equals("--version")
                    ? new GeneralCommandLine(node, "--version")
                    : new GeneralCommandLine(node).withParameters(args);
            ProcessOutput output = new CapturingProcessHandler(line.withWorkDirectory(workDir)).runProcess(60_000);
            return (output.getStdout() + output.getStderr()).trim();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
