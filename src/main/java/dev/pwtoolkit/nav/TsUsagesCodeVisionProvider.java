package dev.pwtoolkit.nav;

import com.intellij.codeInsight.hints.codeVision.ReferencesCodeVisionProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * "N usages" above classes, functions, methods and class fields (Code Vision), counted by the TypeScript server.
 * A click shows the usages, like in IntelliJ IDEA.
 */
public final class TsUsagesCodeVisionProvider extends ReferencesCodeVisionProvider {
    private static final long TIMEOUT_MS = 5000;

    @Override
    public @NotNull String getId() {
        return "dev.pwtoolkit.ts.usages";
    }

    @Override
    public @NotNull List<com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering> getRelativeOrderings() {
        return List.of();
    }

    @Override
    public boolean acceptsFile(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        return PwSettings.getInstance().getState().navigationEnabled && JsFiles.isScript(virtualFile)
                && virtualFile.isInLocalFileSystem() && !virtualFile.getPath().contains("/node_modules/");
    }

    /** Declarations worth a counter: classes, functions, methods, class fields (not locals, parameters, object literal keys). */
    @Override
    public boolean acceptsElement(@NotNull PsiElement element) {
        if (!(element instanceof PsiNamedElement named) || named.getName() == null) return false;
        String kind = element.getClass().getSimpleName();
        return kind.contains("Function") || kind.contains("Method") || kind.contains("Class") || kind.contains("Field");
    }

    @Override
    public @Nullable String getHint(@NotNull PsiElement element, @NotNull PsiFile file) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        Document document = virtualFile == null ? null : FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document == null) return null;
        int nameOffset = element.getTextOffset();
        CompletableFuture<List<TsLanguageService.Reference>> future =
                TsLanguageService.getInstance(element.getProject()).references(virtualFile, document, nameOffset);
        if (future == null) return null;
        List<TsLanguageService.Reference> references = await(future);
        if (references == null) return null;
        long usages = references.stream().filter(r -> !(r.file().equals(virtualFile.toNioPath())
                && TsEdits.offset(document, r.range().line(), r.range().character()) == nameOffset)).count();
        return usages == 0 ? "no usages" : usages == 1 ? "1 usage" : usages + " usages";
    }

    private static @Nullable List<TsLanguageService.Reference> await(@NotNull CompletableFuture<List<TsLanguageService.Reference>> future) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
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
