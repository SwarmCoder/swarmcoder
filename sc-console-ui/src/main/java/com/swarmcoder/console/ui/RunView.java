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
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * A run's tab: a segmented toggle over the two live views of the same run — the visual run
 * graph and the requirements/task-list plan. Both mount at once (so the graph's push
 * subscription stays warm) and share the {@code run-graph} signal via {@link GraphStore};
 * the toggle just swaps which one is visible.
 *
 * <p>{@link Disposable} because {@code SubTabs.close} has always asked its panel to release itself
 * and this class never answered: closing a run tab left both inner views bound to the process-wide
 * run-graph signal, redrawing DOM that had been removed from the document. The two views own the
 * bindings that matter and are disposed here with this view's own toggle effects.
 */
final class RunView extends Div implements Disposable {

    private final ValueSignal<String> tab = new ValueSignal<>("graph");
    private final List<Disposable> disposables = new ArrayList<>();
    private final RunGraphView graphView;
    private final PlanView planView;

    RunView(String runId) {
        addClassName("flex flex-col flex-1 min-h-0");

        graphView = new RunGraphView(runId);
        planView = new PlanView(runId);

        Div toolbar = new Div();
        toolbar.addClassName("flex items-center gap-2 px-3 py-1.5 border-b border-base-300 shrink-0");
        Div segment = new Div();
        segment.addClassName("inline-flex rounded-lg bg-base-200 p-0.5 gap-0.5");
        segment.add(segButton("graph", "Graph"));
        segment.add(segButton("plan", "Requirements"));
        toolbar.add(segment);
        add(toolbar);

        Div stack = new Div();
        stack.addClassName("flex-1 min-h-0 flex flex-col");
        stack.add(graphView, planView);
        add(stack);

        disposables.add(Effect.create(() -> {
            boolean graph = "graph".equals(tab.get());
            graphView.setVisible(graph);
            planView.setVisible(!graph);
        }));
    }

    @Override
    public void dispose() {
        graphView.dispose();
        planView.dispose();
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    private Div segButton(String id, String label) {
        Div button = new Div(label);
        button.addDomEventListener("click", e -> tab.set(id));
        disposables.add(Effect.create(() -> {
            boolean active = id.equals(tab.get());
            button.setClassName("px-3 py-1 rounded-md text-xs font-semibold cursor-pointer "
                + "transition-colors " + (active
                    ? "bg-base-100 text-base-content shadow-sm"
                    : "text-base-content/50 hover:text-base-content"));
        }));
        return button;
    }
}
