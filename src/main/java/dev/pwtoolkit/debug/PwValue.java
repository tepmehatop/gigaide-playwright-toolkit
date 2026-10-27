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

    @NotNull String getValueText() {
        return value;
    }

    @Override
    public @Nullable String getEvaluationExpression() {
        return evaluateName;
    }
}
