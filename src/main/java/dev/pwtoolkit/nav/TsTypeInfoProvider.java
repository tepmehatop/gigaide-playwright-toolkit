package dev.pwtoolkit.nav;

import com.intellij.lang.ExpressionTypeProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Type Info (Ctrl+Shift+P): the type of the name under the caret, from the TypeScript server. */
public final class TsTypeInfoProvider extends ExpressionTypeProvider<PsiElement> {
    @Override
    public @NotNull String getInformationHint(@NotNull PsiElement element) {
        TsLanguageService.QuickInfo info = quickInfo(element);
        if (info == null || info.signature() == null) return getErrorHint();
        return StringUtil.escapeXmlEntities(typeOf(info.signature(), element.getText()));
    }

    @Override
    public @NotNull String getErrorHint() {
        return "No type information";
    }

    @Override
    public @NotNull List<PsiElement> getExpressionsAt(@NotNull PsiElement elementAt) {
        VirtualFile file = elementAt.getContainingFile() == null ? null : elementAt.getContainingFile().getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file) || elementAt.getFirstChild() != null || !elementAt.getText().matches("[A-Za-z_$][\\w$]*")) return List.of();
        return List.of(elementAt);
    }

    /**
     * "(parameter) page: Page" -> "Page", "const user: { login: string }" -> "{ login: string }",
     * "(method) Page.fill(selector: string, ...): Promise<void>" stays a signature.
     */
    static @NotNull String typeOf(@NotNull String signature, @NotNull String name) {
        String text = signature.replaceFirst("^\\([^)]*\\)\\s*", "").replaceFirst("^(const|let|var|function|class|interface|type|enum|import|module|namespace)\\s+", "");
        if (text.startsWith(name + ": ")) return text.substring(name.length() + 2);
        if (text.startsWith(name + "?: ")) return text.substring(name.length() + 3) + " | undefined";
        return signature;
    }

    private static @Nullable TsLanguageService.QuickInfo quickInfo(@NotNull PsiElement element) {
        VirtualFile file = element.getContainingFile().getOriginalFile().getVirtualFile();
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return null;
        CompletableFuture<TsLanguageService.QuickInfo> future =
                TsLanguageService.getInstance(element.getProject()).quickInfo(file, document, element.getTextRange().getStartOffset());
        if (future == null) return null;
        long deadline = System.currentTimeMillis() + 2000;
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
