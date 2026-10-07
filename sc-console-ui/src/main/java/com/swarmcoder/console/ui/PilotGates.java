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
import com.swarmcoder.console.api.ReadinessSignals;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.layout.Div;

/**
 * The six things the machine does for the operator while it is building on its own, and the one
 * sentence every control that does one of them says instead.
 *
 * <h2>Why anything is taken away at all</h2>
 *
 * <p>The operator asked for it, and then narrowed it himself: <i>"I meant disabling the features
 * that would interfere or are equivalent to what the auto mode is doing!"</i> Not the application —
 * the gates. He also said, in the same conversation, that the whole point of the mode is to watch
 * the work and still open things, so <b>nothing that only LOOKS is touched</b>: the requirements
 * workspace, the board, the run graph, the workers, the chats, the decision record and every editor
 * stay exactly as they are.
 *
 * <p>What is taken away is the set of buttons that press the same lever the pilot has its hand on.
 * Pressing one is at best a duplicate — the pilot would have done it a few seconds later — and at
 * worst a collision, two actors submitting answers to the same round of questions.
 *
 * <h2>The six, and nothing beyond them</h2>
 *
 * <ol>
 *   <li>Agreeing a requirement (its own form, and the guide's copy of the same act).</li>
 *   <li>Answering the analyst's questions about the documents, and applying what it drafted.</li>
 *   <li>Accepting a planner suggestion as real work.</li>
 *   <li>Answering the planner's questions, and adding its proposals to the backlog.</li>
 *   <li>Starting a build.</li>
 *   <li>(There is no sixth control: marking a story ready and accepting a suggestion are the same
 *       button, which is what the pipeline board has always offered.)</li>
 * </ol>
 *
 * <p><b>Deliberately still enabled</b>, and each for a reason that would be a defect if it were
 * not: judging what a build handed back ("Accept delivery", "Send it back"), because the arming
 * screen PROMISES that anything the machine could not prove is left on the board for the operator —
 * disabling that would break the feature's own contract; building a stopped story again, because
 * the pilot never retries and says so; and every kind of editing, because editing is not a gate.
 *
 * <h2>Only for the project it is actually driving</h2>
 *
 * <p>A session is bound to the project it was started on and will not touch another. So a control
 * is only the pilot's while the operator is looking at THAT project — otherwise switching project
 * would grey out a project nothing is happening in.
 *
 * <p>Matched on the project's NAME, because that is the only identifier both facts carry: the
 * status says which project it drives, readiness says which one is open. Two projects with the same
 * name would be indistinguishable here; that is a known and accepted limit, and the failure mode is
 * a disabled button with a sentence explaining itself, not a wrong action.
 *
 * <h2>It comes back the moment he takes it back</h2>
 *
 * <p>There is no stored "disabled" state anywhere. Every caller reads {@link #handedOver()} inside
 * the render that draws the control, so the answer is recomputed from the signal the server
 * publishes — the same publish that removes the strip from the top of the screen also redraws every
 * one of these. Stopping is one click and the application is his again in the same frame.
 */
final class PilotGates {

    /**
     * What a control says instead of doing its job. One sentence, in the words of the mode that
     * took it, and it names the way to get it back rather than leaving the operator to find one.
     */
    static final String WHY = "SwarmCoder is doing this for you while it is building on its own. "
        + "Stop it on the yellow strip at the top and this comes back.";

    private PilotGates() {}

    /**
     * True while the pilot owns the gates of the project the operator is looking at.
     *
     * <p>Callers must read this INSIDE the render that draws the control, never once at
     * construction: that is what makes the control come back by itself when the switch goes off.
     */
    static boolean handedOver() {
        AutonomousStatus status = AutonomousSignals.CURRENT.get();
        if (status == null || !status.running()) {
            return false;
        }
        ConsoleReadiness readiness = ReadinessSignals.CURRENT.get();
        String open = readiness == null || readiness.projectName() == null
            ? "" : readiness.projectName();
        // An unnamed project on either side cannot be told apart from the one being driven, and the
        // safe answer there is "this is it": a gate wrongly left open lets two actors press the
        // same lever, and a gate wrongly closed only asks the operator to stop the pilot first.
        return status.project().isEmpty() || open.isEmpty() || status.project().equals(open);
    }

    /** {@link #WHY} while the pilot owns this gate, or null while the operator does. */
    static String why() {
        return handedOver() ? WHY : null;
    }

    /** Greys a button out and puts the reason behind the mouse. No-op while nothing is running. */
    static void hold(Button button) {
        if (handedOver()) {
            button.setEnabled(false);
            button.getElement().setAttribute("title", WHY);
        }
    }

    /**
     * The reason, in words, under the control it is about.
     *
     * <p>Greying something out without saying why is the same anxiety in a different costume, and
     * this Console's own rule is that every gate carries its own exit. So the sentence is on the
     * screen, not only in a tooltip, wherever there is room for it.
     */
    static Div note() {
        Div line = new Div(WHY);
        line.addClassName("text-[11px] leading-snug text-warning mt-1");
        line.getElement().setAttribute("data-testid", "pilot-holds-this");
        return line;
    }
}
