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
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.ui.theme.TextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The one window for "build all of this without asking me": what it will decide before it is
 * pressed, what it is doing while it runs, what it decided afterwards, and the button that stops it.
 *
 * <h2>Why all four are in one place</h2>
 *
 * <p>They are the same question asked at four moments. Before: what am I handing over? During: what
 * is it doing and how do I stop it? After: what did it decide while I was asleep? Splitting those
 * across three screens is how a person ends up switching something on without reading what it does,
 * and then never finding the record of what it did.
 *
 * <p><b>The arming screen is not a warning banner.</b> It lists the seven things the operator
 * normally decides and says, for each, what happens instead. The one that matters is the third: a
 * question the analyst asks exists because the document did not say, so an answer to it is a
 * requirement being invented. That sentence is on screen before the button is reachable, in the
 * ordinary words the rest of the Console uses.
 *
 * <p><b>The decisions list separates invented from read.</b> Everything the machine chose is here,
 * but the ones where the documents were silent are marked, because those are the ones that are now
 * requirements nobody wrote. Reading past a list of forty entries looking for the four that matter
 * is not a review, so the four are marked and counted at the top.
 *
 * <p><b>It is no longer where anybody watches from.</b> The operator's report was that this window
 * covers the application, cannot be moved, and once closed leaves no obvious way back. So the
 * watching moved out of it onto {@link AutonomousBanner} — a loud strip across the top of every
 * screen that covers nothing and carries the way back to this window. What is left here is what
 * genuinely needs a window: the seven handovers, read before the button is pressed, and the diary of
 * what was decided, read afterwards. Closing it has never stopped anything, and now it does not hide
 * anything either.
 *
 * <p><b>No timer.</b> The status used to be re-asked every four seconds while this was open, which
 * is far too often for a machine that moves once every thirty seconds and not at all once the window
 * is shut. It reads {@link AutonomousSignals} instead - the server publishes when the switch moves
 * and at the end of every tick - and re-reads the diary when that arrives, because a published step
 * is precisely when there is a new line in it.
 *
 * <p>A {@link Div} holding its dialog rather than being one, for the reason {@link OnboardingWizard}
 * gives: the wrapper is {@code display: contents}, so mounting it costs nothing.
 */
final class AutonomousDialog extends Div implements Disposable {

    private final ControlService control = new ControlService_Stub();

    private final Dialog dialog = new Dialog();
    private final Div body = new Div();
    private final Div footer = new Div();
    private final Div noticeLine = new Div();

    private final ValueSignal<List<AutonomousDecision>> decisions =
        new ValueSignal<>(new ArrayList<>());
    private final ValueSignal<String> notice = new ValueSignal<>("");

    private final List<Disposable> disposables = new ArrayList<>();
    private boolean open;
    private boolean disposed;
    private Runnable closed;

    AutonomousDialog() {
        addClassName("contents");
        dialog.setWidth("44rem");
        dialog.setAriaLabel("Building without being asked");
        dialog.getElement().setAttribute("data-testid", "autonomous-dialog");
        body.addClassName("flex flex-col gap-3 min-h-0 max-h-[58vh] overflow-y-auto pr-1 "
            + "[&>*]:shrink-0");
        footer.addClassName("flex items-center gap-2 mt-5");
        noticeLine.addClassName("mt-2");
        TextStyle.CAPTION.applyTo(noticeLine);
        dialog.add(header(), body, noticeLine, footer);
        add(dialog);

        disposables.add(Effect.create(this::render));
        // A published step is exactly when there is a new line in the diary. Nothing is re-read
        // while this window is shut, because nothing in it is on the screen while it is shut.
        disposables.add(Effect.create(() -> {
            AutonomousSignals.CURRENT.get();
            if (open) {
                refreshDecisions();
            }
        }));
        disposables.add(Effect.create(() -> {
            String text = notice.get();
            noticeLine.setText(text == null ? "" : text);
            noticeLine.setVisible(text != null && !text.isEmpty());
        }));
        dialog.addCloseListener(e -> {
            open = false;
            if (closed != null) {
                closed.run();
            }
        });
    }

    /**
     * What to do when this window is dismissed - by its button, by Escape, or by a click outside.
     *
     * <p>The guide hands over to this one by CLOSING itself, because two modals at once are drawn
     * in the browser's top layer and the second comes out behind the first. So it needs telling
     * when to come back, exactly as the analysis and planning windows do.
     */
    void onClosed(Runnable action) {
        this.closed = action;
    }

    @Override
    public void dispose() {
        disposed = true;
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    /** Opens it, on whichever of its two faces the server says is current. */
    void open() {
        open = true;
        notice.set("");
        refreshDecisions();
        dialog.open();
    }

    // --- server reads ---------------------------------------------------------------------------

    /**
     * Reloads the diary on a green thread.
     *
     * <p>Whether it is running and what it is doing are NOT read here: they arrive on
     * {@link AutonomousSignals}, so there is one answer to that question and one transport for it.
     * The diary is a list rather than a fact, so it is still fetched - but only while this window is
     * open, and only when the server has just said that something moved.
     *
     * <p>On a green thread because every caller is a non-threading context - a click handler is one,
     * a signal effect is not - and a suspending RMI call made from an effect throws "Suspension
     * point reached from non-threading context". One path for both, so neither can be forgotten.
     */
    private void refreshDecisions() {
        new Thread(() -> {
            if (disposed) {
                return;
            }
            try {
                decisions.set(new ArrayList<>(control.autonomousDecisions()));
            } catch (Exception e) {
                ClientLog.warn("AutonomousDialog", "could not read what it has decided: " + e);
                notice.set("Could not reach the server. What is shown here may be out of date.");
            }
        }).start();
    }

    // --- chrome -----------------------------------------------------------------------------------

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-start gap-3 mb-3");
        Div titles = new Div();
        titles.addClassName("flex-1 min-w-0");
        Div title = new Div("Build all of this without asking me");
        title.addClassName("text-lg font-bold");
        Div subtitle = TextStyle.CAPTION.paragraph(
            "SwarmCoder reads what you have written down, decides everything it would normally "
            + "ask you, and builds it. You can stop it at any time, and everything it decided is "
            + "written down here.");
        subtitle.addClassName("mt-0.5");
        titles.add(title, subtitle);
        Button close = new Button(Icon.of("x", "w-4 h-4"), "Close");
        close.addClassName("btn-ghost btn-xs");
        close.addClickListener(e -> dialog.close());
        bar.add(titles, close);
        return bar;
    }

    private void render() {
        AutonomousStatus status = AutonomousSignals.CURRENT.get();
        boolean live = status != null && status.running();
        List<AutonomousDecision> made = decisions.get();
        body.removeAll();
        footer.removeAll();
        if (live) {
            renderRunning(status.line());
        } else {
            renderArming(made);
        }
        if (made != null && !made.isEmpty()) {
            body.add(decisionList(made));
        }
    }

    // --- before it is pressed -----------------------------------------------------------------

    private void renderArming(List<AutonomousDecision> made) {
        body.add(lead("This hands seven decisions to the machine."));
        body.add(prose(
            "Normally SwarmCoder stops and asks you at each of these. If you switch this on, it "
            + "answers them itself and keeps going. Read them before you press the button - the "
            + "third one is the one that costs money if it is wrong."));

        body.add(handover("It reads your documents",
            "Nothing is decided here. It would have done this anyway when you pressed \"read it\"."));
        body.add(handover("It answers its own questions about them",
            "This is the one to look at. It only asks a question when your document did NOT say. "
            + "So it is not looking the answer up - it is choosing, for you, what the thing you "
            + "are building does. Every answer it invents is written down and marked, and you can "
            + "read the list on this screen afterwards.", true));
        body.add(handover("It agrees the requirements as the real scope",
            "This is the moment something stops being a draft and becomes what the work must "
            + "deliver. It will not agree anything that has no test that could prove it - that "
            + "rule still holds, and anything it refuses is named in the list."));
        body.add(handover("It accepts the slices of work it planned",
            "Which piece of work comes first, and what each one covers. Nobody reads them."));
        body.add(handover("It starts building them, one at a time",
            "Same as leaving unattended running switched on overnight."));
        body.add(handover("It accepts a finished piece of work",
            "Only when every check that piece promised actually ran and passed. Anything less is "
            + "left on the board for you, with the reason on the card."));
        body.add(handover("It does NOT answer a build that got stuck",
            "When a build stops and asks something, that means the work could not be done. There "
            + "is no answer to that, so it does not invent one: it writes down which build "
            + "stopped, leaves it, and carries on with everything that does not depend on it."));

        body.add(prose(
            "It stops on its own when there is nothing left to do, when it has been running for as "
            + "long as your settings allow a single run, or when it reaches the spending limit in "
            + "your settings. Closing SwarmCoder also stops it, and it does not start itself again."));

        if (made != null && !made.isEmpty()) {
            body.add(prose("Below is everything it has decided for you on this project so far."));
        }

        footer.add(spacer());
        Button go = new Button("Start building without asking me");
        go.addClassName("btn-sm btn-primary");
        go.getElement().setAttribute("data-testid", "autonomous-start");
        go.addClickListener(e -> start());
        footer.add(go);
    }

    /** One of the seven, with the {@code weight} flag on the one that invents requirements. */
    private Div handover(String what, String instead) {
        return handover(what, instead, false);
    }

    private Div handover(String what, String instead, boolean weighty) {
        Div row = new Div();
        row.addClassName("rounded-lg border px-3 py-2 " + (weighty
            ? "border-warning/50 bg-warning/10" : "border-base-300"));
        Div name = new Div(what);
        name.addClassName("text-sm font-medium");
        row.add(name);
        Div text = new Div(instead);
        text.addClassName("text-xs leading-relaxed text-base-content/70 mt-0.5");
        row.add(text);
        return row;
    }

    // --- while it runs ---------------------------------------------------------------------------

    private void renderRunning(String sentence) {
        body.add(lead("It is building on its own."));
        Div line = new Div(sentence == null ? "" : sentence);
        line.addClassName("rounded-lg border border-primary/40 bg-primary/10 px-3 py-2 text-sm "
            + "leading-relaxed");
        line.getElement().setAttribute("data-testid", "autonomous-status");
        body.add(line);
        body.add(prose(
            "Close this and carry on working. The yellow strip across the top of every screen "
            + "keeps saying what it is doing, brings you back here, and stops it in one click."));
        body.add(prose(
            "Stopping it leaves anything that is already building to finish - throwing away work "
            + "that is minutes from being proved would help nobody. Nothing new is started and "
            + "nothing further is decided for you."));

        footer.add(spacer());
        Button stop = new Button("Stop deciding things for me");
        stop.addClassName("btn-sm btn-error");
        stop.getElement().setAttribute("data-testid", "autonomous-stop");
        stop.addClickListener(e -> stop());
        footer.add(stop);
    }

    // --- the record ----------------------------------------------------------------------------

    private Div decisionList(List<AutonomousDecision> made) {
        Div box = new Div();
        box.addClassName("flex flex-col gap-1.5 mt-2");
        box.getElement().setAttribute("data-testid", "autonomous-decisions");

        int invented = 0;
        for (AutonomousDecision decision : made) {
            if (decision.kind() == AutonomousDecisionKind.ANSWERED_QUESTION
                    && !decision.grounded()) {
                invented++;
            }
        }
        Div heading = new Div(invented == 0
            ? made.size() + " decision" + (made.size() == 1 ? "" : "s") + " taken for you. None of "
                + "them invented anything your documents did not say."
            : made.size() + " decision" + (made.size() == 1 ? "" : "s") + " taken for you, and "
                + invented + " of them made something up that your documents did not say. Those "
                + (invented == 1 ? "is" : "are") + " marked below.");
        heading.addClassName("text-sm font-medium");
        box.add(heading);

        for (int i = made.size() - 1; i >= 0; i--) {
            box.add(decisionRow(made.get(i)));
        }
        return box;
    }

    private Div decisionRow(AutonomousDecision decision) {
        boolean invented = decision.kind() == AutonomousDecisionKind.ANSWERED_QUESTION
            && !decision.grounded();
        Div row = new Div();
        row.addClassName("rounded-lg border px-3 py-2 " + (invented
            ? "border-warning/50 bg-warning/10" : "border-base-300"));

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        Span badge = new Span(decision.headline());
        badge.addClassName("text-[10px] uppercase tracking-wide font-semibold "
            + (invented ? "text-warning" : "text-base-content/50"));
        top.getElement().appendChild(badge.getElement());
        row.add(top);

        Div subject = new Div(decision.subject() == null ? "" : decision.subject());
        subject.addClassName("text-sm font-medium mt-0.5");
        row.add(subject);

        if (decision.question() != null && !decision.question().isBlank()) {
            Div asked = new Div("It was asked: " + decision.question());
            asked.addClassName("text-xs text-base-content/60 mt-1 leading-relaxed");
            row.add(asked);
        }
        if (decision.answer() != null && !decision.answer().isBlank()) {
            Div answer = new Div("It decided: " + decision.answer());
            answer.addClassName("text-xs mt-1 leading-relaxed");
            row.add(answer);
        }
        if (decision.reasoning() != null && !decision.reasoning().isBlank()) {
            Div why = new Div(decision.reasoning());
            why.addClassName("text-xs text-base-content/60 mt-1 leading-relaxed");
            row.add(why);
        }
        return row;
    }

    // --- actions ---------------------------------------------------------------------------------

    private void start() {
        notice.set("Starting...");
        new Thread(() -> {
            try {
                String refused = control.startAutonomousBuild();
                if (refused != null && refused.startsWith("error:")) {
                    notice.set(refused.substring("error:".length()).strip());
                } else {
                    notice.set("");
                }
            } catch (Exception e) {
                ClientLog.error("AutonomousDialog", "could not start autonomous running: " + e);
                notice.set("Could not reach the server, so nothing was started.");
            }
            // Nothing is re-read here. The server publishes the switch moving, and this window
            // renders from that publish exactly as the header's mark does.
        }).start();
    }

    private void stop() {
        notice.set("Stopping...");
        new Thread(() -> {
            try {
                control.stopAutonomousBuild();
                notice.set("Stopped. Nothing further will be decided for you.");
            } catch (Exception e) {
                ClientLog.error("AutonomousDialog", "could not stop autonomous running: " + e);
                notice.set("Could not reach the server, so it may still be running.");
            }
        }).start();
    }

    // --- small helpers ---------------------------------------------------------------------------

    private static Div lead(String text) {
        Div div = new Div(text);
        div.addClassName("text-base font-semibold");
        return div;
    }

    private static Div prose(String text) {
        Div div = new Div(text);
        div.addClassName("text-sm leading-relaxed text-base-content/80");
        return div;
    }

    private static Div spacer() {
        Div div = new Div();
        div.addClassName("flex-1");
        return div;
    }
}
