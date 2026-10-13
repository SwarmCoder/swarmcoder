/*
 * Copyright 2026 The SwarmCoder Authors
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChecksAlreadyProved;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
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
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When no worker of a task changes anything, the product runs the task's acceptance test on the
 * unchanged tree itself; and a task measured as needing no change is not dispatched
 * (DEVELOPER_CORRECTIONS section 89).
 *
 * <p>Live supervised build, 2026-10-13, with other names. A story's one task was built on code
 * earlier stories had delivered. Every worker ended without a change and reported that the
 * acceptance test passed; none had run it. Nothing measured the unchanged tree, the task was
 * BLOCKED with a question that gave no reason, and the story ended with its check unproved.
 *
 * <p>The shape: one task whose test needs one file. In the first test the start tree holds that
 * file and the workers change nothing; in the second it does not, and they still change nothing.
 */
@ModelCodeOnThisPc
class ATaskNoWorkerChangedIsMeasuredByTheProductTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String STORE_TEST = ACCEPT_DIR + "/BookStoreTest.java";
    private static final String SERVICE_TEST = ACCEPT_DIR + "/BooksServiceTest.java";
    private static final String STORE = "src/main/book-store.txt";
    private static final String SERVICE = "src/main/books-service.txt";
    private static final String MISSING = "src/main/NotThere.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;

    @Test
    void aGreenRunOnTheUnchangedTreeMeansTheTaskNeedsNoChange() throws Exception {
        UUID runId = UUID.randomUUID();
        String tests = initRepo(runId, "// needs: " + STORE + "\n", null);
        Task keeps = task("Keep books across restarts", STORE, STORE_TEST, "BookStoreTest");

        try (FakeVllm fake = new FakeVllm(conversation -> nothingToChange());
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, tests, List.of(keeps), List.of());
            engine(fake, store).executeRun(run);

            assertThat(keeps.needsNoChange()).as("measured by the product, not claimed").isTrue();
            ChecksAlreadyProved record = keeps.checksAlreadyProved();
            assertThat(record.tests()).containsExactly("swarm.accept.BookStoreTest#proves");
            assertThat(record.passed()).isEqualTo(1);
            assertThat(record.measuredWhen()).contains("left the tree unchanged");
            assertThat(record.waveBase()).as("the tree it was measured on").isNotBlank();
            assertThat(record.describe(keeps.title()))
                .startsWith("Task 'Keep books across restarts' needs no change: "
                    + "swarm.accept.BookStoreTest#proves passed on ")
                .contains("The product ran them itself");
            assertThat(keeps.state()).as("done without a chosen candidate, not BLOCKED")
                .isEqualTo(TaskState.DONE);
            assertThat(openQuestions(store)).as("nothing is asked").isEmpty();
            List<CandidateSolution> candidates = candidatesOf(store, keeps);
            assertThat(candidates).as("the first round only: no repair worker").hasSize(2);
            assertThat(candidates).as("nothing is chosen: there is nothing to merge")
                .noneMatch(c -> c.state() == CandidateState.SELECTED);
            assertThat(candidates.stream().filter(c -> c.verification() != null))
                .as("one unchanged tree was measured, once").hasSize(1);
            assertThat(keeps.repairOfferedBy()).isNull();
            assertThat(keeps.retestOfferedBy()).isNull();
        }
    }

    @Test
    void aRedRunOnTheUnchangedTreeIsSaidInTheQuestionWhichTakesRepairAndRetest()
            throws Exception {
        UUID runId = UUID.randomUUID();
        String tests = initRepo(runId, "// needs: " + MISSING + "\n", null);
        Task keeps = task("Keep books across restarts", STORE, STORE_TEST, "BookStoreTest");

        try (FakeVllm fake = new FakeVllm(conversation -> nothingToChange());
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, tests, List.of(keeps), List.of());
            try {
                engine(fake, store).executeRun(run);
            } catch (RunMustPark stoppedBehindTheBlockedTask) {
                // either way the task is BLOCKED and has asked
            }

            assertThat(keeps.needsNoChange()).isFalse();
            assertThat(keeps.state()).isEqualTo(TaskState.BLOCKED);
            List<Decision> asked = openQuestions(store);
            assertThat(asked).hasSize(1);
            assertThat(asked.get(0).briefMarkdown())
                .startsWith("Task BLOCKED after swarm + repair round: 'Keep books across "
                    + "restarts'\n\nNo worker of the first round changed anything. The product "
                    + "then ran the task's acceptance test(s) on that unchanged tree itself: "
                    + "0p/1f/0e (passed/failed/errored).")
                .contains("Why: acceptance test swarm.accept.BookStoreTest#proves fails - "
                    + "missing")
                .contains("compiles=true acceptance 0p/1f/0e");
            assertThat(keeps.repairOfferedBy()).as("the question takes repair")
                .isEqualTo(asked.get(0).id());
            assertThat(keeps.retestOfferedBy()).as("and retest: the test may be what is wrong")
                .isEqualTo(asked.get(0).id());
        }
    }

    /** A task already measured as needing no change: no worker, and what builds on it is built. */
    @Test
    void aTaskThatNeedsNoChangeIsNotDispatchedAndDoesNotHoldBackWhatBuildsOnIt()
            throws Exception {
        UUID runId = UUID.randomUUID();
        String tests = initRepo(runId, "// needs: " + STORE + "\n", "// needs: " + SERVICE + "\n");
        Task keeps = task("Keep books across restarts", STORE, STORE_TEST, "BookStoreTest");
        keeps.setChecksAlreadyProved(ChecksAlreadyProved.noChangeNeeded(Instant.now(), base,
            List.of("swarm.accept.BookStoreTest#proves"), List.of(), 1,
            "at the red check, before any worker was started"));
        Task serves = task("Create the books service", SERVICE, SERVICE_TEST, "BooksServiceTest");

        try (FakeVllm fake = new FakeVllm(ATaskNoWorkerChangedIsMeasuredByTheProductTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, tests, List.of(keeps, serves),
                List.of(new TaskEdge(keeps.id(), serves.id())));
            engine(fake, store).executeRun(run);

            assertThat(candidatesOf(store, keeps)).as("no worker was started for it").isEmpty();
            assertThat(keeps.state()).isEqualTo(TaskState.DONE);
            assertThat(serves.state()).as("the task that builds on it was built")
                .isEqualTo(TaskState.SELECTED);
            assertThat(openQuestions(store)).isEmpty();
        }
    }

    // --- the reading, on its own -------------------------------------------------------------

    @Test
    void onlyARoundInWhichNobodyChangedAnythingIsMeasuredAndOnlyAMeasuredPassIsAccepted() {
        Task task = task("Keep books across restarts", STORE, STORE_TEST, "BookStoreTest");
        CandidateSolution unchanged = candidate(task, 0, "", CandidateState.FAILED, null);
        CandidateSolution stopped = candidate(task, 1, "", CandidateState.KILLED,
            KillReason.WRITESET_VIOLATION);
        CandidateSolution changed = candidate(task, 2, "diff --git a/x b/x\n",
            CandidateState.FAILED, null);

        assertThat(UnchangedTree.toMeasure(List.of(stopped, unchanged)))
            .as("the worker that ended by itself without a change").isSameAs(unchanged);
        assertThat(UnchangedTree.toMeasure(List.of(unchanged, changed)))
            .as("somebody changed something: the round is judged as before").isNull();
        assertThat(UnchangedTree.toMeasure(List.of(stopped)))
            .as("a worker stopped before it could decide has said nothing").isNull();

        VerificationReport green = report(new TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.BookStoreTest#proves"), List.of(), false)
            .withStageOutcome(TestStageOutcome.EXECUTED));
        assertThat(UnchangedTree.refusal(task, green)).isNull();

        VerificationReport otherClass = report(new TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.SomethingElseTest#proves"), List.of(), false)
            .withStageOutcome(TestStageOutcome.EXECUTED));
        assertThat(UnchangedTree.refusal(task, otherClass))
            .contains("nothing shows it was executed");

        VerificationReport red = report(new TestResults(0, 1, 0, 0, List.of(new TestFailure(
            "swarm.accept.BookStoreTest#proves", "expected: <1> but was: <0>", "")))
            .withStageOutcome(TestStageOutcome.EXECUTED));
        assertThat(UnchangedTree.refusal(task, red)).contains("clean pass");

        Task withJourney = task("Show the shelf", STORE, STORE_TEST, "BookStoreTest");
        withJourney.setJourneyPaths(List.of(ACCEPT_DIR + "/shelf.journey.yaml"));
        assertThat(UnchangedTree.refusal(withJourney, green))
            .as("a journey is carried out in a browser; no test run shows it")
            .contains("journey");

        Task noTest = task("Tidy up", STORE, STORE_TEST, "BookStoreTest");
        noTest.setAuthoredTestPaths(List.of());
        assertThat(UnchangedTree.refusal(noTest, green)).contains("claims no acceptance test");
    }

    // --- the fixture ---------------------------------------------------------------------------

    private static CandidateSolution candidate(Task task, int worker, String diff,
                                               CandidateState state, KillReason why) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            diff, null, null, null, state, why);
    }

    private static VerificationReport report(TestResults acceptance) {
        return new VerificationReport(UUID.randomUUID(), true, true, acceptance, null, null,
            null, Duration.ZERO, "", null);
    }

    private static List<CandidateSolution> candidatesOf(ArtifactStore store, Task task) {
        List<CandidateSolution> found = new ArrayList<>();
        for (var lazy : store.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && task.id().equals(candidate.taskId())) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static List<Decision> openQuestions(ArtifactStore store) {
        return store.root().decisions.values().stream()
            .filter(d -> d.state() == DecisionState.PENDING
                && d.kind() == DecisionKind.BLOCKED_TASK).toList();
    }

    /** A worker that finds nothing to change and says so. */
    private static FakeVllm.Reply nothingToChange() {
        return FakeVllm.Reply.toolCall("report_done",
            "{\"summary\": \"the existing code already does this; the test passes\"}");
    }

    /** Every worker writes the service and nothing else. */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("wrote " + SERVICE)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", SERVICE).put("content", "class X { }\n").toString());
    }

    private Run seed(ArtifactStore store, UUID runId, String tests, List<Task> tasks,
                     List<TaskEdge> edges) throws Exception {
        UUID graphId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, graphId, null, Instant.now(), null);
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(tests);
        store.append(() -> {
            store.root().taskGraphs.put(graphId, new TaskGraph(graphId, 1, null, tasks, edges));
            store.root().runs.put(runId, run);
            return null;
        }).get();
        return run;
    }

    private SwarmEngineImpl engine(FakeVllm fake, ArtifactStore store) {
        SwarmEngineImpl engine = new SwarmEngineImpl(
            new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
            new ModelProfileRegistry(List.of(new ModelProfile("fake",
                new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                ModelProfile.Kind.WORKER, 0, 0))),
            new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
        engine.setWaveGate((asked, wave, waveBase) -> null);
        return engine;
    }

    private static Task task(String title, String writes, String testPath, String testClass) {
        Task task = new Task(UUID.randomUUID(), 1, title, "Create " + writes + ".",
            Set.of(writes), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), title,
                "swarm.accept." + testClass + "#proves")), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(List.of(testPath));
        return task;
    }

    // --- the repository ------------------------------------------------------------------------

    /**
     * A start tree that holds {@link #STORE}, and the run's tests branch with the store's test
     * and, when {@code serviceMarkers} is given, the service's.
     *
     * @return the tests commit
     */
    private String initRepo(UUID runId, String storeMarkers, String serviceMarkers)
            throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main"));
        Files.writeString(repoDir.resolve(STORE), "class BookStore { }\n");
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
        Files.createDirectories(repoDir.resolve(ACCEPT_DIR));
        Files.writeString(repoDir.resolve(STORE_TEST), "package swarm.accept;\n" + storeMarkers
            + "class BookStoreTest { void proves() {} }\n");
        if (serviceMarkers != null) {
            Files.writeString(repoDir.resolve(SERVICE_TEST), "package swarm.accept;\n"
                + serviceMarkers + "class BooksServiceTest { void proves() {} }\n");
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        String sha = git("rev-parse HEAD").strip();
        git("checkout -q master");
        return sha;
    }

    /** The stand-in acceptance stage: a test passes when every path in its {@code // needs:} is there. */
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
                        xml.append("<failure message=\\"missing\\">the file it needs is missing</failure>");
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
