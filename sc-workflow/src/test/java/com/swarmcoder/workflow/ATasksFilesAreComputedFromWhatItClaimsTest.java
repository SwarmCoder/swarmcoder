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
import com.swarmcoder.verify.BuildLayout;
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
 * What a task starts with is computed, with no model, from what it claims (owner's decision,
 * 2026-10-08; section 73): the file of every contract it delivers - where the tree has the type,
 * or where its package puts a new one - and every existing file that stops compiling with it.
 * The planner no longer has to guess them, and where it wrote a path the tree or the contract
 * contradicts, the path is put right and the log says so.
 */
class ATasksFilesAreComputedFromWhatItClaimsTest {

    private static final String API = "shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "server/src/main/java/org/example/shop/server/OrderServiceImpl.java";
    private static final String ITEM = "shared/src/main/java/org/example/shop/Item.java";
    private static final String SCREEN =
        "client/src/main/java/org/example/shop/client/OrderScreen.java";

    /** Three modules, as a build says it: what it compiles, tests and packages. */
    private static final BuildLayout.Layout LAYOUT = new BuildLayout.Layout("maven",
        List.of("shared/src/main/java", "server/src/main/java", "server/src/main/resources",
            "server/src/test/java", "client/src/main/java"),
        List.of("shared", "server", "client"), List.of(), "");

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
        write(IMPL, """
            package org.example.shop.server;

            import org.example.shop.OrderService;

            public class OrderServiceImpl implements OrderService {
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
    void run74sTaskIsGivenTheInterfaceAndTheClassThatStopsCompilingWithoutBeingTold() {
        Task extend = task("Extend OrderService", Set.of(),
            contract("org.example.shop.OrderService", "void place(String id)",
                "void cancel(String id)"));
        TaskGraph graph = graph(extend);

        List<String> lines = ComputedReservation.apply(graph, LAYOUT, repo);

        assertThat(extend.writeSet()).containsExactlyInAnyOrder(API, IMPL);
        assertThat(extend.computedReservation()).containsExactlyInAnyOrder(API, IMPL);
        assertThat(lines).anyMatch(l -> l.contains("'Extend OrderService'")
            && l.contains("void cancel(String id)") && l.contains(IMPL)
            && l.contains("reserved for this task too"));
        assertThat(ChangeBreaksExistingCode.objections(graph, repo))
            .as("so the plan check that sent run 74's plan back has nothing to say").isEmpty();
    }

    @Test
    void aRecordGainingAComponentTakesEveryFileThatConstructsIt() {
        Task grow = task("Give an item a price", Set.of(),
            contract("org.example.shop.Item", "String name", "int price"));

        ComputedReservation.apply(graph(grow), LAYOUT, repo);

        assertThat(grow.writeSet()).containsExactlyInAnyOrder(ITEM, SCREEN);
    }

    @Test
    void aNewTypeGoesWhereItsPackageAlreadyLives() {
        Task add = task("Add the cancel command", Set.of(),
            contract("org.example.shop.server.CancelCommand", "String id()"));

        ComputedReservation.apply(graph(add), LAYOUT, repo);

        assertThat(add.writeSet()).containsExactly(
            "server/src/main/java/org/example/shop/server/CancelCommand.java");
    }

    @Test
    void aNewTypeInANewPackageGoesInTheModuleThePlannerNamedOrTheNearestPackageAbove() {
        Task named = task("Add billing", Set.of("server/src/main/java"),
            contract("org.example.billing.Invoice", "String number()"));
        Task nearest = task("Add order history", Set.of(),
            contract("org.example.shop.server.history.OrderHistory", "int size()"));

        ComputedReservation.apply(graph(named, nearest), LAYOUT, repo);

        assertThat(named.writeSet())
            .as("the directory the planner gave already covers the file; nothing is added")
            .containsExactly("server/src/main/java");
        assertThat(nearest.writeSet()).containsExactly(
            "server/src/main/java/org/example/shop/server/history/OrderHistory.java");
    }

    @Test
    void whenTheModuleCannotBeToldNothingIsComputedAndTheLogSaysSo() {
        Task nowhere = task("Add billing", Set.of(),
            contract("com.elsewhere.billing.Invoice", "String number()"));

        List<String> lines = ComputedReservation.apply(graph(nowhere), LAYOUT, repo);

        assertThat(nowhere.writeSet()).as("left unrestricted, as a task with no paths was")
            .isEmpty();
        assertThat(lines).anyMatch(l -> l.contains("no file was computed for the new type "
            + "com.elsewhere.billing.Invoice"));
    }

    @Test
    void whatThePlannerAddedIsKeptAndWhatTheTreeOrTheContractContradictsIsPutRight() {
        String resource = "server/src/main/resources/orders.properties";
        String wrongPlace = "server/src/main/java/org/example/shop/OrderService.java";
        String wrongFolder = "server/src/main/java/commands/CancelCommand.java";
        Task task = task("Cancel an order", Set.of(resource, wrongPlace, wrongFolder),
            contract("org.example.shop.OrderService", "void place(String id)"),
            contract("org.example.shop.server.CancelCommand", "String id()"));

        List<String> lines = ComputedReservation.apply(graph(task), LAYOUT, repo);

        assertThat(task.writeSet()).containsExactlyInAnyOrder(resource, API,
            "server/src/main/java/org/example/shop/server/CancelCommand.java");
        assertThat(lines)
            .anyMatch(l -> l.contains(wrongPlace) && l.contains("the project already has in "
                + API) && l.contains("dropped"))
            .anyMatch(l -> l.contains(wrongFolder) && l.contains("whose package puts it in "
                + "server/src/main/java/org/example/shop/server/CancelCommand.java")
                && l.contains("replaced"));
        assertThat(task.computedReservation())
            .as("the record says which entries were computed").doesNotContain(resource);
    }

    @Test
    void aPathThePlannerWroteExactlyWhereTheContractPutsItStands() {
        String exact = "client/src/main/java/org/example/shop/server/CancelCommand.java";
        Task task = task("Cancel an order", Set.of(exact),
            contract("org.example.shop.server.CancelCommand", "String id()"));

        ComputedReservation.apply(graph(task), LAYOUT, repo);

        assertThat(task.writeSet())
            .as("the package is right; which module is the planner's to say").containsExactly(exact);
    }

    @Test
    void aTaskThatDeliversNoContractKeepsThePlannersPaths() {
        Task glue = task("Wire it in", Set.of(SCREEN));

        assertThat(ComputedReservation.apply(graph(glue), LAYOUT, repo)).isEmpty();
        assertThat(glue.writeSet()).containsExactly(SCREEN);
        assertThat(glue.computedReservation()).isEmpty();
    }

    @Test
    void twoTasksTheComputationGivesOneFileAreSentBackUnlessOneWaitsForTheOther() {
        Task extend = task("Extend OrderService", Set.of(),
            contract("org.example.shop.OrderService", "void place(String id)",
                "void cancel(String id)"));
        Task implement = task("Implement cancel", Set.of(IMPL));

        TaskGraph beside = graph(extend, implement);
        ComputedReservation.apply(beside, LAYOUT, repo);
        assertThat(new TaskGraphValidator().validate(beside, null, LAYOUT, null, repo).violations())
            .as("nothing orders them: the plan's own disjointness check speaks")
            .anyMatch(v -> v.contains("overlap on write path") && v.contains(IMPL));

        Task extendFirst = task("Extend OrderService", Set.of(),
            contract("org.example.shop.OrderService", "void place(String id)",
                "void cancel(String id)"));
        Task implementAfter = task("Implement cancel", Set.of(IMPL));
        TaskGraph ordered = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(extendFirst, implementAfter)),
            new ArrayList<>(List.of(new TaskEdge(extendFirst.id(), implementAfter.id()))));
        ComputedReservation.apply(ordered, LAYOUT, repo);
        assertThat(new TaskGraphValidator().validate(ordered, null, LAYOUT, null, repo)
            .violations())
            .as("one waits for the other: they may share the file")
            .noneMatch(v -> v.contains("overlap on write path"))
            .noneMatch(v -> v.contains("stops") && v.contains("compiling"));
    }

    @Test
    void onlyProductionJavaRootsAreWhereANewTypeMayBePut() {
        assertThat(ComputedReservation.javaSourceRoots(LAYOUT)).containsExactly(
            "shared/src/main/java", "server/src/main/java", "client/src/main/java");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ApiContract contract(String type, String... members) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return new ApiContract(UUID.randomUUID(), simple, "what the story needs", simple, type,
            List.of(members));
    }

    private static Task task(String title, Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "server/src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        task.setDeliveredContracts(List.of(delivers));
        return task;
    }

    private static TaskGraph graph(Task... tasks) {
        return new TaskGraph(UUID.randomUUID(), 1, null, new ArrayList<>(List.of(tasks)),
            new ArrayList<>());
    }
}
