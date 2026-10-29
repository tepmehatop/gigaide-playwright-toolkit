package dev.pwtoolkit.nav;

import org.jetbrains.annotations.NotNull;

/** Splits an LSP hover ("```typescript\nsignature\n```\n\ndocumentation") into signature and documentation. */
final class TsQuickInfoText {
    private TsQuickInfoText() {
    }

    static @NotNull TsLanguageService.QuickInfo fromMarkdown(@NotNull String markdown) {
        String text = markdown.strip();
        if (text.isEmpty()) return TsLanguageService.QuickInfo.NONE;
        int fence = text.indexOf("```");
        if (fence < 0) return new TsLanguageService.QuickInfo(null, text);
        int codeStart = text.indexOf('\n', fence);
        int fenceEnd = codeStart < 0 ? -1 : text.indexOf("```", codeStart);
        if (fenceEnd < 0) return new TsLanguageService.QuickInfo(null, text);
        String signature = text.substring(codeStart + 1, fenceEnd).strip();
        String documentation = (text.substring(0, fence) + text.substring(fenceEnd + 3)).strip();
        return new TsLanguageService.QuickInfo(signature.isEmpty() ? null : signature, documentation);
    }
}
