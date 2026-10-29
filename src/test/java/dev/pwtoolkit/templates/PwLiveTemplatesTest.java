package dev.pwtoolkit.templates;

import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PwLiveTemplatesTest extends BasePlatformTestCase {
    public void testTestTemplateExpandsInTypeScript() {
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("login.test.ts", "<caret>");
        myFixture.type("pwstep");
        myFixture.performEditorAction("ExpandLiveTemplateByTab");
        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        assertNotNull("template did not expand", state);
        state.gotoEnd(false);
        assertEquals("await test.step(\"step name\", async () => {\n    \n});", myFixture.getEditor().getDocument().getText());
    }

    public void testPageObjectTemplate() {
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("cart.page.ts", "<caret>");
        myFixture.type("pwpage");
        myFixture.performEditorAction("ExpandLiveTemplateByTab");
        TemplateManagerImpl.getTemplateState(myFixture.getEditor()).gotoEnd(false);
        String text = myFixture.getEditor().getDocument().getText();
        assertTrue(text, text.contains("export default class MainPage {") && text.contains("selectors = {") && text.contains("await this.page.goto(this.path);"));
    }

    public void testNotAvailableInOtherFiles() {
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("notes.txt", "<caret>");
        myFixture.type("pwt");
        myFixture.performEditorAction("ExpandLiveTemplateByTab");
        assertNull(TemplateManagerImpl.getTemplateState(myFixture.getEditor()));
    }

    public void testForiLikeWebStorm() {
        assertEquals("function f() {\n    for (let i = 0; i < ; i++) {\n        \n    }\n}", expand("a.ts", "function f() {\n    <caret>\n}", "fori"));
    }

    public void testItarMirrorsTheArrayAndIndex() {
        assertEquals("for (let i = 0; i < array.length; i++) {\n    const element = array[i];\n    \n}", expand("a.js", "<caret>", "itar"));
    }

    public void testWaitForResponseFirstThenAction() {
        String text = expand("a.test.ts", "<caret>", "pwwait");
        assertEquals("const responsePromise = page.waitForResponse((response) => response.url().includes(\"/api/\") && response.status() === 200);\n\n"
                + "const response = await responsePromise;", text);
    }

    public void testTypeScriptOnlyTemplates() {
        assertEquals("interface Name {\n    \n}", expand("a.ts", "<caret>", "intf"));
        assertNull("no interfaces in JavaScript", tryExpand("a.js", "<caret>", "intf"));
    }

    public void testNotInStringsCommentsOrAfterDot() {
        assertNull(tryExpand("a.ts", "const s = \"<caret>\";", "fori"));
        assertNull(tryExpand("a.ts", "// <caret>", "fori"));
        assertNull("request.get is a member", tryExpand("a.ts", "request.<caret>", "getter"));
    }

    /** Every template of the plugin expands in a test file (variables, quoting and contexts are valid). */
    public void testAllTemplatesExpand() {
        var settings = com.intellij.codeInsight.template.impl.TemplateSettings.getInstance();
        java.util.List<String> failed = new java.util.ArrayList<>();
        int count = 0;
        for (var template : settings.getTemplates()) {
            if (!template.getGroupName().equals("Playwright") && !template.getGroupName().equals("TypeScript/JavaScript (Playwright Toolkit)")) continue;
            count++;
            String result = tryExpand("all" + count + ".test.ts", "<caret>", template.getKey());
            if (result == null || result.contains(template.getKey() + "\t") || result.equals(template.getKey())) failed.add(template.getKey());
        }
        assertTrue("templates found: " + count, count >= 80);
        assertEquals(java.util.List.of(), failed);
    }

    private String expand(String file, String text, String key) {
        String result = tryExpand(file, text, key);
        assertNotNull(key + " did not expand", result);
        return result;
    }

    /** Types the key, Tab, accepts the defaults; null when nothing expanded. */
    private String tryExpand(String file, String text, String key) {
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText(file, text);
        myFixture.type(key);
        myFixture.performEditorAction("ExpandLiveTemplateByTab");
        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        String typed = text.replace("<caret>", key);
        if (state == null) {
            // a template without variables is finished at once
            String result = myFixture.getEditor().getDocument().getText();
            return result.equals(typed) ? null : result;
        }
        // in the IDE Tab / Enter run inside a command; here it has to be one
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> state.gotoEnd(false));
        return myFixture.getEditor().getDocument().getText();
    }
}
