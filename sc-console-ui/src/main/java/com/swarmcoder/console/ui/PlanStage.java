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

import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.console.api.BrdSignals;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.Resizer;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 — the pipeline: turning agreed scope into work, and watching it through.
 *
 * <p>It used to show five things at once: a backlog triage LIST, a backlog BOARD, an always-mounted
 * "add iteration" form, the requirements reference and a story inspector. The list and the board were
 * two renderings of the same stories, so the operator's first job on arriving was to work out which
 * of the two copies of a story they were meant to act on. There is one board now
 * ({@link PipelineBoard}), and the rest of this class is about making sure it is not crowded again.
 *
 * <p>That board is the whole pipeline as of UX v3 §3.1 — suggested through delivered, including the
 * stories being built, which used to live on a separate stage. So this stage no longer hands work
 * over to another one: a story it starts stays on the board in front of the operator.
 *
 * <h2>What is on the screen, top to bottom</h2>
 *
 * <ul>
 *   <li>{@link NextStepBar}, across the FULL width of the stage. It was previously mounted twice —
 *       once inside the triage panel and once inside the board — where it read as a strip belonging
 *       to a column rather than as the answer to "what do I do here". One bar, spanning everything,
 *       directly under the stage bar, is the only placement that lands. Its "plan stories" button
 *       opens the board's wizard rather than navigating, because the operator is already here.</li>
 *   <li>The board, taking the width it needs.</li>
 *   <li>{@link RequirementsReference}, read-only, collapsed to a labelled edge.</li>
 * </ul>
 *
 * <p>The requirements column is read-only on purpose and says so. Editing them is the previous
 * stage's job — one click away on the stage bar — because a text box next to a plan invites you to
 * move the goalposts to fit the plan rather than the other way round.
 *
 * <h2>Why the reference starts CLOSED</h2>
 *
 * <p>The earlier reasoning was "you plan against requirements, so hiding them makes the thing the
 * stage answers to the thing you have to go and find", and it opened by default. That was the right
 * principle and the wrong control: coverage is a question you ask ABOUT a story — "does anything
 * deliver R3:C2 yet?" — not a text you read continuously, and a permanently open 20rem column made
 * every board column narrower for every operator all of the time. So it is collapsed, and the edge
 * that opens it is <b>labelled with the word plus the count of criteria nothing has claimed</b>. That
 * number is the whole reason to open the pane, so the prompt to open it is on the thing you click,
 * which is the one arrangement that cannot go unnoticed. The choice is persisted client-side like the
 * theme, because it is about this browser rather than about the project — and it matches
 * {@link RequirementsStage}'s knowledge pane, which is the same kind of thing in the same position.
 *
 * <p>Bound to two process-wide shared signals for the edge's count; callers MUST {@link #dispose()}
 * it, and {@code StageHost} does.
 */
final class PlanStage extends Div implements Disposable {

    private static final String KEY = "console.plan.requirements";

    private final PipelineBoard board = new PipelineBoard();
    private final RequirementsReference reference =
        new RequirementsReference(() -> setOpen(false));
    /**
     * <b>Plan's own guidance</b>, which is the whole point of the component now.
     *
     * <p>Two earlier versions of this were wrong in opposite directions. First it was filtered to
     * {@code plan-stories} and {@code start-run}, which hid it in exactly the situation the operator
     * complained about: they stood in Plan while the global next step was still in Requirements, so
     * the screen was empty AND silent about why. Then the filter came off — and the bar spent its
     * life saying "go and promote your requirements", permanently, while six proposed stories sat on
     * the board underneath it. Guidance about another stage is not this stage's guidance.
     *
     * <p>It now reads the server's PLAN guidance: accept the suggestions that are real work, then
     * build them — each step announcing the next, and a "waiting for" line naming the stage it needs
     * when Plan genuinely cannot act. There is no scheduling step between the two, and the sentence
     * says so: {@code startSession} asks only that a story be READY, so the guidance that used to
     * sit here telling the operator to put it in an iteration first was naming a gate the server has
     * never had.
     */

    private final Div referencePane = new Div();
    private final Div edge = new Div();
    private final Span edgeLabel = new Span("Requirements");
    private final Resizer resizer;
    /** Effects this stage owns on process-wide signals; every one is released in {@link #dispose}. */
    private final List<Disposable> disposables = new ArrayList<>();

    PlanStage() {
        addClassName("flex flex-col h-full min-h-0");

        // Full width and first: this is the sentence the operator reads before anything else, and
        // the board's wizard is what its button opens.

        // Creating an iteration is not a step on the way to anything any more — grouping never
        // gated a run — so the server no longer names it as one. The hook stays because it costs
        // nothing and NextAction travels the wire by name: an older server that still sends
        // CREATE_ITERATION gets a button that does the thing rather than one that navigates here.

        // The rest are answered by a control on the card itself, not by one button up here that
        // would have to pick a story on the operator's behalf. Their off-stage label is "Go to
        // Plan", which on the Plan stage is a button that does nothing at all.

        // No guidance bar. There is ONE next-step line now and it lives in the status header (UX v3
        // §3): three of these — one per stage — meant the operator's answer to "what do I do" depended
        // on which stage they happened to be standing in, and each bar spent most of its life talking
        // about a different stage's business.

        Div row = new Div();
        row.addClassName("flex flex-row flex-1 min-h-0");
        add(row);

        Div center = new Div();
        center.addClassName("flex-1 min-w-0 min-h-0 flex flex-col");
        center.add(board);
        row.add(center);

        // The collapsed state: a full-height strip carrying the word "Requirements" down its edge,
        // with the count of unclaimed criteria under it, so the pane advertises both itself and the
        // reason to open it instead of hiding behind a glyph.
        // whitespace-nowrap because in a vertical writing mode the strip's WIDTH is the text's line
        // length: without it the label wrapped onto a second column inside a 28px strip and read as
        // "Requi rements", which is a label that has stopped being one.
        edge.addClassName("shrink-0 w-7 border-l border-base-300 bg-base-200/40 cursor-pointer "
            + "flex items-center justify-center whitespace-nowrap text-[11px] tracking-wider "
            + "text-base-content/50 hover:text-primary hover:bg-base-300/50");
        edge.setStyle("writing-mode", "vertical-rl");
        edge.getElement().appendChild(edgeLabel.getElement());
        edge.getElement().setAttribute("title", "Show the requirements you are planning against");
        // By testid, because the edge's label is the word "Requirements" and so is the stage bar's
        // — a text selector would find the stage chip first and navigate away instead of opening
        // the pane.
        edge.getElement().setAttribute("data-testid", "plan-requirements-edge");
        edge.addDomEventListener("click", e -> setOpen(true));
        row.add(edge);

        referencePane.addClassName("w-80 shrink-0 min-h-0 flex flex-col border-l border-base-300");
        referencePane.getElement().setAttribute("data-testid", "plan-requirements-pane");
        referencePane.add(reference);
        resizer = new Resizer(referencePane, Resizer.Orientation.VERTICAL, true);
        row.add(resizer, referencePane);

        // Closed unless this browser has said otherwise — see the class javadoc for why the default
        // moved.
        applyOpen("1".equals(Js.localGet(KEY)));

        // No RMI in here: both signals are already published, and this runs inside an Effect.
        disposables.add(Effect.create(() -> {
            int unclaimed = RequirementsReference.unclaimedCriteria(
                BrdSignals.CURRENT.get(), BacklogSignals.CURRENT.get());
            edgeLabel.setText(unclaimed == 0
                ? "Requirements"
                : "Requirements · " + unclaimed + " unplanned");
        }));
    }

    @Override
    public void dispose() {
        board.dispose();
        reference.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    private void setOpen(boolean open) {
        Js.localSet(KEY, open ? "1" : "0");
        applyOpen(open);
    }

    private void applyOpen(boolean open) {
        referencePane.setVisible(open);
        resizer.setVisible(open);
        edge.setVisible(!open);
    }
}
