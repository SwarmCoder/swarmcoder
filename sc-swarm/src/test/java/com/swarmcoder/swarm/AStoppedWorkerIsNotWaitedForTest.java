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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.KillReason;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: a worker whose task already had a passing candidate was told to stop, noticed
 * only when its model request came back, and its task waited twelve minutes for that. A stop now
 * abandons the request in flight. The model server here is scripted and local.
 */
class AStoppedWorkerIsNotWaitedForTest {

    private static final long SERVER_HOLDS_THE_ANSWER_MILLIS = 20_000;

    @Test
    void stoppingTheGroupAbandonsTheModelRequestInFlight() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        try (FakeVllm slow = new FakeVllm(conversation -> {
            asked.countDown();
            try {
                Thread.sleep(SERVER_HOLDS_THE_ANSWER_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeVllm.Reply.text("too late");
        })) {
            ModelQuirks quirks = new ModelQuirks("scripted", 4096, false, null, null, true, true,
                false, 262144, 65536, 1024, 8, false);
            AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("worker-1", "You work.",
                new AgentRuntime.ModelEndpoint(slow.baseUrl(), "", "fake-vllm", 262144, quirks),
                0.2, 10, List.of(), info -> Optional.empty());
            GroupSignal signal = new GroupSignal();

            try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
                signal.onStop(session::cancel);
                CompletableFuture<AgentRuntime.SessionResult> running =
                    CompletableFuture.supplyAsync(() -> session.run("Begin now."));
                assertThat(asked.await(30, TimeUnit.SECONDS))
                    .as("the request reached the model server").isTrue();

                long stoppedAt = System.currentTimeMillis();
                signal.supersede();
                AgentRuntime.SessionResult result =
                    running.get(SERVER_HOLDS_THE_ANSWER_MILLIS / 2, TimeUnit.MILLISECONDS);

                assertThat(System.currentTimeMillis() - stoppedAt)
                    .as("it did not wait for the answer").isLessThan(SERVER_HOLDS_THE_ANSWER_MILLIS / 2);
                assertThat(result.killReason()).contains(KillReason.SUPERSEDED);
            }
        }
    }

    @Test
    void aSessionStoppedBeforeItAsksNeverAsks() throws Exception {
        try (FakeVllm never = new FakeVllm(conversation -> FakeVllm.Reply.text("unwanted"))) {
            AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("worker-1", "You work.",
                new AgentRuntime.ModelEndpoint(never.baseUrl(), "", "fake-vllm", 262144, null),
                0.2, 10, List.of(), info -> Optional.empty());
            GroupSignal signal = new GroupSignal();
            signal.stop(KillReason.ROUND_TIME_UP);
            try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
                signal.onStop(session::cancel);
                assertThat(session.run("Begin now.").killReason())
                    .contains(KillReason.ROUND_TIME_UP);
            }
            assertThat(never.requests).isEmpty();
        }
    }
}
