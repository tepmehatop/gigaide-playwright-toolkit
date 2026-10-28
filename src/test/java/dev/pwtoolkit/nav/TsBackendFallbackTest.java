package dev.pwtoolkit.nav;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.pwtoolkit.settings.PwSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto mode on a machine where native binaries may not be executed (SberOS: "Exec failed, error=1"):
 * the TypeScript 7 binary cannot be started, the project's TypeScript 5 tsserver.js is used instead.
 */
public class TsBackendFallbackTest extends BasePlatformTestCase {
    private String savedBackend;
    private String savedServerPath;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var state = PwSettings.getInstance().getState();
        savedBackend = state.tsBackend;
        savedServerPath = state.tsServerPath;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            TsLanguageService.getInstance(getProject()).restartAll();
            var state = PwSettings.getInstance().getState();
            state.tsBackend = savedBackend;
            state.tsServerPath = savedServerPath;
        } finally {
            super.tearDown();
        }
    }

    public void testNativeServerThatCannotRunFallsBackToTsserver() throws Exception {
        Path project = Files.createTempDirectory("pwtk-fallback");
        Files.writeString(project.resolve("tsconfig.json"), "{\"compilerOptions\": {\"strict\": true}}");
        Files.createDirectories(project.resolve("node_modules"));
        Path ts5 = Path.of(System.getProperty("pwtoolkit.ts5")).getParent().getParent();
        Files.createSymbolicLink(project.resolve("node_modules/typescript"), ts5);
        Files.writeString(project.resolve("page.ts"), "export class Page {\n    open(): void {}\n}\n");
        Files.writeString(project.resolve("main.ts"), "import { Page } from './page';\nnew Page().open();\n");
        // a "native server" that may not be executed
        Path tsc = Files.writeString(project.resolve("tsc"), "#!/bin/sh\n");
        tsc.toFile().setExecutable(false);

        var state = PwSettings.getInstance().getState();
        state.tsBackend = "auto";
        state.tsServerPath = tsc.toString();

        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolve("main.ts"));
        Document document = FileDocumentManager.getInstance().getDocument(file);
        int offset = document.getText().indexOf("open()");
        CompletableFuture<List<TsLanguageService.Location>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.Location>>>) () ->
                        TsLanguageService.getInstance(getProject()).definition(file, document, offset));
        List<String> describe = TsLanguageService.getInstance(getProject()).describe(file);
        assertNotNull(describe.toString(), future);
        List<TsLanguageService.Location> locations = future.get(30, TimeUnit.SECONDS);
        assertEquals(locations.toString(), "page.ts", locations.get(0).file().getFileName().toString());
        assertEquals(1, locations.get(0).line());

        String report = String.join("\n", describe);
        assertTrue(report, report.contains("TS server: tsserver.js (TypeScript 5.8.2)"));
        assertTrue(report, report.contains("TS skipped: native: " + tsc));
    }
}
