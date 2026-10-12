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

import com.swarmcoder.console.api.AttentionItem;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.InstructedRetest;
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
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stopped build's question can be answered {@code retest}: the acceptance test of the task
 * it names goes back to its author with an instruction, without the story being designed or
 * built again (DEVELOPER_CORRECTIONS section 86).
 *
 * <p>Live supervised build, 2026-10-12. A task was blocked on its own acceptance test, which
 * errored in its set-up for every candidate. The supervisor was offered {@code retry}, which
 * repeats the stop, and {@code repair}, a round of workers none of whom may change a test.
 *
 * <p>What is checked here is the answer: where it is offered, what it needs, what it writes
 * for the workflow to act on, how it is counted, and that it is the same answer on the
 * supervisor connection and on screen. What the engine then does with the corrected test is
 * the swarm's ({@code ABrokenCorrectedTestGetsNoRepairRoundTest}).
 */
class AStoppedBuildCanBeAnsweredRetestTest {

    private static final String TEST = "src/test/java/swarm/accept/OrderStoreTest.java";

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
        System.clearProperty(InstructedRetest.LIMIT_PROPERTY);
    }

    /** The build that was already stopped: the engine's mark for repair, and a test of its own. */
    @Test
    void aBlockedTaskWithATestOfItsOwnTakesRetestWithTextAndTheTaskCarriesTheInstruction(
            @TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, Mark.ENGINE, true);

            AttentionItem item = desk.nextAttention(0);
            assertThat(item.kind()).isEqualTo("RUN_QUESTION");
            assertThat(item.options()).hasSize(3);
            assertThat(item.options().get(0)).startsWith("retry:");
            assertThat(item.options().get(1)).startsWith("repair:");
            assertThat(item.options().get(2)).startsWith("retest:")
                .contains("'Store orders'").contains("REQUIRED")
                .contains("back to its author").contains("The story is not designed again")
                .contains("Time 1 of the 1");
            assertThat(desk.decisionOptions(stopped.decisionId().toString())).hasSize(3);

            assertThat(desk.answerDecision(stopped.decisionId().toString(), "retest", " "))
                .describedAs("a test sent back with nothing new to go on is not sent")
                .startsWith("error:").contains("needs text");
            assertThat(store.root().decisions.get(stopped.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(stopped.task().retestInstruction()).isNull();
            assertThat(resumed.get()).isNull();

            String said = desk.answerDecision(stopped.decisionId().toString(), "retest",
                "keep how the first version starts; give each test its own store");

            assertThat(said).contains("The acceptance test of task 'Store orders' goes back to "
                    + "its author").contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(stopped.runId());
            Task after = store.root().tasks().get(stopped.task().id());
            assertThat(after.retestInstruction())
                .isEqualTo("keep how the first version starts; give each test its own store");
            assertThat(after.repairInstruction()).as("no worker round was asked for").isNull();
            Decision answered = store.root().decisions.get(stopped.decisionId());
            assertThat(answered.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(answered.humanResponse()).isEqualTo("Answered by the supervisor (retest): "
                + "keep how the first version starts; give each test its own store");
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.actor()).isEqualTo("supervisor");
                assertThat(entry.answer()).startsWith("retest: keep how the first version starts");
            });
        }
    }

    /** A stop the workflow raised on the task's own test: no worker round, but the test can go back. */
    @Test
    void aStopOnTheTasksOwnTestTakesRetestAndNotRepair(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, new AtomicReference<>());
            Stopped stopped = stopped(store, RunState.EXECUTING, Mark.WORKFLOW, true);

            List<String> options = desk.decisionOptions(stopped.decisionId().toString());

            assertThat(options).hasSize(2);
            assertThat(options.get(0)).startsWith("retry:");
            assertThat(options.get(1)).startsWith("retest:");
        }
    }

    /** A fault of the plan, a task with no test of its own, a stop at final integration. */
    @Test
    void whereThereIsNoTestToSendBackRetestIsNotOfferedAndIsRefused(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped planFault = stopped(store, RunState.EXECUTING, Mark.NONE, true);
            Stopped noTest = stopped(store, RunState.EXECUTING, Mark.ENGINE, false);
            Stopped integration = stopped(store, RunState.FINAL_INTEGRATION, Mark.ENGINE, true);

            assertThat(desk.decisionOptions(planFault.decisionId().toString()))
                .as("the engine marked no task: the plan is at fault").singleElement().asString()
                .startsWith("retry:");
            assertThat(desk.decisionOptions(noTest.decisionId().toString()))
                .noneMatch(option -> option.startsWith("retest:"));
            assertThat(desk.decisionOptions(integration.decisionId().toString()))
                .as("a failed final integration: journeys are left out")
                .noneMatch(option -> option.startsWith("retest:"));

            String said = desk.answerDecision(planFault.decisionId().toString(), "retest",
                "rewrite the test");

            assertThat(said).startsWith("error:").contains("no acceptance test to send back here")
                .contains("[retry]");
            assertThat(store.root().decisions.get(planFault.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(planFault.task().retestInstruction()).isNull();
            assertThat(resumed.get()).isNull();
        }
    }

    @Test
    void aTasksTestGoesBackAsOftenAsTheSettingAllowsAndNoMore(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, Mark.WORKFLOW, true);
            // The workflow counts a turn on the task when it starts it.
            stopped.task().setInstructedRetests(1);
            store.saveTask(stopped.task());

            assertThat(desk.decisionOptions(stopped.decisionId().toString())).singleElement()
                .asString().startsWith("retry:");
            assertThat(desk.answerDecision(stopped.decisionId().toString(), "retest", "again"))
                .startsWith("error:").contains("has already gone back to its author 1 time(s)")
                .contains(InstructedRetest.LIMIT_PROPERTY);
            assertThat(resumed.get()).isNull();

            System.setProperty(InstructedRetest.LIMIT_PROPERTY, "2");
            assertThat(desk.decisionOptions(stopped.decisionId().toString())).hasSize(2);
            assertThat(desk.decisionOptions(stopped.decisionId().toString()).get(1))
                .contains("Time 2 of the 2");
        }
    }

    /** The Console's answer box takes free text; the first word is the answer. */
    @Test
    void theSameAnswerOnScreenSendsTheTestBackAndElsewhereItIsAPlainAnswer(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, new AtomicReference<>());
            Stopped offered = stopped(store, RunState.EXECUTING, Mark.ENGINE, true);
            Stopped notOffered = stopped(store, RunState.EXECUTING, Mark.NONE, true);

            DecisionAnswers.Outcome outcome = DecisionAnswers.answerFromConsole(
                offered.decisionId().toString(), "retest: give each test its own store");
            DecisionAnswers.Outcome plain = DecisionAnswers.answerFromConsole(
                notOffered.decisionId().toString(), "retest the plan first, I have changed it");

            assertThat(outcome.ok()).isTrue();
            assertThat(store.root().tasks().get(offered.task().id()).retestInstruction())
                .isEqualTo("give each test its own store");
            assertThat(store.root().decisions.get(offered.decisionId()).humanResponse())
                .isEqualTo("Answered by the operator (retest): give each test its own store");
            assertThat(plain.ok()).isTrue();
            assertThat(notOffered.task().retestInstruction()).isNull();
            assertThat(store.root().decisions.get(notOffered.decisionId()).humanResponse())
                .isEqualTo("retest the plan first, I have changed it");
        }
    }

    // --- fixture ---------------------------------------------------------------------------------

    /** Which mark a stopped task carries for its question. */
    private enum Mark { NONE, ENGINE, WORKFLOW }

    private record Stopped(UUID runId, UUID decisionId, Task task) {}

    /** A story's build stopped with one question about one task. */
    private Stopped stopped(ArtifactStore store, RunState at, Mark mark, boolean hasATest)
            throws Exception {
        Story story = new Story(UUID.randomUUID(), projectId, "S" + (store.root().runs.size() + 2),
            StoryKind.DELIVERY, "Store an order", null, StoryState.RUNNING, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        UUID runId = UUID.randomUUID();
        UUID graphId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 1, "Store orders", "Store them.",
            Set.of("src/main/OrderStore.java"), Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.BLOCKED);
        if (hasATest) {
            task.setAuthoredTestPaths(List.of(TEST));
        }
        if (mark == Mark.ENGINE) {
            task.setRepairOfferedBy(decisionId);
        } else if (mark == Mark.WORKFLOW) {
            task.setRetestOfferedBy(decisionId);
        }
        Run run = new Run(runId, WorkflowKind.GREENFIELD, at, projectId, story.id(), null,
            graphId, null, Instant.now(), new RunReport(runId, "goal"));
        run.setParkedAt(Instant.now().minusSeconds(30));
        run.setParkReason("stopped to ask");
        store.append(() -> {
            store.root().taskGraphs.put(graphId,
                new TaskGraph(graphId, 1, null, new ArrayList<>(List.of(task)), List.of()));
            store.root().runs.put(runId, run);
            store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                DecisionKind.BLOCKED_TASK, "Task BLOCKED after swarm + repair round: 'Store "
                    + "orders'", DecisionState.PENDING, null, Instant.now()));
            return null;
        }).get();
        store.saveTask(task);
        story.setRunIds(new ArrayList<>(List.of(runId)));
        store.saveStory(story);
        return new Stopped(runId, decisionId, task);
    }

    private void install(ArtifactStore store, AtomicReference<UUID> resumed) {
        ConsoleContext.StoryRunStarter starter = (goal, kind, storyId) -> UUID.randomUUID();
        ConsoleContext context = new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter)
            .withUnattended(() -> false)
            .withSupervised(() -> false);
        context.withRunResume(id -> {
            resumed.set(id);
            return "";
        });
        ConsoleContext.set(context);
    }
}
