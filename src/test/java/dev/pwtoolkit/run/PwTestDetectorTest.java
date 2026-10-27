package dev.pwtoolkit.run;

import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PwTestDetectorTest extends BasePlatformTestCase {
    public void testRecognisesPlaywrightCalls() {
        String text = """
                test.describe("Login", () => {
                    test("admin sees greeting", async ({ page }) => {
                        await test.step("Open", async () => {});
                    });
                    test.only('single \\'quoted\\'', async () => {});
                    test.describe.serial(`serial suite`, () => {});
                    test.beforeEach(async () => {});
                    mytest("not playwright", () => {});
                });
                """;
        DocumentImpl document = new DocumentImpl(text);
        PwTestDetector.TestCall suite = PwTestDetector.parseCall(document, text.indexOf("test.describe(\"Login"));
        assertNotNull(suite);
        assertTrue(suite.suite());
        assertEquals("Login", suite.title());
        assertEquals(0, suite.line());

        PwTestDetector.TestCall test = PwTestDetector.parseCall(document, text.indexOf("test(\"admin"));
        assertNotNull(test);
        assertFalse(test.suite());
        assertEquals("admin sees greeting", test.title());
        assertEquals(1, test.line());

        assertNull("test.step is not a test", PwTestDetector.parseCall(document, text.indexOf("test.step")));
        assertNull("hooks are not tests", PwTestDetector.parseCall(document, text.indexOf("test.beforeEach")));
        assertNull("other identifiers", PwTestDetector.parseCall(document, text.indexOf("test(\"not")));

        PwTestDetector.TestCall only = PwTestDetector.parseCall(document, text.indexOf("test.only"));
        assertNotNull(only);
        assertEquals("single 'quoted'", only.title());

        PwTestDetector.TestCall serial = PwTestDetector.parseCall(document, text.indexOf("test.describe.serial"));
        assertNotNull(serial);
        assertTrue(serial.suite());
        assertEquals("serial suite", serial.title());
    }
}
