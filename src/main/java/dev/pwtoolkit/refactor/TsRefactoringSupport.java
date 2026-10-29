package dev.pwtoolkit.refactor;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import dev.pwtoolkit.completion.ExpressionScanner;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Extract Variable (Cmd+Alt+V / Ctrl+Alt+V), Constant (Cmd+Alt+C), Field (Cmd+Alt+F) and Method (Cmd+Alt+M) by TypeScript's
 * "Extract Symbol" refactoring. Without a selection the expression at the caret is extracted (the statement on the line
 * for Extract Method). Several scopes for a method: the IDE asks which one, like IntelliJ IDEA.
 */
public final class TsRefactoringSupport extends RefactoringSupportProvider {
    enum Kind { VARIABLE, CONSTANT, FIELD, METHOD }

    @Override
    public boolean isAvailable(@NotNull PsiElement context) {
        PsiFile file = context.getContainingFile();
        return file != null && JsFiles.isScript(file.getOriginalFile().getVirtualFile());
    }

    @Override
    public @Nullable RefactoringActionHandler getIntroduceVariableHandler() {
        return new Handler(Kind.VARIABLE);
    }

    @Override
    public @Nullable RefactoringActionHandler getIntroduceConstantHandler() {
        return new Handler(Kind.CONSTANT);
    }

    @Override
    public @Nullable RefactoringActionHandler getIntroduceFieldHandler() {
        return new Handler(Kind.FIELD);
    }

    @Override
    public @Nullable RefactoringActionHandler getExtractMethodHandler() {
        return new Handler(Kind.METHOD);
    }

    static final class Handler implements RefactoringActionHandler {
        private final Kind kind;

        Handler(@NotNull Kind kind) {
            this.kind = kind;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
            VirtualFile virtualFile = editor == null ? null : TsRefactorings.fileOf(editor);
            if (virtualFile == null) return;
            String title = switch (kind) {
                case VARIABLE -> "Extract Variable";
                case CONSTANT -> "Extract Constant";
                case FIELD -> "Extract Field";
                case METHOD -> "Extract Method";
            };
            String unavailable = TsLanguageService.getInstance(project).refactoringsUnavailableReason(virtualFile);
            if (unavailable != null) {
                CommonRefactoringUtil.showErrorHint(project, editor, "TypeScript refactorings are not available: " + unavailable, title, null);
                if (unavailable.contains("TypeScript 7")) TsLanguageService.getInstance(project).suggestTypeScript5ForRefactorings();
                return;
            }
            int[] range = range(editor);
            if (range == null) {
                CommonRefactoringUtil.showErrorHint(project, editor, "Select an expression to extract", title, null);
                return;
            }
            List<TsRefactorings.Refactoring> refactorings = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> TsRefactorings.applicable(project, virtualFile, editor.getDocument(), range[0], range[1], 10_000), title, true, project);
            List<TsRefactorings.Refactoring> matching = refactorings == null ? List.of() : matching(refactorings);
            if (matching.isEmpty()) {
                CommonRefactoringUtil.showErrorHint(project, editor, "Cannot " + title.toLowerCase() + " here: select a complete expression"
                        + (kind == Kind.METHOD ? " or statements" : ""), title, null);
                return;
            }
            if (matching.size() == 1) {
                TsRefactorings.perform(project, editor, virtualFile, range[0], range[1], matching.get(0));
                return;
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(matching)
                    .setTitle("Choose Scope")
                    .setRenderer(new com.intellij.ui.SimpleListCellRenderer<TsRefactorings.Refactoring>() {
                        @Override
                        public void customize(@NotNull javax.swing.JList<? extends TsRefactorings.Refactoring> list, TsRefactorings.Refactoring value,
                                              int index, boolean selected, boolean hasFocus) {
                            setText(value.description());
                        }
                    })
                    .setItemChosenCallback(chosen -> TsRefactorings.perform(project, editor, virtualFile, range[0], range[1], chosen))
                    .createPopup()
                    .showInBestPositionFor(editor);
        }

        @Override
        public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext) {
        }

        /** The "Extract Symbol" actions of this kind, innermost scope first. */
        @NotNull List<TsRefactorings.Refactoring> matching(@NotNull List<TsRefactorings.Refactoring> all) {
            List<TsRefactorings.Refactoring> constants = all.stream().filter(r -> r.action().startsWith("constant_scope_")).toList();
            return switch (kind) {
                case VARIABLE -> constants.stream().filter(r -> !r.description().contains("field")).limit(1).toList();
                case CONSTANT -> {
                    List<TsRefactorings.Refactoring> module = constants.stream()
                            .filter(r -> r.description().contains("module scope") || r.description().contains("global scope")).toList();
                    List<TsRefactorings.Refactoring> plain = constants.stream().filter(r -> !r.description().contains("field")).toList();
                    yield !module.isEmpty() ? module : plain.isEmpty() ? List.of() : List.of(plain.get(plain.size() - 1));
                }
                case FIELD -> constants.stream().filter(r -> r.description().contains("field")).toList();
                case METHOD -> all.stream().filter(r -> r.action().startsWith("function_scope_")).toList();
            };
        }

        /** The selection; without one the expression at the caret (or the statement on the line for Extract Method). */
        private int @Nullable [] range(@NotNull Editor editor) {
            if (editor.getSelectionModel().hasSelection()) {
                return new int[]{editor.getSelectionModel().getSelectionStart(), editor.getSelectionModel().getSelectionEnd()};
            }
            Document document = editor.getDocument();
            CharSequence text = document.getImmutableCharSequence();
            int caret = editor.getCaretModel().getOffset();
            if (kind == Kind.METHOD) {
                int line = document.getLineNumber(caret);
                int start = document.getLineStartOffset(line);
                int end = document.getLineEndOffset(line);
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                return start < end ? new int[]{start, end} : null;
            }
            int end = expressionEnd(text, caret);
            int start = ExpressionScanner.start(text, end);
            // "await page.title()": the awaited expression with its await
            String before = text.subSequence(Math.max(0, start - 6), start).toString();
            if (before.equals("await ")) start -= 6;
            return start < end ? new int[]{start, end} : null;
        }

        /** From the caret to the right: the identifier, then calls, indexes and member accesses chained to it. */
        static int expressionEnd(@NotNull CharSequence text, int caret) {
            int i = caret;
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '(' || c == '[') {
                    int close = closing(text, i);
                    if (close < 0) break;
                    i = close + 1;
                } else if (c == '.' && i + 1 < text.length() && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                    i++;
                    while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                } else {
                    break;
                }
            }
            return i;
        }

        private static int closing(@NotNull CharSequence text, int open) {
            int depth = 0;
            char quote = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == '\\') i++;
                    else if (c == quote) quote = 0;
                    continue;
                }
                if (c == '"' || c == '\'' || c == '`') quote = c;
                else if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }
    }
}
