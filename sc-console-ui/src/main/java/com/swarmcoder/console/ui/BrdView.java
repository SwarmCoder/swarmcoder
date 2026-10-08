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

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.BacklogService;
import com.swarmcoder.console.api.BacklogService_Stub;
import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.console.api.BrdService;
import com.swarmcoder.console.api.BrdService_Stub;
import com.swarmcoder.console.api.BrdSignals;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdNodePosition;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BrdRevision;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementTree;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Select;
import com.zeroz4j.ui.component.SplitPane;
import com.zeroz4j.ui.component.SvgCanvas;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.EventTarget;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.xml.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-project Business Requirements Document editor (author decision 2026-07-24). The BRD is a
 * persisted network graph of requirement nodes and typed edges — one living document per project.
 * The domain {@link Brd} travels on the wire directly (@DataModel); the current graph lives in the
 * server-authoritative shared signal {@link BrdSignals#CURRENT}, so this view binds an
 * {@code Effect} and redraws live after any edit (editor OR the intake wizard) — no DTOs, no
 * refresh.
 * A History panel browses the append-only revision history and restores any past state.
 */
final class BrdView extends Div implements Disposable {

    private static final int NODE_W = 168;
    private static final int NODE_H = 58;
    private static final int GAP_X = 64;
    private static final int GAP_Y = 52;
    /** Content-space margin between the canvas origin and the first node. */
    private static final int MARGIN = 24;
    /** How close two node boxes may come before the automatic layout calls it a collision. */
    private static final int CLEARANCE = 12;

    private final BrdService service = new BrdService_Stub();
    /**
     * Only ever used to ask the server to publish the backlog. This view never mutates the plan —
     * it reads it to answer one question a requirement cannot answer about itself: has anybody
     * actually undertaken to deliver this?
     */
    private final BacklogService backlogService = new BacklogService_Stub();
    /** The live graph — server-authoritative shared signal; the server sets it after every edit. */
    private final ValueSignal<Brd> brd = BrdSignals.CURRENT;
    /**
     * The plan, as the Backlog panel already holds it. A requirement's lifecycle is not written
     * anywhere: "agreed but nobody has undertaken to build it" is the difference between a
     * requirement whose criteria some story claims and one whose criteria none does, and that fact
     * lives on the stories. It is read from the same shared signal the backlog binds to rather than
     * fetched again, so the two surfaces can never disagree about what is planned.
     */
    private final ValueSignal<Backlog> backlog = BacklogSignals.CURRENT;
    /** A past revision being previewed, or null for the live graph. */
    private final ValueSignal<Brd> preview = new ValueSignal<>(null);
    private final ValueSignal<BrdRequirement> selected = new ValueSignal<>(null);
    private final ValueSignal<Boolean> historyMode = new ValueSignal<>(false);

    private final SvgCanvas canvas = new SvgCanvas();
    /**
     * The single status line, as a signal. The form and the empty state both report progress, and
     * they are never visible at the same time — an upload started from the empty state must not
     * announce itself only into the panel the operator cannot see.
     */
    private final ValueSignal<String> statusText = new ValueSignal<>("");
    private final Span revisionLabel = new Span("");
    /**
     * Opens the guided intake flow. Supplied by {@link RequirementsStage}, which OWNS it.
     *
     * <p>It used to be owned here, and that was a real fault rather than tidiness: this panel is the
     * Graph lens, and the stage hides the lens that is not showing. A dialog mounted inside a hidden
     * subtree is not drawn, so once "Analyse documents" moved above the tabs — where it belongs,
     * because adding a document acts on the requirement set and not on how you are looking at it —
     * pressing it from the List did nothing at all, silently.
     */
    private Runnable onAnalyse = () -> { };
    /**
     * The one thing to do next IN REQUIREMENTS, when there is one. Bound to the stage rather than
     * filtered to a list of step keys: the filter used to be how guidance about the backlog was kept
     * off this panel, but the server now answers per stage, so a bar can only ever say something
     * about the stage it is mounted in.
     */
    private final List<Disposable> disposables = new ArrayList<>();

    BrdView() {
        addClassName("flex flex-col h-full min-h-0");
        // No guidance bar. There is ONE next-step line now and it lives in the status header (UX v3
        // §3): three of these — one per stage — meant the operator's answer to "what do I do" depended
        // on which stage they happened to be standing in, and each bar spent most of its life talking
        // about a different stage's business.
        add(header());
        add(focusBanner());
        SplitPane split = SplitPane.horizontal("brd", 640, 300, 1400);
        split.setFirst(graphPane());
        split.setSecond(rightPane());
        add(split);

        // Here the "analyse documents" step can BE the intake, not a link to the panel the operator
        // is already looking at — and the stage bar above has already said this is Requirements.
        installDragHandlers();
        disposables.add(Effect.create(this::drawGraph));
        // The picture follows the panel: a window resize, a splitter drag or the editor opening all
        // change the canvas width, and a picture fitted to the old width would sit small in a corner.
        JSObject sizeWatch = observeSize(canvas.getElement(), this::refitIfUntouched);
        disposables.add(() -> stopObserving(sizeWatch));
        disposables.add(Effect.create(() -> {
            Brd b = viewGraph();
            int n = b.requirements() == null ? 0 : b.requirements().size();
            revisionLabel.setText(n + " requirements · rev " + b.revision()
                + (preview.get() != null ? " (preview)" : ""));
        }));
        // Direct RMI (zeroz4j 0.3.0 runs handlers on green threads): nudge the server to publish
        // this project's BRD into the signal; the return is ignored — the signal drives the view.
        try {
            service.brd();
        } catch (Exception e) {
            // Not fatal — the signal still populates on the next server publish — so this stays a
            // warning and the view is left alone. It is logged because a graph that never arrives
            // looks exactly like a project with no requirements, and only this line tells them apart.
            ClientLog.warn("BrdView", "could not ask the server to publish this project's BRD; the "
                + "graph stays empty until the next publish: " + e);
        }
        // The same nudge for the backlog. Without it a Requirements tab opened in a session where
        // nothing has changed the plan would read an empty backlog off the retained signal and show
        // every agreed requirement as unplanned — which is a specific, wrong claim, not a blank.
        try {
            backlogService.backlog();
        } catch (Exception e) {
            ClientLog.warn("BrdView", "could not ask the server to publish the backlog; requirement "
                + "nodes may show as unplanned when stories in fact claim them: " + e);
        }
        // And the run list, which is what makes the live ring truthful. It is loaded on its own
        // thread by MainView because this is a constructor, not a DOM event handler.
        MainView.refreshRuns();
    }

    @Override
    public void dispose() {
        form.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /**
     * Whether requirements taken out of scope are drawn. Off by default, and the line under the
     * diagram says how many are not there and offers to show them.
     */
    private final ValueSignal<Boolean> showRetired = new ValueSignal<>(false);
    /** How many the last draw left out for that reason. */
    private final ValueSignal<Integer> retiredHidden = new ValueSignal<>(0);

    /** The graph currently shown: a previewed revision if one is selected, else the live signal. */
    private Brd viewGraph() {
        Brd p = preview.get();
        return p != null ? p : brd.get();
    }

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300");
        Span title = new Span("Requirements (BRD)");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        TextStyle.CAPTION.applyTo(revisionLabel);
        bar.getElement().appendChild(revisionLabel.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        bar.add(iconButton("plus", "New requirement", this::newRequirement));
        // Only what belongs to THIS view. "Analyse documents" and "History" used to be here too, and
        // both act on the requirement set rather than on how you are looking at it — so on the List,
        // which is the surface an operator lands on, the primary way of getting requirements in was
        // not offered at all: its empty state told people to go and find the diagram. Both are above
        // the tabs now, in RequirementsStage, where the whole workspace's actions live. What is left
        // here is genuinely about the drawing.
        //
        // The way back from a bad drag session. Dragging is only safe to offer because this exists:
        // hand-placed nodes are never moved again by the layout, which without an undo of the whole
        // policy would make one careless afternoon permanent.
        bar.add(iconButton("refresh", "Re-layout — forget every hand-placed node position and lay "
            + "the graph out automatically again", this::relayout));
        bar.add(textButton("\u2212", "Zoom out", "out", () -> zoomBy(1 / 1.25)));
        bar.add(textButton("+", "Zoom in", "in", () -> zoomBy(1.25)));
        bar.add(textButton("Fit", "Fit the whole graph in the panel", "fit", () -> fitView(fitW, fitH, true)));
        return bar;
    }

    /** Where to send "analyse some documents". Wired by the stage, which owns the wizard. */
    void onAnalyse(Runnable action) {
        this.onAnalyse = action == null ? () -> { } : action;
    }

    /**
     * Says what an applied analysis did, in the panel the operator is looking at.
     *
     * <p>Applying closes the wizard, so its summary has to land somewhere still on screen —
     * otherwise the requirements simply appear with no account of where from.
     */
    void showStatus(String summary) {
        statusText.set(summary);
    }

    /**
     * Shows (or hides) the revision browser.
     *
     * <p>Reachable from above the tabs, because past versions are a fact about the document and not
     * about the diagram. Switching to this view is part of pressing it: history is BROWSED by
     * drawing the graph as it stood, and there is nothing in the list to show instead.
     */
    void toggleHistory() {
        historyMode.set(!Boolean.TRUE.equals(historyMode.get()));
    }

    private Div graphPane() {
        Div pane = new Div();
        pane.addClassName("flex flex-col min-h-0 h-full bg-base-200/30");
        canvas.addClassName("flex-1 min-h-0");
        canvas.svg().setAttribute("width", "100%");
        canvas.svg().setAttribute("height", "100%");
        pane.add(canvas);
        pane.add(legend());
        return pane;
    }

    /**
     * The key to the colours. A colour with no key is decoration: the operator can see that two
     * nodes differ without being able to say how, which is worse than no colour at all because it
     * implies a meaning they then have to guess.
     *
     * <p>It extends the hint line that already explained the edge colours rather than opening a new
     * surface — the explanation of the picture belongs directly under the picture, and a legend
     * behind a toggle is a legend nobody reads.
     */
    private Div legend() {
        Div box = new Div();
        box.addClassName("flex flex-col gap-1 px-3 py-1.5 text-[11px] leading-snug "
            + "text-base-content/40 border-t border-base-300");

        Div states = new Div();
        states.addClassName("flex flex-wrap items-center gap-x-3 gap-y-1");
        states.getElement().appendChild(legendLabel("State").getElement());
        for (ReqState state : ReqState.values()) {
            states.add(swatch(state.color, state.word, state.explanation));
        }
        box.add(states);

        Div edges = new Div();
        edges.addClassName("flex flex-wrap items-center gap-x-3 gap-y-1");
        edges.getElement().appendChild(legendLabel("Links").getElement());
        for (RequirementRelation relation : RequirementRelation.values()) {
            // The words are RequirementRelation's, not this view's. This key used to read
            // "depends refines conflicts derived gates" - four of the five are the Java constants,
            // on the one panel that exists to explain the picture (25.5).
            edges.add(swatch(relationColor(relation), relation.label(),
                relationMeaning(relation)));
        }
        box.add(edges);

        Div retired = new Div();
        retired.addClassName("flex items-center gap-1.5");
        retired.getElement().setAttribute("data-testid", "graph-retired-hidden");
        Span retiredText = new Span("");
        retired.getElement().appendChild(retiredText.getElement());
        Div retiredToggle = new Div();
        retiredToggle.addClassName("cursor-pointer text-primary hover:underline");
        retiredToggle.getElement().setAttribute("data-testid", "graph-retired-toggle");
        Span retiredToggleText = new Span("");
        retiredToggle.getElement().appendChild(retiredToggleText.getElement());
        retiredToggle.addDomEventListener("click",
            e -> showRetired.set(!Boolean.TRUE.equals(showRetired.get())));
        retired.add(retiredToggle);
        disposables.add(Effect.create(() -> {
            boolean showing = Boolean.TRUE.equals(showRetired.get());
            int hidden = retiredHidden.get() == null ? 0 : retiredHidden.get();
            retired.setVisible(showing || hidden > 0);
            retiredText.setText(showing
                ? "Requirements that are out of scope are shown."
                : hidden + (hidden == 1
                    ? " requirement is out of scope and is not drawn."
                    : " requirements are out of scope and are not drawn."));
            retiredToggleText.setText(showing ? "Hide them" : "Show them");
        }));
        box.add(retired);

        Div hint = new Div();
        hint.setText("Laid out in dependency order: what a requirement depends on sits to its "
            + "left. A pulsing ring means a build is working one of its checks right now. Click a "
            + "node to edit it; hover one to read its state in full. Drag a node to place it by "
            + "hand — it stays exactly there, through every later edit, until you use Re-layout in "
            + "the toolbar to hand the whole graph back to the automatic layout.");
        box.add(hint);
        return box;
    }

    private static Span legendLabel(String text) {
        Span label = new Span(text);
        label.addClassName("uppercase tracking-wider text-base-content/30");
        return label;
    }

    /** A colour chip and its name, with the full meaning on hover. */
    private static Div swatch(String color, String word, String meaning) {
        Div item = new Div();
        item.addClassName("flex items-center gap-1");
        item.getElement().setAttribute("title", meaning);
        Span dot = new Span("");
        // Inline style rather than a class: these colours are chosen in Java and vary per item, so
        // there is no class for the CSS build to have emitted.
        dot.getElement().setAttribute("style",
            "width:8px;height:8px;border-radius:9999px;display:inline-block;flex:none;background:"
                + color);
        item.getElement().appendChild(dot.getElement());
        Span name = new Span(word);
        item.getElement().appendChild(name.getElement());
        return item;
    }

    // ---- the derived lifecycle ------------------------------------------------------------------

    /**
     * Where a requirement has actually got to, DERIVED rather than stored.
     *
     * <p>No field on {@link BrdRequirement} says any of this, and none should: every one of these
     * states is already implied by the requirement's status, its criteria, their evidence and the
     * plan, and a stored copy of an implied fact is a copy that goes wrong. What was missing is
     * that the operator could not SEE it — a graph of identical boxes showed a requirement nobody
     * can ever verify exactly like one that is delivered and passing.
     *
     * <p>The order of the constants is the lifecycle order, and the legend is generated from it, so
     * the key reads the way the work actually flows.
     */
    private enum ReqState {
        DRAFT("draft", "#94a3b8",
            "Captured but not yet agreed. It is not scope until you agree it, and nothing will "
                + "be built from it."),
        RULE("a leftover rule", "#0f766e",
            "A rule about how the project is built, written as a requirement during the few hours "
                + "that was possible. Rules live in Guidelines now, where they are actually sent "
                + "to the architect, the test author and every worker. This one is sending "
                + "nothing: state it on the Guidelines screen and take this row out of scope."),
        UNPROVABLE("unprovable", "#ef4444",
            "Agreed scope with no agreed check on it — so there is nothing that could ever "
                + "show it was met. It cannot be sliced into a story and can never reach verified. "
                + "This is a defect in the BRD, not a stage of progress: give it a check."),
        UNPLANNED("unplanned", "#60a5fa",
            "Agreed and verifiable, but no story claims any of its checks — nobody has "
                + "undertaken to deliver it. Plan it in the backlog."),
        PLANNED("planned", "#a78bfa",
            "A story claims its checks, so it is in the plan. The evidence is not in yet."),
        VERIFIED("verified", "#10b981",
            "Every accepted check is passing against the CURRENT wording of the requirement. "
                + "This is the only state that means the system demonstrably does what this says."),
        STALE("stale", "#f59e0b",
            "It passed, but against an OLDER wording — the requirement's text has been edited "
                + "since. The evidence exists and no longer speaks to what the requirement now "
                + "says, so this is not green: re-verify it before trusting it."),
        RETIRED("retired", "#64748b",
            "Deprecated. Out of scope, kept so the work that already shipped against it stays "
                + "traceable.");

        private final String word;
        private final String color;
        private final String explanation;

        ReqState(String word, String color, String explanation) {
            this.word = word;
            this.color = color;
            this.explanation = explanation;
        }
    }

    /** The ring drawn around a requirement a run is working right now. */
    private static final String LIVE_COLOR = "#22d3ee";

    /**
     * Which criteria the plan has claimed, and which of those something is working on now.
     *
     * <p>Both are keyed by criterion id as a string rather than as a {@link UUID}, matching the
     * idiom the rest of this view already uses for graph lookups.
     */
    private static final class Coverage {
        private final Set<String> planned = new HashSet<>();
        private final Set<String> live = new HashSet<>();
    }

    /**
     * Reads the plan and the run list to answer the two questions the BRD cannot answer alone.
     *
     * <p>Called from inside the drawing effect, so touching both signals here is also what
     * subscribes the graph to them: a story being planned, or a run starting, redraws the nodes.
     * Neither read is an RMI call — both are signals the server has already published.
     */
    private Coverage coverage() {
        Coverage coverage = new Coverage();
        Backlog plan = backlog.get();
        if (plan == null) {
            return coverage;
        }
        Set<String> inFlight = new HashSet<>();
        for (RunSummaryDto run : safe(RunsStore.runs.get())) {
            if (isInFlight(run.getState())) {
                inFlight.add(run.getRunId());
            }
        }
        for (Story story : plan.stories()) {
            if (story.state() == StoryState.CANCELLED) {
                // A cancelled story claims nothing: leaving it in would show a requirement as
                // planned on the strength of work that was explicitly called off.
                continue;
            }
            // The story's own RUNNING state is not enough on its own. A run that died leaves the
            // story sitting in RUNNING indefinitely, and a ring that says "this is being worked on
            // right now" must not be the last thing a crashed run left behind — so the claim is
            // only made when the run list still shows one of its runs in flight.
            boolean working = story.state() == StoryState.RUNNING
                && anyIn(story.runIds(), inFlight);
            for (UUID criterionId : story.criterionIds()) {
                String key = idStr(criterionId);
                coverage.planned.add(key);
                if (working) {
                    coverage.live.add(key);
                }
            }
        }
        return coverage;
    }

    /** True while a run has neither been delivered nor abandoned — i.e. it is still going. */
    private static boolean isInFlight(String state) {
        return state != null && !state.isEmpty()
            && !"DELIVERED".equals(state) && !"ABORTED".equals(state);
    }

    private static boolean anyIn(List<UUID> ids, Set<String> keys) {
        for (UUID id : safe(ids)) {
            if (keys.contains(idStr(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The state to paint, tested in lifecycle order with the two exceptions that matter.
     *
     * <p>STALE is tested BEFORE anything about the plan, because it is the one state that is both
     * invisible and actively misleading: the requirement still looks delivered, its criteria still
     * hold a green result, and the only thing that changed is that the result no longer describes
     * what the requirement says. It must out-rank "planned" or a re-planned stale requirement would
     * hide behind the plan.
     *
     * <p>"Nothing gates it" is tested before evidence for the same reason
     * {@link BrdRequirement#statusFromEvidence()} refuses to call such a requirement implemented:
     * with no accepted criterion there is no evidence to have, and a node that simply looked
     * un-started would read as a scheduling matter rather than as the hole in the BRD it is.
     */
    private static ReqState stateOf(BrdRequirement r, Coverage coverage) {
        RequirementStatus status = r.status();
        if (status == RequirementStatus.DEPRECATED) {
            return ReqState.RETIRED;
        }
        if (status == null || status == RequirementStatus.DRAFT) {
            return ReqState.DRAFT;
        }
        // Before "unprovable". A leftover rule genuinely has no checks and never will, and
        // painting it red with "give it a check" sends the operator to fix the wrong thing: the
        // fix is to state it as a guideline and retire the row.
        if (r.isConstraint()) {
            return ReqState.RULE;
        }
        List<AcceptanceCriterion> gates = r.gatingCriteria();
        if (gates.isEmpty()) {
            return ReqState.UNPROVABLE;
        }
        for (AcceptanceCriterion criterion : gates) {
            if (criterion.effectiveState(r.contentRevision()) == CriterionState.STALE) {
                return ReqState.STALE;
            }
        }
        if (r.statusFromEvidence() == RequirementStatus.IMPLEMENTED) {
            return ReqState.VERIFIED;
        }
        return claimed(r, coverage.planned) ? ReqState.PLANNED : ReqState.UNPLANNED;
    }

    /** True when any of this requirement's criteria appears in the given set. */
    private static boolean claimed(BrdRequirement r, Set<String> keys) {
        for (AcceptanceCriterion criterion : r.criteria()) {
            if (keys.contains(idStr(criterion.id()))) {
                return true;
            }
        }
        return false;
    }

    /** What the hover text says, beyond the colour's own meaning. */
    private static String nodeTooltip(BrdRequirement r, ReqState state, boolean working,
                                      String shapeWarning) {
        StringBuilder sb = new StringBuilder();
        sb.append(nz(r.handle())).append(" · ").append(displayTitle(r)).append('\n')
            // NOT upper-cased. The word here is already the operator's ("draft", "stale"), and
            // shouting it made it read as the Java constant it deliberately is not.
            .append(state.word).append(" — ").append(state.explanation);
        if (shapeWarning != null) {
            sb.append("\n\n").append(shapeWarning);
        }
        List<AcceptanceCriterion> criteria = r.criteria();
        int gates = r.gatingCriteria().size();
        if (r.isConstraint()) {
            // "0 accepted and therefore gating" is arithmetic about something that has no gate to
            // be counted against. It read as a shortfall; it is the definition of a rule.
            sb.append("\n\nNo checks, and never any: nothing delivers a rule.");
        } else {
            sb.append("\n\n").append(criteria.size())
                .append(criteria.size() == 1 ? " check" : " checks")
                .append(", ").append(gates).append(" accepted and therefore gating.");
        }
        if (working) {
            sb.append("\nA build is working one of them right now.");
        }
        return sb.toString();
    }

    /**
     * What is wrong with this requirement's place in the hierarchy, in the operator's words, or null
     * when nothing is.
     *
     * <p>It says what to DO. "Belongs in one place" is actionable; "has two REFINES edges" names an
     * internal relation (UX v3 rule 3) and leaves the reader to work out the consequence. Both shapes
     * below were creatable before the single-parent rule, so this is aimed at someone reading a
     * document they did not knowingly break.
     */
    private static String shapeWarningFor(RequirementTree tree, BrdRequirement r,
                                          List<BrdRequirement> all) {
        if (r == null || r.id() == null) {
            return null;
        }
        List<UUID> extra = tree.extraParentsOf(r.id());
        if (!extra.isEmpty()) {
            StringBuilder sb = new StringBuilder("A requirement belongs in one place. ");
            sb.append(nz(r.handle())).append(" is shown under ")
                .append(handleOf(all, tree.parentOf(r.id())))
                .append(", and is also marked as part of ");
            for (int i = 0; i < extra.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(handleOf(all, extra.get(i)));
            }
            sb.append(". Remove the links you do not want.");
            return sb.toString();
        }
        if (tree.inCycle().contains(r.id())) {
            return "These requirements are marked as parts of each other, which cannot be true. "
                + nz(r.handle()) + " is shown at the top so the rest stay reachable - remove one of "
                + "the links to break the loop.";
        }
        return null;
    }

    /** A requirement's handle by id, for the warning text; the raw id if it is not in this graph. */
    private static String handleOf(List<BrdRequirement> all, UUID id) {
        if (id == null) {
            return "(nothing)";
        }
        for (BrdRequirement r : all) {
            if (r != null && id.equals(r.id())) {
                return nz(r.handle());
            }
        }
        return id.toString();
    }

    /**
     * The requirement the diagram is scoped to, or null for the whole document.
     *
     * <p>A String rather than a UUID because it is what the row hands over and what the banner reads
     * back; parsing it once per draw is cheaper than keeping two spellings in step.
     */
    private final ValueSignal<String> focus = new ValueSignal<>(null);

    /**
     * Scopes the diagram to one requirement's surroundings. Called from the list, which is a DOM
     * handler, so the redraw this triggers is on a green thread.
     */
    void focusOn(String requirementId) {
        focus.set(requirementId);
    }

    /**
     * Draws the diagram again, now that the pane it lives in is actually on screen.
     *
     * <p>{@code SvgCanvas.fit} scales the picture by measuring its own {@code offsetWidth}, which is
     * ZERO while this pane is hidden — and this pane starts hidden, because the list is what the
     * operator lands on. So the diagram was laid out at a scale that fitted nothing, and switching to
     * it showed a picture drawn for a window of no width: requirements past the fold sat outside the
     * pane entirely, invisible and unclickable. On the fixture that found it, two of five
     * requirements could not be reached at all — and since the list has no editor either, that made
     * them uneditable by any route.
     *
     * <p>The same fault in the same method is already recorded for the scoped view (focus before
     * show), where it hid the one requirement the operator had just asked to see. This is the plain
     * tab switch, which nothing had ever driven.
     */
    void redraw() {
        drawGraph();
    }

    /**
     * {@code reqs} narrowed to the focus requirement's neighbourhood: itself, the chain it is part of,
     * its own parts, and anything one relation away in either direction.
     *
     * <p>Ancestors and not just the parent, because "where does this sit" is the first question a
     * diagram is opened to answer and one level up rarely answers it. Everything else is one hop: two
     * hops on a dense graph is most of the document again, which is the problem being solved.
     *
     * <p>A focus id that is not in the graph — a requirement deleted while the diagram was open —
     * falls back to the whole document rather than rendering nothing, because an empty canvas reads as
     * "this project has no requirements".
     */
    private List<BrdRequirement> inScope(List<BrdRequirement> reqs, RequirementTree tree) {
        String focused = focus.get();
        // Out of scope is out of the picture, unless the operator asked to see them or is looking
        // at one. A retired requirement that still draws a box among the live ones has only been
        // renamed (25.1); the count under the diagram is what keeps it from being a disappearance.
        List<BrdRequirement> live = new ArrayList<>();
        int hidden = 0;
        boolean showAll = Boolean.TRUE.equals(showRetired.get());
        for (BrdRequirement r : reqs) {
            if (r == null) {
                continue;
            }
            boolean isFocus = focused != null && !focused.isEmpty() && focused.equals(idStr(r.id()));
            if (r.isRetired() && !showAll && !isFocus) {
                hidden++;
                continue;
            }
            live.add(r);
        }
        retiredHidden.set(hidden);
        reqs = live;
        if (focused == null || focused.isEmpty()) {
            return reqs;
        }
        UUID centre = null;
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null && focused.equals(idStr(r.id()))) {
                centre = r.id();
                break;
            }
        }
        if (centre == null) {
            return reqs;
        }
        Set<UUID> keep = new HashSet<>();
        keep.add(centre);
        for (UUID at = tree.parentOf(centre); at != null && keep.add(at); at = tree.parentOf(at)) {
            // walking up; keep.add returning false ends it, so a cycle cannot spin here
        }
        keep.addAll(tree.childrenOf(centre));
        for (BrdEdge edge : graph().edges() == null ? List.<BrdEdge>of() : graph().edges()) {
            if (edge == null) {
                continue;
            }
            if (centre.equals(edge.from()) && edge.to() != null) {
                keep.add(edge.to());
            } else if (centre.equals(edge.to()) && edge.from() != null) {
                keep.add(edge.from());
            }
        }
        List<BrdRequirement> scoped = new ArrayList<>();
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null && keep.contains(r.id())) {
                scoped.add(r);
            }
        }
        return scoped;
    }

    /** The graph being rendered — same choice {@link #viewGraph} makes, named for use inside helpers. */
    private Brd graph() {
        return viewGraph();
    }

    /**
     * The line that says the diagram is showing part of the document, and the way out of it.
     *
     * <p>Without this a scoped graph is indistinguishable from a project with four requirements, which
     * is a specific and wrong claim rather than a blank (UX v3 rule 1: what is not shown is accounted
     * for, and reachable).
     */
    private Div focusBanner() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-1 text-[11px] border-b "
            + "border-base-300 bg-base-200/40 shrink-0");
        bar.getElement().setAttribute("data-testid", "graph-focus-banner");
        Span text = new Span("");
        text.addClassName("text-base-content/60");
        bar.getElement().appendChild(text.getElement());
        Div clear = new Div();
        clear.addClassName("cursor-pointer text-primary hover:underline");
        clear.getElement().setAttribute("data-testid", "graph-focus-clear");
        Span clearText = new Span("Show the whole document");
        clear.getElement().appendChild(clearText.getElement());
        clear.addDomEventListener("click", e -> focus.set(null));
        bar.add(clear);
        disposables.add(Effect.create(() -> {
            String focused = focus.get();
            bar.setVisible(focused != null && !focused.isEmpty());
            if (focused == null || focused.isEmpty()) {
                return;
            }
            Brd current = viewGraph();
            String handle = focused;
            int total = current.requirements() == null ? 0 : current.requirements().size();
            for (BrdRequirement r : current.requirements() == null
                    ? List.<BrdRequirement>of() : current.requirements()) {
                if (r != null && r.id() != null && focused.equals(idStr(r.id()))) {
                    handle = nz(r.handle());
                    break;
                }
            }
            text.setText("Showing what surrounds " + handle + " — " + total
                + (total == 1 ? " requirement" : " requirements") + " in all.");
        }));
        return bar;
    }

    // ---- graph rendering ----------------------------------------------------------------------

    private void drawGraph() {
        Brd graph = viewGraph();
        canvas.clearContent();
        // Resolved ONCE per draw, not per node: it is the same answer for every node, and it is the
        // SAME derivation the server validates edits with (RequirementTree, sc-domain). A requirement
        // claiming two parents, or sitting in a REFINES loop, is placed somewhere sensible and SAYS
        // SO here - a shape nothing used to forbid must not be silently tidied away on screen.
        RequirementTree tree = RequirementTree.of(graph);
        List<BrdRequirement> reqs = graph.requirements();
        if (reqs == null || reqs.isEmpty()) {
            // Nothing left to hold on to.
            endDrag();
            return;
        }
        // Scoped to one requirement's surroundings when the list sent us here
        // (REQUIREMENTS_AT_SCALE_DESIGN §3.3). This is what makes the diagram legible again: it stops
        // trying to draw the document and draws a neighbourhood, which is the only thing a node-link
        // layout is actually good at. Every edge whose other end is outside the scope is skipped for
        // free — the edge loop below already drops an edge with an endpoint it has no position for.
        reqs = inScope(reqs, tree);
        Coverage coverage = coverage();
        int n = reqs.size();
        List<BrdEdge> edges = graph.edges() == null ? List.of() : graph.edges();
        boolean live = preview.get() == null;
        Map<String, double[]> at = layout(reqs, edges, graph.nodePositions());
        // A redraw can land in the middle of a gesture — this very redraw is often the one the last
        // drop caused. The node under the pointer is therefore drawn where the POINTER has it, not
        // where the document says it is, and the gesture adopts the new element below. A drag
        // whose requirement has gone (deleted elsewhere) or whose canvas has switched to a
        // read-only revision preview has nothing left to drop, so it is abandoned instead.
        if (dragId != null && (!live || !at.containsKey(dragId))) {
            endDrag();
        }
        if (dragId != null) {
            at.put(dragId, new double[]{dragX, dragY});
        }
        double contentW = 0;
        double contentH = 0;
        for (int i = 0; i < n; i++) {
            double[] p = at.get(idStr(reqs.get(i).id()));
            contentW = Math.max(contentW, p[0] + NODE_W);
            contentH = Math.max(contentH, p[1] + NODE_H);
        }

        // Edges first, so a node always sits on top of the lines that reach it.
        //
        // Each line is routed round every box that is not one of its two ends (EdgeRouting), so a
        // link between two boxes in one row no longer strikes out the title of the box between them.
        // The relation's name is NOT printed on the line any more: where lines cross or run close
        // the words landed on top of each other, and the colour is already keyed in the legend. The
        // name is the line's hover text instead, so nothing is lost and nothing can overprint.
        double[] boxX = new double[n];
        double[] boxY = new double[n];
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String id = idStr(reqs.get(i).id());
            double[] p = at.get(id);
            boxX[i] = p[0];
            boxY[i] = p[1];
            indexOf.put(id, i);
        }
        // Links joining the same two boxes are fanned out sideways so they do not lie on one line.
        Map<String, Integer> pairTotal = new HashMap<>();
        for (BrdEdge edge : edges) {
            Integer a = indexOf.get(idStr(edge.from()));
            Integer b = indexOf.get(idStr(edge.to()));
            if (a != null && b != null && !a.equals(b)) {
                pairTotal.merge(pairKey(a, b), 1, Integer::sum);
            }
        }
        Map<String, Integer> pairSeen = new HashMap<>();
        for (BrdEdge edge : edges) {
            Integer a = indexOf.get(idStr(edge.from()));
            Integer b = indexOf.get(idStr(edge.to()));
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            String color = relationColor(edge.relation());
            String key = pairKey(a, b);
            int seen = pairSeen.merge(key, 1, Integer::sum) - 1;
            double fan = (seen - (pairTotal.get(key) - 1) / 2.0) * 10;
            double ax = boxX[a] + NODE_W / 2.0;
            double ay = boxY[a] + NODE_H / 2.0;
            double bx = boxX[b] + NODE_W / 2.0;
            double by = boxY[b] + NODE_H / 2.0;
            double len = Math.max(1, Math.hypot(bx - ax, by - ay));
            // Sideways of the a->b direction, flipped for b->a so both orders fan the same way.
            double sign = a < b ? 1 : -1;
            double nx = -(by - ay) / len * fan * sign;
            double ny = (bx - ax) / len * fan * sign;
            double[][] route = EdgeRouting.route(boxX, boxY, NODE_W, NODE_H, a, b,
                new double[]{ax + nx, ay + ny}, new double[]{bx + nx, by + ny});
            // Clipped to the two node borders rather than drawn centre to centre: a line that
            // disappears under both boxes gives no clue which way it points, and direction is the
            // entire content of a "depends on".
            route[0] = EdgeRouting.exit(boxX[a], boxY[a], NODE_W, NODE_H, route[0], route[1], 2);
            route[route.length - 1] = EdgeRouting.exit(boxX[b], boxY[b], NODE_W, NODE_H,
                route[route.length - 1], route[route.length - 2], 2);
            double[] tip = route[route.length - 1];
            double[] tail = route[route.length - 2];
            // The line stops at the base of the arrow head, so the stroke does not poke through it.
            double[][] drawn = route.clone();
            drawn[drawn.length - 1] = backOff(tip, tail, 8);
            String d = roundedPath(drawn);
            String hover = nz(reqs.get(a).handle()) + " "
                + (edge.relation() == null ? "" : edge.relation().label()) + " "
                + nz(reqs.get(b).handle());
            Element line = SvgCanvas.el("path", "d", d, "fill", "none",
                "stroke", color, "stroke-width", "1.5", "stroke-opacity", "0.7",
                "stroke-linejoin", "round");
            // A wider invisible stroke on top, so the hover text is easy to hit.
            Element hit = SvgCanvas.el("path", "d", d, "fill", "none", "stroke", "transparent",
                "stroke-width", "10", "pointer-events", "stroke");
            hit.setAttribute("data-edge", hover);
            hit.appendChild(svgTitle(hover));
            canvas.viewport().appendChild(line);
            canvas.viewport().appendChild(arrowHead(tip, tail, color));
            canvas.viewport().appendChild(hit);
        }

        BrdRequirement sel = selected.get();
        for (int i = 0; i < n; i++) {
            BrdRequirement r = reqs.get(i);
            double[] p = at.get(idStr(r.id()));
            int x = round(p[0]);
            int y = round(p[1]);
            boolean isSel = live && sel != null && r.id() != null && r.id().equals(sel.id());
            ReqState state = stateOf(r, coverage);
            // A previewed revision is a past graph; the plan and the runs describe the present, so
            // claiming a historical node is being worked on right now would simply be false.
            boolean working = live && claimed(r, coverage.live);
            Element group = SvgCanvas.el("g");
            group.setAttribute("data-node", "req-" + r.handle());
            group.setAttribute("data-state", state.word);
            // An SVG <title> is the platform's own tooltip: no positioning code, no hover state to
            // keep, and it reaches keyboard and screen-reader users that a painted popover does not.
            String shapeWarning = shapeWarningFor(tree, r, reqs);
            group.appendChild(svgTitle(nodeTooltip(r, state, working, shapeWarning)));
            if (shapeWarning != null) {
                group.setAttribute("data-shape-warning", "true");
                // Top-LEFT, where nothing else sits: the state dot owns the top-right corner and the
                // selection takes the border over, so a warning in either place reads as those.
                group.appendChild(text(x + 5, y + 14, "!", 13, "#f59e0b", "start"));
            }
            if (working) {
                group.appendChild(livePulse(x, y));
            }
            Element rect = SvgCanvas.el("rect",
                "x", String.valueOf(x), "y", String.valueOf(y),
                "width", String.valueOf(NODE_W), "height", String.valueOf(NODE_H),
                "rx", "8", "fill", "rgba(120,130,150,0.12)",
                "stroke", isSel ? "#6366f1" : state.color, "stroke-width", isSel ? "2.5" : "1.5");
            group.appendChild(rect);
            // The state also gets a filled dot, because selecting a node takes its border over —
            // and the operator must not lose the state of the very node they are editing.
            group.appendChild(SvgCanvas.el("circle",
                "cx", String.valueOf(x + NODE_W - 12), "cy", String.valueOf(y + 13),
                "r", "4", "fill", state.color));
            group.appendChild(text(x + 12, y + 17,
                nz(r.handle()) + "  " + state.word, 11, state.color, "start"));
            group.appendChild(text(x + 12, y + 35, truncate(displayTitle(r), 22), 12, "#cbd5e1", "start"));
            group.appendChild(text(x + 12, y + 50,
                r.priority() == null ? "" : r.priority().name().toLowerCase(),
                9, priorityColor(r.priority()), "start"));
            if (live) {
                onNodeClick(group, r);
                enableDrag(group, r, p[0], p[1]);
                if (dragId != null && dragId.equals(idStr(r.id()))) {
                    // The gesture adopts the element that has just replaced the one it was holding,
                    // and notes where that element was painted so the next pointer move offsets
                    // from the right origin. Without this a redraw mid-drag would leave the pointer
                    // dragging a detached node nobody can see.
                    dragGroup = group;
                    dragDrawnX = p[0];
                    dragDrawnY = p[1];
                }
            }
            canvas.viewport().appendChild(group);
        }
        fitView(contentW + MARGIN * 2, contentH + MARGIN * 2, false);
    }

    /**
     * A ring that breathes around a node a run is working right now.
     *
     * <p>This is the one thing a static view genuinely cannot tell you, which is what earns it the
     * animation. It is a ring and not a spinner on purpose: a spinner sits inside the node and
     * competes with the text for the eye every second it runs, whereas motion at the edge reads in
     * peripheral vision and stops mattering the moment you look straight at it.
     *
     * <p>SMIL rather than a CSS class: the stylesheet is generated from the classes that appear in
     * the source, and an animation used only here, only on an SVG element, is not something to make
     * the CSS build responsible for.
     */
    private static Element livePulse(int x, int y) {
        Element ring = SvgCanvas.el("rect",
            "x", String.valueOf(x - 5), "y", String.valueOf(y - 5),
            "width", String.valueOf(NODE_W + 10), "height", String.valueOf(NODE_H + 10),
            "rx", "12", "fill", "none", "stroke", LIVE_COLOR, "stroke-width", "1.5");
        Element animate = SvgCanvas.el("animate",
            "attributeName", "stroke-opacity", "values", "0.9;0.15;0.9",
            "dur", "1.8s", "repeatCount", "indefinite");
        ring.appendChild(animate);
        return ring;
    }

    /** A small filled triangle at {@code tip}, pointing away from {@code tail}. */
    private static Element arrowHead(double[] tip, double[] tail, String color) {
        double dx = tip[0] - tail[0];
        double dy = tip[1] - tail[1];
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1) {
            length = 1;
        }
        double ux = dx / length;
        double uy = dy / length;
        // The two base corners are one arrow-length back along the line and half that to each side.
        double baseX = tip[0] - ux * 9;
        double baseY = tip[1] - uy * 9;
        String points = num(tip[0]) + "," + num(tip[1])
            + " " + num(baseX - uy * 4) + "," + num(baseY + ux * 4)
            + " " + num(baseX + uy * 4) + "," + num(baseY - ux * 4);
        return SvgCanvas.el("polygon", "points", points, "fill", color, "fill-opacity", "0.75");
    }

    /** Key for the pair of boxes a link joins, the same whichever way it points. */
    private static String pairKey(int a, int b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    /** The point {@code distance} short of {@code tip}, on the way back towards {@code tail}. */
    private static double[] backOff(double[] tip, double[] tail, double distance) {
        double dx = tail[0] - tip[0];
        double dy = tail[1] - tip[1];
        double len = Math.hypot(dx, dy);
        if (len < distance * 1.5) {
            return tip;
        }
        return new double[]{tip[0] + dx / len * distance, tip[1] + dy / len * distance};
    }

    /** An SVG path through {@code pts} with the bends rounded. */
    private static String roundedPath(double[][] pts) {
        StringBuilder d = new StringBuilder();
        d.append("M").append(num(pts[0][0])).append(",").append(num(pts[0][1]));
        for (int i = 1; i < pts.length; i++) {
            if (i == pts.length - 1) {
                d.append(" L").append(num(pts[i][0])).append(",").append(num(pts[i][1]));
                break;
            }
            double[] p = pts[i];
            double[] before = backOff(p, pts[i - 1],
                Math.min(12, Math.hypot(p[0] - pts[i - 1][0], p[1] - pts[i - 1][1]) / 2));
            double[] after = backOff(p, pts[i + 1],
                Math.min(12, Math.hypot(p[0] - pts[i + 1][0], p[1] - pts[i + 1][1]) / 2));
            d.append(" L").append(num(before[0])).append(",").append(num(before[1]));
            d.append(" Q").append(num(p[0])).append(",").append(num(p[1])).append(" ")
                .append(num(after[0])).append(",").append(num(after[1]));
        }
        return d.toString();
    }

    private static String num(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static Element svgTitle(String content) {
        Element title = SvgCanvas.el("title");
        title.appendChild(Window.current().getDocument().createTextNode(content));
        return title;
    }

    // ---- layout -----------------------------------------------------------------------------

    /**
     * The column each requirement belongs in, so that what a requirement depends on sits to its
     * left.
     *
     * <p>The grid this replaced ignored the edges completely, which meant a dependency could run
     * backwards across the canvas and the picture said nothing about order — the one thing a
     * requirement graph is for. This is a longest-path layering: relax every ordering edge until
     * nothing moves, which puts each requirement one column past the furthest thing it waits on.
     *
     * <p><b>Cycles.</b> A cycle cannot be laid out in dependency order at all — that is what a
     * cycle means — so the relaxation is bounded at one pass per requirement instead of being run
     * to a fixed point it would never reach. The cycle is then broken at whichever member the fixed
     * edge order happens to reach last: arbitrary, but IDENTICAL on every redraw, which is the
     * property that actually matters. A graph that reshuffles itself each time the signal fires is
     * unreadable however optimal each individual arrangement is, so a stable layout beats a clever
     * one every time.
     */
    private static int[] layerOf(List<BrdRequirement> reqs, List<BrdEdge> edges) {
        int n = reqs.size();
        int[] layer = new int[n];
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < n; i++) {
            indexById.put(idStr(reqs.get(i).id()), i);
        }
        // Each pair is {the one that must come later, the one it waits on}.
        List<int[]> ordering = new ArrayList<>();
        for (BrdEdge edge : edges) {
            Integer from = indexById.get(idStr(edge.from()));
            Integer to = indexById.get(idStr(edge.to()));
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            RequirementRelation relation = edge.relation();
            if (relation == null || relation == RequirementRelation.CONFLICTS_WITH) {
                // A conflict is symmetric — it says the two cannot both hold, not that one comes
                // first — so it carries no order and must not be allowed to invent one.
                continue;
            }
            if (relation == RequirementRelation.GATES) {
                // The only relation pointing the other way: the gate constrains its target, so the
                // non-functional requirement is what the target sits behind.
                ordering.add(new int[]{to, from});
            } else {
                ordering.add(new int[]{from, to});
            }
        }
        for (int pass = 0; pass < n; pass++) {
            boolean changed = false;
            for (int[] pair : ordering) {
                if (layer[pair[0]] <= layer[pair[1]]) {
                    layer[pair[0]] = layer[pair[1]] + 1;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return layer;
    }

    /**
     * Turns layers into grid cells: {@code place(...)[i] = {column, row}}.
     *
     * <p>A layer is not forced into a single column, because most of a real BRD's requirements
     * depend on nothing at all and would all land in layer 0 — a graph with no edges yet would come
     * out as one column tall enough to need scrolling to see six requirements. Each layer instead
     * fills down to a row cap and then spills into a further column of its OWN band, so layers stay
     * in dependency order left to right while a wide one reads as a block.
     *
     * <p>The cap is the side of a square, which means a BRD with no edges lays out as very nearly
     * the grid it always did — the right answer for a graph carrying no ordering information yet.
     *
     * <p>Everything here follows the requirement list's own order, so a redraw never re-sorts a
     * column: the operator's memory of where a node was is worth more than any tidier arrangement.
     */
    private static int[][] place(int[] layer, int n) {
        int perColumn = Math.max(3, (int) Math.ceil(Math.sqrt(n)));
        int layers = 1;
        for (int value : layer) {
            layers = Math.max(layers, value + 1);
        }
        int[] size = new int[layers];
        for (int value : layer) {
            size[value]++;
        }
        int[] firstColumn = new int[layers];
        int next = 0;
        for (int l = 0; l < layers; l++) {
            firstColumn[l] = next;
            next += Math.max(1, (size[l] + perColumn - 1) / perColumn);
        }
        int[] placed = new int[layers];
        int[][] cell = new int[n][2];
        for (int i = 0; i < n; i++) {
            int l = layer[i];
            int rank = placed[l]++;
            cell[i][0] = firstColumn[l] + rank / perColumn;
            cell[i][1] = rank % perColumn;
        }
        return cell;
    }

    /**
     * Where every node goes: {@code id -> {x, y}} of its top-left corner, in content coordinates.
     *
     * <p><b>The policy, which is the point of the whole feature.</b> A node the operator has dragged
     * has a saved position and is put back EXACTLY there — for ever, through every later edit, every
     * new requirement and every re-analysis. Nothing but Re-layout ever moves it again. If the
     * automatic pass were allowed to "improve" a hand-placed node, the operator's arrangement would
     * last only until the next intake, and the drag would be a toy.
     *
     * <p>Everything else is positioned by the layering pass above, unchanged: with nothing placed by
     * hand, every node lands on its own grid cell and this produces precisely the layout it always
     * did (grid cells are further apart than {@link #CLEARANCE}, so they never collide with each
     * other). What is new is only what happens when an automatic node's cell is already taken by a
     * hand-placed one: it takes the first free slot below it, and then across into the next column —
     * near where the dependency order wanted it, never on top of an existing node, and never outside
     * the fitted content box, because the canvas is fitted to whatever bounds come out of here.
     */
    private Map<String, double[]> layout(List<BrdRequirement> reqs, List<BrdEdge> edges,
                                         List<BrdNodePosition> placed) {
        int n = reqs.size();
        Map<String, double[]> saved = new HashMap<>();
        for (BrdNodePosition p : safe(placed)) {
            if (p != null && p.requirementId() != null) {
                saved.put(idStr(p.requirementId()), new double[]{p.x(), p.y()});
            }
        }
        int[][] cell = place(layerOf(reqs, edges), n);
        Map<String, double[]> out = new HashMap<>();
        List<double[]> taken = new ArrayList<>();
        // EVERY hand-placed node is committed before a single automatic one is put anywhere. The
        // automatic pass has to see the whole occupied board up front, or a new requirement early in
        // the list would be dropped on top of a hand-placed node that merely comes later in it.
        for (BrdRequirement r : reqs) {
            double[] p = saved.get(idStr(r.id()));
            if (p != null) {
                out.put(idStr(r.id()), p);
                taken.add(p);
            }
        }
        int rowsPerColumn = Math.max(3, (int) Math.ceil(Math.sqrt(Math.max(1, n))));
        for (int i = 0; i < n; i++) {
            String id = idStr(reqs.get(i).id());
            if (out.containsKey(id)) {
                continue;
            }
            double[] p = firstFreeSlot(MARGIN + cell[i][0] * (NODE_W + GAP_X),
                MARGIN + cell[i][1] * (NODE_H + GAP_Y), rowsPerColumn, taken);
            out.put(id, p);
            taken.add(p);
        }
        return out;
    }

    /**
     * The first slot at or after {@code x,y} that no already-positioned node occupies: straight down
     * this column, then back to the top of the next column to the right.
     *
     * <p>Down first, right second, because a node pushed downwards is still in the dependency column
     * the layering pass chose for it, and the column is the part of the picture that carries meaning.
     */
    private static double[] firstFreeSlot(double x, double y, int rowsPerColumn, List<double[]> taken) {
        double column = x;
        double row = y;
        int rowsTried = 0;
        // Bounded rather than run to a fixed point: every step lands on a slot no earlier step used
        // (rows increase within a column, columns only ever increase), so one more slot than there
        // are obstacles is always enough — and a search with no bound over a list that grows with
        // the document is a hung tab, not a layout defect.
        for (int step = 0; step <= taken.size() * 2 + rowsPerColumn + 4; step++) {
            if (!collides(column, row, taken)) {
                return new double[]{column, row};
            }
            row += NODE_H + GAP_Y;
            if (++rowsTried > rowsPerColumn) {
                rowsTried = 0;
                column += NODE_W + GAP_X;
                row = MARGIN;
            }
        }
        return new double[]{column, row};
    }

    /** True when a node box at {@code x,y} would touch, or come within a hair of, one already there. */
    private static boolean collides(double x, double y, List<double[]> taken) {
        for (double[] t : taken) {
            if (Math.abs(t[0] - x) < NODE_W + CLEARANCE && Math.abs(t[1] - y) < NODE_H + CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    // ---- dragging a node ------------------------------------------------------------------------

    /**
     * How far the pointer must travel before the gesture stops being a click and becomes a drag.
     *
     * <p>The threshold is what keeps a click a click. No pointing device presses and releases on
     * exactly the same pixel — a trackpad in particular moves a pixel or two under the finger — so
     * with no threshold at all every attempt to open a requirement would instead nudge it a hair and
     * write a position, and the editor would become unreachable from the graph.
     *
     * <p>Measured in SCREEN pixels, deliberately, not content units: it is a fact about the human's
     * hand, so it must mean the same distance whether the canvas is zoomed in or out.
     */
    private static final double DRAG_THRESHOLD_PX = 4;

    /** The requirement id being dragged right now, or null when no gesture is in flight. */
    private String dragId;
    /** Node origin minus pointer, in content units, captured at grab — the node's "hold point". */
    private double dragGrabX;
    private double dragGrabY;
    /** Where the gesture currently has the node's top-left corner, in content coordinates. */
    private double dragX;
    private double dragY;
    /** Where the node was last PAINTED, which is what the live transform is measured from. */
    private double dragDrawnX;
    private double dragDrawnY;
    /** The group being moved. Replaced, not invalidated, when a redraw lands mid-gesture. */
    private Element dragGroup;
    private boolean dragMoved;
    private double dragFromClientX;
    private double dragFromClientY;
    /**
     * Set on the drop of a gesture that actually moved, so the click the browser fires immediately
     * afterwards does not also open the requirement in the editor.
     */
    private boolean swallowClick;
    /**
     * The group left carrying a drag transform by the last drop, and where that transform put it.
     *
     * <p>Between the drop and the redraw the save provokes, the node is NOT where it was painted —
     * it is where the transform moved it. A second grab in that window has to measure from here, or
     * the node jumps back to its painted position the instant the operator touches it again. The
     * pair goes stale by identity: once a redraw replaces the element, this reference simply stops
     * matching anything on screen.
     */
    private Element droppedGroup;
    private double droppedX;
    private double droppedY;

    /**
     * The move and release handlers, on the DOCUMENT and installed once.
     *
     * <p>Not on the node: a drag that leaves the box — or leaves the canvas entirely, which is how
     * one drags a node to the far side of a large graph — must keep tracking, and above all must
     * still hear the release. A mouseup missed because the pointer had wandered off the element
     * leaves a node glued to the cursor with no way out.
     */
    private void installDragHandlers() {
        HTMLDocument document = Window.current().getDocument();
        EventListener<MouseEvent> move = this::onDragMove;
        EventListener<MouseEvent> up = e -> onDrop();
        document.addEventListener("mousemove", move);
        document.addEventListener("mouseup", up);
        disposables.add(() -> {
            document.removeEventListener("mousemove", move);
            document.removeEventListener("mouseup", up);
        });
    }

    /** Makes one node group draggable. Only ever called for the live graph, never for a preview. */
    private void enableDrag(Element group, BrdRequirement r, double x, double y) {
        if (r.id() == null) {
            return;
        }
        String id = idStr(r.id());
        EventTarget target = group.cast();
        target.addEventListener("mousedown", (EventListener<MouseEvent>) e -> {
            // The canvas starts a pan on mousedown anywhere inside it, and this node is inside it.
            // Stopping the event here is what keeps that handler from ever seeing the press;
            // cancelPan() — which the framework put there for exactly this — is the belt to that
            // brace, and also ends a pan that was already running when the pointer arrived.
            e.stopPropagation();
            canvas.cancelPan();
            double cx = toContent(canvas.viewport(), e.getClientX(), e.getClientY(), 0);
            double cy = toContent(canvas.viewport(), e.getClientX(), e.getClientY(), 1);
            if (Double.isNaN(cx) || Double.isNaN(cy)) {
                return; // the canvas is not on screen yet; there is no sane place to drag to
            }
            // Where this node actually is: where it was painted, unless it is still carrying the
            // transform the previous drop left on it and the redraw has not caught up yet.
            boolean stillTransformed = group == droppedGroup;
            double fromX = stillTransformed ? droppedX : x;
            double fromY = stillTransformed ? droppedY : y;
            dragId = id;
            dragGroup = group;
            // The hold point, in content units. Keeping the grab OFFSET rather than the pointer's
            // start position is what makes the gesture survive a redraw that re-fits the canvas: the
            // node keeps the same relation to the pointer whatever the pan and scale become.
            dragGrabX = fromX - cx;
            dragGrabY = fromY - cy;
            dragX = fromX;
            dragY = fromY;
            // …but the transform is always measured from where the element was PAINTED, because that
            // is what it is applied to.
            dragDrawnX = x;
            dragDrawnY = y;
            dragMoved = false;
            dragFromClientX = e.getClientX();
            dragFromClientY = e.getClientY();
            // Every gesture starts able to select. This also clears a suppression left over from a
            // drag whose click never arrived because a redraw had replaced the element it was due
            // to fire on — otherwise it would swallow the operator's NEXT, genuine click.
            swallowClick = false;
        });
    }

    private void onDragMove(MouseEvent e) {
        if (dragId == null) {
            return;
        }
        if (!dragMoved) {
            if (Math.abs(e.getClientX() - dragFromClientX) < DRAG_THRESHOLD_PX
                    && Math.abs(e.getClientY() - dragFromClientY) < DRAG_THRESHOLD_PX) {
                return; // still a click as far as anyone can tell — do not move the node at all
            }
            dragMoved = true;
        }
        double cx = toContent(canvas.viewport(), e.getClientX(), e.getClientY(), 0);
        double cy = toContent(canvas.viewport(), e.getClientX(), e.getClientY(), 1);
        if (Double.isNaN(cx) || Double.isNaN(cy)) {
            return;
        }
        // Clamped at the origin: the canvas is fitted from a content box that starts there, so a
        // node at a negative coordinate is not further left, it is off the fitted picture — and the
        // operator would have no way to reach it to drag it back.
        dragX = Math.max(0, cx + dragGrabX);
        dragY = Math.max(0, cy + dragGrabY);
        if (dragGroup != null) {
            // The node moves by transforming its own group — no redraw per pointer move. Its edges
            // therefore stay where they were until the drop redraws the graph: moving one box is
            // worth doing sixty times a second, re-deriving the entire picture is not.
            dragGroup.setAttribute("transform",
                "translate(" + (dragX - dragDrawnX) + "," + (dragY - dragDrawnY) + ")");
        }
    }

    /** The release: one save per gesture, and only when the node actually went somewhere. */
    private void onDrop() {
        if (dragId == null) {
            return;
        }
        String id = dragId;
        boolean moved = dragMoved;
        double x = dragX;
        double y = dragY;
        Element group = dragGroup;
        endDrag();
        if (!moved) {
            // A click, not a drag. The click event that follows is left alone to open the editor.
            return;
        }
        swallowClick = true;
        droppedGroup = group;
        droppedX = x;
        droppedY = y;
        // Saved HERE, on the drop: one call per gesture rather than one per pointer move.
        //
        // On a GREEN THREAD, because this handler is not what it looks like. The move and up
        // listeners are registered with document.addEventListener — a raw TeaVM listener, not
        // zeroz4j's addDomEventListener, which is the thing that wraps a handler in threaded(...).
        // So despite being a DOM event, this runs as a native JS callback, and a suspending RMI
        // call made here throws "Suspension point reached from non-threading context". That is
        // exactly what happened: every drop threw, nothing was ever saved, and the next redraw put
        // the node back where the layout wanted it — a drag that looked like it worked and silently
        // did nothing.
        new Thread(() -> {
            try {
                String result = service.saveNodePosition(id, x, y);
                if (result != null && result.startsWith("error")) {
                    ClientLog.error("BrdView", "could not save where a requirement node was "
                        + "dropped — it has jumped back to where the layout puts it: " + result);
                    statusText.set(result);
                    // Put it back at once. The node is still sitting where it was dropped, and a
                    // picture showing a position the server refused to keep is a picture that lies
                    // until some unrelated edit happens to redraw it.
                    drawGraph();
                }
            } catch (Exception e) {
                ClientLog.error("BrdView",
                    "could not save where a requirement node was dropped: " + e);
                statusText.set("could not save the node position: " + e);
                drawGraph();
            }
        }).start();
    }

    private void endDrag() {
        dragId = null;
        dragGroup = null;
        dragMoved = false;
    }

    private void relayout() {
        try {
            String result = service.clearNodePositions();
            if (result != null && result.startsWith("error")) {
                ClientLog.error("BrdView", "could not clear the hand-placed node positions, so the "
                    + "graph is still arranged by hand: " + result);
                statusText.set(result);
                return;
            }
            statusText.set("laid out automatically — hand-placed positions cleared");
            fitView(fitW, fitH, true);
        } catch (Exception e) {
            ClientLog.error("BrdView", "could not clear the hand-placed node positions: " + e);
            statusText.set("could not re-lay out the graph: " + e);
        }
    }

    /**
     * A client (screen) coordinate in the canvas's own content coordinates — {@code axis} 0 for x, 1
     * for y — or NaN while the canvas has no rendered geometry to measure against.
     *
     * <p>This is the whole of the pan-and-zoom problem, and getting it wrong is what makes nodes jump
     * on the first drag of a canvas that has been panned or zoomed. The nodes live inside the
     * canvas's viewport {@code <g>}, which the framework moves and scales through a transform the
     * view cannot read — there are no accessors for the pan offset or the scale, and keeping a second
     * copy of them here would mean re-deriving them from every wheel and drag event the canvas
     * handles. The browser already holds the exact answer: the viewport's screen CTM IS the
     * content → client mapping, pan and scale and the SVG element's own position on the page all
     * folded together. So this simply inverts it — {@code content = (client − translate) / scale}.
     * Two divisions rather than a matrix solve because the canvas only ever translates and scales;
     * it never rotates or skews.
     */
    @JSBody(params = {"viewport", "clientX", "clientY", "axis"}, script =
        "var m = viewport.getScreenCTM();"
        + "if (!m || !m.a || !m.d) { return NaN; }"
        + "return axis === 0 ? (clientX - m.e) / m.a : (clientY - m.f) / m.d;")
    private static native double toContent(Element viewport, double clientX, double clientY, int axis);

    // ---- fit and zoom -----------------------------------------------------------------------------

    /** The picture is never blown up past this when it fits with room to spare. */
    private static final double FIT_MAX_SCALE = 1.6;
    private static final double MIN_SCALE = 0.15;
    private static final double MAX_SCALE = 4.0;
    private static final double FIT_PADDING = 24;

    /** Content size the last draw asked to have fitted. */
    private double fitW;
    private double fitH;
    /** The transform the last fit left on the picture; any other transform means the operator moved it. */
    private String fitTransform;

    /**
     * Scales and centres the whole picture in the canvas, growing it as well as shrinking it.
     *
     * <p>{@code SvgCanvas.fit} only ever shrinks (its scale is capped at 1), so a small graph sat at
     * natural size in the middle of a wide panel, and a large one was fitted once, at whatever width
     * the panel had then. This does the same job without the cap, and is called again when the panel
     * changes size. The canvas's own wheel zoom and drag pan are untouched.
     *
     * @param force refit even when the operator has zoomed or panned since the last fit
     */
    private void fitView(double contentW, double contentH, boolean force) {
        fitW = contentW;
        fitH = contentH;
        String now = canvas.viewport().getAttribute("transform");
        boolean touched = fitTransform != null && now != null && !fitTransform.equals(now);
        if (touched && !force) {
            return;   // keep the view the operator chose through edits and drags
        }
        int w = canvas.getElement().getOffsetWidth();
        int h = canvas.getElement().getOffsetHeight();
        if (w == 0 || h == 0 || contentW <= 0 || contentH <= 0) {
            return;   // not on screen yet; the size watcher fits it once it is
        }
        double scale = Math.max(MIN_SCALE, Math.min(FIT_MAX_SCALE,
            Math.min((w - FIT_PADDING) / contentW, (h - FIT_PADDING) / contentH)));
        canvas.setView(-(w - contentW * scale) / (2 * scale), -(h - contentH * scale) / (2 * scale),
            scale);
        fitTransform = canvas.viewport().getAttribute("transform");
    }

    /** Called when the canvas changes size: refit, unless the operator has moved the picture. */
    private void refitIfUntouched() {
        if (fitW > 0) {
            fitView(fitW, fitH, false);
        }
    }

    /** Zooms about the middle of the canvas, like the keyboard's plus and minus do. */
    private void zoomBy(double factor) {
        Element vp = canvas.viewport();
        double scale = transformPart(vp, 2);
        if (Double.isNaN(scale) || scale <= 0) {
            return;
        }
        double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        double cx = canvas.getElement().getOffsetWidth() / 2.0;
        double cy = canvas.getElement().getOffsetHeight() / 2.0;
        double panX = cx - (cx - transformPart(vp, 0)) * (newScale / scale);
        double panY = cy - (cy - transformPart(vp, 1)) * (newScale / scale);
        canvas.setView(-panX / newScale, -panY / newScale, newScale);
    }

    /** Part of the viewport's transform: 0 = pan x, 1 = pan y, 2 = scale. NaN if there is none yet. */
    @JSBody(params = {"viewport", "part"}, script =
        "var t = viewport.getAttribute('transform');"
        + "if (!t) { return part === 2 ? 1 : 0; }"
        + "var m = /translate\\(([-0-9.e]+),([-0-9.e]+)\\) scale\\(([-0-9.e]+)\\)/.exec(t);"
        + "return m ? parseFloat(m[part + 1]) : NaN;")
    private static native double transformPart(Element viewport, int part);

    /** Something to call when an element changes size. */
    @JSFunctor
    private interface SizeCallback extends JSObject {
        void changed();
    }

    @JSBody(params = {"element", "callback"}, script =
        "if (typeof ResizeObserver === 'undefined') { return null; }"
        + "var o = new ResizeObserver(function () { callback(); });"
        + "o.observe(element);"
        + "return o;")
    private static native JSObject observeSize(Element element, SizeCallback callback);

    @JSBody(params = {"observer"}, script = "if (observer) { observer.disconnect(); }")
    private static native void stopObserving(JSObject observer);

    private static int round(double value) {
        return (int) Math.round(value);
    }

    // ---- right pane: editor OR history --------------------------------------------------------

    /**
     * The one requirement editor, shared with the list (see {@link RequirementForm}).
     *
     * <p>It used to be written out inside this class, which made the diagram the only place a
     * requirement could be written at all - the list an operator lands on had no way in, and every
     * edit began with finding the Graph tab.
     */
    private final RequirementForm form =
        new RequirementForm("graph", selected, this::liveSelected, statusText, () -> { });
    private final Div historyList = new Div();

    private Div rightPane() {
        Div editor = editorPane();
        Div history = historyPane();
        Div host = new Div();
        host.addClassName("flex flex-col min-h-0 h-full");
        host.add(editor, history);
        disposables.add(Effect.create(() -> {
            boolean h = Boolean.TRUE.equals(historyMode.get());
            editor.setVisible(!h);
            history.setVisible(h);
            if (h) {
                loadHistory();
            }
        }));
        return host;
    }

    private Div editorPane() {
        Div pane = new Div();
        pane.addClassName("flex flex-col min-h-0 h-full overflow-y-auto");

        Div empty = new Div();
        empty.addClassName("flex flex-col gap-3");
        empty.add(new EmptyState("list", "Requirements",
            "The project's living BRD: a graph of requirement objects rather than a document. "
            + "Most projects start from a document you already have."));
        // The upload lived behind an unlabelled icon in the header, so the primary way to get
        // requirements in was invisible on the one screen that exists to explain it. Offer the
        // real action here, not a description of where to find it.
        Button uploadButton = new Button("Analyse requirement documents");
        uploadButton.addClassName("btn-primary btn-sm self-center");
        // Named, because its words are the analysis dialog's own title as well, and there is more
        // than one of that dialog on the page now — the guide composes one too.
        uploadButton.getElement().setAttribute("data-testid", "brd-empty-analyse");
        uploadButton.addClickListener(e -> onAnalyse.run());
        empty.add(uploadButton);
        Div emptyHint = new Div();
        emptyHint.addClassName("text-[11px] text-base-content/50 text-center leading-relaxed "
            + "max-w-md self-center");
        emptyHint.setText("Add the specs, briefs and notes you already have - markdown, text, PDF, "
            + "DOCX or a photo of a whiteboard - say what each one is for, and the analyst drafts "
            + "requirements you review before anything is written. You can also add one by hand "
            + "with + above, or drop a file anywhere on this panel. The clock icon browses history.");
        empty.add(emptyHint);
        Div emptyStatus = new Div();
        emptyStatus.addClassName("text-xs text-center text-base-content/60");
        empty.add(emptyStatus);
        disposables.add(Effect.create(() -> emptyStatus.setText(nz(statusText.get()))));

        // The form shows and hides itself from the same signal; only the empty state is this
        // pane's business.
        disposables.add(Effect.create(() -> empty.setVisible(selected.get() == null)));

        pane.add(form, empty, edgesPanel());
        return pane;
    }

    // ---- edges editor -------------------------------------------------------------------------

    private final Select edgeTargetSelect = new Select();
    private final Select edgeRelationSelect = new Select();
    private final Div edgeList = new Div();
    /** Why the last "Add edge" was refused, shown in this panel rather than beside Save. */
    private final Div edgeStatus = new Div();
    private final ValueSignal<String> edgeStatusText = new ValueSignal<>("");

    private Div edgesPanel() {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-2 p-3 border-t border-base-300");
        Span heading = new Span("How this connects to other requirements");
        heading.addClassName("text-xs font-semibold uppercase tracking-wider text-base-content/50");
        panel.getElement().appendChild(heading.getElement());

        edgeList.addClassName("flex flex-col gap-1");
        panel.add(edgeList);

        edgeRelationSelect.addClassName("select select-bordered select-xs");
        edgeRelationSelect.setItems(relationLabels());
        edgeRelationSelect.getElement().setAttribute("data-testid", "brd-edge-relation");
        edgeTargetSelect.addClassName("select select-bordered select-xs flex-1");
        edgeTargetSelect.getElement().setAttribute("data-testid", "brd-edge-target");
        Button add = new Button("Add link");
        add.addClassName("btn-outline btn-xs");
        add.getElement().setAttribute("data-testid", "brd-edge-add");
        add.addClickListener(e -> addEdge());
        // The refusal belongs HERE, with the controls that were used (UX v3 D4). It used to go to
        // the form's status line, which sits beside Save roughly 400px further up and scrolls out of
        // view: pressing "Add edge" on a link the rules forbid looked exactly like pressing it on a
        // link that worked — nothing appeared in the list, and the reason was off screen. Three
        // rules are enforced through this one button (one place in the hierarchy, no loops, only a
        // quality requirement may constrain another) and all three were silent.
        //
        // ABOVE the row rather than below it: this panel is the last thing in a scrolling pane, so
        // the button often sits on the bottom edge of the window and anything under it is off the
        // screen — which is the same defect again, just closer.
        edgeStatus.addClassName("text-[11px] leading-snug text-error");
        edgeStatus.getElement().setAttribute("data-testid", "brd-edge-status");
        panel.add(edgeStatus);

        Div addRow = new Div();
        addRow.addClassName("flex items-center gap-2");
        addRow.add(edgeRelationSelect, edgeTargetSelect, add);
        panel.add(addRow);

        disposables.add(Effect.create(() -> {
            panel.setVisible(selected.get() != null && preview.get() == null);
            String message = nz(edgeStatusText.get());
            edgeStatus.setText(message);
            edgeStatus.setVisible(!message.isEmpty());
            renderEdges();
        }));
        return panel;
    }

    private void renderEdges() {
        edgeList.getElement().setInnerHTML("");
        BrdRequirement sel = selected.get();
        Brd graph = brd.get();
        if (sel == null || sel.id() == null) {
            edgeList.add(muted("Save this requirement first to connect it."));
            edgeTargetSelect.setItems(List.of());
            return;
        }
        Map<String, String> handleById = new HashMap<>();
        List<String> targetOptions = new ArrayList<>();
        for (BrdRequirement r : safe(graph.requirements())) {
            handleById.put(idStr(r.id()), r.handle() + " · " + displayTitle(r));
            if (!r.id().equals(sel.id())) {
                targetOptions.add(r.handle() + " · " + displayTitle(r));
            }
        }
        edgeTargetSelect.setItems(targetOptions);

        boolean any = false;
        for (BrdEdge edge : safe(graph.edges())) {
            if (!sel.id().equals(edge.from())) {
                continue;
            }
            any = true;
            Div row = new Div();
            row.addClassName("flex items-center gap-2 text-xs");
            Span rel = new Span(edge.relation() == null ? "" : edge.relation().label());
            rel.addClassName("badge badge-xs");
            rel.getElement().setAttribute("style", "color:" + relationColor(edge.relation()));
            Span target = new Span("→ " + handleById.getOrDefault(idStr(edge.to()), idStr(edge.to())));
            target.addClassName("truncate flex-1");
            row.getElement().appendChild(rel.getElement());
            row.getElement().appendChild(target.getElement());
            Div del = new Div();
            del.addClassName("cursor-pointer text-base-content/40 hover:text-error");
            del.add(Icon.of("x", "w-3 h-3"));
            del.addDomEventListener("click", e -> service.deleteEdge(edge));
            row.add(del);
            edgeList.add(row);
        }
        if (!any) {
            edgeList.add(muted("Not connected to anything yet."));
        }
    }

    private void addEdge() {
        BrdRequirement sel = selected.get();
        if (sel == null) {
            return;
        }
        String targetLabel = edgeTargetSelect.getValue();
        if (targetLabel == null || targetLabel.isEmpty()) {
            return;
        }
        UUID targetId = null;
        for (BrdRequirement r : safe(brd.get().requirements())) {
            if ((r.handle() + " · " + displayTitle(r)).equals(targetLabel)) {
                targetId = r.id();
                break;
            }
        }
        if (targetId == null) {
            return;
        }
        // Read back through the enum, so the words offered and the words understood are one list.
        RequirementRelation relation = RequirementRelation.of(edgeRelationSelect.getValue());
        if (relation == null) {
            relation = RequirementRelation.DEPENDS_ON;
        }
        String result = service.saveEdge(new BrdEdge(sel.id(), targetId, relation));
        if (result != null && result.startsWith("error")) {
            // A rejected edge leaves the graph looking merely unconnected, which reads as an
            // omission by whoever drew it rather than as a failure.
            ClientLog.error("BrdView", "could not add a " + relation + " edge from " + sel.handle()
                + " — the two requirements are still unconnected: " + result);
            // "error: " is the wire's prefix for a failed call, not a word for a person to read.
            edgeStatusText.set(stripErrorPrefix(result));
        } else {
            edgeStatusText.set("");
        }
    }

    /** The server's refusal without the wire prefix a person has no use for. */
    private static String stripErrorPrefix(String result) {
        String text = nz(result).trim();
        return text.startsWith("error:") ? text.substring("error:".length()).trim() : text;
    }

    // ---- history + restore --------------------------------------------------------------------

    private Div historyPane() {
        Div pane = new Div();
        pane.addClassName("flex flex-col min-h-0 h-full overflow-y-auto");
        Div head = new Div();
        head.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300");
        Span h = new Span("History");
        h.addClassName("text-xs font-semibold uppercase tracking-wider text-base-content/50 flex-1");
        head.getElement().appendChild(h.getElement());
        Button live = new Button("Back to live");
        live.addClassName("btn-ghost btn-xs");
        live.addClickListener(e -> {
            preview.set(null);
            historyMode.set(false);
        });
        head.add(live);
        pane.add(head);
        historyList.addClassName("flex flex-col gap-1 p-2");
        pane.add(historyList);
        return pane;
    }

    private void loadHistory() {
        historyList.getElement().setInnerHTML("");
        List<BrdRevision> revisions;
        try {
            revisions = service.history();
        } catch (Exception e) {
            historyList.add(muted("Could not load history: " + e.getMessage()));
            return;
        }
        if (revisions == null || revisions.isEmpty()) {
            historyList.add(muted("No revisions yet — edits will appear here."));
            return;
        }
        for (BrdRevision rev : revisions) {
            Div row = new Div();
            row.addClassName("flex items-center gap-2 px-2 py-1.5 rounded-lg text-xs "
                + "hover:bg-base-300/50 cursor-pointer");
            // Which revision this row IS. Every row here reads "rev <n> · <summary>" and carries an
            // identical restore control, so nothing but the number tells them apart — and a test
            // that reaches for the control by its label gets whichever row is first, silently
            // restoring the newest revision while appearing to restore the chosen one.
            row.getElement().setAttribute("data-revision", String.valueOf(rev.revision()));
            Span badge = new Span(rev.author() == null ? "?" : rev.author());
            badge.addClassName("badge badge-xs " + authorBadge(rev.author()));
            row.getElement().appendChild(badge.getElement());
            Span summary = new Span("rev " + rev.revision() + " · "
                + (rev.summary() == null ? "" : rev.summary()));
            summary.addClassName("truncate flex-1");
            row.getElement().appendChild(summary.getElement());
            Div restore = new Div();
            restore.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
            restore.add(Icon.of("refresh", "w-3.5 h-3.5"));
            restore.getElement().setAttribute("title", "Restore this revision");
            restore.getElement().setAttribute("data-restore", String.valueOf(rev.revision()));
            restore.addDomEventListener("click", e -> {
                e.stopPropagation();
                // Asked first, and told what it will take away (25.6). Restoring is the one action
                // that moves the whole document backwards, and it said nothing at all: the two
                // requirements written minutes earlier simply were not there any more. The sentence
                // is the server's, because only the server can compare the two versions.
                String cost;
                try {
                    cost = nz(service.restoreSummary(rev.revision()));
                } catch (Exception ex) {
                    cost = "This puts the requirements back to how they were at revision "
                        + rev.revision() + ". Nothing is deleted: it is saved as a new revision, "
                        + "and restoring a later one brings everything back.";
                }
                ConfirmDialog.ask(this, "Go back to revision " + rev.revision() + "?", cost,
                    "Go back to it", "brd-restore-impact", () -> {
                        service.restore(rev.revision());
                        preview.set(null);
                        historyMode.set(false);
                    });
            });
            row.add(restore);
            long r = rev.revision();
            row.addDomEventListener("click", e -> preview.set(service.revisionAt(r)));
            historyList.add(row);
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    private void newRequirement() {
        BrdRequirement fresh = new BrdRequirement(null, null, "New requirement", "",
            Priority.MEDIUM, RequirementStatus.DRAFT, null);
        select(fresh);
    }

    /**
     * Puts one requirement in the editor. The form fills itself from the signal, so this is only
     * the two things that are the DIAGRAM's business.
     */
    private void select(BrdRequirement r) {
        selected.set(r);
        statusText.set("");
        // A refusal is about the requirement it was refused on; carrying it to the next one would
        // accuse a link nobody has tried yet.
        edgeStatusText.set("");
    }

    // ---- small helpers ------------------------------------------------------------------------

    /**
     * The selection as it stands in the LIVE graph. The selection signal holds the instance that was
     * clicked; criteria are mutated server-side, so reading through the graph is what makes the
     * criteria list redraw itself after every criterion edit (it also registers the dependency).
     */
    private BrdRequirement liveSelected() {
        BrdRequirement sel = selected.get();
        Brd graph = brd.get();
        if (sel == null || sel.id() == null || graph == null) {
            return sel;
        }
        for (BrdRequirement r : safe(graph.requirements())) {
            if (sel.id().equals(r.id())) {
                return r;
            }
        }
        return sel;
    }

    /** The signal redraws this view; this only surfaces an error the operator must act on. */
    private void report(String result) {
        if (result != null && result.startsWith("error")) {
            // The status line is the operator's signal and stays. The log is for afterwards: a
            // refused edit is cleared by the next action, so without this there is no record that
            // the criterion or edge the operator believes they saved was rejected.
            ClientLog.error("BrdView", "a BRD edit was refused by the server: " + result);
        }
        statusText.set(result != null && result.startsWith("error") ? result : "");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private Div iconButton(String glyph, String tooltip, Runnable action) {
        Div b = new Div();
        b.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
        b.add(Icon.of(glyph, "w-4 h-4"));
        b.getElement().setAttribute("title", tooltip);
        b.addDomEventListener("click", e -> action.run());
        return b;
    }

    /** A small text button for the toolbar, where no icon says it. */
    private Div textButton(String label, String tooltip, String id, Runnable action) {
        Div b = new Div();
        b.addClassName("cursor-pointer text-[12px] leading-none px-1 text-base-content/40 "
            + "hover:text-primary");
        b.getElement().appendChild(new Span(label).getElement());
        b.getElement().setAttribute("title", tooltip);
        b.getElement().setAttribute("data-testid", "graph-zoom-" + id);
        b.addDomEventListener("click", e -> action.run());
        return b;
    }

    private Span muted(String text) {
        Span s = new Span(text);
        TextStyle.CAPTION.applyTo(s);
        return s;
    }

    private Element text(int x, int y, String content, int size, String fill, String anchor) {
        Element t = SvgCanvas.el("text",
            "x", String.valueOf(x), "y", String.valueOf(y),
            "text-anchor", anchor, "fill", fill, "font-size", String.valueOf(size),
            "font-family", "ui-sans-serif, system-ui, sans-serif");
        t.appendChild(Window.current().getDocument().createTextNode(content));
        return t;
    }

    /**
     * Clicking a node opens it in the editor — unless the "click" was the tail of a drag.
     *
     * <p>The browser fires a click after every press-and-release on the same element, drag or not, so
     * telling the two apart is not optional: without this, every reposition would also re-open the
     * requirement and overwrite whatever the operator had half-typed in the form.
     */
    private void onNodeClick(Element element, BrdRequirement r) {
        EventTarget target = element.cast();
        target.addEventListener("click", e -> {
            if (swallowClick) {
                swallowClick = false;
                return;
            }
            select(r);
        });
        // "grab" rather than "pointer": the node is both clickable and draggable, and the drag is
        // the affordance nothing else on screen hints at.
        element.setAttribute("style", "cursor:grab");
    }

    private static String idStr(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static String displayTitle(BrdRequirement r) {
        if (r.title() != null && !r.title().isEmpty()) {
            return r.title();
        }
        return r.text() == null ? "(untitled)" : r.text();
    }

    private static String authorBadge(String author) {
        if (author == null) {
            return "";
        }
        return switch (author) {
            case "human" -> "badge-primary";
            case "agent" -> "badge-secondary";
            case "restore" -> "badge-warning";
            default -> "badge-ghost";
        };
    }

    private static String priorityColor(Priority priority) {
        if (priority == null) {
            return "#94a3b8";
        }
        return switch (priority) {
            case CRITICAL -> "#ef4444";
            case HIGH -> "#f59e0b";
            case MEDIUM -> "#3b82f6";
            default -> "#94a3b8";
        };
    }

    private static String relationColor(RequirementRelation relation) {
        if (relation == null) {
            return "#94a3b8";
        }
        return switch (relation) {
            case DEPENDS_ON -> "#f59e0b";
            case REFINES -> "#3b82f6";
            case CONFLICTS_WITH -> "#ef4444";
            case DERIVED_FROM -> "#10b981";
            case GATES -> "#a855f7";
        };
    }

    /**
     * What one kind of link between two requirements means, for the legend's hover text.
     *
     * <p>Written for somebody who has never read the source. It used to explain each one in terms of
     * "the source" and "the target" and named two of the five by their Java constant.
     */
    private static String relationMeaning(RequirementRelation relation) {
        if (relation == null) {
            return "";
        }
        return switch (relation) {
            case DEPENDS_ON -> "This one cannot be delivered until the other one is. It is what "
                + "decides the order work is done in, so it is the one link that must not be a "
                + "guess.";
            case REFINES -> "This one is a piece of the other: a big requirement broken into the "
                + "smaller ones that make it up.";
            case CONFLICTS_WITH -> "The two pull against each other and cannot both be fully met. "
                + "It works the same way round both ways, so it does not affect the layout.";
            case DERIVED_FROM -> "This one was worked out from the other - for example a technical "
                + "requirement drawn out of something the business asked for.";
            case GATES -> "This is a quality bar - a speed, a limit, a standard - that the other "
                + "requirement and every part of it must meet. It is how one bar applies to a "
                + "whole area at once.";
        };
    }


    /** Every relation in the operator's words, in the order the enum declares them. */
    private static List<String> relationLabels() {
        List<String> labels = new ArrayList<>();
        for (RequirementRelation relation : RequirementRelation.values()) {
            labels.add(relation.label());
        }
        return labels;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
