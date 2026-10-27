package dev.pwtoolkit.run;

import junit.framework.TestCase;

import static dev.pwtoolkit.run.PwLocatorExpressions.Kind.EXPRESSION;
import static dev.pwtoolkit.run.PwLocatorExpressions.Kind.PAGE_LOCATOR;
import static dev.pwtoolkit.run.PwLocatorExpressions.Kind.SELECTOR;

public class PwLocatorExpressionsTest extends TestCase {
    /** `|` marks the caret. */
    private static PwLocatorExpressions.Target at(String textWithCaret) {
        int caret = textWithCaret.indexOf('|');
        return PwLocatorExpressions.at(textWithCaret.replace("|", ""), caret);
    }

    private static void assertTarget(PwLocatorExpressions.Kind kind, String text, PwLocatorExpressions.Target target) {
        assertNotNull(target);
        assertEquals(kind, target.kind());
        assertEquals(text, target.text());
    }

    public void testSelectorsOfPageObjects() {
        assertTarget(SELECTOR, "header.hero-section", at("        heroSection: \"header.her|o-section\","));
        assertTarget(SELECTOR, ".course-list li", at("await this.page.locator('.course|-list li').nth(index).click();"));
        assertTarget(SELECTOR, "#submit", at("await page.cli|ck('#submit');"));
    }

    public void testPageLocatorChains() {
        assertTarget(PAGE_LOCATOR, "getByRole('link', { name: 'Find Bugs' }).first()",
                at("await page.getByRole('link', { name: 'Find Bugs' }).fi|rst().click();"));
        assertTarget(PAGE_LOCATOR, "getByRole('link', { name: 'Find Bugs' })",
                at("await page.getByRole('link', { name: 'Find B|ugs' }).click();"));
        assertTarget(PAGE_LOCATOR, "locator(mainPage.selectors.heroSection)",
                at("await expect(page.loc|ator(mainPage.selectors.heroSection)).toBeVisible();"));
        assertTarget(PAGE_LOCATOR, "locator('.modal')", at("await this.page.locator('.modal').cli|ck();"));
    }

    public void testPageObjectReferences() {
        assertTarget(EXPRESSION, "mainPage.selectors.heroSection",
                at("await expect(page.locator(mainPage.selectors.hero|Section)).toBeVisible();"));
        assertTarget(EXPRESSION, "header.selectors.logo", at("page.locator(head|er.selectors.logo)"));
    }

    public void testNothingToHighlight() {
        assertNull("plain call with side effects", at("await mainPage.clickCou|rseCard(0);"));
        assertNull("function result as base", at("await makePage().loca|tor('x')"));
        assertNull("test title", at("test(\"should load page succ|essfully\", async () => {"));
        assertNull("interpolated", at("page.locator(`#item-${i|d}`)"));
        assertNull("comment", at("// page.locator('#a|b')"));
        assertNull("keyword", at("await| page"));
    }
}
