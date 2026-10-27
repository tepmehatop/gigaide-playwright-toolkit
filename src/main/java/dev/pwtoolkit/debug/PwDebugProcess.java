package dev.pwtoolkit.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.util.net.NetUtils;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.tests.PwTestConsoleProperties;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debugs {@code playwright test} through vscode-js-debug's DAP server.
 * <p>
 * The root session launches the Playwright CLI; js-debug then asks (reverse request {@code startDebugging})
 * to open a separate DAP session for every child target - the test-runner process and each worker.
 * Breakpoints are sent to every session; whichever target loads the file binds them.
 */
public final class PwDebugProcess extends XDebugProcess {
    private static final Logger LOG = Logger.getInstance(PwDebugProcess.class);
    private static final String READY_MARKER = "Debug server listening at";

    private final PwLaunchSpec spec;
    /** When set, the Debug tab shows the Playwright test tree (same as Run) instead of a plain console. */
    private final @Nullable PwTestConsoleProperties testProperties;
    private final PwDebugProcessHandler processHandler = new PwDebugProcessHandler(this::stopAsync0);
    private final XBreakpointHandler<?>[] breakpointHandlers;
    /** Breakpoints per file path; a key stays (with an empty list) so later setBreakpoints clear removed ones. */
    private final Map<String, List<XLineBreakpoint<?>>> breakpoints = new ConcurrentHashMap<>();
    private final List<DapSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile OSProcessHandler server;
    private volatile int port;
    private volatile DapSession root;
    private volatile int exitCode;
    private volatile RunToTarget runTo;
    /** Breakpoints bound to loaded code, and the ones actually hit: to explain at the end which were never reached. */
    private final Set<XLineBreakpoint<?>> boundBreakpoints = ConcurrentHashMap.newKeySet();
    private final Set<XLineBreakpoint<?>> hitBreakpoints = ConcurrentHashMap.newKeySet();

    private record RunToTarget(@NotNull String path, int line) {
    }

    PwDebugProcess(@NotNull XDebugSession session, @NotNull PwLaunchSpec spec) {
        this(session, spec, null);
    }

    PwDebugProcess(@NotNull XDebugSession session, @NotNull PwLaunchSpec spec, @Nullable PwTestConsoleProperties testProperties) {
        super(session);
        this.spec = spec;
        this.testProperties = testProperties;
        List<XBreakpointHandler<?>> handlers = new ArrayList<>();
        handlers.add(new PwBreakpointHandler(PwLineBreakpointType.class, this));
        // GigaIDE's own JavaScript Debugger plugin (if enabled) has a line breakpoint type too: honour those as well
        for (XBreakpointType<?, ?> type : XBreakpointType.EXTENSION_POINT_NAME.getExtensionList()) {
            if ("dap-breakpoint".equals(type.getId())) handlers.add(new PwBreakpointHandler(type.getClass(), this));
        }
        this.breakpointHandlers = handlers.toArray(XBreakpointHandler[]::new);
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return PwEditorsProvider.INSTANCE;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return breakpointHandlers;
    }

    @Override
    protected @NotNull ProcessHandler doGetProcessHandler() {
        return processHandler;
    }

    /** The platform does not connect the default debug console to the process: do it, or Playwright's output is lost. */
    @Override
    public @NotNull com.intellij.execution.ui.ExecutionConsole createConsole() {
        if (testProperties != null) {
            try {
                var console = com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil.createAndAttachConsole(
                        PwTestConsoleProperties.FRAMEWORK, processHandler, testProperties);
                console.addMessageFilter(testProperties.createLinkFilter());
                return console;
            } catch (com.intellij.execution.ExecutionException e) {
                LOG.warn("Cannot create the test tree console", e);
            }
        }
        com.intellij.execution.ui.ConsoleView console = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
                .createBuilder(getSession().getProject()).getConsole();
        console.attachToProcess(processHandler);
        return console;
    }

    @Override
    public void sessionInitialized() {
        if (!processHandler.isStartNotified()) processHandler.startNotify();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                start();
            } catch (Exception e) {
                LOG.info("Cannot start Playwright debug session", e);
                processHandler.print("Cannot start debugging: " + e.getMessage() + "\n", ProcessOutputTypes.STDERR);
                stopAsync0();
            }
        });
    }

    private void start() throws Exception {
        Path script = JsDebugLocator.findServerScript();
        if (script == null) {
            throw new IOException("vscode-js-debug (dapDebugServer.js) was not found. It is part of GigaIDE's NodeJS plugin; " +
                    "or set its folder in Settings | Tools | Playwright Toolkit.");
        }
        port = NetUtils.findAvailableSocketPort();
        GeneralCommandLine command = new GeneralCommandLine(spec.node(), script.toString(), String.valueOf(port), "127.0.0.1")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        CountDownLatch ready = new CountDownLatch(1);
        OSProcessHandler serverHandler = new OSProcessHandler(command);
        serverHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (event.getText().contains(READY_MARKER)) {
                    ready.countDown();
                } else if (outputType == ProcessOutputTypes.STDERR) {
                    processHandler.print("[js-debug] " + event.getText(), ProcessOutputTypes.SYSTEM);
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                ready.countDown();
                if (!stopping.get()) stopAsync0();
            }
        });
        server = serverHandler;
        serverHandler.startNotify();
        if (!ready.await(30, TimeUnit.SECONDS) || serverHandler.isProcessTerminated()) {
            throw new IOException("js-debug server did not start (" + command.getCommandLineString() + ")");
        }
        processHandler.print(FileUtil.toSystemDependentName(spec.cli()) + " " + String.join(" ", spec.args()) + "\n", ProcessOutputTypes.SYSTEM);
        root = openSession("Playwright", "launch", launchConfiguration());
    }

    private @NotNull JsonObject launchConfiguration() {
        JsonObject config = new JsonObject();
        config.addProperty("type", "pwa-node");
        config.addProperty("request", "launch");
        config.addProperty("name", "Playwright");
        config.addProperty("runtimeExecutable", spec.node());
        config.addProperty("program", spec.cli());
        config.add("args", toArray(spec.args()));
        config.addProperty("cwd", spec.workDir().getPath());
        JsonObject env = new JsonObject();
        spec.env().forEach(env::addProperty);
        config.add("env", env);
        config.addProperty("console", "internalConsole");
        config.addProperty("outputCapture", "std");
        config.addProperty("sourceMaps", true);
        config.addProperty("autoAttachChildProcesses", true);
        List<String> skip = new ArrayList<>(List.of("<node_internals>/**"));
        if (PwSettings.getInstance().getState().skipPlaywrightInternals) {
            skip.addAll(List.of("**/node_modules/playwright-core/**", "**/node_modules/playwright/**", "**/node_modules/@playwright/**"));
        }
        config.add("skipFiles", toArray(skip));
        return config;
    }

    private @NotNull DapSession openSession(@NotNull String name, @NotNull String request, @NotNull JsonObject configuration) throws IOException {
        DapSession session = new DapSession(name);
        LOG.debug("[pwtoolkit] debug: open session '" + name + "' (" + request + ")");
        sessions.add(session);
        session.start(request, configuration);
        return session;
    }

    // ---- breakpoints -------------------------------------------------------------------------------------------

    void addBreakpoint(@NotNull XLineBreakpoint<?> breakpoint) {
        String path = pathOf(breakpoint);
        if (path == null) return;
        breakpoints.compute(path, (k, list) -> {
            List<XLineBreakpoint<?>> result = list == null ? new CopyOnWriteArrayList<>() : list;
            if (!result.contains(breakpoint)) result.add(breakpoint);
            return result;
        });
        resendBreakpoints(path);
    }

    void removeBreakpoint(@NotNull XLineBreakpoint<?> breakpoint) {
        String path = pathOf(breakpoint);
        if (path == null) return;
        List<XLineBreakpoint<?>> list = breakpoints.get(path);
        if (list != null) list.remove(breakpoint);
        resendBreakpoints(path);
    }

    private void resendBreakpoints(@NotNull String path) {
        for (DapSession session : sessions) {
            if (session.configured) session.sendBreakpoints(path);
        }
    }

    private static @Nullable String pathOf(@NotNull XLineBreakpoint<?> breakpoint) {
        String url = breakpoint.getFileUrl();
        return url == null ? null : FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(url));
    }

    private @Nullable XLineBreakpoint<?> breakpointAt(@Nullable String path, int line) {
        if (path == null) return null;
        List<XLineBreakpoint<?>> list = breakpoints.get(FileUtil.toSystemDependentName(path));
        if (list == null) return null;
        for (XLineBreakpoint<?> breakpoint : list) {
            if (breakpoint.getLine() + 1 == line) return breakpoint;
        }
        return null;
    }

    // ---- stepping ----------------------------------------------------------------------------------------------

    @Override
    public void resume(@Nullable XSuspendContext context) {
        threadCommand(context, "continue");
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        threadCommand(context, "next");
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        threadCommand(context, "stepIn");
    }

    @Override
    public void startForceStepInto(@Nullable XSuspendContext context) {
        threadCommand(context, "stepIn");
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        threadCommand(context, "stepOut");
    }

    @Override
    public void startPausing() {
        for (DapSession session : sessions) {
            for (Integer threadId : session.threads) session.connection.request("pause", Map.of("threadId", threadId));
        }
    }

    @Override
    public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
        String path = FileUtil.toSystemDependentName(position.getFile().getPath());
        runTo = new RunToTarget(path, position.getLine() + 1);
        resendBreakpoints(path);
        resume(context);
    }

    private void threadCommand(@Nullable XSuspendContext context, @NotNull String command) {
        if (context instanceof PwSuspendContext suspendContext) {
            PwExecutionStack stack = suspendContext.getActiveExecutionStack();
            stack.session.connection.request(command, Map.of("threadId", stack.threadId));
        }
    }

    // ---- stop --------------------------------------------------------------------------------------------------

    @Override
    public void stop() {
        stopAsync0();
    }

    private void stopAsync0() {
        if (!stopping.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DapSession rootSession = root;
            if (rootSession != null && !rootSession.connection.isClosed()) {
                try {
                    rootSession.connection.request("disconnect", Map.of("terminateDebuggee", true)).get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            sessions.forEach(s -> s.connection.close());
            OSProcessHandler serverHandler = server;
            if (serverHandler != null) serverHandler.destroyProcess();
            reportUnreachedBreakpoints();
            processHandler.finish(exitCode);
        });
    }

    private void markBound(@NotNull XLineBreakpoint<?> breakpoint) {
        boundBreakpoints.add(breakpoint);
        getSession().setBreakpointVerified(breakpoint);
    }

    private void reportUnreachedBreakpoints() {
        for (XLineBreakpoint<?> breakpoint : boundBreakpoints) {
            if (hitBreakpoints.contains(breakpoint) || !breakpoint.isEnabled()) continue;
            String path = pathOf(breakpoint);
            String where = (path == null ? "?" : new java.io.File(path).getName()) + ":" + (breakpoint.getLine() + 1);
            processHandler.print("\n⚠ Breakpoint " + where + " was never reached: the code ran, but execution did not get to this line " +
                    "(usually the test failed or was skipped earlier — see the errors above).\n", ProcessOutputTypes.SYSTEM);
        }
    }

    private static @NotNull JsonArray toArray(@NotNull List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /**
     * Explore locators while paused: evaluates a locator expression (side-effect free, see PwLocatorExpressions) in the
     * selected frame and returns locator code for the highlight ({@code locator('h1')}), or null.
     */
    public @Nullable String evaluateLocator(@NotNull String expression) {
        if (!(getSession().getSuspendContext() instanceof PwSuspendContext context)) return null;
        PwExecutionStack stack = context.getActiveExecutionStack();
        com.intellij.xdebugger.frame.XStackFrame current = getSession().getCurrentStackFrame();
        int frameId = current instanceof PwStackFrame frame ? frame.frameId()
                : stack.getTopFrame() instanceof PwStackFrame top ? top.frameId() : -1;
        if (frameId < 0) return null;
        String js = "(() => { try { const v = (" + expression + "); "
                + "if (typeof v === 'string') return \"locator('\" + v.replace(/\\\\/g, '\\\\\\\\').replace(/'/g, \"\\\\'\") + \"')\"; "
                + "if (v && typeof v._selector === 'string') return String(v); return ''; } catch (e) { return ''; } })()";
        try {
            JsonObject result = stack.session.connection.request("evaluate",
                    Map.of("expression", js, "frameId", frameId, "context", "clipboard")).get(5, TimeUnit.SECONDS);
            String value = result.has("result") ? result.get("result").getAsString().strip() : "";
            // js-debug wraps strings that contain ' in double quotes, even in the "clipboard" context
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = new com.google.gson.Gson().fromJson(value, String.class);
            }
            return value.isBlank() || value.equals("''") ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    static @NotNull String errorMessage(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return String.valueOf(cause.getMessage());
    }

    /** One DAP connection: the root launch or a child target announced via startDebugging. */
    final class DapSession implements DapConnection.Listener {
        final String name;
        final DapConnection connection;
        final Set<Integer> threads = ConcurrentHashMap.newKeySet();
        final Map<Integer, XLineBreakpoint<?>> breakpointIds = new ConcurrentHashMap<>();
        volatile boolean configured;

        DapSession(@NotNull String name) throws IOException {
            this.name = name;
            this.connection = DapConnection.connect(port, this, name);
        }

        void start(@NotNull String request, @NotNull JsonObject configuration) {
            JsonObject initialize = new JsonObject();
            initialize.addProperty("clientID", "playwright-toolkit");
            initialize.addProperty("clientName", "Playwright Toolkit for GigaIDE");
            initialize.addProperty("adapterID", "pwa-node");
            initialize.addProperty("pathFormat", "path");
            initialize.addProperty("linesStartAt1", true);
            initialize.addProperty("columnsStartAt1", true);
            initialize.addProperty("supportsVariableType", true);
            initialize.addProperty("supportsStartDebuggingRequest", true);
            initialize.addProperty("supportsRunInTerminalRequest", false);
            connection.request("initialize", initialize)
                    .thenCompose(capabilities -> connection.request(request, configuration))
                    .exceptionally(error -> {
                        if (this == root && !stopping.get()) {
                            processHandler.print("Debug launch failed: " + errorMessage(error) + "\n", ProcessOutputTypes.STDERR);
                            stopAsync0();
                        }
                        return null;
                    });
        }

        @Override
        public void onEvent(@NotNull DapConnection c, @NotNull String event, @NotNull JsonObject body) {
            switch (event) {
                case "initialized" -> {
                    Set<String> paths = new HashSet<>(breakpoints.keySet());
                    RunToTarget target = runTo;
                    if (target != null) paths.add(target.path);
                    paths.forEach(this::sendBreakpoints);
                    connection.request("setExceptionBreakpoints", Map.of("filters", List.of()));
                    connection.request("configurationDone", null);
                    configured = true;
                }
                case "output" -> onOutput(body);
                case "stopped" -> onStopped(body);
                case "thread" -> {
                    int threadId = body.get("threadId").getAsInt();
                    if ("exited".equals(body.get("reason").getAsString())) threads.remove(threadId);
                    else threads.add(threadId);
                }
                case "breakpoint" -> {
                    LOG.debug("[pwtoolkit] debug: [" + name + "] breakpoint event " + body);
                    JsonObject bp = body.getAsJsonObject("breakpoint");
                    if (bp != null && bp.has("id") && bp.has("verified") && bp.get("verified").getAsBoolean()) {
                        XLineBreakpoint<?> breakpoint = breakpointIds.get(bp.get("id").getAsInt());
                        if (breakpoint != null) markBound(breakpoint);
                    }
                }
                case "exited" -> {
                    if (this == root && body.has("exitCode")) exitCode = body.get("exitCode").getAsInt();
                }
                case "terminated" -> {
                    if (this == root) stopAsync0();
                }
                default -> {
                }
            }
        }

        @Override
        public @Nullable JsonObject onReverseRequest(@NotNull DapConnection c, @NotNull String command, @NotNull JsonObject arguments) throws Exception {
            if ("startDebugging".equals(command)) {
                JsonObject configuration = arguments.getAsJsonObject("configuration");
                String request = arguments.has("request") ? arguments.get("request").getAsString() : "launch";
                String childName = configuration.has("name") ? configuration.get("name").getAsString() : "child";
                openSession(childName, request, configuration);
                return new JsonObject();
            }
            throw new UnsupportedOperationException(command + " is not supported");
        }

        @Override
        public void onClosed(@NotNull DapConnection c) {
            sessions.remove(this);
            if (this == root) stopAsync0();
        }

        void sendBreakpoints(@NotNull String path) {
            List<XLineBreakpoint<?>> list = new ArrayList<>(breakpoints.getOrDefault(path, List.of()));
            JsonArray dapBreakpoints = new JsonArray();
            for (XLineBreakpoint<?> breakpoint : list) {
                JsonObject bp = new JsonObject();
                bp.addProperty("line", breakpoint.getLine() + 1);
                var condition = breakpoint.getConditionExpression();
                if (condition != null && !condition.getExpression().isBlank()) bp.addProperty("condition", condition.getExpression());
                dapBreakpoints.add(bp);
            }
            RunToTarget target = runTo;
            if (target != null && target.path.equals(path)) {
                JsonObject bp = new JsonObject();
                bp.addProperty("line", target.line);
                dapBreakpoints.add(bp);
            }
            connection.request("setBreakpoints", Map.of("source", Map.of("path", path), "breakpoints", dapBreakpoints))
                    .thenAccept(response -> {
                        JsonArray result = response.getAsJsonArray("breakpoints");
                        LOG.debug("[pwtoolkit] debug: [" + name + "] setBreakpoints " + path + " " + dapBreakpoints + " -> " + result);
                        if (result == null) return;
                        for (int i = 0; i < Math.min(result.size(), list.size()); i++) {
                            JsonObject bp = result.get(i).getAsJsonObject();
                            if (bp.has("id")) breakpointIds.put(bp.get("id").getAsInt(), list.get(i));
                            if (bp.has("verified") && bp.get("verified").getAsBoolean()) markBound(list.get(i));
                        }
                    });
        }

        private void onOutput(@NotNull JsonObject body) {
            String category = body.has("category") ? body.get("category").getAsString() : "console";
            if ("telemetry".equals(category) || !body.has("output")) return;
            Key<?> type = switch (category) {
                case "stderr" -> ProcessOutputTypes.STDERR;
                case "important" -> ProcessOutputTypes.SYSTEM;
                default -> ProcessOutputTypes.STDOUT;
            };
            processHandler.print(body.get("output").getAsString(), type);
        }

        private void onStopped(@NotNull JsonObject body) {
            if (!body.has("threadId")) return;
            int threadId = body.get("threadId").getAsInt();
            String reason = body.has("reason") ? body.get("reason").getAsString() : "";
            LOG.debug("[pwtoolkit] debug: [" + name + "] stopped " + body);
            JsonArray frames;
            try {
                JsonObject trace = connection.request("stackTrace", Map.of("threadId", threadId, "startFrame", 0, "levels", 200))
                        .get(15, TimeUnit.SECONDS);
                frames = trace.has("stackFrames") ? trace.getAsJsonArray("stackFrames") : new JsonArray();
            } catch (Exception e) {
                LOG.info("stackTrace failed", e);
                frames = new JsonArray();
            }
            PwSuspendContext context = new PwSuspendContext(new PwExecutionStack(this, threadId, frames));

            String topPath = null;
            int topLine = -1;
            if (!frames.isEmpty()) {
                JsonObject top = frames.get(0).getAsJsonObject();
                JsonObject source = top.has("source") ? top.getAsJsonObject("source") : null;
                topPath = source != null && source.has("path") ? source.get("path").getAsString() : null;
                topLine = top.get("line").getAsInt();
            }

            RunToTarget target = runTo;
            if (target != null && topPath != null && FileUtil.pathsEqual(target.path, topPath) && target.line == topLine) {
                runTo = null;
                resendBreakpoints(target.path);
                if (breakpointAt(topPath, topLine) == null) {
                    getSession().positionReached(context);
                    return;
                }
            }

            XLineBreakpoint<?> breakpoint = null;
            if ("breakpoint".equals(reason)) {
                JsonArray hit = body.getAsJsonArray("hitBreakpointIds");
                if (hit != null) {
                    for (JsonElement id : hit) {
                        breakpoint = breakpointIds.get(id.getAsInt());
                        if (breakpoint != null) break;
                    }
                }
                if (breakpoint == null) breakpoint = breakpointAt(topPath, topLine);
            }
            if (breakpoint != null) {
                hitBreakpoints.add(breakpoint);
                if (!getSession().breakpointReached(breakpoint, null, context)) {
                    connection.request("continue", Map.of("threadId", threadId));
                }
            } else {
                if (!frames.isEmpty()) {
                    reportFailedStep(frames.get(0).getAsJsonObject().get("id").getAsInt(), topPath, topLine);
                }
                getSession().positionReached(context);
            }
        }

        /** Stopped on the `debugger` inserted by debugTransform.cjs: show why the step failed. */
        private void reportFailedStep(int frameId, @Nullable String path, int line) {
            try {
                // "clipboard" context returns the raw string (no quotes/escaping); ANSI colors are stripped in JS
                JsonObject result = connection.request("evaluate", Map.of("frameId", frameId, "context", "clipboard",
                        "expression", "typeof __playwright_error__ === 'undefined' ? '' : " +
                                "String(__playwright_error__?.message ?? __playwright_error__).replace(/\\u001b\\[[0-9;]*m/g, '')"))
                        .get(5, TimeUnit.SECONDS);
                String message = result.has("result") ? result.get("result").getAsString().strip() : "";
                if (message.isEmpty() || message.equals("''")) return;
                String where = path == null ? "" : " at " + new java.io.File(path).getName() + ":" + line;
                processHandler.print("\nâ¸ Paused on a failed step" + where + " (resume to let the test fail):\n" + message + "\n\n",
                        ProcessOutputTypes.STDERR);
                // a balloon in the Debug window, so a failure stop is not mistaken for a breakpoint
                String firstLine = message.lines().findFirst().orElse(message);
                getSession().reportMessage("Test step failed" + where + ": " + firstLine
                        + "\nPaused before the test fails (Settings | Tools | Playwright Toolkit to disable).", MessageType.WARNING);
            } catch (Exception e) {
                LOG.debug("Cannot read the failed step error", e);
            }
        }
    }
}
