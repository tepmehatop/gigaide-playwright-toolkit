package dev.pwtoolkit.run;

import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TextFieldWithAutoCompletion;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PwSettingsEditor extends SettingsEditor<PwRunConfiguration> {
    private static final String[] TRACE_MODES = {"", "on", "retain-on-failure", "on-first-retry", "off"};

    private final Project project;
    private final TextFieldWithBrowseButton workingDirectory = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton testPath = new TextFieldWithBrowseButton();
    private final JSpinner testLine = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final TextFieldWithAutoCompletion<String> testName;
    private final TextFieldWithAutoCompletion<String> grepInvert;
    private final ComboBox<String> projectName = new ComboBox<>(new DefaultComboBoxModel<>());
    private final JBTextField retries = new JBTextField();
    private final JSpinner repeatEach = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 1));
    private final JSpinner maxFailures = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 1));
    private final JBTextField workers = new JBTextField();
    private final JBCheckBox lastFailed = new JBCheckBox("Only tests that failed in the previous run (--last-failed)");
    private final ComboBox<String> traceMode = new ComboBox<>(TRACE_MODES);
    private final JBTextField extraArgs = new JBTextField();
    private final TextFieldWithBrowseButton nodePath = new TextFieldWithBrowseButton();
    private final JBCheckBox headed = new JBCheckBox("Headed browser (--headed)");
    private final EnvironmentVariablesComponent env = new EnvironmentVariablesComponent();
    private String loadedFor;

    PwSettingsEditor(@NotNull Project project) {
        this.project = project;
        testName = TextFieldWithAutoCompletion.create(project, List.of(), true, "");
        grepInvert = TextFieldWithAutoCompletion.create(project, List.of(), true, "");
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Folder With playwright.config"));
        testPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                .withTitle("Test File or Folder"));
        nodePath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Node.js Interpreter"));
        projectName.setEditable(true);
        traceMode.setRenderer(SimpleListCellRenderer.create("as in playwright.config", value -> value));
        testName.setToolTipText("--grep: regular expression or @tag (completion: Ctrl+Space)");
        grepInvert.setToolTipText("--grep-invert: skip tests matching this regular expression or @tag");
        retries.getEmptyText().setText("as in playwright.config");
        workers.getEmptyText().setText("as in playwright.config (Debug: 1)");
        extraArgs.getEmptyText().setText("e.g. --update-snapshots");
        nodePath.getTextField().setToolTipText("Empty = auto-detect (Settings | Tools | Playwright Toolkit, PATH, nvm)");
    }

    @Override
    protected void resetEditorFrom(@NotNull PwRunConfiguration config) {
        workingDirectory.setText(config.getWorkingDirectory());
        testPath.setText(config.getTestPath());
        testLine.setValue(config.getTestLine());
        testName.setText(config.getTestName());
        grepInvert.setText(config.getGrepInvert());
        projectName.setSelectedItem(StringUtil.notNullize(config.getProjectName()));
        retries.setText(config.getRetries());
        repeatEach.setValue(config.getRepeatEach());
        maxFailures.setValue(config.getMaxFailures());
        workers.setText(config.getWorkers());
        lastFailed.setSelected(config.isLastFailed());
        traceMode.setSelectedItem(StringUtil.notNullize(config.getTraceMode()));
        extraArgs.setText(config.getExtraArgs());
        nodePath.setText(config.getNodePath());
        headed.setSelected(config.isHeaded());
        env.setEnvData(config.getEnvData());
        loadChoices(config.getWorkingDirectory(), config.getNodePath());
    }

    /** Fills the project list and @tag completion in the background (listing projects starts Node). */
    private void loadChoices(String workDir, String node) {
        if (StringUtil.isEmptyOrSpaces(workDir) || workDir.equals(loadedFor)) return;
        loadedFor = workDir;
        File dir = new File(workDir);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Set<String> tags = PwProjectInfo.findTags(dir);
            List<String> projects = PwProjectInfo.listProjects(dir, StringUtil.notNullize(node));
            ApplicationManager.getApplication().invokeLater(() -> {
                testName.setVariants(new ArrayList<>(tags));
                grepInvert.setVariants(new ArrayList<>(tags));
                Object selected = projectName.getEditor().getItem();
                DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) projectName.getModel();
                model.removeAllElements();
                model.addElement("");
                projects.forEach(model::addElement);
                projectName.setSelectedItem(selected);
            }, ModalityState.any());
        });
    }

    @Override
    protected void applyEditorTo(@NotNull PwRunConfiguration config) {
        config.setWorkingDirectory(workingDirectory.getText().trim());
        config.setTestPath(testPath.getText().trim());
        config.setTestLine((Integer) testLine.getValue());
        config.setTestName(testName.getText());
        config.setGrepInvert(grepInvert.getText());
        Object project = projectName.getEditor().getItem();
        config.setProjectName(project == null ? "" : project.toString().trim());
        config.setRetries(retries.getText().trim());
        config.setRepeatEach((Integer) repeatEach.getValue());
        config.setMaxFailures((Integer) maxFailures.getValue());
        config.setWorkers(workers.getText().trim());
        config.setLastFailed(lastFailed.isSelected());
        config.setTraceMode(StringUtil.notNullize((String) traceMode.getSelectedItem()));
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
                .addLabeledComponent("Playwright project:", projectName)
                .addLabeledComponent("Test name filter (--grep):", testName)
                .addLabeledComponent("Exclude (--grep-invert):", grepInvert)
                .addSeparator()
                .addLabeledComponent("Retries:", retries)
                .addLabeledComponent("Repeat each test (--repeat-each, 0 = off):", repeatEach)
                .addLabeledComponent("Stop after N failures (--max-failures, 0 = off):", maxFailures)
                .addLabeledComponent("Workers:", workers)
                .addComponent(lastFailed)
                .addLabeledComponent("Trace (--trace):", traceMode)
                .addSeparator()
                .addLabeledComponent("Additional arguments:", extraArgs)
                .addLabeledComponent("Node interpreter:", nodePath)
                .addComponent(headed)
                .addComponent(env)
                .addComponentFillVertically(new javax.swing.JPanel(), 0)
                .getPanel();
    }
}
