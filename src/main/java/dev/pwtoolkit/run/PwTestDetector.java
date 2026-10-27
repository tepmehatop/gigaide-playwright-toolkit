package dev.pwtoolkit.run;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-based recognition of Playwright tests. Works on any PSI (GigaIDE ships its own JS/TS PSI),
 * because it only needs the document text around the {@code test} identifier.
 */
public final class PwTestDetector {
    private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.[cm]?[jt]sx?$");
    private static final List<String> CONFIG_NAMES = List.of(
            "playwright.config.ts", "playwright.config.js", "playwright.config.mts",
            "playwright.config.mjs", "playwright.config.cts", "playwright.config.cjs");
    /** After the {@code test} identifier: optional modifiers, '(' and a string literal with the title. */
    private static final Pattern CALL = Pattern.compile(
            "^((?:\\.(?:describe|only|skip|fixme|fail|slow|serial|parallel))*)\\s*\\(\\s*(['\"`])((?:\\\\.|(?!\\2)[^\\\\])*)\\2");

    private PwTestDetector() {
    }

    /** A {@code test(...)} or {@code test.describe(...)} call found at a given offset. */
    public record TestCall(@NotNull String title, boolean suite, int line) {
    }

    public static boolean isTestFile(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && TEST_FILE.matcher(file.getName()).matches() && findConfigDir(file) != null;
    }

    /** Directory with playwright.config.* closest to the file (the directory where Playwright must be started). */
    public static @Nullable VirtualFile findConfigDir(@NotNull VirtualFile fileOrDir) {
        for (VirtualFile dir = fileOrDir.isDirectory() ? fileOrDir : fileOrDir.getParent(); dir != null; dir = dir.getParent()) {
            for (String name : CONFIG_NAMES) {
                if (dir.findChild(name) != null) return dir;
            }
            if (dir.findChild("node_modules") != null && dir.findChild("package.json") != null) {
                // a package root without a Playwright config: stop climbing
                return null;
            }
        }
        return null;
    }

    /**
     * The test call at {@code offset}, otherwise the closest test or describe declared above it
     * ("run the test at caret"). Returns null in the file header, before the first test.
     */
    public static @Nullable TestCall findEnclosingCall(@NotNull Document document, int offset) {
        TestCall exact = parseCall(document, offset);
        if (exact != null) return exact;
        CharSequence text = document.getCharsSequence();
        for (int line = document.getLineNumber(offset); line >= 0; line--) {
            int start = document.getLineStartOffset(line);
            int end = document.getLineEndOffset(line);
            for (int i = start; i + 4 <= end; i++) {
                if (text.charAt(i) == 't') {
                    TestCall call = parseCall(document, i);
                    if (call != null) return call;
                }
            }
        }
        return null;
    }

    /**
     * Recognises {@code test("title", ...)}, {@code test.only(...)}, {@code test.describe("title", ...)} etc.
     * {@code offset} must point at the {@code test} identifier.
     */
    public static @Nullable TestCall parseCall(@NotNull Document document, int offset) {
        CharSequence text = document.getCharsSequence();
        if (offset < 0 || offset + 4 > text.length() || !"test".contentEquals(text.subSequence(offset, offset + 4))) return null;
        if (offset > 0) {
            char before = text.charAt(offset - 1);
            if (Character.isJavaIdentifierPart(before) || before == '.') return null;
        }
        int end = Math.min(text.length(), offset + 4 + 2000);
        Matcher matcher = CALL.matcher(text.subSequence(offset + 4, end));
        if (!matcher.find()) return null;
        String modifiers = matcher.group(1);
        boolean suite = modifiers.contains(".describe");
        String title = matcher.group(3).replace("\\'", "'").replace("\\\"", "\"");
        return new TestCall(title, suite, document.getLineNumber(offset));
    }
}
