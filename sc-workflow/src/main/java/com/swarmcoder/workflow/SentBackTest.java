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
package com.swarmcoder.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The words for an acceptance test that goes back to its author after candidates exist
 * (section 86): a correction that errors before any candidate's code runs, and a test sent back
 * on the instruction of whoever answered the stopped build's question.
 *
 * <p><b>Why (live supervised build, 2026-10-12).</b> A test's author rightly called its test
 * wrong and corrected it; the correction also changed how the test starts, and from then on
 * every test errored in its own set-up. Nothing told the author so: the correction was taken,
 * a repair round of workers who may not change the test was spent on it, and the supervisor's
 * only answers were to run the stage again or to give the workers another round.
 *
 * <p>The author is told what the build measured - the error itself, and that the earlier
 * version did start when that is on record - and is pointed at the tests of this task and of
 * this project that pass, by name, as lookups it makes itself (CLAUDE.md section 1). No test
 * file is pasted here and nothing is read from the words of an error.
 */
final class SentBackTest {

    private SentBackTest() {
    }

    /** The most names of passing tests listed from each source. */
    static final int MOST_NAMES = 6;

    /**
     * Which tests pass, and where to look up how they are set up; "" when none is on record.
     *
     * @param ofTheTask    ids ({@code Class#method}) of this task's own tests that passed for
     *                     at least one candidate, as verification last recorded them - under
     *                     the test as it stood before it was last changed
     * @param ofTheProject ids or class names of other acceptance tests of the project that
     *                     pass: of tasks of this build that have a chosen candidate, and of
     *                     earlier stories
     */
    static String setupLookups(Collection<String> ofTheTask, Collection<String> ofTheProject) {
        List<String> mine = new ArrayList<>();
        if (ofTheTask != null) {
            for (String id : ofTheTask) {
                if (id != null && !id.isBlank() && !mine.contains(id.strip())
                        && mine.size() < MOST_NAMES) {
                    mine.add(id.strip());
                }
            }
        }
        List<String> others = classesOf(ofTheProject, new LinkedHashSet<>(classesOf(mine, Set.of())));
        if (mine.isEmpty() && others.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\nHOW TESTS THAT PASS ARE SET UP. Do not guess "
            + "a set-up: these tests start and run in this project's build.");
        if (!mine.isEmpty()) {
            text.append("\n- This task's own test(s) ").append(String.join(", ", mine))
                .append(" passed for at least one candidate as the test stood before it was "
                    + "last changed, so the set-up of that version works.");
        }
        if (!others.isEmpty()) {
            text.append("\n- Passing acceptance tests of this project: ")
                .append(String.join(", ", others))
                .append(". How each one starts - the annotations on its class, its fields, "
                    + "its set-up methods - is a lookup: body_of <Class> returns it from the "
                    + "syntax tree.");
        }
        return text.toString();
    }

    /** Distinct class names of the ids, in order, without those in {@code not}, at most {@link #MOST_NAMES}. */
    private static List<String> classesOf(Collection<String> ids, Set<String> not) {
        List<String> classes = new ArrayList<>();
        if (ids == null) {
            return classes;
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String cls = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
            cls = cls.strip();
            if (!cls.isEmpty() && !classes.contains(cls) && !not.contains(cls)) {
                classes.add(cls);
            }
            if (classes.size() >= MOST_NAMES) {
                break;
            }
        }
        return classes;
    }

    /**
     * What the author is told about a correction that errors before any candidate's code runs.
     *
     * @param reason         what was measured, in a sentence
     * @param failureText    the first error as the runner reported it
     * @param earlierStarted true when the version before the correction is on record as having
     *                       started and run to its assertions for the candidates
     * @param setups         {@link #setupLookups}
     */
    static String brokenCorrectionReask(String reason, String failureText, boolean earlierStarted,
                                        String setups) {
        return "The corrected test you handed in is broken, not red: " + reason + ". A worker "
            + "may not edit your test, so every candidate dies on this whatever it wrote. The "
            + "error as the runner reported it:\n\n" + failureText + "\n\n"
            + (earlierStarted
                ? "The version before your correction did start: it ran to its assertions for "
                    + "every candidate. So what the correction changed in how the test starts "
                    + "is where this error comes from. "
                : "")
            + "Keep what your correction put right in what the test checks, and put right how "
            + "the test starts. Do not change what a check proves." + setups;
    }

    /**
     * What the author is told when the test is sent back on an instruction.
     *
     * @param instruction the text of the answer {@code retest}
     * @param evidence    what the build stopped on, as verification recorded it
     * @param setups      {@link #setupLookups}
     */
    static String instructedReask(String instruction, String evidence, String setups) {
        return "The build stopped on this task, and whoever supervises the build read the "
            + "failure below and sends your test back with this instruction. Do what it says:"
            + "\n\n" + (instruction == null ? "" : instruction.strip())
            + "\n\nWHAT THE BUILD STOPPED ON:\n"
            + (evidence == null || evidence.isBlank() ? "(no failure is on record)"
                : evidence.strip())
            + "\n\nCorrect the test so that it still proves the same criterion and still fails "
            + "on code that does not implement it. No worker may edit your test." + setups;
    }

    /** The brief a run stops with when a corrected test is still broken. */
    static String stop(String taskTitle, String reason, String failureText, String note) {
        return "The acceptance test for task '" + taskTitle + "' is broken, not red: " + reason
            + ".\n\n" + (failureText == null ? "" : failureText.strip()) + "\n\n"
            + (note == null || note.isBlank() ? "" : note.strip() + "\n\n")
            + "No repair round was started: no worker may change an acceptance test, so no "
            + "candidate can make this one pass.";
    }
}
