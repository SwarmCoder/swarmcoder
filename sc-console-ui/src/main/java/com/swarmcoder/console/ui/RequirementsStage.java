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

import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.Resizer;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

/**
 * Stage 2 — the BRD graph and editor, with Knowledge beside it.
 *
 * <p>Knowledge is <b>not a destination</b>. It is the conventions, the canonical examples and the
 * house rules an analyst reads <em>while</em> writing a requirement, which is why it is a pane on
 * this stage and not an entry in a menu. Given its own slot in the navigation it became somewhere
 * you go and then leave, which is the opposite of reference material.
 *
 * <p>Collapsed by default, and the collapsed state is the full-height labelled edge you click — not
 * an icon. A reference pane you cannot find is worse than no reference pane. The choice is persisted
 * client-side like the theme, because it is about this browser, not about the project.
 */
final class RequirementsStage extends Div implements Disposable {

    private static final String KEY = "console.requirements.knowledge";
    private static final String VIEW_KEY = "console.requirements.view";

    /**
     * The tree is the default and the graph is a choice, not the other way round
     * ({@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.3).
     *
     * <p>The graph is kept, not replaced: it is the right tool for the relationships around one
     * requirement, and step 6 of that design scopes it to exactly that. What changed is which one an
     * operator lands on, because scanning a canvas is not how you find one requirement among hundreds.
     * The choice is persisted client-side like the Knowledge pane — it is about this browser.
     */
    private final RequirementTreeView tree = new RequirementTreeView();
    private final BrdView brd = new BrdView();
    /**
     * The guided intake, owned by the STAGE rather than by either lens.
     *
     * <p>Because it belongs to neither: adding a document acts on the requirement set, and the
     * button that opens it is above the tabs. It also has to be mounted somewhere that is on the
     * page whichever lens is showing — the stage hides the one you are not looking at, and a dialog
     * inside a hidden subtree is not drawn at all.
     */
    private final IntakeWizard intake = new IntakeWizard();
    private final Div treeHost = new Div();
    private final Div graphHost = new Div();
    private final Div treeTab = new Div();
    private final Div graphTab = new Div();
    private KnowledgeView knowledge;

    private final Div knowledgePane = new Div();
    private final Div knowledgeHost = new Div();
    private final Div edge = new Div();
    private final Resizer resizer;

    RequirementsStage() {
        addClassName("flex flex-row h-full min-h-0");

        Div main = new Div();
        // overflow-hidden, because BrdView's split pane carries an explicit, operator-set width and
        // cannot shrink below it. With the knowledge pane, the chat dock and the inspector all open
        // at once the editor runs out of room, and clipping is at least legible — without it the
        // graph legend renders straight through the panel beside it.
        main.addClassName("flex-1 min-w-0 min-h-0 flex flex-col overflow-hidden");
        main.add(viewSwitch());
        // BOTH are built and kept mounted, with one hidden. Rebuilding the graph on every switch would
        // discard the operator's hand-placed node positions' rendered state and re-issue its RMI loads,
        // and BrdView binds effects that only its own dispose() releases.
        treeHost.addClassName("flex-1 min-h-0 flex flex-col");
        treeHost.add(tree);
        graphHost.addClassName("flex-1 min-h-0 flex flex-col");
        graphHost.add(brd);
        main.add(treeHost);
        main.add(graphHost);
        add(main);
        // Both lenses reach the same wizard. The diagram's empty state offers it too, because that
        // is the one screen whose whole job is explaining where requirements come from.
        brd.onAnalyse(intake::open);
        intake.onApplied(brd::showStatus);
        // Last in this container and outside both lenses, so the modal is never a flex item in a
        // column and never inside a subtree the view switch has hidden.
        add(intake);
        // The list is how you FIND a requirement; the diagram is how you see what surrounds one. The
        // handover carries the requirement, not just the view change, which is what keeps the diagram
        // to a neighbourhood instead of the whole document.
        tree.onShowSurroundings(requirementId -> {
            // SHOW FIRST, then focus. The canvas fits its content by measuring its own offsetWidth and
            // gives up when that is zero — which it is while this pane is still hidden. Focusing first
            // therefore drew the neighbourhood at whatever scale and pan the canvas last had, and the
            // requirement the operator asked about sat off the right-hand edge, unreachable, on the one
            // screen opened to look at it.
            Js.localSet(VIEW_KEY, "graph");
            applyView(false);
            brd.focusOn(requirementId);
        });
        applyView(!"graph".equals(Js.localGet(VIEW_KEY)));

        // The collapsed state: a full-height strip carrying the word "Knowledge" down its edge, so
        // the pane advertises itself instead of hiding behind a glyph.
        edge.addClassName("shrink-0 w-7 border-l border-base-300 bg-base-200/40 cursor-pointer "
            + "flex items-center justify-center text-[11px] tracking-wider text-base-content/50 "
            + "hover:text-primary hover:bg-base-300/50");
        edge.setStyle("writing-mode", "vertical-rl");
        Span edgeLabel = new Span("Knowledge");
        edge.getElement().appendChild(edgeLabel.getElement());
        edge.getElement().setAttribute("title", "Show the knowledge base beside the requirements");
        // A DOM handler: opening builds KnowledgeView, whose constructor loads over RMI.
        edge.addDomEventListener("click", e -> setOpen(true));
        add(edge);

        knowledgePane.addClassName("w-[22rem] shrink-0 min-h-0 border-l border-base-300 "
            + "flex flex-col bg-base-200/20 overflow-hidden");
        knowledgePane.add(knowledgeHeader());
        knowledgeHost.addClassName("flex-1 min-h-0 flex flex-col");
        knowledgePane.add(knowledgeHost);
        resizer = new Resizer(knowledgePane, Resizer.Orientation.VERTICAL, true);
        add(resizer, knowledgePane);

        applyOpen("1".equals(Js.localGet(KEY)));
    }

    /**
     * The workspace's own bar: which lens on the left, and on the right the actions that belong to
     * the requirements themselves rather than to one way of looking at them.
     *
     * <p>This is where "Analyse documents" now lives, and the reason is a defect the author found by
     * using it: it was in the diagram's own header, so the List — which is the surface an operator
     * lands on ({@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.3) — offered no way at all to add a
     * document, and its empty state sent people off to find the other tab. Adding a document acts on
     * the requirement set. It cannot belong to one view of it.
     *
     * <p>"Past versions" is here for the same reason: a revision is a fact about the document. It
     * switches to the diagram when pressed, because browsing a past revision means drawing the graph
     * as it stood and the list has nothing to show instead.
     *
     * <p>"New requirement" is deliberately NOT lifted. Each view has its own editor and each already
     * offers the button, so it is reachable from both — which is the whole test being applied here.
     */
    private Div viewSwitch() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-1 px-3 py-1.5 border-b border-base-300 shrink-0 "
            + "bg-base-100");
        tab(treeTab, "List", "Every requirement, indented by what contains what", true);
        tab(graphTab, "Graph", "The requirements as a diagram of how they relate", false);
        bar.add(treeTab);
        bar.add(graphTab);

        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);

        // A labelled button, not an icon: getting documents in is the primary way a set of
        // requirements starts, and an unlabelled glyph made the one action that matters most
        // invisible. A DOM handler, so the wizard's RMI load has a threading context.
        Div analyse = new Div();
        analyse.addClassName("flex items-center gap-1.5 px-2 py-0.5 rounded cursor-pointer "
            + "text-[11px] bg-primary text-primary-content hover:opacity-90 shrink-0");
        analyse.getElement().setAttribute("data-testid", "requirements-analyse");
        analyse.getElement().setAttribute("title",
            "Add a specification, brief or note and have SwarmCoder draft requirements from it");
        analyse.add(Icon.of("upload", "w-3 h-3"));
        Span analyseLabel = new Span("Analyse documents");
        analyse.getElement().appendChild(analyseLabel.getElement());
        analyse.addDomEventListener("click", e -> intake.open());
        bar.add(analyse);

        Div history = new Div();
        history.addClassName("flex items-center gap-1.5 px-2 py-0.5 rounded cursor-pointer "
            + "text-[11px] text-base-content/60 hover:text-primary hover:bg-base-300/50 shrink-0");
        history.getElement().setAttribute("data-testid", "requirements-history");
        history.getElement().setAttribute("title",
            "Browse and restore past versions of the requirements — shown on the diagram");
        history.add(Icon.of("clock", "w-3 h-3"));
        Span historyLabel = new Span("Past versions");
        history.getElement().appendChild(historyLabel.getElement());
        history.addDomEventListener("click", e -> {
            // SHOW FIRST, then draw — the same ordering the surroundings handover below depends on,
            // and for the same reason: the canvas fits its picture by measuring its own width, which
            // is zero while this pane is hidden.
            Js.localSet(VIEW_KEY, "graph");
            applyView(false);
            brd.toggleHistory();
        });
        bar.add(history);
        return bar;
    }

    private void tab(Div tab, String label, String tooltip, boolean showsTree) {
        tab.addClassName("px-2 py-0.5 text-[11px] rounded cursor-pointer");
        Span text = new Span(label);
        tab.getElement().appendChild(text.getElement());
        tab.getElement().setAttribute("title", tooltip);
        tab.getElement().setAttribute("data-testid", "requirements-view-" + label.toLowerCase());
        // A DOM handler, so anything either view does on becoming visible is on a green thread.
        tab.addDomEventListener("click", e -> {
            Js.localSet(VIEW_KEY, showsTree ? "tree" : "graph");
            applyView(showsTree);
        });
    }

    private void applyView(boolean showTree) {
        treeHost.setVisible(showTree);
        graphHost.setVisible(!showTree);
        treeTab.removeClassName(showTree ? "text-base-content/50" : "bg-base-300");
        graphTab.removeClassName(showTree ? "bg-base-300" : "text-base-content/50");
        treeTab.addClassName(showTree ? "bg-base-300" : "text-base-content/50");
        graphTab.addClassName(showTree ? "text-base-content/50" : "bg-base-300");
        if (!showTree) {
            // SHOW, THEN DRAW — the same ordering the surroundings handover above already depends
            // on, for the same reason. The canvas scales its picture by measuring its own width,
            // which is zero while this pane is hidden, and the list is what the operator lands on.
            // So the diagram was always first laid out for a window of no width, and switching to it
            // showed requirements sitting outside the pane: invisible, unclickable, and — since the
            // list has no editor — unreachable by any route at all.
            brd.redraw();
        }
    }

    @Override
    public void dispose() {
        tree.dispose();
        brd.dispose();
        intake.dispose();
        // KnowledgeView binds effects to its own signals; it is built lazily, so it may not exist.
        if (knowledge != null) {
            knowledge.dispose();
        }
    }

    private Div knowledgeHeader() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 shrink-0");
        Span title = new Span("Knowledge");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        Span note = new Span("what workers and the analyst read");
        note.addClassName("text-[10px] text-base-content/40 truncate");
        bar.getElement().appendChild(note.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        Div hide = new Div();
        hide.addClassName("cursor-pointer text-base-content/40 hover:text-error");
        hide.add(Icon.of("x", "w-3.5 h-3.5"));
        hide.getElement().setAttribute("title", "Hide the knowledge pane");
        hide.addDomEventListener("click", e -> setOpen(false));
        bar.add(hide);
        return bar;
    }

    /** Toggles and persists. Called only from DOM handlers, so building the view here is safe. */
    private void setOpen(boolean open) {
        Js.localSet(KEY, open ? "1" : "0");
        applyOpen(open);
    }

    private void applyOpen(boolean open) {
        if (open && knowledge == null) {
            knowledge = new KnowledgeView();
            knowledgeHost.add(knowledge);
        }
        knowledgePane.setVisible(open);
        resizer.setVisible(open);
        edge.setVisible(!open);
    }
}
