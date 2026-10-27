package dev.pwtoolkit.util;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.text.VersionComparatorUtil;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a Node.js executable. A GUI-launched IDE on macOS does not inherit the shell PATH,
 * so besides the login-shell PATH we also look into nvm and the usual install locations.
 */
public final class NodeLocator {
    private static final String NODE = SystemInfo.isWindows ? "node.exe" : "node";

    private NodeLocator() {
    }

    public static @Nullable String findNode(@Nullable String preferred) {
        if (isExecutable(preferred)) return preferred;
        String configured = PwSettings.getInstance().getState().nodePath;
        if (isExecutable(configured)) return configured;

        File inPath = PathEnvironmentVariableUtil.findInPath(NODE, EnvironmentUtil.getValue("PATH"), null);
        if (inPath != null) return inPath.getPath();

        File nvm = new File(System.getProperty("user.home"), ".nvm/versions/node");
        File[] versions = nvm.listFiles(File::isDirectory);
        if (versions != null && versions.length > 0) {
            File newest = Arrays.stream(versions)
                    .max(Comparator.comparing(File::getName, VersionComparatorUtil::compare))
                    .orElseThrow();
            File node = new File(newest, "bin/" + NODE);
            if (node.canExecute()) return node.getPath();
        }

        for (String candidate : List.of("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node",
                "C:\\Program Files\\nodejs\\node.exe")) {
            if (isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isExecutable(@Nullable String path) {
        return !StringUtil.isEmptyOrSpaces(path) && new File(path).canExecute();
    }
}
