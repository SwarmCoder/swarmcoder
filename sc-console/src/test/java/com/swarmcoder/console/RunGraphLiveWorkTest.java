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
package com.swarmcoder.console;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkerHealth;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.LiveWorkerSessions;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the run graph knows about workers that are still working.
 *
 * <p>Written after an afternoon in which the operator watched four containers burn CPU while the
 * graph showed two empty task boxes, and was wrong three times in one day about whether a build was
 * alive.
 */
class RunGraphLiveWorkTest {

    @TempDir
    Path storeDir;

    @Test
    void aWorkerStillWorkingIsInTheGraph_withHowLongAndWhatItIsDoing() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seedRun(store, runId, taskId);

            for (int i = 0; i < 4; i++) {
                UUID sessionId = LiveWorkerSessions.open(hub, runId, taskId, i,
                    "qwen3.8-27b", 0.1 + i * 0.23);
                LiveWorkerSessions.step(hub, sessionId, "exec", "mvn -q test-compile");
            }

            var snapshot = new GraphServiceImpl().snapshot(runId.toString());
            assertThat(snapshot.getCandidates())
                .describedAs("a worker that has been dispatched and has not finished is an "
                    + "attempt in flight, and the graph draws one chip per attempt")
                .hasSize(4);
            assertThat(snapshot.getLiveWorkers())
                .describedAs("and the frame says outright how many are working")
                .isEqualTo(4);
            assertThat(snapshot.getAtMillis())
                .describedAs("with the server's clock, so a browser whose clock is out cannot "
                    + "turn a healthy worker into a hung one")
                .isGreaterThan(0);
            var worker = snapshot.getCandidates().get(0);
            assertThat(worker.getState()).isEqualTo("RUNNING");
            assertThat(worker.getOpenedAtMillis())
                .describedAs("how long it has been going — the first half of telling working "
                    + "from stuck")
                .isGreaterThan(0);
            assertThat(worker.getLastStepKind())
                .describedAs("and what it is doing — the second half")
                .isEqualTo("TOOL_CALL");
            assertThat(worker.getLastStepLabel()).isEqualTo("exec");
            assertThat(worker.getLastStepAtMillis()).isGreaterThan(0);
        }
    }

    /**
     * What the graph knows about how close a running worker is to dying.
     *
     * <p>Every worker that died on 2026-09-01 died the same way: one request to the model outlasted
     * the fifteen minutes it is allowed, because the conversation is re-sent whole on every turn
     * and nothing trims it. So the size of the conversation is the number that predicts death, the
     * budget it is measured against is the model profile's own, and both have to reach the screen
     * inside the frame the graph is already assembled from — a second call per chip would be a
     * round trip per worker, several times a second.
     */
    @Test
    void aRunningWorkerCarriesTheNumbersThatSayWhetherToWorry() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seedRun(store, runId, taskId);

            UUID healthy = LiveWorkerSessions.open(hub, runId, taskId, 0, "qwen3.8-27b", 0.1,
                8_000);
            LiveWorkerSessions.answered(hub, healthy, "on it", 12_000);
            LiveWorkerSessions.step(hub, healthy, "exec", "mvn -q test-compile");
            LiveWorkerSessions.resultReturned(hub, healthy, "exec", "BUILD SUCCESS");

            UUID bloated = LiveWorkerSessions.open(hub, runId, taskId, 1, "qwen3.8-27b", 0.33,
                8_000);
            LiveWorkerSessions.answered(hub, bloated, "reading", 20_000);
            LiveWorkerSessions.answered(hub, bloated, "still reading", 42_000);

            var snapshot = new GraphServiceImpl().snapshot(runId.toString());
            var first = snapshot.getCandidates().get(0);
            var second = snapshot.getCandidates().get(1);

            assertThat(first.getTurns())
                .describedAs("how far in it is. This was a hardcoded zero for the whole life of "
                    + "every running worker: the runtime counted turns in a field of its own and "
                    + "only handed the number over when the session CLOSED")
                .isEqualTo(1);
            assertThat(second.getTurns()).isEqualTo(2);
            assertThat(second.getTokens())
                .describedAs("and how big its conversation has grown — the number that actually "
                    + "predicts whether the next request finishes in time")
                .isEqualTo(42_000);
            assertThat(second.getContextBudgetTokens())
                .describedAs("with the budget it is measured against, because 42k is comfortable "
                    + "for a worker allowed 262k and is trouble for one allowed 32k")
                .isEqualTo(32_768);
            // And how much of that conversation is the fixed head of the prompt - the part every
            // worker on this task sends identically and no compaction ever rewrites, so the model
            // server prefills it once for the whole group. It rides on this same frame, measured
            // when the session opened, because a second call per chip would be a round trip per
            // worker several times a second.
            assertThat(first.getPrefillTokens())
                .describedAs("the head, estimated at four characters to the token")
                .isEqualTo(8_000);
            assertThat(second.getPrefillTokens())
                .describedAs("the same head for both workers on the task, which is the whole "
                    + "reason it is worth prefilling once")
                .isEqualTo(8_000);
            assertThat(second.getTokens() - second.getPrefillTokens())
                .describedAs("so 34,000 of this worker's 42,000 are its own, and that is the part "
                    + "the operator has been waiting on all week without being able to name it")
                .isEqualTo(34_000);

            long now = snapshot.getAtMillis();
            assertThat(WorkerHealth.of(first.getTokens(), first.getContextBudgetTokens(),
                    first.getLastStepKind(), first.getLastStepAtMillis(), now))
                .describedAs("a worker inside its budget that just spoke is not marked")
                .isEqualTo(WorkerHealth.Kind.FINE);
            assertThat(WorkerHealth.of(second.getTokens(), second.getContextBudgetTokens(),
                    second.getLastStepKind(), second.getLastStepAtMillis(), now))
                .describedAs("a worker past what its model allows is")
                .isEqualTo(WorkerHealth.Kind.OVER_BUDGET);
        }
    }

    /**
     * A worker whose numbers have not arrived is not a worker in trouble.
     *
     * <p>The first seconds of every worker look exactly like this: opened, no answer yet, so no
     * token count. If a missing count read as trouble, every worker would be orange for its first
     * few seconds and the colour would mean nothing.
     */
    @Test
    void aWorkerWithNoTokenCountYetIsNotMarked() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seedRun(store, runId, taskId);
            LiveWorkerSessions.open(hub, runId, taskId, 0, "qwen3.8-27b", 0.1);

            var snapshot = new GraphServiceImpl().snapshot(runId.toString());
            var worker = snapshot.getCandidates().get(0);
            assertThat(worker.getTokens()).isZero();
            assertThat(WorkerHealth.of(worker.getTokens(), worker.getContextBudgetTokens(),
                    worker.getLastStepKind(), worker.getLastStepAtMillis(), snapshot.getAtMillis()))
                .isEqualTo(WorkerHealth.Kind.UNKNOWN);
        }
    }

    /**
     * The frame the browser fetches by hand must never be older than one it already holds.
     *
     * <p>This is the fault that let a Console hold correct data and show an empty box. The browser
     * drops a run-graph frame whose sequence is not newer than its current one — the rule that
     * stops a late push overwriting a newer one. A snapshot fetched over RMI carried no sequence at
     * all, so from the first pushed frame onwards every hand-fetched snapshot was thrown away as
     * stale, and re-opening the graph could not recover it: only reloading the page could.
     */
    @Test
    void aFreshlyFetchedSnapshotIsNeverOlderThanOneAlreadyOnScreen() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            seedRun(store, runId, UUID.randomUUID());

            GraphServiceImpl graph = new GraphServiceImpl();
            long first = graph.snapshot(runId.toString()).getSeq();
            long second = graph.snapshot(runId.toString()).getSeq();

            assertThat(first)
                .describedAs("a snapshot with no sequence is a snapshot the browser discards")
                .isGreaterThan(0);
            assertThat(second)
                .describedAs("and the later of two fetches is the later frame")
                .isGreaterThan(first);
        }
    }

    @Test
    void aWorkerThatHasStoppedIsStillOnTheGraph_withItsNumbersAndItsReason() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TraceHub hub = new TraceHub(null);
            ConsoleContext.set(new ConsoleContext(store, hub,
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seedRun(store, runId, taskId);

            // w0 finished and nothing has judged it yet - the window in which it used to be in no
            // collection this frame reads, and vanished off the screen for the minutes
            // verification takes. w1 was archived with a verdict. w2 is still going.
            UUID finished = UUID.randomUUID();
            UUID archived = UUID.randomUUID();
            var report = new com.swarmcoder.domain.VerificationReport();
            report.setId(UUID.randomUUID());
            report.setCompiles(false);
            report.setLogTail("[verdict] NOT SURVIVED \u2014 the candidate does not compile\n");
            var solution = new com.swarmcoder.domain.CandidateSolution(archived, taskId, 1,
                "swarm/w1", new com.swarmcoder.domain.SamplingConfig("qwen3.8-27b", 0.8, 0, "", ""),
                "", report, null, null, com.swarmcoder.domain.CandidateState.FAILED, null);
            var closed = new com.swarmcoder.domain.AgentSessionRecord(UUID.randomUUID(), runId,
                taskId, finished, 0, "worker-0", "qwen3.8-27b", 0.1,
                Instant.now().minusSeconds(600), Instant.now(), "COMPLETED", null, 15, 41_139,
                List.of());
            var judgedSession = new com.swarmcoder.domain.AgentSessionRecord(UUID.randomUUID(),
                runId, taskId, archived, 1, "worker-1", "qwen3.8-27b", 0.8,
                Instant.now().minusSeconds(700), Instant.now(), "COMPLETED", null, 17, 40_541,
                List.of());
            store.append(() -> {
                store.root().agentSessions().put(closed.id(),
                    org.eclipse.serializer.reference.Lazy.Reference(closed));
                store.root().agentSessions().put(judgedSession.id(),
                    org.eclipse.serializer.reference.Lazy.Reference(judgedSession));
                store.root().candidateArchives.put(archived,
                    org.eclipse.serializer.reference.Lazy.Reference(solution));
                return null;
            }).get();
            LiveWorkerSessions.open(hub, runId, taskId, 2, "qwen3.8-27b", 0.5);

            var snapshot = new GraphServiceImpl().snapshot(runId.toString());
            assertThat(snapshot.getCandidates())
                .describedAs("all three: one still writing, one done and unjudged, one failed - "
                    + "a status display may change what it says about a worker, never stop "
                    + "saying anything about it")
                .hasSize(3);

            var stopped = byWorker(snapshot, 0);
            assertThat(stopped.getState())
                .describedAs("neither running nor judged, and it has a state that says so")
                .isEqualTo("FINISHED");
            assertThat(stopped.getTurns()).isEqualTo(15);
            assertThat(stopped.getTokens())
                .describedAs("with the numbers that were on its chip a moment earlier - a chip "
                    + "that swaps them for something else reads as a different worker")
                .isEqualTo(41_139);
            assertThat(stopped.getPrefillTokens())
                .describedAs("and NOT the head of its prompt, which is a fact about a "
                    + "conversation still being sent. A stopped worker gains no new number; its "
                    + "chip goes back to the two it always had")
                .isZero();

            var failed = byWorker(snapshot, 1);
            assertThat(failed.getState()).isEqualTo("FAILED");
            assertThat(failed.getFailReason())
                .describedAs("carrying the verdict the verification stage already wrote, so a red "
                    + "chip is not a red square with nothing behind it")
                .isEqualTo("the candidate does not compile");
            assertThat(failed.getTokens())
                .describedAs("and an archived attempt keeps its numbers too: they live on its "
                    + "session, not on the candidate")
                .isEqualTo(40_541);
        }
    }

    @Test
    void aRepairWorkerSaysWhichAttemptItIsFixing() {
        int second = com.swarmcoder.domain.RepairIndex.of(2, 1);
        assertThat(second)
            .describedAs("the number the engine writes for the second go at what w2 got wrong")
            .isEqualTo(121);
        assertThat(com.swarmcoder.domain.RepairIndex.isRepair(121)).isTrue();
        assertThat(com.swarmcoder.domain.RepairIndex.repairOf(121))
            .describedAs("and it decodes back to w2, which is the whole point: w121 on its own "
                + "read as a restart")
            .isEqualTo(2);
        assertThat(com.swarmcoder.domain.RepairIndex.attempt(121)).isEqualTo(1);
        assertThat(com.swarmcoder.domain.RepairIndex.isRepair(3))
            .describedAs("a first-wave worker is not a repair")
            .isFalse();
    }

    private static com.swarmcoder.console.api.GraphCandidateDto byWorker(
            com.swarmcoder.console.api.RunGraphDto snapshot, int workerIndex) {
        return snapshot.getCandidates().stream()
            .filter(candidate -> candidate.getWorkerIndex() == workerIndex)
            .findFirst().orElseThrow();
    }

    private static void seedRun(ArtifactStore store, UUID runId, UUID taskId) throws Exception {
        var graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(new Task(taskId, 1, "Add shared Book model", "", Set.of("shared/src/main"),
                Set.of(), List.of(), null, null, null,
                new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.DISPATCHED)),
            List.of());
        store.append(() -> {
            store.root().taskGraphs.put(graph.id(), graph);
            store.root().runs.put(runId, new Run(runId, WorkflowKind.GREENFIELD,
                RunState.EXECUTING, null, null, null, graph.id(), null,
                Instant.now(), new RunReport(runId, "a bookshelf that stores books")));
            return null;
        }).get();
    }
}
