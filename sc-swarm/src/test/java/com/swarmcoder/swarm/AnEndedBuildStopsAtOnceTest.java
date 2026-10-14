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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.RunEnded;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.SupersededRuns;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 110: a build ended from outside while its swarm is building stops at once. Live build
 * of 2026-10-14: a story was sent back while its build was in wave 1 of 4; the build was ABORTED in
 * the store, but its swarm merged wave 1, ran a compile check and dispatched wave 2 on the model
 * server.
 */
@ModelCodeOnThisPc
class AnEndedBuildStopsAtOnceTest {

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    /** Which task each opened session was for, in order. */
    private final List<UUID> sessionsOpened = new CopyOnWriteArrayList<>();
    /** Why each cancelled session was told to stop. */
    private final List<KillReason> cancelledWith = new CopyOnWriteArrayList<>();
    private final CountDownLatch inSession = new CountDownLatch(1);

    @Test
    void aBuildEndedInWaveOneStartsNoFurtherWaveOrCandidateAndItsCandidatesAreCancelled()
            throws Exception {
        initRepo();
        Task first = task("Improve alpha", "alpha.md");
        Task second = task("Improve beta", "beta.md");

        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text("unused"));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Project project = store.ensureProject("library", repoDir.toString(), List.of());
            UUID graphId = UUID.randomUUID();
            // The second task builds on the first: two waves.
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(first, second),
                List.of(new TaskEdge(first.id(), second.id())));
            Story story = new Story(UUID.randomUUID(), project.id(), "S1", StoryKind.DELIVERY,
                "Improve", "Improve the files.", StoryState.RUNNING, new ArrayList<>(),
                new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
                new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
            UUID runId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, project.id(),
                story.id(), null, graphId, null, Instant.now(), null);
            story.setRunIds(new ArrayList<>(List.of(runId)));
            store.saveStory(story);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, run);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), this::open,
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));

            CompletableFuture<Run> building = CompletableFuture.supplyAsync(
                () -> engine.executeRun(run));
            assertThat(inSession.await(60, TimeUnit.SECONDS))
                .as("a candidate of wave 1 is in its session").isTrue();

            // Sent back, as BacklogServiceImpl.retryStoryAs does it.
            story.setState(StoryState.READY);
            store.saveStory(story);
            List<UUID> ended = SupersededRuns.supersede(store, story, null,
                "S1 was sent back to be built again.");
            assertThat(ended).containsExactly(runId);

            Throwable stopped = null;
            try {
                building.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                stopped = e.getCause();
            }
            assertThat(stopped).as("the swarm stops, and says why").isInstanceOf(RunEnded.class);

            assertThat(cancelledWith).as("the candidate in its session was cancelled at once")
                .isNotEmpty().containsOnly(KillReason.BUILD_ENDED);
            assertThat(sessionsOpened).as("no candidate of wave 2 was started")
                .doesNotContain(second.id());
            assertThat(second.state()).isEqualTo(TaskState.READY);
            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
            assertThat(archived).as("what ran is kept, as stopped, and was not verified")
                .isNotEmpty()
                .allSatisfy(candidate -> {
                    assertThat(candidate.taskId()).isEqualTo(first.id());
                    assertThat(candidate.state()).isEqualTo(CandidateState.KILLED);
                    assertThat(candidate.killReason()).isEqualTo(KillReason.BUILD_ENDED);
                    assertThat(candidate.verification()).isNull();
                });
            assertThat(store.root().runs.get(runId).state()).isEqualTo(RunState.ABORTED);
            assertThat(store.root().runs.get(runId).progressCommit())
                .as("nothing of wave 1 was merged after the build was ended").isNull();
        }
    }

    /** A model session that works until it is cancelled. */
    private AgentRuntime.AgentSession open(AgentRuntime.SessionSpec spec) {
        sessionsOpened.add(spec.meta().taskId());
        CountDownLatch cancelled = new CountDownLatch(1);
        KillReason[] why = new KillReason[1];
        return new AgentRuntime.AgentSession() {
            @Override
            public AgentRuntime.SessionResult run(String input) {
                inSession.countDown();
                try {
                    if (!cancelled.await(60, TimeUnit.SECONDS)) {
                        return new AgentRuntime.SessionResult("", Optional.of(KillReason.TIMEOUT),
                            1, 0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgentRuntime.SessionResult("", Optional.of(why[0]), 1, 0);
            }

            @Override
            public void cancel(KillReason reason) {
                why[0] = reason;
                cancelledWith.add(reason);
                cancelled.countDown();
            }

            @Override
            public long tokensUsed() {
                return 0;
            }

            @Override
            public void close() {
            }
        };
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
