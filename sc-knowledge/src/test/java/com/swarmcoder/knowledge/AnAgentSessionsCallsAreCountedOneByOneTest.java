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

import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.CloudGate.Cap;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.CloudGate.Where;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.UsageLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 93 (live supervised build, 2026-10-13): the planning roles ran on a paid model, the
 * architect alone made 51 calls, and the build was reported as "1163749 in, 2369 out". A role
 * that works as an agent with tools was charged the growth of its conversation's size, all of it
 * as input, so none of what it wrote was ever counted as output.
 *
 * <p>The real stack runs here - the real agent runtime, the real turn guard, the real gate and
 * ledger - against a scripted endpoint that reports 100 prompt and 20 completion tokens for every
 * call, as a model server does. No model is called.
 */
class AnAgentSessionsCallsAreCountedOneByOneTest {

    /** The one tool the scripted session calls. */
    public static final class Tools {
        /** Whose work the thread that ran the tool was doing, each time it was called. */
        final List<Where> ranFor = new java.util.concurrent.CopyOnWriteArrayList<>();

        public String look(String what) {
            ranFor.add(CloudGate.current());
            return "nothing about " + what;
        }
    }

    private final Tools tools = new Tools();

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @AfterEach
    void noRecorderIsLeftBehind() {
        RunMeter.recordTo(null);
    }

    /** Two turns that look something up, then the model only speaks, which ends the session. */
    private static ScriptedExpertEndpoint endpoint() throws Exception {
        return new ScriptedExpertEndpoint(turn -> turn <= 2
            ? ScriptedExpertEndpoint.Reply.toolCall("look", "{\"what\":\"turn " + turn + "\"}")
            : ScriptedExpertEndpoint.Reply.text("That is all I found."));
    }

    private AgentRuntime.SessionResult runArchitect(ScriptedExpertEndpoint endpoint,
                                                    CloudGate gate) throws Exception {
        AgentRuntime.ToolBinding look = new AgentRuntime.ToolBinding("look",
            "Looks one thing up.", tools, Tools.class.getMethod("look", String.class));
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("architect",
            "You look things up.", new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), "",
                "expert-fake", 32_000, ModelQuirks.DEFAULTS.withLabel("expert-fake")),
            0.2, 10, List.of(look), new TurnMeter(gate, new AtomicLong(), null));
        try (var in = CloudGate.enter(new Where(project, story, run));
             AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
            return session.run("Begin.");
        }
    }

    @Test
    void everyCallOfASessionIsRecordedWithItsOwnInputAndOutput() throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        UsageLedger ledger = new UsageLedger(gate, null, address -> false); // every server is paid
        RunMeter.recordTo(ledger);

        try (ScriptedExpertEndpoint endpoint = endpoint()) {
            AgentRuntime.SessionResult result = runArchitect(endpoint, gate);

            int calls = endpoint.turnsServed();
            assertThat(result.killReason()).isEmpty();
            assertThat(calls).as("two lookups, an answer, a nudge and an answer").isEqualTo(4);

            RunUsageRecord usage = ledger.usageOf(run);
            assertThat(usage.models()).hasSize(1);
            ModelUsageRecord architect = usage.models().get(0);
            assertThat(architect.role()).isEqualTo("architect");
            assertThat(architect.paid()).isTrue();
            assertThat(architect.calls()).as("one record per call, not one per session")
                .isEqualTo(calls);
            assertThat(architect.inputTokens()).isEqualTo(100L * calls);
            assertThat(architect.outputTokens())
                .as("what the session wrote is output, call by call").isEqualTo(20L * calls);
            assertThat(architect.sizedCalls())
                .as("and beside the server's count, the size of each request as it went out")
                .isEqualTo(calls);
            assertThat(architect.sizedInputTokens()).isEqualTo(100L * calls);
            assertThat(architect.requestChars()).isGreaterThan(300L * calls);
            assertThat(architect.repeatCalls()).as("every call after the first")
                .isEqualTo(calls - 1);
            assertThat(architect.samePrefixChars())
                .as("a session's request begins as its last one did: what a prompt cache serves")
                .isBetween(100L * (calls - 1), architect.repeatRequestChars());

            assertThat(gate.spendOfRun(run))
                .as("and the budget holds the same figures: before, this session was charged "
                    + "about one call's size as input and nothing as output")
                .isEqualTo(new CloudGate.Spend(100L * calls, 20L * calls));
        }
    }

    /**
     * The framework runs a session's loop on a thread of its own pool. Such a thread carries the
     * scope of whichever thread first created it, so a later run's session was counted for an
     * earlier run, or for none.
     */
    @Test
    void aSecondRunsSessionIsCountedForTheSecondRunAndItsToolsRunForIt() throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        UsageLedger ledger = new UsageLedger(gate, null, address -> false);
        RunMeter.recordTo(ledger);
        UUID earlier = UUID.randomUUID();

        try (ScriptedExpertEndpoint first = endpoint(); ScriptedExpertEndpoint second = endpoint()) {
            AgentRuntime.ToolBinding look = new AgentRuntime.ToolBinding("look",
                "Looks one thing up.", new Tools(), Tools.class.getMethod("look", String.class));
            try (var in = CloudGate.enter(new Where(project, story, earlier));
                 AgentRuntime.AgentSession session = new KoogAgentRuntime().open(
                     new AgentRuntime.SessionSpec("planner", "You look things up.",
                         new AgentRuntime.ModelEndpoint(first.baseUrl(), "", "expert-fake", 32_000,
                             ModelQuirks.DEFAULTS.withLabel("expert-fake")),
                         0.2, 10, List.of(look), new TurnMeter(gate, new AtomicLong(), null)))) {
                session.run("Begin.");
            }

            runArchitect(second, gate);

            assertThat(gate.spendOfRun(earlier)).isEqualTo(new CloudGate.Spend(400, 80));
            assertThat(gate.spendOfRun(run)).isEqualTo(new CloudGate.Spend(400, 80));
            assertThat(ledger.usageOf(run).models()).extracting(ModelUsageRecord::role)
                .containsExactly("architect");
            assertThat(tools.ranFor).as("each lookup ran as the work of the session's own run")
                .hasSize(2).containsOnly(new Where(project, story, run));
        }
    }

    @Test
    void theSameSessionWithoutTheLedgerShowsWhatWasWrong() throws Exception {
        // The gate as every run had it until now: no ledger, so the turn guard charges what the
        // session reports each turn - the size of its latest call - by its growth.
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });

        try (ScriptedExpertEndpoint endpoint = endpoint()) {
            runArchitect(endpoint, gate);

            assertThat(endpoint.turnsServed()).isEqualTo(4);
            assertThat(gate.spendOfRun(run).output())
                .as("no output was ever counted for a session").isZero();
            assertThat(gate.spendOfRun(run).input())
                .as("and four calls of 100 in and 20 out were charged as one call's 120")
                .isEqualTo(120);
        }
    }

    @Test
    void aSessionIsStoppedOnTheTurnThatPassesAnOutputLimit() throws Exception {
        List<CloudGate.Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(new Limits(new Cap(0, 0, 30), Cap.NONE, Cap.NONE),
            raised::add);
        gate.countFromUsage();
        RunMeter.recordTo(new UsageLedger(gate, null, address -> false));

        try (ScriptedExpertEndpoint endpoint = endpoint()) {
            AgentRuntime.SessionResult result = runArchitect(endpoint, gate);

            assertThat(result.killReason()).contains(KillReason.BUDGET_EXCEEDED);
            assertThat(endpoint.turnsServed())
                .as("20 out, then 40: over 30 after its second call, and no third is sent")
                .isEqualTo(2);
            assertThat(raised).hasSize(1);
            assertThat(raised.get(0).direction()).isEqualTo(CloudGate.Direction.OUTPUT);
            assertThat(raised.get(0).runId()).isEqualTo(run);
        }
    }

    @Test
    void aCallThatAsksOneQuestionIsRecordedWithItsRoleAndALocalServerIsNotCharged()
            throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.text("The second one, because it compiles."))) {
            // This time the scripted endpoint is the operator's own server.
            UsageLedger ledger = new UsageLedger(gate, null,
                UsageLedger.normalized(endpoint.baseUrl())::equals);
            RunMeter.recordTo(ledger);
            VllmClient client = new VllmClient(endpoint.baseUrl(), "", "expert-fake",
                ModelQuirks.DEFAULTS.withLabel("expert-fake"));

            String answer;
            try (var in = CloudGate.enter(new Where(project, story, run));
                 var lines = client.as("judge").chatCompletionStream(
                     List.of(Map.of("role", "user", "content", "Which is better?")), null, 0.0)) {
                answer = lines.collect(Collectors.joining());
            }

            assertThat(endpoint.everythingSent())
                .as("a recorded call asks the server for its usage").contains("include_usage");
            ModelUsageRecord judge = ledger.usageOf(run).models().get(0);
            assertThat(judge.role()).isEqualTo("judge");
            assertThat(judge.paid()).isFalse();
            assertThat(judge.calls()).isEqualTo(1);
            assertThat(judge.estimatedCalls())
                .as("this endpoint's stream carries no usage, so the call is counted from the "
                    + "size of its text and says so").isEqualTo(1);
            assertThat(judge.inputTokens()).isGreaterThan(0);
            assertThat(judge.outputTokens()).isEqualTo(Math.max(1, answer.length() / 4));
            assertThat(gate.spendOfRun(run)).as("local tokens are not cloud spend")
                .isEqualTo(new CloudGate.Spend(0, 0));
        }
    }

    /**
     * Section 98: beside the sums, each call of a session is kept by itself with what it was
     * for and its thinking; a server that gives no count of the thinking has it estimated from
     * the length of what it thought.
     */
    @Test
    void eachCallOfASessionIsKeptWithWhatItWasForAndItsThinking() throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        try (ScriptedExpertEndpoint endpoint = endpoint()) {
            endpoint.thinking(turn -> "x".repeat(400 * turn));
            UsageLedger ledger = new UsageLedger(gate, null,
                UsageLedger.normalized(endpoint.baseUrl())::equals); // the operator's own server
            RunMeter.recordTo(ledger);

            runArchitect(endpoint, gate);

            int calls = endpoint.turnsServed();
            RunUsageRecord usage = ledger.usageOf(run);
            assertThat(usage.calls()).hasSize(calls);
            assertThat(usage.calls()).allSatisfy(call -> {
                assertThat(call.role()).isEqualTo("architect");
                assertThat(call.purpose()).isEqualTo("a turn of the session architect");
                assertThat(call.paid()).isFalse();
                assertThat(call.input()).isEqualTo(100);
                assertThat(call.output()).isEqualTo(20);
            });
            assertThat(usage.calls().get(0).thinking())
                .as("400 characters of thinking, counted by nobody: about 100 tokens")
                .isEqualTo(100);
            assertThat(usage.calls().get(1).thinking()).isEqualTo(200);
            assertThat(usage.models().get(0).thinkingCalls()).isEqualTo(calls);
            assertThat(usage.models().get(0).estimatedThinkingCalls()).isEqualTo(calls);
        }
    }

    @Test
    void aCallThatAsksOneQuestionSaysWhatItIsFor() throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn ->
                ScriptedExpertEndpoint.Reply.text("The second one."))) {
            UsageLedger ledger = new UsageLedger(null, null,
                UsageLedger.normalized(endpoint.baseUrl())::equals);
            RunMeter.recordTo(ledger);
            VllmClient client = new VllmClient(endpoint.baseUrl(), "", "expert-fake",
                ModelQuirks.DEFAULTS.withLabel("expert-fake"));
            List<Map<String, String>> question =
                List.of(Map.of("role", "user", "content", "Which is better?"));

            try (var in = CloudGate.enter(new Where(project, story, run))) {
                try (var lines = client.as("judge", "task-1", "3")
                        .purpose("judging one candidate")
                        .chatCompletionStream(question, null, 0.0)) {
                    lines.collect(Collectors.joining());
                }
                try (var lines = client.as("test author")
                        .chatCompletionStream(question, null, 0.0)) {
                    lines.collect(Collectors.joining());
                }
            }
            // The Console's chat: a call made for no run at all.
            try (var lines = client.as("chat").purpose("the Console's chat")
                    .chatCompletionStream(question, null, 0.0)) {
                lines.collect(Collectors.joining());
            }

            RunUsageRecord usage = ledger.usageOf(run);
            assertThat(usage.calls()).hasSize(2);
            assertThat(usage.calls().get(0).role()).isEqualTo("judge");
            assertThat(usage.calls().get(0).purpose()).isEqualTo("judging one candidate");
            assertThat(usage.calls().get(0).task()).isEqualTo("task-1");
            assertThat(usage.calls().get(0).worker()).isEqualTo("3");
            assertThat(usage.calls().get(1).purpose())
                .as("a caller that says nothing is a single question").isEqualTo("one question");
            assertThat(ledger.outsideRuns().calls()).as("and the chat's call is kept too")
                .extracting(call -> call.role() + ": " + call.purpose())
                .containsExactly("chat: the Console's chat");
        }
    }
}
