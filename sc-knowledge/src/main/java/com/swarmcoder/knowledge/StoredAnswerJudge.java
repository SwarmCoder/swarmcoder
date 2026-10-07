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

import java.util.List;

/**
 * The first of the help desk's two steps (2026-10-04): before any paid research, something free
 * reads the question beside what the run already holds - the answers the expert researched
 * earlier in this run, and the best the project's own code offered - and says whether one of them
 * answers it. Only when none does is the expert asked.
 *
 * <p><b>Why.</b> Harness run 77: twelve researched answers, 6.6 million prompt tokens, and the
 * same facts researched again and again because the asker put them in new words. The desk decided
 * "is this the same question" by counting shared words, which calls two long questions about one
 * project the same when they are not and different when they are. Whether an answer answers a
 * question is a reading, so it is given to a model that reads - the workers' own local one, which
 * costs nothing - and the word count is no longer asked when one is wired.
 *
 * <p>A seam, like the escalation: null on a desk is exactly the behaviour before it existed.
 */
public interface StoredAnswerJudge {

    /**
     * One thing the run already holds that might answer the question.
     *
     * @param question the question it was researched for; "" for what the project's code offered
     * @param text     the answer, or the code and documentation found
     */
    record Candidate(String question, String text) {}

    /**
     * @return the index in {@code candidates} of the one that answers every part of
     *         {@code question}, or -1 when none does or the check could not be made
     */
    int whichAnswers(String question, List<Candidate> candidates);
}
