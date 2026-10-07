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

import com.swarmcoder.testsupport.LocalCheckouts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What the structural index costs and what it can answer on a real framework checkout.
 *
 * <p>The synthetic fixture in {@link TheSemanticIndexAnswersStructurallyTest} proves the queries
 * are right. This one proves they are AFFORDABLE, on the 681-file checkout the whole knowledge
 * layer is calibrated against, and prints the two numbers a person deciding whether to keep this
 * needs: how long the first build takes and how much of it stays on disk.
 *
 * <p>Skipped loudly when {@code C:/work/zeroz4j} is not on this machine. No model is called.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheSemanticIndexOnTheRealCheckoutTest {

    private static final Path REFERENCE = LocalCheckouts.find("zeroz4j");

    private static SemanticIndex index;
    private static long coldMillis;
    private static long warmMillis;

    @BeforeAll
    void buildIt() throws Exception {
        assumeThat(Files.isDirectory(REFERENCE))
            .as("the real reference checkout is not on this machine, so its cost is not measured")
            .isTrue();
        // A cache of this test's own, so what is measured is a build and not somebody else's.
        Path cache = Paths.get(System.getProperty("java.io.tmpdir"), "semantic-index-measurement");
        deleteTree(cache);

        List<KnowledgeCurator.Root> roots =
            List.of(new KnowledgeCurator.Root("zeroz4j", REFERENCE, "local"));

        long started = System.nanoTime();
        index = SemanticIndex.over(roots, cache);
        coldMillis = (System.nanoTime() - started) / 1_000_000;

        started = System.nanoTime();
        SemanticIndex.over(roots, cache);
        warmMillis = (System.nanoTime() - started) / 1_000_000;
    }

    @AfterAll
    void sayWhatItCost() {
        if (index == null) {
            return;
        }
        SemanticIndex.Stats stats = index.stats();
        System.out.println();
        System.out.println("=== the semantic index on " + REFERENCE + " ===");
        System.out.printf("  first build (parse + write)  %,d ms%n", coldMillis);
        System.out.printf("  reopened from disk           %,d ms%n", warmMillis);
        System.out.printf("  on disk                      %,d KB (gzipped JSON)%n",
            stats.bytesOnDisk() / 1024);
        System.out.printf("  java files                   %,d parsed of %,d found%n",
            stats.parsedFiles(), stats.javaFiles());
        System.out.printf("  types                        %,d%n", stats.types());
        System.out.printf("  resolved type uses           %,d%n", stats.uses());
        System.out.printf("  resolved calls               %,d%n", stats.calls());
        System.out.printf("  declared dependencies        %,d%n", stats.declaredDependencies());
        System.out.println("  degraded: " + (index.degradedReason().isEmpty()
            ? "no, every root parsed whole" : index.degradedReason()));
        System.out.println("=== end ===");
    }

    @Test
    void itParsesTheWholeCheckoutAndKeepsSomethingSmall() {
        assertThat(index.available()).as(index.unavailableReason()).isTrue();
        assertThat(index.stats().parsedFiles())
            .as("nearly every Java file under the checkout")
            .isGreaterThan((int) (index.stats().javaFiles() * 0.9));
        assertThat(index.stats().bytesOnDisk())
            .as("relations, not a serialised tree — tens of megabytes would be the wrong design")
            .isLessThan(64L * 1024 * 1024);
    }

    @Test
    void reopeningItIsNotABuild() {
        assertThat(warmMillis)
            .as("the whole point of persisting it: %d ms cold, %d ms warm", coldMillis, warmMillis)
            .isLessThan(Math.max(2_000L, coldMillis / 4));
    }

    @Test
    void itKnowsWhatImplementsTheFrameworksOwnStoreInterface() {
        List<SemanticIndex.Ref> found = index.implementationsOf("DataRootProvider");

        assertThat(found).as("every example server in the checkout provides one")
            .isNotEmpty();
        assertThat(found).allSatisfy(ref -> {
            assertThat(ref.file()).endsWith(".java");
            assertThat(ref.line()).isPositive();
        });
    }

    /**
     * The precision this whole index exists for, stated as a test.
     *
     * <p>{@code RmiService} is an ANNOTATION in this framework, not an interface, so "what
     * implements it" is genuinely nothing and "what carries it" is the real question. A text
     * search cannot tell those two apart; this does, and answering the wrong one with an empty
     * list is the correct behaviour rather than a miss.
     */
    @Test
    void anAnnotationHasCarriersAndNotImplementations() {
        assertThat(index.implementationsOf("RmiService"))
            .as("nothing implements an annotation")
            .isEmpty();
        assertThat(index.typesAnnotatedWith("RmiService"))
            .as("the service contracts the annotation processor generates stubs for")
            .isNotEmpty();
    }

    @Test
    void whatCouldNotBeParsedIsNamedRatherThanHidden() {
        // The archetype ships template sources with ${placeholder} in them. They are not Java and
        // never will be; the index says how many it skipped instead of pretending it read them.
        assertThat(index.stats().parsedFiles()).isLessThanOrEqualTo(index.stats().javaFiles());
        if (index.stats().parsedFiles() < index.stats().javaFiles()) {
            assertThat(index.degradedReason()).contains("would not parse");
        }
    }

    @Test
    void itKnowsWhereAFrameworkTypeIsUsed() {
        assertThat(index.usagesOf("ZeroZDbNode")).isNotEmpty();
    }

    @Test
    void itKnowsWhatTheServerDiscoversByAnnotation() {
        assertThat(index.typesAnnotatedWith("ApplicationScoped"))
            .as("CDI discovery is by annotation, and the index answers it as a fact")
            .isNotEmpty();
    }

    @Test
    void itKnowsWhichBuildFileDeclaresAnArtifact() {
        List<SemanticIndex.Ref> found = index.dependencyDeclaring("zerozstack-store-eclipsestore");

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).file()).endsWith("pom.xml");
        assertThat(found.get(0).line()).isPositive();
    }

    @Test
    void itCanIntersectTwoTypesAcrossTheWholeCheckout() {
        List<String> both = index.filesUsingAll(List.of("java.util.List", "java.util.Map"));

        assertThat(both).as("plenty of files use both").isNotEmpty();
        assertThat(both).allSatisfy(file ->
            assertThat(index.typesUsedIn(file)).contains("java.util.List", "java.util.Map"));
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var tree = Files.walk(dir)) {
            tree.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {                              // noqa
                    // a leftover cache is a slower measurement, not a failure
                }
            });
        }
    }
}
