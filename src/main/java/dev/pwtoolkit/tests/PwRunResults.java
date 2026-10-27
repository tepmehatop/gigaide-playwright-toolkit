package dev.pwtoolkit.tests;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.util.io.FileUtil;
import dev.pwtoolkit.run.PwResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Last run of a Playwright project as written by pwtk-reporter.cjs (PWTK_RESULTS_FILE): status and attachments
 * (screenshots, traces, error context) of every test, keyed by the Playwright test id.
 */
public record PwRunResults(@NotNull Map<String, Test> tests) {
    public record Attachment(@NotNull String name, @NotNull String path, @Nullable String contentType) {
        public boolean isTrace() {
            return name.equals("trace") || path.endsWith(".zip");
        }

        public boolean isImage() {
            return contentType != null && contentType.startsWith("image/");
        }
    }

    public record Test(@NotNull String id, @NotNull String file, int line, @NotNull String title, @Nullable String project,
                       @NotNull String status, @NotNull List<Attachment> attachments) {
    }

    /** One results file per working directory: always the last run started from the IDE. */
    public static @NotNull Path fileFor(@NotNull File workDir) {
        String key = Integer.toHexString(FileUtil.toSystemIndependentName(workDir.getAbsolutePath()).hashCode());
        return PwResources.dir().resolve("results").resolve(workDir.getName() + "-" + key + ".json");
    }

    public static @Nullable PwRunResults load(@NotNull File workDir) {
        Path file = fileFor(workDir);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = new Gson().fromJson(Files.readString(file), JsonObject.class);
            Map<String, Test> tests = new java.util.LinkedHashMap<>();
            for (var entry : json.getAsJsonObject("tests").entrySet()) {
                JsonObject t = entry.getValue().getAsJsonObject();
                List<Attachment> attachments = new ArrayList<>();
                for (var a : t.getAsJsonArray("attachments")) {
                    JsonObject o = a.getAsJsonObject();
                    attachments.add(new Attachment(o.get("name").getAsString(), o.get("path").getAsString(),
                            o.has("contentType") ? o.get("contentType").getAsString() : null));
                }
                tests.put(entry.getKey(), new Test(entry.getKey(), t.get("file").getAsString(), t.get("line").getAsInt(),
                        t.get("title").getAsString(), t.has("project") && !t.get("project").isJsonNull() ? t.get("project").getAsString() : null,
                        t.get("status").getAsString(), attachments));
            }
            return new PwRunResults(tests);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tests declared at file:line (several when the test runs in several projects or repeats). */
    public @NotNull List<Test> at(@NotNull String file, int line) {
        List<Test> result = new ArrayList<>();
        for (Test test : tests.values()) {
            if (test.line() == line && FileUtil.pathsEqual(test.file(), file)) result.add(test);
        }
        return result;
    }
}
