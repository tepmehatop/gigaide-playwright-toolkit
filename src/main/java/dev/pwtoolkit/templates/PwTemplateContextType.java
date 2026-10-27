package dev.pwtoolkit.templates;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/** Live templates of the plugin are available in TypeScript/JavaScript files. */
public final class PwTemplateContextType extends TemplateContextType {
    public PwTemplateContextType() {
        super("Playwright (TypeScript/JavaScript)");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        PsiFile file = context.getFile();
        return JsFiles.isScript(file.getOriginalFile().getVirtualFile()) || JsFiles.isScriptName(file.getName());
    }
}
