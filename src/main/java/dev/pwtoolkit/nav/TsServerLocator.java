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

    /** npm platform suffix, e.g. linux-x64, darwin-arm64. */
    static @NotNull String platform() {
        String os = SystemInfo.isMac ? "darwin" : SystemInfo.isWindows ? "win32" : "linux";
        return os + "-" + (CpuArch.isArm64() ? "arm64" : "x64");
    }

    private static @NotNull Path previewBinary(@NotNull Path nodeModules) {
        return nodeModules.resolve("@typescript/native-preview-" + platform() + "/lib/tsgo" + (SystemInfo.isWindows ? ".exe" : ""));
    }

    private static @NotNull Path typescriptBinary(@NotNull Path nodeModules) {
        return nodeModules.resolve("@typescript/typescript-" + platform() + "/lib/tsc" + (SystemInfo.isWindows ? ".exe" : ""));
    }

    private static @Nullable Path findInNodeModules(@NotNull Path nodeModules) {
        if (!Files.isDirectory(nodeModules)) return null;
        Path preview = previewBinary(nodeModules);
        if (Files.isRegularFile(preview)) return executable(preview);
        if (majorVersion(nodeModules.resolve("typescript/package.json")) >= 7) {
            Path tsc = typescriptBinary(nodeModules);
            if (Files.isRegularFile(tsc)) return executable(tsc);
        }
        return null;
    }

    /** Packages unpacked by some registries/proxies lose the exec bit on Linux: restore it. */
    private static @NotNull Path executable(@NotNull Path binary) {
        if (!SystemInfo.isWindows && !Files.isExecutable(binary)) {
            boolean fixed = binary.toFile().setExecutable(true, false) || binary.toFile().setExecutable(true, true);
            com.intellij.openapi.diagnostic.Logger.getInstance(TsServerLocator.class)
                    .info("[pwtoolkit] " + binary + " was not executable, chmod +x " + (fixed ? "done" : "FAILED"));
        }
        return binary;
    }

    /** Human-readable explanation of the search, for Check Environment and Diagnose Highlighting. */
    public static @NotNull java.util.List<String> explain(@NotNull Path root) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String configured = PwSettings.getInstance().getState().tsServerPath;
        if (!StringUtil.isEmptyOrSpaces(configured)) {
            lines.add("setting: " + configured + (Files.isRegularFile(Path.of(configured)) ? "" : " (NOT FOUND)"));
        }
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        for (Path dir = root; dir != null; dir = dir.getParent()) dirs.add(dir.resolve("node_modules"));
        dirs.add(managedInstallDir().resolve("node_modules"));
        for (Path nodeModules : dirs) {
            if (!Files.isDirectory(nodeModules)) continue;
            Path packageJson = nodeModules.resolve("typescript/package.json");
            boolean hasPreview = Files.isRegularFile(previewBinary(nodeModules));
            if (!Files.isRegularFile(packageJson) && !hasPreview) continue;
            String version = version(packageJson);
            Path binary = hasPreview ? previewBinary(nodeModules) : typescriptBinary(nodeModules);
            String state;
            if (!hasPreview && majorVersion(packageJson) < 7) state = "typescript " + version + " (needs 7+)";
            else if (!Files.isRegularFile(binary)) state = "typescript " + version + ", MISSING " + nodeModules.relativize(binary)
                    + " (optional dependency not installed: npm install @typescript/typescript-" + platform() + "@" + version + ")";
            else state = "typescript " + version + ", " + binary.getFileName() + (Files.isExecutable(binary) ? " ok" : " NOT EXECUTABLE");
            lines.add(nodeModules + ": " + state);
        }
        if (lines.isEmpty()) lines.add("no typescript in node_modules above " + root + " nor in " + managedInstallDir());
        lines.add("platform: " + platform() + " (" + System.getProperty("os.name") + " " + System.getProperty("os.version") + ")");
        return lines;
    }

    private static @NotNull String version(@NotNull Path packageJson) {
        try {
            return JsonParser.parseString(Files.readString(packageJson)).getAsJsonObject().get("version").getAsString();
        } catch (Exception e) {
            return "?";
        }
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
