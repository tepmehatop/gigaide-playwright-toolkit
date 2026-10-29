package dev.pwtoolkit.tests;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.run.PwLocatorPicker;

/** A picked locator goes to the caret: the selector inside a string literal, the locator code elsewhere. */
public class PwPickLocatorTest extends BasePlatformTestCase {
    private static final PwLocatorPicker.Candidate DATA_QA =
            new PwLocatorPicker.Candidate(1, "data-qa", "[data-qa=\"login-field\"]", "locator('[data-qa=\"login-field\"]')", 1);

    public void testInsertsTheLocatorCode() {
        myFixture.configureByText("a.ts", "await page.<caret>.click();\n");
        PwPickLocatorAction.insert(getProject(), myFixture.getEditor(), DATA_QA);
        myFixture.checkResult("await page.locator('[data-qa=\"login-field\"]')<caret>.click();\n");
    }

    public void testInsertsTheSelectorInAString() {
        myFixture.configureByText("a.ts", "const selectors = { submit: '<caret>' };\n");
        PwPickLocatorAction.insert(getProject(), myFixture.getEditor(), DATA_QA);
        myFixture.checkResult("const selectors = { submit: '[data-qa=\"login-field\"]<caret>' };\n");
        assertFalse(PwPickLocatorAction.insideString("a('x', ", 7));
        assertTrue(PwPickLocatorAction.insideString("a('it\\'s ", 9));
        assertEquals("test attribute data-qa", PwPickLocatorAction.label(DATA_QA));
    }
}
