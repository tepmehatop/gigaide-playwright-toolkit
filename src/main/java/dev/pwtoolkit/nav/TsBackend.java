package dev.pwtoolkit.nav;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A TypeScript language service process: the native TypeScript 7 server (LSP) or the JavaScript tsserver.js of
 * TypeScript 5/6 run by Node.js (for machines where native binaries may not be executed).
 */
interface TsBackend {
    /** A document to open, update or close ({@code text == null}) in the server. */
    record DocumentChange(@NotNull Path file, @NotNull String languageId, @Nullable String text, int version, boolean open) {
    }

    @NotNull CompletableFuture<?> ready();

    void sync(@NotNull List<DocumentChange> changes);

    /** 0-based line and UTF-16 character. */
    @NotNull CompletableFuture<List<TsLanguageService.Location>> definition(@NotNull Path file, int line, int character);

    /** Offsets in {@code text}, the content last synced for the file. */
    @NotNull CompletableFuture<List<TsLanguageService.SemanticToken>> semanticTokens(@NotNull Path file, @NotNull CharSequence text);

    /** Files changed on disk (created 1, changed 2, deleted 3). */
    /** Completion at a 0-based position; trigger is "." when the user just typed it. */
    @NotNull CompletableFuture<List<TsLanguageService.Completion>> completions(@NotNull Path file, int line, int character, @Nullable String trigger);

    void filesChanged(@NotNull List<Path> files, @NotNull List<Integer> types);

    boolean isAlive();

    /** "running", "exited with code 1"... */
    @NotNull String state();

    @NotNull String stderrTail();

    /** What runs, e.g. "native TypeScript 7: /path/tsc" or "tsserver.js (TypeScript 5.8.2) via node". */
    @NotNull String description();

    void shutdown();
}
