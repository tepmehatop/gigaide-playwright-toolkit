package dev.pwtoolkit.debug;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;

/** Represents the debugged Playwright run in the Debug tool window; the real processes are owned by js-debug. */
final class PwDebugProcessHandler extends ProcessHandler {
    private final Runnable stop;

    PwDebugProcessHandler(@NotNull Runnable stop) {
        this.stop = stop;
    }

    void print(@NotNull String text, @NotNull Key<?> outputType) {
        notifyTextAvailable(text, outputType);
    }

    void finish(int exitCode) {
        if (!isProcessTerminated()) notifyProcessTerminated(exitCode);
    }

    @Override
    protected void destroyProcessImpl() {
        stop.run();
    }

    @Override
    protected void detachProcessImpl() {
        stop.run();
    }

    @Override
    public boolean detachIsDefault() {
        return false;
    }

    @Override
    public @Nullable OutputStream getProcessInput() {
        return null;
    }
}
