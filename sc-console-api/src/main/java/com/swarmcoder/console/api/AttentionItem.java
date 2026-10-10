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
package com.swarmcoder.console.api;

import java.util.List;

/**
 * The one thing that most needs the supervisor now, written to be read alone.
 *
 * <p>Short on purpose: a supervising model pays for every character it is sent, on every wake. So
 * an item names its project and story in words, puts the question in one place, lists the answers
 * that will be acted on, and carries only as much evidence as it takes to choose between them. The
 * ids are there to answer with, never to be looked up before the item can be understood.
 *
 * <p>Not a wire model: it never travels to the browser, so it is a plain record.
 *
 * @param kind       what sort of thing is waiting, e.g. {@code DELIVERY} or {@code RUN_QUESTION}
 * @param project    the project's name
 * @param story      the story it concerns as "S3 Title", or "" when it concerns none
 * @param question   what is being asked, in full sentences
 * @param options    the answers that will be acted on, each as "token: what it does"
 * @param evidence   what is known that bears on the answer; "" when nothing is
 * @param answerWith the tool call that answers it, with the ids already filled in
 */
public record AttentionItem(String kind, String project, String story, String question,
                            List<String> options, String evidence, String answerWith) {

    /** The total the texts of one item are held to, so the whole reply stays near 1,500 characters. */
    public static final int MAX_CHARS = 1_300;

    public AttentionItem {
        kind = kind == null ? "" : kind;
        project = project == null ? "" : project;
        story = story == null ? "" : story;
        question = question == null ? "" : question;
        options = options == null ? List.of() : List.copyOf(options);
        evidence = evidence == null ? "" : evidence;
        answerWith = answerWith == null ? "" : answerWith;
    }

    /** How many characters of text this item carries. */
    public int chars() {
        int total = kind.length() + project.length() + story.length() + question.length()
            + evidence.length() + answerWith.length();
        for (String option : options) {
            total += option.length();
        }
        return total;
    }

    /**
     * The same item cut to {@link #MAX_CHARS}: evidence goes first, then the end of the question.
     * The options and the way to answer are never cut, because an item that cannot be answered is
     * worth nothing.
     */
    public AttentionItem fitted() {
        int over = chars() - MAX_CHARS;
        if (over <= 0) {
            return this;
        }
        String shortEvidence = cut(evidence, Math.max(0, evidence.length() - over));
        over -= evidence.length() - shortEvidence.length();
        String shortQuestion = over <= 0 ? question
            : cut(question, Math.max(80, question.length() - over));
        return new AttentionItem(kind, project, story, shortQuestion, options, shortEvidence,
            answerWith);
    }

    private static String cut(String text, int keep) {
        if (text.length() <= keep) {
            return text;
        }
        return keep <= 1 ? "" : text.substring(0, keep - 1).stripTrailing() + "…";
    }
}
