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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fix for the 2026-09-04 incident: {@code lookup_api} answered three different questions in
 * one worker's run with the same leading documentation section, and the run only WARNED about
 * it — the worker read the identical page a third time anyway and then went looking for jars.
 *
 * <p>Two things are pinned here, each on a tree built for the purpose so they hold on any
 * machine:
 *
 * <ol>
 *   <li>{@code lookup_api} never hands the same worker the same section twice: told which
 *       sections it has already given (by address and heading — a stable identity, not a text
 *       hash), it returns the next-best section that is NOT one of those, and says so plainly
 *       once nothing new is left;</li>
 *   <li>a refined question — one that adds a word its earlier questions did not have — actually
 *       moves the ranking, instead of landing on the same page a broader question did.</li>
 * </ol>
 */
class LibrarianDoesNotRepeatASectionTest {

    // --- 1. never the same section twice --------------------------------------------------------

    @TempDir
    Path repo;
    @TempDir
    Path framework;
    @TempDir
    Path cache;

    /**
     * Three pages, byte-identical in every field the ranking reads (title, heading, body length
     * and the term they share) except their address — so they score EXACTLY the same for the one
     * query that matches all three, and the ranking's own deterministic tie-break (by address)
     * decides the order. That is what makes "which one comes back first" pinnable at all: real
     * documentation never ties this cleanly, which is exactly how the original incident's four
     * identical answers went unnoticed.
     */
    private void buildThreeTiedPages() throws Exception {
        String body = """
            # Guide

            ## Answer
            Kittenstore keeps the filler words identical across every page so the three tie on score.
            """;
        write("docs/guides/alpha.md", body);
        write("docs/guides/beta.md", body);
        write("docs/guides/gamma.md", body);
    }

    private void write(String relative, String content) throws Exception {
        Path file = framework.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Librarian librarianOverTheFramework() {
        return new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework), repo, null, cache);
    }

    /**
     * Three questions that all match the same three tied sections get three DIFFERENT answers —
     * the second and third excluding whichever came before — and a fourth question, once all
     * three have been given, is told plainly that there is no more documentation on this instead
     * of being handed the first section a fourth time.
     */
    @Test
    void threeQuestionsGetThreeDifferentSectionsThenNothingNewOnTheFourth() throws Exception {
        buildThreeTiedPages();
        Librarian librarian = librarianOverTheFramework();
        Set<String> given = new LinkedHashSet<>();
        List<String> ledWith = new ArrayList<>();

        for (String query : List.of("kittenstore", "the kittenstore", "please kittenstore")) {
            String answer = librarian.lookupApi(query, given, Set.of());
            String section = leadingSection(answer);
            assertThat(section).as("query '%s' matched no section at all", query).isNotBlank();
            assertThat(given).as("query '%s' repeated a section already given", query)
                .doesNotContain(section);
            given.add(section);
            ledWith.add(section);
        }

        assertThat(ledWith).as("three different sections, not the same one three times")
            .hasSize(3).doesNotHaveDuplicates();

        String fourth = librarian.lookupApi("a kittenstore", given, Set.of());

        assertThat(fourth)
            .as("verbatim message when every matching section is already given")
            .isEqualTo("Every documentation section matching this lives in what you already "
                + "read: " + String.join(", ", ledWith) + ". There is no more documentation on "
                + "this; write your best attempt, or ask_expert with the exact question.");
    }

    /** Every answer carries the section's own identity — address and heading — not just its text. */
    @Test
    void theAnswerNamesTheSectionItCameFrom() throws Exception {
        buildThreeTiedPages();
        Librarian librarian = librarianOverTheFramework();

        String answer = librarian.lookupApi("kittenstore", Set.of(), Set.of());

        assertThat(answer).contains("#### " + framework.getFileName() + "/docs/guides/")
            .contains("› Answer");
    }

    /** {@code lookupApi(query)} — no exclusion set — behaves exactly as it always did. */
    @Test
    void withoutAnExclusionSetTheOldOneArgMethodIsUnchanged() throws Exception {
        buildThreeTiedPages();
        Librarian librarian = librarianOverTheFramework();

        String direct = librarian.lookupApi("kittenstore");
        String throughTheNewOverload = librarian.lookupApi("kittenstore", Set.of(), Set.of());

        assertThat(direct).isEqualTo(throughTheNewOverload);
    }

    private static String leadingSection(String answer) {
        for (String line : answer.lines().toList()) {
            if (line.startsWith("#### ")) {
                return line.substring(5).strip();
            }
        }
        return "";
    }

    // --- 2. a refined question re-ranks toward the word it added --------------------------------

    @TempDir
    Path repo2;
    @TempDir
    Path framework2;
    @TempDir
    Path cache2;

    /**
     * A broad page that answers the ORIGINAL question outright, and a narrow page that answers
     * only the SPECIFIC method the refined question names — sharing one common word so both are
     * candidates, but with the broad page ahead until the specific word is boosted.
     */
    private void buildABroadAndASpecificPage() throws Exception {
        Path root = framework2;
        write(root, "docs/guides/broadpage.md", """
            # Broad guide

            ## Overview
            persistiron widget assembly overview notes for this section of the guide.
            """);
        write(root, "docs/guides/specificpage.md", """
            # Specific guide

            ## Overview
            persistiron createDefaultRoot makes the first root the store will ever hold.
            """);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * The broad question is answered by the broad page — that is the "leading section" a worker
     * would have been given already. Adding the one word the refined question names moves the
     * ranking to the page that actually documents it, which the SAME words without the boost do
     * not do (asserted first, so the reversal is attributed to the boost and not to the extra
     * word alone).
     */
    @Test
    void aRefinedQuestionOutranksThePageThatAnsweredTheBroaderOne() throws Exception {
        buildABroadAndASpecificPage();
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(framework2), repo2, null, cache2);
        String broadQuery = "persistiron widget assembly";
        String refinedQuery = "persistiron widget assembly createDefaultRoot";

        String broadAnswer = librarian.lookupApi(broadQuery, Set.of(), Set.of());
        assertThat(leadingSection(broadAnswer)).as("the broad question's leading section")
            .contains("broadpage.md");

        String refinedUnboosted = librarian.curator().relevantDocsExcluding(
            refinedQuery, 1, 1_500, false, Set.of(), Set.of()).rendered();
        assertThat(leadingSectionOf(refinedUnboosted))
            .as("without the boost, the extra word alone does not move the ranking")
            .contains("broadpage.md");

        String refinedBoosted = librarian.lookupApi(refinedQuery, Set.of(),
            Set.of("createDefaultRoot"));
        assertThat(leadingSection(refinedBoosted))
            .as("boosting the word the refined question added ranks the page that documents it "
                + "above the page that only answered the broader question")
            .contains("specificpage.md");
    }

    private static String leadingSectionOf(String rendered) {
        for (String line : rendered.lines().toList()) {
            if (line.startsWith("#### ")) {
                return line.substring(5).strip();
            }
        }
        return "";
    }
}
