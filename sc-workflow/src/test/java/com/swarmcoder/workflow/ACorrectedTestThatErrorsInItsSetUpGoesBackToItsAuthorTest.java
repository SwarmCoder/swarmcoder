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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.RepairIndex;
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
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A corrected acceptance test is held to the standard of a first-written one, and a stopped
 * build's test can be sent back on an instruction (DEVELOPER_CORRECTIONS section 86).
 *
 * <p>Live supervised build, 2026-10-12, with other names. Every first candidate failed the
 * same test with the same assertion, the test went back to its author, and the author's
 * correction also changed how the test starts: from then on it errored in its own set-up
 * before any candidate's code ran. The correction was taken as "red", the candidates were
 * verified again, a repair round of workers who may not change the test was started, and the
 * build stopped with a question that offered only another go and another worker round.
 *
 * <p>The whole workflow runs here with the real engine, scripted models and a stand-in test
 * stage. The test's content says how it behaves: it errors in what it starts (seen before any
 * candidate is applied), it errors in its own lines (seen only once the candidates are
 * verified again, since a test written before its code looks the same), or it runs.
 */
@ModelCodeOnThisPc
class ACorrectedTestThatErrorsInItsSetUpGoesBackToItsAuthorTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String TEST_PATH = ACCEPT_DIR + "/LogbookTableTest.java";
    private static final String MARKER = "src/main/sorted-logbook-marker.txt";
    private static final String BODY = "// needs: " + MARKER + "\n"
        + "class LogbookTableTest { void sortsByAnyColumn() {} }\n";
    private static final String WRONG_TEST = "package swarm.accept;\n// wrong-expectation: true\n" + BODY;
    private static final String DIES_IN_WHAT_IT_STARTS = "package swarm.accept;\n// errors: container\n" + BODY;
    private static final String DIES_IN_ITS_OWN_LINES = "package swarm.accept;\n// errors: own\n" + BODY;
    private static final String CORRECTED_TEST = "package swarm.accept;\n" + BODY;

    private static final String REVIEW = "every one of them compiled and failed your test";
    private static final String BROKEN_CORRECTION = "The corrected test you handed in is broken, not red";
    private static final String ON_INSTRUCTION = "sends your test back with this instruction";
    private static final String INSTRUCTION = "Start the test the way the first version did; "
        + "give each test a store of its own.";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    /** What the scripted test author hands in, and what it was asked. */
    private static final class Author {
        final String review;
        final String afterBrokenCorrection;
        final List<String> reviews = new CopyOnWriteArrayList<>();
        final List<String> brokenCorrections = new CopyOnWriteArrayList<>();
        final List<String> instructed = new CopyOnWriteArrayList<>();

        Author(String review, String afterBrokenCorrection) {
            this.review = review;
            this.afterBrokenCorrection = afterBrokenCorrection;
        }
    }

    @Test
    void aCorrectionThatDiesInWhatItStartsGoesBackOnceWithTheErrorBeforeItIsTaken()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();
        Author author = new Author(DIES_IN_WHAT_IT_STARTS, CORRECTED_TEST);

        try (FakeVllm fake = new FakeVllm(r -> route(r, author));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            workflow(fake, store).advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(author.reviews).hasSize(1);
            assertThat(author.brokenCorrections).as("sent back once more, with the error")
                .hasSize(1);
            assertThat(author.brokenCorrections.get(0))
                .contains("java.lang.IllegalStateException: container not started")
                .contains("at org.framework.Container.start(Container.java:88)")
                .contains("The version before your correction did start")
                .contains("// errors: container");
            assertThat(finished.parkedAt()).as("the second correction held").isNull();
            assertThat(finished.state()).isNotIn(RunState.EXECUTING);
            assertThat(new String(new GitService(repoDir).fileAt(finished.acceptanceTestsCommit(),
                TEST_PATH))).as("the broken correction was never committed")
                .isEqualTo(CORRECTED_TEST);
            List<CandidateSolution> candidates = candidatesOf(store, task);
            assertThat(candidates).anyMatch(c -> c.state() == CandidateState.SELECTED);
            assertThat(candidates).as("no repair worker was started")
                .noneMatch(c -> RepairIndex.isRepair(c.workerIndex()));
        }
    }

    @Test
    void aCorrectionBrokenForEveryCandidateStopsTheRunWithNoRepairRoundAndRetestPutsItRight()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();
        // The author changes how the test starts, and does so again when told.
        Author author = new Author(DIES_IN_ITS_OWN_LINES, DIES_IN_ITS_OWN_LINES + "// second\n");

        try (FakeVllm fake = new FakeVllm(r -> route(r, author));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            GreenfieldWorkflow workflow = workflow(fake, store);
            workflow.advance(run);

            Run stopped = store.root().runs.get(runId);
            Task stored = store.root().taskGraphs.get(stopped.taskGraphId()).tasks().get(0);
            assertThat(author.reviews).hasSize(1);
            assertThat(author.brokenCorrections)
                .as("found when the candidates were verified again, and sent back once")
                .hasSize(1);
            assertThat(author.brokenCorrections.get(0))
                .contains("none of their code ran")
                .contains("java.lang.NullPointerException: store is null")
                .contains("at swarm.accept.LogbookTableTest.setUp(LogbookTableTest.java:12)");
            assertThat(stopped.state()).isEqualTo(RunState.EXECUTING);
            assertThat(stopped.parkedAt()).as("still broken: the run stops at once").isNotNull();
            assertThat(stopped.parkReason())
                .startsWith("The acceptance test for task 'Server-side logbook listing' is "
                    + "broken, not red: all 2 candidate(s) compiled and none of their code ran")
                .contains("java.lang.NullPointerException")
                .contains("still broken for every candidate")
                .contains("No repair round was started")
                .contains("To correct the test by hand");
            assertThat(candidatesOf(store, task)).as("no repair worker was started")
                .noneMatch(c -> RepairIndex.isRepair(c.workerIndex()))
                .noneMatch(c -> c.state() == CandidateState.SELECTED);
            List<Decision> questions = store.root().decisions.values().stream()
                .filter(d -> d.kind() == DecisionKind.BLOCKED_TASK
                    && d.state() == DecisionState.PENDING).toList();
            assertThat(questions).hasSize(1);
            assertThat(stored.retestOfferedBy())
                .as("the stop is about the task's own test: its question takes retest")
                .isEqualTo(questions.get(0).id());
            assertThat(stored.repairOfferedBy()).as("and no worker round").isNull();

            // The question is answered retest (DecisionAnswers writes the text on the task and
            // hands the run back).
            stored.setRetestInstruction(INSTRUCTION);
            store.saveTask(stored);
            workflow.advance(stopped);

            Run finished = store.root().runs.get(runId);
            assertThat(author.instructed).as("the author was given the instruction").hasSize(1);
            String asked = author.instructed.get(0);
            assertThat(asked.indexOf(INSTRUCTION)).isPositive()
                .isLessThan(asked.indexOf("WHAT THE BUILD STOPPED ON"));
            assertThat(asked).as("and what the build stopped on")
                .contains("errored before it could fail or pass - java.lang.NullPointerException")
                .contains("the test, not the candidates, is the likely cause")
                .contains("acceptance 0p/0f/1e");
            assertThat(stored.instructedRetests()).isEqualTo(1);
            assertThat(stored.retestInstruction()).isNull();
            assertThat(finished.state()).isNotIn(RunState.EXECUTING);
            assertThat(new String(new GitService(repoDir).fileAt(finished.acceptanceTestsCommit(),
                TEST_PATH))).isEqualTo(CORRECTED_TEST);
            List<CandidateSolution> candidates = candidatesOf(store, task);
            assertThat(candidates).as("a candidate the task already had passes and is chosen")
                .anyMatch(c -> c.state() == CandidateState.SELECTED);
            assertThat(candidates).as("the story was not built again")
                .noneMatch(c -> RepairIndex.isRepair(c.workerIndex())).hasSize(2);
            assertThat(stored.authoredTests().repairedNote()).contains("on an instruction");
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

    /** The judge, the test author's three kinds of call, and the workers. */
    private static FakeVllm.Reply route(String conversation, Author author) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains(BROKEN_CORRECTION)) {
            author.brokenCorrections.add(conversation);
            return files(author.afterBrokenCorrection);
        }
        if (conversation.contains(ON_INSTRUCTION)) {
            author.instructed.add(conversation);
            return files(CORRECTED_TEST);
        }
        if (conversation.contains(REVIEW)) {
            author.reviews.add(conversation);
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.put("testIsWrong", true).put("reason", "The test expected insertion order.");
            root.putArray("files").addObject().put("path", TEST_PATH).put("content", author.review);
            root.putArray("wrote");
            return FakeVllm.Reply.text(root.toString());
        }
        if (conversation.contains("wrote " + MARKER)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"sorted\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", MARKER).put("content", "sorted " + UUID.randomUUID() + "\n").toString());
    }

    private static FakeVllm.Reply files(String content) {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.putArray("files").addObject().put("path", TEST_PATH).put("content", content);
        root.putArray("wrote");
        return FakeVllm.Reply.text(root.toString());
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
     * The stand-in acceptance stage of
     * {@code ASuspectAcceptanceTestGoesBackToItsAuthorBeforeAnyRepairRoundTest}, which also
     * reports an ERROR for a test whose content says it errors: {@code // errors: container}
     * with a stack whose top frame is in what the test started, {@code // errors: own} with a
     * stack that never leaves the test's own class. Either way no test passes or fails, with
     * or without a candidate's code.
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
                int errors = 0;
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
                    if (content.contains("// errors: container")) {
                        errors++;
                        xml.append("<error message=\\"container not started\\">")
                           .append("java.lang.IllegalStateException: container not started\\n")
                           .append("\\tat org.framework.Container.start(Container.java:88)\\n")
                           .append("\\tat swarm.accept.").append(name).append(".setUp(")
                           .append(name).append(".java:12)</error>");
                    } else if (content.contains("// errors: own")) {
                        errors++;
                        xml.append("<error message=\\"store is null\\">")
                           .append("java.lang.NullPointerException: store is null\\n")
                           .append("\\tat swarm.accept.").append(name).append(".setUp(")
                           .append(name).append(".java:12)</error>");
                    } else if (!needsOk) {
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
                           .append("\\tat org.example.logbook.server.LogbookServiceImpl.getLogbookSorted(LogbookServiceImpl.java:41)\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".sortsByAnyColumn(").append(name).append(".java:88)")
                           .append("</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name);
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"" + errors + "\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures + errors > 0 ? 1 : 0);
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
