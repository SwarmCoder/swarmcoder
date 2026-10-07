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
package com.swarmcoder.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A promised member that IS a declaration is read as one however a model wrapped it, and a sentence
 * stays a sentence. Every member read as "not a declaration" goes back to the architect and, after
 * the revisions run out, parks the run — so a declaration misread as prose is a false alarm that
 * can stop a run with nothing wrong in it.
 */
class ContractMemberReadsWhatAModelWritesTest {

    @Test
    void aDeclarationWithARemarkAfterItIsStillThatDeclaration() {
        // harness run 71, word for word
        ContractMember member = ContractMember.parse("Qso addQso(Qso qso) - this story adds: throws "
            + "com.zeroz4j.server.ClientVisibleException(\"End time must be after the start "
            + "time.\") when startUtc!=0 && endUtc!=0 && endUtc<startUtc, before persisting")
            .orElseThrow();

        assertThat(member.method()).isTrue();
        assertThat(member.name()).isEqualTo("addQso");
        assertThat(member.type()).isEqualTo("Qso");
        assertThat(member.paramTypes()).containsExactly("Qso");
    }

    @Test
    void aSentenceWithNoDeclarationInFrontStaysASentence() {
        // harness run 71, word for word: the member that failed six correct candidates
        assertThat(ContractMember.isDeclaration("call field is annotated @NotBlank "
            + "(com.zeroz4j.api.validation.NotBlank) - this is the member this story adds")).isFalse();
        assertThat(ContractMember.isDeclaration("returns the stored contact")).isFalse();
        assertThat(ContractMember.isDeclaration("the service validates the callsign")).isFalse();
    }

    @Test
    void aFieldWithAnInitialValueThatCallsSomethingIsAField() {
        ContractMember member =
            ContractMember.parse("private final List<String> names = new ArrayList<>();").orElseThrow();

        assertThat(member.method()).isFalse();
        assertThat(member.name()).isEqualTo("names");
        assertThat(member.type()).isEqualTo("List<String>");
    }

    @Test
    void anEnumConstantWithItsArgumentsIsThatConstant() {
        ContractMember member = ContractMember.parse("CURRENTLY_READING(\"currently reading\")")
            .orElseThrow();

        assertThat(member.method()).isFalse();
        assertThat(member.name()).isEqualTo("CURRENTLY_READING");
    }

    @Test
    void wrappingsAroundADeclarationAreNotPartOfIt() {
        for (String written : List.of("`String getCall()`", "- String getCall()",
                "String getCall(); // the callsign as logged", "String getCall() /* never null */",
                "String getCall() — the callsign as logged")) {
            ContractMember member = ContractMember.parse(written).orElseThrow();
            assertThat(member.name()).as(written).isEqualTo("getCall");
            assertThat(member.type()).as(written).isEqualTo("String");
        }
    }

    @Test
    void anAnnotationElementWithItsDefaultIsAMethod() {
        ContractMember member = ContractMember.parse("int max() default 10").orElseThrow();

        assertThat(member.method()).isTrue();
        assertThat(member.name()).isEqualTo("max");
    }

    @Test
    void aDashInsideAnAnnotationsArgumentsOrAnInitialValueCutsNothingOff() {
        ContractMember annotated =
            ContractMember.parse("@Size(min = 1, message = \"a - b\") String call;").orElseThrow();
        assertThat(annotated.name()).isEqualTo("call");
        assertThat(annotated.annotations()).containsExactly("Size");

        ContractMember constant = ContractMember.parse("static final int WIDTH = FULL - MARGIN")
            .orElseThrow();
        assertThat(constant.name()).isEqualTo("WIDTH");
        assertThat(constant.type()).isEqualTo("int");
    }
}
