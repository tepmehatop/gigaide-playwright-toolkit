package dev.pwtoolkit.debug;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.text.StringUtil;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds vscode-js-debug's standalone DAP server ({@code src/dapDebugServer.js}).
 * GigaIDE ships it with its NodeJS plugin, so normally nothing has to be installed.
 */
public final class JsDebugLocator {
    private static final String SERVER = "src/dapDebugServer.js";

    private JsDebugLocator() {
    }

    public static @Nullable Path findServerScript() {
        List<Path> roots = new ArrayList<>();
        String configured = PwSettings.getInstance().getState().jsDebugPath;
        if (!StringUtil.isEmptyOrSpaces(configured)) roots.add(Path.of(configured));
        roots.add(PathManager.getPluginsDir().resolve("nodeJS/js-debug"));
        roots.add(Path.of(PathManager.getHomePath(), "plugins", "nodeJS", "js-debug"));
        roots.add(Path.of(System.getProperty("user.home"), ".gigaide", "dap", "vscode-js-debug"));
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve(SERVER))) return root.resolve(SERVER);
            if (Files.isRegularFile(root.resolve("js-debug").resolve(SERVER))) return root.resolve("js-debug").resolve(SERVER);
            if (root.getFileName() != null && root.getFileName().toString().equals("dapDebugServer.js") && Files.isRegularFile(root)) return root;
        }
        return null;
    }
}
