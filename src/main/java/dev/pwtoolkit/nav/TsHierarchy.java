package dev.pwtoolkit.nav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classes, interfaces and their members in GigaIDE's PSI, with the supertypes from "extends" / "implements" resolved by
 * the TypeScript server: Go to Super, the "overrides" / "implements" gutter icons. Must be called inside a read action.
 */
public final class TsHierarchy {
    private static final Pattern EXTENDS = Pattern.compile("\\bextends\\s+([A-Za-z_$][\\w$.]*)");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\s+([^{]+)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$.]*");
    private static final Key<Map<Integer, Supertypes>> CACHE = Key.create("pwtoolkit.supertypes");

    private record Supertypes(long stamp, List<PsiElement> types) {
    }

    /** A super member and whether it comes from an interface (implements) or a class (overrides). */
    public record Super(@NotNull PsiElement element, boolean fromInterface) {
    }

    private TsHierarchy() {
    }

    static boolean isType(@NotNull PsiElement element) {
        String kind = element.getClass().getSimpleName();
        return element instanceof PsiNamedElement && (kind.contains("Class") || kind.contains("Interface"));
    }

    static boolean isInterface(@NotNull PsiElement element) {
        return element.getClass().getSimpleName().contains("Interface");
    }

    static boolean isMember(@NotNull PsiElement element) {
        String kind = element.getClass().getSimpleName();
        return element instanceof PsiNamedElement && !isType(element) && containingType(element) != null
                && (kind.contains("Function") || kind.contains("Method") || kind.contains("Field") || kind.contains("Property") || kind.contains("Signature"));
    }

    static @Nullable PsiElement containingType(@NotNull PsiElement element) {
        for (PsiElement parent = element.getParent(); parent != null && !(parent instanceof PsiFile); parent = parent.getParent()) {
            if (isType(parent)) return parent;
            // a function inside a method is not a member
            if (parent.getClass().getSimpleName().contains("Function")) return null;
        }
        return null;
    }

    /** Members with the same name in the supertypes (nearest first), for a member; the supertypes for a class. */
    public static @NotNull List<Super> supers(@NotNull Project project, @NotNull PsiElement element) {
        List<Super> result = new ArrayList<>();
        if (isType(element)) {
            for (PsiElement type : supertypes(project, element)) result.add(new Super(type, isInterface(type)));
            return result;
        }
        if (!isMember(element)) return result;
        String name = ((PsiNamedElement) element).getName();
        PsiElement type = containingType(element);
        if (name == null || type == null) return result;
        collect(project, type, name, result, new LinkedHashSet<>());
        return result;
    }

    private static void collect(@NotNull Project project, @NotNull PsiElement type, @NotNull String name, @NotNull List<Super> result, @NotNull Set<PsiElement> seen) {
        for (PsiElement supertype : supertypes(project, type)) {
            if (!seen.add(supertype)) continue;
            PsiElement member = memberNamed(supertype, name);
            if (member != null) {
                result.add(new Super(member, isInterface(supertype)));
            } else {
                collect(project, supertype, name, result, seen);
            }
        }
    }

    static @Nullable PsiElement memberNamed(@NotNull PsiElement type, @NotNull String name) {
        for (PsiNamedElement candidate : PsiTreeUtil.findChildrenOfType(type, PsiNamedElement.class)) {
            if (name.equals(candidate.getName()) && containingType(candidate) == type && isMember(candidate)) return candidate;
        }
        // GigaIDE gives interface members (method and property signatures) no names: find the declaration in the text
        String text = type.getText();
        int body = text.indexOf('{');
        if (body < 0) return null;
        Matcher matcher = Pattern.compile("(?m)^\\s*(?:(?:readonly|public|protected|private|static|async|abstract)\\s+)*(" + Pattern.quote(name)
                + ")\\s*[?!]?\\s*[(<:]").matcher(text);
        while (matcher.find()) {
            if (matcher.start(1) < body) continue;
            PsiElement leaf = type.getContainingFile().findElementAt(type.getTextRange().getStartOffset() + matcher.start(1));
            if (leaf != null && containingType(leaf) == type) return leaf;
        }
        return null;
    }

    /** The classes and interfaces after "extends" and "implements", resolved by the server (cached per document version). */
    static @NotNull List<PsiElement> supertypes(@NotNull Project project, @NotNull PsiElement type) {
        PsiFile psiFile = type.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return List.of();
        int start = type.getTextRange().getStartOffset();
        Map<Integer, Supertypes> cache = document.getUserData(CACHE);
        if (cache != null) {
            Supertypes cached = cache.get(start);
            if (cached != null && cached.stamp() == document.getModificationStamp() && cached.types().stream().allMatch(PsiElement::isValid)) {
                return cached.types();
            }
        }
        String text = type.getText();
        int brace = text.indexOf('{');
        String header = brace < 0 ? text : text.substring(0, brace);
        List<Integer> nameOffsets = new ArrayList<>();
        Matcher extendsMatcher = EXTENDS.matcher(header);
        // interfaces may extend several: "interface A extends B, C"
        if (extendsMatcher.find()) {
            String list = header.substring(extendsMatcher.start(1));
            int stop = list.indexOf("implements");
            addNames(list.substring(0, stop < 0 ? list.length() : stop), start + extendsMatcher.start(1), nameOffsets);
        }
        Matcher implementsMatcher = IMPLEMENTS.matcher(header);
        if (implementsMatcher.find()) addNames(implementsMatcher.group(1), start + implementsMatcher.start(1), nameOffsets);
        List<PsiElement> types = new ArrayList<>();
        boolean complete = true;
        for (int offset : nameOffsets) {
            PsiNamedElement declaration = TsDeclarations.declarationAt(project, file, offset, 10_000);
            if (declaration == null) complete = false; // e.g. the server still loads the project: ask again next time
            else if (isType(declaration)) types.add(declaration);
        }
        if (!complete) return types;
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            document.putUserData(CACHE, cache);
        }
        cache.put(start, new Supertypes(document.getModificationStamp(), types));
        return types;
    }

    /** "Base<T>, Other" -> offsets of the last segment of each name ("ns.Base" -> "Base"), skipping type arguments. */
    private static void addNames(@NotNull String list, int listOffset, @NotNull List<Integer> offsets) {
        int depth = 0;
        int i = 0;
        while (i < list.length()) {
            char c = list.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && (Character.isJavaIdentifierStart(c))) {
                Matcher name = NAME.matcher(list).region(i, list.length());
                if (name.lookingAt()) {
                    String found = name.group();
                    int dot = found.lastIndexOf('.');
                    offsets.add(listOffset + i + dot + 1);
                    i += found.length();
                    continue;
                }
            }
            i++;
        }
    }

    /** Implementations (subclasses, implementing classes, overriding members) of a declaration, by the server. */
    public static @NotNull List<PsiElement> implementations(@NotNull Project project, @NotNull PsiElement declaration, long timeoutMs) {
        PsiFile psiFile = declaration.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getOriginalFile().getVirtualFile();
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (document == null || !file.isInLocalFileSystem()) return List.of();
        CompletableFuture<List<TsLanguageService.Location>> future =
                TsLanguageService.getInstance(project).implementation(file, document, declaration.getTextOffset());
        if (future == null) return List.of();
        List<TsLanguageService.Location> locations = await(future, timeoutMs);
        List<PsiElement> result = new ArrayList<>();
        if (locations == null) return result;
        for (TsLanguageService.Location location : locations) {
            PsiNamedElement named = TsDeclarations.namedElementAt(project, location);
            if (named != null && !named.equals(declaration) && !result.contains(named)) result.add(named);
        }
        return result;
    }

    private static <T> @Nullable T await(@NotNull CompletableFuture<T> future, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
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
