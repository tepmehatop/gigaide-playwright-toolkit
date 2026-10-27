package dev.pwtoolkit.run;

import com.intellij.execution.configurations.LocatableRunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

public class PwRunConfigurationOptions extends LocatableRunConfigurationOptions {
    private final StoredProperty<String> workingDirectory = string("").provideDelegate(this, "workingDirectory");
    private final StoredProperty<String> testPath = string("").provideDelegate(this, "testPath");
    private final StoredProperty<Integer> testLine = property(0).provideDelegate(this, "testLine");
    private final StoredProperty<String> testName = string("").provideDelegate(this, "testName");
    private final StoredProperty<String> projectName = string("").provideDelegate(this, "projectName");
    private final StoredProperty<String> extraArgs = string("").provideDelegate(this, "extraArgs");
    private final StoredProperty<String> nodePath = string("").provideDelegate(this, "nodePath");
    private final StoredProperty<Boolean> headed = property(false).provideDelegate(this, "headed");
    private final StoredProperty<String> grepInvert = string("").provideDelegate(this, "grepInvert");
    private final StoredProperty<String> retries = string("").provideDelegate(this, "retries");
    private final StoredProperty<Integer> repeatEach = property(0).provideDelegate(this, "repeatEach");
    private final StoredProperty<Integer> maxFailures = property(0).provideDelegate(this, "maxFailures");
    private final StoredProperty<String> workers = string("").provideDelegate(this, "workers");
    private final StoredProperty<Boolean> lastFailed = property(false).provideDelegate(this, "lastFailed");
    private final StoredProperty<String> traceMode = string("").provideDelegate(this, "traceMode");

    public String getWorkingDirectory() { return workingDirectory.getValue(this); }
    public void setWorkingDirectory(String value) { workingDirectory.setValue(this, value); }

    public String getTestPath() { return testPath.getValue(this); }
    public void setTestPath(String value) { testPath.setValue(this, value); }

    public int getTestLine() { return testLine.getValue(this); }
    public void setTestLine(int value) { testLine.setValue(this, value); }

    public String getTestName() { return testName.getValue(this); }
    public void setTestName(String value) { testName.setValue(this, value); }

    public String getProjectName() { return projectName.getValue(this); }
    public void setProjectName(String value) { projectName.setValue(this, value); }

    public String getExtraArgs() { return extraArgs.getValue(this); }
    public void setExtraArgs(String value) { extraArgs.setValue(this, value); }

    public String getNodePath() { return nodePath.getValue(this); }
    public void setNodePath(String value) { nodePath.setValue(this, value); }

    public boolean isHeaded() { return headed.getValue(this); }
    public void setHeaded(boolean value) { headed.setValue(this, value); }

    public String getGrepInvert() { return grepInvert.getValue(this); }
    public void setGrepInvert(String value) { grepInvert.setValue(this, value); }

    public String getRetries() { return retries.getValue(this); }
    public void setRetries(String value) { retries.setValue(this, value); }

    public int getRepeatEach() { return repeatEach.getValue(this); }
    public void setRepeatEach(int value) { repeatEach.setValue(this, value); }

    public int getMaxFailures() { return maxFailures.getValue(this); }
    public void setMaxFailures(int value) { maxFailures.setValue(this, value); }

    public String getWorkers() { return workers.getValue(this); }
    public void setWorkers(String value) { workers.setValue(this, value); }

    public boolean isLastFailed() { return lastFailed.getValue(this); }
    public void setLastFailed(boolean value) { lastFailed.setValue(this, value); }

    public String getTraceMode() { return traceMode.getValue(this); }
    public void setTraceMode(String value) { traceMode.setValue(this, value); }
}
