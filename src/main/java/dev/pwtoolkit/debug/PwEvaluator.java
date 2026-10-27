package dev.pwtoolkit.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Evaluates watches, "Evaluate expression" and value hints in the selected frame. */
final class PwEvaluator extends XDebuggerEvaluator {
    private final PwDebugProcess.DapSession session;
    private final int frameId;

    PwEvaluator(@NotNull PwDebugProcess.DapSession session, int frameId) {
        this.session = session;
        this.frameId = frameId;
    }

    @Override
    public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback, @Nullable XSourcePosition position) {
        session.connection.request("evaluate", Map.of("expression", expression, "frameId", frameId, "context", "watch"))
                .whenComplete((body, error) -> {
                    if (error != null) {
                        callback.errorOccurred(PwDebugProcess.errorMessage(error));
                        return;
                    }
                    callback.evaluated(new PwValue(session, "",
                            body.has("result") ? body.get("result").getAsString() : "undefined",
                            body.has("type") ? body.get("type").getAsString() : null,
                            body.has("variablesReference") ? body.get("variablesReference").getAsInt() : 0,
                            expression));
                });
    }

    /** Hover support: the identifier under the caret together with its qualifier, e.g. {@code loginPage.selectors.greeting}. */
    @Override
    public @Nullable TextRange getExpressionRangeAtOffset(@NotNull Project project, @NotNull Document document, int offset, boolean sideEffectsAllowed) {
        CharSequence text = document.getCharsSequence();
        if (offset < 0 || offset >= text.length() || !Character.isJavaIdentifierPart(text.charAt(offset))) return null;
        int start = offset;
        while (start > 0 && (Character.isJavaIdentifierPart(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) start--;
        while (start < offset && text.charAt(start) == '.') start++;
        int end = offset;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        if (start >= end || Character.isDigit(text.charAt(start))) return null;
        return new TextRange(start, end);
    }
}
