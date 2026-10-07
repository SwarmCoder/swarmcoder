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
import com.swarmcoder.verify.BrowserOnlyCode;
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
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance tests of several tasks are written at the same time (2026-10-02).
 *
 * <p>Harness run 66: the test-authoring stage took 15.3 minutes with one model call in flight
 * throughout, on a server that serves four. The author was handed one task and got the next only
 * when that one's tests were back - and since it works in steps with lookup tools now, one task
 * takes minutes. The calls are independent: each writes its own task's files.
 *
 * <p>No model is involved. The author here is a fake that holds every call until three are in
 * together - which, one at a time, never happens - then writes a real file and answers the way
 * the real one does. What comes after the writing is unchanged and still happens one task at a
 * time in plan order, so each task ends up claiming exactly its own file.
 */
@ModelCodeOnThisPc
class TheTestsOfSeveralTasksAreWrittenAtTheSameTimeTest {

    @TempDir
    Path dir;

    private static String testClass(String name, String method) {
        return """
            package swarm.accept;

            import org.junit.jupiter.api.Test;

            class %s {

                @Test
                void %s() {
                }
            }
            """.formatted(name, method);
    }

    @Test
    void threeTasksAreWithTheAuthorTogetherAndEachClaimsItsOwnFile() throws Exception {
        Path repo = dir.resolve("repo");
        assertThat(GitService.ensureRepo(repo)).isTrue();

        Task book = task("Store a book", "BookTest", "storesABook");
        Task count = task("Count the books", "CountTest", "countsTheBooks");
        Task shelf = task("Name the shelf", "ShelfTest", "namesTheShelf");
        Task enabler = new Task(UUID.randomUUID(), 1, "Set up the shared module", "",
            Set.of("src/main/java"), Set.of(), new ArrayList<>(), "src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.1, 0.8, List.of()), TaskState.PENDING);
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(book, enabler, count, shelf), List.of());
        UUID runId = UUID.randomUUID();

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch threeIn = new CountDownLatch(3);
        List<String> handedOver = Collections.synchronizedList(new ArrayList<>());

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
                    if (forCriteria == null || forCriteria.isEmpty()) {
                        return Authored.NOTHING; // the real author's own guard
                    }
                    handedOver.add(task.title());
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    threeIn.countDown();
                    try {
                        threeIn.await(5, TimeUnit.SECONDS);
                        String className = forCriteria.get(0).testClassOrFile()
                            .replace("swarm.accept.", "").replaceAll("#.*", "");
                        String method = forCriteria.get(0).testClassOrFile().replaceAll(".*#", "");
                        String file = "src/test/java/swarm/accept/" + className + ".java";
                        Path target = repoRoot.resolve(file);
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, testClass(className, method));
                        return Authored.of(List.of(file), List.of());
                    } catch (Exception e) {
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

            try {
                workflow.advance(run);
            } catch (RuntimeException afterTheStage) {
                // What follows the writing is not what is measured here.
            }

            assertThat(handedOver).containsExactlyInAnyOrder("Store a book", "Count the books",
                "Name the shelf");
            assertThat(peak.get())
                .as("author calls in flight together: three tasks have checks. One means the "
                    + "author was handed them one at a time")
                .isEqualTo(3);
            assertThat(store.getTask(book.id()).authoredTestPaths())
                .containsExactly("src/test/java/swarm/accept/BookTest.java");
            assertThat(store.getTask(count.id()).authoredTestPaths())
                .containsExactly("src/test/java/swarm/accept/CountTest.java");
            assertThat(store.getTask(shelf.id()).authoredTestPaths())
                .containsExactly("src/test/java/swarm/accept/ShelfTest.java");
            assertThat(store.getTask(enabler.id()).authoredTestPaths()).isEmpty();
            assertThat(store.getTask(shelf.id()).authoredTests().written())
                .as("and each task's record says its tests were written").isTrue();
        }
    }

    private static Task task(String title, String testClass, String method) {
        return new Task(UUID.randomUUID(), 1, title, "", Set.of("src/main/java"), Set.of(),
            new ArrayList<>(List.of(new AcceptanceCriterion(UUID.randomUUID(), title,
                "swarm.accept." + testClass + "#" + method))),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.1, 0.8, List.of()), TaskState.PENDING);
    }
}
