package dev.pwtoolkit.nav;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Go to Implementation (Cmd+Alt+B / Ctrl+Alt+B) for TS/JS: implementing classes and overriding members, by the server. */
public final class TsImplementationSearch implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    @Override
    public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters parameters, @NotNull Processor<? super PsiElement> consumer) {
        PsiElement element = parameters.getElement();
        if (!PwSettings.getInstance().getState().navigationEnabled) return true;
        List<PsiElement> implementations = ApplicationManager.getApplication().runReadAction((Computable<List<PsiElement>>) () -> {
            if (!(element instanceof PsiNamedElement) || !element.isValid()) return List.of();
            PsiFile file = element.getContainingFile();
            if (file == null || !JsFiles.isScript(file.getOriginalFile().getVirtualFile())) return List.of();
            return TsHierarchy.implementations(element.getProject(), element, 10_000);
        });
        for (PsiElement implementation : implementations) {
            if (!consumer.process(implementation)) return false;
        }
        return true;
    }
}
