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
import com.swarmcoder.console.api.GuidedFlowService;
import com.swarmcoder.console.api.GuidedFlowService_Stub;
import com.swarmcoder.console.api.GuidedFlowSignals;
import com.swarmcoder.domain.FlowDiscussionTurn;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Checkbox;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.RadioButtonGroup;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import org.teavm.jso.browser.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * The requirements-intake wizard (docs/GUIDED_FLOWS_DESIGN.md) — the guided replacement for the
 * now-removed {@code /brd} chat mode. One dialog walks the whole opinionated process:
 *
 * <pre>documents ─▶ [Analyse] ─▶ progress ─▶ questions ─▶ review ─▶ apply</pre>
 *
 * <p>It is a <em>view of a server-owned flow</em>, not the owner of one. Everything it shows comes
 * from {@link GuidedFlowSignals#CURRENT}, so closing it does not cancel the analysis and re-opening
 * it lands the operator exactly where the work has got to — including in a second browser tab.
 *
 * <p>The body is rebuilt only when {@link #key} changes rather than on every publish. A rebuild
 * replaces the notes and answer fields, and doing that while the operator is filling them in would
 * throw away what they typed.
 *
 * <h2>Mounting it</h2>
 *
 * <pre>{@code
 * private final IntakeWizard wizard = new IntakeWizard();
 * ...
 * add(wizard);          // last, so the modals are not flex items in the middle of a column
 * wizard.onApplied(statusText::set);
 * ...
 * wizard.dispose();     // the effects are bound to a process-wide signal
 * }</pre>
 *
 * <p>It is a {@link Div} holding two {@link Dialog}s rather than being one, because the second
 * dialog is opened <em>over</em> the first. A daisyUI {@code .modal-box} carries a
 * {@code transform}, and a transformed element becomes the containing block for its
 * {@code position: fixed} descendants — a dialog nested inside the wizard's own modal box would be
 * laid out and clipped inside that box instead of over the viewport. The wrapper is
 * {@code display: contents} so it is not a box of its own either: mounting it is exactly as
 * weightless as mounting the two dialogs was.
 */
final class IntakeWizard extends Div implements Disposable {

    private final GuidedFlowService service = new GuidedFlowService_Stub();
    /**
     * What the wizard renders. Seeded from {@code intake()}'s RETURN VALUE and thereafter kept up to
     * date by the shared signal.
     *
     * <p>Binding straight to {@link GuidedFlowSignals#CURRENT} meant the first frame depended on a
     * server broadcast arriving; until it did, the dialog rendered the empty "no flow" state and the
     * operator saw a wizard with no documents and no way to add any. The direct call already carries
     * the answer, so it is used, and the signal keeps it live from then on.
     */
    private final ValueSignal<FlowView> flow = new ValueSignal<>(FlowView.none());

    private final Dialog dialog = new Dialog();
    /** The per-question background, opened from the info icon. Reused, contents replaced. */
    private final Dialog detail = new Dialog();
    private final Div progressBar = new Div();
    private final Div stepLine = new Div();
    private final Div errorLine = new Div();
    private final Div body = new Div();
    private final Div footer = new Div();
    /**
     * The project's uploaded documents that are not in the flow.
     *
     * <p>Held in a signal and refreshed only from DOM handlers. Fetching it during a render would
     * put an RMI call inside a signal effect, which throws "suspension point reached from
     * non-threading context" — the framework only permits calls from the green-threaded handlers.
     */
    private final ValueSignal<List<SourceDocument>> pool = new ValueSignal<>(new ArrayList<>());
    /** Client-side status for things the server does not know about yet (an upload in flight). */
    private final ValueSignal<String> notice = new ValueSignal<>("");
    private final Div noticeLine = new Div();

    private final List<Disposable> disposables = new ArrayList<>();
    private String rendered = "";
    /** Told what happened after Apply closes the dialog, so the outcome is not lost with it. */
    private java.util.function.Consumer<String> onApplied;

    IntakeWizard() {
        this("intake-wizard");
    }

    /**
     * The same wizard under a name of its own.
     *
     * <p>There can be more than one of these on the page: the requirements workspace mounts one, and
     * the getting-started guide composes another so that a first-time operator never has to leave
     * the walk to answer the analyst's questions. Two copies of the same dialog carry the same
     * words, so a browser test selecting on prose picks whichever is earlier in the document — which
     * is the CLOSED one, and a closed dialog is never visible. That failure reads as "the button
     * does nothing" and is nothing of the kind.
     */
    IntakeWizard(String testId) {
        // The wrapper must not become a flex item wherever it is mounted: the two modals are
        // position:fixed and were previously added straight to the host's column.
        addClassName("contents");
        dialog.setWidth("48rem");
        // Scoped for the browser tests: the wizard and the per-question window it can open
        // over itself are both dialogs, and both carry the same words.
        dialog.getElement().setAttribute("data-testid", testId);
        detail.getElement().setAttribute("data-testid", testId + "-detail");
        detail.setWidth("48rem");
        // The discussion window polls the server for the model's reply, and the Close button used
        // to be what stopped the poll. Escape and a click outside close a dialog now and never
        // reach that button, so the stop hangs off the close itself — any close, by any route.
        detail.addCloseListener(e -> discussionGeneration++);
        dialog.add(header(), progressPane(), body, noticeLine, footer);
        add(dialog, detail);

        // The shared signal is the live feed; the direct call in open() supplies the first frame.
        disposables.add(Effect.create(() -> {
            FlowView published = GuidedFlowSignals.CURRENT.get();
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
     * Opens the wizard. The server call both creates the flow on first use and publishes it, so the
     * dialog is populated by the signal rather than by this return value.
     */
    void open() {
        notice.set("");
        try {
            FlowView view = service.intake();
            if (view != null) {
                flow.set(view);
            }
        } catch (Exception e) {
            notice.set("could not load the analysis: " + e);
        }
        refreshPool();
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
        Div title = new Div("Analyse requirement documents");
        title.addClassName("text-lg font-bold");
        Div subtitle = new Div("Upload what the client gave you, tell the analyst what each "
            + "document is for, and it drafts requirements for you to review.");
        subtitle.addClassName("text-xs text-base-content/60 mt-0.5");
        titles.add(title, subtitle);
        // Always available, in every state. A wizard that can reach a state its own UI offers no
        // way out of is a trap, and "start again" is the first thing anyone reaches for.
        Button over = new Button("Clear documents", e -> {
            GuidedFlow f = flow.get() == null ? null : flow.get().flow();
            if (f == null) {
                notice.set("there is no analysis to clear yet");
                return;
            }
            int had = flow.get().documents().size();
            call(() -> service.startOver(f.id().toString()));
            // Say what happened either way. Clearing an already-empty analysis changes nothing on
            // screen, and silence there reads as a broken button rather than as "nothing to do".
            if (notice.get().isEmpty()) {
                notice.set(had == 0
                    ? "Nothing to clear — this analysis had no documents. Uploads stay in the "
                        + "project below; use Delete to remove one for good."
                    : "Cleared " + had + " document(s) from the analysis. They are still in the "
                        + "project's uploads below.");
            }
        });
        over.addClassName("btn-xs btn-ghost");
        over.getElement().setAttribute("title",
            "Empty this analysis's document list and its unapplied proposals. The uploads stay in "
            + "the project, and nothing already in the BRD is touched.");
        bar.add(titles, over, iconButton("x", "Close — the analysis keeps running", dialog::close));
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
        stepLine.setText(step > 0 ? "Step " + step + " of " + total + " — " + label : label);
        String error = f == null ? null : f.error();
        errorLine.setText(error == null ? "" : error);
    }

    /**
     * A fingerprint of everything the body's STRUCTURE depends on. Notes typed into a field and
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
        return f.id() + "|" + f.state() + "|" + view.documents().size() + "|"
            + view.questions().size() + "|" + view.proposals().size() + "|" + f.error()
            + "|" + pool.get().size() + "|" + documentsKey(view.flow()) + "|"
            + view.totalCharacters() + "|" + view.characterBudget()
            + "|" + PilotGates.handedOver();
    }

    /**
     * Which documents are ticked, both ways.
     *
     * <p>Included changes the footer and the budget line. Technical changes the line under the list
     * that says nothing has been marked as saying how the system must be built — and it is read by
     * the guide's document step too, which shows the same tick and the same line.
     */
    static String documentsKey(GuidedFlow f) {
        StringBuilder sb = new StringBuilder();
        if (f != null) {
            for (FlowDocument entry : f.documents()) {
                sb.append(entry.included() ? "in" : "out")
                    .append(entry.technical() ? "+built" : "");
            }
        }
        return sb.toString();
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
            body.add(hint("Select a project first — requirements belong to one."));
            return;
        }
        switch (f.state()) {
            case AWAITING_ANSWERS -> renderQuestions(view);
            case REVIEW -> renderReview(view);
            case RUNNING -> renderRunning(f);
            case APPLIED -> renderApplied(f, view);
            default -> renderDocuments(view, f); // DRAFT and FAILED both collect documents
        }
    }

    // --- step 1: documents ----------------------------------------------------------------------

    private void renderDocuments(FlowView view, GuidedFlow f) {
        String flowId = f.id().toString();
        // A second run is a MERGE, and nothing on screen used to say so. An operator who thinks
        // re-analysing wipes the BRD will not press the button; one who thinks it appends blindly
        // will press it and then distrust the result.
        body.add(hint("Analysing compares the documents against the requirements you already have "
            + "and proposes changes — new ones to add, existing ones to amend or retire. Nothing "
            + "is written, deleted or overwritten until you review it and press Apply."));
        Div list = new Div();
        list.addClassName("flex flex-col gap-2 max-h-80 overflow-y-auto mt-2");
        if (view.documents().isEmpty()) {
            list.add(hint("No documents yet. Add the specs, briefs and notes the client gave you — "
                + "PDF, Word, Markdown, plain text, or a photo of a whiteboard."));
        }
        for (SourceDocument document : view.documents()) {
            list.add(documentRow(flowId, document, entryFor(f, document)));
        }
        body.add(list);

        // Said once, quietly, and it stops nothing. See DocumentKind.NONE_TICKED.
        if (!view.documents().isEmpty() && noneTechnical(f)) {
            Div line = TextStyle.CAPTION.paragraph(DocumentKind.NONE_TICKED);
            line.addClassName("mt-2");
            line.getElement().setAttribute("data-testid", "intake-no-technical-document");
            body.add(line);
        }

        // The upload box lives here, in the step whose whole job is collecting documents, rather
        // than behind a button that opened the browser's file picker. It takes several files at
        // once, shows a progress bar and a cancel button for each, and prints the server's own
        // sentence beside the file it is about.
        body.add(DocumentUpload.box().addUploadListener((name, accepted, message) -> {
            notice.set(name + " — " + message);
            if (accepted) {
                // The server attaches an accepted document to this project's intake flow and
                // publishes the flow, so the list above refreshes itself. The pool below it is
                // fetched by hand and has to be asked again.
                refreshPool();
            }
        }));

        // Uploads outlive an analysis. Without this, a document ingested before the flow existed is
        // stored, invisible, and can only be got back by finding the original file again.
        List<SourceDocument> available = pool.get();
        if (!available.isEmpty()) {
            Div heading = new Div("Already uploaded to this project");
            heading.addClassName("text-[11px] uppercase tracking-wide text-base-content/40 mt-4");
            body.add(heading);
            Div pool = new Div();
            pool.addClassName("flex flex-col gap-1 max-h-40 overflow-y-auto mt-1");
            for (SourceDocument document : available) {
                String documentId = document.id().toString();
                Div line = new Div();
                line.addClassName("flex items-center gap-2 text-xs px-2 py-1 rounded "
                    + "border border-base-300/60");
                Div label = new Div();
                label.addClassName("flex-1 min-w-0");
                Div name = new Div(document.filename() == null ? "(unnamed)" : document.filename());
                name.addClassName("truncate");
                // Two uploads of the same name are common and the name alone cannot tell them
                // apart — the size and the extractor can.
                Div meta = new Div(document.byteSize() + " bytes · "
                    + (document.extractedBy() == null ? "unknown" : document.extractedBy()));
                meta.addClassName("text-[10px] text-base-content/40");
                label.add(name, meta);
                Button look = new Button("View", e -> showDocument(document));
                look.addClassName("btn-xs btn-ghost");
                Button pick = new Button("Add",
                    e -> call(() -> service.addDocument(flowId, documentId, null)));
                pick.addClassName("btn-xs btn-ghost");
                Button drop = new Button("Delete",
                    e -> call(() -> service.deleteDocument(documentId)));
                drop.addClassName("btn-xs btn-ghost text-error");
                drop.getElement().setAttribute("title",
                    "Remove it from the project for good. The file on disk is not touched.");
                line.add(label, look, pick, drop);
                pool.add(line);
            }
            body.add(pool);
        }

        body.add(budgetRow(view, flowId));

        Button paste = new Button("Paste text", e -> showPaste(flowId));
        paste.addClassName("btn-sm btn-ghost");
        footer.add(paste);

        int reading = 0;
        for (FlowDocument entry : f.documents()) {
            if (entry.included()) {
                reading++;
            }
        }
        Button analyse = new Button("Analyse", e -> call(() -> service.start(flowId)));
        analyse.setText((view.hasRequirements() ? "Re-analyse " : "Analyse ")
            + reading + (reading == 1 ? " document" : " documents"));
        analyse.addClassName("btn-primary");
        if (reading == 0 || view.overBudget()) {
            analyse.getElement().setAttribute("disabled", "disabled");
        }
        footer.add(analyse);
    }

    /**
     * How much text will be sent, and the ceiling it must fit under.
     *
     * <p>Both numbers are always on screen, not only when something goes wrong. The limit exists
     * because the model has a context window, and the operator is the only one who knows which
     * model this Console is pointed at — so they get the figures and the control, rather than
     * discovering after the fact that part of their input was quietly ignored.
     */
    private Div budgetRow(FlowView view, String flowId) {
        Div row = new Div();
        row.addClassName("flex items-center gap-2 mt-3 text-xs");
        boolean over = view.overBudget();

        Div summary = new Div(view.totalCharacters() + " characters to read · limit "
            + view.characterBudget());
        summary.addClassName("flex-1 " + (over ? "text-error" : "text-base-content/50"));
        row.add(summary);

        TextField limit = new TextField("limit");
        limit.addClassName("input-xs w-28");
        limit.setValue(String.valueOf(view.characterBudget()));
        limit.getElement().setAttribute("title",
            "Characters of document text this analysis may send. Raise it if your model's context "
            + "can take more; blank or 0 restores the default.");
        limit.addValueChangeListener(e -> call(() ->
            service.setCharacterBudget(flowId, parseInt(limit.getValue()))));
        row.add(limit);

        if (over) {
            // One click for the obvious intent, rather than making them work out the number.
            int needed = view.totalCharacters() + view.totalCharacters() / 10;
            Button raise = new Button("Raise to fit",
                e -> call(() -> service.setCharacterBudget(flowId, needed)));
            raise.addClassName("btn-xs btn-warning");
            row.add(raise);
        }
        return row;
    }

    private static int parseInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;   // treated as "use the default", which is what a blank box means
        }
    }

    private Div documentRow(String flowId, SourceDocument document, FlowDocument entry) {
        String notes = entry.notes();
        String docId = document.id().toString();
        Div row = new Div();
        row.addClassName("flex items-start gap-3 p-2 rounded border border-base-300");
        // The tick decides what the NEXT run reads. Analysed documents arrive unticked, so adding
        // one file to a project that already has five costs one file's worth of reading.
        Checkbox include = new Checkbox();
        include.addClassName("checkbox-sm mt-1");
        include.setValue(entry.included());
        include.getElement().setAttribute("title", "Read this document in the next analysis");
        include.addValueChangeListener(e -> call(() -> service.setDocumentIncluded(flowId, docId,
            Boolean.TRUE.equals(include.getValue()))));
        Icon icon = Icon.of("file", "w-4 h-4");
        icon.addClassName("mt-1 opacity-50");
        Div column = new Div();
        column.addClassName("flex-1 min-w-0");
        Div nameRow = new Div();
        nameRow.addClassName("flex items-center gap-2");
        Div name = new Div(document.filename() == null ? "(unnamed)" : document.filename());
        name.addClassName("text-sm font-medium truncate");
        nameRow.add(name, stateBadge(entry));
        column.add(nameRow, provenance(document));

        TextField notesField = new TextField(
            "What is this document for? e.g. \"authoritative for pricing\", \"ignore section 4\"");
        notesField.addClassName("input-sm w-full mt-1.5");
        if (notes != null) {
            notesField.setValue(notes);
        }
        String documentId = document.id().toString();
        // TextField publishes its model on every "input" event, so this fires per keystroke. That is
        // a round trip per character, which is more than the note is worth — but it is what the
        // component does, and hiding from it behind the DOM is what got these fields into trouble
        // before. Debouncing belongs in the component, not in a private copy of it here.
        notesField.addValueChangeListener(e -> {
            String typed = notesField.getValue();
            call(() -> service.setNotes(flowId, documentId, typed == null ? "" : typed));
        });
        column.add(notesField);

        // What KIND of document this is. One tick, set by the person who has the file open, and it
        // is the only thing telling the analyst whether it is reading about what the system does or
        // about what it must be made of. The wording is DocumentKind's and not this screen's: the
        // guide asks the same question on its own document step, and two wordings would be two
        // questions.
        Div kindRow = DocumentKind.tick(entry.technical(), "intake-doc-technical",
            value -> call(() -> service.setDocumentTechnical(flowId, documentId, value)));
        kindRow.addClassName("mt-1.5");
        column.add(kindRow);

        Div actions = new Div();
        actions.addClassName("flex flex-col items-end gap-1 shrink-0");
        Button view = new Button("View", e -> showDocument(document));
        view.addClassName("btn-xs btn-ghost");
        Button remove = new Button("Remove",
            e -> call(() -> service.removeDocument(flowId, documentId)));
        remove.addClassName("btn-xs btn-ghost");
        remove.getElement().setAttribute("title",
            "Take it out of this analysis. It stays in the project's uploads.");
        actions.add(view, remove);
        row.add(include, icon, column, actions);
        return row;
    }

    /**
     * Whether this document has already become requirements.
     *
     * <p>Without it the list is a pile of filenames with no way to tell what the next run will
     * actually read — which is how re-analysing everything becomes the accidental default.
     */
    private Span stateBadge(FlowDocument entry) {
        Span badge = new Span(entry.analysed() ? "analysed" : "new");
        badge.addClassName("badge badge-xs shrink-0 "
            + (entry.analysed() ? "badge-ghost" : "badge-success"));
        badge.getElement().setAttribute("title", entry.analysed()
            ? "Already turned into requirements. Tick it to read it again — do that when its "
                + "meaning has changed, not to repeat work already done."
            : "Not yet read by any analysis.");
        return badge;
    }

    /**
     * How the text was obtained. Vision output is a model's READING of an image and may be wrong or
     * invented, so it is called out rather than presented as equivalent to extracted text.
     */
    private Div provenance(SourceDocument document) {
        String by = document.extractedBy() == null ? "unknown" : document.extractedBy();
        Div line = new Div();
        line.addClassName("text-[11px] mt-0.5");
        Span source = new Span(document.byteSize() + " bytes · read by " + by);
        source.addClassName("text-base-content/50");
        line.add(source);
        if (by.startsWith("vision")) {
            Span warn = new Span("  model's reading of an image — verify anything drawn from it");
            warn.addClassName("text-warning");
            line.add(warn);
        }
        return line;
    }

    private static FlowDocument entryFor(GuidedFlow f, SourceDocument document) {
        for (FlowDocument entry : f.documents()) {
            if (entry.documentId().equals(document.id())) {
                return entry;
            }
        }
        return new FlowDocument(document.id(), null);
    }

    /** True when this flow holds documents and not one of them says how the system must be built. */
    static boolean noneTechnical(GuidedFlow f) {
        for (FlowDocument entry : f.documents()) {
            if (entry.technical()) {
                return false;
            }
        }
        return true;
    }

    // --- step 2: running ------------------------------------------------------------------------

    private void renderRunning(GuidedFlow f) {
        body.add(hint("The analyst is reading your documents. This takes a few minutes on a large "
            + "spec — you can close this window and come back; it keeps running."));
        Button cancel = new Button("Cancel", e -> call(() -> service.cancel(f.id().toString())));
        cancel.addClassName("btn-sm btn-ghost");
        footer.add(cancel);
    }

    // --- step 3: questions ----------------------------------------------------------------------

    private void renderQuestions(FlowView view) {
        String flowId = view.flow().id().toString();
        Div intro = new Div();
        intro.addClassName("flex items-start gap-2");
        Div introText = hint("The analyst found things the documents leave open. Answer what "
            + "matters — skip anything you do not want to decide now, and it will state the "
            + "assumption it made instead.");
        introText.addClassName("flex-1");
        // Half of these answers live in someone else's head. Copying the lot out as plain text is
        // how they get into an email, and that beats asking the operator to guess on their behalf.
        Button copyAll = copyButton("Copy all", () -> asText(flow.get().questions(),
            "Requirements clarifications"));
        copyAll.addClassName("btn-xs btn-ghost");
        intro.add(introText, copyAll);
        body.add(intro);
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
        // Answering these is one of the six things autonomous running does for the operator, and
        // two actors submitting the same round of answers is a real collision rather than a
        // duplicate. The questions themselves stay on screen and stay readable - what he asked to
        // lose is the lever, not the view.
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
        // Added, which it was not. The subject is the two or three words saying what a question is
        // ABOUT — "Refund window", "Who approves" — and it is the first thing the analyst is asked
        // for, precisely so a page of clarifications can be skimmed. This row built it, styled it
        // and then never put it in the head, so every question in this wizard arrived as a bare
        // sentence with no label while the planning wizard's identical row showed one. It carries
        // flex-1, so it has to come first: without it the icons after it had nothing to push
        // against and sat hard against the left edge of the row.
        head.add(subject);
        // The fuller background lives behind the info icon rather than inline: much of a
        // requirements pile is a diagram or a photographed page that cannot be quoted, and the
        // explanation that replaces a quote is longer than a form row can carry.
        if (question.background() != null && !question.background().isBlank()) {
            head.add(iconButton("info", "More context behind this question",
                () -> showDetail(question)));
        }
        head.add(copyIconButton("Copy this question — to ask someone who knows",
            () -> asText(List.of(question), null)));
        // Answer, skip, or go and read the document yourself used to be the whole menu. The reply
        // an operator actually has is often "why are you asking?" — this is where they can say it.
        Button talk = new Button("Discuss");
        talk.addClassName("btn-xs btn-ghost");
        talk.getElement().setAttribute("title",
            "Ask the analyst why it wants this, or what the document actually says — then capture "
            + "what you conclude as your answer.");
        head.add(talk);
        row.add(head);
        // The passage first, then the question. A question about a document the operator has not
        // memorised is unanswerable on its own, and making them go and find the section is exactly
        // the friction this wizard exists to remove.
        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            Div quote = new Div(question.sourceQuote());
            quote.addClassName("text-xs mt-1.5 pl-2 border-l-2 border-base-300 "
                + "text-base-content/70 whitespace-pre-wrap");
            row.add(quote);
            if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
                Div from = new Div("— " + question.sourceDocument());
                from.addClassName("text-[10px] pl-2 text-base-content/40");
                row.add(from);
            }
        }

        Div text = new Div(question.text() == null ? "" : question.text());
        text.addClassName("text-sm mt-1.5 font-medium");
        row.add(text);

        // Where "Use this as my answer" writes what the discussion settled. Held in a one-slot array
        // because it is only known inside the branch below, and the click handler outlives it.
        final TextField[] captureTarget = new TextField[1];
        boolean choice = question.kind() == FlowQuestionKind.CHOICE;
        if (choice) {
            RadioButtonGroup choices = new RadioButtonGroup("q-" + questionId);
            choices.addClassName("flex flex-col gap-1 mt-2 text-sm");
            choices.setItems(new ArrayList<>(question.options()));
            if (question.answer() != null) {
                choices.setValue(question.answer());
            }
            choices.addValueChangeListener(e ->
                call(() -> service.answer(flowId, questionId, choices.getValue())));
            row.add(choices);
            // The options are the analyst's guess at the answer space, and they are routinely not
            // quite it. A free-text line lets the operator qualify the choice — "yes, but only once
            // the gate closes" — instead of picking the least wrong radio button and moving on.
            TextField noteField = new TextField("Add detail (optional) — anything the options miss");
            noteField.addClassName("input-xs w-full mt-2");
            if (question.note() != null) {
                noteField.setValue(question.note());
            }
            noteField.addValueChangeListener(e -> {
                String typed = noteField.getValue();
                call(() -> service.note(flowId, questionId, typed == null ? "" : typed));
            });
            row.add(noteField);
            // A discussion about a CHOICE resolves into the NOTE, not the answer: the answer is one
            // of the analyst's options and stays a picked option, machine-readable. What the
            // conversation produces is the qualification the options did not have room for.
            captureTarget[0] = noteField;
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
            captureTarget[0] = answer;
        }
        // Wired last, because what it writes into depends on which control was built above. The
        // discussion has already told the server, so this only has to put the wording on screen —
        // and setValue does that, because the ROW's field never saw the discussion and still holds
        // whatever was in it before. It will re-send the same text through the field's own change
        // listener; that is one idempotent call, and the alternative is writing behind the model.
        talk.addClickListener(e -> showDiscussion(flowId, question, choice, captured -> {
            if (captureTarget[0] != null) {
                captureTarget[0].setValue(captured);
            }
        }));

        Div skipRow = new Div();
        skipRow.addClassName("flex items-center gap-2 mt-2");
        Checkbox skip = new Checkbox();
        skip.addClassName("checkbox-xs");
        skip.setValue(question.skipped());
        skip.addValueChangeListener(e -> call(() -> Boolean.TRUE.equals(skip.getValue())
            ? service.skip(flowId, questionId)
            : service.answer(flowId, questionId, "")));
        Span label = new Span("Skip — decide this later");
        TextStyle.CAPTION.applyTo(label);
        skipRow.add(skip, label);
        row.add(skipRow);
        return row;
    }

    // --- step 4: review -------------------------------------------------------------------------

    private void renderReview(FlowView view) {
        String flowId = view.flow().id().toString();
        body.add(hint("Nothing has been written yet. Untick anything you do not want, then apply."));
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
        Button back = new Button("Back to documents", e -> call(() -> service.reopen(flowId)));
        back.addClassName("btn-sm btn-ghost");
        Button apply = new Button("Apply to the BRD", e -> {
            call(() -> service.apply(flowId));
            // Close on success: the operator's next act is to look at the requirements, and the
            // BRD behind this dialog has already redrawn from the signal. Leaving the wizard up
            // makes them dismiss a window to see the thing they just asked for. A failure keeps it
            // open, because the message explaining it is in here.
            if (notice.get().isEmpty()) {
                dialog.close();
                if (onApplied != null) {
                    GuidedFlow applied = flow.get() == null ? null : flow.get().flow();
                    onApplied.accept(applied == null || applied.stepLabel() == null
                        ? "Applied to the BRD." : applied.stepLabel());
                }
            }
        });
        apply.addClassName("btn-primary");
        // Applying what the analyst drafted is the pilot's own next step while it is building on
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
        head.addClassName("flex items-center gap-2");
        head.add(kindBadge(proposal.kind()));
        if (proposal.handle() != null && !proposal.handle().isBlank()) {
            Span handle = new Span(proposal.handle());
            handle.addClassName("text-[11px] font-mono text-base-content/50");
            head.add(handle);
        }
        Span title = new Span(proposal.title() == null ? "(untitled)" : proposal.title());
        title.addClassName("text-sm font-medium");
        head.add(title);
        column.add(head);

        if (proposal.before() != null && !proposal.before().isBlank()) {
            column.add(block("before", proposal.before(), "text-error/80 line-through/0"));
        }
        if (proposal.after() != null && !proposal.after().isBlank()) {
            column.add(block(proposal.before() == null ? "" : "after", proposal.after(),
                "text-base-content/80"));
        }
        if (proposal.impact() != null && !proposal.impact().isBlank()) {
            // Directly under the title, not buried under the diff: this is what the tick costs.
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

    private Div block(String label, String text, String tone) {
        Div wrap = new Div();
        wrap.addClassName("mt-1");
        if (!label.isEmpty()) {
            Div caption = new Div(label);
            caption.addClassName("text-[10px] uppercase tracking-wide text-base-content/40");
            wrap.add(caption);
        }
        Div content = new Div(text);
        content.addClassName("text-xs whitespace-pre-wrap " + tone);
        wrap.add(content);
        return wrap;
    }

    private Span kindBadge(FlowProposalKind kind) {
        Span badge = new Span(switch (kind) {
            case ADD -> "+ new";
            case EDIT -> "~ edit";
            case DEPRECATE -> "- retire";
            case CONFLICT -> "! conflict";
        });
        badge.addClassName("badge badge-xs " + switch (kind) {
            case ADD -> "badge-success";
            case EDIT -> "badge-info";
            case DEPRECATE -> "badge-warning";
            case CONFLICT -> "badge-error";
        });
        return badge;
    }

    // --- done -----------------------------------------------------------------------------------

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
        body.add(hint("Applied " + accepted + " change" + (accepted == 1 ? "" : "s")
            + " to the requirements"
            + (declined == 0 ? "." : ", and left " + declined + " unapplied.")
            + " Each new requirement is a draft, with the checks that would prove it proposed "
            + "alongside. Open the Requirements panel and agree the ones you want built."));
        Button again = new Button("Add more documents", e -> call(() -> service.reopen(f.id().toString())));
        again.addClassName("btn-sm btn-outline");
        Button done = new Button("Done", e -> dialog.close());
        done.addClassName("btn-primary");
        footer.add(again, done);
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * The questions as plain text a stakeholder can answer in an email.
     *
     * <p>Each carries its source passage and a blank "Answer:" line: the person who knows the answer
     * is usually not the person in front of this screen, and pasting a bare question at them just
     * moves the "what is this about?" problem one desk over.
     */
    private static String asText(List<FlowQuestion> questions, String title) {
        StringBuilder out = new StringBuilder();
        if (title != null) {
            out.append(title).append("\n\n").append(questions.size())
                .append(questions.size() == 1 ? " question" : " questions")
                .append(" raised while turning the attached documents into requirements. "
                    + "Please answer what you can — anything left blank will be filled with a "
                    + "stated assumption instead.\n\n");
        }
        int n = 0;
        for (FlowQuestion question : questions) {
            n++;
            if (title != null) {
                out.append(n).append(". ");
            }
            if (question.subject() != null && !question.subject().isBlank()) {
                out.append('[').append(question.subject()).append("]\n");
            }
            if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
                out.append("Context");
                if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
                    out.append(" (from ").append(question.sourceDocument()).append(')');
                }
                out.append(":\n").append(question.sourceQuote().strip()).append("\n\n");
            }
            out.append("Q: ").append(question.text() == null ? "" : question.text()).append('\n');
            if (!question.options().isEmpty()) {
                out.append("Options:\n");
                for (String option : question.options()) {
                    out.append("  - ").append(option).append('\n');
                }
            }
            out.append("\nAnswer: ").append(question.answer() == null ? "" : question.answer())
                .append("\n\n");
        }
        return out.toString();
    }

    /**
     * Opens the background behind one question.
     *
     * <p>A second dialog rather than an expanding row: this is reference material read once while
     * deciding, and growing the row would push every other question off screen just when the
     * operator wants to compare them.
     */
    private void showDetail(FlowQuestion question) {
        takeOverDetail();
        Div title = new Div(question.subject() == null || question.subject().isBlank()
            ? "Background" : question.subject());
        title.addClassName("text-lg font-bold");
        detail.add(title);

        if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
            Div from = new Div("From " + question.sourceDocument());
            from.addClassName("text-[11px] text-base-content/40 mb-2");
            detail.add(from);
        }
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
        Button copy = copyButton("Copy", () -> asText(List.of(question), null));
        copy.addClassName("btn-sm btn-ghost");
        Button close = new Button("Close", e -> detail.close());
        close.addClassName("btn-sm btn-primary");
        actions.add(copy, close);
        detail.add(actions);
        detail.open();
    }

    // --- discussing one question ------------------------------------------------------------------

    /** How often the wizard asks whether the analyst has replied, and for how long it keeps asking. */
    private static final int REPLY_POLL_MILLIS = 1_500;
    private static final int REPLY_POLL_ATTEMPTS = 200;      // ~5 minutes

    /**
     * Which discussion is open, bumped every time one opens or closes.
     *
     * <p>A poll started for one question must not go on writing into the dialog after the operator
     * has closed it or opened another — the transcript would be replaced by somebody else's
     * conversation, in a dialog that has already moved on. Each scheduled tick carries the
     * generation it was started under and gives up when it no longer matches.
     */
    private int discussionGeneration;

    /**
     * Empties the shared second dialog and retires whatever was using it.
     *
     * <p>Every screen that reuses {@link #detail} goes through here, because a discussion left
     * polling into a body that has since become the background dialog would rewrite a surface that
     * is no longer its own — and would do it under the operator's eyes.
     */
    private void takeOverDetail() {
        discussionGeneration++;
        detail.removeAll();
    }

    /**
     * A side conversation about one clarification question.
     *
     * <p>It reuses {@link #detail} rather than adding a third surface: this is the same "read more
     * about this question" act as the background dialog, and a wizard with three stacked modals is
     * one the operator cannot find their way out of.
     *
     * <p>The question and its background sit at the top so nobody is typing blind, and the answer
     * the conversation reaches is captured with one button rather than retyped into the row behind.
     *
     * @param choice whether this is a CHOICE question — the capture then lands on the note
     * @param capture writes the settled answer into the row that opened this, so closing returns to
     *                the question list with it already filled in
     */
    private void showDiscussion(String flowId, FlowQuestion question, boolean choice,
                                java.util.function.Consumer<String> capture) {
        String questionId = question.id().toString();
        takeOverDetail();
        int generation = discussionGeneration;

        Div title = new Div(question.subject() == null || question.subject().isBlank()
            ? "Discuss this question" : "Discuss: " + question.subject());
        title.addClassName("text-lg font-bold");
        detail.add(title);

        Div asked = new Div(question.text() == null ? "" : question.text());
        asked.addClassName("text-sm font-medium mt-1");
        detail.add(asked);

        // Everything the row already showed, repeated here. The operator opened this dialog because
        // the question was not answerable as it stood; sending them back to the list to re-read the
        // quote would be the same walk to the filing cabinet the wizard exists to remove.
        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            Div quote = new Div(question.sourceQuote());
            quote.addClassName("text-xs mt-2 pl-2 border-l-2 border-base-300 "
                + "text-base-content/70 whitespace-pre-wrap");
            detail.add(quote);
        }
        if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
            Div from = new Div("— " + question.sourceDocument());
            from.addClassName("text-[10px] pl-2 text-base-content/40");
            detail.add(from);
        }
        if (question.background() != null && !question.background().isBlank()) {
            Div background = new Div(question.background());
            background.addClassName("text-xs mt-2 text-base-content/60 whitespace-pre-wrap "
                + "max-h-32 overflow-y-auto");
            detail.add(background);
        }

        Div transcript = new Div();
        transcript.addClassName("flex flex-col gap-2 mt-3 pt-3 border-t border-base-300 "
            + "max-h-72 overflow-y-auto");
        detail.add(transcript);

        // The analyst's most recent words, so "Use this as my answer" has something to capture when
        // the operator has not restated it themselves.
        String[] latestReply = {""};
        List<FlowDiscussionTurn> opening = refetch(flowId, questionId, transcript, latestReply);

        // This dialog's own status line. A message on the wizard behind it would be underneath the
        // surface the operator is looking at, which reads exactly like a dead button.
        Div status = new Div();
        status.addClassName("text-xs text-base-content/70 mt-2 min-h-4");
        if (!opening.isEmpty() && !answered(opening)) {
            // Re-opened while an answer was still coming. The operator is told this is expected —
            // otherwise their own question sitting alone reads as one the analyst ignored.
            status.setText("The analyst has not answered your last message yet — waiting…");
            awaitReply(generation, flowId, questionId, transcript, status, latestReply, 0);
        }
        TextArea input = new TextArea("Ask why this matters, what the documents say, or say what "
            + "you think the answer is");
        input.addClassName("textarea textarea-bordered w-full h-24 mt-2 text-xs");
        detail.add(input, status);

        Div actions = new Div();
        actions.addClassName("flex items-center justify-end gap-2 mt-3");
        Div note = new Div(choice
            ? "Nothing here changes the requirements. Capturing keeps your wording alongside the "
                + "option you picked."
            : "Nothing here changes the requirements — capture what you decide as your answer.");
        note.addClassName("text-[11px] text-base-content/40 flex-1");
        actions.add(note);

        Button use = new Button("Use this as my answer", e -> {
            // What is IN the box wins, because an operator who typed their own wording meant it.
            // Otherwise the analyst's last reply is captured — the case where the conversation
            // reached the answer and retyping it would be busywork.
            String typed = input.getValue() == null ? "" : input.getValue();
            String resolution = typed.isBlank() ? latestReply[0] : typed;
            if (resolution.isBlank()) {
                status.setText("nothing to capture yet — type your answer, or ask the analyst first");
                return;
            }
            String result;
            try {
                result = choice ? service.note(flowId, questionId, resolution)
                    : service.answer(flowId, questionId, resolution);
            } catch (Exception ex) {
                status.setText("that did not reach the server: " + ex);
                return;
            }
            if (result != null && !result.isEmpty()) {
                status.setText(result);
                return;                     // stay open, so the wording is not lost
            }
            capture.accept(resolution);
            discussionGeneration++;         // stops any poll still running for this conversation
            detail.close();
        });
        use.addClassName("btn-sm btn-outline");

        Button send = new Button("Send", e -> {
            String message = input.getValue() == null ? "" : input.getValue();
            if (message.isBlank()) {
                status.setText("type something first");
                return;
            }
            String result;
            try {
                result = service.discuss(flowId, questionId, message);
            } catch (Exception ex) {
                status.setText("that did not reach the server: " + ex);
                return;
            }
            if (result != null && !result.isEmpty()) {
                status.setText(result);
                return;
            }
            // The operator's own turn is already stored, so showing it costs one fetch and makes
            // the button visibly do something while the model is still being called.
            input.setValue("");
            if (answered(refetch(flowId, questionId, transcript, latestReply))) {
                status.setText("");         // a fast analyst can beat this fetch
                return;
            }
            status.setText("The analyst is reading the documents…");
            awaitReply(generation, flowId, questionId, transcript, status, latestReply, 0);
        });
        send.addClassName("btn-sm btn-primary");

        Button close = new Button("Close", e -> detail.close());
        close.addClassName("btn-sm btn-ghost");
        actions.add(close, use, send);
        detail.add(actions);
        detail.open();
    }

    /** Re-reads the transcript and redraws it. @return the turns as they now stand */
    private List<FlowDiscussionTurn> refetch(String flowId, String questionId, Div transcript,
                                             String[] latestReply) {
        List<FlowDiscussionTurn> turns;
        try {
            turns = service.discussion(flowId, questionId);
        } catch (Exception e) {
            return List.of();
        }
        fillTranscript(transcript, turns, latestReply);
        return turns == null ? List.of() : turns;
    }

    /**
     * Whether the analyst has had the last word.
     *
     * <p>Deliberately not "are there more turns than before". Counting would keep the wizard saying
     * "thinking…" when a fast model had already answered before the fetch that was meant to show the
     * operator's own turn, and it would say "answered" if a second operator turn arrived from
     * another tab. Who spoke last is the thing actually being waited on.
     */
    private static boolean answered(List<FlowDiscussionTurn> turns) {
        return !turns.isEmpty() && !turns.get(turns.size() - 1).fromOperator();
    }

    /**
     * Waits for the analyst's reply and drops it into the open dialog.
     *
     * <p>The reply cannot arrive with the call that asked for it: the model runs on a server worker
     * so that a slow analyst does not become a request timeout and a Send button that appears dead.
     * So the wizard asks again until the analyst has had the last word.
     *
     * <p>The RMI call runs inside a {@code new Thread(...)}: a {@code Window.setTimeout} callback is
     * a native JS context, and a suspending call made directly from one throws "Suspension point
     * reached from non-threading context" — the same trap {@link HealthStrip} documents.
     */
    private void awaitReply(int generation, String flowId, String questionId,
                            Div transcript, Div status, String[] latestReply, int attempt) {
        if (generation != discussionGeneration) {
            return;                         // the dialog closed, or another question was opened
        }
        Window.setTimeout(() -> new Thread(() -> {
            if (generation != discussionGeneration) {
                return;
            }
            List<FlowDiscussionTurn> turns;
            try {
                turns = service.discussion(flowId, questionId);
            } catch (Exception e) {
                status.setText("could not reach the server — the reply may still arrive: " + e);
                return;
            }
            if (answered(turns == null ? List.of() : turns)) {
                fillTranscript(transcript, turns, latestReply);
                status.setText("");
                return;
            }
            if (attempt + 1 >= REPLY_POLL_ATTEMPTS) {
                // Say so rather than sitting on "thinking…" for ever. Whatever went wrong is in the
                // server log, and the transcript survives — re-opening this shows any late reply.
                status.setText("The analyst has still not answered. Close this and open it again "
                    + "to check, or look at the server log.");
                return;
            }
            awaitReply(generation, flowId, questionId, transcript, status, latestReply,
                attempt + 1);
        }).start(), REPLY_POLL_MILLIS);
    }

    /**
     * Redraws the conversation, and records the analyst's latest words for the capture button.
     *
     * <p>Only the transcript is replaced. The input box is left strictly alone — rebuilding it under
     * a poll would delete whatever the operator was part-way through typing while they waited.
     */
    private void fillTranscript(Div transcript, List<FlowDiscussionTurn> turns,
                                String[] latestReply) {
        transcript.removeAll();
        if (turns == null || turns.isEmpty()) {
            transcript.add(hint("Nothing said yet. Ask why this was raised, what the documents "
                + "actually say around it, or what changes depending on the answer."));
            return;
        }
        for (FlowDiscussionTurn turn : turns) {
            boolean mine = turn.fromOperator();
            Div line = new Div();
            line.addClassName("text-xs");
            Div who = new Div(mine ? "You" : "Analyst");
            who.addClassName("text-[10px] uppercase tracking-wide "
                + (mine ? "text-base-content/40" : "text-primary/70"));
            Div said = new Div(turn.text());
            said.addClassName("whitespace-pre-wrap " + (mine
                ? "text-base-content/70 pl-2 border-l-2 border-base-300"
                : "text-base-content/90"));
            line.add(who, said);
            transcript.add(line);
            if (!mine) {
                latestReply[0] = turn.text();
            }
        }
    }

    /**
     * Shows what the analyst actually reads from a document.
     *
     * <p>The EXTRACTED text, not the original file — the original bytes are not kept, and when a
     * requirement comes out wrong the question is never "what did the PDF look like?" but "what was
     * read out of it?". For a vision extraction that distinction is the whole point: the text is a
     * model's reading of a picture and can be wrong, and this is where that becomes visible.
     */
    private void showDocument(SourceDocument document) {
        takeOverDetail();
        Div title = new Div(document.filename() == null ? "(unnamed)" : document.filename());
        title.addClassName("text-lg font-bold");
        detail.add(title);

        String by = document.extractedBy() == null ? "unknown" : document.extractedBy();
        Div meta = new Div("Extracted by " + by + " · " + document.byteSize() + " bytes");
        TextStyle.CAPTION.applyTo(meta);
        detail.add(meta);
        if (by.startsWith("vision")) {
            Div warn = new Div("This is a model's reading of an image, not the document itself. "
                + "It may be incomplete or wrong — check anything important against the original.");
            warn.addClassName("text-xs text-warning mt-2");
            detail.add(warn);
        }

        String text;
        try {
            text = service.documentText(document.id().toString());
        } catch (Exception e) {
            text = "could not load the text: " + e;
        }
        Div content = new Div(text);
        content.addClassName("text-xs whitespace-pre-wrap font-mono mt-3 p-3 rounded "
            + "bg-base-200/50 max-h-[60vh] overflow-y-auto");
        detail.add(content);

        Div actions = new Div();
        actions.addClassName("flex justify-end gap-2 mt-4");
        String copyable = text;
        Button copyIt = copyButton("Copy", () -> copyable);
        copyIt.addClassName("btn-sm btn-ghost");
        Button close = new Button("Close", e -> detail.close());
        close.addClassName("btn-sm btn-primary");
        actions.add(copyIt, close);
        detail.add(actions);
        detail.open();
    }

    /**
     * Paste text straight in as a document.
     *
     * <p>Much of what a requirement starts from is an email, a chat excerpt or a page of notes that
     * has no file. Saving it to disk purely so it can be uploaded back is a step that earns nothing.
     */
    private void showPaste(String flowId) {
        takeOverDetail();
        Div title = new Div("Paste requirement text");
        title.addClassName("text-lg font-bold");
        Div subtitle = new Div("An email, a chat excerpt, a page of notes — anything the analyst "
            + "should read. It is stored as a document like any upload.");
        subtitle.addClassName("text-xs text-base-content/60 mb-3");
        detail.add(title, subtitle);

        TextField name = new TextField("call with finance, 12 Mar");
        name.addClassName("input-sm w-full");
        TextArea text = new TextArea("paste it here");
        text.addClassName("textarea textarea-bordered w-full h-64 mt-2 text-xs font-mono");
        // This dialog needs its own status line. Reporting into the wizard's would put the message
        // on the surface underneath this one, where the operator cannot see it — which is exactly
        // how "nothing to add" reads as a dead button.
        Div status = new Div();
        status.addClassName("text-xs text-error mt-2");
        detail.add(name.withLabel("Name it"), text.withLabel("The text"), status);

        Div actions = new Div();
        actions.addClassName("flex justify-end gap-2 mt-4");
        Button cancel = new Button("Cancel", e -> detail.close());
        cancel.addClassName("btn-sm btn-ghost");
        Button save = new Button("Add it", e -> {
            String body = text.getValue() == null ? "" : text.getValue();
            if (body.isBlank()) {
                status.setText("nothing to add — paste the text first");
                return;
            }
            String documentName = name.getValue() == null ? "" : name.getValue();
            String result;
            try {
                result = service.addPastedDocument(flowId, documentName, body);
            } catch (Exception ex) {
                status.setText("that did not reach the server: " + ex);
                return;
            }
            if (result != null && !result.isEmpty()) {
                status.setText(result);
                return;                      // stay open so the text is not lost
            }
            refreshPool();
            detail.close();
        });
        save.addClassName("btn-sm btn-primary");
        actions.add(cancel, save);
        detail.add(actions);
        detail.open();
    }

    /** One service call. Anything it can return or throw ends up in front of the operator. */
    private interface Call {
        String run();
    }

    /**
     * A Copy button that really copies.
     *
     * <p>Not {@code new Button(label, listener)}: every click listener the component library
     * registers is deferred onto a green thread, and by the time it runs the browser's user
     * gesture has expired and the clipboard write is refused with no error anywhere. See
     * {@link Clipboard}. Every Copy control in this wizard goes through here or through
     * {@link #copyIconButton}.
     */
    private Button copyButton(String label, java.util.function.Supplier<String> text) {
        Button button = new Button(label);
        Clipboard.onCopyClick(button.getElement(), text, this::saidCopied);
        return button;
    }

    /** {@link #copyButton} as the small icon the question rows carry. */
    private Div copyIconButton(String tooltip, java.util.function.Supplier<String> text) {
        Div button = new Div();
        button.addClassName("cursor-pointer text-base-content/40 hover:text-primary shrink-0");
        button.add(Icon.of("copy", "w-4 h-4"));
        button.getElement().setAttribute("title", tooltip);
        Clipboard.onCopyClick(button.getElement(), text, this::saidCopied);
        return button;
    }

    private void saidCopied(boolean copied) {
        notice.set(copied ? "Copied to the clipboard."
            : "Could not reach the clipboard — select the text and copy it by hand.");
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

    /**
     * Runs a service call and puts whatever comes back in front of the operator — "" means it
     * worked, an "error: …" string is shown, and a THROW is shown too.
     *
     * <p>The throw case is the reason this exists. Calling the stub inline meant an RMI failure
     * escaped the click handler and the button simply did nothing: no dialog change, no message,
     * nothing in the server log because the call never arrived. A button that can fail silently is
     * worse than one that is missing.
     */
    private void call(Call callable) {
        try {
            String result = callable.run();
            notice.set(result == null || result.isEmpty() ? "" : result);
        } catch (Exception e) {
            notice.set("that did not reach the server: " + e);
            return;
        }
        refreshPool();
    }

    /**
     * Re-reads the project's unattached documents. Only ever called from a DOM event handler — see
     * the note on {@link #pool} for why that restriction is not negotiable.
     */
    private void refreshPool() {
        FlowView view = flow.get();
        if (view == null || view.flow() == null) {
            return;
        }
        try {
            List<SourceDocument> available = service.availableDocuments(view.flow().id().toString());
            pool.set(available == null ? new ArrayList<>() : new ArrayList<>(available));
        } catch (Exception e) {
            // Not worth interrupting the operator over: the pool is an extra way in, and uploading
            // the file again still works.
            pool.set(new ArrayList<>());
        }
    }
}
