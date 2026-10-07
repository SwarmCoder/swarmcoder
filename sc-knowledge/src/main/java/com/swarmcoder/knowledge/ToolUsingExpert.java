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
package com.swarmcoder.knowledge;

import com.swarmcoder.inference.MaterialBudget;

import java.util.List;
import java.util.function.BiFunction;

/**
 * An expert that can say how it got to its answer, not only what the answer was.
 *
 * <p>{@link ExpertDesk}'s escalation seam is a plain {@code (question, context) -> answer}
 * {@link BiFunction}, and it stays one: every test in this module supplies a lambda, the harness
 * supplies one, and none of them should have to know what an agent session is. But the real
 * escalation is now an agent session with tools, and two facts about that session belong on the
 * worker's help record — how many turns it took, and what it looked up — because they are the
 * difference between "a model said so" and "a model read seven files in this codebase and then said
 * so". The judge is told to weigh those differently and the operator reads them on the chip.
 *
 * <p>So the seam is widened rather than replaced: an escalation that has more to say implements
 * this, the desk asks for the richer report when it is offered and takes the plain string when it
 * is not, and every existing lambda keeps working untouched.
 */
public interface ToolUsingExpert extends BiFunction<String, String, String> {

    /**
     * @param text      the answer for the worker, or — when {@code answered} is false — the honest
     *                  sentence saying what happened instead. Never null, never blank.
     * @param answered  false for a session that reached no answer: the turn allowance ran out, the
     *                  endpoint was not there, the budget was spent, or the final reply was empty.
     *                  There is no third state and there is never a made-up answer.
     * @param turns     model turns the expert spent
     * @param toolsUsed the lookups it made, in order; a name repeats when it was called twice
     * @param tokens    what the whole session cost, as charged to the run's budget
     * @param promptTokens     what the model server counted as SENT to it over the session's
     *                         calls, every turn's resend of the conversation included; -1 when
     *                         it did not say
     * @param completionTokens what the model server counted as generated; -1 when it did not say
     */
    record Report(String text, boolean answered, int turns, List<String> toolsUsed, int tokens,
                  long promptTokens, long completionTokens) {

        public Report {
            toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
        }

        /** A report from an expert that does not know the server's own counts. */
        public Report(String text, boolean answered, int turns, List<String> toolsUsed,
                      int tokens) {
            this(text, answered, turns, toolsUsed, tokens, -1, -1);
        }
    }

    /** The expert's whole session: the answer, and what it did to reach it. */
    Report answer(String question, String context);

    /**
     * The working room of the model this expert runs on, so what the desk hands it is sized for the
     * reader rather than for the worker that asked (see {@code MaterialBudget}). The baseline
     * unless the expert knows its own model.
     */
    default MaterialBudget room() {
        return MaterialBudget.BASELINE;
    }

    /**
     * True when this expert searches the project's index for the question itself before it
     * answers, so the desk must not also hand it whole sources and documentation sections.
     */
    default boolean searchesForItself() {
        return false;
    }

    /** The narrow seam, for callers that only want the text. */
    @Override
    default String apply(String question, String context) {
        Report report = answer(question, context);
        return report.answered() ? report.text() : "";
    }
}
