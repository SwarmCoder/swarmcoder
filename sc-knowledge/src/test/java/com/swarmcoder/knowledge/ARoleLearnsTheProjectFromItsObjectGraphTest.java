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
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 80, 2026-10-04. One 783-line story cost 2.2 million prompt tokens in the planner and
 * the test author. Of the 406,000 characters they looked up, 260,000 were text searches (12,000
 * each, two files quoted per search) and 121,000 whole files; the object graph's own answers
 * ({@code public_shape}, {@code find_usages}) were 10,000. The roles were meant to learn the
 * project from the graph.
 */
class ARoleLearnsTheProjectFromItsObjectGraphTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;

    @BeforeEach
    void aProjectOfTwoModules() throws Exception {
        app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>");
        write(app.resolve("shop-server/pom.xml"), """
            <project>
              <groupId>com.shop</groupId><artifactId>shop-server</artifactId><version>1</version>
              <properties><ledger.version>2.1</ledger.version></properties>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId><artifactId>ledger</artifactId>
                  <version>${ledger.version}</version><scope>test</scope>
                </dependency>
              </dependencies>
              <build><plugins><plugin>
                <groupId>org.example</groupId><artifactId>packer</artifactId><version>3</version>
              </plugin></plugins></build>
            </project>
            """);
        write(app.resolve("shop-shared/src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public interface Basket {
                int size();
                void add(String item);
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

                public void add(String item) {
                    items.add(item);
                }
            }
            """);
        write(app.resolve("shop-server/src/test/java/com/shop/server/StoredBasketTest.java"), """
            package com.shop.server;

            class StoredBasketTest {
            }
            """);
        write(app.resolve("docs/testing.md"), "# Testing\n\nBaskets are tested through the server.\n");
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local")),
            null, world.resolve("cache"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private ExpertTools aRolesTools() {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("planner", "hand in", 0, null, 0, 0));
    }

    @Test
    void aRolesToolsStartWithTheGraphAndTheExpertsListIsWhatItWas() {
        List<String> role = aRolesTools().lookupBindings().stream().map(ToolBinding::name).toList();
        List<String> expert = new ExpertTools(curator, null, null, new CloudGate(0, null))
            .lookupBindings().stream().map(ToolBinding::name).toList();

        assertThat(role.subList(0, 4))
            .containsExactly("public_shape", "types_in", "body_of", "find_usages");
        assertThat(role).contains("build_of", "search", "read_file", "list_files");
        assertThat(role.indexOf("search")).isGreaterThan(role.indexOf("build_of"));
        assertThat(role.indexOf("read_file")).isGreaterThan(role.indexOf("search"));
        assertThat(expert.subList(0, 3)).as("the expert is offered the same tree queries")
            .containsExactly("public_shape", "types_in", "body_of");
        assertThat(role).as("nothing was taken away")
            .contains("find_usages", "files_using", "call_chain", "dependency_declaring",
                "types_annotated_with", "skeleton_for");
    }

    @Test
    void whatAPackageOrAModuleHoldsIsAnsweredFromTheGraphWithFileAndLine() {
        ExpertTools tools = aRolesTools();

        String byPackage = tools.typesIn("com.shop");
        String byModule = tools.typesIn("shop-server");

        assertThat(byPackage)
            .contains("3 type(s) in 2 package(s)")
            .contains("interface Basket {")
            .contains("add(")
            .contains("class StoredBasket is-a Basket")
            .contains("project/shop-shared/src/main/java/com/shop/Basket.java:3");
        assertThat(byModule).contains("StoredBasket").doesNotContain("interface Basket");
        assertThat(byPackage.length())
            .as("a few hundred characters, where reading the files is thousands")
            .isLessThan(900);
    }

    @Test
    void whatAModulesBuildDeclaresIsAnsweredWithoutReadingTheBuildFile() {
        assertThat(aRolesTools().buildOf("shop-server"))
            .contains("project/shop-server/pom.xml")
            .contains("artifact: com.shop:shop-server:1")
            .contains("properties (1): ledger.version=2.1")
            .contains("dependencies (1): org.example:ledger:${ledger.version} [test]")
            .contains("plugins (1): org.example:packer:3")
            .doesNotContain("<dependency>");
    }

    @Test
    void oneMethodIsTakenOutOfTheTreeByItsTypesNameWithItsFileAndLines() {
        ExpertTools tools = aRolesTools();

        assertThat(tools.bodyOf("StoredBasket#add"))
            .startsWith("// project/shop-server/src/main/java/com/shop/server/StoredBasket.java:")
            .contains("items.add(item);")
            .doesNotContain("return items.size()");
        assertThat(tools.bodyOf("com.shop.server.StoredBasket#items"))
            .as("a field with its initialiser").contains("new java.util.ArrayList<>()");
        assertThat(tools.bodyOf("StoredBasket#remove"))
            .contains("declares no `remove`").contains("add").contains("size");
        assertThat(tools.bodyOf("Basket")).as("a whole type").contains("interface Basket");

        TreeQueries worker = TreeQueries.forAWorkerOf(curator, app);
        assertThat(worker.bodyOf("StoredBasket#size"))
            .as("a worker is given the path its own read tool takes")
            .startsWith("// shop-server/src/main/java/com/shop/server/StoredBasket.java:");
        assertThat(worker.shapeOf("Basket")).contains("interface com.shop.Basket");
        assertThat(worker.usagesOf("Basket")).contains("StoredBasket.java:");
    }

    @Test
    void everyLookupIsOnTheRunsRecordByKind() {
        com.swarmcoder.inference.RunMeter.enable();
        com.swarmcoder.inference.LookupMeter.reset();
        try {
            ExpertTools tools = aRolesTools();
            tools.publicShape("Basket");
            tools.bodyOf("StoredBasket#add");
            tools.search("StoredBasket");
            tools.readFile("project/docs/testing.md");
            tools.readFile("project/docs/testing.md:1-2");

            assertThat(com.swarmcoder.inference.LookupMeter.counts())
                .extracting(c -> c.role() + " " + c.kind() + " " + c.calls())
                .containsExactly("planner TREE 2", "planner SEARCH 1", "planner WHOLE_FILE 1",
                    "planner FILE_PART 1");
            assertThat(com.swarmcoder.inference.LookupMeter.ofShellCommand("grep -rn Basket ."))
                .isEqualTo(com.swarmcoder.inference.LookupMeter.Kind.SHELL_READ);
            assertThat(com.swarmcoder.inference.LookupMeter.ofShellCommand("mvn -o test")).isNull();
        } finally {
            com.swarmcoder.inference.RunMeter.disable();
            com.swarmcoder.inference.LookupMeter.reset();
        }
    }

    @Test
    void aSearchAnswersWithPlacesAndNotWithQuotedFiles() {
        String role = aRolesTools().search("StoredBasket add item");
        String expert = new ExpertTools(curator, null, null, new CloudGate(0, null))
            .search("StoredBasket add item");

        assertThat(role)
            .contains("StoredBasket")
            .contains("shop-server/src/main/java/com/shop/server/StoredBasket.java")
            .doesNotContain("## Source:")
            .doesNotContain("items.add(item)");
        assertThat(expert).as("the expert's search is the same").doesNotContain("## Source:");
        assertThat(role.length()).isLessThanOrEqualTo(ExpertTools.SEARCH_CHARS + 400);
        assertThat(aRolesTools().readFile(
                "project/shop-server/src/main/java/com/shop/server/StoredBasket.java"))
            .as("the body is still one read away, whole")
            .contains("items.add(item)");
    }

    @Test
    void theProjectMapIsTheSameTextEveryTimeAndSaysWhatTheGraphKnowsOfEachType() throws Exception {
        String map = ProjectMap.of(app, 5_000, curator);

        assertThat(map)
            .contains("shop-shared/src/main/java/com/shop/: Basket (interface 2)")
            .contains("StoredBasket (3 is-a Basket)")
            .contains("shop-server/src/test/java/com/shop/server/: StoredBasketTest")
            .contains("docs/: testing.md");
        assertThat(ProjectMap.of(app, 5_000, curator)).isEqualTo(map);

        write(app.resolve("shop-shared/src/main/java/com/shop/Price.java"),
            "package com.shop;\n\npublic record Price(long cents) { }\n");
        assertThat(ProjectMap.of(app, 5_000)).as("a new file is in the next map").contains("Price");
    }

    @Test
    void aProjectTooLargeForItsNamesGetsACoarserMapAndNeverACutOne() {
        String names = ProjectMap.of(app, 5_000);
        String coarse = ProjectMap.of(app, names.length() - 1);

        assertThat(names).contains("Basket (interface)");
        assertThat(coarse.length()).isLessThan(names.length());
        assertThat(coarse).as("every directory and every name is still there, the kinds are not")
            .contains("shop-shared/src/main/java/com/shop/: Basket\n")
            .contains("StoredBasketTest").doesNotContain("(interface)");
        assertThat(ProjectMap.of(world.resolve("nothing-here"), 5_000)).isEmpty();
    }

    @Test
    void onAServerThatCachesThePromptHeadOldResultsAreLeftAloneForAQuarterOfTheRoom() {
        AgentRuntime.SessionOptions options = LookupAgent.sessionOptions(null, 983_040);

        assertThat(options.tidyAboveTokens()).isEqualTo(LookupAgent.TIDY_ABOVE_TOKENS);
        assertThat(options.tidyAboveWhenCachedTokens()).isEqualTo(983_040 / 4);
        assertThat(LookupAgent.toolGuide(List.of("search")))
            .contains("OBJECT GRAPH FIRST")
            .contains("SEVERAL AT ONCE")
            .contains("Any file may be read whole");
    }
}
