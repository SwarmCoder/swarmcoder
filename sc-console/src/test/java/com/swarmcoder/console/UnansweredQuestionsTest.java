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
import com.swarmcoder.domain.FlowQuestion;
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
 * The analyst may not draft from a question it has not had answered (author decision,
 * DEVELOPER_CORRECTIONS.md §20.1).
 *
 * <p><b>What happened, verbatim.</b> Given a two-sentence document whose second sentence said
 * multiplication must "behave sensibly at the edges of the range we support", the analyst asked a
 * good question about exactly that sentence — and in the same pass drafted
 * <em>"Handle multiplication at the boundaries of the supported integer range"</em>, which no test
 * could ever prove. Asking well and drafting badly at once is the failure; the rules as they stood
 * did not forbid it, and the prompt then explicitly invited it by telling the analyst to pick the
 * most defensible reading of anything unanswered.
 *
 * <p><b>So there are two changes and this covers both.</b> The prompt now says plainly that an
 * unanswered question is not the analyst's to resolve. And because a prompt is advice, a proposal
 * whose wording comes back from an unanswered question is unticked in code, with the question
 * quoted, so the operator has to look at it before it becomes anything.
 */
class UnansweredQuestionsTest {

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

    /** The live failure, reproduced: the question is asked, ignored, and drafted from anyway. */
    @Test
    void aProposalDrawnFromAnUnansweredQuestionIsUntickedAndQuotesTheQuestion() {
        FakeAnalyst analyst = install(theQuestionItReallyAsked(), theRequirementItReallyDrafted());
        SourceDocument document = upload("multiply.md",
            "The system multiplies two whole numbers. Multiplication has to behave sensibly at "
            + "the edges of the range we support.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        FlowQuestion question = store.listFlowQuestions(awaiting.id()).get(0);
        assertThat(question.answer()).isNull();
        assertThat(question.skipped())
            .as("not skipped either — simply left, which is the case that used to slip through")
            .isFalse();

        // The operator presses Continue without answering.
        assertThat(service.submitAnswers(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        // The prompt tells the analyst not to draft from it…
        assertThat(analyst.prompt(1))
            .contains("STILL UNANSWERED")
            .contains("DO NOT WRITE A REQUIREMENT FROM THIS")
            .doesNotContain("SKIPPED, SO NOT ANSWERED");

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(2);

        // …and when it does it anyway, the proposal is stopped in code.
        FlowProposal fromTheQuestion = proposals.get(1);
        assertThat(fromTheQuestion.title())
            .isEqualTo("Handle multiplication at the boundaries of the supported integer range");
        assertThat(fromTheQuestion.accepted())
            .as("the whole point: this must not be one click from being scope")
            .isFalse();
        assertThat(fromTheQuestion.rationale())
            .contains("you have not answered")
            .contains("What is the maximum value");

        // The requirement the document really did state is untouched and still one click away.
        assertThat(proposals.get(0).title()).isEqualTo("Multiply two whole numbers");
        assertThat(proposals.get(0).accepted()).isTrue();

        assertThat(reviewing.stepLabel()).contains("restate a question you have not answered");

        // Applying writes the one the operator can actually have, and nothing else.
        assertThat(service.apply(flowId)).isEmpty();
        assertThat(store.getBrd(projectId).requirements())
            .extracting(com.swarmcoder.domain.BrdRequirement::title)
            .containsExactly("Multiply two whole numbers");
    }

    /** Answer the question and the same proposal is ordinary work again. */
    @Test
    void onceTheQuestionIsAnsweredTheSameProposalIsTickedLikeAnyOther() {
        install(theQuestionItReallyAsked(), theRequirementItReallyDrafted());
        SourceDocument document = upload("multiply.md",
            "The system multiplies two whole numbers. Multiplication has to behave sensibly at "
            + "the edges of the range we support.");

        String flowId = service.intake().flow().id().toString();
        service.addDocument(flowId, document.id().toString(), null);
        service.start(flowId);

        GuidedFlow awaiting = await(flowId, GuidedFlowState.AWAITING_ANSWERS);
        FlowQuestion question = store.listFlowQuestions(awaiting.id()).get(0);
        assertThat(service.answer(flowId, question.id().toString(),
            "Signed 64-bit integers; anything beyond that is refused.")).isEmpty();

        assertThat(service.submitAnswers(flowId)).isEmpty();
        GuidedFlow reviewing = await(flowId, GuidedFlowState.REVIEW);

        List<FlowProposal> proposals = store.listFlowProposals(reviewing.id());
        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(1).accepted())
            .as("answered means the analyst was entitled to write it")
            .isTrue();
        assertThat(reviewing.stepLabel()).doesNotContain("restate a question");
    }

    /** The question as the 27B model really asked it on 2026-08-28. */
    private static String theQuestionItReallyAsked() {
        return """
            {"questions":[{"subject":"Range of supported whole numbers",\
            "text":"The document requires multiplication to 'behave sensibly at the edges of the \
            range we support', but does not define what that range is. What is the maximum value \
            for the input whole numbers and the resulting product?",\
            "sourceQuote":"Multiplication has to behave sensibly at the edges of the range we \
            support.","kind":"TEXT"}]}""";
    }

    /** And the requirement it drafted out of that same sentence, in the same run. */
    private static String theRequirementItReallyDrafted() {
        return """
            {"proposals":[\
            {"kind":"ADD","ref":"N1","title":"Multiply two whole numbers",\
            "rationale":"the document's first sentence",\
            "priority":"HIGH","requirementKind":"FUNCTIONAL",\
            "text":"The system multiplies two whole numbers and returns the product.",\
            "criteria":[{"text":"two by three is six",\
            "test":"swarm.accept.MultiplyTest#twoByThree"}]},\
            {"kind":"ADD","ref":"N2",\
            "title":"Handle multiplication at the boundaries of the supported integer range",\
            "rationale":"the document's second sentence",\
            "priority":"MEDIUM","requirementKind":"FUNCTIONAL",\
            "text":"Multiplication behaves sensibly at the edges of the supported range of whole \
            numbers. ASSUMPTION: the range is whatever the platform supports.",\
            "criteria":[{"text":"the edges of the range behave sensibly",\
            "test":"swarm.accept.MultiplyTest#edges"}]}\
            ]}""";
    }

    // --- helpers ---------------------------------------------------------------------------------

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

        String prompt(int index) {
            assertThat(prompts.size()).as("model calls made").isGreaterThan(index);
            return prompts.get(index);
        }
    }
}
