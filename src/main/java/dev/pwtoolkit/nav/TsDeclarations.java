package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Maps positions to the IDE's declaration elements with the TypeScript server, for the platform features that work
 * with PSI targets: Show Usages, highlighting of usages under the caret, Quick Definition, Ctrl+Click on a declaration.
 * Must be called inside a read action.
 */
final class TsDeclarations {
    /** Answers per document version and offset: the same position is asked several times per caret move. */
    private static final Key<Map<Integer, Location>> CACHE = Key.create("pwtoolkit.declarations");

    private record Location(long stamp, @Nullable TsLanguageService.Location location) {
    }

    private TsDeclarations() {
    }

    /** The named element declared at the definition of the symbol at {@code offset}, or null. */
    static @Nullable PsiNamedElement declarationAt(@NotNull Project project, @NotNull VirtualFile file, int offset, long timeoutMs) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || !JsFiles.isScript(file)) return null;
        TsLanguageService.Location location = definition(project, file, document, offset, timeoutMs);
        return location == null ? null : namedElementAt(project, location);
    }

    private static @Nullable TsLanguageService.Location definition(@NotNull Project project, @NotNull VirtualFile file, @NotNull Document document,
                                                                  int offset, long timeoutMs) {
        Map<Integer, Location> cache = document.getUserData(CACHE);
        long stamp = document.getModificationStamp();
        if (cache != null) {
            Location cached = cache.get(offset);
            if (cached != null && cached.stamp() == stamp) return cached.location();
        }
        CompletableFuture<List<TsLanguageService.Location>> future = TsLanguageService.getInstance(project).definition(file, document, offset);
        if (future == null) return null;
        List<TsLanguageService.Location> locations = await(future, timeoutMs);
        if (locations == null) return null; // timeout: ask again next time
        TsLanguageService.Location location = locations.isEmpty() ? null : locations.get(0);
        if (cache == null || cache.size() > 200) {
            cache = new ConcurrentHashMap<>();
            document.putUserData(CACHE, cache);
        }
        cache.put(offset, new Location(stamp, location));
        return location;
    }

    /** The element whose name starts at the location (a function, method, field, property, class, variable, parameter). */
    static @Nullable PsiNamedElement namedElementAt(@NotNull Project project, @NotNull TsLanguageService.Location location) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(location.file());
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
        if (document == null || psiFile == null || location.line() >= document.getLineCount()) return null;
        int offset = TsEdits.offset(document, location.line(), location.character());
        PsiElement leaf = psiFile.findElementAt(offset);
        int depth = 0;
        for (PsiElement element = leaf; element != null && !(element instanceof PsiFile) && depth < 6; element = element.getParent(), depth++) {
            if (element instanceof PsiNamedElement named && element.getTextOffset() == offset) return named;
        }
        return null;
    }

    private static @Nullable List<TsLanguageService.Location> await(@NotNull CompletableFuture<List<TsLanguageService.Location>> future, long timeoutMs) {
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
                return List.of();
            }
        }
    }
}
