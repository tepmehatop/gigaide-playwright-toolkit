package dev.pwtoolkit.completion;

import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Parameter info (Ctrl+P / Cmd+P, and on typing "("): the signatures of the call from the TypeScript server, e.g.
 * {@code selectFromDropDown(page: Page, name: string, locator: string)} with the current parameter in bold.
 * The owner is the "(" of the call; the current parameter is counted locally from the commas.
 */
public final class TsParameterInfoHandler implements ParameterInfoHandler<PsiElement, TsLanguageService.Signature> {
    private static final long TIMEOUT_MS = 2000;

    @Override
    public @Nullable PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        PsiFile file = context.getFile();
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(virtualFile)) return null;
        Document document = context.getEditor().getDocument();
        int paren = CallScanner.openParen(document.getImmutableCharSequence(), context.getOffset());
        if (paren < 0) return null;
        CompletableFuture<TsLanguageService.SignatureHelp> future =
                TsLanguageService.getInstance(file.getProject()).signatureHelp(virtualFile, document, paren + 1);
        TsLanguageService.SignatureHelp help = future == null ? null : await(future);
        if (help == null || help.signatures().isEmpty()) return null;
        context.setItemsToShow(help.signatures().toArray());
        return file.findElementAt(paren);
    }

    @Override
    public void showParameterInfo(@NotNull PsiElement element, @NotNull CreateParameterInfoContext context) {
        context.showHint(element, element.getTextRange().getStartOffset() + 1, this);
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        PsiElement owner = context.getParameterOwner();
        if (owner == null || !owner.isValid()) return null;
        int paren = CallScanner.openParen(context.getEditor().getDocument().getImmutableCharSequence(), context.getOffset());
        return paren == owner.getTextRange().getStartOffset() ? owner : null;
    }

    @Override
    public void updateParameterInfo(@NotNull PsiElement owner, @NotNull UpdateParameterInfoContext context) {
        CharSequence text = context.getEditor().getDocument().getImmutableCharSequence();
        context.setCurrentParameter(CallScanner.argumentIndex(text, owner.getTextRange().getStartOffset(), context.getOffset()));
    }

    @Override
    public void updateUI(TsLanguageService.Signature signature, @NotNull ParameterInfoUIContext context) {
        int index = context.getCurrentParameterIndex();
        int start = -1;
        int end = -1;
        if (index >= 0 && index < signature.parameters().size()) {
            start = signature.parameters().get(index)[0];
            end = signature.parameters().get(index)[1];
        }
        context.setupUIComponentPresentation(signature.label(), start, end, false, false, false, context.getDefaultParameterColor());
    }

    private static @Nullable TsLanguageService.SignatureHelp await(@NotNull CompletableFuture<TsLanguageService.SignatureHelp> future) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            ProgressManager.checkCanceled();
            try {
                return future.get(20, TimeUnit.MILLISECONDS);
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
