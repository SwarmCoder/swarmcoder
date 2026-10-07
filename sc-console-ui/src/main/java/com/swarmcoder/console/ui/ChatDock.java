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
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.KeyedList;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

/**
 * Chat, in every stage.
 *
 * <p><b>Chat is not a stage.</b> It is where you think out loud, ask what the codebase does, and
 * work out what you even want — and you do that while looking at requirements, while triaging the
 * backlog, and while a run is going wrong. Making it a destination would mean leaving whatever
 * prompted the question in order to ask it, which is exactly when a question stops being asked.
 *
 * <p>So it is a companion pane the stages slide under, not a tab: opened from the top bar, kept in
 * whatever state it was in across every stage swap, and never torn down by one. Its open/closed
 * choice is persisted client-side like the theme.
 *
 * <p>Building a {@link ChatView} loads its history over RMI, so {@link #openChat} must be called from
 * a DOM event handler or a green thread — from a signal {@link Effect} or a native JS callback a
 * suspending call throws "Suspension point reached from non-threading context".
 */
final class ChatDock extends Div implements Disposable {

    private static final String KEY = "console.chat";

    /** Whether the dock is showing. A signal so the top-bar button can render its own state. */
    static final ValueSignal<Boolean> open =
        new ValueSignal<>("1".equals(Js.localGet(KEY)));

    private final Div host = new Div();
    private final Div placeholder = new Div();
    private final java.util.List<Disposable> disposables = new java.util.ArrayList<>();
    private ChatView current;
    private String currentId;

    ChatDock() {
        // Wide enough to think in, narrow enough that the stage beside it is still usable — the
        // whole point is that you do not leave what you were looking at in order to ask about it.
        // Resizable from the shell if that trade is wrong for a given screen.
        addClassName("w-[26rem] shrink-0 min-h-0 flex flex-col border-l border-base-300 "
            + "bg-base-100 overflow-hidden");
        // Named, so a test can say "the chat is open" without depending on whether a conversation
        // has been started in it — with none, the dock shows only its placeholder.
        getElement().setAttribute("data-testid", "chat-dock");
        add(header());
        add(chatList());
        host.addClassName("flex-1 min-h-0 flex flex-col");
        add(host);

        placeholder.addClassName("flex-1 min-h-0 flex items-center justify-center px-6 "
            + "text-center text-xs text-base-content/40 leading-relaxed");
        placeholder.setText("Pick a chat above, or start a new one. Chat stays with you through "
            + "every stage — it is where you think out loud, not a step you go to.");
        host.add(placeholder);

        disposables.add(Effect.create(() -> setVisible(Boolean.TRUE.equals(open.get()))));
    }

    @Override
    public void dispose() {
        if (current != null) {
            current.dispose();
            current = null;
        }
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Shows or hides the dock and persists the choice. */
    static void setOpen(boolean value) {
        Js.localSet(KEY, value ? "1" : "0");
        open.set(value);
    }

    static void toggle() {
        setOpen(!Boolean.TRUE.equals(open.get()));
    }

    /**
     * Opens a chat in the dock, replacing whatever was there.
     *
     * <p>MUST be called from a DOM event handler or a green thread — see the class javadoc.
     */
    void openChat(String chatId, String title) {
        setOpen(true);
        if (chatId == null || chatId.equals(currentId)) {
            return;
        }
        if (current != null) {
            host.remove(current);
            // The outgoing view holds effects on the chat stores; leaving it bound would keep it
            // rendering into DOM that is no longer in the document.
            current.dispose();
            current = null;
        }
        placeholder.setVisible(false);
        currentId = chatId;
        current = new ChatView(chatId);
        host.add(current);
    }

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 shrink-0");
        bar.add(Icon.of("chat", "w-4 h-4 opacity-60"));
        Span title = new Span("Chat");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        Div hide = new Div();
        hide.addClassName("cursor-pointer text-base-content/40 hover:text-error");
        hide.add(Icon.of("x", "w-3.5 h-3.5"));
        hide.getElement().setAttribute("title", "Hide chat");
        hide.addDomEventListener("click", e -> setOpen(false));
        bar.add(hide);
        return bar;
    }

    /** The chat picker: compact, above the thread, so switching does not mean leaving. */
    private Div chatList() {
        Div wrap = new Div();
        wrap.addClassName("shrink-0 max-h-28 overflow-y-auto border-b border-base-300 "
            + "px-1 py-1 flex flex-col gap-0.5 bg-base-200/30");
        // Kept and disposed. Since ZeroZ Stack 0.8.0 a keyed list watches its signal for as long
        // as it exists, and this one watches a process-wide store signal that outlives the dock.
        // Dropped on the floor it goes on rebuilding a list nobody is looking at, for ever.
        disposables.add(new KeyedList<>(wrap, ChatStore.chats,
            chat -> chat.getId().toString() + ":" + chat.getTitle(), chat -> {
                Div item = new Div();
                item.addClassName("flex items-center gap-2 px-2 py-1 rounded cursor-pointer "
                    + "text-xs text-base-content/70 hover:bg-base-300/60 hover:text-base-content");
                item.add(Icon.of("chat", "w-3 h-3 opacity-50"));
                Span name = new Span(chat.getTitle() == null || chat.getTitle().isEmpty()
                    ? "New chat" : chat.getTitle());
                name.addClassName("truncate");
                item.getElement().appendChild(name.getElement());
                item.addDomEventListener("click",
                    e -> openChat(chat.getId().toString(), chat.getTitle()));
                return item;
            }));
        return wrap;
    }
}
