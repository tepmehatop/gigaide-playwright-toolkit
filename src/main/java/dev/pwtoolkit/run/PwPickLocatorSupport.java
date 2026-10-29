package dev.pwtoolkit.run;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Settings of the Playwright project that matter for picking locators: testIdAttribute and baseURL of playwright.config. */
public final class PwPickLocatorSupport {
    private static final Pattern TEST_ID_ATTRIBUTE = Pattern.compile("testIdAttribute\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern BASE_URL = Pattern.compile("baseURL\\s*:\\s*['\"`]([^'\"`]+)['\"`]");

    private PwPickLocatorSupport() {
    }

    public static @Nullable String testIdAttribute(@NotNull File workDir) {
        return fromConfig(workDir, TEST_ID_ATTRIBUTE);
    }

    public static @Nullable String baseUrl(@NotNull File workDir) {
        return fromConfig(workDir, BASE_URL);
    }

    private static @Nullable String fromConfig(@NotNull File workDir, @NotNull Pattern pattern) {
        for (String name : new String[]{"playwright.config.ts", "playwright.config.js", "playwright.config.mjs", "playwright.config.cjs"}) {
            File config = new File(workDir, name);
            if (!config.isFile()) continue;
            try {
                Matcher matcher = pattern.matcher(Files.readString(config.toPath(), StandardCharsets.UTF_8));
                if (matcher.find()) return matcher.group(1);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
