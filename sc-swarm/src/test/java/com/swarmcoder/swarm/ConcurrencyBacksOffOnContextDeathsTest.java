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
package com.swarmcoder.swarm;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.AdaptiveConcurrency;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ServerCapabilities;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.TraceHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's ask, driven through the real stack: "if we find we are always running out of context
 * for a task, back off on the number of concurrent sessions and increase each one's context size."
 *
 * <p>Real dispatcher, real worker loop, real Koog client over HTTP, real compaction — against a
 * scripted {@link FakeVllm} that hands back a full-size tool output every turn until the history
 * cannot be compacted under its room any more, which is the death the 2026-09-01 workers died.
 * No model is called. The server's figures are a scaled-down Spark: a pool of 65536 tokens shared
 * four ways gives each worker 14336 tokens of room at startup (65536 / 4 = 16384, 90% of that is
 * 14745, rounded down to a whole 1024); shared two ways it is 28672.
 */
@ModelCodeOnThisPc
class ConcurrencyBacksOffOnContextDeathsTest {

    private static final int POOL = 65_536;
    private static final int SERVED = 262_144;
    private static final int AT_FOUR = 14_336;
    private static final int AT_TWO = 28_672;

    @TempDir
    Path repoDir;

    /** The room each worker session was actually opened with, by session — read off the trace hub. */
    private final Map<UUID, Integer> roomBySession = new ConcurrentHashMap<>();
    private final List<UUID> sessionsInOrder = new ArrayList<>();

    private void initRepo() throws Exception {
        run("git init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        // Files large enough that every read returns the toolbox's full 8000-character cap, and
        // different from one another: reading the SAME content twice is a fruitless call, and six
        // of those in a row is the stall guard, which is a different death from the one measured.
        for (int i = 0; i < BIG_FILES; i++) {
            Files.writeString(repoDir.resolve("Big" + i + ".java"),
                "// file " + i + " " + ("x" + i).repeat(12_000));
        }
        run("git add -A");
        run("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private static final int BIG_FILES = 64;

    /**
     * Never finishes: the shape of the workers that died on 2026-09-01 — a full-size read most
     * turns, a write every fourth, until the history cannot be compacted under its room. Counted
     * across all workers rather than read out of the conversation, because compaction rewrites the
     * conversation and a script that read its own past out of it would change with every rewrite.
     */
    private static Function<String, FakeVllm.Reply> keepReading() {
        AtomicInteger turn = new AtomicInteger();
        return conversation -> {
            int n = turn.getAndIncrement();
            if (n % 4 == 3) {
                return FakeVllm.Reply.toolCall("write_file",
                    "{\"path\":\"README.md\",\"content\":\"" + ("y" + n).repeat(3000) + "\"}");
            }
            return FakeVllm.Reply.toolCall("read",
                "{\"path\":\"Big" + (n % BIG_FILES) + ".java\"}");
        };
    }

    /** Finishes at once: a clean completion that fitted its room. */
    private static FakeVllm.Reply finishAtOnce(String conversation) {
        return FakeVllm.Reply.toolCall("report_done", "{\"summary\":\"nothing to do\"}");
    }

    private static Task task(int n) {
        return new Task(UUID.randomUUID(), 1, "Improve README", "Add a line to README.md",
            Set.of("README.md"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(n, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private record Stack(SwarmDispatcher dispatcher, AdaptiveConcurrency controller,
                         ModelProfile spark, ModelProfile other) { }

    private Stack stack(FakeVllm spark, FakeVllm other, AtomicReference<Function<String, FakeVllm.Reply>> ignored) {
        ServerCapabilities caps = new ServerCapabilities(
            spark.baseUrl(), SERVED, 4, POOL, "answered");
        // The figures in force at startup, exactly as DependencyGraph derives them: the shape's
        // numbers with the server's own written over them.
        ModelQuirks shape = new ModelQuirks("test model", 4096, false, null, null, true, true,
            false, SERVED, 32_768, 1024, 16, false);
        ModelQuirks inForce = caps.applyTo(shape);
        assertThat(inForce.workingContextTokens()).isEqualTo(AT_FOUR);
        assertThat(inForce.maxConcurrentSequences()).isEqualTo(4);

        ModelProfile sparkProfile = new ModelProfile("spark",
            new AgentRuntime.ModelEndpoint(spark.baseUrl(), "", "spark", SERVED, inForce),
            ModelProfile.Kind.WORKER, inForce, 0);
        ModelProfile otherProfile = new ModelProfile("other",
            new AgentRuntime.ModelEndpoint(other.baseUrl(), "", "other", SERVED, inForce),
            ModelProfile.Kind.WORKER, inForce, 0);

        AdaptiveConcurrency controller = new AdaptiveConcurrency();
        controller.register("spark", spark.baseUrl(), inForce, caps, false, 8, false);
        controller.register("other", other.baseUrl(), inForce,
            new ServerCapabilities(other.baseUrl(), SERVED, 4, POOL,
                "answered"), false, 8, false);

        TraceHub traceHub = new TraceHub(null);
        traceHub.addListener(new TraceHub.Listener() {
            @Override
            public void sessionStarted(AgentSessionRecord snapshot) {
                // The room the worker was opened with — the same figure the live-workers band
                // draws on its chip, read from the same place.
                roomBySession.put(snapshot.id(), traceHub.contextBudgetTokens(snapshot.id()));
                synchronized (sessionsInOrder) {
                    sessionsInOrder.add(snapshot.id());
                }
            }
        });
        SwarmDispatcher dispatcher = new SwarmDispatcher(
            new InferenceScheduler(8, 1024L * 1024 * 1024, 1024),
            new GitService(repoDir),
            new KoogAgentRuntime(traceHub),
            new ModelProfileRegistry(List.of(sparkProfile, otherProfile)));
        dispatcher.setConcurrencyController(controller);
        return new Stack(dispatcher, controller, sparkProfile, otherProfile);
    }

    @Test
    void aWaveThatDiesOfRoomIsFollowedByASmallerWaveWithMoreRoomEachAndRecoversWhenClean() throws Exception {
        initRepo();
        AtomicReference<Function<String, FakeVllm.Reply>> script =
            new AtomicReference<>(keepReading());
        try (FakeVllm spark = new FakeVllm(c -> script.get().apply(c));
             FakeVllm other = new FakeVllm(ConcurrencyBacksOffOnContextDeathsTest::finishAtOnce)) {
            Stack stack = stack(spark, other, script);
            UUID runId = UUID.randomUUID();

            // Wave 1: four workers, 14336 tokens of room each, every one of them reading until its
            // history can no longer be compacted under that room.
            List<WorkerResult> wave1 = stack.dispatcher().dispatch(task(4), runId);
            assertThat(wave1).hasSize(4);
            assertThat(wave1).allSatisfy(result -> {
                assertThat(result.candidate().state()).isEqualTo(CandidateState.KILLED);
                assertThat(result.candidate().killReason()).isEqualTo(KillReason.BUDGET_EXCEEDED);
                assertThat(result.contextDeath()).as("the death was about room").isNotNull();
            });
            assertThat(roomOf(0, 4)).containsOnly(AT_FOUR);

            // Decided at the fourth death, applied at the next dispatch.
            AdaptiveConcurrency.Plan plan = stack.controller().plan("spark");
            assertThat(plan.concurrency()).isEqualTo(2);
            assertThat(plan.workingContextTokens()).isEqualTo(AT_TWO);
            assertThat(stack.controller().throttled()).singleElement().asString()
                .contains("2 workers at a time instead of 4")
                .contains(AT_TWO + " tokens of room instead of " + AT_FOUR);

            // The other server's pool is untouched by the first one's deaths.
            assertThat(stack.controller().plan("other").concurrency()).isEqualTo(4);
            assertThat(stack.controller().plan("other").workingContextTokens()).isEqualTo(AT_FOUR);

            // Wave 2: the same task asks for four again and gets two, each opened with the room the
            // other two gave up. The script is clean now.
            script.set(ConcurrencyBacksOffOnContextDeathsTest::finishAtOnce);
            List<WorkerResult> wave2 = stack.dispatcher().dispatch(task(4), runId);
            assertThat(wave2).hasSize(2);
            assertThat(wave2).allSatisfy(result ->
                assertThat(result.contextDeath()).as("a clean finish").isNull());
            assertThat(roomOf(4, 6)).containsOnly(AT_TWO);
            assertThat(stack.controller().plan("spark").concurrency())
                .as("one clean wave of two is not yet two").isEqualTo(2);

            // Wave 3: two more clean completions make two clean waves at two — one step back up.
            List<WorkerResult> wave3 = stack.dispatcher().dispatch(task(4), runId);
            assertThat(wave3).hasSize(2);
            assertThat(roomOf(6, 8)).containsOnly(AT_TWO);
            AdaptiveConcurrency.Plan recovered = stack.controller().plan("spark");
            assertThat(recovered.concurrency()).isEqualTo(4);
            assertThat(recovered.workingContextTokens()).isEqualTo(AT_FOUR);
            assertThat(stack.controller().throttled()).isEmpty();

            // Wave 4: back to four workers with the startup room.
            List<WorkerResult> wave4 = stack.dispatcher().dispatch(task(4), runId);
            assertThat(wave4).hasSize(4);
            assertThat(roomOf(8, 12)).containsOnly(AT_FOUR);
        }
    }

    /** The room of the sessions opened at positions [from, to) in dispatch order. */
    private List<Integer> roomOf(int from, int to) {
        List<UUID> ids;
        synchronized (sessionsInOrder) {
            ids = new ArrayList<>(sessionsInOrder.subList(from, Math.min(to, sessionsInOrder.size())));
        }
        assertThat(ids).hasSize(to - from);
        return ids.stream().map(roomBySession::get).toList();
    }

    private void run(String command) throws Exception {
        Process p = new ProcessBuilder(gitCmd(command))
            .directory(repoDir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }

    private static List<String> gitCmd(String command) {
        return System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command)
            : List.of("sh", "-c", command);
    }
}
