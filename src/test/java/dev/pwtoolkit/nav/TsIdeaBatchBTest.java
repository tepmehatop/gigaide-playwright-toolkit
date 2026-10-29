package dev.pwtoolkit.nav;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.refactor.TsRefactorings;
import dev.pwtoolkit.settings.PwSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Go to Super, implementations, hierarchy gutter icons, call hierarchy, refactorings and Extract - with each server. */
public class TsIdeaBatchBTest extends BasePlatformTestCase {
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
        // what "Install TypeScript 5 for the IDE" does: refactorings next to the native server
        Path managed = TsServerLocator.managedTypeScript5Dir().resolve("node_modules");
        Files.createDirectories(managed);
        Path typescript = managed.resolve("typescript");
        if (!Files.exists(typescript)) Files.createSymbolicLink(typescript, Path.of(System.getProperty("pwtoolkit.ts5")).getParent().getParent());
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

    public void testSupersImplementationsAndGutterIcons() {
        open("pages/searchPage.page.ts");
        PsiElement open = declaration("open() {");
        List<TsHierarchy.Super> supers = TsHierarchy.supers(getProject(), open);
        assertEquals(1, supers.size());
        assertEquals("basePage.page.ts", supers.get(0).element().getContainingFile().getName());
        assertFalse("overrides a class member", supers.get(0).fromInterface());
        assertSame(open, TsGotoSuperHandler.owner(myFixture.getFile().findElementAt(document.getText().indexOf("open() {") + 2)));
        List<String> markers = markers();
        assertTrue(markers.toString(), markers.contains("Overrides BasePage.open"));

        open("pages/basePage.page.ts");
        PsiElement abstractOpen = declaration("open(): Promise<void>;\n\n    public");
        List<TsHierarchy.Super> fromInterface = TsHierarchy.supers(getProject(), abstractOpen);
        assertEquals(1, fromInterface.size());
        assertTrue("implements an interface member", fromInterface.get(0).fromInterface());

        PsiElement basePage = declaration("BasePage implements");
        List<PsiElement> subclasses = new ArrayList<>(DefinitionsScopedSearch.search(basePage).findAll());
        assertTrue(subclasses.toString(), subclasses.stream().anyMatch(e -> "SearchPage".equals(((PsiNamedElement) e).getName())));
        markers = markers();
        assertTrue(markers.toString(), markers.contains("Has subclasses: SearchPage"));
        assertTrue(markers.toString(), markers.contains("Overridden in SearchPage"));
    }

    public void testCallHierarchy() {
        open("pages/loginPage.page.ts");
        PsiElement login = declaration("login(login: string");
        TsLanguageService.CallItem item = TsCallHierarchyProvider.prepare(getProject(), login);
        assertNotNull(item);
        List<TsLanguageService.CallItem> callers = TsCallHierarchyProvider.calls(getProject(), item, true);
        assertTrue(callers.toString(), callers.stream().anyMatch(c -> c.file().getFileName().toString().equals("login.test.ts")));
        List<TsLanguageService.CallItem> callees = TsCallHierarchyProvider.calls(getProject(), item, false);
        assertTrue(callees.toString(), callees.stream().anyMatch(c -> c.name().equals("fill")));
        assertNotNull(TsCallHierarchyProvider.element(getProject(), callers.get(0)));
    }

    public void testRefactoringsAndExtractVariable() {
        open("pages/loginPage.page.ts");
        String expression = "this.selectors.loginInput";
        int start = document.getText().indexOf(expression);
        List<TsRefactorings.Refactoring> refactorings = TsRefactorings.applicable(getProject(), myFixture.getFile().getVirtualFile(), document,
                start, start + expression.length(), 20_000);
        assertNotNull("no refactorings: " + TsLanguageService.getInstance(getProject()).refactoringsUnavailableReason(myFixture.getFile().getVirtualFile()), refactorings);
        assertTrue(refactorings.toString(), refactorings.stream().anyMatch(r -> r.description().equals("Extract to constant in enclosing scope")));

        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.getEditor().getSelectionModel().setSelection(start, start + expression.length());
        new dev.pwtoolkit.refactor.TsRefactoringSupport().getIntroduceVariableHandler().invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), null);
        var state = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("the new name is renamed in place", state);
        state.gotoEnd(false);
        String text = document.getText();
        assertTrue(text, text.matches("(?s).*const (\\w+) = this\\.selectors\\.loginInput;\\s*await this\\.page\\.fill\\(\\1, login\\);.*"));
    }

    private PsiElement declaration(String needle) {
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf(needle) + 1);
        PsiElement element = TargetElementUtil.findTargetElement(myFixture.getEditor(), TargetElementUtil.ELEMENT_NAME_ACCEPTED);
        assertNotNull(needle, element);
        return element;
    }

    private List<String> markers() {
        List<LineMarkerInfo<?>> result = new ArrayList<>();
        List<PsiElement> elements = SyntaxTraverser.psiTraverser(myFixture.getFile()).toList();
        new TsHierarchyLineMarkerProvider().collectSlowLineMarkers(elements, result);
        return result.stream().map(LineMarkerInfo::getLineMarkerTooltip).toList();
    }

    private void open(String path) {
        if (document != null) {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
            FileDocumentManager.getInstance().saveDocument(document);
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample") + "/" + path);
        myFixture.configureFromExistingVirtualFile(file);
        document = myFixture.getEditor().getDocument();
        original = document.getText();
    }
}
