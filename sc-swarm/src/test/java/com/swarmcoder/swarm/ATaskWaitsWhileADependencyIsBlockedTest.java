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
package com.swarmcoder.swarm;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A task is not dispatched while a task it depends on has no winner.
 *
 * <p>Harness run 39, 2026-09-25 (run {@code d51ee25e}). Task B, "Create shared BooksService
 * interface", ended BLOCKED after its swarm and its repair round and raised its question. Task A
 * was merged at the same moment. The engine then dispatched task C, "Implement server-side
 * BooksService with EclipseStore", which depends on both A and B — workers started implementing
 * an interface that did not exist. See {@link WaitingOnDependencies}.
 *
 * <p>The shape here is run 39's, on the real swarm stack with scripted workers: A delivers a file
 * and wins; B's acceptance test needs a file no worker ever writes, so B is BLOCKED after its
 * repair round; C depends on A and B; and one more task, E, depends on A alone — its work is not
 * in question, so it must still run.
 */
@ModelCodeOnThisPc
class ATaskWaitsWhileADependencyIsBlockedTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String SERVICE_TEST = ACCEPT_DIR + "/BooksServiceTest.java";
    private static final String BOOK = "src/main/Book.java";
    private static final String SERVICE = "src/main/BooksService.java";
    private static final String IMPL = "src/main/BooksServiceImpl.java";
    private static final String LIST = "src/main/BookList.java";
    /** What B's test needs and nobody ever writes: B can never pass. */
    private static final String NOWHERE = "src/main/Nowhere.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    // --- the pure rule -------------------------------------------------------------------------

    @Test
    void run39sImplementationTaskWaitsForTheBlockedInterfaceTask() {
        Task a = task("Create shared data model classes (Book and Rating)", "", Set.of(BOOK),
            List.of(), List.of());
        Task b = task("Create shared BooksService interface", "", Set.of(SERVICE), List.of(),
            List.of());
        Task c = task("Implement server-side BooksService with EclipseStore", "", Set.of(IMPL),
            List.of(), List.of());
        Task d = task("Implement server-side BooksServiceFactory", "", Set.of("src/main/F.java"),
            List.of(), List.of());
        Task e = task("Book list screen", "", Set.of(LIST), List.of(), List.of());
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(a, b, c, d, e),
            List.of(new TaskEdge(a.id(), c.id()), new TaskEdge(b.id(), c.id()),
                new TaskEdge(c.id(), d.id()), new TaskEdge(a.id(), e.id())));
        WaitingOnDependencies gate = new WaitingOnDependencies(graph);

        assertThat(gate.dispatchable(List.of(a, b))).containsExactly(a, b);
        gate.finishedWithoutWinner(b);
        assertThat(gate.dispatchable(List.of(c, e)))
            .as("C needs B, which has no winner; E needs only A, which has one")
            .containsExactly(e);
        assertThat(gate.dispatchable(List.of(d)))
            .as("and whatever waits on a waiting task waits too")
            .isEmpty();

        assertThat(gate.anyWaiting()).isTrue();
        assertThat(gate.waitingTitles()).containsExactly(
            "Implement server-side BooksService with EclipseStore",
            "Implement server-side BooksServiceFactory");
        assertThat(gate.parkBrief())
            .contains("stopped behind task 'Create shared BooksService interface'")
            .contains("already raised")
            .contains("'Implement server-side BooksService with EclipseStore', "
                + "'Implement server-side BooksServiceFactory'");
    }

    @Test
    void nothingWaitsWhenEveryTaskHasAWinner() {
        Task a = task("A", "", Set.of(BOOK), List.of(), List.of());
        Task c = task("C", "", Set.of(IMPL), List.of(), List.of());
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(a, c),
            List.of(new TaskEdge(a.id(), c.id())));
        WaitingOnDependencies gate = new WaitingOnDependencies(graph);

        assertThat(gate.dispatchable(List.of(a))).containsExactly(a);
        assertThat(gate.dispatchable(List.of(c))).containsExactly(c);
        assertThat(gate.anyWaiting()).isFalse();
    }

    // --- on the real engine --------------------------------------------------------------------

    @Test
    void theEngineDoesNotDispatchATaskWhoseDependencyIsBlockedAndParksBehindItsQuestion()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task model = task("Create shared data model classes (Book and Rating)",
            "Create " + BOOK + ". Nothing else.", Set.of(BOOK), List.of(), List.of());
        Task service = task("Create shared BooksService interface",
            "Create " + SERVICE + ".", Set.of(SERVICE),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the service exists",
                "swarm.accept.BooksServiceTest#proves")),
            List.of(SERVICE_TEST));
        Task impl = task("Implement server-side BooksService with EclipseStore",
            "Create " + IMPL + ".", Set.of(IMPL), List.of(), List.of());
        Task list = task("Book list screen", "Create " + LIST + ".", Set.of(LIST), List.of(),
            List.of());

        List<CandidateSolution> archived;
        List<Decision> decisions;
        RunMustPark park;
        try (FakeVllm fake = new FakeVllm(ATaskWaitsWhileADependencyIsBlockedTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(model, service, impl, list),
                List.of(new TaskEdge(model.id(), impl.id()),
                    new TaskEdge(service.id(), impl.id()),
                    new TaskEdge(model.id(), list.id())));
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, run);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            List<String> gated = new ArrayList<>();
            engine.setWaveGate((asked, wave, waveBase) -> {
                gated.add(wave.stream().map(Task::title).toList().toString());
                return null;
            });

            park = catchThrowableOfType(() -> engine.executeRun(run), RunMustPark.class);

            assertThat(gated)
                .as("the second wave was asked about with only the task whose work is not in "
                    + "question")
                .containsExactly("[Book list screen]");
            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
            decisions = new ArrayList<>(store.root().decisions.values());
        }

        assertThat(service.state()).isEqualTo(TaskState.BLOCKED);
        assertThat(archived.stream().filter(c -> c.taskId().equals(impl.id())).toList())
            .as("not one worker was spent on the implementation of an interface that does not "
                + "exist")
            .isEmpty();
        assertThat(impl.state()).as("it is left exactly as it was").isEqualTo(TaskState.READY);
        assertThat(archived.stream().filter(c -> c.taskId().equals(list.id())).toList())
            .as("a task that needs only the finished work still runs")
            .isNotEmpty();

        assertThat(park).as("the run stops behind the blocked task").isNotNull();
        assertThat(park.questionAlreadyRaised()).isTrue();
        assertThat(park.brief())
            .contains("'Create shared BooksService interface'")
            .contains("'Implement server-side BooksService with EclipseStore'");
        assertThat(decisions.stream()
                .filter(d -> d.state() == DecisionState.PENDING
                    && d.kind() == DecisionKind.BLOCKED_TASK).toList())
            .as("one question, the blocked task's own — not a second one about the same fault")
            .hasSize(1)
            .allSatisfy(d -> assertThat(d.briefMarkdown())
                .contains("Create shared BooksService interface"));
    }

    // --- the repository --------------------------------------------------------------------------

    private void initRepo(UUID runId) throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main"));
        Files.writeString(repoDir.resolve("src/main/.keep"), "");
        Files.createDirectories(repoDir.resolve("tools"));
        Files.writeString(repoDir.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java"
            timeoutSeconds: 60
            """.formatted(java));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();

        git("checkout -q -b swarm/tests/" + runId);
        write(SERVICE_TEST, "package swarm.accept;\n// needs: " + SERVICE + "\n// needs: " + NOWHERE
            + "\nclass BooksServiceTest { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /** The stand-in acceptance stage {@code ALaterWaveBuildsOnEarlierWinnersTest} uses. */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                Path dir = Path.of("src/test/java/swarm/accept");
                List<Path> files = new ArrayList<>();
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) {
                        s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                    }
                }
                if (files.isEmpty()) {
                    System.out.println("no acceptance test present; nothing run");
                    return;
                }
                StringBuilder xml = new StringBuilder();
                int failures = 0;
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    boolean ok = true;
                    for (String line : Files.readString(file).split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            ok &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"proves\\">");
                    if (!ok) {
                        failures++;
                        // A different message per candidate: identical failures from every candidate would
                        // (rightly) send the test back to its author, not into the repair round.
                        String tag = Long.toString(System.nanoTime(), 36);
                        tag = tag.substring(tag.length() - 4);
                        xml.append("<failure message=\\"missing ").append(tag)
                           .append("\\">the file it needs is missing</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " -> " + (ok ? "pass" : "FAIL"));
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures > 0 ? 1 : 0);
            }
        }
        """;

    /** Each worker writes its own task's one file, then reports done. */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        String path;
        if (conversation.contains(IMPL)) {
            path = IMPL;
        } else if (conversation.contains(SERVICE)) {
            path = SERVICE;
        } else if (conversation.contains(LIST)) {
            path = LIST;
        } else {
            path = BOOK;
        }
        if (conversation.contains("wrote " + path)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", path).put("content", "class X { }\n").toString());
    }

    private static Task task(String title, String instructions, Set<String> writeSet,
                             List<AcceptanceCriterion> criteria, List<String> authoredTestPaths) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            criteria, "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(authoredTestPaths);
        return task;
    }

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
