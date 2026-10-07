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

import com.swarmcoder.inference.VllmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link StoredAnswerJudge} on a model server that costs nothing: the workers' own local one.
 *
 * <p>One plain chat call per question, no tools: the question, then the stored answers numbered,
 * and the model replies with one number. The reply is read for that number and for nothing else;
 * anything that is not a number of a candidate is "none", and so is a call that fails - the
 * question then goes to the expert exactly as it did before this step existed.
 */
public final class LocalAnswerJudge implements StoredAnswerJudge {

    private static final Logger log = LoggerFactory.getLogger(LocalAnswerJudge.class);

    /** The role these calls are recorded under on the run's cost record. */
    static final String ROLE = "answer check";

    /**
     * How much of the stored material one check is sent, in characters, at most: about 15,000
     * tokens, which every local worker model serves. Candidates past it are left out, newest
     * kept - the desk hands them newest first.
     */
    static final int MAX_MATERIAL_CHARS = 60_000;

    static final String SYSTEM = """
        You check whether a question about a software project has ALREADY been answered. You are \
        given the question and a numbered list of stored answers. Each stored answer was read out \
        of the project's files; some carry the question they were first written for.

        Decide whether ONE stored answer settles the question: every part of it, with the fact \
        stated in the stored answer itself, not merely a related subject. The wording of the \
        question does not matter - two questions in different words can ask for the same fact, \
        and two questions that share most of their words can ask for different facts. A stored \
        answer that covers only some parts of the question does not settle it.

        Reply with one line and nothing else: ANSWERED BY <number> when one stored answer \
        settles the question, or ANSWERED BY 0 when none does. When in doubt, 0.\
        """;

    private static final Pattern VERDICT = Pattern.compile("ANSWERED BY\\s*#?\\s*(\\d+)");

    private final VllmClient client;

    /** @param client the local worker model's client */
    public LocalAnswerJudge(VllmClient client) {
        this.client = client;
    }

    @Override
    public int whichAnswers(String question, List<Candidate> candidates) {
        if (client == null || question == null || question.isBlank() || candidates == null
                || candidates.isEmpty()) {
            return -1;
        }
        String prompt = prompt(question, candidates);
        try (Stream<String> reply = client.as(ROLE).chatCompletionStream(List.of(
                Map.of("role", "system", "content", SYSTEM),
                Map.of("role", "user", "content", prompt)), null, 0.0)) {
            int chosen = verdict(reply.collect(Collectors.joining()), candidates.size());
            log.info("the local check of {} stored answer(s) for '{}' found {}", candidates.size(),
                question.length() <= 80 ? question : question.substring(0, 80) + "...",
                chosen < 0 ? "none that answers it" : "that number " + (chosen + 1) + " answers it");
            return chosen;
        } catch (Exception e) {                                            // noqa
            log.warn("the local check of stored answers could not be made ({}); the question "
                + "goes on as if there were none", e.toString());
            return -1;
        }
    }

    /** The question and the numbered candidates, as the model is sent them. */
    static String prompt(String question, List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder("## The question\n").append(question.strip())
            .append("\n\n## Stored answers\n");
        int room = MAX_MATERIAL_CHARS;
        for (int i = 0; i < candidates.size() && room > 0; i++) {
            Candidate candidate = candidates.get(i);
            String text = candidate.text() == null ? "" : candidate.text();
            if (text.length() > room) {
                text = text.substring(0, room);
            }
            room -= text.length();
            sb.append("\n### Stored answer ").append(i + 1).append('\n');
            if (candidate.question() != null && !candidate.question().isBlank()) {
                sb.append("First written for the question: ").append(candidate.question().strip())
                    .append('\n');
            }
            sb.append(text).append('\n');
        }
        return sb.append("\nReply with one line: ANSWERED BY <number>, or ANSWERED BY 0.").toString();
    }

    /**
     * The index the reply names, or -1. The LAST verdict in the reply counts: a model that
     * reasons aloud first states its conclusion at the end.
     */
    static int verdict(String reply, int candidates) {
        if (reply == null) {
            return -1;
        }
        Matcher matcher = VERDICT.matcher(reply);
        int chosen = -1;
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                chosen = number >= 1 && number <= candidates ? number - 1 : -1;
            } catch (NumberFormatException e) {                            // noqa
                chosen = -1;
            }
        }
        return chosen;
    }
}
