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
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design, on the real workflow + swarm stack: <b>when every candidate of a task fails
 * because the acceptance test threw inside its own code, the test goes back to its author instead
 * of sending a repair round at candidates that were never broken.</b>
 *
 * <p>Mirrors harness run 23 (01:59): a test named {@code BookManagementTest#removesBook} never
 * initialised its own {@code root} field, so every candidate errored with an NPE inside the test's
 * own class before either one's code ever ran. The stand-in acceptance stage reproduces exactly
 * that shape — a test file carrying a {@code // selftest-bug: true} marker errors with a trace that
 * never leaves the test's own class, regardless of what any candidate wrote.
 *
 * <p>Two scenarios, both driven through {@link GreenfieldWorkflow#advance}, starting the run
 * directly in EXECUTING (design, plan and the original TEST_AUTHORING call are not this test's
 * concern — only what happens once {@code SwarmEngineImpl} raises the fault):
 *
 * <ol>
 *   <li>the test author's correction removes the bug; the correction is red-checked, committed, and
 *       the SAME two candidates are re-verified against it — no new swarm — and the run reaches
 *       DELIVERED with a winner selected;</li>
 *   <li>the test author's "correction" still carries the same bug; re-verification still errors
 *       inside the test's own class, and the run parks naming the task and the original error.</li>
 * </ol>
 */
@ModelCodeOnThisPc
class ABrokenAcceptanceTestGoesBackToTheTestAuthorTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String TEST_PATH = ACCEPT_DIR + "/BookManagementTest.java";
    private static final String MARKER = "src/main/removed-book-marker.txt";
    private static final String BUGGY_CONTENT = "package swarm.accept;\n"
        + "// selftest-bug: true\n"
        + "// needs: " + MARKER + "\n"
        + "class BookManagementTest { void removesBook() {} }\n";
    private static final String CORRECTED_CONTENT = "package swarm.accept;\n"
        + "// needs: " + MARKER + "\n"
        + "class BookManagementTest { void removesBook() {} }\n";
    /** The "repair" that does not actually fix anything — for the still-faulty scenario. */
    private static final String STILL_BUGGY_CONTENT = BUGGY_CONTENT;
    /**
     * Harness run 37's shape (2026-09-25): the test compiles, calls the candidate's code, and that
     * code hits a TeaVM native method — UnsatisfiedLinkError, with the CANDIDATE's class on the
     * stack, so "inside the test's own code" is false and only the browser-only reading applies.
     */
    private static final String BROWSER_CONTENT = "package swarm.accept;\n"
        + "// browser-bug: true\n"
        + "// needs: " + MARKER + "\n"
        + "class BookManagementTest { void removesBook() {} }\n";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;
    @TempDir
    Path cacheDir;

    private String base;
    private String testsCommit;

    @Test
    void theCorrectionIsRedCheckedCommittedAndTheSameCandidatesAreReverified() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();

        try (FakeVllm fake = new FakeVllm(r -> route(r, CORRECTED_CONTENT));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            VllmClient client4j = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            SwarmEngineImpl engine = buildEngine(fake, store, client4j);
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client4j, store, new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, new TestAuthorClient(client4j, new CloudGate(1_000_000, null))),
                new GitService(repoDir));

            workflow.advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(finished.state()).as("the run should not still be stuck")
                .isNotIn(RunState.EXECUTING);
            assertThat(finished.parkedAt()).as("the corrected test held, so nothing should park").isNull();
            assertThat(finished.acceptanceTestsCommit())
                .as("a new commit was made for the correction")
                .isNotEqualTo(testsCommit);

            Task storedTask = store.root().taskGraphs.get(store.root().runs.get(runId).taskGraphId())
                .tasks().get(0);
            assertThat(storedTask.testRepairAttempted()).isTrue();
            assertThat(storedTask.authoredTests()).isNotNull();
            assertThat(storedTask.authoredTests().repairedNote())
                .contains("BookManagementTest")
                .contains("was repaired by the test author after failing inside its own code");

            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .filter(c -> c.taskId().equals(task.id()))
                .toList();
            assertThat(archived).anyMatch(c -> c.state() == CandidateState.SELECTED);
        }
    }

    @Test
    void aSecondFailureParksNamingTheTaskAndTheOriginalError() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();

        try (FakeVllm fake = new FakeVllm(r -> route(r, STILL_BUGGY_CONTENT));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            VllmClient client4j = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            SwarmEngineImpl engine = buildEngine(fake, store, client4j);
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client4j, store, new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, new TestAuthorClient(client4j, new CloudGate(1_000_000, null))),
                new GitService(repoDir));

            workflow.advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(finished.parkedAt()).as("the correction never actually fixed the test").isNotNull();
            assertThat(finished.parkReason())
                .contains("Add removeBook to BookService")
                .contains("is broken and could not be repaired");

            Task storedTask = store.root().taskGraphs.get(finished.taskGraphId()).tasks().get(0);
            assertThat(storedTask.testRepairAttempted())
                .as("bounded to one attempt even though it did not hold").isTrue();
        }
    }

    /**
     * Harness run 37, 2026-09-25: every candidate died with {@code UnsatisfiedLinkError} on a TeaVM
     * native method, through its OWN code, and the run spent a repair round of two more workers on
     * a test no code could pass. Now the test goes back to its author with the browser-only reason,
     * the correction is committed, and the same candidates are re-verified — no repair round.
     */
    @Test
    void run37ATestThatReachedBrowserOnlyCodeGoesBackToItsAuthorNotToARepairRound() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId, BROWSER_CONTENT);
        Task task = task();
        java.util.List<String> authorAsks = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (FakeVllm fake = new FakeVllm(r -> {
                if (r.contains("call code that can only run in a web browser")) {
                    authorAsks.add(r);
                }
                return route(r, CORRECTED_CONTENT);
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            VllmClient client4j = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            SwarmEngineImpl engine = buildEngine(fake, store, client4j);
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client4j, store, new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, new TestAuthorClient(client4j, new CloudGate(1_000_000, null))),
                new GitService(repoDir));

            workflow.advance(run);

            Run finished = store.root().runs.get(runId);
            assertThat(finished.parkedAt()).as("the corrected test held, so nothing should park").isNull();
            assertThat(authorAsks).as("the author was asked once, with the browser-only framing")
                .hasSize(1);
            assertThat(authorAsks.get(0))
                .contains("Your test ran and the test reached code that can only run in a browser")
                .contains("UnsatisfiedLinkError");
            Task storedTask = store.root().taskGraphs.get(finished.taskGraphId()).tasks().get(0);
            assertThat(storedTask.testRepairAttempted()).isTrue();
            assertThat(storedTask.authoredTests().repairedNote())
                .contains("every candidate showed that the test reached code that can only run in "
                    + "a browser");
            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .filter(c -> c.taskId().equals(task.id()))
                .toList();
            assertThat(archived).anyMatch(c -> c.state() == CandidateState.SELECTED);
            assertThat(archived).as("re-verified, not re-swarmed: no repair worker was dispatched")
                .allMatch(c -> c.workerIndex() < 100);
        }
    }

    /**
     * The repair during execution works as an agent session too, and its {@code compile_test}
     * compiles the draft in a throwaway tree (2026-10-02). It used to answer "NOT COMPILED: no
     * build is available" here, because only first authoring and three of the re-authoring
     * calls had a draft compiler.
     */
    @Test
    void theRepairDuringExecutionCanCompileItsDraftInAThrowawayTree() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task task = task();

        try (FakeVllm fake = new FakeVllm(r -> route(r, CORRECTED_CONTENT));
             ScriptedAgentLlm authorLlm = new ScriptedAgentLlm(
                 conversation -> "I decline to produce JSON.",
                 (turn, conversation) -> turn == 1
                     ? ScriptedAgentLlm.Turn.call("compile_test",
                         java.util.Map.of("path", TEST_PATH, "content", CORRECTED_CONTENT))
                     : ScriptedAgentLlm.Turn.call("report_done", java.util.Map.of("wrote",
                         "a book can be removed => swarm.accept.BookManagementTest#removesBook")));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = seedRun(store, runId, task);
            VllmClient client4j = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            VllmClient authorClient = new VllmClient(authorLlm.baseUrl(), "", "scripted", true);
            CloudGate gate = new CloudGate(1_000_000, null);
            TestAuthorClient author = new TestAuthorClient(authorClient, gate);
            author.setLookupAgent(new com.swarmcoder.knowledge.LookupAgent(
                new com.swarmcoder.knowledge.KnowledgeCurator(List.of(
                    new com.swarmcoder.knowledge.KnowledgeCurator.Root("project", repoDir, "local")),
                    null, cacheDir), null, repoDir, gate, new KoogAgentRuntime(), null, null));
            SwarmEngineImpl engine = buildEngine(fake, store, client4j);
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client4j, store, new RunPersister(store), gate, repoDir,
                new CloudRoles(null, null, author), new GitService(repoDir));

            workflow.advance(run);

            assertThat(authorLlm.sessionRequests)
                .as("the repair ran as a session: a compile_test turn, then the hand-in")
                .hasSizeGreaterThanOrEqualTo(2);
            String afterCompile = authorLlm.sessionRequests.get(1);
            assertThat(afterCompile).as("compile_test answered with the red check's verdict")
                .doesNotContain("no build is available")
                .doesNotContain("no tree could be prepared")
                .containsAnyOf("HEALTHY", "BROKEN TEST", "NOT CONCLUSIVE");
            assertThat(Files.exists(Path.of(System.getProperty("user.home"), ".swarmcoder", "wt",
                "draftcheck-" + runId))).as("the throwaway tree was removed").isFalse();
            assertThat(store.root().runs.get(runId).parkedAt()).isNull();
        }
    }

    // --- shared wiring ---------------------------------------------------------------------

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

    private SwarmEngineImpl buildEngine(FakeVllm fake, ArtifactStore store, VllmClient client4j) {
        return new SwarmEngineImpl(client4j, store,
            new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
            new ModelProfileRegistry(List.of(new ModelProfile("fake",
                new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                ModelProfile.Kind.WORKER, 0, 0))),
            new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
    }

    private static Task task() {
        Task task = new Task(UUID.randomUUID(), 1, "Add removeBook to BookService",
            "Implement removeBook on BookService, on the server.", Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "a book can be removed",
                "swarm.accept.BookManagementTest#removesBook")),
            "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(List.of(TEST_PATH));
        return task;
    }

    // --- the repository ----------------------------------------------------------------------

    private void initRepo(UUID runId) throws Exception {
        initRepo(runId, BUGGY_CONTENT);
    }

    private void initRepo(UUID runId, String originalTest) throws Exception {
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

        // The run's own tests ref, carrying the ORIGINAL buggy test — exactly what
        // TEST_AUTHORING would have committed before this test's story begins.
        git("checkout -q -b swarm/tests/" + runId);
        write(TEST_PATH, originalTest);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /**
     * The stand-in acceptance stage. A test file carrying {@code // selftest-bug: true} ERRORS with
     * a trace that never leaves the test's own class — the shape {@code AcceptanceFailureAttribution}
     * reads as "inside the test itself" — whatever any candidate wrote. Otherwise it behaves like
     * javac/surefire always have in these tests: a {@code // needs:} line names a path that must
     * exist for the test to pass, and a real failure names the candidate's own class in its trace.
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
                    boolean selftestBug = content.contains("selftest-bug: true");
                    boolean browserBug = content.contains("browser-bug: true");
                    boolean needsOk = true;
                    for (String line : content.split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            needsOk &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"removesBook\\">");
                    if (browserBug) {
                        errors++;
                        xml.append("<error message=\\"'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'\\" type=\\"java.lang.UnsatisfiedLinkError\\">")
                           .append("java.lang.UnsatisfiedLinkError: 'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'\\n")
                           .append("\\tat org.teavm.jso.browser.Window.current(Native Method)\\n")
                           .append("\\tat com.demo.client.BookStore.getInstance(BookStore.java:22)\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".removesBook(").append(name).append(".java:26)")
                           .append("</error>");
                    } else if (selftestBug) {
                        errors++;
                        xml.append("<error message=\\"root not initialised\\">")
                           .append("java.lang.NullPointerException: root not initialised\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".removesBook(").append(name).append(".java:108)")
                           .append("</error>");
                    } else if (!needsOk) {
                        failures++;
                        xml.append("<failure message=\\"expected the book to be removed\\">")
                           .append("java.lang.AssertionError: expected the book to be removed\\n")
                           .append("\\tat com.demo.server.BookServiceServerImpl.removeBook(BookServiceServerImpl.java:22)\\n")
                           .append("\\tat swarm.accept.").append(name)
                           .append(".removesBook(").append(name).append(".java:110)")
                           .append("</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " selftestBug=" + selftestBug
                        + " needsOk=" + needsOk);
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"" + errors + "\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit((failures + errors) > 0 ? 1 : 0);
            }
        }
        """;

    /**
     * The one FakeVllm route for the whole test: the code-review judge, the test author's ONE
     * repair call (recognised by the exact instruction {@link TestAuthorClient#repairFailingTest}
     * sends), and the two workers, who both implement the fix by writing the marker file.
     */
    private static FakeVllm.Reply route(String conversation, String correction) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("Fix the test's setup or assertion")
                || conversation.contains("call code that can only run in a web browser")) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.putArray("files").addObject().put("path", TEST_PATH).put("content", correction);
            root.putArray("wrote");
            return FakeVllm.Reply.text(root.toString());
        }
        if (conversation.contains("wrote " + MARKER)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"removed\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", MARKER).put("content", "removed\n").toString());
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
