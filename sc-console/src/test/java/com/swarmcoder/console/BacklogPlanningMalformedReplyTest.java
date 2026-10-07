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
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
 * The planner surviving a reply that will not parse, and a reply that reads fine but proposes
 * nothing — the console-side twin of {@code TestAuthorClientMalformedReplyTest} /
 * {@code ArchitectClientMalformedReplyTest} (commit 642b6d7, 2026-09-03), for the guided-flow
 * planner rather than a workflow role. Before this, {@link BacklogPlanning} sliced the first '{' to
 * the last '}' with no leniency and no retry, and a bad reply — or an honestly empty one, with
 * checks still unclaimed — failed the whole run on the first try.
 */
class BacklogPlanningMalformedReplyTest {

    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private PlanningFlowServiceImpl service;
    private BlobStore blobs;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        blobs = new BlobStore(dir.resolve("blobs"));
        service = new PlanningFlowServiceImpl();
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    @Test
    void anEscapedReplyParsesOnTheFirstTry() {
        FakePlanner planner = install("{\"questions\":[]}", escaped(storyProposalsReply()));
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).as("no retry needed").isEqualTo(2);
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).title()).isEqualTo("Guest can pay and see it worked");
    }

    @Test
    void aGarbageReplyThenAGoodOneSucceedsWithExactlyOneRetry() {
        FakePlanner planner = install("{\"questions\":[]}",
            "not json at all, the model just talked", storyProposalsReply());
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).as("one question round, one bad reply, one retry").isEqualTo(3);
        assertThat(planner.prompt(2))
            .as("the retry conversation carries the bad reply and the parser's own complaint")
            .contains("not json at all, the model just talked")
            .contains("That was not valid JSON")
            .contains("Reply with only the JSON object");
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
    }

    @Test
    void aSecondGarbageReplyFailsHonestlyWithABlobRef() throws Exception {
        FakePlanner planner = install("{\"questions\":[]}", "still not json", "still not json either");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow failed = await(flowId, GuidedFlowState.FAILED);

        assertThat(planner.calls()).as("one question round, one bad reply, one retry, then stop")
            .isEqualTo(3);
        assertThat(failed.error())
            .contains("the planner's reply was not valid JSON (twice)")
            .contains("kept as blob ");
        String ref = failed.error().substring(failed.error().indexOf("kept as blob ") + 13).strip();
        assertThat(new String(blobs.getBlob(ref), StandardCharsets.UTF_8))
            .isEqualTo("still not json either");
    }

    @Test
    void anEmptyButValidReplyIsAskedAgainOnceWhileChecksAreUnclaimed() {
        FakePlanner planner = install("{\"questions\":[]}", "{\"proposals\":[]}", storyProposalsReply());
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).as("questions, an empty plan, then the re-ask").isEqualTo(3);
        assertThat(planner.prompt(2))
            .contains("Your stories claim 0 of the 2 agreed checks")
            .contains("R1:C1")
            .contains("R1:C2")
            .contains("Add stories that claim them, or add the claims to the stories you proposed");
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).title()).isEqualTo("Guest can pay and see it worked");
    }

    @Test
    void anEmptyReplyIsNotAskedAgainOnceEveryCheckIsAlreadyClaimed() {
        // Bypasses PlanningFlowServiceImpl.start, which already refuses to launch a run once every
        // check is claimed (GuidedFlowPlanningTest pins that refusal). This exercises the same
        // defensive case inside BacklogPlanning.run itself, for a story written while a run was
        // already in flight — the one way the model can still be asked with nothing left to claim.
        FakePlanner planner = install("{\"questions\":[]}", "{\"proposals\":[]}");
        agreedBrd();
        BacklogAuthoring.proposeStory(store, projectId, "Guest checkout", "R1:C1,R1:C2", null);

        GuidedFlow flow = GuidedFlows.ensurePlanning(store, projectId);
        BacklogPlanning.launch(ConsoleContext.get(), flow);

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline && planner.calls() < 2) {
            sleep();
        }
        sleep();   // give a stray third call a chance to land before asserting it never did

        assertThat(planner.calls()).as("no re-ask once nothing is left to claim").isEqualTo(2);
        GuidedFlow after = store.getGuidedFlow(flow.id());
        assertThat(after.state()).as("not a failure").isEqualTo(GuidedFlowState.DRAFT);
        assertThat(after.stepLabel())
            .contains("Every check is already claimed by a story, so there was nothing to plan");
    }

    /**
     * The exact bug the harness hit: one story, an enabler, claims none of the agreed checks. The
     * old code only re-asked when the reply proposed literally zero stories, so this passed through
     * untouched and the plan covered nothing. The new re-ask fires on the real condition — a check
     * with nobody to claim it — and the second reply's claiming story is MERGED with the enabler
     * rather than replacing it, so the groundwork the first reply got right survives.
     */
    @Test
    void aStoryThatClaimsNoChecksIsAskedAgainAndTheSecondReplyIsMergedWithTheFirst() {
        FakePlanner planner = install("{\"questions\":[]}",
            """
            {"proposals":[\
            {"kind":"ADD","storyKind":"ENABLER","title":"Establish persistent storage",\
            "unblocks":"R1","rationale":"groundwork before anything can be verified"}\
            ]}""",
            """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Books persist across sessions",\
            "delivers":"R1:C1,R1:C2","dependsOn":["Establish persistent storage"],\
            "narrative":"As a reader I want my books there tomorrow",\
            "rationale":"the agreed checks, end to end"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).as("questions, an enabler that claims nothing, then the re-ask")
            .isEqualTo(3);
        // The re-ask names what is unclaimed AND calls out the enabler by name: it claims no check
        // and, at the moment of the re-ask, nothing depends on it either.
        assertThat(planner.prompt(2))
            .contains("Your stories claim 0 of the 2 agreed checks")
            .contains("R1:C1").contains("R1:C2")
            .contains("'Establish persistent storage' claims no check and nothing builds on it");

        // Both stories land: the enabler is not discarded just because it claimed nothing by itself.
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).extracting(FlowProposal::title)
            .containsExactlyInAnyOrder("Establish persistent storage", "Books persist across sessions");
        assertThat(reviewing.stepLabel()).as("nothing is left unclaimed").doesNotContain("unclaimed");
    }

    /**
     * When the second reply still leaves a check unclaimed, the run is not failed a second time —
     * it proceeds with whatever was claimed, and says in plain words what is still missing. A
     * partial plan the operator can see and extend beats no plan at all.
     */
    @Test
    void aSecondReplyThatStillLeavesACheckUnclaimedProceedsAndNamesTheShortfall() {
        FakePlanner planner = install("{\"questions\":[]}", "{\"proposals\":[]}",
            """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest can pay",\
            "delivers":"R1:C1","narrative":"As a guest I want to pay",\
            "rationale":"one demonstrable slice"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).as("one question round, an empty plan, one re-ask, then stop")
            .isEqualTo(3);
        assertThat(reviewing.stepLabel())
            .describedAs("not a failure — a partial plan the operator can see and extend")
            .contains("the planner left 1 agreed check(s) unclaimed").contains("R1:C2");
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).title()).isEqualTo("Guest can pay");
    }

    /**
     * An enabler that something DOES depend on is never named a defect, even while the run is
     * asking again for a different, unrelated unclaimed check.
     */
    @Test
    void anEnablerWithADependantIsNotNamedAsADefectInTheReask() {
        FakePlanner planner = install("{\"questions\":[]}",
            """
            {"proposals":[\
            {"kind":"ADD","storyKind":"ENABLER","title":"Payment sandbox",\
            "unblocks":"R1","rationale":"groundwork"},\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest can pay",\
            "delivers":"R1:C1","dependsOn":["Payment sandbox"],\
            "narrative":"As a guest I want to pay","rationale":"first slice"}\
            ]}""",
            """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest sees the receipt",\
            "delivers":"R1:C2","narrative":"As a guest I want a receipt",\
            "rationale":"closes the gap"}\
            ]}""");
        agreedBrd();

        String flowId = service.planning().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(planner.calls()).isEqualTo(3);
        assertThat(planner.prompt(2))
            .contains("R1:C2")
            .doesNotContain("claims no check and nothing builds on it");

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).extracting(FlowProposal::title)
            .containsExactlyInAnyOrder("Payment sandbox", "Guest can pay", "Guest sees the receipt");
        assertThat(reviewing.stepLabel()).doesNotContain("unclaimed");
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private void agreedBrd() {
        Brd brd = store.ensureBrd(projectId);
        brd.requirements().add(requirementWith("R1", "Guest checkout", RequirementStatus.ACTIVE,
            "a purchase completes with no account", "a receipt is shown"));
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

    private static String storyProposalsReply() {
        return """
            {"proposals":[\
            {"kind":"ADD","storyKind":"DELIVERY","title":"Guest can pay and see it worked",\
            "delivers":"R1:C1,R1:C2","narrative":"As a guest I want to pay without an account",\
            "rationale":"these two criteria are one demonstrable slice end to end"}\
            ]}""";
    }

    /** The same object, with every quote backslash-escaped and no outer quotes — the shape a paid
     * endpoint sent live (LlmJsonTest#unwrapsAnEscapedBodyWithNoOuterQuotes). */
    private static String escaped(String json) {
        return json.replace("\"", "\\\"");
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private FakePlanner install(String... replies) {
        FakePlanner planner = new FakePlanner(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), blobs,
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
            sleep();
        }
        throw new AssertionError("flow " + flowId + " never reached " + expected
            + " within " + WAIT_MILLIS + "ms — last state was " + seen);
    }

    /** A scripted planner: hands back the next canned reply and keeps every prompt it was given. */
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
