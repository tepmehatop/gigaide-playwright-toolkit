package dev.pwtoolkit.nav;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Semantic highlighting (fixtures, Page Object fields and methods, parameters...) from the TypeScript 7 server.
 * <p>
 * Applied directly to the document markup instead of through an annotator: annotators only run when the IDE's
 * analysis of the file completes, and in GigaIDE it never completes for *.test.ts / *.spec.ts files
 * (its NodeJS plugin's Jest line marker fails there on every pass).
 */
@Service(Service.Level.PROJECT)
public final class TsSemanticHighlighter implements Disposable {
    private static final Logger LOG = Logger.getInstance(TsSemanticHighlighter.class);
    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS = Key.create("pwtoolkit.semantic.highlighters");
    private static final int DELAY_MS = 300;

    private final Project project;
    private final Alarm alarm;

    public TsSemanticHighlighter(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        // colors are picked per scheme, so re-apply when the user switches it
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC, scheme -> scheduleOpenFiles());
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                // a change in a Page Object may change how tests are highlighted, so refresh every open script
                if (JsFiles.isScript(file) && FileEditorManager.getInstance(project).isFileOpen(file)) scheduleOpenFiles();
            }
        }, this);
    }


    public static @NotNull TsSemanticHighlighter getInstance(@NotNull Project project) {
        return project.getService(TsSemanticHighlighter.class);
    }

    public void scheduleOpenFiles() {
        alarm.cancelAllRequests();
        alarm.addRequest(() -> {
            for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
                if (JsFiles.isScript(file)) highlight(file);
            }
        }, DELAY_MS);
    }

    /** Requests tokens (blocking, call off the EDT in production) and applies them on the EDT. */
    public void highlight(@NotNull VirtualFile file) {
        if (project.isDisposed()) return;
        Document document = ApplicationManager.getApplication().runReadAction(
                (Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(file));
        if (document == null) return;
        if (!PwSettings.getInstance().getState().semanticHighlighting) {
            ApplicationManager.getApplication().invokeLater(() -> apply(document, document.getModificationStamp(), List.of(), true), project.getDisposed());
            return;
        }
        long stamp = document.getModificationStamp();
        CharSequence text = document.getImmutableCharSequence();
        CompletableFuture<List<TsLanguageService.SemanticToken>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.SemanticToken>>>) () ->
                        TsLanguageService.getInstance(project).semanticTokens(file, document));
        if (future == null) return;
        List<TsLanguageService.SemanticToken> tokens;
        try {
            tokens = future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.info("[pwtoolkit] semantic highlighting: tokens request failed for " + file.getName(), e);
            return;
        }
        List<TsLanguageService.SemanticToken> allTokens = withImportBindings(text, tokens);
        boolean typeScript = isTypeScript(file);
        ApplicationManager.getApplication().invokeLater(() -> apply(document, stamp, allTokens, typeScript), project.getDisposed());
    }

    private void apply(@NotNull Document document, long stamp, @NotNull List<TsLanguageService.SemanticToken> tokens, boolean typeScript) {
        if (document.getModificationStamp() != stamp) return; // a newer request is on its way
        MarkupModel model = DocumentMarkupModel.forDocument(document, project, true);
        List<RangeHighlighter> old = document.getUserData(HIGHLIGHTERS);
        if (old != null) {
            for (RangeHighlighter highlighter : old) {
                if (highlighter.isValid()) model.removeHighlighter(highlighter);
            }
        }
        List<RangeHighlighter> added = new ArrayList<>();
        int length = document.getTextLength();
        for (TsLanguageService.SemanticToken token : tokens) {
            TextAttributesKey key = TsSemanticColors.keyFor(token, typeScript);
            int end = token.start() + token.length();
            if (key == null || end > length) continue;
            added.add(model.addRangeHighlighter(key, token.start(), end, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE));
        }
        document.putUserData(HIGHLIGHTERS, added);
        LOG.debug("[pwtoolkit] semantic highlighting: " + added.size() + " ranges in " + FileDocumentManager.getInstance().getFile(document));
    }

    private static final java.util.regex.Pattern IMPORT_CLAUSE =
            java.util.regex.Pattern.compile("(?m)^\\s*import\\s+((?:type\\s+)?[^'\"`;]*?)\\s+from\\s*['\"]");
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*");

    /**
     * The TypeScript 7 server returns no tokens for names in `import ... from` clauses; WebStorm colors them like
     * their usages. Give each imported name the token type of its first usage in the file.
     */
    static @NotNull List<TsLanguageService.SemanticToken> withImportBindings(@NotNull CharSequence text,
                                                                           @NotNull List<TsLanguageService.SemanticToken> tokens) {
        java.util.Map<Integer, TsLanguageService.SemanticToken> byStart = new java.util.HashMap<>();
        java.util.Map<String, TsLanguageService.SemanticToken> byName = new java.util.HashMap<>();
        for (TsLanguageService.SemanticToken token : tokens) {
            byStart.put(token.start(), token);
            byName.putIfAbsent(text.subSequence(token.start(), token.start() + token.length()).toString(), token);
        }
        List<TsLanguageService.SemanticToken> result = new ArrayList<>(tokens);
        java.util.regex.Matcher clause = IMPORT_CLAUSE.matcher(text);
        while (clause.find()) {
            java.util.regex.Matcher name = IDENTIFIER.matcher(text).region(clause.start(1), clause.end(1));
            while (name.find()) {
                String identifier = name.group();
                if (identifier.equals("type") || identifier.equals("as") || byStart.containsKey(name.start())) continue;
                TsLanguageService.SemanticToken usage = byName.get(identifier);
                if (usage != null) {
                    result.add(new TsLanguageService.SemanticToken(name.start(), identifier.length(), usage.type(), usage.modifiers()));
                }
            }
        }
        return result;
    }

    /** Ranges currently applied to the document (for tests and diagnostics). */
    public static @Nullable List<RangeHighlighter> appliedHighlighters(@NotNull Document document) {
        return document.getUserData(HIGHLIGHTERS);
    }

    private static boolean isTypeScript(@NotNull VirtualFile file) {
        String name = StringUtil.toLowerCase(file.getName());
        return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".mts") || name.endsWith(".cts");
    }

    @Override
    public void dispose() {
    }
}
