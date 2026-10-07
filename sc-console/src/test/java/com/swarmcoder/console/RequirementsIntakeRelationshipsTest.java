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

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
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
 * Relationships through the intake path, with a scripted analyst instead of a live model — the same
 * fixture shape as {@link GuidedFlowIntakeTest}, which this deliberately mirrors.
 *
 * <p>What is under test is the two things that made a BRD's graph degenerate into a grid of
 * unconnected boxes: that a relationship the analyst proposes survives the round trip through the
 * proposal block and becomes a real {@link BrdEdge} on apply, and that one naming a requirement
 * that does not exist is REPORTED rather than quietly discarded. The second matters as much as the
 * first: an edge that vanishes leaves a graph that looks merely sparse, and nothing downstream will
 * ever ask why the dependency is not there.
 */
class RequirementsIntakeRelationshipsTest {

    /** Long enough to absorb a slow EclipseStore flush, short enough to fail rather than hang. */
    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private GuidedFlowServiceImpl service;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        service = new GuidedFlowServiceImpl();
    }

    @AfterEach
    void closeStore() throws Exception {
        ConsoleContext.set(null);
        if (store != null) {
            store.close();
        }
    }

    // --- 1. a relationship survives the block and becomes an edge -----------------------------------

    @Test
    void aProposedRelationshipBecomesAnEdgeAndADanglingHandleIsDroppedAtIntake() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Refund window",\
            "rationale":"the spec gives customers 30 days",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL","category":"Refunds",\
            "text":"A customer can request a refund within 30 days of purchase.",\
            "criteria":["a request on day 30 is accepted"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"R1"},\
            {"relation":"REFINES","target":"R404"}]}\
            ]}""");
        // The thing the new requirement depends on has to already exist — that is the rule the
        // analyst is given, and the one case where an edge can be written with no ambiguity at all.
        assertThat(BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A guest can complete a purchase without an account", "HIGH", null, null, null, null))
            .startsWith("R1");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. Refunds are accepted for 30 days.");

        // R404 was never proposed in this reply and never existed in the BRD, so it is dropped the
        // moment the reply is read — before it ever reaches apply. The good relationship survives:
        // one broken handle must not cost the operator the rest of what the analyst said.
        List<FlowProposal> proposals = store.listFlowProposals(UUID.fromString(flowId));
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).after())
            .contains("DependsOn: R1")
            .doesNotContain("Refines: R404");

        // The step label the operator reads at REVIEW says a relationship was dropped.
        assertThat(flowOf(flowId).stepLabel())
            .contains("1 relationship referred to a handle that does not exist and was dropped");

        // Apply now has nothing dangling left to refuse — it succeeds outright.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(flowOf(flowId).state()).isEqualTo(GuidedFlowState.APPLIED);

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).extracting(BrdRequirement::handle)
            .containsExactly("R1", "R2");
        assertThat(brd.edges()).hasSize(1);
        BrdEdge edge = brd.edges().get(0);
        assertThat(edge.relation()).isEqualTo(RequirementRelation.DEPENDS_ON);
        assertThat(handleOf(brd, edge.from())).isEqualTo("R2");
        assertThat(handleOf(brd, edge.to())).isEqualTo("R1");
    }

    // --- 2. edges are written after every requirement exists ----------------------------------------

    @Test
    void anEdgeToARequirementCreatedLaterInTheSameBatchStillResolves() {
        // The first proposal names a handle that does not exist yet and will not until the SECOND
        // proposal is applied. Written inline, this edge is refused purely because of the order the
        // model happened to emit its proposals in; deferred to a second pass, it resolves.
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"R2"}]},\
            {"kind":"ADD","handle":"","title":"Payment capture",\
            "rationale":"the spec says the card is charged at checkout",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Payments",\
            "text":"The customer's card is charged when the order is placed.",\
            "criteria":["a placed order has a captured payment"]}\
            ]}""");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. The card is charged when the order is placed.");
        assertThat(service.apply(flowId)).isEmpty();

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).extracting(BrdRequirement::handle)
            .containsExactly("R1", "R2");
        assertThat(brd.edges()).hasSize(1);
        BrdEdge edge = brd.edges().get(0);
        assertThat(edge.relation()).isEqualTo(RequirementRelation.DEPENDS_ON);
        assertThat(handleOf(brd, edge.from())).as("the proposal that declared the relationship")
            .isEqualTo("R1");
        assertThat(handleOf(brd, edge.to())).as("the requirement created after it")
            .isEqualTo("R2");
    }

    @Test
    void aRelationshipBetweenTWONEWRequirementsResolvesThroughTheirRefs() {
        // The case that matters most and was impossible until refs existed: a FIRST analysis, where
        // the BRD is empty and every requirement is new, so every relationship worth stating is
        // between two things in this same batch. The analyst was told to omit those, so it did —
        // and every first analysis produced a graph with no edges at all.
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"N2"}]},\
            {"kind":"ADD","ref":"N2","title":"Payment capture",\
            "rationale":"the spec says the card is charged at checkout",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Payments",\
            "text":"The customer's card is charged when the order is placed.",\
            "criteria":["a placed order has a captured payment"]}\
            ]}""");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. The card is charged when the order is placed.");
        assertThat(service.apply(flowId)).isEmpty();

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).extracting(BrdRequirement::handle)
            .containsExactly("R1", "R2");
        assertThat(brd.edges()).as("a first analysis must be able to state its own structure")
            .hasSize(1);
        BrdEdge edge = brd.edges().get(0);
        assertThat(edge.relation()).isEqualTo(RequirementRelation.DEPENDS_ON);
        // N1 and N2 are the model's temporary names; the edge must land on the real handles.
        assertThat(handleOf(brd, edge.from())).isEqualTo("R1");
        assertThat(handleOf(brd, edge.to())).isEqualTo("R2");
    }

    // --- 3. the analyst is actually asked for relationships -----------------------------------------

    @Test
    void theDraftingCallExplainsWhatADependencyMeansAndForbidsGuessingHandles() {
        FakeAnalyst analyst = install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Refund window","rationale":"30 days",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL","category":"Refunds",\
            "text":"A customer can request a refund within 30 days.",\
            "criteria":["a request on day 30 is accepted"]}\
            ]}""");

        analyse("spec.md", "Refunds are accepted for 30 days.");

        String drafting = analyst.prompt(1);
        assertThat(drafting).contains("relationships");
        // A guessed dependency graph is worse than none, because it gets used for sequencing —
        // the prompt has to say that, not merely offer the field.
        assertThat(drafting).contains("CANNOT BE DELIVERED");
        assertThat(drafting).contains("ALREADY EXISTS");
    }

    // --- 4. a dangling relationship is dropped at intake, not reported at apply ---------------------

    @Test
    void aDanglingRelationshipAmongEighteenProposalsIsDroppedAndTheOtherSeventeenApply() {
        // One relationship among many, on one proposal in the middle of the batch, names a handle
        // ("N99") that nothing in this reply proposed and the BRD never held. Harness run 20
        // (2026-09-03): exactly this shape cost the operator the entire batch, technical document
        // and all, because the one bad edge reached apply and apply refuses the whole call.
        install("{\"questions\":[]}", eighteenProposalsWithOneDanglingRelationship());

        String flowId = analyse("spec.md", "Eighteen things the system must do.");

        List<FlowProposal> proposals = store.listFlowProposals(UUID.fromString(flowId));
        assertThat(proposals).hasSize(18);
        FlowProposal five = proposals.stream().filter(p -> "N5".equals(p.handle())).findFirst()
            .orElseThrow(() -> new AssertionError("no proposal N5"));
        assertThat(five.after()).doesNotContain("Gates: N99");

        assertThat(flowOf(flowId).stepLabel())
            .contains("1 relationship referred to a handle that does not exist and was dropped");

        // Nothing left dangling — apply succeeds outright and every proposal lands.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(flowOf(flowId).state()).isEqualTo(GuidedFlowState.APPLIED);

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).hasSize(18);
        // The dropped relationship was the batch's only one — nothing was left to become an edge.
        assertThat(brd.edges()).isEmpty();
    }

    @Test
    void whenEveryRelationshipResolvesTheBatchIsLeftUnchanged() {
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"N2"}]},\
            {"kind":"ADD","ref":"N2","title":"Payment capture",\
            "rationale":"the spec says the card is charged at checkout",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Payments",\
            "text":"The customer's card is charged when the order is placed.",\
            "criteria":["a placed order has a captured payment"]}\
            ]}""");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. The card is charged when the order is placed.");

        List<FlowProposal> proposals = store.listFlowProposals(UUID.fromString(flowId));
        FlowProposal checkout = proposals.stream().filter(p -> "N1".equals(p.handle())).findFirst()
            .orElseThrow(() -> new AssertionError("no proposal N1"));
        // Nothing was dangling, so the block the operator reviews is exactly what the analyst wrote.
        assertThat(checkout.after()).contains("DependsOn: N2");
        assertThat(flowOf(flowId).stepLabel()).doesNotContain("referred to a handle that does not exist");

        assertThat(service.apply(flowId)).isEmpty();
        Brd brd = store.getBrd(projectId);
        assertThat(brd.edges()).hasSize(1);
    }

    @Test
    void aRelationshipToAnExistingRequirementHandleIsKept() {
        assertThat(BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A guest can complete a purchase without an account", "HIGH", null, null, null, null))
            .startsWith("R1");

        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Refund window",\
            "rationale":"the spec gives customers 30 days",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL","category":"Refunds",\
            "text":"A customer can request a refund within 30 days of purchase.",\
            "criteria":["a request on day 30 is accepted"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"R1"}]}\
            ]}""");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. Refunds are accepted for 30 days.");

        List<FlowProposal> proposals = store.listFlowProposals(UUID.fromString(flowId));
        assertThat(proposals).hasSize(1);
        // R1 already exists in the BRD, so the relationship is a known-good endpoint and survives.
        assertThat(proposals.get(0).after()).contains("DependsOn: R1");
        assertThat(flowOf(flowId).stepLabel()).doesNotContain("referred to a handle that does not exist");

        assertThat(service.apply(flowId)).isEmpty();
        Brd brd = store.getBrd(projectId);
        assertThat(brd.edges()).hasSize(1);
        BrdEdge edge = brd.edges().get(0);
        assertThat(edge.relation()).isEqualTo(RequirementRelation.DEPENDS_ON);
        assertThat(handleOf(brd, edge.to())).isEqualTo("R1");
    }

    // --- 5. an edge to a proposal nobody accepted is skipped at apply, not fatal --------------------

    @Test
    void anEdgeToAProposalThatWasNeverAcceptedIsSkippedNotFatal() {
        // Harness run 47 (2026-09-27): N15's ref was real when the reply was read — some proposal in
        // this same batch declared it — so dropDanglingRelationships (intake-time) leaves the
        // relationship alone. But the operator (or an earlier automatic un-tick) can leave that
        // proposal unaccepted, so by the time apply runs, N15 was never actually written. The edge
        // naming it must not fail the whole apply.
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"],\
            "relationships":[{"relation":"DEPENDS_ON","target":"N15"}]},\
            {"kind":"ADD","ref":"N15","title":"Payment capture",\
            "rationale":"the spec says the card is charged at checkout",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Payments",\
            "text":"The customer's card is charged when the order is placed.",\
            "criteria":["a placed order has a captured payment"]}\
            ]}""");

        String flowId = analyse("spec.md",
            "A guest must be able to pay. The card is charged when the order is placed.");

        // The operator accepts only the first proposal — N15 is left unticked, exactly as an
        // operator choosing not to accept one half of a batch would leave it.
        List<FlowProposal> proposals = new ArrayList<>(
            store.listFlowProposals(UUID.fromString(flowId)));
        FlowProposal paymentCapture = proposals.stream().filter(p -> "N15".equals(p.handle()))
            .findFirst().orElseThrow(() -> new AssertionError("no proposal N15"));
        paymentCapture.setAccepted(false);
        store.saveFlowProposals(UUID.fromString(flowId), proposals);

        // Apply must not fail as a whole: the accepted requirement lands, and the edge to the
        // proposal nobody accepted is skipped — reported in plain words — rather than refused.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(flowOf(flowId).state()).isEqualTo(GuidedFlowState.APPLIED);
        assertThat(flowOf(flowId).stepLabel())
            .contains("skipped: R1 depends_on N15 — N15 does not exist");

        Brd brd = store.getBrd(projectId);
        assertThat(brd.requirements()).extracting(BrdRequirement::handle).containsExactly("R1");
        assertThat(brd.edges()).isEmpty();
    }

    /** Eighteen ADD proposals; the fifth carries one relationship to a handle that does not exist. */
    private static String eighteenProposalsWithOneDanglingRelationship() {
        StringBuilder sb = new StringBuilder("{\"proposals\":[");
        for (int i = 1; i <= 18; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"kind\":\"ADD\",\"ref\":\"N").append(i).append("\",")
                .append("\"title\":\"Requirement ").append(i).append("\",")
                .append("\"rationale\":\"the document says so\",")
                .append("\"priority\":\"MEDIUM\",\"requirementKind\":\"FUNCTIONAL\",")
                .append("\"category\":\"General\",")
                .append("\"text\":\"The system does thing number ").append(i).append(".\",")
                .append("\"criteria\":[\"thing ").append(i).append(" happens\"]");
            if (i == 5) {
                sb.append(",\"relationships\":[{\"relation\":\"GATES\",\"target\":\"N99\"}]");
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Uploads one document, runs the analysis and returns the flow id parked at REVIEW. */
    private String analyse(String filename, String text) {
        SourceDocument document = upload(filename, text);
        String flowId = service.intake().flow().id().toString();
        assertThat(service.addDocument(flowId, document.id().toString(), null)).isEmpty();
        assertThat(service.start(flowId)).isEmpty();
        await(flowId, GuidedFlowState.REVIEW);
        return flowId;
    }

    private static String handleOf(Brd brd, UUID requirementId) {
        for (BrdRequirement r : brd.requirements()) {
            if (r.id().equals(requirementId)) {
                return r.handle();
            }
        }
        throw new AssertionError("no requirement " + requirementId + " in the BRD");
    }

    private GuidedFlow flowOf(String flowId) {
        GuidedFlow flow = store.getGuidedFlow(UUID.fromString(flowId));
        assertThat(flow).as("flow " + flowId + " is not in the store").isNotNull();
        return flow;
    }

    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(analyst));
        return analyst;
    }

    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).isFalse();
        return result.document();
    }

    /** Bounded poll on the persisted flow — the store, not the signal, is the source of truth. */
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

    /** Hands back the next canned reply and keeps every prompt it was given. */
    private static final class FakeAnalyst implements ConsoleContext.ChatModel {

        private final List<String> replies;
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger call = new AtomicInteger();

        FakeAnalyst(String... replies) {
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

        /** Everything sent on the n-th call, roles included. */
        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
