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

import com.swarmcoder.console.api.GraphCandidateDto;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Elapsed;
import com.swarmcoder.domain.RepairIndex;
import com.swarmcoder.domain.WorkerHealth;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GraphService_Stub;
import com.swarmcoder.console.api.GraphRequirementDto;
import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.GraphTestDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.swarmcoder.domain.TaskState;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.PropertyGrid;
import com.zeroz4j.ui.component.StatusDot;
import com.zeroz4j.ui.component.SvgCanvas;
import com.zeroz4j.ui.component.Steps;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.mixin.HasLayer;
import com.zeroz4j.ui.theme.Layer;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.EventTarget;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.TextRectangle;
import org.teavm.jso.dom.xml.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.LaneTimeline;
import java.util.HashSet;
import java.util.Set;

/**
 * The live run graph (design §6): phase strip → task DAG → candidate fan, rebuilt from the run's
 * snapshot signal — the LIVE pill means deltas are flowing. Clicking a candidate opens the
 * Inspector (level 1: sampling/verdict; level 2: the full session transcript).
 *
 * <p>The task DAG and the candidate fan are drawn on {@link SvgCanvas}, which pans and zooms. The
 * phase strip is not: it is DOM above the canvas, because it is chrome rather than graph, and
 * because the browser lays out running text far better than fixed SVG coordinates can — see
 * {@link #renderPhaseStrip}.
 *
 * <h2>It must be disposed</h2>
 *
 * <p>The effects below are bound to {@link GraphStore}, whose signals are process-wide and live for
 * as long as the page does. An instance that is dropped without {@link #dispose()} therefore keeps
 * redrawing into a subtree nobody can see, for ever, and every re-open adds another one. That is
 * cheap to ignore while this only ever appeared as a Build tab opened a handful of times; it is not
 * cheap now that the Approval Center opens one as a dialog for a peek at a run, which an operator
 * working a queue of twenty-one does twenty-one times.
 *
 * <p>Both mount points honour it: {@code SubTabs.close} disposes a closable tab's panel, and
 * {@code PipelineBoard.closeRunGraph} disposes the peek when its dialog closes.
 */
final class RunGraphView extends Div implements Disposable {

    /**
     * The phases a build goes through, keyed by run state.
     *
     * <p>APPROVAL is not among them: that park is abolished (UX v3 2.3), so drawing it as a step
     * every build was heading towards described a place none of them can reach. The labels are the
     * operator's words rather than the enum's (4) - {@code BuildState.phrase} is where they live, so
     * this graph and the card that opens it say the same thing about the same phase.
     */
    private static final String[] STAGES = {"INTAKE", "DESIGN", "DESIGN_REVIEW", "PLAN",
        "TEST_AUTHORING", "EXECUTING", "FINAL_INTEGRATION"};

    private static final int TASK_W = 190;
    /**
     * Three lines: the title, the write set, and what the test author did. The third line is
     * where a task says "writing its tests" and then wears its "3 tests · 2 checks" badge - inside
     * the box rather than on an edge of it, because both edges are already spoken for: the phase
     * caption sits above the top edge, and the dependency edges leave from the bottom one. A row
     * inside the box overlaps neither at any width, and every box keeps one shape whether or not
     * it has anything to say.
     */
    private static final int TASK_H = 66;
    private static final int CAND_W = 52;
    private static final int CAND_H = 34;

    private final String runId;
    /** Everything bound to {@link GraphStore}'s process-wide signals — see the class javadoc. */
    private final List<Disposable> disposables = new ArrayList<>();
    private final SvgCanvas canvas = new SvgCanvas();
    /** The phase strip. In the DOM above the canvas, not drawn into it — see {@link #renderPhaseStrip}. */
    private final Div phaseStrip = new Div();
    /** Who is working right now, and on what — see {@link #renderLiveBand}. */
    private final Div liveBand = new Div();
    private final LaneTimeline timeline =
        new LaneTimeline();
    private final Div timelineHost = new Div();
    private final Div timelineScroll = new Div();
    private com.zeroz4j.ui.component.Resizer resizer;
    private Button expandBtn;
    private boolean timelineExpanded = false;
    /** sessionId → [openedAt, closedAt] for replay-state computation. */
    private final Map<String, long[]> sessionWindows = new HashMap<>();
    private boolean timelineLoaded;
    /**
     * The detail behind a node, in the application's own idiom rather than the operating system's.
     *
     * <p>ONE element, reused by every node. The alternative - a card per node - leaves a trail of
     * them behind a pointer crossing a fan of twenty chips, because the card that should close is
     * the one whose node the pointer has already left.
     *
     * <p>It is HTML, and the graph is SVG. The two are not mixed: the card is a sibling of the
     * canvas, absolutely positioned over it, placed from the hovered node's position on the screen.
     * Drawing it INTO the canvas was never an option - SVG text neither wraps nor clips, which is
     * the same fault that took the phase strip out of the canvas (see {@link #renderPhaseStrip}) -
     * and a card inside the canvas would also pan and zoom with the graph.
     */
    private final Div hoverCard = new Div();

    RunGraphView(String runId) {
        this.runId = runId;
        // relative: the hover card is positioned against this box, so it must be the offset parent.
        addClassName("flex-1 min-h-0 flex flex-col relative");
        getElement().setAttribute("data-testid", "run-graph");

        Div header = new Div();
        header.addClassName("flex items-center gap-3 px-4 py-2 border-b border-base-300 text-sm shrink-0");
        Span idChip = new Span(runId.substring(0, 8));
        idChip.addClassName("font-mono text-xs bg-base-300/60 rounded px-1.5 py-0.5");
        StatusDot stateDot = new StatusDot("PENDING", "waiting to start");
        Span stateLabel = new Span("");
        stateLabel.addClassName("font-semibold");
        Span goal = new Span("");
        goal.addClassName("flex-1 truncate text-base-content/60");
        Span live = new Span("LIVE");
        live.addClassName("text-[10px] font-bold tracking-wider text-success animate-pulse");
        header.getElement().appendChild(idChip.getElement());
        header.add(stateDot);
        header.getElement().appendChild(stateLabel.getElement());
        header.getElement().appendChild(goal.getElement());
        header.getElement().appendChild(live.getElement());
        Button replayToggle = new Button("Timeline");
        replayToggle.addClassName("btn-ghost btn-xs ml-3 text-base-content/70");
        replayToggle.getElement().setAttribute("title", "Scrub through the run's history");
        replayToggle.addClickListener(e -> toggleTimeline());
        header.add(replayToggle);
        add(header);

        phaseStrip.addClassName("shrink-0 px-5 pt-4 pb-3 border-b border-base-300 bg-base-100");
        add(phaseStrip);

        liveBand.addClassName("shrink-0");
        liveBand.setVisible(false);
        add(liveBand);

        canvas.addClassName("flex-1 min-h-0 bg-base-100");
        canvas.getElement().setAttribute("data-testid", "run-graph-canvas");
        canvas.svg().setAttribute("width", "100%");
        canvas.svg().setAttribute("height", "100%");
        add(canvas);

        // A surface that floats on the page, in the Console's own words for it: bg-base-100 is
        // what anything lifted off the page is painted with, base-300 is every edge, and the
        // shadow is what the completion panels in the chat composer already use for the same job.
        // pointer-events-none is not decoration: without it the card lands under the pointer, the
        // node's mouseleave fires, the card closes, the pointer is back on the node - and a chip
        // near the card flickers instead of being clickable.
        hoverCard.addClassName("absolute pointer-events-none w-[19rem] max-w-[90vw] "
            + "bg-base-100 border border-base-300 rounded-xl shadow-2xl "
            + "px-3.5 py-3 text-[11px] leading-snug text-base-content");
        HasLayer.applyTo(hoverCard, Layer.TOOLTIP);
        hoverCard.getElement().setAttribute("data-testid", "graph-hover-card");
        hoverCard.setVisible(false);
        add(hoverCard);
        // Dragging the graph slides the nodes out from under a pointer that has not moved, so no
        // mouseleave fires and the card would go on describing a node that is no longer there.
        canvas.getElement().addEventListener("mousedown",
            (org.teavm.jso.dom.events.EventListener<org.teavm.jso.dom.events.Event>)
                event -> hideHoverCard());
        
        timelineHost.setVisible(false);
        timelineHost.addClassName("flex flex-col bg-base-100");
        
        Div timelineBar = new Div();
        timelineBar.addClassName("sticky top-0 flex items-center justify-between px-3 py-1.5 bg-base-200/90 border-b border-base-300");
        // A bar that stays put while the replay scrolls under it: the named tier for exactly that,
        // in place of the hand-picked z-10 it used to carry.
        HasLayer.applyTo(timelineBar, Layer.STICKY);
        
        Div leftTitle = new Div();
        leftTitle.addClassName("flex items-center gap-2 font-semibold text-xs text-base-content/70");
        leftTitle.add(Icon.of("clock", "w-3.5 h-3.5"));
        Span tspan = new Span("Timeline Replay");
        leftTitle.getElement().appendChild(tspan.getElement());
        
        Div rightControls = new Div();
        rightControls.addClassName("flex items-center gap-1");
        
        expandBtn = new Button("Expand");
        expandBtn.addClassName("btn-ghost btn-xs text-base-content/70");
        expandBtn.addClickListener(e -> {
            timelineExpanded = !timelineExpanded;
            updateTimelineSizing();
        });
        
        Button closeBtn = new Button(Icon.of("x", "w-4 h-4"));
        closeBtn.addClassName("btn-ghost btn-xs text-base-content/70 hover:text-error");
        closeBtn.addClickListener(e -> toggleTimeline());
        
        rightControls.add(expandBtn);
        rightControls.add(closeBtn);
        
        timelineBar.add(leftTitle);
        timelineBar.add(rightControls);
        
        timelineScroll.addClassName("overflow-y-auto flex-1 min-h-0");
        timeline.getElement().setAttribute("data-testid", "replay-timeline");
        timelineScroll.add(timeline);
        
        timelineHost.add(timelineBar);
        timelineHost.add(timelineScroll);
        
        updateTimelineSizing();
        resizer = new com.zeroz4j.ui.component.Resizer(timelineHost, com.zeroz4j.ui.component.Resizer.Orientation.HORIZONTAL, true);
        add(resizer);
        add(timelineHost);
        // Time travel: any cursor move recomputes every candidate's state at that instant.
        disposables.add(Effect.create(() -> {
            timeline.cursor.get();
            RunGraphDto graph = GraphStore.graph(runId).get();
            if (graph != null) {
                redraw(graph);
            }
        }));

        disposables.add(Effect.create(() -> {
            RunGraphDto graph = GraphStore.graph(runId).get();
            if (graph == null) {
                return;
            }
            // The phase, not the enum name. This label was the last place in the product where a
            // RunState reached the operator verbatim — and the dot beside it hovered as the enum
            // for a while longer, which is the same leak through a different attribute.
            String phase = BuildState.isTerminal(graph.getRunState())
                ? ("DELIVERED".equals(graph.getRunState()) ? "finished" : "stopped")
                : BuildState.phrase(graph.getRunState());
            stateDot.setState(graph.getRunState(), phase);
            stateLabel.setText(phase);
            goal.setText(graph.getGoal());
            // APPROVAL is still treated as terminal: no build reaches it any more, but one persisted
            // there by an older process must not leave this view polling for ever.
            boolean terminal = "APPROVAL".equals(graph.getRunState())
                || BuildState.isTerminal(graph.getRunState());
            live.setVisible(!terminal);
            renderLiveBand(graph);
            redraw(graph);
        }));

        // Snapshot now, then let the server publisher stream changes.
        
            try {
                GraphService graphService = new GraphService_Stub();
                GraphStore.onGraph(graphService.snapshot(runId));
                graphService.watch(runId);
            } catch (Exception ex) {
                // Without the snapshot AND the watch there is nothing to draw and nothing coming,
                // so the blank canvas under a pulsing LIVE pill would claim a healthy live run.
                // The header goal line is the one piece of chrome already visible here; a later
                // snapshot from the push topic overwrites it with the real goal.
                goal.setText("run graph unavailable: " + ex.getMessage());
                live.setVisible(false);
                ClientLog.error("RunGraphView", "run graph failed to load — the graph for run "
                    + runId + " is blank and no live updates are subscribed: " + ex);
            }

        toggleTimeline();
    }

    /**
     * Releases the bindings to the run-graph signal.
     *
     * <p>The server-side {@code watch} started in the constructor is deliberately NOT cancelled:
     * the push topic is shared by every view of this run and by the run list, so unsubscribing on
     * behalf of one of them would go dark on the others. What is released here is this view's own
     * redraw, which is the part that would otherwise outlive it.
     */
    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    private void updateTimelineSizing() {
        if (timelineExpanded) {
            timelineHost.removeClassName("max-h-64");
            timelineHost.removeClassName("shrink-0");
            timelineHost.addClassName("flex-1");
            timelineHost.addClassName("min-h-0");
            expandBtn.setText("Collapse");
        } else {
            timelineHost.removeClassName("flex-1");
            timelineHost.removeClassName("min-h-0");
            timelineHost.addClassName("shrink-0");
            timelineHost.addClassName("max-h-64");
            expandBtn.setText("Expand");
        }
    }

    private boolean timelineVisible;

    /** Opens the replay panel, loading lanes (sessions + event ticks) on first use. */
    private void toggleTimeline() {
        timelineVisible = !timelineVisible;
        timelineHost.setVisible(timelineVisible);
        if (resizer != null) {
            resizer.setVisible(timelineVisible);
        }
        if (!timelineVisible || timelineLoaded) {
            return;
        }
        timelineLoaded = true;
        
            try {
                GraphService graphService = new GraphService_Stub();
                var observer = new ObserverService_Stub();
                var lanes = new ArrayList<LaneTimeline.Lane>();
                int lanesWithoutTicks = 0;
                String lastTickFailure = "";
                for (var session : graphService.runSessions(runId)) {
                    var eventTimes = new ArrayList<Long>();
                    try {
                        for (var event : observer.sessionEvents(session.getSessionId(), 0, 500)) {
                            eventTimes.add(event.getAtMillis());
                        }
                    } catch (Exception ex) {
                        // A run can have dozens of sessions and one broken store fails all of
                        // them, so the tally is reported once below rather than per lane. The lane
                        // still renders: its bar is right, only its event ticks are missing.
                        lanesWithoutTicks++;
                        lastTickFailure = String.valueOf(ex);
                    }
                    sessionWindows.put(session.getSessionId(), new long[]{
                        session.getOpenedAtMillis(), session.getClosedAtMillis()});
                    lanes.add(new LaneTimeline.Lane(
                        laneLabel(session.getRole(), session.getModel()),
                        session.getOutcome(), session.getOpenedAtMillis(),
                        session.getClosedAtMillis(), eventTimes));
                }
                timeline.setLanes(lanes);
                if (lanesWithoutTicks > 0) {
                    ClientLog.warn("RunGraphView", "timeline event ticks failed to load for "
                        + lanesWithoutTicks + " of " + lanes.size() + " sessions in run " + runId
                        + " — those lanes are drawn without their event marks: " + lastTickFailure);
                }
            } catch (Exception ex) {
                // Lanes are loaded once and timelineLoaded is already true, so nothing will retry
                // and the replay panel would stay blank as if the run had no sessions at all.
                Div failed = new Div("Timeline unavailable: " + ex.getMessage());
                failed.addClassName("text-xs text-error p-3");
                timelineScroll.add(failed);
                ClientLog.error("RunGraphView", "run timeline failed to load — the replay panel "
                    + "for run " + runId + " shows no sessions and will not retry: " + ex);
            }
    }

    /**
     * The label on a replay lane: which worker, and what it was writing with.
     *
     * <p>Both, again. The model id was dropped from here on ZeroZ Stack 0.7.0, where
     * {@code LaneTimeline} drew its labels into a fixed 90&nbsp;px column and silently cut
     * anything over twelve characters — "worker-0 qwen3.8-27b" arrived on the row as
     * "worker-0 qw" plus the component's own ellipsis, so the model never survived to be read and
     * passing it was worse than not. 0.8.0 measures the column from the longest name instead, up
     * to 260&nbsp;px, and shows the whole name on hover. Which model wrote an attempt is the point
     * of reading a replay beside the graph, so it goes back on the row.
     */
    private static String laneLabel(String role, String model) {
        String worker = role == null ? "" : role.trim();
        String wrote = model == null ? "" : model.trim();
        return wrote.isEmpty() ? worker : (worker + " " + wrote).trim();
    }

    /**
     * A candidate's state at the replay cursor: PENDING before its session opened, RUNNING
     * inside the window, its final state after — live state when the cursor is null or the
     * session window is unknown.
     */
    private String stateAt(GraphCandidateDto candidate) {
        Long at = timeline.cursor.get();
        if (at == null) {
            return nz(candidate.getState());
        }
        long[] window = sessionWindows.get(nz(candidate.getSessionId()));
        if (window == null || window[0] <= 0) {
            return nz(candidate.getState());
        }
        if (at < window[0]) {
            return "PENDING";
        }
        if (window[1] > 0 && at >= window[1]) {
            return nz(candidate.getState());
        }
        return "RUNNING";
    }

    // --- drawing ---------------------------------------------------------------------------------

    /**
     * Draws the whole graph — and keeps drawing when one node cannot be drawn.
     *
     * <p>Every node is drawn inside its own guard. It used to be one straight loop, so any fault in
     * any node — a field that arrived null, a state nobody has seen before — threw out of the
     * effect that called it and left the operator with whatever half of the graph had been painted
     * before the throw, permanently, since a dead effect never runs again. One unreadable attempt
     * must cost that attempt's chip and nothing else, so it is drawn as a question mark and the
     * reason goes to the client log.
     */
    private void redraw(RunGraphDto graph) {
        // Every node the card could be pointing at is about to be thrown away, and a frame arrives
        // roughly once a second while a swarm runs.
        hideHoverCard();
        canvas.clearContent();
        renderPhaseStrip(nz(graph.getRunState()));

        // Tasks in dependency-layer order, candidates fanned under their task.
        List<List<GraphTaskDto>> layers = layers(graph.getTasks());
        Map<String, int[]> taskCenters = new HashMap<>();
        int taskTop = 32;
        int y = taskTop;
        for (List<GraphTaskDto> layer : layers) {
            int x = 40;
            for (GraphTaskDto task : layer) {
                List<GraphCandidateDto> candidates = candidatesOf(graph, nz(task.getTaskId()));
                int blockWidth = Math.max(TASK_W, Math.min(candidates.size(), 5) * (CAND_W + 8));
                try {
                    drawTask(task, graph, x, y, blockWidth);
                } catch (RuntimeException e) {
                    ClientLog.warn("RunGraphView", "a task could not be drawn in run " + runId
                        + "; the rest of the graph is unaffected: " + e);
                }
                taskCenters.put(nz(task.getTaskId()), new int[]{x + blockWidth / 2, y});
                drawCandidates(candidates, x, y + TASK_H + 14, blockWidth);
                x += blockWidth + 60;
            }
            y += TASK_H + 14 + candidateRowsHeight(graph, layer) + 50;
        }
        // Dependency edges between task layers.
        for (GraphTaskDto task : graph.getTasks()) {
            int[] to = taskCenters.get(nz(task.getTaskId()));
            if (to == null || nz(task.getDependsOnCsv()).isEmpty()) {
                continue;
            }
            for (String from : nz(task.getDependsOnCsv()).split(",")) {
                int[] source = taskCenters.get(from);
                if (source != null) {
                    Element path = SvgCanvas.el("path",
                        "d", "M " + source[0] + " " + (source[1] + TASK_H)
                            + " C " + source[0] + " " + (source[1] + TASK_H + 40) + ", "
                            + to[0] + " " + (to[1] - 40) + ", " + to[0] + " " + to[1],
                        "fill", "none", "stroke", "#64748b", "stroke-width", "1.5",
                        "stroke-dasharray", "5 3");
                    canvas.viewport().appendChild(path);
                }
            }
        }
    }

    /**
     * The phases across the top, in the operator's words, each shown ONCE.
     *
     * <p>The dedupe is the point. Two run states share one phrase — DESIGN and DESIGN_REVIEW are both
     * "working out a design" (UX v3 §4) — which is right on a card, where only one phase is on screen,
     * and wrong here: the ribbon drew the same three words twice in a row, side by side, and a
     * sequence that repeats itself reads as a rendering fault. Collapsing where the VOCABULARY
     * collapses keeps one derivation of the words and one step per thing the operator can distinguish.
     *
     * <h2>Why this is DOM and not SVG</h2>
     *
     * <p>It used to be drawn into the canvas: six 128&nbsp;px rounded rectangles at fixed
     * coordinates, each with one centred {@code <svg:text>} inside it. SVG text neither wraps nor
     * clips to a shape, so a phrase wider than 128&nbsp;px simply painted over its neighbours —
     * "writing the tests it must pass" needs about 145&nbsp;px at 10&nbsp;px type and overlapped the
     * pills on both sides of it. The strip as a whole came to 918&nbsp;px of fixed coordinates in a
     * canvas about 860&nbsp;px wide, so the last phase was cut off at the dialog's edge as well. Both
     * were true at 1600&nbsp;px, and got worse as the window narrowed: nothing in the drawing knew
     * how wide the words were or how much room there was.
     *
     * <p>The fix is to stop drawing text the browser is better at laying out. In the DOM the phase
     * strip is one {@code ul.steps} of six equal columns that divide whatever width the dialog has,
     * and a phrase too long for its column wraps onto a second line inside it. It therefore cannot
     * overflow at any width, and {@code ConsoleBrowserSmokeTest} asserts exactly that.
     *
     * <p>Equal columns with wrapping, rather than pills sized to their words that scroll sideways:
     * this strip's whole job is to show where in a fixed sequence of six the build has got to, and
     * that reading is destroyed by a phase being scrolled out of sight. Every phase stays visible at
     * every width; what gives way is line count, which costs a few pixels of height and no meaning.
     *
     * <p>It also belongs outside the canvas for a second reason: the canvas pans and zooms, and a
     * status strip that slides away when the operator drags the graph under it was never intended.
     */
    private void renderPhaseStrip(String runState) {
        int currentIndex = stageIndex(runState);
        List<String> phases = new ArrayList<>();
        List<Integer> firstState = new ArrayList<>();
        for (int i = 0; i < STAGES.length; i++) {
            String phrase = BuildState.phrase(STAGES[i]);
            if (phases.isEmpty() || !phases.get(phases.size() - 1).equals(phrase)) {
                phases.add(phrase);
                firstState.add(i);
            }
        }
        // Which cell the run is in: the last cell whose first state is at or before it.
        int activeCell = 0;
        for (int cell = 0; cell < firstState.size(); cell++) {
            if (firstState.get(cell) <= currentIndex) {
                activeCell = cell;
            }
        }
        boolean delivered = "DELIVERED".equals(runState);

        phaseStrip.removeAll();
        Steps steps = new Steps();
        // w-full is what makes the columns share the dialog rather than grow to fit their words:
        // daisyUI sizes .steps by its content unless it is given a width to divide.
        steps.addClassName("w-full text-[11px] leading-tight");
        steps.getElement().setAttribute("data-testid", "phase-strip");
        for (int i = 0; i < phases.size(); i++) {
            boolean done = i < activeCell || delivered;
            boolean active = i == activeCell && !delivered;
            HTMLElement step = Window.current().getDocument().createElement("li");
            step.setAttribute("class", "step whitespace-normal px-1"
                + (done ? " step-success" : active ? " step-primary" : "")
                + (active ? " font-semibold text-primary"
                          : done ? " text-base-content/70" : " text-base-content/45"));
            // A tick for what is finished; the rest keep daisyUI's own step numbers, which say how
            // far through a build of six phases this is without any extra words.
            if (done) {
                step.setAttribute("data-content", "✓");
            }
            step.setAttribute("data-phase", phases.get(i));
            step.appendChild(Window.current().getDocument().createTextNode(phases.get(i)));
            steps.getElement().appendChild(step);
        }
        phaseStrip.add(steps);
    }

    /**
     * Who is working right now, above the graph, in words.
     *
     * <p>The graph draws a chip per attempt, which answers "how many" and never answered "is
     * anything actually happening". An operator watching four workers build for twenty minutes was
     * looking at exactly the same picture they would see if all four had hung — and on the day this
     * was written they were also looking at no chips at all, because the frame on screen had been
     * assembled before the swarm was dispatched. So the count, the age and the step are said in
     * words where the eye lands first, and the age of the last step is the number that separates
     * working from stuck.
     *
     * <p>Every duration is measured against the SERVER's clock, which travels with the frame. The
     * browser's own clock is minutes out on some machines, and that error lands squarely on the
     * number an operator uses to decide a worker is dead.
     */
    private void renderLiveBand(RunGraphDto graph) {
        List<GraphCandidateDto> running = new ArrayList<>();
        for (GraphCandidateDto candidate : graph.getCandidates()) {
            if ("RUNNING".equals(candidate.getState())) {
                running.add(candidate);
            }
        }
        liveBand.removeAll();
        liveBand.setVisible(!running.isEmpty());
        liveBand.getElement().setAttribute("data-testid", "live-workers");
        if (running.isEmpty()) {
            return;
        }
        long now = graph.getAtMillis() > 0 ? graph.getAtMillis() : System.currentTimeMillis();

        // Every worker's health first, so the headline can say whether anything is wrong before
        // the rows are built. The question the band is read to answer is "should I be worried",
        // and counting orange rows is not an answer at a glance.
        List<WorkerHealth.Kind> health = new ArrayList<>();
        int inTrouble = 0;
        for (GraphCandidateDto candidate : running) {
            WorkerHealth.Kind kind = healthOf(candidate, now);
            health.add(kind);
            if (WorkerHealth.isWarning(kind)) {
                inTrouble++;
            }
        }

        Div box = new Div();
        box.addClassName("px-5 py-2.5 border-b border-base-300 "
            + (inTrouble > 0 ? "bg-warning/10" : "bg-info/10"));
        Div heading = new Div();
        heading.addClassName("flex items-center gap-2 text-xs font-semibold text-base-content/80");
        heading.add(Icon.of("bolt", "w-3.5 h-3.5 " + (inTrouble > 0 ? "text-warning" : "text-info")));
        Span headline = new Span(running.size() == 1
            ? "1 worker is writing code right now"
            : running.size() + " workers are writing code right now");
        heading.getElement().appendChild(headline.getElement());
        if (inTrouble > 0) {
            Span alarm = new Span(inTrouble == 1
                ? " - 1 of them is heading for trouble"
                : " - " + inTrouble + " of them are heading for trouble");
            alarm.addClassName("text-warning font-semibold");
            alarm.getElement().setAttribute("data-testid", "live-workers-warning");
            heading.getElement().appendChild(alarm.getElement());
        }
        box.add(heading);

        Div rows = new Div();
        // Two columns at most. Four fitted across at 1600 px and every line was then cut at
        // "running exe...", which threw away the half of the sentence the operator actually needs:
        // how long it has been since the worker last did anything.
        rows.addClassName("mt-1.5 grid gap-x-8 gap-y-1 grid-cols-1 md:grid-cols-2");
        for (int i = 0; i < running.size(); i++) {
            GraphCandidateDto candidate = running.get(i);
            boolean warn = WorkerHealth.isWarning(health.get(i));
            // One CELL per worker, holding its line and - when there is one - the reason under it.
            // The reason cannot be a sibling in the grid: it would land in the next worker's
            // column. And it cannot be appended to the line either, because that line is already
            // truncated at two columns and what gets cut is always the end.
            Div cell = new Div();
            Div row = new Div();
            row.addClassName("flex items-baseline gap-2 text-[11px] font-mono "
                + "whitespace-nowrap overflow-hidden "
                + (warn ? "text-warning" : "text-base-content/70"));
            row.getElement().setAttribute("data-testid",
                "live-worker-" + candidate.getWorkerIndex());
            if (warn) {
                row.getElement().setAttribute("data-health", "warning");
            }
            Span who = new Span(chipName(candidate.getWorkerIndex()));
            who.addClassName(warn ? "font-semibold" : "font-semibold text-base-content");
            row.getElement().appendChild(who.getElement());
            Span open = new Span(candidate.getOpenedAtMillis() > 0
                ? "working " + Elapsed.words(now - candidate.getOpenedAtMillis())
                : "working");
            row.getElement().appendChild(open.getElement());
            Span step = new Span(stepWords(candidate, now));
            step.addClassName("truncate opacity-90");
            row.getElement().appendChild(step.getElement());
            cell.add(row);
            String why = WorkerHealth.warning(health.get(i), candidate.getTokens(),
                candidate.getContextBudgetTokens(), candidate.getLastStepAtMillis(), now);
            if (!why.isEmpty()) {
                Div reason = new Div("↳ " + why);
                reason.addClassName("text-[11px] text-warning whitespace-normal pl-4");
                reason.getElement().setAttribute("data-testid",
                    "live-worker-warning-" + candidate.getWorkerIndex());
                cell.add(reason);
            }
            rows.add(cell);
        }
        box.add(rows);
        liveBand.add(box);
    }

    /**
     * What one worker is doing, and how long since it last did anything.
     *
     * <p>"last spoke 4m ago" is the whole point of the line: a worker mid-turn on a large model is
     * silent for minutes and healthy, and a worker silent for an hour is gone. The number is the
     * only thing that tells them apart, so it is always shown, even when the step itself is not
     * known yet.
     */
    private static String stepWords(GraphCandidateDto candidate, long now) {
        String kind = nz(candidate.getLastStepKind());
        if (kind.isEmpty() || candidate.getLastStepAtMillis() <= 0) {
            return "· starting up";
        }
        String label = nz(candidate.getLastStepLabel());
        String doing = switch (kind) {
            case "TOOL_CALL" -> label.isEmpty() ? "running a tool" : "running " + label;
            case "TOOL_RESULT" -> label.isEmpty() ? "reading a result" : "read " + label;
            case "LLM_RESPONSE" -> "thinking";
            case "NUDGE" -> "being steered";
            case "SESSION_OPENED" -> "reading its instructions";
            case "DONE" -> "wrapping up";
            case "KILLED" -> "being stopped";
            default -> label.isEmpty() ? kind.toLowerCase() : label;
        };
        return "· " + doing + ", last spoke "
            + Elapsed.words(now - candidate.getLastStepAtMillis()) + " ago";
    }

    private void drawTask(GraphTaskDto task, RunGraphDto graph, int x, int y, int width) {
        Element group = SvgCanvas.el("g");
        group.setAttribute("data-node", "task-" + nz(task.getTaskId()));
        group.appendChild(SvgCanvas.el("rect",
            "x", String.valueOf(x), "y", String.valueOf(y),
            "width", String.valueOf(width), "height", String.valueOf(TASK_H),
            "rx", "10", "fill", "rgba(100,116,139,0.08)",
            "stroke", "#64748b", "stroke-width", "1.5"));
        group.appendChild(text(x + 12, y + 22, truncate(nz(task.getTitle()), width / 8),
            12, "currentColor", "start"));
        group.appendChild(text(x + 12, y + 40, nz(task.getWriteSetCsv()).isEmpty()
            ? "(no write set)"
            : truncate(nz(task.getWriteSetCsv()), width / 7), 9, "#94a3b8", "start"));
        drawTestsLine(group, task, x, y, width);
        // The two phases a glance must not miss, said ABOVE the box rather than inside it: the
        // box has room for a cut title and a cut path and nothing else, and putting the words
        // over it costs no width at all. Every other phase stays on the hover, where a fact
        // nobody has to act on belongs.
        String phase = phaseCaption(nz(task.getState()));
        if (!phase.isEmpty()) {
            group.appendChild(text(x + 2, y - 7, phase, 9,
                "BLOCKED".equals(nz(task.getState())) ? "#ef4444" : REPAIR_COLOR, "start"));
        }
        onClick(group, () -> Inspector.open("Task — " + nz(task.getTitle()), taskInspector(task)));
        // The box shows a cut title and a cut path. What the task actually is goes on the hover.
        onHover(group, () -> fillTaskCard(task, graph));
        canvas.viewport().appendChild(group);
    }

    /** Teal: the colour of a test badge. Distinct from every worker-state colour on this graph. */
    private static final String TESTS_COLOR = "#14b8a6";

    /**
     * The third line of a task box: what the test author is doing, or did, for this task.
     *
     * <p>This is the line the owner asked for in as many words - "it would be cool to see the
     * actual tests appearing on the GUI as proof that something is happening". The test-authoring
     * stage takes minutes, works one task at a time, and used to draw nothing at all for the whole
     * of it, which is the same picture a hung stage draws. Now the task being authored says so,
     * and every task whose tests have landed wears a badge saying how many and how many of its
     * checks they prove; the badges lighting up one box at a time across the graph IS the proof.
     *
     * <p>Three things it can say, and one it deliberately does not:
     * <ul>
     *   <li>"writing its tests" - the author has this task now. Pulses, like a running chip.
     *   <li>a badge, "3 tests · 2 checks" - the tests are on disk. Click it to read them. Red with
     *       a "!" when a check points at a test nobody wrote, which is the finding that parks the
     *       run.
     *   <li>"no tests were written" - the author was handed checks and produced nothing, which the
     *       run log says too and the operator should not have to open it to learn.
     *   <li>NOTHING for a task that claims no checks - an enabler. That is not "no tests yet", and
     *       a "0 tests" badge on it would read as a failure of a stage that had nothing to do.
     * </ul>
     *
     * <p>The badge says what was WRITTEN. It says nothing about whether any worker will run these
     * tests, because that is not decided here.
     */
    private void drawTestsLine(Element group, GraphTaskDto task, int x, int y, int width) {
        String phase = nz(task.getTestsPhase());
        int baseline = y + TASK_H - 9;
        if (GraphTaskDto.TESTS_WRITING.equals(phase)) {
            Element words = text(x + 12, baseline, "writing its tests\u2026", 9, "#94a3b8", "start");
            words.setAttribute("data-tests", "writing");
            animateFade(words);
            group.appendChild(words);
            return;
        }
        if (!GraphTaskDto.TESTS_WRITTEN.equals(phase) || task.getChecksClaimed() <= 0) {
            return;
        }
        if (task.getTestsWritten() <= 0) {
            Element words = text(x + 12, baseline, "no tests were written", 9, "#94a3b8", "start");
            words.setAttribute("data-tests", "none-written");
            group.appendChild(words);
            return;
        }
        boolean trouble = task.getTestProblems() > 0;
        String label = badgeWords(task);
        // 9 px monospace runs at about 5.4 px a glyph; six leaves the words room to breathe.
        int pillWidth = Math.min(width - 16, label.length() * 6 + 14);
        String colour = trouble ? "#ef4444" : TESTS_COLOR;
        Element badge = SvgCanvas.el("g");
        badge.setAttribute("data-tests", "written");
        badge.setAttribute("data-tests-badge", nz(task.getTaskId()));
        badge.appendChild(SvgCanvas.el("rect",
            "x", String.valueOf(x + 8), "y", String.valueOf(baseline - 11),
            "width", String.valueOf(pillWidth), "height", "15", "rx", "7",
            "fill", trouble ? "rgba(239,68,68,0.12)" : "rgba(20,184,166,0.14)",
            "stroke", colour, "stroke-width", "1"));
        badge.appendChild(text(x + 8 + pillWidth / 2, baseline, label, 9, colour, "middle"));
        // Its own click, and not the box's: the box opens the task, the badge opens the tests.
        onClickOnly(badge, () -> openTests(task));
        group.appendChild(badge);
    }

    /** "3 tests · 2 checks" - with a "!" in front when a check names a test nobody wrote. */
    private static String badgeWords(GraphTaskDto task) {
        int tests = task.getTestsWritten();
        int checks = task.getChecksProved();
        return (task.getTestProblems() > 0 ? "! " : "")
            + tests + (tests == 1 ? " test" : " tests")
            + " \u00b7 " + checks + (checks == 1 ? " check" : " checks");
    }

    /**
     * The phase a task box says out loud, and "" for every phase it does not.
     *
     * <p>Two of them. A task being repaired and a task that has given up are the two the operator
     * has to see without hovering anything - the first because new workers appearing on a task
     * that looks fine reads as a restart, the second because nothing else on the graph says a task
     * has stopped. The other eight are ordinary progress and stay on the hover card; a box that
     * announces every phase is a log.
     */
    private static String phaseCaption(String state) {
        return "REPAIRING".equals(state) || "BLOCKED".equals(state)
            ? TaskState.labelOf(state) : "";
    }

    private static final int LOD_THRESHOLD = 24;

    private void drawCandidates(List<GraphCandidateDto> candidates, int x, int y, int blockWidth) {
        // LOD (design §6.3): a very wide fan collapses to a summary chip.
        if (candidates.size() > LOD_THRESHOLD) {
            int survived = 0;
            int failed = 0;
            int selected = 0;
            for (GraphCandidateDto candidate : candidates) {
                switch (stateAt(candidate)) {
                    case "SURVIVED" -> survived++;
                    case "SELECTED" -> selected++;
                    case "FAILED", "KILLED" -> failed++;
                    default -> { }
                }
            }
            Element group = SvgCanvas.el("g");
            group.setAttribute("data-node", "cand-summary");
            group.appendChild(SvgCanvas.el("rect",
                "x", String.valueOf(x), "y", String.valueOf(y),
                "width", "220", "height", String.valueOf(CAND_H),
                "rx", "7", "fill", "transparent", "stroke", "#94a3b8", "stroke-width", "1.5"));
            group.appendChild(text(x + 110, y + 21, candidates.size() + " candidates: "
                + selected + "♛ " + survived + "✓ " + failed + "✗", 11, "#94a3b8", "middle"));
            canvas.viewport().appendChild(group);
            return;
        }
        // Two waves, drawn as two bands. The repair round does not REPLACE the attempts that
        // failed - the operator asked for both at once, in those words: "can we keep the original
        // failed chips onscreen and then add the new ones in addition". So the first wave keeps
        // the rows it always had, and the repairs start on a row of their own underneath, behind
        // one word saying what they are. Without the break they are four more chips in the same
        // run of chips, which is precisely how w100 and w110 got read as a restart.
        List<GraphCandidateDto> firstWave = new ArrayList<>();
        List<GraphCandidateDto> repairs = new ArrayList<>();
        for (GraphCandidateDto candidate : candidates) {
            (RepairIndex.isRepair(candidate.getWorkerIndex()) ? repairs : firstWave).add(candidate);
        }
        int perRow = Math.max(1, blockWidth / (CAND_W + 8));
        int rowY = drawChipRows(firstWave, x, y, perRow);
        if (repairs.isEmpty()) {
            return;
        }
        // The caption is above its band rather than beside it: beside it costs the width of a
        // chip on every row, and at a narrow window that is the difference between four across
        // and three.
        // "the repairs", not "repairing what failed" again: the box overhead already says the task
        // is fixing what failed, and the same four words twice within an inch reads as a fault.
        Element caption = text(x, rowY + 11, "the repairs", 9, REPAIR_COLOR, "start");
        caption.setAttribute("data-repair-band", "yes");
        canvas.viewport().appendChild(caption);
        drawChipRows(repairs, x, rowY + 16, perRow);
    }

    /**
     * One band of chips, wrapped, and the y the next band starts at.
     *
     * <p>Wrapping rather than a row that grows: eight chips on one task is now ordinary - four
     * that failed and four repairing them - and a row of eight is 480&nbsp;px, which does not fit
     * beside a second task at any window width the Console is held to.
     */
    private int drawChipRows(List<GraphCandidateDto> candidates, int x, int y, int perRow) {
        for (int i = 0; i < candidates.size(); i++) {
            GraphCandidateDto candidate = candidates.get(i);
            int cx = x + (i % perRow) * (CAND_W + 8);
            int cy = y + (i / perRow) * (CAND_H + 8);
            try {
                drawCandidate(candidate, cx, cy);
            } catch (RuntimeException e) {
                ClientLog.warn("RunGraphView", "an attempt could not be drawn in run " + runId
                    + "; every other attempt still is: " + e);
                Element unknown = SvgCanvas.el("g");
                unknown.appendChild(SvgCanvas.el("rect",
                    "x", String.valueOf(cx), "y", String.valueOf(cy),
                    "width", String.valueOf(CAND_W), "height", String.valueOf(CAND_H),
                    "rx", "7", "fill", "transparent", "stroke", "#94a3b8",
                    "stroke-width", "1.5", "stroke-dasharray", "3 3"));
                unknown.appendChild(text(cx + CAND_W / 2, cy + 21, "?", 11, "#94a3b8", "middle"));
                canvas.viewport().appendChild(unknown);
            }
        }
        int rows = candidates.isEmpty() ? 0 : (candidates.size() + perRow - 1) / perRow;
        return y + rows * (CAND_H + 8);
    }

    /**
     * One attempt, drawn on its own so a fault in it cannot reach any other.
     *
     * <p>A chip that is still RUNNING shows its health instead of its temperature. Two numbers:
     * how many turns it has taken, and how big its conversation has grown. The second is the one
     * that matters - a worker is killed by a single request that cannot finish in the time it is
     * allowed, and what makes a request slow is the conversation it has to re-send, which nothing
     * trims. Turn count only correlates, because turns grow the conversation. The temperature it
     * was sampled at is a fact about how the attempt was STARTED and comes back the moment it
     * finishes; while it is running, nothing on the screen was saying whether to worry.
     *
     * <p>Orange is not decoration and must not be common: {@link WorkerHealth} decides it, from the
     * budget the worker's own model profile sets and from the timeout its request is running
     * against. A chip with no token count yet is drawn exactly as it was - a missing number costs
     * the numbers, never the chip.
     */
    private void drawCandidate(GraphCandidateDto candidate, int cx, int cy) {
        String state = nz(stateAt(candidate));
        boolean running = "RUNNING".equals(state);
        // Health is a fact about NOW, so it is not computed at a replay cursor in the past: a chip
        // being scrubbed through history shows the state it had, without a live warning on it.
        WorkerHealth.Kind health = running && timeline.cursor.get() == null
            ? healthOf(candidate) : WorkerHealth.Kind.UNKNOWN;
        boolean warn = WorkerHealth.isWarning(health);
        String color = warn ? WARNING_COLOR : candidateColor(state);
        Element group = SvgCanvas.el("g");
        group.setAttribute("data-node", "cand-" + candidate.getWorkerIndex());
        // Which task's fan this chip is in. The worker index alone is not unique across a graph -
        // every task has a w0 - so anything picking one chip out of a run needs both.
        group.setAttribute("data-task", nz(candidate.getTaskId()));
        if (warn) {
            // Readable by a test, and by anything else that wants to count the workers in trouble
            // without re-deciding what trouble is.
            group.setAttribute("data-health", "warning");
        }
        Element rect = SvgCanvas.el("rect",
            "x", String.valueOf(cx), "y", String.valueOf(cy),
            "width", String.valueOf(CAND_W), "height", String.valueOf(CAND_H),
            "rx", "7", "fill", warn ? "rgba(249,115,22,0.20)"
                : running ? "rgba(56,189,248,0.15)" : "transparent",
            "stroke", color,
            "stroke-width", warn || "SELECTED".equals(state) ? "2.5" : "1.5");
        if (running) {
            animatePulse(rect);
        }
        group.appendChild(rect);
        String label = chipName(candidate.getWorkerIndex())
            + (warn ? " !"
            : "SELECTED".equals(state) ? " ♛"
            : "SURVIVED".equals(state) ? " ✓"
            : "FAILED".equals(state) ? " ✗"
            : "KILLED".equals(state) ? " ☠"
            // Not a verdict, and drawn so it cannot be read as one: it has stopped, and the
            // checks have not spoken.
            : "FINISHED".equals(state) ? " …" : "");
        group.appendChild(text(cx + CAND_W / 2, cy + 15, label, 11, color, "middle"));
        group.appendChild(text(cx + CAND_W / 2, cy + 27, chipNumbers(candidate, running),
            8, warn ? WARNING_COLOR : "#94a3b8", "middle"));
        // The chip is narrow and the operator needs a sentence, so the sentence is the hover.
        // The band above the graph says the same thing without one.
        //
        // It used to be an SVG <title>, which is the OPERATING SYSTEM's tooltip: unstyled,
        // unstylable, half a second late, and belonging to no application. Same facts, drawn by
        // the Console instead.
        onHover(group, () -> fillCandidateCard(candidate, state, health));
        onClick(group, () -> openCandidate(candidate));
        canvas.viewport().appendChild(group);
    }

    /** Orange: heading for trouble but not yet in it. Distinct from every state colour below. */
    private static final String WARNING_COLOR = "#f97316";

    /** Violet: the repair band's caption, and the colour of a worker that has stopped unjudged. */
    private static final String REPAIR_COLOR = "#a78bfa";

    /**
     * What a chip calls its worker.
     *
     * <p>"w3" for a first-wave worker, and "\u21bbw2" for one repairing what w2 got wrong -
     * rather than "w120", which is what the number is and what nobody could read. The scheme lives
     * on {@link RepairIndex}, where the engine writes it, so the two ends cannot drift.
     */
    private static String chipName(int workerIndex) {
        return RepairIndex.isRepair(workerIndex)
            ? "\u21bbw" + RepairIndex.repairOf(workerIndex)
            : "w" + workerIndex;
    }

    /** This worker's health right now, measured against the clock the frame arrived with. */
    private WorkerHealth.Kind healthOf(GraphCandidateDto candidate) {
        return healthOf(candidate, frameNow());
    }

    /** The same decision, when the caller already holds the frame's clock. */
    private static WorkerHealth.Kind healthOf(GraphCandidateDto candidate, long now) {
        return WorkerHealth.of(candidate.getTokens(), candidate.getContextBudgetTokens(),
            nz(candidate.getLastStepKind()), candidate.getLastStepAtMillis(), now);
    }

    /**
     * The second line of a chip: what this worker has done, whenever there is anything to say.
     *
     * <p>A running worker with no token count yet keeps the temperature rather than showing a
     * blank or a zero. The count arrives on the worker's first answer, so this is what the first
     * seconds of every worker look like, and "0k" would be a false reading of a healthy start.
     *
     * <p>A worker that has STOPPED keeps those same numbers rather than reverting to the
     * temperature. The chip used to swap "21t 41k" for "t0.8" the moment a worker finished, so an
     * operator watching a number climb saw it replaced by an unrelated one - and next to a chip
     * that had also changed colour, that reads as a different worker rather than as the same one a
     * step further on. The temperature is on the hover card, where a fact about how the attempt was
     * STARTED belongs.
     *
     * <p><b>And how much of that conversation never changes.</b> "21t 8/41k": eight thousand of the
     * forty-one are the fixed head of the prompt - the project's rules, the task, the repository
     * map - which every worker on this task sends identically, so the model server reads it once
     * for all of them and no compaction ever rewrites it. The other thirty-three thousand are this
     * worker's own. That is the whole of "everything is slow" made visible, which is why it earned
     * the chip rather than the card alone; the words explaining which half is which are on the
     * card, because nine characters is all the chip has. See {@link WorkerHealth#splitWords}, and
     * note that a worker with no measurement - which is every worker that has already stopped -
     * gets exactly the two numbers it got before.
     */
    private static String chipNumbers(GraphCandidateDto candidate, boolean running) {
        if (candidate.getTokens() > 0) {
            return candidate.getTurns() + "t "
                + WorkerHealth.splitWords(candidate.getPrefillTokens(), candidate.getTokens());
        }
        return "t" + trim(candidate.getTemperature());
    }

    // --- the hover card --------------------------------------------------------------------------

    /** How far the card sits from the node it describes. */
    private static final int HOVER_GAP = 10;
    /** How close the card is ever allowed to get to the edge of the window. */
    private static final int HOVER_EDGE = 8;
    /** How many lines of a list a card shows before it says how many more there are. */
    private static final int HOVER_LINES = 3;

    /**
     * Puts the card under the pointer while it rests on this node, and takes it away again.
     *
     * <p>The card is filled first and placed second, because where it can go depends on how tall
     * the node made it.
     */
    private void onHover(Element node, Runnable fill) {
        EventTarget target = node.cast();
        target.addEventListener("mouseenter", event -> {
            try {
                fill.run();
                placeHoverCard(node);
            } catch (RuntimeException e) {
                // Same rule as the per-node draw guards above: one unreadable node costs its own
                // card and nothing else. A throw out of a DOM listener does not reach the effect
                // that drew the graph, so without this a half-filled card would stay on screen.
                hideHoverCard();
                ClientLog.warn("RunGraphView", "a hover card could not be built in run " + runId
                    + "; the graph is unaffected: " + e);
            }
        });
        target.addEventListener("mouseleave", event -> hideHoverCard());
    }

    private void hideHoverCard() {
        hoverCard.setVisible(false);
    }

    /**
     * Where the card goes: beside the node, and never off an edge.
     *
     * <p>Measured before it is placed. The card is shown at a position nobody can see, its real
     * size is read back, and only then is it put somewhere - because a card cannot be flipped away
     * from an edge until its height is known, and its height depends on how many rows the node
     * gave it.
     *
     * <p>It FLIPS rather than slides: below the node normally, above it when the bottom edge is in
     * the way; aligned with the node's left edge normally, with its right edge when the right edge
     * is in the way. The clamp afterwards is for the one case flipping cannot answer - a node with
     * no room on either side - and it is what makes "never overflows" true rather than nearly true.
     *
     * <h2>Which edge</h2>
     *
     * <p>THIS VIEW'S, not the window's, where the two differ. The run graph is normally opened as a
     * dialog over the pipeline board, and a dialog is a box with its overflow hidden: a card placed
     * inside the window but past the bottom of that box is not clipped by the window and IS clipped
     * by the box, so it loses its last line or two and nothing anywhere reports it. That is exactly
     * what happened - a task card lost the row saying how its attempts were going, while every
     * measurement against the window said it fitted, and only a screenshot showed it. The bounds
     * are therefore the view's own box narrowed to the window, which is right whether this is a
     * dialog over a board or a whole page.
     */
    private void placeHoverCard(Element node) {
        HTMLElement nodeElement = node.cast();
        hoverCard.setVisible(true);
        hoverCard.getElement().getStyle().setProperty("left", "-10000px");
        hoverCard.getElement().getStyle().setProperty("top", "0px");
        TextRectangle card = hoverCard.getElement().getBoundingClientRect();
        TextRectangle anchor = nodeElement.getBoundingClientRect();
        TextRectangle host = getElement().getBoundingClientRect();
        int width = card.getWidth();
        int height = card.getHeight();
        int minLeft = Math.max(host.getLeft(), 0) + HOVER_EDGE;
        int maxRight = Math.min(host.getRight(), Window.current().getInnerWidth()) - HOVER_EDGE;
        int minTop = Math.max(host.getTop(), 0) + HOVER_EDGE;
        int maxBottom = Math.min(host.getBottom(), Window.current().getInnerHeight()) - HOVER_EDGE;

        int top = anchor.getBottom() + HOVER_GAP;
        if (top + height > maxBottom) {
            top = anchor.getTop() - HOVER_GAP - height;
        }
        int left = anchor.getLeft();
        if (left + width > maxRight) {
            left = anchor.getRight() - width;
        }
        top = Math.max(minTop, Math.min(top, maxBottom - height));
        left = Math.max(minLeft, Math.min(left, maxRight - width));

        // Written relative to this view, which is the card's offset parent.
        hoverCard.getElement().getStyle().setProperty("left", (left - host.getLeft()) + "px");
        hoverCard.getElement().getStyle().setProperty("top", (top - host.getTop()) + "px");
    }

    /**
     * The detail behind a chip - where the numbers go that will not fit on the chip.
     *
     * <p>Every number the operator asked to be able to see is here, in the order it is worth
     * reading: what the worker is, how far in it is, how big its conversation has grown against
     * what it is allowed, how long since it last did anything, and - when it is orange - the one
     * sentence saying why. The words are unchanged from the tooltip this replaced; only the surface
     * they are drawn on is new.
     *
     * <p>A number that has not arrived costs its own line and nothing else. A worker in its first
     * seconds has no token count, and a row reading "0k" would be a false alarm about a healthy
     * start; an empty row would be worse still.
     */
    private void fillCandidateCard(GraphCandidateDto candidate, String state,
                                   WorkerHealth.Kind health) {
        Div head = startCard("worker");
        Span who = new Span(chipName(candidate.getWorkerIndex()));
        who.addClassName("font-mono text-[13px] font-semibold text-base-content");
        head.getElement().appendChild(who.getElement());
        String words = CandidateState.labelOf(state);
        if (!words.isEmpty()) {
            head.add(new StatusDot(state, words));
            Span label = new Span(words);
            label.addClassName("text-base-content/60");
            head.getElement().appendChild(label.getElement());
        }
        long now = frameNow();
        Div rows = cardRows();
        // First, when it is one: a repair worker is not a fifth attempt at the task, it is a
        // second go at ONE attempt that failed, and reading it as a restart is what the operator
        // actually did.
        cardRow(rows, "repairing", repairWords(candidate.getWorkerIndex()));
        if ("RUNNING".equals(state)) {
            cardRow(rows, "working for", candidate.getOpenedAtMillis() > 0
                ? Elapsed.words(now - candidate.getOpenedAtMillis()) : "");
            cardRow(rows, "turns taken", String.valueOf(candidate.getTurns()));
            cardRow(rows, "conversation", conversationWords(candidate));
            // The split the chip shows as "8/41k", in words, with which part is which. Two rows
            // and not one, because "8k of 41k" leaves the operator to do the subtraction that is
            // the whole point of the number.
            cardRow(rows, "fixed start", prefillWords(candidate));
            cardRow(rows, "its own", ownWords(candidate));
            cardRow(rows, "last spoke", candidate.getLastStepAtMillis() > 0
                ? Elapsed.words(now - candidate.getLastStepAtMillis()) + " ago" : "");
        } else {
            // What it DID, once it has stopped doing it. A chip that changes state must not read
            // as a different worker: the turns and the size of the conversation are the numbers
            // the operator was watching a moment ago, and they stay on the card afterwards.
            cardRow(rows, "turns taken", candidate.getTurns() > 0
                ? String.valueOf(candidate.getTurns()) : "");
            cardRow(rows, "conversation", candidate.getTokens() > 0
                ? WorkerHealth.tokensWords(candidate.getTokens()) + " tokens" : "");
            cardRow(rows, "sampled at", "temperature " + trim(candidate.getTemperature()));
            cardRow(rows, "model", nz(candidate.getModel()));
            cardRow(rows, "judge score", candidate.getJudgeScore() < 0
                ? "" : String.valueOf(candidate.getJudgeScore()));
        }
        if (rows.getElement().getFirstChild() != null) {
            ruleUnderHeading(head);
            hoverCard.add(rows);
        }
        // What the two rows above actually mean. The chip can only carry "8/41k"; this is the one
        // place with room to say why an operator should care which way that number goes.
        if (WorkerHealth.hasPrefill(candidate.getPrefillTokens(), candidate.getTokens())) {
            hoverCard.add(cardHint("The fixed start is its task and the project's rules. Every"
                + " worker on this task sends the same one, so the model server reads it once for"
                + " all of them. The rest is this worker's own."));
        }

        // Why it went wrong, on the card, for the case that has needed it most. A stopped or
        // failed attempt used to be a coloured square and nothing else.
        // Before the fail reason, and shown whatever the outcome: a SURVIVED candidate that went
        // outside its paths is exactly the case the operator needs this for, and a candidate that
        // also died still tells them how far it had got.
        addWriteSetNote(candidate, head);
        addDocsDeadEndNote(candidate, head);
        addHelpNote(candidate, head);
        String reason = nz(candidate.getFailReason());
        if (!reason.isEmpty()) {
            ruleUnderHeading(head);
            hoverCard.add(cardNote("warning", "text-error", capitalise(reason) + "."));
            return;
        }
        String why = WorkerHealth.warning(health, candidate.getTokens(),
            candidate.getContextBudgetTokens(), candidate.getLastStepAtMillis(), now);
        if (!why.isEmpty()) {
            ruleUnderHeading(head);
            hoverCard.add(cardNote("bolt", "text-warning",
                "Heading for trouble: " + why + "."));
        }
    }

    /**
     * The note saying this attempt changed files its task was not given.
     *
     * <p>It is a warning and not an error, which is the whole change it reports: a worker that
     * writes outside its slice is no longer killed for it, because the slice is decided from a plan
     * before any code exists and is routinely wrong about which files the work really spans. So the
     * attempt is still usable and the operator still has to know - one neighbouring file to make
     * the code compile is fine, a scattering of them is not, and the card names them so the
     * difference can be seen without opening the diff.
     */
    private void addWriteSetNote(GraphCandidateDto candidate, Div head) {
        String paths = nz(candidate.getOutOfWriteSetPaths());
        if (paths.isEmpty()) {
            return;
        }
        int count = paths.split(",").length;
        ruleUnderHeading(head);
        hoverCard.add(cardNote("warning", "text-warning",
            "Changed " + count + " file(s) its task was not given: " + paths + "."));
    }

    /**
     * The note saying WHY a {@code DOCS_DEAD_END} attempt stopped: every question it asked
     * {@code lookup_api} and the section each one answered with, so the operator can see the
     * documentation search failing on the card itself, without opening a log that a finished run's
     * temp store has usually already deleted.
     */
    private void addDocsDeadEndNote(GraphCandidateDto candidate, Div head) {
        String evidence = nz(candidate.getDocsDeadEndEvidence());
        if (evidence.isEmpty()) {
            return;
        }
        ruleUnderHeading(head);
        hoverCard.add(cardNote("warning", "text-warning",
            "The documentation search answered these differently: "
                + evidence.replace("\n", "  ") + "."));
    }

    /**
     * The note saying this attempt asked the help desk, and how hard the expert worked on it.
     *
     * <p>Not a warning, and drawn as a plain hint rather than a tinted note for exactly that
     * reason. Asking is the behaviour this system wants: the measured alternative is a worker that
     * spends sixty-six shell commands disassembling the framework's jars and delivers nothing. What
     * the operator needs off the card is the LOOKUP count - the expert is an agent session with
     * read-only tools over this codebase, so an answer either was read out of real files or was
     * recalled by a model, and a card that shows seven lookups is telling them which.
     */
    private void addHelpNote(GraphCandidateDto candidate, Div head) {
        String summary = nz(candidate.getHelpSummary());
        if (summary.isEmpty()) {
            return;
        }
        ruleUnderHeading(head);
        hoverCard.add(cardHint("It " + summary + " - asking is right, not a weakness."));
    }

    /**
     * A sentence that was written to sit mid-line, given a line of its own.
     *
     * <p>The wording itself comes off the frame - {@code KillReason} carries a sentence for every
     * way a worker is stopped, and the verification stage records its own verdict on the report -
     * and is worked out once, on the server. Nothing here re-words it: a second wording of the same
     * death is how two screens come to disagree about it.
     */
    private static String capitalise(String words) {
        return words.isEmpty() ? words
            : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * A quiet sentence at the foot of a card: what a row above it MEANS.
     *
     * <p>Deliberately not {@link #cardNote}, which is tinted and carries an icon because it always
     * says something is wrong. An explanation drawn that way would read as a warning on every
     * healthy worker, and a card where everything is loud says nothing. So: no icon, no tint,
     * smaller, and the same muted grey the row labels use.
     */
    private static Div cardHint(String words) {
        Div hint = new Div(words);
        hint.addClassName("mt-2.5 text-[11px] leading-snug text-base-content/45 break-words");
        return hint;
    }

    /** A tinted strip at the foot of a card: the one sentence that says what to do about it. */
    private static Div cardNote(String icon, String tone, String words) {
        Div note = new Div();
        note.addClassName("mt-2.5 flex items-start gap-1.5 "
            + "font-medium " + tone);
        note.add(Icon.of(icon, "w-3.5 h-3.5 shrink-0 mt-px"));
        Span text = new Span(words);
        text.addClassName("min-w-0 break-words");
        note.getElement().appendChild(text.getElement());
        return note;
    }

    /** What a repair worker is fixing, and which go this is; nothing for a first-wave worker. */
    private static String repairWords(int workerIndex) {
        if (!RepairIndex.isRepair(workerIndex)) {
            return "";
        }
        return "what w" + RepairIndex.repairOf(workerIndex) + " got wrong, try "
            + (RepairIndex.attempt(workerIndex) + 1);
    }

    /**
     * The fixed head of this worker's prompt - the part that is prefilled once and never rewritten.
     *
     * <p>Says "(estimated)" because it is: there is no tokenizer on this side of the wire, so it is
     * characters over four, the same crude ratio the compaction scheme decides by. The count beside
     * it on the "conversation" row is the model server's own, so the two are not measured the same
     * way and the card must not pretend they are. This codebase already labels estimated token
     * counts that way in the compaction line an operator reads in the run log.
     *
     * <p>Nothing at all when there is no measurement, which is every worker that has stopped: an
     * absent row costs the row and nothing else.
     */
    private static String prefillWords(GraphCandidateDto candidate) {
        if (!WorkerHealth.hasPrefill(candidate.getPrefillTokens(), candidate.getTokens())) {
            return "";
        }
        return WorkerHealth.tokensWords(candidate.getPrefillTokens()) + " tokens (estimated)";
    }

    /** Everything after that head: this worker's own conversation, and nobody else's. */
    private static String ownWords(GraphCandidateDto candidate) {
        if (!WorkerHealth.hasPrefill(candidate.getPrefillTokens(), candidate.getTokens())) {
            return "";
        }
        return WorkerHealth.tokensWords(candidate.getTokens() - candidate.getPrefillTokens())
            + " tokens";
    }

    /** How big this worker's conversation has grown, against what it is allowed to grow to. */
    private static String conversationWords(GraphCandidateDto candidate) {
        if (candidate.getTokens() <= 0) {
            return "not counted yet";
        }
        String grown = WorkerHealth.tokensWords(candidate.getTokens()) + " tokens";
        return candidate.getContextBudgetTokens() > 0
            ? grown + " of the " + WorkerHealth.tokensWords(candidate.getContextBudgetTokens())
                + " it is allowed"
            : grown;
    }

    /**
     * What a task box actually is - the thing the box itself has room for about a third of.
     *
     * <p>A task box draws a title cut at roughly twenty characters and a file path cut at about
     * twenty-five, and nothing else. Every line below is either already on the frame or worked out
     * from it, so a hover costs no request:
     *
     * <ul>
     *   <li><b>the whole title</b>, and the state in the operator's words rather than the enum's;
     *   <li><b>the story</b> it belongs to - the work item a person recognises, where the title is
     *       written for a worker. This is the one thing that was not on the frame and is now;
     *   <li><b>what it answers to</b>: the planner's requirement handles WITH the requirement's own
     *       words, which the frame already carries for the plan view;
     *   <li><b>how many checks</b> have to pass;
     *   <li><b>which files</b> it may change;
     *   <li><b>what it waits for</b>, by title - the graph draws those edges, but the boxes they
     *       come from have cut titles, so the edge does not say what it came from;
     *   <li><b>how the attempts went</b>, counted off the chips under the box.
     * </ul>
     *
     * <p>What is deliberately NOT here: the text of each acceptance check, and the tasks that wait
     * on this one. The first would double the height of the card for wording the requirement line
     * above it already carries; the second is the same edges read the other way round. A hover card
     * that needs scrolling is a panel, and this task already opens one on click.
     */
    private void fillTaskCard(GraphTaskDto task, RunGraphDto graph) {
        Div head = startCard("task");
        head.removeClassName("items-center");
        head.addClassName("flex-col items-start gap-1");
        Span title = new Span(nz(task.getTitle()).isEmpty() ? "untitled task" : nz(task.getTitle()));
        title.addClassName("text-[13px] font-semibold leading-snug break-words");
        head.getElement().appendChild(title.getElement());
        String stateWords = TaskState.labelOf(nz(task.getState()));
        if (!stateWords.isEmpty()) {
            Div line = new Div();
            line.addClassName("flex items-center gap-1.5 text-base-content/60");
            line.add(new StatusDot(nz(task.getState()), stateWords));
            Span label = new Span(stateWords);
            line.getElement().appendChild(label.getElement());
            head.add(line);
        }

        Div rows = cardRows();
        cardRow(rows, "story", storyWords(task));
        addRequirementLines(rows, task, graph);
        cardRow(rows, "checks", task.getCriteria() <= 0 ? "" : task.getCriteria()
            + (task.getCriteria() == 1 ? " check must pass" : " checks must pass"));
        cardRow(rows, "tests", testsWords(task));
        cardLines(rows, "files", split(task.getWriteSetCsv()), true);
        cardLines(rows, "waits for", dependencyTitles(task, graph), false);
        cardRow(rows, "attempts", attemptWords(task, graph));
        if (rows.getElement().getFirstChild() != null) {
            ruleUnderHeading(head);
            hoverCard.add(rows);
        }
    }

    /**
     * What the test author did for this task, in a sentence - and nothing for a task that claims
     * no checks, which is the same rule the box's third line follows.
     */
    private static String testsWords(GraphTaskDto task) {
        String phase = nz(task.getTestsPhase());
        if (GraphTaskDto.TESTS_WRITING.equals(phase)) {
            return "being written right now";
        }
        if (!GraphTaskDto.TESTS_WRITTEN.equals(phase) || task.getChecksClaimed() <= 0) {
            return "";
        }
        if (task.getTestsWritten() <= 0) {
            return "none were written, though it has "
                + task.getChecksClaimed() + (task.getChecksClaimed() == 1 ? " check" : " checks");
        }
        String written = task.getTestsWritten() + " written, proving "
            + task.getChecksProved() + " of its " + task.getChecksClaimed()
            + (task.getChecksClaimed() == 1 ? " check" : " checks");
        if (task.getTestProblems() > 0) {
            written += "; " + task.getTestProblems()
                + (task.getTestProblems() == 1 ? " check names" : " checks name")
                + " a test nobody wrote";
        }
        return written + " \u00b7 click the badge to read them";
    }

    /** The story a task belongs to, or nothing at all when it belongs to none. */
    private static String storyWords(GraphTaskDto task) {
        String key = nz(task.getStoryKey());
        String title = nz(task.getStoryTitle());
        if (key.isEmpty() && title.isEmpty()) {
            return "";
        }
        if (title.isEmpty()) {
            return key;
        }
        return key.isEmpty() ? title : key + " \u00b7 " + title;
    }

    /**
     * What this task is answering to: the handle the planner gave a requirement, and the
     * requirement's own words beside it.
     *
     * <p>The handle alone is what the plan view shows, and a handle on its own is a label rather
     * than an answer - "R4" tells a person nothing they did not already know.
     */
    private void addRequirementLines(Div rows, GraphTaskDto task, RunGraphDto graph) {
        List<GraphRequirementDto> found = new ArrayList<>();
        for (String id : split(task.getRequirementIdsCsv())) {
            for (GraphRequirementDto requirement : graph.getRequirements()) {
                if (id.equals(nz(requirement.getRequirementId()))) {
                    found.add(requirement);
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        Div box = new Div();
        box.addClassName("flex flex-col gap-1 min-w-0");
        int shown = Math.min(found.size(), 2);
        for (int i = 0; i < shown; i++) {
            GraphRequirementDto requirement = found.get(i);
            Div line = new Div();
            line.addClassName("flex items-start gap-1.5 min-w-0");
            Span handle = new Span(nz(requirement.getHandle()).isEmpty()
                ? "req" : nz(requirement.getHandle()));
            handle.addClassName("shrink-0 font-mono text-[10px] font-bold text-primary "
                + "bg-primary/10 rounded px-1.5 py-0.5");
            line.getElement().appendChild(handle.getElement());
            Span text = new Span(shorten(nz(requirement.getText()), 110));
            text.addClassName("min-w-0 break-words text-base-content/90");
            line.getElement().appendChild(text.getElement());
            box.add(line);
        }
        addMore(box, found.size() - shown);
        rows.getElement().appendChild(cardLabel("answers").getElement());
        rows.add(box);
    }

    /** The titles of the tasks this one is waiting for - the edges, said in words. */
    private static List<String> dependencyTitles(GraphTaskDto task, RunGraphDto graph) {
        List<String> titles = new ArrayList<>();
        for (String id : split(task.getDependsOnCsv())) {
            for (GraphTaskDto other : graph.getTasks()) {
                if (id.equals(nz(other.getTaskId()))) {
                    titles.add(nz(other.getTitle()).isEmpty() ? "untitled task" : other.getTitle());
                    break;
                }
            }
        }
        return titles;
    }

    /** How the attempts on this task went, counted off the chips drawn under its box. */
    private static String attemptWords(GraphTaskDto task, RunGraphDto graph) {
        List<GraphCandidateDto> candidates = candidatesOf(graph, nz(task.getTaskId()));
        if (candidates.isEmpty()) {
            return "";
        }
        int running = 0;
        int finished = 0;
        int chosen = 0;
        int passed = 0;
        int gone = 0;
        for (GraphCandidateDto candidate : candidates) {
            switch (nz(candidate.getState())) {
                case "RUNNING" -> running++;
                case "FINISHED" -> finished++;
                case "SELECTED" -> chosen++;
                case "SURVIVED" -> passed++;
                case "FAILED", "KILLED" -> gone++;
                default -> { }
            }
        }
        StringBuilder how = new StringBuilder();
        appendTally(how, running, "still writing");
        appendTally(how, finished, "being checked");
        appendTally(how, chosen, "chosen");
        appendTally(how, passed, "passed");
        appendTally(how, gone, "did not finish");
        String total = candidates.size() + (candidates.size() == 1 ? " worker" : " workers");
        return how.length() == 0 ? total : total + " \u2014 " + how;
    }

    private static void appendTally(StringBuilder how, int count, String words) {
        if (count <= 0) {
            return;
        }
        if (how.length() > 0) {
            how.append(", ");
        }
        how.append(count).append(' ').append(words);
    }

    // --- card furniture --------------------------------------------------------------------------

    /**
     * Empties the card and gives back its heading row, ready for whatever this node is.
     *
     * <p>Emptying is what stops one card describing two nodes: the pointer crosses a fan of chips
     * faster than a card could be torn down and rebuilt per node.
     */
    private Div startCard(String kind) {
        hoverCard.removeAll();
        hoverCard.getElement().setAttribute("data-hover", kind);
        Div head = new Div();
        head.addClassName("flex items-center gap-2");
        hoverCard.add(head);
        return head;
    }

    /**
     * The rule under a card's heading - drawn only when something follows it.
     *
     * <p>A task with no story, no requirement, no checks and no files gets a card of two lines,
     * and an unconditional rule left it with a line ruled under the last thing on the card and
     * empty space beneath. A divider that divides nothing reads as content that failed to load.
     */
    private static void ruleUnderHeading(Div head) {
        head.addClassName("pb-1.5 mb-2 border-b border-base-300");
    }

    /** The two-column body of a card: a quiet label, and the fact beside it. */
    private static Div cardRows() {
        Div rows = new Div();
        rows.addClassName("grid grid-cols-[5.25rem_1fr] gap-x-2.5 gap-y-1.5 items-baseline");
        return rows;
    }

    private static Span cardLabel(String label) {
        Span key = new Span(label);
        key.addClassName("text-right text-base-content/45");
        return key;
    }

    /** One fact on a card - and no row at all when the fact is missing. */
    private static void cardRow(Div rows, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        rows.getElement().appendChild(cardLabel(label).getElement());
        Span text = new Span(value);
        text.addClassName("min-w-0 break-words text-base-content/90");
        rows.getElement().appendChild(text.getElement());
    }

    /** A short list of things on one card row, capped, with a count of what was left off. */
    private static void cardLines(Div rows, String label, List<String> values, boolean mono) {
        if (values.isEmpty()) {
            return;
        }
        Div box = new Div();
        box.addClassName("flex flex-col gap-0.5 min-w-0");
        int shown = Math.min(values.size(), HOVER_LINES);
        for (int i = 0; i < shown; i++) {
            Div line = new Div(values.get(i));
            line.addClassName("min-w-0 break-words text-base-content/90"
                + (mono ? " font-mono text-[10px]" : ""));
            box.add(line);
        }
        addMore(box, values.size() - shown);
        rows.getElement().appendChild(cardLabel(label).getElement());
        rows.add(box);
    }

    private static void addMore(Div box, int more) {
        if (more <= 0) {
            return;
        }
        Div note = new Div("+" + more + " more");
        note.addClassName("text-base-content/45");
        box.add(note);
    }

    /** A CSV field as a list, with nothing in it when the field is empty. */
    private static List<String> split(String csv) {
        List<String> values = new ArrayList<>();
        for (String value : nz(csv).split(",")) {
            if (!value.trim().isEmpty()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    /** Requirement wording, cut at a length a card can hold rather than at whatever it arrived. */
    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1).trim() + "\u2026";
    }

    /**
     * The clock every duration on this screen is measured against.
     *
     * <p>The server's, which travels with the frame. Two machines' clocks are minutes apart often
     * enough, and that error lands squarely on the number an operator uses to decide a worker is
     * dead. Falls back to the browser's own only when a frame arrived without one.
     */
    private long frameNow() {
        RunGraphDto graph = GraphStore.graph(runId).get();
        return graph != null && graph.getAtMillis() > 0
            ? graph.getAtMillis() : System.currentTimeMillis();
    }

    // --- inspector levels ------------------------------------------------------------------------

    private void openCandidate(GraphCandidateDto candidate) {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-3 p-4");
        // Every value here is drawn through CopyValue rather than through the grid's own string
        // row. The framework puts a copy button beside a string row and that button does nothing
        // — it defers its work off the browser's user gesture — so a plain row here would be a
        // panel of dead buttons over the one id anybody ever wants out of this screen.
        PropertyGrid grid = new PropertyGrid()
            .row("state", stateRow(candidate.getState()))
            .row("worker", new CopyValue("worker", "w" + candidate.getWorkerIndex()))
            .row("model", new CopyValue("model", candidate.getModel()))
            .row("temperature", new CopyValue("temperature",
                String.valueOf(candidate.getTemperature())))
            // The budget beside the count, when there is one. A bare token total is a number
            // nobody can act on: the same 42,000 is comfortable for a worker allowed 262k and is
            // trouble for one allowed 32k. It is a live fact, so a finished attempt shows the
            // count alone rather than a stale ceiling.
            .row("turns / tokens", new CopyValue("turns and tokens",
                candidate.getTurns() + " / " + candidate.getTokens()
                + (candidate.getContextBudgetTokens() > 0
                    ? " of " + candidate.getContextBudgetTokens() + " allowed" : "")))
            .row("judge score", new CopyValue("judge score", candidate.getJudgeScore() < 0
                ? "" : String.valueOf(candidate.getJudgeScore())))
            .row("cluster", new CopyValue("cluster", candidate.getClusterHash().isEmpty() ? ""
                : candidate.getClusterHash().substring(0, Math.min(12, candidate.getClusterHash().length()))))
            .row("verified", new CopyValue("verified",
                candidate.isVerified() ? (candidate.isCompiles() ? "green" : "red") : ""))
            .row("kill reason", new CopyValue("kill reason", candidate.getKillReason().isEmpty()
                ? "" : candidate.getKillReason()))
            .row("candidate id", new CopyValue("candidate id", candidate.getCandidateId()));
        panel.add(grid);
        if (!candidate.getSessionId().isEmpty()) {
            Button transcript = new Button("Open session transcript");
            transcript.addClassName("btn-primary btn-sm");
            transcript.addClickListener(e -> Inspector.push(
                "Transcript w" + candidate.getWorkerIndex(),
                new TranscriptPane(candidate.getSessionId())));
            panel.add(transcript);
        } else {
            Div note = new Div("No persisted session for this candidate.");
            TextStyle.CAPTION.applyTo(note);
            panel.add(note);
        }
        Inspector.open("Candidate w" + candidate.getWorkerIndex(), panel);
    }

    /**
     * The tests behind a badge, in the drill-down.
     *
     * <p>The drill-down and not a panel inside the graph: opened from inside the run-graph dialog
     * it is drawn as a second dialog over the first ({@link Inspector}), which is the idiom this
     * console already uses for a candidate clicked on the same graph, and is the only surface that
     * cannot be clipped by the run-graph dialog's box - a panel INSIDE that box is cut at 70vh,
     * and a test file is longer than that. It is also the surface with a scrollbar of its own.
     *
     * <p>The names and the checks come off the frame the badge was drawn from; only the source is
     * fetched, one call per file, now.
     */
    private void openTests(GraphTaskDto task) {
        Inspector.open("Tests \u2014 " + nz(task.getTitle()), testsPanel(task));
    }

    private Div testsPanel(GraphTaskDto task) {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-4 p-4 text-sm");
        panel.getElement().setAttribute("data-testid", "task-tests");

        int tests = task.getTestsWritten();
        int proved = task.getChecksProved();
        int claimed = task.getChecksClaimed();
        Div lead = new Div(tests + (tests == 1 ? " test was" : " tests were")
            + " written for this task. "
            + (proved == 1 ? "It proves" : "They prove") + " " + proved + " of its "
            + claimed + (claimed == 1 ? " check." : " checks."));
        lead.addClassName("font-semibold");
        panel.add(lead);
        panel.add(cardHint("Written at the test-writing stage and saved in the project's "
            + "folder. A check counts as proved when a test with exactly the name the check asks "
            + "for exists. This shows what was written; it does not say whether the tests have "
            + "been run."));
        if (task.getTestProblems() > 0) {
            panel.add(cardNote("warning", "text-error", task.getTestProblems()
                + (task.getTestProblems() == 1 ? " check points" : " checks point")
                + " at a test nobody wrote, so the run has stopped and is waiting for a "
                + "decision. The details are on the run's question."));
        }

        Div list = new Div();
        list.addClassName("flex flex-col gap-2.5");
        for (GraphTestDto test : task.getTests()) {
            Div row = new Div();
            row.addClassName("flex flex-col gap-0.5 min-w-0");
            row.getElement().setAttribute("data-test-ref", nz(test.getTestRef()));
            Div name = new Div(nz(test.getTestRef()));
            name.addClassName("font-mono text-[12px] font-semibold break-all");
            row.add(name);
            Div proves = new Div();
            proves.addClassName("flex items-start gap-1.5 min-w-0 text-[12px]");
            if (nz(test.getProvesRef()).isEmpty()) {
                Span none = new Span("no check asks for this test");
                none.addClassName("text-base-content/45");
                proves.getElement().appendChild(none.getElement());
            } else {
                Span label = new Span("proves");
                label.addClassName("shrink-0 text-base-content/45");
                proves.getElement().appendChild(label.getElement());
                Span handle = new Span(nz(test.getProvesRef()));
                handle.addClassName("shrink-0 font-mono text-[10px] font-bold text-primary "
                    + "bg-primary/10 rounded px-1.5 py-0.5");
                proves.getElement().appendChild(handle.getElement());
                Span words = new Span(nz(test.getProvesText()));
                words.addClassName("min-w-0 break-words text-base-content/90");
                proves.getElement().appendChild(words.getElement());
            }
            row.add(proves);
            list.add(row);
        }
        panel.add(list);

        for (String path : split(task.getTestFilesCsv())) {
            Div file = new Div();
            file.addClassName("flex flex-col gap-1.5 min-w-0");
            Div heading = new Div(path);
            heading.addClassName("font-mono text-[11px] text-base-content/60 break-all");
            file.add(heading);
            Div source = new Div();
            // whitespace-pre and its own sideways scroll: source is not prose, and a line that is
            // wider than the dialog scrolls inside this box rather than wrapping into nonsense or
            // pushing the dialog wider.
            source.addClassName("font-mono text-[11px] leading-snug whitespace-pre "
                + "overflow-x-auto bg-base-200 rounded-lg p-3");
            source.getElement().setAttribute("data-test-source", path);
            try {
                source.setText(new GraphService_Stub().testSource(runId, nz(task.getTaskId()), path));
            } catch (Exception ex) {
                source.setText("The source could not be loaded: " + ex.getMessage());
                ClientLog.warn("RunGraphView", "the source of " + path + " for run " + runId
                    + " could not be fetched: " + ex);
            }
            file.add(source);
            panel.add(file);
        }
        return panel;
    }

    private static Div taskInspector(GraphTaskDto task) {
        Div panel = new Div();
        panel.addClassName("p-4");
        panel.add(new PropertyGrid()
            .row("title", new CopyValue("title", nz(task.getTitle())))
            .row("state", new CopyValue("state", nz(task.getState())))
            .row("write set", new CopyValue("write set", nz(task.getWriteSetCsv())))
            .row("criteria", new CopyValue("criteria", String.valueOf(task.getCriteria())))
            .row("task id", new CopyValue("task id", nz(task.getTaskId()))));
        return panel;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static List<GraphCandidateDto> candidatesOf(RunGraphDto graph, String taskId) {
        List<GraphCandidateDto> result = new ArrayList<>();
        for (GraphCandidateDto candidate : graph.getCandidates()) {
            if (nz(taskId).equals(nz(candidate.getTaskId()))) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * How much room this layer's fans need - both bands of them.
     *
     * <p>It counts the two waves separately because they are drawn separately, plus the caption
     * between them. Counting them together under-measured every repaired task by a row, and a
     * layer that is measured short is a layer the one below it is drawn on top of.
     */
    private static int candidateRowsHeight(RunGraphDto graph, List<GraphTaskDto> layer) {
        int max = 0;
        for (GraphTaskDto task : layer) {
            List<GraphCandidateDto> candidates = candidatesOf(graph, nz(task.getTaskId()));
            if (candidates.size() > LOD_THRESHOLD) {
                max = Math.max(max, CAND_H + 8);
                continue;
            }
            int repairs = 0;
            for (GraphCandidateDto candidate : candidates) {
                if (RepairIndex.isRepair(candidate.getWorkerIndex())) {
                    repairs++;
                }
            }
            int first = candidates.size() - repairs;
            int height = ((first + 4) / 5) * (CAND_H + 8);
            if (repairs > 0) {
                height += 16 + ((repairs + 4) / 5) * (CAND_H + 8);
            }
            max = Math.max(max, height);
        }
        return max;
    }

    /** Kahn layering over dependsOnCsv — same shape the engine executes in. */
    private static List<List<GraphTaskDto>> layers(List<GraphTaskDto> tasks) {
        Map<String, GraphTaskDto> byId = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (GraphTaskDto task : tasks) {
            byId.put(nz(task.getTaskId()), task);
            inDegree.put(nz(task.getTaskId()),
                nz(task.getDependsOnCsv()).isEmpty()
                    ? 0 : nz(task.getDependsOnCsv()).split(",").length);
        }
        List<List<GraphTaskDto>> layers = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        while (emitted.size() < byId.size()) {
            List<GraphTaskDto> layer = new ArrayList<>();
            for (GraphTaskDto task : byId.values()) {
                if (emitted.contains(nz(task.getTaskId()))) {
                    continue;
                }
                boolean ready = true;
                if (!nz(task.getDependsOnCsv()).isEmpty()) {
                    for (String dep : nz(task.getDependsOnCsv()).split(",")) {
                        if (byId.containsKey(dep) && !emitted.contains(dep)) {
                            ready = false;
                            break;
                        }
                    }
                }
                if (ready) {
                    layer.add(task);
                }
            }
            if (layer.isEmpty()) {
                layer.addAll(byId.values().stream()
                    .filter(t -> !emitted.contains(nz(t.getTaskId()))).toList());
            }
            layer.forEach(t -> emitted.add(nz(t.getTaskId())));
            layers.add(layer);
        }
        return layers;
    }

    private static int stageIndex(String state) {
        for (int i = 0; i < STAGES.length; i++) {
            if (STAGES[i].equals(state)) {
                return i;
            }
        }
        return "DELIVERED".equals(state) ? STAGES.length : 0;
    }

    /**
     * The coloured dot for an attempt, with the words beside it rather than only behind a hover.
     *
     * <p>The row used to be the dot alone, so its only reading was the tooltip — and the tooltip
     * was the constant, {@code SELECTED} or {@code KILLED}. Both halves are fixed here: the dot
     * carries the operator's words for the hover and for assistive technology, and the same words
     * sit on the row, where a person reading the panel will actually see them.
     */
    private static Div stateRow(String state) {
        Div row = new Div();
        row.addClassName("flex items-center gap-2");
        row.add(new StatusDot(state, CandidateState.labelOf(state)));
        Span words = new Span(CandidateState.labelOf(state));
        row.getElement().appendChild(words.getElement());
        return row;
    }

    private static String candidateColor(String state) {
        return switch (state) {
            case "RUNNING" -> "#38bdf8";
            // Violet: done writing, nothing has judged it. It must not be green (that would claim
            // the checks passed) and must not be blue (that would claim it is still working).
            case "FINISHED" -> "#a78bfa";
            case "SURVIVED" -> "#22c55e";
            case "SELECTED" -> "#eab308";
            // Red, and not the amber it used to be: amber sat one shade from the orange that
            // means "heading for trouble", so the one state that has already gone wrong looked
            // like the one that might. "If they fail they should turn red" - his words.
            case "FAILED" -> "#ef4444";
            case "KILLED" -> "#f43f5e";
            case "SUPERSEDED" -> "#64748b";
            default -> "#94a3b8";
        };
    }

    private Element text(int x, int y, String content, int size, String fill, String anchor) {
        Element t = SvgCanvas.el("text",
            "x", String.valueOf(x), "y", String.valueOf(y),
            "text-anchor", anchor, "fill", fill, "font-size", String.valueOf(size),
            "font-family", "ui-monospace, monospace");
        t.appendChild(Window.current().getDocument().createTextNode(content));
        return t;
    }

    private static void animatePulse(Element rect) {
        Element animate = SvgCanvas.el("animate",
            "attributeName", "stroke-opacity", "values", "1;0.35;1",
            "dur", "1.6s", "repeatCount", "indefinite");
        rect.appendChild(animate);
    }

    private static void onClick(Element element, Runnable action) {
        EventTarget target = element.cast();
        target.addEventListener("click", e -> action.run());
        // A pointer cursor on hover — SVG elements accept CSS via attribute.
        element.setAttribute("style", "cursor:pointer");
    }

    /**
     * A click that is this element's alone: it does not also reach the node it sits inside.
     *
     * <p>The action runs on a green thread, because it fetches: a suspending RMI call made from a
     * DOM handler's own frame throws "Suspension point reached from non-threading context", the
     * rule every other fetching handler in this client obeys. The propagation stop, though, has to
     * happen in the handler's own frame, before the event goes on to the node underneath.
     */
    private static void onClickOnly(Element element, Runnable action) {
        EventTarget target = element.cast();
        target.addEventListener("click", e -> {
            e.stopPropagation();
            new Thread(action).start();
        });
        element.setAttribute("style", "cursor:pointer");
    }

    /** The same slow pulse a running chip has, on a piece of text. */
    private static void animateFade(Element element) {
        Element animate = SvgCanvas.el("animate",
            "attributeName", "opacity", "values", "1;0.35;1",
            "dur", "1.6s", "repeatCount", "indefinite");
        element.appendChild(animate);
    }

    /**
     * A string that is safe to use, whatever arrived.
     *
     * <p>Every field on these DTOs is filled by the server, so in principle none of them is ever
     * null. In practice a field added on one side of the wire and not the other reads back as
     * nothing, and the first thing the graph does with a title or a state is call a method on it,
     * which then takes out the whole drawing. One blank word is a far better failure than a blank
     * screen.
     */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String trim(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}

