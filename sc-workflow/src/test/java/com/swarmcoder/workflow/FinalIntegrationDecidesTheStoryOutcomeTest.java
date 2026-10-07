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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CriterionStatus;
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
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FINAL_INTEGRATION, not a candidate's own report, decides the story's outcome.
 *
 * <p>Reproduces harness run 13 (2026-09-03), story "Assign a rating to a book": the first two UI
 * candidates for one task failed to compile because {@code Rating} did not exist yet; a repair
 * candidate created it, compiled, and was selected. Final integration then merged it, placed the
 * story's acceptance test on the integrated tree, and verified green — and the story was still sent
 * to BLOCKED, quoting the compile failure from the two dead candidates, because the criterion check
 * read the WINNING CANDIDATE's own pre-merge report (which never ran the acceptance test at all)
 * instead of the report FinalIntegrator itself had just produced by actually running it.
 *
 * <p>Two shapes of the same fixture: integration verifies green (the story must reach REVIEW, and
 * its outcome must name the commit and the test that passed — never the two dead candidates), and
 * integration comes up red on the real, merged tree (the story must go BLOCKED naming THAT failure,
 * not the two dead candidates either).
 *
 * <p>No paid model call anywhere: the LLM roles this run touches are never exercised (the run starts
 * already at FINAL_INTEGRATION), and the one {@link VllmClient} the engine's constructor requires is
 * pointed at a {@link ScriptedLlm} that is never actually asked anything.
 */
@ModelCodeOnThisPc
class FinalIntegrationDecidesTheStoryOutcomeTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String RATING_TEST = ACCEPT_DIR + "/BookRatingTest.java";
    /** What the two dead candidates could not find, and what the repair candidate creates. */
    private static final String RATING_FILE = "src/main/client/Rating.java";
    private static final String CHECK =
        "A rating can be assigned to a book and is displayed alongside the book's details";
    private static final String TASK_TITLE = "Implement client-side UI for rating a book";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    @Test
    void greenIntegrationDeliversTheStoryDespiteTheEarlierDeadCandidates() throws Exception {
        Fixture fx = build(true); // the winner really creates Rating.java

        awaitSettled(fx.store, fx.runId);

        Story story = fx.store.getStory(fx.storyId);
        assertThat(story.state())
            .as("the integrated tree verified green with the acceptance test passing — that is a "
                + "delivered story, whatever two earlier, since-fixed candidates did")
            .isEqualTo(StoryState.REVIEW);
        assertThat(story.integrationCommit())
            .as("the durable requirement→code link was recorded")
            .isNotNull();
        String shortSha = story.integrationCommit().length() > 8
            ? story.integrationCommit().substring(0, 8) : story.integrationCommit();

        List<com.swarmcoder.domain.ChangeEvent> history =
            fx.store.changeHistory(fx.projectId, fx.storyId);
        assertThat(history).isNotEmpty();
        String outcome = history.get(history.size() - 1).summary();
        assertThat(outcome)
            .as("the outcome names the commit and the test that actually passed")
            .contains(shortSha)
            .contains("swarm.accept.BookRatingTest#proves")
            .as("never the two dead candidates' failure")
            .doesNotContain("Every attempt at this story failed to compile");

        Task task = fx.store.root().taskGraphs.get(fx.graphId).tasks().get(0);
        assertThat(task.selectedCandidateId())
            .as("the winning candidate is the one recorded against the task")
            .isEqualTo(fx.winnerId);

        Run run = fx.store.root().runs.get(fx.runId);
        assertThat(run.state()).isEqualTo(RunState.DELIVERED);
    }

    @Test
    void redIntegrationBlocksTheStoryWithItsOwnFailureNotTheDeadCandidates() throws Exception {
        Fixture fx = build(false); // the winner's merge leaves Rating.java missing

        awaitSettled(fx.store, fx.runId);

        Story story = fx.store.getStory(fx.storyId);
        assertThat(story.state())
            .as("the ACTUAL, merged tree is what failed here — a real regression, not the two "
                + "earlier dead candidates")
            .isEqualTo(StoryState.BLOCKED);
        assertThat(story.waitingReason())
            .as("names the real integration failure")
            .contains("Final integration failed")
            .as("never the two dead candidates' stale compile failure")
            .doesNotContain("Every attempt at this story failed to compile");

        Run run = fx.store.root().runs.get(fx.runId);
        assertThat(run.state())
            .as("a red FINAL_INTEGRATION stays resumable, exactly as before this fix")
            .isEqualTo(RunState.FINAL_INTEGRATION);
        assertThat(run.parkReason()).contains("FINAL_INTEGRATION failed");
    }

    // --- the fixture ----------------------------------------------------------------------------

    private record Fixture(ArtifactStore store, UUID projectId, UUID storyId, UUID graphId,
                           UUID runId, UUID winnerId) {}

    /**
     * @param winnerFixesIt true: the repair candidate's branch really creates {@link #RATING_FILE},
     *                      so integration verifies green. False: it does not, so the merged tree's
     *                      acceptance test still fails and integration comes up red.
     */
    private Fixture build(boolean winnerFixesIt) throws Exception {
        UUID runId = UUID.randomUUID();
        String base = initRepo();

        // Stage 4's commit: the story's acceptance test, on the run's own ref.
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
        Task task = new Task(taskId, 1, TASK_TITLE, "build the rating UI",
            Set.of("src/main/client"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), CHECK,
                "swarm.accept.BookRatingTest#proves")),
            ACCEPT_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        task.setAuthoredTestPaths(List.of(RATING_TEST));

        // The repair candidate's winning branch.
        String winnerBranch = "swarm/" + taskId + "/2";
        git("checkout -q -b " + winnerBranch);
        if (winnerFixesIt) {
            write(RATING_FILE, "package client;\nclass Rating {}\n");
        } else {
            write("src/main/client/Placeholder.java", "package client;\nclass Placeholder {}\n");
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m repair");
        git("checkout -q master");

        // The repair candidate's OWN pre-merge verification: it survived selection, but never ran
        // the acceptance test itself — harness run 13's "acceptance EXECUTED 0 executed", the exact
        // shape that made the old code read the criterion as UNKNOWN even when integration (which
        // DID place and run the test) was green.
        VerificationReport winnerOwnReport = new VerificationReport(UUID.randomUUID(), true, true,
            new com.swarmcoder.domain.TestResults(0, 0, 0, 0, List.of())
                .withStageOutcome(com.swarmcoder.domain.TestStageOutcome.EXECUTED),
            null, null, null, null, "compiled", null);
        UUID winnerId = UUID.randomUUID();
        CandidateSolution winner = new CandidateSolution(winnerId, taskId, 2, winnerBranch, null,
            "the repair diff", winnerOwnReport, null, null, CandidateState.SELECTED, null);

        // The two dead candidates: independently failed to compile on the same missing name, which
        // is exactly the evidence MissingPieceDetector needs to produce "Every attempt at this
        // story failed to compile because \"Rating\" does not exist...".
        CandidateSolution dead1 = deadCandidate(taskId, 0);
        CandidateSolution dead2 = deadCandidate(taskId, 1);

        Project project = new Project();
        UUID projectId = UUID.randomUUID();
        project.setId(projectId);
        project.setName("bookshelf");
        project.setCreatedAt(Instant.now());

        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), CHECK,
            "swarm.accept.BookRatingTest#proves");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R4", "Book ratings",
            "A reader can rate a book", Priority.HIGH, RequirementStatus.ACTIVE, null);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));

        Story story = new Story(UUID.randomUUID(), projectId, "S1", StoryKind.DELIVERY,
            "Assign a rating to a book", null, StoryState.RUNNING,
            new ArrayList<>(List.of(requirement.id())), new ArrayList<>(List.of(criterion.id())),
            null, 0, StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null,
            null, Instant.now(), Instant.now());

        UUID graphId = UUID.randomUUID();
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());

        ArtifactStore store = new ArtifactStore(storeDir);
        store.saveProject(project);
        store.saveBrd(new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now()),
            "test", "fixture");
        store.saveStory(story);
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            store.root().candidateArchives.put(winnerId, Lazy.Reference(winner));
            store.root().candidateArchives.put(dead1.id(), Lazy.Reference(dead1));
            store.root().candidateArchives.put(dead2.id(), Lazy.Reference(dead2));
            return null;
        }).get();

        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.FINAL_INTEGRATION,
            projectId, story.id(), null, graphId, null, Instant.now(),
            new RunReport(runId, "deliver S1"));
        run.setBaseCommit(base);
        run.setAcceptanceTestsCommit(testsCommit);
        // Not pre-persisted into store.root().runs: WorkflowEngine.advanceSync() saves the run it
        // is handed before doing anything else, exactly as DeliveryHandbackTest relies on.

        ScriptedLlm llm = new ScriptedLlm(conversation -> "not used at this stage");
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("no agents at this stage");
        };
        VllmClient client = new VllmClient(llm.baseUrl(), "", "test-model", true);
        WorkflowEngine engine = new WorkflowEngine(unusedRuntime, r -> r, client, store,
            new CloudGate(0, null), repo, new CloudRoles(null, null, null), new GitService(repo));
        engine.advance(run);

        return new Fixture(store, projectId, story.id(), graphId, runId, winnerId);
    }

    private static CandidateSolution deadCandidate(UUID taskId, int index) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false,
            null, null, null, null, null,
            "error: cannot find symbol\n  symbol:   class Rating\n  location: class RatingPanel\n",
            null);
        return new CandidateSolution(UUID.randomUUID(), taskId, index,
            "swarm/" + taskId + "/" + index, null, "a failed diff", report, null, null,
            CandidateState.KILLED, null);
    }

    private static void awaitSettled(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && (run.state() == RunState.DELIVERED
                    || run.state() == RunState.ABORTED)) {
                return;
            }
            if (store.root().decisions.values().stream().anyMatch(d -> runId.equals(d.runId()))) {
                return; // parked — the assertions on run/story state say what happened
            }
            Thread.sleep(50);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("run never settled; last state: "
            + (last == null ? "never persisted" : last.state()));
    }

    // --- the repository ---------------------------------------------------------------------------

    private String initRepo() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.createDirectories(repo.resolve("tools"));
        Files.writeString(repo.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repo.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java"
            timeoutSeconds: 60
            """.formatted(java));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        return git("rev-parse HEAD").strip();
    }

    /** Same stand-in acceptance stage as {@code TestsLiveOnTheRunsOwnRefTest}: scans every test
     * source under {@code src/test/java/swarm/accept} for a {@code // needs: <path>} marker and
     * fails that test in the JUnit XML it writes when the named path does not exist. */
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
