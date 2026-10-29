package dev.pwtoolkit.tests;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import dev.pwtoolkit.run.PwBrowserServer;
import dev.pwtoolkit.run.PwDebugController;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pick Locator (like VS Code's "Pick locator" and IDEA's Web Inspector): the browser kept open by Show Browser highlights
 * the element under the mouse; the clicked element's locator is inserted at the caret and copied to the clipboard.
 * In a string literal the selector is inserted ({@code internal:role=button[name="Sign in"i]}), elsewhere the code
 * ({@code getByRole('button', { name: 'Sign in' })}).
 */
public final class PwPickLocatorAction extends PwToolsActions.Base {
    private static final Pattern TEST_ID_ATTRIBUTE = Pattern.compile("testIdAttribute\\s*:\\s*['\"]([^'\"]+)['\"]");

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PwToolsActions.Command command = command(e);
        if (project == null || command == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        try {
            PwBrowserServer.getInstance(project).ensureStarted(command.workDir(), command.node(), command.cli());
        } catch (Exception ex) {
            Messages.showErrorDialog(project, "Cannot start the browser: " + ex.getMessage(), "Pick Locator");
            return;
        }
        String testId = testIdAttribute(command.workDir());
        new Task.Backgroundable(project, "Pick locator: click an element in the browser", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Click an element in the browser (open the page in it first: Show Browser keeps it after a test run)");
                PwDebugController controller = PwBrowserServer.getInstance(project).controller();
                if (controller == null) {
                    balloon(project, "The browser is not available", NotificationType.ERROR);
                    return;
                }
                CompletableFuture<PwDebugController.Picked> picked = controller.pickLocator(testId);
                try {
                    while (true) {
                        if (indicator.isCanceled()) {
                            picked.cancel(false);
                            return;
                        }
                        try {
                            PwDebugController.Picked result = picked.get(100, TimeUnit.MILLISECONDS);
                            ApplicationManager.getApplication().invokeLater(() -> insert(project, editor, result));
                            return;
                        } catch (TimeoutException ignored) {
                        }
                    }
                } catch (java.util.concurrent.CancellationException ignored) {
                } catch (Exception ex) {
                    balloon(project, "Pick locator failed: " + ex.getMessage(), NotificationType.ERROR);
                }
            }
        }.queue();
    }

    /** At the caret (replacing the selection) when the editor is writable; always to the clipboard. On the EDT. */
    static void insert(@NotNull Project project, @Nullable Editor editor, @NotNull PwDebugController.Picked picked) {
        String text = picked.locator();
        if (editor != null && !editor.isDisposed() && editor.getDocument().isWritable()) {
            Document document = editor.getDocument();
            int caret = editor.getCaretModel().getOffset();
            if (insideString(document.getImmutableCharSequence(), caret)) text = picked.selector();
            String inserted = text;
            WriteCommandAction.runWriteCommandAction(project, "Pick Locator", null, () -> {
                int start = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionStart() : caret;
                int end = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionEnd() : caret;
                document.replaceString(start, end, inserted);
                editor.getCaretModel().moveToOffset(start + inserted.length());
                editor.getSelectionModel().removeSelection();
            });
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        balloon(project, "Picked: " + text + " (copied to the clipboard)", NotificationType.INFORMATION);
    }

    /** An odd number of unescaped quotes of one kind before the caret on its line. */
    static boolean insideString(@NotNull CharSequence text, int caret) {
        int lineStart = caret;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        char open = 0;
        for (int i = lineStart; i < caret; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (open == 0 && (c == '"' || c == '\'' || c == '`')) {
                open = c;
            } else if (c == open) {
                open = 0;
            }
        }
        return open != 0;
    }

    private static @Nullable String testIdAttribute(@NotNull File workDir) {
        for (String name : new String[]{"playwright.config.ts", "playwright.config.js", "playwright.config.mjs"}) {
            File config = new File(workDir, name);
            if (!config.isFile()) continue;
            try {
                Matcher matcher = TEST_ID_ATTRIBUTE.matcher(Files.readString(config.toPath(), StandardCharsets.UTF_8));
                if (matcher.find()) return matcher.group(1);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void balloon(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("Playwright Toolkit").createNotification(message, type).notify(project);
    }
}
