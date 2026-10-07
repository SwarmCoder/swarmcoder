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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for harness runs 41 and 42 (2026-09-26): DeepSeek V4 Flash, revising a design
 * against review objections, twice replied with a JSON object that parsed cleanly but never
 * mentioned {@code requirements} or {@code contracts} at all — the model has no structured-output
 * support on its endpoint (ds4 refuses {@code response_format json_object}, so this JSON shape is
 * enforced by the prompt alone) and reasons at length before answering, and it reasoned its way to
 * "the objections were about the contract, so I will just send that" rather than restating the
 * whole design as asked. {@link ArchitectClient#discardReason} correctly saw a design that said
 * less than the one it was meant to fix and refused it outright — the SAME correct behaviour
 * {@link ArchitectReviseNeverReplacesWithLessTest} pins — but with no other signal to go on, a
 * model that could easily have been reminded what it left out never got the chance, and design
 * review could never improve a design on this model.
 *
 * <p>{@link ArchitectClient#revise} now re-asks once, naming exactly what came back missing,
 * before discarding — but ONLY when every dropped section was left out of the reply's JSON
 * entirely ({@code null} on the wire, because {@link com.swarmcoder.inference.LlmJson} leaves a
 * field {@code null} when its key was never in the reply). A model that explicitly states a
 * section is now empty ({@code "contracts": []}) is making a deliberate claim, not omitting one,
 * and is still refused immediately with no re-ask at all — see
 * {@link ArchitectReviseNeverReplacesWithLessTest} and {@link DesignReviewDiscardsAnEmptyRevisionTest}
 * for that half, which this class does not repeat.
 */
class ArchitectRevisionReAsksBeforeDiscardingTest {

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    private static DesignDocument design(int requirementCount, int contractCount) {
        List<Requirement> requirements = new ArrayList<>();
        for (int i = 0; i < requirementCount; i++) {
            requirements.add(new Requirement(UUID.randomUUID(), "Requirement " + i, Priority.HIGH));
        }
        List<ApiContract> contracts = new ArrayList<>();
        for (int i = 0; i < contractCount; i++) {
            contracts.add(new ApiContract(UUID.randomUUID(), "Contract" + i, "desc", "",
                "com.acme.demo.Type" + i, List.of("int field")));
        }
        return new DesignDocument(UUID.randomUUID(), 1, "Guest can pay", requirements,
            new ArrayList<>(), contracts, new ArrayList<>(), null, Instant.now());
    }

    /** A reply whose JSON never mentions requirements or contracts at all — both absent, not emptied. */
    private static final String FORGOT_REQUIREMENTS_AND_CONTRACTS = """
            {"decisions":[{"decision":"keep the existing decisions","rationale":"unaffected"}],
             "risks":[]}
            """;

    private static final String COMPLETE_REVISION = """
            {"requirements":[{"text":"Requirement 0","priority":"HIGH"}],
             "decisions":[{"decision":"Use a server-side cart","rationale":"the rule requires it"}],
             "contracts":[{"name":"Contract0","description":"desc","signature":"",
               "type":"com.acme.demo.Type0","members":["int field"]}],
             "risks":[]}
            """;

    @Test
    void aReplyThatOmitsSectionsEntirelyIsMergedAfterOneReAsk() throws Exception {
        DesignDocument original = design(1, 1);
        AtomicInteger calls = new AtomicInteger();
        List<String> conversations = new ArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                conversations.add(conversation);
                int call = calls.incrementAndGet();
                return call == 1 ? FORGOT_REQUIREMENTS_AND_CONTRACTS : COMPLETE_REVISION;
            })) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("tighten the contract"));

            assertThat(attempt.discarded())
                .as("the second, complete reply replaces the design — it was never really lost")
                .isFalse();
            assertThat(attempt.design().requirements()).hasSize(1);
            assertThat(attempt.design().contracts()).hasSize(1);
            assertThat(attempt.design().contracts().get(0).typeName()).isEqualTo("com.acme.demo.Type0");
            assertThat(calls.get())
                .as("one initial attempt, then exactly one re-ask — never a whole new retry loop")
                .isEqualTo(2);
            assertThat(conversations.get(1))
                .as("the re-ask names what came back missing rather than repeating the same ask blind")
                .contains("Requirement 0")
                .contains("Contract0");
        }
    }

    @Test
    void aReplyThatKeepsOmittingSectionsIsDiscardedAfterOneReAsk() throws Exception {
        DesignDocument original = design(1, 1);
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                calls.incrementAndGet();
                return FORGOT_REQUIREMENTS_AND_CONTRACTS;
            })) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("tighten the contract"));

            assertThat(attempt.discarded())
                .as("a model that forgets the same section twice running is still refused — the "
                    + "re-ask is one extra chance, not an unbounded retry")
                .isTrue();
            assertThat(attempt.discardReason())
                .isEqualTo("revision discarded: it dropped 1 requirement(s) and all contracts");
            assertThat(attempt.design()).isSameAs(original);
            assertThat(calls.get())
                .as("exactly two model calls: the original attempt and the one re-ask")
                .isEqualTo(2);
        }
    }

    @Test
    void aDeliberatelyEmptiedSectionIsNeverReAsked() throws Exception {
        DesignDocument original = design(1, 1);
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                calls.incrementAndGet();
                // requirements omitted (forgotten), but contracts is explicitly emptied — a stated
                // removal, which must poison the whole revision back to an immediate discard.
                return "{\"decisions\":[],\"contracts\":[],\"risks\":[]}";
            })) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("tighten the contract"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason())
                .isEqualTo("revision discarded: it dropped 1 requirement(s) and all contracts");
            assertThat(calls.get())
                .as("a section the model explicitly emptied is a deliberate drop — no re-ask spent on it")
                .isEqualTo(1);
        }
    }

    @Test
    void aGenuineCompleteRevisionOnTheFirstTryIsAcceptedWithNoReAsk() throws Exception {
        DesignDocument original = design(1, 0);
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                calls.incrementAndGet();
                return COMPLETE_REVISION;
            })) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs a contract"));

            assertThat(attempt.discarded()).isFalse();
            assertThat(attempt.design().contracts()).hasSize(1);
            assertThat(calls.get())
                .as("a revision that already carries everything the original had costs one call")
                .isEqualTo(1);
        }
    }
}
