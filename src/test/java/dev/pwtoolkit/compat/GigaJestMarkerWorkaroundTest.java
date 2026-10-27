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
}
