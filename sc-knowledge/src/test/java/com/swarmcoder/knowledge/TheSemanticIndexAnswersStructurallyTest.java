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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural index, built on a framework that does not exist, and asked every question it
 * offers.
 *
 * <p><b>Why an invented framework and not the real checkout.</b> The same reason
 * {@link TheNearestExampleOnAnUnrelatedFrameworkTest} invents one: a mechanism developed against
 * one real folder is one edit away from being a set of rules about that folder. Nothing here
 * shares a name with anything the implementation was written against. The real checkout is
 * exercised separately, for cost and scale, by
 * {@link TheSemanticIndexOnTheRealCheckoutTest}.
 *
 * <p><b>What is being proved is that these are FACTS, not resemblances.</b> Two of the assertions
 * below would fail against any text search: {@code CustomerAggregate} implements the framework's
 * interface through an intermediate interface and never names it, and {@code Unrelated} contains
 * the word {@code LedgerSession} in a comment and in a string and uses nothing.
 *
 * <p>No model is called and nothing outside this test's own {@code @TempDir} is written.
 */
class TheSemanticIndexAnswersStructurallyTest {

    @TempDir
    Path world;

    Path reference;
    Path cache;
    SemanticIndex index;

    @BeforeEach
    void buildTheWorld() throws Exception {
        reference = world.resolve("ledgerworks");
        cache = world.resolve("cache");

        write(reference.resolve("pom.xml"), """
            <project>
              <groupId>com.ledgerworks</groupId>
              <artifactId>ledgerworks</artifactId>
              <version>3.2.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>ledger-core</module>
                <module>orders-service</module>
              </modules>
            </project>
            """);

        // ---- the framework module ------------------------------------------------------------
        write(reference.resolve("ledger-core/pom.xml"), """
            <project>
              <parent>
                <groupId>com.ledgerworks</groupId>
                <artifactId>ledgerworks</artifactId>
                <version>3.2.0</version>
              </parent>
              <artifactId>ledger-core</artifactId>
            </project>
            """);
        write(reference.resolve(
            "ledger-core/src/main/java/com/ledgerworks/core/LedgerSession.java"), """
            package com.ledgerworks.core;

            public class LedgerSession {
                public void append(String entry) { }
                public String head() { return ""; }
            }
            """);
        write(reference.resolve(
            "ledger-core/src/main/java/com/ledgerworks/core/Repository.java"), """
            package com.ledgerworks.core;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Repository { }
            """);
        write(reference.resolve(
            "ledger-core/src/main/java/com/ledgerworks/core/Aggregate.java"), """
            package com.ledgerworks.core;

            public interface Aggregate {
                String id();
            }
            """);
        // The intermediate interface: what makes implementationsOf a fact rather than a search.
        write(reference.resolve(
            "ledger-core/src/main/java/com/ledgerworks/core/Snapshotted.java"), """
            package com.ledgerworks.core;

            public interface Snapshotted extends Aggregate {
                String snapshot();
            }
            """);

        // ---- the application module ------------------------------------------------------------
        write(reference.resolve("orders-service/pom.xml"), """
            <project>
              <parent>
                <groupId>com.ledgerworks</groupId>
                <artifactId>ledgerworks</artifactId>
                <version>3.2.0</version>
              </parent>
              <artifactId>orders-service</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId>
                  <artifactId>ledger-core</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        write(reference.resolve(
            "orders-service/src/main/java/com/acme/orders/OrderAggregate.java"), """
            package com.acme.orders;

            import com.ledgerworks.core.Aggregate;
            import com.ledgerworks.core.LedgerSession;
            import com.ledgerworks.core.Repository;

            @Repository
            public class OrderAggregate implements Aggregate {

                private final LedgerSession session = new LedgerSession();

                public String id() {
                    return "order";
                }

                public void save(String what) {
                    session.append(what);
                }
            }
            """);
        // Implements the framework interface through an intermediate and never names it.
        write(reference.resolve(
            "orders-service/src/main/java/com/acme/orders/CustomerAggregate.java"), """
            package com.acme.orders;

            import com.ledgerworks.core.Snapshotted;

            public class CustomerAggregate implements Snapshotted {
                public String id() { return "customer"; }
                public String snapshot() { return "{}"; }
            }
            """);
        // Names the session type twice and uses nothing.
        write(reference.resolve(
            "orders-service/src/main/java/com/acme/orders/Unrelated.java"), """
            package com.acme.orders;

            /** Nothing here goes near a LedgerSession. */
            public class Unrelated {
                public String describe() { return "not a LedgerSession"; }
            }
            """);
        write(reference.resolve(
            "orders-service/src/main/java/com/acme/orders/Facade.java"), """
            package com.acme.orders;

            public class Facade {

                private final OrderAggregate orders = new OrderAggregate();

                public void run() {
                    orders.save("placed");
                }
            }
            """);

        index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0")), cache);
    }

    // -- it builds at all ------------------------------------------------------------------------

    @Test
    void theIndexIsBuiltAndSaysWhatIsInIt() {
        assertThat(index.available()).as(index.unavailableReason()).isTrue();
        assertThat(index.stats().parsedFiles()).isEqualTo(8);
        assertThat(index.stats().types()).isGreaterThanOrEqualTo(8);
        assertThat(index.stats().declaredDependencies()).isEqualTo(1);
    }

    @Test
    void theSecondBuildComesOffDiskAndSaysTheSameThing() {
        SemanticIndex again = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0")), cache);

        assertThat(again.available()).isTrue();
        assertThat(again.stats().types()).isEqualTo(index.stats().types());
        assertThat(again.implementationsOf("com.ledgerworks.core.Aggregate"))
            .isEqualTo(index.implementationsOf("com.ledgerworks.core.Aggregate"));
        assertThat(again.stats().bytesOnDisk()).isPositive();
    }

    // -- the queries -------------------------------------------------------------------------

    @Test
    void implementationsOfFindsTheOneThatNeverNamesTheInterface() {
        List<SemanticIndex.Ref> found = index.implementationsOf("com.ledgerworks.core.Aggregate");

        assertThat(found).extracting(SemanticIndex.Ref::file)
            .anyMatch(f -> f.endsWith("OrderAggregate.java"))
            .as("it implements Snapshotted, which extends Aggregate, and the word Aggregate "
                + "appears nowhere in it")
            .anyMatch(f -> f.endsWith("CustomerAggregate.java"));
        assertThat(found).extracting(SemanticIndex.Ref::file)
            .noneMatch(f -> f.endsWith("Unrelated.java"));
    }

    @Test
    void implementationsOfTakesASimpleNameToo() {
        assertThat(index.implementationsOf("Aggregate")).hasSameSizeAs(
            index.implementationsOf("com.ledgerworks.core.Aggregate"));
    }

    @Test
    void usagesOfATypeSkipsTheFileThatOnlyTalksAboutIt() {
        List<SemanticIndex.Ref> found = index.usagesOf("LedgerSession");

        assertThat(found).isNotEmpty();
        assertThat(found).extracting(SemanticIndex.Ref::file)
            .anyMatch(f -> f.endsWith("OrderAggregate.java"))
            .as("Unrelated says the word twice and uses nothing")
            .noneMatch(f -> f.endsWith("Unrelated.java"));
    }

    @Test
    void usagesOfAMethodGivesTheCallSiteWithItsLine() {
        List<SemanticIndex.Ref> found = index.usagesOf("LedgerSession#append");

        assertThat(found).hasSize(1);
        SemanticIndex.Ref call = found.get(0);
        assertThat(call.file()).endsWith("OrderAggregate.java");
        assertThat(call.line())
            .as("session.append(what) is on line 17 of the file written above")
            .isEqualTo(17);
        assertThat(call.detail()).contains("save").contains("OrderAggregate");
    }

    @Test
    void filesUsingAllIsAnIntersectionAndNotAScore() {
        assertThat(index.filesUsingAll(List.of("com.ledgerworks.core.LedgerSession",
                "com.ledgerworks.core.Aggregate")))
            .hasSize(1)
            .allMatch(f -> f.endsWith("OrderAggregate.java"));

        assertThat(index.filesUsingAll(List.of("com.ledgerworks.core.Aggregate")))
            .as("the interface alone is used by both aggregates")
            .hasSizeGreaterThanOrEqualTo(2);

        assertThat(index.filesUsingAll(List.of("com.ledgerworks.core.LedgerSession",
                "com.ledgerworks.core.Snapshotted")))
            .as("nothing does both, and an empty answer is a real answer")
            .isEmpty();
    }

    @Test
    void typesAnnotatedWithFindsTheOneCarryingIt() {
        List<SemanticIndex.Ref> found = index.typesAnnotatedWith("com.ledgerworks.core.Repository");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).file()).endsWith("OrderAggregate.java");
        assertThat(found.get(0).line())
            .as("the annotated class declaration starts on line 7")
            .isEqualTo(7);
    }

    @Test
    void callChainReachesTheFrameworkTypeThroughTheApplicationsOwnMethod() {
        List<SemanticIndex.Ref> chain =
            index.callChain("Facade#run", "com.ledgerworks.core.LedgerSession", 4);

        assertThat(chain).as("run() calls OrderAggregate.save(), which calls LedgerSession.append()")
            .hasSize(2);
        assertThat(chain.get(0).detail()).contains("OrderAggregate#save");
        assertThat(chain.get(1).detail()).contains("LedgerSession#append");
    }

    @Test
    void callChainSaysNothingWhenThereIsNoChain() {
        assertThat(index.callChain("Unrelated#describe", "com.ledgerworks.core.LedgerSession", 4))
            .isEmpty();
    }

    @Test
    void publicShapeIsResolvedSignaturesAndNotTheTextOfTheFile() {
        String shape = index.publicShape("OrderAggregate");

        assertThat(shape)
            .contains("com.acme.orders.OrderAggregate")
            .as("the interface it implements, resolved")
            .contains("com.ledgerworks.core.Aggregate")
            .as("the annotation, with the package the compiler chose")
            .contains("com.ledgerworks.core.Repository")
            .as("the return type is fully qualified even though the source says String")
            .contains("java.lang.String id()")
            .contains("save(java.lang.String)")
            .contains("OrderAggregate.java:");
    }

    @Test
    void dependencyDeclaringNamesTheBuildFileAndTheLine() {
        List<SemanticIndex.Ref> found = index.dependencyDeclaring("ledger-core");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).file()).endsWith("orders-service/pom.xml");
        assertThat(found.get(0).line())
            .as("the <dependency> element starts on line 9 of the pom written above")
            .isEqualTo(9);
        assertThat(found.get(0).detail()).contains("declares").contains("com.ledgerworks");
    }

    @Test
    void namesKnownInAQuestionAreOnlyTheOnesTheIndexActuallyHolds() {
        assertThat(index.namesKnownIn("how do I use LedgerSession with a Frobnicator"))
            .containsExactly("LedgerSession");
        assertThat(index.artifactsNamedIn("what is ledger-core for")).containsExactly("ledger-core");
    }

    // -- failing open --------------------------------------------------------------------------

    @Test
    void aRootThatIsNotThereLeavesTheIndexUnavailableAndSaysWhy() {
        SemanticIndex missing = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("gone", world.resolve("nowhere"), "x")),
            world.resolve("cache2"));

        assertThat(missing.available()).isFalse();
        assertThat(missing.unavailableReason()).isNotBlank();
        assertThat(missing.implementationsOf("Anything")).isEmpty();
        assertThat(missing.usagesOf("Anything")).isEmpty();
        assertThat(missing.filesUsingAll(List.of("Anything"))).isEmpty();
        assertThat(missing.typesAnnotatedWith("Anything")).isEmpty();
        assertThat(missing.callChain("A#b", "C", 3)).isEmpty();
        assertThat(missing.publicShape("Anything")).isEmpty();
        assertThat(missing.dependencyDeclaring("anything")).isEmpty();
    }

    @Test
    void noRootsAtAllIsUnavailableRatherThanAnError() {
        SemanticIndex none = SemanticIndex.over(List.of(), world.resolve("cache3"));

        assertThat(none.available()).isFalse();
        assertThat(none.unavailableReason()).contains("no reference roots");
    }

    @Test
    void aFileThatWillNotParseIsSkippedAndTheRestOfTheRootStillAnswers() throws Exception {
        Path broken = world.resolve("half-broken");
        write(broken.resolve("pom.xml"),
            "<project><groupId>x</groupId><artifactId>half</artifactId>"
                + "<version>1</version></project>");
        write(broken.resolve("src/main/java/x/Good.java"), """
            package x;
            public class Good { public String hello() { return "hi"; } }
            """);
        write(broken.resolve("src/main/java/x/Broken.java"),
            "package x; public class Broken { this is not java at all ((( }");

        SemanticIndex partial = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("half", broken, "1")), world.resolve("cache4"));

        assertThat(partial.available()).as("the good file still answers").isTrue();
        assertThat(partial.publicShape("Good")).contains("x.Good").contains("hello()");
    }

    @Test
    void theSwitchTurnsItOffCompletely() {
        System.setProperty("swarmcoder.semanticIndex.off", "true");
        try {
            SemanticIndex off = SemanticIndex.over(
                List.of(new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0")),
                world.resolve("cache5"));

            assertThat(off.available()).isFalse();
            assertThat(off.unavailableReason()).contains("switched off");
        } finally {
            System.clearProperty("swarmcoder.semanticIndex.off");
        }
    }

    // -----------------------------------------------------------------------------------------

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
