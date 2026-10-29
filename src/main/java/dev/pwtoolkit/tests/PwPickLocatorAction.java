package dev.pwtoolkit.tests;

import com.intellij.ide.impl.ProjectUtil;
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
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import dev.pwtoolkit.run.PwLocatorPicker;
import dev.pwtoolkit.run.PwPickLocatorSupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pick Locator (like IDEA's Web Inspector): click an element in the picker browser; its locator candidates - unique on
 * the page, best first - are offered, the chosen one is inserted at the caret (the selector inside a string literal,
 * the code elsewhere) and copied to the clipboard.
 */
public final class PwPickLocatorAction extends PwToolsActions.Base {
    private static final String[] RANKS = {"", "test attribute", "data-testid", "id", "name / class", "role", "XPath by text", "inside a unique parent", "by position"};

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PwToolsActions.Command command = command(e);
        if (project == null || command == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        new Task.Backgroundable(project, "Pick locator", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                PwLocatorPicker picker = PwLocatorPicker.getInstance(project);
                try {
                    indicator.setText("Starting the browser for picking locators...");
                    if (picker.ensureStarted(command.workDir(), command.node(), false)) {
                        String url = askUrl(project, command.workDir(), picker.lastUrl());
                        if (url != null) picker.navigate(url);
                    }
                } catch (Exception ex) {
                    balloon(project, "Pick locator: " + ex.getMessage(), NotificationType.ERROR);
                    return;
                }
                indicator.setText("Click an element in the Pick Locator browser (Esc there cancels)");
                CompletableFuture<List<PwLocatorPicker.Candidate>> picked = picker.pick();
                while (true) {
                    if (indicator.isCanceled()) {
                        picker.cancel();
                        return;
                    }
                    try {
                        List<PwLocatorPicker.Candidate> candidates = picked.get(100, TimeUnit.MILLISECONDS);
                        if (!candidates.isEmpty()) ApplicationManager.getApplication().invokeLater(() -> choose(project, editor, candidates));
                        return;
                    } catch (TimeoutException ignored) {
                    } catch (Exception ex) {
                        balloon(project, "Pick locator: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()), NotificationType.ERROR);
                        return;
                    }
                }
            }
        }.queue();
    }

    /** The page to open in a just started picker browser: the last one, or baseURL of playwright.config. */
    static @Nullable String askUrl(@NotNull Project project, @NotNull File workDir, @Nullable String last) {
        String initial = last != null ? last : PwPickLocatorSupport.baseUrl(workDir);
        String[] url = new String[1];
        ApplicationManager.getApplication().invokeAndWait(() -> url[0] = Messages.showInputDialog(project,
                "Page to open in the Pick Locator browser (you can also type it in the browser; logins are kept between picks):",
                "Pick Locator", null, initial == null ? "https://" : initial, null));
        return url[0] == null || url[0].isBlank() || url[0].equals("https://") ? null : url[0].trim();
    }

    /** One candidate: inserted at once; several: a list, best first. On the EDT. */
    static void choose(@NotNull Project project, @Nullable Editor editor, @NotNull List<PwLocatorPicker.Candidate> candidates) {
        ProjectUtil.focusProjectWindow(project, true);
        if (candidates.size() == 1) {
            insert(project, editor, candidates.get(0));
            return;
        }
        JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
                .setTitle(candidates.get(0).count() == 1 ? "Choose Locator (unique on the page, best first)" : "No unique locator: choose one")
                .setRenderer(new ColoredListCellRenderer<PwLocatorPicker.Candidate>() {
                    @Override
                    protected void customizeCellRenderer(@NotNull JList<? extends PwLocatorPicker.Candidate> list, PwLocatorPicker.Candidate value,
                                                         int index, boolean selected, boolean hasFocus) {
                        append(value.code(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        append("   " + label(value), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        if (value.count() > 1) append("   " + value.count() + " matches!", SimpleTextAttributes.ERROR_ATTRIBUTES);
                    }
                })
                .setItemChosenCallback(chosen -> insert(project, editor, chosen))
                .createPopup();
        if (editor != null && !editor.isDisposed()) popup.showInBestPositionFor(editor);
        else popup.showCenteredInCurrentWindow(project);
    }

    static @NotNull String label(@NotNull PwLocatorPicker.Candidate candidate) {
        String rank = candidate.rank() > 0 && candidate.rank() < RANKS.length ? RANKS[candidate.rank()] : candidate.kind();
        return candidate.rank() <= 2 ? rank + " " + candidate.kind() : rank;
    }

    /** At the caret (replacing the selection) when the editor is writable; always to the clipboard. On the EDT. */
    static void insert(@NotNull Project project, @Nullable Editor editor, @NotNull PwLocatorPicker.Candidate candidate) {
        String text = candidate.code();
        if (editor != null && !editor.isDisposed() && editor.getDocument().isWritable()) {
            Document document = editor.getDocument();
            int caret = editor.getCaretModel().getOffset();
            if (insideString(document.getImmutableCharSequence(), caret)) text = candidate.selector();
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
        balloon(project, "Locator: " + text + (candidate.count() > 1 ? " (" + candidate.count() + " matches on the page!)" : "")
                + " - copied to the clipboard", candidate.count() > 1 ? NotificationType.WARNING : NotificationType.INFORMATION);
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

    private static void balloon(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("Playwright Toolkit").createNotification(message, type).notify(project);
    }

    /** Tools | Playwright | Open Page in Pick Locator Browser... */
    public static final class OpenPage extends PwToolsActions.Base {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            PwToolsActions.Command command = command(e);
            if (project == null || command == null) return;
            new Task.Backgroundable(project, "Opening the Pick Locator browser", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    PwLocatorPicker picker = PwLocatorPicker.getInstance(project);
                    try {
                        picker.ensureStarted(command.workDir(), command.node(), false);
                        String url = askUrl(project, command.workDir(), picker.lastUrl());
                        if (url != null) picker.navigate(url);
                    } catch (Exception ex) {
                        balloon(project, "Pick locator: " + ex.getMessage(), NotificationType.ERROR);
                    }
                }
            }.queue();
        }
    }
}
