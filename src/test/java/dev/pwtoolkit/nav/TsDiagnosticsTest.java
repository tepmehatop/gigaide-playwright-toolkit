package dev.pwtoolkit.nav;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.settings.PwSettings;

import java.util.Arrays;
import java.util.List;

/**
 * The case from the bug report: a helper in utils/customHelpers.ts with untyped parameters, used in a test without
 * an import. GigaIDE says nothing about it; the TypeScript server reports the error and offers the import.
 */
public class TsDiagnosticsTest extends BasePlatformTestCase {
    private static final String CALL = "await selectFromDropDown(page, \"Course\", \"#course\");";
    private String savedBackend;
    private String savedServerPath;
    private Document document;
    private VirtualFile sampleRoot;
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
        // Alt+Enter is collected only for project files, as the sample is in a real project
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

    public void testMissingImportIsAnErrorWithAddImportFix() {
        open("tests/login/login.test.ts", "await loginPage.open();", CALL);
        HighlightInfo error = waitForProblem("selectFromDropDown");
        assertEquals(HighlightSeverity.ERROR, error.getSeverity());
        assertTrue(error.getDescription(), error.getDescription().contains("Cannot find name 'selectFromDropDown'"));

        assertTrue(intentionsAt("selectFromDropDown(page").toString(), intentionsAt("selectFromDropDown(page").stream().anyMatch(t -> t.startsWith("Add import from")));
        launch("selectFromDropDown(page", "Add import from");
        assertTrue(document.getText(), document.getText().contains("import { selectFromDropDown } from \"../../utils/customHelpers\""));
    }

    public void testCompletionAddsTheImport() {
        open("tests/login/login.test.ts", "await loginPage.open();", "await selectFromDrop");
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf("selectFromDrop") + "selectFromDrop".length());
        LookupElement[] items = myFixture.completeBasic();
        if (items != null) {
            LookupElement item = Arrays.stream(items).filter(i -> i.getLookupString().equals("selectFromDropDown")).findFirst().orElse(null);
            assertNotNull(Arrays.stream(items).map(LookupElement::getLookupString).toList().toString(), item);
            myFixture.getLookup().setCurrentItem(item);
            myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR);
        }
        String text = document.getText();
        assertTrue(text, text.contains("await selectFromDropDown()"));
        assertTrue(text, text.contains("import { selectFromDropDown } from \"../../utils/customHelpers\""));
    }

    /** page has no type in the helper: Playwright's members by name, for navigation, completion and colors. */
    public void testUntypedPageFallsBackToPlaywrightApi() {
        open("utils/customHelpers.ts", "    await page.waitForTimeout(300);", "    await page.waitForSel");
        waitForProblem("page,"); // implicit any: the server works and has answered
        TsSemanticHighlighter.getInstance(getProject()).highlight(myFixture.getFile().getVirtualFile());
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertTrue(intentionsAt("page,").toString(), intentionsAt("page,").contains("Annotate 'page' as Page (@playwright/test)"));

        int offset = document.getText().indexOf("click(locator)");
        var source = myFixture.getFile().findElementAt(offset);
        var targets = new TsGotoDeclarationHandler().getGotoDeclarationTargets(source, offset, myFixture.getEditor());
        assertNotNull("Ctrl+Click on click of an untyped page", targets);
        assertEquals("types.d.ts", targets[0].getContainingFile().getName());
        assertTrue(targets.length > 1); // Page.click, Locator.click... - the IDE shows a chooser

        var highlighters = TsSemanticHighlighter.appliedHighlighters(document);
        assertNotNull(highlighters);
        assertTrue("click( is colored as a method", highlighters.stream().anyMatch(h -> h.getStartOffset() == offset
                && h.getTextAttributesKey() != null && h.getTextAttributesKey().getExternalName().contains("FUNCTION")
                || h.getStartOffset() == offset && h.getTextAttributesKey() != null && h.getTextAttributesKey().getExternalName().contains("METHOD")));

        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf("waitForSel") + "waitForSel".length());
        LookupElement[] items = myFixture.completeBasic();
        String text = document.getText();
        assertTrue(text, items == null ? text.contains("page.waitForSelector()") : Arrays.stream(items).anyMatch(i -> i.getLookupString().equals("waitForSelector")));
    }

    /** The mistakes from the bug report: untyped page, "name = string". */
    public void testFixesForHelperParameters() {
        open("utils/customHelpers.ts", "export async function", "");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(document.getText().replace("page, name, locator", "page, name = string, locator")));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        HighlightInfo typeAsValue = waitForProblem("string, locator");
        assertTrue(typeAsValue.getDescription(), typeAsValue.getDescription().contains("only refers to a type"));
        launch("string, locator", "Change '= string' to ': string'");
        assertTrue(document.getText(), document.getText().contains("page, name: string, locator"));

        waitForProblem("page,");
        launch("page,", "Annotate 'page' as Page");
        String text = document.getText();
        assertTrue(text, text.startsWith("import { Page } from \"@playwright/test\";\n"));
        assertTrue(text, text.contains("selectFromDropDown(page: Page, name: string, locator"));
        waitForProblem("locator,");
        launch("locator,", "Annotate 'locator' as string");
        assertTrue(document.getText(), document.getText().contains("locator: string"));
    }

    private void open(String path, String anchor, String insert) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample") + "/" + path);
        myFixture.configureFromExistingVirtualFile(file);
        document = myFixture.getEditor().getDocument();
        original = document.getText();
        int at = original.indexOf(anchor);
        assertTrue(anchor, at >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(at, insert + "\n        "));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    /** Runs the IDE's analysis (with the plugin's annotator) until a TypeScript problem starts at {@code needle}. */
    private HighlightInfo waitForProblem(String needle) {
        int offset = document.getText().indexOf(needle);
        long deadline = System.currentTimeMillis() + 20_000;
        List<HighlightInfo> infos = List.of();
        while (System.currentTimeMillis() < deadline) {
            infos = myFixture.doHighlighting();
            for (HighlightInfo info : infos) {
                if (info.getStartOffset() == offset && info.getDescription() != null && info.getDescription().contains("(TS")) return info;
            }
            // typescript-language-server pushes its diagnostics a moment later
            PlatformTestUtil.waitForAlarm(300);
        }
        fail("no problem at " + needle + ": " + infos);
        return null;
    }

    private List<String> intentionsAt(String needle) {
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf(needle));
        return myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
    }

    private void launch(String needle, String fix) {
        myFixture.getEditor().getCaretModel().moveToOffset(document.getText().indexOf(needle));
        IntentionAction action = myFixture.getAvailableIntentions().stream().filter(i -> i.getText().startsWith(fix)).findFirst().orElse(null);
        assertNotNull(fix + " in " + intentionsAt(needle), action);
        myFixture.launchAction(action);
    }
}
