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
import com.swarmcoder.console.api.NextAction;
import com.swarmcoder.console.api.StageGuidance;
import com.zeroz4j.signals.ValueSignal;

/**
 * Where the operator is, and where the server says work is waiting.
 *
 * <p><b>Everything here is read from the server.</b> {@link ConsoleReadiness} carries the single
 * global recommendation ({@link ConsoleReadiness#nextAction()}) and one {@link StageGuidance} per
 * surface, judged from the requirement graph, the pipeline and the build history — none of which the
 * browser can see without asking. This class only <em>maps</em> those answers onto a {@link Stage}. It
 * invents no second notion of progress: a client that guessed would disagree with the server the
 * moment anything changed, and two disagreeing progress indicators are worse than none.
 *
 * <p>{@link #SELECTED} is a different thing entirely: it is where the operator chose to be. It follows
 * the recommendation exactly once, on load, and after that moves only when something is clicked — a
 * workspace that reassigns itself while you are typing in it is worse than one that started in the
 * wrong place.
 *
 * <h2>What was deleted from here, and why it could not be fixed instead</h2>
 *
 * <p>This class used to compute done / current / ahead per stage, and a hover hint per state, for the
 * stepper. Both are gone with it (UX v3 §6). The reasoning is worth keeping: doneness was originally
 * read off "is this stage left of the recommendation", which is a waterfall, and the process is not
 * one — so with four drafts outstanding the rule ticked NOTHING off and told an operator standing in
 * front of six proposed stories that the stage had not started. Deriving it from each stage's OWN
 * outstanding count fixed that particular lie and left a control still claiming a sequence exists.
 * There are two workspaces now and neither is ever "done": one holds the scope, the other holds the
 * work, and both stay open for the life of the project.
 */
final class Stages {

    /**
     * The surface whose workspace is on screen. Set only from a click, or once at boot.
     *
     * <p>Defaults to the pipeline rather than to Setup: Setup stopped being a place to be sent
     * (§3.3), and the pipeline is where a project that is already going has its work.
     */
    static final ValueSignal<Stage> SELECTED = new ValueSignal<>(Stage.PLAN);

    private Stages() {}

    /** The id this surface's guidance is filed under, or null for the developer tools. */
    static String id(Stage stage) {
        return switch (stage) {
            case SETUP -> StageGuidance.SETUP;
            case REQUIREMENTS -> StageGuidance.REQUIREMENTS;
            case PLAN -> StageGuidance.PLAN;
            case BUILD -> StageGuidance.BUILD;
            default -> null;
        };
    }

    /** What this surface says for itself, or null when it has nothing to say. */
    static StageGuidance guidance(Stage stage, ConsoleReadiness readiness) {
        if (readiness == null || stage == null || stage.developer()) {
            return null;
        }
        return readiness.guidance(id(stage));
    }

    /** The surface a guidance's {@code waitingOn} points at, or null when it points nowhere known. */
    static Stage stageOf(String stageId) {
        if (stageId == null) {
            return null;
        }
        for (Stage stage : Stage.values()) {
            if (stageId.equals(id(stage))) {
                return stage;
            }
        }
        return null;
    }

    /** How many things on this surface are waiting on the operator. */
    static int attention(Stage stage, ConsoleReadiness readiness) {
        StageGuidance guidance = guidance(stage, readiness);
        return guidance == null ? 0 : guidance.attention();
    }

    /**
     * The workspace the server's landing recommendation points at.
     *
     * <p>Only ever one of the two workspaces. Setup is no longer somewhere to be SENT: it is the
     * health dot, and a project that cannot run says so there while the operator gets on with the
     * scope — which they can author without a repository. Landing them in a configuration panel was
     * the previous behaviour, and it put setup in front of everybody who had come to do something else.
     */
    static Stage recommended(ConsoleReadiness readiness) {
        if (readiness == null || !readiness.hasProject()) {
            return Stage.REQUIREMENTS;
        }
        return switch (readiness.nextAction()) {
            case ANALYSE_DOCUMENTS, PROMOTE_DRAFTS -> Stage.REQUIREMENTS;
            // Everything else happens on the pipeline board, on a story's own card — including the
            // questions a build stopped to ask, once §9 step 4 folds those onto it.
            case PLAN_STORIES, PROMOTE_STORIES, CREATE_ITERATION, SCHEDULE_STORIES,
                 REVIEW_RESULTS, START_RUN, ANSWER_DECISIONS -> Stage.PLAN;
            case NONE -> Stage.PLAN;
        };
    }

    /** Where a step is performed, or null when it has no single home. */
    static Stage destinationOf(NextAction action) {
        return switch (action) {
            case ANALYSE_DOCUMENTS, PROMOTE_DRAFTS -> Stage.REQUIREMENTS;
            case PLAN_STORIES, PROMOTE_STORIES, CREATE_ITERATION, SCHEDULE_STORIES,
                 REVIEW_RESULTS, START_RUN -> Stage.PLAN;
            // The pipeline, not the retired Approval Center: a question a build stopped to ask is on
            // the card of the story it belongs to (UX v3 §2.3). Pointing at "More" produced a button
            // labelled "Open More" beside a sentence about answering questions — a destination that no
            // longer holds them, named after a menu.
            case ANSWER_DECISIONS -> Stage.PLAN;
            case NONE -> null;
        };
    }

    /**
     * Whether autonomous running does this step itself.
     *
     * <p>Listed rather than inferred, so that adding a step to {@link NextAction} makes somebody
     * decide which side of the line it is on rather than defaulting to the wrong one. The five below
     * are the gates {@code AutonomousBuild} passes on the operator's behalf.
     *
     * <p>The other three are deliberately NOT here, and each for a reason the mode itself gives:
     * a question a build stopped to ask has no answer the machine may invent, judging what came back
     * is what the arming screen promises the operator keeps, and grouping work into iterations is
     * not something the pilot touches at all.
     */
    static boolean doneByThePilot(NextAction action) {
        return switch (action) {
            case ANALYSE_DOCUMENTS, PROMOTE_DRAFTS, PLAN_STORIES, PROMOTE_STORIES, START_RUN -> true;
            case SCHEDULE_STORIES, CREATE_ITERATION, REVIEW_RESULTS, ANSWER_DECISIONS, NONE -> false;
        };
    }

    /**
     * The button label for the header's one guidance line, or null when there should be no button.
     *
     * <p>Null matters as much as the label does. The line's job is to name the single next step; a
     * button that navigates to where the operator already is was the dead end the per-stage bars kept
     * producing ("Go to Plan", pressed from Plan). The honest answer there is a sentence with no
     * button, because the controls are on the cards in front of them.
     */
    static String destinationLabel(NextAction action, Stage showing) {
        Stage destination = destinationOf(action);
        if (destination == null || destination == showing) {
            return null;
        }
        return "Open " + destination.label();
    }
}
