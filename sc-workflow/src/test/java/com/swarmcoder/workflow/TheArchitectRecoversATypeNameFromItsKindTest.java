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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for harness run 46 (2026-09-27): a live design came back with EVERY contract's
 * {@code "type"} set to {@code "class"} — the model answered the field with the KIND of the type
 * rather than its fully-qualified NAME (the prompt asks for {@code "type":"com.acme.shop.Rating"}).
 * All four contracts then shared the same {@code typeName} ("class"), so
 * {@code TaskGraphValidator.checkEveryContractIsDelivered} reported every one of them as "claimed
 * by 4 tasks" and the run was doomed before a single task ran.
 *
 * <p>{@code ArchitectClient.toContracts} now recognises a bare type-kind keyword in the wire
 * {@code "type"} field and recovers the real name from {@code "name"} or from a kind-then-name
 * pattern in {@code "signature"} (e.g. "public record com.x.Book(...)"), rather than storing the
 * kind word as the type's name.
 */
class TheArchitectRecoversATypeNameFromItsKindTest {

    @Test
    void aKindWithAQualifiedNameFieldUsesThatName() throws Exception {
        DesignDocument design = designFrom("""
            {"requirements":[],"decisions":[],
             "contracts":[{"name":"com.x.Book","description":"a book","signature":"",
               "type":"class","members":["String title"]}],
             "risks":[]}
            """);

        assertThat(design.contracts()).hasSize(1);
        assertThat(design.contracts().get(0).typeName()).isEqualTo("com.x.Book");
    }

    @Test
    void aKindWithOnlyASimpleNameRecoversTheQualifiedNameFromTheSignature() throws Exception {
        DesignDocument design = designFrom("""
            {"requirements":[],"decisions":[],
             "contracts":[{"name":"Book","description":"a book",
               "signature":"public record com.x.Book(String title, int year)",
               "type":"record","members":["String title"]}],
             "risks":[]}
            """);

        assertThat(design.contracts()).hasSize(1);
        assertThat(design.contracts().get(0).typeName()).isEqualTo("com.x.Book");
    }

    @Test
    void anOrdinaryQualifiedTypeIsUnchanged() throws Exception {
        DesignDocument design = designFrom("""
            {"requirements":[],"decisions":[],
             "contracts":[{"name":"Book","description":"a book","signature":"",
               "type":"com.x.Book","members":["String title"]}],
             "risks":[]}
            """);

        assertThat(design.contracts()).hasSize(1);
        assertThat(design.contracts().get(0).typeName()).isEqualTo("com.x.Book");
    }

    @Test
    void aKindWithNoRecoverableNameLeavesTheContractNamingNoType() throws Exception {
        DesignDocument design = designFrom("""
            {"requirements":[],"decisions":[],
             "contracts":[{"name":"","description":"a book","signature":"",
               "type":"class","members":["String title"]}],
             "risks":[]}
            """);

        assertThat(design.contracts()).hasSize(1);
        assertThat(design.contracts().get(0).namesAType())
            .as("no name could be honestly recovered, so this must not name a fake type called "
                + "\"class\"")
            .isFalse();
    }

    private static DesignDocument designFrom(String reply) throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> reply)) {
            return architect(llm).design("Track a bookshelf");
        }
    }

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }
}
