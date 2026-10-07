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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that parks and is then resumed must stop reading as "stopped and asking you something" the
 * instant it gets past the stage that parked it — not five/twenty minutes later, and not with the
 * old question still sitting there PENDING next to whatever the new attempt raises.
 *
 * <p>Reproduces the shape of the morning report this fix is for: a run parks at EXECUTING (the same
 * site {@code RunMustPark} covers — a wave would not merge), raising one BLOCKED_TASK decision and
 * leaving the run's recorded state unchanged. It is then resumed exactly the way {@link RunResumer}
 * resumes a run at process start: the persisted run is handed back to {@code WorkflowEngine.advance}.
 * This time the same stage succeeds, and two things must be true immediately: the run itself no
 * longer reads as parked ({@code BuildHealth} territory, proved directly on {@code Run}), and the
 * decision the first attempt raised is RESOLVED rather than sitting PENDING forever.
 *
 * <p>{@code PipelineBoard.questionsFor} (sc-console-ui) is exactly this query — every PENDING
 * {@code BLOCKED_TASK} decision whose {@code runId} is one of the story's runs — so proving no such
 * decision remains PENDING for this run IS proving the card has nothing left to ask about it;
 * sc-workflow cannot call into sc-console-ui directly (TeaVM-compiled, depends only on
 * sc-console-api), so this is the faithful proxy for that assertion at this layer.
 *
 * <p>No paid model call anywhere: the LLM roles are pointed at {@link ScriptedLlm}. DESIGN answers
 * every prompt unparseably and takes its offline fallback, exactly as {@link WorkflowPersistenceTest}
 * does; PLAN is given a real, working reply — this test is about EXECUTING's own park-and-resume,
 * not about PLAN's, which {@link WorkflowPersistenceTest} covers — and the swarm itself is the fake
 * below, never a real endpoint.
 */
class ParkWithdrawnOnResumeTest {

    @TempDir
    Path storeDir;

    /**
     * One task, no story (this run names none), and — deliberately — no criteria: this test is
     * about EXECUTING's park-and-resume, so TEST_AUTHORING must pass trivially rather than needing
     * the ScriptedLlm's test-author role to author anything real.
     */
    private static final String PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Implement it","instructions":"do the thing",
          "writeSet":["src/main/java/com/example"],"readSet":[],"criteria":[]}],
         "edges":[]}
        """;

    @Test
    void aParkIsWithdrawnTheMomentTheRunGetsPastIt() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger executeAttempts = new AtomicInteger(0);

        try (ScriptedLlm llm = new ScriptedLlm(conversation ->
                conversation.contains("AI planner") ? PLAN_JSON : "I decline to produce JSON.");
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            // Parks on the FIRST call (a wave would not merge — the exact RunMustPark site), and
            // succeeds on every call after that, exactly as a real merge conflict resolved by hand
            // would on the next attempt.
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    if (executeAttempts.getAndIncrement() == 0) {
                        throw new RunMustPark("Wave 1's winners would not merge onto each other: "
                            + "task 'A' and task 'B' both rewrote the same file.");
                    }
                    return run;
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(), new RunReport(runId, "demo goal"));
            engine.advance(run);

            // --- first attempt: parked at EXECUTING, one question raised -----------------------
            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);
            assertThat(parked.state())
                .as("a park does not move the run's recorded state - it stays resumable here")
                .isEqualTo(RunState.EXECUTING);
            assertThat(parked.parkedAt())
                .as("the park is now a recorded fact on the run itself, not inferred from silence")
                .isNotNull();
            assertThat(parked.parkReason()).contains("would not merge");

            List<Decision> pendingForRun = pendingDecisionsFor(store, runId);
            assertThat(pendingForRun)
                .as("exactly one question raised, matching the run's own park reason")
                .hasSize(1);
            assertThat(pendingForRun.get(0).briefMarkdown()).contains("would not merge");

            // --- resumed exactly as RunResumer resumes it at process start ---------------------
            engine.advance(store.root().runs.get(runId));

            awaitState(store, runId, RunState.DELIVERED);
            Run finished = store.root().runs.get(runId);
            assertThat(finished.parkedAt())
                .as("the run got past EXECUTING - nothing about it should still claim to be parked")
                .isNull();
            assertThat(finished.parkReason()).isNull();

            assertThat(pendingDecisionsFor(store, runId))
                .as("and the question the first attempt raised must not still be waiting on an "
                    + "answer - the card would otherwise go on saying \"this build stopped and is "
                    + "asking you something\" about a run that finished cleanly")
                .isEmpty();

            Decision withdrawn = store.root().decisions.values().stream()
                .filter(d -> runId.equals(d.runId()))
                .findFirst().orElseThrow();
            assertThat(withdrawn.state())
                .as("resolved, not deleted - the same lifecycle a human's own answer goes through")
                .isEqualTo(DecisionState.RESOLVED);
            assertThat(withdrawn.humanResponse())
                .as("and the record says WHY it was resolved, since nobody actually answered it")
                .containsIgnoringCase("moved on");
        }
    }

    /**
     * Harness run 39, 2026-09-25: a task went BLOCKED and raised its own question, and the tasks
     * that depend on it were then held back rather than dispatched. The run parks behind that one
     * question — marked stopped, with the reason saying which tasks waited — and does NOT raise a
     * second question about the same fault.
     */
    @Test
    void aParkBehindABlockedTasksQuestionRaisesNoSecondQuestion() throws Exception {
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation ->
                conversation.contains("AI planner") ? PLAN_JSON : "I decline to produce JSON.");
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> {
                    // What SwarmEngineImpl does: the blocked task raises its own question...
                    UUID decisionId = UUID.randomUUID();
                    try {
                        store.append(() -> {
                            store.root().decisions.put(decisionId, new Decision(decisionId,
                                run.id(), DecisionKind.BLOCKED_TASK, "Task BLOCKED after swarm + "
                                    + "repair round: 'Create shared BooksService interface'",
                                DecisionState.PENDING, null, Instant.now()));
                            return null;
                        }).get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    // ...and the run stops behind it once the tasks that need it were held back.
                    throw new RunMustPark("The run stopped behind task 'Create shared BooksService "
                        + "interface', which has no winner.", true);
                },
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);

            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(), new RunReport(runId, "demo goal")));

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);
            assertThat(parked.state()).isEqualTo(RunState.EXECUTING);
            assertThat(parked.parkReason()).contains("stopped behind task");
            assertThat(pendingDecisionsFor(store, runId))
                .as("the blocked task's own question, and nothing else")
                .hasSize(1)
                .allSatisfy(d -> assertThat(d.briefMarkdown()).startsWith("Task BLOCKED"));
        }
    }

    private static List<Decision> pendingDecisionsFor(ArtifactStore store, UUID runId) {
        return store.root().decisions.values().stream()
            .filter(d -> d.state() == DecisionState.PENDING
                && d.kind() == DecisionKind.BLOCKED_TASK
                && runId.equals(d.runId()))
            .toList();
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

    private static void awaitState(ArtifactStore store, UUID runId, RunState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.state() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("Run never reached " + expected + "; last persisted state: "
            + (last == null ? "never persisted" : last.state()));
    }
}
