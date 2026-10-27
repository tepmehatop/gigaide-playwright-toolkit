package dev.pwtoolkit.run;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;

/** Tools | Playwright: keep the browser open between runs, close it. */
public final class PwBrowserActions {
    private PwBrowserActions() {
    }

    public static final class ToggleReuse extends ToggleAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return PwSettings.getInstance().getState().reuseBrowser;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            PwSettings.getInstance().getState().reuseBrowser = state;
            if (!state && e.getProject() != null) PwBrowserServer.getInstance(e.getProject()).stopAll();
        }
    }

    public static final class Close extends AnAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(e.getProject() != null && PwBrowserServer.getInstance(e.getProject()).isRunning());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) PwBrowserServer.getInstance(e.getProject()).stopAll();
        }
    }
}
