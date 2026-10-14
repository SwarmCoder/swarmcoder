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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.RunEnded;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.SupersededRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 110: a story's new build that starts while its earlier build is still building waits
 * for the earlier one to stop, and then takes over its design, plan and tests (section 99). Live
 * build of 2026-10-14: the new build made its plan and tests again "because the earlier build has
 * not stopped yet", while the ended build went on into its next wave.
 */
class ANewBuildWaitsForItsEndedBuildToStopTest {

    @TempDir
    Path tmp;

    private ArtifactStore store;
    private ScriptedLlm llm;
    private Project project;
    private Path repo;
    private GitService git;
    private String head;
    private final List<Run> handedToTheSwarm = new CopyOnWriteArrayList<>();
    private final CountDownLatch earlierBuilding = new CountDownLatch(1);
    private volatile UUID earlierId;

    @BeforeEach
    void setUp() throws Exception {
        store = new ArtifactStore(tmp.resolve("store"));
        llm = new ScriptedLlm(conversation -> "I decline to produce JSON.");
        repo = Files.createDirectories(tmp.resolve("repo"));
        assertThat(GitService.ensureRepo(repo)).isTrue();
        git = new GitService(repo);
        head = git.resolveCommit("HEAD");
        project = store.ensureProject("library", repo.toString(), List.of());
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
    void theNewBuildReusesPlanAndTestsOnceTheEndedBuildHasStopped() throws Exception {
        Story story = story();
        Run earlier = earlierBuildStillBuilding(story);
        earlierId = earlier.id();
        WorkflowEngine engine = engine();

        Future<?> earlierDrive = engine.advanceAsync(earlier);
        assertThat(earlierBuilding.await(60, TimeUnit.SECONDS))
            .as("the earlier build is in its swarm").isTrue();

        // Started again, as BacklogServiceImpl.start does it: the new build is handed to its
        // engine first, and the earlier one is ended a moment after.
        Run rebuild = newBuild(story);
        Future<?> rebuilding = engine.advanceAsync(rebuild);
        Thread.sleep(300);
        assertThat(SupersededRuns.supersede(store, story, rebuild.id(), "S1 has a newer build."))
            .containsExactly(earlier.id());

        rebuilding.get(60, TimeUnit.SECONDS);
        earlierDrive.get(60, TimeUnit.SECONDS);

        assertThat(handedToTheSwarm).as("only the new build reaches the swarm again")
            .extracting(Run::id).containsExactly(rebuild.id());
        Run building = handedToTheSwarm.get(0);
        assertThat(building.designId()).isEqualTo(earlier.designId());
        assertThat(building.taskGraphId()).isEqualTo(earlier.taskGraphId());
        assertThat(building.acceptanceTestsCommit()).isEqualTo(head);
        Run stored = store.root().runs.get(rebuild.id());
        assertThat(stored.reusedFromRunId()).isEqualTo(earlier.id());
        assertThat(stored.reuseRecord())
            .anyMatch(line -> line.startsWith("Plan: reused"))
            .anyMatch(line -> line.startsWith("Tests: reused"))
            .noneMatch(line -> line.contains("has not stopped yet"));
        assertThat(store.root().runs.get(earlier.id()).state())
            .as("the earlier build stays ended").isEqualTo(RunState.ABORTED);
    }

    // --- fixtures ------------------------------------------------------------------------------

    private WorkflowEngine engine() {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        SwarmEngine swarm = run -> {
            if (run.id().equals(earlierId)) {
                // The earlier build's swarm: it builds until it is ended, then takes a moment
                // to stop (a tool command or a verification under way finishes first).
                earlierBuilding.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (store.root().runs.get(run.id()).state() != RunState.ABORTED
                        && System.nanoTime() < until) {
                    sleep(50);
                }
                sleep(1_000);
                throw new RunEnded(run.id(), "while wave 1 was being built");
            }
            handedToTheSwarm.add(run);
            throw new RunMustPark("stopped by the test");
        };
        return new WorkflowEngine(unusedRuntime, swarm,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, null, git, null, List.of());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Story story() {
        Story story = new Story(UUID.randomUUID(), project.id(), "S1", StoryKind.DELIVERY,
            "Lend a book", "A member borrows a book.", StoryState.RUNNING, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return store.saveStory(story);
    }

    private Run run(Story story, RunState state, Instant startedAt) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, project.id(), story.id(),
            null, null, null, startedAt, new RunReport(runId, "Lend a book"));
        run.setBaseCommit(head);
        return run;
    }

    /** A build at EXECUTING with its design, plan and tests in the store, not ended. */
    private Run earlierBuildStillBuilding(Story story) throws Exception {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Lend a book",
            List.of(), List.of(), List.of(new ApiContract(UUID.randomUUID(), "Book",
                "a book", "class Book { String title(); }", "com.x.Book", List.of())),
            List.of(), null, Instant.now());
        Task done = task("Add Book", "src/main/java/com/x/Book.java", TaskState.DONE);
        done.setSelectedCandidateId(UUID.randomUUID());
        Task open = task("Add Shelf", "src/main/java/com/x/Shelf.java", TaskState.READY);
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, design.id(),
            new ArrayList<>(List.of(done, open)), new ArrayList<>());
        Run going = run(story, RunState.EXECUTING, Instant.now().minusSeconds(1_000));
        going.setDesignId(design.id());
        going.setTaskGraphId(graph.id());
        going.setAcceptanceTestsCommit(head);
        going.setInputsFingerprint(EarlierBuild.fingerprint(going, StoryScope.empty(story)));
        store.append(() -> {
            store.root().designs.put(design.id(), design);
            store.root().taskGraphs.put(graph.id(), graph);
            store.root().runs.put(going.id(), going);
            return null;
        }).get();
        store.indexTasks(graph.tasks());
        story.setRunIds(new ArrayList<>(List.of(going.id())));
        store.saveStory(story);
        return going;
    }

    /** The build that is starting: stored at INTAKE and listed on its story. */
    private Run newBuild(Story story) throws Exception {
        Run run = run(story, RunState.INTAKE, Instant.now());
        store.append(() -> store.root().runs.put(run.id(), run)).get();
        List<UUID> runs = new ArrayList<>(story.runIds());
        runs.add(run.id());
        story.setRunIds(runs);
        story.setState(StoryState.RUNNING);
        store.saveStory(story);
        return run;
    }

    private static Task task(String title, String writes, TaskState state) {
        return new Task(UUID.randomUUID(), 1, title, "Create " + writes + ".", Set.of(writes),
            Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), state);
    }
}
