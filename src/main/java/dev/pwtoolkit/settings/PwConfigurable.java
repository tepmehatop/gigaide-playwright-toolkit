package dev.pwtoolkit.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import dev.pwtoolkit.debug.JsDebugLocator;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.nio.file.Path;
import java.util.Objects;

/** Settings | Tools | Playwright Toolkit. */
public final class PwConfigurable implements SearchableConfigurable {
    private TextFieldWithBrowseButton nodePath;
    private TextFieldWithBrowseButton tsServerPath;
    private TextFieldWithBrowseButton jsDebugPath;
    private JBCheckBox navigationEnabled;
    private JSpinner navigationTimeout;
    private JBCheckBox skipInternals;
    private JBCheckBox semanticHighlighting;
    private JBCheckBox showBrowser;
    private JBCheckBox pauseOnFailure;
    private JBCheckBox disableJestMarker;

    @Override
    public @NotNull String getId() {
        return "dev.pwtoolkit.settings";
    }

    @Override
    public String getDisplayName() {
        return "Playwright Toolkit";
    }

    @Override
    public @Nullable JComponent createComponent() {
        nodePath = new TextFieldWithBrowseButton();
        nodePath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Node.js Interpreter"));
        tsServerPath = new TextFieldWithBrowseButton();
        tsServerPath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("TypeScript 7 Executable"));
        jsDebugPath = new TextFieldWithBrowseButton();
        jsDebugPath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("vscode-js-debug Folder"));
        navigationEnabled = new JBCheckBox("Resolve Go to Declaration with the TypeScript 7 language server");
        navigationTimeout = new JSpinner(new SpinnerNumberModel(3000, 200, 60000, 100));
        skipInternals = new JBCheckBox("Do not step into Playwright internals (node_modules/playwright*)");
        semanticHighlighting = new JBCheckBox("Semantic highlighting (fixtures, Page Object fields and methods) from TypeScript 7");
        showBrowser = new JBCheckBox("Show the browser while debugging (--headed)");
        pauseOnFailure = new JBCheckBox("Pause on the failing line of a test while debugging (like VS Code)");
        disableJestMarker = new JBCheckBox("Work around GigaIDE's broken Jest gutter marker in test files (restart required)");
        disableJestMarker.setToolTipText("GigaIDE NodeJS plugin fails on every *.test.ts and the file analysis never finishes");

        String node = NodeLocator.findNode(null);
        Path jsDebug = JsDebugLocator.findServerScript();
        nodePath.getTextField().setToolTipText("Detected: " + Objects.requireNonNullElse(node, "not found"));
        jsDebugPath.getTextField().setToolTipText("Detected: " + (jsDebug == null ? "not found" : jsDebug.toString()));

        return FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("Leave paths empty to detect them automatically."))
                .addLabeledComponent("Node.js:", nodePath)
                .addComponentToRightColumn(hint("Detected: " + Objects.requireNonNullElse(node, "not found")))
                .addSeparator()
                .addComponent(navigationEnabled)
                .addLabeledComponent("TypeScript 7 executable (tsc/tsgo):", tsServerPath)
                .addComponentToRightColumn(hint("Default: node_modules/@typescript/typescript-<os>-<arch>/lib/tsc of the project"))
                .addLabeledComponent("Navigation timeout, ms:", navigationTimeout)
                .addComponent(semanticHighlighting)
                .addSeparator()
                .addLabeledComponent("vscode-js-debug folder:", jsDebugPath)
                .addComponentToRightColumn(hint("Detected: " + (jsDebug == null ? "not found" : jsDebug.toString())))
                .addComponent(skipInternals)
                .addComponent(showBrowser)
                .addComponent(pauseOnFailure)
                .addSeparator()
                .addComponent(disableJestMarker)
                .addComponentFillVertically(new javax.swing.JPanel(), 0)
                .getPanel();
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        return label;
    }

    @Override
    public boolean isModified() {
        PwSettings.StateBean s = PwSettings.getInstance().getState();
        return !nodePath.getText().trim().equals(s.nodePath)
                || !tsServerPath.getText().trim().equals(s.tsServerPath)
                || !jsDebugPath.getText().trim().equals(s.jsDebugPath)
                || navigationEnabled.isSelected() != s.navigationEnabled
                || (Integer) navigationTimeout.getValue() != s.navigationTimeoutMs
                || skipInternals.isSelected() != s.skipPlaywrightInternals
                || semanticHighlighting.isSelected() != s.semanticHighlighting
                || showBrowser.isSelected() != s.showBrowserWhenDebugging
                || pauseOnFailure.isSelected() != s.pauseOnFailure
                || disableJestMarker.isSelected() != s.disableGigaJestMarker;
    }

    @Override
    public void apply() {
        PwSettings.StateBean s = PwSettings.getInstance().getState();
        boolean serverChanged = !tsServerPath.getText().trim().equals(s.tsServerPath) || navigationEnabled.isSelected() != s.navigationEnabled;
        s.nodePath = nodePath.getText().trim();
        s.tsServerPath = tsServerPath.getText().trim();
        s.jsDebugPath = jsDebugPath.getText().trim();
        s.navigationEnabled = navigationEnabled.isSelected();
        s.navigationTimeoutMs = (Integer) navigationTimeout.getValue();
        s.skipPlaywrightInternals = skipInternals.isSelected();
        s.semanticHighlighting = semanticHighlighting.isSelected();
        s.showBrowserWhenDebugging = showBrowser.isSelected();
        s.pauseOnFailure = pauseOnFailure.isSelected();
        if (disableJestMarker.isSelected() && !s.disableGigaJestMarker) dev.pwtoolkit.compat.GigaJestMarkerWorkaround.apply();
        s.disableGigaJestMarker = disableJestMarker.isSelected();
        if (serverChanged) {
            for (var project : ProjectManager.getInstance().getOpenProjects()) TsLanguageService.getInstance(project).restartAll();
        }
        for (var project : ProjectManager.getInstance().getOpenProjects()) {
            dev.pwtoolkit.nav.TsSemanticHighlighter.getInstance(project).scheduleOpenFiles();
        }
    }

    @Override
    public void reset() {
        PwSettings.StateBean s = PwSettings.getInstance().getState();
        nodePath.setText(s.nodePath);
        tsServerPath.setText(s.tsServerPath);
        jsDebugPath.setText(s.jsDebugPath);
        navigationEnabled.setSelected(s.navigationEnabled);
        navigationTimeout.setValue(s.navigationTimeoutMs);
        skipInternals.setSelected(s.skipPlaywrightInternals);
        semanticHighlighting.setSelected(s.semanticHighlighting);
        showBrowser.setSelected(s.showBrowserWhenDebugging);
        pauseOnFailure.setSelected(s.pauseOnFailure);
        disableJestMarker.setSelected(s.disableGigaJestMarker);
    }
}
