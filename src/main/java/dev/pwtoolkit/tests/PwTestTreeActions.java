package dev.pwtoolkit.tests;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.run.PwTestDetector;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Comparator;
import java.util.List;

/** Context-menu actions of the Playwright test tree: trace, attachments, report. */
public final class PwTestTreeActions {
    private PwTestTreeActions() {
    }

    /** The selected tree node resolved to the project folder and the last-run result of that test. */
    record Selection(@NotNull File workDir, @Nullable PwRunResults.Test test) {
        @NotNull List<PwRunResults.Attachment> attachments() {
            return test == null ? List.of() : test.attachments();
        }
    }

    static @Nullable Selection selection(@NotNull AnActionEvent e) {
        AbstractTestProxy proxy = e.getData(AbstractTestProxy.DATA_KEY);
        String url = proxy == null ? null : proxy.getLocationUrl();
        if (url == null || !url.startsWith(PwTestLocator.PROTOCOL + "://")) return null;
        PwTestLocator.FileLine location = PwTestLocator.FileLine.parse(url.substring(PwTestLocator.PROTOCOL.length() + 3));
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(location.path());
        VirtualFile configDir = file == null ? null : PwTestDetector.findConfigDir(file);
        if (configDir == null) return null;
        File workDir = new File(configDir.getPath());
        PwRunResults results = proxy.isLeaf() ? PwRunResults.load(workDir) : null;
        PwRunResults.Test test = results == null ? null : results.at(location.path(), location.line()).stream()
                // the failed run first when the test ran in several projects/repeats
                .min(Comparator.comparing(t -> t.status().equals("passed") || t.status().equals("skipped")))
                .orElse(null);
        return new Selection(workDir, test);
    }

    abstract static class Base extends AnAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Selection selection = selection(e);
            e.getPresentation().setVisible(selection != null);
            e.getPresentation().setEnabled(selection != null && isEnabled(selection));
        }

        boolean isEnabled(@NotNull Selection selection) {
            return true;
        }
    }

    /** Opens the test's trace.zip in Playwright Trace Viewer (`playwright show-trace`). */
    public static final class OpenTrace extends Base {
        @Override
        boolean isEnabled(@NotNull Selection selection) {
            return selection.attachments().stream().anyMatch(PwRunResults.Attachment::isTrace);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Selection selection = selection(e);
            if (selection == null) return;
            selection.attachments().stream().filter(PwRunResults.Attachment::isTrace).findFirst()
                    .ifPresent(trace -> showTrace(e.getProject(), selection.workDir(), trace.path()));
        }
    }

    /** Screenshots, videos, error context and other attachments of the test. */
    public static final class OpenAttachment extends Base {
        @Override
        boolean isEnabled(@NotNull Selection selection) {
            return selection.attachments().stream().anyMatch(a -> !a.isTrace());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Selection selection = selection(e);
            Project project = e.getProject();
            if (selection == null || project == null) return;
            List<PwRunResults.Attachment> attachments = selection.attachments().stream().filter(a -> !a.isTrace()).toList();
            if (attachments.size() == 1) {
                openAttachment(project, attachments.get(0));
                return;
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(attachments)
                    .setTitle("Attachments")
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("", a -> a.name() + "  " + new File(a.path()).getName()))
                    .setItemChosenCallback(a -> openAttachment(project, a))
                    .createPopup()
                    .showInBestPositionFor(e.getDataContext());
        }
    }

    /** The test page in playwright-extended-report (or the standard HTML report). */
    public static final class OpenInReport extends Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Selection selection = selection(e);
            if (selection == null || e.getProject() == null) return;
            PwReports.getInstance(e.getProject()).open(selection.workDir(), selection.test() == null ? null : selection.test().id());
        }
    }

    static void openAttachment(@NotNull Project project, @NotNull PwRunResults.Attachment attachment) {
        File file = new File(attachment.path());
        String name = file.getName().toLowerCase();
        if (attachment.isImage() || name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".html")) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true);
                return;
            }
        }
        RevealFileAction.openFile(file);
    }

    static void showTrace(@Nullable Project project, @NotNull File workDir, @NotNull String trace) {
        String node = NodeLocator.findNode(null);
        String cli = PwLaunchSpec.findCli(workDir.toPath());
        if (node == null || cli == null) {
            Messages.showErrorDialog(project, "Node.js or @playwright/test was not found for " + workDir, "Open Trace");
            return;
        }
        try {
            new OSProcessHandler(new GeneralCommandLine(node, cli, "show-trace", trace).withWorkDirectory(workDir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)).startNotify();
        } catch (Exception ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "Open Trace");
        }
    }
}
