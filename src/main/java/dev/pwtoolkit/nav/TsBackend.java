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

    /** Completion at a 0-based position; trigger is "." when the user just typed it. */
    @NotNull CompletableFuture<List<TsLanguageService.Completion>> completions(@NotNull Path file, int line, int character, @Nullable String trigger);

    /** Extra edits of a completion item, e.g. the import of an auto-imported function. */
    @NotNull CompletableFuture<List<TsLanguageService.Edit>> completionEdits(@NotNull Path file, int line, int character,
                                                                             @NotNull TsLanguageService.Completion completion);

    /** Errors and warnings of the file (syntax and types). */
    @NotNull CompletableFuture<List<TsLanguageService.Diagnostic>> diagnostics(@NotNull Path file);

    /** Quick fixes for a diagnostic: "Add import from ...", "Add missing function declaration ...". */
    @NotNull CompletableFuture<List<TsLanguageService.Fix>> fixes(@NotNull Path file, @NotNull TsLanguageService.Diagnostic diagnostic);

    /** Declaration of the type of the symbol at the position: page -> interface Page. */
    @NotNull CompletableFuture<List<TsLanguageService.Location>> typeDefinition(@NotNull Path file, int line, int character);

    /** Signatures of the call around the position (parameter info). */
    @NotNull CompletableFuture<TsLanguageService.SignatureHelp> signatureHelp(@NotNull Path file, int line, int character);

    /** Type and documentation of the symbol at the position (quick documentation, hover). */
    @NotNull CompletableFuture<TsLanguageService.QuickInfo> quickInfo(@NotNull Path file, int line, int character);

    /** All references to the symbol at the position, the declaration included. */
    @NotNull CompletableFuture<List<TsLanguageService.Reference>> references(@NotNull Path file, int line, int character);

    /** Declarations of the file as a tree: classes, methods, functions, tests ("test(...) callback"). */
    @NotNull CompletableFuture<List<TsLanguageService.Symbol>> symbols(@NotNull Path file);

    /** Parameter name hints ("locator:") before literal arguments; offsets in {@code text}. */
    @NotNull CompletableFuture<List<TsLanguageService.InlayHint>> inlayHints(@NotNull Path file, @NotNull CharSequence text);

    /** Called when the server pushes new diagnostics for a file by itself (typescript-language-server). */
    default void onDiagnosticsPushed(@NotNull java.util.function.Consumer<Path> listener) {
    }

    /** Files changed on disk (created 1, changed 2, deleted 3). */
    void filesChanged(@NotNull List<Path> files, @NotNull List<Integer> types);

    boolean isAlive();

    /** "running", "exited with code 1"... */
    @NotNull String state();

    @NotNull String stderrTail();

    /** What runs, e.g. "native TypeScript 7: /path/tsc" or "tsserver.js (TypeScript 5.8.2) via node". */
    @NotNull String description();

    void shutdown();
}
