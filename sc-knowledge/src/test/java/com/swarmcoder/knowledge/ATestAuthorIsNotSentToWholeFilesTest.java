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

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.lsp.LspDiagnostic;
import com.swarmcoder.lsp.LspResult;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.runtime.CloudGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What sent run 86's test author to whole files and folder listings (DEVELOPER_CORRECTIONS
 * section 58), on an invented framework in a {@code @TempDir}; no model is called.
 *
 * <ul>
 *   <li>{@code body_of} did not know a nested type by the name the language gives it, nor a
 *       member after a dot: five such calls were answered "no such type", and the file was read
 *       whole three times.</li>
 *   <li>{@code find_symbol} answered "0 type(s) match" seven times for a library type the tree
 *       knew by its full name.</li>
 *   <li>The test shown as the example came from the reference material even when the project
 *       itself holds a passing test built with what its rules name for tests.</li>
 * </ul>
 */
class ATestAuthorIsNotSentToWholeFilesTest {

    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n"
        + "- A service is never constructed by hand. Server tests use the framework TestLedger.\n";

    @TempDir
    Path world;

    Path project;
    Path reference;

    @BeforeEach
    void buildTheWorld() throws Exception {
        project = world.resolve("orders-app");
        reference = world.resolve("ledgerworks");
        write(project.resolve("pom.xml"), "<project><artifactId>orders-app</artifactId></project>");
        write(project.resolve("src/main/java/com/acme/orders/Stock.java"), """
            package com.acme.orders;

            import org.vendor.Vault;

            public class Stock {
                private Vault vault;
            }
            """);
        write(reference.resolve("pom.xml"), "<project><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("runtime/pom.xml"), "<project><artifactId>runtime</artifactId></project>");
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/testing/TestLedger.java"), """
            package com.ledgerworks.testing;

            public class TestLedger implements AutoCloseable {
                public static Builder builder() { return new Builder(); }
                public <T> T bean(Class<T> type) { return null; }
                public void close() { }

                public static class Builder {
                    public Builder beans(Class<?>... beans) { return this; }
                    public TestLedger start() { return new TestLedger(); }
                }
            }
            """);
        write(reference.resolve("examples/chat/pom.xml"),
            "<project><artifactId>chat</artifactId></project>");
        write(reference.resolve(
            "examples/chat/src/test/java/com/ledgerworks/chat/ChatRoomTest.java"), """
            package com.ledgerworks.chat;

            import com.ledgerworks.testing.TestLedger;
            import org.junit.jupiter.api.Test;

            class ChatRoomTest {
                @Test
                void aPostedMessageIsRead() throws Exception {
                    try (TestLedger ledger = TestLedger.builder().beans(ChatRoom.class).start()) {
                        ledger.bean(ChatRoom.class).post("hello");
                    }
                }
            }
            """);
    }

    private ExpertTools aRolesTools() {
        KnowledgeCurator curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", project, "local"),
            new KnowledgeCurator.Root("ledgerworks", reference, "local")),
            null, world.resolve("cache"));
        // A server that is installed and lists no symbol at all, as run 86's did for a jar type.
        curator.languageServer(new LspService() {
            @Override public List<LspDiagnostic> diagnostics(Path file) {
                return List.of();
            }
            @Override public boolean isAvailable() {
                return true;
            }
            @Override public boolean isInstalled() {
                return true;
            }
            @Override public LspResult workspaceSymbols(String query) {
                return LspResult.ok("0 type(s) match `" + query + "`.", List.of(), 0);
            }
            @Override public void close() {
            }
        }, project);
        return new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("test author", "hand in", 0, null, 0, 0));
    }

    @Test
    void aNestedTypeAndAMemberAfterADotAreAnsweredFromTheTree() {
        ExpertTools tools = aRolesTools();

        for (String asked : List.of("com.ledgerworks.testing.TestLedger.Builder",
                "com.ledgerworks.testing.TestLedger$Builder", "TestLedger.Builder")) {
            assertThat(tools.bodyOf(asked)).as(asked)
                .contains("TestLedger.java:")
                .contains("public Builder beans(Class<?>... beans)")
                .contains("public TestLedger start()")
                .as("the nested type alone, not the type around it").doesNotContain("void close()");
        }
        assertThat(tools.bodyOf("com.ledgerworks.testing.TestLedger.bean"))
            .as("a member after a dot").contains("public <T> T bean(Class<T> type)")
            .doesNotContain("class Builder");
        assertThat(tools.bodyOf("TestLedger.Builder#start"))
            .contains("public TestLedger start()").doesNotContain("beans(Class");
        assertThat(tools.bodyOf("TestLedger.Builder.start"))
            .contains("public TestLedger start()").doesNotContain("beans(Class");
        assertThat(tools.bodyOf("TestLedger.Nothing"))
            .as("a name the type does not declare is said, with what it does declare")
            .contains("declares no `Nothing`").contains("Builder").contains("bean");
        assertThat(tools.bodyOf("com.nowhere.Absent"))
            .contains("No type `com.nowhere.Absent` is declared");
    }

    @Test
    void aSymbolTheServerDoesNotListIsAnsweredWithWhatTheTreeKnows() {
        ExpertTools tools = aRolesTools();

        assertThat(tools.findSymbol("Vault"))
            .as("a library type known only by the code that uses it")
            .contains("org.vendor.Vault").contains("library jar")
            .contains("public_shape").contains("find_usages")
            .doesNotContain("0 type(s) match");
        assertThat(tools.findSymbol("TestLedger"))
            .contains("com.ledgerworks.testing.TestLedger").contains("TestLedger.java:");
        assertThat(tools.findSymbol("NobodyKnowsThis"))
            .as("nothing in the tree either: the server's own answer")
            .contains("0 type(s) match `NobodyKnowsThis`");
    }

    @Test
    void aTestTheProjectAlreadyHasIsTheExampleAheadOfTheReferenceMaterial() throws Exception {
        write(project.resolve("src/test/java/swarm/accept/StockTest.java"), """
            package swarm.accept;

            import com.acme.orders.Stock;
            import com.ledgerworks.testing.TestLedger;
            import org.junit.jupiter.api.Test;

            class StockTest {
                @Test
                void stockIsKept() throws Exception {
                    try (TestLedger ledger = TestLedger.builder().beans(Stock.class).start()) {
                        ledger.bean(Stock.class);
                    }
                }
            }
            """);
        // A test of the project that does not use what the rules name for tests is no recipe.
        write(project.resolve("src/test/java/com/acme/orders/StockArithmeticTest.java"), """
            package com.acme.orders;

            import org.junit.jupiter.api.Test;

            class StockArithmeticTest {
                @Test
                void adds() { }
            }
            """);

        String example = librarian().testExample(theTask(), List.of(theContract()));

        assertThat(example)
            .contains("A TEST THIS PROJECT ALREADY HAS, WHICH PASSES TODAY")
            .contains("src/test/java/swarm/accept/StockTest.java")
            .contains("TestLedger.builder().beans(Stock.class).start()")
            .contains("the project's rules name `TestLedger` for tests")
            .as("it belongs to earlier work").contains("your test is a new class")
            .doesNotContain("ChatRoomTest").doesNotContain("StockArithmeticTest");
    }

    @Test
    void withoutSuchATestTheReferenceMaterialIsTheExampleAsBefore() throws Exception {
        write(project.resolve("src/test/java/com/acme/orders/StockArithmeticTest.java"), """
            package com.acme.orders;

            import org.junit.jupiter.api.Test;

            class StockArithmeticTest {
                @Test
                void adds() { }
            }
            """);

        String example = librarian().testExample(theTask(), List.of(theContract()));

        assertThat(example).contains("A REAL TEST THAT PASSES TODAY").contains("ChatRoomTest")
            .doesNotContain("StockArithmeticTest").doesNotContain("THIS PROJECT ALREADY HAS");
    }

    private Librarian librarian() throws Exception {
        Librarian librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(world.resolve("docs-index")),
            List.of(reference), project, null, world.resolve("cache-lib"), null);
        librarian.setProjectRules(() -> RULES);
        return librarian;
    }

    private static Task theTask() {
        return new Task(UUID.fromString("00000000-0000-0000-0000-0000000000b2"), 1L,
            "Implement OrderService in service", "Implement the service behind the shared interface.",
            Set.of("src/main/java/com/acme/orders/OrderServiceImpl.java"),
            Set.of(), List.of(), "src/test/java/swarm/accept", null, null,
            new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING);
    }

    private static ApiContract theContract() {
        return new ApiContract(UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            "OrderService", "The order list.", "", "com.acme.orders.OrderService",
            List.of("List<String> list()", "String save(String order)"));
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
