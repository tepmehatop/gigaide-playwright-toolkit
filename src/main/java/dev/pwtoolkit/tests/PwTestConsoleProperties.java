package dev.pwtoolkit.tests;

import com.intellij.execution.Executor;
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction;
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.execution.ui.ConsoleView;
import dev.pwtoolkit.run.PwRunConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/** Test tree settings for "Playwright Test": ID-based tree from pwtk-reporter.cjs, pwtest:// locations. */
public final class PwTestConsoleProperties extends SMTRunnerConsoleProperties {
    public static final String FRAMEWORK = "Playwright";

    private final PwRunConfiguration config;

    public PwTestConsoleProperties(@NotNull PwRunConfiguration config, @NotNull Executor executor) {
        super(config, FRAMEWORK, executor);
        setIdBasedTestTree(true);
        setPrintTestingStartedTime(false);
        this.config = config;
    }

    /**
     * Clickable stacks, code frames and attachment paths. Must be added to the console itself
     * ({@code addMessageFilter}): the test tree does not apply {@code addStackTraceFilter} to this output.
     */
    public @NotNull PwStackTraceFilter createLinkFilter() {
        String workDir = config.getWorkingDirectory();
        return new PwStackTraceFilter(config.getProject(), new File(workDir == null ? "" : workDir));
    }

    @Override
    public @NotNull SMTestLocator getTestLocator() {
        return PwTestLocator.INSTANCE;
    }

    @Override
    public AbstractRerunFailedTestsAction createRerunFailedTestsAction(ConsoleView consoleView) {
        return new PwRerunFailedTestsAction(consoleView, this);
    }

}
