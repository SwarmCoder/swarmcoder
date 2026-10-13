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
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspHit;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 101. A role's answer and a worker's answer to the same question were kept apart,
 * because a role reads a file as {@code <root>/<path>} and a worker by its path in the
 * checkout. The fact under both is one: it is computed once, and each reader is given it with
 * its own addresses - character for character what its own computation gives.
 */
class ARoleAndAWorkerShareOneComputationTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;
    private final List<String> askedOfTheServer = new ArrayList<>();
    private volatile boolean serverDown;
    private final AtomicInteger own = new AtomicInteger();

    private final LspService server = new LspService() {
        @Override public List<LspDiagnostic> diagnostics(Path file) {
            return List.of();
        }
        @Override public boolean isAvailable() {
            return true;
        }
        @Override public boolean isInstalled() {
            return true;
        }
        @Override public LspResult definition(String symbol) {
            askedOfTheServer.add("definition " + symbol);
            return LspResult.ok("declared at:", List.of(new LspHit(
                "shop-shared/src/main/java/com/shop/Basket.java", 3, "interface Basket")), 1);
        }
        @Override public LspResult references(String symbol) {
            askedOfTheServer.add("references " + symbol);
            return LspResult.ok("1 reference(s) to `" + symbol + "`:", List.of(new LspHit(
                "shop-server/src/main/java/com/shop/server/StoredBasket.java", 5,
                "public class StoredBasket implements Basket {")), 1);
        }
        @Override public LspResult callers(String method) {
            askedOfTheServer.add("callers " + method);
            if (serverDown) {
                return LspResult.unavailable("it stopped");
            }
            return LspResult.ok("1 call(s) of `" + method + "` in this project.",
                List.of(new LspHit("shop-server/src/main/java/com/shop/server/StoredBasket.java",
                    9, "return items.size();")), 1);
        }
        @Override public void close() {
        }
    };

    @BeforeEach
    void aProjectAndWhatItBuildsOn() throws Exception {
        app = world.resolve("app");
        Path lib = world.resolve("lib");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version><modules>"
            + "<module>shop-shared</module><module>shop-server</module></modules></project>");
        write(app.resolve("shop-shared/pom.xml"), "<project><parent><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></parent>"
            + "<artifactId>shop-shared</artifactId></project>");
        write(app.resolve("shop-server/pom.xml"), "<project><parent><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></parent>"
            + "<artifactId>shop-server</artifactId><dependencies><dependency>"
            + "<groupId>com.shop</groupId><artifactId>shop-shared</artifactId>"
            + "<version>1</version></dependency></dependencies></project>");
        write(app.resolve("shop-shared/src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public interface Basket {
                int size();
            }
            """);
        write(app.resolve("shop-server/src/main/java/com/shop/server/StoredBasket.java"), """
            package com.shop.server;

            import com.shop.Basket;

            public class StoredBasket implements Basket {
                private final java.util.List<String> items = new java.util.ArrayList<>();

                public int size() {
                    return items.size();
                }
            }
            """);
        write(app.resolve("shop-server/src/main/resources/app.properties"),
            "basket.max=40\nbasket.name=Shop\n");
        write(app.resolve("docs/guide.md"), "# Guide\n\nHow baskets work.\n\n## Storing\n\n"
            + "A basket is stored whole.\n\n## Reading\n\nA basket is read by its owner.\n");
        write(lib.resolve("pom.xml"), "<project><groupId>org.lib</groupId>"
            + "<artifactId>lib</artifactId><version>2</version></project>");
        write(lib.resolve("src/main/java/org/lib/Stored.java"), """
            package org.lib;

            public class Stored {
                public void write(Object value) {
                }
            }
            """);
        write(lib.resolve("docs/storing.md"), "# Storing\n\nWrite an object.\n\n## Roots\n\n"
            + "A basket of roots is where storing starts.\n");
        curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("lib", lib, "2")), null, world.resolve("cache"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String asARole(String query, String argument, Supplier<String> compute) {
        return curator.answers().answerFor(Addresses.OF_A_ROLE, SharedAnswers.ROLE, query,
            argument, SharedAnswers.reachOf(query, argument), "planner", () -> {
                own.incrementAndGet();
                return new SharedAnswers.Computed(compute.get(), null);
            }).text();
    }

    private SharedAnswers.Served asAWorker(String query, String argument,
                                           Supplier<String> compute) {
        return curator.answers().answerFor(Addresses.ofAWorker("project"), SharedAnswers.WORKER,
            query, argument, SharedAnswers.reachOf(query, argument), "worker", () -> {
                own.incrementAndGet();
                return new SharedAnswers.Computed(compute.get(), null);
            });
    }

    private String rolesOwn(String query, String argument) {
        return ownOf(new TreeQueries(curator), new DocumentQueries(curator), query, argument);
    }

    private String workersOwn(String query, String argument) {
        return ownOf(TreeQueries.forAWorkerOf(curator, app),
            DocumentQueries.forAWorkerOf(curator, app), query, argument);
    }

    private static String ownOf(TreeQueries tree, DocumentQueries documents, String query,
                                String argument) {
        return switch (query) {
            case "types_in" -> tree.typesIn(argument);
            case "build_of" -> tree.buildOf(argument);
            case "resources_of" -> tree.resourcesOf(argument);
            case "body_of" -> tree.bodyOf(argument);
            case "doc_outline" -> documents.outlineOf(argument);
            case "doc_section" -> documents.sectionOf(argument);
            case "doc_search" -> documents.search(argument);
            default -> throw new IllegalArgumentException(query);
        };
    }

    /** A question as a role writes it and as a worker writes it. */
    private record Asked(String query, String byARole, String byAWorker) {
        Asked(String query, String both) {
            this(query, both, both);
        }
    }

    @Test
    void eachReaderIsGivenItsOwnTextAndTheFactIsComputedOnce() {
        SharedAnswers answers = curator.answers();
        List<Asked> questions = List.of(
            new Asked("types_in", "shop-server"),
            new Asked("types_in", "com.shop"),
            new Asked("types_in", "org.lib"),
            new Asked("build_of", ""),
            new Asked("build_of", "shop-server"),
            new Asked("build_of", "lib"),
            new Asked("body_of", "StoredBasket#size"),
            new Asked("body_of", "com.shop.Basket"),
            new Asked("body_of", "org.lib.Stored#write"),
            new Asked("body_of", "NoSuchType"),
            new Asked("resources_of", "shop-server"),
            new Asked("resources_of", "shop-server/src/main/resources/app.properties"),
            new Asked("doc_outline", ""),
            new Asked("doc_outline", "project/docs/guide.md", "docs/guide.md"),
            new Asked("doc_outline", "lib/docs/storing.md", "/reference/lib/docs/storing.md"),
            new Asked("doc_section", "project/docs/guide.md#Storing", "docs/guide.md#Storing"),
            new Asked("doc_section", "lib/docs/storing.md#2",
                "/reference/lib/docs/storing.md#2"),
            new Asked("doc_section", "project/docs/guide.md#Nothing", "docs/guide.md#Nothing"),
            new Asked("doc_search", "basket"));

        for (Asked one : questions) {
            String rolesText = rolesOwn(one.query(), one.byARole());
            String workersText = workersOwn(one.query(), one.byAWorker());
            long computedBefore = answers.asked() - answers.served();
            own.set(0);

            String role = asARole(one.query(), one.byARole(),
                () -> rolesOwn(one.query(), one.byARole()));
            SharedAnswers.Served worker = asAWorker(one.query(), one.byAWorker(),
                () -> workersOwn(one.query(), one.byAWorker()));

            String what = one.query() + " " + one.byARole();
            assertThat(role).as("a role's text is what it was: %s", what).isEqualTo(rolesText);
            assertThat(worker.text()).as("a worker's text is what it was: %s", what)
                .isEqualTo(workersText);
            assertThat(own).as("neither reader's own computation ran: %s", what).hasValue(0);
            assertThat(worker.fromStore()).as("the worker was served what the role's question "
                + "computed: %s", what).isTrue();
            assertThat(answers.asked() - answers.served()).as("one computation for the two: %s",
                what).isEqualTo(computedBefore + 1);
        }
        // The addresses do differ: that is all that differs.
        assertThat(asARole("body_of", "StoredBasket#size", () -> ""))
            .contains("// project/shop-server/src/main/java/com/shop/server/StoredBasket.java:");
        assertThat(asAWorker("body_of", "StoredBasket#size", () -> "").text())
            .contains("// shop-server/src/main/java/com/shop/server/StoredBasket.java:")
            .doesNotContain("project/");
        assertThat(asAWorker("body_of", "org.lib.Stored#write", () -> "").text())
            .contains("// /reference/lib/src/main/java/org/lib/Stored.java:");
    }

    @Test
    void whatIsWrittenByEachReadersOwnRulesIsStillComputedForEach() {
        SharedAnswers answers = curator.answers();
        // A root itself and a folder of documents are written by each reader's own rules.
        for (Asked one : List.of(new Asked("resources_of", "project"),
                new Asked("doc_outline", "project/docs", "docs"),
                new Asked("doc_outline", "no/such.md"))) {
            String rolesText = rolesOwn(one.query(), one.byARole());
            String workersText = workersOwn(one.query(), one.byAWorker());
            own.set(0);
            assertThat(asARole(one.query(), one.byARole(),
                () -> rolesOwn(one.query(), one.byARole()))).isEqualTo(rolesText);
            assertThat(asAWorker(one.query(), one.byAWorker(),
                () -> workersOwn(one.query(), one.byAWorker())).text()).isEqualTo(workersText);
            assertThat(own).as("each reader's own: %s %s", one.query(), one.byARole())
                .hasValue(2);
        }
        // A worker whose checkout is not the root the queries take for the project's own.
        own.set(0);
        asARole("body_of", "StoredBasket#size", () -> "");
        String elsewhere = answers.answerFor(Addresses.ofAWorker(""), SharedAnswers.WORKER,
            "body_of", "StoredBasket#size", SharedAnswers.Reach.ONE_PLACE, "worker", () -> {
                own.incrementAndGet();
                return new SharedAnswers.Computed("its own", null);
            }).text();
        assertThat(elsewhere).isEqualTo("its own");
        assertThat(own).hasValue(1);
    }

    @Test
    void aChangedFileTakesTheOneFactFromBothReaders() throws Exception {
        String before = asARole("body_of", "StoredBasket#size", () -> "");
        Path stored = app.resolve("shop-server/src/main/java/com/shop/server/StoredBasket.java");
        Files.writeString(stored, Files.readString(stored)
            .replace("return items.size();", "return items.size() + 0;"));

        SharedAnswers.Served worker = asAWorker("body_of", "StoredBasket#size", () -> "");

        assertThat(before).contains("return items.size();");
        assertThat(worker.fromStore()).as("never the role's answer from before the change")
            .isFalse();
        assertThat(worker.text()).contains("return items.size() + 0;");
    }

    @Test
    void theLanguageServerIsAskedOnceForARoleAndAWorker() {
        curator.languageServer(server, app);
        ExpertTools planner = new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("planner", "hand in", 0, null, 0, 0));

        // Where a type is used: each reader's answer is worded by its own tool, and the
        // server's part under both is asked once.
        String role = planner.findUsages("Basket");
        String worker = LanguageQueries.forAWorkerOf(curator).usagesOrNull("Basket");

        assertThat(role).contains(
            "project/shop-server/src/main/java/com/shop/server/StoredBasket.java:5");
        assertThat(worker).contains(
            "shop-server/src/main/java/com/shop/server/StoredBasket.java:5")
            .doesNotContain("project/");
        assertThat(askedOfTheServer).as("one question to the server for the two readers")
            .containsExactly("definition Basket", "references Basket");

        // A query that is the server's own is one answer for both.
        askedOfTheServer.clear();
        String rolesCallers = planner.callersOf("Basket#size");
        SharedAnswers.Served workersCallers = asAWorker("callers_of", "Basket#size", () -> "");
        assertThat(rolesCallers).contains(
            "project/shop-server/src/main/java/com/shop/server/StoredBasket.java:9");
        assertThat(workersCallers.fromStore()).isTrue();
        assertThat(workersCallers.tag()).as("a worker's record still says who answered")
            .isEqualTo("server");
        assertThat(askedOfTheServer).containsExactly("callers Basket#size");
        assertThat(workersCallers.text()).as("what a worker's own question to the server gives")
            .isEqualTo(LanguageQueries.forAWorkerOf(curator).callersOf("Basket#size"));
    }

    @Test
    void thatTheServerDidNotAnswerIsNotKeptAsAnAnswer() {
        curator.languageServer(server, app);
        serverDown = true;
        SharedAnswers.Served first = asAWorker("callers_of", "Basket#size", () -> "");
        assertThat(first.text()).contains("not available");

        serverDown = false;
        SharedAnswers.Served second = asAWorker("callers_of", "Basket#size", () -> "");

        assertThat(second.fromStore()).as("the server was asked again").isFalse();
        assertThat(second.text()).contains("1 call(s) of `Basket#size`");
    }
}
