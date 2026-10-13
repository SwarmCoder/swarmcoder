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
 * How much of an earlier build of a story the next build may start from, as far as the stage the
 * earlier build ended at and a send-back decide it (section 98 of
 * {@code docs/DEVELOPER_CORRECTIONS.md}). Whether the inputs are still the same is a separate
 * question, answered where the next build starts.
 *
 * <p><b>Nothing here reads the note.</b> A send-back's note is free text and is never searched
 * for what it is about. What a send-back objects to is taken from two facts and one optional
 * statement:
 *
 * <ul>
 *   <li><b>The sender names the stage</b> ({@code design}, {@code plan}, {@code tests} or
 *       {@code code}). That stage and every later one are done again.</li>
 *   <li><b>A delivery that came back for a verdict was refused</b> and no stage was named. What
 *       was looked at is the finished result, which the design, the plan, the tests and the code
 *       made together, so nothing is kept.</li>
 *   <li><b>A build that stopped was sent back</b> and no stage was named. What was in front of
 *       the sender is the stop. Every stage before the one that stopped was passed by the
 *       product's own checks and is kept; the stage that stopped is done again. A build that
 *       stopped while building keeps its tests and the tasks that already have a chosen
 *       candidate. A build that failed its final integration had code that did not hold
 *       together, so its code is done again.</li>
 * </ul>
 *
 * <p>A build that is simply started again (the process stopped, the story was freed) has no
 * objection against it: everything it finished is kept.
 */
public final class RebuildFrom {

    private RebuildFrom() {}

    /** What the next build starts from. Not stored: worked out from stored facts each time. */
    public enum Keep {
        /** Design, plan, tests and code are all made again. */
        NOTHING,
        /** The design is kept. */
        DESIGN,
        /** The design and the plan are kept; the tests are written again. */
        PLAN,
        /** Design, plan and tests are kept, and so is every task that has a chosen candidate. */
        TESTS_AND_FINISHED_TASKS
    }

    /** The stage words a send-back may name, in the order the stages run. */
    public static final String STAGE_WORDS = "design, plan, tests, code";

    /**
     * The stage a send-back names, as the first stage to be done again.
     *
     * @return null when nothing was named
     * @throws IllegalArgumentException for anything but one of {@link #STAGE_WORDS}
     */
    public static RunState stated(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        return switch (word.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "design" -> RunState.DESIGN;
            case "plan" -> RunState.PLAN;
            case "tests" -> RunState.TEST_AUTHORING;
            case "code" -> RunState.EXECUTING;
            default -> throw new IllegalArgumentException("redo_from is one of " + STAGE_WORDS
                + ", or is left out; \"" + word.strip() + "\" is none of them");
        };
    }

    /** What a build that follows one with no objection against it starts from. */
    public static Keep afterAnInterruption(RunState endedAt) {
        if (endedAt == null) {
            return Keep.NOTHING;
        }
        return switch (endedAt) {
            case INTAKE, DESIGN, DESIGN_REVIEW, ABORTED, ABANDONED -> Keep.NOTHING;
            case PLAN -> Keep.DESIGN;
            case TEST_AUTHORING -> Keep.PLAN;
            case REPRODUCE, CHARACTERIZE, EXECUTING, FINAL_INTEGRATION, APPROVAL, DELIVERED ->
                Keep.TESTS_AND_FINISHED_TASKS;
        };
    }

    /**
     * What the build that follows a send-back starts from.
     *
     * @param was     the state the story was in when it was sent back
     * @param endedAt the stage its last build ended at; null when not known
     * @param stated  the first stage to do again as the sender named it; null when none was named
     */
    public static Keep afterASendBack(StoryState was, RunState endedAt, RunState stated) {
        if (stated != null) {
            return switch (stated) {
                case PLAN -> Keep.DESIGN;
                // The code is objected to. The tests could stand, but a plan's tasks carry the
                // candidates chosen for them, so the level below is the most that can be kept.
                case TEST_AUTHORING, EXECUTING -> Keep.PLAN;
                default -> Keep.NOTHING;
            };
        }
        if (was == StoryState.REVIEW || endedAt == null) {
            return Keep.NOTHING;
        }
        return switch (endedAt) {
            case PLAN -> Keep.DESIGN;
            case TEST_AUTHORING -> Keep.PLAN;
            case REPRODUCE, CHARACTERIZE, EXECUTING -> Keep.TESTS_AND_FINISHED_TASKS;
            // The merged code failed: the code is done again (see the case for "code" above).
            case FINAL_INTEGRATION, APPROVAL -> Keep.PLAN;
            default -> Keep.NOTHING;
        };
    }

    /** {@code keep} in a sentence, for whoever sent the story back and for the run's record. */
    public static String inWords(Keep keep) {
        return switch (keep) {
            case NOTHING -> "The next build makes the design, the plan, the tests and the code "
                + "again.";
            case DESIGN -> "The next build keeps the design, if what it was made from is "
                + "unchanged, and makes the plan, the tests and the code again.";
            case PLAN -> "The next build keeps the design and the plan, if what they were made "
                + "from is unchanged and no task has a chosen candidate yet (otherwise only the "
                + "design), and makes the tests and the code again.";
            case TESTS_AND_FINISHED_TASKS -> "The next build keeps the design, the plan, the "
                + "tests and every task that already has a chosen candidate, if what they were "
                + "made from is unchanged, and builds the tasks that have none.";
        };
    }
}
