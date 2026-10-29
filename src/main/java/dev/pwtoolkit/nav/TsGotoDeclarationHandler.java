package dev.pwtoolkit.nav;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ctrl+Click / Go to Declaration for TypeScript and JavaScript, answered by the TypeScript language server.
 * Registered with order="first"; returns null when the server has no answer, so the IDE's own handlers still run.
 */
public final class TsGotoDeclarationHandler implements GotoDeclarationHandler {
    private static final Logger LOG = Logger.getInstance(TsGotoDeclarationHandler.class);

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null || editor == null) return null;
        PwSettings.StateBean settings = PwSettings.getInstance().getState();
        if (!settings.navigationEnabled) return null;
        PsiFile psiFile = sourceElement.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file)) return null;

        Project project = sourceElement.getProject();
        List<TsLanguageService.Location> locations;
        try {
            CompletableFuture<List<TsLanguageService.Location>> future =
                    ApplicationManager.getApplication().runReadAction((Computable<CompletableFuture<List<TsLanguageService.Location>>>) () -> TsLanguageService.getInstance(project).definition(file, editor.getDocument(), offset));
            if (future == null) return null;
            locations = await(future, settings.navigationTimeoutMs);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            LOG.debug("Definition request failed", e);
            return null;
        }
        if (locations != null && locations.isEmpty()) locations = playwrightMembers(file, editor.getDocument(), offset);
        if (locations == null || locations.isEmpty()) return null;

        List<PsiElement> targets = new ArrayList<>();
        for (TsLanguageService.Location location : locations) {
            PsiElement target = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> toElement(project, location));
            if (target != null) targets.add(target);
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    /** TypeScript knows nothing about the receiver (an untyped parameter): Playwright's members with this name. */
    private static @NotNull List<TsLanguageService.Location> playwrightMembers(VirtualFile file, Document document, int offset) {
        CharSequence text = document.getImmutableCharSequence();
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) before--;
        if (start == end || before < 0 || text.charAt(before) != '.') return List.of();
        List<TsLanguageService.Location> locations = new ArrayList<>();
        for (PwApiIndex.Member member : PwApiIndex.find(file.toNioPath(), text.subSequence(start, end).toString())) {
            locations.add(new TsLanguageService.Location(member.file(), member.line(), member.character()));
        }
        return locations;
    }

    private static @Nullable List<TsLanguageService.Location> await(CompletableFuture<List<TsLanguageService.Location>> future, int timeoutMs)
            throws ExecutionException, InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 200);
        while (true) {
            ProgressManager.checkCanceled();
            try {
                return future.get(20, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.currentTimeMillis() > deadline) return null;
            }
        }
    }

    static @Nullable PsiElement toElement(Project project, TsLanguageService.Location location) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(location.file());
        if (file == null) return null;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (document == null || psiFile == null) return null;
        if (location.line() >= document.getLineCount()) return psiFile;
        int lineStart = document.getLineStartOffset(location.line());
        int offset = Math.min(lineStart + location.character(), document.getLineEndOffset(location.line()));
        PsiElement element = psiFile.findElementAt(offset);
        return element != null ? element : psiFile;
    }
}
