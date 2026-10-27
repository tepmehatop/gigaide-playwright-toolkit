package dev.pwtoolkit.debug;

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;

final class PwSuspendContext extends XSuspendContext {
    private final PwExecutionStack stack;

    PwSuspendContext(@NotNull PwExecutionStack stack) {
        this.stack = stack;
    }

    @Override
    public @NotNull PwExecutionStack getActiveExecutionStack() {
        return stack;
    }

    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        return new XExecutionStack[]{stack};
    }
}
