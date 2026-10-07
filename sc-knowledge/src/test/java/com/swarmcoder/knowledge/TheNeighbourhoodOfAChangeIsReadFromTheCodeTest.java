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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bug report against a library that does not exist, answered out of that library's own code.
 *
 * <p><b>Why an invented library.</b> The same reason {@code TheSemanticIndexAnswersStructurallyTest}
 * invents one: a mechanism developed against one real checkout is one edit away from being a set of
 * rules about that checkout. Nothing here shares a name with anything the implementation was written
 * against. The five real jsoup change requests are exercised separately, at their real cost, by
 * {@code BrownfieldUnderstandingTest} in {@code sc-app}.
 *
 * <p><b>The assertion that matters is the ordering one.</b> Ranking "the tests that already cover
 * this" by how many of the named types a test file uses puts a project's integration tests top of
 * every list, because they import everything on their way to doing something else. Here
 * {@code DeliveryIT} uses more of the named types than {@code RouteParserTest} does, and
 * {@code RouteParserTest} must still come first — because it is named after one of them and sits in
 * the same package, which is what "the test for this type" means in every JVM project.
 *
 * <p>No model is called and nothing outside this test's own {@code @TempDir} is written.
 */
class TheNeighbourhoodOfAChangeIsReadFromTheCodeTest {

    /**
     * The report, as somebody would write it: an expression that goes in, what comes out, and what
     * should. It names one type outright, calls two methods, and mentions a type the library does
     * not have — all three of which the neighbourhood has to handle differently.
     */
    private static final String ISSUE = """
        Quoted labels with a space in them are never matched

        ```
        RouteParser.parse("via \\"two words\\"");
        Parcel found = depot.firstMatching(expression);
        System.out.println(found.label());   // expected "two words", got ""
        ```

        I also tried going through HttpFetcher and it made no difference. On 2.1.0 this worked.
        Sometimes it throws an IndexOutOfBoundsException instead of returning an empty String.
        """;

    @TempDir
    static Path world;

    private static SemanticIndex index;
    private static ChangeNeighbourhood.Neighbourhood found;

    @BeforeAll
    static void buildTheLibraryAndReadTheReport() throws Exception {
        Path repo = world.resolve("parcelworks");

        write(repo.resolve("pom.xml"), """
            <project>
              <groupId>com.parcelworks</groupId>
              <artifactId>parcelworks</artifactId>
              <version>2.2.0</version>
            </project>
            """);

        write(repo.resolve("src/main/java/com/parcelworks/route/RouteParser.java"), """
            package com.parcelworks.route;

            import com.parcelworks.nodes.Parcel;

            public class RouteParser {
                public static RouteMatcher parse(String expression) {
                    return new PrefixMatcher(expression);
                }
                public Parcel firstMatching(String expression) {
                    return null;
                }
            }
            """);
        write(repo.resolve("src/main/java/com/parcelworks/route/RouteMatcher.java"), """
            package com.parcelworks.route;

            public interface RouteMatcher {
                boolean matches(String label);
            }
            """);
        write(repo.resolve("src/main/java/com/parcelworks/route/PrefixMatcher.java"), """
            package com.parcelworks.route;

            public class PrefixMatcher implements RouteMatcher {
                private final String prefix;
                public PrefixMatcher(String prefix) { this.prefix = prefix; }
                @Override public boolean matches(String label) { return label.startsWith(prefix); }
            }
            """);
        write(repo.resolve("src/main/java/com/parcelworks/nodes/Parcel.java"), """
            package com.parcelworks.nodes;

            public class Parcel {
                private String label = "";
                public String label() { return label; }
                public Parcel label(String value) { this.label = value; return this; }
            }
            """);

        // The test for the parser: named after it, beside it, and it uses FEWER of the named types
        // than the integration test below. It must still be first.
        write(repo.resolve("src/test/java/com/parcelworks/route/RouteParserTest.java"), """
            package com.parcelworks.route;

            class RouteParserTest {
                void parsesAPlainLabel() {
                    RouteMatcher matcher = RouteParser.parse("via one");
                    if (!matcher.matches("one")) { throw new AssertionError(); }
                }
            }
            """);
        write(repo.resolve("src/test/java/com/parcelworks/nodes/ParcelTest.java"), """
            package com.parcelworks.nodes;

            class ParcelTest {
                void carriesItsLabel() {
                    Parcel parcel = new Parcel().label("one");
                    if (!parcel.label().equals("one")) { throw new AssertionError(); }
                }
            }
            """);
        // The noise: it imports everything on its way to doing something else entirely.
        write(repo.resolve("src/test/java/com/parcelworks/integration/DeliveryIT.java"), """
            package com.parcelworks.integration;

            import com.parcelworks.nodes.Parcel;
            import com.parcelworks.route.PrefixMatcher;
            import com.parcelworks.route.RouteMatcher;
            import com.parcelworks.route.RouteParser;

            class DeliveryIT {
                void deliversEndToEnd() {
                    RouteMatcher matcher = RouteParser.parse("via one");
                    Parcel parcel = new Parcel().label("one");
                    RouteMatcher other = new PrefixMatcher("v");
                    if (!matcher.matches(parcel.label()) || other == null) {
                        throw new AssertionError();
                    }
                }
            }
            """);

        index = SemanticIndex.over(
            List.of(new KnowledgeCurator.Root("project", repo)), world.resolve("cache"));
        found = ChangeNeighbourhood.read(ISSUE, index, List.of("src/test/java"),
            ChangeNeighbourhood.MAX_CHARS);
        System.out.println("[NEIGHBOURHOOD] " + found.describe());
        System.out.println("[NEIGHBOURHOOD] index " + index.stats());
        System.out.println(found.brief());
    }

    @Test
    void theIndexWasBuiltAtAll() {
        assertThat(index.available())
            .as("the invented library parsed: %s", index.unavailableReason())
            .isTrue();
    }

    @Test
    void itNamesTheTypeTheReportNamesWithTheFileAndLineItIsDeclaredOn() {
        ChangeNeighbourhood.NamedType parser = found.types().stream()
            .filter(t -> t.fqn().equals("com.parcelworks.route.RouteParser"))
            .findFirst().orElse(null);
        assertThat(parser).as("the report names RouteParser: %s", found.describe()).isNotNull();
        assertThat(parser.where()).matches(".*RouteParser\\.java:\\d+");
        assertThat(found.brief()).contains(parser.where());
    }

    @Test
    void itNamesTheTypeBehindAMethodTheReportOnlyCalls() {
        // The report never writes "Parcel" as a type — it calls found.label(). The call graph knows
        // which type declares label(), and that is the neighbourhood.
        assertThat(found.methods()).contains("label");
        assertThat(found.types().stream().map(ChangeNeighbourhood.NamedType::fqn))
            .as("the type declaring the method the report calls: %s", found.describe())
            .contains("com.parcelworks.nodes.Parcel");
    }

    @Test
    void theTestNamedAfterTheTypeComesBeforeTheIntegrationTestThatUsesMoreOfThem() {
        List<String> files = found.tests().stream()
            .map(ChangeNeighbourhood.CoveringTest::file).toList();
        assertThat(files).as("the tests that already cover this area").isNotEmpty();
        assertThat(files.get(0))
            .as("nearest first, not most-types first — %s", found.tests())
            .endsWith("RouteParserTest.java");
        int parserAt = indexOfEnding(files, "RouteParserTest.java");
        int integrationAt = indexOfEnding(files, "DeliveryIT.java");
        if (integrationAt >= 0) {
            assertThat(parserAt)
                .as("the integration test uses more of the named types and must still rank below")
                .isLessThan(integrationAt);
        }
    }

    @Test
    void itNamesNothingTheProjectDoesNotHave() {
        assertThat(found.brief()).doesNotContain("HttpFetcher");
    }

    @Test
    void platformTypesAreNotTheNeighbourhoodOfAnything() {
        assertThat(found.types().stream().map(ChangeNeighbourhood.NamedType::fqn))
            .noneMatch(fqn -> fqn.startsWith("java."));
    }

    @Test
    void everyFileItQuotesIsAFileThatExists() {
        Matcher references = Pattern.compile("(src/[A-Za-z0-9_/.$-]+\\.java):(\\d+)")
            .matcher(found.brief());
        int checked = 0;
        while (references.find()) {
            Path onDisk = world.resolve("parcelworks").resolve(references.group(1));
            assertThat(Files.isRegularFile(onDisk))
                .as("the brief quotes %s, which must be a real file", references.group(0))
                .isTrue();
            assertThat(Integer.parseInt(references.group(2))).isGreaterThan(0);
            checked++;
        }
        assertThat(checked)
            .as("a neighbourhood with no file:line reference in it is not a neighbourhood")
            .isGreaterThan(0);
    }

    @Test
    void itStaysInsideTheBudgetItIsGiven() {
        ChangeNeighbourhood.Neighbourhood small =
            ChangeNeighbourhood.read(ISSUE, index, List.of("src/test/java"), 1_200);
        assertThat(small.chars()).isLessThanOrEqualTo(1_200);
        assertThat(small.types()).isNotEmpty();
        assertThat(found.chars()).isLessThanOrEqualTo(ChangeNeighbourhood.MAX_CHARS);
    }

    @Test
    void theBudgetIsSizedAgainstTheWindowTheWorkerActuallyGets() {
        // 51,200 tokens is the working context design §2.2 measured against, and 2.9% of it at
        // four characters a token is 5,939 — which is where the design's "≤ 6,000 characters,
        // 2.9% of the window" came from, so the two must still agree.
        assertThat(ChangeNeighbourhood.forWorkingContext(51_200))
            .isBetween(5_000, ChangeNeighbourhood.MAX_CHARS);
        // A smaller server gets a smaller brief rather than the same one overrunning its window.
        assertThat(ChangeNeighbourhood.forWorkingContext(16_384))
            .isLessThan(ChangeNeighbourhood.MAX_CHARS).isGreaterThan(0);
        // Nothing discovered at all: the ceiling, not zero.
        assertThat(ChangeNeighbourhood.forWorkingContext(0))
            .isEqualTo(ChangeNeighbourhood.MAX_CHARS);
    }

    @Test
    void withNoIndexItSaysSoAndDoesNotThrow() {
        ChangeNeighbourhood.Neighbourhood nothing = ChangeNeighbourhood.read("anything", null);
        assertThat(nothing.isEmpty()).isTrue();
        assertThat(nothing.brief()).isEmpty();
        assertThat(nothing.note()).contains("could not answer");
        assertThat(nothing.references()).isZero();
    }

    @Test
    void withNoTextItSaysSoAndDoesNotThrow() {
        ChangeNeighbourhood.Neighbourhood nothing = ChangeNeighbourhood.read("  ", index);
        assertThat(nothing.isEmpty()).isTrue();
        assertThat(nothing.note()).contains("no text");
    }

    @Test
    void whatCountsAsATestFileIsTheBuildsAnswerWhenThereIsOne() {
        assertThat(ChangeNeighbourhood.isTestFile("src/test/java/a/B.java", List.of("src/test/java")))
            .isTrue();
        assertThat(ChangeNeighbourhood.isTestFile("src/main/java/a/B.java", List.of("src/test/java")))
            .isFalse();
        // A module's test root, named by the build relative to the repository root.
        assertThat(ChangeNeighbourhood.isTestFile("core/src/test/java/a/B.java",
            List.of("core/src/test/java"))).isTrue();
        // No build layout to hand: the conventions, which are generous rather than silent.
        assertThat(ChangeNeighbourhood.isTestFile("core/src/test/java/a/B.java", List.of()))
            .isTrue();
        assertThat(ChangeNeighbourhood.isTestFile("core/src/main/java/a/B.java", List.of()))
            .isFalse();
    }

    private static int indexOfEnding(List<String> files, String suffix) {
        for (int at = 0; at < files.size(); at++) {
            if (files.get(at).endsWith(suffix)) {
                return at;
            }
        }
        return -1;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
