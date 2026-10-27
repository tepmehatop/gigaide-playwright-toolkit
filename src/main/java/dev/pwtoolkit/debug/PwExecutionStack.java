package dev.pwtoolkit.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The paused thread of one js-debug target (Playwright runner process or a worker). */
final class PwExecutionStack extends XExecutionStack {
    final PwDebugProcess.DapSession session;
    final int threadId;
    private final List<PwStackFrame> frames = new ArrayList<>();

    PwExecutionStack(@NotNull PwDebugProcess.DapSession session, int threadId, @NotNull JsonArray frames) {
        super(session.name, AllIcons.Debugger.ThreadSuspended);
        this.session = session;
        this.threadId = threadId;
        for (JsonElement frame : frames) this.frames.add(new PwStackFrame(session, frame.getAsJsonObject()));
    }

    @Override
    public @Nullable XStackFrame getTopFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    @Override
    public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
        int from = Math.min(firstFrameIndex, frames.size());
        container.addStackFrames(new ArrayList<>(frames.subList(from, frames.size())), true);
    }
}
