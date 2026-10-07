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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test the test author is shown uses the types the project's rules name for tests, ahead of a
 * test that only looks like the right kind of class (live run 63, 2026-10-02).
 *
 * <p>The rules said a service is never built by hand and named the framework's test server. The
 * example chosen was a small test of a {@code ...ServiceImpl} that built its service by hand and
 * injected its database by reflection. The reference material held a test that used the test
 * server; it was a little larger than the size cap and was never considered. The author copied
 * what it was shown, then guessed at the test server's API.
 *
 * <p>An invented framework in a {@code @TempDir}; no model is called.
 */
class TheTestExampleUsesWhatTheRulesNameForTestsTest {

    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n"
        + "- A service reaches its data through the injected LedgerNode, never a field of its own.\n"
        + "- A service is never constructed by hand. Server tests use the framework TestLedger.\n";

    @TempDir
    Path world;

    Path project;
    Librarian librarian;

    @BeforeEach
    void buildTheWorld() throws Exception {
        project = world.resolve("orders-app");
        Path reference = world.resolve("ledgerworks");
        write(project.resolve("pom.xml"), "<project><artifactId>orders-app</artifactId></project>");
        write(reference.resolve("pom.xml"), "<project><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("runtime/pom.xml"), "<project><artifactId>runtime</artifactId></project>");
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/runtime/LedgerNode.java"), """
            package com.ledgerworks.runtime;

            public class LedgerNode {
                public <T> T append(Object entry) { return null; }
            }
            """);
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/testing/TestLedger.java"), """
            package com.ledgerworks.testing;

            public class TestLedger implements AutoCloseable {
                public static TestLedger start(Class<?>... beans) { return new TestLedger(); }
                public <T> T bean(Class<T> type) { return null; }
                public void close() { }
            }
            """);
        // Small, named exactly like the class under test, and it builds the service by hand.
        write(reference.resolve("examples/stock/pom.xml"),
            "<project><artifactId>stock</artifactId></project>");
        write(reference.resolve(
            "examples/stock/src/test/java/com/ledgerworks/stock/StockServiceImplTest.java"), """
            package com.ledgerworks.stock;

            import com.ledgerworks.runtime.LedgerNode;
            import java.lang.reflect.Field;
            import org.junit.jupiter.api.Test;

            class StockServiceImplTest {
                @Test
                void aSavedItemIsListed() throws Exception {
                    StockServiceImpl service = new StockServiceImpl();
                    Field node = StockServiceImpl.class.getDeclaredField("node");
                    node.setAccessible(true);
                    node.set(service, new LedgerNode());
                }
            }
            """);
        // Uses the harness the rules name, and is larger than the usual cap of 8,000 characters.
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
                    try (TestLedger ledger = TestLedger.start(ChatRoom.class)) {
                        ChatRoom room = ledger.bean(ChatRoom.class);
            """ + "            room.post(\"hello, this is one more message\");\n".repeat(200) + """
                    }
                }
            }
            """);
        librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(world.resolve("docs-index")),
            List.of(reference), project, null, world.resolve("cache"), null);
        librarian.setProjectRules(() -> RULES);
    }

    @Test
    void theHarnessTheRulesNameBeatsATestOfTheSameKindOfClassByName() {
        Task task = new Task(UUID.fromString("00000000-0000-0000-0000-0000000000b2"), 1L,
            "Implement OrderService in service", "Implement the service behind the shared interface.",
            Set.of("orders-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java"),
            Set.of(), List.of(), "src/test/java/swarm/accept", null, null,
            new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING);
        ApiContract contract = new ApiContract(UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            "OrderService", "The order list.", "", "com.acme.orders.shared.OrderService",
            List.of("List<String> list()", "String save(String order)"));

        String example = librarian.testExample(task, List.of(contract));

        assertThat(example)
            .as("the test that obtains its service from the harness the rules name")
            .contains("ChatRoomTest")
            .contains("TestLedger.start(ChatRoom.class)")
            .as("said to the reader").contains("the project's rules name `TestLedger` for tests")
            .as("not the look-alike that builds the service by hand")
            .doesNotContain("StockServiceImplTest")
            .doesNotContain("getDeclaredField");
    }

    @Test
    void theRulesAboutTestsAreReadFromTheirOwnWords() {
        assertThat(ExamplesByUse.rulesAboutTests(RULES))
            .contains("TestLedger")
            .as("a rule about production code names nothing for tests")
            .doesNotContain("LedgerNode");
        assertThat(ExamplesByUse.rulesAboutTests(
            "Persistence goes through StoreNode. Wire a MockClock or a TestClock where time matters."))
            .contains("TestClock").doesNotContain("StoreNode").doesNotContain("MockClock");
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
