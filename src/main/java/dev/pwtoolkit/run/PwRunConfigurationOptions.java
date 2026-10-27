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
}
