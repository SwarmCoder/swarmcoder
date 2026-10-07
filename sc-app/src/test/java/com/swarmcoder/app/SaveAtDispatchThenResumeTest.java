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
package com.swarmcoder.app;

import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.workflow.RunResumer;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The save-and-resume loop through the product's real workflow engine and its real start-up resume
 * path, with no model (2026-09-25).
 *
 * <p>The live harness cannot be run to prove this — it needs the local model, which is busy, and a
 * scripted run to EXECUTING is not cheap: {@code FullProductDressRehearsalTest}'s scripted journey
 * currently parks in DESIGN (its architect names no contract types). So this drives the one part
 * of the journey the mechanism is about. A run enters EXECUTING in a real {@link WorkflowEngine};
 * the engine calls its swarm engine; the {@link DispatchSeam} fires there, on the workflow thread,
 * and saves a snapshot; the swarm engine then stops the run (standing in for the workers). The
 * snapshot is restored elsewhere, and {@link RunResumer} — exactly what the app runs at start-up —
 * hands the restored run to a fresh engine, which calls ITS swarm engine with the run in EXECUTING.
 *
 * <p>What that proves: the seam is where the workflow thread holds the run in EXECUTING before any
 * dispatch; a backup taken there, under a live engine, reopens; the snapshot is the moment of the
 * seam and not after it (the original run went on to park; the restored one has no park); the
 * project follows the repository to its new path; and the product's own resume path picks the run
 * up at the workers. What only a live run can prove: that real workers, verification, the judge
 * and integration then behave on a restored run as they do on a fresh one.
 */
class SaveAtDispatchThenResumeTest {

    @TempDir
    Path tmp;

    private static final AgentRuntime NO_RUNTIME = spec -> {
        throw new UnsupportedOperationException("no agent runs in this test");
    };

    private static VllmClient noModel() {
        // Never called: nothing in EXECUTING asks a role client anything before the swarm engine.
        return new VllmClient("http://127.0.0.1:9/v1", "", "no-model", true);
    }

    @Test
    void aRunSavedAtTheMomentOfDispatchIsPickedUpThereByTheProductsOwnResumePath()
            throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("original").resolve("bookshelf"));
        BookshelfFixture.git(repo, "init -q -b master");
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        BookshelfFixture.git(repo, "add -A");
        BookshelfFixture.git(repo, "-c user.email=t@local -c user.name=t commit -q -m Baseline");
        Path snapshot = tmp.resolve("snapshot");
        UUID runId = UUID.randomUUID();
        UUID projectId;

        try (ArtifactStore store = new ArtifactStore(tmp.resolve("original").resolve("store"))) {
            Project project = store.ensureProject("bookshelf", repo.toString(), List.of());
            projectId = project.id();
            Map<UUID, Boolean> seenAtTheSeam = new ConcurrentHashMap<>();
            DispatchSeam seam = new DispatchSeam(atDispatch -> {
                // What is in the store at this instant is what the snapshot will hold.
                seenAtTheSeam.put(atDispatch.id(),
                    store.root().runs.get(atDispatch.id()).state() == RunState.EXECUTING);
                try {
                    HarnessSnapshot.save(snapshot, store, repo, new HarnessSnapshot.Manifest(
                        HarnessSnapshot.FORMAT, Instant.now().toString(), atDispatch.id(),
                        atDispatch.projectId(), null, "abc", List.of(), "http://127.0.0.1:9/v1",
                        "no-model", null, 2, "origin", null,
                        BookshelfFixture.git(repo, "rev-parse HEAD").strip(), null, "reference",
                        List.of(), HarnessSnapshot.registeredWorktrees(repo)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            SwarmEngine workersStandIn = run -> {
                seam.beforeDispatch(run);
                throw new RunMustPark("the test stops the run where its workers would start");
            };
            WorkflowEngine engine = new WorkflowEngine(NO_RUNTIME, workersStandIn, noModel(), store,
                new CloudGate(0, null), repo, null, null, null, List.of());

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, projectId, null,
                null, null, null, Instant.now(), new RunReport(runId, "rate a book"));
            engine.advanceAsync(run).get(60, TimeUnit.SECONDS);

            assertThat(seenAtTheSeam).as("the seam fired once, with the run persisted in "
                + "EXECUTING").containsExactly(Map.entry(runId, true));
            assertThat(store.root().runs.get(runId).parkedAt())
                .as("the original run went on past the seam and parked").isNotNull();
        }
        assertThat(snapshot.resolve(HarnessSnapshot.MANIFEST)).exists();

        HarnessSnapshot.Restored restored = HarnessSnapshot.restore(snapshot, tmp.resolve("resumed"),
            tmp.resolve("wt"));
        Map<UUID, Run> dispatched = new ConcurrentHashMap<>();
        CountDownLatch reached = new CountDownLatch(1);
        try (ArtifactStore store = new ArtifactStore(restored.store())) {
            Run saved = store.root().runs.get(runId);
            assertThat(saved.state()).isEqualTo(RunState.EXECUTING);
            assertThat(saved.parkedAt()).as("the snapshot is the moment of the seam — the park "
                + "that came after it in the original is not in it").isNull();

            HarnessSnapshot.repointProject(store, projectId, restored.repo());
            SwarmEngine resumedWorkers = run -> {
                dispatched.put(run.id(), run);
                reached.countDown();
                throw new RunMustPark("the test stops the resumed run too");
            };
            WorkflowEngine engine = new WorkflowEngine(NO_RUNTIME, resumedWorkers, noModel(), store,
                new CloudGate(0, null), restored.repo(), null, null, null, List.of());

            List<RunResumer.Resumed> resumed = new RunResumer(store,
                id -> projectId.equals(id) ? engine : null).resumeAll();

            assertThat(resumed).singleElement().satisfies(r -> {
                assertThat(r.runId()).isEqualTo(runId);
                assertThat(r.state()).isEqualTo(RunState.EXECUTING);
                assertThat(r.repoPath()).isEqualTo(restored.repo());
            });
            assertThat(reached.await(60, TimeUnit.SECONDS))
                .as("the resumed engine dispatched the run").isTrue();
            assertThat(dispatched.get(runId).state()).isEqualTo(RunState.EXECUTING);
            assertThat(store.getProject(projectId).primaryPath())
                .isEqualTo(restored.repo().toString());
            awaitParkPersisted(store, runId);
        }
    }

    /** The stand-in parks the run; let that write land before the store is closed under it. */
    private static void awaitParkPersisted(ArtifactStore store, UUID runId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null && run.heartbeatAt() != null
                    && !run.heartbeatAt().isBefore(run.parkedAt())) {
                store.append(() -> null).get();
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the resumed run's park never reached the store");
    }
}
