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
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestResults;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The decided design, proved on the real swarm stack: <b>a later wave builds on the earlier waves'
 * winners, not on the commit the run was pinned to.</b>
 *
 * <p><b>What used to happen.</b> Every worker worktree of a run was cut from the pinned base, and
 * winners were merged only at the very end. So a task whose whole job is to use a class another
 * task delivers could not see that class, however long ago the other task had finished and won. On
 * 2026-09-02 that happened twice in one day: four workers wrote their own copy of a data class a
 * sister task had already delivered, and a second run's workers went hunting for a service class
 * that existed only on somebody else's candidate branch and were killed for making no progress.
 *
 * <p>Two tasks here, with a dependency edge between them. The first delivers {@code Book.java}. The
 * second is scripted to do exactly what a real worker does: read the file it needs FIRST, and write
 * its own copy of it if it is not there. So the assertions separate the two worlds cleanly — the
 * second task's winner either contains a duplicate {@code Book.java} (the defect) or does not (the
 * fix), and its acceptance test, which needs both files, either runs green or does not.
 *
 * <p>The acceptance stage is the same stand-in the sibling test uses: one test case per file under
 * the protected directory, failing any whose {@code // needs:} lines name a path that is not there.
 */
@ModelCodeOnThisPc
class ALaterWaveBuildsOnEarlierWinnersTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String SERVICE_TEST = ACCEPT_DIR + "/BookServiceTest.java";
    private static final String BOOK = "src/main/Book.java";
    private static final String SERVICE = "src/main/BookService.java";

    /** In the file the FIRST task writes. Its presence in a later tree is the whole proof. */
    private static final String DELIVERED = "DELIVERED-BY-TASK-A";
    /** In the file the second task writes only when it could NOT find the first task's work. */
    private static final String INVENTED = "INVENTED-BY-TASK-B";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    /**
     * The scripted workers here add small classes that no entry point of this fixture uses; what
     * this test is about is not whether they are reachable, so that check is off for it
     * (section 62 has its own tests).
     */
    @BeforeEach
    void reachabilityIsNotWhatThisTestIsAbout() {
        System.setProperty(ReachableCode.SWITCH, "off");
    }

    @AfterEach
    void reachabilityIsBackOn() {
        System.clearProperty(ReachableCode.SWITCH);
    }

    private String base;
    private String testsCommit;

    @Test
    void theSecondTaskSeesTheFirstTasksWinnerAndNeverWritesItsOwnCopy() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task deliverBook = task("Deliver the Book type",
            "Create " + BOOK + ". Nothing else.", Set.of(BOOK), List.of(), List.of());
        Task deliverService = task("Deliver the BookService",
            "Create " + SERVICE + ". It uses the Book type another task delivers; read it first.",
            Set.of(SERVICE),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the service exists over the Book",
                "swarm.accept.BookServiceTest#proves")),
            List.of(SERVICE_TEST));

        List<CandidateSolution> archived;
        List<String> gatedWaves = new ArrayList<>();
        List<String> gatedBases = new ArrayList<>();
        Run run;
        try (FakeVllm fake = new FakeVllm(ALaterWaveBuildsOnEarlierWinnersTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null,
                List.of(deliverBook, deliverService),
                List.of(new TaskEdge(deliverBook.id(), deliverService.id())));
            run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
            final Run stored = run;
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, stored);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            // The seam the red-check uses in production: asked once per wave after the first, with
            // the tree that wave will be cut from. Here it only records what it was asked.
            engine.setWaveGate((asked, wave, waveBase) -> {
                gatedWaves.add(wave.stream().map(Task::title).toList().toString());
                gatedBases.add(waveBase);
                return null;
            });
            engine.executeRun(run);

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }

        // --- the run recorded where the second wave was cut from ---------------------------------
        assertThat(run.progressCommit())
            .as("the first wave's winner was merged onto the run's progress branch")
            .isNotNull();
        assertThat(fileAt(run.progressCommit(), BOOK))
            .as("and that commit carries the first task's delivered file")
            .contains(DELIVERED);
        assertThat(run.baseCommit()).as("the operator's pinned base is untouched").isEqualTo(base);

        // --- the second wave was asked about, and asked about on the right tree ------------------
        assertThat(gatedWaves).containsExactly("[Deliver the BookService]");
        assertThat(gatedBases).containsExactly(run.progressCommit());

        // --- the second task's candidates saw the first task's work ------------------------------
        List<CandidateSolution> ofService = archived.stream()
            .filter(c -> c.taskId().equals(deliverService.id())).toList();
        assertThat(ofService).isNotEmpty();
        for (CandidateSolution candidate : ofService) {
            assertThat(fileOnBranch(candidate.branch(), BOOK))
                .as("every worker of the second task was cut from a tree holding the first "
                    + "task's winner")
                .contains(DELIVERED);
            assertThat(candidate.diffUnified())
                .as("so no worker had to write its own copy of it - that is the defect this "
                    + "test exists for")
                .doesNotContain(INVENTED);
            TestResults acceptance = candidate.verification().acceptance();
            assertThat(acceptance.executed())
                .as("exactly the one test this task claims; log:\n"
                    + candidate.verification().logTail())
                .isEqualTo(1);
            assertThat(acceptance.passedIds())
                .as("and it passes, which it can only do when BOTH files are in the tree")
                .containsExactly("swarm.accept.BookServiceTest#proves");
            assertThat(candidate.state()).isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
        }
        assertThat(ofService).anyMatch(c -> c.state() == CandidateState.SELECTED);

        // --- and the first task is untouched by any of it ----------------------------------------
        List<CandidateSolution> ofBook = archived.stream()
            .filter(c -> c.taskId().equals(deliverBook.id())).toList();
        assertThat(ofBook).isNotEmpty();
        assertThat(ofBook).anyMatch(c -> c.state() == CandidateState.SELECTED);
        for (CandidateSolution candidate : ofBook) {
            assertThat(fileOnBranch(candidate.branch(), SERVICE))
                .as("the first wave is cut from the pinned base, as it always was").isNull();
        }
    }

    /**
     * The same thing after a crash: <b>a resumed run continues from the progress commit, not from
     * the pinned base.</b>
     *
     * <p>The store is closed and reopened between setting the run up and driving it, so what the
     * engine reads is what actually survived to disk — the field, not an object still in memory. The
     * first task already has an archived winner, so it is not swarmed again; the second task has
     * none, and everything it needs is only in the commit the crashed process recorded.
     */
    @Test
    void aResumedRunContinuesFromTheProgressCommitAndDoesNotReswarmTheFinishedTask() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task deliverBook = task("Deliver the Book type",
            "Create " + BOOK + ". Nothing else.", Set.of(BOOK), List.of(), List.of());
        Task deliverService = task("Deliver the BookService",
            "Create " + SERVICE + ". It uses the Book type another task delivers; read it first.",
            Set.of(SERVICE),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the service exists over the Book",
                "swarm.accept.BookServiceTest#proves")),
            List.of(SERVICE_TEST));

        // What the killed process had already done: the first task's winner on its own branch, and
        // that branch merged onto the run's progress branch.
        String winnerBranch = "swarm/" + deliverBook.id() + "/0";
        git("checkout -q -b " + winnerBranch + " " + base);
        write(BOOK, "// " + DELIVERED + "\nclass Book { }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m \"first task winner\"");
        String progress = git("rev-parse HEAD").strip();
        git("branch " + WaveIntegrator.branchOf(runId) + " " + progress);
        git("checkout -q master");

        UUID graphId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TaskGraph graph = new TaskGraph(graphId, 1, null,
                List.of(deliverBook, deliverService),
                List.of(new TaskEdge(deliverBook.id(), deliverService.id())));
            Run crashed = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            crashed.setBaseCommit(base);
            crashed.setAcceptanceTestsCommit(testsCommit);
            crashed.setProgressCommit(progress);
            CandidateSolution won = new CandidateSolution(winnerId, deliverBook.id(), 0,
                winnerBranch, new SamplingConfig("fake", 0.2, 0L, "minimal-diff", "full-files"),
                "diff --git a/" + BOOK + " b/" + BOOK + "\n+++ b/" + BOOK + "\n",
                null, null, null, CandidateState.SELECTED, null);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, crashed);
                store.root().candidateArchives.put(winnerId, Lazy.Reference(won));
                return null;
            }).get();
        }

        List<String> gatedBases = new ArrayList<>();
        List<CandidateSolution> archived;
        try (FakeVllm fake = new FakeVllm(ALaterWaveBuildsOnEarlierWinnersTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run resumed = store.root().runs.get(runId);
            assertThat(resumed.progressCommit())
                .as("the progress commit survived the process that wrote it")
                .isEqualTo(progress);
            assertThat(resumed.progressPoint()).isEqualTo(progress);
            assertThat(resumed.withState(RunState.EXECUTING).progressCommit())
                .as("and a state transition carries it, like the pinned base")
                .isEqualTo(progress);

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            engine.setWaveGate((asked, wave, waveBase) -> {
                gatedBases.add(waveBase);
                return null;
            });
            engine.executeRun(resumed);

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }

        assertThat(gatedBases)
            .as("the second wave was cut from what the crashed process had already integrated")
            .containsExactly(progress);
        assertThat(archived.stream().filter(c -> c.taskId().equals(deliverBook.id())).toList())
            .as("the finished task was not swarmed again - only its archived winner is there")
            .hasSize(1);
        List<CandidateSolution> ofService = archived.stream()
            .filter(c -> c.taskId().equals(deliverService.id())).toList();
        assertThat(ofService).isNotEmpty();
        for (CandidateSolution candidate : ofService) {
            assertThat(fileOnBranch(candidate.branch(), BOOK)).contains(DELIVERED);
            assertThat(candidate.diffUnified()).doesNotContain(INVENTED);
        }
        assertThat(ofService).anyMatch(c -> c.state() == CandidateState.SELECTED);
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
        write(SERVICE_TEST, "package swarm.accept;\n// needs: " + BOOK + "\n// needs: " + SERVICE
            + "\nclass BookServiceTest { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /** The same stand-in acceptance stage the sibling test uses. */
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

    /**
     * The scripted workers. The second task's is the interesting one: it reads the file it needs
     * before writing anything, and writes its own copy when the read comes back "file not found" —
     * which is exactly what the real workers did on 2026-09-02, and what this fix must stop being
     * necessary.
     */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        boolean second = conversation.contains(SERVICE);
        if (!second) {
            if (conversation.contains("wrote " + BOOK)) {
                return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"Book delivered\"}");
            }
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", BOOK)
                .put("content", "// " + DELIVERED + "\nclass Book { }\n").toString());
        }
        if (conversation.contains("wrote " + SERVICE)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"service delivered\"}");
        }
        if (conversation.contains(DELIVERED) || conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", SERVICE)
                .put("content", "class BookService { Book book; }\n").toString());
        }
        if (conversation.contains("error: file not found: " + BOOK)) {
            // The old world: the class it depends on is nowhere, so it invents one.
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", BOOK)
                .put("content", "// " + INVENTED + "\nclass Book { }\n").toString());
        }
        return FakeVllm.Reply.toolCall("read",
            mapper.createObjectNode().put("path", BOOK).toString());
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

    private String fileOnBranch(String branch, String path) {
        return fileAt(branch, path);
    }

    private String fileAt(String ref, String path) {
        try {
            return git("show " + ref + ":" + path);
        } catch (Exception notThere) {
            return null;
        }
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
