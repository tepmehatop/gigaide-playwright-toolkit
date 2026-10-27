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

    /** GigaIDE's gutter icon that runs *.test.ts through tsx/node:test - wrong for Playwright tests. */
    static final String TSX_MARKER = "com.gigaide.nodejs.test.tsx.TypeScriptTestFileLineMarkerProvider";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PwSettings.StateBean settings = PwSettings.getInstance().getState();
        if (settings.disableGigaJestMarker) apply();
        if (settings.hideGigaTsxMarker) wrapForNonPlaywrightFiles(TSX_MARKER);
    }

    /**
     * Replaces the registrations of a GigaIDE run-line marker with a wrapper that stays silent in Playwright test
     * files (so only our Run/Debug icon is shown there) and delegates everywhere else. Returns how many were wrapped.
     */
    public static int wrapForNonPlaywrightFiles(@NotNull String implementationClass) {
        ExtensionPoint<LanguageExtensionPoint<RunLineMarkerContributor>> point = RUN_LINE_MARKERS.getPoint();
        List<LanguageExtensionPoint<RunLineMarkerContributor>> originals = point.getExtensionList().stream()
                .filter(bean -> implementationClass.equals(bean.implementationClass)).toList();
        if (originals.isEmpty()) return 0;
        List<LanguageExtensionPoint<RunLineMarkerContributor>> wrappers = new java.util.ArrayList<>();
        for (LanguageExtensionPoint<RunLineMarkerContributor> bean : originals) {
            LanguageExtensionPoint<RunLineMarkerContributor> wrapper =
                    new LanguageExtensionPoint<>(bean.language, new NotInPlaywrightTests(bean.getInstance()));
            // beans created in code must know their plugin, otherwise getInstance() fails
            wrapper.setPluginDescriptor(com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId("dev.pwtoolkit.gigaide")));
            wrappers.add(wrapper);
        }
        point.unregisterExtensions((className, adapter) -> !isMarker(adapter, implementationClass), false);
        wrappers.forEach(wrapper -> point.registerExtension(wrapper, ApplicationManager.getApplication()));
        LOG.info("[pwtoolkit] GigaIDE marker " + implementationClass + " hidden in Playwright test files");
        return wrappers.size();
    }

    static final class NotInPlaywrightTests extends RunLineMarkerContributor {
        private final RunLineMarkerContributor delegate;

        NotInPlaywrightTests(@NotNull RunLineMarkerContributor delegate) {
            this.delegate = delegate;
        }

        private static boolean inPlaywrightTest(@NotNull com.intellij.psi.PsiElement element) {
            com.intellij.psi.PsiFile file = element.getContainingFile();
            return file != null && dev.pwtoolkit.run.PwTestDetector.isTestFile(file.getVirtualFile());
        }

        @Override
        public @org.jetbrains.annotations.Nullable Info getInfo(@NotNull com.intellij.psi.PsiElement element) {
            return inPlaywrightTest(element) ? null : delegate.getInfo(element);
        }

        @Override
        public @org.jetbrains.annotations.Nullable Info getSlowInfo(@NotNull com.intellij.psi.PsiElement element) {
            return inPlaywrightTest(element) ? null : delegate.getSlowInfo(element);
        }
    }

    private static boolean isJestMarker(@NotNull ExtensionComponentAdapter adapter) {
        return isMarker(adapter, JEST_MARKER);
    }

    private static boolean isMarker(@NotNull ExtensionComponentAdapter adapter, @NotNull String implementationClass) {
        try {
            Object bean = adapter.createInstance(ApplicationManager.getApplication());
            return bean instanceof LanguageExtensionPoint<?> languageBean && implementationClass.equals(languageBean.implementationClass);
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
