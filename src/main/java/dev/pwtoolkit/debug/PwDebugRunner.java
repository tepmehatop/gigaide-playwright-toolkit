package dev.pwtoolkit.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.run.PwRunConfiguration;
import dev.pwtoolkit.tests.PwTestConsoleProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Debug executor for "Playwright Test" configurations. */
public final class PwDebugRunner extends GenericProgramRunner<RunnerSettings> {
    @Override
    public @NotNull String getRunnerId() {
        return "PlaywrightToolkitDebugRunner";
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof PwRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
            throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();
        PwRunConfiguration config = (PwRunConfiguration) environment.getRunProfile();
        PwLaunchSpec spec = PwLaunchSpec.create(config, true);
        PwTestConsoleProperties testProperties = new PwTestConsoleProperties(config, environment.getExecutor());
        XDebugProcessStarter starter = new XDebugProcessStarter() {
            @Override
            public @NotNull XDebugProcess start(@NotNull XDebugSession session) {
                return new PwDebugProcess(session, spec, testProperties);
            }
        };
        XDebuggerManager manager = XDebuggerManager.getInstance(environment.getProject());
        try {
            // 2025.3+: the descriptor must come from the builder, the IDE runs the debugger UI in "split" mode
            return manager.newSessionBuilder(starter).environment(environment).startSession().getRunContentDescriptor();
        } catch (NoSuchMethodError e) {
            return startLegacy(manager, environment, starter);
        }
    }

    @SuppressWarnings("deprecation")
    private static RunContentDescriptor startLegacy(XDebuggerManager manager, ExecutionEnvironment environment,
                                                    XDebugProcessStarter starter) throws ExecutionException {
        return manager.startSession(environment, starter).getRunContentDescriptor();
    }
}
