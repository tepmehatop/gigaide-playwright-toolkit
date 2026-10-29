package dev.pwtoolkit.run;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Syntax errors in Playwright selectors: string arguments of {@code locator()}, {@code click()}, {@code fill()}... and
 * values of Page Object {@code selectors} / {@code locators} objects. CSS, XPath and chained ({@code >>}) selectors.
 */
public final class PwSelectorAnnotator implements Annotator {
    /** Methods whose first argument is a selector. */
    static final Set<String> SELECTOR_METHODS = Set.of("locator", "$", "$$", "$eval", "$$eval", "waitForSelector", "click", "dblclick",
            "fill", "type", "press", "check", "uncheck", "setChecked", "hover", "focus", "tap", "textContent", "innerText", "innerHTML",
            "getAttribute", "inputValue", "isVisible", "isHidden", "isEnabled", "isDisabled", "isChecked", "isEditable", "selectOption",
            "setInputFiles", "dispatchEvent", "frameLocator", "querySelector", "querySelectorAll");
    private static final Pattern CALL_BEFORE = Pattern.compile("\\.\\s*([\\w$]+)\\s*\\(\\s*$");
    private static final Pattern SELECTORS_OBJECT = Pattern.compile("(?i)\\b(selectors|locators)\\s*[:=]\\s*\\{[^{}]*$");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element.getFirstChild() != null) return;
        String text = element.getText();
        if (text.length() < 2 || !(text.startsWith("\"") || text.startsWith("'") || text.startsWith("`")) || text.charAt(text.length() - 1) != text.charAt(0)
                || text.contains("${")) return;
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(virtualFile) || virtualFile.getPath().contains("/node_modules/")) return;
        if (!isSelector(file.getViewProvider().getContents(), element.getTextRange().getStartOffset())) return;
        String selector = text.substring(1, text.length() - 1);
        String error = PwSelectorSyntax.check(selector);
        if (error == null) return;
        TextRange range = element.getTextRange();
        holder.newAnnotation(HighlightSeverity.ERROR, error).range(new TextRange(range.getStartOffset() + 1, range.getEndOffset() - 1)).create();
    }

    /** The first argument of a selector method, or a value in a selectors / locators object. */
    static boolean isSelector(@NotNull CharSequence text, int stringStart) {
        int from = Math.max(0, stringStart - 300);
        String before = text.subSequence(from, stringStart).toString();
        var call = CALL_BEFORE.matcher(before);
        if (call.find()) return SELECTOR_METHODS.contains(call.group(1));
        // { loginInput: "#login", ... } after "selectors = {" (a property value, not a key)
        String tail = before.stripTrailing();
        if (!tail.endsWith(":")) return false;
        return SELECTORS_OBJECT.matcher(before).find();
    }
}
