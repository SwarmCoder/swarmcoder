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

import com.swarmcoder.domain.BuildReachability;
import com.swarmcoder.domain.BuildReachabilityStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The gate a compile command cannot be: did the build that just passed actually compile the
 * candidate's code?
 *
 * <p><b>Why an exit code is not enough.</b> Measured on 2026-08-30 against
 * {@code dev/bookshelf-demo}, whose root {@code pom.xml} is a {@code <packaging>pom</packaging>}
 * aggregator over three modules. A run wrote six files under {@code src/main/java/...} at the
 * repository root — a source root that does not exist, because an aggregator compiles nothing. The
 * verification contract's {@code mvn -o -q -B compile test-compile} ran from that root, built the
 * three untouched modules, exited 0, and reported the candidate compiled. It did compile
 * something; it compiled everything except the candidate. Two tasks were selected and marked
 * delivered on code that is in no jar, no war and no classpath. Adding a compile command was not
 * the fix and could never have been: the question is REACHABILITY, and it has to be asked
 * separately.
 *
 * <p><b>Two rules, and one refusal to guess.</b>
 * <ol>
 *   <li><b>Source files fail.</b> A changed {@code .java}/{@code .kt}/{@code .scala}/{@code .groovy}
 *       file outside every source root the build compiles is an orphan. Nothing will ever compile
 *       it, so the work behind it does not exist.</li>
 *   <li><b>Other files fail only inside a {@code src/} tree nobody owns.</b> A css, a resource, a
 *       template placed under a {@code src/} directory belonging to no module the build builds is
 *       the same defect wearing a different extension — {@code src/main/webapp/bookstore.css} at
 *       the root of an aggregator is going nowhere. Anywhere else — {@code docs/}, {@code scripts/},
 *       a README, a Dockerfile, a build file, or an unusual directory inside a module the build DOES
 *       own — it is recorded and nothing more. Repositories legitimately hold files no compiler
 *       touches, and failing those would be inventing a defect.</li>
 * </ol>
 *
 * <p><b>What it does when it cannot tell.</b> Nothing. No changed-file list, a toolchain whose
 * source roots are configuration rather than convention (node, cargo, python), build files that
 * would not parse, a reactor in which no module compiles — each of these produces
 * {@link BuildReachabilityStatus#UNDETERMINED}, a sentence saying which one, and no effect on
 * survival. A gate that fails candidates on suspicion is worse than the hole it plugs: it would
 * make every unfamiliar repository unusable, and the operator would turn it off.
 */
final class BuildReachabilityCheck {

    /** Extensions a JVM build compiles. A file with one of these outside a source root is dead code. */
    private static final Set<String> COMPILED_EXTENSIONS = Set.of(
        ".java", ".kt", ".kts", ".scala", ".groovy");

    /**
     * Files that describe the build or the repository rather than being built by it. They are
     * exempt from both rules: a candidate editing the root {@code pom.xml} is doing the normal
     * thing, and a README is not code.
     */
    private static final Set<String> EXEMPT_NAMES = Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "package.json", "package-lock.json",
        "cargo.toml", "cargo.lock", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt",
        "dockerfile", "makefile", "readme.md", "readme", "license", "notice", ".gitignore",
        ".gitattributes", ".editorconfig");

    /** Directory prefixes that are repository furniture, never build input. */
    private static final List<String> EXEMPT_PREFIXES = List.of(
        ".swarmcoder/", ".github/", ".mvn/", ".gradle/", ".idea/", ".vscode/",
        "docs/", "doc/", "scripts/", "script/", "tools/", "bin/");

    /** The candidate never wrote these; they are build output and would be noise. */
    private static final List<String> IGNORED_PREFIXES = List.of(
        "target/", "build/", "out/", "dist/", "node_modules/", ".git/");

    /** Cap on how many paths are named in a report, so one runaway diff cannot flood the store. */
    private static final int MAX_LISTED = 50;

    private BuildReachabilityCheck() {}

    /**
     * @param target        the candidate's workspace, as the candidate left it
     * @param toolchain     the verification contract's declared toolchain
     * @param changedFiles  repo-relative paths the candidate added or changed; empty means unknown
     */
    static BuildReachability check(ExecTarget target, String toolchain, Set<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return undetermined("No list of the files this candidate added or changed was "
                + "available, so nothing could be compared against the build's source roots.");
        }
        BuildLayout.Layout layout = BuildLayout.read(target, toolchain);
        if (!layout.determined()) {
            return undetermined(layout.note());
        }

        List<String> roots = layout.sourceRoots();
        List<String> orphans = new ArrayList<>();
        List<String> noted = new ArrayList<>();

        for (String raw : new TreeSet<>(changedFiles)) {
            String path = BuildLayout.normalize(raw);
            if (path == null || path.isEmpty() || ignored(path) || exempt(path)) {
                continue;
            }
            if (underAnyRoot(path, roots)) {
                continue;
            }
            if (isCompiledSource(path)) {
                orphans.add(path);                                  // rule 1
            } else if (inUnownedSrcTree(path, layout.compilingModules())) {
                orphans.add(path);                                  // rule 2
            } else {
                noted.add(path);
            }
        }

        BuildReachabilityStatus status = orphans.isEmpty()
            ? BuildReachabilityStatus.REACHABLE : BuildReachabilityStatus.ORPHANED;
        String explanation = layout.note()
            + (noted.isEmpty() ? "" : " Outside every source root, but not failed on that alone: "
                + String.join(", ", cap(noted)) + ".");
        return new BuildReachability(status, cap(roots), cap(orphans), cap(noted), explanation);
    }

    private static BuildReachability undetermined(String why) {
        return new BuildReachability(BuildReachabilityStatus.UNDETERMINED, List.of(), List.of(),
            List.of(), why + " No candidate is failed on this: nothing was established either way.");
    }

    private static boolean underAnyRoot(String path, List<String> roots) {
        for (String root : roots) {
            if (root != null && !root.isEmpty() && path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompiledSource(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : COMPILED_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rule 2: the path lies under a {@code src/} directory whose owning directory is not a module
     * the build builds. {@code src/main/webapp/x.css} at the root of an aggregator has owner
     * {@code ""} — the repository root — which an aggregator pom is not a compiling module of, so
     * it fails. {@code some-module/src/main/frontend/x.css} has owner {@code some-module}, which IS
     * built, so it is merely noted: a module may hold directories a plugin picks up in ways this
     * check cannot read, and guessing there would fail honest work.
     */
    private static boolean inUnownedSrcTree(String path, List<String> compilingModules) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("src".equals(segments[i])) {
                String owner = String.join("/", List.of(segments).subList(0, i));
                return !compilingModules.contains(owner);
            }
        }
        return false;
    }

    private static boolean exempt(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (EXEMPT_NAMES.contains(name) || name.startsWith(".")) {
            return true;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ignored(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String prefix : IGNORED_PREFIXES) {
            if (lower.startsWith(prefix) || lower.contains("/" + prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> cap(List<String> values) {
        if (values.size() <= MAX_LISTED) {
            return List.copyOf(values);
        }
        List<String> capped = new ArrayList<>(values.subList(0, MAX_LISTED));
        capped.add("… and " + (values.size() - MAX_LISTED) + " more");
        return List.copyOf(capped);
    }
}
