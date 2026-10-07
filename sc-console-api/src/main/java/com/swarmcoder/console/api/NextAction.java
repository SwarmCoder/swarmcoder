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

/**
 * The one thing the operator should do next, as a stable machine-readable key.
 *
 * <p>It exists because a sentence alone is not actionable. {@link ConsoleReadiness} carries the
 * prose — what to do and why it matters — and this carries the identity of the step, so the client
 * can put a button next to the sentence that actually performs it, and a panel can decide whether
 * that step is one IT can offer at all.
 *
 * <p>The judgement of which step is current is made server-side, in {@code ReadinessPublisher}: the
 * browser cannot see the BRD, the backlog or the run history without asking, and a client that
 * guessed would disagree with the server the moment anything changed.
 *
 * <p><b>APPEND-ONLY.</b> These constants travel the wire by {@code name()} (the generated serializer
 * writes {@code name()} and reads {@code valueOf}), so removing or renaming one breaks every client
 * that is not redeployed in the same instant. Add new stages at the end; never reorder, never
 * delete. {@link #NONE} is the "nothing to say" value and must stay first so an unset field and an
 * explicit "nothing" read the same.
 */
public enum NextAction {

    /** Nothing to say — the next-step bar renders nothing at all. */
    NONE(""),
    /** The BRD is empty: turn the documents the operator already has into requirements. */
    ANALYSE_DOCUMENTS("analyse-documents"),
    /** Requirements exist but are still proposals: promoting them makes them the agreed scope. */
    PROMOTE_DRAFTS("promote-drafts"),
    /** Scope is agreed but there is no work: stories are what turn a requirement into a run. */
    PLAN_STORIES("plan-stories"),
    /** Work is ready and nothing has been attempted yet. */
    START_RUN("start-run"),

    // --- appended 2026-07-27, for per-stage guidance ------------------------------------------
    // Everything below is a step the PLAN stage owns. They exist because one global next action
    // could only ever name the earliest unfinished thing in the whole project, so Plan's own
    // progression — promote → schedule → start — was invisible: the operator promoted a story and
    // the screen said nothing about what was now sitting in the Unscheduled column.

    /** DRAFT stories are waiting: promoting one puts it in the plan, which makes it schedulable. */
    PROMOTE_STORIES("promote-stories"),
    /** There is agreed work and nowhere to put it — a project with no iteration yet. */
    CREATE_ITERATION("create-iteration"),
    /** Agreed work is sitting unscheduled while iterations exist to put it in. */
    SCHEDULE_STORIES("schedule-stories"),
    /** A run came back: something is in REVIEW or BLOCKED and the next move is the operator's. */
    REVIEW_RESULTS("review-results"),

    // --- appended 2026-07-28, for Build's own attention ----------------------------------------
    // Appended, not inserted: see the APPEND-ONLY note above. Build previously had no step of its
    // own at all — it reported runs in flight and carried attention 0 — so a project with
    // thirty-seven unanswered decisions had a Build chip with no badge and a Build bar that talked
    // about watching runs. Watching is not a decision the operator owes; answering is.

    /**
     * Decisions are unanswered. Each was raised where work stopped — a blocked task, a run parked
     * mid-workflow, or an exhausted cloud budget — and they are answered in Build's Approvals.
     */
    ANSWER_DECISIONS("answer-decisions");

    private final String key;

    NextAction(String key) {
        this.key = key;
    }

    /**
     * The stable key the client maps to a button — {@code analyse-documents}, {@code promote-drafts},
     * {@code plan-stories}, {@code start-run}, {@code promote-stories}, {@code create-iteration},
     * {@code schedule-stories}, {@code review-results}. Kebab-case rather than the constant's name so
     * a view's list of steps reads as steps, not as Java identifiers.
     */
    public String key() {
        return key;
    }

    /** The action for a key, or {@link #NONE} for null, blank or an unrecognised one. */
    public static NextAction ofKey(String key) {
        if (key != null && !key.isBlank()) {
            for (NextAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
        }
        return NONE;
    }
}
