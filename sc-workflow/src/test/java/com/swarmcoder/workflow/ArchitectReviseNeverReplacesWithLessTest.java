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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for harness run 21 (2026-09-04): a design carrying 1 requirement and 0 contracts
 * was revised into one with 0 requirements — a revision that said LESS than the design it was
 * meant to fix — and {@link ArchitectClient#revise} handed it straight back as though the
 * architect had genuinely improved the design. {@link GreenfieldWorkflow}'s DESIGN_REVIEW then
 * reviewed the empty rendering as a brand-new problem ("The design section is empty"), and the run
 * reached PLAN with nothing to plan; three PLAN attempts then failed against nothing, each one
 * reporting only "the architect answered unusably".
 *
 * <p>{@link ArchitectClient#revise} now returns an {@link ArchitectClient.ReviseAttempt}: a
 * revision that drops requirements the original had, drops every contract the original had, or
 * never parses at all is discarded and the original design is kept.
 */
class ArchitectReviseNeverReplacesWithLessTest {

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

    @Test
    void aRevisionThatDropsRequirementsAndAllContractsIsDiscarded() throws Exception {
        DesignDocument original = design(1, 1);
        try (ScriptedLlm llm = new ScriptedLlm(conversation ->
                "{\"decisions\":[],\"contracts\":[],\"risks\":[]}")) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("critical conflict"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason())
                .isEqualTo("revision discarded: it dropped 1 requirement(s) and all contracts");
            assertThat(attempt.design())
                .as("the original is kept, unchanged, when the revision is discarded")
                .isSameAs(original);
        }
    }

    @Test
    void aRevisionThatDropsOnlyRequirementsIsDiscardedEvenWithContractsIntact() throws Exception {
        DesignDocument original = design(2, 1);
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"requirements":[{"text":"Requirement 0","priority":"HIGH"}],
                 "decisions":[],
                 "contracts":[{"name":"Contract0","description":"desc","signature":"",
                   "type":"com.acme.demo.Type0","members":["int field"]}],
                 "risks":[]}
                """)) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs work"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason())
                .isEqualTo("revision discarded: it dropped 1 requirement(s)");
            assertThat(attempt.design()).isSameAs(original);
        }
    }

    @Test
    void aRevisionThatDropsAllContractsIsDiscardedEvenWithRequirementsIntact() throws Exception {
        DesignDocument original = design(1, 2);
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"requirements":[{"text":"Requirement 0","priority":"HIGH"}],
                 "decisions":[],"contracts":[],"risks":[]}
                """)) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs work"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason())
                .isEqualTo("revision discarded: it dropped all contracts");
            assertThat(attempt.design()).isSameAs(original);
        }
    }

    @Test
    void aRevisionThatNeverParsesIsDiscarded() throws Exception {
        DesignDocument original = design(1, 0);
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "I would rather not, thanks.")) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs work"));

            assertThat(attempt.discarded()).isTrue();
            assertThat(attempt.discardReason()).isEqualTo("revision discarded: it did not parse");
            assertThat(attempt.design()).isSameAs(original);
        }
    }

    @Test
    void aGenuineImprovementIsAccepted() throws Exception {
        DesignDocument original = design(1, 0);
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"requirements":[{"text":"Requirement 0","priority":"HIGH"}],
                 "decisions":[{"decision":"Use a server-side cart","rationale":"the rule requires it"}],
                 "contracts":[{"name":"CartApi","description":"cart ops","signature":"",
                   "type":"com.acme.shop.Cart","members":["int total"]}],
                 "risks":[]}
                """)) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs a contract"));

            assertThat(attempt.discarded()).isFalse();
            assertThat(attempt.discardReason()).isNull();
            assertThat(attempt.design()).isNotSameAs(original);
            assertThat(attempt.design().requirements()).hasSize(1);
            assertThat(attempt.design().contracts()).hasSize(1);
            assertThat(attempt.design().contracts().get(0).typeName()).isEqualTo("com.acme.shop.Cart");
            // Same id, bumped revision — a revision is the same design evolving.
            assertThat(attempt.design().id()).isEqualTo(original.id());
            assertThat(attempt.design().revision()).isEqualTo(original.revision() + 1);
        }
    }

    @Test
    void anAcceptedRevisionCarriesForwardTheBrdRequirementLinks() throws Exception {
        DesignDocument original = design(1, 0);
        UUID brdRequirementId = original.requirements().get(0).id();
        original.setBrdRequirementIds(List.of(brdRequirementId));
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"requirements":[{"text":"Requirement 0","priority":"HIGH"}],
                 "decisions":[],
                 "contracts":[{"name":"CartApi","description":"cart ops","signature":"",
                   "type":"com.acme.shop.Cart","members":["int total"]}],
                 "risks":[]}
                """)) {

            ArchitectClient.ReviseAttempt attempt =
                architect(llm).revise(original, List.of("needs a contract"));

            assertThat(attempt.discarded()).isFalse();
            assertThat(attempt.design().brdRequirementIds()).containsExactly(brdRequirementId);
        }
    }
}
