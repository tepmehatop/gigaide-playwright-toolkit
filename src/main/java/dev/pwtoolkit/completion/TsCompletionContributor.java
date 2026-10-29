package dev.pwtoolkit.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import dev.pwtoolkit.nav.PwApiIndex;
import dev.pwtoolkit.nav.TsEdits;
import dev.pwtoolkit.nav.TsLanguageService;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Code completion answered by the TypeScript server: GigaIDE's JavaScript plugin does not know the types from
 * node_modules, so it offers nothing after {@code page.} or {@code locator.}. Items of the IDE's own contributors
 * (live templates, postfix templates, words) are kept; ours are added when the IDE has no item with that name.
 */
public final class TsCompletionContributor extends CompletionContributor {
    private static final Logger LOG = Logger.getInstance(TsCompletionContributor.class);
    private static final long TIMEOUT_MS = 3000;

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (!PwSettings.getInstance().getState().completion) return;
        VirtualFile file = parameters.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file)) return;
        Document document = parameters.getEditor().getDocument();
        int offset = parameters.getOffset();
        CharSequence text = document.getImmutableCharSequence();
        if (offset > text.length()) return;
        int start = offset;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) start--;
        String prefix = text.subSequence(start, offset).toString();
        String trigger = start > 0 && text.charAt(start - 1) == '.' ? "." : null;

        CompletableFuture<List<TsLanguageService.Completion>> future =
                TsLanguageService.getInstance(parameters.getPosition().getProject()).completions(file, document, offset, trigger);

        Set<String> fromIde = new HashSet<>();
        result.runRemainingContributors(parameters, r -> {
            fromIde.add(r.getLookupElement().getLookupString());
            result.passResult(r);
        });
        if (future == null) return;
        List<TsLanguageService.Completion> completions = await(future);
        if (completions == null) return;

        CompletionResultSet ours = result.withPrefixMatcher(prefix);
        // tsserver offers the words of the file ("warning" entries) when it knows nothing about the receiver
        boolean untypedReceiver = completions.stream().allMatch(c -> c.kind().equals("word"));
        if (untypedReceiver && trigger != null) {
            // an untyped receiver (e.g. a parameter without a type): Playwright's members, like IntelliJ's weak completion
            for (PwApiIndex.Member member : PwApiIndex.completionMembers(file.toNioPath())) {
                if (fromIde.contains(member.name()) || !ours.getPrefixMatcher().prefixMatches(member.name())) continue;
                LookupElementBuilder element = LookupElementBuilder.create(member.name())
                        .withIcon(member.method() ? AllIcons.Nodes.Method : AllIcons.Nodes.Property)
                        .withTypeText(member.owner(), true);
                if (member.method()) {
                    element = element.withTailText("()", true).withInsertHandler(ParenthesesInsertHandler.getInstance(true));
                }
                ours.addElement(PrioritizedLookupElement.withPriority(element, -100));
            }
            return;
        }
        for (TsLanguageService.Completion completion : completions) {
            String name = completion.name();
            if (completion.kind().equals("word")) continue;
            if (fromIde.contains(name) || !ours.getPrefixMatcher().prefixMatches(name)) continue;
            // e.g. ["data-id"] or ?.x: rewrites text before the caret, not supported here
            if (completion.insertText() != null && !completion.insertText().equals(name)) continue;
            boolean callable = completion.kind().equals("method") || completion.kind().equals("function");
            LookupElementBuilder element = LookupElementBuilder.create(completion, name)
                    .withIcon(icon(completion.kind()))
                    .withTypeText(completion.isAutoImport() ? completion.source() : completion.kind().equals("other") ? null : completion.kind(), true);
            if (callable) element = element.withTailText("()", true);
            if (callable || completion.isAutoImport()) element = element.withInsertHandler(new Insert(file, callable));
            ours.addElement(PrioritizedLookupElement.withPriority(element, priority(completion)));
        }
    }

    /** Parentheses for methods, the import for an item from another module (asked from the server after insertion). */
    private record Insert(@NotNull VirtualFile file, boolean callable) implements com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> {
        @Override
        public void handleInsert(@NotNull com.intellij.codeInsight.completion.InsertionContext context,
                                 @NotNull com.intellij.codeInsight.lookup.LookupElement item) {
            int nameStart = context.getStartOffset();
            if (callable) ParenthesesInsertHandler.getInstance(true).handleInsert(context, item);
            if (!(item.getObject() instanceof TsLanguageService.Completion completion) || !completion.isAutoImport()) return;
            context.commitDocument();
            CompletableFuture<List<TsLanguageService.Edit>> future = TsLanguageService.getInstance(context.getProject())
                    .completionEdits(file, context.getDocument(), nameStart, completion);
            if (future == null) return;
            try {
                TsEdits.applyInWriteAction(future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                LOG.info("[pwtoolkit] import for " + completion.name() + " from " + completion.source() + " failed", e);
            }
        }
    }

    /** TypeScript sorts by sortText ("11" own members before "12" inherited ones, "15" globals...): lower first. */
    private static double priority(@NotNull TsLanguageService.Completion completion) {
        // typescript-language-server prefixes it with \uffff
        String sort = completion.sortText().replaceFirst("^[^0-9]+", "");
        int digits = 0;
        while (digits < sort.length() && digits < 6 && Character.isDigit(sort.charAt(digits))) digits++;
        return digits == 0 ? 0 : -Double.parseDouble(sort.substring(0, digits));
    }

    private static @Nullable List<TsLanguageService.Completion> await(@NotNull CompletableFuture<List<TsLanguageService.Completion>> future) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            ProgressManager.checkCanceled();
            try {
                return future.get(20, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.currentTimeMillis() > deadline) return null;
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                // tsserver answers "No content available" when it has nothing to offer (e.g. after an untyped receiver)
                LOG.debug("Completion request failed", e);
                return List.of();
            }
        }
    }

    private static @NotNull Icon icon(@NotNull String kind) {
        return switch (kind) {
            case "method" -> AllIcons.Nodes.Method;
            case "function" -> AllIcons.Nodes.Function;
            case "property" -> AllIcons.Nodes.Property;
            case "variable" -> AllIcons.Nodes.Variable;
            case "constant" -> AllIcons.Nodes.Constant;
            case "class" -> AllIcons.Nodes.Class;
            case "interface", "typeParameter" -> AllIcons.Nodes.Interface;
            case "enum" -> AllIcons.Nodes.Enum;
            case "enumMember" -> AllIcons.Nodes.Field;
            case "module" -> AllIcons.Nodes.Package;
            default -> AllIcons.Nodes.EmptyNode;
        };
    }

    static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
