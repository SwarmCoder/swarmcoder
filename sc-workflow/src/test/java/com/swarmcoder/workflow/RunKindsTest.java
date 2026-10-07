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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing may report work delivered that was never attempted.
 *
 * <p>Four of the six kinds of run used to do exactly that. Typing {@code /bugfix}, {@code /refactor},
 * {@code /docs} or {@code /analyze} — all four on the chat's own command menu, all four described in
 * the user manual as working features — started a "workflow" that renamed the run's state three or
 * four times, called the swarm engine with no task graph for it to execute, and marked the run
 * DELIVERED. No design, no plan, no acceptance test, no worker, no build. No test had ever started
 * one.
 *
 * <p>What replaced them, and what this pins:
 * <ul>
 *   <li>bugfix and refactor run the ONE delivery path, so they design, plan and produce a task graph
 *       like any other build, and the only difference is what the design roles are told;</li>
 *   <li>docs and analysis cannot be started at all, and any left in a store by an older build end
 *       ABORTED rather than DELIVERED;</li>
 *   <li>and underneath both, a run with no plan cannot be recorded as delivered by any route.</li>
 * </ul>
 */
class RunKindsTest {

    @TempDir
    Path storeDir;

    /**
     * A bugfix and a refactor now go through the real thing: a design is made, a plan is produced
     * and persisted, and only then can the run say it delivered.
     *
     * <p>Every role answers unusably here, exactly as in {@code WorkflowPersistenceTest}, so the
     * design degrades to the minimal one and the plan to the single-task fallback. That is the
     * point: even the weakest possible run of the real path produces a design and a task graph,
     * which the old bugfix and refactor "workflows" never did at their best.
     */
    @Test
    void bugfixAndRefactorRunsGoThroughTheRealDeliveryPath() throws Exception {
        for (WorkflowKind kind : List.of(WorkflowKind.BUGFIX, WorkflowKind.REFACTOR)) {
            Path dir = storeDir.resolve("real-" + kind);
            UUID runId = UUID.randomUUID();
            try (ScriptedLlm llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
                 ArtifactStore store = new ArtifactStore(dir)) {
                WorkflowEngine engine = engine(store, llm);
                engine.advance(new Run(runId, kind, RunState.INTAKE, null, null,
                    null, null, null, Instant.now(),
                    new RunReport(runId, "the login button does nothing on the second click")));

                awaitState(store, runId, RunState.DELIVERED);
                Run finished = store.root().runs.get(runId);
                assertThat(finished.designId())
                    .as("%s must be designed like any other build", kind)
                    .isNotNull();
                assertThat(finished.taskGraphId())
                    .as("%s must be planned; a run with no plan built nothing", kind)
                    .isNotNull();
                assertThat(store.root().taskGraphs).containsKey(finished.taskGraphId());
                assertThat(store.root().designs).containsKey(finished.designId());
            }
        }
    }

    /**
     * The kind is not decoration: a bugfix run tells the architect to reproduce the fault first, a
     * refactor run tells it not to change behaviour, and a feature run says neither.
     *
     * <p>Without this, routing the two kinds to the one delivery path would be indistinguishable
     * from deleting them — which is not what was decided.
     */
    @Test
    void theKindChangesWhatTheDesignRolesAreTold() throws Exception {
        assertThat(RunBrief.forKind(WorkflowKind.BUGFIX, "the login button does nothing"))
            .contains("the login button does nothing")
            .contains("BUGFIX RUN")
            .contains("must FAIL against the code exactly as it stands today");
        assertThat(RunBrief.forKind(WorkflowKind.REFACTOR, "split the giant service class"))
            .contains("split the giant service class")
            .contains("REFACTOR RUN")
            .contains("No behaviour change at all");
        assertThat(RunBrief.forKind(WorkflowKind.GREENFIELD, "add a multiply method"))
            .as("a feature run's goal reaches the architect exactly as the operator wrote it")
            .isEqualTo("add a multiply method");

        // …and the brief genuinely reaches the model, rather than being a string nobody sends.
        List<String> conversations = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                conversations.add(conversation);
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir.resolve("brief"))) {
            WorkflowEngine engine = engine(store, llm);
            engine.advance(new Run(runId, WorkflowKind.BUGFIX, RunState.INTAKE, null, null,
                null, null, null, Instant.now(),
                new RunReport(runId, "the login button does nothing on the second click")));
            awaitState(store, runId, RunState.DELIVERED);
        }
        assertThat(conversations)
            .as("the architect was told this is a bugfix and what its evidence has to be")
            .anySatisfy(c -> assertThat(c).contains("BUGFIX RUN"));
    }

    /**
     * A docs or analysis run persisted by an older build ends ABORTED. It is the only honest end for
     * it: nothing was designed, planned, tested or built, so there is nothing to deliver.
     */
    @Test
    void docsAndAnalysisRunsAreAbortedAndNeverDelivered() throws Exception {
        for (WorkflowKind kind : List.of(WorkflowKind.DOCS, WorkflowKind.ANALYSIS)) {
            Path dir = storeDir.resolve("retired-" + kind);
            UUID runId = UUID.randomUUID();
            try (ScriptedLlm llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
                 ArtifactStore store = new ArtifactStore(dir)) {
                WorkflowEngine engine = engine(store, llm);
                engine.advance(new Run(runId, kind, RunState.INTAKE, null, null,
                    null, null, null, Instant.now(),
                    new RunReport(runId, "write the manual")));

                awaitState(store, runId, RunState.ABORTED);
                Run ended = store.root().runs.get(runId);
                assertThat(ended.state()).isEqualTo(RunState.ABORTED);
                assertThat(ended.taskGraphId())
                    .as("nothing was planned, and nothing pretended otherwise")
                    .isNull();
                assertThat(store.root().taskGraphs).isEmpty();
            }
        }
    }

    /** Neither kind can be offered to an operator, whatever a menu somewhere still says. */
    @Test
    void onlyKindsThatActuallyBuildAreStartable() {
        assertThat(WorkflowKind.startable())
            .containsExactly(WorkflowKind.GREENFIELD, WorkflowKind.ENHANCEMENT,
                WorkflowKind.BUGFIX, WorkflowKind.REFACTOR);
        assertThat(WorkflowKind.DOCS.isStartable()).isFalse();
        assertThat(WorkflowKind.ANALYSIS.isStartable()).isFalse();
    }

    /**
     * The backstop, independent of which workflows happen to exist today: the store will not record
     * a delivery for a run that was never planned.
     */
    @Test
    void aRunWithNoPlanCannotBeRecordedAsDelivered() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir.resolve("backstop"))) {
            RunPersister persister = new RunPersister(store);
            UUID runId = UUID.randomUUID();
            Run neverPlanned = new Run(runId, WorkflowKind.GREENFIELD, RunState.DELIVERED,
                null, null, null, null, null, Instant.now(), new RunReport(runId, "a goal"));

            assertThatThrownBy(() -> persister.save(neverPlanned))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no task graph");
            assertThat(store.root().runs).doesNotContainKey(runId);

            // The same run WITH a plan behind it saves normally — the rule is about evidence, not
            // about making delivery hard.
            Run planned = new Run(runId, WorkflowKind.GREENFIELD, RunState.DELIVERED,
                null, null, UUID.randomUUID(), UUID.randomUUID(), null, Instant.now(),
                new RunReport(runId, "a goal"));
            persister.save(planned);
            assertThat(store.root().runs.get(runId).state()).isEqualTo(RunState.DELIVERED);
        }
    }

    private static WorkflowEngine engine(ArtifactStore store, ScriptedLlm llm) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new WorkflowEngine(unusedRuntime, run -> run,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store);
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
