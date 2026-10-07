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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The test-authoring stage writes the tests of several tasks side by side, and writes what it did
 * onto each task as it goes, not when the stage ends.
 *
 * <p>The stage used to author tasks one at a time, and this test asserted that order: the author
 * was handed the next task only when the previous one was recorded. Since the author works in
 * steps with lookup tools, one task takes minutes, so the calls - independent, each writing its
 * own task's files - now run side by side, as many at once as the author's server serves. What is
 * decided FROM what was written still happens task by task, in plan order.
 *
 * <p>What this pins: the author really is inside several tasks' calls at the same moment; each
 * task already says it is being written when the author is handed it (the run graph draws the task
 * boxes from the store, and a stage that says nothing for minutes looks hung); and when the stage
 * is done every task's record says what was written for it, and the records survive a restart of
 * the store.
 *
 * <p>No model is involved. The test author is a fake that writes a real file and answers the way
 * the real one does. Two of its calls wait for each other, so a stage that authored one task at a
 * time could not get past them; the swarm engine stops the run the moment the stage hands over to
 * it.
 */
@ModelCodeOnThisPc
class TestAuthoringIsRecordedPerTaskTest {

    @TempDir
    Path dir;

    private static final String BOOK_TEST = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        class BookTest {

            @Test
            void storesAllBookFields() {
            }

            @Test
            void rejectsUnknownReadingStatus() {
            }
        }
        """;

    private static final String COUNT_TEST = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        class CountTest {

            @Test
            void countsTheBooksOnTheShelf() {
            }
        }
        """;

    /** What the store said about a task at the moment the author was handed it. */
    private final Map<String, String> ownStateWhenHanded = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    /** Two calls must be in the author at the same moment for either to go on. */
    private final CountDownLatch twoAtOnce = new CountDownLatch(2);

    @Test
    void tasksAreAuthoredSideBySideAndEachTaskIsRecordedAsItGoes() throws Exception {
        Path repo = dir.resolve("repo");
        assertThat(GitService.ensureRepo(repo)).isTrue();

        UUID bookId = UUID.randomUUID();
        UUID enablerId = UUID.randomUUID();
        UUID countId = UUID.randomUUID();
        Task book = task(bookId, "Store a book with all its fields", List.of(
            new AcceptanceCriterion(UUID.randomUUID(), "A book keeps every field it was given",
                "swarm.accept.BookTest#storesAllBookFields"),
            new AcceptanceCriterion(UUID.randomUUID(), "The reading status is limited to the "
                + "three known ones", "swarm.accept.BookTest#rejectsUnknownReadingStatus")));
        Task enabler = task(enablerId, "Set up the shared module", List.of());
        Task count = task(countId, "Count the books on the shelf", List.of(
            new AcceptanceCriterion(UUID.randomUUID(), "The shelf says how many books it holds",
                "swarm.accept.CountTest#countsTheBooksOnTheShelf")));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(book, enabler, count), List.of());
        UUID runId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                return null;
            }).get();
            store.indexTasks(graph.tasks());
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.TEST_AUTHORING, null, null,
                null, graph.id(), null, Instant.now(), new RunReport(runId, "a bookshelf"));
            RunPersister persister = new RunPersister(store);
            persister.save(run);

            TestAuthorClient author = new TestAuthorClient(null, null) {
                @Override
                public Authored authorTests(Path repoRoot, Task task, DesignDocument design,
                                            List<AcceptanceCriterion> forCriteria,
                                            String constraintBrief, boolean webProject,
                                            String acceptanceModule, List<String> moduleArtifactIds,
                                            BrowserOnlyCode.Survey survey, List<Task> planTasks) {
                    ownStateWhenHanded.put(task.title(), stateOf(store.getTask(task.id())));
                    int now = inFlight.incrementAndGet();
                    mostInFlight.accumulateAndGet(now, Math::max);
                    try {
                        // Waits for a second call to arrive; a stage that handed the author one
                        // task at a time would sit here until the timeout.
                        twoAtOnce.countDown();
                        twoAtOnce.await(10, TimeUnit.SECONDS);
                        if (forCriteria == null || forCriteria.isEmpty()) {
                            return Authored.NOTHING; // the real author's own guard
                        }
                        String file = task.id().equals(bookId)
                            ? "src/test/java/swarm/accept/BookTest.java"
                            : "src/test/java/swarm/accept/CountTest.java";
                        Path target = repoRoot.resolve(file);
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, task.id().equals(bookId) ? BOOK_TEST : COUNT_TEST);
                        return Authored.of(List.of(file), List.of());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            };
            SwarmEngine stopHere = r -> {
                throw new IllegalStateException("the stage handed over to the swarm");
            };
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(null, stopHere, null, store,
                persister, new CloudGate(1_000_000, null), repo,
                new CloudRoles(null, null, author), new GitService(repo));

            assertThatThrownBy(() -> workflow.advance(run))
                .as("TEST_AUTHORING finished and the run moved on - the swarm is where it stops")
                .hasMessage("the stage handed over to the swarm");

            // --- side by side -------------------------------------------------------------------
            assertThat(mostInFlight.get())
                .as("the author was inside more than one task's call at the same moment")
                .isGreaterThanOrEqualTo(2);
            assertThat(ownStateWhenHanded.keySet()).containsExactlyInAnyOrder(
                "Store a book with all its fields", "Set up the shared module",
                "Count the books on the shelf");
            assertThat(ownStateWhenHanded.values())
                .as("each task already said it was being written when the author was handed it")
                .containsOnly("writing");

            // --- what the records hold once the stage is done ----------------------------------
            AuthoredTests bookRecord = store.getTask(bookId).authoredTests();
            assertThat(bookRecord.written()).isTrue();
            assertThat(bookRecord.checksOffered()).isEqualTo(2);
            assertThat(bookRecord.files()).containsExactly("src/test/java/swarm/accept/BookTest.java");
            assertThat(bookRecord.tests()).extracting(t -> t.testRef()).containsExactly(
                "swarm.accept.BookTest#storesAllBookFields",
                "swarm.accept.BookTest#rejectsUnknownReadingStatus");
            assertThat(bookRecord.tests().get(1).provesRef())
                .as("a task that owns its checks has no R7:C1 handle, and says so")
                .isEqualTo("(a check this task owns)");
            assertThat(bookRecord.tests().get(1).provesText())
                .isEqualTo("The reading status is limited to the three known ones");
            assertThat(bookRecord.checksProved()).isEqualTo(2);
            assertThat(bookRecord.problems()).isEmpty();
            assertThat(bookRecord.startedAt()).isNotNull();
            assertThat(bookRecord.writtenAt()).isAfterOrEqualTo(bookRecord.startedAt());

            AuthoredTests enablerRecord = store.getTask(enablerId).authoredTests();
            assertThat(enablerRecord.written()).isTrue();
            assertThat(enablerRecord.checksOffered()).isZero();
            assertThat(enablerRecord.tests()).isEmpty();

            assertThat(store.getTask(countId).authoredTests().checksProved()).isEqualTo(1);
        }

        // --- and it is durable: a store opened again still has every record ---------------------
        try (ArtifactStore reopened = new ArtifactStore(dir.resolve("store"))) {
            AuthoredTests bookRecord = reopened.getTask(bookId).authoredTests();
            assertThat(bookRecord)
                .as("the record and the tests inside it survive a restart of the store")
                .isNotNull();
            assertThat(bookRecord.tests()).hasSize(2);
            assertThat(bookRecord.tests().get(0).provesText())
                .isEqualTo("A book keeps every field it was given");
        }
    }

    /** One word per state the graph tells apart, plus the numbers a badge would show. */
    private static String stateOf(Task task) {
        AuthoredTests record = task == null ? null : task.authoredTests();
        if (record == null) {
            return "unknown";
        }
        if (record.inProgress()) {
            return "writing";
        }
        if (record.checksOffered() == 0) {
            return "written " + record.tests().size() + " tests, claims none";
        }
        return "written " + record.tests().size() + " tests, " + record.checksProved() + " checks";
    }

    private static Task task(UUID id, String title, List<AcceptanceCriterion> criteria) {
        return new Task(id, 1, title, "", Set.of("src/main/java"), Set.of(),
            new ArrayList<>(criteria), "src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.1, 0.8, List.of()), TaskState.PENDING);
    }
}
