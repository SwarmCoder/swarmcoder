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

import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspHit;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner's decision, 2026-10-04: roles find usages, navigate and learn a library type's members
 * through the Java language server and the syntax tree, with ONE tool per question. The server
 * here is a stand-in that records what it was asked; the real one is exercised by
 * {@code JdtLanguageServerLiveTest}.
 */
class ARoleAsksTheLanguageServerTest {

    @TempDir
    Path world;

    private Path app;
    private KnowledgeCurator curator;
    private final List<String> asked = new ArrayList<>();

    /** Knows one project type ({@code Basket}) and one type of a library jar ({@code Ledger}). */
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
            asked.add("definition " + symbol);
            if (symbol.startsWith("Basket")) {
                return LspResult.ok("declared at:", List.of(new LspHit(
                    "shop-shared/src/main/java/com/shop/Basket.java", 3, "interface Basket")), 1);
            }
            return symbol.contains("Ledger") ? LspResult.ok("declared at:",
                List.of(new LspHit("[ledger-2.1.jar] org.example.Ledger", 0, "")), 1)
                : LspResult.notFound("no type called " + symbol);
        }
        @Override public LspResult references(String symbol) {
            asked.add("references " + symbol);
            return LspResult.ok("1 reference(s) to `com.shop." + symbol + "`:", List.of(new LspHit(
                "shop-server/src/main/java/com/shop/server/StoredBasket.java", 5,
                "public class StoredBasket implements Basket {")), 1);
        }
        @Override public LspResult members(String type) {
            asked.add("members " + type);
            return type.endsWith("Ledger") ? LspResult.ok("`org.example.Ledger` (library jar "
                + "ledger-2.1.jar) declares 1 member(s). Its own members:",
                List.of(new LspHit("", 0, "void post(Entry entry)  // Posts one entry.")), 1)
                : LspResult.notFound("The language server knows no type called `" + type + "`.");
        }
        @Override public LspResult callers(String method) {
            asked.add("callers " + method);
            return LspResult.ok("0 call(s) of `" + method + "` in this project.", List.of(), 0);
        }
        @Override public void close() {
        }
    };

    @BeforeEach
    void aProject() throws Exception {
        app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><groupId>com.shop</groupId>"
            + "<artifactId>shop</artifactId><version>1</version></project>");
        write(app.resolve("shop-shared/src/main/java/com/shop/Basket.java"), """
            package com.shop;

            public interface Basket {
                int size();
            }
            """);
        write(app.resolve("shop-server/src/main/java/com/shop/server/StoredBasket.java"), """
            package com.shop.server;

            import com.shop.Basket;
            import org.example.Ledger;

            public class StoredBasket implements Basket {
                private Ledger ledger;

                public int size() {
                    return 0;
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

    private ExpertTools aRolesTools() {
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("planner", "hand in", 0, null, 0, 0));
    }

    @Test
    void withoutAServerTheToolsAreWhatTheyWereAndALibraryTypeIsAnsweredPlainly() {
        List<String> names = aRolesTools().lookupBindings().stream().map(ToolBinding::name).toList();

        assertThat(names).doesNotContain("supertypes_of", "callers_of", "callees_of",
            "find_symbol", "outline_of", "doc_of");
        assertThat(aRolesTools().publicShape("Ledger"))
            .contains("library jar")
            .contains("The Java language server is not available: none is installed")
            .contains("cannot be listed");
    }

    @Test
    void theServersQueriesSitWithTheTreesBeforeSearchAndWholeFiles() {
        curator.languageServer(server, app);

        List<String> names = aRolesTools().lookupBindings().stream().map(ToolBinding::name).toList();

        assertThat(names.subList(0, 11)).containsExactly("public_shape", "types_in", "body_of",
            "find_usages", "find_implementations", "supertypes_of", "callers_of", "callees_of",
            "find_symbol", "outline_of", "doc_of");
        assertThat(names.indexOf("search")).isGreaterThan(names.indexOf("doc_of"));
        assertThat(names.indexOf("read_file")).isGreaterThan(names.indexOf("search"));
        assertThat(names).doesNotHaveDuplicates();
        assertThat(new ExpertTools(curator, null, null, new CloudGate(0, null)).lookupBindings()
            .stream().map(ToolBinding::name).toList()).as("the expert is offered them too")
            .contains("callers_of", "find_symbol", "doc_of");
    }

    @Test
    void oneToolPerQuestionAndAFixedRuleForWhichSourceAnswers() {
        curator.languageServer(server, app);
        RunMeter.enable();
        LookupMeter.reset();
        try {
            ExpertTools tools = aRolesTools();

            // A type the tree holds: the tree answers, and the server is not asked for members.
            assertThat(tools.publicShape("Basket")).contains("int size()");
            assertThat(asked).noneMatch(q -> q.startsWith("members"));

            // A type that lives in a jar: the server lists it, with its real signature.
            assertThat(tools.publicShape("org.example.Ledger"))
                .contains("library jar ledger-2.1.jar").contains("void post(Entry entry)");

            // A symbol declared in the project: the server's references, addressed for read_file.
            assertThat(tools.findUsages("Basket"))
                .contains("project/shop-server/src/main/java/com/shop/server/StoredBasket.java:5")
                .doesNotContain("what it is used on");
            // A library's symbol: the tree, which also reads the reference checkouts.
            assertThat(tools.findUsages("Ledger")).doesNotContain("reference(s) to");
            assertThat(asked).doesNotContain("references Ledger");

            assertThat(tools.callersOf("Basket#size")).contains("0 call(s)");

            assertThat(LookupMeter.counts())
                .anySatisfy(c -> {
                    assertThat(c.role()).isEqualTo("planner");
                    assertThat(c.kind()).isEqualTo(LookupMeter.Kind.LANGUAGE_SERVER);
                    assertThat(c.calls()).isEqualTo(3); // Ledger's shape, Basket's usages, callers
                })
                .anySatisfy(c -> {
                    assertThat(c.kind()).isEqualTo(LookupMeter.Kind.TREE);
                    assertThat(c.calls()).isEqualTo(2); // Basket's shape, Ledger's usages
                });
        } finally {
            RunMeter.disable();
            LookupMeter.reset();
        }
    }

    @Test
    void aContractCannotGiveALibraryJarsTypeAMemberItDoesNotHave() {
        LibraryTypes library = LibraryTypes.NONE.withJarMembers(
            name -> name.equals("org.example.Ledger") ? List.of("post", "balance", "toString")
                : List.of());

        assertThat(library.jarMemberNames("org.example.Ledger")).contains("post");
        assertThat(library.jarMemberNames("org.example.Nothing")).isEmpty();
        assertThat(library.membersNotInJar(new com.swarmcoder.domain.ApiContract(
            java.util.UUID.randomUUID(), "Ledger", "", "", "org.example.Ledger",
            List.of("void post(Entry entry)", "public Money settleAll(int year);",
                "long balance", "Ledger(String name)"))))
            .containsExactly("settleAll");
    }

    @Test
    void theLastColumnOfTheKindEnumIsTheLanguageServerAndTheOthersKeepTheirPlaces() {
        // Appended, never inserted: anything that keeps a kind by its position stays right.
        assertThat(LookupMeter.Kind.values()).extracting(Enum::name).containsExactly("TREE",
            "SEARCH", "WHOLE_FILE", "FILE_PART", "LISTING", "SHELL_READ", "LANGUAGE_SERVER",
            "DOCUMENT", "ACCEPTANCE_TEST", "JOURNEY_CHECK");
    }
}
