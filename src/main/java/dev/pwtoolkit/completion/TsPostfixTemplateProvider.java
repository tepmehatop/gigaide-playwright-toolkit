package dev.pwtoolkit.completion;

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Postfix templates for TypeScript/JavaScript, which GigaIDE does not have: {@code value.log} + Tab -> {@code console.log(value)}.
 * The expression is found in the text (GigaIDE's PSI is not the WebStorm one).
 */
public final class TsPostfixTemplateProvider implements PostfixTemplateProvider {
    private final Set<PostfixTemplate> templates = Set.of(new ConsoleTemplate(this, "log"), new ConsoleTemplate(this, "error"));

    @Override
    public @NotNull String getId() {
        return "dev.pwtoolkit.ts";
    }

    @Override
    public @NotNull String getPresentableName() {
        return "Playwright Toolkit (TypeScript/JavaScript)";
    }

    @Override
    public @NotNull Set<PostfixTemplate> getTemplates() {
        return templates;
    }

    @Override
    public boolean isTerminalSymbol(char currentChar) {
        return currentChar == '.' || currentChar == '!';
    }

    @Override
    public void preExpand(@NotNull PsiFile file, @NotNull Editor editor) {
    }

    @Override
    public void afterExpand(@NotNull PsiFile file, @NotNull Editor editor) {
    }

    @Override
    public @NotNull PsiFile preCheck(@NotNull PsiFile copyFile, @NotNull Editor realEditor, int currentOffset) {
        return copyFile;
    }

    /** {@code expr.log} -> {@code console.log(expr)}. */
    private static final class ConsoleTemplate extends PostfixTemplate {
        private final String method;

        ConsoleTemplate(@NotNull PostfixTemplateProvider provider, @NotNull String method) {
            super("dev.pwtoolkit." + method, method, "console." + method + "(expr)", provider);
            this.method = method;
        }

        @Override
        public boolean isApplicable(@NotNull PsiElement context, @NotNull Document copyDocument, int newOffset) {
            return ExpressionScanner.start(copyDocument.getImmutableCharSequence(), newOffset) < newOffset;
        }

        @Override
        public void expand(@NotNull PsiElement context, @NotNull Editor editor) {
            Document document = editor.getDocument();
            int end = editor.getCaretModel().getOffset();
            int start = ExpressionScanner.start(document.getImmutableCharSequence(), end);
            if (start >= end) return;
            String expression = document.getText().substring(start, end);
            String replacement = "console." + method + "(" + expression + ")";
            document.replaceString(start, end, replacement);
            editor.getCaretModel().moveToOffset(start + replacement.length());
        }
    }
}
