package dev.pwtoolkit.debug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XLineBreakpointTypeBase;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/** Line breakpoints in .ts/.js files, used by the Playwright debugger. */
public final class PwLineBreakpointType extends XLineBreakpointTypeBase {
    public static final String ID = "playwright-toolkit-line";

    public PwLineBreakpointType() {
        super(ID, "TypeScript/JavaScript Line Breakpoints", PwEditorsProvider.INSTANCE);
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        return JsFiles.isScript(file);
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
