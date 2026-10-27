package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PwRunConfiguration extends LocatableConfigurationBase<PwRunConfigurationOptions> {
    private EnvironmentVariablesData envData = EnvironmentVariablesData.DEFAULT;
    /** Not persisted: set on the copy created by "Rerun Failed Tests" (absolute path:line of each failed test). */
    private java.util.List<String> rerunTargets = java.util.List.of();

    PwRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, @Nullable String name) {
        super(project, factory, name);
    }

    @Override
    protected @NotNull PwRunConfigurationOptions getOptions() {
        return (PwRunConfigurationOptions) super.getOptions();
    }

    public String getWorkingDirectory() { return getOptions().getWorkingDirectory(); }
    public void setWorkingDirectory(String value) { getOptions().setWorkingDirectory(value); }

    public String getTestPath() { return getOptions().getTestPath(); }
    public void setTestPath(String value) { getOptions().setTestPath(value); }

    /** 1-based line of the test/describe call, 0 = the whole file or directory. */
    public int getTestLine() { return getOptions().getTestLine(); }
    public void setTestLine(int value) { getOptions().setTestLine(value); }

    public String getTestName() { return getOptions().getTestName(); }
    public void setTestName(String value) { getOptions().setTestName(value); }

    public String getProjectName() { return getOptions().getProjectName(); }
    public void setProjectName(String value) { getOptions().setProjectName(value); }

    public String getExtraArgs() { return getOptions().getExtraArgs(); }
    public void setExtraArgs(String value) { getOptions().setExtraArgs(value); }

    public String getNodePath() { return getOptions().getNodePath(); }
    public void setNodePath(String value) { getOptions().setNodePath(value); }

    public boolean isHeaded() { return getOptions().isHeaded(); }
    public void setHeaded(boolean value) { getOptions().setHeaded(value); }

    public String getGrepInvert() { return getOptions().getGrepInvert(); }
    public void setGrepInvert(String value) { getOptions().setGrepInvert(value); }

    /** Empty = as in playwright.config. */
    public String getRetries() { return getOptions().getRetries(); }
    public void setRetries(String value) { getOptions().setRetries(value); }

    /** --repeat-each, 0 = off. */
    public int getRepeatEach() { return getOptions().getRepeatEach(); }
    public void setRepeatEach(int value) { getOptions().setRepeatEach(value); }

    /** --max-failures, 0 = off. */
    public int getMaxFailures() { return getOptions().getMaxFailures(); }
    public void setMaxFailures(int value) { getOptions().setMaxFailures(value); }

    /** Empty = as in playwright.config (Debug uses 1). */
    public String getWorkers() { return getOptions().getWorkers(); }
    public void setWorkers(String value) { getOptions().setWorkers(value); }

    /** --last-failed: only the tests that failed in the previous run. */
    public boolean isLastFailed() { return getOptions().isLastFailed(); }
    public void setLastFailed(boolean value) { getOptions().setLastFailed(value); }

    /** --trace: empty = as in playwright.config, otherwise on / retain-on-failure / off. */
    public String getTraceMode() { return getOptions().getTraceMode(); }
    public void setTraceMode(String value) { getOptions().setTraceMode(value); }

    public @NotNull java.util.List<String> getRerunTargets() { return rerunTargets; }
    public void setRerunTargets(@NotNull java.util.List<String> rerunTargets) { this.rerunTargets = java.util.List.copyOf(rerunTargets); }

    public @NotNull EnvironmentVariablesData getEnvData() { return envData; }
    public void setEnvData(@NotNull EnvironmentVariablesData envData) { this.envData = envData; }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new PwSettingsEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (StringUtil.isEmptyOrSpaces(getWorkingDirectory())) {
            throw new RuntimeConfigurationError("Working directory (folder with playwright.config) is not set");
        }
        try {
            PwLaunchSpec.create(this, false);
        } catch (ExecutionException e) {
            throw new RuntimeConfigurationError(e.getMessage());
        }
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new PwRunState(environment, this);
    }

    @Override
    public @Nullable String suggestedName() {
        return PwLaunchSpec.suggestName(this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        envData = EnvironmentVariablesData.readExternal(element);
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        envData.writeExternal(element);
    }

    @Override
    public RunConfiguration clone() {
        PwRunConfiguration copy = (PwRunConfiguration) super.clone();
        copy.envData = envData;
        return copy;
    }
}
