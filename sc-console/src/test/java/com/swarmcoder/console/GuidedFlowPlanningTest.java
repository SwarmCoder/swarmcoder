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

import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backlog-planning wizard end to end, with a scripted planner instead of a live model.
 *
 * <p>What is under test is the <em>contract around</em> the model, not the model: that planning is
 * refused when there is no agreed scope to plan against, that a round of questions parks the flow
 * instead of guessing, that a skipped question still lets it finish and is told to the planner as
 * such, that nothing reaches the backlog until the operator applies, that what lands is a DRAFT
 * story claiming exactly the criteria the proposal named, and that a story naming a criterion the
 * BRD does not have is caught during the review rather than at Apply. All of those are rules the
 * operator relies on and none of them need an LLM to prove.
 *
 * <p>The planning runs on its own daemon thread, so every state transition is waited for with a
 * bounded poll on the persisted flow — the store, not the signal, is the source of truth.
 */
class GuidedFlowPlanningTest {

    /** Long enough to absorb a slow EclipseStore flush, short enough to fail rather than hang. */
    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private PlanningFlowServiceImpl service;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        service = new PlanningFlowServiceImpl();
    }

    @AfterEach
    void closeStore() throws Exception {
        // The context is installed statically; leaving one behind would let a later test run
        // against this test's closed store.
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- 1. the happy path ------------------------------------------------------------------------

    @Test
    void aRoundOfQuestionsThenStoriesThatOnlyLandWhenTheOperatorApplies() {
        FakePlanner planner = install(
            """
            {"questions":[{"subject":"Sequencing","text":"Which comes first?","kind":"CHOICE",\
            "options":["checkout","refunds"]}]}""",
            storyProposalsReply());
        agreedBrd();

        FlowView opened = service.planning();
        assertThat(opened.flow()).isNotNull();
        assertThat(opened.flow().state()).isEqualTo(GuidedFlowState.DRAFT);
        // The inputs the wizard shows instead of a document list. Without these the first screen is
        // one button and no account of what pressing it reads.
        assertThat(opened.existingRequirements()).isEqualTo(2);
        assertThat(opened.unclaimedCriteria()).isEqualTo(3);
        assertThat(opened.backlogStories()).isZero();
        assertThat(opened.coverage()).contains("R1").contains("R2").contains("unclaimed");
        String flowId = opened.flow().id().toString();

        assertThat(service.start(flowId)).isEmpty();

        // --- the question round ---------------------------------------------------------------
        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        assertThat(awaiting.stepLabel()).contains("1 question");
        List<FlowQuestion> questions = store.listFlowQuestions(awaiting.id());
        assertThat(questions).hasSize(1);
        FlowQuestion question = questions.get(0);
        assertThat(question.subject()).isEqualTo("Sequencing");
        assertThat(question.options()).containsExactly("checkout", "refunds");
        assertThat(service.answer(flowId, question.id().toString(), "checkout")).isEmpty();

        assertThat(service.submitAnswers(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        // The planner is given the requirement graph, the backlog, the coverage report and the
        // answers. Any one of those missing is a plan drawn from something the operator never saw.
        assertThat(planner.prompt(0))
            .contains("R1:C1")
            .contains("THE BACKLOG AS IT STANDS")
            .contains("COVERAGE");
        assertThat(planner.prompt(1)).contains("ANSWER: checkout");

        // --- the review -------------------------------------------------------------------------
        List<FlowProposal> proposals = store.listFlowProposals(awaiting.id());
        assertThat(proposals).hasSize(3);

        FlowProposal delivery = proposals.get(0);
        assertThat(delivery.kind()).isEqualTo(FlowProposalKind.ADD);
        assertThat(delivery.title()).isEqualTo("Guest can pay and see it worked");
        // The criteria refs ARE the story's identity — what apply resolves and what decides whether
        // the story may exist at all.
        assertThat(delivery.handle()).isEqualTo("R1:C1,R1:C2");
        // ...and `after` is the same story rendered for a human, with the criteria spelled out, so
        // the operator does not have to hold the requirement graph in their head to review it.
        assertThat(delivery.after())
            .contains("Narrative:")
            .contains("a purchase completes with no account")
            .contains("a receipt is shown");
        // No story kind on the review screen. Kinds are cut from every operator surface (UX v3
        // §6) and this block used to open with the Java constant, DELIVERY or ENABLER.
        assertThat(delivery.after())
            .describedAs("no Java constant reaches the block the operator reads")
            .doesNotContain("DELIVERY").doesNotContain("ENABLER").doesNotContain("Kind:");
        assertThat(delivery.rationale()).contains("end to end");
        assertThat(delivery.accepted()).isTrue();

        FlowProposal enabler = proposals.get(1);
        assertThat(enabler.kind()).isEqualTo(FlowProposalKind.ADD);
        assertThat(enabler.handle()).isEqualTo("R2");
        // An enabler is told apart by what it SAYS — it unblocks a requirement rather than
        // delivering checks — not by a kind header.
        assertThat(enabler.after()).contains("Unblocks R2")
            .doesNotContain("ENABLER").doesNotContain("Kind:");
        assertThat(enabler.accepted()).isTrue();

        // A conflict is a question, not a change: it is never pre-accepted and nothing is written
        // for it, because accepting one would be choosing a side on the operator's behalf.
        FlowProposal conflict = proposals.get(2);
        assertThat(conflict.kind()).isEqualTo(FlowProposalKind.CONFLICT);
        assertThat(conflict.accepted()).isFalse();

        // The whole point of proposals: at REVIEW nothing has been written to the backlog at all.
        assertThat(store.listStories(projectId)).isEmpty();

        // --- apply --------------------------------------------------------------------------
        assertThat(service.apply(flowId)).isEmpty();

        GuidedFlow finished = store.getGuidedFlow(awaiting.id());
        assertThat(finished.state()).isEqualTo(GuidedFlowState.APPLIED);
        assertThat(finished.stepLabel()).contains("2 stories added, waiting for you to accept");

        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(2);

        Story written = stories.get(0);
        assertThat(written.kind()).isEqualTo(StoryKind.DELIVERY);
        assertThat(written.title()).isEqualTo("Guest can pay and see it worked");
        // Still the agent's draft — apply is the operator accepting a proposal, not a promotion.
        assertThat(written.state()).isEqualTo(StoryState.DRAFT);
        assertThat(written.narrative()).contains("As a guest");
        // The story claims exactly the criteria the proposal named, by id — not by a second copy of
        // their wording, which is the whole reason a story carries no requirement content.
        assertThat(written.criterionIds())
            .containsExactly(criterionId("R1", 0), criterionId("R1", 1));
        assertThat(written.requirementIds()).containsExactly(requirement("R1").id());

        Story groundwork = stories.get(1);
        assertThat(groundwork.kind()).isEqualTo(StoryKind.ENABLER);
        assertThat(groundwork.state()).isEqualTo(StoryState.DRAFT);
        assertThat(groundwork.criterionIds()).as("an enabler delivers no criterion").isEmpty();
        assertThat(groundwork.requirementIds()).containsExactly(requirement("R2").id());

        // Planning again now sees a smaller gap — which is what stops a second run re-proposing
        // work the first one just wrote.
        assertThat(service.reopen(flowId)).isEmpty();
        assertThat(service.planning().unclaimedCriteria()).isEqualTo(1);
        assertThat(service.planning().backlogStories()).isEqualTo(2);
    }

    // --- 2. skipping ------------------------------------------------------------------------------

    @Test
    void aSkippedQuestionDoesNotBlockAndIsToldToThePlannerAsUnanswered() {
        FakePlanner planner = install(
            """
            {"questions":[{"subject":"Scope","text":"Is refunds in scope now?","kind":"TEXT"}]}""",
            storyProposalsReply());
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();

        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        FlowQuestion question = store.listFlowQuestions(awaiting.id()).get(0);
        assertThat(service.skip(flowId, question.id().toString())).isEmpty();
        assertThat(store.listFlowQuestions(awaiting.id()).get(0).skipped()).isTrue();

        assertThat(service.submitAnswers(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        // Skipping must not silently become a guess: the planning call is told the question went
        // unanswered and that whatever it assumed has to be stated in the rationale.
        assertThat(planner.prompt(1))
            .contains("Is refunds in scope now?")
            .contains("NOT ANSWERED")
            .contains("RATIONALE");
        assertThat(planner.prompt(1)).doesNotContain("ANSWER: ");
    }

    // --- 3. guard rails ---------------------------------------------------------------------------

    @Test
    void planningIsRefusedWithoutAgreedScopeAndAgainOnceEveryCriterionIsClaimed() {
        FakePlanner planner = install("{\"questions\":[]}", storyProposalsReply());

        String flowId = service.planning().flow().id().toString();

        // Nothing agreed at all: planning here would invent the scope, and the stories it invented
        // would look exactly like stories drawn from real requirements.
        // The refusal says what is missing and where to go, without the state machine's own
        // words: neither the constant ACTIVE nor the verb "promote" (UX v3 §4).
        assertThat(service.start(flowId)).startsWith("error:")
            .contains("no agreed requirement has a check on it")
            .contains("Open the Requirements panel and agree")
            .doesNotContain("ACTIVE").doesNotContain("romote");
        assertThat(service.apply(flowId)).startsWith("error:").contains("apply");

        // A DRAFT requirement is scope nobody has agreed to, so it still does not count.
        Brd brd = store.ensureBrd(projectId);
        brd.requirements().add(requirementWith("R1", "Guest checkout", RequirementStatus.DRAFT,
            "a purchase completes with no account"));
        store.saveBrd(brd);
        assertThat(service.start(flowId)).startsWith("error:")
            .contains("no agreed requirement has a check on it");
        assertThat(planner.calls()).as("a refused start must not reach the model").isZero();

        // Promoted, and now plannable.
        requirement("R1").setStatus(RequirementStatus.ACTIVE);
        store.saveBrd(store.ensureBrd(projectId));
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        // A story now claims the only criterion there is. Planning again would either duplicate it
        // or produce nothing, so it is refused — and the refusal says what to do about it.
        BacklogAuthoring.proposeStory(store, projectId, "Guest checkout", "R1:C1", null);
        assertThat(service.reopen(flowId)).isEmpty();
        String refusal = service.start(flowId);
        assertThat(refusal).startsWith("error:")
            .contains("already claimed")
            .contains("cancel a story");
        assertThat(flowOf(flowId).state())
            .as("a refused start leaves the flow ready to run, not running")
            .isEqualTo(GuidedFlowState.DRAFT);
    }

    // --- 4. a story the backlog cannot accept is caught during the review --------------------------

    @Test
    void aStoryNamingACriterionTheBrdDoesNotHaveIsUntickedAndSaysWhy() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Two-factor sign-in",\
            "delivers":"R9:C1","narrative":"As a user I want 2FA",\
            "rationale":"security matters"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest can pay",\
            "delivers":"R1:C1","narrative":"As a guest I want to pay",\
            "rationale":"closes the first gap"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"A vague idea","delivers":"",\
            "narrative":"improve things","rationale":"felt right"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(3);

        // R9 does not exist. Caught while the operator is still reviewing — unticked and explained
        // — rather than at Apply, when a half-written batch and an error string are all they get.
        FlowProposal invented = proposals.get(0);
        assertThat(invented.accepted()).isFalse();
        assertThat(invented.impact()).contains("R9:C1");

        // A delivery story that names no check delivers nothing the BRD asks for. The message
        // says where the capability belongs instead, because that is the actual next move.
        FlowProposal vague = proposals.get(2);
        assertThat(vague.accepted()).isFalse();
        assertThat(vague.impact()).contains("names no check").contains("requirement");

        // Flagging must not become a blanket refusal: the sound one is still one click away.
        assertThat(proposals.get(1).accepted()).isTrue();
        assertThat(reviewing.stepLabel()).contains("2 cannot be written");

        // Applying writes the sound one only; the flagged ones stay out until they are ticked.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(store.listStories(projectId)).extracting(Story::title)
            .containsExactly("Guest can pay");
        assertThat(store.listStories(projectId).get(0).criterionIds())
            .containsExactly(criterionId("R1", 0));
    }

    // --- 4. dependencies -------------------------------------------------------------------------

    /**
     * A plan whose story titles contain commas — which is most real plans — still comes out ordered.
     *
     * <p>The field that says what a story has to be built after used to be one string cut on every
     * comma in it. A story genuinely called "Book records with add, edit, remove, validation, and
     * labelled forms" therefore became five fragments, none of which named anything, so every one of
     * those edges was thrown away and the operator was handed a list of nonsense. The ordering came
     * off exactly the stories that most needed it, and this was the whole point of the feature.
     *
     * <p>So the model is asked for a JSON array now, one whole title per entry. This drives the
     * wizard end to end with a title that has four commas in it and insists the second story really
     * does wait for it.
     */
    @Test
    void aStoryCanWaitForAnotherWhoseTitleIsFullOfCommas() {
        FakePlanner planner = install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY",\
            "title":"Book records with add, edit, remove, validation, and labelled forms",\
            "delivers":"R1:C1","dependsOn":[],\
            "narrative":"the record and everything that edits it",\
            "rationale":"one demonstrable slice"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Receipts",\
            "delivers":"R1:C2",\
            "dependsOn":["Book records with add, edit, remove, validation, and labelled \
            forms"],\
            "narrative":"show the receipt",\
            "rationale":"nothing to receipt until records exist"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // The model is told to send an array, and told why: a joined string cannot be taken apart.
        assertThat(planner.prompt(1))
            .describedAs("the prompt has to ask for the shape the parser can actually read")
            .contains("JSON ARRAY OF STRINGS");

        // The review block carries the whole title on one line — commas and all.
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(1).after())
            .contains("Comes after: Book records with add, edit, remove, validation, "
                + "and labelled forms");

        // Apply reports no trouble at all — the old code reported five phantom dependencies here.
        assertThat(service.apply(flowId)).isEmpty();

        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(2);
        Story records = stories.get(0);
        Story receipts = stories.get(1);
        assertThat(receipts.title()).isEqualTo("Receipts");
        assertThat(receipts.dependsOn())
            .describedAs("the ordering must survive a title with commas in it")
            .containsExactly(records.id());
        assertThat(records.dependsOn()).isEmpty();
    }

    /**
     * A reply that sends the old single string is still read, and read by MEANING rather than by
     * punctuation: the whole string is offered to the names that exist before any comma is
     * considered. That keeps a legacy reply working and keeps a key list working at the same time.
     */
    @Test
    void aOneStringDependencyIsMatchedAgainstRealTitlesBeforeAnyCommaIsUsed() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Sign in, sign out, and stay signed in",\
            "delivers":"R1:C1","narrative":"the session","rationale":"one slice"},\
            {"kind":"ADD","storyKind":"ENABLER","title":"Payment sandbox",\
            "unblocks":"R2","rationale":"needed first"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Receipts","delivers":"R1:C2",\
            "dependsOn":"Sign in, sign out, and stay signed in, Payment sandbox",\
            "narrative":"show the receipt","rationale":"comes last"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // Two dependencies, not five fragments: the title with commas is taken whole because it is
        // a name that exists, and only what is left over is looked at for a comma.
        String receipts = store.listFlowProposals(reviewing.id()).get(2).after();
        assertThat(BacklogPlanning.dependenciesOf(receipts))
            .containsExactly("Sign in, sign out, and stay signed in", "Payment sandbox");

        assertThat(service.apply(flowId)).isEmpty();
        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(3);
        assertThat(stories.get(2).dependsOn())
            .containsExactlyInAnyOrder(stories.get(0).id(), stories.get(1).id());
    }

    /**
     * A dependency naming something that was never proposed still fails — and it fails READABLY.
     *
     * <p>Two things are asserted. The unmatched text is named WHOLE rather than cut into fragments,
     * even though it sits in a one-string value beside a name that does exist, so the reader sees
     * what the planner meant instead of five pieces of it. And the message lists the names that
     * were available, so a near miss shows up as a near miss rather than as a bare string with
     * nothing to compare it to. The one dependency that could be matched is still recorded.
     */
    @Test
    void aDependencyOnNothingIsNamedWholeAndListedBesideTheNamesThatDoExist() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","storyKind":"ENABLER","title":"Payment sandbox",\
            "unblocks":"R2","rationale":"needed first"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Receipts","delivers":"R1:C2",\
            "dependsOn":"A story nobody ever proposed, with a comma in it, Payment sandbox",\
            "narrative":"show the receipt","rationale":"comes last"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);

        String problem = service.apply(flowId);
        assertThat(problem)
            .describedAs("an unmatched dependency is named whole, not cut into fragments")
            .contains("A story nobody ever proposed, with a comma in it")
            .doesNotContain("with a comma in it (named by")
            .contains("exact title")
            .contains("The stories that exist are:")
            .contains("Payment sandbox");

        // Both stories still landed, and the one dependency that could be matched was kept.
        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(2);
        assertThat(stories.get(1).title()).isEqualTo("Receipts");
        assertThat(stories.get(1).dependsOn()).containsExactly(stories.get(0).id());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * Two agreed requirements with three criteria between them, and nothing planned — the state a
     * project is in when the operator has finished intake and promoted what they agree with.
     */
    private void agreedBrd() {
        Brd brd = store.ensureBrd(projectId);
        brd.requirements().add(requirementWith("R1", "Guest checkout", RequirementStatus.ACTIVE,
            "a purchase completes with no account", "a receipt is shown"));
        brd.requirements().add(requirementWith("R2", "Refunds", RequirementStatus.ACTIVE,
            "a request on day 30 is accepted"));
        store.saveBrd(brd);
    }

    private static BrdRequirement requirementWith(String handle, String title,
                                                  RequirementStatus status, String... criteria) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title,
            title + " must work", Priority.HIGH, status, null);
        List<AcceptanceCriterion> list = new ArrayList<>();
        for (String text : criteria) {
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text, null);
            criterion.setStatus(CriterionStatus.ACCEPTED);
            list.add(criterion);
        }
        requirement.setCriteria(list);
        return requirement;
    }

    /** One delivery story, one enabler, and one conflict — the three shapes a plan can produce. */
    private static String storyProposalsReply() {
        return """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest can pay and see it worked",\
            "delivers":"R1:C1,R1:C2","narrative":"As a guest I want to pay without an account",\
            "rationale":"these two criteria are one demonstrable slice end to end"},\
            {"kind":"ADD","storyKind":"ENABLER","title":"Payment provider sandbox",\
            "unblocks":"R2","rationale":"nothing can be verified until the sandbox exists"},\
            {"kind":"CONFLICT","title":"Guest checkout versus saved cards",\
            "delivers":"R1:C1","before":"a purchase completes with no account",\
            "after":"cards are saved against an account",\
            "rationale":"one forecloses the other"}\
            ]}""";
    }

    private BrdRequirement requirement(String handle) {
        for (BrdRequirement r : store.ensureBrd(projectId).requirements()) {
            if (handle.equals(r.handle())) {
                return r;
            }
        }
        throw new AssertionError("no requirement " + handle + " in the BRD");
    }

    private UUID criterionId(String handle, int index) {
        return requirement(handle).criteria().get(index).id();
    }

    /** The persisted flow, re-read rather than remembered — the store is the source of truth. */
    private GuidedFlow flowOf(String flowId) {
        GuidedFlow flow = store.getGuidedFlow(UUID.fromString(flowId));
        assertThat(flow).as("flow " + flowId + " is not in the store").isNotNull();
        return flow;
    }

    private FakePlanner install(String... replies) {
        FakePlanner planner = new FakePlanner(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(planner));
        return planner;
    }

    /**
     * Waits for the planning thread to reach a state, bounded. Polls the persisted flow rather than
     * sleeping a fixed time: a fixed sleep either flakes on a slow machine or wastes the difference
     * on a fast one, and neither says what went wrong.
     */
    private GuidedFlow await(String flowId, GuidedFlowState expected) {
        UUID id = UUID.fromString(flowId);
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        GuidedFlowState seen = null;
        while (System.currentTimeMillis() < deadline) {
            GuidedFlow flow = store.getGuidedFlow(id);
            if (flow != null) {
                seen = flow.state();
                if (seen == expected) {
                    return flow;
                }
                if (seen == GuidedFlowState.FAILED && expected != GuidedFlowState.FAILED) {
                    throw new AssertionError("flow " + flowId + " FAILED while waiting for "
                        + expected + ": " + flow.error());
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + expected, e);
            }
        }
        throw new AssertionError("flow " + flowId + " never reached " + expected
            + " within " + WAIT_MILLIS + "ms — last state was " + seen);
    }

    /**
     * A scripted planner: hands back the next canned reply and keeps every prompt it was given, so a
     * test can assert on what the flow actually told the model rather than only on what came back.
     */
    private static final class FakePlanner implements ConsoleContext.ChatModel {

        private final List<String> replies;
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger call = new AtomicInteger();

        FakePlanner(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public Stream<String> stream(List<Map<String, String>> messages, String modelOverride) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                sb.append(message.get("role")).append(": ")
                    .append(message.get("content")).append('\n');
            }
            prompts.add(sb.toString());
            int index = call.getAndIncrement();
            return Stream.of(index < replies.size() ? replies.get(index)
                : replies.get(replies.size() - 1));
        }

        /** How many times the model was called at all — so "it was not asked" can be asserted. */
        int calls() {
            return prompts.size();
        }

        /** Everything sent on the n-th call, roles included. */
        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
