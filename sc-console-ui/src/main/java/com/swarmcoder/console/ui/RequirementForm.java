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

import com.swarmcoder.console.api.BrdService;
import com.swarmcoder.console.api.BrdService_Stub;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AgreementGate;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.SourceRef;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Select;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one requirement editor: title, statement, priority, standing, kind, and the checks that prove
 * it — with the agreement gate, the impact warning and the retire confirmation attached to it.
 *
 * <p><b>Why it is its own class.</b> It lived inside {@code BrdView}, which is the diagram. That
 * made the diagram the only place a requirement could be written at all: the list an operator lands
 * on had no "new", no clickable row and no editor, so every edit began with finding the Graph tab
 * ({@code docs/DEVELOPER_CORRECTIONS.md} §25.2). Both surfaces now mount THIS, so the list can write
 * and there is still exactly one form — not a second one that will drift from the first.
 *
 * <p><b>What the owner supplies.</b> Which requirement is being edited ({@code selected}), how to
 * read the STORED version of it ({@code live} — the criteria are edited through their own service
 * calls and the form must redraw from the server's answer, not from what it sent), where to put a
 * line of feedback ({@code statusText}), and what to do when the server has been written to
 * ({@code onServerChanged}). The diagram's live copy comes off the graph signal; the list's comes
 * from re-fetching the one requirement.
 *
 * <p><b>Every dangerous act asks first, and says what it will cost</b> — in the sentence the server
 * itself computes ({@code RequirementImpact}), never in a second wording invented here.
 */
final class RequirementForm extends Div implements Disposable {

    private static final String KIND_FUNCTIONAL = "functional";
    private static final String KIND_NON_FUNCTIONAL = "non-functional";
    /**
     * What a leftover CONSTRAINT row reads as. It is NOT offered when creating anything: rules
     * about how the project is built are guidelines, not requirements (author decision 2026-08-31,
     * see {@code RequirementKind.CONSTRAINT}), and the Rules screen is where they are stated now.
     *
     * <p>Kept so that a requirement written during the few hours the third kind existed still says
     * what it is, and so that editing one does not silently turn it into a functional requirement.
     */
    private static final String KIND_CONSTRAINT = "a leftover rule — rules live in Guidelines now";

    /**
     * The words the Status picker offers.
     *
     * <p>Paired with {@link #parseStatus} and {@link #statusLabel}, which are the only two places
     * the mapping between them and {@link RequirementStatus} lives.
     */
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_AGREED = "agreed";
    private static final String STATUS_DELIVERED = "delivered";
    private static final String STATUS_RETIRED = "retired";

    private final BrdService service = new BrdService_Stub();

    private final ValueSignal<BrdRequirement> selected;
    private final Supplier<BrdRequirement> live;
    private final ValueSignal<String> statusText;
    private final Runnable onServerChanged;

    private final TextField titleField = new TextField();
    private final TextArea textField = new TextArea();
    private final Select prioritySelect = new Select();
    private final Select statusSelect = new Select();
    private final Select kindSelect = new Select();
    private final Select nfrSelect = new Select();
    private final ValueSignal<Boolean> nonFunctional = new ValueSignal<>(false);
    private final Div provenance = new Div();
    private final Div criteriaList = new Div();
    private final TextField newCriterionText = new TextField();
    private final TextField newCriterionTest = new TextField();
    private final Div status = new Div();
    private final List<Disposable> disposables = new ArrayList<>();

    /**
     * True once a fetch has been STARTED, so the render effect asks for the documents once rather
     * than launching a thread on every redraw.
     */
    private boolean sourcesRequested;
    /**
     * The documents, and whether fetching them failed, as signals — because the fetch cannot happen
     * inside an {@code Effect}: an RMI call from there sits on the signal-dispatch path, where TeaVM
     * cannot suspend, and it threw every time. The load happens on its own thread and publishes into
     * these; reading them in the render is also what redraws it when they arrive.
     */
    private final ValueSignal<List<SourceDocument>> sources = new ValueSignal<>(List.of());
    /**
     * Set while the last attempt to load the source documents failed. Kept apart from the list
     * because "there is no such document" and "the documents could not be read" are different
     * answers, and the provenance line must not give the first when the truth is the second.
     */
    private final ValueSignal<Boolean> sourcesFailed = new ValueSignal<>(false);

    /**
     * @param where which surface this copy is mounted on - {@code "graph"} beside the diagram,
     *              {@code "list"} in the dialog over the list. It becomes {@code data-form}, and it
     *              is not decoration: two copies of the same form are in the page at once, so every
     *              placeholder and every test id inside one of them now matches twice. A selector
     *              that does not say which form it means finds whichever comes first in the
     *              document, which is the hidden one.
     */
    RequirementForm(String where, ValueSignal<BrdRequirement> selected,
                    Supplier<BrdRequirement> live, ValueSignal<String> statusText,
                    Runnable onServerChanged) {
        this.selected = selected;
        this.live = live;
        this.statusText = statusText;
        this.onServerChanged = onServerChanged == null ? () -> { } : onServerChanged;
        addClassName("flex flex-col gap-2 p-3");
        getElement().setAttribute("data-testid", "requirement-form");
        getElement().setAttribute("data-form", where == null ? "" : where);
        build();
    }

    private void build() {
        titleField.addClassName("input input-bordered input-sm w-full font-medium");
        titleField.getElement().setAttribute("placeholder", "short title");
        textField.addClassName("textarea textarea-bordered w-full text-sm min-h-24");
        textField.getElement().setAttribute("placeholder", "full requirement statement");
        textField.getElement().setAttribute("data-testid", "brd-req-text");
        prioritySelect.addClassName("select select-bordered select-xs");
        prioritySelect.setItems(List.of(Priority.LOW.label(), Priority.MEDIUM.label(),
            Priority.HIGH.label(), Priority.CRITICAL.label()));
        statusSelect.addClassName("select select-bordered select-xs");
        statusSelect.getElement().setAttribute("data-testid", "brd-status");
        // The operator's words, not the enum's (UX v3 §4). A picker reading DRAFT / ACTIVE /
        // IMPLEMENTED / DEPRECATED asks somebody to have learned a state machine before they can
        // change a status.
        statusSelect.setItems(List.of(STATUS_DRAFT, STATUS_AGREED, STATUS_DELIVERED, STATUS_RETIRED));

        Div selects = new Div();
        selects.addClassName("flex gap-2");
        selects.add(prioritySelect.withLabel("Priority"), statusSelect.withLabel("Status"));

        // Kind is one field on one type (author decision 2026-07-25): a quality requirement is not a
        // different class, it is a requirement whose checks are fitness checks.
        kindSelect.addClassName("select select-bordered select-xs");
        kindSelect.setItems(List.of(KIND_FUNCTIONAL, KIND_NON_FUNCTIONAL));
        kindSelect.getElement().setAttribute("data-testid", "brd-kind");
        kindSelect.addDomEventListener("change",
            e -> nonFunctional.set(KIND_NON_FUNCTIONAL.equals(kindSelect.getValue())));
        nfrSelect.addClassName("select select-bordered select-xs");
        nfrSelect.setItems(nfrOptions());
        nfrSelect.withLabel("Quality");
        Div kinds = new Div();
        kinds.addClassName("flex gap-2");
        kinds.add(kindSelect.withLabel("Kind"), nfrSelect);

        Div fitnessHint = new Div();
        fitnessHint.addClassName("text-[11px] leading-snug text-warning/80");
        fitnessHint.setText("This is a quality requirement. Every check below must give a "
            + "measurable threshold — a number and a unit — or nothing can decide it.");

        disposables.add(Effect.create(() -> {
            boolean nf = Boolean.TRUE.equals(nonFunctional.get());
            nfrSelect.setVisible(nf);
            fitnessHint.setVisible(nf);
        }));

        provenance.addClassName("flex flex-col gap-1 text-[11px] leading-snug text-base-content/50");
        disposables.add(Effect.create(() -> {
            // Both signals read HERE, at the top, unconditionally. Reading them inside
            // renderProvenance only happens for a requirement that HAS a source, so on every other
            // render this effect would not be watching them, and the answer arriving from the fetch
            // would land on nobody.
            sources.get();
            sourcesFailed.get();
            renderProvenance();
        }));

        Button save = new Button("Save");
        save.addClassName("btn-primary btn-sm");
        save.getElement().setAttribute("data-testid", "brd-save");
        save.addClickListener(e -> saveSelected());
        // Cancel is always available. Dismissing used to be possible only through "Delete", which
        // for a requirement that has never been saved reads as destroying something — so the form
        // looked like a trap with no way out that did not sound dangerous.
        Button cancel = new Button("Cancel");
        cancel.addClassName("btn-ghost btn-sm");
        cancel.getElement().setAttribute("data-testid", "brd-cancel");
        cancel.addClickListener(e -> selected.set(null));
        // "Take out of scope", not "Delete". The button used to remove the requirement, its links
        // and its position, which severed the trail from the requirement to the commit that
        // satisfied it. It now retires it, and the label says what it does (§25.1).
        Button retire = new Button("Take out of scope");
        retire.addClassName("btn-ghost btn-sm text-error");
        retire.getElement().setAttribute("data-testid", "brd-retire");
        retire.addClickListener(e -> confirmRetire());
        disposables.add(Effect.create(() -> {
            BrdRequirement sel = selected.get();
            BrdRequirement stored = sel == null ? null : live.get();
            boolean retired = stored != null && stored.isRetired();
            // Only once there IS something stored, and not for something already out of scope —
            // the way back is the Status picker, which is where the operator changed it from.
            retire.setVisible(sel != null && sel.id() != null && !retired);
        }));
        // Promotion is the requirement's own act, not a status field to remember to change. It
        // carries the checks with it — agreeing a requirement IS agreeing what must be true of it,
        // and asking for that a second time per check is the same decision put twice.
        Button promote = new Button("Agree this requirement");
        promote.addClassName("btn-sm btn-outline");
        promote.getElement().setAttribute("data-testid", "brd-promote");
        promote.addClickListener(e -> promoteSelected());
        // Why it cannot be agreed yet, said next to the button rather than only after the click
        // (§20.2). The server refuses either way; this is so the operator is not sent to find out by
        // pressing something. One derivation: the sentence comes from AgreementGate, which is also
        // what the server enforces.
        Div blocked = new Div();
        blocked.addClassName("text-xs text-warning");
        blocked.getElement().setAttribute("data-testid", "brd-agree-blocked");
        disposables.add(Effect.create(() -> {
            BrdRequirement sel = liveSelected();
            boolean draft = sel != null && sel.id() != null
                && sel.status() == RequirementStatus.DRAFT;
            String refusal = draft ? AgreementGate.rejectionFor(sel) : null;
            // Agreeing is one of the six things autonomous running does for the operator, so while
            // that is on this is its lever and not theirs. Read INSIDE the effect on purpose: the
            // control comes back by itself the moment the switch goes off, with no reload and no
            // stale grey button left behind. See PilotGates.
            String held = refusal != null ? null : PilotGates.why();
            // Disabled, not hidden (UX v3 rule 1). A button that disappears looks like a feature
            // that is not there; one that is greyed out with the reason underneath it is a
            // condition the operator can go and satisfy.
            promote.setVisible(draft);
            promote.setEnabled(refusal == null && held == null);
            blocked.setVisible(draft && (refusal != null || held != null));
            blocked.setText(refusal != null ? refusal : held == null ? "" : held);
            promote.getElement().setAttribute("title", refusal != null ? refusal
                : held != null ? held
                : draft && sel.criteria() != null
                    ? "Make this the agreed scope and accept its " + sel.criteria().size()
                        + " checks"
                    : "Make this the agreed scope");
        }));
        Div actions = new Div();
        actions.addClassName("flex items-center gap-2 flex-wrap");
        actions.add(save, promote, cancel, retire);
        TextStyle.CAPTION.applyTo(status);
        disposables.add(Effect.create(() -> status.setText(nz(statusText.get()))));
        actions.getElement().appendChild(status.getElement());

        add(provenance);
        add(titleField.withLabel("Title"), textField.withLabel("Statement"));
        add(selects, kinds, fitnessHint, actions, blocked, criteriaPanel());

        // The fields say what the SELECTED requirement says. An effect rather than a call from the
        // owner's click handler, so a form mounted over the list and a form beside the diagram fill
        // themselves the same way from the same signal.
        disposables.add(Effect.create(() -> {
            BrdRequirement r = selected.get();
            setVisible(r != null);
            if (r != null) {
                populate(r);
            }
        }));
    }

    /** Fills every control from one requirement. */
    private void populate(BrdRequirement r) {
        titleField.setValue(nz(r.title()));
        textField.setValue(nz(r.text()));
        prioritySelect.setValue(r.priority() == null
            ? Priority.MEDIUM.label() : r.priority().label());
        statusSelect.setValue(statusLabel(r.status()));
        boolean nf = r.kind() == RequirementKind.NON_FUNCTIONAL;
        // A leftover rule is offered its own label, and ONLY then: nothing new may be created as
        // one, and a picker that still offered it would keep making them.
        kindSelect.setItems(r.kind() == RequirementKind.CONSTRAINT
            ? List.of(KIND_FUNCTIONAL, KIND_NON_FUNCTIONAL, KIND_CONSTRAINT)
            : List.of(KIND_FUNCTIONAL, KIND_NON_FUNCTIONAL));
        kindSelect.setValue(switch (r.kind()) {
            case NON_FUNCTIONAL -> KIND_NON_FUNCTIONAL;
            case CONSTRAINT -> KIND_CONSTRAINT;
            case FUNCTIONAL -> KIND_FUNCTIONAL;
        });
        nonFunctional.set(nf);
        nfrSelect.setValue(lower(r.nfrCategory() == null
            ? NfrCategory.PERFORMANCE.name() : r.nfrCategory().name()));
        newCriterionText.setValue("");
        newCriterionTest.setValue("");
        // Reading the documents runs from a signal effect, so it goes on its own thread. A failed
        // load retries from here, which is what makes "reselect the requirement to try again" true.
        if (!sourcesRequested || Boolean.TRUE.equals(sourcesFailed.get())) {
            sourcesRequested = true;
            requestSources();
        }
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    // ---- saving, agreeing, retiring -------------------------------------------------------------

    /**
     * Saves, asking first when the statement has changed under work somebody has already committed
     * to.
     *
     * <p>UX v3 §2.2: editing a requirement a story has claimed is permitted, but it is a deliberate
     * act that shows its impact and requires confirmation. It used to do neither — the passing check
     * correctly went stale, and the story card did not change at all (§25.3).
     */
    private void saveSelected() {
        BrdRequirement sel = selected.get();
        if (sel == null) {
            return;
        }
        BrdRequirement stored = liveSelected();
        boolean reworded = sel.id() != null && stored != null
            && !nz(textField.getValue()).equals(nz(stored.text()));
        if (!reworded) {
            doSave();
            return;
        }
        // Asked from a click handler, which is where an RMI call belongs.
        String impact = "";
        try {
            impact = nz(service.editImpact(sel.id().toString()));
        } catch (Exception e) {
            ClientLog.error("RequirementForm", "could not work out what editing "
                + sel.handle() + " would disturb; saving without the warning: " + e);
        }
        if (impact.isEmpty()) {
            doSave();
            return;
        }
        confirm("Change what " + nz(sel.handle()) + " says?", impact,
            "Change it anyway", "brd-edit-impact", this::doSave);
    }

    private void doSave() {
        BrdRequirement sel = selected.get();
        if (sel == null) {
            return;
        }
        BrdRequirement edited = new BrdRequirement(sel.id(), sel.handle(),
            titleField.getValue(), textField.getValue(),
            parsePriority(prioritySelect.getValue()), parseStatus(statusSelect.getValue()),
            // NULL, deliberately: the form does not edit the category, and the server preserves what
            // it holds when this is absent. Sending "" instead would erase the extractor's record of
            // which document heading a requirement came from.
            null);
        boolean nf = KIND_NON_FUNCTIONAL.equals(kindSelect.getValue());
        edited.setKind(nf ? RequirementKind.NON_FUNCTIONAL
            : KIND_CONSTRAINT.equals(kindSelect.getValue()) ? RequirementKind.CONSTRAINT
            : RequirementKind.FUNCTIONAL);
        edited.setNfrCategory(nf ? parseNfr(nfrSelect.getValue()) : null);
        // Carry the parts this form does not own — checks have their own service calls, and
        // provenance and the content revision belong to the extractor and the server.
        BrdRequirement stored = liveSelected();
        if (stored != null) {
            edited.setCriteria(stored.getCriteria());
            edited.setSourceRef(stored.sourceRef());
            edited.setContentRevision(stored.contentRevision());
        }
        String result;
        try {
            result = sel.id() == null && parentId != null && !parentId.isEmpty()
                ? service.addRequirement(edited, parentId)
                : service.saveRequirement(edited);
        } catch (Exception e) {
            statusText.set("could not save: " + e);
            ClientLog.error("RequirementForm", "saving a requirement failed: " + e);
            return;
        }
        if (result != null && result.startsWith("error")) {
            // Save is the one action the operator assumes worked; the form stays open with the
            // reason, and the log keeps it after the next action clears the status line.
            ClientLog.error("RequirementForm", "could not save requirement "
                + (edited.handle() == null ? "(new)" : edited.handle())
                + " — the edit was not stored: " + result);
            statusText.set(result);
        } else {
            statusText.set("saved");
            onServerChanged.run();
            selected.set(null);
        }
    }

    /**
     * The requirement this one will be made part of when it is first saved, or "".
     *
     * <p>Only meaningful for a requirement that has never been saved: "Add a part" on a row in the
     * list opens this form with the row's requirement here, and the server writes the requirement
     * and the link in one save.
     */
    private String parentId = "";

    void setParentId(String parentId) {
        this.parentId = parentId == null ? "" : parentId;
    }

    /**
     * Asks before taking a requirement out of scope, in the words that say what it actually does.
     *
     * <p>The sentence is the server's, so what the operator reads and what will happen are one
     * derivation. It always includes "nothing is deleted", because the control used to say Delete
     * and used to mean it.
     */
    private void confirmRetire() {
        BrdRequirement sel = selected.get();
        if (sel == null || sel.id() == null) {
            return;
        }
        String impact;
        try {
            impact = nz(service.retireImpact(sel.id().toString()));
        } catch (Exception e) {
            impact = "This takes " + nz(sel.handle()) + " out of the list and out of every count. "
                + "Nothing is deleted.";
        }
        final String id = sel.id().toString();
        confirm("Take " + nz(sel.handle()) + " out of scope?", impact,
            "Take it out of scope", "brd-retire-impact", () -> {
                String result;
                try {
                    result = service.retireRequirement(id);
                } catch (Exception e) {
                    statusText.set("could not take it out of scope: " + e);
                    return;
                }
                if (result != null && result.startsWith("error")) {
                    statusText.set(result);
                    ClientLog.error("RequirementForm", "retiring was refused: " + result);
                    return;
                }
                statusText.set("It is out of scope now, and still on the record. Set its status "
                    + "back to a draft to bring it back.");
                onServerChanged.run();
                selected.set(null);
            });
    }

    /**
     * Agrees the selected draft, checks and all.
     *
     * <p>The selection is left in place rather than cleared: the operator's next glance is at the
     * checks they just agreed, and closing the form would hide the result of their own click.
     */
    private void promoteSelected() {
        BrdRequirement sel = selected.get();
        if (sel == null || sel.id() == null) {
            return;
        }
        try {
            String result = service.promoteRequirement(sel.id().toString());
            if (result != null && !result.isEmpty()) {
                statusText.set(result);
                ClientLog.error("RequirementForm", "agreeing " + sel.handle() + " was refused, so "
                    + "it is still a draft and its checks are still proposals: " + result);
                return;
            }
            // The form is left open on purpose, so the field that says what this requirement IS has
            // to catch up. It did not: the Status picker still read "draft" after the requirement
            // had been agreed, so pressing Save — the most ordinary next thing anyone does in an
            // open form — wrote "draft" straight back and quietly undid the decision.
            statusSelect.setValue(STATUS_AGREED);
            onServerChanged.run();
            statusText.set(sel.handle() + " is now agreed — its checks are accepted, and "
                + "nothing counts as delivered until they pass.");
        } catch (Exception e) {
            statusText.set("could not agree " + sel.handle() + ": " + e);
            ClientLog.error("RequirementForm", "agreeing " + sel.handle() + " failed — it is still "
                + "a draft: " + e);
        }
    }

    private void confirm(String heading, String sentence, String confirmLabel, String testId,
                         Runnable action) {
        ConfirmDialog.ask(this, heading, sentence, confirmLabel, testId, action);
    }

    // ---- checks ---------------------------------------------------------------------------------

    /**
     * The requirement's executable definition. Checks are edited through their OWN service calls
     * (not carried on the Save button) because each one is an independent statement with its own
     * verification history.
     */
    private Div criteriaPanel() {
        Div panel = new Div();
        panel.addClassName("flex flex-col gap-2 pt-2 border-t border-base-300");
        Span heading = new Span("Checks");
        heading.addClassName("text-xs font-semibold uppercase tracking-wider text-base-content/50");
        panel.getElement().appendChild(heading.getElement());

        criteriaList.addClassName("flex flex-col gap-1.5");
        panel.add(criteriaList);

        newCriterionText.addClassName("input input-bordered input-xs w-full");
        newCriterionText.getElement().setAttribute("placeholder",
            "New check — one statement you can test");
        newCriterionTest.addClassName("input input-bordered input-xs flex-1");
        newCriterionTest.getElement().setAttribute("placeholder", "Test class or file");
        Button add = new Button("Add check");
        add.addClassName("btn-outline btn-xs");
        add.getElement().setAttribute("data-testid", "brd-add-criterion");
        add.addClickListener(e -> addCriterion());
        Div addRow = new Div();
        addRow.addClassName("flex items-center gap-1.5");
        addRow.add(newCriterionTest, add);
        panel.add(newCriterionText, addRow);

        disposables.add(Effect.create(this::renderCriteria));
        return panel;
    }

    private void renderCriteria() {
        criteriaList.getElement().setInnerHTML("");
        BrdRequirement sel = liveSelected();
        if (sel == null) {
            return;
        }
        if (sel.id() == null) {
            criteriaList.add(muted("Save this requirement first to give it checks."));
            return;
        }
        List<AcceptanceCriterion> criteria = sel.criteria();
        if (criteria.isEmpty()) {
            // Said ONCE. The agreement gate's line directly above already says this requirement has
            // no check and what to do about it, and a screenshot of the two together was two
            // warning-coloured paragraphs in a row saying nearly the same words - which reads as a
            // wall of orange rather than as one thing to go and fix. For a draft, the line above is
            // the one attached to the control the operator would press, so it wins.
            if (sel.status() != RequirementStatus.DRAFT) {
                Div warn = new Div();
                warn.addClassName("flex items-start gap-1.5 text-[11px] leading-snug text-warning");
                warn.add(Icon.of("warning", "w-3.5 h-3.5 shrink-0 mt-px"));
                Span t = new Span("No checks. Nothing can verify, deliver or track this requirement "
                    + "until it has one: it cannot be sliced into a story and can never count as "
                    + "delivered.");
                warn.getElement().appendChild(t.getElement());
                criteriaList.add(warn);
            }
            return;
        }
        for (int i = 0; i < criteria.size(); i++) {
            criteriaList.add(criterionRow(sel, criteria.get(i), i));
        }
    }

    private Div criterionRow(BrdRequirement req, AcceptanceCriterion criterion, int index) {
        String requirementId = req.id().toString();
        Div row = new Div();
        row.addClassName("flex flex-col gap-1 rounded-lg border border-base-300 px-2 py-1.5");
        // Which of the two kinds of test name this row carries. Marked on the ROW, not on the note,
        // so "a confirmed name shows nothing" is something a test can point at.
        row.getElement().setAttribute("data-check-ref",
            criterion.testRefIsProposal() ? "suggested" : "confirmed");
        boolean retired = criterion.status() == CriterionStatus.RETIRED;
        if (retired) {
            // Kept on screen and clearly out of the gate. It is history the operator can read and
            // can put back, not something that has gone.
            row.addClassName("opacity-50");
            row.getElement().setAttribute("data-check-retired", "true");
        }

        Div top = new Div();
        top.addClassName("flex items-center gap-1.5");
        Span ref = new Span(criterionRef(req, index));
        ref.addClassName("text-[10px] font-mono text-base-content/40 shrink-0");
        top.getElement().appendChild(ref.getElement());
        top.getElement().appendChild(stateBadge(criterion, req.contentRevision()).getElement());
        String commit = criterion.lastVerifiedCommit();
        if (commit != null && !commit.isBlank()) {
            Span sha = new Span(shortSha(commit));
            sha.addClassName("text-[10px] font-mono text-success/70");
            sha.getElement().setAttribute("title", "Last verified at commit " + commit);
            top.getElement().appendChild(sha.getElement());
        }
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        top.add(spacer);

        // The OPERATOR may agree a check — that is exactly the act of deciding it now gates delivery.
        Select agreed = new Select();
        agreed.addClassName("select select-bordered select-xs");
        // The words, not the constants. This picker offered PROPOSED / ACCEPTED / RETIRED, which
        // asks the operator to have read the enum before they can change a check's standing.
        agreed.setItems(List.of(CriterionStatus.PROPOSED.label(), CriterionStatus.ACCEPTED.label(),
            CriterionStatus.RETIRED.label()));
        agreed.setValue(criterion.status().label());
        agreed.getElement().setAttribute("title", "Agreeing a check makes it a gate: this "
            + "requirement only counts as delivered while every agreed check passes.");
        top.add(agreed);

        if (!retired) {
            Div out = new Div();
            out.addClassName("cursor-pointer text-base-content/40 hover:text-error");
            out.add(Icon.of("x", "w-3 h-3"));
            out.getElement().setAttribute("data-testid", "brd-retire-criterion");
            // It says what it does. It used to say "Delete this check" and it used to delete it,
            // which left any story claiming it pointing at nothing (§25.1).
            out.getElement().setAttribute("title",
                "Take this check out of the gate. It stays on the record.");
            out.addDomEventListener("click",
                e -> retireCriterion(requirementId, idStr(criterion.id())));
            top.add(out);
        }
        row.add(top);

        TextField text = new TextField();
        text.addClassName("input input-bordered input-xs w-full");
        text.getElement().setAttribute("placeholder", "Checkable statement");
        text.setValue(nz(criterion.text()));
        TextField test = new TextField();
        test.addClassName("input input-bordered input-xs w-full font-mono");
        test.getElement().setAttribute("placeholder", "Test class or file");
        test.setValue(nz(criterion.testClassOrFile()));
        row.add(text, test);
        Div testNote = testRefNote(criterion);
        if (testNote != null) {
            row.add(testNote);
        }

        text.addDomEventListener("change",
            e -> saveCriterion(requirementId, criterion, text, test, agreed));
        test.addDomEventListener("change",
            e -> saveCriterion(requirementId, criterion, text, test, agreed));
        agreed.addDomEventListener("change",
            e -> saveCriterion(requirementId, criterion, text, test, agreed));
        return row;
    }

    /**
     * Takes a check out of the gate and says what that did to any story delivering it.
     *
     * <p>The server's answer here is not an error and is not blank: it names the story and how much
     * of it is left. Retiring a check that was the last thing a story had to deliver is the case
     * that used to leave a story pointing at nothing at all, silently.
     */
    private void retireCriterion(String requirementId, String criterionId) {
        String result;
        try {
            result = service.retireCriterion(requirementId, criterionId);
        } catch (Exception e) {
            statusText.set("could not retire that check: " + e);
            return;
        }
        if (result != null && result.startsWith("error")) {
            ClientLog.error("RequirementForm", "retiring a check was refused: " + result);
            statusText.set(result);
            return;
        }
        onServerChanged.run();
        statusText.set(nz(result));
    }

    /**
     * The line under the test box saying where that name came from, or null when there is nothing
     * to say.
     *
     * <p>A name the requirements wizard suggested is a guess about a test that does not exist yet; a
     * name a person typed is a decision. The operator has to be able to see at a glance which ones
     * still want a look.
     */
    private Div testRefNote(AcceptanceCriterion criterion) {
        if (!criterion.testRefIsProposal()) {
            return null;
        }
        Div note = new Div();
        note.addClassName("flex items-start gap-1.5 text-[11px] leading-snug text-warning/90");
        note.getElement().setAttribute("data-testid", "check-ref-suggested");
        note.add(Icon.of("warning", "w-3 h-3 shrink-0 mt-px"));
        Span t = new Span("Suggested by the analyst — no test with this name exists yet, and "
            + "nobody has confirmed it. Change it now if it is wrong: the build must use this "
            + "exact name.");
        note.getElement().appendChild(t.getElement());
        return note;
    }

    private void saveCriterion(String requirementId, AcceptanceCriterion original,
                               TextField text, TextField test, Select agreed) {
        // Carry the verification cache across: the operator edits the statement, the test binding
        // and the agreement — never the evidence, which only a run may write.
        AcceptanceCriterion edited =
            new AcceptanceCriterion(original.id(), text.getValue(), test.getValue());
        edited.setStatus(parseCriterionStatus(agreed.getValue()));
        edited.setVerification(original.getVerification());
        edited.setLastVerifiedRunId(original.lastVerifiedRunId());
        edited.setLastVerifiedCommit(original.lastVerifiedCommit());
        edited.setLastVerifiedAt(original.lastVerifiedAt());
        edited.setVerifiedAgainstContentRevision(original.verifiedAgainstContentRevision());
        report(service.saveCriterion(requirementId, edited));
        onServerChanged.run();
    }

    private void addCriterion() {
        BrdRequirement sel = liveSelected();
        if (sel == null || sel.id() == null) {
            statusText.set("save this requirement before adding checks");
            return;
        }
        String text = nz(newCriterionText.getValue()).trim();
        if (text.isEmpty()) {
            statusText.set("a check needs a statement");
            return;
        }
        String result = service.saveCriterion(sel.id().toString(),
            new AcceptanceCriterion(null, text, nz(newCriterionTest.getValue()).trim()));
        if (result != null && result.startsWith("error")) {
            // Kept out of report(): the typed text must survive on screen for a retry, which means
            // this branch returns early rather than clearing the fields.
            ClientLog.error("RequirementForm", "could not add a check to "
                + sel.handle() + " — it was not stored: " + result);
            statusText.set(result);
            return;
        }
        newCriterionText.setValue("");
        newCriterionTest.setValue("");
        statusText.set("");
        onServerChanged.run();
    }

    // ---- provenance -----------------------------------------------------------------------------

    /**
     * Where an extracted requirement came from. Vision-extracted documents are called out because
     * their text is a model's reading of an image, not the document — it can be wrong or invented.
     */
    private void renderProvenance() {
        provenance.getElement().setInnerHTML("");
        BrdRequirement sel = liveSelected();
        SourceRef ref = sel == null ? null : sel.sourceRef();
        provenance.setVisible(ref != null);
        if (ref == null) {
            return;
        }
        SourceDocument doc = sourceById(ref.documentId());
        Div line = new Div();
        line.addClassName("flex items-center gap-1.5");
        line.add(Icon.of("file", "w-3 h-3 shrink-0"));
        String locator = ref.locator();
        Span name = new Span("Extracted from "
            + (doc == null || doc.filename() == null ? "an uploaded document" : doc.filename())
            + (locator == null || locator.isBlank() ? "" : " · " + locator));
        name.addClassName("truncate");
        line.getElement().appendChild(name.getElement());
        provenance.add(line);

        if (Boolean.TRUE.equals(sourcesFailed.get())) {
            // Without this the operator reads "an uploaded document" and takes it for a document
            // whose name we simply do not have, when in fact the list could not be fetched at all.
            Div failed = new Div();
            failed.addClassName("flex items-start gap-1.5 text-error");
            failed.add(Icon.of("warning", "w-3 h-3 shrink-0 mt-px"));
            Span t = new Span("The source documents could not be loaded, so this requirement's "
                + "origin cannot be named — it is unknown, not absent. Reselect the requirement "
                + "to try again.");
            failed.getElement().appendChild(t.getElement());
            provenance.add(failed);
        }

        String extractor = doc == null ? null : doc.extractedBy();
        if (extractor != null && extractor.startsWith("vision")) {
            Div warn = new Div();
            warn.addClassName("flex items-start gap-1.5 text-warning");
            warn.add(Icon.of("warning", "w-3 h-3 shrink-0 mt-px"));
            Span t = new Span("Read from an image by a vision model — a model's reading of the "
                + "page, not the document itself. It may be wrong or invented; check the original.");
            warn.getElement().appendChild(t.getElement());
            provenance.add(warn);
        }
    }

    private SourceDocument sourceById(UUID documentId) {
        if (documentId == null) {
            return null;
        }
        for (SourceDocument doc : safe(sources.get())) {
            if (documentId.equals(doc.id())) {
                return doc;
            }
        }
        return null;
    }

    /** Fetches the uploaded documents off the signal-dispatch path and publishes the answer. */
    private void requestSources() {
        new Thread(() -> {
            try {
                List<SourceDocument> fetched = service.sources();
                sourcesFailed.set(false);
                sources.set(fetched == null ? List.of() : fetched);
            } catch (Exception e) {
                // The latch stays set here ON PURPOSE. Clearing it would have the failure signal
                // re-run the render, which would start another fetch, which would fail — a retry
                // loop against a server that is down.
                ClientLog.error("RequirementForm", "could not load source documents — a requirement "
                    + "that came from one cannot name it: " + e);
                sourcesFailed.set(true);
            }
        }).start();
    }

    // ---- small helpers --------------------------------------------------------------------------

    /**
     * The selection as it stands in the STORE. The selection signal holds the instance that was
     * clicked; checks are mutated server-side, so reading through the owner's live view is what
     * makes the checks list redraw itself after every check edit.
     */
    private BrdRequirement liveSelected() {
        BrdRequirement sel = selected.get();
        if (sel == null) {
            return null;
        }
        BrdRequirement stored = live.get();
        return stored == null ? sel : stored;
    }

    /** The signal redraws this view; this only surfaces an error the operator must act on. */
    private void report(String result) {
        if (result != null && result.startsWith("error")) {
            ClientLog.error("RequirementForm", "a BRD edit was refused by the server: " + result);
        }
        statusText.set(result != null && result.startsWith("error") ? result : "");
    }

    private Span muted(String text) {
        Span s = new Span(text);
        TextStyle.CAPTION.applyTo(s);
        return s;
    }

    private static String criterionRef(BrdRequirement r, int index) {
        return (r.handle() == null || r.handle().isEmpty() ? "R?" : r.handle()) + ":C" + (index + 1);
    }

    private static Span stateBadge(AcceptanceCriterion criterion, long contentRevision) {
        CriterionState state = criterion.effectiveState(contentRevision);
        Span badge = new Span(lower(state.name()));
        badge.addClassName("badge badge-xs " + stateBadgeClass(state));
        if (state == CriterionState.STALE) {
            badge.getElement().setAttribute("title", "It passed, but against an OLDER wording of "
                + "this requirement — the evidence no longer speaks to what the requirement now "
                + "says. Re-verify before trusting it.");
        } else if (state == CriterionState.UNVERIFIED) {
            badge.getElement().setAttribute("title", "Never checked against the code.");
        }
        return badge;
    }

    private static String stateBadgeClass(CriterionState state) {
        return switch (state) {
            case PASSING -> "badge-success";
            case FAILING -> "badge-error";
            case STALE -> "badge-warning";
            case UNVERIFIED -> "badge-ghost text-base-content/40";
        };
    }

    private static List<String> nfrOptions() {
        List<String> options = new ArrayList<>();
        for (NfrCategory category : NfrCategory.values()) {
            options.add(lower(category.name()));
        }
        return options;
    }

    private static NfrCategory parseNfr(String s) {
        if (s != null) {
            for (NfrCategory category : NfrCategory.values()) {
                if (lower(category.name()).equals(s.trim())) {
                    return category;
                }
            }
        }
        return NfrCategory.PERFORMANCE;
    }

    private static CriterionStatus parseCriterionStatus(String s) {
        if (s == null || s.isBlank()) {
            return CriterionStatus.PROPOSED;
        }
        try {
            return CriterionStatus.of(s.trim());
        } catch (IllegalArgumentException e) {
            return CriterionStatus.PROPOSED;
        }
    }

    private static Priority parsePriority(String s) {
        if (s == null || s.isBlank()) {
            return Priority.MEDIUM;
        }
        try {
            return Priority.of(s.trim());
        } catch (IllegalArgumentException e) {
            return Priority.MEDIUM;
        }
    }

    /** The operator's word for a status. Unknown or unset reads as a draft, the safe end. */
    private static String statusLabel(RequirementStatus status) {
        return status == null ? STATUS_DRAFT : status.label();
    }

    /**
     * The status behind a word. Falls back to DRAFT rather than throwing: this reads a select whose
     * options it also wrote, so a miss means the two lists have drifted apart, and the least harmful
     * reading of an unrecognised status is the one that claims nothing has been agreed.
     */
    private static RequirementStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return RequirementStatus.DRAFT;
        }
        String word = s.trim();
        if (STATUS_AGREED.equals(word)) {
            return RequirementStatus.ACTIVE;
        }
        if (STATUS_DELIVERED.equals(word)) {
            return RequirementStatus.IMPLEMENTED;
        }
        if (STATUS_RETIRED.equals(word)) {
            return RequirementStatus.DEPRECATED;
        }
        if (STATUS_DRAFT.equals(word)) {
            return RequirementStatus.DRAFT;
        }
        // An enum name, from an older client's stored form value or a test driving the raw select.
        try {
            return RequirementStatus.valueOf(word);
        } catch (IllegalArgumentException e) {
            return RequirementStatus.DRAFT;
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String idStr(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
