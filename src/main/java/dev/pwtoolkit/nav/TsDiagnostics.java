package dev.pwtoolkit.nav;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Errors and warnings of the TypeScript server in the editor, with its quick fixes on Alt+Enter ("Add import from ...").
 * GigaIDE's JavaScript plugin does not report unresolved names or type errors.
 */
final class TsDiagnostics {
    /** Our own highlighter group: the IDE's passes leave it alone. */
    static final int GROUP = 0x5057_544B;
    /** TypeScript reports unused declarations as suggestions ("reportsUnnecessary"): shown grayed out like in VS Code. */
    private static final Set<Integer> UNUSED = Set.of(6133, 6138, 6192, 6196, 6198, 6199, 6205);
    private static final Set<Integer> DEPRECATED = Set.of(6385, 6387);

    private TsDiagnostics() {
    }

    /** A diagnostic with the fixes the server offers for it. */
    record Problem(@NotNull TsLanguageService.Diagnostic diagnostic, @NotNull List<TsLanguageService.Fix> fixes) {
    }

    static boolean isShown(@NotNull TsLanguageService.Diagnostic diagnostic) {
        return !diagnostic.severity().equals("suggestion") || UNUSED.contains(diagnostic.code()) || DEPRECATED.contains(diagnostic.code());
    }

    /** On the EDT. */
    static void apply(@NotNull Project project, @NotNull Document document, @NotNull List<Problem> problems) {
        List<HighlightInfo> infos = new ArrayList<>();
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
            HighlightInfoType type = d.severity().equals("error") ? HighlightInfoType.ERROR
                    : d.severity().equals("warning") ? HighlightInfoType.WARNING
                    : DEPRECATED.contains(d.code()) ? HighlightInfoType.DEPRECATED : HighlightInfoType.UNUSED_SYMBOL;
            String message = "TS" + d.code() + ": " + d.message();
            HighlightInfo.Builder builder = HighlightInfo.newHighlightInfo(type).range(start, end).descriptionAndTooltip(message);
            for (TsLanguageService.Fix fix : problem.fixes()) {
                builder.registerFix(new FixAction(fix), null, null, null, null);
            }
            HighlightInfo info = builder.create();
            if (info != null) infos.add(info);
        }
        UpdateHighlightersUtil.setHighlightersToEditor(project, document, 0, document.getTextLength(), infos,
                EditorColorsManager.getInstance().getGlobalScheme(), GROUP);
    }

    /** One quick fix of the server: its title and edits are known when the problem is shown. */
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
            TsEdits.apply(project, fix.title(), fix.edits());
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public @NotNull Priority getPriority() {
            return fix.title().startsWith("Add import") || fix.title().startsWith("Update import") ? Priority.TOP : Priority.NORMAL;
        }

        @Override
        public @Nullable com.intellij.codeInsight.intention.preview.IntentionPreviewInfo generatePreview(@NotNull Project project,
                @NotNull Editor editor, @NotNull PsiFile file) {
            return com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY;
        }
    }

    /**
     * Alt+Enter: the fixes of the problems under the caret. The IDE collects fixes only from highlighting of its own
     * passes, so they are added here.
     */
    static final class MenuContributor implements com.intellij.codeInsight.daemon.impl.IntentionMenuContributor {
        @Override
        public void collectActions(@NotNull Editor hostEditor, @NotNull PsiFile hostFile,
                                   com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.@NotNull IntentionsInfo intentions,
                                   int passIdToShowIntentionsFor, int offset) {
            Document document = hostEditor.getDocument();
            Set<String> present = new java.util.HashSet<>();
            for (var descriptor : intentions.errorFixesToShow) present.add(descriptor.getAction().getText());
            for (Problem problem : TsSemanticHighlighter.shownProblems(document)) {
                TsLanguageService.Range r = problem.diagnostic().range();
                int start = TsEdits.offset(document, r.line(), r.character());
                int end = Math.max(TsEdits.offset(document, r.endLine(), r.endCharacter()), start + 1);
                if (offset < start || offset > end) continue;
                for (TsLanguageService.Fix fix : problem.fixes()) {
                    if (!present.add(fix.title())) continue;
                    intentions.errorFixesToShow.add(new HighlightInfo.IntentionActionDescriptor(new FixAction(fix), null, null, null, null, null,
                            com.intellij.lang.annotation.HighlightSeverity.ERROR));
                }
            }
        }
    }

    static @NotNull Map<String, Integer> counts(@NotNull List<Problem> problems) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Problem p : problems) counts.merge(p.diagnostic().severity(), 1, Integer::sum);
        return counts;
    }
}
