package dev.pwtoolkit.debug;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import dev.pwtoolkit.run.PwLaunchSpec;
import dev.pwtoolkit.util.NodeLocator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end check of the debugger engine: real js-debug (from the GigaIDE installation), real Playwright run
 * of samples/playwright-offline; the IDE debug session is replaced by a recording proxy.
 */
public class PwDebugProcessTest extends BasePlatformTestCase {
    private record Stop(String kind, XSuspendContext context, XLineBreakpoint<?> breakpoint) {
    }

    private final BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
    private final Set<Object> verified = ConcurrentHashMap.newKeySet();
    private final StringBuffer output = new StringBuffer();
    private PwDebugProcess process;

    @Override
    protected void tearDown() throws Exception {
        try {
            if (process != null) {
                process.stop();
                process.getProcessHandler().waitFor(10_000);
            }
        } finally {
            super.tearDown();
        }
    }

    public void testBreakpointsInPageObjectAndTest() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        String node = NodeLocator.findNode(null);
        assertNotNull("node not found", node);
        PwLaunchSpec spec = new PwLaunchSpec(node, new File(sample, "node_modules/@playwright/test/cli.js").getPath(),
                List.of("test", "tests/login/login.test.ts:5", "--workers=1", "--timeout=0", "--reporter=list"),
                sample, Map.of(), true);

        process = new PwDebugProcess(fakeSession(), spec);
        XLineBreakpoint<?> inPageObject = breakpoint(new File(sample, "pages/loginPage.page.ts"), 25);
        XLineBreakpoint<?> inTest = breakpoint(new File(sample, "tests/login/login.test.ts"), 16);
        process.addBreakpoint(inPageObject);
        process.addBreakpoint(inTest);
        ProcessHandler handler = process.getProcessHandler();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                output.append(event.getText());
            }
        });
        com.intellij.execution.ui.ConsoleView console = (com.intellij.execution.ui.ConsoleView) process.createConsole();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), console);
        console.getComponent(); // creates the console editor (needed in headless tests)
        process.sessionInitialized();

        // 1. stops inside the Page Object method, with its parameters visible
        Stop first = nextStop();
        assertEquals(output.toString(), "breakpoint", first.kind);
        assertSame(inPageObject, first.breakpoint);
        XStackFrame frame = first.context.getActiveExecutionStack().getTopFrame();
        assertPosition(frame, "loginPage.page.ts", 25);
        List<String> names = childNames(frame);
        assertTrue(names.toString(), names.contains("login") && names.contains("password"));
        assertEquals("'admin:Qwerty123'", evaluate(frame, "login + ':' + password"));
        assertTrue("breakpoints must be verified", verified.contains(inPageObject));

        // 2. resume -> stops in the test body
        process.resume(first.context);
        Stop second = nextStop();
        assertSame(inTest, second.breakpoint);
        assertPosition(second.context.getActiveExecutionStack().getTopFrame(), "login.test.ts", 16);
        assertEquals("'Hello, admin'", evaluate(second.context.getActiveExecutionStack().getTopFrame(), "greeting"));

        // 3. step over -> next line of the test
        process.startStepOver(second.context);
        Stop third = nextStop();
        assertEquals("position", third.kind);
        XSourcePosition stepped = third.context.getActiveExecutionStack().getTopFrame().getSourcePosition();
        assertNotNull(stepped);
        assertEquals("login.test.ts", stepped.getFile().getName());
        assertTrue("stepped to line " + stepped.getLine(), stepped.getLine() > 16);

        // 4. resume -> the run finishes successfully
        process.resume(third.context);
        assertTrue("debuggee did not finish:\n" + output, handler.waitFor(60_000));
        assertTrue(output.toString(), output.toString().contains("1 passed"));

        // the Debug tool window console must show Playwright's output
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        ((com.intellij.execution.impl.ConsoleViewImpl) console).flushDeferredText();
        String consoleText = ((com.intellij.execution.impl.ConsoleViewImpl) console).getText();
        assertTrue("console: " + consoleText, consoleText.contains("1 passed"));
    }

    /** VS Code-like "pause on failure": the debugger stops on the failing line and the error is printed. */
    public void testPausesOnFailedStep() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        File dir = new File(sample, "tests/tmp-failing");
        File failing = new File(dir, "failing.test.ts");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        java.nio.file.Files.writeString(failing.toPath(), """
                import test, { expect } from "../baseTest";

                test("fails on purpose", async ({ page, loginPage }) => {
                    await loginPage.open();
                    await expect(page.locator(loginPage.selectors.greeting)).toHaveText("never", { timeout: 500 });
                    await loginPage.open();
                });
                """);
        try {
            String node = NodeLocator.findNode(null);
            PwLaunchSpec spec = new PwLaunchSpec(node, new File(sample, "node_modules/@playwright/test/cli.js").getPath(),
                    List.of("test", "tests/tmp-failing/failing.test.ts", "--workers=1", "--timeout=0", "--reporter=list"),
                    sample, Map.of("PW_TEST_SOURCE_TRANSFORM", dev.pwtoolkit.run.PwResources.install(dev.pwtoolkit.run.PwResources.DEBUG_TRANSFORM).toString(),
                    "PW_TEST_SOURCE_TRANSFORM_SCOPE", dir.getPath()), true);
            process = new PwDebugProcess(fakeSession(), spec);
            process.addBreakpoint(breakpoint(failing, 5));
            ProcessHandler handler = process.getProcessHandler();
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    output.append(event.getText());
                }
            });
            process.sessionInitialized();

            Stop stop = nextStop();
            assertEquals("position", stop.kind);
            assertPosition(stop.context.getActiveExecutionStack().getTopFrame(), "failing.test.ts", 4);
            assertTrue(output.toString(), output.toString().contains("Paused on a failed step at failing.test.ts:5"));
            assertTrue(output.toString(), output.toString().contains("toHaveText"));
            assertFalse("raw ANSI/escaped text: " + output, output.toString().contains("\\x1b") || output.toString().contains("\u001b["));

            process.resume(stop.context);
            assertTrue("debuggee did not finish:\n" + output, handler.waitFor(60_000));
            assertTrue(output.toString(), output.toString().contains("1 failed"));
            assertTrue(output.toString(), output.toString().contains("Breakpoint failing.test.ts:6 was never reached"));
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir);
        }
    }

    /** Debug shows the same test tree as Run: the reporter output reaches the IDE through js-debug. */
    public void testDebugShowsTestTree() throws Exception {
        File sample = new File(System.getProperty("pwtoolkit.sample"));
        PwLaunchSpec spec = new PwLaunchSpec(NodeLocator.findNode(null), new File(sample, "node_modules/@playwright/test/cli.js").getPath(),
                List.of("test", "tests/login/login.test.ts", "--workers=1", "--timeout=0"), sample,
                Map.of("PW_TEST_REPORTER", dev.pwtoolkit.run.PwResources.install(dev.pwtoolkit.run.PwResources.REPORTER).toString()), true);
        dev.pwtoolkit.run.PwRunConfiguration config = (dev.pwtoolkit.run.PwRunConfiguration)
                dev.pwtoolkit.run.PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        config.setWorkingDirectory(sample.getPath());
        process = new PwDebugProcess(fakeSession(), spec, new dev.pwtoolkit.tests.PwTestConsoleProperties(config,
                com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance()));
        var console = (com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView) process.createConsole();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), console);
        process.sessionInitialized();
        assertTrue("debuggee did not finish", process.getProcessHandler().waitFor(120_000));
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        var tests = console.getResultsViewer().getTestsRootNode().getAllTests();
        var names = tests.stream().map(t -> t.getName()).toList();
        assertTrue(names.toString(), names.contains("admin sees greeting") && names.contains("header shows title"));
        assertTrue(tests.stream().filter(t -> t.isLeaf()).allMatch(t -> t.isPassed()));
    }

    private Stop nextStop() throws InterruptedException {
        Stop stop = stops.poll(120, TimeUnit.SECONDS);
        assertNotNull("debugger did not stop. Output:\n" + output, stop);
        return stop;
    }

    private static void assertPosition(XStackFrame frame, String fileName, int line) {
        XSourcePosition position = frame.getSourcePosition();
        assertNotNull("no source position", position);
        assertEquals(fileName, position.getFile().getName());
        assertEquals(line, position.getLine());
    }

    private static String evaluate(XStackFrame frame, String expression) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        frame.getEvaluator().evaluate(expression, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue value) {
                result.complete(((PwValue) value).getValueText());
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                result.completeExceptionally(new AssertionError(errorMessage));
            }
        }, null);
        return result.get(30, TimeUnit.SECONDS);
    }

    private static List<String> childNames(XStackFrame frame) throws Exception {
        List<String> names = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        XCompositeNode node = (XCompositeNode) Proxy.newProxyInstance(XCompositeNode.class.getClassLoader(), new Class[]{XCompositeNode.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addChildren" -> {
                            XValueChildrenList list = (XValueChildrenList) args[0];
                            for (int i = 0; i < list.size(); i++) names.add(list.getName(i));
                            if ((Boolean) args[1]) done.complete(null);
                        }
                        case "setErrorMessage" -> done.completeExceptionally(new AssertionError(String.valueOf(args[0])));
                        case "isObsolete" -> {
                            return false;
                        }
                        default -> {
                        }
                    }
                    return null;
                });
        frame.computeChildren(node);
        done.get(30, TimeUnit.SECONDS);
        return names;
    }

    private XDebugSession fakeSession() {
        return (XDebugSession) Proxy.newProxyInstance(XDebugSession.class.getClassLoader(), new Class[]{XDebugSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "breakpointReached" -> {
                        XSuspendContext context = null;
                        for (Object arg : args) if (arg instanceof XSuspendContext c) context = c;
                        stops.add(new Stop("breakpoint", context, (XLineBreakpoint<?>) args[0]));
                        yield true;
                    }
                    case "positionReached" -> {
                        stops.add(new Stop("position", (XSuspendContext) args[0], null));
                        yield null;
                    }
                    case "setBreakpointVerified" -> {
                        verified.add(args[0]);
                        yield null;
                    }
                    case "getProject" -> getProject();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeXDebugSession";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static XLineBreakpoint<?> breakpoint(File file, int line) {
        String url = VfsUtilCore.pathToUrl(file.getPath());
        return (XLineBreakpoint<?>) Proxy.newProxyInstance(XLineBreakpoint.class.getClassLoader(), new Class[]{XLineBreakpoint.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFileUrl" -> url;
                    case "getLine" -> line;
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> file.getName() + ":" + (line + 1);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
