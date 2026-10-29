package dev.pwtoolkit.debug;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.xdebugger.XDebuggerUtil;

/** Values shown inline: at the closest line at or above the paused one that mentions the variable. */
public class PwInlineValuesTest extends BasePlatformTestCase {
    public void testLineOfTheVariable() {
        myFixture.configureByText("a.test.ts", """
                test("t", async ({ page, loginPage }) => {
                    const user = users.admin;
                    const title = "x";
                    await loginPage.login(user.login, user.password);
                    console.log(title.length);
                });
                """);
        var paused = XDebuggerUtil.getInstance().createPosition(myFixture.getFile().getVirtualFile(), 4);
        assertEquals(Integer.valueOf(4), PwValue.lineMentioning(paused, "title"));
        assertEquals(Integer.valueOf(3), PwValue.lineMentioning(paused, "user"));
        assertEquals(Integer.valueOf(3), PwValue.lineMentioning(paused, "loginPage"));
        assertEquals(Integer.valueOf(0), PwValue.lineMentioning(paused, "page"));
        assertNull("only members named so: user.login, loginPage.login", PwValue.lineMentioning(paused, "login"));
    }
}
