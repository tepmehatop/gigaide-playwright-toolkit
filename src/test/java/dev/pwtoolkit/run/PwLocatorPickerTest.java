package dev.pwtoolkit.run;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.util.NodeLocator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ranking of picked locators in a real (headless) browser: unique on the page first, then data-qa, data-testid, id,
 * name / class, role, XPath; a data-testid repeated by front-end unit tests is never offered.
 */
public class PwLocatorPickerTest extends BasePlatformTestCase {
    private static final String PAGE = """
            <form id="login-form">
              <label for="email">Email</label><input id="email" name="email" placeholder="Email" data-testid="field">
              <input name="password" data-testid="field" type="password">
              <button data-qa="login-field" class="btn primary">Войти</button>
              <button data-testid="submit-btn" class="btn">Сохранить</button>
              <a href="/x" class="main-nav-link">Docs</a>
            </form>
            <div><button class="btn">Сохранить</button></div>
            """;

    @Override
    protected void tearDown() throws Exception {
        try {
            PwLocatorPicker.getInstance(getProject()).stop();
        } finally {
            super.tearDown();
        }
    }

    public void testRanking() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        Path page = Files.createTempFile("picker", ".html");
        Files.writeString(page, PAGE);
        PwLocatorPicker picker = PwLocatorPicker.getInstance(getProject());
        assertTrue(picker.ensureStarted(sample, NodeLocator.findNode(null), true));
        picker.navigate(page.toUri().toString());
        Thread.sleep(2000);

        List<String> login = codes(picker, "button >> nth=0");
        assertEquals("locator('[data-qa=\"login-field\"]')", login.get(0));
        assertTrue(login.toString(), login.contains("getByRole('button', { name: 'Войти' })"));
        assertTrue(login.toString(), login.contains("locator('//button[contains(text(), \\'Войти\\')]')"));

        assertEquals("a unique data-testid", "locator('[data-testid=\"submit-btn\"]')", codes(picker, "button >> nth=1").get(0));

        List<String> email = codes(picker, "input >> nth=0");
        assertEquals("locator('#email')", email.get(0));
        assertFalse("data-testid=field repeats", email.toString().contains("data-testid"));

        List<String> link = codes(picker, "a");
        assertEquals("locator('.main-nav-link')", link.get(0));

        List<PwLocatorPicker.Candidate> repeated = picker.candidatesFor("button >> nth=2").get(30, TimeUnit.SECONDS);
        assertTrue(repeated.toString(), repeated.stream().allMatch(c -> c.count() == 1 && c.kind().equals("position")));

        // the real flow: picking mode, a mouse click on the element (the page does not get the click)
        var picked = picker.pick();
        Thread.sleep(500);
        picker.testClick("[data-qa=\"login-field\"]");
        List<PwLocatorPicker.Candidate> clicked = picked.get(30, TimeUnit.SECONDS);
        assertEquals("locator('[data-qa=\"login-field\"]')", clicked.get(0).code());
    }

    private static List<String> codes(PwLocatorPicker picker, String selector) throws Exception {
        List<PwLocatorPicker.Candidate> candidates = picker.candidatesFor(selector).get(30, TimeUnit.SECONDS);
        assertTrue(candidates.toString(), candidates.stream().allMatch(c -> c.count() == 1));
        return candidates.stream().map(PwLocatorPicker.Candidate::code).toList();
    }
}
