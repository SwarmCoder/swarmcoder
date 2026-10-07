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
package com.swarmcoder.store;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing the store must not throw away, or blow up on, a write that is already queued.
 *
 * <h2>The fault these tests pin</h2>
 *
 * <p>{@code close()} used to call {@code writerThread.shutdown()} — which refuses new work and
 * returns immediately, without waiting for the work already queued — and then close the storage
 * manager underneath the writer thread that was still running it. Every queued write then failed on
 * the {@code swarmcoder-artifact-store-writer} thread with:
 *
 * <pre>
 * org.eclipse.serializer.persistence.exceptions.PersistenceExceptionTransfer
 *   at EmbeddedStorageBinaryTarget$Default.write(EmbeddedStorageBinaryTarget.java:123)
 *   at BinaryStorer$Default.commit(BinaryStorer.java:634)
 * Caused by: org.eclipse.store.storage.exceptions.StorageException: Problem in channel #0
 * Caused by: java.lang.NullPointerException: Cannot invoke
 *   "StorageLiveDataFile$Default.needsRetirement(StorageDataFileEvaluator)"
 *   because "this.headFile" is null
 * </pre>
 *
 * <p>Nobody ever saw that exception: it went into a {@code Future} the caller had discarded. The
 * only symptom was a field that had been written, was true in memory, and was absent after a
 * restart — a park's reason, a heartbeat stamp — <em>sometimes</em>, depending entirely on how much
 * of the queue had drained before the close landed. That is the whole of the "persistence is
 * flaky" report of 2026-09-03.
 *
 * <p>Every loop here is deterministic: before the fix, the FIRST iteration of each one failed.
 */
class QueuedWritesSurviveCloseTest {

    /** Enough writes that the queue cannot possibly have drained by the time close() lands. */
    private static final int QUEUED_WRITES = 400;

    /** Iterations of each loop. Nothing here depends on timing; 20 is repetition, not sampling. */
    private static final int ITERATIONS = 20;

    @TempDir
    Path storeDir;

    private static Run newRun(UUID id) {
        return new Run(id, WorkflowKind.GREENFIELD, RunState.PLAN,
            null, null, null, null, null, Instant.now(), new RunReport(id, "a goal"));
    }

    @Test
    void aParkWrittenAndNotWaitedForIsStillOnDiskAfterAClose() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Path dir = storeDir.resolve("park" + i);
            UUID runId = UUID.randomUUID();

            try (ArtifactStore store = new ArtifactStore(dir)) {
                Run run = newRun(runId);
                store.append(() -> {
                    store.root().runs.put(runId, run);
                    return null;
                }).get();
                // Exactly what RunHeartbeat.touch does: mutate the already-stored run, then hand
                // the write to the writer thread WITHOUT waiting for it.
                run.setParkedAt(Instant.now());
                run.setParkReason("the planner could not produce a plan #" + i);
                store.updateRunLater(run);
            }

            try (ArtifactStore reopened = new ArtifactStore(dir)) {
                Run afterRestart = reopened.root().runs.get(runId);
                assertThat(afterRestart).as("run present after restart, iteration %d", i).isNotNull();
                assertThat(afterRestart.parkReason())
                    .as("the reason the run stopped, iteration %d", i)
                    .isEqualTo("the planner could not produce a plan #" + i);
                assertThat(afterRestart.parkedAt())
                    .as("when it stopped, iteration %d", i).isNotNull();
            }
        }
    }

    @Test
    void aCloseOnTopOfADeepQueueBreaksNoWriteInIt() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Path dir = storeDir.resolve("deep" + i);
            UUID runId = UUID.randomUUID();
            List<Future<?>> queued = new ArrayList<>();

            try (ArtifactStore store = new ArtifactStore(dir)) {
                Run run = newRun(runId);
                store.append(() -> {
                    store.root().runs.put(runId, run);
                    return null;
                }).get();
                for (int k = 0; k < QUEUED_WRITES; k++) {
                    run.setHeartbeatAt(Instant.now());
                    queued.add(store.updateRunLater(run));
                }
                run.setParkReason("last one in, iteration " + i);
                queued.add(store.updateRunLater(run));
            }

            for (int k = 0; k < queued.size(); k++) {
                assertThat(queued.get(k).isDone())
                    .as("queued write %d of iteration %d ran before the store closed", k, i)
                    .isTrue();
                // Rethrows the storage library's own exception if this write failed.
                queued.get(k).get();
            }

            try (ArtifactStore reopened = new ArtifactStore(dir)) {
                Run afterRestart = reopened.root().runs.get(runId);
                assertThat(afterRestart.parkReason())
                    .as("the LAST write in the queue, iteration %d", i)
                    .isEqualTo("last one in, iteration " + i);
                assertThat(afterRestart.heartbeatAt())
                    .as("the stamp, iteration %d", i).isNotNull();
            }
        }
    }

    /**
     * The heartbeat writer and the workflow writer stamping the same run at the same time. Both go
     * through the store's single writer thread, so neither can be mid-store when the other starts;
     * this proves that holds under load, that neither throws, and that nothing either wrote is lost.
     */
    @Test
    void twoThreadsWritingTheSameRunLoseNothingAndThrowNothing() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < ITERATIONS; i++) {
            Path dir = storeDir.resolve("race" + i);
            UUID runId = UUID.randomUUID();
            final int iteration = i;

            try (ArtifactStore store = new ArtifactStore(dir)) {
                Run run = newRun(runId);
                store.append(() -> {
                    store.root().runs.put(runId, run);
                    return null;
                }).get();

                CountDownLatch go = new CountDownLatch(1);
                Thread heartbeat = new Thread(() -> {
                    try {
                        go.await();
                        for (int k = 0; k < 200; k++) {
                            run.setHeartbeatAt(Instant.now());
                            store.updateRunLater(run);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, "test-heartbeat-writer");
                Thread workflow = new Thread(() -> {
                    try {
                        go.await();
                        for (int k = 0; k < 200; k++) {
                            run.setProgressCommit("commit-" + iteration + "-" + k);
                            store.updateRun(run);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, "test-workflow-writer");

                heartbeat.start();
                workflow.start();
                go.countDown();
                heartbeat.join();
                workflow.join();

                run.setParkedAt(Instant.now());
                run.setParkReason("parked after the race, iteration " + i);
                store.updateRun(run);
            }

            assertThat(failure.get()).as("neither writer threw, iteration %d", i).isNull();

            try (ArtifactStore reopened = new ArtifactStore(dir)) {
                Run afterRestart = reopened.root().runs.get(runId);
                assertThat(afterRestart.parkReason())
                    .as("the park written after the race, iteration %d", i)
                    .isEqualTo("parked after the race, iteration " + i);
                assertThat(afterRestart.progressCommit())
                    .as("the last commit the workflow writer recorded, iteration %d", i)
                    .isEqualTo("commit-" + i + "-199");
                assertThat(afterRestart.heartbeatAt())
                    .as("the stamp the heartbeat writer left, iteration %d", i).isNotNull();
            }
        }
    }
}
