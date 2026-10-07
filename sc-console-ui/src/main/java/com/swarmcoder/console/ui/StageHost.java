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

import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ReadinessSignals;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.layout.Div;

import java.util.EnumMap;
import java.util.Map;

/**
 * The workspace. One stage at a time, whole.
 *
 * <p><b>Built once, then kept alive hidden</b> — not rebuilt on every stage click. The trade was
 * between leak risk and losing state, and state loses badly here: moving Requirements → Plan →
 * Requirements is the ordinary rhythm of the model this shell now describes, and rebuilding would
 * discard the selected requirement, the backlog's inspector context, the knowledge editor's unsaved
 * text and every scroll position, then re-run each constructor's server nudge for nothing. The leak
 * the shell's javadoc warns about is an {@code Effect} outliving its view; a view that is merely
 * hidden is still its effect's owner, so there is nothing orphaned — only a background subscription
 * redrawing into a detached-from-view subtree, which is exactly what an unselected tab did before.
 *
 * <p>The cost is paid at two points and only two: {@link #dispose()}, which the shell calls, and
 * {@link #reset()} on a project switch, where rebuilding is not a cost but the correct behaviour —
 * every stage is scoped to a project, so none of the old ones is showing the right thing any more.
 * Both dispose every stage they drop.
 *
 * <p>Stages are also built LAZILY: a first-run session that never leaves Setup never constructs the
 * BRD editor or the backlog at all.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #show} constructs stages, and stage constructors talk to the server (BrdView nudges the
 * BRD publish, PipelineBoard the backlog). It MUST therefore be called from
 * a DOM event handler or a green thread. Calling it from a signal {@link com.zeroz4j.signals.Effect}
 * or a native JS callback throws "Suspension point reached from non-threading context" — see
 * {@code MainView.refreshRuns} for the documented workaround the shell uses at boot.
 */
final class StageHost extends Div implements Disposable {

    private final Map<Stage, Component> built = new EnumMap<>(Stage.class);
    /** Shown instead of a stage that cannot mean anything yet; one instance, re-rendered. */
    private final Div gate = new Div();
    private Stage shown;
    /** Whether the gate — not a stage — is what is on screen right now. */
    private boolean gateShown;
    /** Stages whose construction is in flight; see {@link #show}. */
    private final java.util.Set<Stage> building = java.util.EnumSet.noneOf(Stage.class);
    /**
     * The one getting-started guide, owned here rather than by a stage.
     *
     * <p>It has to outlive whichever workspace is on screen, because the walk it describes crosses
     * both of them: adding a document belongs to Requirements and building belongs to the pipeline,
     * and a guide rebuilt on every stage swap would forget which step the operator had reached. It
     * used to be built fresh in three places, none of which was reachable without first finding the
     * small coloured health mark in the corner of the header.
     *
     * <p>It is a {@code display: contents} wrapper around its dialogs, so mounting it here costs no
     * layout, and it is never one of the things {@link #hideAllExcept} hides — those are only the
     * stages this host built.
     */
    private final OnboardingWizard guide = new OnboardingWizard();

    StageHost() {
        addClassName("flex-1 min-w-0 min-h-0 flex flex-col");
        gate.addClassName("flex-1 min-h-0 flex flex-col");
        gate.setVisible(false);
        add(gate);
        // Last, so the dialog is not a flex item in the middle of the column.
        add(guide);
        Nav.openGuide = guide::open;
    }

    @Override
    public void dispose() {
        for (Component stage : built.values()) {
            if (stage instanceof Disposable disposable) {
                disposable.dispose();
            }
        }
        built.clear();
        guide.dispose();
    }

    /**
     * Swaps the whole workspace to a stage.
     *
     * <p>MUST be called from a DOM event handler or a green thread — see the class javadoc.
     *
     * <h3>Why the {@link #building} guard exists</h3>
     *
     * <p>Building a stage SUSPENDS: BrdView and PipelineBoard call the server from their
     * constructors, and a green thread yields at every such call. Two calls can therefore be inside
     * {@code build} for the same stage at once — which happens on an ordinary first load, because
     * the readiness signal recommends a stage and the operator may click that same stage while its
     * construction is still in flight. Without the guard both calls construct, both add their panel,
     * the map keeps only the second, and the first is left in the document forever — hidden by
     * whichever call ran its hide pass last. The symptom is a workspace that looks right but whose
     * first copy of every element is invisible, which is precisely the kind of thing that is found
     * by a test timing out on a selector the screenshot shows perfectly well.
     */
    void show(Stage stage) {
        shown = stage;
        // Set HERE, not in the click handler that used to own it. This is the single funnel for
        // "the workspace is now showing this stage", and every other way in — openRun, openBuildTab,
        // Nav.openApprovals, Nav.openRequirements — goes through it without touching the bar. The
        // result was a stage bar that stayed on Plan while the operator was plainly looking at
        // Build: the one control whose entire job is saying where you are, lying about it.
        Stages.SELECTED.set(stage);
        boolean gateNeeded = gated(stage);
        if (gateNeeded) {
            gateShown = true;
            renderGate(stage);
            hideAllExcept(null);
            gate.setVisible(true);
            return;
        }
        Component panel = built.get(stage);
        if (panel == null) {
            if (!building.add(stage)) {
                // Another call is already constructing this stage; it will show it when it lands.
                return;
            }
            Component created;
            try {
                created = build(stage);
            } finally {
                building.remove(stage);
            }
            built.put(stage, created);
            created.setVisible(false);
            add(created);
            panel = created;
            // The selection may have moved on across the suspension points in build(). Honouring a
            // stale request would move the operator off the stage they just chose.
            if (shown != stage) {
                return;
            }
        }
        gateShown = false;
        gate.setVisible(false);
        hideAllExcept(panel);
        panel.setVisible(true);
    }

    private void hideAllExcept(Component keep) {
        for (Component existing : built.values()) {
            if (existing != keep) {
                existing.setVisible(false);
            }
        }
    }

    /**
     * Re-shows the current stage if it is gated and the gate has since lifted — a project appearing
     * makes the stage the operator already chose usable, which is not moving them, it is the same
     * stage becoming what they asked for.
     *
     * <p>Same threading rule as {@link #show}. Returns true when something was rebuilt.
     */
    boolean refreshGate() {
        if (gateStale()) {
            show(shown);
            return true;
        }
        return false;
    }

    /**
     * Whether what is on screen disagrees with whether the current stage can mean anything. Pure —
     * safe to call from inside a signal {@link com.zeroz4j.signals.Effect}, which is the point: the
     * shell asks this before paying for a green thread on every readiness republish.
     */
    boolean gateStale() {
        return shown != null && gated(shown) != gateShown;
    }

    /** Drops every built stage, disposing it. Used on a project switch — everything is rescoped. */
    void reset() {
        for (Component stage : built.values()) {
            remove(stage);
            if (stage instanceof Disposable disposable) {
                disposable.dispose();
            }
        }
        built.clear();
        // The guide is kept, and its walk is not: every step of it was about the project that has
        // just been left. Disposing it instead would take the one Nav.openGuide hook with it.
        guide.reset();
        if (shown != null) {
            show(shown);
        }
    }

    /**
     * Existing readiness gating, kept: without a project nothing in the Console is scoped to
     * anything, so a stage past Setup would be a graph of nothing and a backlog of nothing. The
     * stage stays CLICKABLE (author decision 2026-07-27) — what changes is that clicking it explains
     * itself instead of showing a convincing empty workspace.
     */
    private boolean gated(Stage stage) {
        if (stage == Stage.SETUP || stage.developer()) {
            return false;
        }
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        return readiness == null || !readiness.hasProject();
    }

    private void renderGate(Stage stage) {
        gate.removeAll();
        gate.add(new EmptyState(stage.icon(), stage.label() + " needs a project",
            "Everything in the Console is scoped to one project — requirements, the backlog, "
                + "knowledge and runs all belong to it. Create one in Setup and this stage fills in.")
            .withAction("Go to Setup", () -> Nav.openStage.accept(Stage.SETUP)));
    }

    private Component build(Stage stage) {
        return switch (stage) {
            case SETUP -> new SetupStage();
            case REQUIREMENTS -> new RequirementsStage();
            case PLAN -> new PlanStage();
            case BUILD -> new BuildStage();
            case PROMPT_LAB -> new Padded(new PromptLabView());
            case COMPONENTS -> new Padded(new GalleryView());
        };
    }

    /** Opens a run's graph, which only the Build stage can show. */
    void openRun(String runId) {
        show(Stage.BUILD);
        if (built.get(Stage.BUILD) instanceof BuildStage build) {
            build.openRun(runId);
        }
    }

    /** Focuses one of the reference faces (Guidelines, Insights). */
    void openBuildTab(String tabId) {
        show(Stage.BUILD);
        if (built.get(Stage.BUILD) instanceof BuildStage build) {
            build.selectTab(tabId);
        }
    }
}
