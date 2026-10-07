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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The twelve questions one worker actually asked, against the folder it actually had.
 *
 * <p><b>What happened, 2026-09-03.</b> A worker was told to build a personal book list on a
 * framework whose checkout it had been given. It asked {@code lookup_api} twelve times, in a row,
 * variations of one question: how do I get hold of the store and its root object, and how do I
 * save. The answer is one page — {@code docs/guides/persistence.md} — and the example that shows it
 * is {@code inventory-crud}. Neither was returned once. What came back was
 * {@code docs/AGENT_PROMPTS.md › Task 3} four times (a set of prompts for an agent that was told to
 * BUILD that example), a lock-design note, a page about server events, and — for a question about a
 * server's main class — the archetype's {@code ServerApp.java}, which has no store in it at all.
 * The worker was killed after the same section answered three different questions; a sibling worker
 * went looking for jars with {@code find / -name "*.jar"}.
 *
 * <p><b>What this pins.</b> Not a ranking table — those move — but the four things that were
 * wrong:
 *
 * <ol>
 *   <li>every one of the twelve is now answered from a page that contains the answer, and never
 *       from the prompt file, a release note, a design note or a template;</li>
 *   <li>the guide that answers is the top section for the questions that are about saving, and in
 *       the top three for the rest;</li>
 *   <li>the code half carries the files that show the API — the service that saves, the root
 *       object, and the provider that makes it — and never the archetype skeleton;</li>
 *   <li>a question about a component reaches the component's own class and the component
 *       reference, which the 400-file cap had made unreachable entirely.</li>
 * </ol>
 *
 * <p>These tests skip, not fail, on a machine without the folder.
 */
class LibrarianReturnsThePageThatAnswersTest {

    static final Path ZEROZ4J = LocalCheckouts.find("zeroz4j");
    static final String PERSISTENCE = "zeroz4j/docs/guides/persistence.md";
    static final String STORE_MODES = "zeroz4j/docs/store-modes.md";
    static final String INVENTORY = "zerozstack-examples/inventory-crud/inventory-crud-server";
    static final String ARCHETYPE = "zerozstack-archetype";

    /** The twelve, verbatim, in the order they were asked. */
    static final List<String> ASKED = List.of(
        "EclipseStore root store setup db.localDb write ctx.edit",
        "how to configure EclipseStore root object class Zeroz4jServer start store root",
        "store-modes embedded database server DbCommand",
        "ZeroZDbNode embedded root factory server main app",
        "inventory-crud server app main Zeroz4jServer",
        "inventory-crud-server main app DataRoot embedded store directory",
        "ZeroZDbNode embedded factory root class how to create root object in application",
        "inventory-crud-server ServerApp main",
        "zerozstack-store-eclipsestore module root configuration",
        "inventory-crud ProductService implementation save store",
        "localDb store write WriteContext edit commit",
        "persistence guide storage store storeAll how to get storage instance");

    /**
     * The pages that carry the answer. Every one of them says how to reach the store and save:
     * the guide, the page explaining the two store modes, and the walkthrough's persistence step.
     * A question naming an example's server class is a question about code, and its prose half is
     * allowed to be about running an example instead.
     */
    static final List<String> ANSWERING_PAGES = List.of(
        PERSISTENCE, STORE_MODES, "zeroz4j/docs/CODE_WALKTHROUGH.md",
        "zeroz4j/docs/guides/oidc-auth.md", "zeroz4j/docs/examples/index.md");

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    Librarian librarian;

    @BeforeEach
    void requireTheReferenceFolder() {
        assumeTrue(Files.isDirectory(ZEROZ4J.resolve("zerozstack-ui-components")),
            "reference folder not present");
        librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(ZEROZ4J), repo, null, cache);
    }

    /** Nothing that is not an answer is ever returned to a worker. */
    @Test
    void noneOfTheTwelveIsAnsweredWithAPromptFileANoteOrATemplate() {
        for (String query : ASKED) {
            String answer = librarian.lookupApi(query);
            assertThat(answer)
                .as("the prompt file that won four of these: %s", query)
                .doesNotContain("AGENT_PROMPTS.md")
                .as("a design note is not documentation: %s", query)
                .doesNotContain("docs/design/")
                .as("a release note answers nothing: %s", query)
                .doesNotContain("CHANGELOG.md")
                .as("the archetype skeleton has no store in it: %s", query)
                .doesNotContain(ARCHETYPE);
        }
    }

    /** Every one of the twelve now lands on a page that carries the answer. */
    @Test
    void everyOneOfTheTwelveLandsOnAPageThatCarriesTheAnswer() {
        for (String query : ASKED) {
            List<KnowledgeCurator.ScoredSection> ranked =
                librarian.curator().scoreSections(query, true);
            assertThat(ranked).as("nothing at all matched: %s", query).isNotEmpty();
            assertThat(ranked.get(0).value().address())
                .as("top section for: %s", query)
                .satisfies(address -> assertThat(ANSWERING_PAGES)
                    .anySatisfy(page -> assertThat(address).contains(page)));
        }
    }

    /**
     * The guide that answers is the top section for the questions that are about saving, and in
     * the top three for every question that is about the store at all.
     */
    @Test
    void thePersistenceGuideWinsTheQuestionsItAnswers() {
        for (String query : List.of(ASKED.get(0), ASKED.get(6), ASKED.get(9), ASKED.get(11))) {
            assertThat(topSection(query))
                .as("the guide must be first for: %s", query)
                .isEqualTo(PERSISTENCE);
        }
        // For the three that mix the two persistence vocabularies the page explaining the two
        // store modes wins, which is the right answer to a worker that does not know which stack
        // it has; the guide is still on the first screen of results.
        for (String query : List.of(ASKED.get(3), ASKED.get(5), ASKED.get(10))) {
            assertThat(topN(query, 5))
                .as("the guide must be reachable for: %s", query)
                .contains(PERSISTENCE);
        }
        assertThat(topSection(ASKED.get(2)))
            .as("a question about store MODES is answered by the page about store modes")
            .isEqualTo(STORE_MODES);
    }

    /**
     * The code half carries the files that show the API. All three the worker needed —
     * the service that saves, the root object, and the provider that makes the root — are
     * returned by these questions, and the archetype skeleton by none of them.
     */
    @Test
    void theCodeHalfCarriesTheFilesThatShowTheApi() {
        assertThat(librarian.lookupApi(ASKED.get(9)))
            .as("the service that saves")
            .contains(INVENTORY + "/src/main/java/com/zeroz4j/example/server/ProductServiceImpl.java");
        String roots = librarian.lookupApi(ASKED.get(5));
        assertThat(roots)
            .as("the root object, from the example the question names")
            .contains(INVENTORY + "/src/main/java/com/zeroz4j/example/server/store/DataRoot.java")
            .as("and the provider that makes it, instead of six other examples' DataRoot")
            .contains(INVENTORY
                + "/src/main/java/com/zeroz4j/example/server/store/DefaultDataRootProvider.java");
        for (String query : ASKED) {
            assertThat(librarian.lookupApi(query))
                .as("every answer carries real code: %s", query)
                .contains("## Source: ");
        }
    }

    /** A question that names an example's server class gets that example's server class. */
    @Test
    void aQuestionAboutTheServersMainClassGetsARealServerAndNeverTheTemplate() {
        assertThat(librarian.lookupApi("inventory-crud-server ServerApp main"))
            .contains(INVENTORY + "/src/main/java/com/zeroz4j/example/server/ExampleServer.java")
            .doesNotContain(ARCHETYPE);
        assertThat(librarian.lookupApi("ExampleServer main"))
            .contains("/example/server/ExampleServer.java")
            .doesNotContain(ARCHETYPE);
        // "ServerApp" names nothing but a template. The template is never the answer; the
        // framework's own server bootstrap is, and it is a real class with a real main.
        assertThat(librarian.lookupApi("ServerApp main"))
            .doesNotContain(ARCHETYPE)
            .containsPattern("## Source: \\S+(Zeroz4jServer|ExampleServer)\\.java");
    }

    /**
     * A whole module that no query could reach.
     *
     * <p>The curator kept 400 source files per root in path order and this folder has 681, so
     * {@code zerozstack-ui-components} — 166 files, every component a worker asks about — was past
     * the cap, and so were the framework's own API module and the store wiring. There is no cap
     * now: the outline of every file is indexed, and the body is read when it is asked for.
     */
    @Test
    void aComponentQuestionReachesTheComponentAndItsReferencePage() {
        String answer = librarian.lookupApi("Select component options addOption value getValue");

        assertThat(answer)
            .contains("zeroz4j/docs/UI_COMPONENTS.md")
            .contains("zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component/Select.java");
        assertThat(sections(answer))
            .as("the reference entry for input components, not just the page's index")
            .anySatisfy(heading -> assertThat(heading).contains("Input & Data-Backed Components"));
    }

    /** The catalogue in the brief opens with the guides, each with a line saying what it is for. */
    @Test
    void theBriefsCatalogueOpensWithTheGuidesAndSaysWhatEachIsFor() {
        String map = librarian.curator().documentationMap(3_000);

        assertThat(map).startsWith("- zeroz4j/docs/guides/");
        assertThat(map)
            .contains("zeroz4j/docs/guides/persistence.md — Saving data: How to persist your object "
                + "graph, and the one rule that prevents the most common data-loss bug.")
            .as("process material is not listed at all")
            .doesNotContain("AGENT_PROMPTS.md")
            .doesNotContain("CHANGELOG.md")
            .doesNotContain("CONTRIBUTING.md")
            .doesNotContain("docs/contribute/");
        assertThat(map.indexOf("docs/guides/"))
            .isLessThan(map.indexOf("docs/CONCEPTS.md"));
    }

    /** The brief says which version of the folder these documents describe. */
    @Test
    void theBriefNamesTheVersionOfTheReferenceFolder() {
        KnowledgeCurator.Root root = librarian.curator().roots().stream()
            .filter(candidate -> "zeroz4j".equals(candidate.label())).findFirst().orElseThrow();

        assertThat(root.version()).isNotBlank().isNotEqualTo("unknown");
        assertThat(root.identity().describe()).contains("commit ");
    }

    /** Indexing the whole folder is affordable — the number the projection is built on. */
    @Test
    void theWholeFolderIsIndexedInSecondsAndMegabytes() {
        librarian.curator().documentCount();
        ReferenceIndex.Stats stats = librarian.curator().indexStats();

        assertThat(stats.files()).isGreaterThan(650);
        assertThat(stats.buildMillis()).isLessThan(60_000);
        assertThat(stats.bytesOnDisk()).isLessThan(64L * 1024 * 1024);
    }

    private String topSection(String query) {
        return librarian.curator().scoreSections(query, true).get(0).value().address();
    }

    private List<String> topN(String query, int n) {
        return librarian.curator().scoreSections(query, true).stream()
            .limit(n).map(section -> section.value().address()).toList();
    }

    private static List<String> sections(String answer) {
        return answer.lines().filter(line -> line.startsWith("#### ")).toList();
    }
}
