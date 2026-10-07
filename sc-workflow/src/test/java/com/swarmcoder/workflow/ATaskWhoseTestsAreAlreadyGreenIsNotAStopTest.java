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
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChecksAlreadyProved;
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
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The decided design, on the real swarm stack: <b>a later wave's task whose acceptance tests are
 * already green is a fact to write down, not a reason to stop.</b>
 *
 * <h2>The run this test is a copy of</h2>
 *
 * <p>2026-09-03, story "Assign and display a book rating". One agreed check — "A rating can be
 * assigned to a book and is displayed alongside the book's details" — answered by one JUnit
 * acceptance test. Three waves: extend the data model, implement the server-side update, then
 * update the client to show and set the rating. The first two waves ran, won and were integrated.
 * Then the third wave's red-check found its test already passing on the integrated tree, and the
 * run PARKED, telling a person that the tests "are not red" and that TEST_AUTHORING "must revise
 * them before dispatch".
 *
 * <p>Everything about that was correct except the conclusion. The test was fine: it proved the
 * check the only way a JUnit test in the acceptance module can, through the service, and the two
 * enabler waves had just built that service. The half the check is worded about — a screen — is
 * not what the test measures and never could be. So the machine stopped at the last step of a run
 * that had gone right, and asked for a repair to something that was not broken.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>The same three-wave shape, on the real engine and the real wave gate (installed by
 * {@link GreenfieldWorkflow}'s constructor), with a scripted model:
 *
 * <ol>
 *   <li>the run does not park — the third wave is dispatched;</li>
 *   <li>the fact is written onto the task and survives to the store: which tests were already
 *       green, on which tree, that the check is worded about a screen, and where the rest of it
 *       would be proved;</li>
 *   <li>the judge is told, in the brief, that the tests were green before the candidate;</li>
 *   <li>the candidate that actually wrote the client UI is selected, and the one whose diff is a
 *       single comment is not — even though the acceptance tests are green for both of them,
 *       which is exactly why the tests cannot be what decides.</li>
 * </ol>
 */
@ModelCodeOnThisPc
class ATaskWhoseTestsAreAlreadyGreenIsNotAStopTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/BookRatingTest.java";
    private static final String BOOK = "src/main/Book.java";
    private static final String SERVICE = "src/main/BookService.java";
    private static final String CLIENT = "src/main/client/BookList.java";

    /** What a candidate that really did the client work writes. */
    private static final String REAL_UI = "renderRatingStars";
    /** What the candidate that did nothing writes: one comment line and no code. */
    private static final String COMMENT_ONLY = "// TODO: show the rating here one day";

    /** The persona handed to worker 1 — the only thing that tells two workers apart in a prompt. */
    private static final String WORKER_ONE = "implement exactly what the acceptance tests require";

    private static final String CHECK =
        "A rating can be assigned to a book and is displayed alongside the book's details";

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
    void theThirdWaveRunsAndTheAlreadyGreenCheckIsRecordedRatherThanParkedOn() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);

        Task model = task("Extend Book data model with rating support",
            "Create " + BOOK + ". Nothing else.", Set.of(BOOK), List.of(), List.of(), 1);
        Task service = task("Implement server-side rating update logic",
            "Create " + SERVICE + ". Nothing else.", Set.of(SERVICE), List.of(), List.of(), 1);
        Task ui = task("Update client UI to display and assign book ratings",
            "Create " + CLIENT + ". It renders each book's rating and lets the reader set it.",
            Set.of("src/main/client"),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), CHECK,
                "swarm.accept.BookRatingTest#assignsRatingToBook")),
            List.of(RATING_TEST), 2);

        List<CandidateSolution> archived;
        Run run;
        ChecksAlreadyProved recorded;
        String judgeBrief;
        try (FakeVllm fake = new FakeVllm(ATaskWhoseTestsAreAlreadyGreenIsNotAStopTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(model, service, ui),
                List.of(new TaskEdge(model.id(), service.id()),
                        new TaskEdge(service.id(), ui.id())));
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
            store.indexTasks(graph.tasks());

            VllmClient client = new VllmClient(fake.baseUrl(), "", "fake-judge", true);
            SwarmEngineImpl engine = new SwarmEngineImpl(client, store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            // Constructing the workflow is what installs the REAL wave gate on the engine — the
            // red-check this whole test is about. Nothing else about the workflow is used here.
            new GreenfieldWorkflow(new KoogAgentRuntime(), engine, client, store,
                new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, null), new GitService(repoDir));

            // Before the fix this threw RunMustPark, from inside the third wave's gate.
            engine.executeRun(run);

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
            judgeBrief = fake.requests.stream()
                .filter(r -> r.contains("code-review judge"))
                .filter(r -> r.contains("Update client UI"))
                .findFirst().orElse("");
        }
        // Read back from a REOPENED store, not from the object still in memory: the field is
        // persisted, and a field that is only correct until the process ends is not a record.
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            recorded = reopened.getTask(ui.id()).checksAlreadyProved();
        }

        // --- 1. the run reached the end ------------------------------------------------------
        assertThat(archived.stream().map(CandidateSolution::taskId).distinct())
            .as("all three tasks were swarmed — the third wave was dispatched, not parked on")
            .containsExactlyInAnyOrder(model.id(), service.id(), ui.id());

        // --- 2. the already-green fact is written down and survives to the store ---------------
        assertThat(recorded)
            .as("the wave gate wrote the fact onto the task, and it survived a restart")
            .isNotNull();
        assertThat(recorded.tests())
            .as("naming the tests that were already passing, not just a count")
            .containsExactly("swarm.accept.BookRatingTest#assignsRatingToBook");
        assertThat(recorded.passed()).isEqualTo(1);
        assertThat(recorded.waveBase())
            .as("on the tree this wave was cut from: the base plus the earlier waves' winners")
            .isEqualTo(run.progressCommit());
        assertThat(recorded.checks()).singleElement().asString().contains(CHECK);
        assertThat(recorded.userFacing())
            .as("the check says 'displayed', so a JUnit test was never going to prove all of it")
            .isTrue();
        assertThat(recorded.remainingProof())
            .as("and this project's contract never starts the application, so the run says so")
            .contains("NOTHING in this project's verification contract");
        assertThat(recorded.describe("Update client UI to display and assign book ratings"))
            .contains("already proved by the winners of the earlier waves")
            .contains("This is not a fault and it does not stop the run");

        // --- 3. the judge was told the tests do not separate anything --------------------------
        assertThat(judgeBrief)
            .as("the judge must not credit a candidate for a test run that was green before it")
            .contains("THE TESTS WERE GREEN BEFORE THIS CANDIDATE EXISTED")
            .contains("does it deliver what the task's instructions above actually ask for");

        // --- 4. the diff is what decides, because nothing else can -----------------------------
        List<CandidateSolution> ofUi = archived.stream()
            .filter(c -> c.taskId().equals(ui.id())).toList();
        assertThat(ofUi).hasSize(2);
        CandidateSolution didTheWork = ofUi.stream()
            .filter(c -> c.diffUnified().contains(REAL_UI)).findFirst().orElseThrow();
        CandidateSolution didNothing = ofUi.stream()
            .filter(c -> !c.diffUnified().contains(REAL_UI)).findFirst().orElseThrow();

        assertThat(didNothing.diffUnified())
            .as("its whole change is one comment line")
            .contains(COMMENT_ONLY);
        assertThat(didNothing.verification().acceptance().failed())
            .as("and the acceptance tests are green for it too — which is the whole problem")
            .isZero();
        assertThat(didNothing.judge().score())
            .as("so the code ceiling is what stops it: a diff with no code cannot score above "
                + "the unproven ceiling")
            .isLessThanOrEqualTo(0.4);
        assertThat(didNothing.judge().rationale()).contains("delivered nothing");
        assertThat(didTheWork.judge().score())
            .as("while the candidate that did the client work is not held back at all")
            .isGreaterThan(didNothing.judge().score());
        assertThat(didTheWork.state()).isEqualTo(CandidateState.SELECTED);
        assertThat(didNothing.state()).isNotEqualTo(CandidateState.SELECTED);
    }

    // --- the repository ---------------------------------------------------------------------

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
        // THE SHAPE OF THE REAL TEST: it proves the check through the SERVICE. It says nothing
        // about the client, because a JUnit test in the acceptance module cannot open a browser.
        write(RATING_TEST, "package swarm.accept;\n// needs: " + SERVICE
            + "\nclass BookRatingTest { void assignsRatingToBook() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
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
                       .append("\\" name=\\"assignsRatingToBook\\">");
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
     * The scripted model. Two workers run on the UI task and they are told apart by their persona,
     * which is the only per-worker text in a worker's prompt: one writes the client component, the
     * other writes a comment and calls it done — the candidate a green acceptance run cannot
     * distinguish from a real one.
     */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        ObjectMapper mapper = new ObjectMapper();
        if (conversation.contains(CLIENT)) {
            if (conversation.contains("wrote " + CLIENT)) {
                return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"client updated\"}");
            }
            String content = conversation.contains(WORKER_ONE)
                ? COMMENT_ONLY + "\n"
                : "class BookList {\n    String " + REAL_UI + "(int rating) { return \"*\"; }\n}\n";
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", CLIENT).put("content", content).toString());
        }
        if (conversation.contains(SERVICE)) {
            if (conversation.contains("wrote " + SERVICE)) {
                return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"service done\"}");
            }
            return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
                .put("path", SERVICE)
                .put("content", "class BookService { void rate(Book b, int r) { } }\n").toString());
        }
        if (conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"model done\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", mapper.createObjectNode()
            .put("path", BOOK).put("content", "class Book { int rating; }\n").toString());
    }

    private static Task task(String title, String instructions, Set<String> writeSet,
                             List<AcceptanceCriterion> criteria, List<String> authoredTestPaths,
                             int workers) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            criteria, "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(workers, false, 0.2, 0.8, List.of()), TaskState.READY);
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
