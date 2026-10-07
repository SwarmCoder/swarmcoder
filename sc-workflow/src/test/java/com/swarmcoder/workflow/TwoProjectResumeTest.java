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
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a restart, an unfinished run must be continued against ITS OWN project's repository, with
 * its own locked modules.
 *
 * <p>The defect: resume took every unfinished run in the store and drove it through one engine —
 * whichever the caller happened to hold, which at startup was always the DEFAULT project's. A run
 * belonging to a second project was therefore continued against the first project's code, with the
 * first project's git service and the first project's locked modules. Nobody had to press anything;
 * a restart was enough. It is a write to the wrong codebase and a containment failure at once,
 * because the containment model resolves policy from the tree the run belongs to.
 *
 * <p>It shipped because every persistence test used a single project, where the fault cannot show:
 * with one project, the wrong engine and the right engine are the same object. So this test uses
 * two of everything — two projects, two repositories, two lock lists, two engines — and the run
 * under scrutiny belongs to the SECOND one.
 */
class TwoProjectResumeTest {

    @TempDir
    Path tmp;

    private ArtifactStore store;
    private ScriptedLlm llm;

    private Project alpha;
    private Project beta;
    private Path alphaRepo;
    private Path betaRepo;

    /** Which engine executed which run — recorded by each engine's own swarm engine. */
    private final Map<UUID, String> executedBy = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new ArtifactStore(tmp.resolve("store"));
        llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
        alphaRepo = Files.createDirectories(tmp.resolve("alpha-repo"));
        betaRepo = Files.createDirectories(tmp.resolve("beta-repo"));
        alpha = store.ensureProject("alpha", alphaRepo.toString(), List.of());
        beta = store.ensureProject("beta", betaRepo.toString(), List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (llm != null) {
            llm.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void eachUnfinishedRunResumesAgainstItsOwnRepositoryAndItsOwnLocks() throws Exception {
        WorkflowEngine alphaEngine = engineFor(alpha, alphaRepo, List.of("alpha-locked/"));
        WorkflowEngine betaEngine = engineFor(beta, betaRepo, List.of("beta-locked/"));

        Run alphaRun = persistUnfinishedRun(alpha.id(), "alpha work");
        Run betaRun = persistUnfinishedRun(beta.id(), "beta work");

        List<RunResumer.Resumed> resumed = new RunResumer(store, projectId -> {
            if (alpha.id().equals(projectId)) {
                return alphaEngine;
            }
            if (beta.id().equals(projectId)) {
                return betaEngine;
            }
            return null;
        }).resumeAll();

        assertThat(resumed).hasSize(2);
        RunResumer.Resumed forBeta = resumed.stream()
            .filter(r -> r.runId().equals(betaRun.id())).findFirst().orElseThrow();
        assertThat(forBeta.repoPath())
            .as("the second project's run must be driven against the SECOND project's repository")
            .isEqualTo(betaRepo);
        assertThat(forBeta.lockedPaths())
            .as("and under the second project's locked modules, or its protections are somebody "
                + "else's")
            .containsExactly("beta-locked/");

        RunResumer.Resumed forAlpha = resumed.stream()
            .filter(r -> r.runId().equals(alphaRun.id())).findFirst().orElseThrow();
        assertThat(forAlpha.repoPath()).isEqualTo(alphaRepo);
        assertThat(forAlpha.lockedPaths()).containsExactly("alpha-locked/");

        // Not just what the resumer says it did — what actually happened. Each engine records the
        // runs it executed, so this fails if a run was handed to the wrong project's machinery.
        awaitExecuted(alphaRun.id());
        awaitExecuted(betaRun.id());
        assertThat(executedBy.get(alphaRun.id())).isEqualTo("alpha");
        assertThat(executedBy.get(betaRun.id())).isEqualTo("beta");
    }

    /**
     * A project that cannot be opened leaves its runs where they are. Falling back to another
     * project's engine is the original defect with better manners: the run would still write to the
     * wrong codebase, and a run left alone loses nothing — the next start can resume it.
     */
    @Test
    void aRunWhoseProjectCannotBeOpenedIsNotResumedAgainstAnotherProject() throws Exception {
        WorkflowEngine alphaEngine = engineFor(alpha, alphaRepo, List.of("alpha-locked/"));

        Run alphaRun = persistUnfinishedRun(alpha.id(), "alpha work");
        Run orphaned = persistUnfinishedRun(beta.id(), "beta work");

        List<RunResumer.Resumed> resumed = new RunResumer(store,
            projectId -> alpha.id().equals(projectId) ? alphaEngine : null).resumeAll();

        assertThat(resumed).extracting(RunResumer.Resumed::runId)
            .containsExactly(alphaRun.id());
        awaitExecuted(alphaRun.id());
        assertThat(executedBy)
            .as("the orphaned run must not have been executed by anybody else's engine")
            .doesNotContainKey(orphaned.id());
        assertThat(store.root().runs.get(orphaned.id()).state())
            .as("it stays exactly where it was, resumable once its project opens again")
            .isEqualTo(RunState.EXECUTING);
    }

    /**
     * Deleting a project must end its unfinished work, not just remove the project record — the
     * defect this guards is a run whose PROJECT was deleted still being found and resumed
     * (see {@link RunResumer#resumeAll()}). Project alpha is deleted while its run is EXECUTING
     * with a pending decision; a fresh {@code RunResumer}, opened afterwards exactly as it would be
     * after a restart, must resume nothing for alpha while still resuming beta's run through beta's
     * own engine.
     */
    @Test
    void deletingAProjectEndsItsExecutingRunAndDropsItsPendingDecision() throws Exception {
        Run alphaRun = persistUnfinishedRun(alpha.id(), "alpha work");
        UUID decisionId = UUID.randomUUID();
        store.append(() -> store.root().decisions.put(decisionId, new Decision(decisionId,
            alphaRun.id(), DecisionKind.BLOCKED_TASK, "why alpha stopped", DecisionState.PENDING,
            null, Instant.now()))).get();

        Run betaRun = persistUnfinishedRun(beta.id(), "beta work");

        store.deleteProject(alpha.id());

        assertThat(store.root().runs.get(alphaRun.id()))
            .as("a deleted project takes its runs with it — nothing is left that could ever be "
                + "resumed")
            .isNull();
        assertThat(store.root().decisions.get(decisionId))
            .as("and the question it raised, which nobody could ever have answered once its "
                + "project was gone")
            .isNull();

        WorkflowEngine betaEngine = engineFor(beta, betaRepo, List.of("beta-locked/"));
        List<RunResumer.Resumed> resumed = new RunResumer(store,
            projectId -> beta.id().equals(projectId) ? betaEngine : null).resumeAll();

        assertThat(resumed).extracting(RunResumer.Resumed::runId).containsExactly(betaRun.id());
        awaitExecuted(betaRun.id());
        assertThat(executedBy.get(betaRun.id())).isEqualTo("beta");
    }

    /**
     * The defect exactly as it happened: a run's project is gone from the store, but the run row
     * itself is still there — the shape of data a deletion left behind before this fix existed, or
     * a race between a live deletion and a resume. {@link RunResumer} must never hand such a run to
     * any engine; instead it retires it (ABANDONED) and drops its pending decision, while a second
     * project's run resumes normally through its own engine.
     */
    @Test
    void aRunOfAProjectRemovedFromTheStoreIsAbandonedNotResumed() throws Exception {
        Run orphanRun = persistUnfinishedRun(alpha.id(), "alpha work");
        UUID decisionId = UUID.randomUUID();
        store.append(() -> store.root().decisions.put(decisionId, new Decision(decisionId,
            orphanRun.id(), DecisionKind.BLOCKED_TASK, "why alpha stopped", DecisionState.PENDING,
            null, Instant.now()))).get();
        // The project record disappears WITHOUT going through ArtifactStore.deleteProject — exactly
        // the shape of pre-existing orphan data on disk, which is what this test is for.
        store.append(() -> store.root().projects.remove(alpha.id())).get();

        Run betaRun = persistUnfinishedRun(beta.id(), "beta work");
        WorkflowEngine betaEngine = engineFor(beta, betaRepo, List.of("beta-locked/"));

        List<RunResumer.Resumed> resumed = new RunResumer(store,
            projectId -> beta.id().equals(projectId) ? betaEngine : null).resumeAll();

        assertThat(resumed).extracting(RunResumer.Resumed::runId).containsExactly(betaRun.id());
        assertThat(executedBy).doesNotContainKey(orphanRun.id());
        assertThat(store.root().runs.get(orphanRun.id()).state())
            .as("retired, not left EXECUTING forever and not silently deleted either")
            .isEqualTo(RunState.ABANDONED);
        assertThat(store.root().decisions.get(decisionId))
            .as("nobody can ever answer a question raised by a run whose project is gone")
            .isNull();

        awaitExecuted(betaRun.id());
        assertThat(executedBy.get(betaRun.id())).isEqualTo("beta");
    }

    private Run persistUnfinishedRun(UUID projectId, String goal) throws Exception {
        UUID runId = UUID.randomUUID();
        // EXECUTING: mid-flight when the process died, which is the state resume exists for. It also
        // means the very next thing the workflow does is hand the run to a swarm engine, so which
        // project's machinery picked it up is directly observable.
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, projectId, null,
            null, UUID.randomUUID(), null, Instant.now(), new RunReport(runId, goal));
        store.append(() -> store.root().runs.put(runId, run)).get();
        return run;
    }

    private WorkflowEngine engineFor(Project project, Path repo, List<String> lockedPaths) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        SwarmEngine recording = run -> {
            executedBy.put(run.id(), project.name());
            return run;
        };
        return new WorkflowEngine(unusedRuntime, recording,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, null, null, null, lockedPaths);
    }

    private void awaitExecuted(UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (executedBy.containsKey(runId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " was never executed by any engine");
    }
}
