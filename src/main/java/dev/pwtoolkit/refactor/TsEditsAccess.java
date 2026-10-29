package dev.pwtoolkit.refactor;

import com.google.gson.JsonArray;
import dev.pwtoolkit.nav.TsLanguageService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** tsserver FileCodeEdits[] -> edits. */
final class TsEditsAccess {
    private TsEditsAccess() {
    }

    static @NotNull List<TsLanguageService.Edit> changes(@Nullable JsonArray changes) {
        return dev.pwtoolkit.nav.TsServerProtocol.changes(changes);
    }
}
