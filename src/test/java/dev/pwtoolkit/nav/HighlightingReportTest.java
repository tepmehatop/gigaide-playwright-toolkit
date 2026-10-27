package dev.pwtoolkit.nav;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

/**
 * Diagnostics: highlights a real test file under the real Islands Dark / Dark schemes (from the IDE jars)
 * and prints, for every range, the key and the resolved color. Run with -Dpwtoolkit.report.file=<path>.
 */
public class HighlightingReportTest extends BasePlatformTestCase {
    @Override
    protected void tearDown() throws Exception {
        try {
            TsLanguageService.getInstance(getProject()).restartAll();
        } finally {
            super.tearDown();
        }
    }

    public void testReport() throws Exception {
        String path = System.getProperty("pwtoolkit.report.file");
        if (path == null) return;
        ClassLoader giga = PluginManagerCore.getPlugin(PluginId.getId("com.gigaide.javascript")).getPluginClassLoader();
        Class.forName("com.gigaide.javascript.fileTypes.TsHighlighterColors", true, giga);
        Class.forName("com.gigaide.javascript.fileTypes.JsHighlighterColors", true, giga);

        EditorColorsManager manager = EditorColorsManager.getInstance();
        EditorColorsScheme original = manager.getGlobalScheme();
        try {
            for (String resource : new String[]{"themes/islands/IslandSchemeDark.xml", "themes/expUI/expUI_darkScheme.xml"}) {
                EditorColorsScheme scheme = load(resource, manager.getScheme("Darcula"));
                manager.setGlobalScheme(scheme);
                report(path, scheme);
            }
            // with "Semantic highlighting" (Settings | Editor | Color Scheme | Language Defaults) turned on
            EditorColorsScheme islands = load("themes/islands/IslandSchemeDark.xml", manager.getScheme("Darcula"));
            com.intellij.codeHighlighting.RainbowHighlighter.setRainbowEnabled((com.intellij.openapi.options.SchemeMetaInfo) islands,
                    com.intellij.lang.Language.findLanguageByID("TypeScript"), Boolean.TRUE);
            manager.setGlobalScheme(islands);
            report(path, islands);
        } finally {
            manager.setGlobalScheme(original);
        }
    }

    private void report(String path, EditorColorsScheme scheme) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
        assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file);
        TsSemanticHighlighter.getInstance(getProject()).highlight(file);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        Document document = myFixture.getEditor().getDocument();
        List<RangeHighlighter> highlighters = TsSemanticHighlighter.appliedHighlighters(document);
        System.out.println("REPORT ===== " + scheme.getName() + " : " + (highlighters == null ? 0 : highlighters.size()) + " ranges");
        if (highlighters == null) return;
        String text = document.getText();
        highlighters.stream().sorted(Comparator.comparingInt(RangeHighlighter::getStartOffset)).forEach(h -> {
            int line = document.getLineNumber(h.getStartOffset()) + 1;
            if (line > 12) return;
            TextAttributes a = scheme.getAttributes(h.getTextAttributesKey());
            System.out.println("REPORT " + line + " " + text.substring(h.getStartOffset(), h.getEndOffset()) + " -> "
                    + h.getTextAttributesKey().getExternalName() + " fg=" + (a == null ? null : a.getForegroundColor()) + " font=" + (a == null ? null : a.getFontType()));
        });
    }

    private static EditorColorsScheme load(String resource, EditorColorsScheme parent) throws Exception {
        try (InputStream in = EditorColorsManager.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, in);
            EditorColorsSchemeImpl scheme = new EditorColorsSchemeImpl(parent);
            scheme.readExternal(JDOMUtil.load(in));
            return scheme;
        }
    }
}
