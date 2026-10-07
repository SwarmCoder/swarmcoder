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

import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ReadinessSignals;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Console needs before it can do anything, and what already works.
 *
 * <p>This is the counterweight to hiding unusable surfaces. Hiding alone turns "not configured yet"
 * into "the tool is broken" — the operator sees an almost-empty window and has no way to learn what
 * is missing. Everything that disappears from the shell is accounted for here, with the reason and
 * the concrete next step, so absence is explained rather than merely tidy.
 */
final class SetupView extends Div implements Disposable {

    private final List<Disposable> disposables = new ArrayList<>();
    private final Div body = new Div();
    /** Opens the guide. Set by {@link SetupStage}, which owns the dialog. */
    private Runnable openGuide = () -> { };

    SetupView() {
        addClassName("flex flex-col h-full min-h-0 overflow-y-auto");
        body.addClassName("max-w-2xl mx-auto w-full px-8 py-10 flex flex-col gap-6");
        add(body);
        disposables.add(Effect.create(this::render));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Tells this panel how to open the guide. Called once, by the stage that owns the dialog. */
    void onOpenGuide(Runnable action) {
        this.openGuide = action == null ? () -> { } : action;
    }

    private void render() {
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        // removeAll, not an empty string written into the element's HTML: since ZeroZ Stack 0.8.0
        // this is what tells everything leaving the page that it has left. Every child below is
        // added as a component for exactly that reason — a span appended straight to the element
        // is not a child as far as the framework is concerned, and removeAll would walk past it.
        body.removeAll();
        if (readiness == null) {
            return;
        }

        Div heading = new Div(readiness.hasProject()
            ? "Finish setting up " + nullSafe(readiness.projectName())
            : "Set up SwarmCoder");
        heading.addClassName("text-xl font-semibold");
        body.add(heading);

        body.add(TextStyle.SECONDARY.paragraph(readiness.hasProject()
            ? "Some surfaces are hidden because they cannot do anything yet. Here is why."
            : "SwarmCoder works on one project at a time. Everything else belongs to a project, "
                + "so nothing is available until one exists."));

        body.add(guideCard(readiness.hasProject()));

        body.add(step(1, "A project", readiness.hasProject(),
            readiness.hasProject()
                ? nullSafe(readiness.projectName()) + " is selected."
                : "Use the + at the top of the list of projects on the left. Point it at the folder "
                    + "you want SwarmCoder to work in."));

        body.add(step(2, "A git repository", readiness.hasRepository(),
            readiness.hasRepository()
                ? nullSafe(readiness.repositoryPath())
                : "The project folder has to be a git repository. "
                    + "SwarmCoder never writes into the copy you are working "
                    + "in: each worker makes a branch and a copy of its own, which is a thing only "
                    + "git can do. Writing down what has to be true, and planning the work, both "
                    + "already work without one — only building is blocked."));

        body.add(step(3, "Somewhere safe to build", readiness.canRun(),
            readiness.canRun()
                ? "Workers will build inside a container, away from the rest of this machine."
                : readiness.hasRepository()
                    ? nullSafe(readiness.runBlocker())
                    : "Available once the project has a repository."));

        if (readiness.nextStep() != null && !readiness.nextStep().isBlank()) {
            Div next = new Div();
            next.addClassName("mt-2 rounded-lg border border-primary/30 bg-primary/5 px-4 py-3 "
                + "text-sm leading-relaxed");
            Span label = new Span("Next: ");
            label.addClassName("font-semibold");
            next.add(label, new Span(readiness.nextStep()));
            body.add(next);
        }

        if (readiness.canAuthor()) {
            // Named for what each one is FOR. This sentence used to say "the project's BRD", the
            // Backlog and "run-dependent views" — one internal name, one screen that was renamed
            // and one phrase describing nothing a person could look for (UX v3 §5 rule 3).
            Div available = TextStyle.CAPTION.paragraph(
                "Working already, with no folder of code: Requirements, where you write down what "
                + "has to be true; the Pipeline, where the work waits and where you watch it; and "
                + "Knowledge, where SwarmCoder keeps what it has read. Everything to do with "
                + "building appears as soon as a build is possible.");
            available.addClassName("pt-2");
            body.add(available);
        }
    }

    /**
     * The way in for somebody who has never seen this before.
     *
     * <p>First on the panel, above the list of what is missing, because "what is this and what can
     * I do with it" comes before "what is not configured yet" for exactly one person — the one
     * this panel was hardest for. It stays after everything is configured, quieter, because
     * somebody who set the machine up in March still has to be able to find the explanation in
     * June.
     */
    private Div guideCard(boolean configured) {
        Div card = new Div();
        card.addClassName("rounded-lg border px-4 py-3 flex items-start gap-3 cursor-pointer "
            + (configured
                ? "border-base-300 hover:bg-base-200/60"
                : "border-primary/40 bg-primary/5 hover:bg-primary/10"));

        Div mark = new Div();
        mark.addClassName("shrink-0 mt-0.5 text-primary");
        mark.add(Icon.of("book", "w-4 h-4"));
        card.add(mark);

        Div text = new Div();
        text.addClassName("flex flex-col gap-1 min-w-0");
        Div title = new Div(configured
            ? "How SwarmCoder works"
            : "New here? Start with this.");
        title.addClassName("text-sm font-medium");
        text.add(title);
        text.add(TextStyle.CAPTION.paragraph(
            "What SwarmCoder does, the four words every screen uses, and the three places you can "
            + "start from. Four screens, about three minutes. It changes nothing."));
        card.add(text);

        card.addDomEventListener("click", e -> openGuide.run());
        return card;
    }

    /** One prerequisite: met or not, with the reason when it is not. */
    private static Div step(int number, String title, boolean met, String detail) {
        Div row = new Div();
        row.addClassName("flex items-start gap-3 rounded-lg border border-base-300 px-4 py-3");

        Div marker = new Div();
        marker.addClassName("shrink-0 mt-0.5 " + (met ? "text-success" : "text-base-content/30"));
        marker.add(Icon.of(met ? "check" : "dot", "w-4 h-4"));
        row.add(marker);

        Div text = new Div();
        text.addClassName("flex flex-col gap-1 min-w-0");
        Div heading = new Div(number + ". " + title);
        heading.addClassName("text-sm font-medium");
        text.add(heading);
        Div body = TextStyle.CAPTION.paragraph(detail == null ? "" : detail);
        body.addClassName("break-words");
        text.add(body);
        row.add(text);
        return row;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
