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
import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.console.api.BrdService;
import com.swarmcoder.console.api.BrdService_Stub;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.console.api.GuidedFlowService;
import com.swarmcoder.console.api.GuidedFlowService_Stub;
import com.swarmcoder.console.api.GuidedFlowSignals;
import com.swarmcoder.console.api.ReadinessSignals;
import com.swarmcoder.console.api.RequirementPageDto;
import com.swarmcoder.console.api.RequirementQuery;
import com.swarmcoder.console.api.RequirementRowDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.ui.theme.Emphasis;
import com.zeroz4j.ui.theme.TextStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The guide: a step-through that <b>carries the work out</b>, explaining each idea at the moment it
 * is used.
 *
 * <h2>The two things this is not, any more</h2>
 *
 * <p><b>It is not a book.</b> It used to open with two full pages of prose — what SwarmCoder is,
 * then the four words it uses — before anything happened at all. The writing was good and most of it
 * is kept, but a person opening a guide is trying to get something done, and a glossary read cold is
 * forgotten by the screen that needs it. So each of the four words is now explained on the step that
 * uses it: what a <em>document</em> is, on the step where one is added; what a <em>requirement</em>
 * and its <em>checks</em> are, while the ones just extracted are on screen; what a <em>story</em>
 * is, on the step that makes them; what a <em>build</em> is, on the step that starts one. UX v3 §2.1
 * is still the whole vocabulary — it simply lands in use.
 *
 * <p><b>It does not send you elsewhere to do the work.</b> Every route used to end at a button that
 * closed the guide and left the operator on another screen to work the rest out. Now the guide adds
 * the documents, starts the reading, agrees the requirements and plans the stories itself. Where a
 * step genuinely belongs to a surface that already exists — answering the analyst's questions,
 * reviewing what it proposes, planning stories — the guide opens <em>that</em> surface over itself
 * and stays behind it, so finishing there returns to the next step rather than to nowhere.
 *
 * <h2>Reuse, not a third implementation</h2>
 *
 * <p>{@link IntakeWizard} and {@link PlanningWizard} already do this work against the same
 * server-owned flows, and {@link DocumentUpload} is the one place the Console asks for a file. All
 * three are composed here. Nothing about uploading, answering or reviewing is written twice: what is
 * new is only the spine that walks a person from one to the next and says why each one matters.
 *
 * <p>The hand-over is a swap, not a stack. Opening one of those windows OVER this one was tried
 * first, and the picture said no: the browser draws both in its top layer, and the window this
 * opened came out behind this one, dimmed by its own backdrop. So the guide closes itself, opens the
 * other window, and comes back on the next step when that window closes — by its button, by Escape,
 * or by a click outside.
 *
 * <h2>What it still does not do</h2>
 *
 * <p><b>It does not choose a kind of project.</b> UX v3 §2.2 keeps the pool of requirements open for
 * the life of a project — "Nothing about being 'in the build phase' closes intake" — so a mode that
 * made a project bug-fix-shaped would lock the operator out of writing a requirement on Tuesday
 * because of what they clicked on Monday. What this picks is <b>what happens next</b>. Every route
 * reaches every other one, and no screen in it ever calls a project a kind of project.
 *
 * <p><b>It does not invent a second notion of progress.</b> Every tick on every step is read from
 * {@link ConsoleReadiness}, the intake flow, or the backlog — the server's own answers. A count made
 * here would disagree with the header the first time anything changed (UX v3 §5 rule 2).
 *
 * <h2>Mounting it</h2>
 *
 * <pre>{@code
 * private final OnboardingWizard guide = new OnboardingWizard();
 * ...
 * add(guide);          // last, so the dialog is not a flex item in the middle of a column
 * ...
 * guide.dispose();     // the effects are bound to process-wide signals
 * }</pre>
 *
 * <p>A {@link Div} holding its dialogs rather than being one, for the reason on
 * {@link PlanningWizard}: the wrapper is {@code display: contents}, so mounting it is exactly as
 * weightless as mounting the dialogs.
 */
final class OnboardingWizard extends Div implements Disposable {

    /** Step 1, always: which of the three things you are about to do. */
    private static final String WHERE = "where";
    /** The route that starts from something written down — five steps that do the work. */
    private static final String DOC_ADD = "doc-add";
    private static final String DOC_READ = "doc-read";
    private static final String DOC_AGREE = "doc-agree";
    private static final String DOC_STORIES = "doc-stories";
    private static final String DOC_BUILD = "doc-build";
    /** The short route, and the one that is designed but not written. */
    private static final String ROUTE_CHANGE = "route-change";
    private static final String ROUTE_EXISTING = "route-existing";

    private final GuidedFlowService intake = new GuidedFlowService_Stub();
    private final BrdService brd = new BrdService_Stub();

    private final Dialog dialog = new Dialog();
    private final Div progressBar = new Div();
    private final Div stepLine = new Div();
    private final Div body = new Div();
    private final Div footer = new Div();
    private final Div noticeLine = new Div();

    /**
     * The analysis and the planner, opened OVER this dialog when a step reaches the part of the job
     * they own. Owned here so the guide is still behind them when they close.
     */
    private final IntakeWizard analysis = new IntakeWizard("guide-intake-wizard");
    private final PlanningWizard planner = new PlanningWizard("guide-planning-wizard");
    /**
     * The other way through this guide: hand every step of it to the machine.
     *
     * <p>It lives on the document step and nowhere else, because that is the only screen where the
     * operator's own sentence - "load up the document and let it run" - is literally true. Every
     * gate the guide walks them through is downstream of it, and every earlier screen has nothing
     * yet to hand over.
     */
    private final AutonomousDialog autonomy = new AutonomousDialog();

    private final ValueSignal<String> screen = new ValueSignal<>(WHERE);
    /**
     * The project's intake flow. Seeded by the direct call the moment a step needs it, and kept live
     * by the shared signal from then on — the same arrangement, and for the same reason, as
     * {@link IntakeWizard}: binding only to the signal leaves the first frame waiting on a broadcast
     * that has not been sent yet.
     */
    private final ValueSignal<FlowView> flow = new ValueSignal<>(FlowView.none());
    /** The requirements nobody has agreed yet. Fetched from click handlers, never from an effect. */
    private final ValueSignal<List<RequirementRowDto>> drafts = new ValueSignal<>(new ArrayList<>());
    /**
     * Which of those the operator has opened to read, by requirement id.
     *
     * <p>Always replaced with a FRESH set, never mutated in place: a signal dedups by equals, so
     * changing the held value means the change is compared against itself and swallowed.
     */
    private final ValueSignal<Set<String>> reading = new ValueSignal<>(new HashSet<>());
    /**
     * The full requirement behind each opened row — its wording and its checks — fetched once each.
     *
     * <p>A {@link RequirementRowDto} deliberately carries neither: it is a LINE, and the statement,
     * the checks and the provenance are fetched only when a row is opened, so the cost of the list
     * stops scaling with how many requirements there are (REQUIREMENTS_AT_SCALE §5).
     * {@link BrdService#requirement(String)} is the call that exists for exactly this moment.
     */
    private final ValueSignal<Map<String, BrdRequirement>> read = new ValueSignal<>(new HashMap<>());
    /** What just happened, for anything the server does not publish (a refused call, an upload). */
    private final ValueSignal<String> notice = new ValueSignal<>("");

    private final List<Disposable> disposables = new ArrayList<>();
    private String rendered = "";
    /** True once it has been opened, so opening it again resumes the walk instead of restarting. */
    private boolean started;

    OnboardingWizard() {
        addClassName("contents");
        dialog.setWidth("46rem");
        // Named for anybody who cannot see it: this dialog draws its own heading, so without this
        // a screen reader announces the word "dialog" and nothing else.
        dialog.setAriaLabel("Getting started with SwarmCoder");
        dialog.getElement().setAttribute("data-testid", "onboarding-wizard");

        // The body scrolls; the heading, the progress line and the forward button do not.
        // Two of these steps carry a drop box, a list and a paste box, and at 960 the button
        // that carries the walk forward was pushed off the bottom of the window — a wizard
        // whose Next button cannot be reached is a dead end at the one width it matters.
        body.addClassName("flex flex-col gap-3 min-h-0 max-h-[58vh] overflow-y-auto pr-1 "
            + "[&>*]:shrink-0");
        footer.addClassName("flex items-center gap-2 mt-5");
        noticeLine.addClassName("mt-2");
        TextStyle.CAPTION.applyTo(noticeLine, Emphasis.FULL);
        dialog.add(header(), progressPane(), body, noticeLine, footer);
        // The sub-wizards are siblings of this dialog, not children of its modal box: a daisyUI
        // .modal-box carries a transform, and a transformed element becomes the containing block for
        // its position:fixed descendants, so a dialog nested inside it would be clipped into the box.
        add(dialog, analysis, planner, autonomy);

        disposables.add(Effect.create(this::renderProgress));
        disposables.add(Effect.create(this::renderIfChanged));
        // The steps tick themselves off from the server's own facts. Everything below is a read.
        disposables.add(Effect.create(() -> {
            ReadinessSignals.CURRENT.get();
            BacklogSignals.CURRENT.get();
            // And whether autonomous running has taken the gates this walk offers. Switching it on
            // must grey the agree controls here as it does everywhere else, and switching it off
            // must bring them straight back - so it forces a rebuild exactly as the other two do.
            com.swarmcoder.console.api.AutonomousSignals.CURRENT.get();
            rendered = "";
            renderIfChanged();
        }));
        disposables.add(Effect.create(() -> {
            FlowView published = GuidedFlowSignals.CURRENT.get();
            if (published != null && published.flow() != null) {
                flow.set(published);
            }
        }));
        disposables.add(Effect.create(() -> {
            String text = notice.get();
            noticeLine.setText(text == null ? "" : text);
            noticeLine.setVisible(text != null && !text.isEmpty());
        }));
        // Applying either flow is the moment its step is finished. The guide is not on screen while
        // they are — see handOver — so the summary is kept and shown when it comes back.
        analysis.onApplied(summary -> {
            notice.set(summary);
            screen.set(DOC_AGREE);
            refreshDrafts();
        });
        planner.onApplied(summary -> {
            notice.set(summary);
            screen.set(DOC_BUILD);
        });
        // Closing either of them — by its button, by Escape, or by a click outside — brings the walk
        // back, on whatever step it is now on.
        analysis.onClosed(this::resume);
        planner.onClosed(this::resume);
        // Except when the machine is now running on its own. The guide is a walk through the
        // decisions a person takes, and the operator has just handed all seven of them away: coming
        // back to step 2 of 6 would be a walk through work nobody is going to do. Worse, it is
        // another modal - and the whole point of switching this on is that the Console stays usable
        // while it runs. So closing the handover window on a running session leaves the operator in
        // the application, with the header's mark saying what is happening and how to stop it.
        autonomy.onClosed(() -> {
            if (!Boolean.TRUE.equals(autonomousRunning())) {
                resume();
            }
        });
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
        analysis.dispose();
        planner.dispose();
        autonomy.dispose();
    }

    /**
     * Opens the guide — at the beginning the first time, and where it was left every time after.
     *
     * <p>Resuming is what makes it a walk rather than a leaflet: two of its steps take minutes on
     * the server, and somebody who closes the window while one runs has to come back to the step
     * they were on, not to the front page.
     */
    void open() {
        if (!started) {
            started = true;
            screen.set(WHERE);
        }
        notice.set("");
        rendered = "";
        renderIfChanged();
        dialog.open();
    }

    /**
     * Hands over to a surface that already does this step's work, and comes straight back.
     *
     * <p>Two modals open at once was the first attempt and it does not work: the browser draws both
     * in its top layer, and the window the guide opened was painted BEHIND the guide, dimmed by its
     * own backdrop and unusable. Measured, in a screenshot, not deduced. So the guide steps aside
     * instead — one window at a time — and {@link #resume()} puts it back the moment the other one
     * closes, on whatever step the work has reached.
     */
    private void handOver(Runnable openIt) {
        dialog.close();
        openIt.run();
    }

    /** Comes back after a hand-over, keeping whatever the other window reported. */
    /**
     * Whether the machine is currently deciding things for the operator.
     *
     * <p>Read from the signal the server publishes rather than asked over RMI: this runs inside a
     * dialog's close handler, and it is the same fact the header's mark is drawn from, so there is
     * one answer to it.
     */
    private static Boolean autonomousRunning() {
        com.swarmcoder.console.api.AutonomousStatus status =
            com.swarmcoder.console.api.AutonomousSignals.CURRENT.get();
        return status != null && status.running();
    }

    private void resume() {
        rendered = "";
        renderIfChanged();
        dialog.open();
    }

    /** Forgets the walk. Called when the project changes — every step was about the old one. */
    void reset() {
        started = false;
        screen.set(WHERE);
        flow.set(FlowView.none());
        drafts.set(new ArrayList<>());
        reading.set(new HashSet<>());
        read.set(new HashMap<>());
        notice.set("");
        rendered = "";
    }

    // --- chrome -----------------------------------------------------------------------------------

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-start gap-3 mb-3");
        Div titles = new Div();
        titles.addClassName("flex-1 min-w-0");
        Div title = new Div("Getting started");
        title.addClassName("text-lg font-bold");
        // Where to find it again is said as a PLACE on the screen, not as the name of a panel. It
        // used to say "the small word beside the coloured mark at the top right", which was true and
        // was the fault: the way back in was hidden behind the health mark and nobody would look
        // there for a guide. There is a button with words on it now (UX v3 §5 rule 3).
        Div subtitle = TextStyle.CAPTION.paragraph(
            "This walks you through a first piece of work, and does each step with you. Close it "
            + "whenever you like — it comes back where you left it, from the \"Getting started\" "
            + "button at the top right of the window.");
        subtitle.addClassName("mt-0.5");
        titles.add(title, subtitle);
        Button close = new Button(Icon.of("x", "w-4 h-4"), "Close");
        close.addClassName("btn-ghost btn-xs");
        close.addClickListener(e -> dialog.close());
        bar.add(titles, close);
        return bar;
    }

    private Div progressPane() {
        Div pane = new Div();
        pane.addClassName("mb-4");
        Div track = new Div();
        track.addClassName("h-1.5 w-full rounded bg-base-300 overflow-hidden");
        progressBar.addClassName("h-full bg-primary transition-all");
        progressBar.getElement().getStyle().setProperty("width", "0%");
        track.add(progressBar);
        TextStyle.CAPTION.applyTo(stepLine);
        stepLine.addClassName("mt-1.5");
        pane.add(track, stepLine);
        return pane;
    }

    private void renderProgress() {
        String current = screen.get() == null ? WHERE : screen.get();
        int step = stepNumber(current);
        int total = totalSteps(current);
        // No total until a route has been picked. The three routes are different lengths, so any
        // number here would be wrong for two of them — it said "step 1 of 2" and then jumped
        // straight to "step 2 of 6", which is a progress bar lying on its first frame.
        boolean known = !WHERE.equals(current);
        progressBar.getElement().getStyle()
            .setProperty("width", known ? (step * 100 / total) + "%" : "8%");
        stepLine.setText(known
            ? "Step " + step + " of " + total + " — " + stepLabel(current)
            : "First: " + stepLabel(current));
    }

    private static int stepNumber(String value) {
        return switch (value) {
            case DOC_ADD -> 2;
            case DOC_READ -> 3;
            case DOC_AGREE -> 4;
            case DOC_STORIES -> 5;
            case DOC_BUILD -> 6;
            case ROUTE_CHANGE, ROUTE_EXISTING -> 2;
            default -> 1;
        };
    }

    /**
     * How long the walk is, which depends on which walk it is.
     *
     * <p>One number for all three routes would be a lie on two of them. The short route really is
     * two screens, and "step 2 of 6" over it would promise four more that do not exist.
     */
    private static int totalSteps(String value) {
        return switch (value) {
            case DOC_ADD, DOC_READ, DOC_AGREE, DOC_STORIES, DOC_BUILD -> 6;
            default -> 2;
        };
    }

    private static String stepLabel(String value) {
        return switch (value) {
            case DOC_ADD -> "add what you have written down";
            case DOC_READ -> "let SwarmCoder read it";
            case DOC_AGREE -> "agree what it found";
            case DOC_STORIES -> "turn it into slices of work";
            case DOC_BUILD -> "build one, and judge it";
            case ROUTE_CHANGE -> "one small change";
            case ROUTE_EXISTING -> "code that already exists";
            default -> "where to start";
        };
    }

    /**
     * A fingerprint of everything the body's structure depends on, so a republish that changes
     * nothing visible does not rebuild the screen under the operator's hands — which would throw
     * away whatever they have half-typed into the paste box.
     */
    private String fingerprint() {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        FlowView view = flow.get();
        GuidedFlow f = view == null ? null : view.flow();
        Backlog backlog = BacklogSignals.CURRENT.get();
        return (screen.get() == null ? WHERE : screen.get())
            + "|" + (readiness == null ? "-" : readiness.hasProject() + "/"
                + readiness.hasRepository() + "/" + readiness.requirementsAgreed() + "/"
                + readiness.requirementsDrafts())
            + "|" + (f == null ? "-" : f.state() + "/" + f.stepLabel() + "/" + f.error())
            + "|" + (view == null ? 0 : view.documents().size())
            // Ticking a document as technical has to redraw the step: the line under the list
            // saying nothing is ticked is about exactly this, and a step that kept showing it
            // after the operator ticked something would be arguing with them.
            + "|" + IntakeWizard.documentsKey(f)
            + "|" + (view == null ? 0 : view.questions().size())
            + "|" + (view == null ? 0 : view.proposals().size())
            + "|" + drafts.get().size()
            + "|" + readingKey()
            + "|" + (backlog == null ? 0 : backlog.stories().size());
    }

    /**
     * Which draft rows are open, and which of those have their wording and checks in hand.
     *
     * <p>Read here, and only here, so opening one redraws the step: the Effect that renders tracks
     * whatever this method touches. A row that has been fetched but is NOT open contributes
     * nothing, so the answer landing back from the server does not rebuild a screen it cannot
     * change — which is the whole point of the fingerprint.
     */
    private String readingKey() {
        Set<String> open = reading.get();
        Map<String, BrdRequirement> fetched = read.get();
        StringBuilder key = new StringBuilder();
        for (RequirementRowDto row : drafts.get()) {
            String id = row.getRequirementId();
            key.append(!open.contains(id) ? '.' : fetched.containsKey(id) ? 'r' : 'o');
        }
        return key.toString();
    }

    private void renderIfChanged() {
        String current = fingerprint();
        if (current.equals(rendered)) {
            return;
        }
        rendered = current;
        body.removeAll();
        footer.removeAll();
        switch (screen.get() == null ? WHERE : screen.get()) {
            case DOC_ADD -> renderAddDocuments();
            case DOC_READ -> renderRead();
            case DOC_AGREE -> renderAgree();
            case DOC_STORIES -> renderStories();
            case DOC_BUILD -> renderBuild();
            case ROUTE_CHANGE -> renderRouteChange();
            case ROUTE_EXISTING -> renderRouteExisting();
            default -> renderWhere();
        }
    }

    // --- step 1: what this is, and where to start --------------------------------------------------

    /**
     * The whole of the old first two screens, compressed to what a person needs before choosing.
     *
     * <p>Two paragraphs and three cards. Everything else those pages said is still in the product —
     * it is said on the step it is about.
     */
    private void renderWhere() {
        body.add(lead("SwarmCoder writes code for you, and proves it works before you keep it."));
        body.add(prose(
            "You say what you want in plain English. SwarmCoder works out a design, writes the "
            + "tests the new code will have to pass, and sets several workers on the same job at "
            + "once. You are shown the version that passed. Nothing is kept because a worker said "
            + "it was finished — it is kept because a test ran and passed."));
        body.add(prose(
            "Pick whichever of these is closest to what you have in front of you right now. It "
            + "decides what happens next and nothing more: you can do the others later, on this "
            + "same project, and none of them shuts any of the others off."));

        body.add(route("I have written down what I want built",
            "You have a document describing something that does not exist yet — a specification, a "
            + "brief, or a page of notes. We add it here, have SwarmCoder read it, agree what it "
            + "found, and turn that into work.",
            "Best when you are starting something new and roughly know what it should do.",
            "Five steps, done in this window. About twenty minutes of your attention before the "
            + "first build starts.",
            true, () -> {
                screen.set(DOC_ADD);
                loadFlow();
            }));

        body.add(route("I want one small change, or a bug fixed",
            "You already have code that works and you want one thing changed today. Point "
            + "SwarmCoder at the folder, say what you want in a sentence or two, and it goes.",
            "Best when you have a bug, a small addition, or you want to watch SwarmCoder work once "
            + "before trusting it with more.",
            "Under five minutes from pointing at the folder to a change that passed its tests. "
            + "That is measured on a real project, not an estimate.",
            true, () -> screen.set(ROUTE_CHANGE)));

        body.add(route("I have code already, and I want SwarmCoder to work out what it does",
            "Point it at a project that has been going for years, and have it read the documents "
            + "already in there, work out what the project was meant to do, and tell you which "
            + "parts of that it can prove are really built.",
            "It is designed and worked out in full, and it is not written.",
            "Open it to read what it will do, and what to use until it exists.",
            false, () -> screen.set(ROUTE_EXISTING)));

        footer.add(spacer());
    }

    // --- step 2: the documents, added here ----------------------------------------------------------

    /**
     * The step that used to say "open Requirements and add the file". It adds the file.
     *
     * <p>The drop box is {@link DocumentUpload#box()} — the same component the requirements screen
     * uses, with its own progress bar, its own cancel and its own size check made before any bytes
     * leave the machine. There is no second upload path here and there must never be one.
     */
    private void renderAddDocuments() {
        body.add(lead("Add what you have written down."));
        // The first of the four words, said where it is about to be used.
        body.add(word("Document",
            "Anything you hand SwarmCoder to read — a specification, a brief, a page of notes, a "
            + "pasted email. It is raw material: once SwarmCoder has read it, the document stops "
            + "being the thing that matters. More than one is fine."));

        if (!hasProject()) {
            body.add(note("There is no project yet, and everything belongs to one. Make one first, "
                + "using the name at the top left of the window, then come back here. A project is "
                + "one folder on this machine, and it has to be a git repository — each worker "
                + "makes its own branch and its own copy of it, and nothing is ever written to "
                + "your own working copy."));
            footer.add(back(WHERE));
            footer.add(spacer());
            return;
        }

        FlowView view = flow.get();
        List<SourceDocument> documents = view == null ? List.of() : view.documents();
        if (!documents.isEmpty()) {
            Div list = new Div();
            // shrink-0 and no scroll of its own. The body is the scrolling box now, and a
            // flex child that carries overflow-y-auto is shrunk to nothing by its flex
            // parent the moment the parent overflows — the list of documents simply was
            // not on the screen, with no error and no gap where it had been.
            list.addClassName("flex flex-col gap-1.5 shrink-0");
            GuidedFlow f = current();
            for (SourceDocument document : documents) {
                list.add(documentRow(f, document));
            }
            body.add(list);
            // Said once, quietly, and it stops nothing. See DocumentKind.NONE_TICKED — this is the
            // last screen before the analyst reads, so it is the last moment it can be said at all.
            if (f != null && IntakeWizard.noneTechnical(f)) {
                Div line = TextStyle.CAPTION.paragraph(DocumentKind.NONE_TICKED);
                line.getElement().setAttribute("data-testid", "guide-no-technical-document");
                body.add(line);
            }
        }

        body.add(DocumentUpload.box().addUploadListener((name, accepted, message) -> {
            // The server attaches an accepted document to this project's intake flow and publishes
            // the flow, so the list above refreshes itself; the sentence is the server's own.
            notice.set(name + " — " + message);
        }));

        body.add(pasteBox());

        footer.add(back(WHERE));
        footer.add(spacer());
        if (documents.isEmpty()) {
            footer.add(TextStyle.CAPTION.span(
                "Add at least one document, or paste some text, to carry on."));
            return;
        }
        // Pressing this on an analysis that is already running, already asking, or already finished
        // must not start it again. Coming back to this step to add a sixth document is ordinary, and
        // a button that silently re-read the other five would cost real money for nothing.
        GuidedFlow existing = current();
        boolean fresh = existing == null
            || existing.state() == GuidedFlowState.DRAFT
            || existing.state() == GuidedFlowState.FAILED;
        // The other way out of this step. Deliberately beside the ordinary button and not instead
        // of it, and deliberately quieter: the walk is still the recommended path, and this is the
        // one that hands seven decisions away. Pressing it opens a window that says which seven
        // before anything starts.
        Button alone = new Button("Or: build it all without asking me");
        alone.addClassName("btn-sm btn-ghost");
        alone.getElement().setAttribute("data-testid", "guide-autonomous");
        alone.getElement().setAttribute("title",
            "SwarmCoder decides everything it would normally ask you, and builds it. It shows you "
                + "what it will decide before it starts.");
        alone.addClickListener(e -> handOver(autonomy::open));
        footer.add(alone);
        footer.add(primary(fresh
            ? (documents.size() == 1
                ? "Read it, and show me what it finds"
                : "Read all " + documents.size() + ", and show me what they say")
            : "Carry on where it got to", () -> {
                GuidedFlow f = current();
                if (f == null) {
                    notice.set("The analysis is not ready yet — try that again in a moment.");
                    return;
                }
                if (fresh) {
                    call(() -> intake.start(f.id().toString()));
                }
                screen.set(DOC_READ);
            }));
    }

    /**
     * One attached document, with the two things the operator can say about it before it is read:
     * take it out, and what kind of document it is.
     *
     * <p>The kind used to be askable only on {@link IntakeWizard}'s document step, which on this
     * route is not reached until AFTER the analyst has been started — so on a new project the
     * documents had already been read, unclassified, by the time the tick existed. It had an effect
     * only on a re-run, which is the one path a new project never takes.
     */
    private Div documentRow(GuidedFlow flowNow, SourceDocument document) {
        Div row = new Div();
        row.addClassName("flex items-start gap-2 rounded-lg border border-base-300 px-3 py-2");
        row.add(Icon.of("file", "w-4 h-4 opacity-50 shrink-0 mt-1"));
        Div text = new Div();
        text.addClassName("flex-1 min-w-0");
        Div name = new Div(document.filename() == null ? "(unnamed)" : document.filename());
        name.addClassName("text-sm truncate");
        text.add(name, TextStyle.CAPTION.paragraph(document.byteSize() + " bytes, ready to read"));
        // The same question, in the same words, as the requirements screen asks — DocumentKind owns
        // both. Here it is asked BEFORE the button below starts the analyst, which is the whole
        // point of it being on this screen.
        Div kind = DocumentKind.tick(technicalIn(flowNow, document), "guide-doc-technical",
            value -> {
                GuidedFlow f = current();
                if (f != null) {
                    call(() -> intake.setDocumentTechnical(f.id().toString(),
                        document.id().toString(), value));
                }
            });
        kind.addClassName("mt-1");
        text.add(kind);
        row.add(text);
        Button remove = new Button("Take it out", e -> {
            GuidedFlow f = current();
            if (f != null) {
                call(() -> intake.removeDocument(f.id().toString(), document.id().toString()));
            }
        });
        remove.addClassName("btn-xs btn-ghost shrink-0");
        row.add(remove);
        return row;
    }

    /** Whether this flow already has this document marked as saying how the system must be built. */
    private static boolean technicalIn(GuidedFlow flowNow, SourceDocument document) {
        if (flowNow == null) {
            return false;
        }
        for (FlowDocument entry : flowNow.documents()) {
            if (document.id().equals(entry.documentId())) {
                return entry.technical();
            }
        }
        return false;
    }

    /**
     * Pasting, in the step rather than behind a second window.
     *
     * <p>A great deal of what a requirement starts from is an email or a page of notes that has no
     * file, and making somebody save it to disk just so it can be uploaded back is a step that earns
     * nothing. It is stored as a document like any upload.
     */
    private Div pasteBox() {
        Div box = new Div();
        box.addClassName("rounded-lg border border-base-300 px-4 py-3 flex flex-col gap-2");
        box.add(TextStyle.SECONDARY.paragraph(
            "No file? Paste the words straight in — an email, a chat, a page of notes."));
        TextField name = new TextField("call with finance, 12 Mar");
        name.addClassName("input-sm w-full");
        TextArea text = new TextArea("paste it here");
        text.addClassName("textarea textarea-bordered w-full h-28 text-xs");
        box.add(name.withLabel("Name it"), text.withLabel("The words"));
        Button add = new Button("Add this text", e -> {
            String words = text.getValue() == null ? "" : text.getValue();
            if (words.isBlank()) {
                notice.set("There is nothing to add yet — paste the words in first.");
                return;
            }
            GuidedFlow f = current();
            if (f == null) {
                notice.set("The analysis is not ready yet — try that again in a moment.");
                return;
            }
            String title = name.getValue() == null ? "" : name.getValue();
            call(() -> intake.addPastedDocument(f.id().toString(), title, words));
            text.setValue("");
        });
        add.addClassName("btn-sm btn-outline self-start");
        box.add(add);
        return box;
    }

    // --- step 3: the reading ------------------------------------------------------------------------

    /**
     * The step where the analyst works, and where the second and third words are explained — because
     * this is the screen on which the operator first meets the things they name.
     *
     * <p>The two states that need a person — a round of questions, and the list of proposed changes
     * — open {@link IntakeWizard} over this dialog. That surface is not duplicated here: it is
     * hundreds of lines of per-question discussion, notes, skipping and budgets, and a second copy
     * of it would be a second answer to the same question.
     */
    private void renderRead() {
        body.add(lead("SwarmCoder is reading what you gave it."));
        body.add(word("Requirement",
            "One thing that must be true about the finished software. \"A customer can reset their "
            + "own password.\" SwarmCoder pulls these out of your documents and shows you every "
            + "one — nothing is ever built from one you have not agreed."));
        body.add(word("Check",
            "A small, exact statement inside a requirement that a test can prove, such as "
            + "\"resetting sends exactly one email\". Checks are what make a requirement provable "
            + "instead of an opinion, and they are what a worker is measured against."));

        GuidedFlow f = current();
        FlowView view = flow.get();
        if (f == null) {
            body.add(note("The analysis is not ready yet. Go back a step and add a document."));
            footer.add(back(DOC_ADD));
            footer.add(spacer());
            return;
        }

        footer.add(back(DOC_ADD));
        footer.add(spacer());
        switch (f.state()) {
            case RUNNING -> body.add(progressNote(
                f.stepLabel() == null || f.stepLabel().isBlank()
                    ? "It is reading now. On a real document this takes a few minutes — you can "
                        + "close this window and come back to it."
                    : f.stepLabel() + ". You can close this window and come back to it."));
            case AWAITING_ANSWERS -> {
                int open = view == null ? 0 : view.openQuestions();
                body.add(progressNote("It has read them, and has "
                    + count(open, "question")
                    + " about what the documents left open. Answer what you can and skip the rest "
                    + "— a question you skip does not quietly become a requirement."));
                footer.add(primary("Answer its questions", () -> handOver(analysis::open)));
            }
            case REVIEW -> {
                int proposals = view == null ? 0 : view.proposals().size();
                body.add(progressNote("It has finished, and proposes "
                    + count(proposals, "change")
                    + ". Nothing is written until you have looked at them and said yes."));
                footer.add(primary("Look at what it found", () -> handOver(analysis::open)));
            }
            case APPLIED -> {
                body.add(doneNote("Done. This project now has "
                    + count(requirementsTotal(), "requirement") + "."));
                footer.add(primary("Next: agree what it found", () -> {
                    screen.set(DOC_AGREE);
                    refreshDrafts();
                }));
            }
            case FAILED -> {
                body.add(note("That run did not finish. "
                    + (f.error() == null ? "" : f.error())));
                footer.add(primary("Open the analysis and try again",
                    () -> handOver(analysis::open)));
            }
            default -> {
                body.add(progressNote("Nothing has been read yet. Press the button below and "
                    + "SwarmCoder starts on the documents you added."));
                footer.add(primary("Start reading",
                    () -> call(() -> intake.start(f.id().toString()))));
            }
        }
    }

    // --- step 4: agreeing ---------------------------------------------------------------------------

    /**
     * The gate that matters most, done here.
     *
     * <p>Agreeing is one call per requirement ({@code promoteRequirement}) or one for all of them
     * ({@code promoteAllDrafts}) — both already exist, because this is the step straight after an
     * intake and the BRD editor has always had to offer it. What was missing was anywhere to do it
     * that also said what it meant.
     *
     * <h2>Reading one, where it is agreed</h2>
     *
     * <p>This screen used to show a title, a check count and an Agree button, and then say in
     * prose: "to read one in full, open the Requirements workspace". That sentence was not a link,
     * and the screen it named is not in this window — so the one screen that calls itself the gate
     * that matters most asked the operator to agree seven requirements, and every check inside
     * them, with none of the words in front of them. The author hit exactly that on a real run.
     *
     * <p>So every row opens where it stands, with "Read it": the requirement's full wording and
     * every check it carries, underneath the row and above its Agree button. <b>Collapsed by
     * default</b> — seven requirements opened at once would push the buttons off the bottom of a
     * 58vh body and turn the step into a document, which is the other thing this guide is not.
     *
     * <p><b>Not a peek dialog</b>, though that is the pattern the pipeline board uses for a story.
     * A second window over this one does not work here and it was measured, not deduced: the
     * browser draws both dialogs in its top layer and the new one came out BEHIND the guide, dimmed
     * by the guide's own backdrop (see {@link #handOver} and DEVELOPER_CORRECTIONS §37.2). The
     * guide's answer to that is to step aside — and stepping aside to read one sentence is the trip
     * this whole step exists to save.
     *
     * <p><b>What is reused.</b> {@link BrdService#requirement(String)} is the server call written
     * for this exact moment — "one requirement in full … what opening a row fetches" — so the list
     * stays a list of lines and only an opened row costs anything. The fold-in-place gesture, and
     * the fresh-set rule that makes it survive signal dedup, are {@code RequirementTreeView}'s.
     * Nothing about reading a requirement is written twice: what is new here is only the shape of
     * the panel, which is the guide's own prose blocks rather than the 10px reference pane
     * {@code RequirementsReference} draws down the side of the planning board.
     */
    private void renderAgree() {
        body.add(lead("Agree what it found."));
        body.add(prose(
            "Nothing is built from a requirement you have not agreed, so this is the gate that "
            + "matters most. Agreeing one says \"this is what must be true\", and agrees its "
            + "checks with it. You can change your mind afterwards — agreeing is not a door that "
            + "locks."));

        int waiting = draftCount();
        List<RequirementRowDto> rows = drafts.get();
        if (waiting == 0) {
            body.add(doneNote("Nothing is waiting on you. All "
                + count(agreedCount(), "requirement") + " in this project "
                + (agreedCount() == 1 ? "is" : "are") + " agreed."));
        } else if (rows.isEmpty()) {
            body.add(progressNote(count(waiting, "requirement") + " waiting on you."));
        } else {
            Set<String> open = reading.get();
            Map<String, BrdRequirement> fetched = read.get();
            Div list = new Div();
            list.addClassName("flex flex-col gap-1.5 shrink-0");
            for (RequirementRowDto row : rows) {
                boolean isOpen = open.contains(row.getRequirementId());
                list.add(draftRow(row, isOpen,
                    isOpen ? fetched.get(row.getRequirementId()) : null));
            }
            body.add(list);
            body.add(TextStyle.CAPTION.paragraph(
                "Press \"Read it\" on any of these to see what it says, and every check that comes "
                + "with it, without leaving this window. Changing the wording of one, or setting "
                + "one aside, is done on the Requirements screen."));
        }

        footer.add(back(DOC_READ));
        footer.add(spacer());
        if (waiting > 0) {
            Button all = new Button("Agree all " + waiting, e -> {
                call(brd::promoteAllDrafts);
                refreshDrafts();
            });
            all.addClassName("btn-outline btn-sm");
            // Agreeing here is the same act as agreeing in the Requirements workspace, so it is
            // held on the same terms while the machine is doing it - see PilotGates.
            PilotGates.hold(all);
            footer.add(all);
        }
        if (PilotGates.handedOver()) {
            body.add(PilotGates.note());
        }
        footer.add(primary("Next: turn these into work", () -> screen.set(DOC_STORIES)));
    }

    private Div draftRow(RequirementRowDto row, boolean open, BrdRequirement full) {
        Div card = new Div();
        card.addClassName("rounded-lg border border-base-300");
        card.getElement().setAttribute("data-testid", "guide-draft-row");

        Div line = new Div();
        line.addClassName("flex items-center gap-2 px-3 py-2");
        Div text = new Div();
        text.addClassName("flex-1 min-w-0");
        Div title = new Div(row.getTitle() == null || row.getTitle().isBlank()
            ? "(no title)" : row.getTitle());
        title.addClassName("text-sm truncate");
        // Everything it carries, gating and not: on a draft every check is still only proposed, and
        // "no checks of its own yet" would be wrong about a requirement that has four.
        int checks = row.getOwn() == null ? 0 : row.getOwn().gating() + row.getOwn().getProposed();
        // A leftover rule is not waiting for checks and never will be. "no checks of its own yet"
        // under nine such rows reads as nine things left to do, and the "yet" is the untrue part.
        boolean rule = "CONSTRAINT".equals(row.getKind());
        text.add(title, TextStyle.CAPTION.paragraph(rule
            ? "a leftover rule — rules live in Guidelines now"
            : checks == 0 ? "no checks of its own yet" : count(checks, "check")));
        line.add(text);

        // Words, not a bare chevron. This is the control that answers "what am I agreeing?", and a
        // guide is the last place to hide it behind a symbol somebody has to try.
        Button reveal = new Button(open ? "Hide it" : "Read it", e -> toggleReading(row));
        reveal.addClassName("btn-ghost btn-xs shrink-0");
        reveal.getElement().setAttribute("data-testid", "guide-read-requirement");
        reveal.getElement().setAttribute("title", open
            ? "Fold this one away again"
            : "Show what this one says, and every check that comes with it");
        line.add(reveal);

        Button agree = new Button("Agree", e -> {
            call(() -> brd.promoteRequirement(row.getRequirementId()));
            refreshDrafts();
        });
        agree.addClassName("btn-xs btn-primary shrink-0");
        PilotGates.hold(agree);
        line.add(agree);
        card.add(line);

        if (open) {
            card.add(readingPanel(full));
        }
        return card;
    }

    /**
     * Opens or folds one draft, fetching its wording and checks the first time it is opened.
     *
     * <p>The call is made straight from the click handler, which is where every other service call
     * in this class is made — the framework permits one from a green-threaded DOM handler, and
     * {@link #refreshDrafts()} beside it does exactly the same thing.
     */
    private void toggleReading(RequirementRowDto row) {
        String id = row.getRequirementId();
        // A FRESH set, never the one in the signal: signals dedup by equals, and mutating the held
        // value in place means the change is compared against itself and swallowed.
        Set<String> next = new HashSet<>(reading.get());
        if (!next.remove(id)) {
            next.add(id);
            if (!read.get().containsKey(id)) {
                try {
                    BrdRequirement fetched = brd.requirement(id);
                    if (fetched != null) {
                        Map<String, BrdRequirement> got = new HashMap<>(read.get());
                        got.put(id, fetched);
                        read.set(got);
                    }
                } catch (Exception e) {
                    // Said out loud. A "Read it" that silently did nothing would be the same
                    // defect as the sentence it replaced: a promise with nothing behind it.
                    notice.set("Could not read that one: " + e);
                }
            }
        }
        reading.set(next);
    }

    /**
     * One requirement, in full, under the row that agrees it: what it says, and every check.
     *
     * <p>The checks are here because the screen above says agreeing a requirement agrees its checks
     * with it. That is true — {@code promoteRequirement} accepts every criterion the requirement
     * carries — so the checks are part of what is being agreed, and a screen that showed only how
     * MANY there were was asking for a decision about text it would not show.
     */
    private Div readingPanel(BrdRequirement full) {
        Div panel = new Div();
        panel.addClassName("border-t border-base-300 bg-base-200/50 px-3 py-2.5 "
            + "flex flex-col gap-1.5");
        panel.getElement().setAttribute("data-testid", "guide-requirement-text");
        if (full == null) {
            panel.add(TextStyle.CAPTION.paragraph("Fetching this one from the server."));
            return panel;
        }

        String statement = full.text();
        Div words = TextStyle.SECONDARY.paragraph(
            statement == null || statement.isBlank()
                ? "This one has no wording beyond its name."
                : statement);
        words.addClassName("break-words");
        panel.add(words);

        List<AcceptanceCriterion> criteria =
            full.criteria() == null ? List.of() : full.criteria();
        if (criteria.isEmpty()) {
            panel.add(TextStyle.CAPTION.paragraph(
                "No checks come with it, so nothing can ever prove it is done. Agreeing it is "
                + "still fine — checks can be added afterwards."));
            return panel;
        }
        panel.add(TextStyle.CAPTION.paragraph(
            count(criteria.size(), "check") + ", agreed along with it:"));
        for (AcceptanceCriterion criterion : criteria) {
            Div item = new Div();
            item.addClassName("flex items-start gap-2");
            Div dot = new Div();
            dot.addClassName("shrink-0 mt-1.5 w-1.5 h-1.5 rounded-full bg-primary/60");
            Div said = TextStyle.SECONDARY.paragraph(
                criterion.text() == null || criterion.text().isBlank()
                    ? "(no wording)" : criterion.text());
            said.addClassName("min-w-0 break-words");
            item.add(dot, said);
            panel.add(item);
        }
        return panel;
    }

    // --- step 5: the stories ------------------------------------------------------------------------

    /**
     * The step that plans the work — and now offers the thing that is actually available on it.
     *
     * <p>It used to end in "Next: build one", always, as the rightmost and most prominent button on
     * the screen. Three things were wrong with that and the author hit all three on one run.
     *
     * <ul>
     *   <li><b>It built nothing.</b> It moved the walk to the last step, which then explains that a
     *       build is started from a story's card on the board. A button that names an act it does
     *       not perform is the same defect as prose that names a link that is not there.</li>
     *   <li><b>It was offered with nothing to build.</b> The screen said "7 agreed requirements to
     *       plan against, and no work proposed yet" and still put "build one" under it.</li>
     *   <li><b>Two primaries.</b> With no stories, "Plan the work" and "Next: build one" were both
     *       coloured as the main action, and the one that led nowhere sat rightmost, where the eye
     *       lands.</li>
     * </ul>
     *
     * <p>So the step now carries ONE primary, and it is whatever is genuinely next (UX v3 §3.1,
     * "one primary action, quiet secondaries" — the grammar the pipeline board already follows):
     * with nothing agreed, go back and agree something; with nothing planned, plan it; with stories
     * on the board, move on, and planning more drops to a quiet outline button. There is a way
     * forward in every one of the three states, because a gate with no exit is itself a defect
     * (UX v3 §5 rule 5).
     */
    private void renderStories() {
        body.add(lead("Turn the agreed requirements into slices of work."));
        body.add(word("Story",
            "One slice of work. A story promises to deliver some named checks and nothing else, so "
            + "when it is finished there is no argument about whether it is finished. SwarmCoder "
            + "proposes them over the checks nothing delivers yet; you accept the ones you want "
            + "and set the rest aside, visibly and reversibly."));

        int stories = storyCount();
        if (agreedCount() == 0) {
            body.add(note("There is nothing to plan against yet — no requirement has been agreed. "
                + "Go back a step and agree at least one."));
        } else if (stories == 0) {
            body.add(progressNote(count(agreedCount(), "agreed requirement")
                + " to plan against, and no work proposed yet. Planning is what happens next: "
                + "SwarmCoder proposes the slices, you keep the ones you want, and each one you "
                + "keep becomes a card you can build."));
        } else {
            body.add(doneNote("This project already has " + count(stories, "story", "stories")
                + "."));
        }

        footer.add(back(DOC_AGREE));
        footer.add(spacer());
        if (agreedCount() == 0) {
            // Nothing has been agreed, so there is nothing to plan and nothing to build. The one
            // thing that moves this forward is the step before it.
            footer.add(primary("Go back and agree one", () -> {
                screen.set(DOC_AGREE);
                refreshDrafts();
            }));
            return;
        }
        if (stories == 0) {
            // Nothing is planned yet, so planning is the only thing worth pressing. Moving on to a
            // step about building would be moving on to a step with nothing to build.
            footer.add(primary("Plan the work", () -> handOver(planner::open)));
            return;
        }
        footer.add(secondary("Plan more work", () -> handOver(planner::open)));
        // Named for what the NEXT STEP is about, not for an act this click performs: the last step
        // explains a build and then puts the operator on the board, where the button that spends
        // the money lives beside the story it is about (UX v3 §2.3).
        footer.add(primary("Next: how to start a build", () -> screen.set(DOC_BUILD)));
    }

    // --- step 6: the build --------------------------------------------------------------------------

    /**
     * The one step that genuinely ends somewhere else, and it hands over FORWARD.
     *
     * <p>A build is started from the card of the story it is about, on the pipeline board — that is
     * UX v3 §2.3 and it is right: the confirmation that money is about to be spent belongs beside
     * the thing being spent on. So this step explains what is about to happen and then puts the
     * operator on the board with the stories in front of them, rather than leaving them on a screen
     * with no idea what to do next.
     */
    private void renderBuild() {
        body.add(lead("Build one, and judge what comes back."));
        body.add(word("Build",
            "One attempt at one story. Inside a build several workers each write their own version "
            + "of the change, and every version is put through the story's checks. You are shown "
            + "the one that passed. You can open a build and watch it, but you never have to."));
        body.add(point("You are asked twice, and only twice.",
            "Once to say a piece of work is worth doing — that was the step before this one. Once "
            + "at the end, to say it did what you wanted. Everything in between happens without "
            + "you: the design, the tests, the workers, and picking the version that passed."));

        if (!hasRepository()) {
            body.add(note("This project has no git repository yet, so nothing can be built. Each "
                + "worker branches from your code into a copy of its own, and there is nothing to "
                + "branch from. Point the project at a folder that has had git init run in it."));
        }
        body.add(prose(
            "On the board every story sits in one of five columns and only ever moves sideways, so "
            + "nothing you act on disappears. Press \"Build this\" on a story under \"Ready to "
            + "build\". Watch it, or go and do something else — it arrives under \"Came back\" "
            + "when it wants your verdict."));

        footer.add(back(DOC_STORIES));
        footer.add(spacer());
        footer.add(primary("Open the board and build one", () -> {
            dialog.close();
            Nav.openBacklogBoard.run();
        }));
    }

    // --- the short route ----------------------------------------------------------------------------

    private void renderRouteChange() {
        body.add(lead("Starting from one small change"));
        body.add(prose(
            "The short way in. No documents, no list of requirements, no planning — you point at "
            + "the folder and say what you want, in the chat."));

        body.add(step(1, "A project, pointed at your folder.",
            hasProject()
                ? "Done — you are in " + projectName() + "."
                : "Use the name at the top left of the window to make one. The folder has to be a "
                    + "git repository.",
            hasProject()));
        body.add(step(2, "Let SwarmCoder work out how to build and test it.",
            hasRepository()
                ? "The project's own settings have a section asking how the project is built. "
                    + "Press the button there. SwarmCoder looks at the folder, works out the "
                    + "commands, and runs the build once to check it was right."
                : "This comes with the project. SwarmCoder looks at the folder, works out how it "
                    + "is built and tested, and runs the build once to prove it got it right.",
            false));
        body.add(note(
            "Read what it found before you save it. If the build command is wrong, every attempt "
            + "fails for the same wrong reason and none of them is really about your change. If "
            + "the build takes minutes, narrow it to the part of the project you are changing — "
            + "every worker pays that time, and pays it again each time it fixes something."));
        body.add(step(3, "Say what you want, in the chat.",
            "Type /bugfix and then what is wrong, in a sentence or two. For a small addition, "
            + "type /run and then what you want added. The button below opens the chat for you.",
            false));
        body.add(step(4, "Watch it, or come back later.",
            "SwarmCoder designs the change, writes tests that fail against the code as it stands "
            + "today, sets workers on it, keeps the version that passed and puts it on a branch "
            + "for you.", false));

        body.add(heading("Say something concrete"));
        body.add(prose(
            "\"Add a percent method that returns 30 for 200 and 15\" works. \"Make the reports "
            + "better\" does not. On this route there is no written-down list behind you for "
            + "SwarmCoder to sharpen a vague goal against, so a vague goal produces a vague "
            + "design. If the project is large, it helps to name the part of it you mean."));

        footer.add(back(WHERE));
        footer.add(spacer());
        footer.add(primary("Open the chat and say it", () -> {
            dialog.close();
            ChatDock.setOpen(true);
        }));
    }

    // --- the route that is not built ------------------------------------------------------------------

    private void renderRouteExisting() {
        body.add(lead("Starting from code that already exists"));
        body.add(notAvailable(
            "This one is not available yet. It is designed and worked out in full, and it is not "
            + "written. Nothing in the window does this today."));

        body.add(heading("What it will do"));
        body.add(prose(
            "Point it at a project that has been running for years. It reads the documents already "
            + "in the folder, works out what the project was meant to do, and then goes looking "
            + "for evidence: for each thing that must be true, is there a test that ran, passed, "
            + "and really proves it?"));
        body.add(prose(
            "What comes back is a list of what the project can prove about itself, and a list of "
            + "what it cannot. The second list is usually the useful one."));

        body.add(heading("Two things about it are already decided"));
        body.add(point("It will only call something built when a test proves it.",
            "A model saying \"this looks like it is implemented, probably by this code\" is shown "
            + "as a suggestion for you to confirm. It never counts as evidence."));
        point(body, "It will never hand you a percentage.",
            "On a well tested, well named project about a third of what is written down can be "
            + "proved. A single number over that would be a number nobody could stand behind.");

        body.add(heading("What to do in the meantime"));
        body.add(prose(
            "Both of the other two work perfectly well on a project that already exists. If you "
            + "want a change made today, take the small-change route — it reads your code, it "
            + "just does not write down what the whole project is for first. If you want the "
            + "written-down list, write down the part you care about and take the first route; "
            + "requirements can be added at any time, for the life of the project."));

        footer.add(back(WHERE));
        footer.add(spacer());
        footer.add(secondary("Take the small-change route instead",
            () -> screen.set(ROUTE_CHANGE)));
    }

    // --- talking to the server ------------------------------------------------------------------------

    /** One service call, with whatever it returns or throws put in front of the operator. */
    private interface Call {
        String run();
    }

    /**
     * Runs a service call and reports it.
     *
     * <p>The throw case is the reason this exists, and it is the same lesson {@link IntakeWizard}
     * records: calling a stub inline means an RMI failure escapes the click handler and the button
     * simply does nothing — no message, and nothing in the server log because the call never
     * arrived. A button that can fail silently is worse than one that is missing.
     *
     * <p>MUST be called from a DOM event handler or a green thread, never from a signal effect.
     */
    private void call(Call callable) {
        try {
            String result = callable.run();
            notice.set(result == null || result.isEmpty() ? "" : result);
        } catch (Exception e) {
            notice.set("That did not reach the server: " + e);
        }
    }

    /**
     * Fetches (and creates, on first use) this project's intake flow.
     *
     * <p>Only ever from a click handler. A server call inside a signal effect throws "suspension
     * point reached from non-threading context" — the framework only permits calls from the
     * green-threaded handlers.
     */
    private void loadFlow() {
        try {
            FlowView view = intake.intake();
            if (view != null) {
                flow.set(view);
            }
        } catch (Exception e) {
            notice.set("Could not open the analysis: " + e);
        }
    }

    /** Re-reads the requirements nobody has agreed. Same threading rule as {@link #loadFlow()}. */
    private void refreshDrafts() {
        RequirementQuery query = new RequirementQuery();
        query.setStatusCsv("DRAFT");
        try {
            RequirementPageDto page = brd.rows(query, 0, 25);
            List<RequirementRowDto> waiting = new ArrayList<>();
            if (page != null && page.getRows() != null) {
                for (RequirementRowDto row : page.getRows()) {
                    // A match arrives with its ancestors, marked contextOnly so a filter can never
                    // orphan a result. Those ancestors are not drafts, and offering to agree one
                    // that is already agreed is worse than not listing it: the list said two and the
                    // button beside it said "Agree all 1".
                    if (!row.isContextOnly()) {
                        waiting.add(row);
                    }
                }
            }
            drafts.set(waiting);
        } catch (Exception e) {
            // Not worth interrupting the operator over: the count above the list comes from
            // readiness, and "Agree all" works whether or not the list could be fetched.
            drafts.set(new ArrayList<>());
        }
    }

    // --- readiness, read and never recomputed ------------------------------------------------------

    private static ConsoleReadiness readiness() {
        return ReadinessSignals.CURRENT.get();
    }

    private static boolean hasProject() {
        ConsoleReadiness value = readiness();
        return value != null && value.hasProject();
    }

    private static boolean hasRepository() {
        ConsoleReadiness value = readiness();
        return value != null && value.hasRepository();
    }

    private static int agreedCount() {
        ConsoleReadiness value = readiness();
        return value == null ? 0 : value.requirementsAgreed();
    }

    private static int draftCount() {
        ConsoleReadiness value = readiness();
        return value == null ? 0 : value.requirementsDrafts();
    }

    private static int requirementsTotal() {
        return agreedCount() + draftCount();
    }

    private static int storyCount() {
        Backlog backlog = BacklogSignals.CURRENT.get();
        return backlog == null ? 0 : backlog.stories().size();
    }

    private static String projectName() {
        ConsoleReadiness value = readiness();
        String name = value == null ? null : value.projectName();
        return name == null || name.isBlank() ? "this project" : name;
    }

    private GuidedFlow current() {
        FlowView view = flow.get();
        return view == null ? null : view.flow();
    }

    private static String count(int number, String noun) {
        return count(number, noun, noun + "s");
    }

    private static String count(int number, String one, String many) {
        return number + " " + (number == 1 ? one : many);
    }

    // --- the pieces every screen is built out of ---------------------------------------------------

    /** The one sentence a screen is about. */
    private static Div lead(String text) {
        Div div = new Div(text);
        div.addClassName("text-base font-semibold leading-snug");
        return div;
    }

    /** Ordinary prose. */
    private static Div prose(String text) {
        return TextStyle.SECONDARY.paragraph(text);
    }

    /** The heading over a group. */
    private static Div heading(String text) {
        Div div = new Div(text);
        div.addClassName("text-sm font-semibold mt-4");
        return div;
    }

    /** A bolded lead-in and an explanation, as one bulleted point. */
    private static Div point(String bold, String rest) {
        Div row = new Div();
        row.addClassName("flex items-start gap-2");
        Div dot = new Div();
        dot.addClassName("shrink-0 mt-2 w-1.5 h-1.5 rounded-full bg-primary/60");
        Div text = new Div();
        text.addClassName("min-w-0");
        Span strong = new Span(bold + " ");
        strong.addClassName("text-sm font-medium");
        Span body = TextStyle.SECONDARY.span(rest);
        text.add(strong, body);
        row.add(dot, text);
        return row;
    }

    private static void point(Div into, String bold, String rest) {
        into.add(point(bold, rest));
    }

    /**
     * One of the four words, explained on the step that uses it.
     *
     * <p>Tinted, unlike everything else on a step, because it is the one block that is explanation
     * rather than instruction: it can be skipped by somebody on their second project, and it has to
     * look skippable.
     */
    private static Div word(String name, String meaning) {
        Div card = new Div();
        card.addClassName("rounded-lg border border-base-300 bg-base-200/50 px-4 py-3 "
            + "flex flex-col gap-1");
        Div title = new Div(name);
        title.addClassName("text-sm font-semibold");
        card.add(title, TextStyle.SECONDARY.paragraph(meaning));
        return card;
    }

    /**
     * One starting point, as a card you press.
     *
     * <p>The card that is not available yet is still a card and is still pressable — it opens the
     * page saying what it will do and what to use instead. A control that looks disabled and says
     * nothing is the thing this whole dialog exists to stop.
     */
    private static Div route(String title, String what, String fit, String cost,
                             boolean available, Runnable open) {
        Div card = new Div();
        card.addClassName("rounded-lg border px-4 py-3 flex flex-col gap-1.5 cursor-pointer "
            + (available
                ? "border-base-300 hover:border-primary/60 hover:bg-primary/5"
                : "border-base-300 border-dashed hover:bg-base-200/60"));

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        Div name = new Div(title);
        name.addClassName("text-sm font-semibold flex-1 min-w-0");
        top.add(name);
        if (!available) {
            Span mark = new Span("Not available yet");
            mark.addClassName("shrink-0 rounded px-1.5 py-0.5 bg-base-300/70");
            TextStyle.CAPTION.applyTo(mark, Emphasis.FULL);
            top.add(mark);
        }
        top.add(Icon.of("chevron-right", "w-4 h-4 opacity-40 shrink-0"));
        card.add(top);

        card.add(TextStyle.SECONDARY.paragraph(what));
        card.add(TextStyle.CAPTION.paragraph(fit + " " + cost));

        card.addDomEventListener("click", e -> open.run());
        return card;
    }

    /** One numbered step of a route, ticked when the server says it is already done. */
    private static Div step(int number, String title, String detail, boolean done) {
        Div row = new Div();
        row.addClassName("flex items-start gap-3 rounded-lg border border-base-300 px-4 py-3");

        Div marker = new Div();
        marker.addClassName("shrink-0 mt-0.5 "
            + (done ? "text-success" : "text-base-content/70"));
        if (done) {
            marker.add(Icon.of("check", "w-4 h-4"));
        } else {
            Span index = new Span(String.valueOf(number));
            index.addClassName("text-xs font-semibold");
            marker.add(index);
        }
        row.add(marker);

        Div text = new Div();
        text.addClassName("flex flex-col gap-1 min-w-0");
        Div name = new Div(title);
        name.addClassName("text-sm font-medium");
        Div body = TextStyle.CAPTION.paragraph(detail);
        body.addClassName("break-words");
        text.add(name, body);
        row.add(text);
        return row;
    }

    /** A tinted strip of prose the operator has to read before pressing the thing below it. */
    private static Div note(String text) {
        Div box = new Div();
        box.addClassName("rounded-lg border border-warning/40 bg-warning/10 px-4 py-3");
        box.add(TextStyle.CAPTION.paragraph(text, Emphasis.FULL));
        return box;
    }

    /** Where the work has got to, in the server's own terms. */
    private static Div progressNote(String text) {
        Div box = new Div();
        box.addClassName("rounded-lg border border-primary/40 bg-primary/10 px-4 py-3");
        box.add(TextStyle.SECONDARY.paragraph(text, Emphasis.FULL));
        return box;
    }

    /** A step that is finished, said as a fact rather than as a tick nobody can read. */
    private static Div doneNote(String text) {
        Div box = new Div();
        box.addClassName("rounded-lg border border-success/40 bg-success/10 px-4 py-3 "
            + "flex items-start gap-2");
        box.add(Icon.of("check", "w-4 h-4 text-success shrink-0 mt-0.5"));
        box.add(TextStyle.SECONDARY.paragraph(text, Emphasis.FULL));
        return box;
    }

    /** Said plainly, at the top of the route that is not built. */
    private static Div notAvailable(String text) {
        Div box = new Div();
        box.addClassName("rounded-lg border border-base-300 border-dashed bg-base-200/50 "
            + "px-4 py-3");
        box.add(TextStyle.SECONDARY.paragraph(text, Emphasis.FULL));
        return box;
    }

    private static Div spacer() {
        Div div = new Div();
        div.addClassName("flex-1");
        return div;
    }

    private Button back(String to) {
        Button button = new Button("Back", e -> screen.set(to));
        button.addClassName("btn-ghost btn-sm");
        return button;
    }

    /**
     * The one button that carries the walk forward, whatever it says on a given step.
     *
     * <p>Named for the tests, because its words change on nearly every screen — "Read it",
     * "Answer its questions", "Next: agree what it found" — and a browser test that had to select
     * on prose would be asserting the copy rather than the walk. Only ever one on a screen.
     */
    private static Button primary(String label, Runnable action) {
        Button button = new Button(label, e -> action.run());
        button.addClassName("btn-primary btn-sm");
        button.getElement().setAttribute("data-testid", "guide-next");
        return button;
    }

    private static Button secondary(String label, Runnable action) {
        Button button = new Button(label, e -> action.run());
        button.addClassName("btn-outline btn-sm");
        return button;
    }
}
