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
        com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document);
        String lineText = document.getText().substring(lineStart, lineEnd).strip();

        // details: every highlighter on the caret line (ours and the IDE's)
        StringBuilder details = new StringBuilder();
        for (String id : new String[]{"TypeScript", "JavaScript"}) {
            Language language = Language.findLanguageByID(id);
            details.append("rainbow[").append(id).append("]=")
                    .append(language == null ? "no language" : RainbowHighlighter.isRainbowEnabledWithInheritance(scheme, language)).append(' ');
        }
        details.append('\n');
        List<RangeHighlighter> ours = TsSemanticHighlighter.appliedHighlighters(document);
        List<String[]> rows = new ArrayList<>();
        collect("doc", DocumentMarkupModel.forDocument(document, project, false), lineStart, lineEnd, scheme, ours, document, rows);
        collect("editor", editor.getMarkupModel(), lineStart, lineEnd, scheme, ours, document, rows);
        rows.sort(Comparator.comparingInt(r -> Integer.parseInt(r[0])));
        for (String[] row : rows) details.append(row[1]).append('\n');

        int caret = editor.getCaretModel().getOffset();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> summary = summary(project, file, document, line + 1, lineText, scheme);
            if (file != null) summary.add(2, gotoAtCaret(project, file, document, caret));
            String report = "[pwtoolkit] highlighting diagnostics\nSUMMARY\n" + String.join("\n", summary) + "\nDETAILS\n" + details;
            LOG.info(report);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                CopyPasteManager.getInstance().setContents(new StringSelection(report));
                // shown in a dialog too: in closed networks the report is retyped by hand, the summary is enough
                com.intellij.openapi.ui.Messages.showInfoMessage(project, String.join("\n", summary)
                        + "\n\n(The full report is in the clipboard and in idea.log.)", "Playwright Toolkit: Highlighting Diagnostics");
            });
        });
    }

    /** Short numbered lines, meant to be retyped by hand from a closed network. */
    static List<String> summary(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document, int line,
                                String lineText, EditorColorsScheme scheme) {
        List<String> lines = new ArrayList<>();
        var app = com.intellij.openapi.application.ApplicationInfo.getInstance();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("dev.pwtoolkit.gigaide"));
        lines.add("IDE: " + app.getFullApplicationName() + " " + app.getBuild() + " | " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch") + " | plugin " + (plugin == null ? "?" : plugin.getVersion()));
        lines.add("file: " + (file == null ? "?" : file.getName()) + " line " + line + ": " + lineText);
        String stats = TsSemanticHighlighter.stats(document);
        lines.add("highlight: " + (stats == null ? "not run for this file yet" : stats));
        if (file != null) lines.addAll(TsLanguageService.getInstance(project).describe(file));
        if (file != null) {
            java.nio.file.Path root = TsLanguageService.findRoot(file);
            var mode = TsServerLocator.backend();
            boolean nativeUsed = mode == TsServerLocator.Backend.AUTO || mode == TsServerLocator.Backend.NATIVE;
            java.nio.file.Path exe = root == null || !nativeUsed ? null : TsServerLocator.find(root);
            if (exe != null) lines.add("TS7 run: " + runVersion(exe));
        }
        lines.add("node: " + dev.pwtoolkit.util.NodeLocator.findNode(null));
        lines.add("scheme: " + scheme.getName() + " | semantic=" + PwSettings.getInstance().getState().semanticHighlighting
                + " brackets=" + PwSettings.getInstance().getState().rainbowBrackets);
        return lines;
    }

    /** Ctrl+Click on the word at the caret: what the server answers and what the IDE gets from it. */
    static String gotoAtCaret(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document, int caret) {
        CharSequence text = document.getImmutableCharSequence();
        int start = caret;
        int end = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        String word = text.subSequence(start, end).toString();
        long started = System.currentTimeMillis();
        try {
            java.util.concurrent.CompletableFuture<List<TsLanguageService.Location>> future = com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<java.util.concurrent.CompletableFuture<List<TsLanguageService.Location>>>)
                            () -> TsLanguageService.getInstance(project).definition(file, document, caret));
            if (future == null) return "goto '" + word + "': no TypeScript server";
            List<TsLanguageService.Location> locations = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            long ms = System.currentTimeMillis() - started;
            if (locations.isEmpty()) return "goto '" + word + "': server found nothing (" + ms + " ms)";
            TsLanguageService.Location first = locations.get(0);
            com.intellij.psi.PsiElement target = com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<com.intellij.psi.PsiElement>) () -> TsGotoDeclarationHandler.toElement(project, first));
            String where = first.file().getFileName() + ":" + (first.line() + 1);
            return "goto '" + word + "': " + locations.size() + " target(s), " + where + ", " + ms + " ms"
                    + (ms > PwSettings.getInstance().getState().navigationTimeoutMs ? " SLOWER THAN TIMEOUT" : "")
                    + (target == null ? ", IDE CANNOT OPEN " + first.file() : "");
        } catch (Exception e) {
            return "goto '" + word + "': failed " + e;
        }
    }

    /** {@code <tsc> --version}: shows "Permission denied", missing libraries, wrong architecture... */
    private static String runVersion(java.nio.file.Path exe) {
        try {
            var output = new com.intellij.execution.process.CapturingProcessHandler(
                    new com.intellij.execution.configurations.GeneralCommandLine(exe.toString(), "--version")).runProcess(15_000);
            String text = (output.getStdout() + " " + output.getStderr()).strip().replace('\n', ' ');
            return "exit " + output.getExitCode() + (output.isTimeout() ? " (timeout)" : "") + ": " + text.substring(0, Math.min(200, text.length()));
        } catch (Exception e) {
            return "cannot start: " + e.getMessage();
        }
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
