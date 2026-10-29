package dev.pwtoolkit.nav;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase;
import com.intellij.ide.hierarchy.HierarchyBrowser;
import com.intellij.ide.hierarchy.HierarchyBrowserManager;
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor;
import com.intellij.ide.hierarchy.HierarchyProvider;
import com.intellij.ide.hierarchy.HierarchyTreeStructure;
import com.intellij.ide.util.treeView.AlphaComparator;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.ide.util.treeView.SourceComparator;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.util.CompositeAppearance;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTree;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Call Hierarchy (Ctrl+Alt+H) for TS/JS functions and methods: callers and callees from the TypeScript server. */
public final class TsCallHierarchyProvider implements HierarchyProvider {
    @Override
    public @Nullable PsiElement getTarget(@NotNull DataContext dataContext) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (editor == null || file == null || !PwSettings.getInstance().getState().navigationEnabled) return null;
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(virtualFile) || !virtualFile.isInLocalFileSystem()) return null;
        PsiElement target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted());
        if (target != null) return target;
        // the caret inside a function: that function
        PsiElement leaf = file.findElementAt(editor.getCaretModel().getOffset());
        return TsGotoSuperHandler.owner(leaf);
    }

    @Override
    public @NotNull HierarchyBrowser createHierarchyBrowser(@NotNull PsiElement target) {
        return new Browser(target.getProject(), target);
    }

    @Override
    public void browserActivated(@NotNull HierarchyBrowser hierarchyBrowser) {
        ((Browser) hierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType());
    }

    static final class Browser extends CallHierarchyBrowserBase {
        Browser(@NotNull Project project, @NotNull PsiElement method) {
            super(project, method);
        }

        @Override
        protected @Nullable PsiElement getElementFromDescriptor(@NotNull HierarchyNodeDescriptor descriptor) {
            return descriptor.getPsiElement();
        }

        @Override
        protected void createTrees(@NotNull Map<? super String, ? super JTree> type2TreeMap) {
            ActionGroup group = (ActionGroup) ActionManager.getInstance().getAction(IdeActions.GROUP_CALL_HIERARCHY_POPUP);
            type2TreeMap.put(getCallerType(), tree(group));
            type2TreeMap.put(getCalleeType(), tree(group));
        }

        private @NotNull JTree tree(@NotNull ActionGroup group) {
            JTree tree = createTree(false);
            PopupHandler.installPopupMenu(tree, group, ActionPlaces.CALL_HIERARCHY_VIEW_POPUP);
            return tree;
        }

        @Override
        protected boolean isApplicableElement(@NotNull PsiElement element) {
            return true;
        }

        @Override
        protected @Nullable HierarchyTreeStructure createHierarchyTreeStructure(@NotNull String type, @NotNull PsiElement element) {
            TsLanguageService.CallItem item = prepare(myProject, element);
            if (item == null) return null;
            return new Structure(myProject, new Node(myProject, null, element, true, item), getCallerType().equals(type));
        }

        @Override
        protected @NotNull Comparator<NodeDescriptor<?>> getComparator() {
            return HierarchyBrowserManager.getInstance(myProject).getState().SORT_ALPHABETICALLY ? AlphaComparator.getInstance() : SourceComparator.INSTANCE;
        }
    }

    /** Children: incoming calls (callers) or outgoing calls (callees); a function already on the path is not expanded again. */
    static final class Structure extends HierarchyTreeStructure {
        private final boolean callers;

        Structure(@NotNull Project project, @NotNull Node base, boolean callers) {
            super(project, base);
            this.callers = callers;
        }

        @Override
        protected Object @NotNull [] buildChildren(@NotNull HierarchyNodeDescriptor descriptor) {
            if (!(descriptor instanceof Node node)) return ArrayUtilEmpty.OBJECTS;
            for (NodeDescriptor<?> parent = node.getParentDescriptor(); parent != null; parent = parent.getParentDescriptor()) {
                if (parent instanceof Node ancestor && ancestor.item.key().equals(node.item.key())) return ArrayUtilEmpty.OBJECTS;
            }
            List<TsLanguageService.CallItem> items = calls(myProject, node.item, callers);
            List<Node> children = new ArrayList<>();
            for (TsLanguageService.CallItem item : items) {
                PsiElement element = ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> element(myProject, item));
                if (element != null) children.add(new Node(myProject, node, element, false, item));
            }
            return children.toArray();
        }
    }

    private static final class ArrayUtilEmpty {
        static final Object[] OBJECTS = new Object[0];
    }

    static final class Node extends HierarchyNodeDescriptor {
        final TsLanguageService.CallItem item;
        private final boolean base;

        Node(@NotNull Project project, @Nullable NodeDescriptor<?> parent, @NotNull PsiElement element, boolean base, @NotNull TsLanguageService.CallItem item) {
            super(project, parent, element, base);
            this.item = item;
            this.base = base;
        }

        @Override
        public boolean update() {
            boolean changes = super.update();
            CompositeAppearance old = myHighlightedText;
            myHighlightedText = new CompositeAppearance();
            String name = item.kind().equals("module") ? java.nio.file.Path.of(item.name()).getFileName().toString() : item.name();
            myHighlightedText.getEnding().addText(name, base ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            String where = item.file().getFileName() + ":" + (item.selection().line() + 1);
            String detail = item.detail().isEmpty() || item.detail().equals(name) ? where : item.detail() + " (" + where + ")";
            myHighlightedText.getEnding().addText("  " + detail, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            myName = myHighlightedText.getText();
            return changes || !Comparing.equal(myHighlightedText, old);
        }
    }

    /** The server's item for a declaration. */
    static @Nullable TsLanguageService.CallItem prepare(@NotNull Project project, @NotNull PsiElement element) {
        CompletableFuture<List<TsLanguageService.CallItem>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.CallItem>>>) () -> {
                    PsiFile psiFile = element.getContainingFile();
                    VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
                    Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                    return document == null ? null : TsLanguageService.getInstance(project).prepareCallHierarchy(file, document, element.getTextOffset());
                });
        List<TsLanguageService.CallItem> items = future == null ? null : await(future);
        return items == null || items.isEmpty() ? null : items.get(0);
    }

    static @NotNull List<TsLanguageService.CallItem> calls(@NotNull Project project, @NotNull TsLanguageService.CallItem item, boolean incoming) {
        CompletableFuture<List<TsLanguageService.CallItem>> future = ApplicationManager.getApplication().runReadAction(
                (Computable<CompletableFuture<List<TsLanguageService.CallItem>>>) () -> {
                    VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(item.file());
                    Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                    return document == null ? null : TsLanguageService.getInstance(project).calls(file, document, item, incoming);
                });
        List<TsLanguageService.CallItem> items = future == null ? null : await(future);
        return items == null ? List.of() : items;
    }

    /** The declaration of an item (its name), or the file for a module-level caller. */
    static @Nullable PsiElement element(@NotNull Project project, @NotNull TsLanguageService.CallItem item) {
        PsiElement named = TsDeclarations.namedElementAt(project, new TsLanguageService.Location(item.file(), item.selection().line(), item.selection().character()));
        if (named != null) return named;
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(item.file());
        PsiFile psiFile = file == null ? null : PsiManager.getInstance(project).findFile(file);
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (psiFile == null || document == null) return null;
        PsiElement leaf = psiFile.findElementAt(TsEdits.offset(document, item.selection().line(), item.selection().character()));
        return leaf != null ? leaf : psiFile;
    }

    private static <T> @Nullable T await(@NotNull CompletableFuture<T> future) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            ProgressManager.checkCanceled();
            try {
                return future.get(10, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.currentTimeMillis() > deadline) return null;
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
