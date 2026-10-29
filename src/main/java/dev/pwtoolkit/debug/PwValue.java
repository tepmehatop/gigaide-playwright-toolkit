package dev.pwtoolkit.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation;
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** A JS value as reported by js-debug; objects expand lazily through {@code variablesReference}. */
final class PwValue extends XNamedValue {
    private final PwDebugProcess.DapSession session;
    private final String value;
    private final @Nullable String type;
    private final int reference;
    private final @Nullable String evaluateName;
    /** Where the frame of this local variable is paused: for showing the value inline in the editor. */
    private volatile @Nullable com.intellij.xdebugger.XSourcePosition frame;

    PwValue(@NotNull PwDebugProcess.DapSession session, @NotNull String name, @NotNull String value,
            @Nullable String type, int reference, @Nullable String evaluateName) {
        super(name);
        this.session = session;
        this.value = value;
        this.type = type;
        this.reference = reference;
        this.evaluateName = evaluateName;
    }

    static @NotNull CompletableFuture<List<PwValue>> load(@NotNull PwDebugProcess.DapSession session, int reference) {
        return session.connection.request("variables", Map.of("variablesReference", reference)).thenApply(body -> {
            List<PwValue> values = new ArrayList<>();
            JsonArray variables = body.has("variables") ? body.getAsJsonArray("variables") : new JsonArray();
            for (JsonElement element : variables) {
                JsonObject v = element.getAsJsonObject();
                values.add(new PwValue(session, v.get("name").getAsString(),
                        v.has("value") ? v.get("value").getAsString() : "",
                        v.has("type") ? v.get("type").getAsString() : null,
                        v.has("variablesReference") ? v.get("variablesReference").getAsInt() : 0,
                        v.has("evaluateName") ? v.get("evaluateName").getAsString() : null));
            }
            return values;
        });
    }

    static void loadInto(@NotNull PwDebugProcess.DapSession session, int reference, @NotNull XCompositeNode node) {
        load(session, reference).whenComplete((values, error) -> {
            if (error != null) {
                node.setErrorMessage(PwDebugProcess.errorMessage(error));
                return;
            }
            XValueChildrenList list = new XValueChildrenList();
            values.forEach(v -> list.add(v.getName(), v));
            node.addChildren(list, true);
        });
    }

    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        boolean hasChildren = reference > 0;
        if ("string".equals(type) && value.length() >= 2 && (value.startsWith("'") || value.startsWith("\""))) {
            node.setPresentation(AllIcons.Debugger.Db_primitive, new XStringValuePresentation(stripAnsi(value.substring(1, value.length() - 1))), hasChildren);
        } else {
            node.setPresentation(hasChildren ? AllIcons.Debugger.Value : AllIcons.Debugger.Db_primitive,
                    new XRegularValuePresentation(stripAnsi(value), null), hasChildren);
        }
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (reference > 0) loadInto(session, reference, node);
        else node.addChildren(XValueChildrenList.EMPTY, true);
    }

    /** Playwright error messages carry terminal colors: show "expect(locator)" instead of "[2mexpect([22m". */
    static @NotNull String stripAnsi(@NotNull String text) {
        // the ESC character itself, or its escaped spelling inside a JS string literal ("\x1b[2m", "\u001b[2m")
        return text.replaceAll("(?:\u001b|\\\\x1b|\\\\u001b)\\[[0-9;]*m", "");
    }

    /** Marks a variable of the paused frame (not a child of an object), so that its value is shown inline. */
    @NotNull PwValue atFrame(@Nullable com.intellij.xdebugger.XSourcePosition position) {
        this.frame = position;
        return this;
    }

    /**
     * "Show values inline" (like IntelliJ IDEA): the value is shown at the end of the closest line at or above the paused
     * line where the variable is mentioned (declared, assigned or used), within the visible function.
     */
    @Override
    public @NotNull com.intellij.util.ThreeState computeInlineDebuggerData(@NotNull com.intellij.xdebugger.frame.XInlineDebuggerDataCallback callback) {
        com.intellij.xdebugger.XSourcePosition position = frame;
        if (position == null || getName().equals("this")) return com.intellij.util.ThreeState.UNSURE;
        Integer line = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<Integer>) () -> lineMentioning(position, getName()));
        if (line == null) return com.intellij.util.ThreeState.NO;
        callback.computed(com.intellij.xdebugger.XDebuggerUtil.getInstance().createPosition(position.getFile(), line));
        return com.intellij.util.ThreeState.YES;
    }

    /** The closest line at or above the paused one with the name as an identifier (not as a member after '.'). */
    static @Nullable Integer lineMentioning(@NotNull com.intellij.xdebugger.XSourcePosition position, @NotNull String name) {
        com.intellij.openapi.editor.Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(position.getFile());
        if (document == null || position.getLine() >= document.getLineCount()) return null;
        java.util.regex.Pattern identifier = java.util.regex.Pattern.compile("(?<![\\w$.])" + java.util.regex.Pattern.quote(name) + "(?![\\w$])");
        CharSequence text = document.getImmutableCharSequence();
        for (int line = position.getLine(); line >= 0 && line > position.getLine() - 300; line--) {
            CharSequence lineText = text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line));
            if (identifier.matcher(lineText).find()) return line;
        }
        return null;
    }

    @NotNull String getValueText() {
        return value;
    }

    @Override
    public @Nullable String getEvaluationExpression() {
        return evaluateName;
    }
}
