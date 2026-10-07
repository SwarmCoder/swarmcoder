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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case where resemblance and evidence disagree, and evidence wins.
 *
 * <p><b>The fixture is built so that the old selector gets it wrong.</b> Two files in the same
 * module have exactly the same shape — the same two members, the same forms, the same kinds of
 * return type. One of them, {@code CatalogHolder}, shares half its NAME with the contract, and on
 * shape similarity alone that name overlap is the only thing separating them, so it wins. The
 * other, {@code OrderRepository}, carries the framework's marker annotation and uses the
 * framework's session type — which is what the code being written will have to do, because the
 * package it is being written into already has a class that does exactly that.
 *
 * <p>So: shape says {@code CatalogHolder}, structure says {@code OrderRepository}, and after this
 * change the answer is {@code OrderRepository}. Both are asserted, from the same fixture, in the
 * same test, so neither can quietly become the other.
 *
 * <p>No model is called and nothing outside this test's own {@code @TempDir} is written.
 */
class TheNearestExampleIsChosenStructurallyTest {

    @TempDir
    Path world;

    Path project;
    Path reference;
    List<KnowledgeCurator.Root> roots;
    List<WorkedExamples.Shape> shapes;
    SemanticIndex index;

    @BeforeEach
    void buildTheWorld() throws Exception {
        project = world.resolve("acme-app");
        reference = world.resolve("ledgerworks");

        // ---- the framework -------------------------------------------------------------------
        write(reference.resolve("pom.xml"), """
            <project>
              <groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId>
              <version>3.2.0</version><packaging>pom</packaging>
              <modules><module>ledger-core</module><module>ledger-example</module></modules>
            </project>
            """);
        write(reference.resolve("ledger-core/pom.xml"),
            "<project><artifactId>ledger-core</artifactId></project>");
        write(reference.resolve("ledger-example/pom.xml"),
            "<project><artifactId>ledger-example</artifactId></project>");
        write(reference.resolve("ledger-core/src/main/java/com/ledgerworks/core/Repository.java"), """
            package com.ledgerworks.core;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Repository { }
            """);
        write(reference.resolve("ledger-core/src/main/java/com/ledgerworks/core/LedgerSession.java"), """
            package com.ledgerworks.core;

            public class LedgerSession {
                public void append(String entry) { }
            }
            """);

        // ---- two candidates of identical shape, in the same module --------------------------
        //
        // The one the framework would actually find: it carries the marker and uses the session.
        write(reference.resolve(
            "ledger-example/src/main/java/com/ledgerworks/example/OrderRepository.java"), """
            package com.ledgerworks.example;

            import com.ledgerworks.core.LedgerSession;
            import com.ledgerworks.core.Repository;

            @Repository
            public class OrderRepository {

                private final LedgerSession session = new LedgerSession();

                public String id() {
                    return "order";
                }

                public void save(String what) {
                    session.append(what);
                }
            }
            """);
        // The one whose NAME looks like the contract's, and which does none of the framework's work.
        write(reference.resolve(
            "ledger-example/src/main/java/com/ledgerworks/example/CatalogHolder.java"), """
            package com.ledgerworks.example;

            public class CatalogHolder {

                public String id() {
                    return "catalog";
                }

                public void save(String what) {
                }
            }
            """);

        // ---- the target application, with ONE class already in the contract's package --------
        write(project.resolve("pom.xml"), """
            <project><groupId>com.acme</groupId><artifactId>acme-app</artifactId>
            <version>1.0</version></project>
            """);
        write(project.resolve("src/main/java/com/acme/app/store/ExistingStore.java"), """
            package com.acme.app.store;

            import com.ledgerworks.core.Repository;

            @Repository
            public class ExistingStore {
                public String id() { return "existing"; }
            }
            """);

        roots = List.of(new KnowledgeCurator.Root("project", project, "1.0"),
            new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0"));
        shapes = WorkedExamples.shapes(roots);
        index = SemanticIndex.over(roots, world.resolve("cache"));
    }

    /** {@code String id()} and {@code void save(String)} — the shape both candidates have. */
    private ApiContract contract() {
        return new ApiContract(UUID.randomUUID(), "CatalogStore",
            "Where the catalogue is kept.", "",
            "com.acme.app.store.CatalogStore",
            List.of("String id()", "void save(String what)"));
    }

    @Test
    void theIndexIsBuiltOverBothRoots() {
        assertThat(index.available()).as(index.unavailableReason()).isTrue();
        assertThat(index.typesAnnotatedWith("com.ledgerworks.core.Repository"))
            .extracting(SemanticIndex.Ref::file)
            .anyMatch(f -> f.endsWith("OrderRepository.java"))
            .anyMatch(f -> f.endsWith("ExistingStore.java"));
    }

    @Test
    void theEvidenceIsTheMarkerTheContractsOwnPackageAlreadyCarries() {
        Set<String> evidence = WorkedExamples.structuralEvidenceFor(index, contract());

        assertThat(evidence)
            .as("the class already in com.acme.app.store carries @Repository, so a new type "
                + "there will have to as well, and only one candidate does")
            .anyMatch(f -> f.endsWith("OrderRepository.java"))
            .noneMatch(f -> f.endsWith("CatalogHolder.java"));
    }

    @Test
    void shapeSimilarityAloneChoosesTheOneWithTheSimilarName() {
        WorkedExamples.Selection selection =
            WorkedExamples.select(shapes, List.of(contract()), Set.of(), null);

        assertThat(selection.matches()).hasSize(1);
        assertThat(selection.matches().get(0).example().simpleName())
            .as("half of `CatalogStore` is `Catalog`, and with the shapes identical that is the "
                + "only thing left to decide on")
            .isEqualTo("CatalogHolder");
    }

    @Test
    void structuralEvidenceChoosesTheOneThatDoesTheFrameworksWork() {
        WorkedExamples.Selection selection =
            WorkedExamples.select(shapes, List.of(contract()), Set.of(), index);

        assertThat(selection.matches()).hasSize(1);
        assertThat(selection.matches().get(0).example().simpleName())
            .as("it carries the marker the contract's own package requires and uses the "
                + "framework's session; the other does neither")
            .isEqualTo("OrderRepository");
    }

    @Test
    void anUnavailableIndexIsExactlyTheOldBehaviour() {
        WorkedExamples.Selection fallback = WorkedExamples.select(shapes, List.of(contract()),
            Set.of(), SemanticIndex.unavailable("pretend the parser is not there"));
        WorkedExamples.Selection old =
            WorkedExamples.select(shapes, List.of(contract()), Set.of());

        assertThat(fallback.matches().get(0).example().file())
            .isEqualTo(old.matches().get(0).example().file());
        assertThat(fallback.matches().get(0).score())
            .isEqualTo(old.matches().get(0).score());
    }

    // -----------------------------------------------------------------------------------------

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
