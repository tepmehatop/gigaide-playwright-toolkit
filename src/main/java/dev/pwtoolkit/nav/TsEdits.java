package dev.pwtoolkit.nav;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies text edits from the TypeScript server (imports, quick fixes) as one undoable command. */
public final class TsEdits {
    private TsEdits() {
    }

    public static void apply(@NotNull Project project, @NotNull String name, @NotNull List<TsLanguageService.Edit> edits) {
        if (edits.isEmpty()) return;
        WriteCommandAction.runWriteCommandAction(project, name, null, () -> applyInWriteAction(edits));
    }

    /** Must be called inside a write action (e.g. from an insert handler). */
    public static void applyInWriteAction(@NotNull List<TsLanguageService.Edit> edits) {
        Map<Path, List<TsLanguageService.Edit>> byFile = new LinkedHashMap<>();
        for (TsLanguageService.Edit edit : edits) byFile.computeIfAbsent(edit.file(), f -> new ArrayList<>()).add(edit);
        for (Map.Entry<Path, List<TsLanguageService.Edit>> entry : byFile.entrySet()) {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.getKey());
            Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
            if (document == null) continue;
            // from the end, so that earlier offsets stay valid
            List<int[]> ranges = new ArrayList<>();
            for (TsLanguageService.Edit edit : entry.getValue()) {
                ranges.add(new int[]{offset(document, edit.range().line(), edit.range().character()),
                        offset(document, edit.range().endLine(), edit.range().endCharacter()), ranges.size()});
            }
            ranges.sort(Comparator.<int[]>comparingInt(r -> r[0]).thenComparingInt(r -> r[2]).reversed());
            for (int[] range : ranges) document.replaceString(range[0], range[1], entry.getValue().get(range[2]).newText());
            for (Project project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }
        }
    }

    static int offset(@NotNull Document document, int line, int character) {
        if (line >= document.getLineCount()) return document.getTextLength();
        return Math.min(document.getLineStartOffset(line) + character, document.getLineEndOffset(line));
    }
}
