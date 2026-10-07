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
import com.swarmcoder.console.api.BuildContractDto;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.ProjectList;
import com.swarmcoder.console.api.ProjectSignals;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.KeyedList;
import com.zeroz4j.ui.component.PropertyGrid;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.component.ThemeController;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;
import com.swarmcoder.console.api.BudgetsDto;
import java.util.concurrent.atomic.AtomicReference;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.xml.Node;

/**
 * Left project rail (design §2 R1): every project as a row (avatar, name, hover gear →
 * project settings dialog), "New project" at the top, and pinned at the bottom the global
 * settings gear (theme toggle + shared config) and the system-health heart.
 *
 * <p>The list is driven by the server-authoritative {@link ProjectSignals#CURRENT} shared signal,
 * the same way {@code BrdView} and {@code PipelineBoard} are driven by theirs. It used to call
 * {@code control.projects()} once from this constructor instead. That never worked: the constructor
 * runs during client bootstrap, inside a native JS callback rather than a TeaVM green thread, so the
 * suspending RMI call threw {@code "Suspension point reached from non-threading context"} on every
 * boot — and because the throw was swallowed with nothing scheduled to retry, the rail stayed empty
 * for the life of the page even though projects existed.
 *
 * <p>Implements {@link Disposable} and disposes its {@link Effect}s: the signal is long-lived and
 * process-wide, so an effect bound to it outlives this view unless it is explicitly torn down.
 */
final class ProjectMenu extends Div implements Disposable {

    private final ControlService control = new ControlService_Stub();
    private final ValueSignal<List<ProjectDto>> projects = new ValueSignal<>(new ArrayList<>());
    private final List<Disposable> disposables = new ArrayList<>();
    /**
     * Where this menu's dialogs are mounted — supplied by the shell, and living in the shell's own DOM
     * tree rather than in this panel's.
     *
     * <p>It has to be, now that the panel is itself inside a dialog: a modal opened into a subtree that
     * is not in the document renders nowhere at all, so "Settings" from the ⋯ overflow silently did
     * nothing while the menu was closed. The dialogs outlive the panel that offers them.
     */
    private final Div dialogHost;
    /**
     * Retires the menu itself, called whenever it is about to put a dialog of its own on screen.
     *
     * <p>Without it the two stack: the menu is a modal, its "Project settings" dialog is another, and
     * closing the inner one leaves the outer still blocking the page — so the next click lands on a
     * backdrop and reads as a frozen Console. A menu has finished its job the moment it has been used.
     */
    private final Runnable onLeaving;
    /** Failures of operator actions (switch/create/save) — never swallowed, always shown here. */
    private final Div error = new Div();
    ProjectMenu(Runnable onProjectSwitched, Div dialogHost, Runnable onLeaving) {
        this.dialogHost = dialogHost;
        this.onLeaving = onLeaving;
        // A panel inside a dialog, not a rail down the side of every screen (UX v3 §8.4). Which
        // project you are in changes a handful of times a day and cost 16rem of every screen all day;
        // the NAME stays permanently visible in the header, which is the part that actually matters.
        addClassName("flex flex-col min-h-0 max-h-[70vh] w-72");

        Div header = new Div();
        header.addClassName("flex items-center justify-between px-4 py-3");
        Span title = new Span("Projects");
        title.addClassName("text-xs font-semibold uppercase tracking-wider text-base-content/50");
        Div addButton = new Div();
        addButton.addClassName("cursor-pointer text-base-content/50 hover:text-primary");
        addButton.add(Icon.of("plus", "w-4 h-4"));
        addButton.getElement().setAttribute("title", "New project");
        addButton.addDomEventListener("click", e -> openCreateDialog(onProjectSwitched));
        header.getElement().appendChild(title.getElement());
        header.add(addButton);
        add(header);

        Div list = new Div();
        list.addClassName("flex-1 min-h-0 overflow-y-auto px-2 space-y-0.5");
        add(list);
        // Key includes isCurrent so switching re-renders the highlight (KeyedList keeps
        // same-key DOM untouched).
        // Kept and disposed: a keyed list watches its signal for as long as it exists (ZeroZ
        // Stack 0.8.0), and nothing else would ever stop this one.
        disposables.add(new KeyedList<>(list, projects, p -> p.getProjectId() + ":" + p.isCurrent(),
            p -> projectRow(p, onProjectSwitched)));

        error.addClassName("px-3 py-1 text-[11px] text-error");
        error.setVisible(false);
        add(error);

        // Pinned bottom: global settings + health.
        Div bottom = new Div();
        bottom.addClassName("border-t border-base-300 px-2 py-2 flex items-center gap-1");
        bottom.add(bottomButton("gear", "Settings", this::openGlobalSettings));
        bottom.add(bottomButton("heart", "System health (C2-5)", () -> { }));
        add(bottom);

        // The Setup stage names these two ways in ("Project settings", "Models & budgets") instead
        // of sending the operator to hunt for an unlabelled gear. The dialogs stay owned here, in
        // this menu's own DOM tree, because they are about the project it selects.
        Nav.openGlobalSettings = this::openGlobalSettings;
        Nav.openProjectSettings = () -> {
            for (ProjectDto project : projects.get()) {
                if (project.isCurrent()) {
                    openProjectDialog(project);
                    return;
                }
            }
            // The Setup stage offered a button and nothing opened. Said on screen AND logged: the
            // rail's error line is cleared by the next successful action, and this is the state a
            // first-time operator is in when they press the most obvious control on the page.
            showError("no project is selected — create one with + above");
            ClientLog.warn("ProjectMenu", "project settings were requested but no project is "
                + "current, so no dialog opened");
        };

        // The menu's only source of state. The server publishes on wiring and on every change, and
        // the framework retains the latest value, so this receives it whether it was published
        // before or after the page connected — no RMI call from the constructor, nothing to retry.
        disposables.add(Effect.create(() -> {
            ProjectList published = ProjectSignals.CURRENT.get();
            projects.set(published == null
                ? new ArrayList<>() : new ArrayList<>(published.getProjects()));
        }));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /**
     * Mounts a dialog, dropping whatever was mounted before it.
     *
     * <p>Closing a {@code Dialog} hides it; it does not remove it. Every open therefore used to
     * leave another copy of a settings form in the document, so the second time the operator opened
     * Settings the page held two "Advanced & developer" tabs and two developer checkboxes — one live
     * and one a hidden ghost. Harmless to look at, and quietly wrong for anything that addresses an
     * element by what it says. These are modal, so at most one is ever wanted.
     */
    private void mount(Dialog dialog) {
        onLeaving.run();
        dialogHost.removeAll();
        dialogHost.add(dialog);
    }

    /**
     * Puts the guide on screen. Called once, from a project that has just been created.
     *
     * <p>This is the moment it exists for. Pressing Create is the one instant at which somebody has
     * committed to the tool and has not yet been told a single thing about it, and the guide's own
     * entry points were all on the Setup panel — a panel {@link Stages#recommended} deliberately
     * never sends anybody to, reachable only through the small health mark in the header. So the
     * first person other than its author to use the Console made a project, landed on the
     * requirements workspace, and saw no sign of the guide at all.
     *
     * <p>The guide itself belongs to {@link StageHost}, which outlives this menu; this only asks for
     * it. Everything mounted here goes first — the create dialog that has just closed, and the
     * project panel behind that — because they are modal and would block the page underneath it.
     *
     * <p>Must be called from a DOM handler or a green thread, like everything else in this class.
     */
    private void openGuide() {
        onLeaving.run();
        dialogHost.removeAll();
        Nav.openGuide.run();
    }

    /** Shows a failed operator action instead of losing it; blank clears the line. */
    private void showError(String message) {
        error.setText(message == null ? "" : message);
        error.setVisible(message != null && !message.isEmpty());
    }

    private Div bottomButton(String icon, String tooltip, Runnable action) {
        Div button = new Div();
        button.addClassName("flex items-center justify-center w-9 h-9 rounded-lg cursor-pointer "
            + "text-base-content/50 hover:text-primary hover:bg-base-300/60");
        button.add(Icon.of(icon, "w-5 h-5"));
        button.getElement().setAttribute("title", tooltip);
        button.addDomEventListener("click", e -> action.run());
        return button;
    }

    private Div projectRow(ProjectDto project, Runnable onProjectSwitched) {
        Div row = new Div();
        styleRow(row, project.isCurrent());

        Div avatar = new Div(initials(project.getName()));
        avatar.addClassName("flex items-center justify-center w-8 h-8 rounded-lg text-xs "
            + "font-bold shrink-0 text-primary-content " + avatarColor(project.getProjectId()));

        Div text = new Div();
        text.addClassName("flex-1 min-w-0");
        Div name = new Div(project.getName() == null || project.getName().isEmpty()
            ? "(unnamed)" : project.getName());
        name.addClassName("text-sm font-medium truncate");
        Div path = new Div(project.getPrimaryPath() == null ? "" : project.getPrimaryPath());
        path.addClassName("text-[11px] text-base-content/40 truncate");
        text.add(name, path);

        Div gear = new Div();
        gear.addClassName("opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer "
            + "text-base-content/50 hover:text-primary shrink-0");
        gear.add(Icon.of("gear", "w-3.5 h-3.5"));
        gear.getElement().setAttribute("title", "Project settings");
        // Addressed by which project it belongs to. Every row carries an identical gear with an
        // identical tooltip, so a test naming one by its words picks whichever row is first.
        gear.getElement().setAttribute("data-testid", "project-settings");
        gear.getElement().setAttribute("data-project-id",
            project.getProjectId() == null ? "" : project.getProjectId());
        gear.addDomEventListener("click", e -> {
            e.stopPropagation();
            openProjectDialog(project);
        });

        row.add(avatar, text, gear);
        row.addDomEventListener("click", e -> {
            if (!project.isCurrent()) {
                try {
                    // The server republishes the registry as part of the switch, so the rail's
                    // highlight moves on the signal — there is nothing to refresh here.
                    control.switchProject(project.getProjectId());
                    showError("");
                    onProjectSwitched.run();
                } catch (Exception ex) {
                    showError("could not switch project: " + ex.getMessage());
                    ClientLog.error("ProjectMenu", "project switch failed — the console is still "
                        + "showing " + project.getProjectId() + "'s predecessor: " + ex);
                }
            }
        });
        return row;
    }

    private static void styleRow(Div row, boolean current) {
        row.setClassName("group flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer "
            + (current ? "bg-primary/10 ring-1 ring-primary/30"
                       : "hover:bg-base-300/60"));
    }

    /** Per-project settings (design §7): context folders + role overrides → project.yaml. */
    private void openProjectDialog(ProjectDto project) {
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        Div title = new Div("Project — " + project.getName());
        title.addClassName("text-lg font-bold mb-3");
        // CopyValue, not the grid's own string row: the framework's copy button defers its work
        // onto a green thread and so loses the browser's user gesture, which is why neither of
        // these two ever copied anything. See CopyValue and Clipboard.
        PropertyGrid grid = new PropertyGrid()
            .row("id", new CopyValue("id", project.getProjectId()))
            .row("primary path", new CopyValue("primary path", project.getPrimaryPath()));

        Div contextLabel = new Div("Context folders (read-only reference, comma-separated)");
        contextLabel.addClassName("text-xs text-base-content/50 mt-3 mb-1");
        TextField contextPaths = new TextField();
        contextPaths.addClassName("input input-bordered input-sm w-full font-mono");
        contextPaths.setValue(project.getContextPathsCsv() == null ? "" : project.getContextPathsCsv());

        // The project layer of the three sizing layers (story, then project, then the settings
        // file). It sits with the role overrides because it is the same kind of thing and lives in
        // the same file: something this repository says about itself, blank meaning inherit.
        Div workersLabel = new Div("Attempts per piece of work — leave empty to use the number in "
            + "the settings file");
        workersLabel.addClassName("text-xs text-base-content/50 mt-3 mb-1");
        TextField workersPerTask = new TextField();
        workersPerTask.addClassName("input input-bordered input-sm w-32 font-mono");
        workersPerTask.getElement().setAttribute("data-testid", "project-workers-per-task");
        try {
            int stated = control.projectWorkersPerTask(project.getProjectId());
            workersPerTask.setValue(stated >= 1 ? String.valueOf(stated) : "");
        } catch (Exception e) {
            ClientLog.error("ProjectMenu", "the project's attempts-per-piece-of-work did not "
                + "load for " + project.getProjectId() + "; the box is showing empty, which SAVES "
                + "as \"inherit\": " + e);
        }

        Div rolesLabel = new Div("Model overrides — blank fields inherit the global config");
        rolesLabel.addClassName("text-xs text-base-content/50 mt-4 mb-1");
        Div formHost = new Div();
        Div status = new Div();
        status.addClassName("text-sm text-error mt-2");
        dialog.add(title, grid, contextLabel, contextPaths, workersLabel, workersPerTask,
            rolesLabel, formHost, status);

        AtomicReference<RoleForm> form = new AtomicReference<>();
        
            try {
                RoleForm loaded = new RoleForm(control.projectRoles(project.getProjectId()), true);
                form.set(loaded);
                formHost.add(loaded);
            } catch (Exception e) {
                status.setText("failed to load overrides: " + e.getMessage());
                // Without the form the dialog still offers Save, and saving then writes an empty
                // override set over whatever the project had — so this is worth a log line even
                // though the dialog already says it.
                ClientLog.error("ProjectMenu", "project role overrides did not load — the dialog "
                    + "is showing no overrides for " + project.getProjectId()
                    + " whether or not it has any: " + e);
            }

        Button cancel = new Button("Cancel", e -> dialog.close());
        Button save = new Button("Save", e -> {
            try {
                RoleForm current = form.get();
                int workers;
                try {
                    String typed = workersPerTask.getValue() == null ? ""
                        : workersPerTask.getValue().trim();
                    workers = typed.isEmpty() ? 0 : Integer.parseInt(typed);
                } catch (NumberFormatException bad) {
                    status.setText("Attempts per piece of work has to be a whole number, or empty.");
                    return;
                }
                if (workers < 0) {
                    status.setText("Attempts per piece of work has to be a whole number, or empty.");
                    return;
                }
                String result = control.saveProjectConfig(project.getProjectId(),
                    contextPaths.getValue(), current == null ? List.of() : current.read(), workers);
                if (result != null && !result.isEmpty()) {
                    status.setText(result);
                    // A refused save is as damaging as a thrown one — project.yaml is unchanged
                    // either way — so the server's refusal reaches the log too.
                    ClientLog.error("ProjectMenu", "project settings save refused — "
                        + project.getProjectId() + " keeps its old context folders and overrides: "
                        + result);
                } else {
                    // The server republishes the registry on a successful save.
                    dialog.close();
                }
            } catch (Exception ex) {
                status.setText("save failed: " + ex.getMessage());
                ClientLog.error("ProjectMenu", "project settings save failed — "
                    + project.getProjectId() + " keeps its old context folders and overrides: "
                    + ex);
            }
        });
        save.addClassName("btn-primary");
        dialog.add(dangerZone(dialog, project));
        dialog.addAction(cancel);
        dialog.addAction(save);
        mount(dialog);
        dialog.open();
    }

    /**
     * The destructive corner of the project dialog: a separated, error-tinted block at the very
     * bottom, far from Save, whose "Delete project" button only REVEALS a confirm step. The confirm
     * step names what will be destroyed with counts, states that the working tree is untouched, and
     * keeps its button disabled until the operator types the project's name exactly.
     *
     * <p>The typed name is re-checked on the server; this gate is the courtesy, not the guarantee.
     */
    private Div dangerZone(Dialog dialog, ProjectDto project) {
        // An unnamed project is confirmed by its id — the server applies the same rule, so a blank
        // name can neither make a project undeletable nor make it deletable by typing nothing.
        String token = project.getName() == null || project.getName().trim().isEmpty()
            ? project.getProjectId() : project.getName().trim();
        String path = project.getPrimaryPath() == null || project.getPrimaryPath().isEmpty()
            ? "(no path recorded)" : project.getPrimaryPath();

        Div zone = new Div();
        zone.addClassName("mt-8 pt-4 border-t-2 border-error/40");
        Div heading = new Div("Danger zone");
        heading.addClassName("text-sm font-bold text-error");
        Div hint = new Div("Deleting a project removes SwarmCoder's records of it. "
            + "It never touches the files on disk.");
        hint.addClassName("text-xs text-base-content/60 mt-1 mb-2");
        zone.add(heading, hint);

        Div confirmBlock = new Div();
        confirmBlock.addClassName("mt-3 rounded-lg border border-error/40 bg-error/5 p-3");
        confirmBlock.setVisible(false);

        Div whatGoes = new Div();
        whatGoes.addClassName("text-sm");
        Div whatStays = new Div("The folder on disk at " + path + " is NOT touched.");
        whatStays.addClassName("text-sm font-semibold mt-1");
        Div typeLabel = new Div("Type " + token + " to confirm");
        typeLabel.addClassName("text-xs text-base-content/60 mt-3 mb-1");
        TextField confirmName = new TextField();
        confirmName.addClassName("input input-bordered input-sm w-full");
        confirmName.getElement().setAttribute("data-testid", "delete-confirm-name");
        Div deleteStatus = new Div();
        deleteStatus.addClassName("text-xs text-error mt-2");

        Button confirmDelete = new Button("Delete permanently");
        confirmDelete.addClassName("btn-error btn-sm mt-3");
        confirmDelete.getElement().setAttribute("data-testid", "confirm-delete-project");
        confirmDelete.setEnabled(false);
        // Wrong text keeps the button disabled. No RMI here — this runs on every keystroke, and a
        // suspending call outside a DOM handler throws "suspension point reached from
        // non-threading context".
        confirmName.addValueChangeListener(e -> confirmDelete.setEnabled(
            token.equals(confirmName.getValue() == null ? "" : confirmName.getValue().trim())));
        confirmDelete.addClickListener(e -> {
            try {
                String result = control.deleteProject(project.getProjectId(), confirmName.getValue());
                if (result != null && !result.isEmpty()) {
                    // Shown in the dialog rather than thrown away: a refused delete the operator
                    // cannot see reads as a dead button.
                    deleteStatus.setText(result);
                    ClientLog.error("ProjectMenu", "project delete refused — " + project.getProjectId()
                        + " and all its records are still there: " + result);
                } else {
                    // The server republishes the registry (and moves off a deleted current
                    // project), so the rail updates on the signal — nothing to refresh here.
                    dialog.close();
                }
            } catch (Exception ex) {
                deleteStatus.setText("delete failed: " + ex.getMessage());
                ClientLog.error("ProjectMenu", "project delete failed — " + project.getProjectId()
                    + " and all its records are still there: " + ex);
            }
        });
        confirmBlock.add(whatGoes, whatStays, typeLabel, confirmName, confirmDelete, deleteStatus);

        Button reveal = new Button("Delete project");
        reveal.addClassName("btn-outline btn-error btn-sm");
        reveal.getElement().setAttribute("data-testid", "reveal-delete-project");
        reveal.addClickListener(e -> {
            // The counts are read from a DOM event handler, which is the only context an RMI call
            // may suspend in.
            try {
                String preview = control.projectDeletionPreview(project.getProjectId());
                if (preview == null || preview.startsWith("error")) {
                    whatGoes.setText("");
                    deleteStatus.setText(preview == null ? "could not read the project" : preview);
                    // The confirm step is still armed with no counts on it, so the operator is
                    // about to confirm a delete without being told what it destroys.
                    ClientLog.warn("ProjectMenu", "project deletion preview unavailable — the "
                        + "confirm step for " + project.getProjectId() + " cannot say what will be "
                        + "destroyed: " + preview);
                } else {
                    whatGoes.setText(preview);
                    deleteStatus.setText("");
                }
            } catch (Exception ex) {
                whatGoes.setText("");
                deleteStatus.setText("could not count this project's records: " + ex.getMessage());
                ClientLog.warn("ProjectMenu", "project deletion preview failed — the confirm step "
                    + "for " + project.getProjectId() + " cannot say what will be destroyed: " + ex);
            }
            confirmName.setValue("");
            confirmDelete.setEnabled(false);
            confirmBlock.setVisible(true);
            reveal.setVisible(false);
        });

        zone.add(reveal, confirmBlock);
        return zone;
    }

    private void openCreateDialog(Runnable onProjectSwitched) {
        openCreateProject(onProjectSwitched, () -> { });
    }

    /**
     * The "New project" form, told what to do if it is abandoned.
     *
     * <p>The second callback exists for the project picker, which has to step off the screen to let
     * this dialog have it — two modals at once are drawn one behind the other and the result is
     * unusable — and has to come back if nothing was created, or there would be no way to choose a
     * project at all.
     *
     * @param onProjectSwitched run when a project was created and opened
     * @param onDismissed       run when the form was closed without creating anything
     */
    void openCreateProject(Runnable onProjectSwitched, Runnable onDismissed) {
        Dialog dialog = new Dialog();
        // Set the moment a project is created, so closing the form afterwards is not mistaken for
        // abandoning it and does not send the picker back up over the guide.
        boolean[] created = {false};
        dialog.addCloseListener(e -> {
            if (!created[0]) {
                onDismissed.run();
            }
        });
        Div title = new Div("New project");
        title.addClassName("text-lg font-bold mb-3");
        // Captions, not placeholders. All three boxes used to be named by their placeholder text,
        // which vanishes the moment anything is typed — fill the folder in and the form no
        // longer says what any box was for. The constructor argument is the placeholder and it now
        // carries an EXAMPLE, which is what a placeholder is for; the caption stays put.
        TextField name = new TextField("Checkout rebuild")
            .withLabel("Project name (optional)");
        TextField path = new TextField("C:\\work\\my-app")
            .withLabel("The folder holding the code");
        TextField context = new TextField("C:\\work\\shared-library, https://docs.example.com")
            .withLabel("Other folders or documentation sites to read "
                + "(optional, separate them with commas)");
        Div status = new Div();
        status.addClassName("text-sm text-error mt-1");
        Div form = new Div();
        form.addClassName("flex flex-col gap-3");
        form.add(name, path, context, status);
        dialog.add(title, form);
        dialog.add(buildSection(path));

        Button cancel = new Button("Cancel", e -> dialog.close());
        Button create = new Button("Create", e -> {
            String pathText = path.getValue();
            if (pathText == null || pathText.trim().isEmpty()) {
                status.setText("The folder holding the code is required — "
                    + "type the folder your code is in.");
                return;
            }
            try {
                String result = control.createProject(name.getValue(), pathText, context.getValue());
                if (result != null && result.startsWith("error")) {
                    status.setText(result);
                    ClientLog.error("ProjectMenu", "project create refused — no project was "
                        + "registered for " + pathText + ": " + result);
                } else {
                    // The server republishes the registry as part of creation.
                    created[0] = true;
                    dialog.close();
                    onProjectSwitched.run();
                    // …and then say what this thing is. Only here, and only on a project that was
                    // just MADE: switching between projects you already have runs the same
                    // onProjectSwitched, and somebody doing that has been here before.
                    openGuide();
                }
            } catch (Exception ex) {
                status.setText("create failed: " + ex.getMessage());
                ClientLog.error("ProjectMenu", "project create failed — no project was registered "
                    + "for " + pathText + ": " + ex);
            }
        });
        create.addClassName("btn-primary");
        dialog.addAction(cancel);
        dialog.addAction(create);
        mount(dialog);
        dialog.open();
    }


    /**
     * "How do we build it?" - the on-ramp, on the same screen as the folder it is about.
     *
     * <p><b>Why it is here and not somewhere later.</b> Without a verification contract nothing
     * builds or tests the attempts the swarm is choosing between: every candidate comes back marked
     * unverified and the winner is picked on a reading of the code alone. It is therefore the FIRST
     * thing an existing project needs, and the moment the operator has just typed where the code is
     * is the moment they can answer it.
     *
     * <p>Two buttons on purpose. Checking runs the project's real build, which on a large codebase
     * is minutes; saving writes a file. Rolling them together would either leave the dialog hanging
     * with nothing to show for it, or save commands nobody has watched run.
     */
    private Div buildSection(TextField path) {
        Div section = new Div();
        section.addClassName("mt-4 rounded-lg border border-base-300 p-3");

        Div heading = new Div("How should SwarmCoder build and test it?");
        heading.addClassName("text-sm font-semibold");
        Div hint = new Div("SwarmCoder tries several attempts at every change and keeps the one "
            + "that passes. To do that it has to be able to build the code and run the tests. "
            + "Check below and it will work out the commands, run the build once to make sure they "
            + "really work, and show you what it found. Nothing is saved until you say so, and you "
            + "can edit anything first.");
        hint.addClassName("text-xs text-base-content/60 leading-relaxed mt-1 mb-2");
        section.add(heading, hint);

        Div result = new Div();
        result.addClassName("text-sm mt-2 whitespace-pre-wrap");
        Div buildStatus = new Div();
        buildStatus.addClassName("text-sm mt-1");
        TextArea yaml = new TextArea("");
        yaml.addClassName("w-full h-64 font-mono text-xs mt-2 hidden");

        Button check = new Button("Check how to build it", e -> {
            String folder = path.getValue();
            if (folder == null || folder.trim().isEmpty()) {
                buildStatus.setText("Type the folder holding the code first.");
                return;
            }
            buildStatus.setText("Working it out, and running the build once - this can take a few "
                + "minutes on a big project...");
            result.setText("");
            try {
                BuildContractDto contract = control.detectBuildContract(folder, true);
                if (contract == null) {
                    buildStatus.setText("That folder could not be read.");
                    ClientLog.error("ProjectMenu", "build-contract detection returned nothing for "
                        + folder);
                    return;
                }
                result.setText(describe(contract));
                if (contract.isRecognised()) {
                    yaml.setValue(contract.getYaml());
                    yaml.removeClassName("hidden");
                    buildStatus.setText(contract.isCompiles()
                        ? "The build works. Read the commands, change anything you disagree with, "
                          + "then save."
                        : "Read this before saving - the build did not succeed.");
                } else {
                    yaml.addClassName("hidden");
                    buildStatus.setText("Nothing was recognised, so nothing is proposed.");
                }
            } catch (Exception ex) {
                buildStatus.setText("Could not check: " + ex.getMessage());
                ClientLog.error("ProjectMenu", "build-contract detection failed for " + folder
                    + " - no contract was written: " + ex);
            }
        });
        check.addClassName("btn-xs");

        Button save = new Button("Save these commands", e -> {
            String folder = path.getValue();
            String text = yaml.getValue();
            if (text == null || text.trim().isEmpty()) {
                buildStatus.setText("Check how to build it first.");
                return;
            }
            try {
                // Never overwrites from here. Replacing a contract the operator already settled on
                // is damage nobody notices until a run later, and this dialog is for a NEW project.
                String error = control.saveBuildContract(folder, text, false);
                buildStatus.setText(error.isEmpty()
                    ? "Saved into that folder as .swarmcoder/verify.yaml. Commit it - it lives with "
                      + "the code on purpose."
                    : error);
                if (!error.isEmpty()) {
                    ClientLog.error("ProjectMenu", "verification contract save refused - nothing "
                        + "was written to " + folder + ": " + error);
                }
            } catch (Exception ex) {
                buildStatus.setText("Save failed: " + ex.getMessage());
                ClientLog.error("ProjectMenu", "verification contract save failed - nothing was "
                    + "written to " + folder + ": " + ex);
            }
        });
        save.addClassName("btn-xs");

        Div buttons = new Div();
        buttons.addClassName("flex gap-2");
        buttons.add(check, save);
        section.add(buttons, buildStatus, result, yaml);
        return section;
    }

    /** Everything the operator has to read before deciding, in the order they need it. */
    private static String describe(BuildContractDto contract) {
        if (!contract.isRecognised()) {
            return contract.getProbeVerdict() == null ? "" : contract.getProbeVerdict();
        }
        StringBuilder out = new StringBuilder();
        out.append("This looks like a ").append(contract.getToolchain()).append(" project.\n");
        if (notBlank(contract.getEvidence())) {
            out.append("\nBecause of:\n").append(indent(contract.getEvidence()));
        }
        out.append("\nIt will run:\n").append(indent(contract.getCommands()));
        if (notBlank(contract.getAcceptanceTestDir())) {
            out.append("\nNew tests for each change go in ")
               .append(contract.getAcceptanceTestDir()).append("\n");
        }
        if (contract.isProbed()) {
            out.append("\n").append(contract.isCompiles() ? "The build worked" : "The build FAILED")
               .append(" - it took ").append(contract.getProbeSeconds()).append(" seconds.\n")
               .append(contract.getProbeVerdict()).append("\n");
            if (!contract.isCompiles() && notBlank(contract.getProbeLog())) {
                out.append("\nWhat it said:\n").append(indent(contract.getProbeLog()));
            }
        }
        if (notBlank(contract.getWarnings())) {
            out.append("\nDecide these before a run depends on it:\n")
               .append(indent(contract.getWarnings()));
        }
        if (contract.isAlreadyHasContract()) {
            out.append("\nThis folder already has a saved contract. Saving will be refused rather "
                + "than replacing it.\n");
        }
        return out.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String indent(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n")) {
            if (!line.trim().isEmpty()) {
                out.append("    ").append(line).append("\n");
            }
        }
        return out.toString();
    }

    /** Global settings (design §7): Roles / Budgets tabs over config.yaml + raw-YAML escape hatch. */
    private void openGlobalSettings() {
        Dialog dialog = new Dialog();
        dialog.setWidth("56rem");
        Div titleRow = new Div();
        titleRow.addClassName("flex items-center justify-between mb-3");
        Div title = new Div("Settings");
        title.addClassName("text-lg font-bold");
        ThemeController theme = new ThemeController(true);
        theme.setValue(!"light".equals(Js.localGet("console.theme")));
        theme.addValueChangeListener(e -> {
            String next = Boolean.TRUE.equals(e.getValue()) ? "dark" : "light";
            Js.setTheme(next);
            Js.localSet("console.theme", next);
        });
        titleRow.add(title, theme);

        Div tabBar = new Div();
        tabBar.addClassName("tabs tabs-boxed w-fit mb-3");
        Div content = new Div();
        Div status = new Div();
        status.addClassName("text-sm mt-2");
        dialog.add(titleRow, tabBar, content, status);

        Runnable rolesTab = () -> {
            content.removeAll();
            status.setText("");
            Div hint = new Div("Per-role model endpoints (~/.swarmcoder/config.yaml) — apply on restart.");
            hint.addClassName("text-xs text-base-content/50 mb-2");
            Div formHost = new Div();
            content.add(hint, formHost);
            
                try {
                    RoleForm form = new RoleForm(control.globalRoles(), false);
                    Button save = new Button("Save roles", e -> {
                        // Wrapped because a throw out of a DOM click handler escapes into JS: the
                        // button would simply do nothing and the operator would read the unchanged
                        // status line as "nothing happened", not as "config.yaml was not written".
                        try {
                            String result = control.saveGlobalRoles(form.read());
                            status.setText(result == null || result.isEmpty()
                                ? "saved — applies on restart" : result);
                            if (result != null && !result.isEmpty()) {
                                ClientLog.error("ProjectMenu", "global roles save refused — "
                                    + "config.yaml still has the old model endpoints: " + result);
                            }
                        } catch (Exception ex) {
                            status.setText("save failed: " + ex.getMessage());
                            ClientLog.error("ProjectMenu", "global roles save failed — config.yaml "
                                + "still has the old model endpoints: " + ex);
                        }
                    });
                    save.addClassName("btn-primary btn-sm mt-3");
                    formHost.add(form, save);
                } catch (Exception e) {
                    status.setText("failed to load roles: " + e.getMessage());
                    ClientLog.error("ProjectMenu", "global roles did not load — the Roles tab is "
                        + "empty and cannot be saved from: " + e);
                }
        };
        Runnable budgetsTab = () -> {
            content.removeAll();
            status.setText("");
            Div formHost = new Div();
            formHost.addClassName("flex flex-col gap-2 max-w-sm");
            content.add(formHost);
            
                try {
                    var budgets = control.globalBudgets();
                    TextField cloud = labeled(formHost, "max cloud tokens / run",
                        String.valueOf(budgets.getMaxCloudTokensPerRun()));
                    TextField local = labeled(formHost, "max local tokens / task",
                        String.valueOf(budgets.getMaxLocalTokensPerTask()));
                    TextField wall = labeled(formHost, "wall-clock ceiling (hours)",
                        String.valueOf(budgets.getWallClockCeilingHours()));
                    // 0 here means "not stated", and the run then uses the built-in 120. Showing
                    // the raw 0 would read as "no steps allowed", so show the number that will
                    // actually be used.
                    TextField turns = labeled(formHost, "steps per attempt",
                        String.valueOf(budgets.getMaxToolTurnsPerWorker() > 0
                            ? budgets.getMaxToolTurnsPerWorker() : 120));
                    Button save = new Button("Save budgets", e -> {
                        try {
                            var dto = new BudgetsDto();
                            dto.setMaxCloudTokensPerRun(Long.parseLong(cloud.getValue().strip()));
                            dto.setMaxLocalTokensPerTask(Long.parseLong(local.getValue().strip()));
                            dto.setWallClockCeilingHours(Integer.parseInt(wall.getValue().strip()));
                            dto.setMaxToolTurnsPerWorker(Integer.parseInt(turns.getValue().strip()));
                            String result = control.saveGlobalBudgets(dto);
                            status.setText(result == null || result.isEmpty()
                                ? "saved — applies on restart" : result);
                            if (result != null && !result.isEmpty()) {
                                ClientLog.error("ProjectMenu", "global budgets save refused — runs "
                                    + "still spend against the old ceilings: " + result);
                            }
                        } catch (NumberFormatException nfe) {
                            status.setText("budgets must be whole numbers");
                        } catch (Exception ex) {
                            // Only the parse failure was caught before, so an RMI failure escaped
                            // into JS and left the operator looking at a status line that still
                            // said whatever it said before the click.
                            status.setText("save failed: " + ex.getMessage());
                            ClientLog.error("ProjectMenu", "global budgets save failed — runs still "
                                + "spend against the old ceilings: " + ex);
                        }
                    });
                    save.addClassName("btn-primary btn-sm mt-2 w-fit");
                    formHost.add(save);
                } catch (Exception e) {
                    status.setText("failed to load budgets: " + e.getMessage());
                    ClientLog.error("ProjectMenu", "global budgets did not load — the Budgets tab "
                        + "is empty and cannot be saved from: " + e);
                }
        };
        Runnable advancedTab = () -> {
            content.removeAll();
            status.setText("");
            content.add(new SettingsView());
        };

        tabBar.add(tab(tabBar, "Roles", rolesTab, true));
        tabBar.add(tab(tabBar, "Budgets", budgetsTab, false));
        // Named for both of the things it holds: the raw config, and the developer toggle that
        // decides whether Prompt Lab and Components appear in the shell at all.
        tabBar.add(tab(tabBar, "Advanced & developer", advancedTab, false));
        rolesTab.run();

        Button closeButton = new Button("Close", e -> dialog.close());
        dialog.addAction(closeButton);
        mount(dialog);
        dialog.open();
    }

    private Div tab(Div bar, String label, Runnable show, boolean active) {
        Div tab = new Div(label);
        tab.addClassName("tab" + (active ? " tab-active" : ""));
        tab.addDomEventListener("click", e -> {
            for (int i = 0; i < bar.getElement().getChildNodes().getLength(); i++) {
                Node child = bar.getElement().getChildNodes().get(i);
                if (child instanceof HTMLElement el) {
                    el.setClassName("tab");
                }
            }
            tab.addClassName("tab-active");
            show.run();
        });
        return tab;
    }

    /** A captioned budget box. The caption is the framework's — one look, defined once. */
    private static TextField labeled(Div host, String label, String value) {
        TextField field = new TextField();
        field.addClassName("input input-bordered input-sm font-mono");
        field.setValue(value);
        field.setLabel(label);
        host.add(field);
        return field;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("[\\s_-]+");
        return parts.length >= 2
            ? ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase()
            : name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    private static String avatarColor(String id) {
        String[] palette = {"bg-indigo-500", "bg-emerald-500", "bg-amber-500", "bg-rose-500",
            "bg-cyan-500", "bg-violet-500", "bg-lime-600", "bg-fuchsia-500"};
        int hash = id == null ? 0 : id.hashCode();
        return palette[Math.abs(hash) % palette.length];
    }
}

