package dev.pwtoolkit.completion;

import org.jetbrains.annotations.NotNull;

/**
 * Finds the start of the expression that ends at an offset, by text: identifiers, member access ({@code .}, {@code ?.}),
 * calls and indexes with balanced brackets, string and template literals, numbers, a non-null {@code !}.
 * {@code await page.title()} gives {@code page.title()}; {@code (await page.title())} is one expression.
 */
final class ExpressionScanner {
    private ExpressionScanner() {
    }

    static int start(@NotNull CharSequence text, int end) {
        int i = end;
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (TsCompletionContributor.isIdentifierPart(c) || c == '.' || c == '!' || (c == '?' && i < end && text.charAt(i) == '.')) {
                i--;
            } else if (c == ')' || c == ']' || c == '}') {
                int open = matchingOpen(text, i - 1);
                if (open < 0) return end;
                i = open;
            } else if (c == '"' || c == '\'' || c == '`') {
                int open = stringStart(text, i - 1, c);
                if (open < 0) return end;
                i = open;
            } else {
                break;
            }
        }
        // a dangling "." or a keyword is not an expression start
        while (i < end && (text.charAt(i) == '.' || text.charAt(i) == '!')) i++;
        return i;
    }

    private static int matchingOpen(@NotNull CharSequence text, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = stringStart(text, i, c);
                if (i < 0) return -1;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int stringStart(@NotNull CharSequence text, int closingQuote, char quote) {
        for (int i = closingQuote - 1; i >= 0; i--) {
            if (text.charAt(i) == quote && (i == 0 || text.charAt(i - 1) != '\\')) return i;
            if (quote != '`' && text.charAt(i) == '\n') return -1;
        }
        return -1;
    }
}
