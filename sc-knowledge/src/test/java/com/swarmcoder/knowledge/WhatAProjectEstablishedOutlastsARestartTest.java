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

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 101. What a project established lived in memory and was gone when the application
 * restarted; the build that section 100 was written from restarted it 15 times. The answers
 * and the findings are kept in the product's store, and what is read back is held against the
 * disk before it is served: a file that changed while the application was closed takes its
 * answers, and an answer is never served by another reading of the syntax tree.
 */
class WhatAProjectEstablishedOutlastsARestartTest {

    private static final String STORED =
        "shop-server/src/main/java/com/shop/server/StoredBasket.java";

    @TempDir
    Path world;

    private Path app;
    private Path lib;
    private final UUID project = UUID.randomUUID();

    @BeforeEach
    void aProjectAndWhatItBuildsOn() throws Exception {
        app = world.resolve("app");
        lib = world.resolve("lib");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>");
        write(app.resolve("shop-shared/src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public interface Basket {
                int size();
                void add(String item);
            }
            """);
        write(app.resolve(STORED), """
            package com.shop.server;

            import com.shop.Basket;

            public class StoredBasket implements Basket {
                private final java.util.List<String> items = new java.util.ArrayList<>();

                public int size() {
                    return items.size();
                }

                public void add(String item) {
                    items.add(item);
                }
            }
            """);
        write(app.resolve("docs/guide.md"), "# Guide\n\nHow baskets work.\n\n## Storing\n\n"
            + "A basket is stored whole.\n");
        write(lib.resolve("src/main/java/org/lib/Stored.java"), """
            package org.lib;

            public class Stored {
                public void write(Object value) {
                }
            }
            """);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** The application as it is after a start: the store opened, the project read. */
    private final class Started implements AutoCloseable {
        private final ArtifactStore store = new ArtifactStore(world.resolve("store"));
        private final KnowledgeCurator curator;
        private final SharedAnswers answers;

        Started(String treeCache) {
            // A new process remembers nothing of what a folder was a minute ago.
            RootIdentity.forget();
            curator = new KnowledgeCurator(List.of(
                new KnowledgeCurator.Root("project", app, "local"),
                new KnowledgeCurator.Root("lib", lib, "2")), null, world.resolve(treeCache));
            answers = curator.answers();
            answers.keptIn(store, project);
        }

        ExpertTools toolsOf(String role) {
            return new ExpertTools(curator, null, null, new CloudGate(0, null),
                MaterialBudget.forWorkingContext(983_040), 0,
                new ExpertTools.User(role, "hand in", 0, null, 0, 0));
        }

        SharedAnswers.Served asAWorker(String query, String argument) {
            return answers.answerFor(Addresses.ofAWorker("project"), SharedAnswers.WORKER, query,
                argument, SharedAnswers.reachOf(query, argument), "worker",
                () -> new SharedAnswers.Computed("the worker's own computation", null));
        }

        @Override
        public void close() throws Exception {
            store.close();
        }
    }

    @Test
    void anAnswerAndAFindingAreServedAfterTheStoreIsClosedAndReopened() throws Exception {
        String body;
        String section;
        try (Started first = new Started("cache")) {
            ExpertTools planner = first.toolsOf("planner");
            body = planner.bodyOf("StoredBasket#add");
            section = planner.docSection("project/docs/guide.md#Storing");
            assertThat(planner.keepFinding("com.shop.server.StoredBasket",
                "body_of StoredBasket#add", "A basket keeps its items in a list in memory."))
                .startsWith("Kept for every later session");
            assertThat(body).contains("items.add(item);");
            assertThat(section).contains("A basket is stored whole.");
        }

        try (Started second = new Started("cache")) {
            ExpertTools reviewer = second.toolsOf("reviewer");

            assertThat(reviewer.bodyOf("StoredBasket#add")).isEqualTo(body);
            assertThat(reviewer.docSection("project/docs/guide.md#Storing")).isEqualTo(section);
            assertThat(second.answers.asked()).isEqualTo(2);
            assertThat(second.answers.served()).as("nothing was computed after the restart")
                .isEqualTo(2);
            assertThat(second.answers.summary()).contains("2 answer(s) held from before");

            // A worker is served what the role computed before the restart, in its own words.
            SharedAnswers.Served worker = second.asAWorker("body_of", "StoredBasket#add");
            assertThat(worker.fromStore()).isTrue();
            assertThat(worker.text()).contains("// " + STORED + ":", "items.add(item);")
                .doesNotContain("project/");

            // The finding is offered by title and told with who found it.
            KnownFacts known = second.answers.known();
            assertThat(known.size()).isEqualTo(1);
            reviewer.know(known);
            assertThat(reviewer.knownAbout("StoredBasket")).contains(
                "A basket keeps its items in a list in memory.", "from: body_of StoredBasket#add",
                "found by: the planner", "unchanged since");
        }

        // A second restart: nothing was written twice and nothing was lost.
        try (Started third = new Started("cache")) {
            assertThat(third.answers.known().size()).isEqualTo(1);
            assertThat(third.toolsOf("architect").bodyOf("StoredBasket#add")).isEqualTo(body);
            assertThat(third.answers.served()).isEqualTo(1);
        }
    }

    @Test
    void aFileChangedWhileTheApplicationWasClosedTakesItsAnswersOnReopen() throws Exception {
        String section;
        try (Started first = new Started("cache")) {
            ExpertTools planner = first.toolsOf("planner");
            assertThat(planner.bodyOf("StoredBasket#add")).contains("items.add(item);");
            section = planner.docSection("project/docs/guide.md#Storing");
            assertThat(planner.typesIn("com.shop")).contains("Basket");
            planner.keepFinding("com.shop.server.StoredBasket", "body_of StoredBasket#add",
                "Items are appended.");
        }

        // Somebody edits one file while the application is not running.
        Path stored = app.resolve(STORED);
        Files.writeString(stored, Files.readString(stored)
            .replace("items.add(item);", "items.add(0, item);"));

        try (Started second = new Started("cache")) {
            ExpertTools reviewer = second.toolsOf("reviewer");

            assertThat(reviewer.docSection("project/docs/guide.md#Storing"))
                .as("the untouched file's answer").isEqualTo(section);
            assertThat(second.answers.served()).isEqualTo(1);
            // Never the body from before the edit.
            assertThat(reviewer.bodyOf("StoredBasket#add")).contains("items.add(0, item);")
                .doesNotContain("items.add(item);");
            reviewer.typesIn("com.shop");
            assertThat(second.answers.served()).as("the changed file's answer and the answer "
                + "that can change with any file were both computed again").isEqualTo(1);
            assertThat(second.answers.summary()).contains("1 answer(s) held from before");

            reviewer.know(second.answers.known());
            assertThat(reviewer.knownAbout("StoredBasket")).as("the finding is still told, and "
                + "no longer as true").contains("Items are appended.", "CHANGED SINCE");
        }

        // What was forgotten is forgotten in the store too, and the new answer is the kept one.
        try (Started third = new Started("cache")) {
            assertThat(third.toolsOf("planner").bodyOf("StoredBasket#add"))
                .contains("items.add(0, item);");
            assertThat(third.answers.served()).isEqualTo(1);
            assertThat(third.answers.dropped()).isZero();
        }
    }

    @Test
    void anAnswerIsNeverServedByAnotherReadingOfTheSyntaxTree() throws Exception {
        String body;
        String section;
        try (Started first = new Started("cache")) {
            ExpertTools planner = first.toolsOf("planner");
            body = planner.bodyOf("StoredBasket#size");
            section = planner.docSection("project/docs/guide.md#Storing");
        }

        // The tree is parsed again - here because its cache is another folder; in the product
        // because the project is at another commit.
        try (Started second = new Started("another-cache")) {
            ExpertTools reviewer = second.toolsOf("reviewer");

            assertThat(reviewer.docSection("project/docs/guide.md#Storing"))
                .as("a document's section was not answered from the tree").isEqualTo(section);
            assertThat(second.answers.served()).isEqualTo(1);
            assertThat(reviewer.bodyOf("StoredBasket#size")).isEqualTo(body);
            assertThat(second.answers.served()).as("the tree's answer was computed by this "
                + "reading of the tree").isEqualTo(1);
        }
    }

    @Test
    void anAnswerShapedByOneSessionIsNotKeptBetweenStarts() throws Exception {
        try (Started first = new Started("cache")) {
            ExpertTools planner = first.toolsOf("planner");
            assertThat(planner.readFile("project/docs/guide.md")).contains("How baskets work.");
            assertThat(first.toolsOf("reviewer").readFile("project/docs/guide.md"))
                .contains("How baskets work.");
            assertThat(first.answers.served()).as("kept while the application runs").isEqualTo(1);
        }
        try (Started second = new Started("cache")) {
            second.toolsOf("reviewer").readFile("project/docs/guide.md");
            assertThat(second.answers.served()).isZero();
        }
    }

    @Test
    void anEditInAReferenceFolderTakesTheWholeProjectAnswersAtOnce() throws Exception {
        try (Started only = new Started("cache")) {
            ExpertTools planner = only.toolsOf("planner");
            String before = planner.findUsages("org.lib.Stored");
            assertThat(only.toolsOf("reviewer").findUsages("org.lib.Stored")).isEqualTo(before);
            assertThat(only.answers.served()).isEqualTo(1);

            // The same path is written again, seconds later: the folder's identity as the
            // product's indexes keep it is remembered for a minute and, for a checkout with
            // uncommitted changes, does not move at all for a path already changed.
            Path file = lib.resolve("src/main/java/org/lib/Stored.java");
            Files.writeString(file, Files.readString(file)
                .replace("public void write(Object value) {",
                    "public void write(Object value, boolean now) {"));

            only.toolsOf("test author").findUsages("org.lib.Stored");
            assertThat(only.answers.served()).as("not served across the edit").isEqualTo(1);
            assertThat(only.answers.dropped()).isEqualTo(1);
        }
    }
}
