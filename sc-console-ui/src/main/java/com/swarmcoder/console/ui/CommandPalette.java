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

import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Js;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ⌘K command palette (design §8): one fuzzy launcher over every chat, run, sidebar view, and
 * quick action. Opened by Ctrl/⌘+K anywhere in the shell; arrow keys move, ↵ runs, Esc closes.
 * Purely a navigator over the existing signal stores — no new server surface.
 */
final class CommandPalette extends Div {

    private static final class Item {
        final String label;
        final String sublabel;
        final String icon;
        final Runnable action;

        Item(String label, String sublabel, String icon, Runnable action) {
            this.label = label;
            this.sublabel = sublabel;
            this.icon = icon;
            this.action = action;
        }
    }

    /**
     * The four stages first — they are the shell's model, and the palette should teach it, not
     * offer a second flat menu beside it. The surfaces below them are Build's faces and the
     * Requirements stage's reference pane, listed because knowing the word "Approvals" should still
     * get you there without knowing which stage it folded into.
     */
    private static final String[][] VIEWS = {
        {"setup", "Setup", "gear"},
        {"requirements", "Requirements", "file"},
        {"plan", "Plan", "list"},
        {"build", "Build", "play"},
        {"decisions", "Questions a build asked", "inbox"},
        {"guidelines", "Guidelines", "book"},
        {"insights", "Insights", "chart"},
        {"knowledge", "Knowledge", "file"},
    };

    /** Off the operator's navigation entirely until the developer toggle in Settings is on. */
    private static final String[][] DEVELOPER_VIEWS = {
        {"promptlab", "Prompt Lab", "diff"},
        {"gallery", "Components", "grip"},
    };

    private final ValueSignal<Boolean> visible = new ValueSignal<>(false);
    private final ValueSignal<String> query = new ValueSignal<>("");
    private final ValueSignal<Integer> selected = new ValueSignal<>(0);

    private final Consumer<String> openView;
    private final BiConsumer<String, String> openChat;
    private final Consumer<String> openRun;
    private final Runnable newChat;

    private final TextField input = new TextField("Search chats, runs, views, actions…");
    private List<Item> currentItems = new ArrayList<>();

    CommandPalette(Consumer<String> openView, BiConsumer<String, String> openChat,
                   Consumer<String> openRun, Runnable newChat) {
        this.openView = openView;
        this.openChat = openChat;
        this.openRun = openRun;
        this.newChat = newChat;

        addClassName("fixed inset-0 z-50 flex items-start justify-center pt-[15vh] px-4");
        getElement().setAttribute("style", "background:rgba(0,0,0,0.45)");
        getElement().addEventListener("click", e -> close());

        Div panel = new Div();
        panel.addClassName("w-[38rem] max-w-[92vw] bg-base-100 rounded-xl shadow-2xl "
            + "border border-base-300 overflow-hidden flex flex-col");
        panel.addDomEventListener("click", (EventListener<Event>) Event::stopPropagation);
        add(panel);

        Div inputRow = new Div();
        inputRow.addClassName("flex items-center gap-2 px-3 border-b border-base-300");
        inputRow.add(Icon.of("search", "w-4 h-4 opacity-40"));
        input.addClassName("!bg-transparent !border-0 focus:outline-none flex-1 py-3 text-sm");
        input.addValueChangeListener(e -> {
            query.set(input.getValue() == null ? "" : input.getValue());
            selected.set(0);
        });
        input.addDomEventListener("keydown", (EventListener<KeyboardEvent>) e -> {
            switch (Keys.of(e)) {
                case "ArrowDown" -> { e.preventDefault(); move(1); }
                case "ArrowUp" -> { e.preventDefault(); move(-1); }
                case "Enter" -> { e.preventDefault(); runSelected(); }
                case "Escape" -> { e.preventDefault(); close(); }
                default -> { }
            }
        });
        inputRow.add(input);
        panel.add(inputRow);

        Div results = new Div();
        results.addClassName("max-h-[22rem] overflow-y-auto py-1.5");
        panel.add(results);

        Div footer = new Div();
        footer.addClassName("flex items-center gap-3 px-3 py-1.5 border-t border-base-300 "
            + "text-[10px] text-base-content/40");
        footer.add(hintKey("↑↓", "navigate"), hintKey("↵", "open"), hintKey("esc", "close"));
        panel.add(footer);

        Effect.create(() -> setVisible(Boolean.TRUE.equals(visible.get())));
        Effect.create(() -> renderResults(results));
        setVisible(false);
    }

    // --- open/close ------------------------------------------------------------------------------

    void open() {
        query.set("");
        input.setValue("");
        selected.set(0);
        visible.set(true);
        Window.setTimeout(() -> input.getElement().focus(), 30);
    }

    void close() {
        visible.set(false);
    }

    void toggle() {
        if (Boolean.TRUE.equals(visible.get())) {
            close();
        } else {
            open();
        }
    }

    // --- rendering -------------------------------------------------------------------------------

    private void renderResults(Div results) {
        List<Item> items = buildItems(query.get());
        currentItems = items;
        results.removeAll();
        if (items.isEmpty()) {
            Div empty = new Div("No matches");
            empty.addClassName("px-4 py-6 text-center text-sm text-base-content/40");
            results.add(empty);
            return;
        }
        int sel = Math.max(0, Math.min(selected.get(), items.size() - 1));
        for (int i = 0; i < items.size(); i++) {
            results.add(row(items.get(i), i == sel));
        }
    }

    private Div row(Item item, boolean active) {
        Div row = new Div();
        row.addClassName("flex items-center gap-2.5 mx-1.5 px-2.5 py-2 rounded-lg cursor-pointer "
            + (active ? "bg-primary/15 text-base-content" : "hover:bg-base-200 text-base-content/80"));
        row.add(Icon.of(item.icon, "w-4 h-4 opacity-60 shrink-0"));
        Span label = new Span(item.label);
        label.addClassName("text-sm truncate flex-1");
        row.getElement().appendChild(label.getElement());
        Span sub = new Span(item.sublabel);
        sub.addClassName("text-[10px] uppercase tracking-wider text-base-content/40 shrink-0");
        row.getElement().appendChild(sub.getElement());
        row.addDomEventListener("click", (EventListener<Event>) e -> {
            e.stopPropagation();
            item.action.run();
        });
        return row;
    }

    private Div hintKey(String key, String label) {
        Div wrap = new Div();
        wrap.addClassName("flex items-center gap-1");
        Span k = new Span(key);
        k.addClassName("font-mono bg-base-300/60 rounded px-1");
        Span l = new Span(label);
        wrap.getElement().appendChild(k.getElement());
        wrap.getElement().appendChild(l.getElement());
        return wrap;
    }

    // --- items -----------------------------------------------------------------------------------

    private List<Item> buildItems(String rawQuery) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("New chat", "action", "plus", () -> {
            close();
            newChat.run();
        }));
        items.add(new Item("Toggle theme", "action", "gear", () -> {
            close();
            toggleTheme();
        }));
        for (String[] view : VIEWS) {
            String id = view[0];
            items.add(new Item(view[1], "view", view[2], () -> {
                close();
                openView.accept(id);
            }));
        }
        if (DevTools.isEnabled()) {
            for (String[] view : DEVELOPER_VIEWS) {
                String id = view[0];
                items.add(new Item(view[1], "developer", view[2], () -> {
                    close();
                    openView.accept(id);
                }));
            }
        }
        for (var chat : ChatStore.chats.get()) {
            String title = chat.getTitle() == null || chat.getTitle().isEmpty()
                ? "New chat" : chat.getTitle();
            String chatId = chat.getId().toString();
            String rawTitle = chat.getTitle();
            items.add(new Item(title, "chat", "chat", () -> {
                close();
                openChat.accept(chatId, rawTitle);
            }));
        }
        for (var run : RunsStore.runs.get()) {
            String goal = run.getGoal() == null || run.getGoal().isEmpty()
                ? run.getRunId().substring(0, 8) : run.getGoal();
            String runId = run.getRunId();
            items.add(new Item(goal, "run · " + run.getState(), "graph", () -> {
                close();
                openRun.accept(runId);
            }));
        }

        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        if (!q.isEmpty()) {
            items.removeIf(item -> !(item.label.toLowerCase().contains(q)
                || item.sublabel.toLowerCase().contains(q)));
        }
        return items;
    }

    private void move(int delta) {
        int n = currentItems.size();
        if (n == 0) {
            return;
        }
        selected.set(((selected.get() + delta) % n + n) % n);
    }

    private void runSelected() {
        int sel = selected.get();
        if (sel >= 0 && sel < currentItems.size()) {
            currentItems.get(sel).action.run();
        }
    }

    private void toggleTheme() {
        boolean dark = !"light".equals(Js.localGet("console.theme"));
        String next = dark ? "light" : "dark";
        Js.setTheme(next);
        Js.localSet("console.theme", next);
    }
}

