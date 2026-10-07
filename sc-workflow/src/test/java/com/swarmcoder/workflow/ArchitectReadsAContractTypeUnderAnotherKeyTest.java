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
import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.Risk;
import com.swarmcoder.domain.Severity;
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
 * Pins the fix for harness run 59 (2026-10-01): with 45 standing rules in the prompt, the
 * architect's design came back with contracts but none carried a "type", and both revision replies
 * were discarded for leaving requirements and contracts out. A model with no structured-output
 * support names things as it likes, so the wire is read leniently: the type under another key,
 * the contract list under another key, and a revision that says only the changed section keeps the
 * rest of the design.
 */
class ArchitectReadsAContractTypeUnderAnotherKeyTest {

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    private static DesignDocument original() {
        return new DesignDocument(UUID.randomUUID(), 1, "Guest can pay",
            new ArrayList<>(List.of(new Requirement(UUID.randomUUID(), "Pay by card", Priority.HIGH))),
            new ArrayList<>(List.of(new ArchDecision(UUID.randomUUID(), "Server cart", "rule", List.of()))),
            new ArrayList<>(List.of(new ApiContract(UUID.randomUUID(), "Cart", "the cart", "", null, List.of()))),
            new ArrayList<>(List.of(new Risk(UUID.randomUUID(), "slow", Severity.LOW, "cache"))),
            null, Instant.now());
    }

    @Test
    void theTypeIsFoundUnderTheOtherKeysAModelUses() throws Exception {
        String reply = """
            {"requirements":[{"text":"Pay by card","priority":"HIGH"}],
             "decisions":[],
             "contracts":[
               {"name":"A","description":"d","signature":"s","typeName":"com.acme.shop.Cart"},
               {"name":"B","description":"d","signature":"s","fullyQualifiedName":"com.acme.shop.Order"},
               {"name":"C","description":"d","signature":"s","className":"com.acme.shop.Rating"},
               {"name":"D","description":"d","signature":"s","type":"class","fqn":"com.acme.shop.Item"},
               {"name":"E","description":"d","signature":"s","interface":"com.acme.shop.Pricing"}],
             "risks":[]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            DesignDocument design = architect(llm).design("Guest can pay");

            assertThat(design.contracts()).extracting(ApiContract::typeName).containsExactly(
                "com.acme.shop.Cart", "com.acme.shop.Order", "com.acme.shop.Rating",
                "com.acme.shop.Item", "com.acme.shop.Pricing");
        }
    }

    @Test
    void theContractListIsFoundUnderTypesOrInterfaces() throws Exception {
        String reply = """
            {"requirements":[{"text":"Pay by card","priority":"HIGH"}],"decisions":[],
             "types":[{"name":"Cart","type":"com.acme.shop.Cart","members":["int total()"]}],
             "risks":[]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            DesignDocument design = architect(llm).design("Guest can pay");

            assertThat(design.contracts()).hasSize(1);
            assertThat(design.contracts().get(0).typeName()).isEqualTo("com.acme.shop.Cart");
        }
    }

    @Test
    void aTypelessContractIsTypedFromItsNameOnlyWhenTheNameLooksLikeAType() throws Exception {
        String reply = """
            {"requirements":[],"decisions":[],
             "contracts":[
               {"name":"com.acme.shop.Cart","description":"d","signature":"s"},
               {"name":"add an item to the cart","description":"d","signature":"s"},
               {"name":"add","description":"d","signature":"s"},
               {"name":"Calculator.multiply","description":"product","signature":"int multiply(int,int)"}],
             "risks":[]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            DesignDocument design = architect(llm).design("Guest can pay");

            assertThat(design.contracts().get(0).typeName()).isEqualTo("com.acme.shop.Cart");
            assertThat(design.contracts().get(1).namesAType()).isFalse();
            assertThat(design.contracts().get(2).namesAType()).isFalse();
            assertThat(design.contracts().get(3).namesAType())
                .as("Type.method is a method of a type, not a type").isFalse();
        }
    }

    @Test
    void aRevisionThatSendsOnlyContractsKeepsTheOtherSectionsOfTheOriginal() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String reply = """
            {"contracts":[{"name":"Cart","description":"the cart","signature":"",
               "type":"com.acme.shop.Cart","members":["int total()"]}]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                calls.incrementAndGet();
                return reply;
            })) {
            DesignDocument original = original();

            ArchitectClient.ReviseAttempt attempt = architect(llm).revise(original, List.of("name the types"));

            assertThat(attempt.discarded()).isFalse();
            assertThat(calls.get()).as("no re-ask is needed").isEqualTo(1);
            assertThat(attempt.design().contracts().get(0).typeName()).isEqualTo("com.acme.shop.Cart");
            assertThat(attempt.design().requirements()).hasSize(1);
            assertThat(attempt.design().decisions()).hasSize(1);
            assertThat(attempt.design().risks()).hasSize(1);
        }
    }

    @Test
    void aRevisionThatStatesASectionEmptyIsStillADrop() throws Exception {
        String reply = """
            {"requirements":[],
             "contracts":[{"name":"Cart","type":"com.acme.shop.Cart"}]}
            """;
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original(), List.of("name the types"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason()).contains("1 requirement(s)");
        }
    }

    @Test
    void aRawReplyInTheLogIsCappedToItsHeadAndTail() {
        String raw = "H".repeat(9000) + "M".repeat(5000) + "T".repeat(9000);

        String shown = ArchitectClient.cappedHeadAndTail(raw);

        assertThat(shown.length()).isLessThan(12200);
        assertThat(shown).startsWith("HHH").endsWith("TTT").contains("chars elided");
        assertThat(ArchitectClient.cappedHeadAndTail("short")).isEqualTo("short");
    }
}
