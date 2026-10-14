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

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 114, finding 2: the plan made its first task impossible. It wrote the root build file
 * listing three modules, two of which later tasks create, and the contract's compile from the
 * root fails on a listed module that does not exist yet. No model: the plan's structure alone.
 */
class AnAggregatingBuildFileListsOnlyModulesThatExistTest {

    @TempDir
    Path emptyProject;

    private static Task task(String title, String... writes) {
        Task task = new Task(UUID.randomUUID(), 1, title, "Do " + title + ".",
            new LinkedHashSet<>(List.of(writes)), Set.of(), List.of(), null, null, null, null,
            TaskState.READY);
        return task;
    }

    private static TaskGraph plan(List<Task> tasks, TaskEdge... edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, new ArrayList<>(List.of(edges)));
    }

    private static boolean runsAfter(TaskGraph graph, Task first, Task later) {
        return graph.dependencies().stream()
            .anyMatch(e -> e.from().equals(first.id()) && e.to().equals(later.id()));
    }

    @Test
    void laterModulesAreAddedByTheTasksThatMakeThemAndTheFirstTaskListsOnlyItsOwn() {
        Task root = task("root and shared", "pom.xml", "shared/pom.xml",
            "shared/src/main/java/app/Book.java");
        Task server = task("server", "server/pom.xml", "server/src/main/java/app/Api.java");
        Task client = task("client", "client/pom.xml", "client/src/main/java/app/Screen.java");
        TaskGraph graph = plan(List.of(root, server, client),
            new TaskEdge(root.id(), server.id()));

        ModulesAreListedAsTheyAreMade.Outcome outcome =
            ModulesAreListedAsTheyAreMade.apply(graph, emptyProject);

        assertThat(outcome.violations()).isEmpty();
        assertThat(server.writeSet()).contains("pom.xml");
        assertThat(client.writeSet()).contains("pom.xml");
        assertThat(runsAfter(graph, root, client)).as("client could not add itself to a root "
            + "build file its checkout does not have").isTrue();
        assertThat(root.instructions()).contains("list only the modules that exist when this "
            + "task ends: shared").contains("Do NOT list server (task 'server'), client "
            + "(task 'client')");
        assertThat(server.instructions()).contains("Add the module `server` to the <modules> "
            + "of pom.xml");
        assertThat(client.instructions()).contains("Add the module `client`");
        assertThat(outcome.corrections()).hasSize(4);

        // The check runs on every draft: a second run changes nothing more.
        String before = root.instructions() + server.instructions() + client.instructions();
        int edges = graph.dependencies().size();
        ModulesAreListedAsTheyAreMade.Outcome again =
            ModulesAreListedAsTheyAreMade.apply(graph, emptyProject);
        assertThat(root.instructions() + server.instructions() + client.instructions())
            .isEqualTo(before);
        assertThat(graph.dependencies()).hasSize(edges);
        assertThat(again.violations()).isEmpty();

        // Through the plan check, said as notes, not objections.
        TaskGraphValidator.Verdict verdict = new TaskGraphValidator().validate(
            plan(List.of(task("a", "pom.xml"), task("b", "server/pom.xml"))), null, null,
            null, emptyProject);
        assertThat(verdict.corrections()).anyMatch(line -> line.contains("lists in pom.xml "
            + "only the modules that exist when it ends (none yet)"));
    }

    @Test
    void modulesMadeBeforeTheAggregatorAreItsOwnAndAnExistingAggregatorIsHeldByTheNewModule()
            throws Exception {
        Task shared = task("shared", "shared/pom.xml");
        Task root = task("root", "pom.xml");
        TaskGraph ordered = plan(List.of(shared, root), new TaskEdge(shared.id(), root.id()));
        ModulesAreListedAsTheyAreMade.Outcome nothing =
            ModulesAreListedAsTheyAreMade.apply(ordered, emptyProject);
        assertThat(nothing.corrections()).isEmpty();
        assertThat(shared.writeSet()).containsExactly("shared/pom.xml");

        Path existing = Files.createTempDirectory(emptyProject, "existing");
        Files.writeString(existing.resolve("pom.xml"), "<project/>");
        Task module = task("reports", "reports/pom.xml", "reports/src/main/java/R.java");
        ModulesAreListedAsTheyAreMade.apply(plan(List.of(module)), existing);
        assertThat(module.writeSet()).contains("pom.xml");
        assertThat(module.instructions()).contains("Add the module `reports`");

        Task one = task("one", "pom.xml");
        Task two = task("two", "pom.xml");
        Task three = task("three", "app/pom.xml");
        ModulesAreListedAsTheyAreMade.Outcome unclear =
            ModulesAreListedAsTheyAreMade.apply(plan(List.of(one, two, three)), emptyProject);
        assertThat(unclear.violations()).singleElement().asString()
            .contains("pom.xml is created by 'one' and 'two'");
    }
}
