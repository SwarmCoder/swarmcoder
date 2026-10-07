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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PLAN stage no longer has a fallback (2026-09-03: run {@code run 7}, all roles on the free
 * local model). Attempt 1 was rightly rejected by the enabler rule; one second later the run was
 * already dispatching a fabricated single-task plan named "Implement Goal" — too fast for a real
 * second model call to have happened, and indeed none had: the second attempt's failure was
 * swallowed by the old loop's bare {@code break}, logged nowhere, and the safety net that caught it
 * could not do the job either (an empty write set with no acceptance criteria reached
 * TEST_AUTHORING and parked three stages later, for a reason that had nothing to do with the real
 * problem).
 *
 * <p>These tests prove the replacement: a plan the validator keeps rejecting genuinely burns all
 * {@link GreenfieldWorkflow#MAX_PLAN_ATTEMPTS} real attempts (never fewer, from a silent early exit;
 * never a fabricated plan standing in for a real one), and only then does PLAN park — with the exact
 * wording an operator reads, the real reason the last attempt failed, and proof that nothing
 * downstream (a persisted task graph, a dispatched test author) ever ran on invented work.
 *
 * <p>No paid model call anywhere: the LLM roles are pointed at {@link ScriptedLlm}.
 */
class GreenfieldWorkflowPlanRetryTest {

    @TempDir
    Path storeDir;

    /**
     * Parses fine, but is impossible by construction: its whole write set is a file under the
     * protected acceptance-test tree, which {@code TaskGraphValidatorTest
     * #rejectsATaskThatMayWriteNothing} already proves the validator rejects. Returned identically
     * on every attempt, so the validator rejects it identically every time and the run genuinely
     * runs out of attempts rather than stumbling into an accepted plan.
     */
    private static final String IMPOSSIBLE_PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"write the acceptance tests myself","instructions":"do it",
          "writeSet":["src/test/java/swarm/accept/ImpossibleAcceptTest.java"],"readSet":[],
          "criteria":[]}],
         "edges":[]}
        """;

    @Test
    void aPlanRejectedThreeTimesParksWithTheAttemptCountAndObjectionsAndNothingDownstreamRuns()
            throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger testAuthorCalls = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("You are a test author")) {
                    testAuthorCalls.incrementAndGet();
                }
                if (conversation.contains("AI planner")) {
                    plannerCalls.incrementAndGet();
                    return IMPOSSIBLE_PLAN_JSON;
                }
                // DESIGN and everything else: decline, exactly like WorkflowPersistenceTest — this
                // test is about PLAN's own retry loop, not about what the model said elsewhere.
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    throw new AssertionError("a swarm was dispatched — PLAN never accepted a plan, "
                        + "so nothing should ever have reached EXECUTING");
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(), new RunReport(runId, "demo goal"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(plannerCalls.get())
                .as("every one of the three attempts must be a real model call — never fewer from "
                    + "a silent early exit, never more from re-litigating an already-failed attempt")
                .isEqualTo(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(testAuthorCalls.get())
                .as("nothing was ever accepted, so the test author must never have been reached")
                .isZero();

            assertThat(parked.state())
                .as("a park does not move the run's recorded state — it stays resumable at PLAN")
                .isEqualTo(RunState.PLAN);
            assertThat(parked.taskGraphId())
                .as("no plan was ever accepted, so nothing was ever persisted to claim it")
                .isNull();
            assertThat(parked.parkReason())
                .contains("The planner could not produce a plan that passes the checks after "
                    + GreenfieldWorkflow.MAX_PLAN_ATTEMPTS + " attempts")
                .contains("Last objections:")
                .contains("may write nothing")
                .contains("Build it again to try once more, or change the story.");
        }
    }

    private static void awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }
}
