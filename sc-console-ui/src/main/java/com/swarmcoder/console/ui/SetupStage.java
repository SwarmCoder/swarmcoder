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
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

/**
 * Stage 1 — the project, its folder, the models and the sandbox.
 *
 * <p>{@link SetupView} already explains what is missing and why; what it could not do was let the
 * operator act on it. The two things it names — "point the project at a git repository", "the models
 * come from config" — both live in dialogs owned by the project rail, reachable only by finding an
 * unlabelled gear. So the stage adds the two ways in, labelled, next to the explanation that sends
 * you looking for them.
 *
 * <p>The project <em>rail</em> deliberately stays outside this stage, in the shell. Which project you
 * are in is not a step in the process — every stage is scoped to it, so it has to be visible and
 * switchable from all four. Setup is where you configure the project you are already in.
 */
final class SetupStage extends Div implements Disposable {

    private final SetupView setup = new SetupView();

    SetupStage() {
        addClassName("flex flex-col h-full min-h-0");
        add(actions());
        Div scroll = new Div();
        scroll.addClassName("flex-1 min-h-0 overflow-y-auto flex flex-col");
        // The panel's own "New here?" card opens the ONE guide, which the workspace host owns.
        // It used to build a second copy of its own, and a guide that forgets which step you had
        // reached the moment you change screens is not a walk through anything.
        setup.onOpenGuide(() -> Nav.openGuide.run());
        scroll.add(setup);
        add(scroll);
    }

    @Override
    public void dispose() {
        setup.dispose();
    }

    private Div actions() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 shrink-0");
        Span title = new Span("Setup");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        bar.add(button("book", "Getting started", () -> Nav.openGuide.run()));
        bar.add(button("folder", "Project settings", () -> Nav.openProjectSettings.run()));
        bar.add(button("gear", "Models & budgets", () -> Nav.openGlobalSettings.run()));
        return bar;
    }

    private Div button(String icon, String label, Runnable action) {
        Div control = new Div();
        control.addClassName("flex items-center gap-1.5 text-xs px-2 py-1 rounded border "
            + "border-base-300 cursor-pointer hover:bg-base-300/60");
        control.add(Icon.of(icon, "w-3.5 h-3.5 opacity-60"));
        Span text = new Span(label);
        control.getElement().appendChild(text.getElement());
        // A DOM handler, so the dialogs' RMI loads have a threading context to suspend in.
        control.addDomEventListener("click", e -> action.run());
        return control;
    }
}
