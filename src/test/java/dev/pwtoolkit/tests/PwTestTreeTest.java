package dev.pwtoolkit.tests;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.Location;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.TestStateStorage;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.run.PwRunConfiguration;
import dev.pwtoolkit.run.PwRunConfigurationType;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Real `playwright test` run through the "Playwright Test" configuration, checked in the IDE test tree. */
public class PwTestTreeTest extends BasePlatformTestCase {
    private File dir;

    @Override
    protected void tearDown() throws Exception {
        try {
            if (dir != null) FileUtil.delete(dir);
        } finally {
            super.tearDown();
        }
    }

    public void testTreeFromReporter() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        dir = new File(sample, "tests/tmp-tree");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        File testFile = new File(dir, "tree.test.ts");
        Files.writeString(testFile.toPath(), """
                import test, { expect } from "../baseTest";

                test.describe("Tree", () => {
                    test("passes", async ({ header, page }) => {
                        await header.render("Hello");
                        await expect(page.locator(header.selectors.title)).toHaveText("Hello");
                    });

                    test("fails", async ({ header, page }) => {
                        await header.render("Hello");
                        await expect(page.locator(header.selectors.title)).toHaveText("Bye", { timeout: 300 });
                    });

                    test.skip("skipped", async () => {});
                });
                """);

        PwRunConfiguration config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory()
                .createTemplateConfiguration(getProject());
        config.setWorkingDirectory(sample.getPath());
        config.setTestPath(testFile.getPath());

        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        ExecutionEnvironment environment = ExecutionEnvironmentBuilder.create(getProject(), executor, config).build();
        RunProfileState state = config.getState(executor, environment);
        ExecutionResult result = state.execute(executor, ProgramRunner.getRunner(executor.getId(), config));
        Disposer.register(getTestRootDisposable(), result.getExecutionConsole());
        result.getProcessHandler().startNotify();
        assertTrue("playwright did not finish", result.getProcessHandler().waitFor(120_000));
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        SMTestProxy.SMRootTestProxy root = ((SMTRunnerConsoleView) result.getExecutionConsole()).getResultsViewer().getTestsRootNode();
        List<? extends SMTestProxy> tests = root.getAllTests();
        SMTestProxy passes = find(tests, "passes");
        SMTestProxy fails = find(tests, "fails");
        SMTestProxy skipped = find(tests, "skipped");
        SMTestProxy suite = find(tests, "Tree");
        assertTrue(passes.isPassed());
        assertTrue(fails.isDefect());
        assertTrue(fails.getErrorMessage(), fails.getErrorMessage().contains("toHaveText"));
        assertTrue("stack: " + fails.getStacktrace(), fails.getStacktrace().contains("tree.test.ts:11"));
        assertTrue(skipped.isIgnored());
        assertSame(suite, passes.getParent());

        // double click in the tree goes to the test line
        assertEquals(PwTestLocator.url(testFile.getPath(), 9), fails.getLocationUrl());
        List<Location> locations = PwTestLocator.INSTANCE.getLocation(PwTestLocator.PROTOCOL,
                testFile.getPath() + ":9", getProject(), GlobalSearchScope.allScope(getProject()));
        assertEquals(1, locations.size());
        Document document = FileDocumentManager.getInstance().getDocument(locations.get(0).getVirtualFile());
        assertEquals(8, document.getLineNumber(locations.get(0).getPsiElement().getTextOffset()));

        // the failed test's output in the console has clickable links to the stack lines
        SMTRunnerConsoleView view = (SMTRunnerConsoleView) result.getExecutionConsole();
        view.getComponent();
        view.getResultsViewer().selectAndNotify(fails);
        var printer = (com.intellij.execution.impl.ConsoleViewImpl) view.getConsole();
        String consoleText = "";
        boolean linked = false;
        for (int i = 0; i < 100 && !linked; i++) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            printer.flushDeferredText();
            var editor = printer.getEditor();
            consoleText = editor.getDocument().getText();
            String text = consoleText;
            linked = com.intellij.execution.impl.EditorHyperlinkSupport.get(editor).getAllHyperlinks(0, text.length()).stream()
                    .anyMatch(h -> text.substring(h.getStartOffset(), h.getEndOffset()).contains("tree.test.ts:11"));
            if (!linked) Thread.sleep(100);
        }
        assertTrue("no link to tree.test.ts:11 in:\n" + consoleText, linked);
        assertEquals("the error message is printed once", consoleText.indexOf("toHaveText(expected) failed"),
                consoleText.lastIndexOf("toHaveText(expected) failed"));

        // gutter icons read the last state by the same URL
        assertNotNull("state for the gutter icon", TestStateStorage.getInstance(getProject()).getState(fails.getLocationUrl()));

        // "Rerun Failed Tests" runs exactly the failed test by file:line
        assertEquals(java.util.Set.of(testFile.getPath() + ":9"), PwRerunFailedTestsAction.failedTargets(List.of(passes, fails, skipped)));
        var restart = ((com.intellij.execution.DefaultExecutionResult) result).getRestartActions();
        assertTrue("Rerun Failed Tests button", restart.length > 0 && restart[0] instanceof PwRerunFailedTestsAction);
    }

    private static SMTestProxy find(List<? extends SMTestProxy> tests, String name) {
        SMTestProxy test = tests.stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
        assertNotNull("no node " + name + " in " + tests, test);
        return test;
    }
}
