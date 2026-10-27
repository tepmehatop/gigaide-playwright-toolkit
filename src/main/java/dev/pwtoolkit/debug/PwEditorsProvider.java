package dev.pwtoolkit.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Editors for watches, conditions and "Evaluate expression": TypeScript-highlighted when GigaIDE's TS support is present. */
final class PwEditorsProvider extends XDebuggerEditorsProvider {
    static final PwEditorsProvider INSTANCE = new PwEditorsProvider();

    @Override
    public @NotNull FileType getFileType() {
        FileType type = FileTypeManager.getInstance().getFileTypeByExtension("ts");
        return type instanceof UnknownFileType ? PlainTextFileType.INSTANCE : type;
    }

    @Override
    public @NotNull Document createDocument(@NotNull Project project, @NotNull XExpression expression,
                                            @Nullable XSourcePosition sourcePosition, @NotNull EvaluationMode mode) {
        LightVirtualFile file = new LightVirtualFile("playwright-debug-expression.ts", getFileType(), expression.getExpression());
        Document document = FileDocumentManager.getInstance().getDocument(file);
        return document != null ? document : EditorFactory.getInstance().createDocument(expression.getExpression());
    }
}
