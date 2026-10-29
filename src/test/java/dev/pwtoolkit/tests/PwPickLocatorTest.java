package dev.pwtoolkit.tests;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.run.PwDebugController;

/** A picked element goes to the caret: the selector inside a string literal, the locator code elsewhere. */
public class PwPickLocatorTest extends BasePlatformTestCase {
    private static final PwDebugController.Picked PICKED =
            new PwDebugController.Picked("getByRole('button', { name: 'Sign in' })", "internal:role=button[name=\"Sign in\"i]");

    public void testInsertsTheLocatorCode() {
        myFixture.configureByText("a.ts", "await page.<caret>.click();\n");
        PwPickLocatorAction.insert(getProject(), myFixture.getEditor(), PICKED);
        myFixture.checkResult("await page.getByRole('button', { name: 'Sign in' })<caret>.click();\n");
    }

    public void testInsertsTheSelectorInAString() {
        myFixture.configureByText("a.ts", "const selectors = { submit: '<caret>' };\n");
        PwPickLocatorAction.insert(getProject(), myFixture.getEditor(), PICKED);
        myFixture.checkResult("const selectors = { submit: 'internal:role=button[name=\"Sign in\"i]<caret>' };\n");
        assertFalse(PwPickLocatorAction.insideString("a('x', ", 7));
        assertTrue(PwPickLocatorAction.insideString("a('it\\'s ", 9));
    }
}
