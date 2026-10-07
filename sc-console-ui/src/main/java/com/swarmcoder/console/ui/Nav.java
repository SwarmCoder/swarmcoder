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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Cross-view navigation hooks, wired by MainView at boot.
 *
 * <p>Every one of these ultimately resolves to a {@link Stage}, because after the 2026-07-27
 * restructure there is nowhere else for anything to be: the workspace shows one stage, whole. The
 * named hooks survive because callers say what they MEAN ("open the requirements") rather than
 * naming a stage, which keeps the mapping in one place if it ever moves.
 *
 * <p><b>Threading:</b> everything here builds views whose constructors talk to the server, so these
 * must be invoked from a DOM event handler or a green thread — never from a signal
 * {@code Effect} or a native JS callback.
 */
final class Nav {

    /** Swaps the workspace to a stage. The one primitive; the rest are named shorthands for it. */
    static Consumer<Stage> openStage = stage -> { };

    /** Opens (or focuses) the run graph for a run id — in the Build stage, the only place it fits. */
    static Consumer<String> openRun = runId -> { };

    /** Opens a chat by id + title in the chat dock, whichever stage is showing. */
    static BiConsumer<String, String> openChat = (chatId, title) -> { };

    /** Goes to the Plan stage — the backlog, triage and the planning wizard. */
    static Runnable openBacklogBoard = () -> { };

    /** Goes to the Requirements stage — where documents are analysed and drafts are promoted. */
    static Runnable openRequirements = () -> { };

    /**
     * Shows whatever is owed an answer — the pipeline board, where each question sits on the card of
     * the story it belongs to (UX v3 §2.3).
     *
     * <p>It used to open the Approvals queue, which was a destination the operator had to know about
     * and which named runs rather than stories. The hook survives the queue because callers say what
     * they MEAN, and "show me what is owed" is still a thing worth being able to ask for.
     */
    static Runnable openApprovals = () -> { };

    /**
     * Opens the getting-started guide, from wherever the operator is.
     *
     * <p>One guide, offered from every place that has a reason to offer it: the labelled button in
     * the header, the empty state a new project lands on, and the moment a project is created. There
     * used to be no hook and three separate instances, all of them on the Setup panel — which is why
     * the first person to use the Console never found it. Setup is behind the small coloured mark in
     * the corner of the header, and nobody clicks a health indicator looking for a guide.
     *
     * <p>{@link StageHost} owns the single instance and wires this, so the walk survives being
     * closed, re-opened, and moved between workspaces.
     */
    static Runnable openGuide = () -> { };

    /** Opens the current project's settings dialog. Wired by {@link ProjectRail}, which owns it. */
    static Runnable openProjectSettings = () -> { };

    /** Opens the global settings dialog (roles, budgets, developer tools, raw YAML). */
    static Runnable openGlobalSettings = () -> { };

    private Nav() {}
}
