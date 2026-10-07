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
 * Harness run 77, 2026-10-03: the architect wrote the type's own annotation as the first member,
 * {@code "@com.zeroz4j.api.DataModel LogbookSort;"}. It was read as a member named LogbookSort,
 * matched the constructor, and every candidate was failed for the constructor not carrying it.
 */
class ATypeAnnotationInAContractIsNotAMemberTest {

    private static final String ENTRY = "@com.zeroz4j.api.DataModel LogbookSort;";
    private static final List<String> RUN_77 = List.of(ENTRY, "LogbookSort()",
        "LogbookSort(LogbookColumn column, boolean ascending)", "LogbookColumn getColumn()");

    @TempDir
    Path tree;

    @Test
    void theEntryIsAStatementAboutTheType() {
        assertThat(ContractMember.typeAnnotations(ENTRY, "LogbookSort"))
            .containsExactly("com.zeroz4j.api.DataModel");
        assertThat(ContractMember.typeAnnotations("@DataModel public class LogbookSort",
            "LogbookSort")).containsExactly("DataModel");
        assertThat(ContractMember.parse(ENTRY, "LogbookSort")).isEmpty();
        // a field of another name, or a constructor with parameters, is a member
        assertThat(ContractMember.typeAnnotations("@NotBlank String call;", "LogbookSort")).isEmpty();
        assertThat(ContractMember.typeAnnotations("@A LogbookSort()", "LogbookSort")).isEmpty();
    }

    @Test
    void aCandidateWithTheAnnotatedClassPasses() throws Exception {
        write("@DataModel", true);

        assertThat(shortfalls()).isEmpty();
    }

    @Test
    void aCandidateWithoutTheAnnotationFailsOnTheTypeNotTheConstructor() throws Exception {
        write("", true);

        List<ContractDelivery.Shortfall> found = shortfalls();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).render()).contains("the type does not carry @com.zeroz4j.api.DataModel")
            .doesNotContain("is declared but does not carry");
    }

    private void write(String annotation, boolean constructors) throws Exception {
        String ctors = constructors ? """
            public LogbookSort() {}
            public LogbookSort(LogbookColumn column, boolean ascending) {}
            """ : "";
        Path file = tree.resolve("src/main/java/com/hambook/LogbookSort.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.hambook;
            import com.zeroz4j.api.DataModel;
            %s
            public class LogbookSort {
                %s
                public LogbookColumn getColumn() { return null; }
            }
            """.formatted(annotation, ctors));
    }

    private List<ContractDelivery.Shortfall> shortfalls() {
        List<String> members = RUN_77;
        ApiContract contract = new ApiContract(UUID.randomUUID(), "LogbookSort", "a sort",
            "LogbookSort", "com.hambook.LogbookSort", members);
        return ContractDelivery.shortfalls(tree, List.of(contract));
    }
}
