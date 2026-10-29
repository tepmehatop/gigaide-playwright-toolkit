package dev.pwtoolkit.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** "log" + Tab and "expr.log" + Tab, like in WebStorm. */
public class ConsoleLogTest extends BasePlatformTestCase {
    public void testPostfixLog() {
        myFixture.configureByText("a.ts", "const x = 1;\nawait page.title().log<caret>\n");
        myFixture.type('\t');
        myFixture.checkResult("const x = 1;\nawait console.log(page.title())<caret>\n");
    }

    public void testPostfixLogOnCallWithStringArguments() {
        myFixture.configureByText("a.ts", "page.locator(\"a)b\").count().log<caret>\n");
        myFixture.type('\t');
        myFixture.checkResult("console.log(page.locator(\"a)b\").count())<caret>\n");
    }

    public void testLiveTemplateLog() {
        myFixture.configureByText("a.ts", "function f() {\n    log<caret>\n}\n");
        myFixture.type('\t');
        myFixture.checkResult("function f() {\n    console.log(<caret>);\n}\n");
    }

    public void testExpressionScanner() {
        String text = "x = (a + b).c?.d[0]!";
        assertEquals("(a + b).c?.d[0]!", text.substring(ExpressionScanner.start(text, text.length())));
        assertEquals(3, ExpressionScanner.start("x =  ", 3));
    }
}
