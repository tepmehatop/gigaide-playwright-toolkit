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
        for (TsLanguageService.Completion completion : completions) {
            String name = completion.name();
            if (fromIde.contains(name) || !ours.getPrefixMatcher().prefixMatches(name)) continue;
            // e.g. ["data-id"] or ?.x: rewrites text before the caret, not supported here
            if (completion.insertText() != null && !completion.insertText().equals(name)) continue;
            LookupElementBuilder element = LookupElementBuilder.create(name)
                    .withIcon(icon(completion.kind()))
                    .withTypeText(completion.kind().equals("other") ? null : completion.kind(), true);
            if (completion.kind().equals("method") || completion.kind().equals("function")) {
                element = element.withTailText("()", true).withInsertHandler(ParenthesesInsertHandler.getInstance(true));
            }
            ours.addElement(PrioritizedLookupElement.withPriority(element, priority(completion)));
        }
    }

    /** TypeScript sorts by sortText ("11" own members before "12" inherited ones, "15" globals...): lower first. */
    private static double priority(@NotNull TsLanguageService.Completion completion) {
        String sort = completion.sortText();
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
                LOG.debug("Completion request failed", e);
                return null;
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
