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
 * One more repair round for a task of a stopped build, told what to change by whoever answered
 * the build's question (section 84).
 *
 * <p>A build that stopped on a blocked task or a failed final integration could be answered in
 * two ways: run the stage again, which repeats the same stop when the cause is something the
 * workers must change, or send the whole story back to be designed and built again. The answer
 * {@code repair} is the one in between: the task's workers get one more repair round, seeded
 * from the candidates the task already has, with the answer's text as their instruction.
 *
 * <p>It is offered only where such a round can succeed - the task has a candidate with a branch
 * and a verification report to start from, and it did not stop for a fault of the plan - and it
 * is counted per task per run and capped. Nothing here reads the words of a question.
 */
public final class InstructedRepair {

    /** The answer token. */
    public static final String TOKEN = "repair";

    /** Rounds one task may be given on an instruction in one run. */
    public static final String LIMIT_PROPERTY = "swarmcoder.repair.instructedRounds";
    public static final int DEFAULT_LIMIT = 1;

    /**
     * The most the setting can allow. A repair worker's index names its branch, and the scheme
     * has ten indices for each repaired attempt ({@link RepairIndex}): two for the ordinary
     * round and two for each instructed one.
     */
    public static final int MOST = 4;

    /** Workers started from each seed in one round; the indices one round uses. */
    public static final int WORKERS_PER_SEED = 2;

    private InstructedRepair() {
    }

    /** How many instructed rounds a task may have in one run: the setting, within 0..{@link #MOST}. */
    public static int limit() {
        int asked = Integer.getInteger(LIMIT_PROPERTY, DEFAULT_LIMIT);
        return Math.max(0, Math.min(MOST, asked));
    }

    /** Whether this task may be given another instructed round. */
    public static boolean roundLeft(Task task) {
        return task != null && task.instructedRepairRounds() < limit();
    }

    /**
     * The first attempt number of an instructed round, for {@link RepairIndex#of}: after the
     * ordinary round's two, and after every earlier instructed round's.
     *
     * @param round which instructed round this is, counted from one
     */
    public static int firstAttemptOf(int round) {
        return Math.min(RepairIndex.PER_ATTEMPT - WORKERS_PER_SEED,
            Math.max(1, round) * WORKERS_PER_SEED);
    }

    /** What the repair workers read before the failure evidence. */
    public static String brief(String instruction) {
        return "--- instruction for this repair round ---\n"
            + "The build stopped on this task and whoever supervises the build read the failure "
            + "below and answered with this instruction. Do what it says:\n\n"
            + (instruction == null ? "" : instruction.strip())
            + "\n\n--- what the build stopped on ---\n";
    }
}
