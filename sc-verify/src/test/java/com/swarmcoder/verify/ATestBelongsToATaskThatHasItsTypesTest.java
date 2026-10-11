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
package com.swarmcoder.verify;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build of 2026-10-12 (DEVELOPER_CORRECTIONS section 83), with other names. A
 * plan of four tasks; the first writes three shared types and owns a test that imports three
 * server types the second task writes. The test belongs to the second task. And a file that was
 * not on the start tree is not called clean there.
 */
class ATestBelongsToATaskThatHasItsTypesTest {

    private static final String SHARED = "shop-shared/src/main/java/org/example/shop/shared/";
    private static final String SERVER = "shop-server/src/main/java/org/example/shop/server/";
    private static final String TEST = "shop-server/src/test/java/swarm/accept/OrderStoreTest.java";

    private final Task shared = task("Add the shared order types",
        SHARED + "Order.java", SHARED + "OrderLine.java", SHARED + "OrderStatus.java");
    private final Task server = task("Store orders on the server",
        SERVER + "OrderStore.java", SERVER + "OrderRecord.java", SERVER + "OrderIndex.java");
    private final Task screen = task("Show the orders",
        "shop-client/src/main/java/org/example/shop/client/OrdersScreen.java");
    private final Task texts = task("Add the screen's texts",
        "shop-client/src/main/java/org/example/shop/client/OrdersTexts.java");
    private final List<Task> plan = List.of(shared, server, screen, texts);
    private final List<TaskEdge> edges = List.of(new TaskEdge(shared.id(), server.id()),
        new TaskEdge(server.id(), screen.id()), new TaskEdge(screen.id(), texts.id()));

    private static final String IMPORTS_THE_SERVER = """
        package swarm.accept;

        import org.example.shop.shared.Order;
        import org.example.shop.server.OrderStore;
        import org.example.shop.server.OrderRecord;
        import org.example.shop.server.OrderIndex;
        import org.junit.jupiter.api.Test;

        class OrderStoreTest {
            @Test
            void storesAnOrder() {
                new OrderStore(new OrderIndex()).put(new OrderRecord(new Order()));
            }
        }
        """;

    @Test
    void theTestGoesToTheTaskThatWritesWhatItImports() {
        TestOwnership.Finding finding = TestOwnership.find(shared, plan, edges,
            List.of(IMPORTS_THE_SERVER), null, type -> false, null);

        assertThat(finding).isNotNull();
        assertThat(finding.outside().keySet()).containsExactly(
            "org.example.shop.server.OrderStore", "org.example.shop.server.OrderRecord",
            "org.example.shop.server.OrderIndex");
        assertThat(finding.needs()).containsKey("org.example.shop.shared.Order");
        assertThat(finding.target()).as("it waits for the first owner, so it has every type")
            .isSameAs(server);
        assertThat(finding.fact())
            .contains("'Add the shared order types' owns an acceptance test it cannot compile")
            .contains("`org.example.shop.server.OrderStore`, which task "
                + "'Store orders on the server' writes")
            .contains("does not wait for a task that does");
    }

    @Test
    void aTaskThatWritesOrWaitsForWhatItsTestNeedsKeepsIt() {
        assertThat(TestOwnership.find(server, plan, edges, List.of(IMPORTS_THE_SERVER), null,
            type -> false, null)).as("its own types, and one of a task it waits for").isNull();
        assertThat(TestOwnership.find(screen, plan, edges, List.of(IMPORTS_THE_SERVER), null,
            type -> false, null)).as("waits for the server through the plan").isNull();
    }

    @Test
    void aTypeTheTreeAlreadyHoldsIsNotANeed() {
        assertThat(TestOwnership.find(shared, plan, edges, List.of(IMPORTS_THE_SERVER), null,
            type -> type.startsWith("org.example.shop.server."), null))
            .as("a later task changes those files; the test compiles without it").isNull();
    }

    @Test
    void whenNoTaskHasEveryTypeNothingIsMovedAndTheFactIsReturned() {
        // Two tasks that do not wait for each other each write one type the test needs.
        Task left = task("Left", SERVER + "OrderStore.java");
        Task right = task("Right", SERVER + "OrderIndex.java");
        Task owner = task("Owner", SHARED + "Order.java");
        TestOwnership.Finding finding = TestOwnership.find(owner, List.of(owner, left, right),
            List.of(), List.of(IMPORTS_THE_SERVER), null, type -> false, null);

        assertThat(finding).isNotNull();
        assertThat(finding.target()).isNull();
        assertThat(finding.whyNoMove()).contains("No task of the plan has all of them")
            .contains("'Right'").contains("does not wait for");
    }

    @Test
    void aTaskAlreadyBuiltIsNotGivenATest() {
        TestOwnership.Finding finding = TestOwnership.find(shared, plan, edges,
            List.of(IMPORTS_THE_SERVER), null, type -> false, other -> other != server);

        assertThat(finding.target()).isNull();
        assertThat(finding.whyNoMove()).contains("has already been built");
    }

    @Test
    void aPackageTheCompilerMissesIsReadFromTheWriteSetsWhenNoTaskNamesTheType() {
        // Directory write sets and no contract: only the compiler's own "package does not
        // exist" says what is needed, and only a package directory says who writes it.
        Task owner = task("Owner", "shop-shared/src/main/java/org/example/shop/shared");
        Task later = task("Later", "shop-server/src/main/java/org/example/shop/server");
        List<TaskEdge> waits = List.of(new TaskEdge(owner.id(), later.id()));

        TestOwnership.Finding finding = TestOwnership.find(owner, List.of(owner, later), waits,
            List.of(), List.of("org.example.shop.server"), type -> false, null);
        assertThat(finding).isNotNull();
        assertThat(finding.target()).isSameAs(later);

        assertThat(TestOwnership.find(later, List.of(owner, later), waits, List.of(),
            List.of("org.example.shop.server", "org.example.shop.shared"), type -> false, null))
            .isNull();
    }

    @Test
    void aWriteSetThatCannotBeReadMovesNothing() {
        Task owner = task("Owner", "somewhere/odd");
        Task later = task("Later", "shop-server/src/main/java/org/example/shop/server");

        assertThat(TestOwnership.find(owner, List.of(owner, later), List.of(), List.of(),
            List.of("org.example.shop.server.OrderStore"), type -> false, null))
            .as("the owner may write anything: the question is not settled").isNull();
    }

    @Test
    void aContractNamesItsTaskAsTheWriter() {
        Task owner = task("Owner", SHARED + "Order.java");
        Task later = task("Later", "shop-server/pom.xml");
        later.setDeliveredContracts(List.of(new ApiContract(UUID.randomUUID(), "OrderStore", "",
            "", "org.example.shop.server.OrderStore", List.of("void put(OrderRecord record)"))));

        TestOwnership.Finding finding = TestOwnership.find(owner, List.of(owner, later),
            List.of(new TaskEdge(owner.id(), later.id())),
            List.of("import static org.example.shop.server.OrderStore.open;\n"), null,
            type -> false, null);
        assertThat(finding.target()).isSameAs(later);
    }

    // --------------------------------------------------- the start tree's files (item 3)

    private static final String TEST_NEEDS_THE_SERVER = """
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /workspace/shop-server/src/test/java/swarm/accept/OrderStoreTest.java:[4,31] package org.example.shop.server does not exist
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project shop-server: Compilation failure
        """;

    @Test
    void aFileThatWasNotOnTheStartTreeIsNotCleanThere(@TempDir Path tree) throws Exception {
        Path order = tree.resolve(SHARED + "Existing.java");
        Files.createDirectories(order.getParent());
        Files.writeString(order, "package org.example.shop.shared; class Existing {}");
        VerificationBaseline.StartCompile start =
            CompileFailureAttribution.startTree(null, null).withFilesOf(tree);

        assertThat(start.existed(SHARED + "Existing.java")).isTrue();
        assertThat(start.wasCleanOnTheStartTree(SHARED + "Existing.java", false)).isTrue();
        assertThat(start.existed(TEST)).isFalse();
        assertThat(start.wasCleanOnTheStartTree(TEST, true))
            .as("the tree compiled, and this file was not on it").isFalse();

        CompileFailure failure = CompileFailureAttribution.attribute(TEST_NEEDS_THE_SERVER,
            Set.of(SHARED + "Order.java"), "shop-server/src/test/java/swarm/accept", null,
            List.of(), start);
        assertThat(failure.cause()).isNotEqualTo(CompileFailureCause.CAUSED_BY_CHANGE);
        assertThat(failure.describe()).doesNotContain("That file compiled on the tree");
        assertThat(failure.acceptanceTest()).isTrue();
    }

    @Test
    void aStartTreeThatWasNotListedKeepsTheOldReading() {
        VerificationBaseline.StartCompile start = CompileFailureAttribution.startTree(null, null);

        assertThat(start.files()).isNull();
        assertThat(start.wasCleanOnTheStartTree(TEST, true)).isTrue();
    }

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), Set.of(),
            List.of(), "shop-server/src/test/java/swarm/accept", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }
}
