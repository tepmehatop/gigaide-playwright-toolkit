package dev.pwtoolkit.tests;

import com.intellij.execution.filters.Filter;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;

public class PwStackTraceFilterTest extends BasePlatformTestCase {
    public void testLinksStackAttachmentsAndCodeFrames() {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        String test = sample.getPath() + "/tests/login/login.test.ts";
        PwStackTraceFilter filter = new PwStackTraceFilter(getProject(), sample);

        assertLink(filter, "    at " + test + ":10:68", test + ":10:68");
        assertLink(filter, "error-context: " + test, test);
        // relative paths from the list reporter / code frames are resolved against the working directory
        assertLink(filter, "  1 [offline] › tests/login/login.test.ts:5:5 › Login › admin sees greeting", "tests/login/login.test.ts:5:5");
    }

    private static void assertLink(Filter filter, String line, String expectedLink) {
        String text = line + "\n";
        Filter.Result result = filter.applyFilter(text, text.length());
        assertNotNull("no link in: " + line, result);
        Filter.ResultItem item = result.getResultItems().get(0);
        assertEquals(expectedLink, text.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset()));
        assertNotNull(item.getHyperlinkInfo());
    }
}
