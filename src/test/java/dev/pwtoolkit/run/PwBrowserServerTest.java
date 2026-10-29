package dev.pwtoolkit.run;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.settings.PwSettings;

import java.io.File;
import java.util.List;

/** "Show browser": one browser kept open by the IDE's run-server between test runs. */
public class PwBrowserServerTest extends BasePlatformTestCase {
    @Override
    protected void tearDown() throws Exception {
        try {
            PwSettings.getInstance().getState().reuseBrowser = false;
            PwBrowserServer.getInstance(getProject()).stopAll();
        } finally {
            super.tearDown();
        }
    }

    public void testBrowserIsKeptBetweenRuns() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        PwRunConfiguration config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        config.setWorkingDirectory(sample.getPath());
        config.setTestPath(new File(sample, "tests/login/login.test.ts").getPath());
        config.setTestLine(5);
        PwSettings.getInstance().getState().reuseBrowser = true;

        PwLaunchSpec spec = PwLaunchSpec.create(config, false);
        String endpoint = spec.env().get("PW_TEST_CONNECT_WS_ENDPOINT");
        assertNotNull(endpoint);
        assertTrue(endpoint, endpoint.startsWith("ws://127.0.0.1:"));
        assertEquals("1", spec.env().get("PW_TEST_REUSE_CONTEXT"));
        assertContainsElements(spec.args(), "--headed");
        // validation (Run Configurations dialog) never starts the server
        PwBrowserServer.getInstance(getProject()).stopAll();
        PwLaunchSpec.create(config, false, false);
        assertFalse(PwBrowserServer.getInstance(getProject()).isRunning());

        spec = PwLaunchSpec.create(config, false);
        endpoint = spec.env().get("PW_TEST_CONNECT_WS_ENDPOINT");
        // headless here, so that no window pops up while the test suite runs
        List<String> args = spec.args().stream().filter(a -> !a.equals("--headed")).toList();
        String firstRun = run(spec, args).getStdout();
        assertTrue(firstRun, firstRun.contains("1 passed"));
        long browsers = chromeProcesses();
        assertTrue("browser must stay open after the run", browsers > 0);
        assertEquals("the second run reuses the same browser", endpoint, PwLaunchSpec.create(config, false).env().get("PW_TEST_CONNECT_WS_ENDPOINT"));
        assertTrue(run(spec, args).getStdout().contains("1 passed"));
        assertEquals(browsers, chromeProcesses());

        PwBrowserServer.getInstance(getProject()).stopAll();
        for (int i = 0; i < 50 && chromeProcesses() > 0; i++) Thread.sleep(100);
        assertEquals("Close Browser closes it", 0, chromeProcesses());
    }

    /** Pick Locator: Playwright accepts the picking mode; a click (inspectRequested) gives the locator, the mode is switched off. */
    public void testPickLocatorMode() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        String node = dev.pwtoolkit.util.NodeLocator.findNode(null);
        String cli = PwLaunchSpec.findCli(sample.toPath());
        PwBrowserServer.getInstance(getProject()).ensureStarted(sample, node, cli);
        PwDebugController controller = PwBrowserServer.getInstance(getProject()).controller();
        assertNotNull(controller);
        var picked = controller.pickLocator("data-testid");
        Thread.sleep(8000); // the server launches a browser and a page for picking
        assertFalse("setRecorderMode rejected: " + picked, picked.isCompletedExceptionally());
        var params = new com.google.gson.JsonObject();
        params.addProperty("selector", "internal:role=button[name=\"Sign in\"i]");
        params.addProperty("locator", "getByRole('button', { name: 'Sign in' })");
        params.addProperty("ariaSnapshot", "- button \"Sign in\"");
        controller.onEvent("inspectRequested", params);
        assertEquals("getByRole('button', { name: 'Sign in' })", picked.get(5, java.util.concurrent.TimeUnit.SECONDS).locator());
        // the mode is switched off: highlighting still works afterwards
        controller.highlight("locator('body')").get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static ProcessOutput run(PwLaunchSpec spec, List<String> args) throws Exception {
        GeneralCommandLine line = new GeneralCommandLine(spec.node(), spec.cli()).withParameters(args)
                .withWorkDirectory(spec.workDir()).withEnvironment(spec.env())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        return new CapturingProcessHandler(line).runProcess(120_000);
    }

    /** Chrome for Testing processes started by this JVM's run-server (descendants of this process). */
    private static long chromeProcesses() {
        return ProcessHandle.current().descendants()
                .filter(p -> p.info().command().orElse("").contains("Chrome for Testing"))
                .count();
    }
}
