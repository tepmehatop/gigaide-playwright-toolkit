package dev.pwtoolkit.nav;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.lang.LanguageCodeInsightActionHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ReadAction;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.codeInsight.hint.HintManager;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Go to Super Method / Class (Cmd+U / Ctrl+U): the overridden or implemented member, or the supertypes of the class. */
public final class TsGotoSuperHandler implements LanguageCodeInsightActionHandler {
    @Override
    public boolean isValidFor(Editor editor, PsiFile file) {
        return file != null && JsFiles.isScript(file.getOriginalFile().getVirtualFile());
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        int offset = editor.getCaretModel().getOffset();
        List<PsiElement> targets = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> ReadAction.compute(() -> {
            PsiElement owner = owner(file.findElementAt(offset));
            return owner == null ? List.<PsiElement>of() : TsHierarchy.supers(project, owner).stream().map(TsHierarchy.Super::element).toList();
        }), "Searching for super declarations", true, project);
        if (targets.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "No super method or class found");
        } else if (targets.size() == 1) {
            ((Navigatable) targets.get(0)).navigate(true);
        } else {
            NavigationUtil.getPsiElementPopup(targets.toArray(PsiElement.EMPTY_ARRAY), "Choose Super Declaration").showInBestPositionFor(editor);
        }
    }

    /** The member or class around the caret. */
    static @Nullable PsiElement owner(@Nullable PsiElement leaf) {
        for (PsiElement element = leaf; element != null && !(element instanceof PsiFile); element = element.getParent()) {
            if (TsHierarchy.isMember(element) || TsHierarchy.isType(element)) return element;
        }
        return null;
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    static @Nullable PsiElement nameLeaf(@NotNull PsiElement declaration) {
        PsiElement leaf = declaration.getContainingFile().findElementAt(declaration.getTextOffset());
        return leaf != null && PsiTreeUtil.isAncestor(declaration, leaf, false) ? leaf : null;
    }
}
