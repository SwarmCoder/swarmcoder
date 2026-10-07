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
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
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

/**
 * The decided design, proved on the real swarm stack: <b>a task is verified against the tests it
 * claims, and only those.</b>
 *
 * <p>A run pinned to a base commit, a stage-4 tests commit on the run's own ref, a task claiming
 * one check and a task claiming none. The base carries a stale test from an earlier run — the
 * thing that failed every candidate of the operator's live run on 2026-09-02 before its own code
 * was looked at. Every worker worktree is cut from the pinned base, as the product does it; the
 * tests reach the candidate only at verification.
 *
 * <p>The acceptance stage is a stand-in for Maven: it lists the test files present under the
 * protected directory and reports one test case per file, failing any whose "needs" line names a
 * path the candidate did not create. So the executed test ids ARE the files that were in the tree,
 * and the assertions can say exactly which ones were.
 */
@ModelCodeOnThisPc
class ATaskIsVerifiedAgainstTheTestsItClaimsTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String FEATURE_TEST = ACCEPT_DIR + "/FeatureTest.java";
    private static final String STALE_TEST = ACCEPT_DIR + "/StaleFromADeadRun.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void theClaimingTaskRunsExactlyItsTestAndTheEnablerRunsNoneAndSurvives() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task feature = task("Deliver the feature", "Create src/main/feature.txt containing the word done.",
            Set.of("src/main"),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the feature file exists",
                "swarm.accept.FeatureTest#proves")),
            List.of(FEATURE_TEST));
        Task enabler = task("Prepare the README", "Add a line to README.md.", Set.of("README.md"),
            List.of(), List.of());

        List<CandidateSolution> archived;
        try (FakeVllm fake = new FakeVllm(ATaskIsVerifiedAgainstTheTestsItClaimsTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(feature, enabler), List.of());
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
            engine.executeRun(run);

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }

        // --- the task claiming a check: its one test ran, the stale one did not -----------------
        List<CandidateSolution> ofFeature = archived.stream()
            .filter(c -> c.taskId().equals(feature.id())).toList();
        assertThat(ofFeature).isNotEmpty();
        for (CandidateSolution candidate : ofFeature) {
            TestResults acceptance = candidate.verification().acceptance();
            assertThat(acceptance.stageOutcome()).isEqualTo(TestStageOutcome.EXECUTED);
            assertThat(acceptance.executed())
                .as("exactly the one test this task claims; log:\n" + candidate.verification().logTail())
                .isEqualTo(1);
            assertThat(acceptance.passedIds()).containsExactly("swarm.accept.FeatureTest#proves");
            assertThat(candidate.verification().logTail())
                .as("the stale test from the base was cleared before the stage ran")
                .doesNotContain("StaleFromADeadRun");
            assertThat(candidate.state()).isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
        }
        assertThat(ofFeature).anyMatch(c -> c.state() == CandidateState.SELECTED);

        // --- the enabler: no test placed, nothing executed, and it survives on that ------------
        List<CandidateSolution> ofEnabler = archived.stream()
            .filter(c -> c.taskId().equals(enabler.id())).toList();
        assertThat(ofEnabler).isNotEmpty();
        for (CandidateSolution candidate : ofEnabler) {
            TestResults acceptance = candidate.verification().acceptance();
            assertThat(acceptance.executed())
                .as("an enabler claims nothing, so nothing runs; log:\n" + candidate.verification().logTail())
                .isZero();
            assertThat(candidate.verification().logTail()).doesNotContain("StaleFromADeadRun");
            assertThat(candidate.state()).isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
        }
        assertThat(ofEnabler).anyMatch(c -> c.state() == CandidateState.SELECTED);

        // --- and the placement was verification-only: no candidate branch carries a test ----------
        for (CandidateSolution candidate : archived) {
            assertThat(candidate.diffUnified()).doesNotContain("FeatureTest");
            assertThat(fileOnBranch(candidate.branch(), FEATURE_TEST))
                .as("the overlay must not reach the candidate's branch").isNull();
        }
    }

    // --- the repository ------------------------------------------------------------------------

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
        // A dead run's test, committed in the base: it needs a file no plan of this run creates.
        write(STALE_TEST, "package swarm.accept;\n// needs: src/main/from-another-plan.txt\n"
            + "class StaleFromADeadRun { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();

        // Stage 4: the run's tests, on the run's own ref, cut from the base.
        git("checkout -q -b swarm/tests/" + runId);
        write(FEATURE_TEST, "package swarm.accept;\n// needs: src/main/feature.txt\n"
            + "class FeatureTest { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * A stand-in for the build's acceptance stage: one test case per file under the protected
     * directory, passing when every path its "needs" line names exists. Writes JUnit XML where the
     * gradle toolchain default expects it, and writes nothing at all when there is no file — which
     * is what surefire does with a selector that matches nothing.
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
                if (args.length > 0) {
                    Files.writeString(Path.of(args[0]), Path.of("").toAbsolutePath() + " " + files + "\\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("wrote src/main/feature.txt") || conversation.contains("wrote README.md")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        if (conversation.contains("feature.txt")) {
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", "src/main/feature.txt").put("content", "done\n").toString());
        }
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", "README.md").put("content", "hello\nimproved by worker\n").toString());
    }

    private static Task task(String title, String instructions, Set<String> writeSet,
                             List<AcceptanceCriterion> criteria, List<String> authoredTestPaths) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            criteria, "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(authoredTestPaths);
        return task;
    }

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String fileOnBranch(String branch, String path) {
        try {
            return git("show " + branch + ":" + path);
        } catch (Exception notThere) {
            return null;
        }
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repoDir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
