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

import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a thinking model thought before an answer is never in a later request of the same session
 * (section 94), read from the requests as they went out to a scripted endpoint.
 *
 * <p>Build of 2026-10-13: on a paid server the input a call was counted for grew by what the
 * model had thought in every earlier call of the session - about 36% of the architect's 4.6
 * million tokens and half of the reviewer's 9.1 million - although the conversation the session
 * held never contained it. This pins this side of the wire: with tool calls sent natively, as
 * the hosted model's shape sends them, and as text, a session's requests carry the answers and
 * the tool results and none of the thinking. What the server then counts is in the run's report
 * ({@code UsageReport.warnings}: characters sent for each input token counted).
 */
class ASessionNeverSendsItsThinkingBackTest {

    public static final class Tools {
        public String look(String what) {
            return "nothing about " + what;
        }
    }

    private static void runAndRead(ModelQuirks quirks) throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        // Each turn speaks, calls two tools at once and "thought" first, as a thinking model does.
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> turn <= 3
                ? ScriptedExpertEndpoint.saying("Let me look.",
                    ScriptedExpertEndpoint.Reply.toolCalls("look",
                        "{\"what\":\"a" + turn + "\"}", "{\"what\":\"b" + turn + "\"}"))
                : ScriptedExpertEndpoint.Reply.text("That is all I found."))
                .thinking(turn -> "THOUGHT-OF-TURN-" + turn + ", at some length")) {
            AgentRuntime.ToolBinding look = new AgentRuntime.ToolBinding("look",
                "Looks one thing up.", new Tools(), Tools.class.getMethod("look", String.class));
            AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("architect",
                "You look things up.", new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), "",
                    "expert-fake", 32_000, quirks),
                0.2, 10, List.of(look), new TurnMeter(gate, new AtomicLong(), null));
            try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
                session.run("Begin.");
            }

            assertThat(endpoint.turnsServed())
                .as("three lookups, an answer, a nudge and an answer").isEqualTo(5);
            for (int turn = 2; turn <= endpoint.turnsServed(); turn++) {
                assertThat(endpoint.request(turn))
                    .as("request %s holds the earlier results and none of the earlier thinking",
                        turn)
                    .contains("nothing about a" + Math.min(turn - 1, 3))
                    .doesNotContain("THOUGHT-OF-TURN").doesNotContain("reasoning");
            }
        }
    }

    @Test
    void withToolCallsSentNativelyAsTheHostedModelsShapeSendsThem() throws Exception {
        ModelQuirks hosted = ModelShapes.get("deepseek-v4-flash-api");
        assertThat(hosted.textualToolHistory()).isFalse();
        runAndRead(hosted);
    }

    @Test
    void withToolCallsSentAsText() throws Exception {
        assertThat(ModelQuirks.DEFAULTS.textualToolHistory()).isTrue();
        runAndRead(ModelQuirks.DEFAULTS.withLabel("expert-fake"));
    }
}
