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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.VerifySpecLoader;
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
 * Section 104 (live local build, 2026-10-14): a run taken up at FINAL_INTEGRATION after a
 * restart merged its three tasks, called each "verified green" within a second, and ended its
 * story with "no verification of a merged tree was made in this build". No verdict had been
 * kept from before the restart and none was reused: the stage found no verification contract
 * it could read and took that for a pass, so nothing was built and no test was run.
 *
 * <p>Here the same stage is made twice for one run, with the process ended in between (the
 * store is closed and opened again, and the run is taken up by {@link RunResumer} as at
 * startup). While no contract can be read the run stops at the stage and says so; it is never
 * delivered or ended on a verification that was not made. Once the contract reads, the resumed
 * stage builds and tests the merged tree again and the story is delivered with its acceptance
 * results.
 *
 * <p>No model is called: the run starts at FINAL_INTEGRATION and the one client the engine's
 * constructor needs points at a {@link ScriptedLlm} nobody asks.
 */
@ModelCodeOnThisPc
class AResumedFinalIntegrationKeepsItsProofTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/BookRatingTest.java";
    private static final String RATING_FILE = "src/main/client/Rating.java";
    private static final String CHECK = "A rating can be assigned to a book";
    private static final String NOT_MADE = "no verification of a merged tree was made";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private UUID runId;
    private UUID projectId;
    private UUID storyId;
    private UUID graphId;

    @Test
    void aRunResumedAtFinalIntegrationAfterARestartVerifiesAgainAndDeliversWithItsResults()
            throws Exception {
        Run run = fixture();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "not used at this stage")) {
            // --- before the restart: no contract can be read ---------------------------------
            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                store(store, run);
                engine(llm, store).advance(run);
                awaitStopped(store);

                Run stopped = store.root().runs.get(runId);
                assertThat(stopped.state())
                    .as("not ended: the stage can be made again once the contract reads")
                    .isEqualTo(RunState.FINAL_INTEGRATION);
                assertThat(stopped.parkReason())
                    .contains("NOTHING WAS VERIFIED")
                    .contains("there is no " + VerifySpecLoader.SPEC_PATH)
                    .contains("1 check(s) are claimed")
                    .contains(RATING_TEST)
                    .doesNotContain(NOT_MADE);
                assertThat(stopped.acceptanceTestsPassed())
                    .as("nothing was run, so nothing is counted").isNull();
                Story story = store.getStory(storyId);
                assertThat(story.state()).isEqualTo(StoryState.BLOCKED);
                assertThat(story.waitingReason()).doesNotContain(NOT_MADE);
                assertThat(store.root().taskGraphs.get(graphId).tasks().get(0).state())
                    .as("a merge nothing verified does not make its task done")
                    .isNotEqualTo(TaskState.DONE);
            }

            // A contract that is there and does not parse is not "no contract" either.
            Files.createDirectories(repo.resolve(".swarmcoder"));
            Files.writeString(repo.resolve(VerifySpecLoader.SPEC_PATH), "compile: [\"unclosed\n");
            assertThat(VerifySpecLoader.load(repo)).isEmpty();
            assertThat(VerifySpecLoader.unreadable(repo)).isNotNull();
            assertThat(VerifySpecLoader.whyNone(repo, null)).contains("is there and cannot be read");

            // --- the restart: the contract reads now, and the run is taken up as at startup ---
            String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
            Files.writeString(repo.resolve(VerifySpecLoader.SPEC_PATH), """
                toolchain: gradle
                compile:
                  - "echo compile-ok"
                acceptance:
                  - "\\"%s\\" tools/Accept.java"
                timeoutSeconds: 60
                """.formatted(java));
            assertThat(VerifySpecLoader.unreadable(repo)).isNull();

            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                WorkflowEngine engine = engine(llm, store);
                List<RunResumer.Resumed> resumed =
                    new RunResumer(store, project -> engine).resumeAll();
                assertThat(resumed).extracting(RunResumer.Resumed::runId).containsExactly(runId);
                assertThat(resumed.get(0).state()).isEqualTo(RunState.FINAL_INTEGRATION);
                awaitEnded(store);

                Run ended = store.root().runs.get(runId);
                assertThat(ended.state()).isEqualTo(RunState.DELIVERED);
                assertThat(ended.acceptanceTestsPassed())
                    .as("the resumed stage ran the story's acceptance test on the merged tree")
                    .isEqualTo(1);
                Story story = store.getStory(storyId);
                assertThat(story.state()).isEqualTo(StoryState.REVIEW);
                assertThat(story.waitingReason()).isNull();
                assertThat(story.integrationCommit()).isNotNull();
                List<com.swarmcoder.domain.ChangeEvent> history =
                    store.changeHistory(projectId, storyId);
                assertThat(history.get(history.size() - 1).summary())
                    .as("the delivery names the test that passed")
                    .contains("swarm.accept.BookRatingTest#proves")
                    .doesNotContain(NOT_MADE);
                assertThat(store.root().taskGraphs.get(graphId).tasks().get(0).state())
                    .isEqualTo(TaskState.DONE);
                assertThat(store.root().decisions.values().stream()
                        .filter(d -> runId.equals(d.runId()))
                        .map(com.swarmcoder.domain.Decision::state))
                    .as("the question of the first attempt is not left open")
                    .doesNotContain(DecisionState.PENDING);
                assertThat(git("branch --list"))
                    .as("the attempt nothing verified is kept under its own name")
                    .contains("swarm/integration-attempt/" + runId + "/1");
            }
        }
    }

    // --- the fixture ----------------------------------------------------------------------------

    private WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("no agents at this stage");
        };
        return new WorkflowEngine(unusedRuntime, r -> r,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, new CloudRoles(null, null, null), new GitService(repo));
    }

    private Task task;
    private CandidateSolution winner;
    private Project project;
    private Brd brd;
    private Story story;

    /** One story with one check, one task with a chosen candidate, a run at FINAL_INTEGRATION. */
    private Run fixture() throws Exception {
        runId = UUID.randomUUID();
        git("init -q");
        write("README.md", "hello\n");
        // The stand-in acceptance stage of FinalIntegrationDecidesTheStoryOutcomeTest. The
        // contract that names it is NOT committed and not there yet.
        write("tools/Accept.java", ACCEPT_STAGE);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        String base = git("rev-parse HEAD").strip();

        git("checkout -q -b swarm/tests/" + runId);
        write(RATING_TEST, """
            package swarm.accept;
            // needs: %s
            class BookRatingTest { void proves() {} }
            """.formatted(RATING_FILE));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        String testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");

        UUID taskId = UUID.randomUUID();
        task = new Task(taskId, 1, "Build the rating", "build the rating",
            Set.of("src/main/client"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), CHECK,
                "swarm.accept.BookRatingTest#proves")),
            ACCEPT_DIR, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            TaskState.SELECTED);
        task.setAuthoredTestPaths(List.of(RATING_TEST));

        String winnerBranch = "swarm/" + taskId + "/0";
        git("checkout -q -b " + winnerBranch);
        write(RATING_FILE, "package client;\nclass Rating {}\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m chosen");
        git("checkout -q master");
        // As the live build's chosen candidates were: no report of their own.
        winner = new CandidateSolution(UUID.randomUUID(), taskId, 0, winnerBranch, null,
            "the chosen diff", null, null, null, CandidateState.SELECTED, null);

        project = new Project();
        projectId = UUID.randomUUID();
        project.setId(projectId);
        project.setName("bookshelf");
        project.setCreatedAt(Instant.now());

        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), CHECK,
            "swarm.accept.BookRatingTest#proves");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Book ratings",
            "A reader can rate a book", Priority.HIGH, RequirementStatus.ACTIVE, null);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        brd = new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());

        storyId = UUID.randomUUID();
        story = new Story(storyId, projectId, "S1", StoryKind.DELIVERY, "Rate a book", null,
            StoryState.RUNNING, new ArrayList<>(List.of(requirement.id())),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        graphId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.FINAL_INTEGRATION, projectId,
            storyId, null, graphId, null, Instant.now(), new RunReport(runId, "deliver S1"));
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(testsCommit);
        return run;
    }

    private void store(ArtifactStore store, Run run) throws Exception {
        store.saveProject(project);
        store.saveBrd(brd, "test", "fixture");
        store.saveStory(story);
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            store.root().candidateArchives.put(winner.id(), Lazy.Reference(winner));
            return null;
        }).get();
    }

    /** The run has stopped with a question, and what it wrote on the way is in the store. */
    private void awaitStopped(ArtifactStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            boolean ended = run != null
                && (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED);
            boolean asked = run != null && run.parkedAt() != null
                && store.root().decisions.values().stream().anyMatch(d ->
                    runId.equals(d.runId()) && d.state() == DecisionState.PENDING);
            if (ended || asked) {
                Thread.sleep(500); // the save that follows the question
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the run neither stopped nor ended");
    }

    private void awaitEnded(ArtifactStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        Run run = null;
        while (System.currentTimeMillis() < deadline) {
            run = store.root().runs.get(runId);
            if (run != null
                    && (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the resumed run never ended; it is at "
            + (run == null ? "nothing" : run.state() + ", stopped on: " + run.parkReason()));
    }

    /**
     * Fails each test source under the acceptance directory whose {@code // needs: <path>} names
     * a file that is not there, and writes the result as JUnit XML.
     */
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
