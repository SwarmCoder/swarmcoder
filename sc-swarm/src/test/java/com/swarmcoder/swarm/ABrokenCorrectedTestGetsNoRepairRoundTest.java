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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A corrected acceptance test that errors for every candidate before any candidate's code runs
 * is a broken test: no repair round is started for it, the question of a blocked task counts
 * the errored tests and says what errored, and a test corrected on an instruction is verified
 * against the candidates the task already has (DEVELOPER_CORRECTIONS section 86).
 *
 * <p>Live supervised build, 2026-10-12, with other names. A test went back to its author, who
 * corrected it and changed how it starts. Every test then errored in its own set-up. The four
 * candidates were verified again ("compiles=true acceptance 0p/0f"), four repair workers who
 * may not change the test were started and killed, and the question that followed gave no
 * reason.
 *
 * <p>The shape of the engine test: one task whose test needs two files; its workers write one,
 * so the task is BLOCKED after its swarm and repair round and has candidates with branches.
 * The test on the run's tests branch is then replaced three times, as a correction would: by
 * one that errors in its own set-up, by one that still needs the second file, and by one that
 * needs only what the candidates wrote.
 */
@ModelCodeOnThisPc
class ABrokenCorrectedTestGetsNoRepairRoundTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String SERVICE_TEST = ACCEPT_DIR + "/BooksServiceTest.java";
    private static final String SERVICE = "src/main/BooksService.java";
    private static final String SCREEN = "src/main/books-screen.html";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void aCorrectionThatErrorsForEveryCandidateStartsNoRepairRoundAndARetestUsesTheCandidates()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task service = task("Create the books service", Set.of(SERVICE, SCREEN));
        service.setAuthoredTestPaths(List.of(SERVICE_TEST));

        try (FakeVllm fake = new FakeVllm(ABrokenCorrectedTestGetsNoRepairRoundTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
            store.append(() -> {
                store.root().taskGraphs.put(graphId,
                    new TaskGraph(graphId, 1, null, List.of(service), List.of()));
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
            engine.setWaveGate((asked, wave, waveBase) -> null);
            try {
                engine.executeRun(run);
            } catch (RunMustPark stoppedBehindTheBlockedTask) {
                // either way the task is BLOCKED and has asked
            }
            assertThat(service.state()).isEqualTo(TaskState.BLOCKED);
            int candidates = candidatesOf(store, service);
            int requests = fake.requests.size();
            long questions = openQuestions(store);

            // 1. A correction that errors in its own set-up, as the suspect test's review took it.
            run.setAcceptanceTestsCommit(commitTest(runId, "// errors in its own set-up\n"));
            SwarmEngineImpl.TestRepairOutcome broken =
                engine.resumeAfterTestReview(service, runId, true, "The author corrected it.");

            assertThat(broken.stillFaulty()).as("a broken test, not a red one").isTrue();
            assertThat(broken.winner()).isNull();
            assertThat(broken.faultMessage()).contains("none of their code ran")
                .contains("java.lang.IllegalStateException").contains("none passed and none failed");
            assertThat(broken.brokenTestFailure()).as("the error itself, for the test's author")
                .contains("swarm.accept.BooksServiceTest#proves")
                .contains("at org.framework.Container.start(Container.java:88)");
            assertThat(candidatesOf(store, service)).as("no repair worker was started")
                .isEqualTo(candidates);
            assertThat(fake.requests.size()).as("no model was called").isEqualTo(requests);
            assertThat(openQuestions(store)).isEqualTo(questions);
            assertThat(engine.testEvidence(service)).as("what the test's author is shown")
                .startsWith("Why: acceptance test swarm.accept.BooksServiceTest#proves errored "
                    + "before it could fail or pass - java.lang.IllegalStateException: container "
                    + "not started")
                .contains("the test, not the candidates, is the likely cause")
                .contains("acceptance 0p/0f/1e").contains("The first failure in full:");

            // 2. A test corrected on an instruction that no candidate passes yet: the task is
            //    left for the stage to build again. It is not BLOCKED a second time.
            run.setAcceptanceTestsCommit(commitTest(runId,
                "// needs: " + SERVICE + "\n// needs: " + SCREEN + "\n// corrected\n"));
            SwarmEngineImpl.TestRepairOutcome none = engine.resumeAfterRetest(service, runId);

            assertThat(none.stillFaulty()).isFalse();
            assertThat(none.winner()).isNull();
            assertThat(openQuestions(store)).as("no second question").isEqualTo(questions);
            assertThat(candidatesOf(store, service)).isEqualTo(candidates);

            // 3. A test corrected on an instruction that the candidates' code passes.
            run.setAcceptanceTestsCommit(commitTest(runId, "// needs: " + SERVICE + "\n"));
            SwarmEngineImpl.TestRepairOutcome passed = engine.resumeAfterRetest(service, runId);

            assertThat(passed.stillFaulty()).isFalse();
            assertThat(passed.winner()).as("a candidate the task already had is chosen").isNotNull();
            assertThat(service.state()).isEqualTo(TaskState.SELECTED);
            assertThat(candidatesOf(store, service)).as("verified again, none written anew")
                .isEqualTo(candidates);
            assertThat(engine.passedAcceptanceIds(service))
                .contains("swarm.accept.BooksServiceTest#proves");
        }
    }

    /**
     * The build that was stopped is taken up again with the broken test still on its tests
     * branch: the task is built again, every new candidate errors the same way, and the test
     * has already been to its author once.
     */
    @Test
    void aTaskWhoseTestIsBrokenAndHasBeenToItsAuthorStopsAtOnceAndItsQuestionTakesRetest()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task service = task("Create the books service", Set.of(SERVICE, SCREEN));
        service.setAuthoredTestPaths(List.of(SERVICE_TEST));
        service.setTestRepairAttempted(true);

        try (FakeVllm fake = new FakeVllm(ABrokenCorrectedTestGetsNoRepairRoundTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, service,
                commitTest(runId, "// errors in its own set-up\n"));
            try {
                engine(fake, store).executeRun(run);
            } catch (RunMustPark stoppedBehindTheBlockedTask) {
                // either way the task is BLOCKED and has asked
            }

            assertThat(service.state()).isEqualTo(TaskState.BLOCKED);
            assertThat(candidatesOf(store, service)).as("the first round only: no repair worker")
                .isEqualTo(2);
            List<Decision> asked = store.root().decisions.values().stream()
                .filter(d -> d.state() == DecisionState.PENDING
                    && d.kind() == DecisionKind.BLOCKED_TASK).toList();
            assertThat(asked).hasSize(1);
            assertThat(asked.get(0).briefMarkdown())
                .startsWith("Task BLOCKED by its own acceptance test, not by its candidates: "
                    + "'Create the books service'")
                .contains("No repair round was started")
                .contains("Why: acceptance test swarm.accept.BooksServiceTest#proves errored "
                    + "before it could fail or pass - java.lang.IllegalStateException")
                .contains("the test, not the candidates, is the likely cause")
                .contains("compiles=true acceptance 0p/0f/1e");
            assertThat(service.retestOfferedBy())
                .as("the question takes the answer retest").isEqualTo(asked.get(0).id());
            assertThat(service.repairOfferedBy())
                .as("and not repair: no worker may change the test").isNull();
        }
    }

    /** The same test on a task whose test has not been to its author: it goes there. */
    @Test
    void aTestBrokenForEveryCandidateGoesToItsAuthorWhenItHasNotBeenYet() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task service = task("Create the books service", Set.of(SERVICE, SCREEN));
        service.setAuthoredTestPaths(List.of(SERVICE_TEST));

        try (FakeVllm fake = new FakeVllm(ABrokenCorrectedTestGetsNoRepairRoundTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seed(store, runId, service,
                commitTest(runId, "// errors in its own set-up\n"));
            SwarmEngineImpl engine = engine(fake, store);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.executeRun(run))
                .isInstanceOfSatisfying(com.swarmcoder.runtime.TestRepairNeeded.class, fault -> {
                    // Whichever rule sends it - the same failure for every candidate, or
                    // this one - it is the author's, and no worker is started on it.
                    assertThat(fault.testClass()).isEqualTo("swarm.accept.BooksServiceTest");
                    assertThat(fault.testMethod()).isEqualTo("proves");
                });
            assertThat(service.testRepairAttempted()).isTrue();
            assertThat(candidatesOf(store, service)).as("no repair worker").isEqualTo(2);
        }
    }

    private Run seed(ArtifactStore store, UUID runId, Task task, String tests)
            throws Exception {
        UUID graphId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, graphId, null, Instant.now(), null);
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(tests);
        store.append(() -> {
            store.root().taskGraphs.put(graphId,
                new TaskGraph(graphId, 1, null, List.of(task), List.of()));
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

    @Test
    void theQuestionOfABlockedTaskCountsErroredTestsAndSaysWhatErrored() {
        Task task = task("Store orders", Set.of("src/main/OrderStore.java"));
        String headline = "Task BLOCKED after swarm + repair round: 'Store orders'\n\n";
        List<CandidateSolution> all = List.of(errored(task, 0, 4), errored(task, 1, 4),
            errored(task, 2, 4), errored(task, 3, 4));

        assertThat(BlockedReason.counts(all.get(0).verification().acceptance()))
            .isEqualTo("0p/0f/4e");
        assertThat(BlockedReason.counts(new TestResults(3, 1, 2, 0, List.of()))).isEqualTo("3p/1f/2e");
        assertThat(BlockedReason.of(task, all, headline)).isEqualTo(
            "Why: acceptance test swarm.accept.OrderStoreTest#storesAnOrder errored before it "
                + "could fail or pass - java.lang.IllegalStateException: container not started "
                + "(4 acceptance test(s) errored, 0 passed and none failed)\n"
                + "All 4 verified candidate(s) errored on that same test with that same "
                + "exception and passed nothing: the test, not the candidates, is the likely "
                + "cause, and no worker may change it.\n\n");

        // One candidate passed a test: it is said what errored, and the test is not blamed.
        VerificationReport mixed = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(1, 0, 3, 0, List.of(setUpError()),
                List.of("swarm.accept.OrderStoreTest#a"), null, false),
            null, null, null, Duration.ZERO, "", null);
        assertThat(BlockedReason.of(task, List.of(all.get(0), new CandidateSolution(
                UUID.randomUUID(), task.id(), 1, "b", null, "diff", mixed, null, null,
                CandidateState.KILLED, null)), headline))
            .startsWith("Why: acceptance test swarm.accept.OrderStoreTest#storesAnOrder errored")
            .doesNotContain("the likely cause");

        // A test that fails an assertion is still named as failing.
        VerificationReport failing = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(0, 1, 0, 0, List.of(new TestFailure(
                "swarm.accept.OrderStoreTest#storesAnOrder", "expected: <1> but was: <0>", ""))),
            null, null, null, Duration.ZERO, "", null);
        assertThat(BlockedReason.of(task, List.of(new CandidateSolution(UUID.randomUUID(),
                task.id(), 0, "b", null, "diff", failing, null, null, CandidateState.KILLED,
                null)), headline))
            .isEqualTo("Why: acceptance test swarm.accept.OrderStoreTest#storesAnOrder fails - "
                + "expected: <1> but was: <0>\n\n");
    }

    private static TestFailure setUpError() {
        return new TestFailure("swarm.accept.OrderStoreTest#storesAnOrder",
            "container not started", "java.lang.IllegalStateException: container not started\n"
                + "\tat org.framework.Container.start(Container.java:88)\n"
                + "\tat swarm.accept.OrderStoreTest.setUp(OrderStoreTest.java:21)");
    }

    private static CandidateSolution errored(Task task, int worker, int count) {
        List<TestFailure> failures = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            failures.add(setUpError());
        }
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b", null, "diff",
            new VerificationReport(UUID.randomUUID(), true, true,
                new TestResults(0, 0, count, 0, failures), null, null, null, Duration.ZERO, "",
                null), null, null, CandidateState.KILLED, null);
    }

    private static int candidatesOf(ArtifactStore store, Task task) {
        int count = 0;
        for (var lazy : store.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && task.id().equals(candidate.taskId())) {
                count++;
            }
        }
        return count;
    }

    private static long openQuestions(ArtifactStore store) {
        return store.root().decisions.values().stream()
            .filter(d -> d.state() == DecisionState.PENDING
                && d.kind() == DecisionKind.BLOCKED_TASK).map(Decision::id).count();
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
        git("checkout -q master");
        testsCommit = commitTest(runId, "// needs: " + SERVICE + "\n// needs: " + SCREEN + "\n");
    }

    /** A new commit on the run's tests branch with the test file's marker lines replaced. */
    private String commitTest(UUID runId, String markers) throws Exception {
        git("checkout -q swarm/tests/" + runId);
        Path file = repoDir.resolve(SERVICE_TEST);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package swarm.accept;\n" + markers
            + "class BooksServiceTest { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        String sha = git("rev-parse HEAD").strip();
        git("checkout -q master");
        return sha;
    }

    /**
     * The stand-in acceptance stage {@code ATaskGetsOneMoreRepairRoundOnAnInstructionTest}
     * uses, which also reports an error with a stack for a test file that says it errors.
     */
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
                int errored = 0;
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    boolean ok = true;
                    boolean errors = false;
                    for (String line : Files.readString(file).split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            ok &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                        errors |= line.trim().equals("// errors in its own set-up");
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"proves\\">");
                    if (errors) {
                        errored++;
                        xml.append("<error message=\\"container not started\\">")
                           .append("java.lang.IllegalStateException: container not started\\n")
                           .append("\\tat org.framework.Container.start(Container.java:88)\\n")
                           .append("\\tat swarm.accept.").append(name).append(".setUp(")
                           .append(name).append(".java:9)\\n</error>");
                    } else if (!ok) {
                        failures++;
                        // A different message per candidate: identical failures from every candidate would
                        // (rightly) send the test back to its author, not into the repair round.
                        String tag = Long.toString(System.nanoTime(), 36);
                        tag = tag.substring(tag.length() - 4);
                        xml.append("<failure message=\\"missing ").append(tag)
                           .append("\\">the file it needs is missing</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " -> "
                        + (errors ? "ERROR" : ok ? "pass" : "FAIL"));
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"" + errored + "\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures + errored > 0 ? 1 : 0);
            }
        }
        """;

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "Create " + SERVICE + ".", writeSet, Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the service exists",
                "swarm.accept.BooksServiceTest#proves")), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
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
