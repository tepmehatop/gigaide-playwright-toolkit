package dev.pwtoolkit.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

final class PwStackFrame extends XStackFrame {
    private final PwDebugProcess.DapSession session;
    private final int frameId;
    private final String name;
    private final @Nullable String path;
    private final @Nullable String sourceName;
    private final int line;
    private final int column;
    private final boolean subtle;
    private volatile XSourcePosition position;
    private volatile boolean positionResolved;

    PwStackFrame(@NotNull PwDebugProcess.DapSession session, @NotNull JsonObject frame) {
        this.session = session;
        this.frameId = frame.get("id").getAsInt();
        this.name = frame.has("name") ? frame.get("name").getAsString() : "<anonymous>";
        this.line = frame.has("line") ? frame.get("line").getAsInt() : 0;
        this.column = frame.has("column") ? frame.get("column").getAsInt() : 0;
        JsonObject source = frame.has("source") ? frame.getAsJsonObject("source") : null;
        this.path = source != null && source.has("path") ? source.get("path").getAsString() : null;
        this.sourceName = source != null && source.has("name") ? source.get("name").getAsString() : null;
        String hint = frame.has("presentationHint") ? frame.get("presentationHint").getAsString() : "";
        String sourceHint = source != null && source.has("presentationHint") ? source.get("presentationHint").getAsString() : "";
        this.subtle = "label".equals(hint) || "subtle".equals(hint) || "deemphasize".equals(sourceHint);
    }

    int frameId() {
        return frameId;
    }

    @Override
    public @Nullable XSourcePosition getSourcePosition() {
        if (!positionResolved) {
            VirtualFile file = path == null ? null : LocalFileSystem.getInstance().findFileByPath(path);
            position = file == null || line <= 0 ? null : XDebuggerUtil.getInstance().createPosition(file, line - 1, Math.max(column - 1, 0));
            positionResolved = true;
        }
        return position;
    }

    @Override
    public @NotNull Object getEqualityObject() {
        return name + "@" + path;
    }

    @Override
    public @NotNull XDebuggerEvaluator getEvaluator() {
        return new PwEvaluator(session, frameId);
    }

    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        boolean known = getSourcePosition() != null;
        component.append(name, known && !subtle ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
        String location = path != null ? new java.io.File(path).getName() : sourceName;
        if (location != null && line > 0) component.append("  " + location + ":" + line, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        component.setIcon(AllIcons.Debugger.Frame);
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        session.connection.request("scopes", Map.of("frameId", frameId)).whenComplete((body, error) -> {
            if (error != null) {
                node.setErrorMessage(PwDebugProcess.errorMessage(error));
                return;
            }
            JsonArray scopes = body.has("scopes") ? body.getAsJsonArray("scopes") : new JsonArray();
            XValueChildrenList groups = new XValueChildrenList();
            for (int i = 1; i < scopes.size(); i++) {
                JsonObject scope = scopes.get(i).getAsJsonObject();
                groups.addBottomGroup(new PwScopeGroup(session, scope.get("name").getAsString(), scope.get("variablesReference").getAsInt()));
            }
            if (scopes.isEmpty()) {
                node.addChildren(groups, true);
                return;
            }
            int locals = scopes.get(0).getAsJsonObject().get("variablesReference").getAsInt();
            PwValue.load(session, locals).whenComplete((values, loadError) -> {
                XValueChildrenList list = new XValueChildrenList();
                if (values != null) values.forEach(v -> list.add(v.getName(), v));
                node.addChildren(list, false);
                node.addChildren(groups, true);
            });
        });
    }
}
