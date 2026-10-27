package dev.pwtoolkit.run;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to start {@code node <playwright cli.js> test ...}, shared by Run and Debug.
 */
public record PwLaunchSpec(@NotNull String node,
                           @NotNull String cli,
                           @NotNull List<String> args,
                           @NotNull File workDir,
                           @NotNull Map<String, String> env,
                           boolean passParentEnv) {

    /** Where the reporter writes attachments (screenshots, traces) of the run, for the IDE actions. */
    public static final String RESULTS_FILE_ENV = "PWTK_RESULTS_FILE";

    public static @NotNull PwLaunchSpec create(@NotNull PwRunConfiguration config, boolean debug) throws ExecutionException {
        File workDir = new File(StringUtil.notNullize(config.getWorkingDirectory()));
        if (!workDir.isDirectory()) throw new ExecutionException("Working directory does not exist: " + workDir);

        String cli = findCli(workDir.toPath());
        if (cli == null) {
            throw new ExecutionException("Playwright is not installed: node_modules/@playwright/test/cli.js was not found above " +
                    workDir + ". Run `npm install` in the project.");
        }
        String node = NodeLocator.findNode(config.getNodePath());
        if (node == null) throw new ExecutionException("Node.js was not found. Set it in the run configuration or in Settings | Tools | Playwright Toolkit.");

        List<String> args = new ArrayList<>();
        args.add("test");
        if (!config.getRerunTargets().isEmpty()) {
            // "Rerun Failed Tests": the failed tests as file:line filters
            for (String rerun : config.getRerunTargets()) args.add(relativeTarget(workDir, rerun));
        } else {
            String target = relativeTarget(workDir, config.getTestPath());
            if (!target.isEmpty()) args.add(config.getTestLine() > 0 ? target + ":" + config.getTestLine() : target);
        }
        if (!StringUtil.isEmptyOrSpaces(config.getProjectName())) args.add("--project=" + config.getProjectName().trim());
        if (!StringUtil.isEmptyOrSpaces(config.getTestName())) {
            args.add("--grep");
            args.add(config.getTestName());
        }
        if (!StringUtil.isEmptyOrSpaces(config.getGrepInvert())) {
            args.add("--grep-invert");
            args.add(config.getGrepInvert());
        }
        if (!StringUtil.isEmptyOrSpaces(config.getRetries())) args.add("--retries=" + config.getRetries().trim());
        if (config.getRepeatEach() > 0) args.add("--repeat-each=" + config.getRepeatEach());
        if (config.getMaxFailures() > 0) args.add("--max-failures=" + config.getMaxFailures());
        if (!StringUtil.isEmptyOrSpaces(config.getWorkers())) args.add("--workers=" + config.getWorkers().trim());
        if (config.isLastFailed() && config.getRerunTargets().isEmpty()) args.add("--last-failed");
        if (!StringUtil.isEmptyOrSpaces(config.getTraceMode())) args.add("--trace=" + config.getTraceMode().trim());
        PwSettings.StateBean settings = PwSettings.getInstance().getState();
        if (config.isHeaded() || (debug && settings.showBrowserWhenDebugging)) args.add("--headed");
        List<String> extra = ParametersListUtil.parse(StringUtil.notNullize(config.getExtraArgs()));
        args.addAll(extra);
        Map<String, String> env = new LinkedHashMap<>();
        if (debug) {
            // one worker keeps the order predictable, no timeout lets you sit on a breakpoint
            if (args.stream().noneMatch(a -> a.startsWith("--workers") || a.equals("-j"))) args.add("--workers=1");
            if (extra.stream().noneMatch(a -> a.startsWith("--timeout"))) args.add("--timeout=0");
            // same as the VS Code extension: `playwright` helper object in the browser DevTools console
            env.put("PWDEBUG", "console");
            if (settings.pauseOnFailure) {
                env.put("PW_TEST_SOURCE_TRANSFORM", PwResources.install(PwResources.DEBUG_TRANSFORM).toString());
                env.put("PW_TEST_SOURCE_TRANSFORM_SCOPE", transformScope(workDir, config.getTestPath()));
            }
        }
        // the IDE test tree: added next to the reporters of playwright.config (html, extended report...), not instead of them
        env.put("PW_TEST_REPORTER", PwResources.install(PwResources.REPORTER).toString());
        env.put(RESULTS_FILE_ENV, PwResources.dir().resolve("results").resolve(java.util.UUID.randomUUID() + ".json").toString());
        env.putAll(config.getEnvData().getEnvs());
        return new PwLaunchSpec(node, cli, args, workDir, env, config.getEnvData().isPassParentEnvs());
    }

    /** Only the tests themselves are instrumented (not Page Objects), so a failure pauses once, in the test. */
    private static @NotNull String transformScope(@NotNull File workDir, @Nullable String testPath) {
        if (StringUtil.isEmptyOrSpaces(testPath)) return workDir.getPath();
        File target = new File(testPath);
        if (!target.isAbsolute()) target = new File(workDir, testPath);
        return target.isDirectory() ? target.getPath() : target.getParent();
    }

    static @Nullable String suggestName(@NotNull PwRunConfiguration config) {
        if (!StringUtil.isEmptyOrSpaces(config.getTestName())) return config.getTestName();
        String path = config.getTestPath();
        if (StringUtil.isEmptyOrSpaces(path)) return "All Playwright tests";
        String name = new File(path).getName();
        return config.getTestLine() > 0 ? name + ":" + config.getTestLine() : name;
    }

    /** Playwright treats CLI filters as paths relative to the working directory, with forward slashes. */
    private static @NotNull String relativeTarget(@NotNull File workDir, @Nullable String testPath) {
        if (StringUtil.isEmptyOrSpaces(testPath)) return "";
        File target = new File(testPath);
        if (!target.isAbsolute()) target = new File(workDir, testPath);
        String relative = FileUtil.getRelativePath(workDir, target);
        return FileUtil.toSystemIndependentName(relative != null ? relative : target.getPath());
    }

    public static @Nullable String findCli(@NotNull Path workDir) {
        for (Path dir = workDir; dir != null; dir = dir.getParent()) {
            for (String candidate : List.of("node_modules/@playwright/test/cli.js", "node_modules/playwright/cli.js")) {
                File cli = dir.resolve(candidate).toFile();
                if (cli.isFile()) return cli.getPath();
            }
        }
        return null;
    }
}
