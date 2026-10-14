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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 106, finding A: a session's owner may order the one tool the
 * next turn is to call. On the real agent runtime against a scripted server:
 *
 * <ul>
 *   <li>the owner is told each turn's calls and which of them got an answer the conversation
 *       already held whole, whatever its length;
 *   <li>the ordered turn is asked for with the request's tool choice naming that one tool, the
 *       tool list unchanged, and the notice at the end of the turn's last result;
 *   <li>the turn after it is asked as every turn is, and the repeated lookup itself was run
 *       and answered: nothing is refused;
 *   <li>a server that answers a named tool with an error is asked again at once without one,
 *       and the session goes on with the notice alone.
 * </ul>
 */
class ATurnOfRepeatsOrdersTheNextTurnTest {

    public static final class Tools {
        final List<String> looked = new ArrayList<>();
        final List<String> checked = new ArrayList<>();

        public String look(String what) {
            looked.add(what);
            return "about " + what;
        }

        public String checkDraft(String draft) {
            checked.add(draft);
            return "NO OBJECTIONS";
        }

        public String reportDone(String summary) {
            return summary;
        }
    }

    private static List<AgentRuntime.ToolBinding> bindings(Tools tools) throws Exception {
        return List.of(
            new AgentRuntime.ToolBinding("look", "Looks one thing up.", tools,
                Tools.class.getMethod("look", String.class)),
            new AgentRuntime.ToolBinding("check_draft", "Checks a draft.", tools,
                Tools.class.getMethod("checkDraft", String.class)),
            new AgentRuntime.ToolBinding(KoogAgentRuntime.DONE_TOOL, "Hands in.", tools,
                Tools.class.getMethod("reportDone", String.class)));
    }

    private static ScriptedModel.Step looks(String what) {
        return ScriptedModel.Step.call("look", "{\"what\":\"" + what + "\"}");
    }

    private static final String NOTICE = "NEXT-TURN-IS-THE-CHECK";

    /** Orders the check after a turn whose every call brought nothing new; records each turn. */
    private static AgentRuntime.SessionOptions ordering(List<List<AgentRuntime.TurnOrder.Made>> told) {
        return AgentRuntime.SessionOptions.NONE.ordering(turn -> {
            told.add(turn);
            return turn.stream().allMatch(AgentRuntime.TurnOrder.Made::heldWhole)
                ? Optional.of(new AgentRuntime.TurnOrder.Ordered("check_draft", NOTICE))
                : Optional.empty();
        });
    }

    private static final List<ScriptedModel.Step> SCRIPT = List.of(
        looks("a"),
        looks("a"),
        ScriptedModel.Step.call("check_draft", "{\"draft\":\"{}\"}"),
        looks("b"),
        ScriptedModel.Step.call(KoogAgentRuntime.DONE_TOOL, "{\"summary\":\"done\"}"));

    @Test
    void theTurnAfterATurnOfRepeatsIsAskedForWithTheOneToolNamedAndTheNextAsAlways()
            throws Exception {
        Tools tools = new Tools();
        List<List<AgentRuntime.TurnOrder.Made>> told = new ArrayList<>();
        try (ScriptedModel server = new ScriptedModel(SCRIPT);
             AgentRuntime.AgentSession session = server.open(bindings(tools), ordering(told))) {
            AgentRuntime.SessionResult result = session.run("Begin.");

            assertThat(result.completed()).isTrue();
            assertThat(server.requests).hasSize(5);
            assertThat(tools.looked).as("the repeated lookup was run and answered: an order "
                + "refuses nothing").containsExactly("a", "a", "b");
            assertThat(tools.checked).hasSize(1);

            assertThat(told).as("the owner is told every turn but the one that handed in")
                .hasSize(4);
            assertThat(told.get(0)).extracting(AgentRuntime.TurnOrder.Made::heldWhole)
                .containsExactly(false);
            assertThat(told.get(1)).as("the same call, the same answer, still whole - and far "
                + "shorter than the answers that are replaced by a line").singleElement()
                .satisfies(made -> {
                    assertThat(made.tool()).isEqualTo("look");
                    assertThat(made.heldWhole()).isTrue();
                });
            assertThat(told.get(3)).extracting(AgentRuntime.TurnOrder.Made::heldWhole)
                .containsExactly(false);

            JsonNode ordered = server.requests.get(2);
            assertThat(ordered.path("tool_choice").path("function").path("name").asText())
                .as("the ordered turn may call the one tool only").isEqualTo("check_draft");
            assertThat(ordered.path("tools")).as("the tool list is the session's own: every "
                + "lookup is still in it").isEqualTo(server.requests.get(1).path("tools"));
            assertThat(server.lastOf(3)).as("the role is told, at the end of the turn's last "
                + "result").contains("about a").endsWith("[" + NOTICE + "]");

            assertThat(server.requests.get(1).path("tool_choice").isObject()).isFalse();
            assertThat(server.requests.get(3).path("tool_choice").isObject())
                .as("the turn after the check is asked as every turn is").isFalse();
            assertThat(server.requests.get(4).path("tool_choice").isObject()).isFalse();
            assertThat(server.lastOf(5)).contains("about b").doesNotContain(NOTICE);
        }
    }

    @Test
    void aServerThatRefusesANamedToolIsAskedAgainWithoutOneAndTheSessionGoesOn()
            throws Exception {
        Tools tools = new Tools();
        List<List<AgentRuntime.TurnOrder.Made>> told = new ArrayList<>();
        try (ScriptedModel server = new ScriptedModel(SCRIPT)) {
            server.refusesANamedTool = true;
            try (AgentRuntime.AgentSession session = server.open(bindings(tools), ordering(told))) {
                AgentRuntime.SessionResult result = session.run("Begin.");

                assertThat(result.completed()).as("%s, refused %d, asked %d", result.killReason(),
                    server.refused.size(), server.requests.size()).isTrue();
                assertThat(server.refused).as("asked once with the tool named").hasSize(1);
                assertThat(server.requests).as("and then as every turn is asked").hasSize(5);
                assertThat(server.lastOf(3)).as("the notice alone says what is due")
                    .endsWith("[" + NOTICE + "]");
                assertThat(server.messagesOf(3).toString()).as("the results were put into "
                    + "the conversation once").containsOnlyOnce(NOTICE);
                assertThat(tools.looked).containsExactly("a", "a", "b");
            }
        }
    }
}
