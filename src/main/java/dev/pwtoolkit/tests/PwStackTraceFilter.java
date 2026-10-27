package dev.pwtoolkit.tests;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes {@code /abs/path/file.ts:14:72} and {@code tests/ui/file.test.ts:13:10} in error stacks, code frames and
 * attachment lines clickable in the test console.
 */
public final class PwStackTraceFilter implements Filter {
    private static final Pattern LOCATION = Pattern.compile(
            "((?:/|[A-Za-z]:[\\\\/])?[\\w.@\\-\\\\/]+\\.(?:[cm]?[jt]sx?|png|jpe?g|zip|webm|md))(?::(\\d+)(?::(\\d+))?)?");
    private final Project project;
    private final File baseDir;

    public PwStackTraceFilter(@NotNull Project project, @NotNull File baseDir) {
        this.project = project;
        this.baseDir = baseDir;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        int lineStart = entireLength - line.length();
        List<ResultItem> items = new ArrayList<>();
        Matcher m = LOCATION.matcher(line);
        while (m.find()) {
            String path = m.group(1);
            File file = new File(path);
            if (!file.isAbsolute()) file = new File(baseDir, path);
            VirtualFile vf = file.isFile() ? LocalFileSystem.getInstance().findFileByIoFile(file) : null;
            if (vf == null) continue;
            int lineNumber = m.group(2) == null ? 0 : Math.max(Integer.parseInt(m.group(2)) - 1, 0);
            int column = m.group(3) == null ? 0 : Math.max(Integer.parseInt(m.group(3)) - 1, 0);
            items.add(new ResultItem(lineStart + m.start(), lineStart + m.end(), new OpenFileHyperlinkInfo(project, vf, lineNumber, column)));
        }
        return items.isEmpty() ? null : new Result(items);
    }
}
