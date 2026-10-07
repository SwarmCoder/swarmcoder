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

import com.swarmcoder.console.api.AutonomousSignals;
import com.swarmcoder.console.api.AutonomousStatus;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.swarmcoder.console.api.ReadinessSignals;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip that says a machine is deciding things for you — across the top of every screen, in the
 * loudest colour the theme has, while it is true and never otherwise.
 *
 * <h2>What it replaces, and why the shape changed twice</h2>
 *
 * <p>Autonomous running was armed in a modal window and could only be watched in that same modal
 * window. The operator's two reports, an hour apart:
 *
 * <blockquote>"I would prefer a mode where I can see what is going on and can still use the UI to
 * open or view things... Hiding everything behind one dialog with little feedback doesn't give me a
 * good feeling."</blockquote>
 *
 * <blockquote>"It is inconvenient to have this dialog box open as it covers underlying UI. It cannot
 * be moved and can only be closed and then how is it ever accessed again? It probably needs to be
 * minimised. And then there must be some strong visual indicator that the process is running
 * autonomously — something bold."</blockquote>
 *
 * <p>He asked for a minimise control. He is not given one, and that is deliberate: minimising is a
 * cure for something that covers the application, and this covers nothing. It is a row of the shell,
 * not an overlay — the workspace begins below it and every pixel of the workspace is still there,
 * still clickable, at every window width. A collapse control on a 36-pixel strip would be a second
 * thing to learn for no gain, and a strip that can be collapsed to nothing is a strip that can be
 * collapsed and forgotten, which is the whole fault being fixed.
 *
 * <p><b>Bold on purpose.</b> Solid {@code warning}, not a tint. Every other permanent element in
 * the Console is deliberately quiet — the guidance line has no error colour, the tab counts are
 * neutral rather than red, and both of those are right, because they are information. This is not
 * information. Something is choosing what the product does, in the operator's name, while he is
 * doing something else. Warning is also the colour this Console already uses for "the machine made
 * this up", which is exactly what is at stake behind this strip.
 *
 * <h2>What is on it</h2>
 *
 * <ol>
 *   <li><b>That it is on</b> — a pulsing mark and four words in capitals.</li>
 *   <li><b>The whole sentence</b>, verbatim from the server: what it is doing, how long it has been
 *       at it, what it has spent, and when it stops of its own accord. This is the line that used to
 *       be inside the window, and the operator said it was the good part; it is only moved.</li>
 *   <li><b>The way back to the record</b> — what it decided, and which of those it invented. That is
 *       a list and reading it is a sitting-down job, so it stays in {@link AutonomousDialog}. The
 *       button is on this strip, which is on every screen, which is the answer to "how is it ever
 *       accessed again".</li>
 *   <li><b>Stop</b>, in one click, from wherever the operator happens to be standing.</li>
 * </ol>
 *
 * <p>There is deliberately NO count of decisions on the strip. The window that holds the list is the
 * one thing that can count it, and a number here would be a second derivation of it — and a number
 * nobody can act on from a header anyway.
 *
 * <p><b>The one thing it will not do is lie.</b> A session is bound to the project it was started
 * on: switch to another project and the pilot goes quiet, because it will not touch a project the
 * operator did not point it at. A strip that went on saying "building on its own" over a project
 * where nothing is happening would be worse than no strip, so it names the project it is driving
 * whenever that is not the one on screen.
 *
 * <p>Everything drawn here comes from {@link AutonomousSignals}, which the server publishes when the
 * switch moves and at the end of every tick the pilot spends on it. Nothing here polls.
 */
final class AutonomousBanner extends Div implements Disposable {

    private final ControlService control = new ControlService_Stub();

    /** The strip. Hidden - not emptied - whenever nothing is running. */
    private final Div strip = new Div();
    private final Span sentence = new Span("");
    private final Div elsewhere = new Div();
    /** The record, opened from here. A {@code display: contents} wrapper, so holding it is free. */
    private final AutonomousDialog record = new AutonomousDialog();

    private final List<Disposable> disposables = new ArrayList<>();

    AutonomousBanner() {
        // The wrapper paints nothing and lays nothing out, so the strip is a row of the header as
        // though it were the header's own child. The window has to hang off something that is never
        // hidden: a native dialog inside a display:none ancestor cannot be opened at all.
        addClassName("contents");

        strip.addClassName("flex items-center gap-3 px-4 py-2 shrink-0 flex-wrap "
            + "bg-warning text-warning-content");
        strip.getElement().setAttribute("data-testid", "autonomous-banner");
        strip.setVisible(false);

        Div live = new Div();
        live.addClassName("w-2.5 h-2.5 rounded-full bg-warning-content animate-pulse shrink-0");
        strip.add(live);

        Span label = new Span("BUILDING ON ITS OWN");
        label.addClassName("text-xs font-black tracking-wider whitespace-nowrap shrink-0");
        strip.getElement().appendChild(label.getElement());

        // The operator's own words for the good half of the window it replaces: what it is doing,
        // how long, what it has cost, when it stops. Written once, on the server.
        sentence.addClassName("text-xs leading-relaxed min-w-0 flex-1");
        sentence.getElement().setAttribute("data-testid", "autonomous-banner-status");
        strip.getElement().appendChild(sentence.getElement());

        elsewhere.addClassName("text-xs font-semibold rounded px-2 py-0.5 shrink-0 "
            + "bg-warning-content/15");
        elsewhere.getElement().setAttribute("data-testid", "autonomous-banner-elsewhere");
        elsewhere.setVisible(false);
        strip.add(elsewhere);

        strip.add(action("What it decided", "autonomous-banner-record",
            "Everything it has chosen for you, with the ones it made up marked.",
            record::open));
        strip.add(action("Stop", "autonomous-banner-stop",
            "Stop deciding things for me. Anything already building finishes; nothing new starts.",
            this::stop));

        add(strip, record);

        disposables.add(Effect.create(() -> {
            AutonomousStatus status = AutonomousSignals.CURRENT.get();
            ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
            boolean on = status != null && status.running();
            strip.setVisible(on);
            sentence.setText(status == null ? "" : status.line());

            String driving = status == null ? "" : status.project();
            String open = readiness == null || readiness.projectName() == null
                ? "" : readiness.projectName();
            boolean somewhereElse = on && !driving.isEmpty() && !driving.equals(open);
            elsewhere.setVisible(somewhereElse);
            elsewhere.setText(somewhereElse
                ? "It is working on " + driving + ", not on what you have open" : "");
        }));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
        record.dispose();
    }

    /**
     * One control on the strip: outlined in the strip's own ink rather than given a button colour,
     * because a coloured button on a coloured bar is two claims about importance in one place.
     */
    private static Div action(String text, String testId, String tooltip, Runnable go) {
        Div button = new Div(text);
        button.addClassName("shrink-0 text-xs font-bold px-2 py-0.5 rounded cursor-pointer "
            + "border border-warning-content/50 hover:bg-warning-content/15");
        button.getElement().setAttribute("data-testid", testId);
        button.getElement().setAttribute("title", tooltip);
        button.addDomEventListener("click", e -> go.run());
        return button;
    }

    /**
     * Stops it, from the strip, without opening anything.
     *
     * <p>On a green thread because a suspending RMI call made from a DOM handler's own frame throws
     * "Suspension point reached from non-threading context" - the same rule every other call in this
     * client obeys. Nothing is drawn from the result: the server publishes the switch going off, and
     * this strip renders from that publish like every other reader.
     */
    private void stop() {
        new Thread(() -> {
            try {
                control.stopAutonomousBuild();
            } catch (Exception e) {
                ClientLog.error("AutonomousBanner", "could not stop autonomous running: " + e);
                MainView.statusMessage.set("Could not reach the server, so autonomous running may "
                    + "still be on.");
            }
        }).start();
    }
}
