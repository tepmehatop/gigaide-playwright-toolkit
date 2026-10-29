package dev.pwtoolkit.refactor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.IntentionMenuContributor;
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Alt+Enter without an error: TypeScript's refactorings for the selection or the caret - Extract to constant / function /
 * method / field, Convert to async function, Add or remove braces, Convert to template string, Convert import...
 */
public final class TsRefactorIntentions implements IntentionMenuContributor {
    private static final long TIMEOUT_MS = 800;

    @Override
    public void collectActions(@NotNull Editor hostEditor, @NotNull PsiFile hostFile, ShowIntentionsPass.@NotNull IntentionsInfo intentions,
                               int passIdToShowIntentionsFor, int offset) {
        if (!PwSettings.getInstance().getState().navigationEnabled) return;
        VirtualFile file = hostFile.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file) || !JsFiles.isOnDisk(file)) return;
        SelectionModel selection = hostEditor.getSelectionModel();
        int start = selection.hasSelection() ? selection.getSelectionStart() : offset;
        int end = selection.hasSelection() ? selection.getSelectionEnd() : offset;
        List<TsRefactorings.Refactoring> refactorings = TsRefactorings.applicable(hostFile.getProject(), file, hostEditor.getDocument(), start, end, TIMEOUT_MS);
        if (refactorings == null) return;
        for (TsRefactorings.Refactoring refactoring : refactorings) {
            intentions.intentionsToShow.add(new HighlightInfo.IntentionActionDescriptor(new Action(refactoring, start, end),
                    null, null, null, null, null, null));
        }
    }

    private static final class Action implements IntentionAction {
        private final TsRefactorings.Refactoring refactoring;
        private final int start;
        private final int end;

        Action(@NotNull TsRefactorings.Refactoring refactoring, int start, int end) {
            this.refactoring = refactoring;
            this.start = start;
            this.end = end;
        }

        @Override
        public @NotNull String getText() {
            return refactoring.description();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "TypeScript refactoring";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            VirtualFile virtualFile = TsRefactorings.fileOf(editor);
            if (virtualFile != null) TsRefactorings.perform(project, editor, virtualFile, start, end, refactoring);
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            return IntentionPreviewInfo.EMPTY;
        }
    }
}
