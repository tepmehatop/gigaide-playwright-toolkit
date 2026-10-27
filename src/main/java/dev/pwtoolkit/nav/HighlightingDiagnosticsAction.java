package dev.pwtoolkit.nav;

import com.intellij.codeHighlighting.RainbowHighlighter;
import com.intellij.lang.Language;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Playwright Toolkit: Diagnose Highlighting": dumps every highlighter on the caret line (ours and the IDE's),
 * with layers and resolved colors, to the clipboard and idea.log.
 */
public final class HighlightingDiagnosticsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(HighlightingDiagnosticsAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) return;
        Document document = editor.getDocument();
        int line = document.getLineNumber(editor.getCaretModel().getOffset());
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        EditorColorsScheme scheme = editor.getColorsScheme();
        StringBuilder out = new StringBuilder();
        out.append("[pwtoolkit] highlighting diagnostics, line ").append(line + 1).append(": ")
                .append(document.getText().substring(lineStart, lineEnd).strip()).append('\n');
        out.append("scheme=").append(scheme.getName())
                .append(" defaultFg=").append(scheme.getDefaultForeground())
                .append(" settings.semantic=").append(PwSettings.getInstance().getState().semanticHighlighting)
                .append(" settings.rainbowBrackets=").append(PwSettings.getInstance().getState().rainbowBrackets).append('\n');
        for (String id : new String[]{"TypeScript", "JavaScript"}) {
            Language language = Language.findLanguageByID(id);
            out.append("rainbow[").append(id).append("]=")
                    .append(language == null ? "no language" : RainbowHighlighter.isRainbowEnabledWithInheritance(scheme, language)).append(' ');
        }
        out.append("rainbow[default]=").append(RainbowHighlighter.isRainbowEnabled(scheme, null)).append('\n');
        List<RangeHighlighter> ours = TsSemanticHighlighter.appliedHighlighters(document);
        out.append("ours=").append(ours == null ? "none" : ours.size()).append('\n');

        List<String[]> rows = new ArrayList<>();
        collect("doc", DocumentMarkupModel.forDocument(document, project, false), lineStart, lineEnd, scheme, ours, document, rows);
        collect("editor", editor.getMarkupModel(), lineStart, lineEnd, scheme, ours, document, rows);
        rows.sort(Comparator.comparingInt(r -> Integer.parseInt(r[0])));
        for (String[] row : rows) out.append(row[1]).append('\n');

        String report = out.toString();
        LOG.info(report);
        CopyPasteManager.getInstance().setContents(new StringSelection(report));
        NotificationGroupManager.getInstance().getNotificationGroup("Playwright Toolkit")
                .createNotification("Highlighting diagnostics copied to the clipboard", "Paste it into the chat (it is also in idea.log).",
                        NotificationType.INFORMATION)
                .notify(project);
    }

    private static void collect(String model, MarkupModel markup, int start, int end, EditorColorsScheme scheme,
                                List<RangeHighlighter> ours, Document document, List<String[]> rows) {
        if (markup == null) return;
        for (RangeHighlighter h : markup.getAllHighlighters()) {
            if (!h.isValid() || h.getEndOffset() <= start || h.getStartOffset() >= end) continue;
            if (h.getEndOffset() - h.getStartOffset() > end - start + 2) continue; // whole-file/whole-line markers
            TextAttributesKey key = h.getTextAttributesKey();
            TextAttributes forced = h.getTextAttributes(scheme);
            TextAttributes resolved = key == null ? null : scheme.getAttributes(key);
            String text = document.getText().substring(h.getStartOffset(), h.getEndOffset());
            rows.add(new String[]{String.valueOf(h.getStartOffset()),
                    String.format("%-6s %-14s layer=%-5d %s key=%s keyFg=%s effectiveFg=%s font=%s", model,
                            "'" + text + "'", h.getLayer(), ours != null && ours.contains(h) ? "OURS" : "    ",
                            key == null ? null : key.getExternalName(),
                            resolved == null ? null : resolved.getForegroundColor(),
                            forced == null ? null : forced.getForegroundColor(),
                            forced == null ? null : forced.getFontType())});
        }
    }
}
