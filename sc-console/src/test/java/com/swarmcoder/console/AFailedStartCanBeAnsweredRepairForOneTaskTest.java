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
 * A build that stopped because the application did not start can be answered {@code repair}
 * for one task where the build's facts name several, or none (DEVELOPER_CORRECTIONS section
 * 92): the answer says which task, and from there it is the round of section 84.
 *
 * <p>What is checked here is the answer. Which tasks a stop marks is the workflow's
 * ({@code TheTaskThatBrokeTheStartIsNamedTest}); the round is the swarm's.
 */
class AFailedStartCanBeAnsweredRepairForOneTaskTest {

    private static final String PRINTED = "SomethingException: com.example.Main\n";

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
        System.clearProperty(InstructedRepair.LIMIT_PROPERTY);
    }

    @Test
    void theAnswerMustNameOneOfTheTasksAndThatTaskAloneGetsTheRound(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store);
            String id = stopped.decisionId().toString();

            AttentionItem item = desk.nextAttention(0);
            assertThat(item.kind()).isEqualTo("RUN_QUESTION");
            assertThat(item.options()).hasSize(2);
            assertThat(item.options().get(0)).startsWith("retry:");
            assertThat(item.options().get(1)).startsWith("repair:")
                .contains("task is REQUIRED: one of 'Book service', 'Start-up'")
                .contains("text is REQUIRED");
            assertThat(item.chars()).isLessThanOrEqualTo(AttentionItem.MAX_CHARS);

            assertThat(desk.answerDecision(id, "repair", "make the field private"))
                .startsWith("error:").contains("repair needs task")
                .contains("'Book service', 'Start-up'");
            assertThat(desk.answerDecision(id, "repair", "make the field private", "Styles"))
                .startsWith("error:").contains("'Styles' is not one of them");
            assertThat(desk.answerDecision(id, "repair", " ", "Start-up"))
                .startsWith("error:").contains("needs text");
            assertThat(store.root().decisions.get(stopped.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);
            assertThat(resumed.get()).isNull();
            assertThat(stopped.service().repairInstruction()).isNull();
            assertThat(stopped.startUp().repairInstruction()).isNull();

            String said = desk.answerDecision(id, "repair", "make the field private",
                "start-up");

            assertThat(said).contains("Task 'Start-up' gets one more repair round")
                .contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(stopped.runId());
            Task chosen = store.root().tasks().get(stopped.startUp().id());
            assertThat(chosen.repairInstruction()).isEqualTo("make the field private");
            assertThat(chosen.repairOfferedBy()).as("the engine reads the round's evidence "
                + "from the question this mark names").isEqualTo(stopped.decisionId());
            assertThat(chosen.repairChoiceOfferedBy()).isNull();
            assertThat(chosen.startOutput()).as("kept for its workers").isEqualTo(PRINTED);
            Task other = store.root().tasks().get(stopped.service().id());
            assertThat(other.repairInstruction()).isNull();
            assertThat(other.repairOfferedBy()).isNull();
            assertThat(other.repairChoiceOfferedBy()).isNull();
            assertThat(other.startOutput()).isNull();
            assertThat(store.root().decisions.get(stopped.decisionId()).humanResponse())
                .isEqualTo("Answered by the supervisor (repair): make the field private");
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.answer())
                    .startsWith("repair (task 'start-up'): make the field private");
            });
        }
    }

    /** On screen the task's title comes after the word, then the instruction. */
    @Test
    void onScreenTheTasksTitleComesFirst(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store);

            DecisionAnswers.Outcome unnamed = DecisionAnswers.answerFromConsole(
                stopped.decisionId().toString(), "repair: make the field private");
            assertThat(unnamed.ok()).isFalse();
            assertThat(unnamed.error()).contains("repair needs task");

            DecisionAnswers.Outcome outcome = DecisionAnswers.answerFromConsole(
                stopped.decisionId().toString(),
                "repair 'Book service': make the field private");

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.resumed()).isTrue();
            assertThat(store.root().tasks().get(stopped.service().id()).repairInstruction())
                .isEqualTo("make the field private");
            assertThat(store.root().tasks().get(stopped.startUp().id()).repairInstruction())
                .isNull();
        }
    }

    /** Section 84's cap holds for a chosen task as for a named one. */
    @Test
    void aTaskThatHasHadItsRoundIsNotAmongTheChoices(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store);
            stopped.startUp().setInstructedRepairRounds(1);
            store.saveTask(stopped.startUp());

            assertThat(desk.nextAttention(0).options().get(1))
                .contains("one of 'Book service'.").doesNotContain("Start-up");
            assertThat(desk.answerDecision(stopped.decisionId().toString(), "repair",
                "make the field private", "Start-up")).startsWith("error:");

            stopped.service().setInstructedRepairRounds(1);
            store.saveTask(stopped.service());
            assertThat(desk.nextAttention(0).options()).singleElement().asString()
                .startsWith("retry:");
        }
    }

    // --- fixture ---------------------------------------------------------------------------------

    private record Stopped(UUID runId, UUID decisionId, Task service, Task startUp) {}

    /** A build stopped at final integration on a failed start that two tasks are named for. */
    private Stopped stopped(ArtifactStore store) throws Exception {
        Story story = new Story(UUID.randomUUID(), projectId, "S2", StoryKind.DELIVERY,
            "Return a book", null, StoryState.RUNNING, new ArrayList<>(), new ArrayList<>(), null,
            0, StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        UUID runId = UUID.randomUUID();
        UUID graphId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Task service = task("Book service");
        Task startUp = task("Start-up");
        Task screen = task("Book list screen");
        for (Task marked : List.of(service, startUp)) {
            marked.setRepairChoiceOfferedBy(decisionId);
            marked.setStartOutput(PRINTED);
        }
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.FINAL_INTEGRATION, projectId,
            story.id(), null, graphId, null, Instant.now(), new RunReport(runId, "goal"));
        run.setParkedAt(Instant.now().minusSeconds(30));
        run.setParkReason("stopped to ask");
        store.append(() -> {
            store.root().taskGraphs.put(graphId, new TaskGraph(graphId, 1, null,
                new ArrayList<>(List.of(service, startUp, screen)), List.of()));
            store.root().runs.put(runId, run);
            store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                DecisionKind.BLOCKED_TASK, "The application did not start in the merged tree: "
                    + "it exited with code 1 after 4 seconds.",
                DecisionState.PENDING, null, Instant.now()));
            return null;
        }).get();
        for (Task one : List.of(service, startUp, screen)) {
            store.saveTask(one);
        }
        story.setRunIds(new ArrayList<>(List.of(runId)));
        store.saveStory(story);
        return new Stopped(runId, decisionId, service, startUp);
    }

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, "Build it.",
            Set.of("src/main/java"), Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.DONE);
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
