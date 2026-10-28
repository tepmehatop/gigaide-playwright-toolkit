package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * With GigaIDE's JS/TS plugin loaded: semantic colors end up in the document markup of a *.test.ts file,
 * independently of the IDE's analysis (which never finishes for test files in GigaIDE).
 */
public class TsSemanticHighlightingTest extends BasePlatformTestCase {
    private String savedBackend;
    private String savedServerPath;

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

    public void testFixturesAndPageObjectMembersAreHighlighted() {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                System.getProperty("pwtoolkit.sample") + "/tests/login/login.test.ts");
        assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file);
        assertEquals("TypeScript", myFixture.getFile().getLanguage().getID());

        TsSemanticHighlighter.getInstance(getProject()).highlight(file);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        Document document = myFixture.getEditor().getDocument();
        List<RangeHighlighter> highlighters = TsSemanticHighlighter.appliedHighlighters(document);
        assertNotNull("nothing applied", highlighters);
        assertTrue(TsSemanticHighlighter.stats(myFixture.getEditor().getDocument()), !String.valueOf(TsSemanticHighlighter.stats(myFixture.getEditor().getDocument())).contains("semantic 0"));
        String text = document.getText();

        RangeHighlighter selectors = at(highlighters, text.indexOf("selectors.greeting"));
        assertNotNull("`selectors` is not highlighted", selectors);
        // above the IDE's own info highlights: GigaIDE puts plain-colored highlights on the same words at layer 3000
        assertTrue(selectors.getLayer() > com.intellij.openapi.editor.markup.HighlighterLayer.ADDITIONAL_SYNTAX);
        assertTrue(selectors.getTextAttributesKey().getExternalName(), selectors.getTextAttributesKey().getExternalName().contains("INSTANCE_FIELD"));
        TextAttributes attributes = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(selectors.getTextAttributesKey());
        assertNotNull("no color for fields", attributes.getForegroundColor());

        RangeHighlighter fixture = at(highlighters, text.indexOf("loginPage.login("));
        assertNotNull("fixture `loginPage` is not highlighted", fixture);
        assertTrue(fixture.getTextAttributesKey().getExternalName().contains("PARAMETER"));

        RangeHighlighter method = at(highlighters, text.indexOf("login(user.login"));
        assertNotNull("Page Object method is not highlighted", method);
        String methodKey = method.getTextAttributesKey().getExternalName();
        assertTrue(methodKey, methodKey.contains("INSTANCE_METHOD") || methodKey.equals("JS.INSTANCE_MEMBER_FUNCTION"));

        // names in `import test, { expect } from ...` get the color of their usages (the server has no tokens there)
        RangeHighlighter importedTest = at(highlighters, text.indexOf("test, { expect }"));
        assertNotNull("`test` in the import is not highlighted", importedTest);
        RangeHighlighter importedExpect = at(highlighters, text.indexOf("expect }"));
        assertNotNull("`expect` in the import is not highlighted", importedExpect);
        RangeHighlighter expectCall = at(highlighters, text.indexOf("expect(greeting)"));
        assertNotNull("`expect(...)` call is not highlighted", expectCall);
        assertEquals(expectCall.getTextAttributesKey(), importedExpect.getTextAttributesKey());
    }

    private static RangeHighlighter at(List<RangeHighlighter> highlighters, int offset) {
        return highlighters.stream().filter(h -> h.getStartOffset() == offset).findFirst().orElse(null);
    }
}
