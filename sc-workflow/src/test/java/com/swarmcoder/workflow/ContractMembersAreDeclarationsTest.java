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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Harness run 71: a contract member written as a sentence is sent back to the architect. */
class ContractMembersAreDeclarationsTest {

    private static final String PROSE = "call field is annotated @NotBlank "
        + "(com.zeroz4j.api.validation.NotBlank) - this is the member this story adds";

    @Test
    void aProseMemberIsObjectedToWithTheExampleForm() {
        List<String> objections = ContractMembersAreDeclarations.objections(
            design("com.hambook.Qso", PROSE, "String getCall()"));

        assertThat(objections).hasSize(1);
        assertThat(objections.get(0)).contains(PROSE).contains("com.hambook.Qso")
            .contains("@Annotation Type name;");
    }

    @Test
    void declarationsAreNotObjectedTo() {
        assertThat(ContractMembersAreDeclarations.objections(design("com.hambook.Qso",
            "@NotBlank String call;", "String getCall()", "void setCall(String)"))).isEmpty();
        assertThat(ContractMembersAreDeclarations.objections(null)).isEmpty();
    }

    private static DesignDocument design(String type, String... members) {
        ApiContract contract = new ApiContract(UUID.randomUUID(),
            type.substring(type.lastIndexOf('.') + 1), "a contact", "", type, List.of(members));
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(),
            List.of(contract), List.of(), null, Instant.now());
    }
}
