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

import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.console.api.PlanningFlowService;
import com.swarmcoder.console.api.PlanningFlowService_Stub;
import com.swarmcoder.console.api.PlanningFlowSignals;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Checkbox;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.RadioButtonGroup;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * The backlog-planning wizard (docs/GUIDED_FLOWS_DESIGN.md §2) — the guided replacement for the
 * now-removed {@code /backlog} chat mode. One dialog walks the whole process:
 *
 * <pre>requirements ─▶ [Plan stories] ─▶ progress ─▶ questions ─▶ review ─▶ apply</pre>
 *
 * <p>The sibling of {@link IntakeWizard} and built to the same rules, which were learned the hard
 * way and are repeated here because they are not obvious from the code alone:
 *
 * <ul>
 *   <li>The first frame comes from {@code planning()}'s RETURN VALUE, not from the signal. Binding
 *       only to the signal made the dialog depend on a broadcast arriving, and until it did the
 *       operator saw the empty "no flow" state.</li>
 *   <li>The body is rebuilt only when {@link #key} changes. A rebuild replaces the answer fields,
 *       and doing that while the operator is filling them in throws away what they typed.</li>
 *   <li>No RMI from a render, an {@link Effect}, or a native JS callback — the framework only
 *       permits calls from its green-threaded handlers, and anywhere else throws "suspension point
 *       reached from non-threading context".</li>
 *   <li>Field values come from {@code getValue()}. {@link TextField} republishes its model on every
 *       {@code input} event, so the component already knows what is on screen as it is typed —
 *       reaching past it into {@code element.value} buys nothing and costs the type system.</li>
 *   <li>Failures are reported on {@link #noticeLine}, inside this dialog. A message on the surface
 *       underneath is a message the operator cannot see, which reads as a dead button.</li>
 * </ul>
 *
 * <p>Unlike intake there is no input-collecting step: the inputs are the project's own requirements
 * and its backlog. The first screen shows what planning would read — how much agreed scope no story
 * yet claims — because a wizard with one button and no account of what it reads is a wizard nobody
 * trusts to press.
 *
 * <h2>Mounting it</h2>
 *
 * <pre>{@code
 * private final PlanningWizard wizard = new PlanningWizard();
 * ...
 * add(wizard);          // last, so the modals are not flex items in the middle of a column
 * wizard.onApplied(status::setText);
 * ...
 * wizard.dispose();     // the effects are bound to a process-wide signal
 * }</pre>
 *
 * <p>It is a {@link Div} holding two {@link Dialog}s rather than being one, for the reason given on
 * {@link IntakeWizard}: a daisyUI {@code .modal-box} carries a {@code transform}, which makes it the
 * containing block for {@code position: fixed} descendants, so the background dialog cannot be a
 * child of the wizard's own modal box and still open over it. The wrapper is
 * {@code display: contents}, so mounting it is exactly as weightless as mounting the two dialogs.
 */
final class PlanningWizard extends Div implements Disposable {

    private final PlanningFlowService service = new PlanningFlowService_Stub();
    /** What the wizard renders: seeded from {@code planning()}, kept live by the shared signal. */
    private final ValueSignal<FlowView> flow = new ValueSignal<>(FlowView.none());

    private final Dialog dialog = new Dialog();
    /** The per-question background, opened from the info icon. Reused, contents replaced. */
    private final Dialog detail = new Dialog();
    private final Div progressBar = new Div();
    private final Div stepLine = new Div();
    private final Div errorLine = new Div();
    private final Div body = new Div();
    private final Div footer = new Div();
    private final ValueSignal<String> notice = new ValueSignal<>("");
    private final Div noticeLine = new Div();

    private final List<Disposable> disposables = new ArrayList<>();
    private String rendered = "";
    /** Told what happened after Apply closes the dialog, so the outcome is not lost with it. */
    private java.util.function.Consumer<String> onApplied;

    PlanningWizard() {
        this("planning-wizard");
    }

    /**
     * The same wizard under a name of its own — see {@link IntakeWizard#IntakeWizard(String)} for
     * why a second copy on the page has to be selectable apart from the first.
     */
    PlanningWizard(String testId) {
        // The wrapper must not become a flex item wherever it is mounted: the two modals are
        // position:fixed and were previously added straight to the host's column.
        addClassName("contents");
        dialog.setWidth("48rem");
        // Scoped for the browser tests: the wizard and the per-question window it can open
        // over itself are both dialogs, and both carry the same words.
        dialog.getElement().setAttribute("data-testid", testId);
        detail.getElement().setAttribute("data-testid", testId + "-detail");
        detail.setWidth("48rem");
        dialog.add(header(), progressPane(), body, noticeLine, footer);
        add(dialog, detail);

        // The shared signal is the live feed; the direct call in open() supplies the first frame.
        disposables.add(Effect.create(() -> {
            FlowView published = PlanningFlowSignals.CURRENT.get();
            if (published != null && published.flow() != null) {
                flow.set(published);
            }
        }));
        disposables.add(Effect.create(this::renderProgress));
        disposables.add(Effect.create(this::renderBodyIfChanged));
        disposables.add(Effect.create(() -> {
            String text = notice.get();
            noticeLine.setText(text == null ? "" : text);
            noticeLine.getElement().getStyle().setProperty("display",
                text == null || text.isEmpty() ? "none" : "block");
        }));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Where to report the outcome once Apply has closed this dialog. */
    void onApplied(java.util.function.Consumer<String> listener) {
        this.onApplied = listener;
    }

    /**
     * Runs whenever this window closes, by any route — the button, Escape, or a click outside.
     *
     * <p>It exists so a caller that handed over to this wizard can take the operator back. The
     * getting-started guide does exactly that: it closes itself, opens this, and re-opens on the
     * next step when this closes. Two modals open at once was tried first and does not work here —
     * the browser draws both in the top layer, and the one this opened was painted BEHIND the guide
     * that opened it, dimmed by its backdrop and unusable.
     */
    void onClosed(Runnable action) {
        if (action != null) {
            dialog.addCloseListener(e -> action.run());
        }
    }

    /**
     * Opens the wizard. The server call both creates the flow on first use and publishes it; the
     * return value seeds the first frame so the dialog is never blank waiting for a broadcast.
     */
    void open() {
        notice.set("");
        try {
            FlowView view = service.planning();
            if (view != null) {
                flow.set(view);
            }
        } catch (Exception e) {
            notice.set("could not load the plan: " + e);
        }
        // Force a redraw even if the signal value is unchanged since last time: the operator may
        // have closed the dialog mid-state, and the body was never torn down.
        rendered = "";
        renderBodyIfChanged();
        dialog.open();
    }

    // --- chrome ---------------------------------------------------------------------------------

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-start gap-3 mb-3");
        Div titles = new Div();
        titles.addClassName("flex-1");
        Div title = new Div("Plan stories");
        title.addClassName("text-lg font-bold");
        Div subtitle = new Div("Turns the requirements you have agreed into work: stories that "
            + "deliver named checks, for you to review before any of them lands.");
        subtitle.addClassName("text-xs text-base-content/60 mt-0.5");
        titles.add(title, subtitle);
        // Always available, in every state. A wizard that can reach a state its own UI offers no
        // way out of is a trap, and "start again" is the first thing anyone reaches for.
        Button over = new Button("Discard this plan", e -> {
            GuidedFlow f = flow.get() == null ? null : flow.get().flow();
            if (f == null) {
                notice.set("there is no plan to discard yet");
                return;
            }
            int had = flow.get().proposals().size();
            call(() -> service.startOver(f.id().toString()));
            // Say what happened either way. Discarding an empty plan changes nothing on screen, and
            // silence there reads as a broken button rather than as "nothing to do".
            if (notice.get().isEmpty()) {
                notice.set(had == 0
                    ? "Nothing to discard — this plan had no proposals. Nothing in the backlog is "
                        + "affected."
                    : "Discarded " + had + " unapplied proposal(s). Stories already in the backlog "
                        + "are untouched.");
            }
        });
        over.addClassName("btn-xs btn-ghost");
        over.getElement().setAttribute("title",
            "Throw away this planning run's questions and unapplied proposals. Nothing already in "
            + "the backlog is touched.");
        bar.add(titles, over, iconButton("x", "Close — the plan keeps running", dialog::close));
        return bar;
    }

    private Div progressPane() {
        Div pane = new Div();
        pane.addClassName("mb-3");
        Div track = new Div();
        track.addClassName("h-1.5 w-full rounded bg-base-300 overflow-hidden");
        progressBar.addClassName("h-full bg-primary transition-all");
        progressBar.getElement().getStyle().setProperty("width", "0%");
        track.add(progressBar);
        stepLine.addClassName("text-xs text-base-content/60 mt-1.5");
        errorLine.addClassName("text-xs text-error mt-1");
        noticeLine.addClassName("text-xs text-base-content/70 mt-2");
        pane.add(track, stepLine, errorLine);
        return pane;
    }

    private void renderProgress() {
        GuidedFlow f = flow.get() == null ? null : flow.get().flow();
        int total = f == null || f.totalSteps() <= 0 ? 4 : f.totalSteps();
        int step = f == null ? 0 : f.step();
        int percent = f != null && f.state() == GuidedFlowState.APPLIED
            ? 100 : Math.max(0, Math.min(100, step * 100 / total));
        progressBar.getElement().getStyle().setProperty("width", percent + "%");
        String label = f == null || f.stepLabel() == null ? "" : f.stepLabel();
        // A real step label, not "phase 2": this sentence is the only account of what the planner
        // is doing while it thinks, and a number says nothing the progress bar has not already said.
        stepLine.setText(step > 0 ? "Step " + step + " of " + total + " — " + label : label);
        String error = f == null ? null : f.error();
        errorLine.setText(error == null ? "" : error);
    }

    /**
     * A fingerprint of everything the body's STRUCTURE depends on. Answers typed into a field and
     * proposals ticked on and off deliberately do not appear here — they are already on screen, and
     * rebuilding to show them back would discard whatever else is half-typed.
     */
    private String key() {
        FlowView view = flow.get();
        GuidedFlow f = view == null ? null : view.flow();
        if (f == null) {
            return "none";
        }
        // Whether autonomous running owns these gates is part of the STRUCTURE: switching it off
        // has to bring the buttons back without a reload, and this fingerprint is what decides
        // whether the body is rebuilt at all. Left out of it, the controls would stay grey.
        return f.id() + "|" + f.state() + "|" + view.questions().size() + "|"
            + view.proposals().size() + "|" + f.error() + "|" + view.unclaimedCriteria()
            + "|" + view.backlogStories() + "|" + view.coverage().length()
            + "|" + PilotGates.handedOver();
    }

    private void renderBodyIfChanged() {
        String current = key();
        if (current.equals(rendered)) {
            return;
        }
        rendered = current;
        body.removeAll();
        footer.removeAll();
        footer.addClassName("flex items-center justify-end gap-2 mt-4");
        FlowView view = flow.get();
        GuidedFlow f = view == null ? null : view.flow();
        if (f == null) {
            body.add(hint("Select a project first — a backlog belongs to one."));
            return;
        }
        switch (f.state()) {
            case AWAITING_ANSWERS -> renderQuestions(view);
            case REVIEW -> renderReview(view);
            case RUNNING -> renderRunning(f);
            case APPLIED -> renderApplied(f, view);
            default -> renderInputs(view, f);   // DRAFT and FAILED both start from here
        }
    }

    // --- step 1: what will be read ----------------------------------------------------------------

    private void renderInputs(FlowView view, GuidedFlow f) {
        String flowId = f.id().toString();
        body.add(hint("Planning reads the requirements you have agreed, the checks each one "
            + "carries, and the backlog as it already stands. It proposes stories over the checks "
            + "no story delivers yet — nothing is written to the backlog until you review it and "
            + "press Apply."));

        Div summary = new Div();
        summary.addClassName("flex flex-wrap gap-4 mt-3");
        summary.add(figure(String.valueOf(view.existingRequirements()), "requirements in the BRD"));
        summary.add(figure(String.valueOf(view.unclaimedCriteria()), "checks no story delivers"));
        summary.add(figure(String.valueOf(view.backlogStories()), "stories already planned"));
        body.add(summary);

        Div heading = new Div("What the planner will be asked to close");
        heading.addClassName("text-[11px] uppercase tracking-wide text-base-content/40 mt-4");
        body.add(heading);
        // The coverage report verbatim, not a count of it. "7 criteria are unclaimed" is a number;
        // this names which requirements they belong to, which is what makes the plan checkable.
        Div coverage = new Div(view.coverage().isEmpty()
            ? "Nothing to report yet." : view.coverage());
        coverage.addClassName("text-xs whitespace-pre-wrap font-mono mt-1 p-3 rounded "
            + "bg-base-200/50 max-h-72 overflow-y-auto");
        body.add(coverage);

        Button plan = new Button("Plan stories", e -> call(() -> service.start(flowId)));
        plan.addClassName("btn-primary");
        if (view.unclaimedCriteria() == 0) {
            // The server refuses this too; disabling here saves the operator a round trip to be
            // told what the figures above already say.
            plan.getElement().setAttribute("disabled", "disabled");
            plan.getElement().setAttribute("title", "Every check is already claimed by a story "
                + "— there is nothing left to plan.");
        }
        footer.add(plan);
    }

    /** One number with its caption — the planning equivalent of intake's document list. */
    private Div figure(String value, String caption) {
        Div box = new Div();
        box.addClassName("flex flex-col");
        Div number = new Div(value);
        number.addClassName("text-xl font-semibold leading-none");
        Div label = new Div(caption);
        label.addClassName("text-[11px] text-base-content/50 mt-0.5");
        box.add(number, label);
        return box;
    }

    // --- step 2: running --------------------------------------------------------------------------

    private void renderRunning(GuidedFlow f) {
        body.add(hint("The planner is reading your requirements and the backlog. This takes a "
            + "minute or two on a large BRD — you can close this window and come back; it keeps "
            + "running."));
        Button cancel = new Button("Cancel", e -> call(() -> service.cancel(f.id().toString())));
        cancel.addClassName("btn-sm btn-ghost");
        footer.add(cancel);
    }

    // --- step 3: questions ------------------------------------------------------------------------

    private void renderQuestions(FlowView view) {
        String flowId = view.flow().id().toString();
        body.add(hint("The planner has decisions it cannot make for you — what comes first, what is "
            + "out of scope for now, what the first slice must prove. Answer what matters; skip "
            + "anything you do not want to decide now and it will say what it assumed instead."));
        Div list = new Div();
        list.addClassName("flex flex-col gap-3 max-h-96 overflow-y-auto mt-2");
        for (FlowQuestion question : view.questions()) {
            list.add(questionRow(flowId, question));
        }
        body.add(list);

        Button skipAll = new Button("Skip the rest", e -> {
            for (FlowQuestion question : flow.get().questions()) {
                if (!question.skipped() && (question.answer() == null || question.answer().isBlank())) {
                    service.skip(flowId, question.id().toString());
                }
            }
            call(() -> service.submitAnswers(flowId));
        });
        skipAll.addClassName("btn-sm btn-ghost");
        Button go = new Button("Continue", e -> call(() -> service.submitAnswers(flowId)));
        go.addClassName("btn-primary");
        // The planner's questions are the pilot's to answer while it is building on its own. The
        // questions stay on screen; only the two controls that submit them are held.
        PilotGates.hold(skipAll);
        PilotGates.hold(go);
        if (PilotGates.handedOver()) {
            body.add(PilotGates.note());
        }
        footer.add(skipAll, go);
    }

    private Div questionRow(String flowId, FlowQuestion question) {
        String questionId = question.id().toString();
        Div row = new Div();
        row.addClassName("p-3 rounded border border-base-300");
        Div head = new Div();
        head.addClassName("flex items-start gap-2");
        Div subject = new Div(question.subject() == null ? "" : question.subject());
        subject.addClassName("text-[11px] uppercase tracking-wide text-base-content/40 flex-1");
        head.add(subject);
        if (question.background() != null && !question.background().isBlank()) {
            head.add(iconButton("info", "More context behind this question",
                () -> showDetail(question)));
        }
        row.add(head);
        // The requirements first, then the question: a question about handles the operator has not
        // memorised is unanswerable on its own, and making them go and look them up is exactly the
        // friction this wizard exists to remove.
        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            Div quote = new Div(question.sourceQuote());
            quote.addClassName("text-xs mt-1.5 pl-2 border-l-2 border-base-300 "
                + "text-base-content/70 whitespace-pre-wrap");
            row.add(quote);
        }

        Div text = new Div(question.text() == null ? "" : question.text());
        text.addClassName("text-sm mt-1.5 font-medium");
        row.add(text);

        if (question.kind() == FlowQuestionKind.CHOICE) {
            RadioButtonGroup choices = new RadioButtonGroup("pq-" + questionId);
            choices.addClassName("flex flex-col gap-1 mt-2 text-sm");
            choices.setItems(new ArrayList<>(question.options()));
            if (question.answer() != null) {
                choices.setValue(question.answer());
            }
            choices.addValueChangeListener(e ->
                call(() -> service.answer(flowId, questionId, choices.getValue())));
            row.add(choices);
            // The options are the planner's guess at the answer space, and they are routinely not
            // quite it. A free-text line lets the operator qualify the choice — "second, but only
            // after billing" — instead of picking the least wrong radio button and moving on.
            TextField note = new TextField("Add detail (optional) — anything the options miss");
            note.addClassName("input-xs w-full mt-2");
            if (question.note() != null) {
                note.setValue(question.note());
            }
            note.addValueChangeListener(e -> {
                String typed = note.getValue();
                call(() -> service.note(flowId, questionId, typed == null ? "" : typed));
            });
            row.add(note);
        } else {
            TextField answer = new TextField("Your answer");
            answer.addClassName("input-sm w-full mt-2");
            if (question.answer() != null) {
                answer.setValue(question.answer());
            }
            answer.addValueChangeListener(e -> {
                String typed = answer.getValue();
                call(() -> service.answer(flowId, questionId, typed == null ? "" : typed));
            });
            row.add(answer);
        }

        Div skipRow = new Div();
        skipRow.addClassName("flex items-center gap-2 mt-2");
        Checkbox skip = new Checkbox();
        skip.addClassName("checkbox-xs");
        skip.setValue(question.skipped());
        skip.addValueChangeListener(e -> call(() -> Boolean.TRUE.equals(skip.getValue())
            ? service.skip(flowId, questionId)
            : service.answer(flowId, questionId, "")));
        Span label = new Span("Skip — let the planner decide and say so");
        TextStyle.CAPTION.applyTo(label);
        skipRow.add(skip, label);
        row.add(skipRow);
        return row;
    }

    // --- step 4: review ---------------------------------------------------------------------------

    private void renderReview(FlowView view) {
        String flowId = view.flow().id().toString();
        body.add(hint("Nothing has been added yet. Untick anything you do not want, then apply. "
            + "What lands is a suggestion — you still decide whether it is real work."));
        Div list = new Div();
        list.addClassName("flex flex-col gap-2 max-h-96 overflow-y-auto mt-2");
        for (FlowProposal proposal : view.proposals()) {
            list.add(proposalRow(flowId, proposal));
        }
        body.add(list);

        Button none = new Button("Untick all", e -> call(() -> service.setAllAccepted(flowId, false)));
        none.addClassName("btn-sm btn-ghost");
        Button all = new Button("Tick all", e -> call(() -> service.setAllAccepted(flowId, true)));
        all.addClassName("btn-sm btn-ghost");
        Button back = new Button("Plan again", e -> call(() -> service.reopen(flowId)));
        back.addClassName("btn-sm btn-ghost");
        Button apply = new Button("Add to the backlog", e -> {
            call(() -> service.apply(flowId));
            // Close on success: the operator's next act is to look at the backlog, which has
            // already redrawn from its own signal behind this dialog. A failure keeps it open,
            // because the message explaining it is in here.
            if (notice.get().isEmpty()) {
                dialog.close();
                if (onApplied != null) {
                    GuidedFlow applied = flow.get() == null ? null : flow.get().flow();
                    onApplied.accept(applied == null || applied.stepLabel() == null
                        ? "Stories added to the backlog." : applied.stepLabel());
                }
            }
        });
        apply.addClassName("btn-primary");
        // Adding the planner's proposals to the backlog is the pilot's own next step while it is building on
        // its own. Everything above stays readable and every tick stays clickable - what is held is
        // the one control that writes.
        PilotGates.hold(apply);
        if (PilotGates.handedOver()) {
            body.add(PilotGates.note());
        }
        footer.add(back, none, all, apply);
    }

    private Div proposalRow(String flowId, FlowProposal proposal) {
        String proposalId = proposal.id().toString();
        Div row = new Div();
        row.addClassName("flex items-start gap-3 p-3 rounded border border-base-300");

        Checkbox accept = new Checkbox();
        accept.addClassName("checkbox-sm mt-1");
        accept.setValue(proposal.accepted());
        accept.addValueChangeListener(e -> call(() -> service.setAccepted(flowId, proposalId,
            Boolean.TRUE.equals(accept.getValue()))));
        if (proposal.kind() == FlowProposalKind.CONFLICT) {
            // Accepting a conflict would be picking a side on the operator's behalf; the server
            // refuses to write one, so offering the tick here would be a lie.
            accept.getElement().setAttribute("disabled", "disabled");
        }

        Div column = new Div();
        column.addClassName("flex-1 min-w-0");
        Div head = new Div();
        head.addClassName("flex items-center gap-2 flex-wrap");
        Span badge = new Span(proposal.kind() == FlowProposalKind.CONFLICT
            ? "! conflict" : "+ story");
        badge.addClassName("badge badge-xs " + (proposal.kind() == FlowProposalKind.CONFLICT
            ? "badge-error" : "badge-success"));
        head.add(badge);
        Span title = new Span(proposal.title() == null ? "(untitled)" : proposal.title());
        title.addClassName("text-sm font-medium");
        head.add(title);
        // The criteria refs ARE the story's identity — what it may claim and what apply resolves —
        // so they are on the row, not hidden behind a details link.
        if (proposal.handle() != null && !proposal.handle().isBlank()) {
            Span refs = new Span(proposal.handle());
            refs.addClassName("text-[11px] font-mono text-base-content/50");
            head.add(refs);
        }
        column.add(head);

        if (proposal.before() != null && !proposal.before().isBlank()) {
            column.add(block("cannot hold with", proposal.before()));
        }
        if (proposal.after() != null && !proposal.after().isBlank()) {
            column.add(block("", proposal.after()));
        }
        if (proposal.impact() != null && !proposal.impact().isBlank()) {
            // Directly under the title, not buried under the story: this is what the tick costs.
            Div impact = new Div(proposal.impact());
            impact.addClassName("text-[11px] text-warning mt-1");
            column.add(impact);
        }
        if (proposal.rationale() != null && !proposal.rationale().isBlank()) {
            Div why = new Div("Why: " + proposal.rationale());
            why.addClassName("text-[11px] text-base-content/50 mt-1");
            column.add(why);
        }
        row.add(accept, column);
        return row;
    }

    private Div block(String label, String text) {
        Div wrap = new Div();
        wrap.addClassName("mt-1");
        if (!label.isEmpty()) {
            Div caption = new Div(label);
            caption.addClassName("text-[10px] uppercase tracking-wide text-base-content/40");
            wrap.add(caption);
        }
        Div content = new Div(text);
        content.addClassName("text-xs whitespace-pre-wrap text-base-content/80");
        wrap.add(content);
        return wrap;
    }

    // --- done -------------------------------------------------------------------------------------

    private void renderApplied(GuidedFlow f, FlowView view) {
        int accepted = 0;
        int declined = 0;
        for (FlowProposal proposal : view.proposals()) {
            if (proposal.accepted() && proposal.kind() != FlowProposalKind.CONFLICT) {
                accepted++;
            } else {
                declined++;
            }
        }
        body.add(hint("Added " + accepted + " stor" + (accepted == 1 ? "y" : "ies")
            + (declined == 0 ? "." : ", and left " + declined + " unapplied.")
            + " They are waiting in the Suggested column of the Pipeline. Accept the ones that "
            + "are real work and they move across to Ready to build."));
        Button again = new Button("Plan again", e -> call(() -> service.reopen(f.id().toString())));
        again.addClassName("btn-sm btn-outline");
        Button done = new Button("Done", e -> dialog.close());
        done.addClassName("btn-primary");
        footer.add(again, done);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Opens the background behind one question.
     *
     * <p>A second dialog rather than an expanding row: this is reference material read once while
     * deciding, and growing the row would push every other question off screen just when the
     * operator wants to compare them.
     */
    private void showDetail(FlowQuestion question) {
        detail.removeAll();
        Div title = new Div(question.subject() == null || question.subject().isBlank()
            ? "Background" : question.subject());
        title.addClassName("text-lg font-bold");
        detail.add(title);

        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            Div quote = new Div(question.sourceQuote());
            quote.addClassName("text-xs pl-2 border-l-2 border-base-300 text-base-content/70 "
                + "whitespace-pre-wrap mb-3");
            detail.add(quote);
        }
        Div background = new Div(question.background() == null ? "" : question.background());
        background.addClassName("text-sm whitespace-pre-wrap leading-relaxed");
        detail.add(background);

        Div asked = new Div(question.text() == null ? "" : question.text());
        asked.addClassName("text-sm font-medium mt-4 pt-3 border-t border-base-300");
        detail.add(asked);

        Div actions = new Div();
        actions.addClassName("flex justify-end gap-2 mt-4");
        Button close = new Button("Close", e -> detail.close());
        close.addClassName("btn-sm btn-primary");
        actions.add(close);
        detail.add(actions);
        detail.open();
    }

    /** One service call. Anything it can return or throw ends up in front of the operator. */
    private interface Call {
        String run();
    }

    /**
     * Runs a service call and puts whatever comes back in front of the operator — "" means it
     * worked, an "error: …" string is shown, and a THROW is shown too.
     *
     * <p>The throw case is the reason this exists. Calling the stub inline means an RMI failure
     * escapes the click handler and the button simply does nothing: no dialog change, no message,
     * nothing in the server log because the call never arrived. A button that can fail silently is
     * worse than one that is missing.
     */
    private void call(Call callable) {
        try {
            String result = callable.run();
            notice.set(result == null || result.isEmpty() ? "" : result);
        } catch (Exception e) {
            notice.set("that did not reach the server: " + e);
        }
    }

    private Div iconButton(String glyph, String tooltip, Runnable action) {
        Div button = new Div();
        button.addClassName("cursor-pointer text-base-content/40 hover:text-primary shrink-0");
        button.add(Icon.of(glyph, "w-4 h-4"));
        button.getElement().setAttribute("title", tooltip);
        button.addDomEventListener("click", e -> action.run());
        return button;
    }

    private Div hint(String text) {
        Div div = new Div(text);
        TextStyle.CAPTION.applyTo(div);
        return div;
    }

}
