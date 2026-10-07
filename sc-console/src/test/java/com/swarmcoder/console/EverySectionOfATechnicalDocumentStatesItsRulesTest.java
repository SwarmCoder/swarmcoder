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
 * A section of a technical document that states rules, and that the analyst drew no rule from, is
 * asked about once by name (live harness runs 56 and 58, 2026-10-01).
 *
 * <p>There the analyst stated thirteen rules from a nine-section document and none from its
 * "Tests" section, which said a service is never constructed by hand in a test. The test author
 * was told "the project's standing rules" without that one, and wrote the forbidden test. See
 * {@link UncoveredRuleSections}. No live model: the analyst is scripted.
 */
class EverySectionOfATechnicalDocumentStatesItsRulesTest {

    private static final long WAIT_MILLIS = 10_000;

    private static final String DOCUMENT = """
        # How this project is built

        Read this first.

        ## Storage

        Storage is an object graph. Nothing is ever persisted in the browser, and SQL is forbidden.

        ## Tests

        A service is never constructed by hand in a test: its store is injected, so a hand-made \
        one has no database. Get it from the test server the framework documents.

        ## Background

        The team picked this stack in the spring because it is small.
        """;

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
    void aSectionThatStatesRulesAndHasNoRuleDrawnFromItIsFound() {
        List<UncoveredRuleSections.Section> passedOver = UncoveredRuleSections.in(DOCUMENT,
            List.of("Nothing is ever persisted in the browser, and SQL is forbidden."));

        assertThat(passedOver).extracting(UncoveredRuleSections.Section::heading)
            .as("Storage has a rule drawn from it; Background states no rule; Tests was passed over")
            .containsExactly("Tests");
    }

    @Test
    void aDocumentWhoseEveryRuleSectionIsCoveredAndAOneSectionDocumentSayNothing() {
        assertThat(UncoveredRuleSections.in(DOCUMENT, List.of(
            "Nothing is ever persisted in the browser, and SQL is forbidden.",
            // quoted loosely, the way a model does: other case, the markdown marks gone
            "a service is NEVER constructed by hand in a test"))).isEmpty();
        assertThat(UncoveredRuleSections.in("## Only\n\nYou must never use SQL here.", List.of()))
            .as("fewer than two headings: no sections to compare")
            .isEmpty();
    }

    @Test
    void theAnalystIsAskedOnceForThePassedOverSectionAndItsRuleIsAdded() {
        FakeAnalyst analyst = install("{\"questions\":[]}", storageRuleOnly(), testsRule());
        SourceDocument document = upload("technical.md", DOCUMENT);

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.setDocumentTechnical(flowId, document.id().toString(), true);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls())
            .as("questions, the drafting reply, and one reask for the passed-over section")
            .isEqualTo(3);
        assertThat(analyst.prompt(2))
            .contains("'technical.md' states rules in section(s) \"Tests\", and you drew no "
                + "CONSTRAINT from them")
            .doesNotContain("\"Storage\"")
            .doesNotContain("\"Background\"");
        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).extracting(FlowProposal::title)
            .containsExactly("Storage is an object graph", "Services come from the test server");
        assertThat(reviewing.stepLabel())
            .as("every rule section is covered now, so nothing is left for the operator to chase")
            .doesNotContain("no rule was stated from");
    }

    @Test
    void aSectionStillPassedOverAfterTheReaskIsNamedToTheOperator() {
        FakeAnalyst analyst = install("{\"questions\":[]}", storageRuleOnly(), "{\"proposals\":[]}");
        SourceDocument document = upload("technical.md", DOCUMENT);

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.setDocumentTechnical(flowId, document.id().toString(), true);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("asked once more, never a third time").isEqualTo(3);
        assertThat(reviewing.stepLabel())
            .contains("no rule was stated from \"Tests\" in 'technical.md', asked twice");
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static String storageRuleOnly() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Storage is an object graph",\
            "rationale":"the document mandates it","priority":"CRITICAL",\
            "requirementKind":"CONSTRAINT",\
            "text":"Storage is an object graph; no browser persistence and no SQL.",\
            "excerpt":"Nothing is ever persisted in the browser, and SQL is forbidden.",\
            "purpose":"so data survives a restart","strength":"hard","criteria":[]}\
            ]}""";
    }

    private static String testsRule() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Services come from the test server",\
            "rationale":"the document says how tests obtain a service","priority":"CRITICAL",\
            "requirementKind":"CONSTRAINT",\
            "text":"A test never constructs a service by hand; it gets it from the test server.",\
            "excerpt":"A service is never constructed by hand in a test: its store is injected, \
            so a hand-made one has no database.",\
            "purpose":"so the service under test has its database","strength":"hard","criteria":[]}\
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
