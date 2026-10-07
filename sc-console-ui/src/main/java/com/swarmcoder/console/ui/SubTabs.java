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
import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tabs <em>within</em> one stage. Not the shell's navigation — {@link StatusHeader} is that — but
 * the several faces a single stage genuinely has: Build is the swarm board AND the approvals queue
 * AND the guidelines workers obey, and those are one job seen from three sides, not three jobs.
 *
 * <p>Panels are built on first selection and then kept alive, hidden, for as long as the stage is:
 * the views here are signal-driven and stateful (a selected story, a scrolled transcript, a
 * half-typed guideline), and rebuilding them on every click would throw all of that away and re-run
 * each constructor's server nudge for nothing.
 *
 * <p>{@link #select} constructs panels, and those constructors talk to the server, so it must only
 * ever be called from a DOM event handler or a green thread. From a signal {@link
 * com.zeroz4j.signals.Effect} or a native JS callback a suspending call throws "Suspension point
 * reached from non-threading context".
 *
 * <p>{@link #dispose()} disposes every panel it built that is {@link Disposable}; a stage swap that
 * tears this down must call it, or effects bound to process-wide signals leak.
 */
final class SubTabs extends Div implements Disposable {

    private record Tab(String label, String icon, Supplier<Component> supplier) {}

    /** A built panel and the holder that positions it, so both can be hidden and removed. */
    private record Panel(Component view, Div holder) {}

    private final Div bar = new Div();
    private final Div body = new Div();
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private final Map<String, Panel> built = new LinkedHashMap<>();
    private final Map<String, Div> pills = new LinkedHashMap<>();
    private String active;

    SubTabs() {
        addClassName("flex flex-col flex-1 min-w-0 min-h-0");
        bar.addClassName("flex items-center gap-1 px-2 py-1 border-b border-base-300 "
            + "bg-base-100 shrink-0 overflow-x-auto");
        body.addClassName("flex-1 min-h-0 flex flex-col");
        add(bar, body);
    }

    /** Registers a permanent tab. The first one registered is the stage's default face. */
    SubTabs tab(String id, String label, String icon, Supplier<Component> supplier) {
        tabs.put(id, new Tab(label, icon, supplier));
        bar.add(pill(id, label, icon, false));
        return this;
    }

    /**
     * Registers (or re-focuses) a closable tab — an opened run. Selecting it builds it, so this has
     * the same threading rule as {@link #select}.
     */
    void open(String id, String label, String icon, Supplier<Component> supplier) {
        if (!tabs.containsKey(id)) {
            tabs.put(id, new Tab(label, icon, supplier));
            bar.add(pill(id, label, icon, true));
        }
        select(id);
    }

    /**
     * Shows a tab, building it the first time.
     *
     * <p>MUST be called from a DOM event handler or a green thread — see the class javadoc.
     */
    void select(String id) {
        Tab tab = tabs.get(id);
        if (tab == null) {
            return;
        }
        active = id;
        for (Panel panel : built.values()) {
            panel.holder().setVisible(false);
        }
        Panel panel = built.get(id);
        if (panel == null) {
            Div holder = new Div();
            holder.addClassName("flex-1 min-h-0 flex flex-col overflow-y-auto");
            Component view = tab.supplier().get();
            holder.add(view);
            panel = new Panel(view, holder);
            built.put(id, panel);
            body.add(holder);
        }
        panel.holder().setVisible(true);
        restyle();
    }

    /** Selects the default (first registered) tab if nothing is selected yet. */
    void selectDefault() {
        if (active == null && !tabs.isEmpty()) {
            select(tabs.keySet().iterator().next());
        }
    }

    @Override
    public void dispose() {
        for (Panel panel : built.values()) {
            if (panel.view() instanceof Disposable disposable) {
                disposable.dispose();
            }
        }
        built.clear();
    }

    private void close(String id) {
        Panel panel = built.remove(id);
        if (panel != null) {
            body.remove(panel.holder());
            // Release the panel's subscriptions to long-lived shared signals — a run graph left
            // bound after its tab is gone keeps redrawing DOM that is no longer in the document.
            if (panel.view() instanceof Disposable disposable) {
                disposable.dispose();
            }
        }
        Div pill = pills.remove(id);
        if (pill != null) {
            bar.remove(pill);
        }
        tabs.remove(id);
        if (id.equals(active)) {
            active = null;
            selectDefault();
        }
    }

    private Div pill(String id, String label, String icon, boolean closable) {
        Div pill = new Div();
        pill.getElement().setAttribute("data-testid", "subtab-" + id);
        pill.add(Icon.of(icon, "w-3.5 h-3.5 opacity-70"));
        Span text = new Span(label);
        pill.getElement().appendChild(text.getElement());
        if (closable) {
            Span dismiss = new Span("×");
            dismiss.addClassName("ml-1 opacity-40 hover:opacity-100 hover:text-error px-0.5");
            dismiss.addDomEventListener("click", e -> {
                e.stopPropagation();
                close(id);
            });
            pill.getElement().appendChild(dismiss.getElement());
        }
        pill.addDomEventListener("click", e -> select(id));
        pills.put(id, pill);
        style(pill, false);
        return pill;
    }

    private void restyle() {
        for (Map.Entry<String, Div> entry : pills.entrySet()) {
            style(entry.getValue(), entry.getKey().equals(active));
        }
    }

    private static void style(Div pill, boolean selected) {
        pill.setClassName("flex items-center gap-1.5 px-2.5 py-1 rounded-lg cursor-pointer "
            + "text-xs whitespace-nowrap select-none shrink-0 "
            + (selected ? "bg-base-100 border border-base-300 font-medium"
                        : "border border-transparent text-base-content/60 hover:bg-base-300/50"));
    }
}
