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
 * Proves, with no live model and no running Koog agent, the one guarantee the live probe cannot
 * show directly by reading the wire: a turn's reasoning never survives into the next request.
 *
 * <p>Thinking now defaults on (see {@code ModelQuirks#thinking}), so a normal turn's assistant
 * message carries a {@link MessagePart.Reasoning} part alongside its text and tool calls. Reading
 * Koog 1.2.0's own {@code AbstractOpenAILLMClient.convertPromptToMessages} shows it replays that
 * part back to the server as {@code reasoning_content} on every later request that includes this
 * turn — a real reasoning-capable server never re-reads its own prior reasoning trace, so every
 * byte of that replay is prompt the server prefills for nothing. {@link KoogAgentRuntime
 * #withoutReasoningOnLastTurn} is what strips it out of the session's own history the instant it
 * arrives, and this is the direct proof that it does exactly that and nothing else.
 */
class KoogAgentRuntimeReasoningTest {

    private static Message.User user(String text) {
        return new Message.User(List.of(new MessagePart.Text(text, null)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    @Test
    void aReasoningPartOnTheLastAssistantTurnIsRemoved() {
        Message.Assistant turn = new Message.Assistant(List.of(
            new MessagePart.Reasoning(List.of("first I should read the file, then write it")),
            new MessagePart.Text("Reading the file now."),
            new MessagePart.Tool.Call("call-1", "read_file", "{\"path\":\"seed.txt\"}")
        ), ResponseMetaInfo.Companion.getEmpty(), null, null, null);

        List<Message> before = List.of(user("Begin now."), turn);
        List<Message> after = KoogAgentRuntime.withoutReasoningOnLastTurn(before);

        assertThat(after).hasSize(2);
        assertThat(after.get(0)).as("every earlier message is untouched").isSameAs(before.get(0));
        Message.Assistant rewritten = (Message.Assistant) after.get(1);
        assertThat(rewritten.getParts())
            .as("the reasoning part is gone")
            .noneMatch(part -> part instanceof MessagePart.Reasoning);
        assertThat(rewritten.getParts())
            .as("everything else on the turn survives, in order")
            .filteredOn(part -> part instanceof MessagePart.Text)
            .hasSize(1);
        assertThat(rewritten.getParts())
            .filteredOn(part -> part instanceof MessagePart.Tool.Call)
            .hasSize(1);
        // The ORIGINAL turn object is untouched — Koog's data classes are immutable, and any code
        // still holding a reference to it (the KoogSession loop itself, reading the same turn's
        // text and tool calls before this ever runs) must keep seeing the reasoning-bearing turn
        // it actually received, not a rewritten copy.
        assertThat(turn.getParts()).anyMatch(part -> part instanceof MessagePart.Reasoning);
    }

    @Test
    void aTurnWithNoReasoningIsReturnedUnchanged() {
        Message.Assistant turn = new Message.Assistant(List.of(
            new MessagePart.Text("All done."),
            new MessagePart.Tool.Call("call-1", "report_done", "{\"summary\":\"ok\"}")
        ), ResponseMetaInfo.Companion.getEmpty(), null, null, null);
        List<Message> before = List.of(user("Begin now."), turn);

        List<Message> after = KoogAgentRuntime.withoutReasoningOnLastTurn(before);

        assertThat(after).as("nothing to strip: the same list instance comes back").isSameAs(before);
    }

    @Test
    void aHistoryEndingOnAToolResultIsReturnedUnchanged() {
        // The last message is the USER turn carrying the tool result, not an assistant message —
        // this is the shape right after ctx.sendToolResults(...), before the next assistant reply
        // exists. Nothing to strip; nothing should be touched.
        Message.Assistant turn = new Message.Assistant(List.of(
            new MessagePart.Reasoning(List.of("thinking")),
            new MessagePart.Tool.Call("call-1", "read_file", "{}")
        ), ResponseMetaInfo.Companion.getEmpty(), null, null, null);
        Message.User toolResult = new Message.User(
            List.of(new MessagePart.Tool.Result("call-1", "read_file", "contents", false)),
            RequestMetaInfo.Companion.getEmpty(), null);
        List<Message> before = List.of(user("Begin now."), turn, toolResult);

        List<Message> after = KoogAgentRuntime.withoutReasoningOnLastTurn(before);

        assertThat(after).as("the last message is not an assistant turn: nothing to do")
            .isSameAs(before);
        // The reasoning on the EARLIER assistant turn is left alone by this call — it is stripped
        // the moment IT was the last turn, one call earlier in the real loop; this method only
        // ever looks at the newest message, which is exactly what makes it cheap to call every turn.
        assertThat(((Message.Assistant) after.get(1)).getParts())
            .anyMatch(part -> part instanceof MessagePart.Reasoning);
    }

    @Test
    void anEmptyHistoryIsReturnedUnchanged() {
        List<Message> before = List.of();
        assertThat(KoogAgentRuntime.withoutReasoningOnLastTurn(before)).isSameAs(before);
    }
}
