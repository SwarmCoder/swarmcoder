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

import com.swarmcoder.console.api.AutonomousSignals;
import com.swarmcoder.console.api.AutonomousStatus;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autonomous running: a loaded document reaches a started build with nobody present, and everything
 * decided on the way is written down.
 *
 * <p>What is under test is not the model. It is the two promises the feature is sold on and the
 * three refusals it must keep.
 *
 * <p><b>The promises.</b> The whole front half really does run without a person - the reading, the
 * questions, the agreement, the planning, the promotion, the start. And every decision taken on the
 * operator's behalf lands in one readable list, with the invented ones separated from the ones the
 * documents actually answered. An assumption filed as a fact is the failure this feature is capable
 * of and the reason it needs a record at all.
 *
 * <p><b>The refusals.</b> It does not lower the agreement gate. It does not answer a build that got
 * stuck, because there is no answer to "the work could not be done". And it stops - cleanly, saying
 * why - when the clock or the budget says so, rather than running until something breaks.
 *
 * <p>The flows run on their own daemon threads, so every wait is a bounded poll on the persisted
 * flow: the store, not a signal, is the source of truth. No real model is called anywhere in this
 * file.
 */
class AutonomousModeTest {

    private static final long WAIT_MILLIS = 15_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        AutonomousMode.reset();
    }

    @AfterEach
    void closeStore() throws Exception {
        AutonomousMode.reset();
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- the promise: document in, build running, nobody present ---------------------------------

    @Test
    void aLoadedDocumentReachesARunningBuildWithNobodyPresent() {
        ScriptedSwarm swarm = install();
        upload("library.md", """
            The library lends books. A member may borrow a book that is on the shelf.
            """);

        assertThat(new ControlServiceImpl().startAutonomousBuild()).isEmpty();
        UnattendedPilot pilot = new UnattendedPilot(store, 1);

        // Every gate a person passes today, passed by the machine instead - one per tick, on the
        // pilot's own timer, exactly as it will run at three in the morning.
        driveUntil(pilot, () -> {
            for (Story story : store.listStories(projectId)) {
                if (story.state() == StoryState.RUNNING) {
                    return true;
                }
            }
            return false;
        }, "a build to be started");

        // 1. the documents were read
        assertThat(intakeFlow().state()).isEqualTo(GuidedFlowState.APPLIED);
        // 2. its questions were answered by the machine
        assertThat(swarm.answersGiven()).isGreaterThan(0);
        // 3. the requirements became agreed scope
        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).isNotEmpty();
        assertThat(brd.requirements().get(0).status()).isNotEqualTo(RequirementStatus.DRAFT);
        // 4. stories were planned, accepted and marked ready
        List<Story> stories = store.listStories(projectId);
        assertThat(stories).isNotEmpty();
        assertThat(stories.stream().noneMatch(s -> s.state() == StoryState.DRAFT)).isTrue();
        // 5. one of them is building
        assertThat(stories.stream().anyMatch(s -> s.state() == StoryState.RUNNING)).isTrue();
    }

    /**
     * The one that matters most: an answer nothing in the documents supported is on the record as
     * an invention, not as something the operator said.
     */
    @Test
    void ananswerTheDocumentsDidNotSupportIsRecordedAsSomethingTheMachineMadeUp() {
        install();
        upload("library.md", "The library lends books.");

        new ControlServiceImpl().startAutonomousBuild();
        UnattendedPilot pilot = new UnattendedPilot(store, 1);
        driveUntil(pilot, () -> !answeredQuestions().isEmpty(), "a question to be answered");

        AutonomousDecision answered = answeredQuestions().get(0);
        // No passage was quoted against the question, so it cannot have come out of the documents
        // however confidently the model claims otherwise.
        assertThat(answered.grounded()).isFalse();
        assertThat(answered.headline()).contains("Made this up");
        assertThat(answered.answer()).isNotBlank();
        assertThat(answered.question()).isNotBlank();

        // ...and the flow's own question carries the same warning, for anybody who opens the
        // wizard tomorrow without knowing a separate record exists.
        GuidedFlow flow = intakeFlow();
        assertThat(store.listFlowQuestions(flow.id()).get(0).note())
            .contains("DECIDED BY SWARMCODER");
    }

    @Test
    void anAnswerThePassageReallyGaveIsNotMarkedAsAnInvention() {
        ScriptedSwarm swarm = install();
        swarm.groundedAnswers = true;
        upload("library.md", "The library lends books. Members may borrow up to five at a time.");
        swarm.quoteWithQuestions = true;

        new ControlServiceImpl().startAutonomousBuild();
        UnattendedPilot pilot = new UnattendedPilot(store, 1);
        driveUntil(pilot, () -> !answeredQuestions().isEmpty(), "a question to be answered");

        assertThat(answeredQuestions().get(0).grounded()).isTrue();
        assertThat(answeredQuestions().get(0).headline()).contains("from your documents");
    }

    @Test
    void agreeingTheScopeWithoutAPersonIsItselfOnTheRecord() {
        install();
        upload("library.md", "The library lends books.");

        new ControlServiceImpl().startAutonomousBuild();
        UnattendedPilot pilot = new UnattendedPilot(store, 1);
        driveUntil(pilot, () -> decisionsOfKind(AutonomousDecisionKind.AGREED_REQUIREMENTS)
            .size() > 0, "the scope to be agreed");

        AutonomousDecision agreed =
            decisionsOfKind(AutonomousDecisionKind.AGREED_REQUIREMENTS).get(0);
        assertThat(agreed.answer()).contains("Agreed");
        assertThat(agreed.reasoning()).contains("without you reading them");
    }

    // --- the refusals ----------------------------------------------------------------------------

    @Test
    void aRequirementNoTestCouldProveIsStillNotAgreed() {
        install();
        // Scope that could never be proved: a check with no test named against it. The gate that
        // stops this is the same one an operator meets, and running unattended must not soften it.
        Brd brd = store.ensureBrd(projectId);
        brd.requirements().add(unprovable("R1", "The library feels welcoming"));
        store.saveBrd(brd);

        new ControlServiceImpl().startAutonomousBuild();
        AutonomousMode.Session session = AutonomousMode.current();
        AutonomousBuild.step(ConsoleContext.get(), session);

        assertThat(store.getBrd(projectId).requirements().get(0).status())
            .isEqualTo(RequirementStatus.DRAFT);
        List<AutonomousDecision> refusals = decisionsOfKind(AutonomousDecisionKind.REFUSED);
        assertThat(refusals).isNotEmpty();
        assertThat(refusals.get(0).reasoning()).contains("R1");
        // Nothing could be built, so it stopped rather than sitting on an empty queue all night.
        assertThat(AutonomousMode.isRunning()).isFalse();
    }

    @Test
    void aBuildThatStoppedToAskSomethingIsNeverAnsweredByTheMachine() {
        install();
        // A stopped task's question is not a question about what the operator wanted. It means the
        // work could not be done, and no sentence from a model changes that.
        UUID runId = persistRun("Lend a book", RunState.EXECUTING, null);
        UUID decisionId = UUID.randomUUID();
        append(() -> store.root().decisions.put(decisionId, new Decision(decisionId, runId,
            DecisionKind.BLOCKED_TASK, "Every attempt failed to compile: Book does not exist.",
            DecisionState.PENDING, null, Instant.now())));

        new ControlServiceImpl().startAutonomousBuild();
        AutonomousMode.Session session = AutonomousMode.current();
        AutonomousBuild.step(ConsoleContext.get(), session);

        // It is still unanswered, and it is on the record as something deliberately left alone.
        assertThat(store.root().decisions.get(decisionId).state())
            .isEqualTo(DecisionState.PENDING);
        assertThat(store.root().decisions.get(decisionId).humanResponse()).isNull();
        List<AutonomousDecision> refusals = decisionsOfKind(AutonomousDecisionKind.REFUSED);
        assertThat(refusals).isNotEmpty();
        assertThat(refusals.get(0).answer()).contains("Left for you");
        assertThat(refusals.get(0).reasoning()).contains("the work could not be done");
    }

    @Test
    void theSameStoppedBuildIsNotRecordedTwiceHoweverLongTheNightIs() {
        install();
        UUID runId = persistRun("Lend a book", RunState.EXECUTING, null);
        UUID decisionId = UUID.randomUUID();
        append(() -> store.root().decisions.put(decisionId, new Decision(decisionId, runId,
            DecisionKind.BLOCKED_TASK, "It would not compile.", DecisionState.PENDING, null,
            Instant.now())));

        new ControlServiceImpl().startAutonomousBuild();
        ConsoleContext context = ConsoleContext.get();
        AutonomousBuild.step(context, AutonomousMode.current());
        AutonomousMode.Session again = AutonomousMode.current();
        if (again != null && again.running()) {
            AutonomousBuild.step(context, again);
        }

        assertThat(decisionsOfKind(AutonomousDecisionKind.REFUSED)).hasSize(1);
    }

    // --- a parked story gets one retry, and only one -----------------------------------------------

    /**
     * The morning this exists for: S1 parked mid-flight (the test author wrote nothing), and while
     * running unattended it should not just sit there until a person reads it — a one-off bad model
     * reply is exactly what a retry fixes. Autonomous mode sends it back to be built again once,
     * automatically, through the SAME call the card's own "Build it again" button uses, and writes
     * down that it did.
     */
    @Test
    void aParkedStoryIsSentBackToBeBuiltAgainOnce() {
        install();
        UUID runId = persistParkedRun("Lend a book", "the test author wrote no test file");
        Story story = persistRunningStory("S1", "Lend a book", runId);

        new ControlServiceImpl().startAutonomousBuild();
        AutonomousMode.Session session = AutonomousMode.current();
        AutonomousBuild.step(ConsoleContext.get(), session);

        assertThat(store.getStory(story.id()).state())
            .describedAs("sent back to be built again, exactly what pressing the card's button "
                + "would have done")
            .isEqualTo(StoryState.READY);
        assertThat(store.root().runs.get(runId).parkedAt())
            .describedAs("the abandoned run no longer claims to be parked")
            .isNull();

        List<AutonomousDecision> retries = decisionsOfKind(AutonomousDecisionKind.RETRIED_PARKED_STORY);
        assertThat(retries).hasSize(1);
        assertThat(retries.get(0).answer())
            .contains("Built it again after it stopped:")
            .contains("the test author wrote no test file");

        // The retry, not a refusal - the two must not both fire for the same stop on the same tick.
        assertThat(decisionsOfKind(AutonomousDecisionKind.REFUSED)).isEmpty();

        // Deliberately untouched (ConsoleStoppedStoryQuestionsBrowserTest pins this for the human
        // path, and the automatic one must not diverge): the question itself is real history about
        // the abandoned attempt, still there and still answerable, not silently erased.
        assertThat(store.root().decisions.values().stream()
                .filter(d -> runId.equals(d.runId())).findFirst().orElseThrow().state())
            .isEqualTo(DecisionState.PENDING);
    }

    /**
     * A second park on the SAME story - not the same run, since the retry above sent it back to be
     * built fresh - is left stopped for a person, exactly like every other stop unattended running
     * has always left alone.
     */
    @Test
    void aSecondParkOnTheSameStoryIsLeftForAPerson() {
        install();
        UUID firstRunId = persistParkedRun("Lend a book", "the test author wrote no test file");
        Story story = persistRunningStory("S1", "Lend a book", firstRunId);

        new ControlServiceImpl().startAutonomousBuild();
        AutonomousMode.Session session = AutonomousMode.current();
        AutonomousBuild.step(ConsoleContext.get(), session);
        assertThat(decisionsOfKind(AutonomousDecisionKind.RETRIED_PARKED_STORY)).hasSize(1);

        // The fresh attempt the retry started parks too - a NEW run, back on the story, RUNNING
        // again exactly as starting it fresh would leave it.
        UUID secondRunId = persistParkedRun("Lend a book",
            "Red-check FAILED: the acceptance tests already pass before any change");
        Story running = store.getStory(story.id());
        running.setState(StoryState.RUNNING);
        List<UUID> runs = new ArrayList<>(running.runIds());
        runs.add(secondRunId);
        running.setRunIds(runs);
        store.saveStory(running);

        AutonomousBuild.step(ConsoleContext.get(), AutonomousMode.current());

        assertThat(decisionsOfKind(AutonomousDecisionKind.RETRIED_PARKED_STORY))
            .describedAs("still exactly one - the story already used its one retry")
            .hasSize(1);
        List<AutonomousDecision> refusals = decisionsOfKind(AutonomousDecisionKind.REFUSED);
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).answer()).contains("Left for you");
        assertThat(store.getStory(story.id()).state())
            .describedAs("left exactly where a stop leaves it - stopped, for a person")
            .isEqualTo(StoryState.RUNNING);
    }

    // --- stopping -------------------------------------------------------------------------------

    @Test
    void itStopsWhenItHasRunForAsLongAsTheSettingsAllowASingleRun() {
        install();
        // A session whose deadline is already behind it - which is what the clock running out
        // looks like from the pilot's side, on the very next tick after the hours are up.
        AutonomousMode.Session expired = new AutonomousMode.Session(projectId,
            Instant.now().minusSeconds(60), 0, 0);

        String reason = AutonomousMode.stopReason(ConsoleContext.get(), expired);

        assertThat(reason).isNotNull();
        assertThat(reason).contains("wallClockCeilingHours");
        assertThat(reason).contains("nothing new was begun");
    }

    @Test
    void itStopsBeforeGoingOverTheSpendingLimitInTheSettings() {
        BudgetsDto budgets = new BudgetsDto();
        budgets.setMaxCloudTokensPerRun(1000);
        budgets.setWallClockCeilingHours(8);
        install(budgets);
        spent.set(5000);     // the gate already reads past the cap

        upload("library.md", "The library lends books.");
        new ControlServiceImpl().startAutonomousBuild();
        new UnattendedPilot(store, 1).tick();

        assertThat(AutonomousMode.isRunning()).isFalse();
        AutonomousDecision last = lastDecision();
        assertThat(last.kind()).isEqualTo(AutonomousDecisionKind.STOPPED);
        assertThat(last.answer()).contains("maxCloudTokensPerRun");
    }

    @Test
    void stoppingItMidFlightDecidesNothingFurther() {
        install();
        upload("library.md", "The library lends books.");
        new ControlServiceImpl().startAutonomousBuild();

        assertThat(new ControlServiceImpl().stopAutonomousBuild()).isEmpty();
        assertThat(new ControlServiceImpl().autonomousRunning()).isFalse();

        int before = store.listAutonomousDecisions(projectId).size();
        for (int i = 0; i < 3; i++) {
            new UnattendedPilot(store, 1).tick();
        }
        assertThat(store.listAutonomousDecisions(projectId)).hasSize(before);
        // The last line of the diary says who stopped it and what that means.
        assertThat(lastDecision().answer()).contains("You stopped it");
    }

    @Test
    void nothingIsDecidedWhileItIsSwitchedOff() {
        install();
        upload("library.md", "The library lends books.");

        for (int i = 0; i < 3; i++) {
            new UnattendedPilot(store, 1).tick();
        }

        assertThat(store.listAutonomousDecisions(projectId)).isEmpty();
        assertThat(intakeFlow().state()).isEqualTo(GuidedFlowState.DRAFT);
    }

    @Test
    void pressingItOnAProjectWithNoDocumentSaysSoRatherThanRunningAllNight() {
        install();

        new ControlServiceImpl().startAutonomousBuild();
        new UnattendedPilot(store, 1).tick();

        assertThat(AutonomousMode.isRunning()).isFalse();
        assertThat(lastDecision().answer()).contains("no document to read");
    }

    // --- it runs where the operator can see it ---------------------------------------------------

    /**
     * The one fact the operator could not get at: it is on, and this is the gate it is at.
     *
     * <p>It used to be reachable only by asking the server, and only from inside the window that
     * armed it - so a person who closed that window to use the Console had no way of telling a
     * working night from a hung one. It is published now, on every change, onto the signal the
     * header of every screen renders from.
     *
     * <p>What is checked here is the CONTRACT, not the pixels: switched on, the signal says so and
     * names the gate; a tick moves the gate on without anything being asked for; switched off, the
     * signal says that instead. {@code ConsoleAutonomousInTheOpenBrowserTest} is where the header
     * itself is looked at.
     */
    @Test
    void whatItIsDoingIsPublishedWhereEveryScreenCanSeeIt() {
        install();
        upload("library.md", "The library lends books.");
        assertThat(AutonomousSignals.CURRENT.get().running())
            .describedAs("nothing is claimed while nothing is running")
            .isFalse();

        new ControlServiceImpl().startAutonomousBuild();
        assertThat(AutonomousSignals.CURRENT.get().running()).isTrue();

        UnattendedPilot pilot = new UnattendedPilot(store, 1);
        // Past "Starting", which is what start() publishes before any tick: the point is that the
        // gate MOVES as the machine passes it, with nothing asking the server for it.
        driveUntil(pilot, () -> !"Starting".equals(AutonomousSignals.CURRENT.get().activity()),
            "the gate it is passing to move off Starting");
        AutonomousStatus running = AutonomousSignals.CURRENT.get();
        assertThat(running.activity())
            .describedAs("the gate, in the words a person reads - short enough for a header row")
            .isNotBlank();
        assertThat(running.line())
            .describedAs("and the whole sentence, for the window and for the hover: what it is "
                + "doing, how long it has been at it, and when it stops of its own accord")
            .contains("Running on its own")
            .contains(running.activity())
            .contains("stops by");

        new ControlServiceImpl().stopAutonomousBuild();
        AutonomousStatus stopped = AutonomousSignals.CURRENT.get();
        assertThat(stopped.running()).isFalse();
        assertThat(stopped.activity())
            .describedAs("off means nothing is drawn at all, so there is no gate to name")
            .isEmpty();
        assertThat(stopped.line()).contains("You stopped it");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicLong spent =
        new java.util.concurrent.atomic.AtomicLong();

    private ScriptedSwarm install() {
        BudgetsDto budgets = new BudgetsDto();
        budgets.setWallClockCeilingHours(8);
        budgets.setMaxCloudTokensPerRun(10_000_000);
        return install(budgets);
    }

    private ScriptedSwarm install(BudgetsDto budgets) {
        ScriptedSwarm swarm = new ScriptedSwarm();
        ConsoleContext context = new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(swarm)
            .withStoryRuns((goal, kind, storyId) -> persistRun(goal, RunState.EXECUTING, storyId))
            .withHealth(new ConsoleContext.Health() {
                public String sparkStatus() { return "up"; }
                public String dockerStatus() { return "off"; }
                public long budgetUsed() { return spent.get(); }
                public long budgetMax() { return budgets.getMaxCloudTokensPerRun(); }
            })
            .withConfigForms(new ConsoleContext.ConfigForms() {
                public List<com.swarmcoder.console.api.RoleEntryDto> globalRoles() {
                    return List.of();
                }
                public String saveGlobalRoles(
                        List<com.swarmcoder.console.api.RoleEntryDto> roles) {
                    return "";
                }
                public BudgetsDto budgets() { return budgets; }
                public String saveBudgets(BudgetsDto b) { return ""; }
                public List<com.swarmcoder.console.api.RoleEntryDto> projectRoles(String id) {
                    return List.of();
                }
                public int projectWorkersPerTask(String id) { return 0; }
                public String saveProjectConfig(String id, String csv,
                        List<com.swarmcoder.console.api.RoleEntryDto> roles, int workers) {
                    return "";
                }
            });
        ConsoleContext.set(context);
        return swarm;
    }

    private GuidedFlow intakeFlow() {
        return GuidedFlows.ensureIntake(store, projectId);
    }

    private List<AutonomousDecision> answeredQuestions() {
        return decisionsOfKind(AutonomousDecisionKind.ANSWERED_QUESTION);
    }

    private List<AutonomousDecision> decisionsOfKind(AutonomousDecisionKind kind) {
        List<AutonomousDecision> out = new ArrayList<>();
        for (AutonomousDecision decision : store.listAutonomousDecisions(projectId)) {
            if (decision.kind() == kind) {
                out.add(decision);
            }
        }
        return out;
    }

    private AutonomousDecision lastDecision() {
        List<AutonomousDecision> all = store.listAutonomousDecisions(projectId);
        assertThat(all).as("the diary should not be empty").isNotEmpty();
        return all.get(all.size() - 1);
    }

    /**
     * Ticks the pilot until the condition holds, exactly as the 30-second timer would.
     *
     * <p>Sleeping between ticks rather than looping hot: the flows run on their own threads and a
     * tick that arrives while one is mid-model-call has nothing to do, which is the real behaviour.
     */
    private void driveUntil(UnattendedPilot pilot, java.util.function.BooleanSupplier done,
                            String what) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) {
                return;
            }
            pilot.tick();
            if (!AutonomousMode.isRunning() && !done.getAsBoolean()) {
                // It stopped on its own. One more check, then report what it said - a bare timeout
                // here would hide the reason it gave up.
                if (done.getAsBoolean()) {
                    return;
                }
                AutonomousMode.Session session = AutonomousMode.current();
                throw new AssertionError("autonomous mode stopped while waiting for " + what
                    + ": " + (session == null ? "(no session)" : session.stoppedBecause()));
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
        AutonomousMode.Session session = AutonomousMode.current();
        throw new AssertionError("never reached " + what + " within " + WAIT_MILLIS
            + "ms - last activity was "
            + (session == null ? "(no session)" : session.activity()));
    }

    /**
     * Loads a document exactly as the Console's upload endpoint does - ingest, then attach it to the
     * project's intake flow. Attaching is the endpoint's job in production, so a test that only
     * ingested would be testing a state the product never reaches.
     */
    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).isFalse();
        GuidedFlows.attachUploaded(store, projectId, result.document().id());
        return result.document();
    }

    private BrdRequirement unprovable(String handle, String title) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title,
            title, Priority.MEDIUM, RequirementStatus.DRAFT, "Lending");
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "it feels welcoming", null);
        criterion.setStatus(CriterionStatus.PROPOSED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        return requirement;
    }

    private UUID persistRun(String goal, RunState state, UUID storyId) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, state, projectId, storyId, null,
            UUID.randomUUID(), null, Instant.now(), new RunReport(runId, goal));
        append(() -> store.root().runs.put(runId, run));
        return runId;
    }

    /**
     * A run parked mid-flight (non-terminal state, {@code parkedAt} set) with the one BLOCKED_TASK
     * decision a real park always raises alongside it — the shape {@code GreenfieldWorkflow.parkRun}
     * leaves behind, reproduced by hand since this module cannot reach sc-workflow.
     */
    private UUID persistParkedRun(String goal, String parkReason) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, projectId, null, null,
            UUID.randomUUID(), null, Instant.now(), new RunReport(runId, goal));
        run.setHeartbeatAt(Instant.now());
        run.setParkedAt(Instant.now());
        run.setParkReason(parkReason);
        UUID decisionId = UUID.randomUUID();
        append(() -> {
            store.root().runs.put(runId, run);
            store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                DecisionKind.BLOCKED_TASK, parkReason, DecisionState.PENDING, null, Instant.now()));
        });
        return runId;
    }

    /** A minimal RUNNING story carrying one run, for the pilot's retry-a-parked-story tests. */
    private Story persistRunningStory(String key, String title, UUID runId) {
        Story story = new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title,
            title, StoryState.RUNNING, List.of(), List.of(), null, 0, StoryOrigin.BACKLOG, null,
            null, "human", new ArrayList<>(List.of(runId)), null, null, null, null,
            Instant.now(), Instant.now());
        store.saveStory(story);
        return story;
    }

    private void append(Runnable work) {
        try {
            store.append(() -> {
                work.run();
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The analyst and the planner, scripted - and the stand-in answerer, which is this feature's own
     * model call and is recognised by its prompt.
     *
     * <p>Dispatching on the prompt rather than on a call counter, because the number of calls the
     * intake and planning engines make is their business and a counter would make this test fail
     * whenever they changed.
     */
    private static final class ScriptedSwarm implements ConsoleContext.ChatModel {

        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger answers = new AtomicInteger();
        private final AtomicInteger drafting = new AtomicInteger();
        /** Whether the analyst attaches the passage its questions arose from. */
        volatile boolean quoteWithQuestions;
        /** Whether the stand-in claims its answers came out of the documents. */
        volatile boolean groundedAnswers;

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                sb.append(message.get("role")).append(": ").append(message.get("content"))
                    .append('\n');
            }
            String prompt = sb.toString();
            prompts.add(prompt);
            return Stream.of(replyTo(prompt));
        }

        private String replyTo(String prompt) {
            if (prompt.contains("standing in for a person who is asleep")) {
                answers.incrementAndGet();
                return "{\"answer\":\"Keep them in the project's own database.\","
                    + "\"grounded\":" + groundedAnswers + ","
                    + "\"why\":\"the smallest ordinary choice\"}";
            }
            if (prompt.contains("THE BACKLOG AS IT STANDS") || prompt.contains("COVERAGE")) {
                return prompt.contains("ANSWER:") || prompt.contains("SKIPPED")
                    ? storyProposals() : planningQuestions();
            }
            // Intake: the question round first, then the drafting pass.
            if (drafting.getAndIncrement() == 0) {
                return intakeQuestions();
            }
            return intakeProposals();
        }

        private String intakeQuestions() {
            String quote = quoteWithQuestions
                ? "\"sourceQuote\":\"Members may borrow up to five at a time.\","
                    + "\"sourceDocument\":\"library.md\","
                : "";
            return "{\"questions\":[{\"subject\":\"Storage\","
                + "\"text\":\"Where are the books kept?\",\"kind\":\"TEXT\"," + quote
                + "\"options\":[]}]}";
        }

        private String intakeProposals() {
            return """
                {"proposals":[\
                {"kind":"ADD","handle":"","title":"Borrow a book",\
                "rationale":"the document says a member may borrow a book on the shelf",\
                "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Lending",\
                "text":"A member can borrow a book that is on the shelf.",\
                "criteria":[{"text":"a book on the shelf can be borrowed",\
                "test":"swarm.accept.BorrowTest#borrowsAShelvedBook"}]}\
                ]}""";
        }

        private String planningQuestions() {
            return "{\"questions\":[{\"subject\":\"Sequencing\","
                + "\"text\":\"Which comes first?\",\"kind\":\"TEXT\",\"options\":[]}]}";
        }

        private String storyProposals() {
            return """
                {"proposals":[\
                {"kind":"ADD","storyKind":"DELIVERY","title":"A member can borrow a shelved book",\
                "delivers":"R1:C1","narrative":"As a member I want to borrow a book",\
                "rationale":"one demonstrable slice"}\
                ]}""";
        }

        int answersGiven() {
            return answers.get();
        }
    }
}
