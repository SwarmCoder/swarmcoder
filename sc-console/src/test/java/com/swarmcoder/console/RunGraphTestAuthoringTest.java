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
package com.swarmcoder.console;

import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the run graph knows about the test-authoring stage while it is still running.
 *
 * <p>The stage takes minutes, authors tasks one at a time, and used to reach the graph as nothing
 * at all: a task's state does not move during it, so the publisher's change detector saw no change
 * and no frame went out until the run moved on. These pin the three things that fix that - the
 * facts are on the task node, they tell "claims no checks" from "not authored yet", and a task
 * gaining tests is a change the publisher notices.
 */
class RunGraphTestAuthoringTest {

    @TempDir
    Path dir;

    private static final String BOOK_TEST = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        class BookTest {

            @Test
            void storesAllBookFields() {
            }
        }
        """;

    @Test
    void aTaskNodeSaysWhereTheTestAuthorIsWithIt_andTellsClaimsNoneFromNotYet() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID written = UUID.randomUUID();
            UUID writing = UUID.randomUUID();
            UUID claimsNone = UUID.randomUUID();
            UUID notYet = UUID.randomUUID();
            UUID nothingCameOut = UUID.randomUUID();
            seed(store, null, runId, RunState.TEST_AUTHORING, List.of(
                task(written, "Store a book", 2), task(writing, "Find a book", 1),
                task(claimsNone, "Set up the module", 0), task(notYet, "List the shelf", 1),
                task(nothingCameOut, "Count the books", 1)));

            store.append(() -> {
                taskIn(store, written).setAuthoredTests(writtenRecord());
                taskIn(store, writing).setAuthoredTests(AuthoredTests.started(1, Instant.now()));
                AuthoredTests none = AuthoredTests.started(0, Instant.now());
                none.setWrittenAt(Instant.now());
                taskIn(store, claimsNone).setAuthoredTests(none);
                AuthoredTests empty = AuthoredTests.started(1, Instant.now());
                empty.setWrittenAt(Instant.now());
                taskIn(store, nothingCameOut).setAuthoredTests(empty);
                return null;
            }).get();

            RunGraphDto snapshot = new GraphServiceImpl().snapshot(runId.toString());

            GraphTaskDto done = node(snapshot, written);
            assertThat(done.getTestsPhase()).isEqualTo(GraphTaskDto.TESTS_WRITTEN);
            assertThat(done.getChecksClaimed()).isEqualTo(2);
            assertThat(done.getTestsWritten())
                .as("how many tests - the first number on the badge").isEqualTo(3);
            assertThat(done.getChecksProved())
                .as("how many checks they prove - the second; a check proved by two tests "
                    + "counts once").isEqualTo(2);
            assertThat(done.getTestProblems()).isZero();
            assertThat(done.getTestFilesCsv())
                .isEqualTo("src/test/java/swarm/accept/BookTest.java");
            assertThat(done.getTests())
                .as("the names and the checks ride on the frame, so the panel costs no request")
                .hasSize(3);
            assertThat(done.getTests().get(0).getTestRef())
                .isEqualTo("swarm.accept.BookTest#storesAllBookFields");
            assertThat(done.getTests().get(0).getProvesRef()).isEqualTo("R1:C1");
            assertThat(done.getTests().get(0).getProvesText())
                .isEqualTo("A book keeps every field it was given");
            assertThat(done.getTests().get(2).getProvesRef())
                .as("a test nothing asked for is listed with nothing to prove").isEmpty();

            GraphTaskDto now = node(snapshot, writing);
            assertThat(now.getTestsPhase())
                .as("the author has this one right now").isEqualTo(GraphTaskDto.TESTS_WRITING);
            assertThat(now.getChecksClaimed()).isEqualTo(1);
            assertThat(now.getTestsWritten()).isZero();

            GraphTaskDto enabler = node(snapshot, claimsNone);
            assertThat(enabler.getTestsPhase())
                .as("authored, and it claimed nothing - which is not 'not yet'")
                .isEqualTo(GraphTaskDto.TESTS_WRITTEN);
            assertThat(enabler.getChecksClaimed()).isZero();
            assertThat(enabler.getTestsWritten()).isZero();

            GraphTaskDto later = node(snapshot, notYet);
            assertThat(later.getTestsPhase())
                .as("the stage has not reached it: nothing is known, nothing is said")
                .isEqualTo(GraphTaskDto.TESTS_UNKNOWN);
            assertThat(later.getChecksClaimed()).isZero();

            GraphTaskDto empty = node(snapshot, nothingCameOut);
            assertThat(empty.getTestsPhase()).isEqualTo(GraphTaskDto.TESTS_WRITTEN);
            assertThat(empty.getChecksClaimed())
                .as("handed a check and produced nothing - distinguishable from the enabler by "
                    + "the checks it was handed").isEqualTo(1);
            assertThat(empty.getTestsWritten()).isZero();
        }
    }

    /**
     * A task gaining tests is a change the publisher pushes.
     *
     * <p>The publisher sends a frame only when the fingerprint of the graph changes, and until now
     * the fingerprint of a task was its state alone. A task's state does not move during
     * TEST_AUTHORING, so a task gaining tests produced no new frame: the server knew, the browser
     * was never told. The same fault as the chips that vanished between finishing and verification.
     */
    @Test
    void aTaskGainingTestsChangesWhatThePublisherWatches() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seed(store, null, runId, RunState.TEST_AUTHORING, List.of(task(taskId, "Store a book", 2)));
            GraphServiceImpl graph = new GraphServiceImpl();

            String before = GraphServiceImpl.fingerprint(graph.snapshot(runId.toString()));
            store.append(() -> {
                taskIn(store, taskId).setAuthoredTests(AuthoredTests.started(2, Instant.now()));
                return null;
            }).get();
            String started = GraphServiceImpl.fingerprint(graph.snapshot(runId.toString()));
            store.append(() -> {
                taskIn(store, taskId).setAuthoredTests(writtenRecord());
                return null;
            }).get();
            String written = GraphServiceImpl.fingerprint(graph.snapshot(runId.toString()));

            assertThat(started)
                .as("the author being handed the task is a change worth a frame")
                .isNotEqualTo(before);
            assertThat(written)
                .as("and so are its tests landing - the task's state is PENDING throughout")
                .isNotEqualTo(started);
            assertThat(GraphServiceImpl.fingerprint(graph.snapshot(runId.toString())))
                .as("and nothing else changing means nothing else is sent")
                .isEqualTo(written);
        }
    }

    /**
     * The source behind a badge is read from the project's checkout, and only for a file the
     * task's own record names.
     */
    @Test
    void theSourceOfATestIsServedOnlyForAFileTheRecordNames() throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("src/test/java/swarm/accept"));
        Files.writeString(repo.resolve("src/test/java/swarm/accept/BookTest.java"), BOOK_TEST);
        Files.writeString(repo.resolve("secrets.txt"), "not for the graph");
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            UUID projectId = UUID.randomUUID();
            Project project = new Project(projectId, "bookshelf", repo.toString(), List.of(),
                Instant.now(), false);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(() -> List.of(project), () -> projectId, (n, p, c) -> null,
                    id -> { }));
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            seed(store, project, runId, RunState.TEST_AUTHORING,
                List.of(task(taskId, "Store a book", 2)));
            store.append(() -> {
                taskIn(store, taskId).setAuthoredTests(writtenRecord());
                return null;
            }).get();
            GraphServiceImpl graph = new GraphServiceImpl();

            String source = graph.testSource(runId.toString(), taskId.toString(),
                "src/test/java/swarm/accept/BookTest.java");
            assertThat(source).contains("class BookTest").contains("storesAllBookFields");

            assertThat(graph.testSource(runId.toString(), taskId.toString(), "secrets.txt"))
                .as("a path the record does not name is refused with a sentence, not served")
                .doesNotContain("not for the graph")
                .contains("does not name a test file");
            assertThat(graph.testSource(runId.toString(), UUID.randomUUID().toString(),
                    "src/test/java/swarm/accept/BookTest.java"))
                .contains("does not name a test file");
        }
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** Three tests in one file: two that prove the task's two checks, one nothing asked for. */
    private static AuthoredTests writtenRecord() {
        AuthoredTests record = AuthoredTests.started(2, Instant.now().minusSeconds(20));
        record.setWrittenAt(Instant.now());
        record.setFiles(new ArrayList<>(List.of("src/test/java/swarm/accept/BookTest.java")));
        record.setTests(new ArrayList<>(List.of(
            new AuthoredTest("swarm.accept.BookTest#storesAllBookFields",
                "src/test/java/swarm/accept/BookTest.java", "R1:C1",
                "A book keeps every field it was given"),
            new AuthoredTest("swarm.accept.BookTest#rejectsUnknownReadingStatus",
                "src/test/java/swarm/accept/BookTest.java", "R1:C2",
                "The reading status is limited to the three known ones"),
            new AuthoredTest("swarm.accept.BookTest#alsoChecksTheTitleIsKept",
                "src/test/java/swarm/accept/BookTest.java", "", ""))));
        return record;
    }

    private static Task task(UUID id, String title, int checks) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (int i = 0; i < checks; i++) {
            criteria.add(new AcceptanceCriterion(UUID.randomUUID(), "check " + (i + 1), ""));
        }
        return new Task(id, 1, title, "", Set.of("src/main/java"), Set.of(), criteria,
            "src/test/java/swarm", null, null, new SwarmPolicy(2, false, 0.1, 0.8, List.of()),
            TaskState.PENDING);
    }

    private static Task taskIn(ArtifactStore store, UUID taskId) {
        for (TaskGraph graph : store.root().taskGraphs.values()) {
            for (Task task : graph.tasks()) {
                if (taskId.equals(task.id())) {
                    return task;
                }
            }
        }
        throw new IllegalStateException("no task " + taskId);
    }

    private static GraphTaskDto node(RunGraphDto snapshot, UUID taskId) {
        return snapshot.getTasks().stream()
            .filter(t -> taskId.toString().equals(t.getTaskId()))
            .findFirst().orElseThrow();
    }

    private static void seed(ArtifactStore store, Project project, UUID runId, RunState state,
                             List<Task> tasks) throws Exception {
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, tasks, List.of());
        store.append(() -> {
            if (project != null) {
                store.root().projects.put(project.id(), project);
            }
            store.root().taskGraphs.put(graph.id(), graph);
            store.root().runs.put(runId, new Run(runId, WorkflowKind.GREENFIELD, state,
                project == null ? null : project.id(), null, null, graph.id(), null,
                Instant.now(), new RunReport(runId, "a bookshelf that stores books")));
            return null;
        }).get();
    }
}
