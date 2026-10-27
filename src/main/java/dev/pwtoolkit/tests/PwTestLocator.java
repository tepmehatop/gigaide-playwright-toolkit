package dev.pwtoolkit.tests;

import com.intellij.execution.Location;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Resolves {@code pwtest:///abs/path/file.test.ts:LINE} location hints printed by pwtk-reporter.cjs. */
public final class PwTestLocator implements SMTestLocator {
    public static final String PROTOCOL = "pwtest";
    public static final PwTestLocator INSTANCE = new PwTestLocator();

    /** The location URL the reporter prints for a test or describe declared on a 1-based line. */
    public static @NotNull String url(@NotNull String path, int line) {
        return PROTOCOL + "://" + path + (line > 0 ? ":" + line : "");
    }

    /** {@code /abs/file.ts:12} → path and 1-based line (0 when absent). */
    public record FileLine(@NotNull String path, int line) {
        public static @NotNull FileLine parse(@NotNull String path) {
            int colon = path.lastIndexOf(':');
            if (colon > 0 && colon < path.length() - 1 && path.substring(colon + 1).chars().allMatch(Character::isDigit)) {
                return new FileLine(path.substring(0, colon), Integer.parseInt(path.substring(colon + 1)));
            }
            return new FileLine(path, 0);
        }
    }

    @Override
    public @NotNull List<Location> getLocation(@NotNull String protocol, @NotNull String path, @NotNull Project project,
                                               @NotNull GlobalSearchScope scope) {
        if (!PROTOCOL.equals(protocol)) return List.of();
        FileLine target = FileLine.parse(path);
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(target.path());
        PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) return List.of();
        PsiElement element = elementAt(psiFile, target.line());
        return List.of(PsiLocation.fromPsiElement(project, element != null ? element : psiFile));
    }

    private static @Nullable PsiElement elementAt(@NotNull PsiFile psiFile, int line) {
        Document document = FileDocumentManager.getInstance().getDocument(psiFile.getVirtualFile());
        if (document == null || line <= 0 || line > document.getLineCount()) return null;
        int start = document.getLineStartOffset(line - 1);
        int end = document.getLineEndOffset(line - 1);
        CharSequence text = document.getCharsSequence();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        return psiFile.findElementAt(start);
    }
}
