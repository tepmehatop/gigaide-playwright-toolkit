package dev.pwtoolkit.nav;

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Go to Type Declaration (Cmd+Shift+B / Ctrl+Shift+B): page -> interface Page, loginPage -> class LoginPage. */
public final class TsTypeDeclarationProvider implements TypeDeclarationProvider {
    @Override
    public PsiElement @Nullable [] getSymbolTypeDeclarations(@NotNull PsiElement symbol) {
        if (!PwSettings.getInstance().getState().navigationEnabled) return null;
        PsiFile psiFile = symbol.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file) || !file.isInLocalFileSystem()) return null;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return null;
        CompletableFuture<List<TsLanguageService.Location>> future =
                TsLanguageService.getInstance(symbol.getProject()).typeDefinition(file, document, symbol.getTextOffset());
        if (future == null) return null;
        List<TsLanguageService.Location> locations = await(future);
        if (locations == null || locations.isEmpty()) return null;
        List<PsiElement> targets = new ArrayList<>();
        for (TsLanguageService.Location location : locations) {
            PsiElement named = TsDeclarations.namedElementAt(symbol.getProject(), location);
            PsiElement target = named != null ? named : TsGotoDeclarationHandler.toElement(symbol.getProject(), location);
            if (target != null) targets.add(target);
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    private static @Nullable List<TsLanguageService.Location> await(@NotNull CompletableFuture<List<TsLanguageService.Location>> future) {
        long deadline = System.currentTimeMillis() + 3000;
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
