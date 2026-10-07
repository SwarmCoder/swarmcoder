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
package com.swarmcoder.app;

import com.swarmcoder.domain.RunState;

import java.util.Arrays;

/**
 * The four places the live harness can save a snapshot of its run and a later run can start from
 * (2026-10-01).
 *
 * <p>A full walk takes about 50 minutes of model calls and every redo gives different rules, a
 * different story and a different design. Each point is a moment the product itself has already
 * persisted — a run state transition — so a snapshot taken there restores into a run the product's
 * own start-up path ({@code RunResumer}) picks up in that state:
 *
 * <ol>
 *   <li>{@link #REQUIREMENTS} — documents ingested, project rules stated, requirement agreed, story
 *       planned, and the run just entered DESIGN. Nothing of the design exists yet.</li>
 *   <li>{@link #DESIGN} — the design passed review; the run just entered PLAN.</li>
 *   <li>{@link #PLAN} — the plan was accepted and stored; the run just entered TEST_AUTHORING.</li>
 *   <li>{@link #BUILD} — the acceptance tests are authored and committed; the run entered EXECUTING
 *       and no worker is dispatched. This is the original {@code saveAtBuild} point.</li>
 * </ol>
 */
enum RestartPoint {

    REQUIREMENTS("1-requirements", RunState.DESIGN, 6,
        "requirements and project rules agreed, before the design"),
    DESIGN("2-design", RunState.PLAN, 6, "the design passed review, before the plan"),
    PLAN("3-plan", RunState.TEST_AUTHORING, 6, "the plan accepted, before the acceptance tests"),
    BUILD("4-build", RunState.EXECUTING, 9,
        "the acceptance tests authored and committed, before the workers");

    private final String folder;
    private final RunState runState;
    private final int links;
    private final String description;

    RestartPoint(String folder, RunState runState, int links, String description) {
        this.folder = folder;
        this.runState = runState;
        this.links = links;
        this.description = description;
    }

    /** The sub-folder name under a {@code saveAt} directory, and the manifest's {@code point}. */
    String folder() {
        return folder;
    }

    /** The state the run is in at this point, and is resumed in. */
    RunState runState() {
        return runState;
    }

    /** How many chain links a snapshot of this point carries (the chain's first N). */
    int links() {
        return links;
    }

    String description() {
        return description;
    }

    /** The point with this folder name; a snapshot that names none is the original build point. */
    static RestartPoint named(String folder) {
        if (folder == null || folder.isBlank()) {
            return BUILD;
        }
        return Arrays.stream(values()).filter(p -> p.folder.equals(folder)).findFirst()
            .orElseThrow(() -> new IllegalStateException("the snapshot names the restart point '"
                + folder + "', which this harness does not know; it knows "
                + Arrays.stream(values()).map(RestartPoint::folder).toList()));
    }

    /** The point reached when a run enters this state, or null when none is saved there. */
    static RestartPoint enteredAt(RunState state) {
        return Arrays.stream(values()).filter(p -> p != BUILD && p.runState == state)
            .findFirst().orElse(null);
    }
}
