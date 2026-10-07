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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every role that has to write against an unfamiliar library is shown real code from the
 * reference material that uses what it is about to use — chosen by USE, not only by what the
 * task delivers.
 *
 * <p><b>The failure it pins (live harness run 58, 2026-10-01).</b> Four data-class tasks each got
 * a worked example. The one hard task — the implementation behind a contracted interface, which
 * delivers no contract of its own — got none, silently, and its two workers spent eighty minutes
 * asking how the framework's injected database is used. The test author was never shown a test.
 *
 * <p>An invented framework in a {@code @TempDir}; no model is called and nothing outside this
 * test's own folder is read.
 */
class ExamplesReachEveryRoleTest {

    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n"
        + "- A service reaches its data through the injected LedgerNode, never a field of its own.\n"
        + "- Tests get a service from TestLedger; a service is never constructed by hand.\n";

    @TempDir
    Path world;

    Path project;
    Path reference;
    Librarian librarian;

    @BeforeEach
    void buildTheWorld() throws Exception {
        project = world.resolve("orders-app");
        reference = world.resolve("ledgerworks");

        write(project.resolve("pom.xml"),
            "<project><artifactId>orders-app</artifactId><packaging>pom</packaging></project>");
        write(project.resolve("orders-service/pom.xml"),
            "<project><artifactId>orders-service</artifactId></project>");
        // The project's own tree holds something that uses the same type. On a new build that is
        // scaffold or an earlier task's guess, and must not be what a worker is told to copy.
        write(project.resolve(
            "orders-service/src/main/java/com/acme/orders/service/LegacyServiceImpl.java"), """
            package com.acme.orders.service;

            import com.ledgerworks.runtime.Inject;
            import com.ledgerworks.runtime.LedgerNode;

            public class LegacyServiceImpl {
                @Inject private LedgerNode node;
                public void scaffoldOnly() { }
            }
            """);

        write(reference.resolve("pom.xml"), "<project><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("runtime/pom.xml"), "<project><artifactId>runtime</artifactId></project>");
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/runtime/LedgerNode.java"), """
            package com.ledgerworks.runtime;

            public class LedgerNode {
                public <T> T append(Object entry) { return null; }
                public <T> T read(Object query) { return null; }
            }
            """);
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/model/Record.java"), """
            package com.ledgerworks.model;

            public @interface Record { }
            """);
        write(reference.resolve("runtime/src/main/java/com/ledgerworks/testing/TestLedger.java"), """
            package com.ledgerworks.testing;

            public class TestLedger implements AutoCloseable {
                public static TestLedger start(Class<?>... beans) { return new TestLedger(); }
                public <T> T bean(Class<T> type) { return null; }
                public void close() { }
            }
            """);

        Path payroll = reference.resolve("examples/payroll");
        write(payroll.resolve("pom.xml"), "<project><artifactId>payroll</artifactId></project>");
        write(payroll.resolve("payroll-shared/pom.xml"),
            "<project><artifactId>payroll-shared</artifactId></project>");
        write(payroll.resolve("payroll-service/pom.xml"),
            "<project><artifactId>payroll-service</artifactId></project>");
        write(payroll.resolve(
            "payroll-shared/src/main/java/com/ledgerworks/payroll/shared/Payslip.java"), """
            package com.ledgerworks.payroll.shared;

            import com.ledgerworks.model.Record;

            @Record
            public class Payslip {
                public String id;
                public int amount;
            }
            """);
        write(payroll.resolve(
            "payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipServiceImpl.java"), """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.runtime.Inject;
            import com.ledgerworks.runtime.LedgerNode;

            import java.util.List;

            public class PayslipServiceImpl {

                @Inject private LedgerNode node;

                public List<String> list() {
                    return node.read(new PayslipsByDate());
                }

                public String save(String payslip) {
                    return node.append(new PayslipRecorded(payslip));
                }
            }
            """);
        write(payroll.resolve(
            "payroll-service/src/test/java/com/ledgerworks/payroll/service/PayslipServiceImplTest.java"), """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.testing.TestLedger;
            import org.junit.jupiter.api.Test;

            class PayslipServiceImplTest {

                @Test
                void aSavedPayslipIsListed() throws Exception {
                    try (TestLedger ledger = TestLedger.start(PayslipServiceImpl.class)) {
                        PayslipServiceImpl service = ledger.bean(PayslipServiceImpl.class);
                        service.save("march");
                    }
                }
            }
            """);
        write(payroll.resolve(
            "payroll-service/src/test/java/com/ledgerworks/payroll/service/PayslipFormatTest.java"), """
            package com.ledgerworks.payroll.service;

            import org.junit.jupiter.api.Test;

            class PayslipFormatTest {
                @Test
                void formats() { }
            }
            """);
        // Uses the same type and is named the same way, and is far too big to be an example.
        write(reference.resolve("examples/kitchen/pom.xml"),
            "<project><artifactId>kitchen</artifactId></project>");
        write(reference.resolve(
            "examples/kitchen/src/main/java/com/ledgerworks/kitchen/service/KitchenServiceImpl.java"),
            """
            package com.ledgerworks.kitchen.service;

            import com.ledgerworks.runtime.Inject;
            import com.ledgerworks.runtime.LedgerNode;

            public class KitchenServiceImpl {
                @Inject private LedgerNode node;
            """ + "    public void everything() { node.append(\"sink\"); }\n".repeat(400) + "}\n");
        // Shaped exactly like a data class and annotated with nothing: a look-alike.
        write(reference.resolve("plain/pom.xml"), "<project><artifactId>plain</artifactId></project>");
        write(reference.resolve("plain/src/main/java/com/ledgerworks/plain/shared/Invoice.java"), """
            package com.ledgerworks.plain.shared;

            public class Invoice {
                public String id;
                public int amount;
            }
            """);

        librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(world.resolve("docs-index")),
            List.of(reference), project, null, world.resolve("cache"), null);
    }

    /**
     * The same selection, asked for on demand by a role in the middle of its work (find_example,
     * 2026-10-02): it names the types it has decided it needs and gets the file that uses them.
     */
    @Test
    void aRoleThatNamesTheTypesItNeedsIsGivenTheFileThatUsesThem() {
        assertThat(librarian.findExample("a service that appends to the injected LedgerNode",
            false, "the architect"))
            .contains("node.append(new PayslipRecorded(payslip));")
            .contains("it uses `LedgerNode`");
        assertThat(librarian.findExample("a test of a service started with TestLedger", true,
            "the test author"))
            .contains("TestLedger.start(PayslipServiceImpl.class)");
        assertThat(librarian.findExample("Zzyzx Qwfp", false, "the planner"))
            .as("nothing uses it, and the answer says what was searched instead of inventing one")
            .startsWith("Nothing in this project's material");
    }

    @Test
    void anImplementationTaskThatDeliversNoContractIsShownTheFileThatUsesWhatItNames() {
        String brief = librarian.assembleBrief(project, task("Implement OrderService in service",
            "Implement the service. Its data is reached through the injected LedgerNode.",
            "orders-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java"))
            .renderedMarkdown();

        assertThat(brief)
            .contains("How this codebase does this")
            .as("the body, with the injected field and the calls on it")
            .contains("@Inject private LedgerNode node;")
            .contains("node.append(new PayslipRecorded(payslip));")
            .as("why this file, said to the reader").contains("it uses `LedgerNode`")
            .as("the project's own scaffold is not the example when the library has one")
            .doesNotContain("LegacyServiceImpl")
            .as("and a 20,000-character file is not an example at all")
            .doesNotContain("KitchenServiceImpl");
    }

    @Test
    void theTypeMayBeNamedOnlyByTheProjectsRules() {
        librarian.setProjectRules(() -> RULES);

        String brief = librarian.assembleBrief(project, task("Implement OrderService in service",
            "Implement the service behind the shared interface.",
            "orders-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java"))
            .renderedMarkdown();

        assertThat(brief).contains("node.append(new PayslipRecorded(payslip));");
    }

    @Test
    void theTestAuthorIsShownARealTestThatUsesTheHarnessTheRulesName() {
        librarian.setProjectRules(() -> RULES);

        String example = librarian.testExample(task("Implement OrderService in service",
            "Implement the service behind the shared interface.",
            "orders-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java"),
            List.of(orderService()));

        assertThat(example)
            .contains("A REAL TEST THAT PASSES TODAY")
            .contains("PayslipServiceImplTest")
            .as("how the service is obtained, which is what the author kept guessing")
            .contains("TestLedger.start(PayslipServiceImpl.class)")
            .contains("ledger.bean(PayslipServiceImpl.class)")
            .as("a test that does not use the harness is not the one to copy")
            .doesNotContain("PayslipFormatTest");
    }

    @Test
    void thePlannerSeesTheSameImplementationAndTheSameTest() {
        librarian.setProjectRules(() -> RULES);

        String examples = librarian.planExamples(List.of(orderService()),
            "Keep a list of orders that survives a restart.", 8_000);

        assertThat(examples)
            .contains("node.append(new PayslipRecorded(payslip));")
            .contains("TestLedger.start(PayslipServiceImpl.class)")
            .doesNotContain("KitchenServiceImpl");
    }

    @Test
    void aShapeLookAlikeThatLacksTheNamedAnnotationGivesWayToTheFileThatCarriesIt() {
        Task task = task("Create Order @Record in shared", "A data class annotated @Record.",
            "orders-service/src/main/java/com/acme/orders/shared/Order.java");
        task.setDeliveredContracts(List.of(new ApiContract(UUID.randomUUID(), "Order",
            "An order.", "", "com.acme.orders.shared.Order", List.of("String id", "int amount"))));

        String brief = librarian.assembleBrief(project, task).renderedMarkdown();

        assertThat(brief).contains("How this codebase does this").contains("@Record")
            .contains("public class Payslip");
    }

    @Test
    void whenNothingIsFoundTheSearchIsStatedAndNoHeadingIsWritten() {
        ExamplesByUse.Result result = ExamplesByUse.find(librarian.curator().shapes(),
            new ExamplesByUse.Query("Fly the Zeppelin", "", 0.5, List.of("BlimpPilot"), List.of(),
                false), 8_000);

        assertThat(result.best()).isNull();
        assertThat(result.searched()).contains("(none named)").contains("blimp")
            .contains("candidate file(s)");

        String brief = librarian.assembleBrief(project, task("Fly the Zeppelin", "Up.",
            "orders-service/src/main/java/com/acme/orders/air/BlimpPilot.java")).renderedMarkdown();
        assertThat(brief).doesNotContain("How this codebase does this");
    }

    private static ApiContract orderService() {
        return new ApiContract(UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            "OrderService", "The order list.", "", "com.acme.orders.shared.OrderService",
            List.of("List<String> list()", "String save(String order)"));
    }

    private static Task task(String title, String instructions, String writes) {
        return new Task(UUID.fromString("00000000-0000-0000-0000-0000000000b2"), 1L, title,
            instructions, Set.of(writes), Set.of(), List.of(), "src/test/java/swarm/accept", null,
            null, new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING);
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
