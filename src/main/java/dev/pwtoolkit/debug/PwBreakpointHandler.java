package dev.pwtoolkit.debug;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings({"rawtypes", "unchecked"})
final class PwBreakpointHandler extends XBreakpointHandler {
    private final PwDebugProcess process;

    PwBreakpointHandler(@NotNull Class breakpointTypeClass, @NotNull PwDebugProcess process) {
        super(breakpointTypeClass);
        this.process = process;
    }

    @Override
    public void registerBreakpoint(@NotNull XBreakpoint breakpoint) {
        if (breakpoint instanceof XLineBreakpoint<?> line) process.addBreakpoint(line);
    }

    @Override
    public void unregisterBreakpoint(@NotNull XBreakpoint breakpoint, boolean temporary) {
        if (breakpoint instanceof XLineBreakpoint<?> line) process.removeBreakpoint(line);
    }
}
