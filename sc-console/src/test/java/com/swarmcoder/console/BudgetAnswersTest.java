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

import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answering the spending-limit question (extend or stop), the Console's own answer taking the same
 * path as the supervisor's, and the change journal naming who acted.
 */
class BudgetAnswersTest {

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();
    private final List<UUID> resumed = new ArrayList<>();
    private final List<UUID> extended = new ArrayList<>();
    private String extensionResult = "The run limit is now 2,000 input and output tokens together.";

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @Test
    void aBudgetQuestionOffersExtendAndStop() {
        assertThat(DecisionAnswers.optionsFor(DecisionKind.BUDGET_EXTENSION))
            .extracting(DecisionAnswers.Option::token).containsExactly("extend", "stop");
    }

    @Test
    void extendRaisesTheLimitThenHandsTheBuildBackAndIsLogged(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            UUID runId = parkedRun(store);
            UUID decisionId = budgetQuestion(store, runId);

            assertThat(desk.decisionOptions(decisionId.toString()))
                .anyMatch(o -> o.startsWith("extend:")).anyMatch(o -> o.startsWith("stop:"));
            String said = desk.answerDecision(decisionId.toString(), "extend", "");

            assertThat(said).contains("limit is now 2,000").contains("handed back to its engine");
            assertThat(extended).containsExactly(runId);
            assertThat(resumed).containsExactly(runId);
            assertThat(store.root().decisions.get(decisionId).state())
                .isEqualTo(DecisionState.RESOLVED);
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.answer()).startsWith("extend");
            });
        }
    }

    @Test
    void extendIsRefusedWithAReasonWhenNoLimitIsOnRecord(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            extensionResult = "error: no spending limit is on record as having stopped that build";
            install(store);
            UUID decisionId = budgetQuestion(store, parkedRun(store));

            String said = desk.answerDecision(decisionId.toString(), "extend", "");

            assertThat(said).startsWith("error:").contains("Not extended")
                .contains("no spending limit is on record");
            assertThat(resumed).isEmpty();
            assertThat(store.root().decisions.get(decisionId).state())
                .describedAs("a refused extension leaves the question open")
                .isEqualTo(DecisionState.PENDING);
        }
    }

    @Test
    void stopLeavesTheBuildStoppedAndIsLogged(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            UUID decisionId = budgetQuestion(store, parkedRun(store));

            String said = desk.answerDecision(decisionId.toString(), "stop", "over budget");

            assertThat(said).contains("stays stopped");
            assertThat(extended).isEmpty();
            assertThat(resumed).isEmpty();
            assertThat(store.root().decisions.get(decisionId).state())
                .isEqualTo(DecisionState.RESOLVED);
            assertThat(desk.decisionLog()).singleElement()
                .satisfies(entry -> assertThat(entry.answer()).startsWith("stop: over budget"));
        }
    }

    @Test
    void theConsolesOwnAnswerRestartsTheBuildLikeTheSupervisorsDoes(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            UUID runId = parkedRun(store);
            UUID blocked = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(blocked, new Decision(blocked, runId,
                    DecisionKind.BLOCKED_TASK, "a task could not be built", DecisionState.PENDING,
                    null, Instant.now()));
                return null;
            }).get();

            new ControlServiceImpl().resolveDecision(blocked.toString(), "I fixed the fixture");

            assertThat(resumed).containsExactly(runId);
            Decision after = store.root().decisions.get(blocked);
            assertThat(after.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(after.humanResponse()).isEqualTo("I fixed the fixture");
        }
    }

    @Test
    void theConsolesAnswerExtendOnABudgetQuestionRaisesTheLimit(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            UUID runId = parkedRun(store);
            UUID decisionId = budgetQuestion(store, runId);

            new ControlServiceImpl().resolveDecision(decisionId.toString(), "Extend, please");

            assertThat(extended).containsExactly(runId);
            assertThat(resumed).containsExactly(runId);
        }
    }

    @Test
    void theJournalNamesTheSupervisorForWhatItDoesAndAPersonForTheirClick(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store);
            Story ready = story("S1", StoryState.DRAFT);
            Story back = story("S2", StoryState.REVIEW);
            Story byHand = story("S3", StoryState.DRAFT);
            store.saveStory(ready);
            store.saveStory(back);
            store.saveStory(byHand);

            assertThat(desk.promoteStory("S1")).isEmpty();
            assertThat(desk.sendBack("S2", "the date is wrong")).isEmpty();
            assertThat(new BacklogServiceImpl().promoteStory(byHand.id().toString())).isEmpty();

            assertThat(actorFor(store, ready.id())).isEqualTo("supervisor");
            assertThat(actorFor(store, back.id())).isEqualTo("supervisor");
            assertThat(actorFor(store, byHand.id())).isEqualTo("human");
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String actorFor(ArtifactStore store, UUID storyId) {
        List<ChangeEvent> events = store.listChangeEvents(projectId).stream()
            .filter(e -> storyId.equals(e.entityId())).toList();
        assertThat(events).hasSize(1);
        return events.get(0).actor();
    }

    private void install(ArtifactStore store) {
        ConsoleContext.StoryRunStarter starter = (goal, kind, storyId) -> UUID.randomUUID();
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter)
            .withRunResume(id -> {
                resumed.add(id);
                return "";
            })
            .withBudgetExtension(id -> {
                extended.add(id);
                return extensionResult;
            }));
    }

    private UUID parkedRun(ArtifactStore store) throws Exception {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, projectId, null,
            null, UUID.randomUUID(), null, Instant.now(), new RunReport(runId, "goal"));
        run.setParkedAt(Instant.now().minusSeconds(30));
        run.setParkReason("a spending limit was passed");
        store.append(() -> {
            store.root().runs.put(runId, run);
            return null;
        }).get();
        return runId;
    }

    private UUID budgetQuestion(ArtifactStore store, UUID runId) throws Exception {
        UUID id = UUID.randomUUID();
        store.append(() -> {
            store.root().decisions.put(id, new Decision(id, runId, DecisionKind.BUDGET_EXTENSION,
                "A run stopped because it passed this run's limit.", DecisionState.PENDING, null,
                Instant.now()));
            return null;
        }).get();
        return id;
    }

    private Story story(String key, StoryState state) {
        return new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, "Story " + key, null,
            state, new ArrayList<>(), new ArrayList<>(List.of(UUID.randomUUID())), null, 0,
            StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
    }
}
