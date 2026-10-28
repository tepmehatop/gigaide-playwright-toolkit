package dev.pwtoolkit.nav;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Talks to the real TypeScript 7 server installed in samples/playwright-offline/node_modules (subclasses: Node.js servers). */
public class TsNavigationTest extends BasePlatformTestCase {
    private VirtualFile sample;

    private String savedBackend;
    private String savedServerPath;

    /** Subclasses run the same checks with the Node.js servers: setting value and tsserver.js to use. */
    protected String backend() {
        return "native";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var state = dev.pwtoolkit.settings.PwSettings.getInstance().getState();
        savedBackend = state.tsBackend;
        savedServerPath = state.tsServerPath;
        state.tsBackend = backend();
        state.tsServerPath = backend().equals("native") ? "" : System.getProperty("pwtoolkit.ts5");
        sample = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample"));
        assertNotNull("sample project not found", sample);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            TsLanguageService.getInstance(getProject()).restartAll();
            var state = dev.pwtoolkit.settings.PwSettings.getInstance().getState();
            state.tsBackend = savedBackend;
            state.tsServerPath = savedServerPath;
        } finally {
            super.tearDown();
        }
    }

    public void testFixtureFromDestructuring() throws Exception {
        assertDefinition("tests/login/login.test.ts", "loginPage }) =>", "baseTest.ts", 5);
    }

    public void testPageObjectMethod() throws Exception {
        assertDefinition("tests/login/login.test.ts", "login(user.login", "loginPage.page.ts", 24);
    }

    public void testPageObjectField() throws Exception {
        assertDefinition("tests/login/login.test.ts", "greeting);", "loginPage.page.ts", 13);
    }

    public void testJsonProperty() throws Exception {
        assertDefinition("tests/login/login.test.ts", "admin;", "users.json", 1);
    }

    public void testImportedDefaultExport() throws Exception {
        assertDefinition("tests/baseTest.ts", "LoginPage(page)", "loginPage.page.ts", 2);
    }

    /** The exact case from the bug report: a property of a local object literal, in an unsaved editor. */
    public void testUnsavedLocalObjectProperty() throws Exception {
        VirtualFile file = sample.findFileByRelativePath("tests/login/login.test.ts");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        String original = document.getText();
        try {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original +
                    "\nconst endpoints = {\n    getEndpoint: 'https://example.com/api/data',\n};\nconsole.log(endpoints.getEndpoint);\n"));
            int firstLine = original.split("\n", -1).length;
            assertDefinition("tests/login/login.test.ts", "getEndpoint);", "login.test.ts", firstLine + 1);
        } finally {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
            FileDocumentManager.getInstance().saveDocument(document);
        }
    }

    /** Highlighting data for a test file: fixtures are parameters, selectors are properties, PO calls are methods. */
    public void testSemanticTokens() throws Exception {
        VirtualFile file = sample.findFileByRelativePath("tests/login/login.test.ts");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        String text = document.getText();
        CompletableFuture<List<TsLanguageService.SemanticToken>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.SemanticToken>>>) () ->
                        TsLanguageService.getInstance(getProject()).semanticTokens(file, document));
        assertNotNull(future);
        List<TsLanguageService.SemanticToken> tokens = future.get(30, TimeUnit.SECONDS);
        assertTokenType(text, tokens, "loginPage.login(", "parameter");
        assertTokenType(text, tokens, "login(user.login", "method");
        assertTokenType(text, tokens, "selectors.greeting", "property");
        assertTokenType(text, tokens, "greeting);", "property");

        TsLanguageService.SemanticToken fixture = tokenAt(tokens, text.indexOf("loginPage.login("));
        assertTrue(TsSemanticColors.keyFor(fixture, true).getExternalName().contains("PARAMETER"));
    }

    private static void assertTokenType(String text, List<TsLanguageService.SemanticToken> tokens, String needle, String type) {
        TsLanguageService.SemanticToken token = tokenAt(tokens, text.indexOf(needle));
        assertNotNull("no token at " + needle, token);
        assertEquals(needle, type, token.type());
    }

    private static TsLanguageService.SemanticToken tokenAt(List<TsLanguageService.SemanticToken> tokens, int offset) {
        return tokens.stream().filter(t -> t.start() == offset).findFirst().orElse(null);
    }

    private void assertDefinition(String fromFile, String needle, String expectedFile, int expectedLine) throws Exception {
        VirtualFile file = sample.findFileByRelativePath(fromFile);
        assertNotNull(fromFile, file);
        Document document = FileDocumentManager.getInstance().getDocument(file);
        int offset = document.getText().indexOf(needle);
        assertTrue("needle not found: " + needle, offset >= 0);
        CompletableFuture<List<TsLanguageService.Location>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.Location>>>) () ->
                        TsLanguageService.getInstance(getProject()).definition(file, document, offset));
        assertNotNull("language server was not started: " + TsLanguageService.getInstance(getProject()).describe(file), future);
        List<TsLanguageService.Location> locations = future.get(30, TimeUnit.SECONDS);
        assertFalse("no definition for " + needle, locations.isEmpty());
        TsLanguageService.Location location = locations.get(0);
        assertEquals(locations.toString(), expectedFile, location.file().getFileName().toString());
        assertEquals(locations.toString(), expectedLine, location.line());
    }
}
