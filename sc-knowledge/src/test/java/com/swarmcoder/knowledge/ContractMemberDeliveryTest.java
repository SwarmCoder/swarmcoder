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

import com.swarmcoder.domain.ApiContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 71, 2026-10-02: the architect wrote a contract member as a sentence, and six correct
 * candidates were each failed for "delivered without the member call field is annotated ...".
 * A member that is not a declaration is skipped; an annotation written on a declaration is checked.
 */
class ContractMemberDeliveryTest {

    private static final String TYPE = "com.hambook.Qso";
    /** The members of the run-71 design's contract, word for word. */
    private static final List<String> RUN_71 = List.of(
        "call field is annotated @NotBlank (com.zeroz4j.api.validation.NotBlank) - this is the "
            + "member this story adds",
        "String getCall()", "void setCall(String)", "long getStartUtc()", "void setStartUtc(long)",
        "long getEndUtc()", "void setEndUtc(long)", "long getFreqHz()", "void setFreqHz(long)");

    @TempDir
    Path tree;

    @Test
    void theRun71ContractPassesForACandidateThatAnnotatedTheField() throws Exception {
        write(qso("@NotBlank"));

        assertThat(shortfalls(RUN_71)).isEmpty();
    }

    @Test
    void aProseMemberDoesNotHideARealMissingMember() throws Exception {
        write("""
            package com.hambook;
            public class Qso {
                private String call;
                public String getCall() { return call; }
            }
            """);

        List<ContractDelivery.Shortfall> found = shortfalls(RUN_71);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).missingMembers()).contains("void setCall(String)")
            .noneMatch(m -> m.startsWith("call field is annotated"));
    }

    @Test
    void anAnnotatedFieldPromiseIsMetByTheAnnotatedField() throws Exception {
        write(qso("@NotBlank"));

        assertThat(shortfalls(List.of("@NotBlank String call;"))).isEmpty();
        assertThat(shortfalls(List.of("@com.zeroz4j.api.validation.NotBlank String call;")))
            .isEmpty();
    }

    @Test
    void aMissingAnnotationIsAFailureThatNamesIt() throws Exception {
        write(qso(""));

        List<ContractDelivery.Shortfall> found = shortfalls(List.of("@NotBlank String call;"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render()).contains("`@NotBlank String call;`")
            .contains("does not carry the annotation @NotBlank");
        assertThat(found.get(0).missingMembers()).containsExactly("@NotBlank String call;");
    }

    @Test
    void aMissingFieldWithAnAnnotationIsStillAMissingMember() throws Exception {
        write(qso("@NotBlank"));

        List<ContractDelivery.Shortfall> found = shortfalls(List.of("@NotBlank String callsign;"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render()).contains("delivered without the member");
    }

    @Test
    void anAnnotationWithArgumentsAndAModifierStillParses() {
        ContractMember member = ContractMember.parse(
            "@Size(min = 1, max = 8) private final String call = \"\";").orElseThrow();

        assertThat(member.annotations()).containsExactly("Size");
        assertThat(member.name()).isEqualTo("call");
        assertThat(member.type()).isEqualTo("String");
        assertThat(member.method()).isFalse();
    }

    @Test
    void declarationsOfEveryKindParseAndSentencesDoNot() {
        for (String ok : List.of("int rating", "public String id;", "RED", "String label()",
                "static ReadingStatus fromLabel(String label)", "Book(String title)",
                "List<Map<String, Integer>> counts()", "void setCall(String)",
                "<T> T first(List<T> items) throws IOException", "String[] names",
                "void add(final String... names)")) {
            assertThat(ContractMember.isDeclaration(ok)).as(ok).isTrue();
        }
        for (String prose : List.of(RUN_71.get(0), "the call must not be blank",
                "String getCall(); void setCall(String)", "see above", "")) {
            assertThat(ContractMember.isDeclaration(prose)).as(prose).isFalse();
        }
    }

    private static String qso(String annotation) {
        return """
            package com.hambook;
            import com.zeroz4j.api.validation.NotBlank;
            public class Qso {
                %s private String call;
                private long startUtc;
                private long endUtc;
                private long freqHz;
                public String getCall() { return call; }
                public void setCall(String call) { this.call = call; }
                public long getStartUtc() { return startUtc; }
                public void setStartUtc(long v) { this.startUtc = v; }
                public long getEndUtc() { return endUtc; }
                public void setEndUtc(long v) { this.endUtc = v; }
                public long getFreqHz() { return freqHz; }
                public void setFreqHz(long v) { this.freqHz = v; }
            }
            """.formatted(annotation);
    }

    private void write(String source) throws Exception {
        Path file = tree.resolve("src/main/java/com/hambook/Qso.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private List<ContractDelivery.Shortfall> shortfalls(List<String> members) {
        ApiContract contract = new ApiContract(UUID.randomUUID(), "Qso", "a contact", "Qso", TYPE,
            members);
        return ContractDelivery.shortfalls(tree, List.of(contract));
    }
}
