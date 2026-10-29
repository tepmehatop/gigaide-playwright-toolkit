package dev.pwtoolkit.completion;

import com.intellij.codeInsight.hint.ParameterInfoController;
import com.intellij.codeInsight.hint.ShowParameterInfoHandler;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.Alarm;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/**
 * Shows parameter info without a shortcut when the caret is put inside the parentheses of a call (a click or the
 * arrow keys), and after "," when the hint was closed.
 */
@Service(Service.Level.PROJECT)
public final class TsParameterInfoAutoPopup implements Disposable {
    private static final int DELAY_MS = 300;
    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    /** Document version at the last caret move: a caret move with a new version is typing, not a click. */
    private long lastStamp = -1;

    public TsParameterInfoAutoPopup(@NotNull Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() != project) return;
                long stamp = editor.getDocument().getModificationStamp();
                boolean typing = stamp != lastStamp;
                lastStamp = stamp;
                if (!typing) showSoon(editor);
            }
        }, this);
    }

    public static @NotNull TsParameterInfoAutoPopup getInstance(@NotNull Project project) {
        return project.getService(TsParameterInfoAutoPopup.class);
    }

    public void showSoon(@NotNull Editor editor) {
        if (!PwSettings.getInstance().getState().completion) return;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!JsFiles.isScript(file)) return;
        alarm.cancelAllRequests();
        int caret = editor.getCaretModel().getOffset();
        alarm.addRequest(() -> {
            if (editor.isDisposed() || editor.getCaretModel().getOffset() != caret || project.isDisposed()) return;
            if (ParameterInfoController.existsWithVisibleHintForEditor(editor, false)) return;
            if (!isInsideCall(editor.getDocument().getImmutableCharSequence(), caret)) return;
            PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (psiFile != null) new ShowParameterInfoHandler().invoke(project, editor, psiFile);
        }, DELAY_MS);
    }

    /** "name(" or "name<T>(" or "f()(" before the caret: a call, not "if (" or a grouping parenthesis. */
    public static boolean isInsideCall(@NotNull CharSequence text, int caret) {
        int paren = CallScanner.openParen(text, caret);
        if (paren <= 0) return false;
        int i = paren - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0) return false;
        char c = text.charAt(i);
        if (c == ')' || c == '>' || c == ']') return true;
        if (!TsCompletionContributor.isIdentifierPart(c)) return false;
        int start = i;
        while (start > 0 && TsCompletionContributor.isIdentifierPart(text.charAt(start - 1))) start--;
        String word = text.subSequence(start, i + 1).toString();
        return !java.util.Set.of("if", "for", "while", "switch", "catch", "function", "return", "typeof", "await", "with").contains(word);
    }

    @Override
    public void dispose() {
    }
}
