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
import com.swarmcoder.domain.FlowProposalKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analyst against a requirement graph too big to send (DEVELOPER_CORRECTIONS.md §21).
 *
 * <p>Everything the build path does is bounded by the size of one story. The analyst path was
 * bounded by nothing: it rendered the whole BRD into every call, at three call sites, and grew until
 * the model server refused. This measures what that costs at 100, 1,000 and 5,000 requirements, and
 * pins the four behaviours that have to survive being given only part of the document.
 *
 * <p>No model is involved: a scripted analyst hands back canned replies and keeps every prompt it
 * was given, so what is asserted is what the product actually sent.
 */
class AnalystAtScaleTest {

    private static final long WAIT_MILLIS = 20_000;

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

    // --- 1. what it costs, measured -----------------------------------------------------------

    /**
     * The number the whole change exists for: how many characters of requirement graph one analyst
     * call carries, at three sizes, before and after.
     */
    @Test
    void theGraphSentToTheAnalystStopsGrowingWithTheProject() {
        StringBuilder table = new StringBuilder(String.format(Locale.ROOT,
            "%nrequirement graph sent to the analyst, in characters:%n"
            + "  %-12s %14s %14s %10s%n", "requirements", "before", "after", "shown"));
        for (int size : new int[]{100, 1_000, 5_000}) {
            Brd brd = seed(size);
            String documents = "The system multiplies two whole numbers and returns the product.";
            int before = BrdAuthoring.render(brd).length();
            String after = BrdSubset.render(brd, documents);
            table.append(String.format(Locale.ROOT, "  %-12d %,14d %,14d %10d%n",
                size, before, after.length(), BrdSubset.choose(brd, documents).size()));
            if (size <= BrdSubset.MAX_REQUIREMENTS) {
                assertThat(BrdSubset.isTrimmed(brd))
                    .as("a small graph is still sent whole").isFalse();
            } else {
                assertThat(BrdSubset.choose(brd, documents))
                    .as("bounded by the cap, not by the project")
                    .hasSize(BrdSubset.MAX_REQUIREMENTS);
                assertThat(after.length())
                    .as("a bigger project must not mean a bigger prompt")
                    .isLessThan(before);
            }
        }
        System.out.println(table);

        // The bound is the point: 1,000 and 5,000 requirements cost the analyst the same.
        Brd thousand = seed(1_000);
        Brd fiveThousand = seed(5_000);
        String documents = "The system multiplies two whole numbers and returns the product.";
        assertThat(BrdSubset.choose(fiveThousand, documents).size())
            .isEqualTo(BrdSubset.choose(thousand, documents).size());
    }

    /** The whole prompt, as the model receives it, at the same three sizes. */
    @Test
    void theWholePromptIsBoundedAndSaysThatItIsASubset() throws Exception {
        StringBuilder table = new StringBuilder(String.format(Locale.ROOT,
            "%nthe drafting prompt as sent, in characters:%n  %-12s %14s%n",
            "requirements", "prompt"));
        ArtifactStore original = store;
        try {
            for (int size : new int[]{100, 1_000, 5_000}) {
                // A store of its own per size: one intake flow lives per project, and re-using it
                // would leave the previous size's document attached and change what was measured.
                try (ArtifactStore scaled = new ArtifactStore(dir.resolve("scale-" + size))) {
                    store = scaled;
                    seed(size);
                    FakeAnalyst analyst = install("{\"questions\":[]}", oneNewProposal());
                    SourceDocument document = upload("spec.md",
                        "The system must let a customer download an invoice as a PDF.");
                    String flowId = service.intake().flow().id().toString();
                    service.addDocument(flowId, document.id().toString(), null);
                    service.start(flowId);
                    await(flowId, GuidedFlowState.REVIEW);

                    String drafting = analyst.prompt(1);
                    table.append(String.format(Locale.ROOT, "  %-12d %,14d%n",
                        size, drafting.length()));
                    if (size > BrdSubset.MAX_REQUIREMENTS) {
                        assertThat(drafting)
                            .as("an absence must never read as a non-existence")
                            .contains("A SUBSET, NOT THE WHOLE DOCUMENT")
                            .contains("This project has " + size + " requirements");
                    } else {
                        assertThat(drafting)
                            .contains("CURRENT BRD (all " + size + " requirement(s))");
                    }
                }
            }
        } finally {
            store = original;
            ConsoleContext.set(null);
        }
        System.out.println(table);
    }

    // --- 2. the four behaviours that must survive being shown only part of the graph -----------

    /**
     * The test that matters, and the one that fails on the old code: a document that says again what
     * the BRD already holds must not become a second requirement.
     *
     * <p>Two shapes of that, because they are not the same thing. A near-verbatim restatement is
     * re-cast as a CHANGE to the requirement it repeats, so the operator reads a diff against a
     * handle rather than a second copy. A reworded one is flagged against that handle and unticked.
     * Neither is written; the difference is what the operator is shown.
     */
    @Test
    void atAThousandRequirementsARestatementIsAChangeToTheOneThatExistsNotANewRequirement() {
        seed(1_000);
        Brd before = store.getBrd(projectId);
        BrdRequirement existing = byHandle(before, "R500");
        assertThat(existing.title()).isEqualTo("Multiply two whole numbers");

        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Multiply two whole numbers",\
            "rationale":"the document says the system multiplies two whole numbers",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL",\
            "text":"The system multiplies two whole numbers and returns their product.",\
            "criteria":[{"text":"two by three is six",\
            "test":"swarm.accept.MultiplyTest#twoByThree"}]},\
            {"kind":"ADD","ref":"N2","title":"Multiplication of two integers",\
            "rationale":"the document restates it in other words",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL",\
            "text":"Given two integers the system returns their product.",\
            "criteria":[{"text":"four by five is twenty",\
            "test":"swarm.accept.MultiplyTest#fourByFive"}]}\
            ]}""");
        SourceDocument document = upload("restated.md",
            "The system multiplies two whole numbers and returns their product.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(2);

        FlowProposal restated = proposals.get(0);
        assertThat(restated.kind())
            .as("said again in the same words: a change to R500, not a second R500")
            .isEqualTo(FlowProposalKind.EDIT);
        assertThat(restated.handle()).isEqualTo("R500");
        assertThat(restated.accepted()).isFalse();
        assertThat(restated.rationale()).contains("R500");
        assertThat(restated.before()).contains("Multiply two whole numbers");

        FlowProposal reworded = proposals.get(1);
        assertThat(reworded.accepted())
            .as("reworded rather than repeated: still not written without a decision")
            .isFalse();
        assertThat(reworded.rationale()).contains("R500");

        assertThat(reviewing.stepLabel()).contains("duplicate");

        // Nothing lands: applying with both unticked writes nothing and says so.
        assertThat(service.apply(flowId)).startsWith("error: nothing is accepted");
        assertThat(store.getBrd(projectId).requirements()).hasSize(1_000);
    }

    @Test
    void atAThousandRequirementsSomethingGenuinelyNewIsStillProposedAndStillLands() {
        seed(1_000);
        install("{\"questions\":[]}", oneNewProposal());
        SourceDocument document = upload("invoices.md",
            "The system must let a customer download an invoice as a PDF.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).kind()).isEqualTo(FlowProposalKind.ADD);
        assertThat(proposals.get(0).accepted())
            .as("new ground is still one click from being written")
            .isTrue();

        assertThat(service.apply(flowId)).isEmpty();
        Brd after = store.getBrd(projectId);
        assertThat(after.requirements()).hasSize(1_001);
        assertThat(after.requirements().get(1_000).title())
            .isEqualTo("Download an invoice as a PDF");
    }

    @Test
    void atAThousandRequirementsAConflictIsStillRaisedAndStillWaitsForTheOperator() {
        seed(1_000);
        install("{\"questions\":[]}", """
            {"proposals":[\
            {"kind":"CONFLICT","handle":"R500","title":"Two answers for multiplication",\
            "rationale":"the new document contradicts R500",\
            "before":"The system multiplies two whole numbers and returns the product.",\
            "after":"Multiplication is refused for numbers over one thousand."}\
            ]}""");
        SourceDocument document = upload("limits.md",
            "Multiplication is refused for numbers over one thousand.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).kind()).isEqualTo(FlowProposalKind.CONFLICT);
        assertThat(proposals.get(0).accepted())
            .as("accepting a conflict would be choosing a side, which is the operator's job")
            .isFalse();
    }

    // --- 3. the tool, and the rule it must not break ------------------------------------------

    /**
     * The analyst may look up what the subset left out — and the answer comes back with the handle,
     * so a requirement outside the window cannot be re-proposed for want of being able to see it.
     */
    @Test
    void theAnalystCanSearchForWhatTheSubsetLeftOutAndTheSearchOnlyEverReads() {
        seed(1_000);
        // A first reply that is nothing but a search line, then the real answer.
        FakeAnalyst analyst = install("{\"questions\":[]}",
            "SEARCH multiply two whole numbers", oneNewProposal());
        SourceDocument document = upload("invoices.md",
            "The system must let a customer download an invoice as a PDF.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        await(flowId, GuidedFlowState.REVIEW);

        // Four calls: questions, drafting, drafting again after the search. The third carries the
        // search result, naming the handle the subset did not show.
        assertThat(analyst.calls()).isEqualTo(3);
        String afterSearch = analyst.prompt(2);
        assertThat(afterSearch)
            .contains("SEARCH RESULT")
            .contains("R500")
            .contains("Multiply two whole numbers");

        // R11: retrieval is not a route by which an agent writes. The graph is untouched by the
        // search, and nothing was added by it.
        assertThat(store.getBrd(projectId).requirements()).hasSize(1_000);
    }

    /** A small project is not put through a tool protocol it has no use for. */
    @Test
    void aProjectSmallEnoughToSendWholeIsNotOfferedASearchAtAll() {
        seed(20);
        FakeAnalyst analyst = install("{\"questions\":[]}", oneNewProposal());
        SourceDocument document = upload("invoices.md",
            "The system must let a customer download an invoice as a PDF.");
        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);
        await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.prompt(1))
            .doesNotContain("YOU CAN LOOK THINGS UP")
            .doesNotContain("A SUBSET, NOT THE WHOLE DOCUMENT");
    }

    @Test
    void theSearchLineIsReadOnlyWhenTheWholeReplyIsOne() {
        assertThat(RequirementsIntake.searchQuery("SEARCH refund window")).isEqualTo("refund window");
        assertThat(RequirementsIntake.searchQuery("  search REFUNDS  ")).isEqualTo("REFUNDS");
        assertThat(RequirementsIntake.searchQuery("{\"proposals\":[]}")).isNull();
        assertThat(RequirementsIntake.searchQuery(
            "{\"proposals\":[]}\nSEARCH something")).isNull();
        assertThat(RequirementsIntake.searchQuery("SEARCH   ")).isNull();
        assertThat(RequirementsIntake.searchQuery(null)).isNull();
    }

    // --- helpers -------------------------------------------------------------------------------

    /** One proposal about ground no seeded requirement covers. */
    private static String oneNewProposal() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Download an invoice as a PDF",\
            "rationale":"the document asks for it",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL",\
            "text":"A customer can download an invoice for a past order as a PDF.",\
            "criteria":[{"text":"a past order yields a PDF invoice",\
            "test":"swarm.accept.InvoiceTest#downloadsAPdf"}]}\
            ]}""";
    }

    /**
     * A BRD of {@code size} requirements, written in one save. Each carries one accepted check, so
     * the rendering is the same shape a real graph produces; R500 is the multiplication requirement
     * the duplicate tests aim at.
     */
    private Brd seed(int size) {
        Brd brd = store.ensureBrd(projectId);
        List<BrdRequirement> requirements = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            String handle = "R" + i;
            BrdRequirement requirement = i == 500
                ? new BrdRequirement(UUID.randomUUID(), handle, "Multiply two whole numbers",
                    "The system multiplies two whole numbers and returns the product.",
                    Priority.HIGH, RequirementStatus.ACTIVE, null)
                : new BrdRequirement(UUID.randomUUID(), handle, area(i) + " " + i,
                    "The system supports " + area(i).toLowerCase(Locale.ROOT) + " case " + i
                        + " for an ordinary user of the product.",
                    Priority.MEDIUM, RequirementStatus.ACTIVE, null);
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
                "case " + i + " behaves as stated", "swarm.accept.Area" + (i % 20) + "Test#case" + i);
            criterion.setStatus(CriterionStatus.ACCEPTED);
            requirement.setCriteria(new ArrayList<>(List.of(criterion)));
            requirements.add(requirement);
        }
        brd.setRequirements(requirements);
        store.saveBrd(brd, "human", "seeded " + size + " requirements");
        return store.getBrd(projectId);
    }

    private static final String[] AREAS = {"Checkout", "Refunds", "Search", "Reporting",
        "Accounts", "Notifications", "Pricing", "Shipping", "Inventory", "Support"};

    private static String area(int i) {
        return AREAS[i % AREAS.length];
    }

    private static BrdRequirement byHandle(Brd brd, String handle) {
        for (BrdRequirement requirement : brd.requirements()) {
            if (handle.equals(requirement.handle())) {
                return requirement;
            }
        }
        throw new AssertionError("no " + handle);
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

    /** Hands back canned replies in order and keeps every prompt it was given. */
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

        int calls() {
            return prompts.size();
        }

        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
