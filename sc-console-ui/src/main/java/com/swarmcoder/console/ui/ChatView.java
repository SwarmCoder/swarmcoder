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

import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.console.api.ChatService;
import com.swarmcoder.console.api.ChatService_Stub;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.KeyedList;
import com.zeroz4j.ui.component.MarkdownView;
import com.zeroz4j.ui.component.StatusDot;
import com.zeroz4j.ui.component.StreamingText;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.TextArea;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.dom.events.KeyboardEvent;

import java.util.List;
import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.mixin.HasLayer;
import com.zeroz4j.ui.theme.Layer;
import java.util.ArrayList;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLElement;

/**
 * One chat tab (design §5.3): transcript (markdown coder bubbles, run-event cards,
 * actionable decision cards), streaming reply with Stop, and a composer with slash-command
 * intake. Renders entirely from ChatStore signals.
 *
 * <p>Implements {@link Disposable} and disposes its {@link Effect}s: {@code ChatStore}'s
 * transcript/stream signals are process-wide and outlive this view, so an effect bound to one keeps
 * being driven after the chat is swapped out unless it is torn down. {@code ChatDock} calls
 * {@link #dispose()} whenever it replaces the open chat, and when the shell tears the dock down.
 */
final class ChatView extends Div implements Disposable {

    private final ChatService chat = new ChatService_Stub();
    private final String chatId;
    private final Div scroll = new Div();
    private final List<Disposable> disposables = new ArrayList<>();

    /**
     * The transcript actually rendered: {@code ChatStore.transcript} narrowed by the header's
     * in-chat search. Kept as its own signal so {@link KeyedList} keeps its keyed diffing — the
     * alternative, re-rendering the thread by hand on every search keystroke, would throw away the
     * streaming bubbles' DOM identity.
     */
    private final ValueSignal<List<ChatMessage>> visible = new ValueSignal<>(new ArrayList<>());

    /** In-chat search text; view-local, so it does not need disposing. */
    private final ValueSignal<String> search = new ValueSignal<>("");

    private final Span searchStatus = new Span("");

    /**
     * Id of the coder message that carries the Regenerate control, or "". Mirrors the server's own
     * rule (ChatOrchestrator.regenerate): the last CODER message with no USER message after it, so
     * the control is offered exactly where the server would actually act.
     */
    private String regenerateAnchor = "";

    ChatView(String chatId) {
        this.chatId = chatId;
        addClassName("flex flex-col h-full min-h-0");

        add(header());

        scroll.addClassName("flex-1 min-h-0 overflow-y-auto px-6 py-4");
        Div thread = new Div();
        thread.addClassName("flex flex-col gap-3 max-w-3xl mx-auto");
        scroll.add(thread);
        add(scroll);

        // Filter + regenerate anchor first, so the list below renders against a populated signal.
        disposables.add(Effect.create(this::applySearch));

        // The anchor is part of the key: the bubble that loses (or gains) the Regenerate control
        // must be re-rendered, and a key of just the id would let a stale control survive.
        // Kept and disposed, like every effect above it: a keyed list watches its signal for as
        // long as it exists (ZeroZ Stack 0.8.0). The dock swaps this view out on every chat
        // switch, so an undisposed list would accumulate one live watcher per chat opened.
        disposables.add(new KeyedList<>(thread, visible,
            m -> m.getId().toString() + (isRegenerateAnchor(m) ? ":last" : ""), this::render));

        // The in-flight coder reply, below the persisted messages.
        Div streamingRow = new Div();
        streamingRow.addClassName("max-w-3xl mx-auto w-full px-6 pb-2");
        Div streamingBubble = new Div();
        streamingBubble.addClassName("rounded-xl bg-base-200 px-4 py-3 text-sm");
        StreamingText streaming = new StreamingText().bind(ChatStore.stream(chatId));
        streamingBubble.add(streaming);
        streamingRow.add(streamingBubble);
        add(streamingRow);
        disposables.add(Effect.create(() -> {
            String text = ChatStore.stream(chatId).get();
            streamingRow.setVisible(text != null && !text.isEmpty());
            scrollToBottom();
        }));

        add(composer());
        loadHistory();
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    // --- header (per-chat model + in-chat search + fork) -----------------------------------------

    private Div header() {
        Div bar = new Div();
        bar.addClassName("shrink-0 flex items-center gap-2 px-6 py-2 border-b border-base-300 "
            + "bg-base-200/40 text-xs");

        bar.add(Icon.of("bolt", "w-3.5 h-3.5 opacity-50"));
        HTMLElement select =
            Window.current().getDocument().createElement("select");
        select.setAttribute("class", "select select-xs select-bordered text-xs");
        select.setAttribute("title", "Model for this chat");
        select.addEventListener("change", (EventListener<
            Event>) e -> {
            String value = Js.selectValue(select);
            try {
                chat.setChatModel(chatId, value);
            } catch (Exception ex) {
                // Unwrapped, a throw from this native listener disappears into JS and the picker
                // keeps displaying the chosen model while the chat still runs on the old one —
                // the one misreport that makes every later reply hard to explain.
                ClientLog.error("ChatView", "could not set the model for chat " + chatId + " to '"
                    + value + "' — it is still running on the previous model: " + ex);
                showNotice("error: could not switch this chat's model, it is unchanged — "
                    + ex.getMessage());
            }
        });

        // Read the override back before painting the options: rendering "Project default model" on
        // a chat that has an override is a lie the operator cannot see through.
        String current = "";
        try {
            current = chat.chatModel(chatId);
        } catch (Exception e) {
            showNotice("error: could not read this chat's model — " + e.getMessage());
        }
        current = current == null ? "" : current;
        List<String> models = new ArrayList<>();
        try {
            models.addAll(chat.availableModels());
        } catch (Exception e) {
            showNotice("error: could not list models — " + e.getMessage());
        }
        // An override the roster no longer offers still has to appear, or the picker would silently
        // present the chat as running on the project default.
        if (!current.isEmpty() && !models.contains(current)) {
            models.add(current);
        }
        addOption(select, "", "Project default model", current.isEmpty());
        for (String model : models) {
            addOption(select, model, model, model.equals(current));
        }
        bar.getElement().appendChild(select);

        bar.add(searchBox());

        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);

        Div fork = new Div();
        fork.addClassName("flex items-center gap-1 text-base-content/50 cursor-pointer "
            + "hover:text-primary");
        fork.add(Icon.of("copy", "w-3.5 h-3.5"));
        Span forkLabel = new Span("Fork");
        fork.getElement().appendChild(forkLabel.getElement());
        fork.getElement().setAttribute("title", "Duplicate this chat with its transcript");
        fork.addDomEventListener("click", e -> {
            try {
                String newId = chat.fork(chatId);
                if (newId != null && newId.startsWith("error")) {
                    // A fork writes a new chat with a copy of the transcript. When it is refused
                    // nothing is created, and the notice is gone as soon as the operator types —
                    // so the log is what remains to explain the chat that never appeared.
                    ClientLog.error("ChatView", "fork of chat " + chatId + " was refused, no copy "
                        + "was created: " + newId);
                    showNotice(newId);
                } else if (newId != null) {
                    MainView.refreshChats();
                    Nav.openChat.accept(newId, "");
                }
            } catch (Exception ex) {
                ClientLog.error("ChatView", "could not fork chat " + chatId + " — no copy was "
                    + "created: " + ex);
                showNotice("error: fork failed — " + ex.getMessage());
            }
        });
        bar.add(fork);
        return bar;
    }

    private static void addOption(HTMLElement select, String value, String label, boolean selected) {
        HTMLElement option =
            Window.current().getDocument().createElement("option");
        option.setAttribute("value", value);
        if (selected) {
            option.setAttribute("selected", "selected");
        }
        option.appendChild(Window.current().getDocument().createTextNode(label));
        select.appendChild(option);
    }

    // --- in-chat search --------------------------------------------------------------------------

    /**
     * Finds text in THIS chat's transcript by narrowing what the thread renders. Deliberately not
     * {@code ObserverService.searchHistory}, which is the cross-session Lucene index over agent
     * traces — a different question with a different answer set.
     */
    private Div searchBox() {
        Div wrap = new Div();
        wrap.addClassName("flex items-center gap-1.5");

        wrap.add(Icon.of("search", "w-3.5 h-3.5 opacity-50"));
        TextField input = new TextField("Find in this chat");
        input.addClassName("input input-xs input-bordered w-44 text-xs");
        input.addValueChangeListener(e -> search.set(input.getValue() == null ? "" : input.getValue()));
        input.addDomEventListener("keydown", (EventListener<KeyboardEvent>) e -> {
            if ("Escape".equals(Keys.of(e))) {
                e.preventDefault();
                input.setValue("");
                search.set("");
            }
        });
        wrap.add(input);

        Div clear = new Div();
        clear.addClassName("cursor-pointer text-base-content/40 hover:text-error");
        clear.add(Icon.of("x", "w-3 h-3"));
        clear.getElement().setAttribute("title", "Clear the search");
        clear.addDomEventListener("click", e -> {
            input.setValue("");
            search.set("");
        });
        clear.setVisible(false);
        wrap.add(clear);

        searchStatus.setClassName("text-[10px] text-base-content/50 whitespace-nowrap");
        searchStatus.setVisible(false);
        wrap.getElement().appendChild(searchStatus.getElement());

        disposables.add(Effect.create(() -> clear.setVisible(!search.get().isEmpty())));
        return wrap;
    }

    /** Recomputes what the thread shows and where Regenerate belongs; driven by both signals. */
    private void applySearch() {
        List<ChatMessage> all = ChatStore.transcript(chatId).get();
        String raw = search.get();
        String query = raw == null ? "" : raw.strip().toLowerCase();
        regenerateAnchor = lastCoderId(all);

        List<ChatMessage> shown = new ArrayList<>();
        if (query.isEmpty()) {
            shown.addAll(all);
            searchStatus.setText("");
            searchStatus.setVisible(false);
        } else {
            for (ChatMessage message : all) {
                String text = message.getMarkdown();
                if (text != null && text.toLowerCase().contains(query)) {
                    shown.add(message);
                }
            }
            searchStatus.setText(shown.isEmpty()
                ? "no matches"
                : shown.size() + " of " + all.size());
            searchStatus.setClassName(shown.isEmpty()
                ? "text-[10px] text-warning whitespace-nowrap"
                : "text-[10px] text-base-content/50 whitespace-nowrap");
            searchStatus.setVisible(true);
        }
        visible.set(shown);
        // Only stick to the bottom when showing the whole transcript: yanking a filtered result set
        // to its end would fight the operator who is reading a match.
        if (query.isEmpty()) {
            scrollToBottom();
        }
    }

    // --- message rendering -----------------------------------------------------------------

    private Component render(ChatMessage message) {
        return switch (message.getKind()) {
            case "RUN_EVENT" -> systemCard(message, "play", "border-info/30");
            case "DECISION" -> decisionCard(message);
            case "ERROR" -> systemCard(message, "warning", "border-error/40");
            case "TOOL" -> toolCard(message);
            case "IMAGE" -> imageBubble(message);
            default -> "USER".equals(message.getRole()) ? userBubble(message) : coderBubble(message);
        };
    }

    private Div userBubble(ChatMessage message) {
        Div row = new Div();
        row.addClassName("flex justify-end");
        Div bubble = new Div();
        bubble.addClassName("rounded-xl bg-primary text-primary-content px-4 py-2.5 text-sm "
            + "max-w-[85%] whitespace-pre-wrap break-words");
        bubble.setText(message.getMarkdown());
        row.add(bubble);
        return row;
    }

    /** A pasted image, right-aligned like a user message. */
    private Div imageBubble(ChatMessage message) {
        Div row = new Div();
        row.addClassName("flex justify-end");
        Div bubble = new Div();
        bubble.addClassName("rounded-xl overflow-hidden max-w-[85%] border border-base-300");
        HTMLElement img =
            Window.current().getDocument().createElement("img");
        img.setAttribute("src", message.getMarkdown());
        img.setClassName("max-h-80 w-auto block");
        bubble.getElement().appendChild(img);
        row.add(bubble);
        return row;
    }

    private Div coderBubble(ChatMessage message) {
        Div row = new Div();
        row.addClassName("flex justify-start");
        Div column = new Div();
        column.addClassName("flex flex-col gap-1 max-w-[92%]");
        Div bubble = new Div();
        bubble.addClassName("rounded-xl bg-base-200 px-4 py-2.5");
        bubble.add(new MarkdownView(message.getMarkdown()));
        column.add(bubble);
        // Only the reply the server would actually replace gets the control; on every bubble it
        // would promise a rewrite of history the orchestrator will not perform.
        if (isRegenerateAnchor(message)) {
            column.add(regenerateControl());
        }
        row.add(column);
        return row;
    }

    private boolean isRegenerateAnchor(ChatMessage message) {
        return !regenerateAnchor.isEmpty()
            && regenerateAnchor.equals(message.getId().toString())
            && isCoderBubble(message);
    }

    /** True for messages {@link #render} turns into a coder bubble (not a card or a chip). */
    private static boolean isCoderBubble(ChatMessage message) {
        String kind = message.getKind();
        return !"RUN_EVENT".equals(kind) && !"DECISION".equals(kind) && !"ERROR".equals(kind)
            && !"TOOL".equals(kind) && !"IMAGE".equals(kind)
            && !"USER".equals(message.getRole());
    }

    /**
     * The message the server's {@code regenerate} would drop: the last CODER message with no USER
     * message after it. Computed the same way here so the control never appears above a reply the
     * server would leave alone.
     */
    private static String lastCoderId(List<ChatMessage> transcript) {
        ChatMessage lastCoder = null;
        for (ChatMessage message : transcript) {
            if ("CODER".equals(message.getRole())) {
                lastCoder = message;
            } else if ("USER".equals(message.getRole())) {
                lastCoder = null;
            }
        }
        return lastCoder == null ? "" : lastCoder.getId().toString();
    }

    private Div regenerateControl() {
        Div action = new Div();
        action.addClassName("self-start flex items-center gap-1 px-1 text-[11px] "
            + "text-base-content/40 cursor-pointer hover:text-primary");
        action.add(Icon.of("refresh", "w-3 h-3"));
        Span label = new Span("Regenerate");
        action.getElement().appendChild(label.getElement());
        action.getElement().setAttribute("title",
            "Discard this reply and answer the last message again");
        // Direct RMI (zeroz4j 0.3.0): DOM handlers already run on green threads.
        action.addDomEventListener("click", e -> {
            showNotice("");
            try {
                String result = chat.regenerate(chatId);
                // The server refuses while a reply is still streaming; that refusal is the whole
                // explanation for "nothing happened", so it has to reach the operator.
                if (result != null && result.startsWith("error")) {
                    // Regenerate deletes the reply and asks for another; a refusal means the
                    // transcript was left exactly as it was. Logged as well as shown, because the
                    // notice is cleared by the next click on this same control.
                    ClientLog.error("ChatView", "regenerate was refused for chat " + chatId
                        + ", the transcript is unchanged: " + result);
                    showNotice(result);
                }
            } catch (Exception ex) {
                ClientLog.error("ChatView", "could not regenerate the last reply in chat " + chatId
                    + ", the transcript is unchanged: " + ex);
                showNotice("error: regenerate failed — " + ex.getMessage());
            }
        });
        return action;
    }

    private Div systemCard(ChatMessage message, String icon, String borderClass) {
        Div card = new Div();
        card.addClassName("flex items-start gap-2.5 rounded-lg border " + borderClass
            + " bg-base-200/50 px-3 py-2 text-sm");
        card.add(Icon.of(icon, "w-4 h-4 mt-0.5 opacity-60"));
        Div body = new Div();
        body.addClassName("flex-1 min-w-0");
        body.add(new MarkdownView(message.getMarkdown()));
        card.add(body);
        if (!runIdStr(message).isEmpty()) {
            // Run cards open the live run graph (design §6).
            Div openGraph = new Div();
            openGraph.addClassName("cursor-pointer text-base-content/40 hover:text-primary shrink-0");
            openGraph.add(Icon.of("graph", "w-4 h-4"));
            openGraph.getElement().setAttribute("title", "Open run graph");
            openGraph.addDomEventListener("click",
                e -> Nav.openRun.accept(runIdStr(message)));
            card.add(openGraph);
        }
        return card;
    }

    /** The analyst researched something — a compact activity chip, not a full card. */
    private Div toolCard(ChatMessage message) {
        Div chip = new Div();
        chip.addClassName("flex items-center gap-1.5 text-[11px] text-base-content/40 px-2");
        chip.add(Icon.of("search", "w-3 h-3"));
        Div body = new Div();
        body.addClassName("font-mono truncate");
        body.setText(message.getMarkdown().replace("`", ""));
        chip.add(body);
        return chip;
    }

    /**
     * A build stopped to ask something: the notice, and the way to the place it is answered.
     *
     * <h2>Why there are no answer buttons on this card any more</h2>
     *
     * <p>There used to be three. <b>Approve</b> and <b>Reject</b> appeared when the message text
     * happened to contain the word {@code APPROVAL}, which was two faults at once: it printed a Java
     * constant into a sentence a person reads, and it gated a control on a state UX v3 §2.3 deleted
     * — a build no longer parks waiting to be approved, it integrates on green gates and comes back
     * for judgment on its own. Nothing has written that state since, so those two buttons could
     * never appear at all. <b>Resolve</b> was worse: it wrote the fixed words "approved from chat"
     * into the record as though they were the operator's answer, having asked them nothing. §1 names
     * that button by name as one of the six original diseases — "a 'Resolve' that resolves nothing".
     *
     * <p>The question itself is answered on the card of the story it belongs to (§2.3, §9 step 4),
     * where the brief, a box to write the answer in and the story it is about are all in one place.
     * Putting a second answer path here would be defect D3 from §1's table — the same decision taken
     * in two places, by two mechanisms, free to disagree. So this card notifies and points; the chat
     * is the companion dock (§3) and never a place work has to be done.
     */
    private Div decisionCard(ChatMessage message) {
        Div card = new Div();
        card.addClassName("rounded-lg border border-warning/50 bg-warning/5 px-4 py-3 text-sm "
            + "flex flex-col gap-2");
        card.getElement().setAttribute("data-testid", "chat-decision");
        Div header = new Div();
        header.addClassName("flex items-center gap-2 font-semibold text-warning");
        header.add(Icon.of("inbox", "w-4 h-4"));
        Span label = new Span("A build stopped to ask you something");
        header.getElement().appendChild(label.getElement());
        card.add(header);
        card.add(new MarkdownView(message.getMarkdown()));

        // Sentence first, on its own line, THEN the button. The dock is a narrow column and the
        // two used to sit side by side, which left the sentence wrapping into a two-line block
        // beside a button half its height at every window width.
        Div where = new Div("The question is on the story's own card, with a box to answer it in.");
        where.addClassName("text-[11px] leading-snug text-base-content/60");
        card.add(where);

        Div actions = new Div();
        actions.addClassName("flex items-center gap-2 mt-1");
        Button show = new Button("Show me the story", e -> Nav.openApprovals.run());
        show.addClassName("btn-warning btn-xs");
        actions.add(show);
        card.add(actions);
        return card;
    }

    // --- composer ------------------------------------------------------------------------------

    /** Result line for a dropped requirements document; empty until something is uploaded. */
    private final Div uploadHint = new Div();

    /** Errors the operator must see (a refused regenerate, a failed RMI call). */
    private final Div notice = new Div();

    private Div composer() {
        Div bar = new Div();
        bar.addClassName("shrink-0 border-t border-base-300 bg-base-200/40 px-6 py-3");
        Div inner = new Div();
        inner.addClassName("max-w-3xl mx-auto flex items-end gap-2");

        // Adding a requirements document from the chat does NOT send anything to the model: the
        // file becomes a SourceDocument the Requirements panel's "Analyse documents" wizard reads,
        // so the hint says what to do next rather than implying work has started.
        uploadHint.addClassName("max-w-3xl mx-auto text-[11px] text-base-content/50 pt-1");

        notice.addClassName("max-w-3xl mx-auto text-[11px] text-error pt-1");
        notice.setVisible(false);

        TextArea input = new TextArea();
        input.addClassName("textarea textarea-bordered flex-1 min-h-[2.75rem] max-h-40 text-sm "
            + "leading-snug resize-none");
        // Four words, because the box is one line tall.
        //
        // It used to hold ninety-three characters documenting three separate features. A
        // placeholder is a single line of grey text inside the input, and this input is
        // 2.75rem high with rows=1, so the sentence wrapped and was cut off at the bottom edge at
        // EVERY window width: what the operator actually read was "Message the coder — / for
        // commands · @file to reference code · Ctrl+V to", stopping mid-sentence.
        //
        // Making the box taller would have fixed the clipping and kept the real fault, which is
        // that a placeholder is the wrong place to teach anybody anything: it is gone the instant
        // they start typing, which is exactly the moment "/" and "@" become useful. The three
        // features now live in a hint UNDER the composer, where they stay put and can wrap.
        input.getElement().setAttribute("placeholder", "Message the coder");
        input.getElement().setAttribute("rows", "1");
        // Named, because "the textarea on the page" stopped being unique: chat is now a companion
        // pane that rides alongside a stage, and the requirements editor has a textarea of its own.
        input.getElement().setAttribute("data-testid", "chat-composer");

        // Paste a screenshot (Ctrl+V) → attach it to the chat as an image message.
        // Direct RMI (zeroz4j 0.3.0): no background thread — handlers run on green threads.
        Js.onPasteImage(input.getElement(), dataUri -> chat.sendImage(chatId, dataUri));

        Button stop = new Button(Icon.of("stop", "w-4 h-4"));
        stop.addClassName("btn-ghost btn-sm text-error");
        stop.getElement().setAttribute("title", "Stop the coder's reply");
        stop.addClickListener(e -> chat.stop(chatId));
        disposables.add(
            Effect.create(() -> stop.setVisible(!ChatStore.stream(chatId).get().isEmpty())));

        Button send = new Button(Icon.of("send", "w-4 h-4"));
        send.addClassName("btn-primary btn-sm");
        send.addClickListener(e -> submit(input));

        // @-mention autocomplete: a dropdown of project files above the composer.
        Div mentionBox = new Div();
        mentionBox.addClassName("absolute bottom-full left-0 right-0 mb-1 max-h-56 overflow-y-auto "
            + "bg-base-100 border border-base-300 rounded-lg shadow-lg");
        // A named tier rather than a number somebody picked. Both completion panels are menus
        // opened from a control, which is what Layer.DROPDOWN means; the old z-20 was a guess,
        // and a guess only holds until a second part of the console picks a bigger one.
        HasLayer.applyTo(mentionBox, Layer.DROPDOWN);
        mentionBox.setVisible(false);

        // Slash-command autocomplete: same box, same keys — a second completion behaviour in one
        // composer would be worse than none.
        Div commandBox = new Div();
        commandBox.addClassName("absolute bottom-full left-0 right-0 mb-1 max-h-56 overflow-y-auto "
            + "bg-base-100 border border-base-300 rounded-lg shadow-lg");
        HasLayer.applyTo(commandBox, Layer.DROPDOWN);
        commandBox.setVisible(false);

        input.addDomEventListener("input",
            (EventListener<Event>) e ->
                updateCompletions(input, mentionBox, commandBox));
        input.addDomEventListener("keydown",
            (EventListener<KeyboardEvent>) e -> {
                if ("Escape".equals(Keys.of(e)) && (mentionOpen || commandOpen)) {
                    e.preventDefault();
                    closeMentions(mentionBox);
                    closeCommands(commandBox);
                    return;
                }
                if ("Enter".equals(Keys.of(e)) && !e.isShiftKey()) {
                    e.preventDefault();
                    // Precedence: an open completion consumes Enter, then the message is sent.
                    // The two dropdowns are mutually exclusive (a '/' token has no whitespace, and
                    // an @-mention needs whitespace before it), but the order is spelled out so
                    // neither can start eating the other's Enter.
                    if (mentionOpen && !currentMentions.isEmpty()) {
                        insertMention(input, mentionBox, currentMentions.get(0));
                    } else if (commandOpen && !currentCommands.isEmpty()) {
                        insertCommand(input, commandBox, currentCommands.get(0));
                    } else {
                        submit(input);
                    }
                }
            });

        Div inputWrap = new Div();
        inputWrap.addClassName("relative flex-1 flex");
        inputWrap.add(input, mentionBox, commandBox);

        // A window holding the shared upload box, so a document can be added without leaving the
        // conversation. The framework's uploader owns its own drop area, which is why this is a
        // button and a window rather than the whole composer being a drop target as it once was.
        com.zeroz4j.ui.component.Dialog uploads = DocumentUpload.dialog(
            () -> uploadHint.setText("Added to this project. Open Requirements and press "
                + "\"Analyse documents\" to turn it into requirements."));
        Button attach = new Button(Icon.of("file", "w-4 h-4"));
        attach.addClassName("btn-ghost btn-sm");
        attach.getElement().setAttribute("title",
            "Add a requirements document to this project");
        attach.addClickListener(e -> uploads.open());

        // What the box can do, in three short sentences that stay on screen while you type.
        Div hints = new Div("Type / to pick a command. Type @ and part of a file name to point at "
            + "a file. You can paste a screenshot straight in.");
        hints.addClassName("max-w-3xl mx-auto text-[11px] text-base-content/50 pt-1.5 "
            + "leading-relaxed");
        hints.getElement().setAttribute("data-testid", "chat-composer-hints");

        inner.add(inputWrap, attach, stop, send);
        bar.add(uploads);
        bar.add(inner);
        bar.add(hints);
        bar.add(uploadHint);
        bar.add(notice);
        return bar;
    }

    private void showNotice(String text) {
        notice.setText(text == null ? "" : text);
        notice.setVisible(text != null && !text.isEmpty());
    }

    /** One entry point so exactly one dropdown can ever be open. */
    private void updateCompletions(TextArea input, Div mentionBox, Div commandBox) {
        String command = trailingCommand(input.getValue());
        if (command != null) {
            closeMentions(mentionBox);
            updateCommands(input, commandBox, command);
            return;
        }
        closeCommands(commandBox);
        updateMentions(input, mentionBox);
    }

    // --- @-mention autocomplete ------------------------------------------------------------------

    private boolean mentionOpen;
    private List<String> currentMentions = new ArrayList<>();

    /** Set while the last candidate fetch failed, so the report below is made once, not per key. */
    private boolean mentionsFailed;

    /** The @token being typed (whitespace-preceded '@' then a path fragment), or null. */
    private static String trailingMention(String text) {
        if (text == null) {
            return null;
        }
        int at = text.lastIndexOf('@');
        if (at < 0 || (at > 0 && !Character.isWhitespace(text.charAt(at - 1)))) {
            return null;
        }
        String tail = text.substring(at + 1);
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '_' || c == '-')) {
                return null; // token broken by a space/newline — not an active mention
            }
        }
        return tail;
    }

    private void updateMentions(TextArea input, Div mentionBox) {
        String fragment = trailingMention(input.getValue());
        if (fragment == null) {
            closeMentions(mentionBox);
            return;
        }

        try {
            renderMentions(input, mentionBox, chat.mentionCandidates(chatId, fragment));
            mentionsFailed = false;
        } catch (Exception e) {
            // The dropdown is still left as it is: tearing it down mid-typing would be worse than
            // an unchanged list. What was wrong before is that the operator got no hint at all, and
            // an @-mention that completes nothing looks like a project with no matching files.
            // Said once, on the way into failure — this runs on every keystroke of a mention.
            if (!mentionsFailed) {
                ClientLog.error("ChatView", "could not fetch @-mention candidates — file "
                    + "completion is unavailable, the list shown may be stale: " + e);
                showNotice("error: file completion unavailable — " + e.getMessage());
            }
            mentionsFailed = true;
        }
    }

    private void renderMentions(TextArea input, Div mentionBox, List<String> candidates) {
        currentMentions = candidates == null ? new ArrayList<>() : candidates;
        mentionBox.removeAll();
        if (currentMentions.isEmpty()) {
            closeMentions(mentionBox);
            return;
        }
        for (String address : currentMentions) {
            Div row = new Div();
            row.addClassName("flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-base-200 "
                + "text-xs");
            row.add(Icon.of("file", "w-3.5 h-3.5 opacity-50"));
            Span label = new Span(address);
            label.addClassName("font-mono truncate");
            row.getElement().appendChild(label.getElement());
            row.addDomEventListener("click", e -> insertMention(input, mentionBox, address));
            mentionBox.add(row);
        }
        mentionOpen = true;
        mentionBox.setVisible(true);
    }

    private void insertMention(TextArea input, Div mentionBox, String address) {
        String value = input.getValue() == null ? "" : input.getValue();
        int at = value.lastIndexOf('@');
        String base = at < 0 ? value : value.substring(0, at);
        input.setValue(base + "@" + address + " ");
        closeMentions(mentionBox);
        input.getElement().focus();
    }

    private void closeMentions(Div mentionBox) {
        mentionOpen = false;
        currentMentions = new ArrayList<>();
        mentionBox.removeAll();
        mentionBox.setVisible(false);
    }

    // --- slash-command autocomplete ---------------------------------------------------------------

    private boolean commandOpen;
    private List<String> currentCommands = new ArrayList<>();

    /**
     * The command catalogue, served by {@link ChatService#commands()} so a command added to the
     * orchestrator cannot go missing from the UI that is supposed to reveal it. Fetched lazily on
     * first use inside a DOM handler: a suspending RMI call from constructor-time code reached via
     * a bootstrap callback throws "Suspension point reached from non-threading context".
     */
    private List<String> commandCatalog;

    /** Set while the last catalogue fetch failed, so the report below is made once, not per key. */
    private boolean commandCatalogFailed;

    private List<String> commandCatalog() {
        if (commandCatalog == null) {
            try {
                List<String> served = chat.commands();
                commandCatalog = served == null ? new ArrayList<>() : new ArrayList<>(served);
                commandCatalogFailed = false;
            } catch (Exception e) {
                // The field is left null so the next '/' tries again. Caching the empty list made
                // one failed call permanent for the session, and an empty catalogue is silently
                // indistinguishable from a server that offers no commands at all — the operator
                // would conclude the feature does not exist. The failure is reported once because
                // this is reached on every keystroke of a slash token.
                if (!commandCatalogFailed) {
                    ClientLog.error("ChatView", "could not load the slash-command catalogue — no "
                        + "command completions will be offered until it loads: " + e);
                }
                commandCatalogFailed = true;
                showNotice("error: slash commands unavailable — " + e.getMessage());
                return new ArrayList<>();
            }
        }
        return commandCatalog;
    }

    /**
     * The /command token being typed, or null. Active only while the composer holds nothing but
     * that first token — the same trailing-token rule the @-mention completion uses, and the reason
     * "/run build a thing" stops offering completions the moment the goal starts.
     */
    private static String trailingCommand(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return null;
            }
        }
        return text;
    }

    private void updateCommands(TextArea input, Div commandBox, String token) {
        String lower = token.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String entry : commandCatalog()) {
            if (commandName(entry).toLowerCase().startsWith(lower)) {
                matches.add(entry);
            }
        }
        // A single exact match has nothing left to complete, so the dropdown closes and Enter sends
        // rather than costing a second keystroke on a fully typed "/done".
        if (matches.size() == 1 && commandName(matches.get(0)).equalsIgnoreCase(token)) {
            closeCommands(commandBox);
            return;
        }
        renderCommands(input, commandBox, matches);
    }

    private void renderCommands(TextArea input, Div commandBox, List<String> matches) {
        currentCommands = matches;
        commandBox.removeAll();
        if (currentCommands.isEmpty()) {
            closeCommands(commandBox);
            return;
        }
        for (String entry : currentCommands) {
            Div row = new Div();
            row.addClassName("flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-base-200 "
                + "text-xs");
            row.add(Icon.of("terminal", "w-3.5 h-3.5 opacity-50"));
            Span name = new Span(commandName(entry));
            name.addClassName("font-mono shrink-0");
            row.getElement().appendChild(name.getElement());
            Span description = new Span(commandDescription(entry));
            description.addClassName("text-base-content/50 truncate");
            row.getElement().appendChild(description.getElement());
            row.addDomEventListener("click", e -> insertCommand(input, commandBox, entry));
            commandBox.add(row);
        }
        commandOpen = true;
        commandBox.setVisible(true);
    }

    /** The token is the whole composer text, so completing it replaces the text outright. */
    private void insertCommand(TextArea input, Div commandBox, String entry) {
        input.setValue(commandName(entry) + " ");
        closeCommands(commandBox);
        input.getElement().focus();
    }

    private void closeCommands(Div commandBox) {
        commandOpen = false;
        currentCommands = new ArrayList<>();
        commandBox.removeAll();
        commandBox.setVisible(false);
    }

    /** Entries are "/name<TAB>description". */
    private static String commandName(String entry) {
        int tab = entry.indexOf('\t');
        return tab < 0 ? entry : entry.substring(0, tab);
    }

    private static String commandDescription(String entry) {
        int tab = entry.indexOf('\t');
        return tab < 0 ? "" : entry.substring(tab + 1);
    }

    // --- send ------------------------------------------------------------------------------------

    private void submit(TextArea input) {
        String text = input.getValue();
        if (text == null || text.strip().isEmpty()) {
            return;
        }
        input.setValue("");
        showNotice("");
        try {
            String result = chat.send(chatId, text.strip());
            if (result != null && result.startsWith("error")) {
                ClientLog.error("ChatView", "the server refused a chat message, it was not sent: "
                    + result);
                showNotice(result);
            }
        } catch (Exception e) {
            // This runs from the send button's click handler and from the composer's keydown, both
            // native DOM callbacks: an exception escaping here vanishes into JS, and since the
            // composer has already been cleared the operator sees their message disappear into a
            // button that did nothing at all.
            ClientLog.error("ChatView", "could not send the chat message — it was not delivered "
                + "and is not in the transcript: " + e);
            showNotice("error: message not sent — " + e.getMessage());
        }
    }

    private void loadHistory() {

        try {
            List<ChatMessage> history = chat.history(chatId);
            ChatStore.transcript(chatId).set(new ArrayList<>(history));
        } catch (Exception e) {
            // The transcript signal is deliberately not written here, so nothing stores this
            // failure as an empty conversation and the next push still fills the thread. But until
            // then the tab shows a blank transcript, which reads as a chat where nothing was ever
            // said — so it is said out loud too, not only logged.
            ClientLog.error("ChatView", "could not load the transcript for chat " + chatId
                + " — the thread shown is incomplete, not empty: " + e);
            showNotice("error: could not load this chat's history — the thread may be incomplete ("
                + e.getMessage() + ")");
        }
    }

    /**
     * Sticks the transcript to the bottom. Deferred a tick (Window.setTimeout, the proven
     * TeaVM functor) because the signal fires before the new message/stream text has been
     * laid out — a synchronous read would see the old scrollHeight.
     */
    private void scrollToBottom() {
        Window.setTimeout(() ->
            scroll.getElement().setScrollTop(scroll.getElement().getScrollHeight()), 0);
    }
    private static String runIdStr(ChatMessage m) {
        return m.getRunId() == null ? "" : m.getRunId().toString();
    }

}
