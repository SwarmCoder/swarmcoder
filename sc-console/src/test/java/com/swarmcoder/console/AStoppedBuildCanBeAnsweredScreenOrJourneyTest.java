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
import com.swarmcoder.domain.CarriedWarning;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.InstructedRepair;
import com.swarmcoder.domain.JourneyVerdict;
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
 * A failed final integration that stopped on a journey can be answered {@code screen} or
 * {@code journey}: the ruling of whoever answers on which side is wrong (DEVELOPER_CORRECTIONS
 * section 88).
 *
 * <p>Live supervised build, 2026-10-12. A journey typed a rating into a text box; the screen
 * was built with radio buttons. The journey's author called the journey wrong, its correction
 * asked for less and was not taken, no worker was started, and the supervisor - who knew from
 * the requirement that the journey was right - was offered {@code retry} only.
 *
 * <p>What is checked here is the answers: where each is offered, what it needs, what it writes
 * for the engine and the workflow to act on, how it is counted, that it is the same answer on
 * the supervisor connection and on screen, and that a warning the build carried is in the
 * delivery's evidence. How a correction is then judged is the workflow's
 * ({@code APersonRulesOnADisputedJourneyTest}).
 */
class AStoppedBuildCanBeAnsweredScreenOrJourneyTest {

    private static final String JOURNEY = "src/test/java/swarm/accept/rating.journey.yaml";
    private static final String EARLIER = "src/test/java/swarm/accept/add-book.journey.yaml";
    private static final String AUTHOR = "The journey's author answered that the journey is "
        + "WRONG: the radio group cannot enter it. Its correction was not taken (it asks for "
        + "less than the journey it replaces).";

    private final UUID projectId = UUID.randomUUID();
    private final SupervisorDesk desk = new SupervisorDesk();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        DecisionAnswers.reset();
        System.clearProperty(InstructedRepair.LIMIT_PROPERTY);
        System.clearProperty(JourneyVerdict.LIMIT_PROPERTY);
    }

    @Test
    void theJourneyIsRightGivesTheScreensTaskARepairRoundOverItsAuthorsAnswer(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.FINAL_INTEGRATION, true, true);

            AttentionItem item = desk.nextAttention(0);
            assertThat(item.kind()).isEqualTo("RUN_QUESTION");
            assertThat(item.options()).as("options are never cut").hasSize(3);
            assertThat(item.options().get(0)).startsWith("retry:");
            assertThat(item.options().get(1)).startsWith("screen: you rule that the journey is "
                + "right and the screen is wrong. Task 'Rate a book on the form' gets one "
                + "repair round").contains(JOURNEY).contains(EARLIER).contains("REQUIRED")
                .contains("outranks what the journey's author answered")
                .contains("Round 1 of the 1");
            assertThat(item.options().get(2)).startsWith("journey: you rule that the journey "
                + "is wrong. The journey(s) [" + JOURNEY + "] of task 'Rate a book on the "
                + "form' go back to their author").contains("REQUIRED")
                .contains("may ask for less than the journey it replaces")
                .contains("recorded in the decision log and shown with the delivery")
                .contains("Time 1 of the 1");
            assertThat(desk.decisionOptions(stopped.decisionId().toString())).hasSize(3);

            assertThat(desk.answerDecision(stopped.decisionId().toString(), "screen", "  "))
                .startsWith("error:").contains("screen needs text");
            assertThat(stopped.task().repairInstruction()).isNull();
            assertThat(resumed.get()).isNull();

            String said = desk.answerDecision(stopped.decisionId().toString(), "screen",
                "the requirement says a person TYPES a rating: a text field, not radio buttons");

            assertThat(said).contains("it outranks the answer of the journey's author")
                .contains("Task 'Rate a book on the form' gets one repair round")
                .contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(stopped.runId());
            Task after = store.root().tasks().get(stopped.task().id());
            assertThat(after.repairInstruction())
                .startsWith("The supervisor has ruled on the failed journey(s) [" + JOURNEY)
                .contains("THE JOURNEY IS RIGHT AND THE SCREEN IS WRONG")
                .contains("outranks anything the journey's author answered")
                .endsWith("the requirement says a person TYPES a rating: a text field, not "
                    + "radio buttons");
            assertThat(after.journeysUpheld()).as("not sent back to its author again")
                .containsExactly(JOURNEY, EARLIER);
            assertThat(after.journeyReviewNote()).as("the author's answer stays on record, and "
                + "the ruling that outranked it follows it").startsWith(AUTHOR)
                .contains("VERDICT OF THE SUPERVISOR").contains("It outranks the answer of the "
                    + "journey's author");
            assertThat(after.journeyInstruction()).isNull();
            Decision answered = store.root().decisions.get(stopped.decisionId());
            assertThat(answered.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(answered.humanResponse()).isEqualTo("Answered by the supervisor (screen): "
                + "the requirement says a person TYPES a rating: a text field, not radio "
                + "buttons");
            assertThat(desk.decisionLog()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(AutonomousDecisionKind.ANSWERED_RUN_QUESTION);
                assertThat(entry.actor()).isEqualTo("supervisor");
                assertThat(entry.answer()).startsWith("screen: the requirement says");
            });
        }
    }

    @Test
    void theJourneyIsWrongSendsItBackToItsAuthorWithTheTextAndStartsNoWorker(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped stopped = stopped(store, RunState.FINAL_INTEGRATION, true, true);
            stopped.task().setJourneysUpheld(List.of(JOURNEY));
            store.saveTask(stopped.task());

            assertThat(desk.answerDecision(stopped.decisionId().toString(), "journey", null))
                .startsWith("error:").contains("journey needs text");
            assertThat(stopped.task().journeyInstruction()).isNull();

            String said = desk.answerDecision(stopped.decisionId().toString(), "journey",
                "click the radio for 4 stars; leave the out-of-range case out");

            assertThat(said).contains("The journey(s) [" + JOURNEY + "] go back to their author")
                .contains("what it drops is recorded").contains("handed back to its engine");
            assertThat(resumed.get()).isEqualTo(stopped.runId());
            Task after = store.root().tasks().get(stopped.task().id());
            assertThat(after.journeyInstruction())
                .isEqualTo("click the radio for 4 stars; leave the out-of-range case out");
            assertThat(after.journeyInstructionBy()).isEqualTo("supervisor");
            assertThat(after.repairInstruction()).as("no worker round was asked for").isNull();
            assertThat(after.journeysUpheld()).as("an earlier ruling that it was right is "
                + "withdrawn by this one").isEmpty();
            assertThat(store.root().decisions.get(stopped.decisionId()).humanResponse())
                .isEqualTo("Answered by the supervisor (journey): click the radio for 4 stars; "
                    + "leave the out-of-range case out");
        }
    }

    @Test
    void neitherIsOfferedWithoutTheWorkflowsMarkOrAwayFromFinalIntegration(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            AtomicReference<UUID> resumed = new AtomicReference<>();
            install(store, resumed);
            Stopped unmarked = stopped(store, RunState.FINAL_INTEGRATION, false, false);
            Stopped building = stopped(store, RunState.EXECUTING, true, true);
            Stopped earlierOnly = stopped(store, RunState.FINAL_INTEGRATION, true, false);

            assertThat(desk.decisionOptions(unmarked.decisionId().toString()))
                .singleElement().asString().startsWith("retry:");
            assertThat(desk.decisionOptions(building.decisionId().toString()))
                .singleElement().asString().startsWith("retry:");
            assertThat(desk.decisionOptions(earlierOnly.decisionId().toString()))
                .as("a journey of an earlier story has no author in this build: screen only")
                .hasSize(2).last().asString().startsWith("screen:");

            assertThat(desk.answerDecision(unmarked.decisionId().toString(), "screen", "x"))
                .startsWith("error: Not recorded: this question is not one about a failed "
                    + "journey that names a task of its build").contains("[retry]");
            assertThat(desk.answerDecision(earlierOnly.decisionId().toString(), "journey", "x"))
                .startsWith("error: Not recorded:").contains("[retry, screen]");
            assertThat(unmarked.task().repairInstruction()).isNull();
            assertThat(earlierOnly.task().journeyInstruction()).isNull();
            assertThat(resumed.get()).isNull();
        }
    }

    @Test
    void eachIsCountedAndCappedByItsOwnSetting(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, new AtomicReference<>());
            Stopped stopped = stopped(store, RunState.FINAL_INTEGRATION, true, true);
            stopped.task().setInstructedRepairRounds(1);
            stopped.task().setJourneyInstructionsGiven(List.of(JOURNEY));
            store.saveTask(stopped.task());
            String id = stopped.decisionId().toString();

            assertThat(desk.decisionOptions(id)).singleElement().asString().startsWith("retry:");
            assertThat(desk.answerDecision(id, "screen", "a text field"))
                .startsWith("error: Not recorded: task 'Rate a book on the form' has already "
                    + "had 1 repair round(s) on an instruction")
                .contains(InstructedRepair.LIMIT_PROPERTY);
            assertThat(desk.answerDecision(id, "journey", "click the radio"))
                .startsWith("error: Not recorded: the journey(s) of task 'Rate a book on the "
                    + "form' this question is about have already gone back to their author")
                .contains(JourneyVerdict.LIMIT_PROPERTY);
            assertThat(store.root().decisions.get(stopped.decisionId()).state())
                .isEqualTo(DecisionState.PENDING);

            System.setProperty(InstructedRepair.LIMIT_PROPERTY, "2");
            System.setProperty(JourneyVerdict.LIMIT_PROPERTY, "2");
            assertThat(desk.decisionOptions(id)).hasSize(3);
            assertThat(desk.decisionOptions(id).get(1)).contains("Round 2 of the 2");
            assertThat(desk.decisionOptions(id).get(2)).contains("Time 2 of the 2");
        }
    }

    /** The Console's answer box takes free text; the first word is the answer. */
    @Test
    void theSameAnswersOnScreenAndElsewhereTheTextIsAPlainAnswer(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, new AtomicReference<>());
            Stopped forScreen = stopped(store, RunState.FINAL_INTEGRATION, true, true);
            Stopped forJourney = stopped(store, RunState.FINAL_INTEGRATION, true, true);
            Stopped notOffered = stopped(store, RunState.FINAL_INTEGRATION, false, false);

            DecisionAnswers.Outcome screen = DecisionAnswers.answerFromConsole(
                forScreen.decisionId().toString(), "screen: a typed field, please");
            DecisionAnswers.Outcome journey = DecisionAnswers.answerFromConsole(
                forJourney.decisionId().toString(), "journey - click the radio for 4 stars");
            DecisionAnswers.Outcome plain = DecisionAnswers.answerFromConsole(
                notOffered.decisionId().toString(), "screen looks fine to me, run it again");

            assertThat(screen.ok()).isTrue();
            assertThat(store.root().tasks().get(forScreen.task().id()).repairInstruction())
                .startsWith("The operator has ruled").endsWith("a typed field, please");
            assertThat(store.root().decisions.get(forScreen.decisionId()).humanResponse())
                .isEqualTo("Answered by the operator (screen): a typed field, please");
            assertThat(journey.ok()).isTrue();
            Task sentBack = store.root().tasks().get(forJourney.task().id());
            assertThat(sentBack.journeyInstruction())
                .isEqualTo("- click the radio for 4 stars");
            assertThat(sentBack.journeyInstructionBy()).isEqualTo("operator");
            assertThat(plain.ok()).isTrue();
            assertThat(notOffered.task().repairInstruction()).isNull();
            assertThat(store.root().decisions.get(notOffered.decisionId()).humanResponse())
                .isEqualTo("screen looks fine to me, run it again");
        }
    }

    @Test
    void whatALesserCorrectionDroppedIsInTheDeliverysEvidence(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            install(store, new AtomicReference<>());
            Stopped stopped = stopped(store, RunState.FINAL_INTEGRATION, true, true);
            Run run = store.root().runs.get(stopped.runId());
            Story story = store.getStory(run.storyId());

            assertThat(SupervisorDesk.deliveryFacts(store, story, run))
                .as("a build that carried nothing says nothing of warnings")
                .doesNotContain("warning");

            run.carryWarning(new CarriedWarning("a journey corrected on a person's instruction "
                + "asks for less", "FINAL_INTEGRATION", "The journey " + JOURNEY + " was "
                + "corrected on the instruction of the supervisor and now asks for LESS than "
                + "the journey it replaces. Dropped: step 6 is no longer made: fill "
                + "role=textbox[name=\"Rating\"] = \"9\"; criterion 2 is no longer proved on "
                + "screen.", Instant.now()));

            assertThat(SupervisorDesk.deliveryFacts(store, story, run))
                .contains("The build carried 1 warning(s): a journey corrected on a person's "
                    + "instruction asks for less - The journey " + JOURNEY)
                .contains("Dropped: step 6 is no longer made")
                .contains("criterion 2 is no longer proved on screen");
        }
    }

    // --- fixture ---------------------------------------------------------------------------------

    private record Stopped(UUID runId, UUID decisionId, Task task) {}

    /**
     * A story's build stopped on a journey its author called wrong.
     *
     * @param screenMarked  the workflow marked the question for {@code screen}
     * @param journeyMarked and for {@code journey}: the task owns the failed journey
     */
    private Stopped stopped(ArtifactStore store, RunState at, boolean screenMarked,
                            boolean journeyMarked) throws Exception {
        Story story = new Story(UUID.randomUUID(), projectId, "S" + (store.root().runs.size() + 2),
            StoryKind.DELIVERY, "Rate a book", null, StoryState.BLOCKED, new ArrayList<>(),
            new ArrayList<>(), null, 0, StoryOrigin.BACKLOG, null, null, "agent",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        UUID runId = UUID.randomUUID();
        UUID graphId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 1, "Rate a book on the form", "Add the rating.",
            Set.of("web/src/main/java/app/BookForm.java"), Set.of(), List.of(),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.DONE);
        task.setSelectedCandidateId(UUID.randomUUID());
        task.setJourneyPaths(List.of(JOURNEY));
        task.setJourneyReviewNote(AUTHOR);
        if (screenMarked) {
            task.setScreenVerdictOfferedBy(decisionId);
            task.setDisputedJourneys(journeyMarked ? List.of(JOURNEY, EARLIER) : List.of(EARLIER));
        }
        if (journeyMarked) {
            task.setJourneyVerdictOfferedBy(decisionId);
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
                DecisionKind.BLOCKED_TASK, "FINAL_INTEGRATION failed on branch swarm/integration/"
                    + runId + ":\n\nEvery acceptance test passed on the merged tree, and 2 of 4 "
                    + "journey(s) failed in a real browser", DecisionState.PENDING, null,
                Instant.now()));
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
