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
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 100. In a five-story build 1,345 of 6,031 lookups were the first time anybody asked
 * about that target, and 101 targets were each looked up by three roles or more. A deterministic
 * lookup is answered once for the project; the next asker - another role, another story, a
 * sibling candidate - is given that answer while every file it stood on is as it was.
 */
class AProjectEstablishesAnAnswerOnceTest {

    private static final String STORED =
        "shop-server/src/main/java/com/shop/server/StoredBasket.java";

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;

    @BeforeEach
    void aProjectOfTwoModules() throws Exception {
        app = world.resolve("app");
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
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, world.resolve("cache"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private ExpertTools toolsOf(String role) {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User(role, "hand in", 0, null, 0, 0));
    }

    @Test
    void aSecondRoleIsGivenTheFirstRolesAnswerAndNothingIsComputedAgain() {
        SharedAnswers answers = curator.answers();

        String planners = toolsOf("planner").bodyOf("StoredBasket#size");
        String reviewers = toolsOf("reviewer").bodyOf("StoredBasket#size");
        String experts = new ExpertTools(curator, null, null, new CloudGate(0, null))
            .bodyOf("StoredBasket#size");

        assertThat(planners).contains("return items.size();");
        assertThat(reviewers).as("word for word what the planner was given").isEqualTo(planners);
        assertThat(experts).isEqualTo(planners);
        assertThat(answers.asked()).isEqualTo(3);
        assertThat(answers.served()).as("two of the three were not computed").isEqualTo(2);
        assertThat(answers.servedChars()).isEqualTo(2L * planners.length());
        assertThat(answers.summary()).contains("2 given an answer already established");
    }

    @Test
    void theComputationItselfRunsOnceHoweverManyAsk() {
        SharedAnswers answers = curator.answers();
        AtomicInteger computed = new AtomicInteger();

        for (String role : List.of("architect", "planner", "test author", "worker")) {
            SharedAnswers.Served given = answers.answer("role", "body_of", " StoredBasket#add ",
                SharedAnswers.Reach.ONE_PLACE, role, () -> {
                    computed.incrementAndGet();
                    return new SharedAnswers.Computed("// project/" + STORED + ":12\nadd", "x");
                });
            assertThat(given.text()).endsWith("add");
            assertThat(given.tag()).isEqualTo("x");
            assertThat(given.fromStore()).isEqualTo(!"architect".equals(role));
        }

        assertThat(computed).hasValue(1);
    }

    @Test
    void aChangedFileTakesTheAnswersBuiltOnItAndLeavesTheOthers() throws Exception {
        SharedAnswers answers = curator.answers();
        ExpertTools first = toolsOf("architect");
        String body = first.bodyOf("StoredBasket#size");
        String shape = first.publicShape("com.shop.Basket");
        String usages = first.findUsages("com.shop.Basket");
        assertThat(shape).contains("size()");
        assertThat(answers.served()).isZero();

        // A later story changed one file of the project.
        Path stored = app.resolve(STORED);
        Files.writeString(stored, Files.readString(stored)
            .replace("return items.size();", "return items.size() + 0;"));

        ExpertTools later = toolsOf("planner");
        String bodyNow = later.bodyOf("StoredBasket#size");
        String shapeNow = later.publicShape("com.shop.Basket");
        String usagesNow = later.findUsages("com.shop.Basket");

        assertThat(bodyNow).as("never the answer from before the change")
            .contains("return items.size() + 0;").isNotEqualTo(body);
        assertThat(shapeNow).as("Basket's file is as it was").isEqualTo(shape);
        assertThat(usagesNow).isEqualTo(usages);
        assertThat(answers.served()).as("only the shape of the untouched type was served: where "
            + "a type is used can change with any file, so that was asked again").isEqualTo(1);
        assertThat(answers.dropped()).isEqualTo(2);

        // And the new answer is the one kept from here on.
        assertThat(toolsOf("reviewer").bodyOf("StoredBasket#size")).isEqualTo(bodyNow);
        assertThat(answers.served()).isEqualTo(2);
    }

    @Test
    void aFileThatAppearsTakesTheAnswerThatSaidItWasNotThere() throws Exception {
        ExpertTools first = toolsOf("architect");
        String missing = first.readFile("project/docs/notes.md");
        assertThat(missing).startsWith("error:");

        write(app.resolve("docs/notes.md"), "# Notes\n\nBaskets are small.\n");

        assertThat(toolsOf("planner").readFile("project/docs/notes.md"))
            .contains("Baskets are small.");
    }

    @Test
    void siblingCandidatesAskingAtTheSameMomentCauseOneComputation() throws Exception {
        SharedAnswers answers = curator.answers();
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch together = new CountDownLatch(1);
        ExecutorService candidates = Executors.newFixedThreadPool(4);
        try {
            List<Future<SharedAnswers.Served>> asked = new java.util.ArrayList<>();
            for (int candidate = 0; candidate < 4; candidate++) {
                asked.add(candidates.submit(() -> {
                    together.await();
                    return answers.answer("worker", "body_of", "StoredBasket#size",
                        SharedAnswers.Reach.ONE_PLACE, "worker", () -> {
                            computed.incrementAndGet();
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return new SharedAnswers.Computed(
                                new TreeQueries(curator).bodyOf("StoredBasket#size"), null);
                        });
                }));
            }
            together.countDown();
            int served = 0;
            String text = null;
            for (Future<SharedAnswers.Served> one : asked) {
                SharedAnswers.Served given = one.get(30, TimeUnit.SECONDS);
                served += given.fromStore() ? 1 : 0;
                assertThat(given.text()).contains("return items.size();");
                assertThat(text == null || text.equals(given.text())).isTrue();
                text = given.text();
            }
            assertThat(computed).as("the first candidate's lookup is the siblings' too")
                .hasValue(1);
            assertThat(served).isEqualTo(3);
        } finally {
            candidates.shutdownNow();
        }
    }

    @Test
    void whatIsChosenByTheSessionOrAnsweredByAModelIsNotKept() {
        assertThat(SharedAnswers.reachOf("search", "basket")).isNull();
        assertThat(SharedAnswers.reachOf("docs_for", "basket")).isNull();
        assertThat(SharedAnswers.reachOf("find_example", "basket")).isNull();
        assertThat(SharedAnswers.reachOf("ask_expert", "how")).isNull();
        assertThat(SharedAnswers.reachOf("known_about", "Basket")).isNull();
        assertThat(SharedAnswers.reachOf("body_of", "Basket#size"))
            .isEqualTo(SharedAnswers.Reach.ONE_PLACE);
        assertThat(SharedAnswers.reachOf("doc_outline", ""))
            .as("every document: any new file changes it").isEqualTo(SharedAnswers.Reach.EVERYWHERE);
        assertThat(SharedAnswers.reachOf("usages_of", "Basket"))
            .isEqualTo(SharedAnswers.Reach.EVERYWHERE);
    }

    @Test
    void whatARoleConcludedIsKeptWithWhoFoundItAndOfferedByTitle() throws Exception {
        ExpertTools planner = toolsOf("planner");
        ToolBinding keep = planner.keepFindingBinding();
        assertThat(keep).isNotNull();
        assertThat(keep.name()).isEqualTo("keep_finding");

        assertThat(planner.keepFinding("com.shop.server.StoredBasket", "body_of StoredBasket#add",
            "Items are appended.")).as("a finding comes from a lookup that was made")
            .startsWith("error:");
        planner.bodyOf("StoredBasket#add");
        String kept = planner.keepFinding("com.shop.server.StoredBasket",
            "body_of StoredBasket#add", "A basket keeps its items in a list in memory; add "
                + "appends and nothing is written to disk.");
        assertThat(kept).startsWith("Kept for every later session");

        // A later session of another role: the title in its opening, the text behind the lookup.
        KnownFacts known = curator.answers().known();
        assertThat(known.size()).isEqualTo(1);
        assertThat(known.index(2_400)).contains("StoredBasket (1)", "who found it")
            .as("titles only").doesNotContain("appends and nothing is written");
        ExpertTools author = toolsOf("test author");
        author.know(known);
        String told = author.knownAbout("StoredBasket");
        assertThat(told).contains("appends and nothing is written to disk",
            "from: body_of StoredBasket#add", "found by: the planner", "unchanged since");

        // The file that lookup read changes: the finding is no longer served as true.
        Path stored = app.resolve(STORED);
        Files.writeString(stored, Files.readString(stored)
            .replace("items.add(item);", "items.add(0, item);"));
        ExpertTools reviewer = toolsOf("reviewer");
        reviewer.know(curator.answers().known());
        assertThat(reviewer.knownAbout("StoredBasket")).contains("CHANGED SINCE",
            "Make that lookup again");
        assertThat(curator.answers().knownAbout("StoredBasket"))
            .as("a worker is told the same").contains("CHANGED SINCE", "found by: the planner");
    }
}
