package dev.pwtoolkit.nav;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext;
import dev.pwtoolkit.completion.TsParameterInfoAutoPopup;
import dev.pwtoolkit.completion.TsParameterInfoHandler;
import dev.pwtoolkit.settings.PwSettings;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Parameter info while typing, parameter name hints, Find Usages and Structure, with each TypeScript server. */
public class TsEditorFeaturesTest extends BasePlatformTestCase {
    private static final String CALL = "await loginPage.login(\"admin\", \"secret\");";
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

    /** The hint stays while typing in the call (the PSI is reparsed) and moves to the next parameter after ",". */
    public void testParameterInfoFollowsTyping() {
        open("tests/login/login.test.ts", "await loginPage.open();", "await loginPage.login()");
        int inside = document.getText().indexOf("login()") + "login(".length();
        myFixture.getEditor().getCaretModel().moveToOffset(inside);
        assertTrue("a click inside the parentheses opens the hint", TsParameterInfoAutoPopup.isInsideCall(document.getImmutableCharSequence(), inside));

        TsParameterInfoHandler handler = new TsParameterInfoHandler();
        MockCreateParameterInfoContext create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        PsiElement owner = handler.findElementForParameterInfo(create);
        assertNotNull(owner);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(inside, "\"admin\", "));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        myFixture.getEditor().getCaretModel().moveToOffset(inside + "\"admin\", ".length());
        MockUpdateParameterInfoContext update = new MockUpdateParameterInfoContext(myFixture.getEditor(), myFixture.getFile(), create.getItemsToShow()) {
            @Override
            public int getParameterListStart() {
                return inside;
            }
        };
        update.setParameterOwner(owner);
        PsiElement updated = handler.findElementForUpdatingParameterInfo(update);
        assertNotNull("the hint is closed after typing", updated);
        handler.updateParameterInfo(updated, update);
        assertEquals("second parameter after the comma", 1, update.getCurrentParameter());

        assertFalse(TsParameterInfoAutoPopup.isInsideCall("if (a", 5));
        assertFalse(TsParameterInfoAutoPopup.isInsideCall("x = (a", 6));
    }

    public void testParameterNameHintsBeforeLiterals() {
        open("tests/login/login.test.ts", "await loginPage.open();", CALL);
        long deadline = System.currentTimeMillis() + 20_000;
        List<String> hints = List.of();
        while (System.currentTimeMillis() < deadline) {
            TsSemanticHighlighter.getInstance(getProject()).highlight(myFixture.getFile().getVirtualFile());
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            hints = TsSemanticHighlighter.shownHints(myFixture.getEditor());
            if (!hints.isEmpty()) break;
            PlatformTestUtil.waitForAlarm(300);
        }
        int admin = document.getText().indexOf("\"admin\", \"secret\"");
        assertTrue(hints.toString(), hints.contains(admin + ":login:"));
        assertTrue(hints.toString(), hints.contains((admin + "\"admin\", ".length()) + ":password:"));
    }

    public void testFindUsagesOfPageObjectMethod() throws Exception {
        open("pages/loginPage.page.ts", "", "");
        int offset = document.getText().indexOf("login(login: string");
        CompletableFuture<List<TsLanguageService.Reference>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.Reference>>>) () ->
                        TsLanguageService.getInstance(getProject()).references(myFixture.getFile().getVirtualFile(), document, offset));
        List<TsLanguageService.Reference> references = future.get(30, TimeUnit.SECONDS);
        FileDocumentManager.getInstance().saveDocument(document);
        document = null;
        List<String> files = references.stream().map(r -> r.file().getFileName().toString()).toList();
        assertTrue(files.toString(), files.contains("login.test.ts"));
        assertTrue(files.toString(), files.contains("loginPage.page.ts"));

        // the IDE's own Find Usages / Show Usages: the target of a usage is resolved with the server
        open("tests/login/login.test.ts", "", "");
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf("login(user.login") + 1);
        PsiElement target = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor(),
                com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted());
        assertNotNull("usage is not resolved", target);
        assertEquals("loginPage.page.ts", target.getContainingFile().getName());
        var usages = myFixture.findUsages(target);
        assertTrue(usages.toString(), usages.stream().anyMatch(u -> u.getFile() != null && u.getFile().getName().equals("login.test.ts")));
        assertFalse("the declaration itself is not a usage", usages.stream().anyMatch(u -> u.getFile() != null
                && u.getFile().getName().equals("loginPage.page.ts") && u.getNavigationOffset() == target.getTextOffset()));

        // highlighting of usages under the caret
        var handler = new TsFindUsagesHandlerFactory().createFindUsagesHandler(target, true);
        var highlighted = handler.findReferencesToHighlight(target, new com.intellij.psi.search.LocalSearchScope(myFixture.getFile()));
        assertEquals(1, highlighted.size());

        // Ctrl+Click on the declaration: no target from the plugin, the IDE shows usages
        open("pages/loginPage.page.ts", "", "");
        int declaration = document.getText().indexOf("login(login: string") + 1;
        assertNull(new TsGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.getFile().findElementAt(declaration), declaration, myFixture.getEditor()));
    }

    public void testStructureOfPageObjectAndTest() {
        open("pages/loginPage.page.ts", "", "");
        List<String> page = structure();
        assertTrue(page.toString(), page.contains("LoginPage"));
        assertTrue(page.toString(), page.contains("LoginPage/login"));
        assertTrue(page.toString(), page.contains("LoginPage/open"));
        assertTrue(page.toString(), page.contains("LoginPage/selectors"));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
        FileDocumentManager.getInstance().saveDocument(document);
        document = null;
        open("tests/login/login.test.ts", "", "");
        List<String> test = structure();
        assertTrue(test.toString(), test.stream().anyMatch(s -> s.contains("admin sees greeting")));
    }

    /** "Parent/child" paths of the structure tree. */
    private List<String> structure() {
        TreeBasedStructureViewBuilder builder = new TsStructureViewFactory().getStructureViewBuilder(myFixture.getFile());
        assertNotNull(builder);
        var model = builder.createStructureViewModel(myFixture.getEditor());
        List<String> paths = new java.util.ArrayList<>();
        collect(model.getRoot(), "", paths);
        return paths;
    }

    private static void collect(StructureViewTreeElement element, String prefix, List<String> paths) {
        for (var child : element.getChildren()) {
            String name = child.getPresentation().getPresentableText();
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            paths.add(path);
            collect((StructureViewTreeElement) child, path, paths);
        }
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
