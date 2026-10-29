package dev.pwtoolkit.completion;

import org.jetbrains.annotations.NotNull;

/** Finds the "(" of the call around an offset and the argument the offset is in, by text (strings and nesting aware). */
final class CallScanner {
    private CallScanner() {
    }

    /** Offset of the unclosed "(" before {@code offset}, or -1. Stops at ";" and "{" outside of brackets. */
    static int openParen(@NotNull CharSequence text, int offset) {
        int depth = 0;
        for (int i = Math.min(offset, text.length()) - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = stringStart(text, i, c);
                if (i < 0) return -1;
            } else if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) return c == '(' ? i : -1;
                depth--;
            } else if (c == ';' && depth == 0) {
                return -1;
            }
        }
        return -1;
    }

    /** Index of the argument at {@code offset}: top-level commas between "(" and the offset. */
    static int argumentIndex(@NotNull CharSequence text, int paren, int offset) {
        int index = 0;
        int depth = 0;
        for (int i = paren + 1; i < Math.min(offset, text.length()); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = stringEnd(text, i, c);
                if (end < 0) break;
                i = end;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                index++;
            }
        }
        return index;
    }

    private static int stringStart(@NotNull CharSequence text, int closingQuote, char quote) {
        for (int i = closingQuote - 1; i >= 0; i--) {
            if (text.charAt(i) == quote && (i == 0 || text.charAt(i - 1) != '\\')) return i;
            if (quote != '`' && text.charAt(i) == '\n') return -1;
        }
        return -1;
    }

    private static int stringEnd(@NotNull CharSequence text, int openingQuote, char quote) {
        for (int i = openingQuote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                i++;
            } else if (text.charAt(i) == quote) {
                return i;
            } else if (quote != '`' && text.charAt(i) == '\n') {
                return -1;
            }
        }
        return -1;
    }
}
