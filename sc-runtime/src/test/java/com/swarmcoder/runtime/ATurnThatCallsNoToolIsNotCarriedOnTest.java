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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn in which the model calls no tool and hands nothing in is answered with a reminder, which
 * is a whole request more (section 98). On the real agent runtime against a scripted server:
 *
 * <ul>
 *   <li>the request that carries the reminder is the one before it with two messages more, so
 *       the server has read everything else in it already;
 *   <li>once the model calls a tool again, the turn that did nothing and the reminder are in no
 *       later request;
 *   <li>a session is reminded {@link KoogAgentRuntime#MAX_TURNS_WITHOUT_A_CALL} times at most,
 *       also when it calls a tool in between; the next such turn ends it, with what the model
 *       said as its answer;
 *   <li>two such turns in a row end it, as they always did.
 * </ul>
 */
class ATurnThatCallsNoToolIsNotCarriedOnTest {

    public static final class Tools {
        public String look(String what) {
            return "about " + what;
        }
    }

    private static List<AgentRuntime.ToolBinding> tools() throws Exception {
        return List.of(new AgentRuntime.ToolBinding("look", "Looks one thing up.", new Tools(),
            Tools.class.getMethod("look", String.class)));
    }

    private static ScriptedModel.Step looks(String what) {
        return ScriptedModel.Step.call("look", "{\"what\":\"" + what + "\"}");
    }

    private static List<JsonNode> first(JsonNode messages, int count) {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(messages.get(i));
        }
        return out;
    }

    @Test
    void theReminderCostsOneRequestThatAddsTwoMessagesAndLeavesNothingBehind() throws Exception {
        assertThat(KoogAgentRuntime.MAX_TURNS_WITHOUT_A_CALL).isEqualTo(3);
        List<ScriptedModel.Step> script = List.of(
            looks("a"),
            ScriptedModel.Step.says("FIRST-IDLE let me think about it"),
            looks("b"),
            looks("c"),
            ScriptedModel.Step.says("SECOND-IDLE still thinking"),
            looks("d"),
            ScriptedModel.Step.says("THIRD-IDLE and again"),
            looks("e"),
            ScriptedModel.Step.says("FOURTH-IDLE I am finished."),
            looks("never asked for"));
        try (ScriptedModel server = new ScriptedModel(script);
             AgentRuntime.AgentSession session = server.open(tools())) {
            AgentRuntime.SessionResult result = session.run("Begin.");

            assertThat(result.completed()).isTrue();
            assertThat(result.finalOutput()).as("the fourth turn without a call ends the "
                + "session; what it said is its answer").isEqualTo("FOURTH-IDLE I am finished.");
            assertThat(server.requests).as("no fourth reminder is sent").hasSize(9);

            List<Integer> reminders = new ArrayList<>();
            for (int turn = 1; turn <= server.requests.size(); turn++) {
                if (server.lastOf(turn).equals("user:" + KoogAgentRuntime.USE_YOUR_TOOLS)) {
                    reminders.add(turn);
                }
            }
            assertThat(reminders).containsExactly(3, 6, 8);

            JsonNode second = server.messagesOf(2);
            JsonNode reminding = server.messagesOf(3);
            assertThat(reminding.size()).isEqualTo(second.size() + 2);
            assertThat(first(reminding, second.size()))
                .as("everything before the turn and its reminder is what was sent before")
                .isEqualTo(first(second, second.size()));
            assertThat(reminding.get(second.size()).path("content").asText())
                .startsWith("FIRST-IDLE");

            JsonNode after = server.messagesOf(4);
            assertThat(after.toString()).as("the model went on to call a tool: neither the turn "
                + "that did nothing nor the reminder is sent again")
                .doesNotContain("FIRST-IDLE").doesNotContain(KoogAgentRuntime.USE_YOUR_TOOLS);
            assertThat(first(after, second.size())).isEqualTo(first(second, second.size()));
            assertThat(after.get(after.size() - 1).path("content").asText())
                .contains("about b");
            assertThat(server.messagesOf(9).toString())
                .doesNotContain("SECOND-IDLE").doesNotContain("THIRD-IDLE")
                .doesNotContain(KoogAgentRuntime.USE_YOUR_TOOLS);
            assertThat(session.canContinue()).isTrue();
        }
    }

    @Test
    void twoTurnsInARowWithoutACallEndTheSessionAsBefore() throws Exception {
        List<ScriptedModel.Step> script = List.of(
            ScriptedModel.Step.says("Here is my answer."),
            ScriptedModel.Step.says("Here it is again."));
        try (ScriptedModel server = new ScriptedModel(script);
             AgentRuntime.AgentSession session = server.open(tools())) {
            AgentRuntime.SessionResult result = session.run("Begin.");

            assertThat(result.completed()).isTrue();
            assertThat(result.finalOutput()).isEqualTo("Here it is again.");
            assertThat(server.requests).hasSize(2);
            assertThat(server.messagesOf(2).toString()).as("the answer the reminder asks about "
                + "is in the request that carries it").contains("Here is my answer.");
        }
    }
}
