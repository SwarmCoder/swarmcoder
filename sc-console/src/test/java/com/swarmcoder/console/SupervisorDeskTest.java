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
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An outside supervisor running a build: it is told the one thing that needs it, its answer is
 * acted on by the same services a person's click reaches, a stopped build goes on when its question
 * is answered, and everything it decided is on the record under its own name.
 */
class SupervisorDeskTest {

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
    }

    @Test
    void aDeliveryIsOneShortItemAndAcceptingItIsRecordedAsTheSupervisors(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false, false, null);
            Story story = delivered(store, "S1", "Lend a book");

            AttentionItem item = desk.nextAttention(0);

            assertThat(item.kind()).isEqualTo("DELIVERY");
            assertThat(item.story()).isEqualTo("S1 Lend a book");
            assertThat(item.options()).hasSize(2);
            assertThat(item.evidence()).contains("Every check it promised (1) was proved");
            assertThat(item.answerWith()).contains("accept_delivery story=S1");
            assertThat(item.chars()).isLessThanOrEqualTo(AttentionItem.MAX_CHARS);

            assertThat(desk.acceptDelivery("s1")).isEmpty();

            Story after = store.getStory(story.id());
            assertThat(after.state()).isEqualTo(StoryState.DONE);
            assertThat(after.acceptedBy())
                .describedAs("never dressed up as a person's acceptance, or the application's")
                .isEqualTo("supervisor");
            List<AutonomousDecision> log = desk.decisionLog();
            assertThat(log).hasSize(1);
            assertThat(log.get(0).kind()).isEqualTo(AutonomousDecisionKind.ACCEPTED_DELIVERY);
            assertThat(log.get(0).actor()).isEqualTo("supervisor");
            assertThat(log.get(0).subject()).isEqualTo("S1 Lend a book");
            assertThat(log.get(0).at()).isNotNull();
            assertThat(log.get(0).headline()).isEqualTo("The supervisor accepted this delivery");
            assertThat(desk.nextAttention(0).kind())
                .describedAs("the only story is delivered, and the supervisor is told so")
                .isEqualTo("FINISHED");
        }
    }

    @Test
    void sendingADeliveryBackNeedsAReasonAndRecordsIt(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false, false, null);
            Story story = delivered(store, "S1", "Lend a book");

            assertThat(desk.sendBack("S1", " ")).startsWith("error:");
            assertThat(desk.sendBack("S1", "the due date is never stored")).isEmpty();

            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.READY);
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.SENT_BACK_DELIVERY);
                assertThat(entry.answer()).contains("the due date is never stored");
            });
        }
    }

    @Test
    void answeringAStoppedBuildsQuestionHandsTheBuildBackToItsEngine(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, false, false, resumed);
            Story story = story("S2", "Return a book", StoryState.RUNNING);
            UUID runId = persistRun(store, story.id(), RunState.TEST_AUTHORING,
                Instant.now().minusSeconds(30));
            story.setRunIds(new ArrayList<>(List.of(runId)));
            store.saveStory(story);
            UUID decisionId = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                    DecisionKind.BLOCKED_TASK, "The acceptance test did not fail first.\n"
                        + "x".repeat(4_000), DecisionState.PENDING, null, Instant.now()));
                return null;
            }).get();

            AttentionItem item = desk.nextAttention(0);
            assertThat(item.kind()).isEqualTo("RUN_QUESTION");
            assertThat(item.story()).isEqualTo("S2 Return a book");
            assertThat(item.question()).startsWith("The acceptance test did not fail first.");
            assertThat(item.options()).singleElement().asString().startsWith("retry:");
            assertThat(item.evidence()).contains("TEST_AUTHORING")
                .contains("decision_text decision_id=" + decisionId);
            assertThat(item.chars())
                .describedAs("a four-thousand-character question still arrives as a short item")
                .isLessThanOrEqualTo(AttentionItem.MAX_CHARS);
            assertThat(desk.decisionText(decisionId.toString())).hasSizeGreaterThan(4_000);

            assertThat(desk.answerDecision(decisionId.toString(), "allow", ""))
                .describedAs("an answer this kind of question does not take changes nothing")
                .startsWith("error:").contains("retry");
            assertThat(resumed.get()).isNull();
            assertThat(store.root().decisions.get(decisionId).state())
                .isEqualTo(DecisionState.PENDING);

            String said = desk.answerDecision(decisionId.toString(), "retry",
                "the test now asserts the due date");

            assertThat(said).contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(runId);
            Decision after = store.root().decisions.get(decisionId);
            assertThat(after.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(after.humanResponse())
                .isEqualTo("Answered by the supervisor (retry): the test now asserts the due date");
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.subject()).isEqualTo("S2 Return a book");
                assertThat(entry.question()).startsWith("The acceptance test did not fail first.");
                assertThat(entry.answer()).startsWith("retry: the test now asserts the due date");
            });
        }
    }

    @Test
    void aSecondAnswerForTheSameStopDoesNotPutASecondThreadOnTheRun(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            List<UUID> resumes = new ArrayList<>();
            ConsoleContext.set(context(store, false, false)
                .withRunResume(id -> {
                    resumes.add(id);
                    return "";
                }));
            UUID runId = persistRun(store, null, RunState.EXECUTING, Instant.now().minusSeconds(30));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(first, new Decision(first, runId,
                    DecisionKind.BLOCKED_TASK, "one", DecisionState.PENDING, null, Instant.now()));
                store.root().decisions.put(second, new Decision(second, runId,
                    DecisionKind.BLOCKED_TASK, "two", DecisionState.PENDING, null, Instant.now()));
                return null;
            }).get();

            assertThat(DecisionAnswers.answerAndResume(first.toString(), "retry", "", "operator")
                .resumed()).isTrue();
            DecisionAnswers.Outcome again =
                DecisionAnswers.answerAndResume(second.toString(), "retry", "", "operator");

            assertThat(again.ok()).isTrue();
            assertThat(again.resumed()).isFalse();
            assertThat(resumes).containsExactly(runId);
            assertThat(store.root().decisions.get(second).state())
                .describedAs("the second answer is still recorded")
                .isEqualTo(DecisionState.RESOLVED);
        }
    }

    @Test
    void anAnswerToAQuestionWhoseBuildCarriedOnRestartsNothing(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, false, false, resumed);
            UUID runId = persistRun(store, null, RunState.EXECUTING, null);
            UUID decisionId = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                    DecisionKind.BLOCKED_TASK, "a task was abandoned", DecisionState.PENDING, null,
                    Instant.now()));
                return null;
            }).get();

            assertThat(desk.nextAttention(0))
                .describedAs("a build that carried on is not waiting on anybody")
                .isNull();
            String said = desk.answerDecision(decisionId.toString(), "retry", "noted");

            assertThat(said).contains("nothing was restarted");
            assertThat(resumed.get()).isNull();
        }
    }

    @Test
    void waitingWakesWhenAStoryComesBackAndNotBefore(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false, false, null);
            Story building = story("S1", "Lend a book", StoryState.RUNNING);
            store.saveStory(building);

            long started = System.currentTimeMillis();
            assertThat(desk.waitForAttention(1, 0))
                .describedAs("nothing needs the supervisor while the story is still building")
                .isNull();
            assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(900);

            Thread finishing = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    delivered(store, building);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            finishing.start();
            started = System.currentTimeMillis();
            AttentionItem item = desk.waitForAttention(30, 0);
            finishing.join();

            assertThat(item).isNotNull();
            assertThat(item.kind()).isEqualTo("DELIVERY");
            assertThat(System.currentTimeMillis() - started)
                .describedAs("woken by the store being written, long before the time was up")
                .isLessThan(10_000);
        }
    }

    @Test
    void skipShowsWhatIsBehindAnItemTheSupervisorLeftAlone(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false, false, null);
            Story stopped = story("S1", "Lend a book", StoryState.BLOCKED);
            stopped.setWaitingReason("every attempt failed to compile");
            store.saveStory(stopped);
            delivered(store, "S2", "Return a book");

            assertThat(desk.nextAttention(0).kind()).isEqualTo("STORY_STOPPED");
            assertThat(desk.nextAttention(0).evidence()).isEqualTo("every attempt failed to compile");
            assertThat(desk.nextAttention(1).kind()).isEqualTo("DELIVERY");
            assertThat(desk.nextAttention(2)).isNull();
        }
    }

    @Test
    void supervisedRunningStartsTheNextStoryButAcceptsNothing(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, true, true, null);
            Story finished = delivered(store, "S1", "Lend a book");
            Story next = story("S2", "Return a book", StoryState.READY);
            next.setCriterionIds(new ArrayList<>(List.of(UUID.randomUUID())));
            store.saveStory(next);

            new UnattendedPilot(store, 1).tick();

            assertThat(store.getStory(finished.id()).state())
                .describedAs("the application would have accepted this itself; supervised, it "
                    + "waits for the supervisor")
                .isEqualTo(StoryState.REVIEW);
            assertThat(store.getStory(finished.id()).waitingReason()).isNull();
            assertThat(store.getStory(next.id()).state())
                .describedAs("the queue still runs: the next startable story is started")
                .isEqualTo(StoryState.RUNNING);
            assertThat(desk.nextAttention(0).kind()).isEqualTo("DELIVERY");
            assertThat(AutonomousMode.start())
                .describedAs("two parties must not both be deciding")
                .startsWith("error:").contains("supervised");
        }
    }

    @Test
    void theAnalystsQuestionsComeOneAtATimeAndEachAnswerIsOnTheRecord(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, false, false, null);
            com.swarmcoder.domain.GuidedFlow flow = GuidedFlows.ensureIntake(store, projectId);
            flow.setState(com.swarmcoder.domain.GuidedFlowState.AWAITING_ANSWERS);
            store.saveGuidedFlow(flow);
            UUID storage = UUID.randomUUID();
            UUID limit = UUID.randomUUID();
            store.saveFlowQuestions(flow.id(), new ArrayList<>(List.of(
                new com.swarmcoder.domain.FlowQuestion(storage, flow.id(), "R3 Catalogue",
                    "Where are books stored?", "The catalogue is kept between sessions.",
                    "brief.md", "The document does not say where.",
                    com.swarmcoder.domain.FlowQuestionKind.CHOICE,
                    new ArrayList<>(List.of("A JSON file", "A database")), null, null, false),
                new com.swarmcoder.domain.FlowQuestion(limit, flow.id(), "R4 Lending",
                    "How many books may one member hold?", null, null, null,
                    com.swarmcoder.domain.FlowQuestionKind.TEXT, new ArrayList<>(), null, null,
                    false))));

            AttentionItem first = desk.nextAttention(0);
            assertThat(first.kind()).isEqualTo("ANALYST_QUESTION");
            assertThat(first.question())
                .isEqualTo("The analyst asks about R3 Catalogue: Where are books stored?");
            assertThat(first.options()).contains("A JSON file", "A database");
            assertThat(first.evidence()).contains("Question 1 of 2")
                .contains("The catalogue is kept between sessions.");
            assertThat(first.answerWith()).contains("question_id=" + storage);

            assertThat(desk.answerQuestion("analyst", storage.toString(), "A JSON file", ""))
                .isEmpty();
            assertThat(desk.nextAttention(0).question()).contains("How many books");
            assertThat(desk.answerQuestion("analyst", limit.toString(), "skip", "")).isEmpty();

            assertThat(desk.nextAttention(0).kind())
                .describedAs("every question has an answer, so the next thing is to submit them")
                .isEqualTo("ANALYST_SUBMIT");
            List<AutonomousDecision> log = desk.decisionLog();
            assertThat(log).extracting(AutonomousDecision::kind)
                .containsExactly(AutonomousDecisionKind.ANSWERED_QUESTION,
                    AutonomousDecisionKind.ANSWERED_QUESTION);
            assertThat(log.get(0).question()).isEqualTo("Where are books stored?");
            assertThat(log.get(0).answer()).isEqualTo("A JSON file");
            assertThat(log.get(0).headline()).isEqualTo("The supervisor answered this question");
            assertThat(log.get(1).answer()).startsWith("Skipped");
            assertThat(store.listAutonomousDecisions(projectId))
                .describedAs("nothing here is the application deciding for itself")
                .allMatch(AutonomousDecision::bySupervisor);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void install(ArtifactStore store, boolean unattended, boolean supervised,
                         AtomicReference<UUID> resumed) {
        ConsoleContext context = context(store, unattended, supervised);
        if (resumed != null) {
            context.withRunResume(id -> {
                resumed.set(id);
                return "";
            });
        }
        ConsoleContext.set(context);
    }

    private ConsoleContext context(ArtifactStore store, boolean unattended, boolean supervised) {
        ConsoleContext.StoryRunStarter starter = (goal, kind, storyId) ->
            persistRun(store, storyId, RunState.EXECUTING, null);
        return new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> starter.start(goal, kind, null), runId -> { }, runId -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withStoryRuns(starter)
            .withUnattended(() -> unattended)
            .withSupervised(() -> supervised);
    }

    private Story delivered(ArtifactStore store, String key, String title) {
        return delivered(store, story(key, title, StoryState.REVIEW));
    }

    /** Brings a story back for a verdict with everything it promised proved. */
    private Story delivered(ArtifactStore store, Story story) {
        UUID runId = persistRun(store, story.id(), RunState.DELIVERED, null);
        story.setState(StoryState.REVIEW);
        story.setRunIds(new ArrayList<>(List.of(runId)));
        story.setCriterionIds(new ArrayList<>(List.of(UUID.randomUUID())));
        story.setIntegrationCommit("0123456789abcdef0123456789abcdef01234567");
        story.setDeliveredCommit("0123456789abcdef0123456789abcdef01234567");
        store.saveStory(story);
        return story;
    }

    private Story story(String key, String title, StoryState state) {
        return new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title, null,
            state, new ArrayList<>(), new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null,
            "agent", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }

    private UUID persistRun(ArtifactStore store, UUID storyId, RunState state, Instant parkedAt) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, projectId, storyId,
            null, UUID.randomUUID(), null, Instant.now(), new RunReport(runId, "goal"));
        if (parkedAt != null) {
            run.setParkedAt(parkedAt);
            run.setParkReason("stopped to ask");
        }
        try {
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return runId;
    }
}
