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
import com.swarmcoder.domain.Waves;
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
 * DEVELOPER_CORRECTIONS section 66, live run 90 (2026-10-07). The plan ran "use the new type in
 * two screens" BEFORE "create the new type": the planner had written both its edges the wrong way
 * round. The product logged "read as describing what a later task builds on its work, which is
 * normal" and kept the order. The fixture is that plan's shape: its titles, write sets, read set
 * and edges, on a tree that holds the two screens and the two text classes and not the new type.
 */
class APlanDoesNotRunATaskBeforeTheTypeItUsesExistsTest {

    private static final String SHARED = "shared/src/main/java/com/log/";
    private static final String CLIENT = "client/src/main/java/com/log/client/";
    private static final String CREATE = "Add shared UtcDateTime utility and service method";
    private static final String TEXTS = "Change UTC label constants in client i18n";
    private static final String SCREENS = "Use UtcDateTime in add/edit contact screens";

    @TempDir
    Path repo;

    private Task create;
    private Task texts;
    private Task screens;

    @BeforeEach
    void theTreeHoldsTheScreensAndTheirTextsAndNotTheNewType() throws Exception {
        write(SHARED + "LogService.java", "package com.log;\npublic interface LogService {}\n");
        write(CLIENT + "AddScreen.java",
            "package com.log.client;\npublic final class AddScreen {}\n");
        write(CLIENT + "EditScreen.java",
            "package com.log.client;\npublic final class EditScreen {}\n");
        write(CLIENT + "i18n/AddTexts.java",
            "package com.log.client.i18n;\npublic final class AddTexts {}\n");
        create = task(CREATE, "Create new file " + SHARED + "UtcDateTime.java: a public final "
                + "class with static String format(long) and static long parse(String).",
            Set.of(SHARED + "UtcDateTime.java", SHARED + "LogService.java"), Set.of(),
            contract("UtcDateTime", "com.log.UtcDateTime",
                List.of("static String format(long epochMillis)", "static long parse(String value)")));
        texts = task(TEXTS, "In AddTexts change START_UTC to \"Start (UTC)\".",
            Set.of(CLIENT + "i18n/AddTexts.java"), Set.of(),
            contract("AddTexts", "com.log.client.i18n.AddTexts",
                List.of("static final String START_UTC")));
        screens = task(SCREENS, "In AddScreen.save() replace Long.parseLong(...) with "
                + "UtcDateTime.parse(...). Keep the labels reading from AddTexts.START_UTC.",
            Set.of(CLIENT + "AddScreen.java", CLIENT + "EditScreen.java"),
            Set.of(SHARED + "UtcDateTime.java"),
            contract("AddScreen", "com.log.client.AddScreen", List.of("Component render()")));
    }

    @Test
    void run90sPlanIsTurnedRoundWhereItsReadSetNamesTheFileToCome() {
        // Until section 76 this went back to the planner with the pair and the edge to write.
        // The read set is a fact, nothing the creating task delivers or reads comes from the
        // screens' task, and the planner's edge ran directly against it: it is turned round.
        // As the planner wrote them: {"from":"task-3","to":"task-1"}, {"from":"task-3","to":"task-2"}.
        TaskGraph graph = graph(new TaskEdge(screens.id(), create.id()),
            new TaskEdge(screens.id(), texts.id()));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(graph, null, repo);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.turned()).hasSize(1);
        assertThat(outcome.turned().get(0))
            .contains("'" + CREATE + "' now finishes before '" + SCREENS + "'")
            .contains("the read set of '" + SCREENS + "' names UtcDateTime");
        assertThat(outcome.notes())
            .as("AddTexts is already in the tree: naming it needs no order, as before")
            .anySatisfy(n -> assertThat(n).contains("mentions AddTexts")
                .contains("the planner's order is kept"));
        assertThat(graph.dependencies()).as("the edge against the read set is turned; the one "
            + "about a type already in the tree is the planner's and stays").hasSize(2);
        assertThat(graph.dependencies()).anyMatch(edge -> edge.from().equals(create.id())
            && edge.to().equals(screens.id()));
        assertThat(graph.dependencies()).anyMatch(edge -> edge.from().equals(screens.id())
            && edge.to().equals(texts.id()));
    }

    @Test
    void theSamePlanIsSentBackOnItsInstructionsAloneWhenTheReadSetSaysNothing() {
        screens = task(SCREENS, screens.instructions(), screens.writeSet(), Set.of(),
            screens.deliveredContracts().get(0));
        TaskGraph graph = graph(new TaskEdge(screens.id(), create.id()));

        TaskGraphValidator.Verdict verdict =
            new TaskGraphValidator().validate(graph, null, null, null, repo);

        assertThat(verdict.violations()).anySatisfy(v -> assertThat(v)
            .contains("'" + SCREENS + "' is told to use UtcDateTime")
            .contains("does not exist in the project yet")
            .contains("the plan runs '" + SCREENS + "' BEFORE '" + CREATE + "'")
            .contains("take the name out of its instructions instead"));
        assertThat(verdict.warnings())
            .noneSatisfy(w -> assertThat(w).contains("mentions UtcDateTime"));
    }

    @Test
    void withNoOrderBetweenThemTheDependencyIsAddedAndTheWavesFollow() {
        TaskGraph graph = graph();

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(graph, null, repo);

        assertThat(outcome.violations()).isEmpty();
        assertThat(graph.dependencies()).contains(new TaskEdge(create.id(), screens.id()));
        assertThat(outcome.added()).anySatisfy(a -> assertThat(a)
            .contains("'" + SCREENS + "' now depends on '" + CREATE + "'")
            .contains("its read set names UtcDateTime"));
        List<List<Task>> waves = Waves.of(graph.tasks(), Task::id, graph.dependencies().stream()
            .map(e -> new Waves.Edge(e.from(), e.to())).toList()).waves();
        assertThat(waves.get(0)).extracting(Task::title).contains(CREATE).doesNotContain(SCREENS);
    }

    @Test
    void aTaskNamingATypeThatAlreadyExistsMayStillRunFirst() {
        // The screens task names AddTexts, which the texts task changes and the tree already
        // has: it compiles against the type as it is, so "a later task builds on it" can be true.
        TaskGraph graph = graph(new TaskEdge(screens.id(), texts.id()),
            new TaskEdge(create.id(), screens.id()));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(graph, null, repo);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.notes()).anySatisfy(n -> assertThat(n).contains("mentions AddTexts"));
    }

    @Test
    void aLaterTaskThatUsesANewTypeOfTheNamingTaskKeepsThePlannersOrder() {
        // Run 40's shape, on a tree: the model task says the store "will persist" what it
        // creates, and the store's contract uses the model's new type.
        Task model = task("Create Entry", "Create Entry. EntryStore will persist it.",
            Set.of(SHARED + "Entry.java"), Set.of(),
            contract("Entry", "com.log.Entry", List.of("String id()")));
        Task store = task("Create EntryStore", "Implement EntryStore.",
            Set.of(SHARED + "EntryStore.java"), Set.of(),
            contract("EntryStore", "com.log.EntryStore", List.of("void save(Entry entry)")));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(model, store)),
            new ArrayList<>(List.of(new TaskEdge(model.id(), store.id()))));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(graph, null, repo);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.notes()).anySatisfy(n -> assertThat(n)
            .contains("mentions EntryStore").contains("the planner's order is kept"));
    }

    @Test
    void twoTasksThatMustBothWriteAFileOneOfThemCreatesAreNeverRunSideBySide() {
        // The write-set side: a task that must edit a file another task creates has that file
        // in its own write set too, and two unordered tasks may not share a path.
        Task edits = task("Add a zone to UtcDateTime", "Add a zone parameter.",
            Set.of(SHARED + "UtcDateTime.java"), Set.of());
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(create, edits)), new ArrayList<>());

        TaskGraphValidator.Verdict verdict =
            new TaskGraphValidator().validate(graph, null, null, null, repo);

        assertThat(verdict.violations()).anySatisfy(v -> assertThat(v)
            .contains("overlap on write path").contains("UtcDateTime.java")
            .contains("add an edge between them"));
    }

    // --- fixtures ------------------------------------------------------------------------------

    private TaskGraph graph(TaskEdge... edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(create, texts, screens)), new ArrayList<>(List.of(edges)));
    }

    private void write(String relative, String text) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static ApiContract contract(String name, String type, List<String> members) {
        return new ApiContract(UUID.randomUUID(), name, name + " contract", "", type, members);
    }

    private static Task task(String title, String instructions, Set<String> writeSet,
                             Set<String> readSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, readSet,
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        task.setDeliveredContracts(new ArrayList<>(List.of(delivers)));
        return task;
    }
}
