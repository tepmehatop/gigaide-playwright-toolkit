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

    public void testPostfixConst() {
        myFixture.configureByText("a.ts", "async function f() {\n    page.locator(\"#a\").const<caret>\n}\n");
        expandAndFinish();
        myFixture.checkResult("async function f() {\n    const locator = page.locator(\"#a\");<caret>\n}\n");
    }

    public void testPostfixIfNotForofAwait() {
        myFixture.configureByText("a.ts", "function f() {\n    visible.if<caret>\n}\n");
        expandAndFinish();
        myFixture.checkResult("function f() {\n    if (visible) {\n        <caret>\n    }\n}\n");

        myFixture.configureByText("b.ts", "const hidden = page.isVisible().not<caret>;\n");
        expandAndFinish();
        myFixture.checkResult("const hidden = !page.isVisible()<caret>;\n");

        myFixture.configureByText("c.ts", "rows.forof<caret>\n");
        expandAndFinish();
        myFixture.checkResult("for (const row of rows) {\n    <caret>\n}\n");

        myFixture.configureByText("d.ts", "const title = page.title().await<caret>;\n");
        expandAndFinish();
        myFixture.checkResult("const title = await page.title()<caret>;\n");
    }

    /** Tab expands; the live template (a name to type) is accepted with its suggestion. */
    private void expandAndFinish() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.type('\t');
        var state = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) state.gotoEnd(false);
    }

    public void testExpressionScanner() {
        String text = "x = (a + b).c?.d[0]!";
        assertEquals("(a + b).c?.d[0]!", text.substring(ExpressionScanner.start(text, text.length())));
        assertEquals(3, ExpressionScanner.start("x =  ", 3));
    }
}
