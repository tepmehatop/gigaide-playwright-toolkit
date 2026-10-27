package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Set;

public class TsSemanticColorsTest extends BasePlatformTestCase {
    /** GigaIDE registers TS_* keys with JS_* fallbacks; we must reuse them instead of failing. */
    public void testReusesKeysRegisteredWithAnotherFallback() {
        TextAttributesKey jsKey = TextAttributesKey.createTextAttributesKey("JS_INTERFACE_TEST_FALLBACK",
                DefaultLanguageHighlighterColors.CLASS_REFERENCE);
        TextAttributesKey gigaKey = TextAttributesKey.createTextAttributesKey("TS_INTERFACE_NAME", jsKey);
        com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().setAttributes(gigaKey,
                new com.intellij.openapi.editor.markup.TextAttributes(java.awt.Color.RED, null, null, null, java.awt.Font.PLAIN));

        TextAttributesKey ours = TsSemanticColors.keyFor(new TsLanguageService.SemanticToken(0, 1, "interface", Set.of()), true);
        assertSame(gigaKey, ours);
    }

    /** In Islands Dark GigaIDE's TS_ keys have no color: fall back to a key that is actually colored. */
    public void testSkipsColorlessGigaKeys() {
        TextAttributesKey colorless = TextAttributesKey.createTextAttributesKey("TS_STATIC_FIELD");
        TextAttributesKey ours = TsSemanticColors.keyFor(new TsLanguageService.SemanticToken(0, 1, "property", Set.of("static")), true);
        assertNotSame(colorless, ours);
        assertEquals(DefaultLanguageHighlighterColors.STATIC_FIELD, ours);
    }

    public void testStripsAnsiFromDebuggerValues() {
        assertEquals("expect(locator).toBeVisible() failed",
                dev.pwtoolkit.debug.PwValueAccess.strip("\u001b[2mexpect(\u001b[22mlocator).toBeVisible() failed"));
        assertEquals("expect(x)", dev.pwtoolkit.debug.PwValueAccess.strip("\\x1b[2mexpect(x)\\u001b[22m"));
    }
}
