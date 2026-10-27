package dev.pwtoolkit.tests;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction;
import com.intellij.openapi.ui.ComponentContainer;
import dev.pwtoolkit.run.PwRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** "Rerun Failed Tests": runs the failed tests again by their file:line, like `playwright test a.test.ts:12 b.test.ts:30`. */
final class PwRerunFailedTestsAction extends AbstractRerunFailedTestsAction {
    PwRerunFailedTestsAction(@NotNull ComponentContainer componentContainer, @NotNull TestConsoleProperties properties) {
        super(componentContainer);
        init(properties);
    }

    @Override
    protected @Nullable MyRunProfile getRunProfile(@NotNull ExecutionEnvironment environment) {
        if (!(myConsoleProperties.getConfiguration() instanceof PwRunConfiguration config)) return null;
        Set<String> targets = failedTargets(getFailedTests(config.getProject()));
        if (targets.isEmpty()) return null;
        return new MyRunProfile(config) {
            @Override
            public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) {
                PwRunConfiguration copy = (PwRunConfiguration) config.clone();
                copy.setRerunTargets(new ArrayList<>(targets));
                return copy.getState(executor, env);
            }
        };
    }


    /** {@code /abs/file.test.ts:LINE} of each failed test (not suites). */
    static @NotNull Set<String> failedTargets(@NotNull Iterable<? extends AbstractTestProxy> failed) {
        Set<String> targets = new LinkedHashSet<>();
        for (AbstractTestProxy test : failed) {
            String url = test.getLocationUrl();
            if (test.isLeaf() && test.isDefect() && !test.isIgnored() && url != null && url.startsWith(PwTestLocator.PROTOCOL + "://")) {
                targets.add(url.substring(PwTestLocator.PROTOCOL.length() + 3));
            }
        }
        return targets;
    }
}
