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
}
