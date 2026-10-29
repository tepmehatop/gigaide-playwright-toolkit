package dev.pwtoolkit.nav;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Quick documentation (Ctrl+Q / F1, and on mouse hover) and Ctrl+hover info from the TypeScript server:
 * the type of the symbol under the caret and its JSDoc. The documented element is the identifier itself, because
 * GigaIDE cannot resolve most references (e.g. Playwright's API in node_modules).
 */
public final class TsDocumentationProvider implements DocumentationProvider {
    private static final long TIMEOUT_MS = 2000;

    @Override
    public @Nullable PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file, @Nullable PsiElement contextElement,
                                                            int targetOffset) {
        if (contextElement == null || !JsFiles.isScript(file.getOriginalFile().getVirtualFile())) return null;
        return isIdentifier(contextElement) ? contextElement : null;
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        TsLanguageService.QuickInfo info = quickInfo(element);
        if (info == null || info.signature() == null && info.documentation().isEmpty()) return null;
        StringBuilder html = new StringBuilder();
        if (info.signature() != null) {
            html.append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(info.signature())).append(DocumentationMarkup.DEFINITION_END);
        }
        if (!info.documentation().isEmpty()) {
            html.append(DocumentationMarkup.CONTENT_START)
                    .append(StringUtil.escapeXmlEntities(info.documentation()).replace("\n\n", "<p>").replace("\n", "<br>"))
                    .append(DocumentationMarkup.CONTENT_END);
        }
        return html.toString();
    }

    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        TsLanguageService.QuickInfo info = quickInfo(originalElement != null && isIdentifier(originalElement) ? originalElement : element);
        return info == null || info.signature() == null ? null : StringUtil.escapeXmlEntities(info.signature());
    }

    private static @Nullable TsLanguageService.QuickInfo quickInfo(@Nullable PsiElement element) {
        if (element == null || !element.isValid() || !isIdentifier(element)) return null;
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(virtualFile)) return null;
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document == null) return null;
        CompletableFuture<TsLanguageService.QuickInfo> future =
                TsLanguageService.getInstance(element.getProject()).quickInfo(virtualFile, document, element.getTextRange().getStartOffset());
        if (future == null) return null;
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

    private static boolean isIdentifier(@NotNull PsiElement element) {
        return element.getFirstChild() == null && element.getText().matches("[A-Za-z_$][\\w$]*");
    }
}
