package dev.pwtoolkit.nav;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.ui.awt.RelativePoint;
import dev.pwtoolkit.settings.PwSettings;
import dev.pwtoolkit.util.JsFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Collection;
import java.util.List;

/**
 * Gutter icons like IntelliJ IDEA: a member overrides / implements a super member (up arrow), a class or interface has
 * subclasses / implementations, and their members are overridden (down arrow). Click navigates.
 */
public final class TsHierarchyLineMarkerProvider implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null; // everything needs the server: slow markers only
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements, @NotNull Collection<? super LineMarkerInfo<?>> result) {
        if (elements.isEmpty() || !PwSettings.getInstance().getState().navigationEnabled) return;
        PsiFile file = elements.get(0).getContainingFile();
        if (file == null || !JsFiles.isScript(file.getOriginalFile().getVirtualFile()) || !file.getOriginalFile().getVirtualFile().isInLocalFileSystem()
                || file.getOriginalFile().getVirtualFile().getPath().contains("/node_modules/")) return;
        java.util.Map<PsiElement, List<PsiElement>> implementationsByType = new java.util.HashMap<>();
        for (PsiElement leaf : elements) {
            if (leaf.getFirstChild() != null) continue;
            PsiElement declaration = declarationNamedBy(leaf);
            if (declaration == null) continue;
            if (TsHierarchy.isType(declaration)) {
                List<PsiElement> implementations = TsHierarchy.implementations(declaration.getProject(), declaration, 3000);
                implementationsByType.put(declaration, implementations);
                if (!implementations.isEmpty()) {
                    boolean iface = TsHierarchy.isInterface(declaration);
                    result.add(marker(leaf, iface ? AllIcons.Gutter.ImplementedMethod : AllIcons.Gutter.OverridenMethod,
                            (iface ? "Has implementations: " : "Has subclasses: ") + names(implementations), implementations,
                            iface ? "Choose Implementation" : "Choose Subclass"));
                }
                continue;
            }
            if (!TsHierarchy.isMember(declaration)) continue;
            List<TsHierarchy.Super> supers = TsHierarchy.supers(declaration.getProject(), declaration);
            if (!supers.isEmpty()) {
                boolean implementing = supers.get(0).fromInterface();
                PsiElement owner = TsHierarchy.containingType(supers.get(0).element());
                result.add(marker(leaf, implementing ? AllIcons.Gutter.ImplementingMethod : AllIcons.Gutter.OverridingMethod,
                        (implementing ? "Implements " : "Overrides ") + (owner instanceof PsiNamedElement named ? named.getName() + "." : "")
                                + ((PsiNamedElement) declaration).getName(),
                        supers.stream().map(TsHierarchy.Super::element).toList(), "Choose Super Declaration"));
            }
            // overridden: only when the type has subclasses / implementations at all
            PsiElement type = TsHierarchy.containingType(declaration);
            List<PsiElement> subtypes = type == null ? List.of()
                    : implementationsByType.computeIfAbsent(type, t -> TsHierarchy.implementations(t.getProject(), t, 3000));
            if (!subtypes.isEmpty()) {
                String name = ((PsiNamedElement) declaration).getName();
                List<PsiElement> overriding = subtypes.stream().map(sub -> name == null ? null : TsHierarchy.memberNamed(sub, name))
                        .filter(java.util.Objects::nonNull).toList();
                if (!overriding.isEmpty()) {
                    boolean iface = TsHierarchy.isInterface(type);
                    result.add(marker(leaf, iface ? AllIcons.Gutter.ImplementedMethod : AllIcons.Gutter.OverridenMethod,
                            (iface ? "Implemented in " : "Overridden in ") + names(overriding.stream().map(TsHierarchy::containingType)
                                    .filter(java.util.Objects::nonNull).toList()), overriding, iface ? "Choose Implementation" : "Choose Overriding Member"));
                }
            }
        }
    }

    /** The declaration whose name is this leaf. */
    private static @Nullable PsiElement declarationNamedBy(@NotNull PsiElement leaf) {
        int offset = leaf.getTextRange().getStartOffset();
        PsiElement element = leaf.getParent();
        for (int depth = 0; element != null && depth < 3; element = element.getParent(), depth++) {
            if (element instanceof PsiNamedElement && element.getTextOffset() == offset) return element;
        }
        return null;
    }

    private static @NotNull String names(@NotNull List<PsiElement> elements) {
        return String.join(", ", elements.stream().limit(5).map(e -> e instanceof PsiNamedElement named ? String.valueOf(named.getName()) : e.getText()).toList())
                + (elements.size() > 5 ? "..." : "");
    }

    private static @NotNull LineMarkerInfo<PsiElement> marker(@NotNull PsiElement leaf, @NotNull Icon icon, @NotNull String tooltip,
                                                             @NotNull List<PsiElement> targets, @NotNull String title) {
        GutterIconNavigationHandler<PsiElement> navigation = (event, element) -> {
            List<PsiElement> valid = targets.stream().filter(PsiElement::isValid).toList();
            if (valid.size() == 1) ((Navigatable) valid.get(0)).navigate(true);
            else if (!valid.isEmpty()) NavigationUtil.getPsiElementPopup(valid.toArray(PsiElement.EMPTY_ARRAY), title).show(new RelativePoint(event));
        };
        return new LineMarkerInfo<>(leaf, leaf.getTextRange(), icon, e -> tooltip, navigation, GutterIconRenderer.Alignment.RIGHT, () -> tooltip);
    }
}
