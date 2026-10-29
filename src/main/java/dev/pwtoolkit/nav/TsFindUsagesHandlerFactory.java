package dev.pwtoolkit.nav;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Usages of TS/JS declarations from the TypeScript server: Find Usages (Alt+F7), Show Usages (Cmd+Alt+F7),
 * Ctrl+Click on a declaration, and the highlighting of usages under the caret.
 */
public final class TsFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        if (!(element instanceof PsiNamedElement) || !PwSettings.getInstance().getState().navigationEnabled) return false;
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
        return virtualFile != null && virtualFile.isInLocalFileSystem() && JsFiles.isScript(virtualFile);
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean forHighlightUsages) {
        return new Handler(element);
    }

    private static final class Handler extends FindUsagesHandler {
        Handler(@NotNull PsiElement element) {
            super(element);
        }

        @Override
        public boolean processElementUsages(@NotNull PsiElement element, @NotNull Processor<? super UsageInfo> processor,
                                            @NotNull FindUsagesOptions options) {
            Project project = element.getProject();
            List<TsLanguageService.Reference> references = references(element, 30_000);
            if (references == null) return true;
            int declaration = ApplicationManager.getApplication().runReadAction((Computable<Integer>) element::getTextOffset);
            VirtualFile declarationFile = ApplicationManager.getApplication().runReadAction(
                    (Computable<VirtualFile>) () -> element.getContainingFile().getOriginalFile().getVirtualFile());
            for (TsLanguageService.Reference reference : references) {
                UsageInfo usage = ApplicationManager.getApplication().runReadAction((Computable<UsageInfo>) () -> {
                    VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(reference.file());
                    Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                    PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
                    if (document == null || psiFile == null) return null;
                    int start = TsEdits.offset(document, reference.range().line(), reference.range().character());
                    int end = TsEdits.offset(document, reference.range().endLine(), reference.range().endCharacter());
                    if (file.equals(declarationFile) && start == declaration) return null; // the declaration itself
                    return new UsageInfo(psiFile, start, Math.max(end, start + 1));
                });
                if (usage != null && !processor.process(usage)) return false;
            }
            return true;
        }

        /** Highlighting of usages under the caret: the IDE's references at the server's positions in that file. */
        @Override
        public @NotNull Collection<PsiReference> findReferencesToHighlight(@NotNull PsiElement target, @NotNull SearchScope searchScope) {
            List<PsiReference> result = new ArrayList<>();
            if (!(searchScope instanceof LocalSearchScope local)) return result;
            List<TsLanguageService.Reference> references = references(target, 2000);
            if (references == null) return result;
            for (PsiElement scopeElement : local.getScope()) {
                PsiFile psiFile = scopeElement.getContainingFile();
                VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
                Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                if (document == null) continue;
                for (TsLanguageService.Reference reference : references) {
                    if (!reference.file().equals(file.toNioPath())) continue;
                    int start = TsEdits.offset(document, reference.range().line(), reference.range().character());
                    PsiReference ref = psiFile.findReferenceAt(start);
                    if (ref != null && !result.contains(ref)) result.add(ref);
                }
            }
            return result;
        }

        private static @Nullable List<TsLanguageService.Reference> references(@NotNull PsiElement element, long timeoutMs) {
            CompletableFuture<List<TsLanguageService.Reference>> future = ApplicationManager.getApplication().runReadAction(
                    (Computable<CompletableFuture<List<TsLanguageService.Reference>>>) () -> {
                        PsiFile psiFile = element.getContainingFile();
                        VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
                        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                        if (document == null) return null;
                        return TsLanguageService.getInstance(element.getProject()).references(file, document, element.getTextOffset());
                    });
            if (future == null) return null;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                ProgressManager.checkCanceled();
                try {
                    return future.get(10, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (System.currentTimeMillis() > deadline) return null;
                } catch (ProcessCanceledException e) {
                    throw e;
                } catch (Exception e) {
                    return null;
                }
            }
        }
    }
}
