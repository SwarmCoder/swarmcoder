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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run being built by a swarm must not read as abandoned.
 *
 * <p>On 2026-09-01 the operator was told "Nothing has touched this run for 29m — the process
 * driving it is gone" about a run whose workers had produced a step thirty-five seconds earlier.
 * A workflow stage that lasts an hour writes nothing to the run in that hour, and the freshness
 * stamp was written only on a stage change.
 */
class RunHeartbeatTest {

    @TempDir
    Path storeDir;

    @Test
    void aWorkerTakingStepsKeepsItsRunLookingAlive() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID runId = UUID.randomUUID();
            Instant longAgo = Instant.now().minusSeconds(29 * 60);
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                UUID.randomUUID(), null, null, UUID.randomUUID(), null, longAgo,
                new RunReport(runId, "a bookshelf"));
            run.setHeartbeatAt(longAgo);
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();

            RunHeartbeat heartbeat = new RunHeartbeat(store);
            UUID sessionId = UUID.randomUUID();
            heartbeat.sessionStarted(new AgentSessionRecord(sessionId, runId, UUID.randomUUID(),
                UUID.randomUUID(), 0, "worker-0", "qwen3.8-27b", 0.1,
                Instant.now(), null, "RUNNING", null, 0, 0, List.of()));

            assertThat(store.root().runs.get(runId).heartbeatAt())
                .describedAs("a worker opening a session is something driving the run")
                .isAfter(longAgo);

            // Thirty-one minutes of work later, still nothing but worker steps.
            Instant afterOpen = store.root().runs.get(runId).heartbeatAt();
            Thread.sleep(5);
            forceNextStamp(heartbeat, runId);
            heartbeat.event(sessionId, new TraceEvent(7, Instant.now(),
                TraceEventKind.TOOL_CALL, "exec", "mvn -q test-compile", null, 1200));

            assertThat(store.root().runs.get(runId).heartbeatAt())
                .describedAs("a step a worker took is the definition of something driving the run")
                .isAfter(afterOpen);
        }
    }

    @Test
    void itDoesNotWriteTheStoreOnEveryStep() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID runId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                UUID.randomUUID(), null, null, UUID.randomUUID(), null, Instant.now(),
                new RunReport(runId, "a bookshelf"));
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();

            RunHeartbeat heartbeat = new RunHeartbeat(store);
            UUID sessionId = UUID.randomUUID();
            heartbeat.sessionStarted(new AgentSessionRecord(sessionId, runId, UUID.randomUUID(),
                UUID.randomUUID(), 0, "worker-0", "qwen3.8-27b", 0.1,
                Instant.now(), null, "RUNNING", null, 0, 0, List.of()));
            Instant afterOpen = store.root().runs.get(runId).heartbeatAt();

            for (int i = 0; i < 50; i++) {
                heartbeat.event(sessionId, new TraceEvent(i, Instant.now(),
                    TraceEventKind.TOOL_CALL, "exec", "ls", null, 1));
            }

            assertThat(store.root().runs.get(runId).heartbeatAt())
                .describedAs("a busy swarm emits several steps a second; the stamp only has to be "
                    + "fresher than the five minutes after which a run reads as dead")
                .isEqualTo(afterOpen);
        }
    }

    /** Pretends the throttle window has passed, without making the test wait thirty seconds. */
    private static void forceNextStamp(RunHeartbeat heartbeat, UUID runId) throws Exception {
        var field = RunHeartbeat.class.getDeclaredField("lastStamp");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var stamps = (java.util.Map<UUID, Long>) field.get(heartbeat);
        stamps.put(runId, System.currentTimeMillis() - RunHeartbeat.MIN_GAP_MILLIS - 1);
    }
}
