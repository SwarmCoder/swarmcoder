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
import com.swarmcoder.console.api.BrdSignals;
import com.swarmcoder.console.api.CheckCountsDto;
import com.swarmcoder.console.api.RequirementPageDto;
import com.swarmcoder.console.api.RequirementQuery;
import com.swarmcoder.console.api.RequirementRowDto;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import org.teavm.jso.browser.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The requirements as a tree of rows — the surface that works at a thousand requirements
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §4).
 *
 * <p><b>Why this and not the canvas.</b> A node-link diagram is excellent at the relationships AROUND a
 * requirement and hopeless at a thousand of them: the old layout put a box per requirement on a canvas
 * tens of thousands of pixels wide and redrew all of it whenever anything changed. Finding one
 * requirement meant scanning. This reads top to bottom, indents by what contains what, and only ever
 * holds one window of rows.
 *
 * <p><b>It never receives the document.</b> Rows arrive from {@code BrdService.rows(...)} — a line each,
 * no statement text, no checks, no history — and a change arrives as {@link BrdSignals#VERSION}, two
 * fields saying "the requirements are now at revision N". The old signal pushed a deep copy of the whole
 * graph to every client on every edit.
 *
 * <p><b>Threading.</b> Every RMI call here is on a {@code new Thread} or inside a DOM handler, never in
 * an {@link Effect}: an effect runs on the signal-dispatch path, and suspending it on a socket is the
 * defect that has bitten this codebase six times (UX v3 rule 7). So the version effect spawns a thread
 * rather than querying, and the render effect touches no RMI at all.
 */
final class RequirementTreeView extends Div implements Disposable {

    /** One window. The server clamps to its own ceiling; this is what the tree asks for. */
    private static final int WINDOW = 500;

    /**
     * How long typing settles before the tree asks the server again.
     *
     * <p>A query per keystroke would put eight round trips into the word "checkout" and make the tree
     * flicker through the answers to prefixes nobody asked about. Short enough that the pause is not
     * felt, long enough that a typed word is one question.
     */
    private static final int SEARCH_SETTLE_MS = 220;

    private final BrdService service = new BrdService_Stub();

    private final ValueSignal<RequirementPageDto> page =
        new ValueSignal<>(new RequirementPageDto());
    private final ValueSignal<String> status = new ValueSignal<>("");
    /** Requirements whose parts are folded away. Client-side: it is about this reader, not the project. */
    private final ValueSignal<Set<String>> collapsed = new ValueSignal<>(new HashSet<>());
    /** Requirements whose coverage breakdown is open. */
    private final ValueSignal<Set<String>> expanded = new ValueSignal<>(new HashSet<>());

    /**
     * The requirement open in the editor, or null.
     *
     * <p>This is the whole of 25.2: the list an operator lands on had no "new" and no clickable row,
     * so every edit began by finding the Graph tab. It opens the SAME form the diagram uses
     * ({@link RequirementForm}) rather than a second one, because two editors of one thing drift.
     */
    private final ValueSignal<BrdRequirement> editing = new ValueSignal<>(null);
    /**
     * That requirement as the SERVER has it, re-fetched after every write.
     *
     * <p>The rows deliberately carry no statement text and no checks, so opening one fetches the
     * requirement itself - one requirement, not the document (which is the cost this whole view
     * exists to avoid).
     */
    private final ValueSignal<BrdRequirement> stored = new ValueSignal<>(null);
    private final ValueSignal<String> editorStatus = new ValueSignal<>("");
    private final RequirementForm form =
        new RequirementForm("list", editing, stored::get, editorStatus,
            this::refetchEditing);
    private final Dialog editorDialog = new Dialog();

    private final Span summary = new Span("");
    private final Div rowsHost = new Div();
    private final Div statusLine = new Div();
    private final Div countsLine = new Div();
    private final List<Disposable> disposables = new ArrayList<>();

    /** What is being looked for. Rebuilt fresh on every change — signals dedup by equals. */
    private final ValueSignal<RequirementQuery> query = new ValueSignal<>(new RequirementQuery());
    private final TextField searchField = new TextField("Search requirements and checks");
    /** Which typing burst is current, so a settled timer from an abandoned one does nothing. */
    private int searchGeneration;

    RequirementTreeView() {
        addClassName("flex flex-col h-full min-h-0");
        getElement().setAttribute("data-testid", "requirements-tree");
        add(header());
        add(queryBar());

        countsLine.addClassName("px-3 py-1 text-[11px] text-base-content/50 shrink-0 "
            + "border-b border-base-300/60 flex items-center gap-1");
        countsLine.setVisible(false);
        add(countsLine);

        statusLine.addClassName("px-3 py-1 text-[11px] text-warning shrink-0");
        statusLine.setVisible(false);
        add(statusLine);

        rowsHost.addClassName("flex-1 min-h-0 overflow-y-auto");
        add(rowsHost);

        // The editor, over the list, in the dialog idiom UX v3 3.2 settled for stories. Since ZeroZ
        // Stack 0.8.0 Escape and a click outside close it, so the only thing to wire is that
        // closing it clears the selection - otherwise reopening the same row would find the form
        // already holding it and show nothing.
        editorDialog.setWidth("48rem");
        // A heading naming the requirement. Without one the only clue to WHICH requirement is open
        // is the value in the Title box, which is also the thing the operator is about to change.
        Div editorHeading = new Div();
        editorHeading.addClassName("text-sm font-semibold px-3 pt-3 flex items-center gap-2");
        editorHeading.getElement().setAttribute("data-testid", "requirement-editor-heading");
        Span editorHandle = new Span("");
        editorHandle.addClassName("font-mono text-base-content/50");
        editorHeading.getElement().appendChild(editorHandle.getElement());
        Span editorName = new Span("");
        editorName.addClassName("truncate");
        editorHeading.getElement().appendChild(editorName.getElement());
        disposables.add(Effect.create(() -> {
            BrdRequirement open = editing.get();
            boolean fresh = open == null || open.id() == null;
            editorHandle.setText(fresh ? "" : nzText(open.handle()));
            editorName.setText(fresh ? "New requirement" : nzText(open.title()));
        }));
        // The form scrolls INSIDE the dialog. A requirement with a dozen checks is taller than any
        // window, and a dialog that grows past the screen puts its own controls out of reach.
        Div editorBody = new Div();
        editorBody.addClassName("max-h-[74vh] overflow-y-auto");
        editorBody.add(form);
        editorDialog.add(editorHeading, editorBody);
        editorDialog.addCloseListener(e -> editing.set(null));
        add(editorDialog);
        disposables.add(Effect.create(() -> {
            boolean open = editing.get() != null;
            if (open && !editorDialog.isOpened()) {
                editorDialog.open();
            } else if (!open && editorDialog.isOpened()) {
                editorDialog.close();
            }
        }));

        disposables.add(Effect.create(this::render));
        disposables.add(Effect.create(() -> {
            // Reading VERSION and the query is what subscribes this effect to both. The reload itself
            // is on a thread, because an RMI call from inside an effect suspends the signal-dispatch
            // path. One effect for both, so an edit arriving while a filter is on re-asks the SAME
            // question rather than quietly resetting to the whole tree.
            BrdSignals.VERSION.get();
            reload(query.get());
        }));
        disposables.add(Effect.create(() -> {
            String message = status.get();
            statusLine.setText(message);
            statusLine.setVisible(!message.isEmpty());
        }));
    }

    @Override
    public void dispose() {
        form.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Fetches one window on its own thread and publishes it; the render effect does the rest. */
    private void reload(RequirementQuery asked) {
        new Thread(() -> {
            try {
                RequirementPageDto fetched = service.rows(asked, 0, WINDOW);
                page.set(fetched == null ? new RequirementPageDto() : fetched);
                status.set("");
            } catch (Exception e) {
                // The tree keeps the rows it had. Saying nothing would leave the operator reading a
                // stale document as though it were current — and an empty tree looks exactly like a
                // project with no requirements, which is a different and specific claim.
                ClientLog.error("RequirementTreeView", "could not load the requirements; the tree is "
                    + "showing stale or empty rows: " + e);
                status.set("These requirements may be out of date — could not reach the server.");
            }
        }).start();
    }

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 shrink-0 "
            + "bg-base-100");
        Span title = new Span("Requirements");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        TextStyle.CAPTION.applyTo(summary);
        bar.getElement().appendChild(summary.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        // Folding is one action on the whole tree, because doing it row by row on a deep document is
        // the sort of chore an operator abandons half-way through.
        bar.add(textButton("Collapse all", () -> {
            Set<String> next = new HashSet<>();
            for (RequirementRowDto row : page.get().getRows()) {
                if (row.hasChildren()) {
                    next.add(row.getRequirementId());
                }
            }
            collapsed.set(next);
        }));
        bar.add(textButton("Expand all", () -> collapsed.set(new HashSet<>())));
        // Writing starts HERE, on the surface the operator lands on. Until now the list could only
        // be read: no way to start a requirement and no way to open one, so every edit began by
        // finding the Graph tab, and nothing on this screen said so (25.2).
        Button add = new Button("New requirement");
        add.addClassName("btn-primary btn-xs");
        add.getElement().setAttribute("data-testid", "tree-new-requirement");
        add.addClickListener(e -> openNew(""));
        bar.add(add);
        return bar;
    }

    /**
     * Opens the editor on a requirement that does not exist yet.
     *
     * <p>{@code parentId} is the requirement it will be made part of when it is first saved, or "".
     * The link is written in the same save, so the tree never briefly shows a top-level requirement
     * nobody asked for.
     */
    private void openNew(String parentId) {
        BrdRequirement fresh = new BrdRequirement(null, null, "New requirement", "",
            Priority.MEDIUM, RequirementStatus.DRAFT, null);
        form.setParentId(parentId);
        editorStatus.set("");
        stored.set(fresh);
        editing.set(fresh);
    }

    /**
     * Opens one row's requirement in the editor.
     *
     * <p>Fetched on its own thread: an RMI call inside a DOM handler is fine, but the row is also
     * the thing the operator just clicked, and a slow server would freeze the click. The dialog
     * opens from the signal when the answer lands.
     */
    private void open(String requirementId) {
        form.setParentId("");
        editorStatus.set("");
        new Thread(() -> {
            try {
                BrdRequirement fetched = service.requirement(requirementId);
                if (fetched == null) {
                    status.set("That requirement is not there any more - the list is reloading.");
                    reload(query.get());
                    return;
                }
                stored.set(fetched);
                editing.set(fetched);
            } catch (Exception e) {
                ClientLog.error("RequirementTreeView", "could not open a requirement, so the "
                    + "editor did not appear: " + e);
                status.set("Could not open that requirement - could not reach the server.");
            }
        }).start();
    }

    /** Re-reads the open requirement after the form has written to the server. */
    private void refetchEditing() {
        BrdRequirement open = editing.get();
        if (open == null || open.id() == null) {
            return;
        }
        String id = open.id().toString();
        new Thread(() -> {
            try {
                BrdRequirement fetched = service.requirement(id);
                if (fetched != null) {
                    stored.set(fetched);
                }
            } catch (Exception e) {
                ClientLog.error("RequirementTreeView", "could not re-read the requirement being "
                    + "edited; its checks may be showing the state before the last change: " + e);
            }
        }).start();
    }

    /**
     * Search and filters. Chips rather than dropdowns: a filter that is ON has to be visible at a
     * glance, and a select box showing "any" looks identical to one nobody has touched.
     */
    private Div queryBar() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-1.5 border-b border-base-300 shrink-0 "
            + "bg-base-100 "
            + "flex-wrap");
        bar.getElement().setAttribute("data-testid", "requirements-query");

        Div searchBox = new Div();
        searchBox.addClassName("flex items-center gap-1.5 flex-1 min-w-[12rem]");
        searchBox.add(Icon.of("search", "w-3.5 h-3.5 opacity-40"));
        searchField.addClassName("!bg-transparent !border-0 focus:outline-none flex-1 text-xs py-0.5");
        searchField.getElement().setAttribute("data-testid", "requirements-search");
        searchField.addValueChangeListener(e -> onSearchTyped());
        searchField.addDomEventListener("input", e -> onSearchTyped());
        searchBox.add(searchField);
        bar.add(searchBox);

        bar.add(chip("draft", "Only requirements not yet agreed",
            q -> "DRAFT".equals(q.getStatusCsv()),
            q -> q.setStatusCsv("DRAFT".equals(q.getStatusCsv()) ? "" : "DRAFT")));
        bar.add(chip("agreed", "Only requirements agreed and not yet delivered",
            q -> "ACTIVE".equals(q.getStatusCsv()),
            q -> q.setStatusCsv("ACTIVE".equals(q.getStatusCsv()) ? "" : "ACTIVE")));
        bar.add(chip("delivered", "Only requirements every check now proves",
            q -> "IMPLEMENTED".equals(q.getStatusCsv()),
            q -> q.setStatusCsv("IMPLEMENTED".equals(q.getStatusCsv()) ? "" : "IMPLEMENTED")));
        bar.add(chip("quality", "Only non-functional requirements",
            q -> "NON_FUNCTIONAL".equals(q.getKindCsv()),
            q -> q.setKindCsv("NON_FUNCTIONAL".equals(q.getKindCsv()) ? "" : "NON_FUNCTIONAL")));
        bar.add(chip("no story yet", "Only requirements with agreed checks nothing is delivering",
            q -> q.getOnlyUnclaimed() == 1,
            q -> q.setOnlyUnclaimed(q.getOnlyUnclaimed() == 1 ? 0 : 1)));
        bar.add(chip("needs re-checking", "Only requirements whose evidence predates an edit",
            q -> q.getOnlyStale() == 1,
            q -> q.setOnlyStale(q.getOnlyStale() == 1 ? 0 : 1)));
        bar.add(chip("misplaced", "Only requirements marked as part of more than one thing",
            q -> q.getOnlyShapeWarnings() == 1,
            q -> q.setOnlyShapeWarnings(q.getOnlyShapeWarnings() == 1 ? 0 : 1)));
        return bar;
    }

    /**
     * One filter toggle. {@code on} reads the current query, {@code toggle} mutates a COPY of it — the
     * signal dedups by equals, so mutating the held value in place would be compared against itself
     * and swallowed (UX v3 rule 7).
     */
    private Div chip(String label, String tooltip, Predicate<RequirementQuery> on,
                     Consumer<RequirementQuery> toggle) {
        Div element = new Div();
        element.getElement().setAttribute("title", tooltip);
        element.getElement().setAttribute("data-chip", label);
        Span text = new Span(label);
        element.getElement().appendChild(text.getElement());
        element.addDomEventListener("click", e -> {
            RequirementQuery next = copyOf(query.get());
            toggle.accept(next);
            query.set(next);
        });
        disposables.add(Effect.create(() -> {
            boolean active = on.test(query.get());
            element.getElement().setAttribute("class",
                "shrink-0 px-1.5 py-0.5 rounded text-[11px] cursor-pointer border "
                    + (active
                        ? "border-primary text-primary bg-primary/10"
                        : "border-base-300 text-base-content/50 hover:text-base-content"));
            element.getElement().setAttribute("data-active", active ? "true" : "false");
        }));
        return element;
    }

    /**
     * Restarts the settle timer. The generation counter is what makes an abandoned burst harmless: the
     * timer that fires for a query the operator has already typed past finds a stale generation and
     * returns, instead of asking a question whose answer is no longer wanted.
     */
    private void onSearchTyped() {
        final int generation = ++searchGeneration;
        Window.setTimeout(() -> {
            if (generation != searchGeneration) {
                return;
            }
            String typed = searchField.getValue() == null ? "" : searchField.getValue();
            RequirementQuery next = copyOf(query.get());
            next.setSearch(typed);
            query.set(next);
        }, SEARCH_SETTLE_MS);
    }

    private static RequirementQuery copyOf(RequirementQuery source) {
        RequirementQuery copy = new RequirementQuery();
        copy.setSearch(source.getSearch());
        copy.setStatusCsv(source.getStatusCsv());
        copy.setKindCsv(source.getKindCsv());
        copy.setNfrCategoryCsv(source.getNfrCategoryCsv());
        copy.setOnlyUnclaimed(source.getOnlyUnclaimed());
        copy.setOnlyStale(source.getOnlyStale());
        copy.setOnlyShapeWarnings(source.getOnlyShapeWarnings());
        copy.setClaimedByStory(source.getClaimedByStory());
        copy.setIncludeRetired(source.getIncludeRetired());
        return copy;
    }

    /** Clears every filter and the search box, from a DOM handler. */
    private void clearQuery() {
        searchField.setValue("");
        searchGeneration++; // any settle timer in flight is now stale
        query.set(new RequirementQuery());
    }

    private Div textButton(String label, Runnable action) {
        Div button = new Div();
        button.addClassName("text-[11px] text-base-content/50 hover:text-primary cursor-pointer px-1");
        Span text = new Span(label);
        button.getElement().appendChild(text.getElement());
        button.addDomEventListener("click", e -> action.run());
        return button;
    }

    // --- rendering --------------------------------------------------------------------------------

    /**
     * Redraws from the signals. Touches no RMI, so it is safe inside an {@link Effect}.
     *
     * <p>Rows arrive in tree order — a parent always before its parts — so hiding a collapsed subtree is
     * a single pass: once a row is inside a folded ancestor, every row deeper than it is too, until the
     * depth comes back up.
     */
    private void render() {
        RequirementPageDto current = page.get();
        Set<String> folded = collapsed.get();
        Set<String> open = expanded.get();
        rowsHost.getElement().setInnerHTML("");

        List<RequirementRowDto> rows = current.getRows();
        summary.setText(summaryText(current));
        renderCounts(current);
        if (rows.isEmpty()) {
            rowsHost.add(current.getTotal() == 0 ? emptyState() : noMatchesState(current));
            return;
        }

        int hideBelowDepth = -1;
        int hiddenByFolding = 0;
        for (RequirementRowDto row : rows) {
            if (hideBelowDepth >= 0 && row.getDepth() > hideBelowDepth) {
                hiddenByFolding++;
                continue;
            }
            hideBelowDepth = -1;
            rowsHost.add(rowElement(row, folded, open));
            if (row.hasChildren() && folded.contains(row.getRequirementId())) {
                hideBelowDepth = row.getDepth();
            }
        }
        if (hiddenByFolding > 0) {
            // Folded is not gone (UX v3 rule 1). The count is the record, and Expand all opens it.
            rowsHost.add(foldedFooter(hiddenByFolding));
        }
    }

    private String summaryText(RequirementPageDto current) {
        int total = current.getTotal();
        if (total == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " requirement" : " requirements");
        if (current.moreFollow()) {
            sb.append(" · showing the first ").append(current.getRows().size());
        }
        sb.append(" · rev ").append(current.getRevision());
        return sb.toString();
    }

    /**
     * The three counts, which exist so a filtered view cannot mislead (§3.7).
     *
     * <p>Matches, parents shown for context, and everything left out — reported separately, because
     * their sum would overstate the result and omitting the parents would break the hierarchy on screen.
     * The excluded count is the openable record UX v3 rule 1 requires: clicking it clears the filters,
     * which is how the operator gets back what is being withheld.
     */
    private void renderCounts(RequirementPageDto current) {
        countsLine.getElement().setInnerHTML("");
        boolean filtered = !query.get().isUnfiltered();
        // Retired requirements are accounted for even when nothing is being filtered, because they
        // are held back from the plain list too. A count nobody can see is a disappearance, and a
        // disappearance is the one thing retiring is not (UX v3 rule 1, 25.1).
        boolean anythingHeldBack = current.getRetiredHidden() > 0
            || query.get().getIncludeRetired() == 1;
        countsLine.setVisible(filtered || anythingHeldBack);
        if (!filtered && !anythingHeldBack) {
            return;
        }
        if (filtered) {
            countsLine.getElement().appendChild(countPart(
                current.getMatches() + (current.getMatches() == 1 ? " match" : " matches"),
                "text-base-content/70").getElement());
        }
        if (current.getContextParents() > 0) {
            countsLine.getElement().appendChild(countPart("· " + current.getContextParents()
                + " shown for context", "text-base-content/40").getElement());
        }
        if (current.getExcluded() > 0) {
            Div excluded = new Div();
            excluded.addClassName("cursor-pointer hover:text-primary underline "
                + "decoration-dotted text-base-content/40");
            excluded.getElement().setAttribute("data-testid", "requirements-excluded");
            excluded.getElement().setAttribute("title", "Clear the search and filters");
            Span text = new Span("· " + current.getExcluded() + " not matching (show all)");
            excluded.getElement().appendChild(text.getElement());
            excluded.addDomEventListener("click", e -> clearQuery());
            countsLine.add(excluded);
        }
        // "Out of scope" is its OWN count, never rolled into the one above. "Your filter did not
        // match this" and "this is not part of the project any more" are different sentences, and a
        // single number would make retiring indistinguishable from deleting.
        boolean showingRetired = query.get().getIncludeRetired() == 1;
        if (current.getRetiredHidden() > 0 || showingRetired) {
            Div retired = new Div();
            retired.addClassName("cursor-pointer hover:text-primary underline "
                + "decoration-dotted text-base-content/40");
            retired.getElement().setAttribute("data-testid", "requirements-retired");
            retired.getElement().setAttribute("title", showingRetired
                ? "Hide the requirements that are out of scope"
                : "Show the requirements that have been taken out of scope. They are still on the "
                    + "record, with everything that was built for them.");
            Span text = new Span(showingRetired
                ? "· out of scope shown (hide them)"
                : "· " + current.getRetiredHidden()
                    + (current.getRetiredHidden() == 1 ? " out of scope" : " out of scope")
                    + " (show them)");
            retired.getElement().appendChild(text.getElement());
            retired.addDomEventListener("click", e -> {
                RequirementQuery next = copyOf(query.get());
                next.setIncludeRetired(next.getIncludeRetired() == 1 ? 0 : 1);
                query.set(next);
            });
            countsLine.add(retired);
        }
    }

    private static String nzText(String s) {
        return s == null ? "" : s;
    }

    private Span countPart(String text, String colour) {
        Span span = new Span(text);
        span.addClassName(colour);
        return span;
    }

    /**
     * A filter that matched nothing, which must never look like a project with no requirements — the
     * distinction the excluded count exists to draw, said again where the rows would have been.
     */
    private Div noMatchesState(RequirementPageDto current) {
        Div empty = new Div();
        empty.addClassName("p-6 text-sm text-base-content/50 flex flex-col gap-2 items-start");
        empty.getElement().setAttribute("data-testid", "requirements-no-matches");
        Span line = new Span("Nothing here matches what you are looking for.");
        line.addClassName("font-medium");
        empty.getElement().appendChild(line.getElement());
        Span held = new Span("This project has " + current.getTotal()
            + (current.getTotal() == 1 ? " requirement" : " requirements") + ".");
        empty.getElement().appendChild(held.getElement());
        empty.add(textButton("Clear the search and filters", this::clearQuery));
        return empty;
    }

    /**
     * The empty list — which is the first thing a brand-new project shows anybody.
     *
     * <p>It teaches rather than reports (UX v3 §3.1), and what it teaches has to be doable from
     * here. It used to say "open the diagram and use Analyse requirement documents", which was an
     * instruction to go and find another tab because the button was only on that one. The button is
     * above the tabs now, on both views, and this points at it by the words written on it.
     */
    private Div emptyState() {
        Div empty = new Div();
        empty.addClassName("p-6 text-sm text-base-content/50 flex flex-col gap-1");
        empty.getElement().setAttribute("data-testid", "requirements-empty");
        Span line = new Span("No requirements yet.");
        line.addClassName("font-medium");
        empty.getElement().appendChild(line.getElement());
        Span how = new Span("Most projects start from a document you already have. Press "
            + "\"Analyse documents\" at the top of this panel and SwarmCoder reads it for you. "
            + "Or write the first requirement here by hand.");
        empty.getElement().appendChild(how.getElement());
        Div buttons = new Div();
        buttons.addClassName("flex items-center gap-2 mt-2");
        Button add = new Button("New requirement");
        add.addClassName("btn-primary btn-sm");
        add.getElement().setAttribute("data-testid", "tree-new-requirement-empty");
        add.addClickListener(e -> openNew(""));
        buttons.add(add);
        // The guide, offered where a new project actually lands. The header button is the permanent
        // way in; this is the one a first-timer's eye is already on, because this panel is the first
        // thing they see and it is empty.
        Button walk = new Button("Show me how this works");
        walk.addClassName("btn-outline btn-sm");
        walk.getElement().setAttribute("data-testid", "requirements-empty-guide");
        walk.addClickListener(e -> Nav.openGuide.run());
        buttons.add(walk);
        empty.add(buttons);
        return empty;
    }

    private Div foldedFooter(int hidden) {
        Div footer = new Div();
        footer.addClassName("px-3 py-2 text-[11px] text-base-content/40 border-t border-base-300");
        footer.getElement().setAttribute("data-testid", "tree-folded-count");
        Span text = new Span(hidden + (hidden == 1 ? " requirement is" : " requirements are")
            + " folded away — Expand all shows them");
        footer.getElement().appendChild(text.getElement());
        return footer;
    }

    private Div rowElement(RequirementRowDto row, Set<String> folded, Set<String> open) {
        Div element = new Div();
        element.addClassName("flex items-center gap-2 px-3 py-1.5 border-b border-base-300/40 "
            + "hover:bg-base-200/40 text-xs");
        element.getElement().setAttribute("data-row", "req-" + row.getHandle());
        // A row present only to keep a match attached to its place in the tree is dimmed and marked.
        // It is context, not a result, and must never read as one.
        if (row.isContextOnly()) {
            element.addClassName("opacity-45");
            element.getElement().setAttribute("data-context-only", "true");
        }
        // A requirement the operator asked to see even though it is out of scope. Quieter than the
        // ones in scope, because the eye should be able to tell them apart down a column of rows
        // without reading each badge.
        if ("DEPRECATED".equals(row.getStatus())) {
            element.addClassName("opacity-60");
            element.getElement().setAttribute("data-retired", "true");
        }

        Div indent = new Div();
        indent.addClassName("shrink-0");
        indent.setStyle("width", (row.getDepth() * 18) + "px");
        element.add(indent);

        element.add(foldToggle(row, folded));

        // The handle and the title together are the way in. A row that cannot be opened is what
        // made the Graph tab compulsory (25.2); this is "Open" from the at-scale design's row
        // actions, given to the part of the row an operator would already aim at.
        Div opener = new Div();
        opener.addClassName("flex items-center gap-2 min-w-0 cursor-pointer hover:text-primary");
        opener.getElement().setAttribute("data-open", row.getHandle());
        opener.getElement().setAttribute("title",
            "Open " + row.getHandle() + " to read it and change it");
        Span handle = new Span(row.getHandle());
        handle.addClassName("shrink-0 w-10 font-mono text-base-content/50");
        opener.getElement().appendChild(handle.getElement());
        Span title = new Span(row.getTitle() == null || row.getTitle().isEmpty()
            ? "(untitled)" : row.getTitle());
        title.addClassName("min-w-0 truncate");
        opener.getElement().appendChild(title.getElement());
        opener.addDomEventListener("click", e -> open(row.getRequirementId()));
        element.add(opener);
        element.add(relations(row));

        // Fixed-width columns from here on, right-aligned. A flex row sized to its content put every
        // row's badge at a different x — which a screenshot showed — and a list you cannot scan down is
        // the one thing this view exists to fix.
        Div kindCol = new Div();
        kindCol.addClassName("shrink-0 w-24 flex justify-end");
        if ("NON_FUNCTIONAL".equals(row.getKind())) {
            kindCol.add(badge(row.getNfrCategory() == null || row.getNfrCategory().isEmpty()
                ? "quality" : row.getNfrCategory().toLowerCase(), "badge-info"));
        } else if ("CONSTRAINT".equals(row.getKind())) {
            // Labelled, so a leftover rule is not mistaken for an ordinary requirement somebody
            // forgot to write checks for. Rules live in Guidelines now; these rows are history.
            kindCol.add(badge("leftover rule", "badge-ghost"));
        }
        element.add(kindCol);

        Div statusCol = new Div();
        statusCol.addClassName("shrink-0 w-20 flex justify-end");
        statusCol.add(statusBadge(row.getStatus()));
        element.add(statusCol);

        Div coverageCol = new Div();
        coverageCol.addClassName("shrink-0 w-36 flex justify-end");
        coverageCol.add(coverage(row, open));
        element.add(coverageCol);

        element.add(stories(row));
        // Always present, even when empty, so the warning column does not squeeze the row beside it and
        // every row's rightmost edge is in the same place.
        Div warningCol = new Div();
        warningCol.addClassName("shrink-0 w-5 flex justify-end");
        if (row.getShapeWarning() != null && !row.getShapeWarning().isEmpty()) {
            warningCol.add(warning(row.getShapeWarning()));
        }
        element.add(warningCol);
        element.add(addPart(row));
        element.add(surroundings(row));

        if (open.contains(row.getRequirementId())) {
            Div wrapper = new Div();
            wrapper.addClassName("flex flex-col");
            wrapper.add(element);
            wrapper.add(breakdown(row));
            return wrapper;
        }
        return element;
    }

    /** The fold control, or a spacer of the same width so nothing shifts between rows. */
    private Div foldToggle(RequirementRowDto row, Set<String> folded) {
        Div toggle = new Div();
        toggle.addClassName("shrink-0 w-4 flex items-center justify-center");
        if (!row.hasChildren()) {
            return toggle;
        }
        boolean isFolded = folded.contains(row.getRequirementId());
        toggle.addClassName("cursor-pointer text-base-content/40 hover:text-primary");
        toggle.add(Icon.of(isFolded ? "chevron-right" : "chevron-down", "w-3 h-3"));
        toggle.getElement().setAttribute("title", isFolded
            ? "Show the " + row.getDescendants() + " requirements inside " + row.getHandle()
            : "Fold away what is inside " + row.getHandle());
        toggle.addDomEventListener("click", e -> {
            // A FRESH set, never the one in the signal: signals dedup by equals, and mutating the held
            // value in place means the change is compared against itself and swallowed.
            Set<String> next = new HashSet<>(collapsed.get());
            if (!next.remove(row.getRequirementId())) {
                next.add(row.getRequirementId());
            }
            collapsed.set(next);
        });
        return toggle;
    }

    /**
     * The coverage figure, and this is where UX v3 rule 4 lands: a requirement's OWN checks and the
     * checks of everything inside it are different numbers and are never added into one.
     *
     * <p>So a leaf shows its own, and a coarse requirement shows its parts' — labelled as its parts',
     * with its own beside it when it has any. Reporting a subtree total as the requirement's own figure
     * would claim it is directly verified when nothing verifies it.
     */
    private Div coverage(RequirementRowDto row, Set<String> open) {
        Div holder = new Div();
        holder.addClassName("shrink-0 flex items-center gap-1 font-mono text-[11px] "
            + "cursor-pointer hover:text-primary");
        holder.getElement().setAttribute("data-coverage", row.getHandle());
        CheckCountsDto own = row.getOwn();
        CheckCountsDto sub = row.getSubtree();

        if (own.gating() > 0) {
            holder.add(ratio(own, "" + own.getPassing() + "/" + own.gating()));
        }
        // A ratio is only worth printing when there is something to count. "0/0 in parts" was the first
        // thing a screenshot showed, and it is not a small figure — it is a figure that means nothing,
        // sitting where the operator has been trained to read progress.
        if (row.hasChildren() && sub.gating() > 0) {
            Span inParts = new Span(own.gating() > 0
                ? "(" + sub.getPassing() + "/" + sub.gating() + " in parts)"
                : sub.getPassing() + "/" + sub.gating() + " in parts");
            inParts.addClassName(sub.allGatingPassing() ? "text-success/70" : "text-base-content/40");
            holder.getElement().appendChild(inParts.getElement());
        }
        if ("CONSTRAINT".equals(row.getKind())) {
            // Not warning-coloured. There is no work missing here; the row is in the wrong place.
            Span rule = new Span("moved to Guidelines");
            rule.addClassName("text-base-content/40");
            rule.getElement().setAttribute("title", "A rule about how the project is built. Rules "
                + "live on the Guidelines screen now, which is where they are sent to the "
                + "architect, the test author and every worker. State it there and retire this.");
            holder.getElement().appendChild(rule.getElement());
        } else if (own.gating() == 0 && sub.gating() == 0) {
            Span none = new Span("no checks");
            none.addClassName("text-warning/70");
            none.getElement().setAttribute("title", row.hasChildren()
                ? "Neither this requirement nor anything inside it has a check yet, so none of it can "
                    + "ever be shown as delivered."
                : "Nothing proves this requirement yet, so it can never be shown as delivered.");
            holder.getElement().appendChild(none.getElement());
        }
        holder.getElement().setAttribute("title", "Click for the breakdown");
        holder.addDomEventListener("click", e -> {
            Set<String> next = new HashSet<>(expanded.get());
            if (!next.remove(row.getRequirementId())) {
                next.add(row.getRequirementId());
            }
            expanded.set(next);
        });
        return holder;
    }

    private Span ratio(CheckCountsDto counts, String text) {
        Span span = new Span(text);
        span.addClassName(counts.allGatingPassing() ? "text-success" : "text-base-content/70");
        return span;
    }

    /** The states behind the ratio, in words — opened by clicking the figure. */
    private Div breakdown(RequirementRowDto row) {
        Div panel = new Div();
        panel.addClassName("px-3 py-2 ml-16 mb-1 text-[11px] text-base-content/60 "
            + "bg-base-200/40 rounded flex flex-col gap-0.5");
        panel.getElement().setAttribute("data-testid", "coverage-breakdown");
        panel.add(breakdownLine("Proving this requirement", row.getOwn()));
        if (row.hasChildren()) {
            panel.add(breakdownLine("Across its " + row.getDescendants()
                + (row.getDescendants() == 1 ? " part" : " parts"), row.getSubtree()));
        }
        if (row.getUnclaimedChecks() > 0) {
            Span unclaimed = new Span(row.getUnclaimedChecks()
                + (row.getUnclaimedChecks() == 1 ? " check has" : " checks have")
                + " no story to deliver them");
            unclaimed.addClassName("text-warning/80");
            panel.getElement().appendChild(unclaimed.getElement());
        }
        return panel;
    }

    private Div breakdownLine(String label, CheckCountsDto counts) {
        Div line = new Div();
        line.addClassName("flex items-center gap-2");
        Span name = new Span(label + ":");
        name.addClassName("text-base-content/40");
        line.getElement().appendChild(name.getElement());
        if (counts.gating() == 0 && counts.getProposed() == 0) {
            Span none = new Span("no checks");
            line.getElement().appendChild(none.getElement());
            return line;
        }
        line.getElement().appendChild(partElement(counts.getPassing(), "verified", "text-success"));
        line.getElement().appendChild(partElement(counts.getFailing(), "failing", "text-error"));
        line.getElement().appendChild(partElement(counts.getUnverified(), "not yet run",
            "text-base-content/60"));
        // "Stale" is not an internal name being leaked — it is the honest word for a check that passed
        // against wording the requirement no longer has, and hiding it would show green for evidence
        // that no longer proves anything.
        line.getElement().appendChild(partElement(counts.getStale(), "needs re-checking after an edit",
            "text-warning"));
        line.getElement().appendChild(partElement(counts.getProposed(), "suggested, not agreed",
            "text-base-content/40"));
        return line;
    }

    /** One state's tally, or nothing at all when that state is empty — a "0 failing" is noise. */
    private org.teavm.jso.dom.xml.Node partElement(int count, String label, String colour) {
        Span span = new Span(count == 0 ? "" : count + " " + label);
        span.addClassName(colour);
        span.setVisible(count > 0);
        return span.getElement();
    }

    private Div stories(RequirementRowDto row) {
        Div holder = new Div();
        holder.addClassName("shrink-0 w-20 text-right text-[11px] text-base-content/40 truncate");
        String keys = row.getStoryKeysCsv();
        if (keys == null || keys.isEmpty()) {
            return holder;
        }
        Span span = new Span(keys);
        span.getElement().setAttribute("title", "Delivered by " + keys);
        holder.getElement().appendChild(span.getElement());
        return holder;
    }

    private Div badge(String text, String tone) {
        Div badge = new Div();
        badge.addClassName("shrink-0 badge badge-xs " + tone);
        Span span = new Span(text);
        badge.getElement().appendChild(span.getElement());
        return badge;
    }

    /**
     * The status the EVIDENCE supports, in the operator's words.
     *
     * <p>The server sends the internal name; the mapping to what a person reads happens here and
     * nowhere else, per UX v3 §4. "agreed" rather than ACTIVE is the point: ACTIVE is a state machine's
     * word for a decision the operator made.
     */
    private Div statusBadge(String status) {
        if (status == null) {
            return badge("", "badge-ghost");
        }
        switch (status) {
            case "DRAFT":
                return badge("draft", "badge-ghost");
            case "ACTIVE":
                return badge("agreed", "badge-outline");
            case "IMPLEMENTED":
                return badge("delivered", "badge-success");
            case "DEPRECATED":
                // "retired", the word RequirementStatus.label() uses. This said "dropped", which is
                // the word for a CANCELLED story - two different things reading the same on screen.
                return badge("retired", "badge-ghost");
            default:
                return badge(status.toLowerCase(), "badge-ghost");
        }
    }

    /**
     * The relations this requirement has beyond the hierarchy, as small badges after its title.
     *
     * <p>Already phrased by the server — this only decides where they sit and how loud they are. The
     * words never name a relation constant: a row says "waits for R4", not "DEPENDS_ON R4" (UX v3 §4,
     * and rule 6 of the design this implements).
     *
     * <p>A conflict is coloured; the rest are quiet. Two requirements that cannot both hold is the one
     * relation that means something is WRONG rather than merely connected, and it earns the only colour
     * on the row that is not about verification.
     *
     * <p>{@code flex-1} lives here rather than on the title, so the badges sit immediately after the
     * text they qualify instead of being pushed to the far side of the row away from it.
     */
    private Div relations(RequirementRowDto row) {
        Div holder = new Div();
        holder.addClassName("flex-1 min-w-0 flex items-center gap-1 overflow-hidden");
        String csv = row.getRelationsCsv();
        if (csv == null || csv.isEmpty()) {
            return holder;
        }
        holder.getElement().setAttribute("data-relations", row.getHandle());
        for (String phrase : csv.split(";")) {
            if (phrase.isBlank()) {
                continue;
            }
            Span badge = new Span(phrase.strip());
            badge.addClassName("shrink-0 px-1 rounded text-[10px] whitespace-nowrap "
                + (phrase.startsWith("conflicts")
                    ? "text-error bg-error/10" : "text-base-content/40 bg-base-200"));
            holder.getElement().appendChild(badge.getElement());
        }
        return holder;
    }

    /**
     * The way to the diagram, from the row it is about.
     *
     * <p>This is the pairing the design turns on: the list answers "which requirement" and the diagram
     * answers "what surrounds this one". Sending a specific requirement across, rather than just
     * switching views, is what keeps the diagram at a size it can draw.
     */
    private Div surroundings(RequirementRowDto row) {
        Div holder = new Div();
        holder.addClassName("shrink-0 w-5 flex justify-end text-base-content/30 "
            + "hover:text-primary cursor-pointer");
        holder.getElement().setAttribute("data-surroundings", row.getHandle());
        holder.getElement().setAttribute("title",
            "Show what surrounds " + row.getHandle() + " as a diagram");
        holder.add(Icon.of("graph", "w-3.5 h-3.5"));
        holder.addDomEventListener("click", e -> {
            if (showSurroundings != null) {
                showSurroundings.accept(row.getRequirementId());
            }
        });
        return holder;
    }

    /**
     * "Add child requirement" from the at-scale design's row actions, in words a person uses.
     *
     * <p>It opens the same editor with the row's requirement already chosen as what the new one is
     * part of, so decomposing something coarse is one click rather than: switch to the diagram, add
     * a requirement, find it, add a link, choose the relation.
     */
    private Div addPart(RequirementRowDto row) {
        Div holder = new Div();
        holder.addClassName("shrink-0 w-5 flex justify-end text-base-content/30 "
            + "hover:text-primary cursor-pointer");
        holder.getElement().setAttribute("data-add-part", row.getHandle());
        holder.getElement().setAttribute("title",
            "Add a requirement inside " + row.getHandle());
        holder.add(Icon.of("plus", "w-3.5 h-3.5"));
        holder.addDomEventListener("click", e -> openNew(row.getRequirementId()));
        return holder;
    }

    private Consumer<String> showSurroundings;

    /** Wired by the workspace: switches to the diagram and scopes it to this requirement. */
    void onShowSurroundings(Consumer<String> handler) {
        this.showSurroundings = handler;
    }

    private Div warning(String text) {
        Div holder = new Div();
        holder.addClassName("shrink-0 text-warning cursor-help");
        holder.getElement().setAttribute("data-shape-warning", "true");
        holder.getElement().setAttribute("title", text);
        // "warning" — the name the rest of this codebase uses. "alert-triangle" is not in the icon set
        // and rendered an unrecognizable fallback glyph, which a screenshot caught and no assertion did.
        holder.add(Icon.of("warning", "w-3.5 h-3.5"));
        return holder;
    }
}
