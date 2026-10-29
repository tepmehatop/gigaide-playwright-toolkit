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
 * Postfix templates for TypeScript/JavaScript like IntelliJ IDEA's, which GigaIDE does not have: {@code value.log} + Tab
 * -> {@code console.log(value)}, {@code .const}, {@code .if}, {@code .forof}... The expression is found in the text
 * (GigaIDE's PSI is not the WebStorm one). Templates with a new name start a live template with the name selected.
 */
public final class TsPostfixTemplateProvider implements PostfixTemplateProvider {
    private final Set<PostfixTemplate> templates = Set.of(
            new ConsoleTemplate(this, "log"), new ConsoleTemplate(this, "error"),
            new LiveTemplate(this, "const", "const name = expr", "const $NAME$ = ⟦E⟧;$END$", true),
            new LiveTemplate(this, "let", "let name = expr", "let $NAME$ = ⟦E⟧;$END$", true),
            new LiveTemplate(this, "return", "return expr", "return ⟦E⟧;$END$", false),
            new LiveTemplate(this, "await", "await expr", "await ⟦E⟧$END$", false),
            new LiveTemplate(this, "not", "!expr", "⟦N⟧$END$", false),
            new LiveTemplate(this, "par", "(expr)", "(⟦E⟧)$END$", false),
            new LiveTemplate(this, "if", "if (expr) {}", "if (⟦E⟧) {\n    $END$\n}", false),
            new LiveTemplate(this, "notnull", "if (expr != null) {}", "if (⟦E⟧ != null) {\n    $END$\n}", false),
            new LiveTemplate(this, "null", "if (expr == null) {}", "if (⟦E⟧ == null) {\n    $END$\n}", false),
            new LiveTemplate(this, "forof", "for (const item of expr) {}", "for (const $NAME$ of ⟦E⟧) {\n    $END$\n}", true),
            new LiveTemplate(this, "for", "for (const key in expr) {}", "for (const $NAME$ in ⟦E⟧) {\n    $END$\n}", true),
            new LiveTemplate(this, "fori", "for (let i = 0; i < expr.length; i++) {}",
                    "for (let $NAME$ = 0; $NAME$ < ⟦E⟧.length; $NAME$++) {\n    $END$\n}", true));

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

    /**
     * The expression is replaced by {@code text}: ⟦E⟧ is the expression, ⟦N⟧ its negation (new lines are
     * indented like the line by the template engine); $NAME$ is a name to type (suggested from the expression), $END$ the caret.
     */
    private static final class LiveTemplate extends PostfixTemplate {
        private final String key;
        private final String text;
        private final boolean withName;

        LiveTemplate(@NotNull PostfixTemplateProvider provider, @NotNull String key, @NotNull String example, @NotNull String text, boolean withName) {
            super("dev.pwtoolkit." + key, key, example, provider);
            this.key = key;
            this.text = text;
            this.withName = withName;
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
            String escaped = expression.replace("$", "$$");
            String negated = expression.matches("[\\w$.!?]+(\\([^()]*\\))?") ? "!" + escaped : "!(" + escaped + ")";
            String body = text.replace("⟦E⟧", escaped).replace("⟦N⟧", negated);
            document.deleteString(start, end);
            editor.getCaretModel().moveToOffset(start);
            com.intellij.codeInsight.template.TemplateManager manager = com.intellij.codeInsight.template.TemplateManager.getInstance(context.getProject());
            com.intellij.codeInsight.template.Template template = manager.createTemplate("", "", body);
            template.setToReformat(false);
            if (withName) {
                template.addVariable("NAME", new com.intellij.codeInsight.template.impl.TextExpression(suggestName(expression, key)), true);
            }
            manager.startTemplate(editor, template);
        }

        /** page.locator("#a") -> locator, await page.title() -> title, items -> item (for .forof). */
        static @NotNull String suggestName(@NotNull String expression, @NotNull String key) {
            if (key.equals("fori")) return "i";
            if (key.equals("for")) return "key";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*)\\s*(\\([^()]*\\))?\\s*$").matcher(expression);
            String name = matcher.find() ? matcher.group(1) : "value";
            if (name.startsWith("get") && name.length() > 3) name = name.substring(3);
            if (key.equals("forof")) name = name.endsWith("s") && name.length() > 1 ? name.substring(0, name.length() - 1) : "item";
            name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            return name.equals("this") ? "value" : name;
        }
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
