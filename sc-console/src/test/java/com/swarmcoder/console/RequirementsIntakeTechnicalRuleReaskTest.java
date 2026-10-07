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

import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
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
 * Harness run 15 (2026-09-03): a technical document — "must"/"never"/"do not"/"forbidden"
 * throughout — produced eight requirement proposals and zero CONSTRAINT proposals, though the same
 * document had yielded 8-13 rules on the previous nine runs. The reply was valid JSON with a
 * non-empty batch, so neither existing safeguard in {@link RequirementsIntake} noticed anything
 * wrong: the malformed-reply retry never fires because nothing was malformed, and the empty-batch
 * reask never fires because the batch was not empty. This pins the third safeguard added for it: a
 * technical document that states no rule is asked again, once, and the answer is merged into —
 * never used to replace — what the first reply already proposed.
 */
class RequirementsIntakeTechnicalRuleReaskTest {

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

    @Test
    void eightRequirementsAndNoConstraintsFromATechnicalDocumentAreAskedAgainAndMerged() {
        FakeAnalyst analyst = install("{\"questions\":[]}", eightRequirements(), oneConstraint());
        SourceDocument document = upload("bookshelf-tech-requirements.md",
            "The system must never use Spring. Do not use SQL - storage of any kind is forbidden.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.setDocumentTechnical(flowId, document.id().toString(), true);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls())
            .as("questions, the drafting reply with no rules, and the rule reask")
            .isEqualTo(3);
        assertThat(analyst.prompt(2))
            .as("the exact reask wording")
            .contains("The technical document 'bookshelf-tech-requirements.md' states rules "
                + "about how the project must be built (sentences with must, never, only, do not, "
                + "forbidden), and you proposed none. Every such sentence is a CONSTRAINT proposal "
                + "with its exact excerpt. Add them to your proposals.");

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals)
            .as("all eight requirements from the first reply, plus the one merged-in constraint")
            .hasSize(9);
        for (int i = 1; i <= 8; i++) {
            int n = i;
            assertThat(proposals.stream().anyMatch(p -> ("Requirement " + n).equals(p.title())))
                .as("requirement " + n + " survived the merge").isTrue();
        }
        assertThat(proposals.stream().filter(p -> p.after().contains("Kind: a rule for building it")))
            .as("the constraint the reask recovered")
            .hasSize(1);
    }

    @Test
    void aFirstReplyThatAlreadyStatesARuleIsNeverAskedAgain() {
        FakeAnalyst analyst = install("{\"questions\":[]}", twoRequirementsAndOneConstraint());
        SourceDocument document = upload("tech.md", "Do not use SQL anywhere in this project.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.setDocumentTechnical(flowId, document.id().toString(), true);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls())
            .as("questions and drafting only — the batch already states a rule")
            .isEqualTo(2);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(3);
    }

    @Test
    void noTechnicalDocumentNeverTriggersTheReask() {
        FakeAnalyst analyst = install("{\"questions\":[]}", eightRequirements());
        SourceDocument document = upload("spec.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls())
            .as("no technical document was attached — nothing to ask again about")
            .isEqualTo(2);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(8);
    }

    @Test
    void aSecondEmptyReplyProceedsWithTheOutcomeSentence() {
        FakeAnalyst analyst = install("{\"questions\":[]}", eightRequirements(), "{\"proposals\":[]}");
        SourceDocument document = upload("bookshelf-tech-requirements.md",
            "The system must never use Spring.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.setDocumentTechnical(flowId, document.id().toString(), true);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls())
            .as("the reask happens, even though it recovers nothing")
            .isEqualTo(3);
        assertThat(reviewing.stepLabel())
            .as("the operator can see that the technical document still stated no rule")
            .contains("the analyst stated no rules from 'bookshelf-tech-requirements.md' twice");
        assertThat(store.listFlowProposals(reviewing.id()))
            .as("nothing recovered from the reask, but the original eight are not lost")
            .hasSize(8);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Eight ordinary FUNCTIONAL proposals, no CONSTRAINT among them — the harness run 15 shape. */
    private static String eightRequirements() {
        StringBuilder sb = new StringBuilder("{\"proposals\":[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"kind\":\"ADD\",\"ref\":\"N").append(i).append("\",")
                .append("\"title\":\"Requirement ").append(i).append("\",")
                .append("\"rationale\":\"the document asks for it\",\"priority\":\"HIGH\",")
                .append("\"requirementKind\":\"FUNCTIONAL\",\"category\":\"General\",")
                .append("\"text\":\"The system does thing number ").append(i).append(".\",")
                .append("\"criteria\":[{\"text\":\"thing ").append(i).append(" happens\",")
                .append("\"test\":\"swarm.accept.ThingsTest#doesThing").append(i).append("\"}]}");
        }
        return sb.append("]}").toString();
    }

    /** One CONSTRAINT proposal — what a rule reask is meant to recover. */
    private static String oneConstraint() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"No Spring or SQL",\
            "rationale":"the document forbids them","priority":"CRITICAL",\
            "requirementKind":"CONSTRAINT",\
            "text":"Do not use Spring or SQL anywhere in this project.",\
            "excerpt":"The system must never use Spring. Do not use SQL - storage of any kind is \
            forbidden.","criteria":[]}\
            ]}""";
    }

    /** Two ordinary requirements and one CONSTRAINT, all in the FIRST reply. */
    private static String twoRequirementsAndOneConstraint() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"No SQL",\
            "rationale":"the document forbids it","priority":"CRITICAL",\
            "requirementKind":"CONSTRAINT",\
            "text":"Do not use SQL anywhere in this project.",\
            "excerpt":"Do not use SQL anywhere in this project.","criteria":[]},\
            {"kind":"ADD","ref":"N2","title":"Requirement A","rationale":"the document asks for it",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"General",\
            "text":"The system does thing A.",\
            "criteria":[{"text":"thing A happens","test":"swarm.accept.ThingsTest#doesThingA"}]},\
            {"kind":"ADD","ref":"N3","title":"Requirement B","rationale":"the document asks for it",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"General",\
            "text":"The system does thing B.",\
            "criteria":[{"text":"thing B happens","test":"swarm.accept.ThingsTest#doesThingB"}]}\
            ]}""";
    }

    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).isFalse();
        return result.document();
    }

    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withChat(analyst));
        return analyst;
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

    /** A scripted analyst: hands back the next canned reply and keeps every prompt it was given. */
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
