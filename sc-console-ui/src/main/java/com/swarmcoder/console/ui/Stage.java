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
package com.swarmcoder.console.ui;

/**
 * The surfaces the workspace can show — <b>two of which are the operator's</b>.
 *
 * <p>This used to be a four-step sequence with a stepper across the top: Setup · Requirements · Plan
 * · Build, numbered, with a progress front drawn between them. The stepper is deleted (UX v3 §6),
 * because the sequence was a fiction: requirements get refined while earlier ones are already being
 * built, so a control whose whole shape says "you are on step 3 of 4" answers a question the process
 * does not have. With one pipeline board, process position is visible ON the board, so navigation no
 * longer needs to pretend to be a progress meter.
 *
 * <p>What is left is two workspaces, plus three things that are reachable without being on the path:
 *
 * <ul>
 *   <li>{@link #REQUIREMENTS} — the pool: documents, intake, the graph, the editor.</li>
 *   <li>{@link #PLAN} — the pipeline board. Every story, suggestion to delivery.</li>
 *   <li>{@link #SETUP} — no longer a step (§3.3). It is what the header's health dot opens when
 *       something is not configured; a green dot means there is nothing in here to do.</li>
 *   <li>{@link #BUILD} — what is read ABOUT the work. Behind the ⋯ overflow: present, never on the
 *       main path.</li>
 *   <li>{@link #PROMPT_LAB} / {@link #COMPONENTS} — developer tools, hidden until the toggle is on.</li>
 * </ul>
 *
 * <p>The <b>ids are unchanged</b> and deliberately so. Server-side
 * {@link com.swarmcoder.console.api.StageGuidance} is filed under them and the browser tests select on
 * {@code stage-<id>}; renaming the keys would orphan the guidance and break the selectors in the same
 * commit, for a cosmetic gain. The constant names {@code PLAN} and {@code BUILD} are stale for the
 * same reason — they are internal, never rendered, and §4's ban is on what the operator sees.
 */
enum Stage {

    /** The project, its folder, the models and the sandbox. Opened from the header's health dot. */
    SETUP("setup", "Setup", "gear", false, false),
    /** The requirements pool, with Knowledge beside it as the analyst's reference. */
    REQUIREMENTS("requirements", "Requirements", "file", true, false),
    /** The pipeline board: every story from suggestion to delivery, including the ones building. */
    PLAN("plan", "Pipeline", "list", true, false),
    /** Approvals, the guidelines workers obey, and what came of it all. Behind the ⋯ overflow. */
    BUILD("build", "More", "grip", false, false),

    /** Developer surface: the exact prompt each worker received. */
    PROMPT_LAB("promptlab", "Prompt Lab", "diff", false, true),
    /** Developer surface: the component gallery. */
    COMPONENTS("gallery", "Components", "grip", false, true);

    private final String id;
    private final String label;
    private final String icon;
    private final boolean workspace;
    private final boolean developer;

    Stage(String id, String label, String icon, boolean workspace, boolean developer) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.workspace = workspace;
        this.developer = developer;
    }

    /** Stable kebab-case key — the {@code data-testid} the browser tests select on. */
    String id() {
        return id;
    }

    String label() {
        return label;
    }

    String icon() {
        return icon;
    }

    /**
     * True for the two surfaces that get a tab in the header.
     *
     * <p>Everything else is reachable — from the health dot, the ⋯ overflow, the command palette or a
     * cross-link — but is not one of the places the operator has to choose between. That is the whole
     * of the navigation collapse: two, not four, and no numbers.
     */
    boolean workspace() {
        return workspace;
    }

    /** True for the surfaces that are tools rather than places; hidden unless the toggle is on. */
    boolean developer() {
        return developer;
    }
}
