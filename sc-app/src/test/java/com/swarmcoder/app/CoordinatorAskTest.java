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
package com.swarmcoder.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.ControlServiceImpl;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model-free proof of {@link CoordinatorAsk}: the question/answer file exchange (answered, timed
 * out, invalid, stop, capped) and that an answer reaches the product's own decision path — the
 * Console's {@code ControlService.resolveDecision} — before the run is handed back to its engine.
 */
class CoordinatorAskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    private CoordinatorAsk ask(long waitMillis, int max) {
        return new CoordinatorAsk(work.resolve("ask"), waitMillis, max, 10, 0, line -> { });
    }

    private static Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decisionKind", "BLOCKED_TASK");
        body.put("workflowState", "TEST_AUTHORING");
        body.put("question", "the red check failed");
        return body;
    }

    /** Writes the answer a moment after the question appears, like a coordinator would. */
    private Thread answerLater(int n, String content) {
        Thread thread = new Thread(() -> {
            try {
                Path question = work.resolve("ask").resolve("question-" + n + ".json");
                while (!Files.exists(question)) {
                    Thread.sleep(5);
                }
                Files.writeString(work.resolve("ask").resolve("answer-" + n + ".json"), content);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void aQuestionIsWrittenWholeAndAnAnswerIsRead() throws Exception {
        CoordinatorAsk ask = ask(5_000, 5);
        Thread answerer = answerLater(1, "{\"answer\":\"Retry\",\"text\":\"fixed the test\"}");

        CoordinatorAsk.Reply reply = ask.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));
        answerer.join();

        assertThat(reply.ending()).isEqualTo(CoordinatorAsk.Ending.ANSWERED);
        assertThat(reply.answer().token()).isEqualTo("retry");
        assertThat(reply.answer().freeText()).isEqualTo("fixed the test");
        JsonNode question = JSON.readTree(work.resolve("ask/question-1.json").toFile());
        assertThat(question.get("n").asInt()).isEqualTo(1);
        assertThat(question.get("question").asText()).isEqualTo("the red check failed");
        assertThat(question.get("options").findValuesAsText("token")).containsExactly("retry", "stop");
        assertThat(question.get("answerFile").asText()).endsWith("answer-1.json");
        // atomic: no temp file is left behind
        try (var files = Files.list(work.resolve("ask"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void noAnswerInTimeIsATimeoutAndTheWaitIsCounted() {
        CoordinatorAsk ask = ask(150, 5);

        CoordinatorAsk.Reply reply = ask.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));

        assertThat(reply.ending()).isEqualTo(CoordinatorAsk.Ending.TIMED_OUT);
        assertThat(ask.waitedMillis()).isGreaterThanOrEqualTo(150);
    }

    @Test
    void anAnswerTheQuestionDoesNotOfferIsInvalid() throws Exception {
        CoordinatorAsk ask = ask(5_000, 5);
        Thread answerer = answerLater(1, "{\"answer\":\"keep\"}"); // keep is a rule answer, not a park answer

        CoordinatorAsk.Reply reply = ask.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));
        answerer.join();

        assertThat(reply.ending()).isEqualTo(CoordinatorAsk.Ending.INVALID);
        assertThat(reply.note()).contains("keep").contains("retry");
    }

    @Test
    void stopEndsItAndGarbageIsInvalid() throws Exception {
        CoordinatorAsk stopping = ask(5_000, 5);
        Thread first = answerLater(1, "{\"answer\":\"stop\",\"text\":\"not worth it\"}");
        CoordinatorAsk.Reply stopped = stopping.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));
        first.join();
        assertThat(stopped.ending()).isEqualTo(CoordinatorAsk.Ending.STOPPED);
        assertThat(stopped.note()).contains("not worth it");

        CoordinatorAsk garbled = new CoordinatorAsk(work.resolve("ask2"), 5_000, 5, 10, 0, l -> { });
        Files.createDirectories(work.resolve("ask2"));
        Files.writeString(work.resolve("ask2/answer-1.json"), "this is not json");
        CoordinatorAsk.Reply bad = garbled.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));
        assertThat(bad.ending()).isEqualTo(CoordinatorAsk.Ending.INVALID);
    }

    @Test
    void theNumberOfQuestionsIsCapped() {
        CoordinatorAsk ask = ask(50, 1);
        ask.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK)); // times out, uses the one
        CoordinatorAsk.Reply second = ask.ask(body(), CoordinatorAsk.optionsFor(DecisionKind.BLOCKED_TASK));

        assertThat(second.ending()).isEqualTo(CoordinatorAsk.Ending.CAP_REACHED);
        assertThat(work.resolve("ask/question-2.json")).doesNotExist();
    }

    @Test
    void aRuleQuestionAnswerIsWrittenInTheFormTheProductReads() {
        var rule = CoordinatorAsk.optionsFor(DecisionKind.GUIDELINE_REVIEW);
        assertThat(rule).extracting(CoordinatorAsk.Option::token)
            .describedAs("the product's own answers for a rule question, then the harness's stop")
            .containsExactly("keep", "reword", "allow", "repair", "stop");
        assertThat(CoordinatorAsk.responseFor(DecisionKind.GUIDELINE_REVIEW,
            new CoordinatorAsk.Answer("reword", " use bundles where possible ")))
            .isEqualTo("reword: use bundles where possible");
        assertThat(CoordinatorAsk.responseFor(DecisionKind.GUIDELINE_REVIEW,
            new CoordinatorAsk.Answer("reword", ""))).isEqualTo("reword");
        assertThat(CoordinatorAsk.responseFor(DecisionKind.GUIDELINE_REVIEW,
            new CoordinatorAsk.Answer("allow", "ignored"))).isEqualTo("allow");
        assertThat(CoordinatorAsk.optionsFor(DecisionKind.BUDGET_EXTENSION))
            .extracting(CoordinatorAsk.Option::token).containsExactly("stop");
    }

    @Test
    void anAnswerIsRecordedThroughTheConsoleAndTheRunIsHandedBack() throws Exception {
        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), id -> { }, id -> { }));
            UUID runId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING, null, null, null,
                null, null, Instant.now(), null);
            run.setParkedAt(Instant.now());
            run.setParkReason("a rule question");
            UUID blocked = UUID.randomUUID();
            UUID ruleQuestion = UUID.randomUUID();
            store.append(() -> {
                store.root().runs.put(runId, run);
                store.root().decisions.put(blocked, new Decision(blocked, runId,
                    DecisionKind.BLOCKED_TASK, "task blocked", DecisionState.PENDING, null,
                    Instant.now().minusSeconds(60)));
                store.root().decisions.put(ruleQuestion, new Decision(ruleQuestion, runId,
                    DecisionKind.GUIDELINE_REVIEW, "a rule question", DecisionState.PENDING, null,
                    Instant.now().minusSeconds(120)));
                return null;
            }).get();

            // not parked -> not a park, whatever is pending
            Run other = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, null, null, Instant.now(), null);
            store.append(() -> {
                store.root().runs.put(other.id(), other);
                return null;
            }).get();
            assertThat(CoordinatorAsk.findPark(store, other.id())).isNull();

            // the decision whose text IS the park reason is the one asked about, not the newest
            CoordinatorAsk.Park park = CoordinatorAsk.findPark(store, runId);
            assertThat(park.decision().id()).isEqualTo(ruleQuestion);
            assertThat(park.alsoPending()).extracting(Decision::id).containsExactly(blocked);

            CoordinatorAsk ask = ask(5_000, 5);
            AtomicReference<UUID> resumed = new AtomicReference<>();
            ask.bind(new ControlServiceImpl(), id -> {
                resumed.set(id);
                run.setParkedAt(null); // what the engine does first when it takes a parked run
            }, work, work.resolve("no-checkouts"));
            Thread answerer = answerLater(1, "{\"answer\":\"reword\",\"text\":\"bundles unless the "
                + "library cannot\"}");

            CoordinatorAsk.Handled handled = ask.handle(store, runId, park);
            answerer.join();

            assertThat(handled.carryOn()).isTrue();
            assertThat(resumed.get()).isEqualTo(runId);
            Decision after = store.root().decisions.get(ruleQuestion);
            assertThat(after.state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(after.humanResponse()).isEqualTo("reword: bundles unless the library cannot");
            JsonNode question = JSON.readTree(work.resolve("ask/question-1.json").toFile());
            assertThat(question.get("decisionKind").asText()).isEqualTo("GUIDELINE_REVIEW");
            assertThat(question.get("workflowState").asText()).isEqualTo("EXECUTING");
            assertThat(question.get("alsoPending")).hasSize(1);
            List<String> tokens = new ArrayList<>(question.get("options").findValuesAsText("token"));
            assertThat(tokens).containsExactly("keep", "reword", "allow", "repair", "stop");
        }
    }

    @Test
    void stopLeavesTheDecisionPendingAndTheRunParked() throws Exception {
        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), id -> { }, id -> { }));
            UUID runId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.TEST_AUTHORING, null, null,
                null, null, null, Instant.now(), null);
            run.setParkedAt(Instant.now());
            UUID decisionId = UUID.randomUUID();
            store.append(() -> {
                store.root().runs.put(runId, run);
                store.root().decisions.put(decisionId, new Decision(decisionId, runId,
                    DecisionKind.BLOCKED_TASK, "not red", DecisionState.PENDING, null, Instant.now()));
                return null;
            }).get();
            CoordinatorAsk ask = ask(5_000, 5);
            AtomicReference<UUID> resumed = new AtomicReference<>();
            ask.bind(new ControlServiceImpl(), resumed::set, work, work);
            Thread answerer = answerLater(1, "{\"answer\":\"stop\"}");

            CoordinatorAsk.Handled handled = ask.handle(store, runId,
                CoordinatorAsk.findPark(store, runId));
            answerer.join();

            assertThat(handled.carryOn()).isFalse();
            assertThat(handled.note()).contains("stop");
            assertThat(resumed.get()).isNull();
            assertThat(store.root().decisions.get(decisionId).state()).isEqualTo(DecisionState.PENDING);
            assertThat(ask.report()).contains("question 1").contains("answer 1");
        }
    }
}
