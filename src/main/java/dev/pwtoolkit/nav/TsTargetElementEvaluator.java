package dev.pwtoolkit.nav;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.openapi.vfs.VirtualFile;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the references GigaIDE cannot resolve (Page Object members, fixtures, Playwright's API...) with the
 * TypeScript server, so that the platform knows the target: Show Usages, Find Usages, highlighting of usages,
 * Quick Definition and Ctrl+Click on a declaration work like in IntelliJ IDEA.
 */
public final class TsTargetElementEvaluator extends TargetElementEvaluatorEx2 {
    private static final long TIMEOUT_MS = 1500;

    @Override
    public @Nullable PsiElement getElementByReference(@NotNull PsiReference ref, int flags) {
        if ((flags & TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED) == 0 || !PwSettings.getInstance().getState().navigationEnabled) return null;
        PsiElement element = ref.getElement();
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;
        if (ref.resolve() != null) return null; // GigaIDE knows it
        int offset = element.getTextRange().getStartOffset() + ref.getRangeInElement().getStartOffset();
        return TsDeclarations.declarationAt(element.getProject(), virtualFile, offset, TIMEOUT_MS);
    }
}
