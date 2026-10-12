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
 * A task's acceptance test sent back to its author on the instruction of whoever answered the
 * stopped build's question (section 86).
 *
 * <p>A build stopped on a task whose own acceptance test is what is wrong could be answered
 * {@code retry}, which repeats the stop, or {@code repair}, a round of workers none of whom may
 * change the test. The answer {@code retest} is the one that reaches the test: its author is
 * given the answer's text and the failure, the correction passes every check a first-written
 * test does, it is committed on the run's tests branch, and the task is verified again from the
 * candidates it already has. The story is not designed or built again.
 *
 * <p>It is offered for a task stopped at the build stage that has a protected acceptance test
 * and did not stop for a fault of the plan, and it is counted per task per run and capped.
 * Nothing here reads the words of a question.
 */
public final class InstructedRetest {

    /** The answer token. */
    public static final String TOKEN = "retest";

    /** Times one task's test may go back to its author on an instruction in one run. */
    public static final String LIMIT_PROPERTY = "swarmcoder.retest.instructedRounds";
    public static final int DEFAULT_LIMIT = 1;

    /** The most the setting can allow. */
    public static final int MOST = 4;

    private InstructedRetest() {
    }

    /** How many times a task's test may go back on an instruction in one run: 0..{@link #MOST}. */
    public static int limit() {
        int asked = Integer.getInteger(LIMIT_PROPERTY, DEFAULT_LIMIT);
        return Math.max(0, Math.min(MOST, asked));
    }

    /** Whether this task's test may go back on an instruction once more. */
    public static boolean left(Task task) {
        return task != null && task.instructedRetests() < limit();
    }

    /** Whether this task has a protected acceptance test of its own to send back. */
    public static boolean hasATest(Task task) {
        return task != null && task.authoredTestPaths() != null
            && !task.authoredTestPaths().isEmpty();
    }
}
