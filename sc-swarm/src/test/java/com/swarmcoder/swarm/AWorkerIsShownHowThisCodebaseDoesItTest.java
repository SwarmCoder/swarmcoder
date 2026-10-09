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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.PromptBundle;
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
 * What a worker is actually handed, through the real path that hands it: the Librarian assembles
 * the brief, the dispatcher frames it into the shared prompt prefix, and this asserts what is in
 * the result.
 *
 * <p><b>The failure it pins.</b> Measured over four runs of a plain agent harness with nothing but
 * native tools and the repository: against an unfamiliar framework with prose documentation and a
 * search tool, this model read 43 documents, then disassembled the framework's jars, then
 * disassembled its annotation processor's bytecode — 467 turns across three runs, <b>zero files
 * written</b>. On a task of the same shape in plain Java it was writing at turn 5 and green at
 * turn 10. What the brief has to carry is not a list of documents to read; it is the code.
 *
 * <p>No model is called and no folder outside this test's own {@code @TempDir} is read. The
 * documentation server is constructed hosted-with-no-key, which marks itself unreachable without
 * sending anything, and the primer model is null.
 */
class AWorkerIsShownHowThisCodebaseDoesItTest {

    @TempDir
    Path world;

    Path project;
    Path reference;
    Librarian librarian;

    @BeforeEach
    void buildTheWorld() throws Exception {
        project = world.resolve("orders-app");
        reference = world.resolve("ledgerworks");

        write(project.resolve("pom.xml"), """
            <project>
              <groupId>com.acme</groupId><artifactId>orders-app</artifactId>
              <packaging>pom</packaging>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-bom</artifactId>
                  <version>3.2.0</version><type>pom</type><scope>import</scope>
                </dependency>
              </dependencies></dependencyManagement>
            </project>
            """);
        write(project.resolve("orders-app-service/pom.xml"), """
            <project><artifactId>orders-app-service</artifactId>
              <dependencies></dependencies>
            </project>
            """);
        write(project.resolve(
            "orders-app-service/src/main/java/com/acme/orders/service/Startup.java"), """
            package com.acme.orders.service;

            public class Startup { }
            """);

        write(reference.resolve("pom.xml"), "<project><artifactId>ledgerworks</artifactId></project>");
        write(reference.resolve("README.md"), "# Ledgerworks\nAn append-only ledger framework.\n");
        write(reference.resolve("docs/persistence.md"), """
            # Persisting with the ledger

            Everything is appended. Nothing is updated in place. The session is the only way in.
            """ + "The ledger appends the appended entry to the ledger. ".repeat(60));

        write(reference.resolve("payroll/pom.xml"), """
            <project><artifactId>payroll</artifactId><packaging>pom</packaging>
              <modules><module>payroll-service</module></modules>
            </project>
            """);
        write(reference.resolve("payroll/payroll-service/pom.xml"), """
            <project>
              <artifactId>payroll-service</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.ledgerworks</groupId><artifactId>ledgerworks-runtime</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
        write(reference.resolve("payroll/README.md"), """
            # Payroll

            Keeps a list of payslips, lets you add one, correct one and remove one, and the list
            survives a restart because every change is appended to the ledger.
            """);
        write(reference.resolve(
            "payroll/payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipServiceImpl.java"), """
            package com.ledgerworks.payroll.service;

            import com.ledgerworks.runtime.LedgerSession;

            import java.util.List;

            public class PayslipServiceImpl {

                private LedgerSession session;

                public List<String> list() {
                    return session.read(new PayslipQueries.All());
                }

                public String save(String payslip) {
                    return session.append(new PayslipEntries.Recorded(payslip));
                }

                public void delete(long id) {
                    session.append(new PayslipEntries.Withdrawn(id));
                }
            }
            """);
        write(reference.resolve(
            "payroll/payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipEntries.java"), """
            package com.ledgerworks.payroll.service;

            public final class PayslipEntries {
                public static final class Recorded {
                    public String payslip;
                    public Recorded(String payslip) { this.payslip = payslip; }
                }
                public static final class Withdrawn {
                    public long id;
                    public Withdrawn(long id) { this.id = id; }
                }
                private PayslipEntries() { }
            }
            """);
        write(reference.resolve(
            "payroll/payroll-service/src/main/java/com/ledgerworks/payroll/service/PayslipQueries.java"), """
            package com.ledgerworks.payroll.service;

            public final class PayslipQueries {
                public static final class All { }
                private PayslipQueries() { }
            }
            """);
        write(reference.resolve("ledgerworks-runtime/pom.xml"),
            "<project><artifactId>ledgerworks-runtime</artifactId></project>");
        write(reference.resolve(
            "ledgerworks-runtime/src/main/java/com/ledgerworks/runtime/LedgerSession.java"), """
            package com.ledgerworks.runtime;

            public class LedgerSession {
                public <T> T append(Object entry) { return null; }
                public <T> T read(Object query) { return null; }
            }
            """);

        librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(world.resolve("docs-index")),
            List.of(reference), project, null, world.resolve("cache"), null);
    }

    @Test
    void theWorkerPrefixCarriesTheCodeAndNotJustAReadingList() {
        KnowledgeBrief brief = librarian.assembleBrief(project, taskWithContracts());
        PromptBundle bundle = SwarmDispatcher.buildBundle(taskWithContracts(), null,
            brief.renderedMarkdown(), "", List.of());

        assertThat(bundle.sharedText())
            .as("the heading a worker reads before it starts")
            .contains("How this codebase does this")
            .as("the whole implementation it is to be shaped after, body included")
            .contains("session.append(new PayslipEntries.Recorded(payslip));")
            .as("and the file that implementation reaches, so the call above can be read")
            .contains("public static final class Recorded")
            .as("with the build line the application is missing, which nothing else would say")
            .contains("com.ledgerworks:ledgerworks-runtime");
    }

    @Test
    void whatTheExampleDisplacesIsGoneAndTheCatalogueRemains() {
        String withExample = librarian.assembleBrief(project, taskWithContracts())
            .renderedMarkdown();
        // A task with no fixed types whose own file resembles nothing in the reference material
        // either. (A task with no contract that writes an OrderServiceImpl is now shown the
        // PayslipServiceImpl — see ExamplesReachEveryRoleTest — so it no longer stands for this.)
        // The file is one the task creates. A task that may write only files the project
        // already has is shown no example and none of these channels (live run 90, section 66):
        // the code it changes is its example - asserted last.
        Task nothingLikeIt = taskWithNoContracts();
        nothingLikeIt.setWriteSet(
            Set.of("orders-app-service/src/main/java/com/acme/orders/service/Housekeeping.java"));
        String withoutExample = librarian.assembleBrief(project, nothingLikeIt)
            .renderedMarkdown();
        Task changesWhatIsThere = taskWithNoContracts();
        changesWhatIsThere.setWriteSet(
            Set.of("orders-app-service/src/main/java/com/acme/orders/service/Startup.java"));
        assertThat(librarian.assembleBrief(project, changesWhatIsThere).renderedMarkdown())
            .contains("The code this task changes is its own example")
            .doesNotContain("How this codebase does this")
            .doesNotContain("Reference sources relevant to this task")
            .contains("Reference documentation you can read");

        assertThat(withoutExample)
            .as("a task that resembles nothing still gets the old guessed-at channels")
            .contains("Reference sources relevant to this task")
            .doesNotContain("How this codebase does this");
        assertThat(withExample)
            .as("the guessed-at documentation slice is what the example is paid for with")
            .doesNotContain("Documentation relevant to this task")
            .as("and so is the guessed-at source channel")
            .doesNotContain("Reference sources relevant to this task")
            .as("the catalogue stays, at a third of its size: lookup_api still has to be findable")
            .contains("Reference documentation you can read");
    }

    /**
     * Section 73 (owner's decision, 2026-10-08): where the architect kept findings with real
     * code about every contract a task delivers, they are in the task and the example chosen
     * here by name and shape is not pasted beside them. Where it kept nothing for a contract,
     * this example is still what the worker has.
     */
    @Test
    void aTaskTheArchitectCoveredIsNotAlsoPastedAnExampleAndOneItDidNotCoverStillIs() {
        Task covered = taskWithContracts();
        covered.setArchitectFindings(List.of(new com.swarmcoder.domain.DesignFinding(
            UUID.fromString("00000000-0000-0000-0000-0000000000c3"), "OrderServiceImpl",
            "body_of com.ledgerworks.sample.PayslipServiceImpl#save",
            "A service appends an entry to its session and commits.",
            "session.append(new PayslipEntries.Recorded(payslip));")));
        String brief = librarian.assembleBrief(project, covered).renderedMarkdown();

        assertThat(brief)
            .contains("How to build this is in your task")
            .doesNotContain("How this codebase does this")
            .as("and what an example displaces does not come back in its place")
            .doesNotContain("Reference sources relevant to this task")
            .doesNotContain("Documentation relevant to this task")
            .as("the catalogue and the libraries stay")
            .contains("Reference documentation you can read");
        assertThat(brief.length())
            .as("the brief is smaller by the example")
            .isLessThan(librarian.assembleBrief(project, taskWithContracts())
                .renderedMarkdown().length());

        Task elsewhere = taskWithContracts();
        elsewhere.setArchitectFindings(List.of(new com.swarmcoder.domain.DesignFinding(
            UUID.fromString("00000000-0000-0000-0000-0000000000c4"), "SomethingElse",
            "body_of com.ledgerworks.sample.PayslipServiceImpl#save", "About another type.",
            "session.commit();")));
        assertThat(librarian.assembleBrief(project, elsewhere).renderedMarkdown())
            .as("nothing was kept for the contract this task delivers: the fallback stands")
            .contains("How this codebase does this");

        Task sentenceOnly = taskWithContracts();
        sentenceOnly.setArchitectFindings(List.of(new com.swarmcoder.domain.DesignFinding(
            UUID.fromString("00000000-0000-0000-0000-0000000000c5"), "OrderServiceImpl",
            "docs_for services", "Services are singletons.", null)));
        assertThat(librarian.assembleBrief(project, sentenceOnly).renderedMarkdown())
            .as("a sentence is not code: the example stands")
            .contains("How this codebase does this");

        // Live run 100: a task that delivers no contract carries findings about what it is
        // built on, not about what it builds; "every contract it delivers" is true of nothing.
        Task buildsOnIt = taskWithNoContracts();
        buildsOnIt.setArchitectFindings(List.of(new com.swarmcoder.domain.DesignFinding(
            UUID.fromString("00000000-0000-0000-0000-0000000000c6"), "OrderService",
            "body_of com.ledgerworks.sample.PayslipServiceImpl#save",
            "A caller of a service does it like this.", "session.commit();")));
        assertThat(librarian.assembleBrief(project, buildsOnIt).renderedMarkdown())
            .as("it keeps what a task with no findings is shown, and has the findings as well")
            .isEqualTo(librarian.assembleBrief(project, taskWithNoContracts())
                .renderedMarkdown())
            .doesNotContain("How to build this is in your task");
    }

    @Test
    void everyWorkerOfOneTaskGetsTheSameBytes() {
        String first = librarian.assembleBrief(project, taskWithContracts()).renderedMarkdown();
        String second = librarian.assembleBrief(project, taskWithContracts()).renderedMarkdown();

        assertThat(second)
            .as("the prefix is shared across a task's workers and cached by the server; a brief "
                + "that differed by one byte between two workers would be prefilled twice")
            .isEqualTo(first);
    }

    @Test
    void theBriefStaysUnderItsCeiling() {
        String rendered = librarian.assembleBrief(project, taskWithContracts()).renderedMarkdown();

        assertThat(rendered.length())
            .as("the brief sits in the shared prefix, which is the floor compaction can never "
                + "reclaim; measured at 14,737 characters on the owner's own project")
            .isLessThanOrEqualTo(18_000);
    }

    // -------------------------------------------------------------------------------------

    private static Task taskWithContracts() {
        Task task = taskWithNoContracts();
        task.setDeliveredContracts(List.of(new ApiContract(
            UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "OrderServiceImpl",
            "The server side of the order list.", "",
            "com.acme.orders.service.OrderServiceImpl",
            List.of("List<String> list()", "String save(String order)", "void delete(long id)"))));
        return task;
    }

    private static Task taskWithNoContracts() {
        return new Task(UUID.fromString("00000000-0000-0000-0000-0000000000b2"), 1L,
            "Add, correct and remove an order",
            "Keep a list of orders that survives a restart. Adding, correcting and removing one "
                + "must each be visible in the list straight away.",
            Set.of("orders-app-service/src/main/java/com/acme/orders/service/OrderServiceImpl.java"),
            Set.of(), List.of(), "src/test/java/swarm/accept", null, null,
            new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING);
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
