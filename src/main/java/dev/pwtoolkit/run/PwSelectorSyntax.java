package dev.pwtoolkit.run;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the syntax of a Playwright selector: parts chained with {@code >>}, each CSS (the default, Playwright's
 * pseudo-classes allowed), XPath ({@code xpath=}, {@code //}, {@code ..}) or another engine ({@code text=},
 * {@code role=}, {@code data-testid=}, {@code internal:...}). Lenient: only clear mistakes are reported.
 */
public final class PwSelectorSyntax {
    private static final Pattern ENGINE = Pattern.compile("^([a-zA-Z_][\\w-]*(?::[\\w-]+)?)\\s*=");
    private static final ThreadLocal<javax.xml.xpath.XPath> XPATH = ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());

    private PwSelectorSyntax() {
    }

    /** An error message, or null when the selector looks valid. */
    public static @Nullable String check(@NotNull String selector) {
        if (selector.isBlank()) return "Empty selector";
        List<String> parts = split(selector);
        if (parts == null) {
            String trimmed = selector.strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("(//")) return checkXPath(trimmed);
            if (trimmed.startsWith("xpath=")) return checkXPath(trimmed.substring(6));
            return "Unclosed quote or bracket";
        }
        for (String raw : parts) {
            String part = raw.strip();
            if (part.isEmpty()) return "Empty part around '>>'";
            String error = checkPart(part);
            if (error != null) return error;
        }
        return null;
    }

    private static @Nullable String checkPart(@NotNull String part) {
        if (part.startsWith("//") || part.startsWith("..") || part.startsWith("(//")) return checkXPath(part);
        if (part.startsWith("\"") || part.startsWith("'")) return balanced(part) ? null : "Unclosed quote in text selector";
        Matcher engine = ENGINE.matcher(part);
        if (engine.find()) {
            String name = engine.group(1);
            String body = part.substring(engine.end()).strip();
            if (name.equals("xpath")) return checkXPath(body);
            if (name.equals("css")) return checkCss(body);
            if (body.isEmpty()) return "Empty " + name + "= selector";
            return balanced(body) ? null : "Unclosed quote or bracket in " + name + "= selector";
        }
        return checkCss(part);
    }

    static @Nullable String checkXPath(@NotNull String xpath) {
        if (xpath.isBlank()) return "Empty XPath";
        try {
            XPATH.get().compile(xpath);
            return null;
        } catch (XPathExpressionException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getCause() != null ? e.getCause().getMessage() : "syntax error";
            return "Invalid XPath: " + message.replaceFirst("^javax\\.xml\\.transform\\.TransformerException:\\s*", "");
        }
    }

    /** Structure of a CSS selector list: brackets, quotes, combinators, attribute and pseudo-class syntax. */
    static @Nullable String checkCss(@NotNull String css) {
        if (!balanced(css)) return "Invalid CSS selector: unclosed quote or bracket";
        String flat = strip(css);
        for (String selector : flat.split(",", -1)) {
            String s = selector.strip();
            if (s.isEmpty()) return "Invalid CSS selector: empty selector around ','";
            if (s.matches("^[>+~].*") && !s.startsWith(">")) return "Invalid CSS selector: starts with a combinator";
            if (s.matches(".*[>+~]$")) return "Invalid CSS selector: ends with a combinator";
            if (s.matches(".*[>+~]\\s*[>+~].*")) return "Invalid CSS selector: two combinators in a row";
            if (s.matches(".*[{};].*")) return "Invalid CSS selector: unexpected '{', '}' or ';'";
            if (s.matches(".*(^|[^\\\\])[#.](\\s|$|[>+~,\\[(:]).*")) return "Invalid CSS selector: '#' or '.' without a name";
            if (s.matches(".*:(\\s|$).*") || s.matches(".*::?[^a-zA-Z_\\-:].*")) return "Invalid CSS selector: ':' without a pseudo-class";
        }
        Matcher attribute = Pattern.compile("\\[([^\\]]*)]").matcher(css);
        while (attribute.find()) {
            String inside = attribute.group(1).strip();
            if (inside.isEmpty()) return "Invalid CSS selector: empty attribute selector []";
            // [name], [name=value], [name~="v" i] ... ; numbers inside :nth-match() / [] of XPath are not CSS here
            if (!inside.matches("[A-Za-z_\\-][\\w\\-:.]*\\s*(([~|^$*]?=)\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s\"'\\]]+)\\s*[isIS]?)?")) {
                return "Invalid CSS selector: wrong attribute selector [" + inside + "]";
            }
        }
        return null;
    }

    /** Quote contents replaced by spaces, so that commas and combinators inside strings and brackets do not count. */
    private static @NotNull String strip(@NotNull String css) {
        StringBuilder out = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                out.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(' ');
            } else if (c == '(' || c == '[') {
                depth++;
                out.append(c);
            } else if (c == ')' || c == ']') {
                depth--;
                out.append(c);
            } else {
                out.append(depth > 0 ? ' ' : c);
            }
        }
        return out.toString();
    }

    static boolean balanced(@NotNull String text) {
        char quote = 0;
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') quote = c;
            else if (c == '(' || c == '[' || c == '{') stack.append(c);
            else if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) return false;
                char open = stack.charAt(stack.length() - 1);
                if ("([{".indexOf(open) != ")]}".indexOf(c)) return false;
                stack.setLength(stack.length() - 1);
            }
        }
        return quote == 0 && stack.isEmpty();
    }

    /** Parts separated by ">>" outside of quotes and brackets; null when quotes or brackets are unbalanced. */
    static @Nullable List<String> split(@NotNull String selector) {
        List<String> parts = new ArrayList<>();
        char quote = 0;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') quote = c;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '>' && depth == 0 && i + 1 < selector.length() && selector.charAt(i + 1) == '>') {
                parts.add(selector.substring(start, i));
                i++;
                start = i + 1;
            }
        }
        if (quote != 0 || depth != 0) return null;
        parts.add(selector.substring(start));
        return parts;
    }
}
