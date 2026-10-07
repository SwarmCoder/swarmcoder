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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which directory of a project the acceptance tests go in — read from the build, never assumed.
 *
 * <p><b>Why this exists.</b> Until 2026-08-31 the answer was the constant
 * {@code src/test/java/swarm/accept}, relative to the repository root. Against
 * {@code dev/bookshelf-demo} — a root {@code pom.xml} with {@code <packaging>pom</packaging>} over
 * three modules and no {@code src/} of its own — that directory is compiled by nothing. Run
 * {@code e01d1378} wrote three acceptance tests there, the test author tried to write a fourth into
 * a real module and was REJECTED by the path policy for leaving the protected directory, the
 * acceptance stage then ran and executed zero tests, and the red-check correctly parked the run.
 * The test author was right and the directory was wrong. This is the same defect as the orphan
 * candidate code {@link BuildReachabilityCheck} was written for: a conventional path assumed
 * instead of read from the build.
 *
 * <h2>The rule</h2>
 *
 * <p><b>Acceptance tests go in the module whose test classpath can see the most of the project</b>
 * — the module that, directly or transitively, depends on the greatest number of the build's other
 * compiling modules. Ties go to the earliest in reactor order, so the answer is stable across runs.
 *
 * <p>The reason is what an acceptance test is. It is story-level: it proves a behaviour the user
 * asked for, and that behaviour routinely spans modules — "a book added through the UI appears in
 * the list" needs the storage module and the UI module on one classpath. "The module the task
 * writes to" is therefore the wrong answer, because a story has several tasks and they write to
 * different modules. The module at the bottom of the dependency graph is the only one that can
 * compile a test naming types from all of them. In {@code bookshelf-demo} that is
 * {@code bookshelf-demo-server}, which depends on both {@code -shared} and {@code -client}.
 *
 * <p><b>A single-module project is unchanged.</b> Its one compiling module is the repository root,
 * it wins with a reach of zero, and the directory comes out exactly as the old constant did.
 *
 * <p><b>When no single module can see the others</b> — a flat reactor whose modules do not depend
 * on each other at all — the rule still picks one (the first, deterministically) and says so in
 * {@link Location#note()}, with {@link Location#seesEveryModule()} false. That is deliberate. The
 * alternative answers are worse: the repository root compiles nothing, so writing there is the bug
 * this class exists to fix, and refusing to place the tests at all would park every run on such a
 * project. A module the build really compiles is always better than a directory it does not, and a
 * criterion that genuinely needs two unrelated modules cannot be tested from anywhere in that
 * repository — the honest fix is a test module the operator adds, and the note says so.
 */
public final class AcceptanceTestLocation {

    /** The protected tree, relative to whichever module hosts it. */
    public static final String PROTECTED_SUBDIR = "src/test/java/swarm";

    /** The package the tests themselves must be in, relative to the module. */
    public static final String WRITE_SUBDIR = PROTECTED_SUBDIR + "/accept";

    private AcceptanceTestLocation() {}

    /**
     * @param module          the module directory chosen, repo-relative; {@code ""} is the
     *                        repository root
     * @param protectedDir    the whole {@code swarm} tree, which workers may never write
     * @param writeDir        the {@code swarm/accept} package, where the test author puts files
     * @param seesEveryModule whether that module's classpath reaches every other compiling module
     * @param note            plain English about the choice, for the run log and the contract file
     */
    public record Location(String module, String protectedDir, String writeDir,
                           boolean seesEveryModule, String note) {}

    /** The convention answer, for a repository whose layout could not be read at all. */
    public static Location convention(String why) {
        return new Location("", PROTECTED_SUBDIR, WRITE_SUBDIR, true, why);
    }

    /**
     * Resolves the acceptance-test directory from a layout already read by {@link BuildLayout}.
     *
     * <p>Never null. When the layout is undetermined — a Node, Cargo or Python project, or build
     * files that could not be read — the convention answer comes back with the reason attached,
     * which is exactly the behaviour that existed before this class.
     */
    public static Location resolve(BuildLayout.Layout layout) {
        if (layout == null || !layout.determined() || layout.compilingModules().isEmpty()) {
            return convention("The repository's module layout could not be read, so the acceptance "
                + "tests go in the conventional place at the repository root. "
                + (layout == null ? "" : layout.note()));
        }
        List<String> modules = layout.compilingModules();
        if (modules.size() == 1) {
            String only = modules.get(0);
            return new Location(only, join(only, PROTECTED_SUBDIR), join(only, WRITE_SUBDIR), true,
                only.isEmpty()
                    ? "This build compiles one module, the repository root, so the acceptance tests "
                        + "go in its own test tree."
                    : "This build compiles exactly one module (" + only + "), so the acceptance "
                        + "tests go in that module's test tree.");
        }

        String best = null;
        int bestReach = -1;
        for (String module : modules) {
            int reach = reachable(layout, module).size();
            if (reach > bestReach) {
                best = module;
                bestReach = reach;
            }
        }
        boolean seesEveryModule = bestReach == modules.size() - 1;
        String where = best.isEmpty() ? "the repository root" : best;
        StringBuilder note = new StringBuilder();
        if (seesEveryModule) {
            note.append("Acceptance tests go in ").append(where)
                .append(", the one module of this ").append(modules.size())
                .append("-module build that depends on all the others, so a test written there can "
                    + "name types from any part of the project.");
        } else if (bestReach == 0) {
            note.append("Acceptance tests go in ").append(where)
                .append(". This build has ").append(modules.size())
                .append(" modules and none of them depends on another, so no module's test "
                    + "classpath can see the rest of the project. The tests are placed in the first "
                    + "module the build compiles, because a module the build really does compile is "
                    + "the only place a test can run at all — the repository root compiles nothing. "
                    + "A criterion that needs two of these modules at once cannot be tested from "
                    + "anywhere in this repository as it stands; add a module that depends on both "
                    + "and point the contract at it.");
        } else {
            note.append("Acceptance tests go in ").append(where)
                .append(", the module of this ").append(modules.size())
                .append("-module build with the widest view: it reaches ").append(bestReach)
                .append(" of the other ").append(modules.size() - 1)
                .append(". A criterion needing a module outside that set cannot be tested from "
                    + "here; add a module that depends on both and point the contract at it.");
        }
        return new Location(best, join(best, PROTECTED_SUBDIR), join(best, WRITE_SUBDIR),
            seesEveryModule, note.toString());
    }

    /** Reads the layout itself, for callers holding only a directory. */
    public static Location resolve(java.nio.file.Path repoRoot, String toolchain) {
        return resolve(BuildLayout.read(repoRoot, toolchain));
    }

    /** Every sibling module reachable from {@code module} through the dependency graph. */
    private static Set<String> reachable(BuildLayout.Layout layout, String module) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(layout.directDependenciesOf(module));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (next.equals(module) || !seen.add(next)) {
                continue;   // a dependency cycle would not build, but must not hang this either
            }
            queue.addAll(layout.directDependenciesOf(next));
        }
        return seen;
    }

    private static String join(String dir, String child) {
        return dir == null || dir.isEmpty() ? child : dir + "/" + child;
    }

    /** The whole-tree protected directory that corresponds to a write directory, and vice versa. */
    public static String writeDirUnder(String protectedDir) {
        if (protectedDir == null || protectedDir.isBlank()) {
            return WRITE_SUBDIR;
        }
        String clean = protectedDir.replace('\\', '/').replaceAll("/+$", "");
        return clean + "/accept";
    }

    /**
     * The module a task's protected acceptance-test directory sits in, repo-relative; {@code ""}
     * for the repository root. The inverse of {@link Location#protectedDir()}: a task carries only
     * the protected directory, and the test author's prompt needs the module name on its own, to
     * say plainly which build file's dependencies bound what the test can import (see
     * {@code TestAuthorClient}).
     */
    public static String moduleOf(String protectedDir) {
        if (protectedDir == null || protectedDir.isBlank()) {
            return "";
        }
        String clean = protectedDir.replace('\\', '/').replaceAll("/+$", "");
        String suffix = "/" + PROTECTED_SUBDIR;
        if (clean.endsWith(suffix)) {
            return clean.substring(0, clean.length() - suffix.length());
        }
        return clean.equals(PROTECTED_SUBDIR) ? "" : clean;
    }
}
