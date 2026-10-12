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

import java.util.List;

/**
 * A person's or the supervisor's verdict on a journey a failed final integration stopped on
 * (section 88).
 *
 * <p>A failed journey goes back to its author first, and the author says which side is wrong
 * (sections 69 and 70). When the author calls its journey wrong and no correction can be taken,
 * the build stops with no worker started, and the only answer was {@code retry}, which asks the
 * author again. The person who holds the functional decision could not say which side is right.
 * Two answers do that, each with text:
 *
 * <ul>
 *   <li>{@link #SCREEN}: the journey is right and the screen is wrong. The task whose screen
 *       the journey uses gets one repair round with the text as its instruction - the round of
 *       {@link InstructedRepair}, with its count and cap - and the journey is not sent back to
 *       its author again. The verdict outranks the author's.</li>
 *   <li>{@link #JOURNEY}: the journey is wrong, and the text says how to correct it. The
 *       journey goes back to its author with the text; the correction is judged by the same
 *       two browser runs as any correction, and may ask for less than the journey it replaces,
 *       which is recorded. Counted per journey per run and capped here.</li>
 * </ul>
 *
 * <p>Which question takes which answer is decided from marks the workflow set when it raised
 * the question, never from the question's words.
 */
public final class JourneyVerdict {

    /** The answer token for "the journey is right, the screen is wrong". */
    public static final String SCREEN = "screen";

    /** The answer token for "the journey is wrong; correct it like this". */
    public static final String JOURNEY = "journey";

    /** Times one journey may go back to its author on an instruction in one run. */
    public static final String LIMIT_PROPERTY = "swarmcoder.journey.instructedCorrections";
    public static final int DEFAULT_LIMIT = 1;

    /** The most the setting can allow. */
    public static final int MOST = 4;

    private JourneyVerdict() {
    }

    /** How many times a journey may go back on an instruction in one run: 0..{@link #MOST}. */
    public static int limit() {
        int asked = Integer.getInteger(LIMIT_PROPERTY, DEFAULT_LIMIT);
        return Math.max(0, Math.min(MOST, asked));
    }

    /** How many times the journey at {@code path} went back on an instruction in this run. */
    public static int given(Task task, String path) {
        return task == null ? 0 : (int) task.journeyInstructionsGiven().stream()
            .filter(entry -> entry.equals(path)).count();
    }

    /** Whether the journey at {@code path} may go back on an instruction once more. */
    public static boolean left(Task task, String path) {
        return task != null && given(task, path) < limit();
    }

    /**
     * The journeys the task's open question is about that the task itself claims and that may
     * still go back to their author on an instruction: what the answer {@link #JOURNEY} would
     * be about. A journey delivered with an earlier story is not among them - this build has
     * no author for it.
     */
    public static List<String> correctable(Task task) {
        if (task == null) {
            return List.of();
        }
        return task.disputedJourneys().stream()
            .filter(path -> task.journeyPaths().stream()
                .anyMatch(claimed -> claimed.replace((char) 92, '/').equals(path)))
            .filter(path -> left(task, path)).toList();
    }

    /**
     * What the repair workers are told for the answer {@link #SCREEN}: the verdict, that it
     * outranks what the journey's author answered, and the answer's own text.
     *
     * @param journeys the journeys the verdict is about, by path
     * @param actor    who answered
     * @param text     the answer's text
     */
    public static String screenInstruction(List<String> journeys, String actor, String text) {
        return "The " + (actor == null || actor.isBlank() ? "operator" : actor) + " has ruled "
            + "on the failed journey(s) " + journeys + ": THE JOURNEY IS RIGHT AND THE SCREEN "
            + "IS WRONG. That ruling outranks anything the journey's author answered, also "
            + "where the evidence below quotes the author calling the journey wrong. Do not "
            + "change a journey; each is a file at the path given, in your checkout, and "
            + "acceptance_test shows the ones this task claims. Change the screen so that a "
            + "person can do every step of the journey exactly as it is written. What the "
            + "ruling says must change:\n\n"
            + (text == null ? "" : text.strip());
    }

    /** One sentence for the task's record: the verdict was given over the author's answer. */
    public static String upheldNote(List<String> journeys, String actor, String text) {
        return "VERDICT OF THE " + (actor == null || actor.isBlank() ? "OPERATOR"
            : actor.toUpperCase(java.util.Locale.ROOT)) + " on " + journeys + ": the journey "
            + "is right and the screen is wrong. It outranks the answer of the journey's "
            + "author recorded before it; the journey is not sent back to its author again, "
            + "and the task's workers are given a repair round with: "
            + (text == null ? "" : text.strip());
    }
}
