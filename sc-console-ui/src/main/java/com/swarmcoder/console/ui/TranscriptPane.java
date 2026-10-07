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

import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.swarmcoder.console.api.TraceEventDto;
import com.zeroz4j.ui.component.CodeBlock;
import com.zeroz4j.ui.component.DiffView;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.MarkdownView;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspector level 2+ (design §6.4): a session's full transcript — LLM output as markdown,
 * tool calls/results as code, kills/nudges as warnings — down to the exact bytes via
 * "Load full payload" from the blob store.
 */
final class TranscriptPane extends Div {

    private final ObserverService observer = new ObserverService_Stub();
    private final ValueSignal<List<TraceEventDto>> events = new ValueSignal<>(new ArrayList<>());
    private final String sessionId;

    TranscriptPane(String sessionId) {
        this.sessionId = sessionId;
        addClassName("flex flex-col gap-2 p-3");
        Div list = new Div();
        list.addClassName("flex flex-col gap-2");
        add(list);
        Effect.create(() -> {
            list.removeAll();
            List<TraceEventDto> current = events.get();
            if (current.isEmpty()) {
                list.add(new EmptyState("clock", "Loading transcript…",
                    "Session " + sessionId.substring(0, 8)));
            }
            for (TraceEventDto event : current) {
                list.add(render(event));
            }
        });
        
            try {
                events.set(new ArrayList<>(observer.sessionEvents(sessionId, 0, 500)));
            } catch (Exception ex) {
                // The empty state says "Loading transcript…" forever otherwise, which reads as a
                // session that has not started rather than one whose events could not be fetched.
                list.removeAll();
                Div failed = new Div("Transcript unavailable: " + ex.getMessage());
                failed.addClassName("text-xs text-error p-2");
                list.add(failed);
                ClientLog.error("TranscriptPane", "session transcript failed to load — the "
                    + "transcript for session " + sessionId + " is not being shown at all: " + ex);
            }
    }

    private Div render(TraceEventDto event) {
        Div card = new Div();
        card.addClassName("rounded-lg border border-base-300 overflow-hidden cursor-pointer hover:border-primary/50 transition-colors relative");

        Div header = new Div();
        header.addClassName("flex items-center gap-2 px-2.5 py-1 text-[11px] "
            + headerClass(event.getKind()));
        header.add(Icon.of(iconFor(event.getKind()), "w-3 h-3"));
        Span kind = new Span(event.getKind() + (event.getLabel().isEmpty() ? "" : " · " + event.getLabel()));
        kind.addClassName("font-semibold");
        Span meta = new Span("#" + event.getSeq()
            + (event.getTokens() > 0 ? " · " + event.getTokens() + " tok" : ""));
        meta.addClassName("ml-auto opacity-60 font-mono");
        header.getElement().appendChild(kind.getElement());
        header.getElement().appendChild(meta.getElement());
        card.add(header);

        Div body = new Div();
        body.addClassName("px-2.5 py-1.5 text-xs");
        renderBody(body, event.getKind(), event.getLabel(), event.getPayloadSnippet());
        card.add(body);

        boolean truncated = !event.getPayloadRef().isEmpty()
            || (event.getPayloadSnippet() != null && event.getPayloadSnippet().endsWith("…"));
            
        if (truncated) {
            Div fade = new Div();
            fade.addClassName("absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-base-100 to-transparent pointer-events-none");
            card.add(fade);
        }

        org.teavm.jso.dom.events.EventListener<org.teavm.jso.dom.events.MouseEvent> clickListener = e -> {
            
                try {
                    String full = truncated ? observer.eventPayload(sessionId, event.getSeq()) : event.getPayloadSnippet();
                    if (full != null && !full.isBlank()) {
                        Div dialogBody = new Div();
                        dialogBody.addClassName("max-h-[70vh] overflow-y-auto text-left");
                        dialogBody.getElement().setAttribute("data-testid", "transcript-payload");
                        renderBody(dialogBody, event.getKind(), event.getLabel(), full);
                        showOverThePage(dialogBody);
                    }
                } catch (Exception ex) {
                    // Clicking a card is a request to see the full payload, so a failure has to
                    // answer in the same place the payload would have appeared — otherwise the
                    // card just looks unclickable.
                    Div failed = new Div("Full payload unavailable: " + ex.getMessage());
                    failed.addClassName("text-sm text-error");
                    failed.getElement().setAttribute("data-testid", "transcript-payload-failed");
                    showOverThePage(failed);
                    ClientLog.error("TranscriptPane", "event payload failed to load — the full "
                        + "text of event #" + event.getSeq() + " in session " + sessionId
                        + " cannot be shown: " + ex);
                }
        };
        card.addDomEventListener("click", clickListener);

        return card;
    }

    /**
     * Puts one panel on screen over everything else, and takes it away again when it is closed.
     *
     * <p>The two calls above used to build a {@code Dialog}, fill it, and call {@code open()} —
     * and never put it in the page. {@code Dialog.open()} adds daisyUI's {@code modal-open} class
     * and nothing more; an element that is not in the document has nothing to add the class to the
     * layout of, so clicking a transcript event did nothing at all, silently, with no error in the
     * console. Every other dialog in the Console is mounted into a host div before it is opened;
     * these two were the exception, which is why the fault survived — no test had ever clicked one.
     *
     * <p>Mounted on the document body rather than on this pane. A daisyUI {@code .modal-box}
     * carries a {@code transform}, and a transformed element is the containing block for its
     * {@code position: fixed} descendants: this pane is shown inside the drill-down, so a dialog
     * nested in it would be laid out and clipped inside the drill-down's panel instead of over the
     * page. Removing it on close rather than merely hiding it keeps a long reading session from
     * leaving a stack of dead modals in the document.
     *
     * <p>Nothing here says what should be in front of what any more. Since ZeroZ Stack 0.8.0 a
     * dialog is a native one, drawn in the browser's top layer, where dialogs stack in the order
     * they were opened. This one is opened last, so it is in front of the drill-down whose button
     * asked for it and of the run graph behind that, with no stacking number in sight.
     */
    private static void showOverThePage(Div content) {
        com.zeroz4j.ui.component.Dialog dialog = new com.zeroz4j.ui.component.Dialog();
        // The default panel is about 32rem wide, too narrow for a diff or a page of model output.
        dialog.setWidth("56rem");
        dialog.add(content);
        Button closeBtn = new Button("Close");
        closeBtn.addClassName("btn-sm btn-primary");
        closeBtn.addClickListener(ev -> dialog.close());
        dialog.addAction(closeBtn);
        // Escape and a click outside close it as well, so the tidying up hangs off the close rather
        // than off the button, which is no longer the only way out.
        dialog.addCloseListener(ev -> {
            org.teavm.jso.dom.xml.Node parent = dialog.getElement().getParentNode();
            if (parent != null) {
                parent.removeChild(dialog.getElement());
            }
        });
        org.teavm.jso.browser.Window.current().getDocument().getBody()
            .appendChild(dialog.getElement());
        dialog.open();
    }

    private static void renderBody(Div body, String kind, String label, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if ("LLM_RESPONSE".equals(kind) || "DONE".equals(kind)) {
            body.add(new MarkdownView(text));
        } else if (isDiff(label)) {
            // A worker changing code is the thing the operator most wants to read here, and it
            // used to arrive as syntax-coloured text in a code block: no per-file grouping, no
            // count of what was added or removed, no gutter. DiffView reads the unified form and
            // shows it as a diff.
            body.add(new DiffView(text));
        } else {
            // The code block draws its own Copy control and wires it the deferred way, so it says
            // "Copied" and copies nothing. Repaired here — see Clipboard.repairCopyButton.
            CodeBlock block = new CodeBlock("json", text);
            Clipboard.repairCopyButton(block.getElement(), text,
                "the transcript's " + (label == null || label.isEmpty() ? "payload" : label));
            body.add(block);
        }
    }

    /** The two tool payloads that carry a unified diff. */
    private static boolean isDiff(String label) {
        return "apply_diff".equals(label) || "write_file".equals(label);
    }

    private static String headerClass(String kind) {
        return switch (kind) {
            case "KILL" -> "bg-error/15 text-error";
            case "NUDGE" -> "bg-warning/15 text-warning";
            case "TOOL_CALL" -> "bg-info/10 text-info";
            case "TOOL_RESULT" -> "bg-base-200 text-base-content/70";
            case "LLM_RESPONSE" -> "bg-primary/10 text-primary";
            default -> "bg-base-200 text-base-content/60";
        };
    }

    private static String iconFor(String kind) {
        return switch (kind) {
            case "TOOL_CALL", "TOOL_RESULT" -> "terminal";
            case "LLM_RESPONSE" -> "chat";
            case "KILL" -> "skull";
            case "NUDGE" -> "warning";
            default -> "dot";
        };
    }

}

