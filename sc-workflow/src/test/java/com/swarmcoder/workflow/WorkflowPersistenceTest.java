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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule R3: every workflow transition is durable. The done-condition from
 * DEVELOPER_CORRECTIONS.md §6.3 — a run driven to its terminal state is visible in the store after a
 * process restart (simulated here by closing and reopening the EclipseStore).
 *
 * <p>Two things about this test changed with UX v3. The terminal state is DELIVERED, not APPROVAL: a
 * build no longer parks at a gate of its own (§2.3). And it no longer reaches the offline planner
 * fallback by pointing at a DEAD port — an unreachable endpoint now pauses the run and waits for it
 * (§2.4), which is exactly the improvement being made, so the model here ANSWERS and answers
 * uselessly.
 *
 * <p>A third thing changed on 2026-09-03: there is no longer an offline planner fallback to reach.
 * A model that only ever answers unparseably now makes PLAN park, on its own recorded stage, after
 * genuinely trying three times — not fabricate a single-task plan and carry on to a fake DELIVERED.
 * A park is a durable transition too, so it is what this test now proves — and it proves the park's
 * OWN fields, {@code parkedAt} and {@code parkReason}, not only the state, because those two are set
 * by mutating an already-stored {@code Run} in place and were the ones actually being lost.
 */
class WorkflowPersistenceTest {

    @TempDir
    Path storeDir;

    @Test
    void aPlannerThatNeverAnswersUsablyParksDurablyAndSurvivesRestart() throws Exception {
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
             ArtifactStore store = new ArtifactStore(storeDir)) {
            // Every role answers unparseably, so DESIGN degrades to a minimal design and PLAN
            // exhausts its three real attempts and parks; the swarm engine is a pass-through fake
            // that this run never reaches. This test is about persistence, not about what the
            // model said.
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(
                unusedRuntime,
                run -> run,
                new VllmClient(llm.baseUrl(), "", "test-model", true),
                store);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(), new RunReport(runId, "demo goal"));
            // Waited on rather than polled: a park is set in memory before it is
            // written, so polling for parkedAt and then closing the store closes it
            // in the middle of the write that records the reason.
            engine.advanceAsync(run).get();

            Run parked = store.root().runs.get(runId);
            assertThat(parked.state())
                .as("a park does not move the run's recorded state — it stays resumable at PLAN")
                .isEqualTo(RunState.PLAN);
            assertThat(parked.taskGraphId())
                .as("nothing was ever accepted, so nothing was ever persisted to claim it")
                .isNull();
            assertThat(parked.parkReason())
                .contains("could not produce a plan that passes the checks after 3 attempts")
                .contains("Build it again to try once more, or change the story");
            assertThat(parked.pausedSince())
                .as("nothing was ever unreachable — this is a park, not a pause")
                .isNull();
        }

        // "Restart": a fresh store instance over the same directory must still see the parked run
        // — with its reason. parkedAt/parkReason are set by mutating an ALREADY-STORED Run object in
        // place (GreenfieldWorkflow.parkRun), not by replacing it with a new one the way every state
        // transition does (Run.withState), so storing the runs map does not reach them: only a store
        // of the run object itself does, which is what ArtifactStore.updateRun is for. Both of those
        // writes used also to be thrown away when the store was closed while they were still queued.
        // Asserted here in full, and pinned at the storage layer by QueuedWritesSurviveCloseTest.
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            Run afterRestart = reopened.root().runs.get(runId);
            assertThat(afterRestart).isNotNull();
            assertThat(afterRestart.state()).isEqualTo(RunState.PLAN);
            assertThat(afterRestart.parkedAt())
                .as("the run is still marked stopped after a restart")
                .isNotNull();
            assertThat(afterRestart.parkReason())
                .as("and it still says WHY it stopped")
                .contains("could not produce a plan that passes the checks after 3 attempts")
                .contains("Build it again to try once more, or change the story");
        }
    }
}
