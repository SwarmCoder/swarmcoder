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
package com.swarmcoder.runtime;

import java.util.List;

/**
 * The worker's 911: when it meets an API that is not in its training, it asks instead of guessing.
 *
 * <p><b>Why this exists.</b> Measured over five runs of a plain agent harness against an
 * unfamiliar framework: the model does not guess an API it is unsure of and it does not let the
 * compiler correct it — it goes looking for certainty. Given prose documentation it read 43
 * documents and then disassembled the framework's jars. Given the framework's own working code, in
 * full, in its own prompt, it spent 66 shell commands extracting source jars and running
 * {@code javap} over the annotation processor and the CDI producer to confirm what it had already
 * been handed, and wrote nothing in 92 turns. The behaviour is not ignorance and it is not
 * laziness. It is a worker with no way to ask.
 *
 * <p>So there is a way to ask. Two questions, both answered by the SYSTEM and never by the
 * worker's own guessing: <em>how does this API get called</em>, and <em>what does this type look
 * like before I fill it in</em>. Both are answered deterministically first — from the reference
 * material, the nearest worked example and the task's own contracts, which cost nothing — and only
 * fall through to a stronger model when that comes up empty.
 *
 * <p>Kept as a plain interface in this module for the same reason {@link ApiLookup} is: the swarm
 * engine carries it without depending on sc-knowledge, and the real implementation lives where the
 * knowledge does.
 */
public interface ExpertHelp {

    /** Where an answer came from — recorded on the candidate, and shown to the judge. */
    enum Source {
        /** The reference material, the nearest example, or the task's contracts. Free. */
        DETERMINISTIC,
        /** A stronger model, because nothing deterministic could answer. Costs tokens. */
        MODEL,
        /** Nothing could answer, or the per-worker cap is spent. */
        NONE
    }

    /**
     * @param text        what the worker is told: worked code, its imports, the build line if one
     *                    is needed, and a short explanation. Never null.
     * @param source      where it came from
     * @param tokens      what it cost, 0 for a deterministic answer
     * @param expertTurns how many turns the expert took to reach it — 0 for anything the free
     *                    tiers answered, and 0 for an expert that answered in one shot without
     *                    looking anything up
     * @param toolsUsed   the lookups the expert made before answering, in the order it made them
     *                    (a name repeats when it was called twice). Empty unless the expert is the
     *                    tool-using kind. This is the difference between "a model said so" and "a
     *                    model read seven files in this codebase and then said so", and the judge
     *                    and the operator both need to be able to tell those apart.
     * @param reason      why this answer came out the way it did — {@code "free: covered 0.8 of
     *                    the question"}, {@code "escalated: free answer covered 0.2"} — or "" when
     *                    nothing more than the source says. Never null. Shown to the operator, on
     *                    the worker's help record, and nowhere it affects what the worker is told
     *                    to do next; it explains the desk's own decision, not the answer's content.
     */
    record Answer(String text, Source source, int tokens, int expertTurns, List<String> toolsUsed,
                  String reason) {

        /** Kept so every call site written before the expert had tools compiles unchanged. */
        public Answer(String text, Source source, int tokens) {
            this(text, source, tokens, 0, List.of(), "");
        }

        /** Kept so every call site written before this desk could say WHY compiles unchanged. */
        public Answer(String text, Source source, int tokens, int expertTurns,
                      List<String> toolsUsed) {
            this(text, source, tokens, expertTurns, toolsUsed, "");
        }

        public Answer {
            toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
            reason = reason == null ? "" : reason;
        }

        public static Answer deterministic(String text) {
            return new Answer(text, Source.DETERMINISTIC, 0);
        }

        public static Answer none(String why) {
            return new Answer(why, Source.NONE, 0);
        }

        /** A question the expert took turns over and still could not answer — say how far it got. */
        public static Answer none(String why, int expertTurns, List<String> toolsUsed) {
            return new Answer(why, Source.NONE, 0, expertTurns, toolsUsed);
        }

        public boolean answered() {
            return source != Source.NONE;
        }
    }

    /**
     * How this codebase calls the API the worker is stuck on.
     *
     * @param question    the exact API question, e.g. "how do I persist a list change"
     * @param whatITried  what the worker already attempted, so an answer can correct it rather than
     *                    repeat what it has. May be null or blank.
     */
    Answer askExpert(String question, String whatITried);

    /**
     * A compiling starting point for a named type, or for the task at hand.
     *
     * <p>Generated from the task's contract when one names that type — package, members,
     * the annotations and injected fields the nearest example carries, and every body a
     * {@code TODO} that compiles. Otherwise the nearest existing implementation's file, whole.
     */
    Answer requestSkeleton(String typeOrTask);

    /** Nothing configured: say so plainly rather than pretending an answer. */
    ExpertHelp UNAVAILABLE = new ExpertHelp() {
        @Override
        public Answer askExpert(String question, String whatITried) {
            return Answer.none("No expert is configured for this project, so this question cannot "
                + "be answered. Write your best attempt and let the build correct you.");
        }

        @Override
        public Answer requestSkeleton(String typeOrTask) {
            return Answer.none("No expert is configured for this project, so no skeleton can be "
                + "generated. Write the file yourself.");
        }
    };
}
