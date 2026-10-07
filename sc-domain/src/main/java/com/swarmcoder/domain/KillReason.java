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
package com.swarmcoder.domain;

/**
 * Why a candidate stopped short. {@code SANDBOX_UNAVAILABLE} is not an early-kill rule (spec
 * §11.3) but an operator-policy refusal: the sandbox is required (config
 * {@code sandbox.required}, the default) and no container could be launched, so the candidate is
 * failed rather than silently run on the workstation.
 *
 * <p>{@code ENDPOINT_OUTAGE} is not a verdict on the candidate at all: the model endpoint never
 * answered, so this worker produced no evidence about anything. The engine treats a wave lost to it
 * as an outage to wait out rather than a failure to repair — see {@code EndpointOutage} in
 * sc-inference (not linked: sc-domain sits below it).
 *
 * <p>{@code NO_ACCEPTANCE_EVIDENCE} is likewise not a verdict on the code: the candidate compiled
 * and broke nothing, but the acceptance stage executed no test at all while the task answered for
 * requirement-checks that only a test can settle. Verification learned nothing about it, so it may
 * not reach the judge. It almost always means the repository's own verification contract selects no
 * tests, or the acceptance tests were never written — see DEVELOPER_CORRECTIONS.md §17.1.
 *
 * <p>{@code WORKER_ERROR} is the default for a worker that died for a reason that is not a genuine
 * elapsed-time timeout and not any of the more specific reasons above: a git worktree that could not
 * be created, a sandbox that could not be attached, an unexpected exception from the plumbing around
 * the model session. Before this constant existed every one of these was reported as {@code TIMEOUT},
 * which is false — nothing in any of these paths ever waits on a clock — and sent whoever read it
 * looking for slowness that was never there. {@code TIMEOUT} itself is kept for a genuine elapsed-time
 * timeout, and since 2026-09-01 something does produce it: a worker whose own request to the model
 * was still unanswered when the client gave up, while that same model server was demonstrably
 * answering other workers at the same time. That is not an outage and must not be waited out --
 * the conversation had simply grown too large to be answered in time.
 *
 * <p>{@code UNBUILT_FILES} is not a verdict on the code either: it compiled, but the build never
 * compiled IT. The candidate wrote source into a directory no module of the project owns — most
 * often a conventional-looking {@code src/main/java} at the root of a repository whose root build
 * file only aggregates modules — so nothing it produced is in the application, and the compile stage
 * that "passed" passed on the untouched modules around it. It almost always means the task's write
 * set was invented rather than read off the repository's real module layout.
 *
 * <p>{@code NO_PROGRESS} is the stall guard. A worker is not working when it has spent turn after
 * turn looking at things — running commands, reading files, asking for documentation — and changed
 * no file at all in between. It was added after a worker spent 121 turns and 105,529 tokens taking
 * a compiler's own jars apart with {@code javap} and produced nothing, while three other workers ran
 * beside it. Nothing about that is a slow model or a broken endpoint: the attempt had stopped
 * converging, and every further turn was pure cost. Killing it early is cheap because the other
 * workers of the group are still going.
 *
 * <p>{@code DOCS_DEAD_END} is the same stall with a known cause, and the distinction is the whole
 * finding. The worker in that run was NOT ignoring its instructions. It was told to use the
 * documentation tool, it asked four well-formed questions about how to handle a button click, and
 * the search answered all four with the same unrelated section. Only then did it go to the jars,
 * where it got its answer in one call. You cannot forbid the only route to an answer you have not
 * provided, so a run that ends this way is evidence about the DOCUMENTATION SEARCH and not about
 * the model: the corpus, the index, or the ranking failed, and that is what wants fixing.
 *
 * <p>{@code TURN_CAP} is its own reason since 2026-09-03, and the finding is what it used to hide.
 * A worker whose turn COUNT ran out and a worker whose conversation no longer FITS its room are
 * different failures with different fixes — one needs a bigger allowance or a smaller task, the
 * other needs a smaller working context or fewer workers sharing a server — and until this constant
 * existed both were reported as {@code BUDGET_EXCEEDED}, which is the compaction's own name for
 * running out of room. Harness run 11 killed two workers at exactly turn 25 with no compaction line
 * and no "ran out of room" explanation: that is a cap, not a room failure, and the two are counted
 * separately by {@link com.swarmcoder.inference.AdaptiveConcurrency} for exactly this reason — a
 * worker stopped by its turn cap says nothing about whether the room it had was enough.
 *
 * <p><b>APPEND ONLY.</b> These are persisted by ordinal; inserting a constant in the middle
 * re-labels every candidate already in the store.
 */
public enum KillReason {
    PARSE_FAIL, TOOLCALL_MALFORMED, BUDGET_EXCEEDED, WRITESET_VIOLATION, COMPILE_FAIL_TWICE,
    TIMEOUT, SUPERSEDED, SANDBOX_UNAVAILABLE, ENDPOINT_OUTAGE, NO_ACCEPTANCE_EVIDENCE,
    WORKER_ERROR, UNBUILT_FILES, NO_PROGRESS, DOCS_DEAD_END, TURN_CAP,
    /**
     * A spare attempt gave its place up (2026-10-02). It was a second attempt on a task that
     * already had one going, running on a place nothing else wanted; then a task with no attempt
     * running needed that place. Says nothing about the code. Appended last: this enum is stored.
     */
    PLACE_NEEDED,
    /**
     * Its repair round reached its wall-clock ceiling and it was still going (live run 74,
     * 2026-10-03: two repair rounds ran 74 and 66 minutes). A safety stop on the round, not a
     * verdict on the worker: whatever it had changed is verified. Appended last: this enum is
     * stored.
     */
    ROUND_TIME_UP;

    /**
     * Why this worker stopped, in the operator's words rather than the constant's name.
     *
     * <p>The same rule {@link CandidateState#label()} and {@link TaskState#label()} follow: a
     * constant is the machine describing itself, and the run graph draws a red chip whose only
     * explanation used to be BUDGET_EXCEEDED or WORKER_ERROR. One sentence each, said once, so the
     * graph, the inspector and anything else say the same thing about the same death.
     */
    public String sentence() {
        return switch (this) {
            case PARSE_FAIL -> "its answers stopped being readable, so it was stopped";
            case TOOLCALL_MALFORMED -> "it kept asking for tools in a form nothing could run";
            case BUDGET_EXCEEDED -> "its conversation outgrew what it is allowed, so it was stopped";
            // Since 2026-09-02 this fires ONLY for protected places - the verification
            // commands, the repository's own git state, a locked module, the acceptance tests.
            // Writing outside the task's own paths is recorded on the candidate instead.
            case WRITESET_VIOLATION -> "it kept trying to change protected files - the tests that "
                + "judge it, or the settings that decide whether it passed";
            case COMPILE_FAIL_TWICE -> "its code would not compile, twice running";
            case TIMEOUT -> "the model did not finish answering its last request in the time "
                + "allowed - an answer too long to write in that time, or a conversation too "
                + "big to read in it";
            case SUPERSEDED -> "another attempt on this task had already won, so this one was "
                + "stopped rather than finished";
            case SANDBOX_UNAVAILABLE -> "no sandbox could be started, and its work will not be "
                + "checked outside one";
            case ENDPOINT_OUTAGE -> "the model was not answering at all, so this attempt proves "
                + "nothing about the code";
            case NO_ACCEPTANCE_EVIDENCE -> "nothing tested the checks this task answers for, so "
                + "there is no evidence it works";
            case WORKER_ERROR -> "something around the worker broke - not the model, and not a "
                + "clock running out";
            case UNBUILT_FILES -> "it wrote files the build never compiles, so none of its work "
                + "is in the application";
            case NO_PROGRESS -> "it stopped getting anywhere - turn after turn of looking things "
                + "up and running commands without changing a single file, so it was stopped "
                + "rather than left to spend the rest of its budget";
            case DOCS_DEAD_END -> "the documentation search kept answering its questions with the "
                + "same wrong section, so it ran out of ways to find out how the code works - "
                + "this is the search failing, not the worker";
            case TURN_CAP -> "it used all of its turns without finishing - raise "
                + "budgets.maxToolTurnsPerWorker or split the task";
            case ROUND_TIME_UP -> "its repair round reached the time it is allowed and it was "
                + "still going, so it was stopped and whatever it had changed was verified";
            case PLACE_NEEDED -> "it was a spare attempt, and its place on the model server was "
                + "needed for a task that had no attempt running, so it was stopped";
        };
    }

    /** {@link #sentence()} for a reason that arrives as a string, and "" for one that is not one. */
    public static String sentenceOf(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        try {
            return valueOf(reason).sentence();
        } catch (IllegalArgumentException e) {
            return reason.toLowerCase().replace('_', ' ');
        }
    }
}
