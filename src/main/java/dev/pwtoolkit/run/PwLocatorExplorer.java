package dev.pwtoolkit.run;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import dev.pwtoolkit.debug.PwDebugProcess;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explore locators: the locator under the caret is highlighted in the browser kept open by "Show Browser"
 * (Playwright's debug controller), like hovering locators in the VS Code extension.
 */
@Service(Service.Level.PROJECT)
public final class PwLocatorExplorer implements Disposable {
    private static final Logger LOG = Logger.getInstance(PwLocatorExplorer.class);
    private final Project project;
    private final Alarm alarm;
    private volatile String lastHighlighted;

    public PwLocatorExplorer(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() != project) return;
                alarm.cancelAllRequests();
                alarm.addRequest(() -> onCaret(editor), 250);
            }
        }, this);
    }

    public static @NotNull PwLocatorExplorer getInstance(@NotNull Project project) {
        return project.getService(PwLocatorExplorer.class);
    }

    private void onCaret(@NotNull Editor editor) {
        if (project.isDisposed() || editor.isDisposed() || !PwSettings.getInstance().getState().exploreLocators) return;
        PwDebugController controller = PwBrowserServer.getInstance(project).controller();
        if (controller == null || controller.pageCount() == 0) return;
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (!JsFiles.isScript(file)) return;
        int offset = ApplicationManager.getApplication().runReadAction((Computable<Integer>) () -> editor.getCaretModel().getOffset());
        String locator = locatorAt(file, document, offset);
        try {
            if (locator != null) {
                if (!locator.equals(lastHighlighted)) controller.highlight(locator).get(5, TimeUnit.SECONDS);
            } else if (lastHighlighted != null) {
                controller.hideHighlight().get(5, TimeUnit.SECONDS);
            }
            lastHighlighted = locator;
        } catch (Exception e) {
            LOG.debug("Cannot highlight " + locator, e);
            lastHighlighted = null;
        }
    }

    /** Locator code for the highlight, or null when the caret is not on a locator. Runs off the EDT. */
    public @Nullable String locatorAt(@NotNull VirtualFile file, @NotNull Document document, int offset) {
        CharSequence text = ApplicationManager.getApplication().runReadAction((Computable<CharSequence>) document::getImmutableCharSequence);
        PwLocatorExpressions.Target target = PwLocatorExpressions.at(text, offset);
        if (target == null) return null;
        // paused in the debugger: the frame knows the real values (variables, Page Object fields...)
        PwDebugProcess paused = pausedPlaywrightDebugger();
        if (paused != null && target.kind() != PwLocatorExpressions.Kind.SELECTOR) {
            String expression = target.kind() == PwLocatorExpressions.Kind.PAGE_LOCATOR ? "page." + target.text() : target.text();
            String evaluated = paused.evaluateLocator(expression);
            if (evaluated != null) return evaluated;
        }
        return switch (target.kind()) {
            case SELECTOR -> PwDebugController.locatorFor(target.text());
            case PAGE_LOCATOR -> resolvePageLocator(file, document, text, offset, target.text());
            case EXPRESSION -> resolveSelectorReference(file, document, text, offset, target.text());
        };
    }

    /** {@code locator(mainPage.selectors.x)} → {@code locator('<value of x>')}; literal-only chains are used as is. */
    private @Nullable String resolvePageLocator(VirtualFile file, Document document, CharSequence text, int offset, String code) {
        Matcher reference = Pattern.compile("^locator\\(\\s*([\\w$]+(?:\\.[\\w$]+)+)\\s*\\)(.*)$", Pattern.DOTALL).matcher(code);
        if (reference.matches()) {
            int at = indexNear(text, offset, reference.group(1));
            String selector = at < 0 ? null : selectorValue(file, document, at + reference.group(1).length() - 1);
            return selector == null ? null : PwDebugController.locatorFor(selector) + reference.group(2);
        }
        // anything referring to variables cannot be resolved without the debugger
        return code.replaceAll("(['\"`])(?:\\\\.|(?!\\1).)*\\1", "''").matches("(?s).*[(,]\\s*[A-Za-z_$][\\w$]*\\s*(?:[.,)]).*") ? null : code;
    }

    /** {@code mainPage.selectors.heroSection} → the string assigned to heroSection (found with TypeScript 7). */
    private @Nullable String resolveSelectorReference(VirtualFile file, Document document, CharSequence text, int offset, String expression) {
        int at = indexNear(text, offset, expression);
        String selector = at < 0 ? null : selectorValue(file, document, at + expression.length() - 1);
        return selector == null ? null : PwDebugController.locatorFor(selector);
    }

    /** Start of the occurrence of {@code needle} closest to the caret (the chain is on the caret line). */
    private static int indexNear(CharSequence text, int offset, String needle) {
        String s = text.toString();
        int best = -1;
        for (int i = s.indexOf(needle, Math.max(0, offset - 300)); i >= 0 && i <= offset + 300; i = s.indexOf(needle, i + 1)) {
            if (best < 0 || Math.abs(i - offset) < Math.abs(best - offset)) best = i;
        }
        return best;
    }

    /** Definition of the identifier ending at {@code offset}: `name: "value"` → value. */
    private @Nullable String selectorValue(VirtualFile file, Document document, int offset) {
        CompletableFuture<List<TsLanguageService.Location>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.Location>>>) () ->
                        TsLanguageService.getInstance(project).definition(file, document, offset));
        if (future == null) return null;
        try {
            for (TsLanguageService.Location location : future.get(5, TimeUnit.SECONDS)) {
                List<String> lines = Files.readAllLines(location.file());
                if (location.line() >= lines.size()) continue;
                Matcher value = Pattern.compile("^[\\w$]+\\s*[:=]\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1")
                        .matcher(lines.get(location.line()).substring(Math.min(location.character(), lines.get(location.line()).length())));
                if (value.find() && !value.group(2).contains("${")) return value.group(2);
            }
        } catch (Exception e) {
            LOG.debug(e);
        }
        return null;
    }

    private @Nullable PwDebugProcess pausedPlaywrightDebugger() {
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        return session != null && session.isSuspended() && session.getDebugProcess() instanceof PwDebugProcess process ? process : null;
    }

    @Override
    public void dispose() {
    }
}
