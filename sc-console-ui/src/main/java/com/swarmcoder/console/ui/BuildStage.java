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
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.layout.Div;

import java.util.ArrayList;
import java.util.List;

/**
 * What is read ABOUT the work, rather than done TO it: approvals still outstanding, the guidelines
 * workers obey, and what came of it all.
 *
 * <p><b>This stage used to hold the work itself</b> — a list of every story being built, with its
 * own reading of whether each run was still alive. That list is gone, into {@link PipelineBoard},
 * because there was never a good answer to "which of these two boards is this story on?": a story
 * crossing from planning to building DISAPPEARED from one screen and reappeared on the other, which
 * the operator reported four times in the same words, and each board decided liveness for itself, so
 * one story read "building now · 9h 5m" here and "stopped" there at the same moment.
 *
 * <p>What is left is two secondary faces: the standing instructions every worker gets, and what came
 * of it all. Both are read ABOUT the work rather than done TO it, and both live behind the ⋯ overflow
 * (UX v3 §3) — present, never on the main path.
 *
 * <p>The third face, Approvals, is <b>gone</b>. A question a build stopped to ask is now the situation
 * on the card of the story it belongs to, answered there (§2.3). Keeping a queue as well would have
 * been two places to answer one question, and the queue was the worse of the two: it listed run ids,
 * so the operator could not tell which of their stories any of the thirty-seven cards was about.
 *
 * <p>It also still owns the run graph, opened as a dialog over whatever is on screen, because
 * {@link Nav#openRun} needs somewhere to put it and {@link RunView} binds effects to a process-wide
 * signal that must be released on every close path.
 */
final class BuildStage extends Div implements Disposable {

    private final List<Disposable> disposables = new ArrayList<>();
    private final SubTabs tabs = new SubTabs();
    private final Div blocker = new Div();
    private final Div dialogHost = new Div();
    /** The run graph open over the page, so closing it can release the effects it holds. */
    private RunView openGraph;
    private Dialog openGraphDialog;

    BuildStage() {
        addClassName("flex flex-col h-full min-h-0");

        blocker.addClassName("shrink-0 px-3 py-1.5 border-b border-base-300 bg-warning/10 "
            + "text-xs text-base-content/70 leading-relaxed");
        blocker.setVisible(false);
        // No guidance bar. There is ONE next-step line now and it lives in the status header (UX v3
        // §3): three of these — one per stage — meant the operator's answer to "what do I do" depended
        // on which stage they happened to be standing in, and each bar spent most of its life talking
        // about a different stage's business.
        add(blocker);

        // No Approvals tab. The Approval Center is retired as a destination (UX v3 2.3 / 6): every
        // question a build stopped to ask is now on the card of the story it belongs to, with the
        // button that answers it, and its history is in that story's dialog. The queue held
        // thirty-seven cards naming RUNS - machinery the operator never handles - with no way to tell
        // which of their stories any of them was about, and nothing anywhere sent them to it.
        tabs.tab("guidelines", "Guidelines", "book", () -> new Padded(new GuidelinesView()))
            .tab("insights", "Insights", "chart", () -> new Padded(new InsightsView()));
        Div lower = new Div();
        lower.addClassName("flex-1 min-h-0 flex flex-col");
        lower.add(tabs);
        add(lower);
        add(dialogHost);

        disposables.add(Effect.create(() -> {
            ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
            boolean blocked = readiness != null && readiness.hasProject() && !readiness.canRun();
            blocker.setVisible(blocked);
            if (blocked) {
                String why = readiness.runBlocker();
                blocker.setText("Nothing can run yet: "
                    + (why == null || why.isBlank() ? "this project is not ready." : why)
                    + " The guidelines and past insights below still apply.");
            }
        }));

        tabs.selectDefault();
    }

    @Override
    public void dispose() {
        closeGraph();
        tabs.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Opens a run's graph OVER the page, so the operator keeps their place. */
    void openRun(String runId) {
        closeGraph();
        Dialog dialog = new Dialog();
        dialog.setWidth("72rem");
        RunView graph = new RunView(runId);
        Div frame = new Div();
        frame.addClassName("h-[70vh] flex flex-col min-h-0");
        frame.add(graph);
        dialog.add(frame);
        Button close = new Button("Close", e -> closeGraph());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        // Escape and a click outside now close the dialog without going through the Close button,
        // so the release has to hang off the close itself. Without this the graph keeps its
        // effects on the run-graph signal and goes on redrawing a page nobody is looking at.
        // Guarded on identity. A close listener runs on a green thread, so it can arrive
        // AFTER whatever replaced this dialog has been mounted and opened — and would then
        // tear down its successor. It only acts while this is still the dialog on screen.
        dialog.addCloseListener(e -> {
            if (openGraphDialog == dialog) {
                closeGraph();
            }
        });
        dialogHost.add(dialog);
        openGraph = graph;
        openGraphDialog = dialog;
        dialog.open();
    }

    /**
     * Releases the open graph.
     *
     * <p>{@link RunView} binds effects to the process-wide run-graph signal, so one that is merely
     * hidden goes on redrawing DOM that is no longer on screen.
     */
    private void closeGraph() {
        if (openGraphDialog != null) {
            openGraphDialog.close();
        }
        if (openGraph != null) {
            openGraph.dispose();
        }
        openGraph = null;
        openGraphDialog = null;
        dialogHost.removeAll();
        // A drill-down opened from the graph is drawn over the graph. The thing it drilled into is
        // gone, so leaving it up would strand a panel over an empty page.
        Inspector.close();
    }

    void selectTab(String id) {
        tabs.select(id);
    }

}
