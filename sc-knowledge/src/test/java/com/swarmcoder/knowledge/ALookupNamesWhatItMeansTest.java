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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 105: an empty project built on a reference library with several
 * example applications. A lookup by a name several types share lists them and picks none
 * (finding A), one part of a build file is answered as the file writes it (C), and a file's
 * imports are a lookup (D) - for a role and for a worker.
 */
class ALookupNamesWhatItMeansTest {

    private static final String SHOP = "lib/examples/shop/src/main/java/com/example/";
    private static final String BANK = "lib/examples/bank/src/main/java/com/example/";

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;

    @BeforeEach
    void anEmptyProjectOnALibraryWithTwoExampleApplications() throws Exception {
        app = world.resolve("app");
        Path lib = world.resolve("lib");
        write(app.resolve("pom.xml"), "<project><groupId>com.mine</groupId>"
            + "<artifactId>mine</artifactId><version>1</version></project>");
        // The same full name in both applications ...
        write(lib.resolve("examples/shop/src/main/java/com/example/DataRoot.java"), """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            public class DataRoot {
                private final List<String> articles = new ArrayList<>();

                public List<String> open() {
                    return articles;
                }
            }
            """);
        write(lib.resolve("examples/bank/src/main/java/com/example/DataRoot.java"), """
            package com.example;

            import java.util.HashMap;
            import java.util.Map;

            public class DataRoot {
                private final Map<String, Long> accounts = new HashMap<>();

                public Map<String, Long> open() {
                    return accounts;
                }
            }
            """);
        // ... and one simple name under two full names.
        write(lib.resolve("examples/shop/src/main/java/com/example/shop/ExampleServer.java"), """
            package com.example.shop;

            public class ExampleServer {
                public String start() {
                    return "shop";
                }
            }
            """);
        write(lib.resolve("examples/bank/src/main/java/com/example/bank/ExampleServer.java"), """
            package com.example.bank;

            public class ExampleServer {
                public String start() {
                    return "bank";
                }
            }
            """);
        write(lib.resolve("examples/shop/pom.xml"), """
            <project>
              <parent><groupId>com.example</groupId><artifactId>examples</artifactId>
                <version>3</version></parent>
              <artifactId>shop</artifactId>
              <properties><store.version>2.4</store.version></properties>
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>store</artifactId>
                  <version>${store.version}</version></dependency>
                <dependency><groupId>org.example</groupId><artifactId>screens</artifactId>
                  <version>7</version></dependency>
              </dependencies>
              <build><plugins>
                <plugin><groupId>org.example</groupId><artifactId>client-generator</artifactId>
                  <version>1.2</version>
                  <configuration><services><service>com.example.shop.Api</service></services>
                    <target>generated</target></configuration>
                  <executions><execution><goals><goal>generate</goal></goals></execution>
                  </executions></plugin>
                <plugin><groupId>org.example</groupId><artifactId>packer</artifactId>
                  <version>4</version></plugin>
              </plugins></build>
            </project>
            """);
        curator = new KnowledgeCurator(List.of(new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("lib", lib, "local")), null, world.resolve("cache"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private ExpertTools aRole(String name) {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User(name, "hand in", 0, null, 0, 0));
    }

    // --- A: a name several types share ---------------------------------------------------------

    @Test
    void aBodyAskedForByANameOfSeveralTypesListsThemAndPicksNone() {
        ExpertTools architect = aRole("architect");

        String asked = architect.bodyOf("DataRoot#open");

        assertThat(asked).as("both, with where each is, and neither one's code")
            .contains("`DataRoot` is the name of 2 types").contains("none was picked")
            .contains(BANK + "DataRoot.java").contains(SHOP + "DataRoot.java")
            .contains("For example: body_of " + BANK + "DataRoot.java#open")
            .doesNotContain("return articles").doesNotContain("return accounts");

        assertThat(architect.bodyOf(SHOP + "DataRoot.java#open"))
            .as("asked for by the path the list gave, it is that one")
            .contains("// " + SHOP + "DataRoot.java:").contains("return articles")
            .doesNotContain("return accounts");
        assertThat(architect.bodyOf(BANK + "DataRoot.java#open")).contains("return accounts");
    }

    @Test
    void aSimpleNameUnderTwoFullNamesIsAskedForByItsFullName() {
        ExpertTools architect = aRole("architect");

        assertThat(architect.publicShape("ExampleServer"))
            .contains("`ExampleServer` is the name of 2 types")
            .contains("com.example.bank.ExampleServer  - class, " + BANK
                + "bank/ExampleServer.java:")
            .contains("For example: public_shape com.example.bank.ExampleServer")
            .doesNotContain("start()");
        assertThat(architect.publicShape("com.example.shop.ExampleServer"))
            .contains("class com.example.shop.ExampleServer").contains("start()")
            .contains("declared in " + SHOP + "shop/ExampleServer.java:");
        assertThat(architect.bodyOf("com.example.shop.ExampleServer#start"))
            .contains("return \"shop\"");
        assertThat(architect.textsOf("ExampleServer")).contains("is the name of 2 types");
        assertThat(architect.filesUsing("ExampleServer"))
            .as("no file uses every type of a shared name: said, not answered as nothing")
            .contains("is the name of 2 types")
            .contains("com.example.bank.ExampleServer, com.example.shop.ExampleServer");
    }

    @Test
    void whatAnOutlinePrintsIsWhatTheLookupTakes() {
        ExpertTools architect = aRole("architect");

        String outline = architect.outlineOf(BANK + "DataRoot.java");

        assertThat(outline).as("the name alone would be answered with a list")
            .contains("body_of " + BANK + "DataRoot.java#<member>")
            .contains("body_of " + BANK + "DataRoot.java#import");
        assertThat(architect.outlineOf(SHOP + "shop/ExampleServer.java"))
            .contains("body_of " + SHOP + "shop/ExampleServer.java#<member>");
    }

    @Test
    void aWorkerIsAnsweredTheSameWithItsOwnAddresses() {
        TreeQueries worker = TreeQueries.forAWorkerOf(curator, app);

        assertThat(worker.bodyOf("DataRoot#open"))
            .contains("`DataRoot` is the name of 2 types")
            .contains("For example: body_of /reference/" + BANK + "DataRoot.java#open");
        assertThat(worker.bodyOf("/reference/" + SHOP + "DataRoot.java#open"))
            .contains("// /reference/" + SHOP + "DataRoot.java:").contains("return articles");
        assertThat(worker.shapeOf("ExampleServer")).contains("is the name of 2 types")
            .contains("For example: shape_of com.example.bank.ExampleServer");
        assertThat(worker.shapeOf("/reference/" + BANK + "bank/ExampleServer.java"))
            .contains("class com.example.bank.ExampleServer")
            .contains("declared in /reference/" + BANK + "bank/ExampleServer.java:");
        assertThat(worker.holds("DataRoot")).as("the tree answers, the server is not asked")
            .isTrue();
        assertThat(worker.othersNamed("DataRoot#open"))
            .as("the line a worker's own type of that name is answered with")
            .contains("2 other type(s) of that name are in the reference material")
            .contains("/reference/" + SHOP + "DataRoot.java");
        assertThat(worker.othersNamed("Nothing")).isEmpty();
    }

    @Test
    void anAnswerThatListsCandidatesIsKeptLikeAnyOther() {
        String first = aRole("architect").bodyOf("DataRoot#open");
        long servedBefore = curator.answers().served();

        String second = aRole("planner").bodyOf("DataRoot#open");

        assertThat(second).isEqualTo(first);
        assertThat(curator.answers().served()).as("the planner's was not computed again")
            .isEqualTo(servedBefore + 1);
    }

    // --- D: a file's imports -------------------------------------------------------------------

    @Test
    void theImportsOfATypesFileAreALookup() {
        String imports = aRole("architect").bodyOf(SHOP + "DataRoot.java#import");

        assertThat(imports).contains("// " + SHOP + "DataRoot.java:1-4 - 2 import(s)")
            .contains("package com.example;").contains("import java.util.ArrayList;")
            .contains("import java.util.List;").doesNotContain("class DataRoot");
        assertThat(TreeQueries.forAWorkerOf(curator, app)
            .bodyOf("com.example.bank.ExampleServer#import"))
            .contains("package com.example.bank;").contains("0 import(s)");
        assertThat(aRole("architect").bodyOf(SHOP + "DataRoot.java#import,open"))
            .as("with a member, in one call")
            .contains("import java.util.List;").contains("return articles");
    }

    // --- C: one part of a build file, as the file writes it ------------------------------------

    @Test
    void onePartOfABuildFileIsAnsweredInTheFilesOwnSyntax() throws Exception {
        TreeQueries tree = new TreeQueries(curator);
        int wholeFile = Files.readString(world.resolve("lib/examples/shop/pom.xml")).length();

        String plugin = tree.buildOf("lib/examples/shop#client-generator");

        assertThat(plugin).as("the declaration somebody writing a build file copies")
            .contains("lib/examples/shop/pom.xml: build/plugins/plugin")
            .contains("<artifactId>client-generator</artifactId>")
            .contains("<service>com.example.shop.Api</service>")
            .contains("<goal>generate</goal>")
            .doesNotContain("packer").doesNotContain("<dependencies>");
        assertThat(plugin.length()).isLessThan(wholeFile);

        assertThat(tree.buildOf("lib/examples/shop#parent,properties"))
            .contains("<parent>").contains("<artifactId>examples</artifactId>")
            .contains("<store.version>2.4</store.version>").doesNotContain("<plugin>");
        assertThat(tree.buildOf("lib/examples/shop#dependencies"))
            .contains("<artifactId>store</artifactId>").contains("<artifactId>screens</artifactId>")
            .doesNotContain("<plugin>");
        assertThat(tree.buildOf("lib/examples/shop#nothing-of-that-name"))
            .contains("has no part `nothing-of-that-name`")
            .contains("parent, properties, dependencies, build")
            .contains("client-generator, packer");
        assertThat(tree.buildOf("lib/examples/shop"))
            .as("the summary says how to ask for a part")
            .contains("plugin org.example:client-generator:1.2:")
            .contains("#<artifactId> for one plugin or dependency");
        assertThat(TreeQueries.forAWorkerOf(curator, app)
            .buildOf("/reference/lib/examples/shop#packer"))
            .as("a worker, by the address its lookups print")
            .contains("/reference/lib/examples/shop/pom.xml: build/plugins/plugin")
            .contains("<artifactId>packer</artifactId>").doesNotContain("client-generator");
    }
}
