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

import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GraphService_Stub;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.swarmcoder.console.api.SessionSummaryDto;
import com.zeroz4j.ui.theme.Emphasis;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;

/**
 * Prompt Lab (design §8): inspect the exact PromptBundle a dispatch received — segmented by
 * ## header with per-segment token estimates — and compare two dispatches to see which segments
 * actually changed between the run that worked and the one that didn't. Reads the SESSION_OPENED
 * payload; no new capture path.
 */
final class PromptLabView extends Div implements com.zeroz4j.api.Disposable {

    /**
     * Effects this view owns. One of them binds {@link RunsStore#runs}, which is process-wide and
     * long-lived, so leaving it bound after the view is dropped is a genuine leak — the developer
     * surfaces are built and dropped like any other stage.
     */
    private final List<com.zeroz4j.api.Disposable> disposables = new ArrayList<>();

    private final GraphService graph = new GraphService_Stub();
    private final ObserverService observer = new ObserverService_Stub();

    private final ValueSignal<String> selectedRun = new ValueSignal<>("");
    private final ValueSignal<List<SessionSummaryDto>> sessions = new ValueSignal<>(new ArrayList<>());
    /**
     * Why the session list could not be read, or "" when the list is trustworthy. An empty list on
     * its own cannot say whether the run had no sessions or the read failed, and those two answers
     * send the operator in opposite directions.
     */
    private final ValueSignal<String> sessionsError = new ValueSignal<>("");
    private final ValueSignal<SessionSummaryDto> slotA = new ValueSignal<>(null);
    private final ValueSignal<SessionSummaryDto> slotB = new ValueSignal<>(null);
    private final ValueSignal<String> promptA = new ValueSignal<>(null);
    private final ValueSignal<String> promptB = new ValueSignal<>(null);

    PromptLabView() {
        addClassName("flex flex-col gap-3 h-[76vh] min-h-0");

        Div header = new Div();
        header.addClassName("flex items-baseline gap-2 shrink-0");
        Span title = new Span("Prompt Lab");
        title.addClassName("text-lg font-bold");
        Span hint = new Span("inspect and compare the exact prompt each worker received");
        TextStyle.CAPTION.applyTo(hint);
        header.getElement().appendChild(title.getElement());
        header.getElement().appendChild(hint.getElement());
        add(header);

        // Run picker.
        Div runBar = new Div();
        runBar.addClassName("flex flex-wrap gap-1.5 shrink-0");
        add(runBar);
        disposables.add(Effect.create(() -> {
            runBar.removeAll();
            List<?> runs = RunsStore.runs.get();
            if (runs.isEmpty()) {
                Span none = new Span("No runs yet — start one from a chat.");
                TextStyle.CAPTION.applyTo(none, Emphasis.FAINT);
                runBar.getElement().appendChild(none.getElement());
                return;
            }
            for (var run : RunsStore.runs.get()) {
                runBar.add(runChip(run.getRunId(),
                    run.getGoal() == null || run.getGoal().isEmpty()
                        ? run.getRunId().substring(0, 8) : run.getGoal()));
            }
        }));

        // Body: session list | prompt panel.
        Div split = new Div();
        split.addClassName("flex gap-3 flex-1 min-h-0");
        Div sessionPane = new Div();
        sessionPane.addClassName("w-64 shrink-0 overflow-y-auto border border-base-300 rounded-xl "
            + "bg-base-200/40 p-1.5 flex flex-col gap-1");
        Div promptPane = new Div();
        promptPane.addClassName("flex-1 min-h-0 overflow-y-auto border border-base-300 rounded-xl "
            + "bg-base-200/40 p-4");
        split.add(sessionPane, promptPane);
        add(split);

        disposables.add(Effect.create(() -> renderSessions(sessionPane)));
        disposables.add(Effect.create(() -> renderPrompt(promptPane)));

        MainView.refreshRuns();
    }

    /** Releases the effects — including the one bound to the process-wide run store. */
    @Override
    public void dispose() {
        for (com.zeroz4j.api.Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    // --- run + session pickers -------------------------------------------------------------------

    private Div runChip(String runId, String label) {
        Div chip = new Div();
        boolean active = runId.equals(selectedRun.get());
        chip.addClassName("px-2.5 py-1 rounded-lg text-xs cursor-pointer border max-w-[16rem] truncate "
            + (active ? "border-primary bg-primary/10 text-primary"
                      : "border-base-300 hover:border-primary/40 text-base-content/70"));
        chip.setText(label);
        chip.getElement().setAttribute("title", runId);
        chip.addDomEventListener("click", e -> {
            selectedRun.set(runId);
            slotA.set(null);
            slotB.set(null);
            promptA.set(null);
            promptB.set(null);
            loadSessions(runId);
        });
        return chip;
    }

    private void renderSessions(Div pane) {
        selectedRun.get(); // subscribe: re-render on run switch too
        pane.removeAll();
        List<SessionSummaryDto> list = sessions.get();
        String failure = sessionsError.get();
        if (!failure.isEmpty()) {
            Div failed = new Div(failure);
            failed.addClassName("text-xs text-error p-2 leading-relaxed");
            pane.add(failed);
            return;
        }
        if (list.isEmpty()) {
            Div empty = new Div(selectedRun.get().isEmpty()
                ? "Pick a run above." : "No sessions for this run.");
            empty.addClassName("text-xs text-base-content/40 p-2");
            pane.add(empty);
            return;
        }
        SessionSummaryDto a = slotA.get();
        SessionSummaryDto b = slotB.get();
        for (SessionSummaryDto session : list) {
            pane.add(sessionRow(session,
                a != null && a.getSessionId().equals(session.getSessionId()),
                b != null && b.getSessionId().equals(session.getSessionId())));
        }
    }

    private Div sessionRow(SessionSummaryDto session, boolean isA, boolean isB) {
        Div row = new Div();
        row.addClassName("rounded-lg px-2 py-1.5 cursor-pointer flex flex-col gap-0.5 "
            + (isA ? "bg-primary/15" : isB ? "bg-secondary/15" : "hover:bg-base-300/50"));
        Div top = new Div();
        top.addClassName("flex items-center gap-1.5");
        Span role = new Span(session.getRole() + " · w" + session.getWorkerIndex());
        role.addClassName("text-xs font-semibold truncate flex-1");
        top.getElement().appendChild(role.getElement());
        if (isA) {
            top.getElement().appendChild(slotBadge("A", "badge-primary").getElement());
        }
        if (isB) {
            top.getElement().appendChild(slotBadge("B", "badge-secondary").getElement());
        }
        row.add(top);
        Span meta = new Span(session.getModel() + " · " + session.getOutcome());
        meta.addClassName("text-[10px] font-mono text-base-content/50 truncate");
        row.getElement().appendChild(meta.getElement());

        // Left-click → A; the "vs" button → B (compare).
        row.addDomEventListener("click", e -> {
            slotA.set(session);
            loadPrompt(session.getSessionId(), promptA);
        });
        Div vs = new Div("compare ▸");
        vs.addClassName("text-[10px] text-base-content/40 hover:text-secondary self-end");
        vs.addDomEventListener("click", (EventListener<
            Event>) e -> {
            e.stopPropagation();
            slotB.set(session);
            loadPrompt(session.getSessionId(), promptB);
        });
        row.add(vs);
        return row;
    }

    private Span slotBadge(String text, String color) {
        Span badge = new Span(text);
        badge.addClassName("badge badge-xs " + color);
        return badge;
    }

    // --- prompt panel ----------------------------------------------------------------------------

    private void renderPrompt(Div pane) {
        pane.removeAll();
        SessionSummaryDto a = slotA.get();
        SessionSummaryDto b = slotB.get();
        if (a == null) {
            pane.add(new EmptyState("terminal", "No dispatch selected",
                "Pick a session on the left to see its exact prompt; hit \"compare\" on a second "
                + "session to diff the two."));
            return;
        }
        if (b == null) {
            pane.add(singlePrompt(a, promptA.get()));
        } else {
            pane.add(comparison(a, promptA.get(), b, promptB.get()));
        }
    }

    private Div singlePrompt(SessionSummaryDto session, String prompt) {
        Div wrap = new Div();
        wrap.addClassName("flex flex-col gap-3");
        wrap.add(promptHeader("A", "text-primary", session, prompt));
        if (prompt == null) {
            wrap.add(loading());
            return wrap;
        }
        List<Segment> segments = parseSegments(prompt);
        if (segments.isEmpty()) {
            wrap.add(rawBlock(prompt));
            return wrap;
        }
        for (Segment segment : segments) {
            wrap.add(segmentBlock(segment));
        }
        return wrap;
    }

    private Div comparison(SessionSummaryDto a, String pa, SessionSummaryDto b, String pb) {
        Div wrap = new Div();
        wrap.addClassName("flex flex-col gap-3");

        Div heads = new Div();
        heads.addClassName("grid grid-cols-2 gap-3");
        heads.add(promptHeader("A", "text-primary", a, pa), promptHeader("B", "text-secondary", b, pb));
        wrap.add(heads);

        if (pa == null || pb == null) {
            wrap.add(loading());
            return wrap;
        }

        Map<String, String> sa = segmentMap(pa);
        Map<String, String> sb = segmentMap(pb);
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        kinds.addAll(sa.keySet());
        kinds.addAll(sb.keySet());

        Div table = new Div();
        table.addClassName("flex flex-col divide-y divide-base-300 border border-base-300 rounded-lg "
            + "overflow-hidden");
        table.add(compareHeaderRow());
        for (String kind : kinds) {
            table.add(compareRow(kind, sa.get(kind), sb.get(kind)));
        }
        wrap.add(table);
        return wrap;
    }

    private Div compareHeaderRow() {
        Div row = new Div();
        row.addClassName("grid grid-cols-[1fr_auto_auto_auto] gap-3 px-3 py-1.5 bg-base-200 "
            + "text-[10px] font-semibold uppercase tracking-wider text-base-content/40");
        row.add(colText("segment"), colText("A"), colText("B"), colText(""));
        return row;
    }

    private Div compareRow(String kind, String a, String b) {
        boolean present = a != null && b != null;
        boolean changed = !Objects.equals(a, b);

        Div block = new Div();
        block.addClassName("flex flex-col");

        Div row = new Div();
        row.addClassName("grid grid-cols-[1fr_auto_auto_auto] gap-3 px-3 py-2 items-center text-xs "
            + (changed ? "cursor-pointer hover:bg-base-300/40" : ""));
        Span name = new Span(kind);
        name.addClassName("font-mono truncate");
        row.getElement().appendChild(name.getElement());
        row.add(colText(a == null ? "—" : "≈" + tokens(a)));
        row.add(colText(b == null ? "—" : "≈" + tokens(b)));
        Span verdict = new Span(!present ? "only " + (a != null ? "A" : "B")
            : changed ? "changed" : "same");
        verdict.addClassName("badge badge-xs " + (!present ? "badge-warning"
            : changed ? "badge-error" : "badge-success"));
        row.getElement().appendChild(verdict.getElement());
        block.add(row);

        if (changed) {
            Div detail = new Div();
            detail.addClassName("grid grid-cols-2 gap-3 px-3 pb-3");
            detail.setVisible(false);
            detail.add(sideText("A", a), sideText("B", b));
            boolean[] open = {false};
            row.addDomEventListener("click", e -> {
                open[0] = !open[0];
                detail.setVisible(open[0]);
            });
            block.add(detail);
        }
        return block;
    }

    // --- segment rendering -----------------------------------------------------------------------

    private Div promptHeader(String slot, String slotColor, SessionSummaryDto session, String prompt) {
        Div head = new Div();
        head.addClassName("flex items-center gap-2 flex-wrap");
        Span badge = new Span(slot);
        badge.addClassName("badge badge-sm " + (slot.equals("A") ? "badge-primary" : "badge-secondary"));
        head.getElement().appendChild(badge.getElement());
        Span who = new Span(session.getRole() + " · w" + session.getWorkerIndex()
            + " · " + session.getModel());
        who.addClassName("text-sm font-semibold " + slotColor);
        head.getElement().appendChild(who.getElement());
        Span size = new Span(prompt == null ? "…" : "≈" + tokens(prompt) + " tok");
        size.addClassName("text-xs font-mono text-base-content/50");
        head.getElement().appendChild(size.getElement());
        return head;
    }

    private Div segmentBlock(Segment segment) {
        Div block = new Div();
        block.addClassName("rounded-lg border border-base-300 overflow-hidden");
        Div head = new Div();
        head.addClassName("flex items-center gap-2 px-3 py-1.5 bg-base-200 cursor-pointer "
            + "hover:bg-base-300/60");
        Icon chevron = Icon.of("chevron-down", "w-3.5 h-3.5");
        Span kind = new Span(segment.kind);
        kind.addClassName("font-mono text-xs font-semibold flex-1");
        Span size = new Span("≈" + tokens(segment.body) + " tok · " + segment.body.length() + " ch");
        size.addClassName("text-[10px] font-mono text-base-content/40");
        head.add(chevron);
        head.getElement().appendChild(kind.getElement());
        head.getElement().appendChild(size.getElement());

        Div body = new Div();
        body.addClassName("overflow-x-auto max-h-72 overflow-y-auto");
        body.getElement().appendChild(pre(segment.body));
        head.addDomEventListener("click", e -> {
            boolean hide = !"none".equals(body.getElement().getStyle().getPropertyValue("display"));
            body.setVisible(!hide);
            chevron.setStyle("transform", hide ? "rotate(-90deg)" : "rotate(0deg)");
        });
        block.add(head, body);
        return block;
    }

    private Div sideText(String slot, String text) {
        Div wrap = new Div();
        wrap.addClassName("flex flex-col gap-1 min-w-0");
        Span label = new Span(slot);
        label.addClassName("text-[10px] font-bold text-base-content/40");
        wrap.getElement().appendChild(label.getElement());
        Div box = new Div();
        box.addClassName("overflow-auto max-h-60 rounded border border-base-300 bg-base-100");
        box.getElement().appendChild(pre(text == null ? "(absent)" : text));
        wrap.add(box);
        return wrap;
    }

    private Div rawBlock(String prompt) {
        Div block = new Div();
        block.addClassName("rounded-lg border border-base-300 overflow-auto max-h-[60vh]");
        block.getElement().appendChild(pre(prompt));
        return block;
    }

    private Div loading() {
        Div div = new Div("Loading prompt…");
        div.addClassName("text-sm text-base-content/50 py-4");
        return div;
    }

    private static Span colText(String text) {
        Span span = new Span(text);
        span.addClassName("font-mono text-right tabular-nums");
        return span;
    }

    private HTMLElement pre(String text) {
        HTMLElement pre = Window.current().getDocument().createElement("pre");
        pre.setClassName("m-0 p-3 font-mono text-[12px] leading-relaxed whitespace-pre-wrap "
            + "break-words text-base-content/80");
        pre.appendChild(Window.current().getDocument().createTextNode(text == null ? "" : text));
        return pre;
    }

    // --- data ------------------------------------------------------------------------------------

    private static final class Segment {
        final String kind;
        final String body;

        Segment(String kind, String body) {
            this.kind = kind;
            this.body = body;
        }
    }

    /** Splits a ## KIND-framed prompt (PromptBundle framing) into ordered segments. */
    private static List<Segment> parseSegments(String prompt) {
        List<Segment> segments = new ArrayList<>();
        if (prompt == null) {
            return segments;
        }
        String currentKind = null;
        StringBuilder body = new StringBuilder();
        for (String line : prompt.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (currentKind != null) {
                    segments.add(new Segment(currentKind, body.toString().strip()));
                }
                currentKind = line.substring(3).trim();
                body.setLength(0);
            } else if (currentKind != null) {
                body.append(line).append('\n');
            }
        }
        if (currentKind != null) {
            segments.add(new Segment(currentKind, body.toString().strip()));
        }
        return segments;
    }

    private static Map<String, String> segmentMap(String prompt) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Segment segment : parseSegments(prompt)) {
            map.put(segment.kind, segment.body);
        }
        return map;
    }

    private static int tokens(String text) {
        return text == null ? 0 : Math.max(1, (text.length() + 3) / 4);
    }

    private void loadSessions(String runId) {
        try {
            sessions.set(new ArrayList<>(graph.runSessions(runId)));
            sessionsError.set("");
        } catch (Exception ex) {
            // The empty list renders as "No sessions for this run.", and on the one screen whose
            // job is to show what a worker was actually given, that answer sends the operator
            // hunting a dispatch bug that is not there. Clicking the run chip again retries.
            ClientLog.error("PromptLabView", "could not load the sessions for run " + runId
                + " — this run's dispatches are unreadable, not absent: " + ex);
            sessions.set(new ArrayList<>());
            sessionsError.set("Could not load this run's sessions — they are unreadable, not "
                + "absent. Click the run again to retry.");
        }
    }

    private void loadPrompt(String sessionId, ValueSignal<String> target) {
        target.set(null);
        try {
            target.set(observer.sessionPrompt(sessionId));
        } catch (Exception ex) {
            // The placeholder already tells the operator on screen; logged as well because a
            // prompt that cannot be fetched is usually the same fault that broke the run being
            // investigated, and it is worth having in the server log next to it.
            ClientLog.error("PromptLabView", "could not load the prompt for session " + sessionId
                + " — it cannot be inspected or compared: " + ex);
            target.set("[prompt unavailable: " + ex.getMessage() + "]");
        }
    }
}

