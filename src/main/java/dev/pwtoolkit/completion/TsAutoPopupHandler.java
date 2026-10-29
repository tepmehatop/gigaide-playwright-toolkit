package dev.pwtoolkit.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/** Opens the completion popup after "page." and parameter info after "(" like in VS Code / WebStorm. */
public final class TsAutoPopupHandler extends TypedHandlerDelegate {
    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (charTyped == '.' && PwSettings.getInstance().getState().completion
                && JsFiles.isScript(file.getOriginalFile().getVirtualFile())) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        }
        if (charTyped == '(' && PwSettings.getInstance().getState().completion
                && JsFiles.isScript(file.getOriginalFile().getVirtualFile())) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null);
        }
        return Result.CONTINUE;
    }
}
