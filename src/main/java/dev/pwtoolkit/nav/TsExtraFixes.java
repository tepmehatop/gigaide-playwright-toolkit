package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes TypeScript does not offer for typical test helpers:
 * <ul>
 *   <li>"Parameter 'page' implicitly has an 'any' type" (TS7006): annotate as {@code Page} from @playwright/test,
 *   as {@code string} for locator/selector/name-like parameters, or as {@code any};</li>
 *   <li>{@code name = string} (TS2693, a type used as a value): {@code name: string}.</li>
 * </ul>
 */
final class TsExtraFixes {
    private static final Pattern STRING_LIKE = Pattern.compile("(?i).*(locator|selector|name|text|url|path|label|title|value|id|query|role)s?");
    private static final Set<String> TYPE_KEYWORDS = Set.of("string", "number", "boolean", "any", "unknown", "object");
    private static final Pattern PLAYWRIGHT_IMPORT = Pattern.compile("import\\s*(type\\s*)?\\{([^}]*)\\}\\s*from\\s*['\"]@playwright/test['\"]");

    private TsExtraFixes() {
    }

    /** Must be called inside a read action. */
    static @NotNull List<TsLanguageService.Fix> forDiagnostic(@NotNull VirtualFile file, @NotNull Document document,
                                                             @NotNull TsLanguageService.Diagnostic diagnostic) {
        List<TsLanguageService.Fix> fixes = new ArrayList<>();
        String text = document.getText();
        int start = TsEdits.offset(document, diagnostic.range().line(), diagnostic.range().character());
        int end = TsEdits.offset(document, diagnostic.range().endLine(), diagnostic.range().endCharacter());
        if (end <= start || end > text.length()) return fixes;
        String name = text.substring(start, end);
        boolean typeScript = !file.getName().matches(".*\\.(js|jsx|mjs|cjs)$");

        if ((diagnostic.code() == 7006 || diagnostic.code() == 7031) && typeScript && name.matches("[A-Za-z_$][\\w$]*")) {
            // "page" or "page = ..." -> insert the type right after the name
            if (name.toLowerCase().contains("page")) {
                List<TsLanguageService.Edit> edits = new ArrayList<>();
                edits.add(insert(file, document, end, ": Page"));
                TsLanguageService.Edit importEdit = playwrightImport(file, document, text, "Page");
                if (importEdit != null) edits.add(importEdit);
                fixes.add(new TsLanguageService.Fix("Annotate '" + name + "' as Page (@playwright/test)", edits));
            }
            if (STRING_LIKE.matcher(name).matches()) {
                fixes.add(new TsLanguageService.Fix("Annotate '" + name + "' as string", List.of(insert(file, document, end, ": string"))));
            }
            fixes.add(new TsLanguageService.Fix("Annotate '" + name + "' as any", List.of(insert(file, document, end, ": any"))));
        }

        if (diagnostic.code() == 2693 && TYPE_KEYWORDS.contains(name)) {
            // "name = string" in a parameter list: the author meant a type
            int eq = start - 1;
            while (eq >= 0 && Character.isWhitespace(text.charAt(eq))) eq--;
            if (eq >= 0 && text.charAt(eq) == '=') {
                int nameEnd = eq;
                while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
                fixes.add(new TsLanguageService.Fix("Change '= " + name + "' to ': " + name + "'",
                        List.of(replace(file, document, nameEnd, end, ": " + name))));
            }
        }
        return fixes;
    }

    /** Adds the name to an existing import from @playwright/test, or a new import line at the top. Null if already imported. */
    private static TsLanguageService.Edit playwrightImport(@NotNull VirtualFile file, @NotNull Document document, @NotNull String text,
                                                          @NotNull String type) {
        Matcher matcher = PLAYWRIGHT_IMPORT.matcher(text);
        if (matcher.find()) {
            String names = matcher.group(2);
            if (Pattern.compile("\\b" + type + "\\b").matcher(names).find()) return null;
            int closing = matcher.start(2) + names.length();
            int at = closing;
            while (at > matcher.start(2) && Character.isWhitespace(text.charAt(at - 1))) at--;
            boolean trailingComma = at > matcher.start(2) && text.charAt(at - 1) == ',';
            return insert(file, document, at, (trailingComma ? " " : ", ") + type);
        }
        return insert(file, document, 0, "import { " + type + " } from \"@playwright/test\";\n");
    }

    private static TsLanguageService.Edit insert(VirtualFile file, Document document, int offset, String text) {
        return replace(file, document, offset, offset, text);
    }

    private static TsLanguageService.Edit replace(VirtualFile file, Document document, int start, int end, String text) {
        int line = document.getLineNumber(start);
        int endLine = document.getLineNumber(end);
        return new TsLanguageService.Edit(file.toNioPath(), new TsLanguageService.Range(line, start - document.getLineStartOffset(line),
                endLine, end - document.getLineStartOffset(endLine)), text);
    }
}
