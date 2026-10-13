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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RebuildFrom.Keep;
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
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 98: a story that is built again starts from what the build before it finished, when
 * what that was made from has not changed, and never from what a send-back objected to.
 */
class ARebuiltStoryStartsFromItsEarlierBuildTest {

    @TempDir
    Path tmp;

    private ArtifactStore store;
    private ScriptedLlm llm;
    private Project project;
    private Path repo;
    private GitService git;
    private String head;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<Run> handedToTheSwarm = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new ArtifactStore(tmp.resolve("store"));
        llm = new ScriptedLlm(conversation -> {
            modelCalls.incrementAndGet();
            return "I decline to produce JSON.";
        });
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

    // --- through the workflow ------------------------------------------------------------------

    @Test
    void unchangedInputsReuseDesignPlanAndTestsAndNoModelIsAsked() throws Exception {
        Story story = story("S1", StoryState.RUNNING);
        Fixture earlier = earlierBuild(story, RunState.EXECUTING);
        Run rebuild = newBuild(story);

        engine().advanceAsync(rebuild).get(60, TimeUnit.SECONDS);

        assertThat(handedToTheSwarm).as("the new build goes straight to building").hasSize(1);
        Run building = handedToTheSwarm.get(0);
        assertThat(building.id()).isEqualTo(rebuild.id());
        assertThat(building.designId()).isEqualTo(earlier.design.id());
        assertThat(building.taskGraphId()).isEqualTo(earlier.graph.id());
        assertThat(building.acceptanceTestsCommit()).isEqualTo(head);
        assertThat(git.headSha(GreenfieldWorkflow.testsBranch(rebuild)))
            .as("the new build's own tests branch stands at the earlier build's tests")
            .isEqualTo(head);
        assertThat(modelCalls).as("no architect, reviewer, planner or test author").hasValue(0);

        Run stored = store.root().runs.get(rebuild.id());
        assertThat(stored.reusedFromRunId()).isEqualTo(earlier.run.id());
        assertThat(stored.reuseRecord()).anyMatch(line -> line.startsWith("Design: reused"))
            .anyMatch(line -> line.startsWith("Plan: reused"))
            .anyMatch(line -> line.startsWith("Tests: reused"))
            .anyMatch(line -> line.startsWith("Tasks: 1 of 2") && line.contains("'Add Book'"));
    }

    @Test
    void aChangedRequirementMakesTheDesignAgainAndEverythingAfterIt() throws Exception {
        Story story = story("S1", StoryState.RUNNING);
        Fixture earlier = earlierBuild(story, RunState.EXECUTING);
        story.setNarrative("A member may also see who holds the book.");
        store.saveStory(story);
        Run rebuild = newBuild(story);

        try {
            engine().advanceAsync(rebuild).get(120, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException designHadNoModel) {
            // The design stage is entered with a model that answers nothing usable; how it
            // ends is not what is tested. What INTAKE decided is already stored.
        }

        assertThat(handedToTheSwarm).as("nothing is built on the earlier plan").isEmpty();
        Run stored = store.root().runs.get(rebuild.id());
        assertThat(stored.reusedFromRunId()).isNull();
        assertThat(stored.designId()).isNotEqualTo(earlier.design.id());
        assertThat(stored.taskGraphId()).isNotEqualTo(earlier.graph.id());
        assertThat(stored.reuseRecord())
            .anyMatch(line -> line.startsWith("Design: made again")
                && line.contains("requirements"))
            .anyMatch(line -> line.startsWith("Plan: made again"))
            .anyMatch(line -> line.startsWith("Tests: made again"));
    }

    // --- the decision on its own ---------------------------------------------------------------

    @Test
    void aChangedCheckOrRuleChangesTheFingerprint() {
        Story story = story("S1", StoryState.RUNNING);
        Run run = run(story, RunState.INTAKE, Instant.now());
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Lend",
            "A member can borrow a book.", null, null, null);
        AcceptanceCriterion check = new AcceptanceCriterion(UUID.randomUUID(),
            "A borrowed book has a due date", "swarm.accept.LendTest");
        String asked = EarlierBuild.fingerprint(run,
            new StoryScope(story, List.of(requirement), List.of(check), List.of("R1:C1"),
                List.of(), "Use the project's own store."));

        assertThat(EarlierBuild.fingerprint(run, new StoryScope(story, List.of(requirement),
            List.of(check), List.of("R1:C1"), List.of(), "Use the project's own store.")))
            .as("the same inputs give the same fingerprint").isEqualTo(asked);
        AcceptanceCriterion reworded = new AcceptanceCriterion(check.id(),
            "A borrowed book has a due date 14 days on", "swarm.accept.LendTest");
        assertThat(EarlierBuild.fingerprint(run, new StoryScope(story, List.of(requirement),
            List.of(reworded), List.of("R1:C1"), List.of(), "Use the project's own store.")))
            .isNotEqualTo(asked);
        assertThat(EarlierBuild.fingerprint(run, new StoryScope(story, List.of(requirement),
            List.of(check), List.of("R1:C1"), List.of(), "Use a database.")))
            .isNotEqualTo(asked);
    }

    @Test
    void aSendBackNeverReusesWhatWasObjectedTo() {
        Story story = story("S1", StoryState.READY);
        Fixture earlier = earlierBuild(story, RunState.EXECUTING);
        Run rebuild = newBuild(story);

        // A build that stopped while building, sent back with no stage named: the stop is what
        // was objected to. The stages before it stand; the finished task is not built again.
        sendBack(story, earlier.run, StoryState.BLOCKED, null, "the second task never compiles");
        EarlierBuild.Decision stopped = decide(rebuild, story, earlier, false);
        assertThat(stopped.keep()).isEqualTo(Keep.TESTS_AND_FINISHED_TASKS);
        assertThat(stopped.note()).isEqualTo("the second task never compiles");

        // The same build, and the sender names the design: nothing is kept.
        sendBack(story, earlier.run, StoryState.BLOCKED, RunState.DESIGN, "store it on the server");
        EarlierBuild.Decision design = decide(rebuild, story, earlier, false);
        assertThat(design.keep()).isEqualTo(Keep.NOTHING);
        assertThat(design.lines()).anyMatch(line -> line.startsWith("Design: made again")
            && line.contains("the sender named"));

        // The sender names the plan: the design stands, the plan and all after it do not.
        sendBack(story, earlier.run, StoryState.BLOCKED, RunState.PLAN, "one task per screen");
        assertThat(decide(rebuild, story, earlier, false).keep()).isEqualTo(Keep.DESIGN);

        // The tests or the code are named: a task judged by the earlier tests may not be
        // carried under a kept plan, so only the design stands.
        sendBack(story, earlier.run, StoryState.BLOCKED, RunState.TEST_AUTHORING, "wrong test");
        assertThat(decide(rebuild, story, earlier, false).keep()).isEqualTo(Keep.DESIGN);
        sendBack(story, earlier.run, StoryState.BLOCKED, RunState.EXECUTING, "wrong code");
        assertThat(decide(rebuild, story, earlier, false).keep()).isEqualTo(Keep.DESIGN);

        // A delivery that was refused, no stage named: nothing that made it is kept.
        Fixture delivered = earlierBuild(story("S2", StoryState.READY), RunState.FINAL_INTEGRATION);
        Story second = store.getStory(delivered.run.storyId());
        sendBack(second, delivered.run, StoryState.REVIEW, null, "the list is in the wrong order");
        EarlierBuild.Decision refused = decide(newBuild(second), second, delivered, false);
        assertThat(refused.keep()).isEqualTo(Keep.NOTHING);
        assertThat(refused.lines()).anyMatch(line -> line.contains("delivery was refused"));

        // A build whose final integration failed and was sent back: its code is made again.
        sendBack(second, delivered.run, StoryState.BLOCKED, null, "try again");
        assertThat(decide(newBuild(second), second, delivered, false).keep())
            .isEqualTo(Keep.DESIGN);
    }

    @Test
    void aSendBackCountsForTheNextBuildOnly() {
        Story story = story("S1", StoryState.READY);
        Fixture first = earlierBuild(story, RunState.EXECUTING);
        sendBack(story, first.run, StoryState.BLOCKED, RunState.DESIGN, "store it on the server");
        // The build after the send-back was itself interrupted while building.
        Fixture second = earlierBuild(story, RunState.EXECUTING);

        EarlierBuild.Decision third = decide(newBuild(story), story, second, false);

        assertThat(third.keep()).isEqualTo(Keep.TESTS_AND_FINISHED_TASKS);
        assertThat(third.note()).isNull();
    }

    @Test
    void anInterruptedBuildIsTakenUpWhereItEnded() {
        Story story = story("S1", StoryState.READY);
        assertThat(decide(newBuild(story), story, earlierBuild(story, RunState.DESIGN_REVIEW),
            false).keep()).isEqualTo(Keep.NOTHING);
        assertThat(decide(newBuild(story), story, earlierBuild(story, RunState.PLAN), false)
            .keep()).isEqualTo(Keep.DESIGN);
        Fixture authoring = earlierBuild(story, RunState.TEST_AUTHORING);
        notYetJudged(authoring);
        EarlierBuild.Decision plan = decide(newBuild(story), story, authoring, false);
        assertThat(plan.keep()).isEqualTo(Keep.PLAN);
        assertThat(plan.lines()).anyMatch(line -> line.startsWith("Tests: made by this build"));
        // While a thread is still in the earlier build, its plan and tasks are not shared.
        assertThat(decide(newBuild(story), story, earlierBuild(story, RunState.EXECUTING), true)
            .keep()).isEqualTo(Keep.DESIGN);
    }

    @Test
    void onAnotherCommitOnlyWhatTheDifferingFilesDoNotTouchIsKept() {
        Story story = story("S1", StoryState.READY);
        Fixture earlier = earlierBuild(story, RunState.EXECUTING);
        Run rebuild = newBuild(story);
        rebuild.setBaseCommit("f".repeat(40));

        assertThat(decide(rebuild, story, earlier, List.of("docs/notes.md")).keep())
            .as("the tests and candidates were cut from the other commit; a task was judged "
                + "by them, so the plan cannot be carried either")
            .isEqualTo(Keep.DESIGN);
        assertThat(decide(rebuild, story, earlier, List.of("src/main/java/com/x/Shelf.java"))
            .lines()).anyMatch(line -> line.startsWith("Plan: made again")
                && line.contains("src/main/java/com/x/Shelf.java"));
        EarlierBuild.Decision named = decide(rebuild, story, earlier,
            List.of("src/main/java/com/y/Book.java"));
        assertThat(named.keep()).isEqualTo(Keep.NOTHING);
        assertThat(named.lines()).anyMatch(line -> line.startsWith("Design: made again")
            && line.contains("a type the design names"));
        assertThat(decide(rebuild, story, earlier, (List<String>) null).keep())
            .as("what differs could not be read").isEqualTo(Keep.NOTHING);

        Fixture planned = earlierBuild(story, RunState.TEST_AUTHORING);
        notYetJudged(planned);
        assertThat(decide(rebuild, story, planned, List.of("docs/notes.md")).keep())
            .as("nothing the design or the plan refers to differs").isEqualTo(Keep.PLAN);
    }

    // --- fixtures ------------------------------------------------------------------------------

    private record Fixture(Run run, DesignDocument design, TaskGraph graph) { }

    /** A plan as it is while its tests are being written: no task has a result yet. */
    private static void notYetJudged(Fixture build) {
        for (Task task : build.graph.tasks()) {
            task.setState(TaskState.READY);
            task.setSelectedCandidateId(null);
        }
    }

    private EarlierBuild.Decision decide(Run rebuild, Story story, Fixture earlier,
                                         boolean earlierIsGoing) {
        return EarlierBuild.decide(fingerprinted(rebuild, story), story, earlier.run,
            earlier.design, earlier.graph, earlierIsGoing, false, (from, to) -> List.of());
    }

    private EarlierBuild.Decision decide(Run rebuild, Story story, Fixture earlier,
                                         List<String> differ) {
        return EarlierBuild.decide(fingerprinted(rebuild, story), story, earlier.run,
            earlier.design, earlier.graph, false, false, (from, to) -> differ);
    }

    private Run fingerprinted(Run run, Story story) {
        run.setInputsFingerprint(EarlierBuild.fingerprint(run, StoryScope.empty(story)));
        return run;
    }

    private void sendBack(Story story, Run after, StoryState from, RunState redoFrom,
                          String note) {
        story.setSentBackNote(note);
        story.setSentBackFrom(from);
        story.setSentBackAfterRunId(after.id());
        story.setSentBackRedoFrom(redoFrom);
        store.saveStory(story);
    }

    private WorkflowEngine engine() {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        SwarmEngine swarm = run -> {
            handedToTheSwarm.add(run);
            throw new RunMustPark("stopped by the test");
        };
        return new WorkflowEngine(unusedRuntime, swarm,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, null, git, null, List.of());
    }

    private Story story(String key, StoryState state) {
        Story story = new Story(UUID.randomUUID(), project.id(), key, StoryKind.DELIVERY,
            "Lend a book", "A member borrows a book.", state, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return store.saveStory(story);
    }

    private int age = 1_000;

    private Run run(Story story, RunState state, Instant startedAt) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, project.id(), story.id(),
            null, null, null, startedAt, new RunReport(runId, "Lend a book"));
        run.setBaseCommit(head);
        return run;
    }

    /** A build that got as far as {@code endedAt} and was then ended, its work in the store. */
    private Fixture earlierBuild(Story story, RunState endedAt) {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "Lend a book",
            List.of(), List.of(), List.of(new ApiContract(UUID.randomUUID(), "Book",
                "a book", "class Book { String title(); }", "com.x.Book", List.of())),
            List.of(), null, Instant.now());
        Task done = task("Add Book", "src/main/java/com/x/Book.java", TaskState.DONE);
        done.setSelectedCandidateId(UUID.randomUUID());
        Task open = task("Add Shelf", "src/main/java/com/x/Shelf.java", TaskState.BLOCKED);
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, design.id(),
            new ArrayList<>(List.of(done, open)), new ArrayList<>());
        Run going = run(story, endedAt, Instant.now().minusSeconds(age--));
        going.setDesignId(design.id());
        going.setTaskGraphId(graph.id());
        going.setAcceptanceTestsCommit(head);
        going.setInputsFingerprint(EarlierBuild.fingerprint(going, StoryScope.empty(story)));
        Run ended = going.withState(RunState.ABORTED);
        assertThat(ended.lastStage()).isEqualTo(endedAt);
        try {
            store.append(() -> {
                store.root().designs.put(design.id(), design);
                store.root().taskGraphs.put(graph.id(), graph);
                store.root().runs.put(ended.id(), ended);
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        store.indexTasks(graph.tasks());
        List<UUID> runs = new ArrayList<>(story.runIds());
        runs.add(ended.id());
        story.setRunIds(runs);
        store.saveStory(story);
        return new Fixture(ended, design, graph);
    }

    /** The build that is starting: stored at INTAKE and listed on its story. */
    private Run newBuild(Story story) {
        Run run = run(story, RunState.INTAKE, Instant.now());
        try {
            store.append(() -> store.root().runs.put(run.id(), run)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
