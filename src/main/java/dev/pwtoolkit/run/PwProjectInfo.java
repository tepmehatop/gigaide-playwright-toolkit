package dev.pwtoolkit.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projects of playwright.config and @tags used in the tests, for the run configuration editor. */
public final class PwProjectInfo {
    private static final Logger LOG = Logger.getInstance(PwProjectInfo.class);
    private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.[cm]?[jt]sx?$");
    /** "title @smoke" in a test title, or tag: '@smoke' / tag: ['@a', '@b'] in test details. */
    private static final Pattern TITLE_TAG = Pattern.compile("(?<=[\\s(])@[A-Za-z][\\w-]*");
    private static final Pattern DETAILS_TAG = Pattern.compile("['\"`](@[A-Za-z][\\w-]*)['\"`]");
    private static final Pattern TAG_PROPERTY = Pattern.compile("tag\\s*:\\s*\\[?([^\\]\\n}]*)");

    private PwProjectInfo() {
    }

    /** Runs {@code playwright test --list --reporter=json} and returns the project names from the resolved config. */
    public static @NotNull List<String> listProjects(@NotNull File workDir, @NotNull String nodePath) {
        List<String> projects = new ArrayList<>();
        String node = NodeLocator.findNode(nodePath);
        String cli = PwLaunchSpec.findCli(workDir.toPath());
        if (node == null || cli == null) return projects;
        try {
            GeneralCommandLine command = new GeneralCommandLine(node, cli, "test", "--list", "--reporter=json")
                    .withWorkDirectory(workDir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
            ProcessOutput output = new CapturingProcessHandler(command).runProcess(60_000);
            String stdout = output.getStdout();
            int start = stdout.indexOf('{');
            if (start < 0) return projects;
            JsonObject json = JsonParser.parseString(stdout.substring(start)).getAsJsonObject();
            for (JsonElement project : json.getAsJsonObject("config").getAsJsonArray("projects")) {
                String name = project.getAsJsonObject().get("name").getAsString();
                if (!name.isEmpty() && !projects.contains(name)) projects.add(name);
            }
        } catch (Exception e) {
            LOG.info("Cannot list Playwright projects in " + workDir, e);
        }
        return projects;
    }

    /** All {@code @tags} found in the test files under the directory (node_modules and reports skipped). */
    public static @NotNull Set<String> findTags(@NotNull File workDir) {
        Set<String> tags = new TreeSet<>();
        try {
            Files.walkFileTree(workDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return name.equals("node_modules") || name.startsWith(".") && !dir.equals(workDir.toPath())
                            || name.equals("test-results") || name.endsWith("report")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    if (TEST_FILE.matcher(file.getFileName().toString()).matches()) {
                        try {
                            collectTags(Files.readString(file), tags);
                        } catch (IOException ignored) {
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug(e);
        }
        return tags;
    }

    static void collectTags(@NotNull String text, @NotNull Set<String> tags) {
        Matcher title = TITLE_TAG.matcher(text);
        while (title.find()) tags.add(title.group());
        Matcher property = TAG_PROPERTY.matcher(text);
        while (property.find()) {
            Matcher tag = DETAILS_TAG.matcher(property.group(1));
            while (tag.find()) tags.add(tag.group(1));
        }
    }
}
