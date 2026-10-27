package dev.pwtoolkit.run;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.settings.PwSettings;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PwLocatorExplorerTest extends BasePlatformTestCase {
    private File sample;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sample = new File(System.getProperty("pwtoolkit.sample"));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            PwSettings.getInstance().getState().reuseBrowser = false;
            PwBrowserServer.getInstance(getProject()).stopAll();
            TsLanguageService.getInstance(getProject()).restartAll();
        } finally {
            super.tearDown();
        }
    }

    private String locatorAt(String relativePath, String needle, int shift) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(sample.getPath() + "/" + relativePath);
        Document document = FileDocumentManager.getInstance().getDocument(file);
        int offset = document.getText().indexOf(needle);
        assertTrue(needle, offset >= 0);
        return PwLocatorExplorer.getInstance(getProject()).locatorAt(file, document, offset + shift);
    }

    public void testResolvesLocatorsFromCode() {
        assertEquals("locator('#greeting')", locatorAt("pages/loginPage.page.ts", "\"#greeting\"", 3));
        // Page Object selector referenced from a test: its value is found with TypeScript 7
        assertEquals("locator('#greeting')", locatorAt("tests/login/login.test.ts", "selectors.greeting", 12));
        assertEquals("locator('h1')", locatorAt("tests/login/login.test.ts", "page.locator(header.selectors.title)", 7));
        assertEquals("locator('#login')", locatorAt("pages/loginPage.page.ts", "this.selectors.loginInput", 18));
        assertNull("not a locator", locatorAt("tests/login/login.test.ts", "loginPage.login(", 12));
    }

    public void testHighlightsInTheKeptBrowser() throws Exception {
        PwSettings.getInstance().getState().reuseBrowser = true;
        PwRunConfiguration config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        config.setWorkingDirectory(sample.getPath());
        config.setTestPath(new File(sample, "tests/login/login.test.ts").getPath());
        config.setTestLine(20);
        PwLaunchSpec spec = PwLaunchSpec.create(config, false);
        List<String> args = spec.args().stream().filter(a -> !a.equals("--headed")).toList();
        String out = new CapturingProcessHandler(new GeneralCommandLine(spec.node(), spec.cli()).withParameters(args)
                .withWorkDirectory(spec.workDir()).withEnvironment(spec.env())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)).runProcess(120_000).getStdout();
        assertTrue(out, out.contains("1 passed"));

        PwDebugController controller = PwBrowserServer.getInstance(getProject()).controller();
        assertNotNull(controller);
        for (int i = 0; i < 50 && controller.pageCount() == 0; i++) Thread.sleep(100);
        assertEquals("the test page stays open", 1, controller.pageCount());
        controller.highlight("locator('h1')").get(10, TimeUnit.SECONDS);
        controller.highlight("getByText('Playwright offline sample')").get(10, TimeUnit.SECONDS);
        controller.hideHighlight().get(10, TimeUnit.SECONDS);
    }
}
