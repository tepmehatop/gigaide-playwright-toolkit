package dev.pwtoolkit.nav;

import com.intellij.icons.AllIcons;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.navigation.ColoredItemPresentation;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Structure tool window and File Structure popup (Cmd+F12 / Ctrl+F12) for TS/JS from the TypeScript server:
 * classes with their fields and methods, functions, and Playwright tests ({@code test("...") callback}).
 */
public final class TsStructureViewFactory implements PsiStructureViewFactory {
    private static final Set<String> LOCAL_KINDS = Set.of("variable", "constant", "property", "other");

    @Override
    public @Nullable TreeBasedStructureViewBuilder getStructureViewBuilder(@NotNull PsiFile psiFile) {
        VirtualFile file = psiFile.getOriginalFile().getVirtualFile();
        if (!JsFiles.isScript(file) || !file.isInLocalFileSystem() || !PwSettings.getInstance().getState().navigationEnabled) return null;
        return new TreeBasedStructureViewBuilder() {
            @Override
            public @NotNull StructureViewModel createStructureViewModel(@Nullable Editor editor) {
                return new Model(psiFile, editor, file);
            }
        };
    }

    static final class Model extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider {
        private final Editor editor;

        Model(@NotNull PsiFile psiFile, @Nullable Editor editor, @NotNull VirtualFile file) {
            super(psiFile, editor, new Node(psiFile, file, null));
            this.editor = editor;
            withSorters(Sorter.ALPHA_SORTER);
        }

        /** The deepest declaration around the caret, so that "Autoscroll from Source" works. */
        @Override
        public @Nullable Object getCurrentEditorElement() {
            if (editor == null) return null;
            Node root = (Node) getRoot();
            int caret = editor.getCaretModel().getOffset();
            Node found = null;
            List<Node> level = root.nodes();
            while (true) {
                Node inside = null;
                for (Node node : level) {
                    if (node.contains(caret)) inside = node;
                }
                if (inside == null) return found;
                found = inside;
                level = inside.nodes();
            }
        }

        @Override
        public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
            return false;
        }

        @Override
        public boolean isAlwaysLeaf(StructureViewTreeElement element) {
            return element instanceof Node node && node.symbol != null && node.nodes().isEmpty();
        }
    }

    /** The file (symbol == null) or a declaration in it. */
    static final class Node implements StructureViewTreeElement, SortableTreeElement, ColoredItemPresentation {
        private final PsiFile psiFile;
        private final VirtualFile file;
        final TsLanguageService.Symbol symbol;
        private List<Node> children;

        Node(@NotNull PsiFile psiFile, @NotNull VirtualFile file, @Nullable TsLanguageService.Symbol symbol) {
            this.psiFile = psiFile;
            this.file = file;
            this.symbol = symbol;
        }

        synchronized @NotNull List<Node> nodes() {
            if (children == null) {
                List<TsLanguageService.Symbol> symbols = symbol == null ? fetch() : symbol.children();
                children = new ArrayList<>();
                boolean callable = symbol != null && (symbol.kind().equals("function") || symbol.kind().equals("method"));
                for (TsLanguageService.Symbol child : symbols) {
                    // locals of a function are noise in the structure; tests ("test(...) callback") are functions and stay
                    if (callable && LOCAL_KINDS.contains(child.kind())) continue;
                    children.add(new Node(psiFile, file, child));
                }
            }
            return children;
        }

        private @NotNull List<TsLanguageService.Symbol> fetch() {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) return List.of();
            CompletableFuture<List<TsLanguageService.Symbol>> future = ApplicationManager.getApplication().runReadAction(
                    (Computable<CompletableFuture<List<TsLanguageService.Symbol>>>) () ->
                            TsLanguageService.getInstance(psiFile.getProject()).symbols(file, document));
            if (future == null) return List.of();
            long deadline = System.currentTimeMillis() + 5000;
            while (true) {
                ProgressManager.checkCanceled();
                try {
                    return future.get(20, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    if (System.currentTimeMillis() > deadline) return List.of();
                } catch (Exception e) {
                    return List.of();
                }
            }
        }

        boolean contains(int offset) {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (symbol == null || document == null) return false;
            TsLanguageService.Range r = symbol.range();
            return TsEdits.offset(document, r.line(), r.character()) <= offset && offset <= TsEdits.offset(document, r.endLine(), r.endCharacter());
        }

        @Override
        public Object getValue() {
            return symbol == null ? psiFile : this;
        }

        @Override
        public @NotNull ItemPresentation getPresentation() {
            return this;
        }

        @Override
        public TreeElement @NotNull [] getChildren() {
            return nodes().toArray(new TreeElement[0]);
        }

        @Override
        public void navigate(boolean requestFocus) {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            int offset = 0;
            if (symbol != null && document != null) {
                offset = TsEdits.offset(document, symbol.selection().line(), symbol.selection().character());
            }
            new OpenFileDescriptor(psiFile.getProject(), file, offset).navigate(requestFocus);
        }

        @Override
        public boolean canNavigate() {
            return true;
        }

        @Override
        public boolean canNavigateToSource() {
            return true;
        }

        @Override
        public @NotNull String getAlphaSortKey() {
            return symbol == null ? file.getName() : symbol.name();
        }

        @Override
        public @Nullable String getPresentableText() {
            return symbol == null ? file.getName() : symbol.name();
        }

        @Override
        public @Nullable Icon getIcon(boolean unused) {
            if (symbol == null) return psiFile.getFileType().getIcon();
            return switch (symbol.kind()) {
                case "class" -> AllIcons.Nodes.Class;
                case "interface", "typeParameter" -> AllIcons.Nodes.Interface;
                case "enum" -> AllIcons.Nodes.Enum;
                case "method" -> AllIcons.Nodes.Method;
                case "function" -> AllIcons.Nodes.Function;
                case "property", "enumMember" -> AllIcons.Nodes.Property;
                case "variable" -> AllIcons.Nodes.Variable;
                case "constant" -> AllIcons.Nodes.Constant;
                case "module" -> AllIcons.Nodes.Package;
                default -> AllIcons.Nodes.EmptyNode;
            };
        }

        @Override
        public @Nullable com.intellij.openapi.editor.colors.TextAttributesKey getTextAttributesKey() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Node other && Objects.equals(file, other.file) && Objects.equals(key(), other.key());
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, key());
        }

        private @Nullable String key() {
            return symbol == null ? null : symbol.name() + "/" + symbol.kind() + "/" + symbol.selection();
        }
    }
}
