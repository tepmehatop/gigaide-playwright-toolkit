package dev.pwtoolkit.nav;

/** Same checks with bundled typescript-language-server + TypeScript 5.8.2 (samples/typescript5). */
public class TsSemanticHighlightingTslsTest extends TsSemanticHighlightingTest {
    @Override
    protected String backend() {
        return "tsls";
    }
}
