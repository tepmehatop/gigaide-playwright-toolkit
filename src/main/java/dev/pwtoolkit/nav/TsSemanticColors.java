package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps LSP semantic token types to color keys: GigaIDE's own JS/TS keys (TS_INSTANCE_FIELD, JS_INSTANCE_FIELD, ...)
 * when they are colored in the current scheme, otherwise the platform defaults (DEFAULT_INSTANCE_FIELD, ...).
 */
final class TsSemanticColors {
    private TsSemanticColors() {
    }

    static @Nullable TextAttributesKey keyFor(@NotNull TsLanguageService.SemanticToken token, boolean typeScript) {
        boolean declaration = token.modifiers().contains("declaration");
        boolean isStatic = token.modifiers().contains("static");
        return switch (token.type()) {
            case "class" -> key(typeScript, "CLASS_NAME", "CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
            case "interface" -> key(typeScript, "INTERFACE_NAME", "INTERFACE_NAME", DefaultLanguageHighlighterColors.INTERFACE_NAME);
            case "enum" -> key(typeScript, "ENUM", "CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
            case "enumMember" -> key(typeScript, "ENUM_FIELD", "STATIC_FIELD", DefaultLanguageHighlighterColors.STATIC_FIELD);
            case "typeParameter" -> webStorm("TS.TYPE_PARAMETER", DefaultLanguageHighlighterColors.CLASS_REFERENCE, key(typeScript, "TYPE_PARAMETER", "CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_REFERENCE));
            case "type" -> key(typeScript, "TYPE_ALIAS", "CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_REFERENCE);
            case "namespace" -> key(typeScript, "MODULE_IDENTIFIER", "MODULE_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
            case "parameter" -> key(typeScript, "PARAMETER", "PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
            case "variable" -> token.modifiers().contains("local")
                    ? webStorm("JS.LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE, key(typeScript, "LOCAL_VARIABLE", "LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE))
                    : firstVisible(DefaultLanguageHighlighterColors.GLOBAL_VARIABLE, typeScript ? "TS_GLOBAL_VARIABLE" : null, "JS.GLOBAL_VARIABLE");
            case "property" -> isStatic
                    ? key(typeScript, "STATIC_FIELD", "STATIC_FIELD", DefaultLanguageHighlighterColors.STATIC_FIELD)
                    : key(typeScript, "INSTANCE_FIELD", "INSTANCE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
            case "method" -> isStatic
                    ? key(typeScript, "STATIC_METHOD", "STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD)
                    : webStorm("JS.INSTANCE_MEMBER_FUNCTION", DefaultLanguageHighlighterColors.INSTANCE_METHOD, key(typeScript, "INSTANCE_METHOD", "INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD));
            case "function" -> declaration
                    ? token.modifiers().contains("local")
                    ? key(typeScript, "LOCAL_FUNCTION_DECLARATION", "LOCAL_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
                    : key(typeScript, "GLOBAL_FUNCTION_DECLARATION", "GLOBAL_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
                    : token.modifiers().contains("local")
                    ? DefaultLanguageHighlighterColors.FUNCTION_CALL
                    // imported or global functions (test, expect, ...), purple like in WebStorm
                    : webStorm("JS.GLOBAL_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION, DefaultLanguageHighlighterColors.FUNCTION_CALL);
            default -> null;
        };
    }

    /**
     * Keys of JetBrains' JavaScript plugin (WebStorm). The platform color schemes (Darcula, Dark, Islands Dark) define
     * colors for them, so using them gives the same look as WebStorm; registered here when GigaIDE has not done it.
     */
    private static @NotNull TextAttributesKey webStorm(@NotNull String name, @NotNull TextAttributesKey base,
                                                      @NotNull TextAttributesKey otherwise) {
        TextAttributesKey key = resolve(name, null);
        if (key == null) {
            // same fallback as in the JavaScript plugin, e.g. JS.GLOBAL_FUNCTION -> DEFAULT_FUNCTION_DECLARATION
            key = KEYS.computeIfAbsent(name, n -> TextAttributesKey.createTextAttributesKey(n, base));
        }
        var scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
        return isVisible(scheme, scheme.getAttributes(key)) ? key : otherwise;
    }

    private static @NotNull TextAttributesKey key(boolean typeScript, @NotNull String tsName, @NotNull String jsName,
                                                  @NotNull TextAttributesKey fallback) {
        return firstVisible(fallback, typeScript ? "TS_" + tsName : null, "JS_" + jsName);
    }

    /**
     * The first of GigaIDE's keys that has a visible style in the current color scheme, else the platform default.
     * GigaIDE's TS_ keys are colorless in some schemes (e.g. Islands Dark), where Page Objects get colors via other keys.
     */
    private static @NotNull TextAttributesKey firstVisible(@NotNull TextAttributesKey fallback, String... names) {
        var scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
        for (String name : names) {
            if (name == null) continue;
            TextAttributesKey key = resolve(name, null);
            if (key != null && isVisible(scheme, scheme.getAttributes(key))) return key;
        }
        return fallback;
    }

    /** Styled differently from plain code (a foreground equal to the default text color does not count). */
    private static boolean isVisible(@NotNull com.intellij.openapi.editor.colors.EditorColorsScheme scheme,
                                     @Nullable com.intellij.openapi.editor.markup.TextAttributes attributes) {
        if (attributes == null) return false;
        if (attributes.getFontType() != 0 || attributes.getEffectColor() != null && attributes.getEffectType() != null) return true;
        java.awt.Color foreground = attributes.getForegroundColor();
        if (foreground == null) return false;
        var identifier = scheme.getAttributes(DefaultLanguageHighlighterColors.IDENTIFIER);
        return !foreground.equals(scheme.getDefaultForeground())
                && (identifier == null || !foreground.equals(identifier.getForegroundColor()));
    }

    private static final Map<String, TextAttributesKey> KEYS = new ConcurrentHashMap<>();

    /**
     * GigaIDE's key if it is registered (its JS/TS highlighter does that when a file is opened), otherwise the platform default.
     * Never registers the TS_ and JS_ keys itself: GigaIDE registers them with other fallbacks and a second registration is an error.
     */
    private static @Nullable TextAttributesKey resolve(@NotNull String name, @Nullable TextAttributesKey fallback) {
        TextAttributesKey cached = KEYS.get(name);
        if (cached != null) return cached;
        for (TextAttributesKey key : TextAttributesKey.getAllKeys()) {
            if (key.getExternalName().equals(name)) {
                KEYS.put(name, key);
                return key;
            }
        }
        return fallback;
    }
}
