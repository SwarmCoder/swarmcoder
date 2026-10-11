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

import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.TestOwnership;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build of 2026-10-12 (DEVELOPER_CORRECTIONS section 83), with other names: what
 * the red check does with a test whose task will not have the types it needs. The finding itself
 * is {@code ATestBelongsToATaskThatHasItsTypesTest} in sc-verify.
 */
class ATestIsGivenToTheTaskThatCanPassItTest {

    private static final String TEST = "shop-server/src/test/java/swarm/accept/OrderStoreTest.java";
    private static final String OWN = "shop-server/src/test/java/swarm/accept/OrderIndexTest.java";
    private static final String SOURCE = """
        package swarm.accept;
        import org.example.shop.shared.Order;
        import org.example.shop.server.OrderStore;
        class OrderStoreTest { }
        """;

    private final Task shared = task("Add the shared order types",
        "shop-shared/src/main/java/org/example/shop/shared/Order.java");
    private final Task server = task("Store orders on the server",
        "shop-server/src/main/java/org/example/shop/server/OrderStore.java");
    private final UUID check = UUID.randomUUID();
    private final UUID serversOwn = UUID.randomUUID();

    @Test
    void theTestAndItsChecksMoveAndTheFirstOwnerClaimsNothing() {
        shared.setAuthoredTestPaths(List.of(TEST));
        shared.setCriterionIds(new LinkedHashSet<>(List.of(check)));
        AuthoredTests written = AuthoredTests.started(1, Instant.parse("2026-10-12T08:00:00Z"));
        written.setWrittenAt(Instant.parse("2026-10-12T08:05:00Z"));
        written.setFiles(List.of(TEST));
        written.setTests(List.of(new AuthoredTest("swarm.accept.OrderStoreTest#storesAnOrder",
            TEST, "R1:C1", "An order is stored")));
        shared.setAuthoredTests(written);
        server.setAuthoredTestPaths(List.of(OWN));
        server.setCriterionIds(new LinkedHashSet<>(List.of(serversOwn)));
        AuthoredTests held = AuthoredTests.started(1, Instant.parse("2026-10-12T08:06:00Z"));
        held.setFiles(List.of(OWN));
        server.setAuthoredTests(held);

        TestOwnership.Finding finding = TestOwnership.find(shared, List.of(shared, server),
            List.of(new TaskEdge(shared.id(), server.id())), List.of(SOURCE), null,
            type -> false, null);
        List<String> moved = TestOwners.give(finding.owner(), finding.target());

        assertThat(moved).containsExactly(TEST);
        assertThat(server.authoredTestPaths()).containsExactly(OWN, TEST);
        assertThat(server.criterionIds()).containsExactlyInAnyOrder(serversOwn, check);
        assertThat(server.authoredTests().files()).containsExactly(OWN, TEST);
        assertThat(server.authoredTests().tests()).hasSize(1);
        assertThat(server.authoredTests().checksOffered()).isEqualTo(2);
        assertThat(shared.authoredTestPaths()).isEmpty();
        assertThat(shared.criterionIds()).isEmpty();
        assertThat(shared.authoredTests().checksOffered()).isZero();
        assertThat(shared.authoredTests().inProgress())
            .as("the first owner is not shown as still waiting for its author").isFalse();

        assertThat(TestOwners.movedNote(finding, moved))
            .contains("given to task 'Store orders on the server'")
            .contains("taken from task 'Add the shared order types'")
            .contains("`org.example.shop.server.OrderStore`")
            .contains("Only the claim moved");
        assertThat(TestOwnership.find(server, List.of(shared, server),
            List.of(new TaskEdge(shared.id(), server.id())), List.of(SOURCE), null,
            type -> false, null)).as("where it is now, it can be compiled").isNull();
    }

    @Test
    void whenNoTaskCanBeGivenItTheAuthorAndThenTheRunAreToldTheFact() {
        Task other = task("Index the orders",
            "shop-server/src/main/java/org/example/shop/server/OrderIndex.java");
        TestOwnership.Finding finding = TestOwnership.find(shared, List.of(shared, server, other),
            List.of(), List.of(SOURCE + "import org.example.shop.server.OrderIndex;\n"), null,
            type -> false, null);

        assertThat(finding.target()).isNull();
        assertThat(TestOwners.reask(finding))
            .contains("cannot be compiled when its task is built")
            .contains("`org.example.shop.server.OrderStore`, which task "
                + "'Store orders on the server' writes")
            .contains("No task of the plan has all of them")
            .contains("that is `org.example.shop.shared.Order`");
        assertThat(TestOwners.park(finding, null))
            .contains("No worker was started on 'Add the shared order types'")
            .contains("make one of the tasks wait for the other");
    }

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), Set.of(),
            List.of(), "shop-server/src/test/java/swarm/accept", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }
}
