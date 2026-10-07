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

import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import ai.koog.prompt.message.RequestMetaInfo;
import ai.koog.prompt.message.ResponseMetaInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 59: a lookup repeated while its earlier answer is still whole in
 * the conversation, and has not changed, is answered with one line; anything else is sent whole.
 */
class ARepeatedLookupWhoseAnswerIsStillHeldIsSentAsOneLineTest {

    private static final String ARGS = "{\"path\":\"pom.xml\"}";
    private static final String ANSWER = "<project>" + "x".repeat(900) + "</project>";

    private static Message call(String id) {
        return call(id, ARGS);
    }

    private static Message call(String id, String args) {
        return new Message.Assistant(List.of(new MessagePart.Tool.Call(id, "read_file", args)),
            ResponseMetaInfo.Companion.getEmpty(), null, null, null);
    }

    private static Message result(String id, String output) {
        return new Message.User(List.of(new MessagePart.Tool.Result(id, "read_file", output, false)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    private static List<Message> held(String output) {
        List<Message> messages = new ArrayList<>();
        messages.add(call("c1"));
        messages.add(result("c1", output));
        messages.add(call("c2", "{\"path\":\"other.xml\"}"));
        messages.add(result("c2", "short answer"));
        messages.add(call("c3")); // the turn being answered: its result is not in yet
        return messages;
    }

    @Test
    void anAnswerStillHeldWholeAndUnchangedIsSentAsOneLineSayingHowFarBackItIs() {
        String line = HistoryTrim.unchangedRepeat(held(ANSWER), "read_file", ARGS, ANSWER);

        assertThat(line).startsWith(HistoryTrim.UNCHANGED_MARK)
            .contains("from your call 2 turns ago").hasSizeLessThan(150);
    }

    @Test
    void theTurnCountdownNoteIsKeptAndDoesNotMakeTheAnswerDiffer() {
        String line = HistoryTrim.unchangedRepeat(held(ANSWER + "\n\n[3 turns left]"), "read_file",
            ARGS, ANSWER + "\n\n[2 turns left]");

        assertThat(line).startsWith(HistoryTrim.UNCHANGED_MARK).endsWith("\n\n[2 turns left]");
    }

    @Test
    void anAnswerThatChangedIsSentWhole() {
        assertThat(HistoryTrim.unchangedRepeat(held(ANSWER), "read_file", ARGS,
            ANSWER.replace("x", "y"))).isNull();
    }

    @Test
    void anEarlierAnswerThatWasTidiedAwayIsSentWholeAgain() {
        List<Message> tidied = held(HistoryTrim.ELIDED_MARK + " 900 characters, it began: <project>");

        assertThat(HistoryTrim.unchangedRepeat(tidied, "read_file", ARGS, ANSWER)).isNull();
    }

    @Test
    void anEarlierAnswerThatIsGoneFromTheConversationIsSentWholeAgain() {
        List<Message> compacted = new ArrayList<>(held(ANSWER).subList(2, 5));

        assertThat(HistoryTrim.unchangedRepeat(compacted, "read_file", ARGS, ANSWER)).isNull();
    }

    @Test
    void aShortAnswerIsSentAgainBecauseTheLineSayingSoWouldBeAsLong() {
        List<Message> messages = held("tiny");

        assertThat(HistoryTrim.unchangedRepeat(messages, "read_file", ARGS, "tiny")).isNull();
    }

    @Test
    void aDifferentCallIsNotAnsweredFromAnotherCallsResult() {
        assertThat(HistoryTrim.unchangedRepeat(held(ANSWER), "read_file",
            "{\"path\":\"other.xml\"}", ANSWER)).isNull();
    }

    @Test
    void aLineSayingUnchangedDoesNotMakeTheWholeAnswerItPointsToTheFirstThingATidyCuts() {
        List<Message> messages = held(ANSWER);
        messages.add(result("c3", HistoryTrim.UNCHANGED_MARK + ", from your call 2 turns ago."));

        assertThat(HistoryTrim.supersededCalls(messages)).doesNotContain("c1");
        assertThat(HistoryTrim.askedAgainCalls(messages)).doesNotContain("c3");
        // and a later repeat still finds the first, whole answer, not the line
        messages.add(call("c4"));
        assertThat(HistoryTrim.unchangedRepeat(messages, "read_file", ARGS, ANSWER))
            .contains("from your call 3 turns ago");
    }
}
