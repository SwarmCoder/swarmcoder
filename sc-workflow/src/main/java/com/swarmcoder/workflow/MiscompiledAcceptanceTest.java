/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.workflow;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.verify.AcceptanceCompileErrors;
import com.swarmcoder.verify.AcceptanceCompileErrors.CompileError;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An acceptance test that does not compile for a reason no task in the plan can fix — the
 * workflow's half of {@link AcceptanceCompileErrors}, which cannot see the checkout.
 *
 * <p><b>Why (brownfield harness run 43, 2026-09-26).</b> The red check confirmed as healthy a test
 * whose line 16 passed an {@code org.jsoup.parser.Parser} where jsoup's {@code Document}
 * constructor takes a {@code String}. Every compile failure of the acceptance stage used to read
 * as "references not-yet-implemented symbols"; on an existing codebase the common failure is the
 * opposite — the test names code that is already there, and uses it wrongly. Two workers, a repair
 * round of four and a BLOCKED task later, the run parked on a line no worker was allowed to touch.
 *
 * <p>This class supplies the three things the pure classifier needs from outside: what the plan
 * promises to change about types that already exist (from {@link ContractDelivery}, over the
 * pre-change tree), the offending source line, and — for the test author's correction — the real
 * signatures of the existing members the test misused, read off the checkout by
 * {@link ProjectTypes#exposedMembers}. The shape of what happens next is the one already used for
 * an undeliverable type ({@link BrokenAcceptanceTest}) and for a test that reaches browser-only
 * code ({@link AcceptanceTestReach}): at TEST_AUTHORING the author is asked once with the
 * compiler's own lines, then the run parks; at wave time the run parks before dispatch.
 */
final class MiscompiledAcceptanceTest {

    private MiscompiledAcceptanceTest() {}

    /**
     * Enough signature lines to show a library type whole together with the builder it hands out
     * (live run 63: a test server and its builder are about thirty), few enough to read.
     */
    private static final int MAX_SIGNATURE_LINES = 80;

    /**
     * Classifies a red state's compile output against {@code testPaths}, with the plan's promised
     * changes to existing types read from {@code tree}.
     *
     * @param tree          the tree the compile ran in (the red-check worktree): the pre-change
     *                      code plus the acceptance tests
     * @param compileOutput the acceptance stage's output; null or blank reads as nothing broken
     * @param testPaths     the task's acceptance test files, repo-relative
     * @param stillToRun    every task that has not run yet
     */
    static Reading read(Path tree, String compileOutput, Collection<String> testPaths,
                        List<Task> stillToRun) {
        if (compileOutput == null || compileOutput.isBlank()) {
            return new Reading(List.of(), List.of());
        }
        return AcceptanceCompileErrors.classify(compileOutput, testPaths, stillToRun,
            plannedChanges(tree, stillToRun), sourceLines(tree, testPaths), sourceInTree(tree),
            packageInModule(tree));
    }

    /**
     * {@code (moduleDir, package)}: whether that module's own sources hold the package, or a package
     * it is nested in of at least two segments. A test importing a package of the project's own
     * code that nobody writes has mistyped it; a package nothing in its own module is near is a
     * dependency it does not have yet (live run 64). Fails open towards "not the module's own":
     * null (not known) when the tree cannot be read, which leaves the rescue to the write sets.
     */
    static java.util.function.BiPredicate<String, String> packageInModule(Path tree) {
        if (tree == null || !Files.isDirectory(tree)) {
            return null;
        }
        return (moduleDir, pkg) -> {
            if (pkg == null || pkg.isBlank()) {
                return false;
            }
            Path module = moduleDir == null || moduleDir.isEmpty() ? tree : tree.resolve(moduleDir);
            Set<String> own = new LinkedHashSet<>();
            for (String root : List.of("src/main/java", "src/test/java", "src/main/kotlin",
                    "src/test/kotlin", "src/main/scala", "src/test/scala")) {
                Path sources = module.resolve(root);
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(sources)) {
                    walk.filter(Files::isRegularFile).forEach(f -> {
                        Path dir = sources.relativize(f).getParent();
                        if (dir != null) {
                            own.add(dir.toString().replace('\\', '/').replace('/', '.'));
                        }
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    // an unreadable source root contributes nothing
                }
            }
            for (String p : own) {
                if (p.split("\\.").length >= 2 && (pkg.equals(p) || pkg.startsWith(p + "."))) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Whether the source of a top-level type is in {@code tree}, so a missing member of a type that
     * is NOT (it comes from a jar) is known to be beyond every task (live run 57, 2026-10-01).
     * Library-agnostic: it is a file-name question, no library is named anywhere. Fails open - an
     * unreadable tree, or no tree, answers yes for everything.
     *
     * <p>A Java type is found by its file ({@code a/b/C.java}). Kotlin, Scala and Groovy may name a
     * file anything, so when the tree holds any of those, a type counts as present when its package
     * directory holds one.
     */
    static Predicate<String> sourceInTree(Path tree) {
        if (tree == null || !Files.isDirectory(tree)) {
            return null;
        }
        Set<String> javaFiles = new LinkedHashSet<>();
        Set<String> otherDirs = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = tree.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.contains("/node_modules/")) {
                    return;
                }
                if (rel.endsWith(".java")) {
                    javaFiles.add(rel);
                } else if (rel.endsWith(".kt") || rel.endsWith(".scala") || rel.endsWith(".groovy")) {
                    int slash = rel.lastIndexOf('/');
                    otherDirs.add(slash < 0 ? "" : rel.substring(0, slash));
                }
            });
        } catch (java.io.IOException | RuntimeException e) {
            return null; // could not read the tree: nothing is established
        }
        return topLevelType -> {
            if (topLevelType == null || topLevelType.isBlank()) {
                return true;
            }
            String path = topLevelType.replace('.', '/');
            String wanted = "/" + path + ".java";
            for (String file : javaFiles) {
                if (file.endsWith(wanted) || file.equals(path + ".java")) {
                    return true;
                }
            }
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash);
            for (String other : otherDirs) {
                if (other.equals(dir) || other.endsWith("/" + dir)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * The members still-to-run tasks' contracts promise on types that already exist in
     * {@code tree} and that {@code tree} does not yet carry, by owner type. A contract naming a
     * type that does not exist at all is not a change to an existing type and is left out; it is
     * {@code TypeDeliverability}'s business.
     */
    static AcceptanceCompileErrors.PlannedChanges plannedChanges(Path tree, List<Task> stillToRun) {
        List<ApiContract> contracts = new ArrayList<>();
        if (stillToRun != null) {
            for (Task task : stillToRun) {
                if (task != null) {
                    contracts.addAll(task.deliveredContracts());
                }
            }
        }
        if (tree == null || contracts.isEmpty()) {
            return AcceptanceCompileErrors.PlannedChanges.NONE;
        }
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        try {
            // notYetThere, not shortfalls: a member that may or may not be inherited from a library
            // supertype is not one a candidate is failed for, but it IS one a task may be about
            // to add, and the test's compile error about it is then a healthy red.
            for (ContractDelivery.Shortfall shortfall : ContractDelivery.notYetThere(tree, contracts)) {
                if (shortfall.missingType()) {
                    continue;
                }
                String owner = shortfall.contract().typeName().strip();
                for (String member : shortfall.missingMembers()) {
                    String name = memberName(member);
                    if (!name.isEmpty()) {
                        byType.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(name);
                    }
                }
            }
        } catch (RuntimeException e) {
            // A scanner that could not read the tree has established nothing; NONE makes every
            // misuse broken, which is the reading run 43 needed, and costs at most one re-ask.
            return AcceptanceCompileErrors.PlannedChanges.NONE;
        }
        return new AcceptanceCompileErrors.PlannedChanges(byType);
    }

    /** {@code public Document(String a, Parser b)} → {@code Document}; {@code String NamespaceXml} → itself. */
    static String memberName(String member) {
        if (member == null) {
            return "";
        }
        String text = member.strip();
        int paren = text.indexOf('(');
        String head = (paren < 0 ? text : text.substring(0, paren)).strip();
        int eq = head.indexOf('=');
        if (eq >= 0) {
            head = head.substring(0, eq).strip();
        }
        head = head.replaceAll(";$", "").strip();
        String[] tokens = head.split("[\\s<>,\\[\\]]+");
        return tokens.length == 0 ? "" : tokens[tokens.length - 1];
    }

    /** Reads the offending line of an error, from wherever the compiler said the file was. */
    static Function<CompileError, String> sourceLines(Path tree, Collection<String> testPaths) {
        return error -> {
            Path file = resolve(tree, error.file(), testPaths);
            if (file == null || error.line() <= 0) {
                return null;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                return error.line() <= lines.size() ? lines.get(error.line() - 1) : null;
            } catch (Exception e) {
                return null; // not available is not empty; the classifier treats it so
            }
        };
    }

    private static Path resolve(Path tree, String printed, Collection<String> testPaths) {
        if (printed == null) {
            return null;
        }
        String p = printed.replace('\\', '/');
        // Maven on Windows prints /C:/Users/...: drop the leading slash before a drive letter.
        if (p.length() > 2 && p.charAt(0) == '/' && p.charAt(2) == ':') {
            p = p.substring(1);
        }
        try {
            Path asPrinted = Path.of(p);
            if (asPrinted.isAbsolute() && Files.isRegularFile(asPrinted)) {
                return asPrinted;
            }
        } catch (RuntimeException ignored) {
            // not a path this platform can read; try the tree
        }
        if (tree == null) {
            return null;
        }
        if (testPaths != null) {
            for (String t : testPaths) {
                String n = t == null ? "" : t.replace('\\', '/').strip();
                if (!n.isEmpty() && (p.equals(n) || p.endsWith("/" + n))) {
                    Path candidate = tree.resolve(n);
                    return Files.isRegularFile(candidate) ? candidate : null;
                }
            }
        }
        Path candidate = tree.resolve(p);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    // ------------------------------------------------------------------- the real signatures

    /** A fully-qualified type name as javac prints one: lower-case package, capitalised type. */
    private static final Pattern QUALIFIED =
        Pattern.compile("\\b((?:[a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\b");
    private static final Pattern CAPITALISED = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");

    /**
     * What the checkout really declares for the project types the broken lines involve — the
     * types javac named in its message, the owner of a missing member, and the project types
     * named on the offending line — restricted to the members those lines name (a constructor
     * only when the line says {@code new Type}); every public member of an owner the test looked
     * for a missing member on. Empty when nothing could be read: the author is then told the
     * compiler's lines alone, which is still the truth.
     */
    static String signatures(Path tree, Reading reading, Collection<String> testPaths) {
        return signatures(tree, reading, testPaths, LibraryTypes.NONE);
    }

    /**
     * As {@link #signatures(Path, Reading, Collection)}, and for a type the project's own tree does
     * not declare, the public members the reference checkouts (the librarian's) say it has: a test
     * that misuses a LIBRARY method is told the library's real signatures, not only the compiler's
     * complaint.
     */
    static String signatures(Path tree, Reading reading, Collection<String> testPaths,
                             LibraryTypes library) {
        if (library == null) {
            library = LibraryTypes.NONE;
        }
        if (tree == null || reading == null || !reading.isBroken()) {
            return "";
        }
        ProjectTypes types;
        try {
            types = ProjectTypes.of(tree);
        } catch (RuntimeException e) {
            return "";
        }
        Function<CompileError, String> lines = sourceLines(tree, testPaths);
        Map<String, Set<String>> shown = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (AcceptanceCompileErrors.Broken broken : reading.broken()) {
            CompileError error = broken.error();
            String src = lines.apply(error);
            String text = error.message() + "\n" + String.join("\n", error.detail())
                + (src == null ? "" : "\n" + src);
            Set<String> named = new LinkedHashSet<>();
            Matcher id = IDENTIFIER.matcher(text);
            while (id.find()) {
                named.add(id.group(1));
            }
            if (error.symbolName() != null) {
                named.add(error.symbolName());
            }
            Set<String> involved = new LinkedHashSet<>();
            if (error.kind() == AcceptanceCompileErrors.Kind.MISSING_MEMBER && error.owner() != null) {
                involved.add(error.owner().replace('$', '.'));
            }
            Matcher q = QUALIFIED.matcher(error.message() + "\n" + String.join("\n", error.detail()));
            while (q.find()) {
                involved.add(q.group(1));
            }
            if (src != null) {
                Matcher c = CAPITALISED.matcher(src);
                while (c.find()) {
                    String simple = c.group(1);
                    if (types.hasSimpleName(simple)) {
                        for (String full : types.fullNames()) {
                            if (full.equals(simple) || full.endsWith("." + simple)) {
                                involved.add(full);
                            }
                        }
                    }
                    involved.addAll(library.fullNamesOf(simple, 3));
                }
            }
            // The types the compiler itself named: for these, "we could not read its members" is
            // said out loud rather than left as silence (live run 63: the author invented a builder
            // method, was told only that it does not exist, and could not correct it).
            Set<String> namedByCompiler = new LinkedHashSet<>(involved);
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(involved);
            Set<String> done = new LinkedHashSet<>();
            Set<String> followed = new LinkedHashSet<>();
            while (!queue.isEmpty()) {
                String type = queue.poll();
                if (!done.add(type)) {
                    continue;
                }
                List<JavaSourceFacts.Exposed> members = types.exposedMembers(type);
                boolean fromLibrary = false;
                if (members.isEmpty()) {
                    members = library.exposedMembers(type);
                    fromLibrary = !members.isEmpty();
                }
                if (members.isEmpty()) {
                    // not a project or reference type (the JDK), or not readable
                    if (namedByCompiler.contains(type) && !type.startsWith("java.")
                            && !type.startsWith("javax.") && !LibraryTypes.jdkDeclares(type)
                            && !types.declares(type) && !library.declares(type)
                            && error.kind() != AcceptanceCompileErrors.Kind.MISSING_TYPE
                            && error.kind() != AcceptanceCompileErrors.Kind.MISSING_PACKAGE
                            && shown.computeIfAbsent(type, k -> new LinkedHashSet<>()).add("?")) {
                        out.add("  " + type + ": its members could NOT be read - there is no source "
                            + "for it in this checkout or in the reference material. Do not guess "
                            + "one: use only members the worked example or the rules show");
                    }
                    continue;
                }
                String simple = type.substring(type.lastIndexOf('.') + 1);
                boolean ownerOfMissing = error.kind() == AcceptanceCompileErrors.Kind.MISSING_MEMBER
                    && error.owner() != null && error.owner().replace('$', '.').equals(type);
                boolean constructs = src != null
                    && Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?" + Pattern.quote(simple) + "\\b")
                        .matcher(src).find();
                // A library type is shown whole: the author cannot open a jar, and a correction
                // that avoids the one wrong call by guessing another is the failure of run 63.
                boolean whole = ownerOfMissing || fromLibrary || followed.contains(type);
                for (JavaSourceFacts.Exposed member : members) {
                    boolean constructor = member.name().equals(simple);
                    boolean onTheLine = constructor ? constructs : named.contains(member.name());
                    if (!whole && !onTheLine) {
                        continue;
                    }
                    // The type a call returns is the type the NEXT call in a chain is made on:
                    // `Server.builder().port(1)` needs the builder's members, not the server's.
                    String returned = constructor ? null : returnTypeOf(member);
                    if (returned != null) {
                        String next = chainedType(returned, type, types, library,
                            onTheLine || ownerOfMissing);
                        if (next != null && !done.contains(next)) {
                            followed.add(next);
                            queue.add(next);
                        }
                    }
                    if (shown.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(member.header())) {
                        out.add("  " + type + ": " + member.header());
                    }
                    if (out.size() >= MAX_SIGNATURE_LINES) {
                        return String.join("\n", out) + "\n  (more members exist; only the first "
                            + MAX_SIGNATURE_LINES + " are shown)";
                    }
                }
            }
        }
        return String.join("\n", out);
    }

    private static final Pattern GENERIC_ARGS = Pattern.compile("<[^<>]*>");

    /** The simple name of the type a method returns or a field has, generics removed; else null. */
    static String returnTypeOf(JavaSourceFacts.Exposed member) {
        String header = member.header() == null ? "" : member.header();
        Matcher at = Pattern.compile("\\b" + Pattern.quote(member.name()) + "\\s*(\\(|=|;|$)")
            .matcher(header);
        if (!at.find()) {
            return null;
        }
        String before = header.substring(0, at.start());
        for (int i = 0; i < 6 && before.contains("<"); i++) {
            before = GENERIC_ARGS.matcher(before).replaceAll(" ");
        }
        String[] tokens = before.strip().split("[\\s\\[\\]]+");
        String last = tokens.length == 0 ? "" : tokens[tokens.length - 1];
        last = last.substring(last.lastIndexOf('.') + 1);
        return last.length() > 1 && Character.isUpperCase(last.charAt(0)) ? last : null;
    }

    /**
     * The type named {@code simple} that a member of {@code owner} returns, when its members can be
     * read: a type nested in the owner's top-level type always (the builder a type hands out); any
     * other project or reference type only when {@code anywhere} (the call is on the offending
     * line, or the owner is the type the missing member was looked for on).
     */
    private static String chainedType(String simple, String owner, ProjectTypes types,
                                      LibraryTypes library, boolean anywhere) {
        String top = topLevelOf(owner);
        if (simple.equals(owner.substring(owner.lastIndexOf('.') + 1))) {
            return null; // a fluent method returning its own type
        }
        // Only when the top-level type's own file is known: the by-name fallback of the index
        // would otherwise answer with any type of that simple name in the package.
        String nested = top + "." + simple;
        if (types.fileOf(top) != null && !types.exposedMembers(nested).isEmpty()) {
            return nested;
        }
        if (types.fileOf(top) == null && library.declares(top)
                && !library.exposedMembers(nested).isEmpty()) {
            return nested;
        }
        if (!anywhere) {
            return null;
        }
        for (String full : types.fullNames()) {
            if (full.endsWith("." + simple)) {
                return full;
            }
        }
        List<String> fromLibrary = library.fullNamesOf(simple, 1);
        return fromLibrary.isEmpty() ? null : fromLibrary.get(0);
    }

    /** {@code a.b.Outer.Inner} to {@code a.b.Outer}: up to the first capitalised segment. */
    private static String topLevelOf(String fullName) {
        StringBuilder sb = new StringBuilder();
        for (String part : fullName.split("\\.")) {
            sb.append(sb.isEmpty() ? "" : ".").append(part);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                return sb.toString();
            }
        }
        return fullName;
    }

    // ---------------------------------------------------------------------------- the words

    /**
     * What the test author is told, once, before the run parks: the compiler's own lines, why no
     * task can fix each one, and the real signatures of what the test misused.
     */
    static String reask(Reading reading, String signatures) {
        StringBuilder sb = new StringBuilder("Your test does not compile, and not because the code "
            + "it needs has not been written yet — that would be a healthy red state. No task in "
            + "this plan can make these lines compile, and a worker may not edit your test, so "
            + "every candidate would fail on them whatever it wrote. The compiler's own words:\n\n")
            .append(reading.quoted());
        if (signatures != null && !signatures.isBlank()) {
            sb.append("\n\nWhat this checkout really declares for the code those lines use:\n")
                .append(signatures);
        }
        sb.append("\n\nCorrect the test so it compiles against the code as it exists — and as the "
            + "design's contracts will make it — while proving the SAME check. Do not change what "
            + "the check proves, only how the test reaches it. Reply with the same JSON object, "
            + "with the corrected file(s).");
        return sb.toString();
    }

    /** The brief a run parks with when the test author was asked once and the test still does not compile. */
    static String park(String taskTitle, Reading reading, String signatures, String note) {
        StringBuilder sb = new StringBuilder("The acceptance test(s) for task '").append(taskTitle)
            .append("' do not compile, and no task in this plan can make them compile:\n\n")
            .append(reading.quoted());
        if (signatures != null && !signatures.isBlank()) {
            sb.append("\n\nWhat the checkout declares for the code those lines use:\n")
                .append(signatures);
        }
        sb.append("\n\n").append(note == null || note.isBlank() ? "" : note.strip() + "\n\n")
            .append("This is not a healthy red state. \"Does not compile\" reads the same whether "
                + "the code has not been written yet or the test uses code that already exists in a "
                + "way it does not accept, and only the second can never turn green. Dispatching a "
                + "swarm now would fail every candidate on a line none of them may edit (brownfield "
                + "harness run 43 spent two workers, a repair round of four and a blocked task "
                + "finding that out).\n\n"
                + "Decide which side is wrong: correct the test against the real signatures, or "
                + "add to the design a contract that changes that signature, so a task is asked to "
                + "deliver it. Then resume the run.");
        return sb.toString();
    }
}
