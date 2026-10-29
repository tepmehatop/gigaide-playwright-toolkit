package dev.pwtoolkit.templates;

import com.intellij.icons.AllIcons;
import com.intellij.ide.IdeView;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * New | Playwright Test: a test file in the style of the project - the ".test.ts" or ".spec.ts" suffix used there,
 * {@code test} and {@code expect} imported from the project's fixtures file (baseTest.ts...) when there is one.
 * The content is the "Playwright Test" file template (Settings | Editor | File and Code Templates | Other).
 */
public final class PwNewTestAction extends AnAction implements DumbAware {
    static final String TEMPLATE = "Playwright Test";
    private static final List<String> FIXTURE_FILES = List.of("baseTest.ts", "fixtures.ts", "fixture.ts", "base.ts", "testBase.ts", "test-base.ts");

    public PwNewTestAction() {
        super("Playwright Test", "Create a Playwright test file", AllIcons.RunConfigurations.TestState.Run);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        IdeView view = e.getData(LangDataKeys.IDE_VIEW);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && view != null && view.getDirectories().length > 0);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        IdeView view = e.getData(LangDataKeys.IDE_VIEW);
        PsiDirectory directory = view == null ? null : view.getOrChooseDirectory();
        if (project == null || directory == null) return;
        String suffix = testSuffix(project, directory.getVirtualFile());
        String name = Messages.showInputDialog(project, "File name (" + suffix + " is added unless you type another suffix):", "New Playwright Test",
                null, "", new InputValidator() {
                    @Override
                    public boolean checkInput(String input) {
                        return !input.isBlank() && directory.findFile(fileName(input, suffix)) == null;
                    }

                    @Override
                    public boolean canClose(String input) {
                        return checkInput(input);
                    }
                });
        if (name == null) return;
        Properties properties = FileTemplateManager.getInstance(project).getDefaultProperties();
        properties.setProperty("IMPORTS", imports(project, directory.getVirtualFile()));
        properties.setProperty("DESCRIBE", words(name.trim()));
        try {
            FileTemplate template = FileTemplateManager.getInstance(project).getInternalTemplate(TEMPLATE);
            PsiElement created = FileTemplateUtil.createFromTemplate(template, fileName(name, suffix), properties, directory);
            PsiFile file = created.getContainingFile();
            if (file == null || file.getVirtualFile() == null) return;
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file.getVirtualFile()), true);
            if (editor != null) moveIntoTestBody(editor);
            view.selectElement(file);
        } catch (Exception ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "New Playwright Test");
        }
    }

    /** The typed name with the project's suffix; a suffix typed by the user ("cart.test.ts", "cart.spec.js") is kept. */
    static @NotNull String fileName(@NotNull String input, @NotNull String suffix) {
        String name = input.trim();
        for (String typed : List.of(".spec.ts", ".test.ts", ".spec.tsx", ".test.tsx", ".spec.js", ".test.js", ".spec.mjs", ".test.mjs")) {
            if (name.endsWith(typed) && name.length() > typed.length()) return name;
        }
        for (String plain : List.of(".ts", ".js")) {
            if (name.endsWith(plain)) name = name.substring(0, name.length() - plain.length());
        }
        return name + suffix;
    }

    private static final java.util.regex.Pattern TEST_MATCH = java.util.regex.Pattern.compile("testMatch\\s*:\\s*([^\\n]*)");

    /**
     * The suffix of the project's tests: from testMatch of playwright.config (e.g. /.*\\.test\\.ts$/), otherwise the
     * suffix most test files of the project have (node_modules excluded), otherwise Playwright's default ".spec.ts".
     */
    static @NotNull String testSuffix(@NotNull Project project, @Nullable VirtualFile directory) {
        for (VirtualFile dir = directory; dir != null; dir = dir.getParent()) {
            for (String config : List.of("playwright.config.ts", "playwright.config.js", "playwright.config.mjs", "playwright.config.cjs")) {
                VirtualFile file = dir.findChild(config);
                if (file == null) continue;
                try {
                    java.util.regex.Matcher matcher = TEST_MATCH.matcher(new String(file.contentsToByteArray(), StandardCharsets.UTF_8));
                    if (matcher.find()) {
                        String match = matcher.group(1);
                        String ext = match.contains("js") && !match.contains("ts") ? ".js" : ".ts";
                        if (match.contains("test")) return ".test" + ext;
                        if (match.contains("spec")) return ".spec" + ext;
                    }
                } catch (Exception ignored) {
                }
                dir = null;
                break;
            }
            if (dir == null) break;
        }
        int[] counts = new int[2];
        com.intellij.openapi.roots.ProjectFileIndex index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
        index.iterateContent(file -> {
            String name = file.getName();
            if (name.endsWith(".test.ts")) counts[0]++;
            else if (name.endsWith(".spec.ts")) counts[1]++;
            return true;
        }, file -> !file.isDirectory() || !file.getName().equals("node_modules"));
        return counts[0] > counts[1] ? ".test.ts" : ".spec.ts";
    }

    /** test and expect from the project's fixtures file (default or named export), else from @playwright/test. */
    static @NotNull String imports(@NotNull Project project, @NotNull VirtualFile directory) {
        for (String fixtureName : FIXTURE_FILES) {
            Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(fixtureName, GlobalSearchScope.projectScope(project));
            for (VirtualFile fixture : files) {
                if (fixture.getPath().contains("/node_modules/")) continue;
                String text;
                try {
                    text = new String(fixture.contentsToByteArray(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    continue;
                }
                if (!text.contains(".extend")) continue;
                String path = relative(directory, fixture);
                boolean defaultTest = text.matches("(?s).*export\\s+default\\s+test\\b.*");
                boolean namedTest = text.matches("(?s).*export\\s+(const|let)\\s+test\\b.*") || text.matches("(?s).*export\\s*\\{[^}]*\\btest\\b[^}]*}.*");
                boolean expect = text.matches("(?s).*export\\s+(const|let)\\s+expect\\b.*") || text.matches("(?s).*export\\s*\\{[^}]*\\bexpect\\b[^}]*}.*");
                if (defaultTest) {
                    return expect ? "import test, { expect } from \"" + path + "\";"
                            : "import test from \"" + path + "\";\nimport { expect } from \"@playwright/test\";";
                }
                if (namedTest) {
                    return expect ? "import { test, expect } from \"" + path + "\";"
                            : "import { test } from \"" + path + "\";\nimport { expect } from \"@playwright/test\";";
                }
            }
        }
        return "import { test, expect } from \"@playwright/test\";";
    }

    private static @NotNull String relative(@NotNull VirtualFile directory, @NotNull VirtualFile file) {
        String path = FileUtil.getRelativePath(directory.getPath(), file.getPath(), '/');
        if (path == null) path = VfsUtilCore.getRelativePath(file, directory, '/');
        if (path == null) path = file.getPath();
        path = path.replaceFirst("\\.ts$", "");
        return path.startsWith(".") ? path : "./" + path;
    }

    /** "checkoutFlow" / "checkout-flow" -> "Checkout flow". */
    static @NotNull String words(@NotNull String name) {
        String base = fileName(name, "").replace('_', ' ').replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim().toLowerCase();
        return base.isEmpty() ? "New test" : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private static void moveIntoTestBody(@NotNull Editor editor) {
        Document document = editor.getDocument();
        int body = document.getText().indexOf("async ({ page }) => {");
        if (body < 0) return;
        int line = document.getLineNumber(body) + 1;
        if (line >= document.getLineCount()) return;
        int offset = document.getLineEndOffset(line);
        editor.getCaretModel().moveToOffset(offset);
        if (document.getText(new com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), offset)).isBlank()) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                document.replaceString(document.getLineStartOffset(line), offset, "        ");
                editor.getCaretModel().moveToOffset(document.getLineStartOffset(line) + 8);
            });
        }
    }
}
