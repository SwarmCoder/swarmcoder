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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The proof that the system can declare a dependency without damaging the file it declares it in.
 *
 * <p><b>What is being proved, exactly.</b> That
 * {@code org.openrewrite.maven.AddDependency} — a refactoring recipe over a real parsed pom — adds
 * the block and leaves EVERY OTHER LINE BYTE-IDENTICAL. Not "roughly the same", not "the same
 * after reformatting": the diff is the added lines and nothing else. That is the property a
 * string insertion cannot have and the reason this is worth a dependency.
 *
 * <p>The pom it is proved on is the demo's server module, chosen because it is the hardest one in
 * this repository: two dependency lists, three build plugins, two profiles, an
 * OS-activated profile, and fourteen comments a person wrote on purpose — including one explaining
 * why {@code <resources>} must be listed explicitly, which a careless edit would silently drop.
 *
 * <p>Skipped loudly when the demo is not on this machine (it is not under version control). The
 * synthetic pom below is not a substitute — it proves the same property on a file nobody hand-wrote
 * — so both are here.
 */
class TheRecipeAddsTheBlockAndChangesNothingElseTest {

    private static final Path DEMO_SERVER_POM =
        LocalCheckouts.find("dev/bookshelf-demo", "swarmcoder/dev/bookshelf-demo").resolve("bookshelf-demo-server/pom.xml");

    @Test
    void onTheDemosServerPomEveryOtherLineIsUntouched() throws Exception {
        assumeThat(Files.isRegularFile(DEMO_SERVER_POM))
            .as("the demo is not under version control, so this proof is skipped without it")
            .isTrue();
        String before = Files.readString(DEMO_SERVER_POM, StandardCharsets.UTF_8);

        String after = MavenRecipes.withDependency(before,
            "com.zeroz4j", "zerozstack-store-eclipsestore", "", null);

        assertThat(after).as("the recipe must actually do something").isNotEmpty();
        assertThat(after).contains("<artifactId>zerozstack-store-eclipsestore</artifactId>");
        assertThat(after)
            .as("the version is managed by the parent, so no <version> element is written")
            .doesNotContain("<artifactId>zerozstack-store-eclipsestore</artifactId>\n"
                + "      <version>");

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        difference(before, after, added, removed);

        assertThat(removed).as("nothing may be removed from a pom to add a dependency to it")
            .isEmpty();
        assertThat(added)
            .as("only the dependency block is added, and it is four lines")
            .allSatisfy(line -> assertThat(line.strip()).matches(
                "<dependency>|</dependency>|<groupId>com\\.zeroz4j</groupId>"
                    + "|<artifactId>zerozstack-store-eclipsestore</artifactId>"));
        assertThat(added).hasSize(4);

        // The comments a person wrote, one by one. These are what a text insertion loses.
        assertThat(after)
            .contains("Must be listed explicitly: declaring <resources> replaces the default")
            .contains("WELD-001409: Ambiguous dependencies")
            .contains("Deliberately NOT a shaded jar")
            .contains("jpackage refuses to overwrite an existing image");
        assertThat(count(after, "<dependencies>")).isEqualTo(count(before, "<dependencies>"));
        assertThat(count(after, "<profile>")).isEqualTo(count(before, "<profile>"));
        assertThat(count(after, "<plugin>")).isEqualTo(count(before, "<plugin>"));
    }

    /**
     * A pom with BOTH kinds of dependency list, so "which list does the block go in" is a real
     * question rather than an accident of there being only one.
     *
     * <p>The artifacts are real ones from the offline repository on purpose. The Maven parser
     * RESOLVES a pom — that is what puts the marker on it that {@code AddDependency} reads — and a
     * pom naming artifacts nothing can resolve is a pom the recipe correctly declines to touch.
     * That is not a limitation worth hiding: it is the reason this class returns "" instead of
     * throwing, and {@code aPomItCannotParse…} below asserts the same thing from the other side.
     */
    @Test
    void itPutsTheBlockInTheRealDependencyListAndNotTheManagedOne() {
        String before = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>orders</artifactId>
              <version>1.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.assertj</groupId>
                    <artifactId>assertj-core</artifactId>
                    <version>3.25.3</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.13</version>
                </dependency>
              </dependencies>
            </project>
            """;

        String after = MavenRecipes.withDependency(before, "org.assertj", "assertj-core", "", null);

        assertThat(after).isNotEmpty();
        int managed = after.indexOf("</dependencyManagement>");
        int added = after.indexOf("<artifactId>assertj-core</artifactId>", managed);
        assertThat(added).as("the block goes in <dependencies>, not <dependencyManagement>")
            .isGreaterThan(managed);
        assertThat(after).contains("<artifactId>slf4j-api</artifactId>");
        assertThat(after.substring(0, managed))
            .as("the managed entry keeps its version and is not touched")
            .contains("<version>3.25.3</version>");
    }

    @Test
    void aPomItCannotParseCostsNothingAndSaysSoByReturningNothing() {
        assertThat(MavenRecipes.withDependency("this is not xml at all",
            "org.example", "widget", "1.0", null)).isEmpty();
        assertThat(MavenRecipes.withDependency(null, "org.example", "widget", "1.0", null))
            .isEmpty();
        assertThat(MavenRecipes.withDependency("<project/>", "", "widget", "1.0", null)).isEmpty();
    }

    @Test
    void theInstructionOffersTheRecipeWithoutImposingIt() {
        String said = MavenRecipes.howToDeclare("com.zeroz4j", "zerozstack-store-eclipsestore",
            true);

        assertThat(said)
            .contains("no `<version>` element")
            .contains("org.openrewrite.maven.AddDependency")
            .as("a worker has a compiler; it is not made to use the recipe")
            .contains("Either way is fine");
    }

    // -----------------------------------------------------------------------------------------

    /** Line-level difference, both directions. Crude on purpose: it must not forgive anything. */
    private static void difference(String before, String after, List<String> added,
                                   List<String> removed) {
        List<String> was = new ArrayList<>(List.of(before.split("\n", -1)));
        for (String line : after.split("\n", -1)) {
            if (!was.remove(line)) {
                added.add(line);
            }
        }
        removed.addAll(was);
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            found++;
            at = text.indexOf(needle, at + 1);
        }
        return found;
    }
}
