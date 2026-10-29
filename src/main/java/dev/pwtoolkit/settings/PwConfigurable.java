package dev.pwtoolkit.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import dev.pwtoolkit.debug.JsDebugLocator;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.nav.TsServerLocator;
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
    /** Index 0: the default of this build, then {@link TsServerLocator.Backend} values. */
    private ComboBox<String> tsBackend;
    private TextFieldWithBrowseButton jsDebugPath;
    private JBCheckBox navigationEnabled;
    private JSpinner navigationTimeout;
    private JBCheckBox skipInternals;
    private JBCheckBox semanticHighlighting;
    private JBCheckBox completion;
    private JBCheckBox diagnostics;
    private JBCheckBox rainbowBrackets;
    private JBCheckBox showBrowser;
    private JBCheckBox pauseOnFailure;
    private JBCheckBox reuseBrowser;
    private JBCheckBox exploreLocators;
    private JBCheckBox disableJestMarker;
    private JBCheckBox hideTsxMarker;

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
        tsServerPath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("TypeScript Server"));
        tsBackend = new ComboBox<>();
        tsBackend.addItem("Default (" + TsServerLocator.defaultBackend() + ")");
        for (TsServerLocator.Backend backend : TsServerLocator.Backend.values()) tsBackend.addItem(backend.title);
        jsDebugPath = new TextFieldWithBrowseButton();
        jsDebugPath.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("vscode-js-debug Folder"));
        navigationEnabled = new JBCheckBox("Resolve Go to Declaration with the TypeScript language server");
        navigationTimeout = new JSpinner(new SpinnerNumberModel(3000, 200, 60000, 100));
        skipInternals = new JBCheckBox("Do not step into Playwright internals (node_modules/playwright*)");
        semanticHighlighting = new JBCheckBox("Semantic highlighting (fixtures, Page Object fields and methods) from TypeScript");
        completion = new JBCheckBox("Code completion from TypeScript (page., locator., fixtures, Page Objects)");
        diagnostics = new JBCheckBox("TypeScript errors in the editor, with quick fixes (Alt+Enter: Add import...)");
        rainbowBrackets = new JBCheckBox("Rainbow brackets in TS/JS files (color by nesting depth)");
        semanticHighlighting.setToolTipText("Unique colors for parameters and local variables: Settings | Editor | Color Scheme | Language Defaults | Semantic highlighting");
        showBrowser = new JBCheckBox("Show the browser while debugging (--headed)");
        exploreLocators = new JBCheckBox("Explore locators: highlight the locator under the caret in that browser");
        reuseBrowser = new JBCheckBox("Show browser and keep it open between runs (Run and Debug)");
        pauseOnFailure = new JBCheckBox("Pause on the failing line of a test while debugging (like VS Code)");
        disableJestMarker = new JBCheckBox("Work around GigaIDE's broken Jest gutter marker in test files (restart required)");
        hideTsxMarker = new JBCheckBox("Hide GigaIDE's tsx Run icon in Playwright test files (restart required)");
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
                .addLabeledComponent("TypeScript server:", tsBackend)
                .addComponentToRightColumn(hint("<html>Auto: native TypeScript 7, then tsserver.js, then typescript-language-server.<br>"
                        + "Node.js servers: TypeScript 5/6 of the project, when native binaries are forbidden</html>"))
                .addLabeledComponent("TypeScript 7 tsc/tsgo or tsserver.js:", tsServerPath)
                .addComponentToRightColumn(hint("Default: found in node_modules of the project"))
                .addLabeledComponent("Navigation timeout, ms:", navigationTimeout)
                .addComponent(completion)
                .addComponent(diagnostics)
                .addComponent(semanticHighlighting)
                .addComponent(rainbowBrackets)
                .addSeparator()
                .addLabeledComponent("vscode-js-debug folder:", jsDebugPath)
                .addComponentToRightColumn(hint("Detected: " + (jsDebug == null ? "not found" : jsDebug.toString())))
                .addComponent(skipInternals)
                .addComponent(reuseBrowser)
                .addComponent(exploreLocators)
                .addComponent(showBrowser)
                .addComponent(pauseOnFailure)
                .addSeparator()
                .addComponent(disableJestMarker)
                .addComponent(hideTsxMarker)
                .addComponentFillVertically(new javax.swing.JPanel(), 0)
                .getPanel();
    }

    private @NotNull String selectedBackend() {
        int index = tsBackend.getSelectedIndex();
        return index <= 0 ? "" : TsServerLocator.Backend.values()[index - 1].name().toLowerCase();
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
                || !selectedBackend().equals(s.tsBackend)
                || !jsDebugPath.getText().trim().equals(s.jsDebugPath)
                || navigationEnabled.isSelected() != s.navigationEnabled
                || (Integer) navigationTimeout.getValue() != s.navigationTimeoutMs
                || skipInternals.isSelected() != s.skipPlaywrightInternals
                || semanticHighlighting.isSelected() != s.semanticHighlighting
                || completion.isSelected() != s.completion
                || diagnostics.isSelected() != s.diagnostics
                || rainbowBrackets.isSelected() != s.rainbowBrackets
                || showBrowser.isSelected() != s.showBrowserWhenDebugging
                || pauseOnFailure.isSelected() != s.pauseOnFailure
                || reuseBrowser.isSelected() != s.reuseBrowser
                || exploreLocators.isSelected() != s.exploreLocators
                || disableJestMarker.isSelected() != s.disableGigaJestMarker
                || hideTsxMarker.isSelected() != s.hideGigaTsxMarker;
    }

    @Override
    public void apply() {
        PwSettings.StateBean s = PwSettings.getInstance().getState();
        boolean serverChanged = !tsServerPath.getText().trim().equals(s.tsServerPath) || navigationEnabled.isSelected() != s.navigationEnabled
                || !selectedBackend().equals(s.tsBackend);
        s.tsBackend = selectedBackend();
        s.nodePath = nodePath.getText().trim();
        s.tsServerPath = tsServerPath.getText().trim();
        s.jsDebugPath = jsDebugPath.getText().trim();
        s.navigationEnabled = navigationEnabled.isSelected();
        s.navigationTimeoutMs = (Integer) navigationTimeout.getValue();
        s.skipPlaywrightInternals = skipInternals.isSelected();
        s.semanticHighlighting = semanticHighlighting.isSelected();
        s.completion = completion.isSelected();
        if (s.diagnostics && !diagnostics.isSelected()) {
            for (var project : ProjectManager.getInstance().getOpenProjects()) dev.pwtoolkit.nav.TsSemanticHighlighter.getInstance(project).clearDiagnostics();
        }
        s.diagnostics = diagnostics.isSelected();
        s.rainbowBrackets = rainbowBrackets.isSelected();
        s.showBrowserWhenDebugging = showBrowser.isSelected();
        s.pauseOnFailure = pauseOnFailure.isSelected();
        if (s.reuseBrowser && !reuseBrowser.isSelected()) {
            for (var project : ProjectManager.getInstance().getOpenProjects()) dev.pwtoolkit.run.PwBrowserServer.getInstance(project).stopAll();
        }
        s.reuseBrowser = reuseBrowser.isSelected();
        s.exploreLocators = exploreLocators.isSelected();
        if (disableJestMarker.isSelected() && !s.disableGigaJestMarker) dev.pwtoolkit.compat.GigaJestMarkerWorkaround.apply();
        s.disableGigaJestMarker = disableJestMarker.isSelected();
        s.hideGigaTsxMarker = hideTsxMarker.isSelected();
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
        int index = 0;
        for (TsServerLocator.Backend backend : TsServerLocator.Backend.values()) {
            if (backend.name().equalsIgnoreCase(s.tsBackend)) index = backend.ordinal() + 1;
        }
        tsBackend.setSelectedIndex(index);
        jsDebugPath.setText(s.jsDebugPath);
        navigationEnabled.setSelected(s.navigationEnabled);
        navigationTimeout.setValue(s.navigationTimeoutMs);
        skipInternals.setSelected(s.skipPlaywrightInternals);
        semanticHighlighting.setSelected(s.semanticHighlighting);
        completion.setSelected(s.completion);
        diagnostics.setSelected(s.diagnostics);
        rainbowBrackets.setSelected(s.rainbowBrackets);
        showBrowser.setSelected(s.showBrowserWhenDebugging);
        pauseOnFailure.setSelected(s.pauseOnFailure);
        reuseBrowser.setSelected(s.reuseBrowser);
        exploreLocators.setSelected(s.exploreLocators);
        disableJestMarker.setSelected(s.disableGigaJestMarker);
        hideTsxMarker.setSelected(s.hideGigaTsxMarker);
    }
}
