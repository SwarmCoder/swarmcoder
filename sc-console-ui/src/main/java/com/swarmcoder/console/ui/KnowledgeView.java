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
import com.swarmcoder.domain.KnowledgeDoc;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.KeyedList;
import com.zeroz4j.ui.component.SplitPane;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;
import com.zeroz4j.signals.Effect;

/**
 * The Knowledge editor (author requirement 2026-07-14): curate the conventions, examples,
 * and best practices that feed every brief and the chat analyst. Docs are store-first
 * {@code KnowledgeDoc} OBJECTS — never markdown files. Post-run extraction proposes
 * entries (amber PROPOSED badge) which the operator accepts, edits, or deletes.
 */
final class KnowledgeView extends Div implements com.zeroz4j.api.Disposable {

    /** Effects this view owns; released when the Requirements stage tears the pane down. */
    private final List<com.zeroz4j.api.Disposable> disposables = new ArrayList<>();

    private final ControlService control = new ControlService_Stub();
    private final ValueSignal<List<KnowledgeDoc>> docs = new ValueSignal<>(new ArrayList<>());
    private final ValueSignal<KnowledgeDoc> selected = new ValueSignal<>(null);
    private final TextField titleField = new TextField();
    private final TextArea editor = new TextArea();
    private final Div status = new Div();

    KnowledgeView() {
        addClassName("flex flex-col h-full min-h-0");
        // Narrower than it once was: this is no longer a full-width destination but a reference pane
        // beside the requirements editor, and a 300px entry list left the editor a strip. Draggable
        // and persisted, so an operator who wants the old proportions sets them once.
        SplitPane split = SplitPane.horizontal("knowledge", 180, 120, 420);
        split.setFirst(listPane());
        split.setSecond(editorPane());
        add(split);
        refresh();
    }

    /** Releases the effects. The Requirements stage owns this pane and disposes it. */
    @Override
    public void dispose() {
        for (com.zeroz4j.api.Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    private Div listPane() {
        Div pane = new Div();
        pane.addClassName("flex flex-col min-h-0 h-full bg-base-200/40");
        Div header = new Div();
        header.addClassName("flex items-center justify-between px-3 py-2 border-b border-base-300");
        Span title = new Span("Knowledge");
        title.addClassName("text-xs font-semibold uppercase tracking-wider text-base-content/50");
        Div actions = new Div();
        actions.addClassName("flex items-center gap-1.5");
        Div research = new Div();
        research.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
        research.add(Icon.of("search", "w-3.5 h-3.5"));
        research.getElement().setAttribute("title",
            "Research — the agent scours the web + docs and files proposals");
        research.addDomEventListener("click", e -> startResearch());
        actions.add(research);
        Div newDoc = new Div();
        newDoc.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
        newDoc.add(Icon.of("plus", "w-3.5 h-3.5"));
        newDoc.getElement().setAttribute("title", "New knowledge entry");
        newDoc.addDomEventListener("click", e -> {
            KnowledgeDoc fresh = new KnowledgeDoc();
            fresh.setTitle("New entry");
            fresh.setBody("Document the convention, rule, or canonical example here.");
            fresh.setStatus("ACTIVE");
            selected.set(fresh);
            titleField.setValue(fresh.getTitle());
            editor.setValue(fresh.getBody());
        });
        Div refreshButton = new Div();
        refreshButton.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
        refreshButton.add(Icon.of("refresh", "w-3.5 h-3.5"));
        refreshButton.addDomEventListener("click", e -> refresh());
        actions.add(newDoc, refreshButton);
        header.getElement().appendChild(title.getElement());
        header.add(actions);
        pane.add(header);

        Div list = new Div();
        list.addClassName("flex-1 min-h-0 overflow-y-auto p-2 flex flex-col gap-0.5");
        pane.add(list);
        // Key includes the mutable fields: KeyedList keeps same-key DOM untouched, so a
        // status/title change must produce a new key to re-render the row.
        // Kept and disposed: a keyed list watches its signal for as long as it exists (ZeroZ
        // Stack 0.8.0), and the Requirements stage tears this pane down and rebuilds it.
        disposables.add(new KeyedList<>(list, docs,
            doc -> (doc.getId() == null ? "" : doc.getId().toString()) + ":" + doc.getStatus() + ":" + doc.getTitle(), this::docRow));

        researchStatus.addClassName("px-3 py-1.5 text-[11px] border-t border-base-300 "
            + "text-base-content/50 truncate");
        researchStatus.setVisible(false);
        pane.add(researchStatus);
        return pane;
    }

    private final Div researchStatus = new Div();
    private boolean researchPolling;

    /** Kicks off a research mission and polls its status until it settles. */
    private void startResearch() {
        // Unwrapped, a throw here escapes out of the DOM click handler into JS, so the magnifier
        // icon does nothing at all and no mission was started — the failure mode that reads as a
        // dead button. The status strip is hidden until it has something to say, so a failure has
        // to switch it on itself.
        try {
            String result = control.startResearch("");
            if (result != null && result.startsWith("error")) {
                showResearch(result);
                ClientLog.error("KnowledgeView", "research mission refused — no research is "
                    + "running and no proposals will appear: " + result);
                return;
            }
            pollResearch();
        } catch (Exception ex) {
            researchStatus.setText("could not start research: " + ex.getMessage());
            researchStatus.setVisible(true);
            ClientLog.error("KnowledgeView", "research mission failed to start — no research is "
                + "running and no proposals will appear: " + ex);
        }
    }

    private void pollResearch() {
        if (researchPolling) {
            return;
        }
        researchPolling = true;
        
            try {
                for (int i = 0; i < 240; i++) { // up to ~20 min
                    String status = control.researchStatus();
                    showResearch(status);
                    if (status == null || status.isEmpty() || status.equals("idle")
                            || !status.startsWith("researching")) {
                        refresh(); // new PROPOSED docs appear
                        break;
                    }
                    Thread.sleep(5_000);
                }
            } catch (Exception ex) {
                // The strip keeps whatever the last poll wrote — normally "🔎 researching…" — so
                // the mission looks like it is still going when nothing is watching it any more.
                // The loop has already exited, so this reports once, not once per poll.
                researchStatus.setText("research status lost: " + ex.getMessage());
                researchStatus.setVisible(true);
                ClientLog.error("KnowledgeView", "research status polling stopped — the mission "
                    + "may still be running but the list will not refresh when it finishes: " + ex);
            } finally {
                researchPolling = false;
            }
    }

    private void showResearch(String text) {
        boolean show = text != null && !text.isEmpty() && !text.equals("idle");
        researchStatus.setVisible(show);
        if (show) {
            researchStatus.setText((text.startsWith("researching") ? "🔎 " : "✓ ") + text);
        }
    }

    private Div docRow(KnowledgeDoc doc) {
        boolean proposed = "PROPOSED".equals(doc.getStatus());
        Div row = new Div();
        row.addClassName("group flex items-center gap-2 px-2.5 py-1.5 rounded-lg cursor-pointer "
            + "text-xs hover:bg-base-300/60");
        row.add(Icon.of("file", "w-3.5 h-3.5 " + (proposed ? "text-warning" : "opacity-50")));
        Span name = new Span(doc.getTitle() == null || doc.getTitle().isEmpty()
            ? doc.getSlug() : doc.getTitle());
        name.addClassName("truncate flex-1" + (proposed ? " text-warning" : ""));
        row.getElement().appendChild(name.getElement());
        if (proposed) {
            Span badge = new Span("proposed");
            badge.addClassName("badge badge-warning badge-xs");
            row.getElement().appendChild(badge.getElement());
            Div accept = new Div();
            accept.addClassName("opacity-0 group-hover:opacity-100 cursor-pointer "
                + "text-success hover:scale-110");
            accept.add(Icon.of("check", "w-3.5 h-3.5"));
            accept.getElement().setAttribute("title", "Accept — the entry starts feeding briefs");
            accept.addDomEventListener("click", e -> {
                e.stopPropagation();
                // Wrapped because a throw would escape into JS and leave the row untouched, which
                // is indistinguishable from an accept that worked but did not re-render.
                try {
                    String result = control.acceptKnowledgeDoc(doc.getId().toString());
                    if (result != null && result.startsWith("error")) {
                        status.setText(result);
                        ClientLog.error("KnowledgeView", "knowledge accept refused — \""
                            + doc.getTitle() + "\" is still PROPOSED and is not feeding briefs: "
                            + result);
                    }
                } catch (Exception ex) {
                    status.setText("accept failed: " + ex.getMessage());
                    ClientLog.error("KnowledgeView", "knowledge accept failed — \"" + doc.getTitle()
                        + "\" is still PROPOSED and is not feeding briefs: " + ex);
                }
                refresh();
            });
            row.add(accept);
        }
        Div delete = new Div();
        delete.addClassName("opacity-0 group-hover:opacity-100 cursor-pointer "
            + "text-base-content/40 hover:text-error");
        delete.add(Icon.of("x", "w-3.5 h-3.5"));
        delete.getElement().setAttribute("title", "Delete");
        delete.addDomEventListener("click", e -> {
            e.stopPropagation();
            // A delete that throws out of the handler skips the refresh below too, so the row
            // stays on screen — which the operator reads as "the click missed" and repeats,
            // rather than as an entry that is still in the store and still feeding briefs.
            try {
                control.deleteKnowledgeDoc(doc.getId().toString());
                KnowledgeDoc current = selected.get();
                if (current != null && doc.getId() != null && doc.getId().equals(current.getId())) {
                    selected.set(null);
                }
            } catch (Exception ex) {
                status.setText("delete failed: " + ex.getMessage());
                ClientLog.error("KnowledgeView", "knowledge delete failed — \"" + doc.getTitle()
                    + "\" is still stored and still feeding briefs: " + ex);
            }
            refresh();
        });
        row.add(delete);
        row.addDomEventListener("click", e -> {
            selected.set(doc);
            titleField.setValue(doc.getTitle());
            editor.setValue(doc.getBody());
            status.setText("");
        });
        return row;
    }

    private Div editorPane() {
        Div pane = new Div();
        pane.addClassName("flex flex-col min-h-0 h-full");
        Div header = new Div();
        header.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 text-sm");
        titleField.addClassName("input input-bordered input-xs flex-1 font-medium");
        titleField.getElement().setAttribute("placeholder", "title");
        Button save = new Button("Save");
        save.addClassName("btn-primary btn-xs");
        save.addClickListener(e -> {
            KnowledgeDoc doc = selected.get();
            if (doc == null) {
                return;
            }
            doc.setTitle(titleField.getValue());
            doc.setBody(editor.getValue());
            // Wrapped because the edits live only in this textarea until the save lands: a throw
            // that escapes into JS leaves the status line blank, which looks the same as a save
            // that has not been clicked yet. UUID.fromString is inside the try for the same
            // reason — a non-UUID reply from the server must not become an invisible throw.
            try {
                String result = control.saveKnowledgeDoc(doc);
                if (result != null && result.startsWith("error")) {
                    status.setText(result);
                    ClientLog.error("KnowledgeView", "knowledge save refused — the edits to \""
                        + doc.getTitle() + "\" exist only in this editor: " + result);
                } else {
                    doc.setId(java.util.UUID.fromString(result));
                    status.setText("saved");
                    refresh();
                }
            } catch (Exception ex) {
                status.setText("save failed: " + ex.getMessage());
                ClientLog.error("KnowledgeView", "knowledge save failed — the edits to \""
                    + doc.getTitle() + "\" exist only in this editor: " + ex);
            }
        });
        header.add(titleField, save);
        status.addClassName("text-xs text-base-content/50 px-1");
        header.add(status);
        pane.add(header);

        editor.addClassName("textarea flex-1 min-h-0 w-full font-mono text-xs leading-relaxed "
            + "rounded-none border-0 resize-none");
        editor.getElement().setAttribute("data-testid", "knowledge-editor");
        Div empty = new Div();
        empty.add(new EmptyState("book", "Knowledge",
            "Curated conventions, examples, and best practices — stored as objects, fed into "
            + "every worker brief and the chat analyst. Select an entry or create one (+). "
            + "Anything marked \"proposed\" was found in a finished build: accept it, edit it, "
            + "or delete it."));
        disposables.add(Effect.create(() -> {
            boolean hasSelection = selected.get() != null;
            titleField.setVisible(hasSelection);
            save.setVisible(hasSelection);
            editor.setVisible(hasSelection);
            empty.setVisible(!hasSelection);
        }));
        pane.add(editor, empty);
        return pane;
    }

    /**
     * Reloads the list into the signal as COPIES, never the objects the RMI stub returned.
     *
     * <p>This is the contract, not caution. The client keeps one instance per id and refreshes it
     * in place, so a second {@code knowledgeDocs()} call hands back the very objects already inside
     * this signal — with their new field values. The signal's {@code set()} dedups by
     * {@code equals}, so setting them compared EQUAL to the retained value (it WAS the retained
     * value) and the update was silently dropped: accepting a proposal promoted it in the store
     * while the row kept its amber PROPOSED badge, with no error anywhere. Retaining copies keeps
     * the signal's value frozen at what was last rendered, so the next fetch genuinely differs.
     * (Server-side publishers deep-copy for exactly this reason — see {@code BacklogPublisher}.)
     */
    private void refresh() {
        try {
            List<KnowledgeDoc> snapshot = new ArrayList<>();
            for (KnowledgeDoc doc : control.knowledgeDocs()) {
                snapshot.add(copyOf(doc));
            }
            docs.set(snapshot);
            if (listLoadFailed) {
                listLoadFailed = false;
                ClientLog.info("KnowledgeView", "the knowledge list is loading again");
            }
        } catch (Exception ex) {
            // The list keeps the last snapshot it managed to render, so a stale list is the visible
            // symptom — and after a save or a delete that stale list is actively misleading.
            // Reported on the transition only: this runs after every edit and from the research
            // poll, so a server that stays down would otherwise write a line per operation.
            status.setText("knowledge list is stale: " + ex.getMessage());
            if (!listLoadFailed) {
                listLoadFailed = true;
                ClientLog.error("KnowledgeView", "the knowledge list failed to reload — the entries "
                    + "shown are stale and may not match what is stored: " + ex);
            }
        }
    }

    /** Whether the last {@link #refresh()} failed, so a run of failures reports once. */
    private boolean listLoadFailed;

    private static KnowledgeDoc copyOf(KnowledgeDoc doc) {
        return new KnowledgeDoc(doc.getId(), doc.getProjectId(), doc.getSlug(), doc.getTitle(),
            doc.getBody(), doc.getStatus(), doc.getSource(), doc.getCreatedAt(), doc.getUpdatedAt());
    }
}

