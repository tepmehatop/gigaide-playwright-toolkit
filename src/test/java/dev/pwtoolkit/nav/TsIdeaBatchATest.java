package dev.pwtoolkit.nav;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.templates.PwNewTestActionAccess;

/** Type Info, Go to Type Declaration, "N usages", New Playwright Test - with each TypeScript server. */
public class TsIdeaBatchATest extends BasePlatformTestCase {
    private String savedBackend;
    private String savedServerPath;
    private VirtualFile sampleRoot;
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
        sampleRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample"));
        com.intellij.testFramework.PsiTestUtil.addContentRoot(getModule(), sampleRoot);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (document != null) {
                WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
                FileDocumentManager.getInstance().saveDocument(document);
            }
            TsLanguageService.getInstance(getProject()).restartAll();
            com.intellij.testFramework.PsiTestUtil.removeContentEntry(getModule(), sampleRoot);
            var state = PwSettings.getInstance().getState();
            state.tsBackend = savedBackend;
            state.tsServerPath = savedServerPath;
        } finally {
            super.tearDown();
        }
    }

    public void testTypeInfo() {
        open("tests/login/login.test.ts");
        PsiElement fixture = myFixture.getFile().findElementAt(document.getText().indexOf("loginPage.login("));
        TsTypeInfoProvider provider = new TsTypeInfoProvider();
        assertEquals(1, provider.getExpressionsAt(fixture).size());
        assertEquals("LoginPage", provider.getInformationHint(fixture));
        assertEquals("Page", TsTypeInfoProvider.typeOf("(parameter) page: Page", "page"));
        assertEquals("{ login: string; }", TsTypeInfoProvider.typeOf("const user: { login: string; }", "user"));
    }

    public void testGoToTypeDeclaration() {
        open("tests/login/login.test.ts");
        PsiElement[] loginPage = typeOf("loginPage.login(");
        assertNotNull(loginPage);
        assertEquals("loginPage.page.ts", loginPage[0].getContainingFile().getName());
        PsiElement[] page = typeOf("page.textContent");
        assertNotNull(page);
        assertTrue(page[0].getContainingFile().getName(), page[0].getContainingFile().getName().endsWith(".d.ts"));
    }

    public void testUsagesCodeVision() {
        open("pages/loginPage.page.ts");
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf("login(login: string") + 1);
        PsiElement login = TargetElementUtil.findTargetElement(myFixture.getEditor(), TargetElementUtil.ELEMENT_NAME_ACCEPTED);
        TsUsagesCodeVisionProvider provider = new TsUsagesCodeVisionProvider();
        assertTrue(provider.acceptsFile(myFixture.getFile()));
        assertTrue(login.getClass().getName(), provider.acceptsElement(login));
        assertEquals("1 usage", provider.getHint(login, myFixture.getFile()));
    }

    public void testNewPlaywrightTestFollowsTheProject() {
        VirtualFile tests = sampleRoot.findFileByRelativePath("tests/login");
        assertEquals(".test.ts", PwNewTestActionAccess.testSuffix(getProject()));
        assertEquals("import test, { expect } from \"../baseTest\";", PwNewTestActionAccess.imports(getProject(), tests));
        assertEquals("Checkout flow", PwNewTestActionAccess.words("checkoutFlow"));
        assertEquals("cart.test.ts", PwNewTestActionAccess.fileName("cart.spec.ts", ".test.ts"));
    }

    private PsiElement[] typeOf(String needle) {
        int offset = document.getText().indexOf(needle);
        PsiElement symbol = TsDeclarations.declarationAt(getProject(), myFixture.getFile().getVirtualFile(), offset, 10_000);
        if (symbol == null) symbol = myFixture.getFile().findElementAt(offset);
        // the type is asked at the symbol's own position
        return new TsTypeDeclarationProvider().getSymbolTypeDeclarations(myFixture.getFile().findElementAt(offset));
    }

    private void open(String path) {
        VirtualFile file = sampleRoot.findFileByRelativePath(path);
        myFixture.configureFromExistingVirtualFile(file);
        document = myFixture.getEditor().getDocument();
        original = document.getText();
    }
}
