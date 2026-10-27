package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import org.jetbrains.annotations.NotNull;

/** Plain "Run": starts the Playwright CLI and shows its output in the console. */
final class PwRunState extends CommandLineState {
    private final PwRunConfiguration config;

    PwRunState(@NotNull ExecutionEnvironment environment, @NotNull PwRunConfiguration config) {
        super(environment);
        this.config = config;
    }

    @Override
    protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        PwLaunchSpec spec = PwLaunchSpec.create(config, false);
        GeneralCommandLine commandLine = new GeneralCommandLine(spec.node(), spec.cli())
                .withParameters(spec.args())
                .withWorkingDirectory(spec.workDir().toPath())
                .withEnvironment(spec.env())
                .withEnvironment("FORCE_COLOR", "1")
                .withParentEnvironmentType(spec.passParentEnv()
                        ? GeneralCommandLine.ParentEnvironmentType.CONSOLE
                        : GeneralCommandLine.ParentEnvironmentType.NONE);
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(commandLine);
        handler.setShouldKillProcessSoftly(true);
        ProcessTerminatedListener.attach(handler);
        return handler;
    }
}
