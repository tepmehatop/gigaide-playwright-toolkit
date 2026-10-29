package dev.pwtoolkit.debug;

import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValueGroup;
import org.jetbrains.annotations.NotNull;

/** A non-local scope (Closure, Module, Global...) shown as a collapsible group. */
final class PwScopeGroup extends XValueGroup {
    private final PwDebugProcess.DapSession session;
    private final int reference;
    /** The paused position: Block and Closure variables (e.g. fixtures) are shown inline too, Global and Script ones not. */
    private final com.intellij.xdebugger.XSourcePosition frame;

    PwScopeGroup(@NotNull PwDebugProcess.DapSession session, @NotNull String name, int reference, @org.jetbrains.annotations.Nullable com.intellij.xdebugger.XSourcePosition frame) {
        super(name);
        this.session = session;
        this.reference = reference;
        this.frame = name.startsWith("Global") || name.startsWith("Script") || name.startsWith("Module") ? null : frame;
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        PwValue.load(session, reference).whenComplete((values, error) -> {
            if (error != null) {
                node.setErrorMessage(PwDebugProcess.errorMessage(error));
                return;
            }
            com.intellij.xdebugger.frame.XValueChildrenList list = new com.intellij.xdebugger.frame.XValueChildrenList();
            values.forEach(v -> list.add(v.getName(), v.atFrame(frame)));
            node.addChildren(list, true);
        });
    }
}
