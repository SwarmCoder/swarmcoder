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

import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ReadinessSignals;
import com.swarmcoder.console.api.ChatService_Stub;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import java.util.ArrayList;
import java.util.List;

/**
 * The Console shell, organised around the PROCESS rather than around a menu.
 *
 * <p>It began as a flat sidebar that mixed stages, inputs to stages and developer tools, all reachable
 * at once, so the workspace routinely showed the requirements editor, the backlog and a story inspector
 * side by side. That was replaced by a four-step stepper, which fixed the clutter and introduced a
 * different lie — a numbered progress front across a process that is not sequential. Both are gone.
 *
 * <p><b>Two workspaces</b> (UX v3 §3): {@link Stage#REQUIREMENTS}, the pool, and {@link Stage#PLAN},
 * the pipeline board. Everything else is reachable without being on the path — Setup from the header's
 * health dot, the reference faces from the ⋯ overflow, developer tools when the toggle is on.
 *
 * <p>Layout: {@link StatusHeader} · workspace · chat dock · inspector, with the status bar below.
 *
 * <ul>
 *   <li><b>Which project</b> is a header chip that opens {@link ProjectMenu}. It was a permanent
 *       16rem rail on every screen, for a fact that changes a few times a day (§8.4).</li>
 *   <li>The <b>chat dock</b> is a companion, not a workspace: you ask what the codebase does while
 *       reading requirements and while a build is going wrong. See {@link ChatDock}.</li>
 *   <li><b>Prompt Lab</b> and <b>Components</b> are off the operator's navigation entirely until the
 *       developer toggle in Settings is switched on. See {@link DevTools}.</li>
 * </ul>
 *
 * <h2>Two rules this class exists to keep</h2>
 *
 * <p><b>Disposal.</b> Views hold {@code Disposable}s bound to process-wide shared signals, and an
 * effect bound to one outlives the view that created it unless it is disposed. This view owns the
 * long-lived children ({@link ProjectMenu}, {@link StatusHeader}, {@link StageHost},
 * {@link ChatDock}) and tears every one of them down in {@link #dispose()}; a workspace swap disposes
 * nothing, because {@link StageHost} keeps surfaces alive rather than rebuilding them.
 *
 * <p><b>Threading.</b> RMI may only suspend inside a DOM event handler or a green thread. Stage
 * construction talks to the server, so it is reached from header clicks (DOM handlers) and, at
 * boot, from a {@code new Thread(...)} — never directly from a signal {@link Effect}, which throws
 * "Suspension point reached from non-threading context". {@link #refreshRuns()} documents the
 * workaround in full.
 */
final class MainView extends Div implements com.zeroz4j.api.Disposable {

    private final StageHost stageHost = new StageHost();
    private final ChatDock chatDock = new ChatDock();
    /** The drill-down's home in the shell: the right-hand rail. */
    private final Div inspector = new Div();
    /**
     * The drill-down's other home: a dialog, for when it was opened from inside one.
     *
     * <p>Held rather than built per use so that going deeper — a candidate, then its transcript,
     * then the full payload — reuses the one panel and only changes what is in it, which is what
     * makes the breadcrumbs mean anything.
     */
    private final com.zeroz4j.ui.component.Dialog inspectorDialog =
        new com.zeroz4j.ui.component.Dialog();
    private final Div inspectorDialogHost = new Div();
    /**
     * The crumbs and the deepest level, built once and placed in whichever home is in use. The two
     * homes therefore cannot drift apart: there is one drill-down, drawn in two places.
     */
    private final Div inspectorBody = new Div();
    private final Div inspectorResizer = new com.zeroz4j.ui.component.Resizer(inspector,
        com.zeroz4j.ui.component.Resizer.Orientation.VERTICAL, true);
    /**
     * Holds at most one shell-level modal — the project menu, the overflow, and anything they open.
     *
     * <p>Declared BEFORE the menu that writes into it: field initialisers run in order, and a menu
     * handed a null host would mount its dialogs nowhere.
     */
    private final Div modalHost = new Div();
    private final ProjectMenu projectMenu =
        new ProjectMenu(this::onProjectSwitched, modalHost, this::closeShellModals);
    /**
     * Asks which project to work on, and does not let go until it is told. Up at start-up and again
     * after any project is deleted; see {@link ProjectPicker} for why nothing opens itself.
     */
    private final ProjectPicker projectPicker =
        new ProjectPicker(projectMenu, this::onProjectSwitched);
    private final StatusHeader header = new StatusHeader(this::selectStage,
        this::openProjectMenu, () -> selectStage(Stage.SETUP), this::openOverflow, this::createChat);
    private final Div dialogHost = new Div();
    /**
     * The status bar's one error slot; empty means nothing is wrong. A background refresh that
     * fails leaves a list showing stale or empty data, which looks exactly like "there is nothing
     * here" — so it has to be said somewhere the user is already looking, not only in the server
     * log. One slot, last failure wins, cleared by the next refresh that succeeds: the durable
     * record is the log, and a warning that outlives its cause is itself misinformation.
     *
     * <p>Static because two of the three writers are — the list refreshes run from the bootstrap
     * and from push callbacks, with no instance in hand.
     */
    static final ValueSignal<String> statusMessage = new ValueSignal<>("");
    /** Effects this view owns on process-wide signals; every one is released in {@link #dispose}. */
    private final List<com.zeroz4j.api.Disposable> disposables = new ArrayList<>();

    MainView() {
        // Restore the persisted theme before anything renders.
        String theme = Js.localGet("console.theme");
        Js.setTheme("light".equals(theme) ? "light" : "dark");

        addClassName("flex flex-col h-screen w-screen overflow-hidden");

        // One header carrying all of the navigation: identity, counts, health, the single next step,
        // and the two workspaces. Nothing else in the shell says where you are or what to do.
        add(header);

        Div body = new Div();
        body.addClassName("flex flex-row flex-1 min-h-0");
        body.add(stageHost);
        // The handle follows the pane it resizes: left behind visible, it is a live-looking grab
        // strip on the edge of a workspace with nothing on the other side of it.
        Div chatResizer = new com.zeroz4j.ui.component.Resizer(chatDock,
            com.zeroz4j.ui.component.Resizer.Orientation.VERTICAL, true);
        disposables.add(Effect.create(
            () -> chatResizer.setVisible(Boolean.TRUE.equals(ChatDock.open.get()))));
        body.add(chatResizer);
        body.add(chatDock);

        // The shell's right-hand home for the drill-down. It is not the only one: opened from
        // inside a dialog the same levels are drawn as a dialog instead, because since ZeroZ Stack
        // 0.8.0 a dialog is a real one and nothing in the ordinary page can be clicked while one is
        // open. See Inspector for the whole reasoning; bindInspector does the placing.
        //
        // Its own opaque surface: a 40%-tinted panel was fine while it was flush in the shell with
        // the page background behind it, and it let a dialog read straight through the words on it
        // the moment it was over one.
        inspector.addClassName("w-96 shrink-0 bg-base-200 border-l border-base-300 shadow-xl "
            + "min-h-0 overflow-y-auto flex flex-col relative");
        // Tests scope drill-down assertions to this. The Plan stage no longer draws a story twice,
        // but a card and the panel it opens still carry the same verbs, so "click Accept" has to say
        // WHICH Accept.
        inspector.getElement().setAttribute("data-testid", "inspector");
        // The handle follows the pane it resizes, exactly as the chat dock's does: left behind
        // visible it is a live-looking grab strip on the edge of a workspace with nothing beyond it.
        body.add(inspectorResizer);
        body.add(inspector);
        bindInspector();

        add(body);
        add(statusBar());
        add(dialogHost);
        add(modalHost);
        // Last of the shell-level modals, so it is drawn over whatever else is up.
        add(projectPicker);
        // The drill-down's dialog home lives with the other dialog hosts, at the shell level: a
        // dialog nested inside a transformed .modal-box is laid out and clipped inside it.
        add(inspectorDialogHost);

        // Cross-view navigation, all of it now expressed as "which stage, and what in it".
        Nav.openStage = this::selectStage;
        Nav.openRun = stageHost::openRun;
        Nav.openChat = chatDock::openChat;
        Nav.openBacklogBoard = () -> selectStage(Stage.PLAN);
        Nav.openRequirements = () -> selectStage(Stage.REQUIREMENTS);
        // A question is answered on the card of the story it belongs to, so "show me what is owed"
        // resolves to the pipeline. Anything that still asks for approvals by name lands there rather
        // than on a queue that no longer exists.
        Nav.openApprovals = () -> selectStage(Stage.PLAN);

        // ⌘K command palette — one launcher over chats, runs, stages, and actions.
        CommandPalette palette = new CommandPalette(this::openNamedView, chatDock::openChat,
            runId -> Nav.openRun.accept(runId), this::createChat);
        add(palette);
        Window.current().getDocument().getDocumentElement().addEventListener("keydown",
            (EventListener<KeyboardEvent>) e -> {
                if ("k".equalsIgnoreCase(Keys.of(e)) && (e.isCtrlKey() || e.isMetaKey())) {
                    e.preventDefault();
                    palette.toggle();
                }
            });

        refreshChats();
        refreshRuns();
        bindReadiness();
    }

    /**
     * Tears down the children that bind {@link Effect}s to long-lived shared signals. Those signals
     * are process-wide, so an effect bound to one outlives the view that created it unless it is
     * disposed; this view owns the ones that do.
     */
    @Override
    public void dispose() {
        projectMenu.dispose();
        projectPicker.dispose();
        header.dispose();
        stageHost.dispose();
        chatDock.dispose();
        for (com.zeroz4j.api.Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /**
     * Selects a stage. The whole workspace swaps; nothing is added beside anything.
     *
     * <p>Callers must be a DOM event handler or a green thread — building a stage constructs views
     * whose constructors talk to the server.
     */
    private void selectStage(Stage stage) {
        // The operator has taken the wheel; readiness stops steering from here on.
        landed = true;
        Stages.SELECTED.set(stage);
        stageHost.show(stage);
    }

    /**
     * Opens a named surface — the command palette's vocabulary, and the shell's own shorthand. Every
     * id resolves to a stage, because there is nowhere else for anything to be.
     */
    void openNamedView(String id) {
        switch (id) {
            case "setup" -> selectStage(Stage.SETUP);
            case "brd", "requirements", "knowledge" -> selectStage(Stage.REQUIREMENTS);
            case "backlog", "plan" -> selectStage(Stage.PLAN);
            case "build" -> selectStage(Stage.BUILD);
            // Build's faces: select the stage, then the face.
            // "board" still routes, for anything that remembers the old id, but it lands on Build
            // itself rather than a face: the swarm board stopped being a destination when workers
            // moved inside the run they belong to.
            case "board" -> selectStage(Stage.BUILD);
            // "decisions" still routes, for anything that remembers the old id — to the pipeline,
            // where questions are actually answered now.
            case "decisions" -> selectStage(Stage.PLAN);
            case "guidelines", "insights" -> {
                selectStage(Stage.BUILD);
                stageHost.openBuildTab(id);
            }
            case "promptlab" -> selectStage(Stage.PROMPT_LAB);
            case "gallery" -> selectStage(Stage.COMPONENTS);
            default -> { }
        }
    }

    /** Creates a chat server-side, refreshes the list, and opens it in the dock. */
    private void createChat() {
            try {
                var stub = new ChatService_Stub();
                String id = stub.createChat("New chat");
                ChatStore.chats.set(new ArrayList<>(stub.chats()));
                chatDock.openChat(id, "New chat");
            } catch (Exception e) {
                // The user pressed ＋ and no chat appeared. Nothing else in this flow reports it, so
                // without a word in the status bar the button just looks broken and the natural
                // response is to press it again.
                ClientLog.error("MainView", "creating a chat failed — no chat was created and no "
                    + "chat was opened: " + e);
                statusMessage.set("Could not create the chat — nothing was saved.");
            }
    }

    /**
     * The project menu, over whatever is on screen.
     *
     * <p>Owned here rather than by the header because it is the shell that has to react to a switch:
     * every surface is scoped to a project, so after one none of the built ones is showing the right
     * thing. Runs from a DOM handler, which is the threading context {@link ProjectMenu} needs.
     */
    private void openProjectMenu() {
        com.zeroz4j.ui.component.Dialog dialog = new com.zeroz4j.ui.component.Dialog();
        Div title = new Div("Projects");
        title.addClassName("text-lg font-bold mb-2");
        dialog.add(title, projectMenu);
        com.zeroz4j.ui.component.Button close =
            new com.zeroz4j.ui.component.Button("Close", e -> closeShellModal(dialog));
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        dialogHost.removeAll();
        dialogHost.add(dialog);
        dialog.open();
    }

    /**
     * Closes a shell modal AND drops it from the document.
     *
     * <p>Closing a {@code Dialog} only hides it. A hidden one left in place keeps every control it
     * carries — including a "Close" button that can never be clicked — and those shadow the identically
     * named controls of whatever it just opened. The overflow's Settings row hit exactly that: the
     * settings dialog appeared, and the next click on "Close" resolved to the retired overflow's.
     */
    private void closeShellModal(com.zeroz4j.ui.component.Dialog dialog) {
        dialog.close();
        dialogHost.removeAll();
    }

    /**
     * Retires whatever shell modal is up. Handed to {@link ProjectMenu} so that using the menu closes
     * it — see the reasoning on its {@code onLeaving} field.
     */
    private void closeShellModals() {
        dialogHost.removeAll();
    }

    /**
     * The ⋯ overflow: present, never on the main path (UX v3 §3).
     *
     * <p>Everything an operator reads ABOUT the work rather than doing to it, plus configuration and
     * the developer tools. It is a menu rather than a set of tabs because none of it is a place you
     * work: putting Guidelines beside Requirements as a peer destination is how the original sidebar
     * ended up with eight equal-weight entries and no model.
     */
    private void openOverflow() {
        com.zeroz4j.ui.component.Dialog dialog = new com.zeroz4j.ui.component.Dialog();
        Div title = new Div("More");
        title.addClassName("text-lg font-bold mb-1");
        Div hint = new Div("Read about the work, and configure the machinery. Nothing here is a step.");
        hint.addClassName("text-xs text-base-content/60 mb-3");
        Div list = new Div();
        list.addClassName("flex flex-col");
        dialog.add(title, hint, list);

        list.add(overflowRow("book", "Guidelines",
            "The standing instructions every worker is given.", () -> {
                closeShellModal(dialog);
                selectStage(Stage.BUILD);
                stageHost.openBuildTab("guidelines");
            }));
        list.add(overflowRow("chart", "Insights",
            "What came of it all — survival, selection, spend.", () -> {
                closeShellModal(dialog);
                selectStage(Stage.BUILD);
                stageHost.openBuildTab("insights");
            }));
        list.add(overflowRow("gear", "Setup",
            "The project's folder, the models, and somewhere to run.", () -> {
                closeShellModal(dialog);
                selectStage(Stage.SETUP);
            }));
        list.add(overflowRow("gear", "Settings",
            "Roles, budgets, developer tools, raw configuration.", () -> {
                closeShellModal(dialog);
                Nav.openGlobalSettings.run();
            }));
        if (Boolean.TRUE.equals(DevTools.enabled.get())) {
            list.add(overflowRow("diff", "Prompt Lab",
                "Developer tool — the exact prompt each worker received.", () -> {
                    closeShellModal(dialog);
                    selectStage(Stage.PROMPT_LAB);
                }));
            list.add(overflowRow("grip", "Components",
                "Developer tool — the component gallery.", () -> {
                    closeShellModal(dialog);
                    selectStage(Stage.COMPONENTS);
                }));
        }

        com.zeroz4j.ui.component.Button close =
            new com.zeroz4j.ui.component.Button("Close", e -> closeShellModal(dialog));
        close.addClassName("btn-sm btn-primary");
        dialog.addAction(close);
        dialogHost.removeAll();
        dialogHost.add(dialog);
        dialog.open();
    }

    private Div overflowRow(String icon, String label, String caption, Runnable action) {
        Div row = new Div();
        row.addClassName("flex items-start gap-3 px-2 py-2 rounded cursor-pointer "
            + "hover:bg-base-300/50");
        row.getElement().setAttribute("data-testid", "overflow-" + label.toLowerCase());
        Div mark = new Div();
        mark.addClassName("shrink-0 pt-0.5 text-base-content/50");
        mark.add(Icon.of(icon, "w-4 h-4"));
        row.add(mark);
        Div text = new Div();
        text.addClassName("flex flex-col min-w-0");
        Span name = new Span(label);
        name.addClassName("text-sm font-medium");
        text.getElement().appendChild(name.getElement());
        Span why = new Span(caption);
        why.addClassName("text-[11px] text-base-content/50 leading-snug");
        text.getElement().appendChild(why.getElement());
        row.add(text);
        row.addDomEventListener("click", e -> action.run());
        return row;
    }

    /**
     * Reloads the Runs list. The RMI call runs on a TeaVM green thread because both of this
     * method's callers are non-threading contexts: the {@code MainView} constructor (client
     * bootstrap) and the {@code run-graph} push listener (a native JS callback). A suspending call
     * made directly from either throws "Suspension point reached from non-threading context" and
     * the list stays empty forever. Deferring the repaint by a tick is the whole cost.
     */
    static void refreshRuns() {
        new Thread(() -> {
            try {
                RunsStore.runs.set(new ArrayList<>(
                    new ObserverService_Stub().listRuns()));
                statusMessage.set("");
            } catch (Exception e) {
                // The rail keeps whatever it last held — for the bootstrap call that is an empty
                // list, which is indistinguishable from "this project has no runs". The user is
                // entitled to know which of the two they are looking at.
                ClientLog.error("MainView", "loading the run list failed — the Runs rail is "
                    + "showing stale or empty data: " + e);
                statusMessage.set("Run list is out of date — could not reach the server.");
            }
        }).start();
    }

    /** Reloads the Chats list on a green thread — same bootstrap constraint as {@link #refreshRuns}. */
    static void refreshChats() {
        new Thread(() -> {
            try {
                ChatStore.chats.set(new ArrayList<>(
                    new ChatService_Stub().chats()));
                statusMessage.set("");
            } catch (Exception e) {
                // Same reasoning as refreshRuns: an empty chat list reads as "you have no chats",
                // which after a failed load is the one thing it does not mean.
                ClientLog.error("MainView", "loading the chat list failed — the chat picker is "
                    + "showing stale or empty data: " + e);
                statusMessage.set("Chat list is out of date — could not reach the server.");
            }
        }).start();
    }

    /**
     * True once the operator is in charge of the selection. Set by their first click, and by the
     * first readiness that actually describes a project — after either, nothing moves them.
     */
    private boolean landed;
    /** The stage the shell auto-selected, so a repeat recommendation is not re-applied. */
    private Stage landing;

    /**
     * Follows readiness — until the operator takes over, and never after.
     *
     * <p>On load the shell lands on the stage the server recommends, because a first screen that
     * ignores what the server already knows is a wasted screen. The wrinkle is that the readiness
     * signal starts at {@link ConsoleReadiness#empty()} and the server's real value arrives a moment
     * later over the socket, so latching on the very first value would mean always landing on Setup
     * and calling it "what the server recommended". So the shell follows the recommendation until
     * the first readiness that actually has a project — and stops there, or sooner if the operator
     * clicks a stage.
     *
     * <p><b>After that it never moves them.</b> A workspace that reassigns itself while you are
     * working in it is worse than one that started in the wrong place, and readiness moves for
     * reasons that have nothing to do with you — another surface promoting a draft, a run finishing.
     * The recommendation stays visible in {@link StatusHeader}'s guidance line instead, where it
     * informs without interrupting.
     *
     * <p>The selection is deferred onto a green thread: this runs inside an {@link Effect}, and
     * building a stage constructs views whose constructors talk to the server. That is the same
     * constraint, and the same workaround, as {@link #refreshRuns()}.
     */
    private void bindReadiness() {
        disposables.add(Effect.create(() -> {
            ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
            if (!landed) {
                Stage recommended = Stages.recommended(readiness);
                if (recommended != landing) {
                    landing = recommended;
                    Stages.SELECTED.set(recommended);
                    new Thread(() -> stageHost.show(recommended)).start();
                }
                if (readiness != null && readiness.hasProject()) {
                    landed = true;
                }
                return;
            }
            // A project appearing (or going away) changes only whether the stage the operator
            // already chose can show anything — not which stage they are in.
            if (stageHost.gateStale()) {
                new Thread(stageHost::refreshGate).start();
            }
        }));
    }

    /**
     * Draws the drill-down, and puts it in whichever of its two homes the occasion calls for.
     *
     * <p>The rail when it was opened from an ordinary screen; a dialog when it was opened from
     * inside one, because a dialog is now a native one and everything outside it is inert. The
     * levels, the breadcrumbs and the close control are built once and moved, so the two homes
     * cannot disagree about what a drill-down is.
     *
     * <p>Escape and a click outside close the dialog without asking us, so the dialog's own close
     * listener is what puts the drill-down away — not the close control, which is no longer the
     * only way out.
     */
    private void bindInspector() {
        inspectorBody.addClassName("flex flex-col min-h-0 flex-1");
        inspectorDialog.setWidth("42rem");
        inspectorDialog.getElement().setAttribute("data-testid", "inspector-dialog");
        inspectorDialog.addCloseListener(e -> Inspector.close());
        inspectorDialogHost.add(inspectorDialog);

        disposables.add(Effect.create(() -> {
            boolean visible = Boolean.TRUE.equals(Inspector.visible.get());
            List<Inspector.Level> stack = Inspector.stack.get();
            boolean asDialog = Boolean.TRUE.equals(Inspector.overDialog.get());
            boolean showing = visible && !stack.isEmpty();

            inspector.setVisible(showing && !asDialog);
            inspectorResizer.setVisible(showing && !asDialog);
            if (!showing || !asDialog) {
                inspectorDialog.close();
            }
            inspector.removeAll();
            inspectorDialog.removeAll();
            inspectorBody.removeAll();
            if (stack.isEmpty()) {
                inspector.add(new EmptyState("search", "Inspector",
                    "Click a graph node or a message to inspect it here."));
                return;
            }
            Div crumbs = new Div();
            crumbs.addClassName("flex items-center gap-1 px-3 py-2 border-b border-base-300 "
                + "text-xs overflow-x-auto shrink-0");
            for (int i = 0; i < stack.size(); i++) {
                final int depth = i;
                if (i > 0) {
                    crumbs.add(Icon.of("chevron-right", "w-3 h-3 opacity-40"));
                }
                Span crumb = new Span(stack.get(i).title());
                crumb.addClassName(i == stack.size() - 1
                    ? "font-semibold whitespace-nowrap"
                    : "text-base-content/50 hover:text-primary cursor-pointer whitespace-nowrap");
                crumb.addDomEventListener("click", e -> Inspector.popTo(depth));
                crumbs.getElement().appendChild(crumb.getElement());
            }
            Div closeButton = new Div();
            closeButton.addClassName("ml-auto cursor-pointer text-base-content/40 hover:text-error");
            closeButton.add(Icon.of("x", "w-3.5 h-3.5"));
            closeButton.addDomEventListener("click", e -> Inspector.close());
            crumbs.add(closeButton);
            inspectorBody.add(crumbs);

            Div content = new Div();
            content.addClassName("flex-1 min-h-0 overflow-y-auto");
            content.add(stack.get(stack.size() - 1).content());
            inspectorBody.add(content);

            if (asDialog) {
                inspectorDialog.add(inspectorBody);
                if (showing && !inspectorDialog.isOpened()) {
                    inspectorDialog.open();
                }
            } else {
                inspector.add(inspectorBody);
            }
        }));
    }

    private Div statusBar() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-4 px-4 h-7 shrink-0 bg-base-200 border-t "
            + "border-base-300 text-[11px] text-base-content/50");
        bar.add(statusChip("Console connected"));
        // Sits next to "Console connected" on purpose: that chip is the claim, and a failed refresh
        // is the qualification to it. Empty text renders as nothing, so the bar is unchanged while
        // everything is working.
        Div problem = new Div();
        problem.addClassName("whitespace-nowrap text-error font-semibold");
        disposables.add(Effect.create(() -> problem.setText(statusMessage.get())));
        bar.add(problem);
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        bar.add(new HealthStrip());
        return bar;
    }

    private Div statusChip(String text) {
        Div chip = new Div(text);
        chip.addClassName("whitespace-nowrap");
        return chip;
    }

    /**
     * Every stage is scoped to a project, so after a switch none of the built ones is showing the
     * right thing. This is the one moment rebuilding is correct rather than wasteful — and
     * {@link StageHost#reset()} disposes what it drops, so nothing is left bound.
     *
     * <p>Runs from the project rail's click handler, which is a threading context.
     */
    private void onProjectSwitched() {
        stageHost.reset();
        refreshChats();
        refreshRuns();
    }
}
