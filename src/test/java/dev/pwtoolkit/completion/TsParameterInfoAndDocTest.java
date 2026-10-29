package dev.pwtoolkit.completion;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext;
import dev.pwtoolkit.nav.TsDocumentationProvider;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.settings.PwSettings;

/** "selectFromDropDown(" shows its parameters; hovering a Playwright method shows its signature and JSDoc. */
public class TsParameterInfoAndDocTest extends BasePlatformTestCase {
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

    public void testParameterInfoOfPageObjectMethod() {
        open("tests/login/login.test.ts", "await loginPage.open();", "await loginPage.login(\"admin\", )");
        int caret = document.getText().indexOf("\"admin\", )") + "\"admin\", ".length();
        myFixture.getEditor().getCaretModel().moveToOffset(caret);

        TsParameterInfoHandler handler = new TsParameterInfoHandler();
        MockCreateParameterInfoContext create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        PsiElement owner = handler.findElementForParameterInfo(create);
        assertNotNull("no parameter info", owner);
        TsLanguageService.Signature signature = (TsLanguageService.Signature) create.getItemsToShow()[0];
        assertTrue(signature.label(), signature.label().contains("login: string") && signature.label().contains("password: string"));
        assertEquals(2, signature.parameters().size());

        MockUpdateParameterInfoContext update = new MockUpdateParameterInfoContext(myFixture.getEditor(), myFixture.getFile(), create.getItemsToShow());
        update.setParameterOwner(owner);
        assertSame(owner, handler.findElementForUpdatingParameterInfo(update));
        handler.updateParameterInfo(owner, update);
        assertEquals("the caret is in the second argument", 1, update.getCurrentParameter());
    }

    public void testHoverDocumentationOfPlaywrightMethod() {
        open("pages/loginPage.page.ts", "fill(this.selectors.loginInput", "");
        int offset = document.getText().indexOf("fill(this.selectors.loginInput");
        PsiElement leaf = myFixture.getFile().findElementAt(offset);
        TsDocumentationProvider provider = new TsDocumentationProvider();
        PsiElement target = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), leaf, offset);
        assertSame(leaf, target);
        String doc = provider.generateDoc(target, leaf);
        assertNotNull(doc);
        assertTrue(doc, doc.contains("fill(selector: string, value: string"));
        assertTrue(doc, doc.toLowerCase().contains("fill"));
    }

    private void open(String path, String anchor, String insert) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample") + "/" + path);
        myFixture.configureFromExistingVirtualFile(file);
        document = myFixture.getEditor().getDocument();
        original = document.getText();
        if (insert.isEmpty()) return;
        int at = original.indexOf(anchor);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(at, insert + "\n        "));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }
}
