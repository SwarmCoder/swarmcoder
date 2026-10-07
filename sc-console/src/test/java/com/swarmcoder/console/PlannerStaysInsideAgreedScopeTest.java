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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
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
 * <b>Nothing is built from a requirement the operator has not agreed.</b>
 *
 * <p>Agreeing a requirement is the gate the product describes as the one that matters most. Until
 * 2026-08-31 it was a line of PROMPT and not a line of code: the planner was shown the whole
 * requirements document with a sentence saying that only agreed requirements count, and the code
 * that turned a reference like {@code R3:C2} into a criterion never looked at the requirement's
 * status. Whether a plan stayed inside what the operator agreed was the model's choice.
 *
 * <p>The end-to-end harness ({@code EndToEndLoopTest}) measured what that choice was worth, twice,
 * against the Bookshelf demo project: <b>six stories claiming sixteen checks, of which exactly one
 * was agreed and fifteen belonged to requirements still in draft</b> — R1, R2, R3, R4, R6 and R7.
 * The plan in {@link #sixStoriesSixteenChecks()} is that plan, with the same shape and the same
 * numbers.
 *
 * <p>What is pinned here:
 *
 * <ul>
 *   <li>The briefing shows the agreed requirements with their checks, and the unagreed ones by
 *       NAME ONLY — no checks, so there is no reference to copy — under a heading that says they
 *       are out of scope.</li>
 *   <li>A plan reaching outside the agreed scope is REJECTED and the planner is asked again, and
 *       the second request TELLS IT what was wrong. It used to be the identical prompt.</li>
 *   <li>If the second attempt is still outside scope, every story that reaches outside is unticked
 *       with a plain reason, so the default is "no".</li>
 *   <li>Ticking one anyway still writes nothing: the backlog itself refuses a check whose
 *       requirement is not agreed.</li>
 *   <li>A plan confined to the agreed requirement lands, untouched.</li>
 * </ul>
 */
class PlannerStaysInsideAgreedScopeTest {

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
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- 1. the briefing --------------------------------------------------------------------

    /**
     * The planner is shown the checks of the agreed requirement and NOT the checks of the others.
     *
     * <p>Showing everything and asking for restraint is exactly what failed. The unagreed
     * requirements are still named, because a planner that cannot see them plans work that ignores
     * an obvious dependency — but a name is not addressable and a check number is.
     */
    @Test
    void theBriefShowsAgreedChecksAndNamesTheRestWithoutTheirChecks() {
        bookshelfBrd();

        String brief = BacklogPlanning.brief(store, projectId);

        // The one agreed requirement, in full, with its check addressable.
        assertThat(brief).contains("R5:C1").contains("my books are still there tomorrow");

        // Every other requirement is named — so dependencies can be reasoned about at all …
        assertThat(brief).contains("R1").contains("Keep a list of my books");
        assertThat(brief).contains("R7").contains("Reject data that makes no sense");

        // … and NOT one of their checks is addressable anywhere in the briefing.
        assertThat(brief)
            .describedAs("an unagreed check that appears in the brief is a check the planner "
                + "can copy into a story")
            .doesNotContain("R1:C1").doesNotContain("R2:C1").doesNotContain("R3:C1")
            .doesNotContain("R4:C1").doesNotContain("R6:C1").doesNotContain("R7:C1");
        assertThat(brief).doesNotContain("the year a book was published is recorded");

        // And it says so in words, so the model is not left to infer it from an absence.
        assertThat(brief.toLowerCase()).contains("not agreed");
    }

    // --- 2. the plan the harness actually got -----------------------------------------------

    /**
     * The measured failure: six stories, sixteen checks, one agreed. Rejected, and the planner is
     * told what it did wrong before it is asked again.
     */
    @Test
    void theSixStoryPlanIsRejectedAndTheSecondRequestSaysWhy() {
        // The planner sends the same over-wide plan twice — a model that does not take the
        // correction. The operator must still be safe.
        FakePlanner planner = install("{\"questions\":[]}", sixStoriesSixteenChecks(),
            sixStoriesSixteenChecks());
        bookshelfBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // Asked twice: the first plan was refused rather than shown.
        assertThat(planner.calls())
            .describedAs("a rejected plan must be re-requested, not accepted")
            .isEqualTo(3);

        // …and the second request is INFORMED. It used to be the identical prompt, which is why a
        // regeneration never changed anything.
        String again = planner.prompt(2);
        assertThat(again)
            .describedAs("the regeneration must name what was wrong with the previous plan")
            .contains("R1:C1")
            .contains("R7:C2");
        assertThat(again.toLowerCase()).contains("not agreed");

        // Every story that reaches outside the agreed scope is unticked, with a reason in words.
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(6);
        assertThat(proposals).allSatisfy(p -> assertThat(p.accepted())
            .describedAs("'" + p.title() + "' claims a check nobody agreed")
            .isFalse());
        assertThat(proposals.get(0).impact()).contains("R1").contains("not agreed");

        // Nothing is appliable, so nothing reaches the backlog.
        assertThat(service.apply(flowId)).startsWith("error:");
        assertThat(store.listStories(projectId)).isEmpty();
    }

    /**
     * The corrected second attempt is the one the operator reviews, and it lands.
     *
     * <p>This is the whole point of telling the model what was wrong: one more call turns a plan
     * that could not be applied into one that can.
     */
    @Test
    void anInformedSecondAttemptThatStaysInsideScopeIsTheOneTheOperatorReviews() {
        install("{\"questions\":[]}", sixStoriesSixteenChecks(), confinedToTheAgreedRequirement());
        bookshelfBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).handle()).isEqualTo("R5:C1");
        assertThat(proposals.get(0).accepted()).isTrue();

        assertThat(service.apply(flowId)).isEmpty();
        List<Story> stories = store.listStories(projectId);
        assertThat(stories).hasSize(1);
        assertThat(stories.get(0).criterionIds()).containsExactly(criterionId("R5", 0));
    }

    /** A plan that never reached outside is asked for once and lands untouched. */
    @Test
    void aPlanConfinedToTheAgreedRequirementIsAcceptedFirstTime() {
        FakePlanner planner = install("{\"questions\":[]}", confinedToTheAgreedRequirement());
        bookshelfBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls())
            .describedAs("a clean plan must not cost a second call")
            .isEqualTo(2);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);

        assertThat(service.apply(flowId)).isEmpty();
        assertThat(store.listStories(projectId)).hasSize(1);
        assertThat(store.listStories(projectId).get(0).criterionIds())
            .containsExactly(criterionId("R5", 0));
    }

    // --- 3. the gate under everything --------------------------------------------------------

    /**
     * The backlog itself refuses the check, so ticking an unticked proposal is not a way round.
     *
     * <p>This is the line of code the gate was missing. Everything above is the wizard being
     * helpful about it; this is what makes it a rule.
     */
    @Test
    void theBacklogRefusesAStoryOnARequirementNobodyAgreed() {
        bookshelfBrd();

        String refused = BacklogAuthoring.proposeStory(store, projectId,
            "Store a book record", "R1:C1,R1:C2", "as a reader I want my books kept");
        assertThat(refused).startsWith("error:").contains("R1").contains("not agreed");
        assertThat(store.listStories(projectId)).isEmpty();

        // A plan half in and half out is still out: one unagreed check refuses the whole story,
        // because writing the agreed half would silently deliver less than the operator read.
        assertThat(BacklogAuthoring.proposeStory(store, projectId, "Half and half",
            "R5:C1,R1:C1", null)).startsWith("error:").contains("R1");
        assertThat(store.listStories(projectId)).isEmpty();

        // An enabler names requirement handles rather than checks, and the same rule holds.
        assertThat(BacklogAuthoring.proposeEnabler(store, projectId, "Set up storage", "R2",
            "nothing persists yet")).startsWith("error:").contains("R2").contains("not agreed");
        assertThat(store.listStories(projectId)).isEmpty();

        // The agreed one is untouched — this is a gate, not a blanket refusal.
        assertThat(BacklogAuthoring.proposeStory(store, projectId, "Books survive a restart",
            "R5:C1", null)).startsWith("S1");
        assertThat(store.listStories(projectId)).hasSize(1);
    }

    // --- fixtures ------------------------------------------------------------------------------

    /**
     * The Bookshelf demo project's requirement graph as the analyst drafts it, with the operator
     * having agreed exactly one — the smallest, by number of checks.
     *
     * <p>Seven requirements, sixteen checks. R5 "Remember everything between visits" carries one
     * check and is ACTIVE; R1, R2, R3, R4, R6 and R7 carry fifteen between them and are DRAFT.
     * Those are the harness's own numbers, and those are the handles it named.
     */
    private void bookshelfBrd() {
        Brd brd = store.ensureBrd(projectId);
        brd.requirements().add(draft("R1", "Keep a list of my books",
            "a book records its title and author",
            "the year a book was published is recorded",
            "a book says whether it is unstarted, being read, or finished"));
        brd.requirements().add(draft("R2", "Add, change and remove a book",
            "a new book can be typed in",
            "an existing book can be corrected",
            "a book can be taken off the list"));
        brd.requirements().add(draft("R3", "Find a book again",
            "books can be searched by title",
            "books can be searched by author",
            "the list can be narrowed to what is being read"));
        brd.requirements().add(draft("R4", "Rate a book I have read",
            "a finished book can be given a rating",
            "the rating is shown beside the book"));
        brd.requirements().add(agreed("R5", "Remember everything between visits",
            "my books are still there tomorrow"));
        brd.requirements().add(draft("R6", "Proper labelled fields",
            "every field carries a visible label",
            "no field uses placeholder text instead of a label"));
        brd.requirements().add(draft("R7", "Reject data that makes no sense",
            "a made-up publication year is refused with a message",
            "a rating outside the scale is refused with a message"));
        store.saveBrd(brd);
    }

    /**
     * The plan the harness got, twice: six stories, sixteen checks, one of them agreed.
     *
     * <p>The first story is the interesting one — it names the agreed check AND three that are not,
     * which is how a plan smuggles unagreed work past a reviewer skimming for the handle they
     * recognise.
     */
    private static String sixStoriesSixteenChecks() {
        return """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Store a book record that survives a restart",\
            "delivers":"R1:C1,R1:C2,R1:C3,R5:C1","dependsOn":[],\
            "narrative":"the book record and where it is kept",\
            "rationale":"nothing can be listed until a book can be stored"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Add, edit and remove a book",\
            "delivers":"R2:C1,R2:C2,R2:C3",\
            "dependsOn":["Store a book record that survives a restart"],\
            "narrative":"the three edits over one record","rationale":"one demonstrable slice"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Search by title or author",\
            "delivers":"R3:C1,R3:C2,R3:C3",\
            "dependsOn":["Store a book record that survives a restart"],\
            "narrative":"find a book again","rationale":"the list grows"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Rate a finished book",\
            "delivers":"R4:C1,R4:C2",\
            "dependsOn":["Store a book record that survives a restart"],\
            "narrative":"a rating on the record","rationale":"reading is not done until judged"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Labelled fields throughout",\
            "delivers":"R6:C1,R6:C2","dependsOn":[],\
            "narrative":"real labels on every field","rationale":"it must read as one app"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Reject nonsense input",\
            "delivers":"R7:C1,R7:C2","dependsOn":[],\
            "narrative":"validation with messages","rationale":"bad data must not get in"}\
            ]}""";
    }

    /** The plan the operator actually agreed to: one story, the one agreed check. */
    private static String confinedToTheAgreedRequirement() {
        return """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Books survive closing the browser",\
            "delivers":"R5:C1","dependsOn":[],\
            "narrative":"as a reader I want my books to still be there tomorrow",\
            "rationale":"the one agreed check, end to end"}\
            ]}""";
    }

    private static BrdRequirement draft(String handle, String title, String... criteria) {
        return requirementWith(handle, title, RequirementStatus.DRAFT, CriterionStatus.PROPOSED,
            criteria);
    }

    private static BrdRequirement agreed(String handle, String title, String... criteria) {
        return requirementWith(handle, title, RequirementStatus.ACTIVE, CriterionStatus.ACCEPTED,
            criteria);
    }

    private static BrdRequirement requirementWith(String handle, String title,
                                                  RequirementStatus status,
                                                  CriterionStatus criterionStatus,
                                                  String... criteria) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title,
            title + " — from the Bookshelf document", Priority.HIGH, status, null);
        List<AcceptanceCriterion> list = new ArrayList<>();
        for (String text : criteria) {
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text,
                "BookshelfAcceptanceTest#" + Integer.toHexString(text.hashCode()));
            criterion.setStatus(criterionStatus);
            list.add(criterion);
        }
        requirement.setCriteria(list);
        return requirement;
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

    private FakePlanner install(String... replies) {
        FakePlanner planner = new FakePlanner(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(planner));
        return planner;
    }

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

    /** A scripted planner that keeps every prompt, so what the flow TOLD the model is assertable. */
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

        int calls() {
            return prompts.size();
        }

        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
