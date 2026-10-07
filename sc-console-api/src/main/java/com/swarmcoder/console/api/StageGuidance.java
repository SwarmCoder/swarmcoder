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
package com.swarmcoder.console.api;

import com.zeroz4j.api.DataModel;

import java.util.Objects;

/**
 * What ONE stage of the process has to say for itself, judged from that stage's own facts.
 *
 * <p>It exists because {@link ConsoleReadiness#nextAction()} is a single global answer computed in a
 * fixed dependency order — empty BRD, then drafts, then no stories, then no runs — and the first
 * match wins. That is a waterfall assumption inside a process that is genuinely concurrent:
 * requirements get refined while earlier ones are already being built. A project with nineteen
 * requirements, four of them still drafts, six proposed stories and a run in flight was told,
 * permanently, to go back to Requirements — while the operator stood in Plan with nothing on the
 * screen telling them what to do there. Pointing backwards is worse than silence, because it reads
 * as "you cannot proceed".
 *
 * <p>So each stage answers for itself. {@link ConsoleReadiness#nextAction()} survives unchanged as
 * the GLOBAL recommendation — it is what decides which stage the shell lands on — and these carry
 * what each stage would say if you were standing in it.
 *
 * <h2>The three shapes a stage's line can take</h2>
 *
 * <ol>
 *   <li><b>Its own step.</b> {@link #action()} is not {@link NextAction#NONE} and {@link #text()}
 *       says what to do and why. The bar draws a button that performs it, or names where it is
 *       done.</li>
 *   <li><b>Waiting.</b> {@link #action()} is {@code NONE} and {@link #waitingOn()} names the stage
 *       the work has to come from. That is deliberately a DIFFERENT sentence from "your next step is
 *       elsewhere": it says what this stage is waiting for, which is information about where you are
 *       standing, not an instruction to leave.</li>
 *   <li><b>Nothing.</b> {@link #text()} is absent and the bar renders nothing at all. Guidance that
 *       cannot fall silent is nagging, not help.</li>
 * </ol>
 *
 * <p>{@link #attention()} is separate from all three: it is how many things in THIS stage are waiting
 * on the operator, and it is what lets the stage bar say "4 items need you" on one stage while a
 * later one also has work. Linear done → next → ahead cannot describe that, and a stage with
 * outstanding items must never read as finished.
 *
 * <p>The stage is identified by the same kebab-case id the client's {@code Stage} enum uses, rather
 * than by a shared enum: {@link NextAction} is append-only precisely because a wire enum is
 * expensive to get wrong, and there is no reason to mint a second one for four stable strings.
 */
@DataModel
public class StageGuidance {

    /** Setup — the project, its folder, the models and the sandbox. */
    public static final String SETUP = "setup";
    /** Requirements — capturing and agreeing what the system must do. */
    public static final String REQUIREMENTS = "requirements";
    /** Plan — turning agreed scope into stories that can be picked up. */
    public static final String PLAN = "plan";
    /** Build — where the work actually runs. */
    public static final String BUILD = "build";

    /** Which stage this speaks for: one of the constants above. */
    private String stage;
    /** This stage's own step, or {@link NextAction#NONE} when it has none. Null on older values. */
    private NextAction action;
    /** The sentence this stage's bar shows. Null or blank means the bar renders nothing. */
    private String text;
    /** How many things in THIS stage are waiting on the operator. */
    private int attention;
    /** The stage id whose work has to arrive first, when this one can do nothing. Nullable. */
    private String waitingOn;

    public StageGuidance() {}

    public StageGuidance(String stage, NextAction action, String text, int attention,
                         String waitingOn) {
        this.stage = stage;
        this.action = action;
        this.text = text;
        this.attention = attention;
        this.waitingOn = waitingOn;
    }

    /** A stage with a step of its own. */
    public static StageGuidance of(String stage, NextAction action, String text, int attention) {
        return new StageGuidance(stage, action, text, attention, null);
    }

    /** A stage that can do nothing until another one has moved — and says which, and why. */
    public static StageGuidance waitingFor(String stage, String waitingOn, String text) {
        return new StageGuidance(stage, NextAction.NONE, text, 0, waitingOn);
    }

    /** A stage with nothing outstanding and nothing to say. The bar renders nothing. */
    public static StageGuidance settled(String stage) {
        return new StageGuidance(stage, NextAction.NONE, null, 0, null);
    }

    /** True when this stage has a step of its own — the case that gets a button. */
    public boolean hasAction() {
        return action() != NextAction.NONE;
    }

    /**
     * True when there is a sentence to render. The bar's whole visibility rule: a stage with an
     * action always has one, a waiting stage has one, and a settled stage has none.
     */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** True when this stage is blocked on another one rather than on the operator. */
    public boolean isWaiting() {
        return waitingOn != null && !waitingOn.isBlank();
    }

    public String stage() { return stage; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    /** Null-safe: an unrecorded action IS "no step of its own", which is what an older value means. */
    public NextAction action() { return action == null ? NextAction.NONE : action; }
    public NextAction getAction() { return action; }
    public void setAction(NextAction action) { this.action = action; }
    public String text() { return text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int attention() { return attention; }
    public int getAttention() { return attention; }
    public void setAttention(int attention) { this.attention = attention; }
    public String waitingOn() { return waitingOn; }
    public String getWaitingOn() { return waitingOn; }
    public void setWaitingOn(String waitingOn) { this.waitingOn = waitingOn; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StageGuidance that = (StageGuidance) o;
        return this.attention == that.attention
            && Objects.equals(this.stage, that.stage)
            && this.action() == that.action()
            && Objects.equals(this.text, that.text)
            && Objects.equals(this.waitingOn, that.waitingOn);
    }

    // action compares through its NULL-SAFE accessor, not the raw field, for the same reason
    // ConsoleReadiness does: the wire serializer reads through the same accessor, so comparing the
    // raw field would make a round-tripped value unequal to the one that was sent — which silently
    // breaks signal dedup and redraws the whole shell on every publish.
    @Override
    public int hashCode() {
        return Objects.hash(stage, action(), text, attention, waitingOn);
    }
}
