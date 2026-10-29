package dev.pwtoolkit.nav;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Members of Playwright's API by name, read from its declaration files. For receivers TypeScript knows nothing about,
 * e.g. an untyped parameter {@code function select(page, locator) { page.click(locator) }}: like IntelliJ's
 * "weak" resolve, {@code click} then leads to {@code Page.click}, {@code Locator.click}...
 */
public final class PwApiIndex {
    private static final Logger LOG = Logger.getInstance(PwApiIndex.class);
    private static final String[] FILES = {"playwright-core/types/types.d.ts", "playwright/types/test.d.ts"};
    /** Most used first: the chooser lists Page before Locator before the rest. */
    private static final List<String> PRIORITY = List.of("Page", "Locator", "Frame", "BrowserContext", "FrameLocator", "ElementHandle",
            "APIRequestContext", "APIResponse", "Response", "Request", "Keyboard", "Mouse", "Browser",
            "PageAssertions", "LocatorAssertions", "APIResponseAssertions", "GenericAssertions", "TestType");
    private static final Pattern CONTAINER = Pattern.compile("^(?:export )?(?:declare )?(?:interface|class|type) ([A-Za-z_$][\\w$]*)");
    private static final Pattern MEMBER = Pattern.compile("^ {2}(?:readonly )?([A-Za-z_$][\\w$]*)\\??\\s*([(<:])");
    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** A member declaration: 0-based line and character in {@code file}. */
    public record Member(@NotNull String owner, @NotNull String name, boolean method, @NotNull Path file, int line, int character) {
    }

    private record Cached(long modified, List<Member> members) {
    }

    private PwApiIndex() {
    }

    /** Declarations of {@code name} (one per interface), most relevant first. */
    public static @NotNull List<Member> find(@NotNull Path from, @NotNull String name) {
        Map<String, Member> byOwner = new LinkedHashMap<>();
        for (Member member : members(from)) {
            if (member.name().equals(name)) byOwner.putIfAbsent(member.owner(), member);
        }
        List<Member> result = new ArrayList<>(byOwner.values());
        result.sort(Comparator.comparingInt(m -> rank(m.owner())));
        return result;
    }

    /** One member per name from the main Playwright interfaces, for completion after an untyped receiver. */
    public static @NotNull List<Member> completionMembers(@NotNull Path from) {
        Map<String, Member> byName = new LinkedHashMap<>();
        List<Member> members = new ArrayList<>(members(from));
        members.removeIf(m -> rank(m.owner()) >= PRIORITY.size());
        members.sort(Comparator.comparingInt(m -> rank(m.owner())));
        for (Member member : members) byName.putIfAbsent(member.name(), member);
        return new ArrayList<>(byName.values());
    }

    private static int rank(@NotNull String owner) {
        int index = PRIORITY.indexOf(owner);
        return index < 0 ? PRIORITY.size() : index;
    }

    private static @NotNull List<Member> members(@NotNull Path from) {
        List<Member> result = new ArrayList<>();
        for (String relative : FILES) {
            Path file = locate(from, relative);
            if (file != null) result.addAll(parse(file));
        }
        return result;
    }

    private static Path locate(@NotNull Path from, @NotNull String relative) {
        for (Path dir = Files.isDirectory(from) ? from : from.getParent(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("node_modules").resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    static @NotNull List<Member> parse(@NotNull Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Cached cached = CACHE.get(file);
            if (cached != null && cached.modified() == modified) return cached.members();
            List<Member> members = new ArrayList<>();
            String owner = null;
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher container = CONTAINER.matcher(line);
                if (container.find()) {
                    owner = container.group(1);
                    continue;
                }
                if (owner == null) continue;
                Matcher member = MEMBER.matcher(line);
                if (member.find()) {
                    members.add(new Member(owner, member.group(1), !member.group(2).equals(":"), file, i, member.start(1)));
                }
            }
            CACHE.put(file, new Cached(modified, members));
            return members;
        } catch (Exception e) {
            LOG.debug("Cannot read " + file, e);
            return List.of();
        }
    }
}
