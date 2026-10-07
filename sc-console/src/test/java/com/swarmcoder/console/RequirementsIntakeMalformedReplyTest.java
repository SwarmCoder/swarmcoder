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
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.SourceDocument;
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
 * The analyst surviving a reply that will not parse, and a reply that reads fine but proposes
 * nothing — the console-side twin of {@code TestAuthorClientMalformedReplyTest} /
 * {@code ArchitectClientMalformedReplyTest} (commit 642b6d7, 2026-09-03). Before this,
 * {@link RequirementsIntake} sliced the first '{' to the last '}' with no leniency and no retry
 * (salvage aside — {@link GuidedFlowIntakeTest#anExtraBraceCostsWhatCameAfterItNotTheWholeAnalysis}
 * pins that it still works alongside the retry added here), and a bad reply — or an honestly empty
 * one, over a document that plainly had something to say — failed the whole run on the first try.
 */
class RequirementsIntakeMalformedReplyTest {

    private static final long WAIT_MILLIS = 10_000;

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private GuidedFlowServiceImpl service;
    private BlobStore blobs;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        blobs = new BlobStore(dir.resolve("blobs"));
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
    void anEscapedReplyParsesOnTheFirstTry() {
        FakeAnalyst analyst = install("{\"questions\":[]}", escaped(proposalsReply()));
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("no retry needed").isEqualTo(2);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
    }

    @Test
    void aGarbageReplyThenAGoodOneSucceedsWithExactlyOneRetry() {
        FakeAnalyst analyst = install("{\"questions\":[]}",
            "not json at all, the model just talked", proposalsReply());
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("one question round, one bad reply, one retry").isEqualTo(3);
        assertThat(analyst.prompt(2))
            .as("the retry conversation carries the bad reply and the parser's own complaint")
            .contains("not json at all, the model just talked")
            .contains("That was not valid JSON")
            .contains("Reply with only the JSON object");
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
    }

    @Test
    void aSecondGarbageReplyFailsHonestlyWithABlobRef() throws Exception {
        FakeAnalyst analyst = install("{\"questions\":[]}", "still not json", "still not json either");
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow failed = await(flowId, GuidedFlowState.FAILED);

        assertThat(analyst.calls()).as("one question round, one bad reply, one retry, then stop")
            .isEqualTo(3);
        assertThat(failed.error())
            .contains("the analyst's reply was not valid JSON (twice)")
            .contains("kept as blob ");
        String ref = failed.error().substring(failed.error().indexOf("kept as blob ") + 13).strip();
        assertThat(new String(blobs.getBlob(ref), StandardCharsets.UTF_8))
            .isEqualTo("still not json either");
    }

    @Test
    void anEmptyButValidReplyIsAskedAgainOnceOverANonEmptyDocument() {
        FakeAnalyst analyst = install("{\"questions\":[]}", "{\"proposals\":[]}", proposalsReply());
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("questions, an empty batch, then the re-ask").isEqualTo(3);
        assertThat(analyst.prompt(2))
            .contains("You proposed no changes")
            .contains("the documents are not empty")
            .contains("Propose at least one requirement change");
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
    }

    /**
     * The other half of "do not re-ask when there is genuinely nothing to do": an empty document is
     * refused before the analyst is ever called at all, so there is nothing to retry — pinned here
     * because it is the condition under which {@link RequirementsIntake#run} would otherwise reach
     * the empty-reply branch with nothing to ask again about.
     *
     * <p>{@code DocumentIngest} itself already refuses a blank upload ("no text could be
     * extracted"), so this state cannot arise from the upload endpoint — the document is saved and
     * attached directly, the way it would be if extraction ever changed underneath an already
     * attached one.
     */
    @Test
    void aDocumentWithNoTextIsRefusedWithoutCallingTheModelAtAll() {
        FakeAnalyst analyst = install("{\"questions\":[]}", proposalsReply());
        SourceDocument document = new SourceDocument(UUID.randomUUID(), projectId, "blank.md",
            "text/markdown", "deadbeef", "   \n   ", "text", 4, java.time.Instant.now());
        store.saveSourceDocument(document);
        GuidedFlows.attachUploaded(store, projectId, document.id());

        String flowId = service.intake().flow().id().toString();
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow failed = await(flowId, GuidedFlowState.FAILED);

        assertThat(failed.error()).contains("has any extracted text to read");
        assertThat(analyst.calls()).as("refused before ever reaching the model").isZero();
    }

    /**
     * Verbatim shape of the harness's link 3 failure (2026-09-03): a reply broken partway through
     * with valid JSON up to the break — "Unexpected close marker '}': expected ']' ... column: 14"
     * — followed by a clean reply. The retry must actually happen and must actually succeed: this
     * used to short-circuit on the FIRST reply because the reader salvaged one proposal from it and
     * counted that a success, so the retry never fired and the run kept only the partial batch.
     */
    @Test
    void aReplyBrokenAtColumnFourteenThenAGoodOneSucceedsWithExactlyOneRetryAndNoSalvage() {
        FakeAnalyst analyst = install("{\"questions\":[]}", brokenAtColumnFourteen(), proposalsReply());
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("one question round, one broken reply, one retry")
            .isEqualTo(3);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
        assertThat(reviewing.stepLabel())
            .as("a retry that succeeds outright needs no salvage wording")
            .doesNotContain("malformed twice")
            .doesNotContain("kept as blob");
    }

    /**
     * Both the reply and its retry are broken the same way — the harness's actual failure, with
     * nothing to retry into. The run must not fail: it proceeds with what salvage recovered, and
     * says so honestly rather than reporting the recovered handful as an ordinary full success.
     */
    @Test
    void aReplyBrokenTwiceSalvagesAndSaysSoHonestly() throws Exception {
        FakeAnalyst analyst = install("{\"questions\":[]}", brokenAtColumnFourteen());
        SourceDocument document = upload("calculator.md", longDocument());

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("one question round, one broken reply, one retry")
            .isEqualTo(3);
        assertThat(store.listFlowProposals(reviewing.id()))
            .as("everything before the break survives; the rest is dropped, not invented")
            .hasSize(1);
        assertThat(reviewing.stepLabel())
            .isEqualTo("the analyst's reply was malformed twice; 1 proposal could be recovered "
                + "from it (kept as blob " + refIn(reviewing.stepLabel()) + ")");
        assertThat(new String(blobs.getBlob(refIn(reviewing.stepLabel())), StandardCharsets.UTF_8))
            .isEqualTo(brokenAtColumnFourteen());
    }

    /**
     * A model that wraps the array under a different name, or drops the envelope entirely, is a
     * shape difference — not a reason to spend the analyst's one retry. Accepted on the first call.
     */
    @Test
    void aReplyWrappingProposalsAsItemsParsesOnTheFirstTry() {
        FakeAnalyst analyst = install("{\"questions\":[]}", """
            {"items":[\
            {"kind":"ADD","handle":"","title":"Guest checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"]}\
            ]}""");
        SourceDocument document = upload("pricing.md", "A guest must be able to pay per seat.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        assertThat(service.start(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        assertThat(analyst.calls()).as("no retry needed for a shape difference").isEqualTo(2);
        assertThat(store.listFlowProposals(reviewing.id())).hasSize(1);
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * Two well-formed proposals with one extra closing brace between them — the exact shape a 27B
     * model produced live on 2026-08-28, and structurally the same break the harness hit at column
     * 14 on 2026-09-03: valid JSON up to the stray brace, then a close where an element or the
     * array's own "]" was expected.
     */
    private static String brokenAtColumnFourteen() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Multiply two whole numbers",\
            "rationale":"the document asks for it in the first line",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Arithmetic",\
            "text":"The system returns the product of two integers.",\
            "criteria":[{"text":"two positive integers give their product",\
            "test":"swarm.accept.ArithmeticTest#multipliesTwoPositives"}]}},\
            {"kind":"ADD","ref":"N2","title":"Edges of the range",\
            "rationale":"the document asks for sensible behaviour at the edges",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Arithmetic",\
            "text":"Multiplication at the range boundary does not produce undefined behaviour.",\
            "criteria":[{"text":"overflow is reported, not silently wrapped",\
            "test":"swarm.accept.ArithmeticTest#reportsOverflow"}]}\
            ]}""";
    }

    /** Well over 1,000 characters, so the honest-outcome wording actually applies (see the doc on
     * it) — the whole sentence repeated, not just its tail, so parenthesise the concatenation. */
    private static String longDocument() {
        return ("The calculator multiplies two whole numbers and must behave sensibly at the "
            + "edges of the range it supports. ").repeat(15);
    }

    /** The blob id out of "... (kept as blob <ref>)" at the end of a label. */
    private static String refIn(String label) {
        return label.substring(label.indexOf("kept as blob ") + 13, label.length() - 1);
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static String proposalsReply() {
        return """
            {"proposals":[\
            {"kind":"ADD","handle":"","title":"Guest checkout",\
            "rationale":"the spec says a guest must be able to pay",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Checkout",\
            "text":"A guest can complete a purchase without an account.",\
            "criteria":["a purchase completes with no account"]}\
            ]}""";
    }

    /** The same object, with every quote backslash-escaped and no outer quotes — the shape a paid
     * endpoint sent live (LlmJsonTest#unwrapsAnEscapedBodyWithNoOuterQuotes). */
    private static String escaped(String json) {
        return json.replace("\"", "\\\"");
    }

    private SourceDocument upload(String filename, String text) {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, filename,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        assertThat(result.failed()).isFalse();
        return result.document();
    }

    private FakeAnalyst install(String... replies) {
        FakeAnalyst analyst = new FakeAnalyst(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), blobs,
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

    /**
     * A scripted analyst: hands back the next canned reply and keeps every prompt it was given.
     */
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
