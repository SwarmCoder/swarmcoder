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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mechanical check objects only to something it has established. Each case here is correct work
 * that a check objected to because of how it read text, found by the audit of 2026-10-02 (branch
 * fix/audit-the-gates) rather than by a live run.
 */
class AChecksObjectionIsAFactItEstablishedTest {

    private static final String TEST_PATH = "app/src/test/java/swarm/accept/LogbookTest.java";

    @TempDir
    Path tree;

    // ---- the test's vocabulary -----------------------------------------------------------------

    @Test
    void aTypeThatMayComeFromAWildcardImportIsNotCalledUnknown() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.Qso;
            import org.junit.jupiter.api.*;
            import com.library.ui.component.*;
            class LogbookTest {
                @Test
                void adds() {
                    Qso qso = new Qso();
                    Button save = new Button("Save");
                    Assertions.assertNotNull(qso);
                }
            }
            """);

        assertThat(vocabulary().unknowns()).isEmpty();
    }

    @Test
    void aConstantIsNotATypeTheTestNames() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.Qso;
            import org.junit.jupiter.api.Test;
            import static com.acme.QslState.SENT;
            class LogbookTest {
                private static final String CALL = "DL1ABC";
                private static final java.nio.file.Path ROOT = java.nio.file.Path.of("x");
                @Test
                void adds() {
                    Qso qso = new Qso();
                    int length = CALL.length() + ROOT.getNameCount() + SENT.ordinal();
                }
            }
            """);

        assertThat(vocabulary().unknowns()).isEmpty();
    }

    @Test
    void aTypeTheBuildGeneratesFromAKnownTypeIsNotCalledUnknown() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.Qso;
            import com.acme.Qso_Rules;
            import org.junit.jupiter.api.Test;
            class LogbookTest {
                @Test
                void validates() {
                    Qso_Rules.validate(new Qso());
                }
            }
            """);

        assertThat(vocabulary().unknowns()).isEmpty();
    }

    @Test
    void aTypeInAProjectPackageThatNothingDeliversIsStillUnknown() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.QsoValidator;
            import org.junit.jupiter.api.Test;
            class LogbookTest {
                @Test
                void validates() {
                    new QsoValidator();
                }
            }
            """);

        assertThat(vocabulary().unknowns()).extracting(AcceptanceTestVocabulary.Unknown::typeName)
            .containsExactly("com.acme.QsoValidator");
    }

    // ---- which tests were written --------------------------------------------------------------

    @Test
    void aTypeNamedInACommentIsNotTheClassTheTestsAreIn() throws Exception {
        write(TEST_PATH, """
            package swarm.accept;
            import org.junit.jupiter.api.Test;
            /** Proves the interface LogbookService stores what it is given. */
            class LogbookTest {
                /** Hands the test the class Producer makes. */
                static class Producer {
                    Object make() { return null; }
                }
                @Test
                void addsContact() { }
            }
            """);
        AcceptanceCriterion check = new AcceptanceCriterion(UUID.randomUUID(), "adding stores it",
            "swarm.accept.LogbookTest#addsContact");

        AuthoredTestAudit.Result audit = AuthoredTestAudit.audit(List.of(check), List.of("R1:C1"),
            tree, TestAuthorClient.Authored.of(List.of(TEST_PATH), List.of()));

        assertThat(audit.findings()).isEmpty();
        assertThat(audit.mapping()).containsExactly("R1:C1 is proved by swarm.accept.LogbookTest#addsContact");
    }

    // ---- a test that implements the contract itself ----------------------------------------------

    @Test
    void usingAMockingFrameworkForSomethingElseIsNotMockingTheContract() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.Qso;
            import com.acme.LogbookService;
            import org.junit.jupiter.api.Test;
            import static org.mockito.Mockito.mock;
            class LogbookTest {
                @Test
                void adds() {
                    java.time.Clock clock = mock(java.time.Clock.class);
                    LogbookService service = new com.acme.server.LogbookServiceImpl(clock);
                    service.addQso(new Qso());
                }
            }
            """);

        assertThat(SelfImplementedContract.check(tree, design(), List.of(TEST_PATH), List.of())
            .findings()).isEmpty();
    }

    @Test
    void mockingTheContractIsStillFound() throws Exception {
        existing();
        write(TEST_PATH, """
            package swarm.accept;
            import com.acme.LogbookService;
            import org.junit.jupiter.api.Test;
            import org.mockito.Mock;
            class LogbookTest {
                @Mock
                private LogbookService service;
                @Test
                void adds() {
                    LogbookService other = org.mockito.Mockito.mock(LogbookService.class);
                }
            }
            """);

        assertThat(SelfImplementedContract.check(tree, design(), List.of(TEST_PATH), List.of())
            .contractTypes()).containsExactly("LogbookService");
    }

    // ---- the plan's order ----------------------------------------------------------------------

    @Test
    void aNestedTypeOfSomethingElseIsNotThePlannedTypeOfThatName() {
        // two tasks; the second's contract uses java.util.Map.Entry, the first delivers com.acme.Entry
        ApiContract entry = contract("com.acme.Entry", "String call()");
        ApiContract index = contract("com.acme.Index",
            "java.util.Map.Entry<String, String> first()", "Map.Entry<String, String> last()");
        Task first = task("Deliver Entry", Set.of("app/src/main/java/com/acme/Entry.java"), entry);
        Task second = task("Deliver Index", Set.of("app/src/main/java/com/acme/Index.java"), index);
        // the planner ordered them the other way round, which is its right: nothing connects them
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, new ArrayList<>(List.of(first, second)),
            new ArrayList<>(List.of(new TaskEdge(second.id(), first.id()))));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(graph, design(entry, index));

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.added()).isEmpty();
    }

    // ---- the types a contract names --------------------------------------------------------------

    @Test
    void aConstantOfARealTypeIsNotATypeThatDoesNotExist() throws Exception {
        existing();
        DesignDocument design = design(contract("com.acme.Settings",
            "java.time.Duration timeout = java.time.Duration.ZERO",
            "java.util.Map.Entry<String, String> first()",
            "@Retention(java.lang.annotation.RetentionPolicy.RUNTIME) String name()"));

        assertThat(ContractsNameRealTypes.objections(design, ProjectTypes.of(tree), LibraryTypes.NONE))
            .isEmpty();
    }

    @Test
    void aTypeTheJdkDoesNotHaveIsStillAnObjection() throws Exception {
        existing();
        DesignDocument design = design(contract("com.acme.Settings", "java.util.Lisst<String> names()"));

        assertThat(ContractsNameRealTypes.objections(design, ProjectTypes.of(tree), LibraryTypes.NONE))
            .singleElement().asString().contains("java.util.Lisst");
    }

    @Test
    void aRemarkAfterADeclarationIsNotSearchedForTypes() throws Exception {
        existing();
        DesignDocument design = design(contract("com.acme.Settings",
            "String name() - see java.util.NoSuchThing for why"));

        assertThat(ContractsNameRealTypes.objections(design, ProjectTypes.of(tree), LibraryTypes.NONE))
            .isEmpty();
    }

    // ---- technology the rules forbid ---------------------------------------------------------------

    @Test
    void theRestOfSomethingIsNotRest() {
        String rules = "- Forbidden frameworks and libraries\n  Do not use, add, import, or write "
            + "tests against: Spring or Spring Boot; REST or JSON endpoints.";
        Task ordinary = task("Add the form", Set.of());
        ordinary.setInstructions("Lay the callsign field out with the rest of the fields, using the "
            + "rest of the row for the frequency.");
        Task violating = task("Add an endpoint", Set.of());
        violating.setInstructions("Expose the logbook using REST, and return it with JSON.");

        assertThat(ForbiddenTechGuard.check(rules, List.of(ordinary))).isEmpty();
        assertThat(ForbiddenTechGuard.check(rules, List.of(violating))).hasSize(2);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private AcceptanceTestVocabulary.Check vocabulary() {
        return AcceptanceTestVocabulary.check(tree, design(), List.of(TEST_PATH), List.of());
    }

    /** A project that already has Qso, QslState and LogbookService in com.acme. */
    private void existing() throws Exception {
        write("app/src/main/java/com/acme/Qso.java", "package com.acme;\npublic class Qso { }\n");
        write("app/src/main/java/com/acme/QslState.java",
            "package com.acme;\npublic enum QslState { SENT, NO }\n");
        write("app/src/main/java/com/acme/LogbookService.java",
            "package com.acme;\npublic interface LogbookService { Qso addQso(Qso qso); }\n");
    }

    private static DesignDocument design(ApiContract... contracts) {
        List<ApiContract> all = contracts.length > 0 ? List.of(contracts)
            : List.of(contract("com.acme.LogbookService", "Qso addQso(Qso qso)"),
                contract("com.acme.Qso", "Qso()"));
        return new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(), List.of(), all, List.of(),
            null, Instant.EPOCH);
    }

    private static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type.substring(type.lastIndexOf('.') + 1), "", "",
            type, List.of(members));
    }

    private static Task task(String title, Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, "", new HashSet<>(writeSet), new HashSet<>(),
            new ArrayList<>(), null, null, null, null, TaskState.PENDING);
        task.setDeliveredContracts(List.of(delivers));
        return task;
    }

    private void write(String relative, String source) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
