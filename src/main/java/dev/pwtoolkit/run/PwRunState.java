package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView;
import dev.pwtoolkit.tests.PwTestConsoleProperties;
import org.jetbrains.annotations.NotNull;

/** "Run": starts the Playwright CLI; the test tree comes from pwtk-reporter.cjs (see {@link PwTestConsoleProperties}). */
final class PwRunState extends CommandLineState {
    private final PwRunConfiguration config;

    PwRunState(@NotNull ExecutionEnvironment environment, @NotNull PwRunConfiguration config) {
        super(environment);
        this.config = config;
    }

    @Override
    public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
        ProcessHandler handler = startProcess();
        PwTestConsoleProperties properties = new PwTestConsoleProperties(config, executor);
        BaseTestsOutputConsoleView console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                PwTestConsoleProperties.FRAMEWORK, handler, properties);
        DefaultExecutionResult result = new DefaultExecutionResult(console, handler, createActions(console, handler, executor));
        AbstractRerunFailedTestsAction rerunFailed = properties.createRerunFailedTestsAction(console);
        if (rerunFailed != null && console instanceof SMTRunnerConsoleView smConsole) {
            rerunFailed.setModelProvider(smConsole::getResultsViewer);
            result.setRestartActions(rerunFailed);
        }
        return result;
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
