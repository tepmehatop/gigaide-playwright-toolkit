package dev.pwtoolkit.nav;

import com.google.gson.JsonArray;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Parsing helpers of the tsserver protocol shared outside of this package. */
public final class TsServerProtocol {
    private TsServerProtocol() {
    }

    /** FileCodeEdits[]: [{fileName, textChanges: [{start, end, newText}]}], 1-based lines and offsets. */
    public static @NotNull List<TsLanguageService.Edit> changes(@Nullable JsonArray changes) {
        return TsserverBackend.changes(changes);
    }
}
