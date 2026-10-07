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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge's calls for the candidates of one task are in flight together (2026-10-02).
 *
 * <p>Harness run 66: eight judge calls took 1,771 seconds, 221 each, and the two of a task ran
 * one after the other - so every task with two survivors waited seven minutes for verdicts that
 * do not depend on each other. Each call sees one diff and never another candidate.
 */
class TheJudgeCallsOfOneTaskRunSideBySideTest {

    private static CandidateSolution candidate(Task task, int worker, String line) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1,2 @@\n class A {\n+"
                + line + "\n", null, null, null, CandidateState.SURVIVED, null);
    }

    @Test
    void twoVerdictsAreAskedForTogetherAndComeBackInTheOrderGiven() throws Exception {
        Task task = new Task(UUID.randomUUID(), 1, "Add a field", "Add a field to A.",
            Set.of("A.java"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.JUDGING);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothIn = new CountDownLatch(2);

        try (FakeVllm fake = new FakeVllm(conversation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            bothIn.countDown();
            try {
                bothIn.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            // The verdict depends on the diff it was shown, so a mix-up would show.
            return FakeVllm.Reply.text(conversation.contains("int first;")
                ? "{\"score\": 0.9, \"rationale\": \"the first one\"}"
                : "{\"score\": 0.4, \"rationale\": \"the second one\"}");
        })) {
            JudgeClient judge = new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true));
            CandidateSolution first = candidate(task, 0, "int first;");
            CandidateSolution second = candidate(task, 1, "int second;");

            List<CandidateSolution> judged = judge.judgeAll(List.of(first, second), task);

            assertThat(peak.get()).as("judge calls in flight together").isEqualTo(2);
            assertThat(judged).extracting(CandidateSolution::id)
                .containsExactly(first.id(), second.id());
            assertThat(judged.get(0).judge().rationale()).contains("the first one");
            assertThat(judged.get(1).judge().rationale()).contains("the second one");
        }
    }

    @Test
    void candidatesWithTheSameMechanicalEvidenceAreSaidToBeIndistinguishableByIt() {
        Task task = new Task(UUID.randomUUID(), 1, "Add a field", "Add a field to A.",
            Set.of("A.java"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.JUDGING);
        CandidateSolution first = candidate(task, 0, "int first;");
        CandidateSolution second = candidate(task, 1, "int second;");

        assertThat(JudgeClient.sameMechanicalEvidence(List.of(first, second), task))
            .as("neither was verified and neither left its own paths: the same evidence")
            .isNotNull().isNotBlank();
        assertThat(JudgeClient.sameMechanicalEvidence(List.of(first), task))
            .as("one candidate is not a comparison").isNull();

        second.setOutOfWriteSetPaths(List.of("B.java"));
        assertThat(JudgeClient.sameMechanicalEvidence(List.of(first, second), task))
            .as("one of them went outside its own paths: the evidence separates them").isNull();
    }

    @Test
    void theJudgeIsAskedForTheVerdictAndNothingAroundIt() {
        assertThat(JudgeClient.systemPrompt(true)).contains("Be brief")
            .contains("The whole answer is that one JSON object");
        assertThat(JudgeClient.systemPrompt(false)).contains("Be brief");
    }
}
