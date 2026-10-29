package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Set;

public class TsSemanticColorsTest extends BasePlatformTestCase {
    /** GigaIDE registers TS_* keys with JS_* fallbacks; we must reuse them instead of failing. */
    public void testReusesKeysRegisteredWithAnotherFallback() {
        // GigaIDE's own highlighting (run by other tests in this JVM) may have registered it already
        TextAttributesKey gigaKey = TextAttributesKey.find("TS_INTERFACE_NAME");
        if (gigaKey.getFallbackAttributeKey() == null) {
            TextAttributesKey jsKey = TextAttributesKey.createTextAttributesKey("JS_INTERFACE_TEST_FALLBACK",
                    DefaultLanguageHighlighterColors.CLASS_REFERENCE);
            gigaKey = TextAttributesKey.createTextAttributesKey("TS_INTERFACE_NAME", jsKey);
        }
        com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().setAttributes(gigaKey,
                new com.intellij.openapi.editor.markup.TextAttributes(java.awt.Color.RED, null, null, null, java.awt.Font.PLAIN));

        TextAttributesKey ours = TsSemanticColors.keyFor(new TsLanguageService.SemanticToken(0, 1, "interface", Set.of()), true);
        assertSame(gigaKey, ours);
    }

    /** In Islands Dark GigaIDE's TS_ keys have no color: fall back to a key that is actually colored. */
    public void testSkipsColorlessGigaKeys() {
        // whatever registered it before (GigaIDE or another test), make TS_STATIC_FIELD colorless in the current scheme
        TextAttributesKey colorless = TextAttributesKey.createTextAttributesKey("TS_STATIC_FIELD");
        var scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
        scheme.setAttributes(colorless, new com.intellij.openapi.editor.markup.TextAttributes());

        TextAttributesKey ours = TsSemanticColors.keyFor(new TsLanguageService.SemanticToken(0, 1, "property", Set.of("static")), true);
        assertNotSame(colorless, ours);
        var attributes = scheme.getAttributes(ours);
        assertTrue(ours.getExternalName(), attributes != null && (attributes.getForegroundColor() != null || attributes.getFontType() != 0));
    }

    public void testStripsAnsiFromDebuggerValues() {
        assertEquals("expect(locator).toBeVisible() failed",
                dev.pwtoolkit.debug.PwValueAccess.strip("\u001b[2mexpect(\u001b[22mlocator).toBeVisible() failed"));
        assertEquals("expect(x)", dev.pwtoolkit.debug.PwValueAccess.strip("\\x1b[2mexpect(x)\\u001b[22m"));
    }
}
