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

import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.ProjectList;
import com.swarmcoder.console.api.ProjectSignals;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * The first thing the Console asks: which project are you working on?
 *
 * <h2>What this replaces</h2>
 *
 * <p>The Console used to open a project on the operator's behalf, and it did it in the one place
 * where that is dangerous. Deleting a project opened whichever project happened to be next in the
 * list: the screen filled straight back up with requirements, a backlog and runs, which looks
 * exactly like the project that was just destroyed still being there. The operator's own words:
 * <i>"it doesn't look like the previous project was deleted and they may go ahead and accidentally
 * delete the project that opens."</i> That second deletion falls on a project nobody meant to
 * touch, and nothing brings it back.
 *
 * <p>So nothing opens itself any more. This dialog is up whenever the server says no project has
 * been chosen — at start-up, and again immediately after any deletion — and the only way past it
 * is to pick a project or make one.
 *
 * <h2>The three shapes it takes</h2>
 *
 * <ul>
 *   <li><b>Several projects.</b> One row each. The row the operator was in last time is marked and
 *       its button holds the keyboard, so the ordinary day — restart, carry on with the same
 *       project — is one keypress. It is still a keypress: a pre-selection is a suggestion, and a
 *       suggestion that acts on its own is the defect above.</li>
 *   <li><b>Exactly one project.</b> The same dialog with one row, still requiring the press. A
 *       single-row list is a moment's friction; opening it automatically is how somebody deletes
 *       the wrong project. The friction is the cheaper of the two.</li>
 *   <li><b>No projects.</b> No list at all — a sentence saying so and the button that makes the
 *       first one.</li>
 * </ul>
 *
 * <p>After a deletion there is no pre-selection, deliberately: what is left standing is not a
 * remembered choice, and putting the keyboard on it would leave the operator one press away from
 * opening something arbitrary at the exact moment they are least sure what just happened.
 *
 * <p>It does not compete with the getting-started guide. That guide never opens by itself — it is
 * reached from "Getting started" in the header, from Setup, and once automatically after a project
 * is CREATED. Creating a project from here goes down that same path, so the sequence is: pick or
 * create; then, only for a project that was just made, the guide.
 *
 * <p>A {@link Div} holding its dialog rather than being one, for the reason on
 * {@link OnboardingWizard}: the wrapper is {@code display: contents}, so mounting it costs nothing.
 */
final class ProjectPicker extends Div implements Disposable {

    private final ControlService control = new ControlService_Stub();
    private final ProjectMenu projects;
    private final Runnable onProjectOpened;

    private final Dialog dialog = new Dialog();
    private final Div body = new Div();
    private final Div error = new Div();
    private final List<Disposable> disposables = new ArrayList<>();

    /**
     * True once a published registry has said a project IS chosen.
     *
     * <p>It is what tells "you have just started the console" apart from "you have just deleted a
     * project", which arrive as the same unchosen registry and want different words.
     */
    private boolean everChosen;

    /** Whether the shell has already been told that what it is drawing belongs to nothing. */
    private boolean cleared;

    /** The suggested row's Open button, so it can be given the keyboard once the dialog is up. */
    private Button rememberedButton;

    ProjectPicker(ProjectMenu projects, Runnable onProjectOpened) {
        this.projects = projects;
        this.onProjectOpened = onProjectOpened;
        addClassName("contents");

        dialog.setWidth("34rem");
        dialog.setAriaLabel("Choose a project");
        dialog.getElement().setAttribute("data-testid", "project-picker");
        // There is no way past this except choosing. Escape and a click on the backdrop would both
        // leave the operator looking at a workspace belonging to a project they never picked —
        // which is the whole thing this dialog exists to stop.
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        // Focusable itself, so that "nothing is pre-selected" can be true. A modal dialog puts the
        // keyboard on its FIRST focusable control when the browser opens it — which after a
        // deletion would be the first project's Open button, and Enter would then open a project
        // nobody picked. That is the defect, arriving by a different route.
        dialog.getElement().setAttribute("tabindex", "-1");

        body.addClassName("flex flex-col gap-3 min-h-0 max-h-[60vh] overflow-y-auto pr-1");
        error.addClassName("text-sm text-error mt-2");
        error.setVisible(false);
        dialog.add(body, error);
        add(dialog);

        disposables.add(Effect.create(this::render));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /**
     * Draws the picker from the server's registry, and opens or closes it to match.
     *
     * <p>Runs inside a signal {@link Effect}, so nothing here may suspend: opening a dialog and
     * building elements are local. The one suspending call — switching project — is made from a
     * click handler, which is a threading context.
     */
    private void render() {
        ProjectList published = ProjectSignals.CURRENT.get();
        if (published == null || !published.isPublished()) {
            // Nothing known yet. An empty list here is the signal's initial value, not an answer,
            // and drawing "you have no projects" over it would be a lie with a button on it.
            return;
        }
        if (published.isChosen()) {
            everChosen = true;
            cleared = false;
            dialog.close();
            return;
        }
        if (everChosen && !cleared) {
            // A project was open a moment ago and is not any more. Every surface in the shell is
            // scoped to a project, so the board behind this dialog is still drawing the deleted
            // one — a console that looks exactly as it did before the deletion, which is the
            // report. The same rebuild a project switch does puts that right.
            //
            // On its own thread because rebuilding a stage talks to the server, and this runs
            // inside a signal effect where a suspending call throws. The same constraint, and the
            // same workaround, as the shell's own boot.
            cleared = true;
            new Thread(onProjectOpened).start();
        }

        List<ProjectDto> rows = published.getProjects() == null
            ? new ArrayList<>() : published.getProjects();
        String suggested = published.getSuggestedProjectId();

        body.removeAll();
        error.setVisible(false);
        body.add(heading(rows.isEmpty()));

        rememberedButton = null;
        for (ProjectDto project : rows) {
            boolean isSuggested = !suggested.isEmpty() && suggested.equals(project.getProjectId());
            body.add(projectRow(project, isSuggested));
        }
        body.add(createRow(rows.isEmpty()));

        if (!dialog.isOpened()) {
            dialog.open();
        }
        // The keyboard goes on the remembered project, and only on the remembered project. After a
        // deletion there is no suggestion and therefore nothing is focused — pressing Enter in that
        // state must not open anything.
        if (rememberedButton != null) {
            rememberedButton.focus();
        } else {
            dialog.getElement().focus();
        }
    }

    private Div heading(boolean nothingToPick) {
        Div block = new Div();
        block.addClassName("flex flex-col gap-1");
        Div title = new Div(nothingToPick
            ? "You have no projects yet"
            : "Which project do you want to work on?");
        title.addClassName("text-lg font-bold");
        Div words = new Div(sentence(nothingToPick));
        words.addClassName("text-sm text-base-content/60 leading-snug");
        words.getElement().setAttribute("data-testid", "project-picker-reason");
        block.add(title, words);
        return block;
    }

    /**
     * The sentence under the heading. Three of them, because three different things have happened.
     *
     * <p>The one that matters is the third: after a deletion the operator needs to be told, in
     * words, that the project is gone — the whole complaint was that a deletion did not look like
     * one.
     */
    private String sentence(boolean nothingToPick) {
        if (nothingToPick) {
            return everChosen
                ? "That was your last project. Its records are gone; the folder of code on your "
                    + "disk was not touched. Make a project to start again."
                : "A project is one folder of code and everything SwarmCoder knows about it. "
                    + "Make one to begin.";
        }
        return everChosen
            ? "That project is deleted. Its records are gone; the folder of code on your disk was "
                + "not touched. Nothing is open — pick the project you want next."
            : "Nothing is open yet. Pick the one you want, or make a new one.";
    }

    private Div projectRow(ProjectDto project, boolean suggested) {
        Div row = new Div();
        row.setClassName("flex items-center gap-3 px-3 py-2.5 rounded-lg border "
            + (suggested ? "border-primary/50 bg-primary/5" : "border-base-300 hover:bg-base-200"));
        row.getElement().setAttribute("data-testid", "project-choice");

        Div avatar = new Div(initials(project.getName()));
        avatar.addClassName("flex items-center justify-center w-9 h-9 rounded-lg text-xs "
            + "font-bold shrink-0 bg-base-300");

        Div text = new Div();
        text.addClassName("flex-1 min-w-0");
        Div name = new Div(project.getName() == null || project.getName().isEmpty()
            ? "(unnamed)" : project.getName());
        name.addClassName("text-sm font-medium truncate");
        text.add(name);
        if (suggested) {
            Span mark = new Span("Where you were last");
            mark.addClassName("text-[11px] text-primary");
            text.getElement().appendChild(mark.getElement());
        }
        Div path = new Div(project.getPrimaryPath() == null ? "" : project.getPrimaryPath());
        path.addClassName("text-[11px] text-base-content/50 truncate");
        text.add(path);

        Button open = new Button("Open");
        open.addClassName(suggested ? "btn-sm btn-primary shrink-0" : "btn-sm btn-outline shrink-0");
        open.getElement().setAttribute("data-testid", "open-project");
        open.getElement().setAttribute("data-project-id",
            project.getProjectId() == null ? "" : project.getProjectId());
        open.addClickListener(e -> choose(project));
        if (suggested) {
            rememberedButton = open;
        }

        row.add(avatar, text, open);
        return row;
    }

    private Div createRow(boolean nothingToPick) {
        Div block = new Div();
        block.addClassName(nothingToPick ? "" : "pt-3 mt-1 border-t border-base-300");
        Button create = new Button("Create a new project");
        create.addClassName(nothingToPick ? "btn-primary w-full" : "btn-sm btn-ghost w-full");
        create.getElement().setAttribute("data-testid", "picker-new-project");
        create.addClickListener(e -> {
            // Off the screen while the form has it. Two modals at once are painted one behind the
            // other with both backdrops over the page, which is unusable — the guide learned the
            // same lesson and swaps rather than stacks.
            dialog.close();
            projects.openCreateProject(onProjectOpened, this::reopen);
        });
        block.add(create);
        return block;
    }

    /** Comes back when the create form was abandoned — otherwise there is no way to choose at all. */
    private void reopen() {
        if (!dialog.isOpened()) {
            dialog.open();
        }
    }

    /**
     * Opens the project the operator picked.
     *
     * <p>The server records the choice and republishes the registry, so this dialog closes on the
     * signal rather than by closing itself — one source of truth for "a project is open", the same
     * arrangement the rail uses.
     */
    private void choose(ProjectDto project) {
        try {
            control.switchProject(project.getProjectId());
            error.setVisible(false);
            onProjectOpened.run();
        } catch (Exception e) {
            error.setText("Could not open " + project.getName() + ": " + e.getMessage());
            error.setVisible(true);
            // The operator pressed Open and stayed where they were. Nothing else in this flow
            // reports it, and a picker that will not let anybody past it is the worst place for a
            // failure to be silent.
            ClientLog.error("ProjectPicker", "opening project " + project.getProjectId()
                + " failed — no project is open and the picker is still up: " + e);
        }
    }

    private static String initials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
    }
}
