package dev.pwtoolkit.completion;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.settings.PwSettings;

import java.util.Arrays;
import java.util.List;

/**
 * GigaIDE's JavaScript plugin offers nothing after "page." (it does not know the types from node_modules):
 * the items come from the TypeScript server. Subclasses: the Node.js servers.
 */
public class TsCompletionTest extends BasePlatformTestCase {
    private String savedBackend;
    private String savedServerPath;
    private Document document;
    private String original;

    protected String backend() {
        return "native";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var state = PwSettings.getInstance().getState();
        savedBackend = state.tsBackend;
        savedServerPath = state.tsServerPath;
        state.tsBackend = backend();
        state.tsServerPath = backend().equals("native") ? "" : System.getProperty("pwtoolkit.ts5");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (document != null) {
                WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
                FileDocumentManager.getInstance().saveDocument(document);
            }
            TsLanguageService.getInstance(getProject()).restartAll();
            var state = PwSettings.getInstance().getState();
            state.tsBackend = savedBackend;
            state.tsServerPath = savedServerPath;
        } finally {
            super.tearDown();
        }
    }

    public void testPageMembersInPageObject() {
        List<String> items = completeAt("pages/loginPage.page.ts", "await this.page.waitFor", "fill(this.selectors.loginInput");
        assertTrue(items.toString(), items.containsAll(List.of("waitForResponse", "waitForTimeout", "waitForSelector")));
        assertFalse(items.toString(), items.contains("fill"));
    }

    public void testPageMembersOfFixtureInTest() {
        List<String> items = completeAt("tests/login/login.test.ts", "await page.", "await loginPage.open();");
        assertTrue(items.toString(), items.containsAll(List.of("locator", "click", "fill", "waitForTimeout", "goto")));
    }

    public void testKeywordsAndGlobals() {
        List<String> items = completeAt("tests/login/login.test.ts", "awa", "await loginPage.open();");
        // a single variant is inserted at once
        assertTrue(items + " " + document.getText(), items.contains("await") || document.getText().contains("await\n        await loginPage.open();"));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
        FileDocumentManager.getInstance().saveDocument(document);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        items = completeAt("tests/login/login.test.ts", "cons", "await loginPage.open();");
        assertTrue(items.toString(), items.containsAll(List.of("console", "const")));
    }

    public void testLocatorMembersAndMethodParentheses() {
        List<String> items = completeAt("pages/loginPage.page.ts", "await this.page.locator('#a').innerTe", "fill(this.selectors.loginInput");
        assertEquals("own members first", "innerText", items.get(0));
        myFixture.getLookup().setCurrentItem(myFixture.getLookupElements()[0]);
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR);
        String text = document.getText();
        int at = text.indexOf("await this.page.locator('#a')");
        assertTrue(items + " " + text.substring(at, at + 60), text.contains("await this.page.locator('#a').innerText()"));
    }

    /** Inserts {@code typed} before {@code anchor} (caret after it) and returns the offered names. */
    private List<String> completeAt(String path, String typed, String anchor) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample") + "/" + path);
        myFixture.configureFromExistingVirtualFile(file);
        document = myFixture.getEditor().getDocument();
        original = document.getText();
        int at = original.indexOf(anchor);
        assertTrue(anchor, at >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(at, typed + "\n        "));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        myFixture.getEditor().getCaretModel().moveToOffset(at + typed.length());
        LookupElement[] elements = myFixture.completeBasic();
        return elements == null ? List.of() : Arrays.stream(elements).map(LookupElement::getLookupString).toList();
    }
}
