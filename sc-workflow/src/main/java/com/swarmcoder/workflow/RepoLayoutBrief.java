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

import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;

import java.nio.file.Path;
import java.util.List;

/**
 * What the planner is told about where this repository's code actually lives.
 *
 * <p><b>Why it exists.</b> Until 2026-08-30 the planner was told nothing about the target
 * repository's shape at all, so every write set it produced was a guess at a conventional layout.
 * Against {@code dev/bookshelf-demo} — a root {@code pom.xml} with {@code <packaging>pom</packaging>}
 * over three modules and no {@code src/} of its own — it planned tasks writing to
 * {@code src/main/java/com/zeroz4j/bookstore/...}, a source root that does not exist. The workers
 * obeyed the write set faithfully, the compile stage passed on the three untouched modules, and the
 * run delivered six files no compiler will ever see. The write set came from the plan; the plan came
 * from a model that had never been shown the module layout it was writing into.
 *
 * <p>The briefing is short on purpose. It names the module directories, the source roots inside
 * them, and one instruction: put files in one of these. When the layout cannot be read, the briefing
 * is empty and the prompt is exactly what it was — the planner is never handed a guess dressed up as
 * a fact.
 */
final class RepoLayoutBrief {

    /** Enough for any reactor a person can hold in their head; longer lists are truncated. */
    private static final int MAX_LISTED_ROOTS = 40;

    private RepoLayoutBrief() {}

    /** Empty when the layout could not be read — the planner is then told nothing about it. */
    static String forRepo(Path repoPath, String toolchain) {
        if (repoPath == null) {
            return "";
        }
        return render(BuildLayout.read(repoPath, toolchain));
    }

    /**
     * {@link #render(BuildLayout.Layout)}, plus — when the build has modules whose code runs only in
     * a browser — the paragraph that says so by name (harness run 37, 2026-09-25). The planner is
     * told, in its own rules, that a check about what a user sees or does is answered by the
     * client task; told nothing about the client being TeaVM, it planned a check whose acceptance
     * test could only be written against browser code, and no JUnit test can run that. See
     * {@link AcceptanceTestReach#architectBrief}.
     */
    static String render(BuildLayout.Layout layout, BrowserOnlyCode.Survey survey,
                         String acceptanceModule) {
        String base = render(layout);
        if (base.isEmpty()) {
            return base;
        }
        return base + AcceptanceTestReach.architectBrief(survey, acceptanceModule);
    }

    static String render(BuildLayout.Layout layout) {
        if (layout == null || !layout.determined()) {
            return "";
        }
        List<String> roots = layout.sourceRoots().stream().limit(MAX_LISTED_ROOTS).toList();
        StringBuilder sb = new StringBuilder("\n\nWHERE THIS REPOSITORY'S CODE LIVES — read this "
            + "before you write a single writeSet path.\n"
            + "These are the ONLY directories this project's build compiles or packages:\n");
        for (String root : roots) {
            sb.append("  ").append(root).append('\n');
        }
        if (layout.sourceRoots().size() > roots.size()) {
            sb.append("  … and ").append(layout.sourceRoots().size() - roots.size()).append(" more\n");
        }
        sb.append("\nEvery writeSet path MUST start with one of those directories. ");
        if (!layout.compilingModules().contains("")) {
            sb.append("There is NO source directory at the repository root: the root build file only "
                + "lists modules, it compiles nothing itself. A path like "
                + "\"src/main/java/...\" at the top level would be written, would look right, and "
                + "would never be compiled by anything — the work would simply not exist. ");
        }
        sb.append("Do not invent a directory that is not in the list above, and do not invent a "
            + "package name: use the package that already exists in the module you are writing "
            + "into.");
        // The planner had no way to express "this work needs a dependency", so it planned as
        // though the build were fixed — and run ede2068b's workers were then killed for being
        // unable to add the one pom line the project's own rules required. A module's build file
        // now comes with the module, so the planner neither has to ask for it nor may spend a task
        // on it.
        sb.append(" You do not need to list a module's build file: every task is automatically "
            + "given the pom.xml (or build.gradle) of any module whose sources it writes, so a "
            + "task that needs a new dependency declares it itself. Never plan a separate task "
            + "whose only work is adding a dependency.");
        return sb.toString();
    }
}
