package dev.pwtoolkit.debug;

public final class PwValueAccess {
    public static String strip(String text) {
        return PwValue.stripAnsi(text);
    }
}
