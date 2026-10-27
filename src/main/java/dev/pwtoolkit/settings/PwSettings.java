package dev.pwtoolkit.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Application-wide plugin settings (Settings | Tools | Playwright Toolkit).
 * Empty paths mean "detect automatically".
 */
@Service(Service.Level.APP)
@State(name = "PlaywrightToolkitSettings", storages = @Storage("playwright-toolkit.xml"))
public final class PwSettings implements PersistentStateComponent<PwSettings.StateBean> {

    public static final class StateBean {
        public String nodePath = "";
        public String tsServerPath = "";
        public String jsDebugPath = "";
        public boolean navigationEnabled = true;
        public int navigationTimeoutMs = 3000;
        public boolean skipPlaywrightInternals = true;
        public boolean semanticHighlighting = true;
        public boolean showBrowserWhenDebugging = true;
        public boolean pauseOnFailure = true;
        public boolean disableGigaJestMarker = true;
        public boolean rainbowBrackets = true;
    }

    private StateBean state = new StateBean();

    public static @NotNull PwSettings getInstance() {
        return ApplicationManager.getApplication().getService(PwSettings.class);
    }

    @Override
    public @NotNull StateBean getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateBean state) {
        this.state = state;
    }
}
