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
import com.swarmcoder.domain.InstructedRepair;
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
 * A stopped build's question can be answered {@code repair}: one more repair round for the task
 * it names, told what to change (section 84).
 *
 * <p>Live supervised build, 2026-10-12. A build stopped twice on something its workers had to
 * change - a screen, a journey. The supervisor could run the stage again, which stopped the same
 * way, or send the story back, which designs and builds it again from the start.
 *
 * <p>What is checked here is the answer: where it is offered, what it needs, what it writes for
 * the engine to act on, how it is counted, and that it is the same answer on the supervisor
 * connection and on screen. The round itself is the swarm's
 * ({@code ATaskGetsOneMoreRepairRoundOnAnInstructionTest}).
 */
class AStoppedBuildCanBeAnsweredRepairTest {

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
        System.clearProperty(InstructedRepair.LIMIT_PROPERTY);
    }

    @Test
    void aQuestionTheEngineMarkedTakesRepairWithTextAndTheTaskCarriesTheInstruction(
            @TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.FINAL_INTEGRATION, true);

            AttentionItem item = desk.nextAttention(0);
            assertThat(item.kind()).isEqualTo("RUN_QUESTION");
            assertThat(item.options()).hasSize(2);
            assertThat(item.options().get(0)).startsWith("retry:");
            assertThat(item.options().get(1)).startsWith("repair:")
                .contains("'Book list screen'").contains("REQUIRED")
                .contains("Round 1 of the 1");
            assertThat(item.chars()).isLessThanOrEqualTo(AttentionItem.MAX_CHARS);
            assertThat(desk.decisionOptions(stopped.decisionId().toString())).hasSize(2);

            assertThat(desk.answerDecision(stopped.decisionId().toString(), "repair", " "))
                .describedAs("a round with nothing new to go on is not started")
                .startsWith("error:").contains("needs text");
            assertThat(store.root().decisions.get(stopped.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(stopped.task().repairInstruction()).isNull();
            assertThat(resumed.get()).isNull();

            String said = desk.answerDecision(stopped.decisionId().toString(), "repair",
                "the list must show the due date column");

            assertThat(said).contains("Task 'Book list screen' gets one more repair round")
                .contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(stopped.runId());
            assertThat(store.root().tasks().get(stopped.task().id()).repairInstruction())
                .isEqualTo("the list must show the due date column");
            Decision after = store.root().decisions.get(stopped.decisionId());
            assertThat(after.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(after.humanResponse()).isEqualTo(
                "Answered by the supervisor (repair): the list must show the due date column");
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.actor()).isEqualTo("supervisor");
                assertThat(entry.answer())
                    .startsWith("repair: the list must show the due date column");
            });
        }
    }

    /** A stop for a fault of the plan, or one that is not one task's: the engine marks no task. */
    @Test
    void aQuestionTheEngineDidNotMarkDoesNotTakeRepair(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, false);

            assertThat(desk.nextAttention(0).options()).singleElement().asString()
                .startsWith("retry:");
            String said = desk.answerDecision(stopped.decisionId().toString(), "repair",
                "write the missing type");

            assertThat(said).startsWith("error:").contains("could not succeed here")
                .contains("[retry]");
            assertThat(store.root().decisions.get(stopped.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(stopped.task().repairInstruction()).isNull();
            assertThat(resumed.get()).isNull();
        }
    }

    @Test
    void aTaskIsGivenAsManyRoundsAsTheSettingAllowsAndNoMore(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, true);
            // The engine counts a round on the task when it starts it.
            stopped.task().setInstructedRepairRounds(1);
            store.saveTask(stopped.task());

            assertThat(desk.nextAttention(0).options()).singleElement().asString()
                .startsWith("retry:");
            assertThat(desk.answerDecision(stopped.decisionId().toString(), "repair", "again"))
                .startsWith("error:").contains("has already had 1 repair round(s)")
                .contains(InstructedRepair.LIMIT_PROPERTY);
            assertThat(resumed.get()).isNull();

            System.setProperty(InstructedRepair.LIMIT_PROPERTY, "2");
            assertThat(desk.nextAttention(0).options()).hasSize(2);
            assertThat(desk.nextAttention(0).options().get(1)).contains("Round 2 of the 2");
        }
    }

    /** The Console's answer box takes free text; the first word is the answer, as for a limit. */
    @Test
    void theSameAnswerOnScreenIsTheSameRound(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, true);

            DecisionAnswers.Outcome outcome = DecisionAnswers.answerFromConsole(
                stopped.decisionId().toString(), "repair: add the search box to the screen");

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.resumed()).isTrue();
            assertThat(store.root().tasks().get(stopped.task().id()).repairInstruction())
                .isEqualTo("add the search box to the screen");
            assertThat(store.root().decisions.get(stopped.decisionId()).humanResponse())
                .isEqualTo("Answered by the operator (repair): add the search box to the screen");
        }
    }

    /** Where repair is not offered, text on screen that begins with the word is a plain answer. */
    @Test
    void onScreenTextThatBeginsWithTheWordIsAPlainAnswerWhereRepairIsNotOffered(
            @TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.EXECUTING, false);

            DecisionAnswers.Outcome outcome = DecisionAnswers.answerFromConsole(
                stopped.decisionId().toString(), "repair the plan first, I have changed it");

            assertThat(outcome.ok()).isTrue();
            assertThat(stopped.task().repairInstruction()).isNull();
            assertThat(store.root().decisions.get(stopped.decisionId()).humanResponse())
                .isEqualTo("repair the plan first, I have changed it");
        }
    }

    // --- fixture ---------------------------------------------------------------------------------

    private record Stopped(UUID runId, UUID decisionId, Task task) {}

    /** A story's build stopped with one question; {@code marked} as the engine marks a task. */
    private Stopped stopped(ArtifactStore store, RunState at, boolean marked) throws Exception {
        Story story = new Story(UUID.randomUUID(), projectId, "S2", StoryKind.DELIVERY,
            "Return a book", null, StoryState.RUNNING, new ArrayList<>(), new ArrayList<>(), null,
            0, StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        UUID runId = UUID.randomUUID();
        UUID graphId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 1, "Book list screen", "Build the list.",
            Set.of("src/main/BookList.java"), Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.BLOCKED);
        if (marked) {
            task.setRepairOfferedBy(decisionId);
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
                DecisionKind.BLOCKED_TASK, "The journey of the book list failed in the browser.",
                DecisionState.PENDING, null, Instant.now()));
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
