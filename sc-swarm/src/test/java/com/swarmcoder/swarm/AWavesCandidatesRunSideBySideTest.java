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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every candidate of every task in a wave has its model call in flight at the same moment, when the
 * model server serves that many at once.
 *
 * <p><b>The incident.</b> Harness run 66 (2026-10-02) ran against a server that serves four requests
 * at once and averaged 1.1 in flight. A wave of two tasks with two candidates each took 76 minutes
 * while its four workers' own sessions were 27, 11, 15 and 14 minutes: they ran one after another.
 * The dispatcher had started all four in the same second. What held them was the admission
 * scheduler: the harness built it without a pool for its model, so every worker leased against the
 * default pool, whose byte ceiling (one gibibyte at 1024 bytes a token) is exactly ONE lease for a
 * model served with 1048576 tokens of context.
 *
 * <p><b>What this pins.</b> The scheduler is built the way the live harnesses now build it
 * ({@link InferenceScheduler#forWorkerModel}), for a model with the served context and the
 * four-at-once limit of the model run 66 used. The scripted model holds every worker's first
 * request until four are in flight together. One at a time, that never happens: each request waits
 * out its allowance alone and the peak is one.
 */
@ModelCodeOnThisPc
class AWavesCandidatesRunSideBySideTest {

    private static final int SERVER_SERVES_AT_ONCE = 4;
    /** How long one request waits for the others before giving up — only ever spent on a failure. */
    private static final long WAIT_FOR_THE_OTHERS_SECONDS = 5;

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final CountDownLatch allIn = new CountDownLatch(SERVER_SERVES_AT_ONCE);

    @AfterEach
    void noCeilingLeftBehind() {
        WorkerSlots.configure(0);
    }

    @Test
    void twoTasksOfTwoCandidatesHaveFourModelCallsInFlightTogether() throws Exception {
        initRepo();
        // The operator's ceiling, as in ~/.swarmcoder/config.yaml: four workers at once.
        WorkerSlots.configure(SERVER_SERVES_AT_ONCE);

        // The two figures that decided run 66, taken from the shape that run used; everything else
        // is the default the scripted server speaks.
        ModelQuirks shape = ModelShapes.get("deepseek-v4-flash-ds4");
        assertThat(shape.maxConcurrentSequences()).isEqualTo(SERVER_SERVES_AT_ONCE);
        ModelQuirks d = ModelQuirks.DEFAULTS;
        ModelQuirks quirks = new ModelQuirks("run 66's model, as the scripted server speaks it",
            d.maxOutputTokens(), d.thinking(), d.thinkingKwarg(), d.noThinkDirective(),
            d.textualToolHistory(), d.jsonResponseFormat(), d.http2(),
            shape.servedContextTokens(), d.workingContextTokens(), shape.kvBytesPerToken(),
            shape.maxConcurrentSequences(), false);

        Task alpha = task("Improve alpha", "alpha.md");
        Task beta = task("Improve beta", "beta.md");

        List<CandidateSolution> archived;
        try (FakeVllm fake = new FakeVllm(this::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            // No edge between them: both tasks are in the same wave.
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(alpha, beta), List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                InferenceScheduler.forWorkerModel("fake", quirks), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm",
                        quirks.servedContextTokens(), quirks),
                    ModelProfile.Kind.WORKER, quirks, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));

            engine.executeRun(new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD,
                RunState.EXECUTING, null, null, null, graphId, null, Instant.now(), null));

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }

        assertThat(peak.get())
            .as("model calls in flight at once across the wave: two tasks of two candidates on a "
                + "server that serves four. One means the candidates were dispatched one at a time")
            .isEqualTo(SERVER_SERVES_AT_ONCE);
        assertThat(archived).as("every candidate of both tasks ran to an archived result").hasSize(4);
        assertThat(archived.stream().filter(c -> c.state() == CandidateState.SELECTED))
            .as("and each task still got its winner").hasSize(2);
    }

    /** Holds each worker's first request until all four are in flight, or its allowance runs out. */
    private FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"clean minimal change\"}");
        }
        if (conversation.contains("applied cleanly")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added a line\"}");
        }
        int now = inFlight.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        allIn.countDown();
        try {
            allIn.await(WAIT_FOR_THE_OTHERS_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
        String file = conversation.contains("beta.md") ? "beta.md" : "alpha.md";
        return FakeVllm.Reply.toolCall("apply_diff", new ObjectMapper().createObjectNode()
            .put("unifiedDiff", patch(file)).toString());
    }

    private static String patch(String file) {
        return """
            diff --git a/%1$s b/%1$s
            --- a/%1$s
            +++ b/%1$s
            @@ -1 +1,2 @@
             hello
            +improved by worker
            """.formatted(file);
    }

    private static Task task(String title, String file) {
        return new Task(UUID.randomUUID(), 1, title, "Add a line to " + file,
            Set.of(file), Set.of(), List.of(), null, null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private void initRepo() throws Exception {
        run("git init -q");
        Files.writeString(repoDir.resolve("alpha.md"), "hello\n");
        Files.writeString(repoDir.resolve("beta.md"), "hello\n");
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            timeoutSeconds: 60
            """);
        run("git add -A");
        run("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private void run(String command) throws Exception {
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }
}
