package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Node-side helpers shipped in the plugin jar (resources/pwtoolkit/*), extracted to the IDE system dir
 * so that Playwright can load them: the "pause on failed step" Babel plugin and the test-tree reporter.
 */
public final class PwResources {
    public static final String DEBUG_TRANSFORM = "debugTransform.cjs";
    public static final String REPORTER = "pwtk-reporter.cjs";

    private PwResources() {
    }

    public static @NotNull Path dir() {
        return PathManager.getSystemDir().resolve("playwright-toolkit");
    }

    public static synchronized @NotNull Path install(@NotNull String name) throws ExecutionException {
        Path target = dir().resolve(name);
        String resource = "/pwtoolkit/" + name;
        try (InputStream in = PwResources.class.getResourceAsStream(resource)) {
            if (in == null) throw new ExecutionException("Missing resource " + resource);
            byte[] content = in.readAllBytes();
            if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), content)) {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            }
            return target;
        } catch (IOException e) {
            throw new ExecutionException("Cannot write " + target + ": " + e.getMessage(), e);
        }
    }
}
