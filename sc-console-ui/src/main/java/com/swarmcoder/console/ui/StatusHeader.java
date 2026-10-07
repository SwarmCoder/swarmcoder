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
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.NextAction;
import com.swarmcoder.console.api.ReadinessSignals;
import com.swarmcoder.console.api.RunSummaryDto;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The whole of the Console's navigation: what project you are in, how much is in it, whether the
 * machinery is up, the single next step, and two workspaces to choose between (UX v3 §3).
 *
 * <p>It replaces a four-step stepper and up to three per-stage guidance bars. Both are deleted for the
 * same reason: they were answering questions with structure the process does not have. The stepper drew
 * a numbered progress front across Setup · Requirements · Plan · Build, but requirements get refined
 * while earlier ones are already being built — so the bar could either lie about doneness or refuse to
 * tick anything off, and it did both in turn. The per-stage bars then multiplied the guidance by the
 * number of stages, which meant the operator's answer to "what do I do" depended on where they happened
 * to be standing.
 *
 * <p>So: <b>one line, one step, one button</b>, computed once by the server, gone when there is nothing
 * to say. Persistent guidance that cannot fall silent is nagging, and an operator who has learned that
 * the strip always says something has learned to stop reading it.
 *
 * <h2>The rows</h2>
 *
 * <p>Above all of them, and only while it is true, is the strip saying a machine is deciding things
 * on the operator's behalf ({@link AutonomousBanner}). It is the one deliberately loud thing in a
 * header whose whole design is quiet, and it is a ROW rather than a window because the window it
 * replaces covered the application it was reporting on.
 *
 * <ol>
 *   <li><b>Identity and counts.</b> Which project (a menu, not a permanent rail — §8.4), what is in it,
 *       and the health dot. The counts are the reason this row exists: "12 agreed · 4 drafts" and
 *       "2 building · 1 needs you" are what an operator wants on arriving, and neither was anywhere
 *       before.</li>
 *   <li><b>The one guidance line.</b> Hidden entirely when the server has nothing to say.</li>
 *   <li><b>The two workspaces</b>, plus the ⋯ overflow. No numbers, no connectors, no progress front:
 *       process position is visible on the pipeline board, so navigation stopped having to imply it.
 * </ol>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The pipeline counts come from {@link PipelineBoard#counts}, which is the same derivation the cards
 * render. That is not tidiness: a header counting "needs you" its own way would eventually disagree
 * with the badges below it, which is the "building now" here / "stopped" there defect moved up a level.
 * The requirement counts are a cardinality of a list the client already holds and draws.
 *
 * <p>Bound to three process-wide shared signals, so callers MUST {@link #dispose()} this.
 */
final class StatusHeader extends Div implements Disposable {

    private final List<Disposable> disposables = new ArrayList<>();

    private final Div counts = new Div();
    private final Div guidance = new Div();
    private final Div tabs = new Div();
    private final Div healthDot = new Div();
    private final Span projectName = new Span("No project");
    /**
     * "Something is deciding things for you", across the top of every screen, with the way to stop
     * it and the way back to what it decided.
     *
     * <p>It lives HERE rather than on the pipeline board because the header is the one thing on the
     * screen whatever the operator is doing, and the whole point of the change is that autonomous
     * running no longer has to be watched from inside a window that covers the application. It is
     * the FIRST row, above the identity line, and it draws nothing at all while nothing is running
     * - see {@link AutonomousBanner}.
     */
    private final AutonomousBanner autonomous = new AutonomousBanner();

    /** Opens a surface. Supplied by the shell, because only it can build one. */
    private final Consumer<Stage> open;
    /** Opens the project menu, the health panel and the overflow — all owned by the shell. */
    private final Runnable openProjects;
    private final Runnable openHealth;
    private final Runnable openOverflow;
    private final Runnable newChat;

    StatusHeader(Consumer<Stage> open, Runnable openProjects, Runnable openHealth,
                 Runnable openOverflow, Runnable newChat) {
        this.open = open;
        this.openProjects = openProjects;
        this.openHealth = openHealth;
        this.openOverflow = openOverflow;
        this.newChat = newChat;

        // bg-base-100: the header is chrome that sits ON the page, so it takes the lifted
        // surface. It was the same colour as the page, and told apart from it only by a
        // border two points of lightness away — see the ramp in index.html.
        addClassName("shrink-0 flex flex-col bg-base-100 border-b border-base-300");
        getElement().setAttribute("data-testid", "status-header");
        // First, above everything, and full width. Nothing else in this header is allowed to be
        // loud; this one has to be, and it is absent whenever it would have nothing to say.
        add(autonomous);
        add(identityRow());
        add(guidanceRow());
        add(workspaceRow());

        disposables.add(Effect.create(this::renderCounts));
        disposables.add(Effect.create(this::renderGuidance));
        disposables.add(Effect.create(this::renderTabs));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
        autonomous.dispose();
    }

    // --- row 1: who, how much, and is it up -----------------------------------------------------

    private Div identityRow() {
        Div row = new Div();
        row.addClassName("flex items-center gap-3 px-4 h-11");

        Div logo = new Div();
        logo.addClassName("flex items-center gap-2 shrink-0");
        logo.add(Icon.of("bolt", "w-4 h-4 text-primary"));
        Span title = new Span("SwarmCoder");
        title.addClassName("font-bold text-sm");
        logo.getElement().appendChild(title.getElement());
        row.add(logo);

        // The project switcher: a chip that opens a menu. It was a permanent 16rem rail down the left
        // of every screen — which is a lot of width for a fact that changes a handful of times a day
        // (§8.4). The name still has to be VISIBLE at all times, because everything in the Console is
        // scoped to it and a Console that does not say which project you are ruining is dangerous.
        Div project = new Div();
        project.addClassName("flex items-center gap-1.5 px-2 h-7 rounded-lg cursor-pointer text-xs "
            + "border border-base-300 hover:bg-base-300/60 shrink-0");
        project.getElement().setAttribute("data-testid", "project-menu");
        project.getElement().setAttribute("title", "Switch project, or create one");
        projectName.addClassName("font-semibold max-w-[14rem] truncate");
        project.getElement().appendChild(projectName.getElement());
        project.add(Icon.of("chevron-down", "w-3 h-3 opacity-50"));
        project.addDomEventListener("click", e -> openProjects.run());
        row.add(project);

        counts.addClassName("flex items-center gap-x-4 gap-y-0.5 flex-wrap min-w-0 "
            + "text-[11px] text-base-content/55");
        counts.getElement().setAttribute("data-testid", "header-counts");
        row.add(counts);

        Div spacer = new Div();
        spacer.addClassName("flex-1");
        row.add(spacer);

        // Setup, demoted to a dot (§3.3). Green means there is nothing in there to do, which is the
        // only honest way to have a configuration surface that nobody has to visit.
        healthDot.addClassName("flex items-center gap-1.5 px-2 h-7 rounded-lg cursor-pointer text-xs "
            + "hover:bg-base-300/60 shrink-0");
        healthDot.getElement().setAttribute("data-testid", "health-dot");
        healthDot.addDomEventListener("click", e -> openHealth.run());
        row.add(healthDot);
        return row;
    }

    private void renderCounts() {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        Backlog backlog = BacklogSignals.CURRENT.get();
        List<RunSummaryDto> runs = RunsStore.runs.get();

        // The name of the project the machinery is pointing at is NOT the same as a project being
        // open. After a deletion the machinery falls back to whatever is left so that nothing
        // dangles, and the header used to announce that fallback as though the operator were in
        // it — a full-looking console naming a project they never chose, moments after destroying
        // one. While the registry says nothing is chosen, this says nothing is open.
        com.swarmcoder.console.api.ProjectList registry =
            com.swarmcoder.console.api.ProjectSignals.CURRENT.get();
        boolean nothingOpen = registry != null && registry.isPublished() && !registry.isChosen();
        projectName.setText(nothingOpen ? "No project open"
            : readiness == null || readiness.projectName() == null
                || readiness.projectName().isBlank() ? "No project" : readiness.projectName());

        counts.getElement().setInnerHTML("");
        boolean hasProject = readiness != null && readiness.hasProject();
        if (!hasProject) {
            // Nothing is scoped to anything yet, so every count would be a zero about nothing.
            counts.add(quiet("Create a project and everything else is scoped to it."));
            renderHealth(readiness);
            return;
        }

        // From the server, NOT counted here. The sentence in the row below is judged from the
        // server's own read of the store, so counting the graph client-side made two sources for one
        // fact — and they drifted the first time one signal was republished without the other: the
        // header read "0 agreed · 1 draft" above a line saying "the agreed requirements have no
        // stories yet". See ConsoleReadiness.requirementsAgreed.
        int agreed = readiness.requirementsAgreed();
        int drafts = readiness.requirementsDrafts();
        PipelineBoard.Counts pipeline = PipelineBoard.counts(backlog, runs);

        counts.add(group("Requirements", Stage.REQUIREMENTS,
            agreed + " agreed", drafts == 0 ? null : drafts + " " + (drafts == 1 ? "draft" : "drafts"),
            drafts > 0));
        counts.add(group("Pipeline", Stage.PLAN,
            pipeline.building() + " building",
            pipeline.needsYou() == 0 ? null : pipeline.needsYou() + " needs you",
            pipeline.needsYou() > 0));
        renderHealth(readiness);
    }

    /**
     * One count group: the surface's name, the neutral number, and the one that means somebody is
     * owed something — emphasised only when it is not zero.
     *
     * <p>Clickable, because a number the operator has just read is the most likely reason they are
     * about to navigate, and making them find the tab afterwards is a step for nothing.
     */
    private Div group(String label, Stage destination, String neutral, String attention,
                      boolean emphasise) {
        Div box = new Div();
        box.addClassName("flex items-baseline gap-1.5 cursor-pointer hover:text-primary");
        box.getElement().setAttribute("data-testid", "count-" + destination.id());
        box.addDomEventListener("click", e -> open.accept(destination));
        Span name = new Span(label + ":");
        name.addClassName("text-base-content/40");
        box.getElement().appendChild(name.getElement());
        Span first = new Span(neutral);
        box.getElement().appendChild(first.getElement());
        if (attention != null) {
            Span dot = new Span("·");
            dot.addClassName("text-base-content/25");
            box.getElement().appendChild(dot.getElement());
            Span second = new Span(attention);
            second.addClassName(emphasise ? "text-warning font-semibold" : "");
            box.getElement().appendChild(second.getElement());
        }
        return box;
    }

    private void renderHealth(ConsoleReadiness readiness) {
        healthDot.removeAll();
        boolean ok = readiness != null && readiness.hasProject() && readiness.canRun();
        Div dot = new Div();
        dot.addClassName("w-2 h-2 rounded-full shrink-0 " + (ok ? "bg-success" : "bg-error"));
        healthDot.add(dot);
        Span text = new Span(ok ? "ready" : "setup");
        text.addClassName(ok ? "text-base-content/50" : "text-error font-semibold");
        healthDot.getElement().appendChild(text.getElement());
        // The sentence goes in the tooltip and in the panel, not on the bar: a blocker is a paragraph,
        // and a paragraph in a 44px row either truncates or pushes the counts off the screen.
        String why = readiness == null ? null : readiness.runBlocker();
        healthDot.getElement().setAttribute("title", ok
            ? "Project, repository and somewhere to run are all in place."
            : (why == null || why.isBlank()
                ? "Something is not configured yet — open Setup." : why));
    }

    // --- row 2: the one guidance line -----------------------------------------------------------

    private Div guidanceRow() {
        guidance.addClassName("flex flex-wrap items-center gap-x-2 gap-y-1 px-4 py-1.5 "
            + "border-t border-base-300 bg-base-200/40");
        guidance.getElement().setAttribute("data-testid", "guidance");
        guidance.setVisible(false);
        return guidance;
    }

    /**
     * The single next step, in the server's own words.
     *
     * <p>Reads {@link ConsoleReadiness#nextAction()} — the GLOBAL recommendation, which the server
     * already computes as "the furthest place with work". That is the retargeting §9 step 3 asks for:
     * the per-stage {@code StageGuidance} machinery stays and still feeds the tab badges, but the
     * sentence the operator reads is now one sentence rather than one per stage.
     *
     * <p>Calm on purpose: no error colour, no ring, no warning glyph. This is help, and the visual
     * weight of an alert on a permanent element is a lie about urgency.
     */
    private void renderGuidance() {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        Stage showing = Stages.SELECTED.get();
        guidance.getElement().setInnerHTML("");
        if (readiness == null || !readiness.hasNextAction()) {
            guidance.setVisible(false);
            guidance.getElement().setAttribute("data-action", "");
            return;
        }
        guidance.setVisible(true);
        NextAction action = readiness.nextAction();
        guidance.getElement().setAttribute("data-action", action.key());

        // The one thing this line must never do is send the operator to a button the machine has
        // taken. While autonomous running owns a step, the line says so instead of asking for it -
        // otherwise the header advises an action and the card below it greys out the very button
        // that would do it. The other steps are still his and still said, because the pilot does
        // not answer a stopped build and does not judge what came back.
        if (PilotGates.handedOver() && Stages.doneByThePilot(action)) {
            Div mark = new Div();
            mark.addClassName("shrink-0 text-warning");
            mark.add(Icon.of("info", "w-3.5 h-3.5"));
            guidance.add(mark);
            Span said = new Span("SwarmCoder is doing this itself while it is building on its own. "
                + "Stop it on the yellow strip above to take this step back.");
            said.addClassName("text-xs leading-relaxed text-base-content/70");
            guidance.getElement().appendChild(said.getElement());
            return;
        }

        Div mark = new Div();
        mark.addClassName("shrink-0 text-base-content/35");
        mark.add(Icon.of("info", "w-3.5 h-3.5"));
        guidance.add(mark);

        Span next = new Span("Next:");
        next.addClassName("text-[11px] uppercase tracking-wider text-base-content/35 shrink-0");
        guidance.getElement().appendChild(next.getElement());

        Span text = new Span(readiness.nextActionText());
        text.addClassName("text-xs leading-relaxed text-base-content/70");
        guidance.getElement().appendChild(text.getElement());

        String label = Stages.destinationLabel(action, showing);
        if (label != null) {
            Div button = new Div(label);
            button.addClassName("shrink-0 text-xs px-2 py-0.5 rounded border border-base-300 "
                + "cursor-pointer text-primary hover:bg-base-300/60");
            // A DOM handler IS a threading context, which is what makes it safe for the surface being
            // opened to construct views whose constructors talk to the server.
            button.addDomEventListener("click",
                e -> open.accept(Stages.destinationOf(action)));
            guidance.add(button);
        }
    }

    // --- row 3: the two workspaces --------------------------------------------------------------

    private Div workspaceRow() {
        Div row = new Div();
        row.addClassName("flex items-center gap-2 px-3 pt-1 border-t border-base-300");
        tabs.addClassName("flex items-center gap-1");
        // Kept as the id the smoke test has always used for "the shell's navigation has rendered".
        tabs.getElement().setAttribute("data-testid", "stage-bar");
        row.add(tabs);
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        row.add(spacer);
        // The way into the guide, in words, where a person looks for help: the top right of the
        // window, beside the other things that belong to the whole Console rather than to one
        // workspace. It is NOT a third workspace — it opens a dialog and the two-workspace rule
        // (UX v3 §3) is untouched — and it is not an icon, because the reason it had to be added is
        // that its only route in was an unlabelled coloured mark that nobody would ever click
        // looking for a guide.
        Div help = new Div();
        help.addClassName("flex items-center gap-1.5 px-2 h-7 mb-1 rounded-lg cursor-pointer "
            + "text-xs whitespace-nowrap border border-base-300 shrink-0 "
            + "text-base-content/70 hover:text-primary hover:bg-base-300/60");
        help.getElement().setAttribute("data-testid", "open-guide");
        help.getElement().setAttribute("title",
            "A step-by-step walk through a first piece of work, done in the window with you");
        help.add(Icon.of("book", "w-3.5 h-3.5 opacity-70"));
        Span helpLabel = new Span("Getting started");
        help.getElement().appendChild(helpLabel.getElement());
        help.addDomEventListener("click", e -> Nav.openGuide.run());
        row.add(help);
        // The companions, which belong to the shell rather than to either workspace: you ask what the
        // codebase does while reading requirements AND while a build is going wrong.
        row.add(iconButton("plus", "New chat", newChat));
        row.add(iconButton("chat", "Toggle chat", ChatDock::toggle));
        row.add(iconButton("search", "Toggle inspector",
            () -> Inspector.visible.set(!Boolean.TRUE.equals(Inspector.visible.get()))));
        Div more = new Div();
        more.addClassName("flex items-center justify-center w-7 h-7 mb-1 rounded-lg cursor-pointer "
            + "text-base-content/50 hover:text-primary hover:bg-base-300/60");
        more.setText("⋯");
        more.getElement().setAttribute("data-testid", "overflow-menu");
        more.getElement().setAttribute("title", "Guidelines, insights, settings and developer tools");
        more.addDomEventListener("click", e -> openOverflow.run());
        row.add(more);
        return row;
    }

    private void renderTabs() {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        Stage selected = Stages.SELECTED.get();
        tabs.getElement().setInnerHTML("");
        for (Stage stage : Stage.values()) {
            if (stage.workspace()) {
                tabs.add(tab(stage, stage == selected, Stages.attention(stage, readiness)));
            }
        }
    }

    /**
     * One workspace tab.
     *
     * <p>Two facts and no more: whether you are on it, and how much in it is waiting on you. The
     * stepper carried four — selected, recommended, done, and a step number — on chips that looked
     * nearly identical, and nothing on screen said the marks meant different things. The
     * recommendation is a sentence in the row above now, where it can explain itself.
     */
    private Div tab(Stage stage, boolean selected, int waiting) {
        Div tab = new Div();
        tab.setClassName("flex items-center gap-1.5 px-3 py-1.5 rounded-t-lg cursor-pointer text-sm "
            + "whitespace-nowrap select-none border-b-2 "
            + (selected
                ? "bg-base-100 border-primary font-semibold text-base-content"
                : "border-transparent text-base-content/60 hover:bg-base-300/50 hover:text-base-content"));
        // The tests select on these; a label is prose and the surface is the contract.
        tab.getElement().setAttribute("data-testid", "stage-" + stage.id());
        tab.getElement().setAttribute("data-selected", selected ? "true" : "false");
        tab.getElement().setAttribute("data-attention", String.valueOf(waiting));
        tab.add(Icon.of(stage.icon(), "w-3.5 h-3.5 opacity-70"));
        Span label = new Span(stage.label());
        tab.getElement().appendChild(label.getElement());
        if (waiting > 0) {
            // Neutral, not red. It is a quantity, not an alarm: a pool with eleven drafts in it is not
            // in trouble, it is where the work is.
            Span count = new Span(String.valueOf(waiting));
            count.addClassName("text-[10px] leading-none font-semibold px-1 py-0.5 rounded "
                + (selected ? "bg-base-content/15" : "bg-base-content/10 text-base-content/60"));
            count.getElement().setAttribute("title", waiting == 1
                ? "1 item here is waiting on you" : waiting + " items here are waiting on you");
            tab.getElement().appendChild(count.getElement());
        }
        tab.addDomEventListener("click", e -> open.accept(stage));
        return tab;
    }

    private Div iconButton(String icon, String tooltip, Runnable action) {
        Div button = new Div();
        button.addClassName("flex items-center justify-center w-7 h-7 mb-1 rounded-lg cursor-pointer "
            + "text-base-content/50 hover:text-primary hover:bg-base-300/60");
        button.add(Icon.of(icon, "w-4 h-4"));
        button.getElement().setAttribute("title", tooltip);
        button.addDomEventListener("click", e -> action.run());
        return button;
    }

    private static Div quiet(String text) {
        Div line = new Div(text);
        TextStyle.CAPTION.applyTo(line);
        return line;
    }
}
