package dev.pwtoolkit.nav;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.UsageViewManager;
import com.intellij.usages.UsageViewPresentation;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Find Usages (Alt+F7) in TS/JS files, answered by the TypeScript server: GigaIDE cannot resolve most references,
 * so its own Find Usages finds nothing. The results go to the usual Find tool window.
 */
public final class TsFindUsagesAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(isApplicable(e.getDataContext()));
    }

    static boolean isApplicable(@NotNull DataContext context) {
        Editor editor = CommonDataKeys.EDITOR.getData(context);
        VirtualFile file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        return editor != null && JsFiles.isScript(file) && PwSettings.getInstance().getState().navigationEnabled;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        int offset = editor.getCaretModel().getOffset();
        String name = wordAt(document.getImmutableCharSequence(), offset);
        if (name.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor, "Put the caret on a name to find its usages");
            return;
        }
        List<TsLanguageService.Reference> references = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            CompletableFuture<List<TsLanguageService.Reference>> future = ApplicationManager.getApplication().runReadAction(
                    (Computable<CompletableFuture<List<TsLanguageService.Reference>>>) () ->
                            TsLanguageService.getInstance(project).references(file, document, offset));
            if (future == null) return null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (true) {
                ProgressManager.checkCanceled();
                try {
                    return future.get(50, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    if (System.currentTimeMillis() > deadline) return null;
                } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) {
                    throw canceled;
                } catch (Exception failed) {
                    return null;
                }
            }
        }, "Finding usages of '" + name + "'", true, project);
        if (references == null) {
            HintManager.getInstance().showErrorHint(editor, "The TypeScript server did not answer (Tools | Playwright | Diagnose Highlighting)");
            return;
        }
        List<Usage> usages = usages(project, references);
        if (usages.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "No usages of '" + name + "' found");
            return;
        }
        UsageViewPresentation presentation = new UsageViewPresentation();
        presentation.setTabText("Usages of '" + name + "'");
        presentation.setTargetsNodeText("TypeScript");
        presentation.setCodeUsagesString("Usages of '" + name + "' (" + usages.size() + ")");
        presentation.setSearchString(name);
        presentation.setShowCancelButton(false);
        UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages.toArray(Usage.EMPTY_ARRAY), presentation);
    }

    static @NotNull List<Usage> usages(@NotNull Project project, @NotNull List<TsLanguageService.Reference> references) {
        List<Usage> usages = new ArrayList<>();
        for (TsLanguageService.Reference reference : references) {
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(reference.file());
            Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
            PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
            if (document == null || psiFile == null) continue;
            int start = TsEdits.offset(document, reference.range().line(), reference.range().character());
            int end = TsEdits.offset(document, reference.range().endLine(), reference.range().endCharacter());
            usages.add(new UsageInfo2UsageAdapter(new UsageInfo(psiFile, start, Math.max(end, start + 1))));
        }
        return usages;
    }

    static @NotNull String wordAt(@NotNull CharSequence text, int offset) {
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return text.subSequence(start, end).toString();
    }

    /** Alt+F7 is also GigaIDE's Find Usages: in TS/JS files ours goes first. */
    public static final class Promoter implements ActionPromoter {
        @Override
        public List<AnAction> promote(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
            for (AnAction action : actions) {
                if (action instanceof TsFindUsagesAction && isApplicable(context)) return List.of(action);
            }
            return null;
        }
    }
}
