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
import com.swarmcoder.domain.PendingExec;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's {@code exec} call must be recorded to durable storage BEFORE the command runs, so a
 * harness death mid-command (runs 21 and 29: a worker's shell command killed the harness JVM
 * itself) leaves evidence of which command it was.
 *
 * <p>{@code KoogAgentRuntime}'s worker loop calls {@code tracer.toolCall} for a turn's tool calls
 * strictly before it invokes any of them — see {@link PendingExecRecorder}'s class javadoc. This
 * test proves the piece that ordering makes possible: by the time {@link #event} returns for a
 * {@code TOOL_CALL} of {@code exec}, the record already exists in the store, which is exactly what
 * "the process has not started yet" needs.
 */
class PendingExecRecorderTest {

    @TempDir
    Path storeDir;

    @Test
    void anExecCallIsOnDiskBeforeTheListenerReturnsSoADeathRightAfterStillLeavesEvidence() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            PendingExecRecorder recorder = new PendingExecRecorder(store);
            UUID sessionId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            recorder.sessionStarted(new AgentSessionRecord(sessionId, runId, taskId,
                UUID.randomUUID(), 1, "worker-1", "qwen3.8-27b", 0.1,
                Instant.now(), null, "RUNNING", null, 0, 0, List.of()));

            // The exact ordering KoogAgentRuntime relies on: toolCall (this event) fires, and only
            // AFTER it returns does the runtime ever call the real exec method.
            recorder.event(sessionId, new TraceEvent(1, Instant.now(), TraceEventKind.TOOL_CALL,
                "exec", "mvn -q test-compile", null, 100));

            // No sessionEnded, no sessionClosed — the process "died" right here. The record must
            // already be readable from a FRESH store handle, not merely held in memory.
            PendingExec found = store.root().pendingExecs().get(sessionId);
            assertThat(found).as("the exec-start record must exist before this test even asks").isNotNull();
            assertThat(found.runId()).isEqualTo(runId);
            assertThat(found.taskId()).isEqualTo(taskId);
            assertThat(found.role()).isEqualTo("worker-1");
            assertThat(found.command()).isEqualTo("mvn -q test-compile");
        }

        // Reopen the store exactly as a restarted harness would — proves it was actually flushed
        // to disk, not just sitting in the live root object graph of the process that wrote it.
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            assertThat(reopened.root().pendingExecs()).hasSize(1);
        }
    }

    @Test
    void theRecordIsClearedOnceTheCommandReturnsAResult() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            PendingExecRecorder recorder = new PendingExecRecorder(store);
            UUID sessionId = UUID.randomUUID();
            recorder.sessionStarted(new AgentSessionRecord(sessionId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0, "worker-0", "qwen3.8-27b", 0.1,
                Instant.now(), null, "RUNNING", null, 0, 0, List.of()));
            recorder.event(sessionId, new TraceEvent(1, Instant.now(), TraceEventKind.TOOL_CALL,
                "exec", "echo hi", null, 10));
            assertThat(store.root().pendingExecs()).containsKey(sessionId);

            recorder.event(sessionId, new TraceEvent(2, Instant.now(), TraceEventKind.TOOL_RESULT,
                "exec", "exit=0\nhi", null, 12));

            // clearPendingExec is fire-and-forget; give the writer thread a moment.
            awaitCleared(store, sessionId);
            assertThat(store.root().pendingExecs()).doesNotContainKey(sessionId);
        }
    }

    @Test
    void toolCallsForOtherToolsAreIgnored() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            PendingExecRecorder recorder = new PendingExecRecorder(store);
            UUID sessionId = UUID.randomUUID();
            recorder.sessionStarted(new AgentSessionRecord(sessionId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0, "worker-0", "qwen3.8-27b", 0.1,
                Instant.now(), null, "RUNNING", null, 0, 0, List.of()));

            recorder.event(sessionId, new TraceEvent(1, Instant.now(), TraceEventKind.TOOL_CALL,
                "read", "src/main/java/App.java", null, 5));

            assertThat(store.root().pendingExecs()).isEmpty();
        }
    }

    private static void awaitCleared(ArtifactStore store, UUID sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (store.root().pendingExecs().containsKey(sessionId) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
