package dev.pwtoolkit.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates "Playwright Test" configurations from the editor gutter, the editor context menu
 * and the Project view (test file or folder).
 */
public final class PwConfigurationProducer extends LazyRunConfigurationProducer<PwRunConfiguration> {

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return PwRunConfigurationType.getInstance().getFactory();
    }

    private record Target(@NotNull VirtualFile configDir, @NotNull VirtualFile fileOrDir, int line, @Nullable String title) {
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull PwRunConfiguration config,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        Target target = findTarget(context.getPsiLocation());
        if (target == null) return false;
        config.setWorkingDirectory(FileUtil.toSystemDependentName(target.configDir.getPath()));
        config.setTestPath(FileUtil.toSystemDependentName(target.fileOrDir.getPath()));
        config.setTestLine(target.line);
        String relative = VfsUtilCore.getRelativePath(target.fileOrDir, target.configDir);
        String base = target.fileOrDir.equals(target.configDir) ? "All tests" : relative != null ? relative : target.fileOrDir.getName();
        config.setName(target.title != null ? target.title : base);
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull PwRunConfiguration config, @NotNull ConfigurationContext context) {
        Target target = findTarget(context.getPsiLocation());
        return target != null
                && FileUtil.pathsEqual(config.getTestPath(), FileUtil.toSystemDependentName(target.fileOrDir.getPath()))
                && config.getTestLine() == target.line;
    }

    @Override
    public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
        return true;
    }

    @Override
    public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
        return true;
    }

    private static @Nullable Target findTarget(@Nullable PsiElement element) {
        if (element == null) return null;
        if (element instanceof PsiDirectory directory) {
            VirtualFile dir = directory.getVirtualFile();
            VirtualFile configDir = PwTestDetector.findConfigDir(dir);
            return configDir == null ? null : new Target(configDir, dir, 0, null);
        }
        PsiFile psiFile = element.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();
        if (!PwTestDetector.isTestFile(file)) return null;
        VirtualFile configDir = PwTestDetector.findConfigDir(file);
        if (configDir == null) return null;

        if (!(element instanceof PsiFile)) {
            Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(psiFile);
            PwTestDetector.TestCall call = document == null ? null
                    : PwTestDetector.findEnclosingCall(document, element.getTextRange().getStartOffset());
            if (call != null) return new Target(configDir, file, call.line() + 1, call.title());
        }
        return new Target(configDir, file, 0, null);
    }
}
