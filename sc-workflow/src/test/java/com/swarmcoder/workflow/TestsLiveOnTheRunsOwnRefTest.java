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
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.BasePin;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a run's acceptance tests live, and who looks at them.
 *
 * <p>They used to be committed in the operator's checkout, on the delivery branch. The run was
 * pinned three stages earlier, so no worktree of the run saw that commit — and a dead run's tests
 * outlived it there: on 2026-09-02 the demo repository's delivery branch carried four test files
 * from four dead runs, and every candidate of the next run failed test-compile on them.
 *
 * <p>Now they are committed on the run's own ref, cut from the pinned base; the delivery branch is
 * not touched; each task records which files are its own; the red-check looks at each task's files
 * on that base, in a worktree, exactly as that task's candidates will be verified; and the
 * integration branch descends from the tests commit, holding the tests due so far after each
 * merge and all of them at the end.
 */
@ModelCodeOnThisPc
class TestsLiveOnTheRunsOwnRefTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String FEATURE_TEST = ACCEPT_DIR + "/FeatureTest.java";
    private static final String LISTING_TEST = ACCEPT_DIR + "/ListingTest.java";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;
    @TempDir
    Path scratch;

    // --- TEST_AUTHORING and the red-check ------------------------------------------------------

    @Test
    void theTestsAreCommittedOnTheRunsRefTheCheckoutIsUntouchedAndTheRedCheckLooksPerTask()
            throws Exception {
        Path acceptLog = scratch.resolve("accept-runs.log");
        initRepo(acceptLog);
        String base = git("rev-parse HEAD").strip();
        UUID runId = UUID.randomUUID();
        List<String> events = new CopyOnWriteArrayList<>();

        try (ScriptedLlm llm = new ScriptedLlm(TestsLiveOnTheRunsOwnRefTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            AgentRuntime unused = spec -> {
                throw new UnsupportedOperationException("no workers in this test");
            };
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(0, null);
            GitService git = new GitService(repo);
            // The swarm engine is a pass-through: this test is about the stages around it.
            WorkflowEngine engine = new WorkflowEngine(unused, run -> run, client, store, gate,
                repo, CloudRoles.allOn(client, gate), git);
            engine.setEventLogger(events::add);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE, null, null,
                null, null, null, Instant.now(), new RunReport(runId, "deliver the feature"));
            assertThat(BasePin.pin(run, git)).isNotNull();
            engine.advance(run);
            awaitTerminal(store, runId);

            Run finished = store.root().runs.get(runId);
            assertThat(finished.state()).as(String.join("\n", events)).isEqualTo(RunState.DELIVERED);
            TaskGraph graph = store.root().taskGraphs.get(finished.taskGraphId());
            Task feature = byTitle(graph, "Deliver the feature");
            Task readme = byTitle(graph, "Prepare the README");

            // The tests are on the run's own ref, cut from the pinned base…
            String testsCommit = finished.acceptanceTestsCommit();
            assertThat(testsCommit).as("the run records its tests commit; log:\n"
                + String.join("\n", events)).isNotNull();
            assertThat(git("rev-parse swarm/tests/" + runId).strip()).isEqualTo(testsCommit);
            assertThat(git("rev-parse " + testsCommit + "~1").strip()).isEqualTo(base);
            assertThat(git("show " + testsCommit + ":" + FEATURE_TEST)).contains("class FeatureTest");
            // …and the operator's checkout and delivery branch are exactly as they were.
            assertThat(git("rev-parse master").strip()).isEqualTo(base);
            assertThat(git("status --porcelain")).isBlank();
            assertThat(repo.resolve("src/test")).doesNotExist();

            // Each task knows which files are its own.
            assertThat(feature.authoredTestPaths()).containsExactly(FEATURE_TEST);
            assertThat(readme.authoredTestPaths()).isEmpty();

            // The red-check ran per claiming task, in a worktree, on the base plus that task's
            // file — not in the operator's checkout, and not for the task claiming nothing.
            assertThat(events).anyMatch(e -> e.contains("Red-check passed for task 'Deliver the feature'"));
            assertThat(events).noneMatch(e -> e.contains("Red-check") && e.contains("Prepare the README"));
            List<String> acceptRuns = Files.readAllLines(acceptLog);
            assertThat(acceptRuns).hasSize(1);
            assertThat(acceptRuns.get(0))
                .doesNotStartWith(repo.toAbsolutePath().toString())
                .contains("FeatureTest.java");
            // And the red-check's scratch is gone; only the tests ref remains.
            assertThat(git("branch --list swarm/redcheck/*")).isBlank();
            assertThat(git("worktree list")).doesNotContain("redcheck-").doesNotContain("tests-");
        }
    }

    // --- FINAL_INTEGRATION ----------------------------------------------------------------------

    @Test
    void theIntegrationBranchHoldsTheTestsDueSoFarAfterEachMergeAndAllOfThemAtTheEnd()
            throws Exception {
        Path acceptLog = scratch.resolve("integration-accept.log");
        initRepo(acceptLog);
        String base = git("rev-parse HEAD").strip();
        UUID runId = UUID.randomUUID();

        // Stage 4's commit: both tasks' tests on the run's ref.
        git("checkout -q -b swarm/tests/" + runId);
        write(FEATURE_TEST, testSource("FeatureTest", "src/main/feature.txt"));
        write(LISTING_TEST, testSource("ListingTest", "src/main/listing.txt"));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        String testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");

        Task feature = task("Deliver the feature", Set.of("src/main/feature.txt"),
            "swarm.accept.FeatureTest#proves", List.of(FEATURE_TEST));
        Task listing = task("Deliver the listing", Set.of("src/main/listing.txt"),
            "swarm.accept.ListingTest#proves", List.of(LISTING_TEST));
        winnerBranch(feature, "src/main/feature.txt");
        winnerBranch(listing, "src/main/listing.txt");

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(feature, listing),
                List.of(new TaskEdge(feature.id(), listing.id())));
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.FINAL_INTEGRATION,
                null, null, null, graph.id(), null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                store.root().candidateArchives.put(UUID.randomUUID(),
                    Lazy.Reference(winner(feature, "swarm/" + feature.id() + "/0", "diff")));
                store.root().candidateArchives.put(UUID.randomUUID(),
                    Lazy.Reference(winner(listing, "swarm/" + listing.id() + "/0", "diff")));
                return null;
            }).get();

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
            List<String> acceptRuns = Files.readAllLines(acceptLog);
            assertThat(acceptRuns).as("one acceptance run per merge").hasSize(2);
            assertThat(acceptRuns.get(0))
                .as("after the first merge only the first task's test is due")
                .contains("FeatureTest.java").doesNotContain("ListingTest.java");
            assertThat(acceptRuns.get(1))
                .as("after the last merge every claimed test is due")
                .contains("FeatureTest.java").contains("ListingTest.java");
            // The integration branch descends from the tests commit, so it carries the tests —
            // the route by which they reach the delivery branch, together with the code.
            String integration = result.integrationBranch();
            assertThat(git("merge-base --is-ancestor " + testsCommit + " " + integration)).isNotNull();
            assertThat(git("show " + integration + ":" + FEATURE_TEST)).contains("class FeatureTest");
            assertThat(git("show " + integration + ":" + LISTING_TEST)).contains("class ListingTest");
            assertThat(git("show " + integration + ":src/main/feature.txt")).contains("done");
            assertThat(git("show " + integration + ":src/main/listing.txt")).contains("done");
        }
    }

    // --- the scripted roles -------------------------------------------------------------------

    private static String route(String conversation) {
        if (conversation.contains("You are an AI planner")) {
            return """
                {"tasks":[
                  {"id":"t1","title":"Prepare the README","instructions":"Add a line to README.md",
                   "writeSet":["README.md"],"readSet":[],"criteria":[],"requirementRefs":[]},
                  {"id":"t2","title":"Deliver the feature","instructions":"Create src/main/feature.txt",
                   "writeSet":["src/main"],"readSet":[],
                   "criteria":[{"text":"the feature file exists","testClassOrFile":"swarm.accept.FeatureTest#proves"}],
                   "requirementRefs":[]}
                ],"edges":[{"from":"t1","to":"t2"}]}
                """;
        }
        if (conversation.contains("You are a test author")) {
            return """
                {"files":[{"path":"src/test/java/swarm/accept/FeatureTest.java",
                  "content":"package swarm.accept;\\n// needs: src/main/feature.txt\\nimport org.junit.jupiter.api.Test;\\nclass FeatureTest {\\n    @Test\\n    void proves() {}\\n}\\n"}],
                 "wrote":[{"criterion":"the feature file exists","test":"swarm.accept.FeatureTest#proves"}]}
                """;
        }
        return "I decline to produce JSON.";
    }

    // --- the repository -------------------------------------------------------------------------

    private void initRepo(Path acceptLog) throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.createDirectories(repo.resolve("src/main"));
        Files.writeString(repo.resolve("src/main/.keep"), "");
        Files.createDirectories(repo.resolve("tools"));
        Files.writeString(repo.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repo.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java %s"
            timeoutSeconds: 60
            """.formatted(java, acceptLog.toAbsolutePath().toString().replace('\\', '/')));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
    }

    private void winnerBranch(Task task, String file) throws Exception {
        git("checkout -q -b swarm/" + task.id() + "/0");
        write(file, "done\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m " + task.id());
        git("checkout -q master");
    }

    private static String testSource(String className, String needs) {
        return "package swarm.accept;\n// needs: " + needs + "\nclass " + className
            + " { void proves() {} }\n";
    }

    /** The same stand-in acceptance stage as the swarm-level proof, plus a record of each run. */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                Path dir = Path.of("src/test/java/swarm/accept");
                List<String> files = new ArrayList<>();
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) {
                        s.filter(p -> p.toString().endsWith(".java")).sorted()
                            .forEach(p -> files.add(p.getFileName().toString()));
                    }
                }
                if (args.length > 0) {
                    Files.writeString(Path.of(args[0]), Path.of("").toAbsolutePath() + " " + files + "\\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                if (files.isEmpty()) {
                    return;
                }
                StringBuilder xml = new StringBuilder();
                int failures = 0;
                for (String file : files) {
                    String name = file.replace(".java", "");
                    boolean ok = true;
                    for (String line : Files.readString(dir.resolve(file)).split("\\n")) {
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
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures > 0 ? 1 : 0);
            }
        }
        """;

    // --- plumbing -------------------------------------------------------------------------------

    private static Task task(String title, Set<String> writeSet, String testRef,
                             List<String> authored) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), title + " is done", testRef)),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        task.setAuthoredTestPaths(authored);
        return task;
    }

    private static CandidateSolution winner(Task task, String branch, String diff) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), 0, branch,
            new SamplingConfig("m", 0.2, 0, "p", "s"), diff, null, null, null,
            CandidateState.SELECTED, null);
    }

    private static Task byTitle(TaskGraph graph, String title) {
        return graph.tasks().stream().filter(t -> title.equals(t.title())).findFirst()
            .orElseThrow(() -> new AssertionError("no task titled " + title + " in "
                + graph.tasks().stream().map(Task::title).toList()));
    }

    private static void awaitTerminal(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED)) {
                return;
            }
            if (store.root().decisions.values().stream().anyMatch(d -> runId.equals(d.runId()))) {
                return; // parked — the assertion on the state will say so
            }
            Thread.sleep(200);
        }
    }

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String git(String args) throws Exception {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.addAll(List.of("cmd.exe", "/c", "git " + args));
        } else {
            command.addAll(List.of("sh", "-c", "git " + args));
        }
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
