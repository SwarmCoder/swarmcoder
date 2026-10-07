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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 72: the contract {@code com.hambook.Qso_Rules} names a class an annotation processor
 * generates at build time. It is in no hand-written source, and every candidate was failed for
 * "no type of that name exists anywhere in this candidate's tree". A type the build produced -
 * generated source, or a compiled class - is delivered.
 */
class AContractTypeTheBuildMadeIsDeliveredTest {

    private static final String TYPE = "com.hambook.Qso_Rules";

    @TempDir
    Path tree;

    private static ApiContract contract(String... members) {
        return new ApiContract(UUID.randomUUID(), "Qso_Rules", "the generated rules", null, TYPE,
            List.of(members));
    }

    private void write(String relative, String text) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static final String GENERATED = """
        package com.hambook;

        import java.util.List;

        public final class Qso_Rules {
            public List<String> validate(Qso qso) { return List.of(); }
            public FieldRule<String> call() { return null; }
        }
        """;

    @Test
    void aTypeThatOnlyExistsAsGeneratedSourceIsDelivered() throws Exception {
        write("hambook-shared/target/generated-sources/annotations/com/hambook/Qso_Rules.java",
            GENERATED);

        assertThat(ContractDelivery.shortfalls(tree, List.of(
            contract("List<String> validate(Qso qso)", "FieldRule<String> call()")))).isEmpty();
    }

    @Test
    void generatedSourceIsStillComparedMemberByMember() throws Exception {
        write("hambook-shared/target/generated-sources/annotations/com/hambook/Qso_Rules.java",
            GENERATED);

        List<ContractDelivery.Shortfall> shortfalls = ContractDelivery.shortfalls(tree,
            List.of(contract("List<String> validate(Qso qso)", "String render()")));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingType()).isFalse();
        assertThat(shortfalls.get(0).missingMembers()).containsExactly("String render()");
    }

    @Test
    void aTypeThatOnlyExistsAsACompiledClassIsDeliveredAndItsMembersAreRead() throws Exception {
        compileIntoClasses("hambook-shared/target/classes",
            "com/hambook/Qso", "package com.hambook; public class Qso { }",
            "com/hambook/FieldRule", "package com.hambook; public class FieldRule<T> { }",
            "com/hambook/Qso_Rules", GENERATED);

        assertThat(ContractDelivery.shortfalls(tree, List.of(
            contract("List<String> validate(Qso qso)", "FieldRule<String> call()")))).isEmpty();

        List<ContractDelivery.Shortfall> shortfalls = ContractDelivery.shortfalls(tree,
            List.of(contract("String render()")));
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingMembers()).containsExactly("String render()");
    }

    @Test
    void aCompiledClassCarriesItsAnnotationsAndItsGenericMembersAreNotMisjudged() throws Exception {
        compileIntoClasses("target/classes",
            "com/hambook/NotBlank", "package com.hambook; "
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                + "public @interface NotBlank { }",
            "com/hambook/Qso_Rules", "package com.hambook; public class Qso_Rules<T> { "
                + "@NotBlank public String call; "
                + "public T first(java.util.List<T> items) { return null; } }");

        assertThat(ContractDelivery.shortfalls(tree, List.of(
            contract("@NotBlank String call;", "T first(List<T> items)")))).isEmpty();
        assertThat(ContractDelivery.shortfalls(tree, List.of(contract("@Missing String call;"))))
            .hasSize(1);
    }

    @Test
    void aTypeInNeitherSourceNorBuildOutputIsStillNotDelivered() throws Exception {
        // A same-named file that is not in a build output directory proves nothing.
        write("notes/classes/com/hambook/Qso_Rules.class", "not a class file");
        write("target/scratch/com/hambook/Qso_Rules.java", GENERATED);

        List<ContractDelivery.Shortfall> shortfalls =
            ContractDelivery.shortfalls(tree, List.of(contract("String call()")));

        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingType()).isTrue();
    }

    @Test
    void aBuiltTypeWhoseClassFileCannotBeReadCountsAsDelivered() throws Exception {
        write("target/classes/com/hambook/Qso_Rules.class", "not a class file");

        assertThat(ContractDelivery.shortfalls(tree, List.of(contract("String call()"))))
            .as("the type exists; its members could not be compared, which is never a failure")
            .isEmpty();
    }

    /** Pairs of (path without extension, source text). */
    private void compileIntoClasses(String outDir, String... pathAndSource) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("these tests need a JDK").isNotNull();
        Path scratch = Files.createTempDirectory("built-types-src");
        try {
            Path out = tree.resolve(outDir);
            Files.createDirectories(out);
            List<String> args = new ArrayList<>(List.of("-d", out.toString()));
            for (int i = 0; i < pathAndSource.length; i += 2) {
                Path file = scratch.resolve(pathAndSource[i] + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, pathAndSource[i + 1]);
                args.add(file.toString());
            }
            assertThat(compiler.run(null, null, null, args.toArray(new String[0]))).isZero();
        } finally {
            try (var walk = Files.walk(scratch)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
