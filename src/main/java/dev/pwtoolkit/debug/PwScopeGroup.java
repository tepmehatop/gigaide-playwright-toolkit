package dev.pwtoolkit.debug;

import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValueGroup;
import org.jetbrains.annotations.NotNull;

/** A non-local scope (Closure, Module, Global...) shown as a collapsible group. */
final class PwScopeGroup extends XValueGroup {
    private final PwDebugProcess.DapSession session;
    private final int reference;

    PwScopeGroup(@NotNull PwDebugProcess.DapSession session, @NotNull String name, int reference) {
        super(name);
        this.session = session;
        this.reference = reference;
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        PwValue.loadInto(session, reference, node);
    }
}
