package dev.pwtoolkit.nav;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

/** Starts the language server as soon as a TS/JS file is opened, so the first navigation is instant. */
public final class TsWarmUpListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (!JsFiles.isScript(file)) return;
        PwSettings.StateBean settings = PwSettings.getInstance().getState();
        if (settings.navigationEnabled) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                if (!source.getProject().isDisposed()) TsLanguageService.getInstance(source.getProject()).warmUp(file);
            });
        }
        if (settings.semanticHighlighting) TsSemanticHighlighter.getInstance(source.getProject()).scheduleOpenFiles();
        // starts following the caret for "Explore locators"
        dev.pwtoolkit.run.PwLocatorExplorer.getInstance(source.getProject());
    }
}
