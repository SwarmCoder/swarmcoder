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

import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.FlowQuestionKind;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutonomousAnswers} surviving a reply that will not parse — the console-side twin of
 * {@code TestAuthorClientMalformedReplyTest} / {@code ArchitectClientMalformedReplyTest} (commit
 * 642b6d7, 2026-09-03), for the overnight stand-in answerer. Before this it bracketed the first '{'
 * to the last '}' with no leniency and no retry, and a bad reply — the same shape a paid endpoint
 * has actually sent live — left the question skipped with a generic "no answer was given" line
 * that could not be told apart from the model genuinely declining to answer.
 *
 * <p>There is no "empty but valid, checks unclaimed" case here — the planner/analyst re-ask
 * (item 3) is about a proposal batch that came back empty while there was still something to
 * propose; a single clarifying question has no such notion, so only the parse-retry (items 1, 2, 4)
 * applies to this site.
 */
class AutonomousAnswersMalformedReplyTest {

    @TempDir
    Path dir;

    private final UUID projectId = UUID.randomUUID();
    private ArtifactStore store;
    private BlobStore blobs;
    private GuidedFlow flow;

    @BeforeEach
    void openStore() throws Exception {
        store = new ArtifactStore(dir);
        blobs = new BlobStore(dir.resolve("blobs"));
        flow = GuidedFlows.ensureIntake(store, projectId);
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
        FakeModel model = install(escaped(
            "{\"answer\":\"Keep it in the project's own database.\",\"grounded\":false,"
                + "\"why\":\"the smallest ordinary choice\"}"));
        FlowQuestion question = question();
        store.saveFlowQuestions(flow.id(), List.of(question));

        AutonomousAnswers.Outcome outcome = AutonomousAnswers.answerAll(ConsoleContext.get(),
            session(), flow, false);

        assertThat(model.calls()).as("no retry needed").isEqualTo(1);
        assertThat(outcome.answered()).isEqualTo(1);
        assertThat(outcome.skipped()).isZero();
        assertThat(store.listFlowQuestions(flow.id()).get(0).answer())
            .isEqualTo("Keep it in the project's own database.");
    }

    @Test
    void aGarbageReplyThenAGoodOneSucceedsWithExactlyOneRetry() {
        FakeModel model = install("not json at all, the model just talked",
            "{\"answer\":\"Keep it in the project's own database.\",\"grounded\":false,"
                + "\"why\":\"the smallest ordinary choice\"}");
        FlowQuestion question = question();
        store.saveFlowQuestions(flow.id(), List.of(question));

        AutonomousAnswers.Outcome outcome = AutonomousAnswers.answerAll(ConsoleContext.get(),
            session(), flow, false);

        assertThat(model.calls()).as("one bad reply, one retry").isEqualTo(2);
        assertThat(outcome.answered()).isEqualTo(1);
        assertThat(model.prompt(1))
            .as("the retry conversation carries the bad reply and the parser's own complaint")
            .contains("not json at all, the model just talked")
            .contains("That was not valid JSON")
            .contains("Reply with only the JSON object");
    }

    @Test
    void aSecondGarbageReplyIsSkippedHonestlyWithABlobRef() throws Exception {
        FakeModel model = install("still not json", "still not json either");
        FlowQuestion question = question();
        store.saveFlowQuestions(flow.id(), List.of(question));

        AutonomousAnswers.Outcome outcome = AutonomousAnswers.answerAll(ConsoleContext.get(),
            session(), flow, false);

        assertThat(model.calls()).as("one bad reply, one retry, then stop").isEqualTo(2);
        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(outcome.answered()).isZero();
        assertThat(store.listFlowQuestions(flow.id()).get(0).skipped()).isTrue();

        List<AutonomousDecision> refusals = new ArrayList<>();
        for (AutonomousDecision decision : store.listAutonomousDecisions(projectId)) {
            if (decision.kind() == AutonomousDecisionKind.REFUSED) {
                refusals.add(decision);
            }
        }
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).reasoning())
            .contains("the model's reply was not valid JSON (twice)")
            .contains("kept as blob ");
        String reasoning = refusals.get(0).reasoning();
        String ref = reasoning.substring(reasoning.indexOf("kept as blob ") + 13).strip();
        assertThat(new String(blobs.getBlob(ref), StandardCharsets.UTF_8))
            .isEqualTo("still not json either");
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private FlowQuestion question() {
        return new FlowQuestion(UUID.randomUUID(), flow.id(), "Storage",
            "Where are the books kept?", null, null, null, FlowQuestionKind.TEXT,
            new ArrayList<>(), null, null, false);
    }

    private AutonomousMode.Session session() {
        return new AutonomousMode.Session(projectId, Instant.now().plusSeconds(3600), 0, 0);
    }

    /** The same object, with every quote backslash-escaped and no outer quotes — the shape a paid
     * endpoint sent live (LlmJsonTest#unwrapsAnEscapedBodyWithNoOuterQuotes). */
    private static String escaped(String json) {
        return json.replace("\"", "\\\"");
    }

    private FakeModel install(String... replies) {
        FakeModel model = new FakeModel(replies);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), blobs,
            (goal, kind) -> null, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
            .withAnalyst(model));
        return model;
    }

    /** A scripted model: hands back the next canned reply and keeps every prompt it was given. */
    private static final class FakeModel implements ConsoleContext.ChatModel {

        private final List<String> replies;
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger call = new AtomicInteger();

        FakeModel(String... replies) {
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
