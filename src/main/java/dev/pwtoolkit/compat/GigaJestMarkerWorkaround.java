package dev.pwtoolkit.compat;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.lang.LanguageExtensionPoint;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.impl.ExtensionComponentAdapter;
import com.intellij.openapi.application.ApplicationManager;
import dev.pwtoolkit.settings.PwSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Workaround for a GigaIDE bug: the Jest gutter marker of its NodeJS plugin fails with a gson LinkageError
 * on every *.test.ts / *.spec.ts, so analysis of test files never finishes ("Analyzing…" forever, no errors or
 * warnings shown). Playwright projects do not need Jest markers, so that one contributor is unregistered.
 * Everything else in GigaIDE's NodeJS plugin stays intact. Disable in Settings | Tools | Playwright Toolkit.
 */
public final class GigaJestMarkerWorkaround implements AppLifecycleListener {
    private static final Logger LOG = Logger.getInstance(GigaJestMarkerWorkaround.class);
    static final String JEST_MARKER = "com.gigaide.nodejs.test.jest.JestTestFileLineMarkerProvider";
    private static final ExtensionPointName<LanguageExtensionPoint<RunLineMarkerContributor>> RUN_LINE_MARKERS =
            ExtensionPointName.create("com.intellij.runLineMarkerContributor");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        if (PwSettings.getInstance().getState().disableGigaJestMarker) apply();
    }

    private static boolean isJestMarker(@NotNull ExtensionComponentAdapter adapter) {
        try {
            Object bean = adapter.createInstance(ApplicationManager.getApplication());
            return bean instanceof LanguageExtensionPoint<?> languageBean && JEST_MARKER.equals(languageBean.implementationClass);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Returns how many registrations were removed. */
    public static int apply() {
        ExtensionPoint<LanguageExtensionPoint<RunLineMarkerContributor>> point = RUN_LINE_MARKERS.getPoint();
        int before = point.getExtensionList().size();
        // the filter answers "keep this registration?"
        point.unregisterExtensions((className, adapter) -> !isJestMarker(adapter), false);
        int removed = before - point.getExtensionList().size();
        if (removed > 0) LOG.info("[pwtoolkit] disabled GigaIDE Jest line marker (" + removed + " registration(s)): it breaks analysis of test files");
        return removed;
    }
}
