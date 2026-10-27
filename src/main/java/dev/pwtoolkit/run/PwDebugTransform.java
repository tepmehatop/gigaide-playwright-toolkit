package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Extracts the "pause on failed step" Babel plugin (resources/pwtoolkit/debugTransform.cjs) to the IDE system dir. */
public final class PwDebugTransform {
    private static final String RESOURCE = "/pwtoolkit/debugTransform.cjs";

    private PwDebugTransform() {
    }

    public static synchronized @NotNull Path install() throws ExecutionException {
        Path target = PathManager.getSystemDir().resolve("playwright-toolkit").resolve("debugTransform.cjs");
        try (InputStream in = PwDebugTransform.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new ExecutionException("Missing resource " + RESOURCE);
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
