package dev.pwtoolkit.util;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** File-extension helpers shared by navigation, run configurations and the debugger. */
public final class JsFiles {
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs");

    private JsFiles() {
    }

    public static boolean isScript(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && isScriptName(file.getName());
    }

    public static boolean isScriptName(@NotNull String name) {
        String ext = StringUtil.toLowerCase(StringUtil.notNullize(com.intellij.openapi.util.io.FileUtilRt.getExtension(name)));
        return SCRIPT_EXTENSIONS.contains(ext);
    }

    /** A real file on disk (the TypeScript server reads it); test and in-memory file systems may look local but are not. */
    public static boolean isOnDisk(@Nullable VirtualFile file) {
        return file != null && file.isInLocalFileSystem() && file.getFileSystem().getNioPath(file) != null;
    }

    /** LSP languageId for a file name. */
    public static @NotNull String languageId(@NotNull String name) {
        String lower = StringUtil.toLowerCase(name);
        if (lower.endsWith(".tsx")) return "typescriptreact";
        if (lower.endsWith(".jsx")) return "javascriptreact";
        if (lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts")) return "typescript";
        if (lower.endsWith(".json")) return "json";
        return "javascript";
    }
}
