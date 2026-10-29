package dev.pwtoolkit.templates;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/**
 * Live templates of the plugin: code of TypeScript/JavaScript files, not inside strings or comments and not after a
 * "." (so that "request.get" or "items.forEach" are completed as members, not expanded).
 */
public class PwTemplateContextType extends TemplateContextType {
    public PwTemplateContextType() {
        this("Playwright (TypeScript/JavaScript)");
    }

    protected PwTemplateContextType(@NotNull String presentableName) {
        super(presentableName);
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        PsiFile file = context.getFile();
        if (!(JsFiles.isScript(file.getOriginalFile().getVirtualFile()) || JsFiles.isScriptName(file.getName()))) return false;
        return isCode(file, context.getStartOffset());
    }

    static boolean isCode(@NotNull PsiFile file, int offset) {
        CharSequence text = file.getViewProvider().getContents();
        int before = offset - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before)) && text.charAt(before) != '\n') before--;
        if (before >= 0 && text.charAt(before) == '.') return false;
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null && offset > 0) leaf = file.findElementAt(offset - 1);
        if (leaf == null) return true;
        if (leaf instanceof PsiComment || leaf.getClass().getSimpleName().contains("Comment")) return false;
        String leafText = leaf.getText();
        boolean string = !leafText.isEmpty() && (leafText.charAt(0) == '"' || leafText.charAt(0) == '\'' || leafText.charAt(0) == '`');
        return !(string && offset > leaf.getTextRange().getStartOffset());
    }

    /** TypeScript only: interface, type, enum, typed fields. */
    public static final class TypeScriptOnly extends PwTemplateContextType {
        public TypeScriptOnly() {
            super("TypeScript (Playwright Toolkit)");
        }

        @Override
        public boolean isInContext(@NotNull TemplateActionContext context) {
            String name = context.getFile().getName().toLowerCase();
            return (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".mts") || name.endsWith(".cts")) && super.isInContext(context);
        }
    }
}
