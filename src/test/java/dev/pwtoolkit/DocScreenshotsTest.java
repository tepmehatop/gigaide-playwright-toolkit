package dev.pwtoolkit;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.nav.TsSemanticHighlighter;
import dev.pwtoolkit.run.PwRunConfiguration;
import dev.pwtoolkit.run.PwRunConfigurationType;
import dev.pwtoolkit.settings.PwConfigurable;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Renders real plugin UI into docs/images for the user guide. Runs only with -Dpwtoolkit.docs.dir=<dir>.
 * Paths shown in the pictures are neutral examples (no local user names).
 */
public class DocScreenshotsTest extends BasePlatformTestCase {
    private File out;
    private File sample;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        String dir = System.getProperty("pwtoolkit.docs.dir");
        out = dir == null ? null : new File(dir);
        sample = new File(System.getProperty("pwtoolkit.sample"));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            TsLanguageService.getInstance(getProject()).restartAll();
        } finally {
            super.tearDown();
        }
    }

    public void testScreenshots() throws Exception {
        if (out == null) return;
        assertTrue(out.mkdirs() || out.isDirectory());
        captureRunConfiguration();
        captureSettings();
        captureEditor();
        captureTestTree();
    }

    private void captureRunConfiguration() throws Exception {
        PwRunConfiguration config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        config.setWorkingDirectory("~/projects/autotests");
        config.setTestPath("~/projects/autotests/tests/ui/mainPage.test.ts");
        config.setTestLine(30);
        config.setProjectName("ui-tests");
        config.setTestName("@smoke");
        config.setRetries("1");
        config.setRepeatEach(3);
        config.setMaxFailures(1);
        config.setTraceMode("retain-on-failure");
        @SuppressWarnings("unchecked")
        SettingsEditor<PwRunConfiguration> editor = (SettingsEditor<PwRunConfiguration>) config.getConfigurationEditor();
        Disposer.register(getTestRootDisposable(), editor);
        JComponent component = editor.getComponent();
        editor.resetFrom(config);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        save(component, 900, 0, "run-configuration.png");
    }

    private void captureSettings() throws Exception {
        PwConfigurable configurable = new PwConfigurable();
        JComponent component = configurable.createComponent();
        configurable.reset();
        save(component, 900, 0, "settings.png");
        configurable.disposeUIResources();
    }

    private void captureEditor() throws Exception {
        EditorColorsManager manager = EditorColorsManager.getInstance();
        EditorColorsScheme original = manager.getGlobalScheme();
        try {
            manager.setGlobalScheme(manager.getScheme("Darcula"));
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(sample.getPath() + "/tests/login/login.test.ts");
            myFixture.configureFromExistingVirtualFile(file);
            myFixture.doHighlighting();
            TsSemanticHighlighter.getInstance(getProject()).highlight(file);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            JComponent component = myFixture.getEditor().getComponent();
            save(component, 1100, 620, "editor.png");
        } finally {
            manager.setGlobalScheme(original);
        }
    }

    private void captureTestTree() throws Exception {
        File dir = new File(sample, "tests/demo");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        File testFile = new File(dir, "header.test.ts");
        Files.writeString(testFile.toPath(), """
                import test, { expect } from "../baseTest";

                test.describe("Header", () => {
                    test("shows the title", async ({ page, header }) => {
                        await header.render("Playwright offline sample");
                        await expect(page.locator(header.selectors.title)).toHaveText("Playwright offline sample");
                    });

                    test("title is up to date", async ({ page, header }) => {
                        await header.render("Playwright offline sample");
                        await expect(page.locator(header.selectors.title)).toHaveText("Playwright Toolkit", { timeout: 300 });
                    });

                    test.skip("old layout", async () => {});
                });
                """);
        try {
            PwRunConfiguration config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
            config.setName("Header");
            config.setWorkingDirectory(sample.getPath());
            config.setTestPath(testFile.getPath());
            Executor executor = DefaultRunExecutor.getRunExecutorInstance();
            ExecutionEnvironment environment = ExecutionEnvironmentBuilder.create(getProject(), executor, config).build();
            ExecutionResult result = config.getState(executor, environment).execute(executor, ProgramRunner.getRunner(executor.getId(), config));
            Disposer.register(getTestRootDisposable(), result.getExecutionConsole());
            result.getProcessHandler().startNotify();
            assertTrue(result.getProcessHandler().waitFor(120_000));
            SMTRunnerConsoleView view = (SMTRunnerConsoleView) result.getExecutionConsole();
            JComponent component = view.getComponent();
            List<? extends SMTestProxy> tests = view.getResultsViewer().getTestsRootNode().getAllTests();
            SMTestProxy failed = tests.stream().filter(t -> t.getName().equals("title is up to date")).findFirst().orElseThrow();
            view.getResultsViewer().selectAndNotify(failed);
            for (int i = 0; i < 30; i++) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
                ((com.intellij.execution.impl.ConsoleViewImpl) view.getConsole()).flushDeferredText();
                Thread.sleep(100);
            }
            save(component, 1300, 560, "test-tree.png");
        } finally {
            FileUtil.delete(dir);
        }
    }

    private void save(JComponent component, int width, int height, String name) throws Exception {
        prepare(component);
        int h = height > 0 ? height : Math.max(component.getPreferredSize().height, 200);
        component.setSize(width, h);
        layout(component);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        layout(component);
        BufferedImage image = UIUtil.createImage(component, width, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(component.getBackground());
        g.fillRect(0, 0, width, h);
        component.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", new File(out, name));
        release(component);
    }

    private static void release(Component component) {
        if (component instanceof com.intellij.ui.EditorTextField field && field.getEditor() != null) field.removeNotify();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) release(child);
        }
    }

    /** Hides the local home folder (user name) and realizes editor-based text fields, which paint only when shown. */
    private static void prepare(Component component) {
        String home = System.getProperty("user.home");
        if (component instanceof javax.swing.JLabel label && label.getText() != null) label.setText(label.getText().replace(home, "~"));
        if (component instanceof com.intellij.ui.components.JBLabel label && label.getText() != null) label.setText(label.getText().replace(home, "~"));
        if (component instanceof javax.swing.text.JTextComponent field && field.getText() != null) field.setText(field.getText().replace(home, "~"));
        if (component instanceof com.intellij.ui.EditorTextField field) {
            try {
                field.addNotify();
            } catch (Throwable ignored) {
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) prepare(child);
        }
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) layout(child);
        }
    }
}
