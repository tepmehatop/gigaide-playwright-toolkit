package dev.pwtoolkit.run;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the locator under the caret for "Explore locators", from the text only (no PSI: GigaIDE has its own JS PSI).
 * Never returns expressions with calls other than locator methods, so evaluating them in the debugger is side-effect free.
 */
public final class PwLocatorExpressions {
    /** Methods that build locators; anything else (click, fill, textContent...) ends the locator part of a chain. */
    static final Set<String> LOCATOR_METHODS = Set.of("locator", "getByRole", "getByText", "getByLabel", "getByPlaceholder",
            "getByAltText", "getByTitle", "getByTestId", "first", "last", "nth", "filter", "and", "or", "frameLocator",
            "contentFrame", "owner");
    /** page.click('sel') and friends: the selector is the first argument (VS Code does the same). */
    private static final Set<String> ACTIONS_WITH_SELECTOR = Set.of("click", "dblclick", "fill", "check", "uncheck", "hover",
            "focus", "press", "type", "selectOption", "setInputFiles", "tap", "textContent", "innerText", "innerHTML",
            "inputValue", "isVisible", "isHidden", "isEnabled", "isDisabled", "isChecked", "waitForSelector", "getAttribute",
            "$", "$$", "$eval", "$$eval");
    private static final Pattern PROPERTY_BEFORE = Pattern.compile("[\\w$]+\\s*:\\s*$");

    public enum Kind {
        /** A selector string, e.g. the value in a Page Object's `selectors`. */
        SELECTOR,
        /** Locator code relative to the page, e.g. {@code getByRole('link').first()}. */
        PAGE_LOCATOR,
        /** An expression to evaluate or resolve, e.g. {@code mainPage.selectors.heroSection} or {@code this.searchButton}. */
        EXPRESSION
    }

    public record Target(@NotNull Kind kind, @NotNull String text) {
    }

    private PwLocatorExpressions() {
    }

    public static @Nullable Target at(@NotNull CharSequence text, int offset) {
        if (offset < 0 || offset > text.length()) return null;
        int[] literal = stringLiteralAround(text, offset);
        if (literal != null) {
            String before = lineBefore(text, literal[0]);
            String value = text.subSequence(literal[0] + 1, literal[1]).toString();
            if (value.contains("${")) return null; // template with interpolation
            if (before.matches(".*(?:\\b(?:locator|waitForSelector|\\$\\$?)|\\$eval|\\$\\$eval)\\s*\\(\\s*$")) return new Target(Kind.SELECTOR, value);
            int unclosed = lastUnclosedParen(before);
            // `heroSection: "..."` in a selectors object, but not `{ name: '...' }` inside getByRole(...)
            if (unclosed < 0 && PROPERTY_BEFORE.matcher(before).find()) return new Target(Kind.SELECTOR, value);
            // inside getByRole('...') etc.: the whole call chain
            int callStart = callNameStart(text, before, literal[0]);
            if (callStart < 0) return null;
            offset = callStart;
        }
        return chainAt(text, offset);
    }

    private static @Nullable Target chainAt(@NotNull CharSequence text, int offset) {
        int start = offset;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') start--;
            else if (c == ')') {
                int open = matchBackward(text, start - 1);
                if (open < 0) return null;
                start = open;
            } else break;
        }
        while (start < offset && text.charAt(start) == '.') start++;
        List<String> names = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        int i = start;
        int end = start;
        while (i < text.length()) {
            int nameStart = i;
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
            if (i == nameStart) break;
            String name = text.subSequence(nameStart, i).toString();
            String call = null;
            if (i < text.length() && text.charAt(i) == '(') {
                int close = matchForward(text, i);
                if (close < 0) return null;
                call = text.subSequence(i, close + 1).toString();
                i = close + 1;
            }
            names.add(name);
            calls.add(call);
            end = i;
            if (i < text.length() && text.charAt(i) == '.') i++;
            else break;
        }
        if (names.isEmpty() || offset > end) return null;
        if (names.get(0).equals("await")) return null;
        if (names.get(0).equals("this") && names.size() > 1) {
            names.remove(0);
            calls.remove(0);
        }
        // the base must be a plain name (no `foo().locator`)
        if (calls.get(0) != null) return null;

        // keep the locator part of the chain: properties and locator methods
        int keep = 1;
        while (keep < names.size() && (calls.get(keep) == null || LOCATOR_METHODS.contains(names.get(keep)))) keep++;
        boolean isPage = names.get(0).equals("page") || names.get(0).equals("_page");
        if (isPage && keep < names.size() && ACTIONS_WITH_SELECTOR.contains(names.get(keep)) && keep == 1) {
            String firstArg = firstStringArgument(calls.get(keep));
            return firstArg == null ? null : new Target(Kind.SELECTOR, firstArg);
        }
        StringBuilder expression = new StringBuilder();
        for (int k = 0; k < keep; k++) {
            if (k > 0) expression.append('.');
            expression.append(names.get(k));
            if (calls.get(k) != null) expression.append(calls.get(k));
        }
        String result = expression.toString();
        if (isPage) {
            int dot = result.indexOf('.');
            return dot < 0 ? null : new Target(Kind.PAGE_LOCATOR, result.substring(dot + 1));
        }
        return keep > 1 ? new Target(Kind.EXPRESSION, result) : null;
    }

    private static @Nullable String firstStringArgument(@Nullable String call) {
        if (call == null) return null;
        Matcher m = Pattern.compile("^\\(\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1").matcher(call);
        return m.find() && !m.group(2).contains("${") ? m.group(2) : null;
    }

    /** [openQuote, closeQuote] of the string literal containing the offset, on the same line. */
    private static int @Nullable [] stringLiteralAround(@NotNull CharSequence text, int offset) {
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = offset;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;
        char quote = 0;
        int open = -1;
        for (int i = lineStart; i < lineEnd; i++) {
            char c = text.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    open = i;
                } else if (c == '/' && i + 1 < lineEnd && text.charAt(i + 1) == '/') {
                    return null;
                }
            } else if (c == '\\') {
                i++;
            } else if (c == quote) {
                if (open < offset && offset <= i) return new int[]{open, i};
                quote = 0;
            }
        }
        return null;
    }

    private static @NotNull String lineBefore(@NotNull CharSequence text, int offset) {
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        return text.subSequence(lineStart, offset).toString();
    }

    /** For `page.getByRole('x'` returns the offset of `getByRole`. */
    private static int callNameStart(@NotNull CharSequence text, @NotNull String before, int quote) {
        int paren = lastUnclosedParen(before);
        if (paren < 0) return -1;
        Matcher m = Pattern.compile("([\\w$]+)\\s*$").matcher(before.substring(0, paren));
        if (!m.find() || !LOCATOR_METHODS.contains(m.group(1))) return -1;
        return quote - before.length() + m.start(1);
    }

    /** Index of the last '(' not closed before the end of the text (quotes skipped), or -1. */
    private static int lastUnclosedParen(@NotNull String text) {
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') open.push(i);
            else if (c == ')' && !open.isEmpty()) open.pop();
        }
        return open.isEmpty() ? -1 : open.peek();
    }

    private static int matchForward(@NotNull CharSequence text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int matchBackward(@NotNull CharSequence text, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(' && --depth == 0) return i;
        }
        return -1;
    }
}
