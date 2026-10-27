package dev.pwtoolkit.nav;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.system.CpuArch;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the native TypeScript 7 language server binary ({@code tsc --lsp --stdio}).
 * Order: explicit setting, project node_modules (walking up), plugin-managed install.
 */
public final class TsServerLocator {
    private TsServerLocator() {
    }

    public static @Nullable Path find(@NotNull Path root) {
        String configured = PwSettings.getInstance().getState().tsServerPath;
        if (!StringUtil.isEmptyOrSpaces(configured) && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured);
        }
        for (Path dir = root; dir != null; dir = dir.getParent()) {
            Path found = findInNodeModules(dir.resolve("node_modules"));
            if (found != null) return found;
        }
        return findInNodeModules(managedInstallDir().resolve("node_modules"));
    }

    /** Where "Install TypeScript 7" puts its own copy (outside of any user project). */
    public static @NotNull Path managedInstallDir() {
        return PathManager.getSystemDir().resolve("playwright-toolkit").resolve("typescript7");
    }

    private static @Nullable Path findInNodeModules(@NotNull Path nodeModules) {
        if (!Files.isDirectory(nodeModules)) return null;
        String platform = SystemInfo.isMac ? "darwin" : SystemInfo.isWindows ? "win32" : "linux";
        String arch = CpuArch.isArm64() ? "arm64" : "x64";
        String exe = SystemInfo.isWindows ? ".exe" : "";

        Path preview = nodeModules.resolve("@typescript/native-preview-" + platform + "-" + arch + "/lib/tsgo" + exe);
        if (Files.isRegularFile(preview)) return preview;

        if (majorVersion(nodeModules.resolve("typescript/package.json")) >= 7) {
            Path tsc = nodeModules.resolve("@typescript/typescript-" + platform + "-" + arch + "/lib/tsc" + exe);
            if (Files.isRegularFile(tsc)) return tsc;
        }
        return null;
    }

    private static int majorVersion(@NotNull Path packageJson) {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(packageJson)).getAsJsonObject();
            String version = json.get("version").getAsString();
            return Integer.parseInt(version.substring(0, version.indexOf('.')));
        } catch (Exception e) {
            return -1;
        }
    }
}
