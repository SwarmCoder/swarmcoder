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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
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
 * Live run 74: "add two methods to the existing service interface" was planned one wave before
 * "implement them in the existing implementing class", and no candidate of the first task could
 * compile the build. The plan check now sends such a plan back, naming the files.
 */
class AChangeThatBreaksExistingCodeTakesThatCodeWithItTest {

    private static final String API = "shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "server/src/main/java/org/example/shop/server/OrderServiceImpl.java";
    private static final String ITEM = "shared/src/main/java/org/example/shop/Item.java";
    private static final String SCREEN =
        "client/src/main/java/org/example/shop/client/OrderScreen.java";

    @TempDir
    Path repo;

    @BeforeEach
    void anExistingProject() throws Exception {
        write(API, """
            package org.example.shop;

            public interface OrderService {
                void place(String id);
            }
            """);
        write("server/src/main/java/org/example/shop/server/BaseService.java", """
            package org.example.shop.server;

            import org.example.shop.OrderService;

            public abstract class BaseService implements OrderService {
            }
            """);
        write(IMPL, """
            package org.example.shop.server;

            public class OrderServiceImpl extends BaseService {
                @Override
                public void place(String id) {
                }
            }
            """);
        write(ITEM, """
            package org.example.shop;

            public record Item(String name) {
            }
            """);
        write(SCREEN, """
            package org.example.shop.client;

            import org.example.shop.Item;

            public class OrderScreen {
                Item first() {
                    return new Item("a");
                }
            }
            """);
    }

    @Test
    void anAbstractMethodAddedToAnInterfaceSplitFromItsImplementorIsSentBack() {
        Task extend = task("Extend OrderService", Set.of(API),
            contract("org.example.shop.OrderService", "void place(String id)",
                "void cancel(String id)"));
        Task implement = task("Implement cancel", Set.of(IMPL));
        List<String> objections = ChangeBreaksExistingCode.objections(
            graph(List.of(extend, implement), new TaskEdge(extend.id(), implement.id())), repo);

        assertThat(objections).hasSize(1);
        assertThat(objections.get(0))
            .contains("'Extend OrderService'").contains("void cancel(String id)")
            .contains("org.example.shop.OrderService").contains(IMPL).contains("implements it")
            .contains("Merge this task with 'Implement cancel'")
            .doesNotContain("BaseService.java");
    }

    @Test
    void theSameThroughTheWholeValidatorIsAViolation() {
        Task extend = task("Extend OrderService", Set.of(API),
            contract("org.example.shop.OrderService", "void cancel(String id)"));
        TaskGraphValidator.Verdict verdict = new TaskGraphValidator().validate(
            graph(List.of(extend)), null, null, null, repo);

        assertThat(verdict.violations()).anyMatch(v -> v.contains(IMPL)
            && v.contains("to this task's writeSet"));
    }

    @Test
    void theImplementorInTheWriteSetIsAccepted() {
        Task both = task("Add cancel", Set.of(API, "server/src/main/java"),
            contract("org.example.shop.OrderService", "void cancel(String id)"));
        assertThat(ChangeBreaksExistingCode.objections(graph(List.of(both)), repo)).isEmpty();
    }

    @Test
    void aDefaultMethodAndAnAlreadyImplementedOneBreakNothing() {
        Task extend = task("Extend OrderService", Set.of(API),
            contract("org.example.shop.OrderService", "default void cancel(String id)",
                "void place(String id)"));
        assertThat(ChangeBreaksExistingCode.objections(graph(List.of(extend)), repo)).isEmpty();
    }

    @Test
    void aRecordComponentAddedWithoutItsCallersIsSentBack() {
        Task extend = task("Give Item a price", Set.of(ITEM),
            contract("org.example.shop.Item", "String name", "int price"));
        List<String> objections =
            ChangeBreaksExistingCode.objections(graph(List.of(extend)), repo);

        assertThat(objections).hasSize(1);
        assertThat(objections.get(0)).contains("int price").contains(SCREEN)
            .contains("constructs it");
    }

    @Test
    void aNewTypeAndAGreenfieldRunAreLeftAlone() {
        Task create = task("Create Basket",
            Set.of("shared/src/main/java/org/example/shop/Basket.java"),
            contract("org.example.shop.Basket", "void add(String id)"));
        assertThat(ChangeBreaksExistingCode.objections(graph(List.of(create)), repo)).isEmpty();

        Task extend = task("Extend OrderService", Set.of(API),
            contract("org.example.shop.OrderService", "void cancel(String id)"));
        assertThat(ChangeBreaksExistingCode.objections(graph(List.of(extend)), null)).isEmpty();
    }

    private void write(String relative, String source) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static ApiContract contract(String typeName, String... members) {
        return new ApiContract(UUID.randomUUID(),
            typeName.substring(typeName.lastIndexOf('.') + 1), "", null, typeName,
            List.of(members));
    }

    private static Task task(String title, Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            null, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        task.setDeliveredContracts(List.of(delivers));
        return task;
    }

    private static TaskGraph graph(List<Task> tasks, TaskEdge... edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, List.of(edges));
    }
}
