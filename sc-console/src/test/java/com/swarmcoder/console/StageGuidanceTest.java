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

import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.NextAction;
import com.swarmcoder.console.api.StageGuidance;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.IterationState;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each stage answers for itself, and a draft requirement no longer silences the whole Console.
 *
 * <p>The defect this pins down was reported in the operator's own words, standing in the Plan stage:
 * <em>"there is nothing to help me to get to the next step"</em> and <em>"I have previously promoted
 * S1, but S1 is nowhere to be seen"</em>. Their project had nineteen requirements with four still
 * drafts, six proposed stories and a run in flight — and because
 * {@link ConsoleReadiness#nextAction()} is one global answer computed first-match-wins in dependency
 * order, the only thing the Console would say, anywhere, permanently, was "go back and promote your
 * requirements". Plan's own bar said it too, which is worse than saying nothing: it reads as "you
 * cannot proceed" while six decisions sit on the board underneath it.
 *
 * <p>So what is under test is <b>independence</b>. Requirements' drafts, Plan's proposals and Build's
 * runs are all true at the same time, because a real project refines scope while earlier scope is
 * already being built, and each stage's guidance has to describe its own facts regardless of what
 * the stages either side of it are doing.
 *
 * <p>The global next action is asserted alongside, in the same situations, because it keeps its old
 * meaning on purpose: it is the LANDING recommendation, the thing that decides which stage the shell
 * opens on, and there the dependency order is still the right answer.
 */
class StageGuidanceTest {

    @TempDir
    Path dir;

    private ArtifactStore store;
    private UUID projectId;

    @BeforeEach
    void openStore() {
        store = new ArtifactStore(dir);
        Project project = store.ensureProject("guidance", dir.toString(), List.of());
        projectId = project.id();
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(store::listProjects, project::id, (n, p, c) -> null, id -> { }));
    }

    @AfterEach
    void closeStore() throws Exception {
        // Installed statically; leaving one behind would let a later test run against a closed store.
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- 1. the reported defect -------------------------------------------------------------------

    /**
     * The operator's actual project, reduced to its shape: some requirements still drafts, stories
     * already proposed against the ones that were agreed, and a run in flight.
     *
     * <p>Every earlier version of this told them to go to Requirements and nothing else. Plan must
     * now talk about STORIES, Requirements about its drafts, and Build about the run — all three at
     * once, because all three are true at once.
     */
    @Test
    void withDraftsAndProposedStoriesPlanTalksAboutStoriesNotRequirements() throws Exception {
        // A real repository, unlike the other fixtures here: this operator had a run in flight, so
        // the project they were looking at was one where runs are possible.
        makeRunnable();
        brd(RequirementStatus.ACTIVE, RequirementStatus.ACTIVE, RequirementStatus.ACTIVE,
            RequirementStatus.ACTIVE, RequirementStatus.ACTIVE, RequirementStatus.ACTIVE,
            RequirementStatus.DRAFT, RequirementStatus.DRAFT, RequirementStatus.DRAFT,
            RequirementStatus.DRAFT);
        for (int i = 0; i < 6; i++) {
            story("S" + (i + 1), StoryState.DRAFT, null);
        }
        building("S7");

        ConsoleReadiness readiness = ReadinessPublisher.build(ConsoleContext.get());

        StageGuidance plan = guidance(readiness, StageGuidance.PLAN);
        assertThat(plan.action())
            .as("Plan's own guidance must be about Plan's own work")
            .isEqualTo(NextAction.PROMOTE_STORIES);
        // "suggested", not "proposed": the board's first column is headed "Suggested" now, and the
        // sentence above it has to use the operator's word for the thing they are looking at.
        assertThat(plan.text())
            .contains("6 suggested stories")
            .doesNotContain("requirement");
        // The board holds six cards and every one of them is a decision the operator owes.
        assertThat(plan.attention()).isEqualTo(6);
        assertThat(plan.isWaiting()).isFalse();

        // …while Requirements says its own true thing, in the same breath.
        StageGuidance requirements = guidance(readiness, StageGuidance.REQUIREMENTS);
        assertThat(requirements.action()).isEqualTo(NextAction.PROMOTE_DRAFTS);
        assertThat(requirements.text()).startsWith("4 requirements are still drafts");
        assertThat(requirements.attention()).isEqualTo(4);

        // …and Build answers "where did the story I started go?", which is the second half of the
        // complaint. It is not an instruction, so it carries no attention and no step.
        StageGuidance build = guidance(readiness, StageGuidance.BUILD);
        // "1 story is being built", not "1 run is in flight": a run is machinery the operator never
        // handles, and "in flight" was said of runs parked at APPROVAL that nothing was driving.
        assertThat(build.text()).contains("1 story is being built");
        assertThat(build.action()).isEqualTo(NextAction.NONE);
        assertThat(build.attention()).isZero();

        // The GLOBAL answer — which decides where the shell lands — follows the FURTHEST stage with
        // outstanding work, so it lands on Plan and not back on Requirements. Dependency order says
        // the drafts come first and it is right about dependencies, but as a landing rule it sent
        // the operator backwards for ever: any straggler outranked everything they had since done.
        // The drafts are not forgotten — Requirements keeps its badge of 4, asserted above.
        assertThat(readiness.nextAction()).isEqualTo(NextAction.PROMOTE_STORIES);
        assertThat(readiness.nextActionText()).isEqualTo(plan.text());
    }

    // --- 1b. the SECOND report of the same defect --------------------------------------------------

    /**
     * Unanswered decisions are attention, and they are Build's.
     *
     * <p>The operator's screen, reported twice: nine runs, thirty-seven pending approvals on the
     * status strip at the bottom of the window, and a Build chip carrying <b>no badge and no
     * sentence</b>. {@code buildGuidance} returned attention {@code 0} unconditionally on the
     * reasoning that watching a run is not a decision the operator owes — true of watching, false of
     * answering — and {@code Facts} never read the decisions at all.
     *
     * <p>Three things are pinned here, and the third is the one that is easy to get wrong:
     *
     * <ol>
     *   <li>Build's attention is the count of unanswered decisions, and its sentence leads with the
     *       number.</li>
     *   <li>Another project's decision does not leak into this project's count.</li>
     *   <li>The story in REVIEW is counted <b>once</b>, in Plan. A verdict is given on the Plan
     *       board, so adding it to Build as well would badge one decision on two chips and make the
     *       bar claim more outstanding work than exists.</li>
     * </ol>
     */
    @Test
    void pendingDecisionsAreBuildsAttentionAndDoNotDoubleCountReviewStories() throws Exception {
        makeRunnable();
        brd(RequirementStatus.ACTIVE);
        // One story back from a run and awaiting a verdict — Plan's business, and nobody else's.
        story("S1", StoryState.REVIEW, null);
        building("S2");

        // Two decisions this project owes, joined the two ways real data joins them: one through a
        // run that carries this project's id, and one BUDGET_EXTENSION, which is minted with a null
        // runId by construction and can therefore never be attributed to any project at all.
        UUID runHere = run(projectId);
        decision(runHere, DecisionKind.BLOCKED_TASK, DecisionState.PENDING);
        decision(null, DecisionKind.BUDGET_EXTENSION, DecisionState.PENDING);
        // …one already answered, which is owed by nobody…
        decision(runHere, DecisionKind.BLOCKED_TASK, DecisionState.RESOLVED);
        // …and one belonging to a different project, which must not appear on this project's badge.
        decision(run(UUID.randomUUID()), DecisionKind.BLOCKED_TASK, DecisionState.PENDING);

        ConsoleReadiness readiness = ReadinessPublisher.build(ConsoleContext.get());

        StageGuidance build = guidance(readiness, StageGuidance.BUILD);
        assertThat(build.attention())
            .as("two decisions are unanswered here; the resolved one and the other project's are not")
            .isEqualTo(2);
        assertThat(build.action()).isEqualTo(NextAction.ANSWER_DECISIONS);
        assertThat(build.text())
            .as("the sentence must lead with the number, not bury it")
            .startsWith("2 builds stopped to ask you something");
        // …and it must name WHERE they are answered, which is no longer a queue: the Approval Center
        // is retired as a destination (UX v3 2.3), and each question is on the card of the story it
        // belongs to. Pointing at a surface that no longer exists is worse than saying nothing.
        assertThat(build.text())
            .contains("on its own story's card")
            .doesNotContain("Approvals");
        // The consequence, stated as it actually is. Nothing in the engine BLOCKS on a decision —
        // no thread parks on one and resolving it restarts nothing — so the sentence must not claim
        // that runs are waiting on the answer. What it may say is where they came from.
        // Nothing in the engine BLOCKS on a decision — no thread parks on one and resolving it
        // restarts nothing — so the sentence must not claim that runs are waiting on the answer.
        assertThat(build.text()).doesNotContain("waiting on you");
        // ONE line, and it stops. This was a paragraph — where the question came from, that answering
        // restarts nothing, how many runs were also in flight — which fitted a strip with a stage to
        // itself. There is one guidance line in the header now, sharing its row with the counts, and a
        // paragraph there wraps and pushes its own button onto a second line.
        assertThat(build.text().length())
            .as("the header's one line has to fit beside the counts")
            .isLessThan(160);

        // Plan owns the verdict, alone. One story in REVIEW, one badge, on the stage where the
        // Accept button is.
        StageGuidance plan = guidance(readiness, StageGuidance.PLAN);
        assertThat(plan.action()).isEqualTo(NextAction.REVIEW_RESULTS);
        assertThat(plan.attention())
            .as("the REVIEW story is Plan's one item — and Build's 2 must not include it")
            .isEqualTo(1);

        // The shell lands on the furthest stage with work, which is now Build — where the answers
        // are actually given. Before this, thirty-seven owed decisions could not move it at all.
        assertThat(readiness.nextAction()).isEqualTo(NextAction.ANSWER_DECISIONS);
        assertThat(readiness.nextActionText()).isEqualTo(build.text());
    }

    /**
     * The Build badge and the status strip's "N to approve" must be the same number.
     *
     * <p>They were about to be computed by two separate inline loops over
     * {@code store.root().decisions}, which is how one surface comes to insist there are
     * thirty-seven and another that there are none. Both read {@code PendingDecisions} now, and on
     * the single-project install that every operator actually has, the two answers are identical —
     * including for a decision that belongs to no run and so cannot be joined to a project.
     */
    @Test
    void theBuildBadgeAgreesWithTheStatusStripsApprovalCount() throws Exception {
        makeRunnable();
        brd(RequirementStatus.ACTIVE);
        story("S1", StoryState.READY, null);
        decision(run(projectId), DecisionKind.BLOCKED_TASK, DecisionState.PENDING);
        decision(null, DecisionKind.BUDGET_EXTENSION, DecisionState.PENDING);
        decision(run(projectId), DecisionKind.BLOCKED_TASK, DecisionState.PENDING);

        int strip = new HealthServiceImpl().snapshot().getPendingApprovals();
        int badge = ReadinessPublisher.build(ConsoleContext.get()).attention(StageGuidance.BUILD);

        assertThat(strip).isEqualTo(3);
        assertThat(badge)
            .as("the stage badge and the status strip must never disagree about what is owed")
            .isEqualTo(strip);
    }

    /**
     * A decision already answered is owed by nobody, and a project with none is silent about them.
     *
     * <p>The negative half matters as much as the positive: guidance that cannot fall silent is
     * nagging, and a Build bar permanently saying "0 decisions are waiting" is the kind of element
     * an operator learns to skip — and then misses on the day it says thirty-seven.
     */
    @Test
    void aResolvedDecisionLeavesBuildWithNothingToSay() throws Exception {
        makeRunnable();
        brd(RequirementStatus.ACTIVE);
        building("S1");
        decision(run(projectId), DecisionKind.BLOCKED_TASK, DecisionState.RESOLVED);

        StageGuidance build =
            guidance(ReadinessPublisher.build(ConsoleContext.get()), StageGuidance.BUILD);

        assertThat(build.attention()).isZero();
        assertThat(build.action()).isEqualTo(NextAction.NONE);
        // It still answers "where did the story I started go?" — that was never the defect.
        assertThat(build.text()).contains("1 story is being built");
        assertThat(build.text()).doesNotContain("decision");
    }

    /**
     * Decisions are owed whether or not a run could start today.
     *
     * <p>Build falls silent when {@code canRun} is false, because {@code BuildStage} already renders
     * the blocker in its own strip and two phrasings of one blocker is how two surfaces come to
     * disagree. That gate must not swallow the decisions: a repository that moved does not unmake
     * the answers already owed, and going quiet about them is the same disappearing act that put
     * this defect in front of the operator twice.
     */
    @Test
    void decisionsAreStillSaidWhenRunsAreImpossible() {
        // No .git in the temp folder, so canRun is false — the blocker path.
        brd(RequirementStatus.ACTIVE);
        story("S1", StoryState.READY, null);
        decision(run(projectId), DecisionKind.BLOCKED_TASK, DecisionState.PENDING);

        StageGuidance build =
            guidance(ReadinessPublisher.build(ConsoleContext.get()), StageGuidance.BUILD);

        assertThat(build.attention()).isEqualTo(1);
        assertThat(build.text()).startsWith("1 build stopped to ask you something");
        // …and it still does not repeat the run blocker, which BuildStage owns.
        assertThat(build.text()).doesNotContain("git repository");
    }

    // --- 2. Plan's own progression ----------------------------------------------------------------

    /**
     * accept → build, with each step announcing the next — and <b>no scheduling step between them</b>.
     *
     * <p>This test used to assert the opposite, and it was pinning a defect. It walked
     * proposal → CREATE_ITERATION → SCHEDULE_STORIES → START_RUN, on the reasoning that a story has
     * to be placed in an iteration before a run can pick it up. Nothing in the service has ever
     * worked that way: {@link BacklogServiceImpl#startSession} refuses anything that is not
     * {@link StoryState#READY} and never so much as reads {@code iterationId}. The two middle steps
     * were guidance towards a gate that does not exist, on the one line the operator reads before
     * touching the Plan board — and the board's own captions agreed with them, which is how
     * "it must be very clear in the UI how to get a story to be built" came back as a complaint.
     *
     * <p>So the sequence asserted here is the real one, and the iteration is exercised for what it
     * actually is: something that can be added or not added without changing a thing about whether
     * the story can be built.
     */
    @Test
    void planWalksTheOperatorFromAcceptingToBuildingWithNoSchedulingStepBetween() {
        brd(RequirementStatus.ACTIVE);

        // Nothing at all: the wizard is the way in.
        assertThat(plan().action()).isEqualTo(NextAction.PLAN_STORIES);

        // A suggestion — accepting it is what makes it buildable.
        Story story = story("S1", StoryState.DRAFT, null);
        assertThat(plan().action()).isEqualTo(NextAction.PROMOTE_STORIES);

        // Accepted, and in no iteration — which is not a problem, a queue, or a waiting state. The
        // next step is building it, and it is refused here rather than offered because this temp
        // folder is not a git repository and a run would fail at dispatch. Pointing at a run that
        // cannot happen is the broken promise readiness exists to avoid, so Plan says what it is
        // waiting for and names Setup. What it must NOT do is name an iteration.
        story.setState(StoryState.READY);
        store.saveStory(story);
        StageGuidance readyToBuild = plan();
        assertThat(readyToBuild.action()).isEqualTo(NextAction.NONE);
        assertThat(readyToBuild.waitingOn()).isEqualTo(StageGuidance.SETUP);
        assertThat(readyToBuild.text())
            .contains("ready to build")
            .contains("nothing can run until setup is finished")
            .as("an iteration is grouping; it is never what a story is waiting for")
            .doesNotContain("iteration");

        // Putting it in one changes the guidance not at all, which is the whole claim.
        Iteration iteration = iteration("Slice 1");
        story.setIterationId(iteration.id());
        store.saveStory(story);
        assertThat(plan().text()).isEqualTo(readyToBuild.text());

        // A verdict outranks everything: a run that has come back is holding up the work behind it.
        story.setState(StoryState.REVIEW);
        store.saveStory(story);
        assertThat(plan().action()).isEqualTo(NextAction.REVIEW_RESULTS);
    }

    /**
     * With a repository behind it, the step Plan names for an agreed story is BUILDING it — and it
     * says so in the words that are on the button, iteration or no iteration.
     */
    @Test
    void anAgreedStoryIsReadyToBuildWithOrWithoutAnIteration() throws Exception {
        makeRunnable();
        brd(RequirementStatus.ACTIVE);
        Story story = story("S1", StoryState.READY, null);

        StageGuidance loose = plan();
        assertThat(loose.action()).isEqualTo(NextAction.START_RUN);
        assertThat(loose.text())
            .contains("1 story is agreed and ready to build")
            .contains("Build this story")
            .as("the optionality has to be said, because the board used to say the opposite")
            .contains("optional grouping");

        story.setIterationId(iteration("Slice 1").id());
        store.saveStory(story);
        assertThat(plan().text())
            .as("grouping a story changes nothing about whether it can be built")
            .isEqualTo(loose.text());
    }

    // --- 3. waiting is not the same as "your next step is elsewhere" -------------------------------

    /**
     * A stage that genuinely cannot act says what it is waiting for and which stage it comes from —
     * and that is a DIFFERENT shape from a step, with no action on it, so a client can render it
     * differently and can never mistake it for an instruction to leave.
     */
    @Test
    void aStageWithNothingToDoNamesWhatItIsWaitingForAndFromWhere() {
        brd(RequirementStatus.DRAFT, RequirementStatus.DRAFT);

        StageGuidance plan = plan();
        assertThat(plan.action()).isEqualTo(NextAction.NONE);
        assertThat(plan.isWaiting()).isTrue();
        assertThat(plan.waitingOn()).isEqualTo(StageGuidance.REQUIREMENTS);
        assertThat(plan.text())
            .contains("planning works from agreed requirements")
            .contains("all 2");
        // Waiting is not attention: there is nothing on this board for the operator to decide, and
        // a count here would put a badge on a stage with an empty board.
        assertThat(plan.attention()).isZero();

        StageGuidance build = guidance(ReadinessPublisher.build(ConsoleContext.get()),
            StageGuidance.BUILD);
        // Runs are impossible in a temp folder, and BuildStage already renders runBlocker in its own
        // strip — a second phrasing of one blocker is how two surfaces come to disagree about it.
        assertThat(build.hasText()).isFalse();
    }

    /** With an empty BRD, Requirements owns the only step there is. */
    @Test
    void anEmptyBrdPutsTheStepInRequirementsAndNowhereElse() {
        ConsoleReadiness readiness = ReadinessPublisher.build(ConsoleContext.get());

        assertThat(guidance(readiness, StageGuidance.REQUIREMENTS).action())
            .isEqualTo(NextAction.ANALYSE_DOCUMENTS);
        assertThat(guidance(readiness, StageGuidance.PLAN).waitingOn())
            .isEqualTo(StageGuidance.REQUIREMENTS);
        assertThat(readiness.nextAction()).isEqualTo(NextAction.ANALYSE_DOCUMENTS);
    }

    // --- 4. the contract the client leans on -------------------------------------------------------

    /**
     * A settled stage says nothing, and "nothing" has to be distinguishable from "not computed" —
     * the next-step bar hides on both, but the stage bar ticks a stage off only for the first.
     */
    @Test
    void aSettledStageIsSilentAndAProjectWithNoGuidanceIsNotTickedOff() {
        brd(RequirementStatus.ACTIVE);
        story("S1", StoryState.DONE, null);

        StageGuidance requirements =
            guidance(ReadinessPublisher.build(ConsoleContext.get()), StageGuidance.REQUIREMENTS);
        assertThat(requirements.hasText()).isFalse();
        assertThat(requirements.hasAction()).isFalse();
        assertThat(requirements.isWaiting()).isFalse();
        assertThat(requirements.attention()).isZero();

        // An empty readiness — the signal's initial value, and anything from a server that predates
        // the field — carries no guidance at all, and asking for one answers null rather than
        // inventing a placeholder a client would render as an empty bar.
        assertThat(ConsoleReadiness.empty().stages()).isEmpty();
        assertThat(ConsoleReadiness.empty().guidance(StageGuidance.PLAN)).isNull();
        assertThat(ConsoleReadiness.empty().attention(StageGuidance.PLAN)).isZero();
    }

    /**
     * Guidance travels on a signal that dedups by value, so a re-publish of an unchanged project has
     * to compare equal — otherwise every readiness push redraws every bound view in the shell.
     */
    @Test
    void twoReadinessValuesForTheSameProjectAreEqual() {
        brd(RequirementStatus.DRAFT, RequirementStatus.ACTIVE);
        story("S1", StoryState.DRAFT, null);

        ConsoleReadiness first = ReadinessPublisher.build(ConsoleContext.get());
        ConsoleReadiness second = ReadinessPublisher.build(ConsoleContext.get());

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        // …and a project that has moved does not.
        store.saveStory(promoted());
        assertThat(ReadinessPublisher.build(ConsoleContext.get())).isNotEqualTo(first);
    }

    /**
     * {@link NextAction} travels the wire by name and is append-only — inserting a constant has
     * taken this app down once. The four original keys are pinned here so a reorder is a test
     * failure rather than a deployment incident.
     */
    @Test
    void theOriginalNextActionKeysNeverMove() {
        assertThat(NextAction.values()[0]).isEqualTo(NextAction.NONE);
        assertThat(NextAction.NONE.key()).isEmpty();
        assertThat(NextAction.ANALYSE_DOCUMENTS.key()).isEqualTo("analyse-documents");
        assertThat(NextAction.PROMOTE_DRAFTS.key()).isEqualTo("promote-drafts");
        assertThat(NextAction.PLAN_STORIES.key()).isEqualTo("plan-stories");
        assertThat(NextAction.START_RUN.key()).isEqualTo("start-run");
        assertThat(NextAction.ofKey("promote-stories")).isEqualTo(NextAction.PROMOTE_STORIES);
        assertThat(NextAction.ofKey("no-such-step")).isEqualTo(NextAction.NONE);
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private StageGuidance plan() {
        return guidance(ReadinessPublisher.build(ConsoleContext.get()), StageGuidance.PLAN);
    }

    private static StageGuidance guidance(ConsoleReadiness readiness, String stage) {
        StageGuidance guidance = readiness.guidance(stage);
        assertThat(guidance).as("no guidance for the %s stage", stage).isNotNull();
        return guidance;
    }

    /**
     * Makes the project's folder something git would accept, which is all readiness checks — a
     * {@code .git} directory. Without it {@code canRun} is false and Build has nothing to say, which
     * is correct but is not the situation being reproduced.
     */
    private void makeRunnable() throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve(".git"));
    }

    /** One requirement per status, handled R1, R2, … — the counts are what the guidance reads. */
    private void brd(RequirementStatus... statuses) {
        Brd brd = store.ensureBrd(projectId);
        int index = 1;
        for (RequirementStatus status : statuses) {
            brd.requirements().add(new BrdRequirement(UUID.randomUUID(), "R" + index,
                "Requirement " + index, "it must work", Priority.HIGH, status, null));
            index++;
        }
        store.saveBrd(brd);
    }

    /**
     * A story that is genuinely being built: RUNNING, with a live run linked to it.
     *
     * <p>The distinction is load-bearing and it is why this helper exists. A story that says RUNNING
     * with no run on record is STRANDED — nothing is driving it — and both the pipeline board and the
     * readiness publisher now say so, from the one shared derivation ({@code BuildHealth}). Writing
     * the fixture without the run would be asserting on the case where the operator is owed
     * something, while reading as though it were the case where they are not.
     */
    private Story building(String key) {
        Story story = story(key, StoryState.RUNNING, null);
        UUID runId = run(projectId);
        story.setRunIds(new ArrayList<>(List.of(runId)));
        return store.saveStory(story);
    }

    private Story story(String key, StoryState state, UUID iterationId) {
        Story story = new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY,
            key + " title", "as a user", state, new ArrayList<>(), new ArrayList<>(), iterationId,
            0, StoryOrigin.BACKLOG, null, null, null, new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        return store.saveStory(story);
    }

    /** The first story, promoted — used only to prove the readiness value actually changed. */
    private Story promoted() {
        Story story = store.listStories(projectId).get(0);
        story.setState(StoryState.READY);
        return story;
    }

    private Iteration iteration(String name) {
        return store.saveIteration(new Iteration(UUID.randomUUID(), projectId, name, null, 1,
            IterationState.PLANNING, Instant.now(), null));
    }

    /** A persisted run belonging to a project — the join a decision reaches its project through. */
    private UUID run(UUID owner) {
        UUID id = UUID.randomUUID();
        Run run = new Run(id, WorkflowKind.GREENFIELD, RunState.EXECUTING, owner, null, null, null,
            null, Instant.now(), null);
        try {
            store.append(() -> store.root().runs.put(id, run)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not seed run " + id, e);
        }
        return id;
    }

    /**
     * A decision straight into the store, as the workflow and the swarm queue them — there is no
     * service that mints one, because nothing but the engine ever does.
     *
     * @param runId null for a {@code BUDGET_EXTENSION}, which is created with no run by design
     */
    private void decision(UUID runId, DecisionKind kind, DecisionState state) {
        UUID id = UUID.randomUUID();
        try {
            store.append(() -> store.root().decisions.put(id, new Decision(id, runId, kind,
                "seeded for the test", state, state == DecisionState.RESOLVED ? "answered" : null,
                Instant.now()))).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not seed decision " + id, e);
        }
    }
}
