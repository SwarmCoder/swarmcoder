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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A conversation kept after a role handed in ends on the hand-in call, which has no answer in the
 * history (the hand-in tool ends the loop before its result is sent back). The next user message
 * may not follow an unanswered call, so that last turn is left off; a turn that only spoke stays.
 */
class AConversationEndedOnAHandInCanBeContinuedTest {

    private static Message.User user(String text) {
        return new Message.User(List.of(new MessagePart.Text(text, null)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    private static Message.Assistant assistant(MessagePart.ResponsePart... parts) {
        return new Message.Assistant(List.of(parts), ResponseMetaInfo.Companion.getEmpty(), null,
            null, null);
    }

    @Test
    void theHandInCallWithNoAnswerIsLeftOff() {
        Message.Assistant lookup = assistant(
            new MessagePart.Tool.Call("c1", "read_file", "{\"path\":\"a\"}"));
        Message.Assistant handIn = assistant(new MessagePart.Text("Done."),
            new MessagePart.Tool.Call("c2", "report_done", "{\"finalJson\":\"\"}"));
        List<Message> before = List.of(user("Begin."), lookup, user("file text"), handIn);

        List<Message> after = KoogAgentRuntime.withoutUnansweredCalls(before);

        assertThat(after).containsExactly(before.get(0), before.get(1), before.get(2));
        assertThat(before).as("the original list is untouched").hasSize(4);
    }

    @Test
    void aTurnThatOnlySpokeStays() {
        List<Message> before = List.of(user("Begin."), assistant(new MessagePart.Text("Here it is.")));

        assertThat(KoogAgentRuntime.withoutUnansweredCalls(before)).isSameAs(before);
    }

    @Test
    void aHistoryThatEndsOnTheUsersTurnIsUnchanged() {
        List<Message> before = List.of(user("Begin."));

        assertThat(KoogAgentRuntime.withoutUnansweredCalls(before)).isSameAs(before);
        assertThat(KoogAgentRuntime.withoutUnansweredCalls(List.of())).isEmpty();
    }
}
