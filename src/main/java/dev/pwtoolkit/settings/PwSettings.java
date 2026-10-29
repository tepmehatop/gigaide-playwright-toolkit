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
        /** Native TypeScript 7 binary, or a TypeScript 5/6 lib/tsserver.js. */
        public String tsServerPath = "";
        /** auto | native | tsserver | tsls; empty = the default of this plugin build. */
        public String tsBackend = "";
        public String jsDebugPath = "";
        public boolean navigationEnabled = true;
        public int navigationTimeoutMs = 3000;
        public boolean skipPlaywrightInternals = true;
        public boolean semanticHighlighting = true;
        /** Code completion from the TypeScript server (GigaIDE knows no types from node_modules, e.g. Playwright's Page). */
        public boolean completion = true;
        public boolean showBrowserWhenDebugging = true;
        public boolean pauseOnFailure = true;
        public boolean disableGigaJestMarker = true;
        /** Hide GigaIDE's tsx/node:test run icon in Playwright test files. */
        public boolean hideGigaTsxMarker = true;
        public boolean rainbowBrackets = true;
        /** Keep one browser open between runs (like "Show browser" in VS Code). */
        public boolean reuseBrowser = false;
        /** Highlight the locator under the caret in the kept-open browser. */
        public boolean exploreLocators = true;
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
