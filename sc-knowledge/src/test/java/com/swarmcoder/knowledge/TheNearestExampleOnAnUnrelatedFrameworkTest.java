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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worked-example machinery, run against a framework that does not exist.
 *
 * <p><b>Why an invented framework.</b> The mechanism was designed while looking at one real
 * reference folder, and a mechanism designed that way is one edit away from being a set of rules
 * about that folder. So this test builds a corporate-shaped codebase from nothing: a
 * ledger/event-sourcing framework whose marker annotations, injected session type, repository
 * idiom and module layout share not one name with anything the mechanism was written against, a
 * second, unrelated framework beside it as a distractor, and a target application that has none of
 * the code yet.
 *
 * <p>Everything asserted below is the SAME behaviour the real reference folder gets, stated in
 * this fixture's vocabulary: the right example project is chosen out of three, one file stands for
 * one contract, the files the winners reach come with them, the wiring class that nothing refers
 * to comes too, the build line the target is missing is named, and the generated skeleton carries
 * this framework's annotations, its injected session and its interface.
 */
class TheNearestExampleOnAnUnrelatedFrameworkTest {

    @TempDir
    Path world;

    /** The application the task is being built in: two modules, none of the feature's code yet. */
    Path app;
    /** A read-only reference checkout: a framework and three example applications built on it. */
    Path reference;

    List<KnowledgeCurator.Root> roots;
    List<ApiContract> contracts;

    @BeforeEach
    void buildTheWorld() throws Exception {
        app = world.resolve("orders-app");
        reference = world.resolve("ledgerworks");

        // ---- the target application ---------------------------------------------------------
        write(app.resolve("pom.xml"), """
            <project>
              <groupId>com.acme</groupId><artifactId>orders-app</artifactId>
              <packaging>pom</packaging>
              <modules>
                <module>orders-app-model</module>
                <module>orders-app-service</module>
              </modules>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-bom</artifactId>
                  <version>3.2.0</version><type>pom</type><scope>import</scope>
                </dependency>
              </dependencies></dependencyManagement>
            </project>
            """);
        write(app.resolve("orders-app-model/pom.xml"), """
            <project>
              <artifactId>orders-app-model</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-model</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        write(app.resolve("orders-app-service/pom.xml"), """
            <project>
              <artifactId>orders-app-service</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.acme</groupId><artifactId>orders-app-model</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        // Enough of the application to say where its packages live, and nothing of the feature.
        write(app.resolve("orders-app-model/src/main/java/com/acme/orders/model/Customer.java"), """
            package com.acme.orders.model;

            public class Customer {
                private String name;
                public String getName() { return name; }
            }
            """);
        write(app.resolve("orders-app-service/src/main/java/com/acme/orders/service/Startup.java"),
            """
            package com.acme.orders.service;

            public class Startup {
                public static void main(String[] args) { }
            }
            """);

        // ---- the reference checkout ---------------------------------------------------------
        write(reference.resolve("pom.xml"), """
            <project>
              <artifactId>ledgerworks-parent</artifactId><packaging>pom</packaging>
            </project>
            """);
        write(reference.resolve("README.md"),
            "# Ledgerworks\nAn append-only ledger framework for back-office applications.\n");

        // The framework's own API, so the annotations and the session type resolve to somewhere.
        write(reference.resolve("ledgerworks-model/pom.xml"),
            "<project><artifactId>ledgerworks-model</artifactId></project>");
        write(reference.resolve(
            "ledgerworks-model/src/main/java/com/ledgerworks/api/Journalled.java"), """
            package com.ledgerworks.api;

            public @interface Journalled { }
            """);
        write(reference.resolve(
            "ledgerworks-model/src/main/java/com/ledgerworks/api/LedgerFacade.java"), """
            package com.ledgerworks.api;

            public @interface LedgerFacade { }
            """);
        write(reference.resolve(
            "ledgerworks-runtime/src/main/java/com/ledgerworks/runtime/LedgerSession.java"), """
            package com.ledgerworks.runtime;

            public class LedgerSession {
                public <T> T append(Object entry) { return null; }
                public <T> T read(Object query) { return null; }
            }
            """);
        write(reference.resolve("ledgerworks-runtime/pom.xml"),
            "<project><artifactId>ledgerworks-runtime</artifactId></project>");

        examplesFolder();
        payrollExample();
        smallerExample();
        unrelatedFramework();

        roots = List.of(new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("ledgerworks", reference, "3.2.0"));

        contracts = List.of(
            contract("com.acme.orders.model.Order",
                List.of("long id", "String customer", "int quantity", "String state")),
            contract("com.acme.orders.api.OrderService",
                List.of("List<Order> list()", "Order save(Order order)", "void delete(long id)")),
            contract("com.acme.orders.service.OrderServiceImpl",
                List.of("List<Order> list()", "Order save(Order order)", "void delete(long id)")));
    }

    /** A folder holding several example applications — the thing that must NOT be chosen. */
    private void examplesFolder() throws Exception {
        write(reference.resolve("ledgerworks-samples/pom.xml"), """
            <project><artifactId>ledgerworks-samples</artifactId><packaging>pom</packaging>
              <modules><module>payroll</module><module>alerts</module></modules>
            </project>
            """);
        write(reference.resolve("ledgerworks-samples/README.md"),
            "# Samples\nEvery sample application built on the ledger.\n");
    }

    /**
     * The complete one: an entity, a facade interface, its implementation, the two files that
     * implementation reaches, and one file nothing reaches at all.
     */
    private void payrollExample() throws Exception {
        Path payroll = reference.resolve("ledgerworks-samples/payroll");
        write(payroll.resolve("pom.xml"), """
            <project><artifactId>payroll</artifactId><packaging>pom</packaging>
              <modules><module>payroll-model</module><module>payroll-service</module></modules>
            </project>
            """);
        write(payroll.resolve("README.md"), """
            # Payroll

            Keeps a list of payslips, lets you add one, correct one, and remove one, and the list
            survives a restart because every change is appended to the ledger.
            """);
        write(payroll.resolve("payroll-model/pom.xml"), """
            <project>
              <artifactId>payroll-model</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-model</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        write(payroll.resolve("payroll-service/pom.xml"), """
            <project>
              <artifactId>payroll-service</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-model</artifactId>
                </dependency>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-runtime</artifactId>
                </dependency>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>payroll-model</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        write(payroll.resolve(
            "payroll-model/src/main/java/com/ledgerworks/payroll/model/Payslip.java"), """
            package com.ledgerworks.payroll.model;

            import com.ledgerworks.api.Journalled;

            @Journalled
            public class Payslip {
                private long id;
                private String employee;
                private int amount;
                private String period;

                public Payslip() { }

                public long getId() { return id; }
                public void setId(long id) { this.id = id; }
                public String getEmployee() { return employee; }
                public void setEmployee(String employee) { this.employee = employee; }
                public int getAmount() { return amount; }
                public void setAmount(int amount) { this.amount = amount; }
                public String getPeriod() { return period; }
                public void setPeriod(String period) { this.period = period; }
            }
            """);
        write(payroll.resolve(
            "payroll-model/src/main/java/com/ledgerworks/payroll/api/PayslipService.java"), """
            package com.ledgerworks.payroll.api;

            import com.ledgerworks.api.LedgerFacade;
            import com.ledgerworks.payroll.model.Payslip;

            import java.util.List;

            @LedgerFacade
            public interface PayslipService {
                List<Payslip> list();
                Payslip save(Payslip payslip);
                void delete(long id);
            }
            """);
        write(payroll.resolve(
            "payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipServiceImpl.java"),
            """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.payroll.api.PayslipService;
            import com.ledgerworks.payroll.model.Payslip;
            import com.ledgerworks.runtime.LedgerSession;

            import java.util.List;

            public class PayslipServiceImpl implements PayslipService {

                private LedgerSession session;

                @Override
                public List<Payslip> list() {
                    return session.read(new PayslipQueries.All());
                }

                @Override
                public Payslip save(Payslip payslip) {
                    return session.append(new PayslipEntries.Recorded(payslip));
                }

                @Override
                public void delete(long id) {
                    session.append(new PayslipEntries.Withdrawn(id));
                }
            }
            """);
        // Reached only through the implementation: one hop.
        write(payroll.resolve(
            "payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipEntries.java"), """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.payroll.model.Payslip;

            public final class PayslipEntries {
                public static final class Recorded {
                    public Payslip payslip;
                    public Recorded(Payslip payslip) { this.payslip = payslip; }
                }
                public static final class Withdrawn {
                    public long id;
                    public Withdrawn(long id) { this.id = id; }
                }
                private PayslipEntries() { }
            }
            """);
        write(payroll.resolve(
            "payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipQueries.java"), """
            package com.ledgerworks.payroll.service;

            public final class PayslipQueries {
                public static final class All { }
                private PayslipQueries() { }
            }
            """);
        // Reached by nothing at all: the container finds it by its name, not by a reference.
        write(payroll.resolve(
            "payroll-service/src/main/java/com/ledgerworks/payroll/service/LedgerBootstrap.java"), """
            package com.ledgerworks.payroll.service;

            public class LedgerBootstrap {
                public static void install() { }
            }
            """);
    }

    /** A second example that answers part of the same shape from fewer files. */
    private void smallerExample() throws Exception {
        Path alerts = reference.resolve("ledgerworks-samples/alerts");
        write(alerts.resolve("pom.xml"),
            "<project><artifactId>alerts</artifactId></project>");
        write(alerts.resolve("README.md"), "# Alerts\nSends an alert when a threshold trips.\n");
        write(alerts.resolve("src/main/java/com/ledgerworks/alerts/api/AlertService.java"), """
            package com.ledgerworks.alerts.api;

            import com.ledgerworks.api.LedgerFacade;

            import java.util.List;

            @LedgerFacade
            public interface AlertService {
                List<String> list();
                String save(String alert);
                void delete(long id);
            }
            """);
    }

    /** A framework with a completely different idiom, so "any Java file" is not good enough. */
    private void unrelatedFramework() throws Exception {
        Path grid = reference.resolve("gridkit");
        write(grid.resolve("pom.xml"), "<project><artifactId>gridkit</artifactId></project>");
        write(grid.resolve("README.md"), "# Gridkit\nDraws tables in a terminal.\n");
        write(grid.resolve("src/main/java/com/gridkit/Table.java"), """
            package com.gridkit;

            public class Table {
                private int columns;
                private int rows;
                public void draw() { }
                public void resize(int columns, int rows) { }
            }
            """);
    }

    // -------------------------------------------------------------------------------------

    @Test
    void itChoosesTheCompleteExampleAndNotTheFolderThatContainsIt() {
        WorkedExamples.Selection selection = select();

        assertThat(selection.projectLabel())
            .as("the one example that has a counterpart for every contract, not the folder of "
                + "examples above it and not the module below it")
            .isEqualTo("payroll");
    }

    @Test
    void oneFileStandsForOneContract() {
        WorkedExamples.Selection selection = select();

        assertThat(named(selection))
            .containsExactlyInAnyOrder("Payslip", "PayslipService", "PayslipServiceImpl");
    }

    @Test
    void theFilesTheWinnersReachComeWithThem() {
        List<String> everything = new ArrayList<>(named(select()));
        select().neighbours().forEach(n -> everything.add(n.simpleName()));

        assertThat(everything)
            .as("the entry classes the implementation names, one hop away")
            .contains("PayslipEntries", "PayslipQueries")
            .as("and the class nothing in the code refers to, which a copy of this example "
                + "cannot run without")
            .contains("LedgerBootstrap");
    }

    @Test
    void nothingFromTheUnrelatedFrameworkIsOffered() {
        List<String> everything = new ArrayList<>(named(select()));
        select().neighbours().forEach(n -> everything.add(n.simpleName()));

        assertThat(everything).doesNotContain("Table");
    }

    @Test
    void theBriefNamesTheBuildLineTheApplicationIsMissing() {
        String brief = TaskBrief.render(app, select(),
            TaskBrief.Ingredients.examplesOnly(), 200_000);

        assertThat(brief)
            .as("the runtime the example's service module declares and the application's does not")
            .contains("com.ledgerworks:ledgerworks-runtime")
            .as("said about the file that has to change")
            .contains("orders-app-service/pom.xml")
            .as("the example's own sibling modules are named after itself and are never advice")
            .doesNotContain("- `com.ledgerworks:payroll-model`");
    }

    @Test
    void theBriefCarriesTheExampleWhole() {
        String brief = TaskBrief.render(app, select(),
            TaskBrief.Ingredients.examplesOnly(), 200_000);

        assertThat(brief)
            .contains("@Journalled")
            .contains("@LedgerFacade")
            .contains("private LedgerSession session;")
            .contains("session.append(new PayslipEntries.Recorded(payslip));");
    }

    @Test
    void theSkeletonIsThisFrameworksIdiomWithThisTasksNames() throws Exception {
        Skeleton.Result result = Skeleton.write(app, contracts, select());

        assertThat(result.files()).hasSize(3);
        Path impl = app.resolve(
            "orders-app-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java");
        assertThat(result.files()).contains(impl);

        String source = Files.readString(impl);
        assertThat(source)
            .as("the interface the example's implementation implements, mapped to this task's own")
            .contains("public class OrderServiceImpl implements OrderService")
            .as("the framework wiring the example holds, which no contract mentions")
            .contains("private LedgerSession session;")
            .contains("import com.ledgerworks.runtime.LedgerSession;")
            .as("every body still to write")
            .contains("throw new UnsupportedOperationException(\"TODO: save\");");

        String entity = Files.readString(app.resolve(
            "orders-app-model/src/main/java/com/acme/orders/model/Order.java"));
        assertThat(entity)
            .as("the marker this framework puts on a persisted type")
            .contains("@Journalled")
            .contains("import com.ledgerworks.api.Journalled;")
            .as("the bean convention the example follows")
            .contains("public String getCustomer()")
            .as("and not the example's own fields")
            .doesNotContain("employee");
    }

    @Test
    void theSkeletonAddsTheBuildLineItNeedsAndNoOther() throws Exception {
        Skeleton.Result result = Skeleton.write(app, contracts, select());

        assertThat(result.buildChanges()).hasSize(1);
        assertThat(result.buildChanges().get(0))
            .contains("orders-app-service/pom.xml")
            .contains("com.ledgerworks:ledgerworks-runtime");
        assertThat(Files.readString(app.resolve("orders-app-service/pom.xml")))
            .contains("<artifactId>ledgerworks-runtime</artifactId>")
            .as("no <version>: the application's own bill of materials decides that")
            .doesNotContain("<version>3.2.0</version>");
    }

    @Test
    void theSkeletonNeverOverwritesWorkThatIsAlreadyThere() throws Exception {
        Path entity = app.resolve(
            "orders-app-model/src/main/java/com/acme/orders/model/Order.java");
        Files.createDirectories(entity.getParent());
        Files.writeString(entity, "package com.acme.orders.model;\n\npublic class Order { }\n");

        Skeleton.Result result = Skeleton.write(app, contracts, select());

        assertThat(result.files()).doesNotContain(entity);
        assertThat(Files.readString(entity))
            .as("a type somebody has already delivered is left completely alone")
            .isEqualTo("package com.acme.orders.model;\n\npublic class Order { }\n");
    }

    @Test
    void aContractOfBareConstantsBecomesAnEnum() throws Exception {
        List<ApiContract> withAnEnum = new ArrayList<>(contracts);
        withAnEnum.add(contract("com.acme.orders.model.OrderState",
            List.of("PLACED", "PICKED", "SHIPPED")));

        Skeleton.write(app, withAnEnum, select());

        assertThat(Files.readString(app.resolve(
            "orders-app-model/src/main/java/com/acme/orders/model/OrderState.java")))
            .as("no type, no parentheses, and a constant's own spelling: the one form an enum "
                + "contract can take")
            .contains("public enum OrderState")
            .contains("PLACED, PICKED, SHIPPED;");
    }

    @Test
    void aTaskWithNoContractsAsksForNothing() {
        WorkedExamples.Selection none =
            WorkedExamples.select(WorkedExamples.shapes(roots), List.of(), Set.of());

        assertThat(none.isEmpty()).isTrue();
        assertThat(TaskBrief.render(app, none, TaskBrief.Ingredients.all(), 200_000))
            .isEmpty();
    }

    /**
     * <b>"Copy this shape elsewhere" and "this is the file you are about to edit" are opposite
     * instructions</b>, and on a change to code that already exists the nearest example is very
     * often the file being changed.
     *
     * <p>That is right — a worker reading the current implementation before changing it is doing
     * the correct thing — but a brief that tells it to copy that file's shape into its own files
     * teaches it to write a parallel copy of the code it was supposed to modify. So the brief says
     * which it is, out of the task's own write set. Design §2.3.
     */
    @Test
    void theBriefSaysWhenTheExampleIsTheFileYouAreChanging() {
        WorkedExamples.Selection selection = select();
        String winner = selection.matches().get(0).example().relative();

        String brief = TaskBrief.render(app, selection, TaskBrief.Ingredients.examplesOnly(),
            200_000, Set.of(winner));

        assertThat(brief)
            .as("the file the task owns is named, and named as a file to change")
            .contains("`" + winner + "` is a file you are changing")
            .contains("CHANGE it in place — do not write a parallel copy somewhere else");
    }

    /** And says the opposite, just as plainly, when none of them is the worker's to write. */
    @Test
    void theBriefSaysWhenNoneOfTheExampleIsYoursToWrite() {
        String brief = TaskBrief.render(app, select(), TaskBrief.Ingredients.examplesOnly(),
            200_000, Set.of("orders-app-web/src/main/java/com/acme/orders/web"));

        assertThat(brief)
            .contains("None of the files below is yours to change")
            .contains("copy their shape into the files your task actually owns")
            .doesNotContain("is a file you are changing");
    }

    /**
     * A caller that states no write set gets no line at all.
     *
     * <p>"No overlap" and "nobody told me" are different facts and only the first is worth a
     * sentence — and the brief sits in a shared prefill prefix, so a guess written into every
     * greenfield task's prompt costs every project a cold cache for nothing.
     */
    @Test
    void anUnstatedWriteSetGetsNoProvenanceLineAtAll() {
        String stated = TaskBrief.render(app, select(), TaskBrief.Ingredients.examplesOnly(),
            200_000, Set.of());
        String silent = TaskBrief.render(app, select(), TaskBrief.Ingredients.examplesOnly(),
            200_000);

        assertThat(stated).isEqualTo(silent)
            .doesNotContain("is a file you are changing")
            .doesNotContain("None of the files below is yours to change");
    }

    // -------------------------------------------------------------------------------------

    private WorkedExamples.Selection select() {
        return WorkedExamples.select(WorkedExamples.shapes(roots), contracts,
            TaskBrief.taskWords("Add, correct and remove an order",
                "Keep a list of orders that survives a restart. Adding, correcting and removing "
                    + "one must all be visible in the list."));
    }

    private static List<String> named(WorkedExamples.Selection selection) {
        List<String> names = new ArrayList<>();
        selection.matches().forEach(m -> names.add(m.example().simpleName()));
        return names;
    }

    private static ApiContract contract(String typeName, List<String> members) {
        return new ApiContract(UUID.randomUUID(), typeName, "", "", typeName, members);
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
