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
        config.setTraceMode("retain-on-failure");

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

        // results for the tree actions: trace and screenshot of the failed test
        PwRunResults results = PwRunResults.load(sample);
        assertNotNull(results);
        PwRunResults.Test failed = results.at(testFile.getPath(), 9).get(0);
        assertEquals("failed", failed.status());
        assertTrue(failed.attachments().toString(), failed.attachments().stream().anyMatch(a -> a.isTrace() && new File(a.path()).isFile()));
        assertTrue(failed.attachments().toString(), failed.attachments().stream().anyMatch(a -> a.isImage() && new File(a.path()).isFile()));
        assertTrue(results.at(testFile.getPath(), 4).get(0).attachments().isEmpty());

        // history of playwright-extended-report is shown in the test output
        File history = new File(sample, "extended-report-history.json");
        Files.writeString(history.toPath(), "{\"version\":1,\"runs\":[" +
                "{\"runId\":\"1\",\"tests\":{\"" + failed.id() + "\":[\"p\",100]}}," +
                "{\"runId\":\"2\",\"tests\":{\"" + failed.id() + "\":[\"p\",120]}}]}");
        try {
            ExecutionResult second = config.getState(executor, environment).execute(executor, ProgramRunner.getRunner(executor.getId(), config));
            Disposer.register(getTestRootDisposable(), second.getExecutionConsole());
            StringBuilder output = new StringBuilder();
            second.getProcessHandler().addProcessListener(new com.intellij.execution.process.ProcessListener() {
                @Override
                public void onTextAvailable(@org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent event,
                                            @org.jetbrains.annotations.NotNull com.intellij.openapi.util.Key outputType) {
                    output.append(event.getText());
                }
            });
            second.getProcessHandler().startNotify();
            assertTrue(second.getProcessHandler().waitFor(120_000));
            assertTrue(output.toString(), output.toString().contains("History (previous 2 runs, extended report): ✓✓ — failed 0 of 2, NEW failure"));
        } finally {
            FileUtil.delete(history);
        }
    }

    private static SMTestProxy find(List<? extends SMTestProxy> tests, String name) {
        SMTestProxy test = tests.stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
        assertNotNull("no node " + name + " in " + tests, test);
        return test;
    }

    public void testEnvironmentCheck() {
        List<String> lines = PwToolsActions.check(new File(System.getProperty("pwtoolkit.sample")));
        String report = String.join("\n", lines);
        assertTrue(report, lines.stream().anyMatch(l -> l.startsWith("✓ Node.js")));
        assertTrue(report, lines.stream().anyMatch(l -> l.startsWith("✓ Playwright: Version 1.")));
        assertTrue(report, lines.stream().anyMatch(l -> l.startsWith("✓ Chrome for Testing")));
        assertTrue(report, lines.stream().anyMatch(l -> l.startsWith("✓ TypeScript 7")));
        assertTrue(report, lines.stream().anyMatch(l -> l.startsWith("✓ vscode-js-debug")));
    }
}
