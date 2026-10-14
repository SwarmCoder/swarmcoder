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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.verify.BuildLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A design whose build file is still to be written keeps the build declaration it must follow
 * (DEVELOPER_CORRECTIONS section 106, finding C). A check of the architect's DRAFT only: once a
 * design is handed in, what was kept can no longer change.
 *
 * <h2>Why</h2>
 *
 * <p>An audited build started from an empty project on a reference library. Its design added
 * modules; of the twelve facts its architect kept, none came from a build lookup. The planner
 * and then every worker read the reference application's build files again, about 280,000
 * tokens. The architect had read them too: it kept how the code is written and not how it is
 * built, and nothing told it so while it could still keep it.
 *
 * <h2>What is decided, and from what</h2>
 *
 * <ul>
 *   <li><b>The design adds a build file</b> when it delivers at least one type the project does
 *       not have, and the project's build compiles no module yet ({@link BuildLayout}). A task
 *       will then have to write a build file, whatever the design's words say. In a project
 *       that already builds, a design names its types by package and not by module, so whether
 *       it adds a module cannot be read from its structure; nothing is objected to there.</li>
 *   <li><b>A fact comes from a build lookup</b> when the lookup it was copied from is one that
 *       reads a build declaration ({@code build_of}, {@code dependency_declaring}) or names a
 *       build file. Read from the finding's recorded source, never from its sentence.</li>
 *   <li><b>Nothing is asked for that cannot be given:</b> with no build file among the
 *       reference material there is no declaration to keep, and no objection.</li>
 * </ul>
 */
final class BuildFactsAreKept {

    private BuildFactsAreKept() {
    }

    /** The lookups that answer with what a build file declares. */
    private static final Set<String> BUILD_LOOKUPS = Set.of("build_of", "dependency_declaring");

    /**
     * @param design            the draft
     * @param kept              every finding the design will carry: kept in this session, and
     *                          carried from the design it revises
     * @param layout            the project's build as its build files state it; null reads as
     *                          a project with none
     * @param checkout          the project's own types; null reads as an empty project
     * @param referenceHasBuild true when a build declaration can be looked up at all
     */
    static List<String> objections(DesignDocument design, List<DesignFinding> kept,
                                   BuildLayout.Layout layout, ProjectTypes checkout,
                                   boolean referenceHasBuild) {
        if (design == null || !referenceHasBuild) {
            return List.of();
        }
        if (layout != null && !layout.compilingModules().isEmpty()) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        for (ApiContract contract : design.contracts()) {
            if (contract != null && contract.namesAType()
                    && (checkout == null || !checkout.declares(contract.typeName().strip()))) {
                added.add(contract.typeName().strip());
            }
        }
        if (added.isEmpty()) {
            return List.of();
        }
        List<DesignFinding> all = kept == null ? List.of() : kept;
        if (all.stream().anyMatch(BuildFactsAreKept::fromABuildLookup)) {
            return List.of();
        }
        return List.of("This design delivers " + added.size() + " type(s) this project does not "
            + "have (" + added.get(0) + (added.size() > 1 ? ", ..." : "") + ") and the project "
            + "has no build file that compiles a module yet, so a task will have to write one - "
            + "and none of the " + all.size() + " fact(s) you kept comes from a build lookup. "
            + "Look up the build declaration the new module must follow, with build_of on a "
            + "module of the reference material that is built the way this one will be, and "
            + "keep it with keep_for_workers (about: empty, it concerns the whole project). "
            + "The planner and every worker are given what you keep; what you do not keep, "
            + "each of them reads again.");
    }

    /** True when {@code finding} was copied from a lookup that reads a build declaration. */
    static boolean fromABuildLookup(DesignFinding finding) {
        String source = finding == null || finding.source() == null ? ""
            : finding.source().strip();
        if (source.isEmpty()) {
            return false;
        }
        int space = source.indexOf(' ');
        String tool = space < 0 ? source : source.substring(0, space);
        if (BUILD_LOOKUPS.contains(tool)) {
            return true;
        }
        // Any other lookup: by the file its argument names, as the lookup tools write an
        // address - a path, then optionally a line range, a section or a bracketed note.
        String argument = space < 0 ? "" : source.substring(space + 1).strip();
        int note = argument.indexOf(" [");
        if (note > 0) {
            argument = argument.substring(0, note);
        }
        int hash = argument.indexOf('#');
        if (hash >= 0) {
            argument = argument.substring(0, hash);
        }
        argument = argument.replaceAll(":\\d+(-\\d+)?$", "").strip();
        return BuildFilesInTheJob.isBuildFile(argument);
    }
}
