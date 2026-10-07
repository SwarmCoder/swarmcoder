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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two questions a role asks the expert in ONE turn are answered at the same time.
 *
 * <p>The one real serialisation on the expert's path (2026-10-02): the agent loop runs a turn's
 * tool calls strictly in order, so a role that asked two independent questions in one turn waited
 * for the first answer - minutes - before the second was even begun. The loop still runs them in
 * order (the agent framework's own "parallel" switch does not run blocking tools at the same
 * time; measured here). What changed is that the toolbox is told the turn's calls before the first
 * runs, and puts every question to the expert at once.
 */
class QuestionsAskedInOneTurnRunSideBySideTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An expert that knows whether another question was being answered at the same time. */
    private static final class Expert implements ExpertHelp {
        private final CountDownLatch bothIn = new CountDownLatch(2);
        final AtomicInteger together = new AtomicInteger();
        final AtomicInteger asked = new AtomicInteger();
        private final long waitMillis;

        Expert(long waitMillis) {
            this.waitMillis = waitMillis;
        }

        @Override
        public Answer askExpert(String question, String whatITried) {
            asked.incrementAndGet();
            bothIn.countDown();
            try {
                if (bothIn.await(waitMillis, TimeUnit.MILLISECONDS)) {
                    together.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Answer.deterministic("answer to " + question);
        }

        @Override
        public Answer requestSkeleton(String typeOrTask) {
            return Answer.none("not asked here");
        }
    }

    @Test
    void twoQuestionsInOneTurnAreAnsweredAtTheSameTime() throws Exception {
        Expert expert = new Expert(15_000);
        String sent = run(expert, true);

        assertThat(expert.asked.get()).as("each question was asked once, not twice").isEqualTo(2);
        assertThat(expert.together.get())
            .as("each question was being answered while the other was")
            .isEqualTo(2);
        assertThat(sent)
            .as("and both answers went back to the model as the results of their own calls")
            .contains("answer to the first question").contains("answer to the second question");
    }

    @Test
    void aSessionThatIsNotToldOfUpcomingCallsStillRunsThemOneAfterAnotherAsAlways() throws Exception {
        Expert expert = new Expert(300);
        run(expert, false);

        assertThat(expert.asked.get()).isEqualTo(2);
        assertThat(expert.together.get())
            .as("the first had finished waiting, alone, before the second began")
            .isEqualTo(1);
    }

    private static String run(Expert expert, boolean toldOfUpcomingCalls) throws Exception {
        try (ScriptedExpertEndpoint endpoint = new ScriptedExpertEndpoint(turn -> turn == 1
                ? ScriptedExpertEndpoint.Reply.toolCalls("ask_expert",
                    args("question", "the first question"), args("question", "the second question"))
                : ScriptedExpertEndpoint.Reply.toolCall("report_done", args("answer", "done")))) {
            ExpertTools tools = new ExpertTools(null, null, null, new CloudGate(10_000_000, null),
                null, 0, new ExpertTools.User("architect", "Hand in.", 0, expert, 0, 0));
            ModelQuirks quirks = ModelQuirks.DEFAULTS.withLabel("expert-fake");
            AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("architect", "You ask.",
                new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), "", "expert-fake",
                    quirks.servedContextTokens(), quirks),
                0.2, 6, tools.bindings(), info -> Optional.empty());
            if (toldOfUpcomingCalls) {
                spec = spec.with(AgentRuntime.SessionOptions.tellingOfUpcomingCalls(tools::upcoming));
            }
            try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
                AgentRuntime.SessionResult result = session.run("Begin.");
                assertThat(result.killReason()).isEmpty();
            }
            return endpoint.everythingSent();
        }
    }

    private static String args(String name, String value) {
        try {
            return JSON.writeValueAsString(JSON.createObjectNode().put(name, value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
