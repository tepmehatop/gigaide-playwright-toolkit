package dev.pwtoolkit.run;

import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

final class PwSettingsEditor extends SettingsEditor<PwRunConfiguration> {
    private final TextFieldWithBrowseButton workingDirectory = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton testPath = new TextFieldWithBrowseButton();
    private final JSpinner testLine = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JBTextField testName = new JBTextField();
    private final JBTextField projectName = new JBTextField();
    private final JBTextField extraArgs = new JBTextField();
    private final TextFieldWithBrowseButton nodePath = new TextFieldWithBrowseButton();
    private final JBCheckBox headed = new JBCheckBox("Headed browser (--headed)");
    private final EnvironmentVariablesComponent env = new EnvironmentVariablesComponent();

    PwSettingsEditor(@NotNull Project project) {
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Folder With playwright.config"));
        testPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                .withTitle("Test File or Folder"));
        nodePath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Node.js Interpreter"));
        testName.getEmptyText().setText("regex for --grep, optional");
        projectName.getEmptyText().setText("e.g. ui-tests, optional");
        extraArgs.getEmptyText().setText("e.g. --retries=0 --reporter=list");
        nodePath.getTextField().setToolTipText("Empty = auto-detect (Settings | Tools | Playwright Toolkit, PATH, nvm)");
    }

    @Override
    protected void resetEditorFrom(@NotNull PwRunConfiguration config) {
        workingDirectory.setText(config.getWorkingDirectory());
        testPath.setText(config.getTestPath());
        testLine.setValue(config.getTestLine());
        testName.setText(config.getTestName());
        projectName.setText(config.getProjectName());
        extraArgs.setText(config.getExtraArgs());
        nodePath.setText(config.getNodePath());
        headed.setSelected(config.isHeaded());
        env.setEnvData(config.getEnvData());
    }

    @Override
    protected void applyEditorTo(@NotNull PwRunConfiguration config) {
        config.setWorkingDirectory(workingDirectory.getText().trim());
        config.setTestPath(testPath.getText().trim());
        config.setTestLine((Integer) testLine.getValue());
        config.setTestName(testName.getText());
        config.setProjectName(projectName.getText().trim());
        config.setExtraArgs(extraArgs.getText());
        config.setNodePath(nodePath.getText().trim());
        config.setHeaded(headed.isSelected());
        config.setEnvData(env.getEnvData());
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Working directory:", workingDirectory)
                .addLabeledComponent("Test file or folder:", testPath)
                .addLabeledComponent("Line (0 = whole file):", testLine)
                .addLabeledComponent("Test name filter:", testName)
                .addLabeledComponent("Playwright project:", projectName)
                .addLabeledComponent("Additional arguments:", extraArgs)
                .addLabeledComponent("Node interpreter:", nodePath)
                .addComponent(headed)
                .addComponent(env)
                .addComponentFillVertically(new javax.swing.JPanel(), 0)
                .getPanel();
    }
}
