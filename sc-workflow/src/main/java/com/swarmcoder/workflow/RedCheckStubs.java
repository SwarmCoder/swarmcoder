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
import com.swarmcoder.verify.AcceptanceCompileErrors;
import com.swarmcoder.verify.AcceptanceCompileErrors.Broken;
import com.swarmcoder.verify.AcceptanceCompileErrors.CompileError;
import com.swarmcoder.verify.AcceptanceCompileErrors.Kind;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import com.swarmcoder.verify.TypeDeliverability;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A second compile of the red-check tree with the types the plan will deliver stubbed in, so the
 * compiler gets past them and reports what ELSE is wrong with the acceptance test.
 *
 * <p><b>Why (live run 63, 2026-10-02).</b> A re-authored test called a builder method the library
 * does not have. The red check reported one error only — the import of the implementation class
 * the task had yet to write — and read it, correctly, as a healthy red. The invented method was
 * never reported: when an annotation processor is on the classpath, as it is in most real builds,
 * javac reports the names it could not resolve and stops before it attributes a method body
 * (measured in {@code TheRedCheckSeesPastTypesNotWrittenYetTest}; without a processor it goes on).
 * Eighty minutes of worker time later every candidate failed on that method, on a line none of
 * them may edit. The same had happened in run 62 one stage earlier.
 *
 * <p><b>What it does.</b> When a red state is a compile failure that reads healthy, and at least
 * one error in the test files is a missing type some task still to run will deliver, an empty
 * stub of each such type is written into the test's own source root, the acceptance stage is run
 * once more, and the errors are read again. A stub has no members and no supertypes — a plan's
 * contract members are a model's prose and need not parse — so the second reading counts only
 * errors a stub cannot have caused:
 * <ul>
 *   <li>a member missing from a type whose owner is not a stub (a library type above all);</li>
 *   <li>a name the test never declared or imported, unless it is statically imported from a stub;</li>
 *   <li>a missing type or package nobody delivers;</li>
 *   <li>a misuse whose compiler lines and source line name no stubbed type.</li>
 * </ul>
 * Everything else fails open, as the first reading does: calling a healthy red broken costs a
 * re-ask and possibly a park, and a stub that is less than the real type must never cause one.
 *
 * <p><b>The stubs never leave the tree they were written in.</b> They go into the throwaway
 * red-check worktree, which is never committed from, and are deleted again before this returns.
 * Beside them goes one file that cannot compile, so the second run always stops at compilation and
 * no test is ever run against a stub.
 */
final class RedCheckStubs {

    private RedCheckStubs() {}

    /** Stubbing one layer of missing types can uncover the next; this many layers are followed. */
    static final int MAX_ROUNDS = 3;

    /** The package of the file that stops the stubbed build before any test runs. */
    static final String MARKER_PACKAGE = "swarmcoderredcheckstubs";

    private static final Pattern PACKAGE_LINE =
        Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_LINE =
        Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;.*$");

    /**
     * What the second, stubbed compile found.
     *
     * @param reading the errors only the stubbed compile showed that no task can fix; never null,
     *                not broken when there were none
     * @param stubbed the fully-qualified types that were stubbed, in order
     */
    record Outcome(Reading reading, List<String> stubbed) {}

    /**
     * Runs the stubbed compile when {@code firstOutput} is a compile failure blocked by missing
     * types the plan will deliver; null when it is not, or the stubs could not be placed.
     *
     * @param tree        the throwaway red-check tree, holding the test files
     * @param firstOutput what the acceptance stage printed without stubs
     * @param testPaths   the task's acceptance test files, repo-relative
     * @param stillToRun  every task that has not run yet
     * @param compile     runs the acceptance stage in the tree and returns what it printed when it
     *                    failed to compile, or null when it did anything else
     */
    static Outcome read(Path tree, String firstOutput, Collection<String> testPaths,
                        List<Task> stillToRun, Function<Path, String> compile) {
        if (tree == null || firstOutput == null || firstOutput.isBlank() || testPaths == null
                || testPaths.isEmpty() || stillToRun == null || stillToRun.isEmpty()
                || compile == null) {
            return null;
        }
        Path root = testSourceRoot(tree, testPaths);
        if (root == null) {
            return null;
        }
        Set<String> stubbed = new LinkedHashSet<>();
        List<Path> written = new ArrayList<>();
        try {
            Reading reading = MiscompiledAcceptanceTest.read(tree, firstOutput, testPaths, stillToRun);
            Set<String> wanted = deliverableMissingTypes(tree, reading, testPaths, stillToRun, stubbed);
            if (wanted.isEmpty()) {
                return null;
            }
            for (int round = 0; round < MAX_ROUNDS && !wanted.isEmpty(); round++) {
                stubbed.addAll(wanted);
                removeAll(written, root);
                written.clear();
                writeStubs(tree, root, stubbed, stillToRun, written);
                String output = compile.apply(tree);
                if (output == null || output.isBlank()) {
                    return new Outcome(new Reading(List.of(), List.of()), List.copyOf(stubbed));
                }
                // Read while the stubs are still in the tree, so a stubbed type counts as the
                // project's own and a member missing from it is never called a library's.
                reading = MiscompiledAcceptanceTest.read(tree, output, testPaths, stillToRun);
                wanted = deliverableMissingTypes(tree, reading, testPaths, stillToRun, stubbed);
            }
            return new Outcome(notCausedByAStub(tree, reading, testPaths, stubbed),
                List.copyOf(stubbed));
        } catch (IOException | RuntimeException e) {
            return null; // an instrument that could not be set up has measured nothing
        } finally {
            removeAll(written, root);
        }
    }

    // ------------------------------------------------------------------------ what to stub

    /**
     * The missing types in the test files that some task still to run will deliver and that are
     * not stubbed yet: a missing class, or the class an {@code import} of a missing package names.
     */
    private static Set<String> deliverableMissingTypes(Path tree, Reading reading,
            Collection<String> testPaths, List<Task> stillToRun, Set<String> already) {
        Set<String> found = new LinkedHashSet<>();
        Function<CompileError, String> lines = MiscompiledAcceptanceTest.sourceLines(tree, testPaths);
        for (CompileError error : reading.inTestFiles()) {
            String type = null;
            if (error.kind() == Kind.MISSING_TYPE) {
                type = error.owner();
            } else if (error.kind() == Kind.MISSING_PACKAGE) {
                String src = lines.apply(error);
                Matcher m = src == null ? null : IMPORT_LINE.matcher(src);
                if (m != null && m.matches() && m.group(1) == null
                        && m.group(2).startsWith(error.owner() + ".")) {
                    type = m.group(2);
                }
            }
            if (type == null || type.isBlank() || already.contains(type) || !type.contains(".")
                    || !simpleNameOf(type).matches("[A-Z]\\w*")) {
                continue;
            }
            if (TypeDeliverability.undeliverable(List.of(type), stillToRun).isEmpty()) {
                found.add(type);
            }
        }
        return found;
    }

    /** The source root the first readable test file sits in: its path less its package and name. */
    static Path testSourceRoot(Path tree, Collection<String> testPaths) {
        for (String testPath : testPaths) {
            if (testPath == null || testPath.isBlank()) {
                continue;
            }
            Path file = tree.resolve(testPath.replace('\\', '/'));
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                Matcher m = PACKAGE_LINE.matcher(Files.readString(file));
                Path dir = file.getParent();
                if (m.find()) {
                    String[] parts = m.group(1).split("\\.");
                    for (int i = parts.length - 1; i >= 0 && dir != null; i--) {
                        if (!dir.getFileName().toString().equals(parts[i])) {
                            dir = null;
                            break;
                        }
                        dir = dir.getParent();
                    }
                }
                if (dir != null && dir.startsWith(tree)) {
                    return dir;
                }
            } catch (IOException | RuntimeException e) {
                // try the next test file
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------ writing them

    private static void writeStubs(Path tree, Path root, Set<String> types, List<Task> stillToRun,
                                   List<Path> written) throws IOException {
        // Nested types go inside the stub of their top-level type.
        Map<String, List<String>> nestedByTop = new LinkedHashMap<>();
        for (String type : types) {
            String top = topLevelOf(type);
            nestedByTop.computeIfAbsent(top, k -> new ArrayList<>());
            if (!top.equals(type)) {
                nestedByTop.get(top).add(type);
            }
        }
        java.util.function.Predicate<String> inTree = MiscompiledAcceptanceTest.sourceInTree(tree);
        for (Map.Entry<String, List<String>> entry : nestedByTop.entrySet()) {
            String top = entry.getKey();
            if (inTree != null && inTree.test(top)) {
                continue; // the tree has this type's source; never shadow it
            }
            int dot = top.lastIndexOf('.');
            String pkg = top.substring(0, dot);
            String simple = top.substring(dot + 1);
            StringBuilder source = new StringBuilder("package ").append(pkg).append(";\n\n")
                .append("/** A red-check stub. Never committed; deleted after the check. */\n")
                .append("public ").append(declaration(top, simple, stillToRun)).append(" {\n");
            for (String nested : entry.getValue()) {
                String rest = nested.substring(top.length() + 1);
                if (rest.contains(".")) {
                    continue; // deeper than one level: left missing, which fails open
                }
                source.append("    public static ").append(declaration(nested, rest, stillToRun))
                    .append(" {}\n");
            }
            source.append("}\n");
            Path file = root.resolve(pkg.replace('.', '/')).resolve(simple + ".java");
            if (Files.exists(file)) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.toString());
            written.add(file);
        }
        // One file that can never compile: the build stops at compilation, after the compiler
        // has read every other file, and no test is run against a stub.
        Path marker = root.resolve(MARKER_PACKAGE).resolve("StopBeforeAnyTestRuns.java");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "package " + MARKER_PACKAGE + ";\n\n"
            + "/** Deliberately wrong, so a build holding red-check stubs never runs a test. */\n"
            + "final class StopBeforeAnyTestRuns {\n"
            + "    int stop = \"a build holding red-check stubs must not get past compilation\";\n"
            + "}\n");
        written.add(marker);
    }

    /**
     * {@code class X}, or what the plan's contract for the type says it is when it says so in its
     * signature sketch ({@code interface X}, {@code enum X}, {@code record X}).
     */
    private static String declaration(String fullName, String simple, List<Task> stillToRun) {
        for (Task task : stillToRun) {
            if (task == null) {
                continue;
            }
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract == null || !contract.namesAType()
                        || !contract.typeName().replace('$', '.').strip().equals(fullName)) {
                    continue;
                }
                String sketch = contract.signatureSketch() == null ? "" : contract.signatureSketch();
                Matcher m = Pattern.compile("(@interface|\\binterface|\\benum|\\brecord)\\s+"
                    + Pattern.quote(simple) + "\\b").matcher(sketch);
                if (m.find()) {
                    String word = m.group(1);
                    return word + " " + simple + ("record".equals(word) ? "()" : "");
                }
            }
        }
        return "class " + simple;
    }

    private static void removeAll(List<Path> written, Path root) {
        for (Path file : written) {
            try {
                Files.deleteIfExists(file);
                // and the directories that held nothing else, up to the source root
                Path dir = file.getParent();
                while (dir != null && !dir.equals(root) && dir.startsWith(root)) {
                    try (var children = Files.list(dir)) {
                        if (children.findAny().isPresent()) {
                            break;
                        }
                    }
                    Files.delete(dir);
                    dir = dir.getParent();
                }
            } catch (IOException | RuntimeException e) {
                // the tree is throwaway and is removed by its owner; nothing depends on this
            }
        }
    }

    // ------------------------------------------------------------------------ reading the result

    /** The broken errors of {@code reading} that a memberless stub cannot have caused. */
    private static Reading notCausedByAStub(Path tree, Reading reading, Collection<String> testPaths,
                                            Set<String> stubbed) {
        Set<String> stubbedTops = new LinkedHashSet<>();
        Set<String> simpleNames = new LinkedHashSet<>();
        for (String type : stubbed) {
            stubbedTops.add(topLevelOf(type));
            simpleNames.add(simpleNameOf(type));
            simpleNames.add(simpleNameOf(topLevelOf(type)));
        }
        Function<CompileError, String> lines = MiscompiledAcceptanceTest.sourceLines(tree, testPaths);
        Set<String> staticallyImported = staticImportsFromStubs(tree, testPaths, stubbedTops);
        List<Broken> kept = new ArrayList<>();
        for (Broken broken : reading.broken()) {
            CompileError error = broken.error();
            boolean keep = switch (error.kind()) {
                case MISSING_MEMBER, MISSING_TYPE, MISSING_PACKAGE -> error.owner() != null
                    && !stubbedTops.contains(topLevelOf(error.owner().replace('$', '.')));
                case UNRESOLVED_NAME -> !staticallyImported.contains(error.symbolName())
                    && !staticallyImported.contains("*");
                case MISUSE -> !namesAny(error.message() + "\n" + String.join("\n", error.detail())
                    + "\n" + (lines.apply(error) == null ? "" : lines.apply(error)), simpleNames);
                case UNREADABLE_ABSENCE -> false;
            };
            if (keep) {
                kept.add(broken);
            }
        }
        return new Reading(reading.inTestFiles(), kept);
    }

    /** The member names the test files import statically from a stubbed type; {@code *} for a wildcard. */
    private static Set<String> staticImportsFromStubs(Path tree, Collection<String> testPaths,
                                                      Set<String> stubbedTops) {
        Set<String> names = new LinkedHashSet<>();
        for (String testPath : testPaths) {
            try {
                for (String line : Files.readAllLines(tree.resolve(testPath.replace('\\', '/')))) {
                    Matcher m = IMPORT_LINE.matcher(line.replace(".*;", ".__all__;"));
                    if (!m.matches() || m.group(1) == null) {
                        continue;
                    }
                    String imported = m.group(2);
                    int dot = imported.lastIndexOf('.');
                    if (dot > 0 && stubbedTops.contains(topLevelOf(imported.substring(0, dot)))) {
                        String member = imported.substring(dot + 1);
                        names.add("__all__".equals(member) ? "*" : member);
                    }
                }
            } catch (IOException | RuntimeException e) {
                names.add("*"); // could not tell: nothing unresolved is counted
            }
        }
        return names;
    }

    private static boolean namesAny(String text, Set<String> simpleNames) {
        for (String name : simpleNames) {
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String simpleNameOf(String fullName) {
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    /** {@code a.b.Outer.Inner} → {@code a.b.Outer}: up to the first capitalised segment. */
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
}
