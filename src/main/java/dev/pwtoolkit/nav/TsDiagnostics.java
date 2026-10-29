package dev.pwtoolkit.nav;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Errors and warnings of the TypeScript server in the editor, with its quick fixes on Alt+Enter ("Add import from ...").
 * GigaIDE's JavaScript plugin does not report unresolved names or type errors.
 */
public final class TsDiagnostics {
    private static final Logger LOG = Logger.getInstance(TsDiagnostics.class);
    /** "problems {error=2}" for Diagnose Highlighting. */
    static final Key<String> STATS = Key.create("pwtoolkit.problems.stats");
    /** TypeScript reports unused declarations as suggestions ("reportsUnnecessary"): shown grayed out like in VS Code. */
    private static final Set<Integer> UNUSED = Set.of(6133, 6138, 6192, 6196, 6198, 6199, 6205);
    private static final Set<Integer> DEPRECATED = Set.of(6385, 6387);
    private static final int MAX_FIX_REQUESTS = 15;

    private TsDiagnostics() {
    }

    /** A diagnostic with the fixes for it. */
    record Problem(@NotNull TsLanguageService.Diagnostic diagnostic, @NotNull List<TsLanguageService.Fix> fixes) {
    }

    static boolean isShown(@NotNull TsLanguageService.Diagnostic diagnostic) {
        return !diagnostic.severity().equals("suggestion") || UNUSED.contains(diagnostic.code()) || DEPRECATED.contains(diagnostic.code());
    }

    /** Asks the server (blocking, off the EDT). Null when no server is available. */
    static @Nullable List<Problem> compute(@NotNull Project project, @NotNull VirtualFile file, @NotNull Document document) {
        TsLanguageService service = TsLanguageService.getInstance(project);
        CompletableFuture<List<TsLanguageService.Diagnostic>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.Diagnostic>>>) () -> service.diagnostics(file, document));
        if (future == null) return null;
        try {
            List<Problem> problems = new ArrayList<>();
            int requests = 0;
            for (TsLanguageService.Diagnostic diagnostic : future.get(15, TimeUnit.SECONDS)) {
                if (!isShown(diagnostic)) continue;
                List<TsLanguageService.Fix> fixes = new ArrayList<>();
                if (!diagnostic.severity().equals("suggestion") && diagnostic.code() > 0 && requests++ < MAX_FIX_REQUESTS) {
                    CompletableFuture<List<TsLanguageService.Fix>> fixFuture = ApplicationManager.getApplication().runReadAction(
                            (Computable<CompletableFuture<List<TsLanguageService.Fix>>>) () -> service.fixes(file, document, diagnostic));
                    try {
                        if (fixFuture != null) fixes.addAll(fixFuture.get(5, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        LOG.debug("Quick fixes request failed", e);
                    }
                }
                fixes.addAll(ApplicationManager.getApplication().runReadAction(
                        (Computable<List<TsLanguageService.Fix>>) () -> TsExtraFixes.forDiagnostic(file, document, diagnostic)));
                problems.add(new Problem(diagnostic, fixes));
            }
            return problems;
        } catch (Exception e) {
            LOG.info("[pwtoolkit] diagnostics request failed for " + file.getName(), e);
            return null;
        }
    }

    /** The IDE runs it after its own analysis: tooltips, the light bulb, Alt+Enter, F2 and the Problems view work as usual. */
    public static final class Annotator extends ExternalAnnotator<Annotator.Input, List<Problem>> {
        record Input(@NotNull Project project, @NotNull VirtualFile file, @NotNull Document document) {
        }

        @Override
        public @Nullable Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
            return collectInformation(file);
        }

        @Override
        public @Nullable Input collectInformation(@NotNull PsiFile file) {
            if (!PwSettings.getInstance().getState().diagnostics) return null;
            VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
            if (!JsFiles.isScript(virtualFile) || !virtualFile.isInLocalFileSystem()) return null;
            Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile);
            return document == null ? null : new Input(file.getProject(), virtualFile, document);
        }

        @Override
        public @Nullable List<Problem> doAnnotate(Input input) {
            List<Problem> problems = compute(input.project(), input.file(), input.document());
            input.document().putUserData(STATS, problems == null ? "problems: no TypeScript server" : "problems " + counts(problems));
            return problems;
        }

        @Override
        public void apply(@NotNull PsiFile file, @Nullable List<Problem> problems, @NotNull AnnotationHolder holder) {
            if (problems == null) return;
            Document document = file.getViewProvider().getDocument();
            if (document == null) return;
            for (Problem problem : problems) {
                TsLanguageService.Diagnostic d = problem.diagnostic();
                int start = TsEdits.offset(document, d.range().line(), d.range().character());
                int end = TsEdits.offset(document, d.range().endLine(), d.range().endCharacter());
                if (end <= start) {
                    // a zero-length range (e.g. "';' expected" at the end of a line): mark one character
                    if (start < document.getTextLength()) end = start + 1;
                    else if (start > 0) start--;
                    else continue;
                }
                HighlightSeverity severity = d.severity().equals("error") ? HighlightSeverity.ERROR
                        : d.severity().equals("warning") ? HighlightSeverity.WARNING : HighlightSeverity.WEAK_WARNING;
                var builder = holder.newAnnotation(severity, d.message() + " (TS" + d.code() + ")").range(new TextRange(start, end));
                if (UNUSED.contains(d.code())) builder = builder.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL);
                else if (DEPRECATED.contains(d.code())) builder = builder.highlightType(ProblemHighlightType.LIKE_DEPRECATED);
                for (TsLanguageService.Fix fix : problem.fixes()) builder = builder.withFix(new FixAction(fix));
                builder.create();
            }
        }
    }

    /** One quick fix: its title and edits are known when the problem is shown. */
    static final class FixAction implements IntentionAction, PriorityAction {
        private final TsLanguageService.Fix fix;

        FixAction(@NotNull TsLanguageService.Fix fix) {
            this.fix = fix;
        }

        @Override
        public @NotNull String getText() {
            return fix.title();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "TypeScript quick fix";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            TsEdits.applyInWriteAction(fix.edits());
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public @NotNull Priority getPriority() {
            return fix.title().startsWith("Add import") || fix.title().startsWith("Update import") || fix.title().startsWith("Annotate")
                    || fix.title().startsWith("Change") ? Priority.TOP : Priority.NORMAL;
        }

        @Override
        public @Nullable com.intellij.codeInsight.intention.preview.IntentionPreviewInfo generatePreview(@NotNull Project project,
                @NotNull Editor editor, @NotNull PsiFile file) {
            return com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY;
        }
    }

    static @NotNull Map<String, Integer> counts(@NotNull List<Problem> problems) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Problem p : problems) counts.merge(p.diagnostic().severity(), 1, Integer::sum);
        return counts;
    }
}
