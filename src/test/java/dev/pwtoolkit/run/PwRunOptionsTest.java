package dev.pwtoolkit.run;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class PwRunOptionsTest extends BasePlatformTestCase {
    private File sample;
    private PwRunConfiguration config;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sample = new File(System.getProperty("pwtoolkit.sample"));
        config = (PwRunConfiguration) PwRunConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        config.setWorkingDirectory(sample.getPath());
        config.setTestPath(new File(sample, "tests/login/login.test.ts").getPath());
    }

    public void testRunOptionsBecomeCliFlags() throws Exception {
        config.setProjectName("offline");
        config.setTestName("@smoke");
        config.setGrepInvert("@slow");
        config.setRetries("2");
        config.setRepeatEach(5);
        config.setMaxFailures(1);
        config.setWorkers("3");
        config.setLastFailed(true);
        config.setTraceMode("retain-on-failure");

        List<String> args = PwLaunchSpec.create(config, false).args();
        assertEquals("test", args.get(0));
        assertEquals("tests/login/login.test.ts", args.get(1));
        assertContainsElements(args, "--project=offline", "--grep", "@smoke", "--grep-invert", "@slow", "--retries=2",
                "--repeat-each=5", "--max-failures=1", "--workers=3", "--last-failed", "--trace=retain-on-failure");

        // debug keeps the user's workers value instead of forcing 1
        List<String> debugArgs = PwLaunchSpec.create(config, true).args();
        assertContainsElements(debugArgs, "--workers=3", "--timeout=0");
        assertDoesntContain(debugArgs, "--workers=1");
    }

    public void testDefaultsAddNothingAndReporterIsAttached() throws Exception {
        PwLaunchSpec spec = PwLaunchSpec.create(config, false);
        assertEquals(List.of("test", "tests/login/login.test.ts"), spec.args());
        assertTrue(spec.env().get("PW_TEST_REPORTER").endsWith("pwtk-reporter.cjs"));
        assertContainsElements(PwLaunchSpec.create(config, true).args(), "--workers=1");
    }

    public void testRerunFailedIgnoresLastFailedAndTestPath() throws Exception {
        config.setLastFailed(true);
        config.setRerunTargets(List.of(new File(sample, "tests/login/login.test.ts").getPath() + ":20"));
        List<String> args = PwLaunchSpec.create(config, false).args();
        assertEquals(List.of("test", "tests/login/login.test.ts:20"), args);
    }

    public void testListsProjectsFromConfig() {
        assertEquals(List.of("offline"), PwProjectInfo.listProjects(sample, ""));
    }

    public void testFindsTags() {
        Set<String> tags = new TreeSet<>();
        PwProjectInfo.collectTags("""
                import { test } from '@playwright/test';
                test('login works @smoke @auth', async () => {});
                test('cart', { tag: ['@regress', "@cart"] }, async () => {});
                test.describe('suite', { tag: '@ui' }, () => {});
                const mail = 'autotest+1@example.com';
                """, tags);
        assertEquals(Set.of("@smoke", "@auth", "@regress", "@cart", "@ui"), tags);
    }
}
