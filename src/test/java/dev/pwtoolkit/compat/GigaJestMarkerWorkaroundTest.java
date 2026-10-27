package dev.pwtoolkit.compat;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.lang.LanguageExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class GigaJestMarkerWorkaroundTest extends BasePlatformTestCase {
    private static final ExtensionPointName<LanguageExtensionPoint<RunLineMarkerContributor>> EP =
            ExtensionPointName.create("com.intellij.runLineMarkerContributor");

    public void testRemovesOnlyTheJestMarker() {
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.pwtoolkit.gigaide"));
        assertNotNull(descriptor);
        EP.getPoint().registerExtension(
                new LanguageExtensionPoint<>("TypeScript", GigaJestMarkerWorkaround.JEST_MARKER, descriptor),
                // not the test disposable: the workaround removes the registration itself, a second removal would fail
                com.intellij.openapi.util.Disposer.newDisposable("fake GigaIDE Jest marker"));
        assertTrue(hasMarker(GigaJestMarkerWorkaround.JEST_MARKER));

        assertEquals(1, GigaJestMarkerWorkaround.apply());

        assertFalse(hasMarker(GigaJestMarkerWorkaround.JEST_MARKER));
        assertTrue("our Playwright marker must stay", hasMarker("dev.pwtoolkit.run.PwTestLineMarkerContributor"));
        assertEquals(0, GigaJestMarkerWorkaround.apply());
    }

    private static boolean hasMarker(String implementationClass) {
        return EP.getExtensionList().stream().anyMatch(bean -> implementationClass.equals(bean.implementationClass));
    }

    /** Stand-in for GigaIDE's tsx marker: an icon on every `test` identifier. */
    public static final class FakeTsxMarker extends RunLineMarkerContributor {
        @Override
        public Info getInfo(com.intellij.psi.PsiElement element) {
            return "test".equals(element.getText()) && element.getFirstChild() == null
                    ? new Info(com.intellij.icons.AllIcons.Actions.Execute, new com.intellij.openapi.actionSystem.AnAction[0], e -> "tsx")
                    : null;
        }
    }

    public void testTsxMarkerHiddenOnlyInPlaywrightTests() {
        LanguageExtensionPoint<RunLineMarkerContributor> fake = new LanguageExtensionPoint<>("TypeScript", new FakeTsxMarker());
        fake.setPluginDescriptor(PluginManagerCore.getPlugin(PluginId.getId("dev.pwtoolkit.gigaide")));
        EP.getPoint().registerExtension(fake, com.intellij.openapi.util.Disposer.newDisposable("fake GigaIDE tsx marker"));
        assertEquals(1, GigaJestMarkerWorkaround.wrapForNonPlaywrightFiles(FakeTsxMarker.class.getName()));
        RunLineMarkerContributor wrapped = EP.getExtensionList().stream()
                .filter(b -> b.getInstance() instanceof GigaJestMarkerWorkaround.NotInPlaywrightTests)
                .map(LanguageExtensionPoint::getInstance).findFirst().orElseThrow();

        var sample = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(
                System.getProperty("pwtoolkit.sample") + "/tests/login/login.test.ts");
        myFixture.configureFromExistingVirtualFile(sample);
        var inTest = myFixture.getFile().findElementAt(myFixture.getEditor().getDocument().getText().indexOf("test(\"admin"));
        assertNull("hidden in a Playwright test file", wrapped.getInfo(inTest));

        myFixture.configureByText("plain.test.ts", "test('node test', () => {});");
        var elsewhere = myFixture.getFile().findElementAt(0);
        assertNotNull("kept outside Playwright projects", wrapped.getInfo(elsewhere));
    }
}
