package dev.pwtoolkit.refactor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.template.TemplateBuilderImpl;
import com.intellij.codeInsight.template.impl.TextExpression;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.nav.TsEdits;
import dev.pwtoolkit.nav.TsLanguageService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript's refactorings (getApplicableRefactors / getEditsForRefactor of tsserver): Extract to constant, function,
 * method, field, Convert to async, Add braces... After an extraction the new name is renamed in place like in IDEA.
 */
public final class TsRefactorings {
    private TsRefactorings() {
    }

    /** One applicable refactoring action, e.g. "Extract Symbol" / "constant_scope_0" / "Extract to constant in enclosing scope". */
    public record Refactoring(@NotNull String refactor, @NotNull String action, @NotNull String description) {
    }

    /** Applicable refactorings for the range (offsets), or null when the server cannot be asked. Blocking, bounded by {@code timeoutMs}. */
    public static @Nullable List<Refactoring> applicable(@NotNull Project project, @NotNull VirtualFile file, @NotNull Document document,
                                                       int start, int end, long timeoutMs) {
        CompletableFuture<JsonObject> future = ApplicationManager.getApplication().runReadAction((Computable<CompletableFuture<JsonObject>>) () ->
                TsLanguageService.getInstance(project).tsserverRequest(file, document, "getApplicableRefactors", range(file, document, start, end)));
        JsonObject response = future == null ? null : await(future, timeoutMs);
        if (response == null || !(response.get("body") instanceof com.google.gson.JsonArray body)) return null;
        List<Refactoring> result = new ArrayList<>();
        for (JsonElement element : body) {
            JsonObject refactor = element.getAsJsonObject();
            if (!refactor.has("actions")) continue;
            for (JsonElement a : refactor.getAsJsonArray("actions")) {
                JsonObject action = a.getAsJsonObject();
                if (action.has("notApplicableReason")) continue;
                result.add(new Refactoring(refactor.get("name").getAsString(), action.get("name").getAsString(), action.get("description").getAsString()));
            }
        }
        return result;
    }

    /** Applies the refactoring; after an extraction the new name gets an in-place rename. On the EDT. */
    public static void perform(@NotNull Project project, @NotNull Editor editor, @NotNull VirtualFile file, int start, int end,
                               @NotNull Refactoring refactoring) {
        Document document = editor.getDocument();
        Map<String, Object> arguments = range(file, document, start, end);
        arguments.put("refactor", refactoring.refactor());
        arguments.put("action", refactoring.action());
        JsonObject response = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            CompletableFuture<JsonObject> future = ApplicationManager.getApplication().runReadAction((Computable<CompletableFuture<JsonObject>>) () ->
                    TsLanguageService.getInstance(project).tsserverRequest(file, document, "getEditsForRefactor", arguments));
            return future == null ? null : await(future, 30_000);
        }, refactoring.description(), true, project);
        if (response == null || !(response.get("body") instanceof JsonObject body)) return;
        List<TsLanguageService.Edit> edits = TsEditsAccess.changes(body.getAsJsonArray("edits"));
        if (edits.isEmpty()) return;
        TsEdits.apply(project, refactoring.description(), edits);
        PsiDocumentManager.getInstance(project).commitDocument(document);
        if (body.has("renameLocation") && body.has("renameFilename")
                && Path.of(body.get("renameFilename").getAsString()).equals(file.toNioPath())) {
            JsonObject location = body.getAsJsonObject("renameLocation");
            int offset = offset(document, location.get("line").getAsInt() - 1, location.get("offset").getAsInt() - 1);
            renameInPlace(project, editor, offset);
        }
    }

    /** All occurrences of the new name (TypeScript picks a fresh one: newLocal, newFunction...) become one editable template. */
    static void renameInPlace(@NotNull Project project, @NotNull Editor editor, int offset) {
        Document document = editor.getDocument();
        CharSequence text = document.getImmutableCharSequence();
        int end = offset;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        if (end == offset) return;
        String name = text.subSequence(offset, end).toString();
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (psiFile == null) return;
        TemplateBuilderImpl builder = new TemplateBuilderImpl(psiFile);
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(text);
        boolean first = true;
        while (matcher.find()) {
            // each occurrence is an identifier leaf of the committed PSI
            com.intellij.psi.PsiElement leaf = psiFile.findElementAt(matcher.start());
            if (leaf == null || !leaf.getTextRange().equals(new TextRange(matcher.start(), matcher.end()))) continue;
            if (first) {
                builder.replaceElement(leaf, "NAME", new TextExpression(name), true);
                first = false;
            } else {
                builder.replaceElement(leaf, "NAME_" + matcher.start(), "NAME", false);
            }
        }
        if (first) return;
        // inline over the whole file: the template engine puts the caret on the first occurrence
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Rename", null, () -> builder.run(editor, true));
    }

    static @NotNull Map<String, Object> range(@NotNull VirtualFile file, @NotNull Document document, int start, int end) {
        Map<String, Object> range = new HashMap<>();
        int startLine = document.getLineNumber(start);
        int endLine = document.getLineNumber(end);
        range.put("file", file.toNioPath().toString());
        range.put("startLine", startLine + 1);
        range.put("startOffset", start - document.getLineStartOffset(startLine) + 1);
        range.put("endLine", endLine + 1);
        range.put("endOffset", end - document.getLineStartOffset(endLine) + 1);
        return range;
    }

    private static int offset(@NotNull Document document, int line, int character) {
        if (line >= document.getLineCount()) return document.getTextLength();
        return Math.min(document.getLineStartOffset(line) + character, document.getLineEndOffset(line));
    }

    static @Nullable JsonObject await(@NotNull CompletableFuture<JsonObject> future, long timeoutMs) {
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

    /** The file of the editor, for the actions. */
    static @Nullable VirtualFile fileOf(@NotNull Editor editor) {
        return FileDocumentManager.getInstance().getFile(editor.getDocument());
    }
}
