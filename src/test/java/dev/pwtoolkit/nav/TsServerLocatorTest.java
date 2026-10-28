package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Problems seen on Linux machines in closed networks, reproduced with fake node_modules. */
public class TsServerLocatorTest extends BasePlatformTestCase {
    private Path dir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = Files.createTempDirectory("pwtk-ts");
        Files.createDirectories(dir.resolve("node_modules/typescript"));
        Files.writeString(dir.resolve("node_modules/typescript/package.json"), "{\"name\":\"typescript\",\"version\":\"7.0.2\"}");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileUtil.delete(dir.toFile());
            TsLanguageService.getInstance(getProject()).restartAll();
        } finally {
            super.tearDown();
        }
    }

    public void testMissingPlatformBinaryIsExplained() {
        assertNull(TsServerLocator.find(dir));
        String report = String.join("\n", TsServerLocator.explain(dir));
        assertTrue(report, report.contains("typescript 7.0.2, MISSING @typescript/typescript-" + TsServerLocator.platform() + "/lib/tsc"));
        assertTrue(report, report.contains("npm install @typescript/typescript-" + TsServerLocator.platform() + "@7.0.2"));
    }

    public void testRestoresExecBit() throws Exception {
        Path binary = dir.resolve("node_modules/@typescript/typescript-" + TsServerLocator.platform() + "/lib/tsc");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\necho fake\n");
        assertTrue(binary.toFile().setExecutable(false, false));
        assertTrue(String.join("\n", TsServerLocator.explain(dir)).contains("NOT EXECUTABLE"));

        assertEquals(binary, TsServerLocator.find(dir));
        assertTrue("chmod +x restored", Files.isExecutable(binary));
    }

    public void testSummaryOnRealProject() {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(System.getProperty("pwtoolkit.sample") + "/tests/login/login.test.ts");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        TsSemanticHighlighter.getInstance(getProject()).highlight(file);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        List<String> summary = HighlightingDiagnosticsAction.summary(getProject(), file, document, 5, "test(...)",
                com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme());
        String report = String.join("\n", summary);
        assertTrue(report, report.contains("highlight: semantic "));
        assertFalse(report, report.contains("semantic 0,"));
        assertTrue(report, report.contains("| running | initialized | last: "));
        assertTrue(report, report.contains("TS7 run: exit 0: Version 7."));
        assertTrue(report, report.contains("typescript 7.0.2, tsc ok"));
    }
}
