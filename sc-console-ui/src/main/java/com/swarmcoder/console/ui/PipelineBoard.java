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
import com.swarmcoder.domain.Task;
import com.swarmcoder.console.api.BacklogService;
import com.swarmcoder.console.api.BacklogService_Stub;
import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.console.api.BrdSignals;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.swarmcoder.console.api.ReadinessSignals;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryGraph;
import com.swarmcoder.domain.StoryState;
import com.zeroz4j.ui.theme.Emphasis;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import org.teavm.jso.browser.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The pipeline: every story from the moment it is suggested to the moment it is delivered, on one
 * board, moving only sideways (UX v3 §3.1).
 *
 * <h2>Why one board</h2>
 *
 * <p>This replaces two: a planning board that held the stories waiting on a decision, and a build
 * stage that held the ones in flight. A story crossed between them by DISAPPEARING from one and
 * reappearing in the other, which the operator reported four separate times in the same words —
 * <em>"it vanished! now what?"</em> — and which no amount of toast could fix, because a message
 * saying where something went is an admission that it went somewhere you were not looking.
 *
 * <p>Splitting them also split the truth. Each board decided for itself whether a run was still
 * alive, so one story read "building now · 9h 5m" on one screen and "stopped" on the other, at the
 * same moment, and nothing on either said which to believe. Here there is one derivation
 * ({@link BuildState}) rendered once.
 *
 * <h2>The columns are the lifecycle; the badge is the health</h2>
 *
 * <p>Five columns, one per stage of a story's life, and a story is in exactly the one its state puts
 * it in. What is HAPPENING to a building story — working, paused, stopped, needs-you — is a badge and
 * a sentence on its card and never a different location, because the moment health decides position,
 * a model server going down moves six cards and the operator has to find them again.
 *
 * <p>Each column carries the question it asks as its caption, so the board is readable from its five
 * headings without reading a single card.
 *
 * <h2>Movement is shown, not narrated</h2>
 *
 * <p>Acting on a card moves it one column right (or left) and {@link #highlight} makes it flash
 * there. That replaces the whole family of "S1 has left this list and is now in…" notices, which
 * existed only because the thing being described was off-screen.
 *
 * <p>Bound to the server-authoritative {@link BacklogSignals#CURRENT} and to {@link RunsStore}, so it
 * redraws whenever anything changes either. Both signals are process-wide, so the {@link Effect}s
 * bound to them outlive this view unless torn down — callers MUST {@link #dispose()}, and
 * {@code PlanStage} does.
 */
final class PipelineBoard extends Div implements Disposable {

    /**
     * The five columns, in the order a story moves through them.
     *
     * <p>Captions are questions, deliberately, and they are the operator's questions rather than the
     * state machine's: "real work?" not "triage DRAFT", "did it deliver what was asked?" not "record
     * a verdict". §4 of the design is the complete vocabulary and no internal name may appear here.
     */
    private enum Column {
        SUGGESTED("suggested", "Suggested", "The planner wrote these. Real work?"),
        READY("ready", "Ready to build", "Agreed work. Build when ready."),
        BUILDING("building", "Building", "Agents at work. Nothing to do unless it asks."),
        /**
         * A build has finished and the next move is the operator's — whether it delivered (judge it)
         * or stopped short (build it again). Both are "it came back", and which one is on the card's
         * badge rather than in its column: a story that failed its gates has not moved backwards in
         * its life, it has come back with bad news.
         */
        CAME_BACK("came-back", "Came back", "Judge it. Did it deliver what was asked?"),
        DELIVERED("delivered", "Delivered", "Done.");

        private final String id;
        private final String label;
        private final String caption;

        Column(String id, String label, String caption) {
            this.id = id;
            this.label = label;
            this.caption = caption;
        }
    }

    /** Where this browser remembers that the orientation line has been read. */
    private static final String ORIENTATION_KEY = "console.pipeline.orientation";

    /** How long a moved card keeps its highlight. Long enough to catch the eye, short enough to go. */
    private static final int HIGHLIGHT_MS = 2600;

    /** How often the runs list is refreshed while something is building. */
    private static final int POLL_MS = 6000;

    /**
     * How long to wait before retrying a failed backlog publish once. A single automatic retry
     * covers the ordinary transient case (the server socket was mid-reconnect); trying forever
     * would just repeat the same log line on a loop for a failure that a retry cannot fix (a
     * malformed response never becomes well-formed on the next attempt).
     */
    private static final int BACKLOG_RETRY_MS = 4000;

    private final BacklogService service = new BacklogService_Stub();
    /** Only for answering a build's questions, which is the one thing on this board it owns. */
    private final ControlService control = new ControlService_Stub();
    private final ValueSignal<Backlog> backlog = BacklogSignals.CURRENT;
    private final List<Disposable> disposables = new ArrayList<>();
    private final Div columns = new Div();
    private final Div orientation = new Div();
    /**
     * Questions that belong to no story - a budget exhaustion, in practice, minted once by the cloud
     * gate with no run attached.
     *
     * <p>It cannot go on a card, because there is no card it is about. It gets a banner instead rather
     * than being dropped for not fitting the shape: rule 1 says nothing vanishes, and the Approval
     * Center it used to live in has been retired as a destination.
     */
    private final Div orphans = new Div();
    /** The settled tail: dropped stories, counted and openable — never resident (rule 1). */
    private final Div settled = new Div();
    private final PlanningWizard wizard = new PlanningWizard();
    /**
     * "Build all of this without asking me", and the way to stop it.
     *
     * <p>The switch itself is offered on the guide's document step, where the operator has just
     * loaded the thing they want built. It is reachable HERE as well for one reason: this is the
     * screen you are looking at while it runs, and a way to stop something that lives on a screen
     * you are not on is not a way to stop it.
     */
    private final AutonomousDialog autonomy = new AutonomousDialog();
    /** Holds at most one modal at a time; see {@link #mount}. */
    private final Div dialogHost = new Div();

    private final Div notice = new Div();
    private final Div noticeMark = new Div();
    private final Div noticeText = new Div();

    /**
     * The story that has just moved, so the render can flash it in its NEW column.
     *
     * <p>This is the whole of "movement is shown, not narrated": the operator pressed a button on a
     * card in one column and the answer is that same card, lit up, one column over.
     */
    private UUID moved;

    /** Stops the runs poll rescheduling itself once this board is gone. */
    private boolean disposed;

    /**
     * The run graph open over the board, so every close path can release the effects it holds.
     *
     * <p>{@link RunView} binds effects to the process-wide run-graph signal; one that is merely
     * hidden goes on redrawing DOM nobody is looking at, and each peek would leak another. §3.2 names
     * this as a rule for anything mounted in a dialog over this board, so the two fields exist to
     * make the release possible rather than hopeful.
     */
    private RunView openGraph;
    private Dialog openGraphDialog;
    /** Whatever is in {@link #dialogHost} right now; see the close listeners for why. */
    private Dialog mountedDialog;

    PipelineBoard() {
        addClassName("flex flex-col h-full min-h-0");
        getElement().setAttribute("data-testid", "pipeline-board");
        add(header());
        add(orientationBar());
        add(noticeBar());
        orphans.addClassName("shrink-0 mx-3 mt-3 rounded-lg border border-warning/40 bg-warning/10 "
            + "px-3 py-2 flex flex-col gap-1.5");
        orphans.getElement().setAttribute("data-testid", "pipeline-orphan-questions");
        orphans.setVisible(false);
        add(orphans);
        columns.addClassName("flex-1 min-h-0 overflow-auto flex flex-row gap-3 p-3 items-start");
        add(columns);
        settled.addClassName("shrink-0 flex flex-wrap items-center gap-2 px-4 py-1.5 "
            + "border-t border-base-300 text-[11px] text-base-content/50");
        settled.getElement().setAttribute("data-testid", "pipeline-settled");
        settled.setVisible(false);
        add(settled);
        // One component carrying both its modals, mounted last by habit — display:contents, so it
        // takes no room in the flex column wherever it goes.
        add(wizard, autonomy, dialogHost);
        wizard.onApplied(applied -> say(applied, false));

        disposables.add(Effect.create(this::render));
        // Direct RMI is safe here: stages are only ever constructed from a DOM handler or a green
        // thread — see StageHost's javadoc.
        requestBacklogPublish(false);
        // The runs list is what makes a BUILDING card say anything: a run changes phase without
        // touching the backlog, so without this the card would sit on "reading the story" for an
        // hour. Fetched once on the way in, because a board judged against the PREVIOUS run reports
        // a build that has just begun as stopped.
        MainView.refreshRuns();
        poll();
    }

    @Override
    public void dispose() {
        disposed = true;
        closeRunGraph();
        wizard.dispose();
        autonomy.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /**
     * Opens a build's graph OVER the board.
     *
     * <p>Over, not away. Watching what a swarm is doing used to mean leaving for another stage, which
     * is the navigation §3.1 removes: the board keeps its full width and stays visible behind, so
     * looking costs no screen and no place in the operator's train of thought.
     */
    private void openRunGraph(String runId) {
        closeRunGraph();
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        RunView graph = new RunView(runId);
        Div frame = new Div();
        frame.addClassName("h-[70vh] flex flex-col min-h-0");
        frame.add(graph);
        dialog.add(frame);
        // Close is the primary action: a modal blocks the page, which is what a modal is for, and one
        // with no way out traps the whole Console — the smoke test pins that.
        Button close = new Button("Close", e -> closeRunGraph());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        // Escape and a click outside now close the dialog without going through the Close button,
        // so the release has to hang off the close itself.
        // Guarded on identity. A close listener runs on a green thread, so it can arrive
        // AFTER whatever replaced this dialog has been mounted and opened — and would then
        // tear down its successor. It only acts while this is still the dialog on screen.
        dialog.addCloseListener(e -> {
            if (openGraphDialog == dialog) {
                closeRunGraph();
            }
        });
        mount(dialog);
        openGraph = graph;
        openGraphDialog = dialog;
        dialog.open();
    }

    private void closeRunGraph() {
        if (openGraphDialog != null) {
            openGraphDialog.close();
        }
        if (openGraph != null) {
            openGraph.dispose();
        }
        openGraph = null;
        openGraphDialog = null;
        mountedDialog = null;
        dialogHost.removeAll();
        // A drill-down opened from the graph is drawn over the graph; the graph is gone.
        Inspector.close();
    }

    /**
     * Asks the server to (re-)publish the backlog onto {@link BacklogSignals#CURRENT}.
     *
     * <p>This call's own return value is never used — the board renders from the signal, not from
     * here — but the RMI stub still decodes the response, and it is exactly that decode that fails
     * when the wire schema this browser was built against does not match what the server just sent
     * (see DEVELOPER_CORRECTIONS.md: a Story field added the same day this bundle was last built
     * shifts every field the generated reader expects after it, and it does not always fail loudly —
     * one shifted field landed a plain empty string on a completely different one).
     *
     * <p>That kind of failure is not something a retry fixes: the bytes are wrong the same way every
     * time until the browser bundle and the server are rebuilt together. A transient one (the socket
     * was still finishing a reconnect) is, so this tries once more before giving up — see the
     * {@code DisconnectedException} pair logged a few seconds into any restart, which is exactly this
     * window and always clears on its own. On a second failure it leaves a notice up rather than
     * leaving the board silently on stale or empty data: before this, the only record of the failure
     * was a browser console line nobody reads, and "the columns stay empty" carried no indication
     * anything was wrong.
     */
    private void requestBacklogPublish(boolean isRetry) {
        if (disposed) {
            return;
        }
        try {
            service.backlog();
            if (isRetry) {
                say("", false); // clear whatever notice the failed first attempt left up
            }
        } catch (Exception e) {
            ClientLog.warn("PipelineBoard", "could not ask the server to publish the backlog; the "
                + "columns stay empty until the next publish: " + e);
            if (!isRetry) {
                Window.setTimeout(() -> requestBacklogPublish(true), BACKLOG_RETRY_MS);
            } else {
                say("The board could not load the latest backlog from the server. What is shown "
                    + "here may be out of date — try reloading the page.", true);
            }
        }
    }

    /**
     * Keeps the runs list current while anything is building, and only then — a board that polls
     * forever is a background cost paid on every idle project.
     *
     * <p>{@code refreshRuns} makes the RMI call on its own thread, which is what makes this legal
     * from a {@code setTimeout} callback: a suspending call made directly from one throws
     * "Suspension point reached from non-threading context".
     */
    private void poll() {
        if (disposed) {
            return;
        }
        Backlog current = backlog.get();
        if (current != null) {
            for (Story story : current.stories()) {
                if (story.state() == StoryState.RUNNING) {
                    MainView.refreshRuns();
                    break;
                }
            }
        }
        Window.setTimeout(this::poll, POLL_MS);
    }

    /** Opens the planning wizard — the board's header action, and what fills SUGGESTED. */
    void openPlanningWizard() {
        wizard.open();
    }

    // --- chrome -------------------------------------------------------------------------------

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-3 px-4 py-2 border-b border-base-300 shrink-0 "
            + "bg-base-100");

        Div titles = new Div();
        titles.addClassName("min-w-0");
        Span title = new Span("Pipeline");
        title.addClassName("text-sm font-semibold");
        titles.getElement().appendChild(title.getElement());
        Div caption = new Div("Every story from suggestion to delivery. Nothing leaves this board "
            + "until it is delivered or dropped.");
        TextStyle.CAPTION.applyTo(caption);
        titles.add(caption);
        bar.add(titles);

        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);

        Button alone = new Button("Building on its own");
        alone.addClassName("btn-xs btn-ghost");
        alone.getElement().setAttribute("data-testid", "pipeline-autonomous");
        alone.getElement().setAttribute("title",
            "Whether SwarmCoder is deciding things for you, what it has decided, and how to stop "
                + "it.");
        alone.addClickListener(e -> autonomy.open());
        // And gone while it IS running: the strip across the top of every screen then says the same
        // four words, carries the same way in to the record, and has the Stop button on it. Two
        // controls saying "Building on its own" a centimetre apart is one of them too many.
        disposables.add(Effect.create(() -> alone.setVisible(!PilotGates.handedOver())));
        bar.add(alone);

        Button plan = new Button("Plan stories");
        plan.addClassName("btn-xs btn-primary");
        plan.addClickListener(e -> wizard.open());
        bar.add(plan);
        // No "New iteration". Iterations are cut from the operator UI (UX v3 8.3) and the data is
        // retained: they were optional grouping that never gated anything - startSession asks only
        // that a story be READY - so every control for them was a step on the way to nothing, and the
        // board spent two versions explaining that the thing it offered did not matter.
        return bar;
    }

    /** The way in, in two sentences. {@link #renderOrientation} decides whether it may be seen. */
    private Div orientationBar() {
        orientation.addClassName("shrink-0 mx-3 mt-3 rounded-lg border border-base-300 "
            + "bg-base-200/50 px-3 py-2 flex items-start gap-2");
        orientation.getElement().setAttribute("data-testid", "pipeline-orientation");
        orientation.setVisible(false);

        Div mark = new Div();
        mark.addClassName("shrink-0 pt-0.5 text-base-content/40");
        mark.add(Icon.of("info", "w-4 h-4"));

        Div text = new Div();
        text.addClassName("flex-1 min-w-0 text-xs leading-relaxed text-base-content/70");
        text.setText("A story moves left to right across these columns and never leaves them. You "
            + "decide twice: accept the suggestions that are real work, and judge what comes back. "
            + "Everything between those two is the machine's job.");

        Div dismiss = new Div("Got it");
        dismiss.addClassName("shrink-0 text-[11px] px-1.5 py-0.5 rounded border border-base-300 "
            + "cursor-pointer hover:bg-base-300/60");
        dismiss.getElement().setAttribute("title", "Hide this. It stays hidden in this browser.");
        dismiss.addDomEventListener("click", e -> {
            Js.localSet(ORIENTATION_KEY, "0");
            orientation.setVisible(false);
        });

        orientation.add(mark, text, dismiss);
        return orientation;
    }

    /**
     * Shows the orientation line only while it could still be telling somebody something: dismissed
     * by hand, or retired the moment the project shows the path has been walked.
     */
    private void renderOrientation(Backlog current) {
        if ("0".equals(Js.localGet(ORIENTATION_KEY))) {
            orientation.setVisible(false);
            return;
        }
        for (Story story : current.stories()) {
            StoryState state = story.state();
            boolean walked = state == StoryState.RUNNING || state == StoryState.REVIEW
                || state == StoryState.DONE || state == StoryState.BLOCKED
                || !story.runIds().isEmpty();
            if (walked) {
                orientation.setVisible(false);
                return;
            }
        }
        orientation.setVisible(true);
    }

    private Div noticeBar() {
        notice.addClassName("shrink-0 mx-3 mt-3 rounded-lg border px-3 py-2 flex items-start gap-2");
        notice.setVisible(false);
        noticeMark.addClassName("shrink-0 pt-0.5");
        noticeText.addClassName("flex-1 min-w-0 text-xs leading-relaxed whitespace-pre-wrap "
            + "break-words");
        Div dismiss = new Div("Dismiss");
        dismiss.addClassName("shrink-0 text-[11px] px-1.5 py-0.5 rounded border border-base-300 "
            + "cursor-pointer hover:bg-base-300/60");
        dismiss.addDomEventListener("click", e -> say("", false));
        notice.add(noticeMark, noticeText, dismiss);
        return notice;
    }

    /**
     * Puts one sentence in front of the operator, coloured by outcome.
     *
     * <p>Much shorter sentences than this board's predecessors carried, and deliberately: the long
     * ones existed to explain where a card had GONE ("S1 has left this list — it is on the Plan board
     * under Ready to build"). Nothing goes anywhere now, so the card itself says it and this slot is
     * back to being what it should always have been — the place a REFUSAL is reported.
     */
    private void say(String message, boolean failure) {
        if (message == null || message.isEmpty()) {
            notice.setVisible(false);
            return;
        }
        notice.setClassName("shrink-0 mx-3 mt-3 rounded-lg border px-3 py-2 flex items-start gap-2 "
            + (failure ? "border-error/40 bg-error/10" : "border-base-300 bg-base-200/60"));
        noticeMark.removeAll();
        noticeMark.add(Icon.of(failure ? "warning" : "info",
            "w-4 h-4 " + (failure ? "text-error" : "text-base-content/40")));
        noticeText.setClassName("flex-1 min-w-0 text-xs leading-relaxed whitespace-pre-wrap "
            + "break-words " + (failure ? "text-error" : "text-base-content/70"));
        noticeText.setText(message);
        notice.setVisible(true);
    }

    /**
     * Mounts a dialog, dropping whatever was mounted before it. Closing a {@code Dialog} hides it
     * rather than removing it, so without this every open would leave another copy in the document.
     */
    private void mount(Dialog dialog) {
        dialogHost.removeAll();
        dialogHost.add(dialog);
        mountedDialog = dialog;
    }

    // --- the board ----------------------------------------------------------------------------

    /**
     * Which column a story is in. Total over {@link StoryState} except CANCELLED, which is the one
     * state with no column: a dropped story is counted on the settled strip and openable from it.
     */
    private static Column columnOf(Story story) {
        if (story.state() == null) {
            return null;
        }
        return switch (story.state()) {
            case DRAFT -> Column.SUGGESTED;
            case READY -> Column.READY;
            case RUNNING -> Column.BUILDING;
            // Both are "a build finished and it is your move now". The badge says which.
            case REVIEW, BLOCKED -> Column.CAME_BACK;
            case DONE -> Column.DELIVERED;
            case CANCELLED -> null;
        };
    }

    /**
     * What the header says about the pipeline in numbers.
     *
     * @param building  agents genuinely at work, including waiting out an outage — nothing owed
     * @param needsYou  everything the operator is on the hook for: a delivery to judge, a build that
     *                  stopped, and a pause that has outlasted "it will be back"
     */
    record Counts(int suggested, int ready, int building, int needsYou, int delivered) { }

    /**
     * Counts the pipeline for the header, through the SAME derivation the cards render.
     *
     * <p>It lives here rather than in the header for exactly that reason. A header that counted
     * "needs you" its own way would eventually disagree with the badges underneath it, and a count
     * that contradicts the thing it is counting is worse than no count — it is the "building now ·
     * 9h 5m" here, "stopped" there defect, moved up one level.
     */
    static Counts counts(Backlog current, List<RunSummaryDto> runs) {
        if (current == null) {
            return new Counts(0, 0, 0, 0, 0);
        }
        int suggested = 0;
        int ready = 0;
        int building = 0;
        int needsYou = 0;
        int delivered = 0;
        for (Story story : current.stories()) {
            Column column = columnOf(story);
            if (column == null) {
                continue; // dropped: settled, and counted on the strip instead
            }
            switch (column) {
                case SUGGESTED -> suggested++;
                case READY -> ready++;
                case DELIVERED -> delivered++;
                // Both halves of CAME BACK are the operator's move: judge a delivery, or deal with a
                // build that stopped short.
                case CAME_BACK -> needsYou++;
                case BUILDING -> {
                    BuildState.Kind kind = BuildState.of(story, newestRun(story, runs));
                    if (kind == BuildState.Kind.WORKING || kind == BuildState.Kind.PAUSED) {
                        building++;
                    } else {
                        // Stopped, or a pause old enough to escalate. Either way somebody has to decide.
                        needsYou++;
                    }
                }
            }
        }
        return new Counts(suggested, ready, building, needsYou, delivered);
    }

    /** No RMI here: everything is on the signals, and this runs inside an {@link Effect}. */
    private void render() {
        Backlog current = backlog.get();
        // Read unconditionally so this effect depends on it: a run changing phase must redraw a
        // BUILDING card even when the story itself has not moved.
        List<RunSummaryDto> runs = RunsStore.runs.get();
        columns.getElement().setInnerHTML("");
        settled.getElement().setInnerHTML("");
        settled.setVisible(false);
        orphans.getElement().setInnerHTML("");
        orphans.setVisible(false);
        if (current == null) {
            orientation.setVisible(false);
            return;
        }
        renderOrientation(current);
        renderOrphanQuestions(current);
        for (Column column : Column.values()) {
            List<Story> inColumn = new ArrayList<>();
            for (Story story : current.stories()) {
                if (columnOf(story) == column) {
                    inColumn.add(story);
                }
            }
            columns.add(column(column, inColumn, current, runs));
        }
        renderSettled(current);
    }

    private Div column(Column column, List<Story> stories, Backlog current,
                       List<RunSummaryDto> runs) {
        Div box = new Div();
        // Sharing the width rather than claiming a fixed slice of it. Five fixed w-72 columns come to
        // ~1500px, so DELIVERED sat off the right-hand edge of an ordinary window and CAME BACK was
        // clipped — which is what the previous board's own javadoc predicted would happen if a fifth
        // column were ever added. The design requires five visible columns, so they flex: min-w keeps
        // a card readable, and the row still scrolls as a last resort on a very narrow window.
        box.addClassName("flex-1 basis-0 min-w-[12rem] rounded-lg bg-base-200/40 "
            + "border border-base-300 flex flex-col max-h-full");
        // Selected by the tests rather than the label, for the reason the stage bar is: a column's
        // label is prose and the stage of life it stands for is the contract.
        box.getElement().setAttribute("data-testid", "pipeline-col-" + column.id);

        Div head = new Div();
        head.addClassName("px-3 py-2 border-b border-base-300");
        Div titleRow = new Div();
        titleRow.addClassName("flex items-center gap-2");
        Span label = new Span(column.label);
        label.addClassName("text-[11px] font-semibold uppercase tracking-wide");
        titleRow.getElement().appendChild(label.getElement());
        Span count = new Span(String.valueOf(stories.size()));
        TextStyle.CAPTION.applyTo(count);
        titleRow.getElement().appendChild(count.getElement());
        head.add(titleRow);
        Div caption = new Div(column.caption);
        caption.addClassName("text-[10px] text-base-content/45 leading-snug mt-0.5");
        head.add(caption);
        box.add(head);

        Div list = new Div();
        list.addClassName("flex-1 min-h-0 overflow-y-auto p-2 flex flex-col gap-2");
        for (Story story : stories) {
            list.add(card(story, newestRun(story, runs), current));
        }
        if (column == Column.BUILDING) {
            // A run with no story still has to live somewhere. `/run` in a chat starts one directly,
            // and under "a run is an attempt at a story" it has nothing to be an attempt AT — so a
            // board built only from stories would drop it silently, which is the same disappearance
            // rule 1 forbids.
            for (RunSummaryDto run : runs) {
                if (!claimed(run, current) && !BuildState.isTerminal(run.getState())) {
                    list.add(looseRunCard(run));
                }
            }
        }
        if (!list.getElement().hasChildNodes()) {
            list.add(emptyState(column));
        }
        box.add(list);
        return box;
    }

    /**
     * What an empty column says.
     *
     * <p>Each names what fills it, and offers the button when this board has one — including the
     * cross-reference when the prerequisite is somewhere else. This is the first-run tutorial nobody
     * reads, delivered at the moment and place it is needed instead (UX v3 §3.1).
     */
    private Div emptyState(Column column) {
        Div box = new Div();
        box.addClassName("px-1 py-2 flex flex-col gap-1.5");
        Div text = new Div();
        TextStyle.CAPTION.applyTo(text);
        switch (column) {
            case SUGGESTED -> {
                text.setText("Nothing suggested. Stories are planned from the requirements you have "
                    + "agreed — each one delivers named checks, and you read every suggestion "
                    + "before it lands here.");
                box.add(text);
                box.add(action("Plan stories", "text-primary/80", this::openPlanningWizard));
                box.add(action("Open Requirements", "text-base-content/50",
                    () -> Nav.openRequirements.run()));
            }
            case READY -> {
                text.setText("Nothing agreed yet. Accept a suggestion on the left and it arrives "
                    + "here, ready to build.");
                box.add(text);
            }
            case BUILDING -> {
                text.setText("Nothing is being built. Press \"Build this story\" on anything in "
                    + "\"Ready to build\" and it moves here.");
                box.add(text);
            }
            case CAME_BACK -> {
                text.setText("Nothing to judge. A build lands here on its own when it finishes — "
                    + "you never have to go and collect it.");
                box.add(text);
            }
            case DELIVERED -> {
                text.setText("Nothing delivered yet. This is where a story ends up once you have "
                    + "accepted what a build handed back.");
                box.add(text);
            }
        }
        return box;
    }

    /**
     * One story: what it is, what is true of it, and the single obvious thing to do about it.
     *
     * <p>The card grammar is the one proven on the planning board — key, title, what it delivers, a
     * sentence in the operator's language, ONE primary button, quiet secondaries — extended with what
     * a building story needs: the phase in words, how long, and what it has cost.
     */
    private Div card(Story story, RunSummaryDto run, Backlog current) {
        List<Decision> questions = questionsFor(story, current);
        // The story's OWN situation, and the action that follows from it, are worked out first and
        // unconditionally — never from the question's situation. A question is a record of something
        // that happened, not a precondition: answering one "records the answer and nothing else, it
        // does not restart a run or retry a task", in the control interface's own words. Deriving the
        // action from the question instead is what once left a stopped story with no way back: a
        // model server restarted, raised seven questions against one story, and "Build it again" was
        // not on the card at all — the operator had to write seven pieces of prose about a network
        // outage before the product would let them press it.
        Situation own = situationOf(story, run, current);
        StoryAction ownAction = actionOf(story, own, current);
        Situation situation = questions.isEmpty()
            ? own
            // A question the build stopped to ask outranks everything else the card could SAY: it is
            // the one thing on it the operator is actually being asked for, and it used to be
            // invisible here and answerable only in a queue that named runs instead of stories. It
            // outranks what the card says. It does not gate what the card offers.
            : askedSituation(questions, ownAction);
        boolean justMoved = story.id() != null && story.id().equals(moved);

        Div card = new Div();
        card.addClassName("rounded border bg-base-100 p-2 flex flex-col gap-1 "
            + situation.border()
            // The flash. A ring rather than a colour change, because the card's border already
            // carries its health and overwriting that to say "this moved" would trade a standing
            // fact for a momentary one.
            + (justMoved ? " ring-2 ring-primary ring-offset-1 ring-offset-base-100" : ""));
        card.getElement().setAttribute("data-testid", "pipeline-card");

        // The head is the drill-down. The actions below are deliberately OUTSIDE it, so pressing
        // "Not needed" does not also open a dialog about the story being dropped.
        Div head = new Div();
        head.addClassName("flex flex-col gap-1 cursor-pointer");
        head.getElement().setAttribute("title", "Open " + key(story));
        head.addDomEventListener("click", e -> openStory(story, current, run));

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        Span key = new Span(key(story));
        key.addClassName("text-[10px] font-mono text-base-content/40");
        top.getElement().appendChild(key.getElement());
        if (situation.badgeLabel() != null) {
            Span badge = new Span(situation.badgeLabel());
            badge.addClassName("badge badge-xs " + situation.badge());
            top.add(badge);
        }
        head.add(top);

        Span title = new Span(title(story));
        title.addClassName("text-xs leading-snug");
        head.getElement().appendChild(title.getElement());

        // Always "delivers N checks". A story kind is cut from the UI (8.3): an enabler renders as an
        // ordinary story, because "is this an enabler?" is not a question the operator was ever asked
        // to answer and the word appeared nowhere else in the product.
        Span delivers = new Span(deliversLine(story, current));
        delivers.addClassName("text-[10px] text-base-content/40");
        head.getElement().appendChild(delivers.getElement());
        card.add(head);

        Div sentence = new Div(situation.sentence());
        sentence.addClassName("text-[11px] text-base-content/60 leading-snug pt-0.5");
        card.add(sentence);

        // Elapsed and cost, on the cards where they mean something. Not on a card that is not being
        // worked: "0 tokens, 4 minutes" against a story nothing is driving is a number that invites
        // the wrong conclusion.
        if (situation.kind() == BuildState.Kind.WORKING || situation.kind() == BuildState.Kind.PAUSED) {
            Div meter = new Div(meter(run));
            meter.addClassName("text-[10px] font-mono text-base-content/40");
            card.add(meter);
        }

        // The deciding button, ON the card (UX v3 2.3) - not a link to a queue somewhere else.
        //
        // One primary, and it is the one the sentence above it is about (UX v3 3.1: "one primary
        // action, quiet secondaries"). With questions waiting, the card's sentence is the question,
        // so answering is the primary and the story's own action drops to a quiet secondary in the
        // row below. It DROPS. It never disappears: rule 5 says every gate carries its own exit, and
        // a stopped story with no reachable "Build it again" is a state with no exit.
        Button primary = questions.isEmpty()
            ? (ownAction == null ? null : primary(ownAction.label(), ownAction.run()))
            : primary(questions.size() == 1 ? "Answer this question"
                    : "Answer these " + questions.size() + " questions",
                () -> openQuestions(story, questions));
        if (primary != null) {
            card.add(primary);
            // Greyed out AND explained. A grey button with no sentence is the same anxiety in a
            // different costume, and the reason has to be readable without hovering.
            if (questions.isEmpty() && ownAction != null && ownAction.heldByPilot() != null) {
                primary.setEnabled(false);
                primary.getElement().setAttribute("title", ownAction.heldByPilot());
                card.add(PilotGates.note());
            }
        }

        Div actions = new Div();
        actions.addClassName("flex flex-wrap items-center gap-x-3 gap-y-1 pt-0.5");
        // The story's own action, when a question has taken the primary slot off it. First in the
        // row and coloured as an action rather than as a way out, because it is the thing that moves
        // the work: answering records a call, this builds the code. Spending is still deliberate —
        // every one of these opens its confirm dialog before a single agent is dispatched (UX v3 6).
        if (!questions.isEmpty() && ownAction != null) {
            if (ownAction.heldByPilot() != null) {
                Div held = new Div(ownAction.label());
                held.addClassName("text-[11px] text-base-content/40 line-through");
                held.getElement().setAttribute("data-testid", "story-own-action");
                held.getElement().setAttribute("title", ownAction.heldByPilot());
                actions.add(held);
            } else {
                Div ownLink = action(ownAction.label(), "text-primary/80", ownAction.run());
                ownLink.getElement().setAttribute("data-testid", "story-own-action");
                actions.add(ownLink);
            }
        }
        // No "add to an iteration". Grouping is cut from the operator UI (UX v3 8.3): it never gated
        // anything, so the control was a step on the way to nothing.
        if (story.state() == StoryState.REVIEW) {
            actions.add(action("Send it back", "text-base-content/50", () -> confirmRetry(story)));
        }
        if (run != null) {
            actions.add(openRunAction(run));
        }
        if (story.state() != StoryState.DONE && story.state() != StoryState.CANCELLED) {
            actions.add(action(dropLabel(story), "text-base-content/50 hover:text-error",
                () -> confirmDrop(story, null)));
        }
        if (actions.getElement().hasChildNodes()) {
            card.add(actions);
        }
        return card;
    }

    /**
     * A run nobody's story owns — started from a chat, or from before the backlog existed.
     *
     * <p>It gets a card in BUILDING rather than being filtered out, because a run that exists and is
     * not on the board is the disappearance this design forbids. It carries no story actions because
     * there is no story to act on.
     */
    private Div looseRunCard(RunSummaryDto run) {
        boolean paused = BuildState.isPaused(run);
        Div card = new Div();
        card.addClassName("rounded border bg-base-100 p-2 flex flex-col gap-1 "
            + (paused ? "border-info/40" : "border-primary/30"));
        card.getElement().setAttribute("data-testid", "pipeline-card");

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        Span badge = new Span(paused ? "paused" : "building now");
        badge.addClassName("badge badge-xs " + (paused ? "badge-info" : "badge-primary"));
        top.add(badge);
        Span origin = new Span("no story");
        origin.addClassName("text-[9px] px-1 rounded bg-base-300 text-base-content/60");
        top.getElement().appendChild(origin.getElement());
        card.add(top);

        Span title = new Span(run.getGoal() == null || run.getGoal().isEmpty()
            ? run.getRunId() : run.getGoal());
        title.addClassName("text-xs leading-snug");
        card.getElement().appendChild(title.getElement());

        Div says = new Div(paused ? BuildState.pauseSentence(run)
            : "Started outside the pipeline — " + BuildState.phrase(run.getState()) + ".");
        TextStyle.CAPTION.applyTo(says);
        card.add(says);

        Div meter = new Div(meter(run));
        meter.addClassName("text-[10px] font-mono text-base-content/40");
        card.add(meter);

        Div actions = new Div();
        actions.addClassName("flex flex-wrap items-center gap-x-3 pt-0.5");
        actions.add(openRunAction(run));
        card.add(actions);
        return card;
    }

    /**
     * The way into the swarm's own detail — a QUIET link, deliberately.
     *
     * <p>The graph is for understanding rather than for acting, and making it the way in is what
     * turned a build into a destination of its own. The primary action on a card is always something
     * that moves the work.
     */
    private Div openRunAction(RunSummaryDto run) {
        Div link = action("Open the run", "text-base-content/50",
            () -> openRunGraph(run.getRunId()));
        link.getElement().setAttribute("data-testid", "open-run");
        return link;
    }

    /** Elapsed time and spend, in the compact form the design asks for ("4 min · 12k tok"). */
    private static String meter(RunSummaryDto run) {
        if (run == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (run.getStartedAtMillis() > 0) {
            long minutes = Math.max(0,
                (System.currentTimeMillis() - run.getStartedAtMillis()) / 60_000);
            text.append(minutes < 60 ? minutes + " min"
                : (minutes / 60) + "h " + (minutes % 60) + "m");
        }
        long tokens = run.getTokensUsed();
        if (tokens > 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            // Rounded, not exact: the operator is asking "is this expensive?", and a nine-digit
            // number answers that question worse than "34k" does.
            text.append(tokens < 1000 ? tokens + " tok"
                : (tokens / 1000) + "k tok");
        }
        return text.toString();
    }

    /**
     * The questions a story's builds stopped to ask and nobody has answered.
     *
     * <p>Joined through the run: a {@link Decision} names the run it was raised in, and a run belongs
     * to a story. That join is the whole of folding the Approval Center onto the board (UX v3 §2.3) —
     * the queue held thirty-seven cards naming RUNS, which is machinery the operator never handles,
     * with no way to tell which of their stories any of them was about.
     */
    private static List<Decision> questionsFor(Story story, Backlog current) {
        List<Decision> mine = new ArrayList<>();
        for (Decision decision : current.decisions()) {
            if (decision.state() != DecisionState.PENDING || decision.runId() == null) {
                continue;
            }
            if (story.runIds().stream().anyMatch(id -> id.equals(decision.runId()))) {
                mine.add(decision);
            }
        }
        return mine;
    }

    /** Every unanswered question that belongs to no story — a budget exhaustion, in practice. */
    private static List<Decision> orphanQuestions(Backlog current) {
        List<Decision> orphans = new ArrayList<>();
        for (Decision decision : current.decisions()) {
            if (decision.state() == DecisionState.PENDING && decision.runId() == null) {
                orphans.add(decision);
            }
        }
        return orphans;
    }

    /**
     * What is true of this story, in the operator's words, plus how it should look.
     *
     * <p>For a story in build the answer comes from {@link BuildState} — ONE derivation, which is
     * what stopped the two old boards contradicting each other. This method only dresses that answer
     * in a sentence.
     */
    private static Situation situationOf(Story story, RunSummaryDto run, Backlog current) {
        StoryState state = story.state();
        if (state == null) {
            return new Situation(null, "", "border-base-300", "", null);
        }
        switch (state) {
            case DRAFT:
                return new Situation(null, "", "border-base-300",
                    "The planner suggested this. Is it real work?", null);
            case READY: {
                // A story that builds on another does not start until that one is accepted, and the
                // card has to SAY so. "Agreed work, nothing is building it yet" on nine cards at
                // once is exactly how nine stories were started together, each one inventing its own
                // version of a domain model the first of them had not finished writing.
                StoryGraph.Blocked blocked =
                    StoryGraph.blockedBy(story, current == null ? List.of() : current.stories());
                if (blocked.any()) {
                    return new Situation("waiting", "badge-ghost", "border-base-300",
                        blocked.reason(), null);
                }
                if (story.waitingReason() != null && !story.waitingReason().isBlank()) {
                    return new Situation("waiting", "badge-ghost", "border-base-300",
                        story.waitingReason(), null);
                }
                return new Situation(null, "", "border-base-300",
                    "Agreed work. Nothing is building it yet.", null);
            }
            case DONE:
                return new Situation("delivered", "badge-success", "border-success/30",
                    (story.acceptedUnattended()
                        // Never dressed up as a human verdict. A story accepted while the operator
                        // slept has been PROVED — every check it promised passed a test that
                        // really ran — but nobody has said yet that it is what they wanted.
                        ? "Delivered, and accepted overnight without anyone looking at it because "
                            + "every check it promised passed"
                        : "Delivered and accepted")
                    + (story.deliveredCommit() == null
                        ? "." : " — commit " + shortSha(story.deliveredCommit()) + "."), null);
            case CANCELLED:
                return new Situation("dropped", "badge-ghost", "border-base-300",
                    "Dropped. It is retired and does not come back.", null);
            default:
                break;
        }
        // RUNNING, REVIEW, BLOCKED — the build side, decided by BuildState.
        BuildState.Kind kind = BuildState.of(story, run);
        if (state == StoryState.REVIEW) {
            // When unattended running left this one for a person, the card says why in the same
            // place. Waking up to nine identical "did it deliver what was asked?" cards, with no
            // clue which of them the machine could have handled and did not, is the morning this
            // whole feature exists to avoid.
            String left = story.waitingReason();
            return new Situation("came back", "badge-warning", "border-warning/40",
                (left != null && !left.isBlank() ? left + " " : "")
                    + (story.deliveredCommit() == null
                        ? "A build finished and handed this back. Did it deliver what was asked?"
                        : "A build finished and delivered commit "
                            + shortSha(story.deliveredCommit())
                            + ". Did it deliver what was asked?"),
                kind);
        }
        if (state == StoryState.BLOCKED) {
            return new Situation("stopped", "badge-error", "border-error/40",
                // The story's own reason when the system worked one out — usually "every attempt
                // failed to compile because X does not exist and nothing planned would create it",
                // which somebody can act on, unlike "a check failed".
                story.waitingReason() != null && !story.waitingReason().isBlank()
                    ? story.waitingReason()
                    : "A build came back without delivering — a check failed or a quality gate "
                        + "held it. Nothing restarts on its own.", kind);
        }
        // RUNNING: the badge and the sentence are the health, and the column never changes.
        return switch (kind) {
            case WORKING -> new Situation(BuildState.label(kind), BuildState.badgeClass(kind),
                BuildState.borderClass(kind), BuildState.phrase(run == null ? "" : run.getState()),
                kind);
            case PAUSED, WAITING -> new Situation(BuildState.label(kind), BuildState.badgeClass(kind),
                BuildState.borderClass(kind), BuildState.pauseSentence(run), kind);
            case STOPPED -> new Situation(BuildState.label(kind), BuildState.badgeClass(kind),
                BuildState.borderClass(kind),
                // A park says its own reason — the same brief its question carries — rather than
                // the generic sentences below, which are guesses at why nothing is driving a build
                // that never actually parked (no run on record, an ended run, a stale heartbeat).
                run != null && BuildState.isParked(run)
                    ? BuildState.parkSentence(run)
                    : run == null
                        ? "This says it is building, but no run of it is on record. Nothing is "
                            + "driving it."
                        : BuildState.isTerminal(run.getState())
                            ? "Its build ended without handing the story back. Nothing is driving it."
                            : "Nothing has touched its build for " + BuildState.quietMinutes(run)
                                + " minutes. Nothing is driving it.",
                kind);
        };
    }

    /**
     * The banner for questions with no story: what it is, and the button that answers it.
     *
     * <p>Hidden entirely when there are none. A permanently mounted "0 questions" strip is chrome the
     * operator learns to skip, and then misses on the day it says something.
     */
    private void renderOrphanQuestions(Backlog current) {
        List<Decision> pending = orphanQuestions(current);
        if (pending.isEmpty()) {
            return;
        }
        orphans.setVisible(true);
        Div head = new Div(pending.size() == 1
            ? "Something stopped that belongs to no single story."
            : pending.size() + " things stopped that belong to no single story.");
        head.addClassName("text-xs font-semibold");
        orphans.add(head);
        Div why = new Div("A budget running out is the usual one: it is the project's, not a story's, "
            + "so there is no card to put it on. Answering records what you decided.");
        TextStyle.CAPTION.applyTo(why);
        orphans.add(why);
        orphans.add(action("Answer " + (pending.size() == 1 ? "it" : "them"), "text-primary/80",
            () -> openOrphanQuestions(pending)));
    }

    /** The same dialog the cards use, for the questions that have no card. */
    private void openOrphanQuestions(List<Decision> questions) {
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        Div body = new Div();
        body.addClassName("flex flex-col gap-3 max-h-[70vh] overflow-y-auto");
        body.getElement().setAttribute("data-testid", "orphan-questions");
        Div title = new Div(questions.size() == 1 ? "1 question" : questions.size() + " questions");
        title.addClassName("text-lg font-bold");
        body.add(title);
        for (Decision question : questions) {
            body.add(questionCard(dialog, null, question));
        }
        dialog.add(body);
        Button close = new Button("Close", e -> dialog.close());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        // Off the close, not off the button: Escape and a click outside close a dialog now, and a
        // dialog left in the document keeps every control it carries — including a Close that can
        // never be clicked, which then shadows the identically named one of whatever opens next.
        // Guarded on identity. A close listener runs on a green thread, so it can arrive
        // AFTER whatever replaced this dialog has been mounted and opened — and would then
        // tear down its successor. It only acts while this is still the dialog on screen.
        dialog.addCloseListener(e -> {
            if (mountedDialog == dialog) {
                closeStory();
            }
        });
        mount(dialog);
        dialog.open();
    }

    /**
     * "delivers 5 checks: 4 verified" — the one line on a card that says how much of the story is
     * actually proven (UX v3 3.2).
     *
     * <p>Verified means the check's evidence stands against the CURRENT wording of its requirement.
     * That qualification is the whole value of the number: a check that passed and then had its
     * requirement reworded is STALE, and counting it as verified is how a requirement comes to claim
     * it is implemented because somebody once ran a test against different words.
     */
    private static String deliversLine(Story story, Backlog current) {
        int total = story.criterionIds().size();
        if (total == 0) {
            // An enabler, or a story nobody has attached checks to. Either way, saying "0 checks
            // verified" would imply something failed; nothing has been claimed to begin with.
            return "delivers no checks yet";
        }
        int verified = verifiedChecks(story);
        return "delivers " + checks(total)
            + (verified == 0 ? "" : ": " + verified + " verified");
    }

    /** How many of this story's checks are passing against the current wording of their requirement. */
    private static int verifiedChecks(Story story) {
        Brd brd = BrdSignals.CURRENT.get();
        if (brd == null || brd.requirements() == null) {
            return 0;
        }
        int verified = 0;
        for (BrdRequirement requirement : brd.requirements()) {
            if (requirement.criteria() == null) {
                continue;
            }
            for (AcceptanceCriterion criterion : requirement.criteria()) {
                if (criterion.id() == null || !story.criterionIds().contains(criterion.id())) {
                    continue;
                }
                if (criterion.effectiveState(requirement.contentRevision()) == CriterionState.PASSING) {
                    verified++;
                }
            }
        }
        return verified;
    }

    /**
     * Every attempt at this story, newest first: when, what it cost, and how it ended.
     *
     * <p>The depth 3.2 asks for, and the place the word "run" is allowed to become visible at all —
     * as an ATTEMPT, numbered, inside the story it was an attempt at. A run was previously a
     * destination with its own rail and its own tab, which is what made "S1 is nowhere to be seen"
     * possible: the operator was being asked to hold two nouns for one thing.
     */
    private Div attemptsPanel(Story story, List<RunSummaryDto> runs) {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-1");
        List<UUID> ids = story.runIds();
        for (int i = ids.size() - 1; i >= 0; i--) {
            UUID id = ids.get(i);
            RunSummaryDto run = null;
            for (RunSummaryDto candidate : runs) {
                if (candidate.getRunId() != null && candidate.getRunId().equals(id.toString())) {
                    run = candidate;
                    break;
                }
            }
            panel.add(attemptRow(i + 1, id, run));
        }
        return panel;
    }

    private Div attemptRow(int number, UUID id, RunSummaryDto run) {
        Div row = new Div();
        row.addClassName("flex items-baseline gap-2 flex-wrap");
        Span label = new Span("#" + number);
        label.addClassName("text-[10px] font-mono text-base-content/40 w-6 shrink-0");
        row.getElement().appendChild(label.getElement());
        if (run == null) {
            // The id is on record but the attempt is not. Said, rather than skipped: a story whose
            // history has a hole in it is worth knowing about, and a silently shorter list reads as
            // fewer attempts having happened.
            Span missing = new Span("no record of this attempt any more");
            TextStyle.CAPTION.applyTo(missing, Emphasis.FAINT);
            row.getElement().appendChild(missing.getElement());
            return row;
        }
        Span outcome = new Span(attemptOutcome(run));
        outcome.addClassName("text-[11px]");
        row.getElement().appendChild(outcome.getElement());
        String meter = meter(run);
        if (!meter.isEmpty()) {
            Span cost = new Span(meter);
            cost.addClassName("text-[10px] font-mono text-base-content/40");
            row.getElement().appendChild(cost.getElement());
        }
        Div open = action("Open", "text-base-content/50", () -> openRunGraph(run.getRunId()));
        row.add(open);
        return row;
    }

    /** How an attempt ended, in the operator's words rather than the run state's. */
    private static String attemptOutcome(RunSummaryDto run) {
        if (BuildState.isPaused(run)) {
            return "paused, waiting for the model server";
        }
        String state = run.getState() == null ? "" : run.getState();
        return switch (state) {
            case "DELIVERED" -> "finished and handed the story back";
            case "ABORTED" -> "stopped without delivering";
            default -> BuildState.phrase(state);
        };
    }

    /**
     * The card's situation when a build has stopped to ask something.
     *
     * <p>Deliberately NOT the decision's own brief, which is a technical account written for whoever
     * would read the queue - "Task BLOCKED after swarm + repair round", a per-candidate evidence
     * dump. The card says what it means for this story in one line and offers to show the rest.
     *
     * <p>When the story has an action of its own it is NAMED here, so the sentence and the card agree
     * about what is on offer. Answering is not a step on the way to it - the two are independent, and
     * the operator has to be able to read that off the card without trying it.
     *
     * @param ownAction the story's own action, or null when it honestly has none
     */
    private static Situation askedSituation(List<Decision> questions, StoryAction ownAction) {
        String what = questions.size() == 1
            ? "This build stopped and is asking you something"
            : "This build stopped and is asking you " + questions.size() + " things";
        return new Situation("needs you", "badge-warning", "border-warning/40",
            what + ". Nothing restarts on its own - answering records your call"
                + (ownAction == null
                    ? "."
                    : ", and \"" + ownAction.label() + "\" below is ready whether you answer or not."),
            BuildState.Kind.WAITING);
    }

    /**
     * The questions themselves, over the board, each with its brief and a box to answer it in.
     *
     * <p>This is what is left of the Approval Center, and the difference is where it is reached from:
     * a story the operator was already looking at, rather than a destination they had to know existed.
     * Answering resolves the row and restarts nothing - {@code resolveDecision} rewrites it and
     * touches nothing else - so the wording promises exactly that and no more.
     */
    private void openQuestions(Story story, List<Decision> questions) {
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        Div body = new Div();
        body.addClassName("flex flex-col gap-3 max-h-[70vh] overflow-y-auto");
        body.getElement().setAttribute("data-testid", "story-questions");

        Div title = new Div(key(story) + " \u00b7 "
            + (questions.size() == 1 ? "1 question" : questions.size() + " questions"));
        title.addClassName("text-lg font-bold");
        body.add(title);
        Div hint = new Div("Each of these is where a build stopped. Answering records what you "
            + "decided \u2014 it does not restart anything, so do whatever it asks for as well.");
        TextStyle.CAPTION.applyTo(hint);
        body.add(hint);

        for (Decision question : questions) {
            body.add(questionCard(dialog, story, question));
        }

        dialog.add(body);
        Button close = new Button("Close", e -> dialog.close());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        // Off the close, not off the button: Escape and a click outside close a dialog now, and a
        // dialog left in the document keeps every control it carries — including a Close that can
        // never be clicked, which then shadows the identically named one of whatever opens next.
        // Guarded on identity. A close listener runs on a green thread, so it can arrive
        // AFTER whatever replaced this dialog has been mounted and opened — and would then
        // tear down its successor. It only acts while this is still the dialog on screen.
        dialog.addCloseListener(e -> {
            if (mountedDialog == dialog) {
                closeStory();
            }
        });
        mount(dialog);
        dialog.open();
    }

    private Div questionCard(Dialog dialog, Story story, Decision question) {
        Div card = new Div();
        card.addClassName("rounded border border-warning/40 bg-base-100 p-3 flex flex-col gap-2");

        Span kind = new Span(kindLabel(question));
        kind.addClassName("badge badge-xs badge-warning self-start");
        card.add(kind);

        // The brief verbatim, monospaced and scrollable. It is evidence - per-candidate compile
        // states, failing tests, log tails - and paraphrasing evidence is how an operator ends up
        // deciding against a summary somebody else wrote.
        Div brief = new Div(question.briefMarkdown() == null ? "" : question.briefMarkdown());
        brief.addClassName("text-[11px] font-mono whitespace-pre-wrap break-words max-h-64 "
            + "overflow-y-auto text-base-content/70 bg-base-200/50 rounded p-2");
        card.add(brief);

        TextArea response = new TextArea("What did you decide, and what did you do about it?");
        response.addClassName("textarea textarea-bordered w-full h-20 text-xs");
        card.add(response);

        Button answer = new Button("Record this answer", e -> {
            String text = response.getValue();
            if (text == null || text.isBlank()) {
                // In the dialog would be better still, but this slot is the one the operator is
                // looking at once the modal closes, and a button that silently does nothing is worse.
                say("Say what you decided \u2014 an answer with no words records nothing.", true);
                return;
            }
            dialog.close();
            closeStory();
            act(story, () -> {
                control.resolveDecision(question.id().toString(), text);
                // resolveDecision returns nothing, so there is no refusal to report; the server
                // republishes the backlog and the card loses its question on the signal.
                return "";
            }, null);
        });
        answer.addClassName("btn-xs btn-primary self-start");
        card.add(answer);
        return card;
    }

    /** The kind, in the operator's words rather than the enum's. */
    private static String kindLabel(Decision question) {
        if (question.kind() == null) {
            return "a question";
        }
        return switch (question.kind()) {
            case BLOCKED_TASK -> "a build stopped";
            case BUDGET_EXTENSION -> "out of budget";
            case GUIDELINE_REVIEW -> "a guideline to review";
            case APPROVAL -> "waiting on you";
        };
    }

    /**
     * A story's own action: what it is called, and what it does.
     *
     * <p>The pair rather than a built {@code Button}, because the same action has to be drawable two
     * ways — as the card's primary, and as a quiet secondary on the card whose primary slot a
     * question has taken. Rule 2 (one derivation per fact): the label and the behaviour are decided
     * once, here, so no surface can offer a button reading one thing and doing another.
     */
    private record StoryAction(String label, Runnable run, String heldByPilot) {
        StoryAction(String label, Runnable run) {
            this(label, run, null);
        }

        /**
         * The same action, marked as the pilot's while it is building on its own.
         *
         * <p>Marked rather than withheld: rule 1 says nothing vanishes, and a card that simply
         * stopped offering its one button would read as a card with nothing to do.
         */
        StoryAction pilots() {
            return new StoryAction(label, run, PilotGates.why());
        }
    }

    /**
     * The one action this story offers, or null when it honestly has none.
     *
     * <p>A building card has none on purpose, and a paused one especially: there is nothing for the
     * operator to do, and a button offering to build it again would invite them to pay for a second
     * swarm over a model server that is restarting. That null stays a null — a question waiting on
     * the story does not conjure an action, and does not withhold one either.
     *
     * <p>Decided from the STORY's own situation and nothing else. Callers must pass the situation
     * from {@link #situationOf} rather than a question's, or a story whose build is quietly working
     * gets offered "Stop waiting and take it back" because the question's situation, which stands in
     * for the card's whole health line, reports itself as a wait.
     */
    private StoryAction actionOf(Story story, Situation situation, Backlog current) {
        // Accepting a suggestion and starting a build are two of the six gates autonomous running
        // passes on the operator's behalf, so while it is on they are its levers. Read here rather
        // than stored anywhere: this method runs inside the board's own render effect, so switching
        // the mode off redraws every card with its button back. Judging what a build handed back is
        // NOT marked - the arming screen promises that anything the machine could not prove is left
        // on the board for the operator, and taking that away would break the promise.
        if (story.state() == StoryState.DRAFT) {
            return new StoryAction("Accept as work",
                () -> act(story, () -> service.promoteStory(story.id().toString()), null)).pilots();
        }
        if (story.state() == StoryState.READY) {
            StoryGraph.Blocked blocked =
                StoryGraph.blockedBy(story, current == null ? List.of() : current.stories());
            if (blocked.any()) {
                // Overruling stays possible, and is a different button with a different name. The
                // schedule is a guess made before any code existed, so the operator has to be able
                // to say "I know, do it anyway" — a rule that cannot be overruled is a rule
                // that gets worked around by editing data. What it must not be is the DEFAULT
                // button, sitting there reading "Build this story" as though nothing were in the way.
                return new StoryAction("Build it anyway",
                    () -> confirmStartAnyway(story, blocked)).pilots();
            }
            return new StoryAction("Build this story", () -> confirmStart(story, null)).pilots();
        }
        if (story.state() == StoryState.REVIEW) {
            return new StoryAction("Accept delivery",
                () -> act(story, () -> service.acceptStory(story.id().toString()), null));
        }
        if (story.state() == StoryState.BLOCKED) {
            return new StoryAction("Build it again", () -> confirmRetry(story));
        }
        if (story.state() == StoryState.RUNNING) {
            // The one case a building story needs a button: the pause has outlasted the point where
            // "it resumes by itself" is the whole story, so the operator is offered the choice
            // rather than left to discover it (rule 5 — every gate carries its own exit).
            if (situation.kind() == BuildState.Kind.WAITING) {
                return new StoryAction("Stop waiting and take it back",
                    () -> confirmRetry(story));
            }
            if (situation.kind() == BuildState.Kind.STOPPED) {
                return new StoryAction("Build it again", () -> confirmRetry(story));
            }
        }
        return null;
    }

    /** What the destructive action is called on this card — named for the deed once it is real work. */
    private static String dropLabel(Story story) {
        return story.state() == StoryState.DRAFT ? "Not needed" : "Drop this story";
    }

    // --- the settled tail -------------------------------------------------------------------------

    /**
     * Dropped stories: counted, openable, never resident.
     *
     * <p>Rule 1 says nothing may vanish, and a dropped story has not — it is retired, which is a
     * different claim. Keeping a column of them would push the columns that need attention off the
     * right-hand edge, which is the failure this whole redesign is undoing, so they are a count with
     * a click on it. Delivered work stays a real column: it is the end state of the process and the
     * operator's evidence that it works.
     */
    private void renderSettled(Backlog current) {
        List<Story> dropped = byState(current, StoryState.CANCELLED);
        List<Story> delivered = byState(current, StoryState.DONE);
        if (dropped.isEmpty()) {
            return;
        }
        settled.setVisible(true);
        Span caption = new Span("Settled:");
        caption.addClassName("text-base-content/35");
        settled.getElement().appendChild(caption.getElement());
        if (!delivered.isEmpty()) {
            // Stated because the design's strip states it, and not clickable because it IS the
            // Delivered column — a second way in would be a second place to act on one thing.
            Span count = new Span(delivered.size() + " delivered");
            count.addClassName("text-base-content/40");
            settled.getElement().appendChild(count.getElement());
        }
        String label = dropped.size() + " dropped";
        if (dropped.size() == 1) {
            Story only = dropped.get(0);
            settled.add(chip(label, "Open " + key(only) + " — " + title(only),
                () -> openStory(only, current, null)));
            return;
        }
        settled.add(chip(label, "List the dropped stories",
            () -> openDroppedList(dropped, current)));
    }

    private void openDroppedList(List<Story> stories, Backlog current) {
        Dialog dialog = new Dialog();
        Div title = new Div("Dropped stories");
        title.addClassName("text-lg font-bold mb-1");
        Div hint = new Div("Retired, with their record and history intact. Nothing puts a dropped "
            + "story back on the board.");
        hint.addClassName("text-xs text-base-content/60 mb-3");
        dialog.add(title, hint);
        Div list = new Div();
        list.addClassName("flex flex-col");
        for (Story story : stories) {
            Div row = new Div();
            row.addClassName("flex items-center gap-2 px-2 py-1 rounded cursor-pointer "
                + "hover:bg-base-300/50");
            Span key = new Span(key(story));
            key.addClassName("text-[10px] font-mono text-base-content/40");
            row.getElement().appendChild(key.getElement());
            Span text = new Span(title(story));
            text.addClassName("text-xs truncate flex-1");
            row.getElement().appendChild(text.getElement());
            row.addDomEventListener("click", e -> openStory(story, current, null));
            list.add(row);
        }
        dialog.add(list);
        Button close = new Button("Close", e -> dialog.close());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        mount(dialog);
        dialog.open();
    }

    // --- the story dialog (UX v3 §3.2) ----------------------------------------------------------

    /**
     * One story in full, in a dialog OVER the board.
     *
     * <p>A dialog rather than a docked panel, by author decision: depth must cost nothing when you
     * are not using it, and a permanent panel taxes every board visit to serve the occasional deep
     * look. The board keeps its full width and stays visible behind it.
     *
     * <p>Three implementation rules the design names explicitly, all honoured here. The wide-modal
     * idiom, so the content is not squeezed into a default-narrow box. <b>Close is the primary
     * action</b> — a modal blocks the page, which is what a modal is for, and one with no way out
     * traps the whole Console. And nothing with signal-bound effects is mounted inside it: the run
     * graph is reached through a link rather than embedded, so no peek can leak an effect on a
     * process-wide signal (the RunView lesson).
     */
    private void openStory(Story story, Backlog current, RunSummaryDto run) {
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        Situation situation = situationOf(story, run, current);

        Div body = new Div();
        body.addClassName("flex flex-col gap-2 max-h-[70vh] overflow-y-auto");
        // Scoped for the tests: this dialog and the card underneath it deliberately carry the SAME
        // words, so a page-wide text selector cannot tell which of the two it found.
        body.getElement().setAttribute("data-testid", "story-dialog");

        Div title = new Div(key(story) + " · " + title(story));
        title.addClassName("text-lg font-bold");
        body.add(title);

        // Where it is in its life, as a trail rather than a state name — the operator reads the
        // journey, and the column they are looking at is the last step of it.
        Div trail = new Div(trailOf(story));
        trail.addClassName("text-[11px] font-mono text-base-content/40");
        body.add(trail);

        Div sentence = new Div(situation.sentence());
        TextStyle.CAPTION.applyTo(sentence);
        body.add(sentence);

        // The same actions as the card, in the same shape: one primary, everything else quiet. Two
        // surfaces naming one action two ways is how an operator comes to believe they are two
        // different actions.
        Div actions = new Div();
        actions.addClassName("flex flex-wrap items-center gap-x-3 gap-y-2");
        StoryAction ownAction = actionOf(story, situation, current);
        if (ownAction != null) {
            // Wrapped so it also closes: this panel describes a story that has just moved, and every
            // button still on it would be stale. The behaviour is the SAME object the card runs, not
            // a second reading of the story's state — this used to re-decide it from a switch, and
            // that switch sent "Build it anyway" down the ordinary build path, so the dialog's copy
            // of the button quietly did something the card's copy did not.
            Button inDialog = primary(ownAction.label(), () -> {
                dialog.close();
                closeStory();
                ownAction.run().run();
            });
            inDialog.removeClassName("w-full");
            actions.add(inDialog);
        }
        // Questions, here too, and beside the action rather than in front of it. The dialog is the
        // other surface this story is acted on from, and a card that offers two things while the
        // dialog behind it offers one is how an operator comes to believe they are different things.
        List<Decision> questions = questionsFor(story, current);
        if (!questions.isEmpty()) {
            actions.add(action(questions.size() == 1 ? "Answer this question"
                    : "Answer these " + questions.size() + " questions",
                "text-warning", () -> {
                    dialog.close();
                    closeStory();
                    openQuestions(story, questions);
                }));
        }
        if (story.state() == StoryState.REVIEW) {
            actions.add(action("Send it back", "text-base-content/50", () -> {
                dialog.close();
                closeStory();
                confirmRetry(story);
            }));
        }
        if (run != null) {
            actions.add(action("Open the run", "text-base-content/50", () -> {
                dialog.close();
                closeStory();
                openRunGraph(run.getRunId());
            }));
        }
        if (story.state() != StoryState.DONE && story.state() != StoryState.CANCELLED) {
            actions.add(action(dropLabel(story), "text-base-content/50 hover:text-error", () -> {
                dialog.close();
                closeStory();
                confirmDrop(story, null);
            }));
        }
        body.add(actions);

        if (story.narrative() != null && !story.narrative().isBlank()) {
            Div narrative = new Div(story.narrative());
            narrative.addClassName("text-xs text-base-content/70 leading-relaxed pt-1");
            body.add(narrative);
        }

        body.add(field("Delivers", deliversLine(story, current)));
        body.add(workersRow(story, dialog));
        body.add(field("Origin",
            (story.origin() == null ? "" : story.origin().label())
                + (story.author() == null ? "" : " · " + story.author())));
        if (story.rationale() != null && !story.rationale().isBlank()) {
            body.add(field("Why", story.rationale()));
        }
        if (!story.runIds().isEmpty()) {
            Div heading = new Div(story.runIds().size() == 1
                ? "1 attempt" : story.runIds().size() + " attempts");
            heading.addClassName("text-[10px] uppercase tracking-wide text-base-content/40 pt-2");
            body.add(heading);
            body.add(attemptsPanel(story, RunsStore.runs.get()));
        }
        if (story.deliveredCommit() != null) {
            body.add(field("Commit", shortSha(story.deliveredCommit())));
        }
        if (story.integrationCommit() != null) {
            body.add(field("Integrated", shortSha(story.integrationCommit())));
        }

        List<Task> tasks = new ArrayList<>();
        for (Task task : current.tasks()) {
            if (story.id().equals(task.storyId())) {
                tasks.add(task);
            }
        }
        if (!tasks.isEmpty()) {
            Div heading = new Div("Tasks");
            heading.addClassName("text-[10px] uppercase tracking-wide text-base-content/40 pt-2");
            body.add(heading);
            for (Task task : tasks) {
                body.add(taskRow(task));
            }
        }

        // Every question this story's builds ever asked, answered or not. The Approval Center is
        // retired as a destination (UX v3 6) and this is where its history went: attached to the
        // story it was about, rather than in a queue listing run ids.
        List<Decision> asked = new ArrayList<>();
        for (Decision decision : current.decisions()) {
            if (decision.runId() != null
                    && story.runIds().stream().anyMatch(id -> id.equals(decision.runId()))) {
                asked.add(decision);
            }
        }
        if (!asked.isEmpty()) {
            Div heading = new Div("Questions");
            heading.addClassName("text-[10px] uppercase tracking-wide text-base-content/40 pt-2");
            body.add(heading);
            for (Decision decision : asked) {
                body.add(questionRow(decision));
            }
        }

        // Loaded on demand, from a DOM handler, which is the threading context an RMI call needs.
        // Appended in place rather than pushed onto a second surface: this dialog IS the only place
        // depth lives, and a breadcrumb stack inside a modal is a second navigation system.
        Div history = new Div();
        body.add(action("History", "text-base-content/60", () -> {
            history.removeAll();
            history.add(historyPanel(story.id().toString()));
        }));
        body.add(history);

        dialog.add(body);
        Button close = new Button("Close", e -> dialog.close());
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        // Off the close, not off the button: Escape and a click outside close a dialog now, and a
        // dialog left in the document keeps every control it carries — including a Close that can
        // never be clicked, which then shadows the identically named one of whatever opens next.
        // Guarded on identity. A close listener runs on a green thread, so it can arrive
        // AFTER whatever replaced this dialog has been mounted and opened — and would then
        // tear down its successor. It only acts while this is still the dialog on screen.
        dialog.addCloseListener(e -> {
            if (mountedDialog == dialog) {
                closeStory();
            }
        });
        mount(dialog);
        dialog.open();
    }

    /** Drops the story dialog from the document — see {@link #mount} for why hiding is not enough. */
    private void closeStory() {
        mountedDialog = null;
        dialogHost.removeAll();
    }

    /**
     * The story's journey, with where it is now in capitals.
     *
     * <p>The words are the columns' words, so the trail and the board agree: an operator reading
     * "suggested → agreed → building → CAME BACK" can point at the column it names.
     */
    private static String trailOf(Story story) {
        List<String> steps = new ArrayList<>();
        steps.add("suggested");
        StoryState state = story.state();
        boolean everBuilt = !story.runIds().isEmpty();
        if (state != StoryState.DRAFT) {
            steps.add("ready");
        }
        if (everBuilt || state == StoryState.RUNNING || state == StoryState.REVIEW
                || state == StoryState.BLOCKED || state == StoryState.DONE) {
            steps.add("building");
        }
        if (state == StoryState.REVIEW || state == StoryState.BLOCKED || state == StoryState.DONE) {
            steps.add("came back");
        }
        if (state == StoryState.DONE) {
            steps.add("delivered");
        }
        if (state == StoryState.CANCELLED) {
            steps.add("dropped");
        }
        // The last step is where it IS, so it is the one shouted.
        int last = steps.size() - 1;
        steps.set(last, steps.get(last).toUpperCase());
        return String.join(" → ", steps);
    }

    /** One past question, with what was decided about it — or that nothing has been. */
    private Div questionRow(Decision decision) {
        Div row = new Div();
        row.addClassName("flex flex-col border-l-2 pl-2 py-0.5 "
            + (decision.state() == DecisionState.PENDING
                ? "border-warning/60" : "border-base-300"));
        Div head = new Div();
        head.addClassName("flex items-center gap-2");
        Span kind = new Span(kindLabel(decision));
        kind.addClassName("text-[10px] px-1 rounded bg-base-300 text-base-content/60");
        head.getElement().appendChild(kind.getElement());
        Span state = new Span(decision.state() == DecisionState.PENDING
            ? "unanswered" : "answered");
        state.addClassName("text-[10px] " + (decision.state() == DecisionState.PENDING
            ? "text-warning" : "text-base-content/40"));
        head.getElement().appendChild(state.getElement());
        row.add(head);
        String answer = decision.humanResponse();
        Span text = new Span(answer == null || answer.isBlank()
            ? firstLine(decision.briefMarkdown()) : answer);
        text.addClassName("text-xs break-words");
        row.getElement().appendChild(text.getElement());
        return row;
    }

    /** The first meaningful line of a brief - enough to recognise it by, in a list. */
    private static String firstLine(String brief) {
        if (brief == null) {
            return "";
        }
        for (String line : brief.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    private Div taskRow(Task task) {
        Div row = new Div();
        row.addClassName("flex items-center gap-1.5 pl-1");
        Span dot = new Span("·");
        dot.addClassName("text-[10px] " + taskColor(task.state()));
        row.getElement().appendChild(dot.getElement());
        Span title = new Span(task.title() == null ? "" : task.title());
        title.addClassName("text-[11px] text-base-content/60 truncate flex-1");
        row.getElement().appendChild(title.getElement());
        if (task.commitSha() != null && !task.commitSha().isBlank()) {
            Span sha = new Span(shortSha(task.commitSha()));
            sha.addClassName("text-[10px] font-mono text-success/70");
            row.getElement().appendChild(sha.getElement());
        }
        return row;
    }

    /** The story's audit trail, oldest first — how it got here and who moved it. */
    private Div historyPanel(String entityId) {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-1 pt-1");
        List<ChangeEvent> events;
        try {
            events = service.history(entityId);
        } catch (Exception e) {
            // An audit trail that cannot be READ is not an empty one, and on a story whose history is
            // the evidence, reporting the second would be worse than reporting nothing.
            ClientLog.error("PipelineBoard", "could not load the change history for story "
                + entityId + " — its audit trail is unavailable, not empty: " + e);
            Div failed = new Div("Could not load the history — it is unavailable, not empty. "
                + "Close this and open it again to retry.");
            failed.addClassName("text-xs text-error leading-relaxed");
            panel.add(failed);
            return panel;
        }
        if (events.isEmpty()) {
            Div empty = new Div("No recorded changes.");
            TextStyle.CAPTION.applyTo(empty, Emphasis.FAINT);
            panel.add(empty);
            return panel;
        }
        for (ChangeEvent event : events) {
            Div row = new Div();
            row.addClassName("flex flex-col border-l-2 border-base-300 pl-2 py-0.5");
            Div head = new Div();
            head.addClassName("flex items-center gap-2");
            // What happened, in words. This printed the constant — STATE_CHANGED,
            // TOMBSTONED — beside every line of a history the operator is meant to read.
            Span kind = new Span(event.kind() == null ? "" : event.kind().label());
            kind.addClassName("text-[10px] font-mono text-base-content/40");
            head.getElement().appendChild(kind.getElement());
            Span actor = new Span(event.actor() == null ? "" : event.actor());
            actor.addClassName("text-[10px] px-1 rounded bg-base-300 text-base-content/60");
            head.getElement().appendChild(actor.getElement());
            row.add(head);
            Span summary = new Span(event.summary() == null ? "" : event.summary());
            summary.addClassName("text-xs");
            row.getElement().appendChild(summary.getElement());
            panel.add(row);
        }
        return panel;
    }

    // --- building a story, on purpose ------------------------------------------------------------

    /**
     * Says what building this story will do, to which story, and what it will consume — then does it.
     *
     * <p><b>Why this one asks.</b> Nine runs and thirty-seven unanswered decisions came out of a few
     * clicks on a button labelled "Start". Accepting a suggestion and grouping a story are cheap,
     * reversible, and confirming them would be friction for its own sake. This is neither: it
     * dispatches a swarm against a real git repository and spends real tokens, and there is no undo.
     * It earns its place by saying something the operator did not already know rather than by asking
     * "are you sure?" (rule 6 — money is deliberate).
     */
    private void confirmStart(Story story, Dialog parent) {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        String repo = readiness == null ? null : readiness.repositoryPath();
        String project = readiness == null ? null : readiness.projectName();

        Dialog dialog = new Dialog();
        Div title = new Div("Build " + key(story) + " now?");
        title.addClassName("text-lg font-bold mb-1");
        Div subject = new Div(title(story));
        subject.addClassName("text-xs text-base-content/60 mb-3");
        dialog.add(title, subject);

        Div what = new Div();
        what.addClassName("flex flex-col gap-1.5 mb-3");
        what.add(bullet("Dispatches a swarm of coding agents"
            + (repo == null || repo.isBlank() ? " against this project's repository."
                : " against " + repo + ".")
            + " They each get their own copy of the project as it stands right now — including "
            + "every story you have already accepted — write code in it, and run this "
            + "project's tests."));
        what.add(bullet("Spends cloud tokens on every agent turn — design, planning, each worker, "
            + "the judge and the repair round. The budget and what is left of it are on the status "
            + "bar at the bottom of the window."));
        int criteria = story.criterionIds() == null ? 0 : story.criterionIds().size();
        what.add(bullet(criteria == 0
            ? "This story names no checks, so nothing will mechanically decide whether the build "
                + "succeeded — the judgement will be entirely yours."
            : "It must satisfy " + criteria + " " + (criteria == 1 ? "check" : "checks")
                + ", and it comes back to this board for your judgement."));
        // Said in terms of the board the operator is looking at. The old version of this sentence
        // had to explain that the story would leave the columns and appear in a band; there is
        // nowhere for it to go now, which is the point.
        what.add(bullet("Moves " + key(story) + " one column right, into \"Building\", where it says "
            + "what it is doing. It moves on to \"Came back\" by itself when it is finished — you do "
            + "not have to come and collect it."));
        what.add(bullet("If your model server goes down mid-build it PAUSES and resumes by itself "
            + "when the server returns. That costs nothing and needs nothing from you."));
        what.add(bullet("There is no undo. Dropping the story afterwards retires the story; it does "
            + "not recall the agents or refund the tokens."));
        dialog.add(what);

        if (project != null && !project.isBlank()) {
            Div scope = new Div("Project: " + project);
            TextStyle.CAPTION.applyTo(scope);
            dialog.add(scope);
        }

        Button cancel = new Button("Not now", e -> dialog.close());
        Button start = new Button("Build it", e -> {
            // Closed FIRST: the call suspends, and a modal still up while a swarm is dispatching
            // reads as a dialog that did nothing and invites a second click.
            dialog.close();
            if (parent != null) {
                parent.close();
            }
            act(story, () -> service.startSession(story.id().toString()), null);
        });
        start.addClassName("btn-primary");
        dialog.addAction(cancel);
        dialog.addAction(start);
        mount(dialog);
        dialog.open();
    }

    /**
     * Building a story that is still waiting for another one — the operator overruling the
     * schedule, told plainly what they are overruling.
     *
     * <p>Its own dialog rather than a checkbox on the ordinary one, because the thing being overruled
     * is the fix for a real failure: nine stories were once started together and eight of them each
     * built their own version of a domain model the first was still writing. The dialog names what is
     * in the way and what happens if it is wrong, and then does exactly what it is asked.
     */
    private void confirmStartAnyway(Story story, StoryGraph.Blocked blocked) {
        Dialog dialog = new Dialog();
        Div title = new Div("Build " + key(story) + " before what it needs is ready?");
        title.addClassName("text-lg font-bold mb-1");
        Div subject = new Div(title(story));
        subject.addClassName("text-xs text-base-content/60 mb-3");
        dialog.add(title, subject);

        Div what = new Div();
        what.addClassName("flex flex-col gap-1.5 mb-3");
        what.add(bullet(blocked.reason()));
        what.add(bullet("The agents building this story will only see the code as it is today. "
            + "Whatever the story above is going to add is not there yet, so they will either "
            + "invent their own version of it or fail to compile."));
        what.add(bullet("Everything else about a build is the same: it spends cloud tokens, it "
            + "cannot be undone, and it comes back to this board when it is finished."));
        dialog.add(what);

        Button cancel = new Button("Leave it waiting", e -> dialog.close());
        Button start = new Button("Build it anyway", e -> {
            dialog.close();
            act(story, () -> service.startSessionAnyway(story.id().toString()), null);
        });
        start.addClassName("btn-primary");
        dialog.addAction(cancel);
        dialog.addAction(start);
        mount(dialog);
        dialog.open();
    }

    private static Div bullet(String text) {
        Div row = new Div();
        row.addClassName("flex items-start gap-2");
        Span mark = new Span("•");
        mark.addClassName("text-base-content/30 leading-relaxed");
        row.getElement().appendChild(mark.getElement());
        Span body = new Span(text);
        body.addClassName("text-xs leading-relaxed text-base-content/70");
        row.getElement().appendChild(body.getElement());
        return row;
    }

    /**
     * Sends a story back to be built again, asking why first.
     *
     * <p>The reason is optional but requested, because it is the only thing that survives into the
     * next attempt: it goes into the change history, so the record shows a judgement rather than a
     * state that moved on its own. Every entry point comes here — a stopped build, a rejected
     * delivery and a pause the operator has run out of patience with are one act from their side:
     * "this is not done, take it back".
     */
    private void confirmRetry(Story story) {
        boolean stopped = story.state() == StoryState.BLOCKED;
        boolean building = story.state() == StoryState.RUNNING;
        Dialog dialog = new Dialog();
        Div title = new Div(stopped || building ? "Take " + key(story) + " back?"
            : "Send " + key(story) + " back?");
        title.addClassName("text-lg font-bold mb-1");
        Div subject = new Div(title(story));
        subject.addClassName("text-xs text-base-content/60 mb-3");
        dialog.add(title, subject);

        Div what = new Div();
        what.addClassName("flex flex-col gap-1.5 mb-3");
        if (building) {
            // Said first and said plainly: this is the one case where the server genuinely cannot
            // tell the operator whether they are about to duplicate live work.
            what.add(bullet("If a build really is still going, it KEEPS going — this cannot call an "
                + "agent back. It only changes what the board says is left to do."));
        }
        what.add(bullet("It moves back to \"Ready to build\". Nothing starts on its own — you build "
            + "it from its card when you are ready."));
        if (!stopped && !building) {
            what.add(bullet("The commit this build delivered stays in the repository, but stops "
                + "being this story's answer — its checks are not marked verified against it."));
        }
        what.add(bullet("Nothing already run is undone, and no agent is called back."));
        dialog.add(what);

        TextArea why = new TextArea(stopped || building
            ? "What was wrong, and what changed? (optional)"
            : "What was wrong with the delivery? (optional)");
        why.addClassName("textarea textarea-bordered w-full h-24 text-xs");
        dialog.add(why);

        Button cancel = new Button("Not now", e -> dialog.close());
        Button go = new Button(stopped || building ? "Take it back" : "Send it back", e -> {
            String note = why.getValue();
            dialog.close();
            act(story, () -> service.retryStory(story.id().toString(), note), null);
        });
        go.addClassName("btn-primary");
        dialog.addAction(cancel);
        dialog.addAction(go);
        mount(dialog);
        dialog.open();
    }

    /**
     * Says what dropping a story actually does — and then does it.
     *
     * <p><b>Why this one asks too.</b> This control was called "Cancel", sat beside "Promote" in a
     * row of four identical links, and the operator pressed it expecting the story back in the
     * backlog. It does not do that: it is a tombstone, and nothing in the service reverses it. So it
     * is named for the deed, it is quiet rather than red-and-prominent, and it says the irreversible
     * part in the same sentence as the act.
     */
    private void confirmDrop(Story story, Dialog parent) {
        Dialog dialog = new Dialog();
        Div title = new Div("Drop " + key(story) + "?");
        title.addClassName("text-lg font-bold mb-1");
        Div subject = new Div(title(story));
        subject.addClassName("text-xs text-base-content/60 mb-3");
        dialog.add(title, subject);

        Div what = new Div();
        what.addClassName("flex flex-col gap-1.5 mb-3");
        what.add(bullet("It is retired. It leaves the columns and does not come back — there is no "
            + "un-drop."));
        what.add(bullet("The record and its history stay, and it is counted under \"Settled\" at the "
            + "bottom of the board, where it can still be opened."));
        if (!story.runIds().isEmpty()) {
            what.add(bullet("Builds that have already happened are untouched, and anything still in "
                + "flight keeps going — this drops the story, not the build."));
        }
        if (story.state() == StoryState.REVIEW) {
            what.add(bullet("A build delivered a commit for this story. Dropping it leaves the "
                + "commit in the repository and simply never marks its checks verified."));
        }
        what.add(bullet("If you might want it later, leave it where it is — it costs nothing sitting "
            + "on the board."));
        dialog.add(what);

        Button keep = new Button("Keep it", e -> dialog.close());
        Button drop = new Button("Drop it", e -> {
            dialog.close();
            if (parent != null) {
                parent.close();
            }
            act(story, () -> service.cancelStory(story.id().toString()),
                "Dropped " + key(story) + " — it is counted under \"Settled\" below.");
        });
        drop.addClassName("btn-error");
        dialog.addAction(keep);
        dialog.addAction(drop);
        mount(dialog);
        dialog.open();
    }

    // --- grouping, which is optional -------------------------------------------------------------

    // --- performing an action -----------------------------------------------------------------

    /** One service call. Anything it can return or throw ends up in front of the operator. */
    private interface Call {
        String run();
    }

    /**
     * Runs an operator action, accounts for it, and — on success — makes the card flash where it has
     * moved to.
     *
     * <p>The THROW case is why this exists rather than a bare call in the click handler: an RMI
     * failure escaping a DOM handler means the button simply does nothing, with no message on screen
     * and nothing in the server log, because the call never arrived. A button that can fail silently
     * is worse than one that is missing.
     */
    private boolean act(Story story, Call call, String successNote) {  // null note = the flash says it
        // Marked BEFORE the call, and this ordering is the whole mechanism. The server republishes the
        // backlog synchronously INSIDE the call, so the render that draws the card in its new column
        // has already happened by the time the call returns — setting the flag afterwards meant that
        // render never saw it, and the flash never happened at all. Set first, undo on failure.
        UUID previous = moved;
        highlight(story);
        String result;
        try {
            result = call.run();
        } catch (Exception e) {
            moved = previous;
            ClientLog.error("PipelineBoard", "a story action did not reach the server, so nothing "
                + "moved: " + e);
            say("That did not reach the server — nothing was changed. " + e, true);
            return false;
        }
        if (result != null && result.startsWith("error")) {
            // Nothing moved, so nothing may be lit up as though it had. The notice is cleared by the
            // next action, so the log is the only place a refused move is still visible once the
            // operator has moved on.
            moved = previous;
            render();
            ClientLog.error("PipelineBoard", "a story action was refused by the server, the story "
                + "did not move: " + result);
            say(result, true);
            return false;
        }
        // A NULL note is not a silent success (rule 4): it means the feedback is the card itself,
        // flashing where it has moved to. Naming the destination in a sentence was only ever
        // necessary because the destination was off-screen, and nothing is off-screen now — so the
        // whole family of "S1 has left this list and is now in…" messages goes with it (§3.1). A note
        // is still passed for the actions that move a story somewhere a flash cannot show.
        say(successNote, false);
        return true;
    }

    /**
     * Marks a story as just-moved, so the render that follows lights it up in its new column, and
     * schedules the light going out.
     *
     * <p>This is the mechanism behind "movement is shown, not narrated". The render itself is driven by
     * the server republishing the backlog — all this does is make sure that render knows to flash it.
     *
     * <p>The timeout callback re-renders, which is legal: {@link #render} touches no RMI. It reads
     * signals outside an {@code Effect}, which tracks no dependency and is exactly what is wanted —
     * this is a one-off repaint to remove a ring.
     */
    private void highlight(Story story) {
        if (story == null || story.id() == null) {
            return;
        }
        UUID id = story.id();
        moved = id;
        Window.setTimeout(() -> {
            if (disposed || !id.equals(moved)) {
                return; // disposed, or another action has since claimed the highlight
            }
            moved = null;
            render();
        }, HIGHLIGHT_MS);
    }

    // --- small helpers ------------------------------------------------------------------------

    /** The one obvious thing to click on a card: a real, filled button, full width. */
    private Button primary(String label, Runnable run) {
        Button button = new Button(label, e -> run.run());
        button.addClassName("btn-xs btn-primary w-full justify-center mt-0.5");
        return button;
    }

    /**
     * A secondary action: quiet by construction. No border, no fill — a link — because a card that
     * offers two things of equal weight is a card that offers no advice.
     */
    private Div action(String label, String colorClass, Runnable run) {
        Div button = new Div();
        button.addClassName("text-[11px] cursor-pointer underline decoration-dotted "
            + "underline-offset-2 hover:text-base-content " + colorClass);
        button.setText(label);
        button.addDomEventListener("click", e -> run.run());
        return button;
    }

    private Div chip(String label, String tooltip, Runnable action) {
        Div chip = new Div(label);
        chip.addClassName("px-1.5 py-0.5 rounded border border-base-300 cursor-pointer "
            + "hover:bg-base-300/60 hover:text-primary");
        chip.getElement().setAttribute("title", tooltip);
        chip.addDomEventListener("click", e -> action.run());
        return chip;
    }

    /**
     * How many tries each piece of this story's work gets, and the box to change it in.
     *
     * <p>On the story rather than on a settings screen, because it is a fact about THIS piece of
     * work and because a number tucked away in settings is a number nobody remembers is set. Empty
     * means the story says nothing and takes the project's number, which is the normal state; the
     * line says which of the two is true rather than showing a figure whose origin is invisible.
     */
    private Div workersRow(Story story, Dialog dialog) {
        Div row = new Div();
        row.addClassName("flex items-baseline gap-2 flex-wrap");
        Span name = new Span("Tries");
        name.addClassName("text-[10px] uppercase tracking-wide text-base-content/40 w-24 shrink-0");
        row.getElement().appendChild(name.getElement());

        TextField box = new TextField();
        box.addClassName("input input-bordered input-xs w-16 font-mono");
        box.getElement().setAttribute("data-testid", "story-workers");
        box.setValue(story.workersPerTask() == null ? "" : String.valueOf(story.workersPerTask()));
        row.getElement().appendChild(box.getElement());

        Span hint = new Span(story.workersPerTask() == null
            ? "attempts at each piece of work. Empty means this story uses whatever the project asks for."
            : "attempts at each piece of work, asked for on this story. Empty it to go back to the project's number.");
        hint.addClassName("text-[11px] text-base-content/50");
        row.getElement().appendChild(hint.getElement());

        row.getElement().appendChild(action("Save", "text-base-content/50", () -> {
            String typed = box.getValue() == null ? "" : box.getValue().trim();
            int workers;
            try {
                workers = typed.isEmpty() ? 0 : Integer.parseInt(typed);
            } catch (NumberFormatException bad) {
                say("Type a whole number of attempts, or leave the box empty to use the "
                    + "project's number.", true);
                return;
            }
            dialog.close();
            closeStory();
            act(story, () -> service.setStoryWorkers(story.id().toString(), workers),
                workers < 1
                    ? key(story) + " goes back to however many attempts the project asks for."
                    : key(story) + " will get " + workers + " attempts at each piece of work.");
        }).getElement());
        return row;
    }

    private static Div field(String label, String value) {
        Div row = new Div();
        row.addClassName("flex items-baseline gap-2");
        Span name = new Span(label);
        name.addClassName("text-[10px] uppercase tracking-wide text-base-content/40 w-24 shrink-0");
        row.getElement().appendChild(name.getElement());
        Span text = new Span(value == null ? "" : value);
        text.addClassName("text-xs break-words");
        row.getElement().appendChild(text.getElement());
        return row;
    }

    /** The story's newest attempt, from the shared derivation — never a second one. */
    private static RunSummaryDto newestRun(Story story, List<RunSummaryDto> runs) {
        return BuildState.newestRun(story, runs);
    }

    private static boolean claimed(RunSummaryDto run, Backlog current) {
        for (Story story : current.stories()) {
            if (story.runIds().stream().anyMatch(id -> id.toString().equals(run.getRunId()))) {
                return true;
            }
        }
        return false;
    }

    private static String key(Story story) {
        return story.key() == null || story.key().isBlank() ? "A story" : story.key();
    }

    private static String title(Story story) {
        return story.title() == null ? "" : story.title();
    }

    private static List<Story> byState(Backlog backlog, StoryState state) {
        List<Story> out = new ArrayList<>();
        for (Story story : backlog.stories()) {
            if (story.state() == state) {
                out.add(story);
            }
        }
        return out;
    }

    private static String checks(int count) {
        return count == 1 ? "1 check" : count + " checks";
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private static String taskColor(com.swarmcoder.domain.TaskState state) {
        if (state == null) {
            return "text-base-content/30";
        }
        return switch (state) {
            case DONE, INTEGRATED, SELECTED -> "text-success";
            case BLOCKED, CANCELLED -> "text-error";
            case PENDING, READY -> "text-base-content/30";
            default -> "text-info";
        };
    }

    /**
     * A card's situation: the badge word, how it looks, what it says, and — for a story in build —
     * which {@link BuildState.Kind} it came from, so the card can decide whether elapsed time and
     * cost mean anything on it.
     */
    private record Situation(String badgeLabel, String badge, String border, String sentence,
                             BuildState.Kind kind) { }
}
