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

import com.swarmcoder.domain.AcceptanceCriterion;
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
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
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
 * <b>A check the existing code already satisfies is recorded as already proved, not parked
 * on</b> (owner decision after the audit of 2026-10-02).
 *
 * <p>The real red check of TEST_AUTHORING, on a real repository, with a stand-in acceptance
 * stage. The start tree already holds {@code com.x.Book}; one task's test asserts on it and
 * passes there. Until now that parked the run ("not red"). Now it is written onto the task with
 * the tests and the commit, the task - which nothing else builds on - leaves the plan, its test
 * stays in the run, and the task that still has something to build goes on. A green test that
 * asserts nothing, or touches no project code, still stops the run as it always did.
 */
@ModelCodeOnThisPc
class ACheckTheCodeAlreadySatisfiesIsRecordedNotParkedOnTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String BOOK = "src/main/java/com/x/Book.java";
    private static final String SHELF = "src/main/java/com/x/Shelf.java";
    private static final String TITLE_TEST = ACCEPT_DIR + "/BookTitleTest.java";
    private static final String SHELF_TEST = ACCEPT_DIR + "/ShelfTest.java";

    /** Asserts on a class the start tree already has. */
    private static final String REAL_TEST = "package swarm.accept;\n"
        + "import com.x.Book;\n"
        + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
        + "// needs: " + BOOK + "\n"
        + "class BookTitleTest {\n"
        + "    void assignsRatingToBook() { assertEquals(\"Emma\", new Book().title()); }\n"
        + "}\n";

    /** Passes on any tree: no assertion, no project code. */
    private static final String VACUOUS_TEST = "package swarm.accept;\n"
        + "// needs: " + BOOK + "\n"
        + "class BookTitleTest {\n"
        + "    void assignsRatingToBook() { String title = \"Emma\"; }\n"
        + "}\n";

    private static final String RED_TEST = "package swarm.accept;\n"
        + "import com.x.Shelf;\n"
        + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
        + "// needs: " + SHELF + "\n"
        + "class ShelfTest {\n"
        + "    void assignsRatingToBook() { assertEquals(0, new Shelf().size()); }\n"
        + "}\n";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void theSatisfiedCheckIsRecordedItsTaskLeavesThePlanAndTheRunGoesOn() throws Exception {
        Task titled = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        Task shelf = claiming("Add the shelf", SHELF, SHELF_TEST, "Books are kept on a shelf");
        Outcome outcome = redCheck(REAL_TEST, List.of(titled, shelf), List.of());

        assertThat(outcome.park).as("the run is not parked").isNull();
        assertThat(outcome.plan.tasks()).extracting(Task::title)
            .as("the task with nothing left to build is dropped; the red one stays")
            .containsExactly("Add the shelf");
        assertThat(outcome.run.alreadySatisfiedTests())
            .as("its test stays in the run, to be run on the merged tree")
            .containsExactly(TITLE_TEST);
        ChecksAlreadyProved record = outcome.recordOf(titled);
        assertThat(record).isNotNull();
        assertThat(record.beforeTheRun()).isTrue();
        assertThat(record.nothingDeliveredYet())
            .as("not the flag that fails a candidate for surviving on a self-measuring test")
            .isFalse();
        assertThat(record.tests()).containsExactly("swarm.accept.BookTitleTest#assignsRatingToBook");
        assertThat(record.waveBase()).as("the commit the tests passed on").isEqualTo(testsCommit);
        assertThat(record.checks()).singleElement().asString().contains("A book has a title");
        assertThat(record.describe(titled.title()))
            .contains("already satisfied by the code this run started from")
            .contains(testsCommit);
        assertThat(outcome.recordOf(shelf)).isNull();
    }

    @Test
    void aTaskSomethingElseBuildsOnStaysInThePlanWithTheRecord() throws Exception {
        Task titled = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        Task shelf = claiming("Add the shelf", SHELF, SHELF_TEST, "Books are kept on a shelf");
        Outcome outcome = redCheck(REAL_TEST, List.of(titled, shelf),
            List.of(new TaskEdge(titled.id(), shelf.id())));

        assertThat(outcome.park).isNull();
        assertThat(outcome.plan.tasks()).hasSize(2);
        assertThat(outcome.run.alreadySatisfiedTests()).isEmpty();
        assertThat(outcome.recordOf(titled).beforeTheRun()).isTrue();
    }

    @Test
    void aGreenTestThatMeasuresNothingStillStopsTheRun() throws Exception {
        Task titled = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        Task shelf = claiming("Add the shelf", SHELF, SHELF_TEST, "Books are kept on a shelf");
        Outcome outcome = redCheck(VACUOUS_TEST, List.of(titled, shelf), List.of());

        assertThat(outcome.park)
            .contains("Show the book's title")
            .contains("not recorded as a check the existing code already satisfies")
            .contains("makes no assertion");
        assertThat(outcome.plan.tasks()).hasSize(2);
        assertThat(outcome.recordOf(titled)).isNull();
    }

    /**
     * Owner decision, 2026-10-03: a story whose every check the existing code already satisfies
     * is delivered without building anything. It used to park here.
     */
    @Test
    void whenNothingIsLeftToBuildTheUnchangedTreeIsVerifiedOnceAndTheRunGoesOnToDelivery()
            throws Exception {
        Task titled = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        Outcome outcome = redCheck(REAL_TEST, List.of(titled), List.of(),
            (store, run) -> new FinalIntegrator(new GitService(repoDir), store).integrate(run));

        assertThat(outcome.park).as("the run is not parked").isNull();
        assertThat(outcome.run.nothingToBuild()).isTrue();
        assertThat(outcome.run.alreadySatisfiedTests()).containsExactly(TITLE_TEST);
        assertThat(outcome.plan.tasks()).as("the plan is left as it was").hasSize(1);

        FinalIntegrator.Result integration = outcome.integration;
        assertThat(integration.ok()).as(String.valueOf(integration.failure())).isTrue();
        assertThat(integration.integrationBranch()).isEqualTo("swarm/integration/" + outcome.run.id());
        assertThat(integration.verification())
            .as("the criteria are stamped from a real run of the tests").isNotNull();
        assertThat(integration.verification().acceptance().passed()).isEqualTo(1);
        assertThat(integration.verification().acceptance().getPassedIds())
            .containsExactly("swarm.accept.BookTitleTest#assignsRatingToBook");
        assertThat(outcome.plan.tasks().get(0).state()).isEqualTo(TaskState.DONE);
        assertThat(git("rev-parse swarm/integration/" + outcome.run.id()).strip())
            .as("nothing was merged: the branch is the run's tests commit").isEqualTo(testsCommit);
    }

    @Test
    void whenTheOneVerificationOfTheUnchangedTreeFailsTheRunParksOnThatEvidence() throws Exception {
        Task titled = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        Outcome outcome = redCheck(REAL_TEST, List.of(titled), List.of(), (store, run) -> {
            // A test the unchanged tree does not pass is due as well.
            run.setAlreadySatisfiedTests(List.of(TITLE_TEST, SHELF_TEST));
            return new FinalIntegrator(new GitService(repoDir), store).integrate(run);
        });

        assertThat(outcome.integration.ok()).isFalse();
        assertThat(outcome.integration.failure())
            .contains("Nothing was built for this run")
            .contains("did NOT pass")
            .contains(testsCommit);
        assertThat(outcome.integration.verification()).isNotNull();
        assertThat(outcome.integration.verification().acceptance().failed()).isEqualTo(1);
        assertThat(outcome.plan.tasks().get(0).state()).isNotEqualTo(TaskState.DONE);
    }

    // --- the guard, on its own ---------------------------------------------------------------

    @Test
    void theGuardAcceptsOnlyATestThatAssertsOnTheProjectsOwnCode() throws Exception {
        Files.createDirectories(repoDir.resolve("src/main/java/com/x"));
        Files.writeString(repoDir.resolve(BOOK),
            "package com.x;\npublic class Book { public String title() { return \"Emma\"; } }\n");
        Task task = claiming("Show the book's title", BOOK, TITLE_TEST, "A book has a title");
        var passed = new com.swarmcoder.domain.TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.BookTitleTest#assignsRatingToBook"), List.of(), false)
            .withStageOutcome(com.swarmcoder.domain.TestStageOutcome.EXECUTED);

        write(TITLE_TEST, REAL_TEST);
        assertThat(AlreadySatisfied.assess(repoDir, null, task, List.of(task), passed).accepted())
            .isTrue();

        write(TITLE_TEST, REAL_TEST.replace("import com.x.Book;\n", "")
            .replace("new Book().title()", "\"Emma\""));
        assertThat(AlreadySatisfied.assess(repoDir, null, task, List.of(task), passed).refusal())
            .contains("names no type the project declares");

        write(TITLE_TEST, REAL_TEST);
        var otherClassRan = new com.swarmcoder.domain.TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.SomethingElseTest#runs"), List.of(), false)
            .withStageOutcome(com.swarmcoder.domain.TestStageOutcome.EXECUTED);
        assertThat(AlreadySatisfied.assess(repoDir, null, task, List.of(task), otherClassRan)
            .refusal()).contains("nothing shows it was executed");
    }

    // --- the stage under test ---------------------------------------------------------------

    private record Outcome(String park, TaskGraph plan, Run run, ArtifactStore closedStore,
                           java.util.Map<UUID, ChecksAlreadyProved> records,
                           FinalIntegrator.Result integration) {
        ChecksAlreadyProved recordOf(Task task) {
            return records.get(task.id());
        }
    }

    private Outcome redCheck(String titleTest, List<Task> tasks, List<TaskEdge> edges)
            throws Exception {
        return redCheck(titleTest, tasks, edges, null);
    }

    private Outcome redCheck(String titleTest, List<Task> tasks, List<TaskEdge> edges,
                             java.util.function.BiFunction<ArtifactStore, Run,
                                 FinalIntegrator.Result> thenIntegrate) throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId, titleTest);
        try (FakeVllm fake = new FakeVllm(c -> FakeVllm.Reply.text("{}"));
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, tasks, edges);
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.TEST_AUTHORING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, run);
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
            GreenfieldWorkflow workflow = new GreenfieldWorkflow(new KoogAgentRuntime(), engine,
                client, store, new RunPersister(store), new CloudGate(1_000_000, null), repoDir,
                new CloudRoles(null, null, null), new GitService(repoDir));

            String park = workflow.redCheckFailure(run, StoryScope.resolve(null, null, ""));

            java.util.Map<UUID, ChecksAlreadyProved> records = new java.util.HashMap<>();
            for (Task task : tasks) {
                Task stored = store.getTask(task.id());
                if (stored != null && stored.checksAlreadyProved() != null) {
                    records.put(task.id(), stored.checksAlreadyProved());
                }
            }
            FinalIntegrator.Result integration = thenIntegrate == null || park != null ? null
                : thenIntegrate.apply(store, run);
            return new Outcome(park, store.root().taskGraphs.get(graphId), run, null, records,
                integration);
        }
    }

    private static Task claiming(String title, String writes, String testPath, String check) {
        Task task = new Task(UUID.randomUUID(), 1, title, "Create " + writes + ".",
            Set.of(writes), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), check,
                "swarm.accept." + testPath.substring(testPath.lastIndexOf('/') + 1)
                    .replace(".java", "") + "#assignsRatingToBook")),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of(testPath));
        return task;
    }

    private void initRepo(UUID runId, String titleTest) throws Exception {
        git("init -q");
        write(BOOK, "package com.x;\npublic class Book { public String title() { return \"Emma\"; } }\n");
        write("tools/Accept.java", ACCEPT_STAGE);
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        write(".swarmcoder/verify.yaml", """
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
        write(TITLE_TEST, titleTest);
        write(SHELF_TEST, RED_TEST);
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
