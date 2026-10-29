package dev.pwtoolkit.run;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/** Selector syntax: what Playwright accepts passes, clear mistakes are reported in the editor. */
public class PwSelectorSyntaxTest extends BasePlatformTestCase {
    public void testValidSelectors() {
        for (String selector : List.of("#login", "button.primary", "li:nth-child(2) > a", "a[href^='http']", "[data-testid=\"x\"]",
                "div:has-text(\"Hi\")", "css=div.a", "text=Sign in", "text=\"Sign in\"", "role=button[name=\"OK\"]",
                "xpath=//div[@id='a']", "//div[contains(@class, 'x')]/span", "..", "div >> text=Hello >> nth=0",
                "internal:role=button[name=\"Sign in\"i]", "internal:testid=[data-testid=\"cart\"s]", "data-testid=submit",
                "#greeting", "input[type=checkbox]:checked", "div, span", "a:visible", "button:text-is('OK')")) {
            assertNull(selector, PwSelectorSyntax.check(selector));
        }
    }

    public void testInvalidSelectors() {
        for (String selector : List.of("//div[", "xpath=//div[@id='a'", "#", "div >", "div >> ", "[=x]", "div[", "text=\"abc",
                "a,,b", "div > > span", "input[type=]", "button:", ".")) {
            assertNotNull(selector, PwSelectorSyntax.check(selector));
        }
        assertTrue(PwSelectorSyntax.check("//div[").startsWith("Invalid XPath"));
    }

    public void testErrorsInTheEditor() {
        myFixture.configureByText("a.ts", """
                await page.locator('//div[').click();
                await page.click('#fine');
                const selectors = {
                    ok: '#ok',
                    broken: 'div >',
                    'not-a-selector-key': '#x',
                };
                console.log('div >');
                """);
        List<HighlightInfo> errors = myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .filter(i -> i.getDescription() != null && (i.getDescription().startsWith("Invalid") || i.getDescription().startsWith("Empty"))).toList();
        List<String> texts = errors.stream().map(HighlightInfo::getText).toList();
        assertEquals(texts.toString(), List.of("//div[", "div >"), texts);
    }
}
