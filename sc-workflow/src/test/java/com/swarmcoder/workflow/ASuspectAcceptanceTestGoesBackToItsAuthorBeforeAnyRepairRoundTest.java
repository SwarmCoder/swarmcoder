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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
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
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.domain.RepairIndex;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 79 (2026-10-04), owner decision: when every first candidate of a task compiles and
 * fails the same acceptance test with the same assertion, the test is suspect and goes back to
 * its author BEFORE any repair round. The author either corrects it - and the candidates already
 * written are re-verified, not rewritten - or stands by it, and its reason is given to the repair
 * workers and to the operator.
 *
 * <p>No model: one {@link FakeVllm} plays the two workers, the test author and the judge. The
 * stand-in acceptance stage fails a test carrying {@code // wrong-expectation: true} for every
 * candidate that did its work, with an id in the message that differs on every run of it.
 */
@ModelCodeOnThisPc
class ASuspectAcceptanceTestGoesBackToItsAuthorBeforeAnyRepairRoundTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String TEST_PATH = ACCEPT_DIR + "/LogbookTableTest.java";
    private static final String MARKER = "src/main/sorted-logbook-marker.txt";
    private static final String WRONG_TEST = "package swarm.accept;\n"
        + "// wrong-expectation: true\n"
        + "// needs: " + MARKER + "\n"
        + "class LogbookTableTest { void sortsByAnyColumn() {} }\n";
    private static final String CORRECTED_TEST = "package swarm.accept;\n"
        + "// needs: " + MARKER + "\n"
        + "class LogbookTableTest { void sortsByAnyColumn() {} }\n";
    private static final String REVIEW_INSTRUCTION = "every one of them compiled and failed your test";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void theAuthorCorrectsTheTestAndTheSameCandidatesAreReverifiedWithNoRepairWorker()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();
        AtomicInteger reviews = new AtomicInteger();

        try (FakeVllm fake = new FakeVllm(r -> route(r, reviews, true,
                "The five contacts all get the same QSL state, so they tie and the design's "
                    + "tie-break decides; the test expected insertion order."));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            workflow(fake, store).advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(reviews.get()).as("the author was asked once").isEqualTo(1);
            assertThat(finished.parkedAt()).as("the correction held, so nothing parks").isNull();
            assertThat(finished.state()).isNotIn(RunState.EXECUTING);
            assertThat(finished.acceptanceTestsCommit())
                .as("the correction is a new commit on the run's tests branch")
                .isNotEqualTo(testsCommit);

            List<CandidateSolution> candidates = candidatesOf(store, task);
            assertThat(candidates).anyMatch(c -> c.state() == CandidateState.SELECTED);
            assertThat(candidates)
                .as("the candidates already written were re-verified; none was rewritten")
                .noneMatch(c -> RepairIndex.isRepair(c.workerIndex()));

            Task stored = store.root().taskGraphs.get(finished.taskGraphId()).tasks().get(0);
            assertThat(stored.testRepairAttempted()).isTrue();
            assertThat(stored.authoredTests().reviewNote())
                .contains("the test was wrong and corrected it").contains("tie-break");
            assertThat(stored.authoredTests().repairedNote())
                .contains("every first candidate failed it with the same assertion");
        }
    }

    @Test
    void theAuthorStandsByTheTestSoTheRepairRoundRunsWithItsReasonAndTheQuestionSaysSo()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();
        AtomicInteger reviews = new AtomicInteger();
        List<String> repairBriefs = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (FakeVllm fake = new FakeVllm(r -> {
                 if (r.contains("the acceptance test was reviewed")) {
                     repairBriefs.add(r);
                 }
                 return route(r, reviews, false,
                     "The design says ascending by QSL state; both candidates sort descending.");
             });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            workflow(fake, store).advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(reviews.get()).as("one send-back per task").isEqualTo(1);
            assertThat(finished.acceptanceTestsCommit())
                .as("the test stands as written").isEqualTo(testsCommit);

            List<CandidateSolution> candidates = candidatesOf(store, task);
            assertThat(candidates)
                .as("the ordinary repair round followed")
                .anyMatch(c -> RepairIndex.isRepair(c.workerIndex()));
            assertThat(repairBriefs)
                .as("every repair worker was told what the author answered")
                .isNotEmpty()
                .allMatch(brief -> brief.contains("both candidates sort descending"));

            Task stored = store.root().taskGraphs.get(finished.taskGraphId()).tasks().get(0);
            assertThat(stored.state()).isEqualTo(TaskState.BLOCKED);
            assertThat(stored.authoredTests().reviewNote())
                .contains("the test is right").contains("both candidates sort descending");
            assertThat(stored.authoredTests().repairedNote())
                .as("nothing was repaired, so the judge is not told a test was").isNull();

            List<Decision> questions = store.root().decisions.values().stream()
                .filter(d -> d.kind() == DecisionKind.BLOCKED_TASK).toList();
            assertThat(questions).hasSize(1);
            assertThat(questions.get(0).briefMarkdown())
                .contains("Task BLOCKED after swarm + repair round")
                .contains("the test went back to the test author before the repair round")
                .contains("both candidates sort descending");
        }
    }

    @Test
    void anAuthorWhoGivesNoUsableAnswerLeavesTheRunWhereItWouldHaveBeen() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();
        AtomicInteger reviews = new AtomicInteger();

        try (FakeVllm fake = new FakeVllm(r -> {
                 if (r.contains(REVIEW_INSTRUCTION)) {
                     reviews.incrementAndGet();
                     return FakeVllm.Reply.text("{\"reason\":\"I am not sure\",\"files\":[]}");
                 }
                 return route(r, reviews, false, "");
             });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            workflow(fake, store).advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(reviews.get()).isEqualTo(1);
            assertThat(finished.acceptanceTestsCommit()).isEqualTo(testsCommit);
            assertThat(candidatesOf(store, task))
                .as("the repair round ran as it would have without the review")
                .anyMatch(c -> RepairIndex.isRepair(c.workerIndex()));
            Task stored = store.root().taskGraphs.get(finished.taskGraphId()).tasks().get(0);
            assertThat(stored.state()).isEqualTo(TaskState.BLOCKED);
            assertThat(stored.authoredTests().reviewNote()).contains("no usable answer");
        }
    }

    // --- shared wiring ---------------------------------------------------------------------

    private GreenfieldWorkflow workflow(FakeVllm fake, ArtifactStore store) {
        VllmClient client = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
        SwarmEngineImpl engine = new SwarmEngineImpl(client, store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
            new ModelProfileRegistry(List.of(new ModelProfile("fake",
                new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                ModelProfile.Kind.WORKER, 0, 0))),
            new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
        return new GreenfieldWorkflow(new KoogAgentRuntime(), engine, client, store,
            new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
            new CloudRoles(null, null, new TestAuthorClient(client, new CloudGate(1_000_000, null))),
            new GitService(repoDir));
    }

    private static List<CandidateSolution> candidatesOf(ArtifactStore store, Task task) {
        return store.root().candidateArchives.values().stream()
            .map(lazy -> (CandidateSolution) Lazy.get(lazy))
            .filter(c -> c.taskId().equals(task.id()))
            .toList();
    }

    private Run seedRun(ArtifactStore store, UUID runId, Task task) throws Exception {
        UUID graphId = UUID.randomUUID();
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
            null, null, null, graphId, null, Instant.now(), null);
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(testsCommit);
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            store.root().runs.put(runId, run);
            return null;
        }).get();
        return run;
    }

    private static Task task() {
        Task task = new Task(UUID.randomUUID(), 1, "Server-side logbook listing",
            "Implement getLogbookSorted on the server.", Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the logbook sorts by any column",
                "swarm.accept.LogbookTableTest#sortsByAnyColumn")),
            "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(List.of(TEST_PATH));
        return task;
    }

    /**
     * The judge, the test author's one review, and the workers - first and repair alike - who
     * all do the work by writing the marker file.
     */
    private static FakeVllm.Reply route(String conversation, AtomicInteger reviews,
                                        boolean testIsWrong, String reason) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains(REVIEW_INSTRUCTION)) {
            reviews.incrementAndGet();
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.put("testIsWrong", testIsWrong).put("reason", reason);
            if (testIsWrong) {
                root.putArray("files").addObject().put("path", TEST_PATH)
                    .put("content", CORRECTED_TEST);
            } else {
                root.putArray("files");
            }
            root.putArray("wrote");
            return FakeVllm.Reply.text(root.toString());
        }
        if (conversation.contains("wrote " + MARKER)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"sorted\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", MARKER).put("content", "sorted " + UUID.randomUUID() + "\n").toString());
    }

    // --- the repository ----------------------------------------------------------------------

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
        Path test = repoDir.resolve(TEST_PATH);
        Files.createDirectories(test.getParent());
        Files.writeString(test, WRONG_TEST);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * The stand-in acceptance stage. A {@code // needs:} line names a path that must exist for the
     * test to pass. A test carrying {@code // wrong-expectation: true} fails even then, as an
     * assertion failure whose trace runs through the candidate's own class and whose message
     * carries ids made up on the spot - what harness run 79's test did to both candidates.
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
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    String content = Files.readString(file);
                    boolean wrongExpectation = content.contains("wrong-expectation: true");
                    boolean needsOk = true;
                    for (String line : content.split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            needsOk &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"sortsByAnyColumn\\">");
                    if (!needsOk) {
                        failures++;
                        xml.append("<failure message=\\"getLogbookSorted is not implemented\\">")
                           .append("java.lang.AssertionError: getLogbookSorted is not implemented\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".sortsByAnyColumn(").append(name).append(".java:60)")
                           .append("</failure>");
                    } else if (wrongExpectation) {
                        failures++;
                        String a = UUID.randomUUID().toString();
                        String b = UUID.randomUUID().toString();
                        String message = "QSL_STATUS ascending reorders by that column ==&gt; "
                            + "expected: &lt;[" + a + ", " + b + "]&gt; but was: &lt;[" + b + ", "
                            + a + "]&gt;";
                        xml.append("<failure message=\\"").append(message).append("\\">")
                           .append("org.opentest4j.AssertionFailedError: ").append(message).append("\\n")
                           .append("\\tat com.hambook.server.LogbookServiceImpl.getLogbookSorted(LogbookServiceImpl.java:41)\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".sortsByAnyColumn(").append(name).append(".java:88)")
                           .append("</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " wrongExpectation="
                        + wrongExpectation + " needsOk=" + needsOk);
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
