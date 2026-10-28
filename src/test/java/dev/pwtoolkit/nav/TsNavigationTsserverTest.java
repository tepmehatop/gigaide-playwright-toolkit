package dev.pwtoolkit.nav;

/** Same checks with TypeScript 5.8.2 lib/tsserver.js run by Node.js (samples/typescript5). */
public class TsNavigationTsserverTest extends TsNavigationTest {
    @Override
    protected String backend() {
        return "tsserver";
    }
}
