package dev.pwtoolkit.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Gutter Run/Debug icons next to {@code test(...)} and {@code test.describe(...)}. */
public final class PwTestLineMarkerContributor extends RunLineMarkerContributor {
    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        // line markers must be registered on leaf elements (the platform reports composite ones as a performance problem)
        if (element.getFirstChild() != null || element.getTextLength() != 4 || !"test".equals(element.getText())) return null;
        PsiFile file = element.getContainingFile();
        if (file == null || !PwTestDetector.isTestFile(file.getVirtualFile())) return null;
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document == null) return null;
        PwTestDetector.TestCall call = PwTestDetector.parseCall(document, element.getTextRange().getStartOffset());
        if (call == null) return null;
        // ✓/✗ of the last run, recorded by the test tree under the same pwtest:// URL the reporter prints
        String url = dev.pwtoolkit.tests.PwTestLocator.url(file.getVirtualFile().getPath(), call.line() + 1);
        javax.swing.Icon icon = getTestStateIcon(url, element.getProject(), call.suite());
        return new Info(icon,
                ExecutorAction.getActions(0), e -> "Run/Debug '" + call.title() + "'");
    }
}
