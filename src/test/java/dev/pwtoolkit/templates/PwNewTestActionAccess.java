package dev.pwtoolkit.templates;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/** Test access to the helpers of {@link PwNewTestAction}. */
public final class PwNewTestActionAccess {
    private PwNewTestActionAccess() {
    }

    public static String testSuffix(Project project) {
        return PwNewTestAction.testSuffix(project);
    }

    public static String imports(Project project, VirtualFile directory) {
        return PwNewTestAction.imports(project, directory);
    }

    public static String words(String name) {
        return PwNewTestAction.words(name);
    }

    public static String fileName(String input, String suffix) {
        return PwNewTestAction.fileName(input, suffix);
    }
}
