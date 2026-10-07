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
package com.swarmcoder.verify;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a symbol the compiler could not find is one this plan will ever create.
 *
 * <p>A freshly authored acceptance test not compiling is, by itself, no evidence at all: it is
 * exactly what a test written before its code looks like, and it is also exactly what a test
 * naming something nobody will ever build looks like. The two are told apart by asking the one
 * question the compiler cannot: who, among the tasks this plan still has to run, either promised
 * this type as a design contract ({@link Task#deliveredContracts()}) or owns a write set whose
 * package could hold it?
 *
 * <p>This is the ONE classifier both callers share: {@link RedChecker}'s compile-failure reading
 * at TEST_AUTHORING, and the wave-time early park ({@code UndeliverableType}, sc-workflow) that
 * stops a later wave from being dispatched at a test that could never compile for anybody. A
 * single implementation means a fix to one is a fix to both, rather than two readings of the same
 * question drifting apart.
 *
 * <p><b>It fails open.</b> No tasks, no missing symbols, or a write-set entry whose source root
 * cannot be read all mean the question could not be settled — and an unsettled question is never
 * read as "broken". It answers "broken" only when it can name, concretely, that nobody in the
 * plan may create the symbol.
 */
public final class TypeDeliverability {

    private TypeDeliverability() {}

    /** The directory tails a build compiles from; everything under one is that task's to create. */
    private static final List<String> SOURCE_ROOTS = List.of(
        "src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy",
        "src/test/java", "src/main", "src");

    /**
     * The entries of {@code missing} that no task in {@code tasks} either delivers as a contract
     * or may write a file for — in the order {@code missing} named them.
     *
     * <p>Each entry is read two ways, because {@link CompileFailureAttribution#missingTypes} mixes
     * two shapes into one list: a fully-qualified TYPE name ({@code com.acme.shop.Rating}, from
     * "cannot find symbol") and a bare PACKAGE name ({@code com.acme.ui}, from "package ... does
     * not exist"). Read as a type, the package is everything before the last dot; read as a
     * package outright, it is the whole string. A task may create the entry if its write set
     * covers either reading — which is what lets a write set of {@code …/ui} answer for a missing
     * {@code com.acme.ui} exactly as it would for a missing {@code com.acme.ui.Something}.
     *
     * @param missing fully-qualified names the compiler said do not exist; a type's package, or a
     *                bare package name
     * @param tasks   every task that has not run yet and so could still create one of them
     */
    public static List<String> undeliverable(List<String> missing, List<Task> tasks) {
        if (missing == null || missing.isEmpty()) {
            return List.of();
        }
        List<Task> candidates = tasks == null ? List.of() : tasks;
        List<String> broken = new ArrayList<>();
        for (String name : missing) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String stripped = name.strip();
            if (nobodyWillCreate(stripped, candidates)) {
                broken.add(stripped);
            }
        }
        return List.copyOf(broken);
    }

    /** True when no task still to run delivers this as a contract, or may write its file. */
    private static boolean nobodyWillCreate(String fullName, List<Task> tasks) {
        for (Task task : tasks) {
            if (task == null) {
                continue;
            }
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract != null && contract.namesAType()
                        && fullName.equalsIgnoreCase(contract.typeName().strip())) {
                    return false;
                }
            }
            if (mayCreate(task, fullName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code task}'s write set could hold a file declaring or opening {@code fullName} —
     * tried both as a type's package (everything before the last dot) and as a bare package (the
     * whole name), so a package-only miss is judged by the package a task actually owns rather
     * than by one level up from it. See the class doc for why both readings matter.
     *
     * <p>A write-set entry may itself be a FILE rather than a directory — {@code ArchitectClient}'s
     * own prompt allows either shape, and a task naming
     * {@code src/main/java/org/jsoup/select/QueryParser.java} is ordinary, not an edge case. A file
     * entry covers its parent directory's package exactly as a directory entry for that directory
     * would (so a sibling type in the same package is covered too), and on top of that directly
     * delivers the one type whose simple name matches the file's own name — a net for a file that
     * sits somewhere the source-root search cannot place, so the concrete promise "this file will
     * exist" still counts for the one type it obviously names.
     */
    static boolean mayCreate(Task task, String fullName) {
        if (task.writeSet() == null || task.writeSet().isEmpty()) {
            return false;
        }
        int dot = fullName.lastIndexOf('.');
        String typePackage = dot < 0 ? "" : fullName.substring(0, dot).replace('.', '/');
        String simpleName = dot < 0 ? fullName : fullName.substring(dot + 1);
        String wholeAsPackage = fullName.replace('.', '/');
        for (String entry : task.writeSet()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String normalized = entry.replace('\\', '/').strip();
            if (isBuildFile(normalized)) {
                // A BUILD FILE DECLARES NO TYPE (brownfield harness run 43, 2026-09-26). Run 43's
                // one task wrote [src/main/java/org/jsoup/nodes/Document.java, pom.xml]; pom.xml
                // has no source root in it, so the fail-open branch below read it as "an
                // unreadable layout, may write anything", and every missing name in the whole
                // plan counted as deliverable by that one entry. The layout is not unreadable: it
                // is a build file, and a build file can add a dependency but never a type.
                continue;
            }
            String tail = packageTail(normalized);
            if (tail.isEmpty()) {
                return true; // an unreadable layout: fail open
            }
            if (typePackage.equals(tail) || typePackage.startsWith(tail + "/")) {
                return true;
            }
            if (wholeAsPackage.equals(tail) || wholeAsPackage.startsWith(tail + "/")) {
                return true;
            }
            String fileType = fileSimpleName(normalized);
            if (fileType != null && fileType.equalsIgnoreCase(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code task}'s write set can hold the source FILE of the existing top-level type
     * {@code topLevelType} — the question "could this task add a member to it", asked by
     * {@link AcceptanceCompileErrors} (brownfield harness run 43, 2026-09-26).
     *
     * <p>Stricter than {@link #mayCreate} on purpose. That method answers "could a type of this
     * name appear anywhere in this package", and a file entry covers its whole directory for it,
     * because a task writing {@code Foo.java} may well add a sibling. Adding a member to an
     * EXISTING type needs that type's own file: a file entry counts only when it names exactly
     * that type ({@link #typeNamed}), a directory entry when its package covers the type's. An
     * entry with no recognised source root, or a build file, contributes nothing — the fail-open
     * of {@code mayCreate} is right for "might this type be created", and wrong here, where a
     * yes waves a test that can never compile through to a whole swarm. A directory entry that
     * IS a source root (a task owning all of {@code src/main/java}) covers everything.
     */
    public static boolean mayWrite(Task task, String topLevelType) {
        return mayWrite(task, topLevelType, true);
    }

    /**
     * {@link #mayWrite(Task, String)} without the "a whole source root covers everything" reading:
     * only an entry that names the type's own file, or a package directory that holds it. For a
     * type that has NO hand-written source (a jar's class, a generated one), a task owning all of
     * {@code src/main/java} says nothing about whether that type grows a member.
     */
    public static boolean mayWriteNarrowly(Task task, String topLevelType) {
        return mayWrite(task, topLevelType, false);
    }

    private static boolean mayWrite(Task task, String topLevelType, boolean rootCoversAll) {
        if (task == null || task.writeSet() == null || topLevelType == null
                || topLevelType.isBlank()) {
            return false;
        }
        String fullName = topLevelType.strip();
        int dot = fullName.lastIndexOf('.');
        String typePackage = dot < 0 ? "" : fullName.substring(0, dot).replace('.', '/');
        for (String entry : task.writeSet()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String normalized = entry.replace('\\', '/').strip();
            if (isBuildFile(normalized)) {
                continue;
            }
            String path = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
            if (isFileName(basename(path))) {
                String named = typeNamed(path);
                if (named != null && named.equals(fullName)) {
                    return true;
                }
                continue;
            }
            String tail = packageTailStrict(path);
            if (tail == null) {
                continue;
            }
            if ((rootCoversAll && tail.isEmpty()) || typePackage.equals(tail) || typePackage.startsWith(tail + "/")) {
                return true;
            }
        }
        return false;
    }

    /** A build file: it can declare a dependency, never a type. */
    static boolean isBuildFile(String entry) {
        String name = basename(entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
        return "pom.xml".equals(name) || "build.gradle".equals(name)
            || "build.gradle.kts".equals(name) || "settings.gradle".equals(name)
            || "settings.gradle.kts".equals(name) || "build.xml".equals(name);
    }

    /**
     * The part of a write-set entry that is package structure rather than build layout.
     *
     * <p>When the entry names a FILE, its own file name is dropped first: the tail is the file's
     * PARENT directory, so {@code .../org/jsoup/select/QueryParser.java} yields
     * {@code org/jsoup/select} — the same tail a directory entry of {@code .../org/jsoup/select}
     * would yield, and for the same reason: everything else in that package is this task's to
     * create too.
     */
    private static String packageTail(String entry) {
        String path = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        if (isFileName(basename(path))) {
            int slash = path.lastIndexOf('/');
            path = slash < 0 ? "" : path.substring(0, slash);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String root : SOURCE_ROOTS) {
            if (lower.endsWith("/" + root) || lower.equals(root)) {
                return "";
            }
            int at = lower.indexOf("/" + root + "/");
            if (at >= 0) {
                return path.substring(at + root.length() + 2);
            }
            if (lower.startsWith(root + "/")) {
                return path.substring(root.length() + 1);
            }
        }
        return ""; // no source root in it: the layout is not readable, so assume it may write
    }

    /**
     * The fully-qualified type name a write-set entry names, concretely — the forward reading of
     * {@link #mayCreate}. That method asks "could this task's write set hold a file declaring X";
     * this asks "what X, precisely, does this entry promise", which is what {@code
     * com.swarmcoder.workflow.AcceptanceTestVocabulary} needs to add a task's own write set to the
     * vocabulary an acceptance test may use (harness run 38, 2026-09-25 — see that class's javadoc
     * for the run this exists because of).
     *
     * <p>Answered only for a write-set entry that is a FILE (carries an extension) sitting under a
     * recognised source root: {@code .../src/main/java/com/acme/shop/server/FooImpl.java} names
     * {@code com.acme.shop.server.FooImpl}. Null for a DIRECTORY entry, which promises a whole
     * package rather than one concrete type, and for a file under no recognised source root, where
     * the package cannot be read off the path with any confidence — unlike {@link #mayCreate},
     * this does NOT fail open, because a wrong guess here does not just fail to dispatch a wave, it
     * tells a test author a name is safe to use when nobody agreed it is.
     */
    public static String typeNamed(String writeSetEntry) {
        if (writeSetEntry == null || writeSetEntry.isBlank()) {
            return null;
        }
        String normalized = writeSetEntry.replace('\\', '/').strip();
        String simple = fileSimpleName(normalized);
        if (simple == null) {
            return null; // a directory entry: it names a package, not one type
        }
        String tail = packageTailStrict(normalized);
        if (tail == null) {
            return null; // no recognised source root: the package cannot be trusted
        }
        return tail.isEmpty() ? simple : tail.replace('/', '.') + "." + simple;
    }

    /**
     * {@link #packageTail}, except a path with no recognised source root is reported as unreadable
     * (null) rather than assumed to be the default package. {@link #mayCreate} wants to fail open
     * on an unreadable layout (dispatch the wave rather than park it on a guess); {@link
     * #typeNamed} wants the opposite, because its answer is offered to a test author as a name safe
     * to use, and a wrong guess there is the mistake this whole file exists to prevent.
     */
    private static String packageTailStrict(String entry) {
        String path = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        if (isFileName(basename(path))) {
            int slash = path.lastIndexOf('/');
            path = slash < 0 ? "" : path.substring(0, slash);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String root : SOURCE_ROOTS) {
            if (lower.endsWith("/" + root) || lower.equals(root)) {
                return "";
            }
            int at = lower.indexOf("/" + root + "/");
            if (at >= 0) {
                return path.substring(at + root.length() + 2);
            }
            if (lower.startsWith(root + "/")) {
                return path.substring(root.length() + 1);
            }
        }
        return null; // no recognised source root at all: unlike packageTail, this is not a guess
    }

    /** The type a FILE write-set entry names by its own file name — null for a directory entry. */
    private static String fileSimpleName(String entry) {
        String path = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        String basename = basename(path);
        if (!isFileName(basename)) {
            return null;
        }
        int dot = basename.lastIndexOf('.');
        return dot < 0 ? basename : basename.substring(0, dot);
    }

    /** The last path segment. */
    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** A path's last segment is a FILE, not a directory, when it carries a file extension. */
    private static boolean isFileName(String basename) {
        return !basename.isEmpty() && basename.indexOf('.') >= 0;
    }
}
