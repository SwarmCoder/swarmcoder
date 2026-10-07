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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.console.AdHocStory;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.ContractProbe;
import com.swarmcoder.verify.ToolchainDetector;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The on-ramp: point SwarmCoder at a repository it has never seen, ask for a change in plain
 * English, and get a verified change out — with no requirements document, no BRD and no criteria.
 *
 * <p><b>What this is proving.</b> Everything needed for a freeform run already existed — an ad-hoc
 * ENABLER story that answers to no requirement, an architect told it may build unscoped work, a
 * planner that skips the coverage invariant when there is no slice to cover. One thing did not:
 * <b>nothing knew how to build an arbitrary project.</b> Without {@code .swarmcoder/verify.yaml}
 * verification is skipped and every candidate comes back UNVERIFIED, and at that point the swarm is
 * a coin toss — choosing between N attempts <em>is</em> testing them.
 *
 * <p><b>Neither test uses a hand-written contract.</b> The demo repository ships one; it is deleted
 * before either test starts, so the contract under test is the one {@link ToolchainDetector}
 * produced from the tree, and if detection regresses these go red rather than quietly passing on a
 * file somebody checked in.
 *
 * <p><b>What was NOT weakened to make this pass.</b> The red-check still requires an acceptance test
 * to fail before any worker starts. {@code AgreementGate} still refuses to agree a requirement
 * without a check naming a test — an ad-hoc story never touches it, because it agrees no
 * requirement. {@code TaskGraphValidator}'s coverage invariant still rejects a graph that leaves a
 * criterion unclaimed — an empty slice has none to leave. {@code RunPersister} still refuses to
 * record DELIVERED without a task graph — an ad-hoc run plans one like any other. Each was checked
 * against the empty-criteria case and each was already right; see
 * {@code docs/DEVELOPER_CORRECTIONS.md} §28.
 */
@ModelCodeOnThisPc
class ExistingProjectOnrampTest {

    @TempDir
    Path work;

    /** Wall-clock cost of each step, printed at the end — the number the whole feature is about. */
    private final Map<String, Duration> timings = new LinkedHashMap<>();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
        if (!timings.isEmpty()) {
            System.out.println("[ONRAMP] ---- how long each step took ----");
            long total = 0;
            for (var entry : timings.entrySet()) {
                System.out.printf("[ONRAMP] %-42s %6.1f s%n", entry.getKey(),
                    entry.getValue().toMillis() / 1000.0);
                total += entry.getValue().toMillis();
            }
            System.out.printf("[ONRAMP] %-42s %6.1f s%n", "TOTAL", total / 1000.0);
        }
    }

    /**
     * Step one of the on-ramp, on its own: a repository with no contract gets one that really does
     * build it, and the swarm can load it from the trusted root exactly as it will at run time.
     *
     * <p>Runs a real Maven build, which is the point — a proposed contract that has not been
     * executed is a guess, and a guessed command that exits 0 for the wrong reason certifies a
     * candidate nobody checked.
     */
    @Test
    void aRepositoryWithNoContractGetsOneThatActuallyBuildsIt() throws Exception {
        Path repo = freshTargetRepo();

        Instant t0 = Instant.now();
        ToolchainDetector.Detection detection = ToolchainDetector.detect(repo);
        timings.put("detect the toolchain", Duration.between(t0, Instant.now()));

        assertThat(detection.recognised()).isTrue();
        assertThat(detection.toolchain()).isEqualTo("maven");
        System.out.println("[ONRAMP] detected: " + detection.summary());
        detection.evidence().forEach(e -> System.out.println("[ONRAMP]   from: " + e));
        detection.warnings().forEach(w -> System.out.println("[ONRAMP]   decide: " + w));

        Instant t1 = Instant.now();
        ContractProbe.Result probe = ContractProbe.probeCompile(repo, detection.proposed(), 600);
        timings.put("run the proposed compile command once", Duration.between(t1, Instant.now()));
        System.out.println("[ONRAMP] probe: " + probe.describe());
        assertThat(probe.compiles())
            .as("the proposed compile command must actually build this project:\n" + probe.logTail())
            .isTrue();

        writeContract(repo, detection);

        // The boundary the swarm actually asks across: the contract comes from the OPERATOR'S tree,
        // never the worker's worktree, because a worker that could edit it could certify itself
        // green (§13.1).
        Path pretendWorktree = Files.createDirectories(work.resolve("pretend-worktree"));
        VerifySpec loaded = VerifySpecLoader.loadTrusted(repo, pretendWorktree).orElseThrow();
        assertThat(loaded.compile()).isEqualTo(detection.proposed().compile());
        assertThat(loaded.existing()).isNotEmpty();
    }

    /**
     * The whole thing, against a live model: repository in, plain-English goal, verified change out.
     *
     * <p>Point it at any repository with {@code -Dswarmcoder.onramp.repo=<path>} — a throwaway copy,
     * never an original, because a run writes commits, branches and worktrees into whatever it is
     * given.
     */
    @Test
    @RunsWhen(Need.LIVE_MODEL)
    void plainEnglishAgainstAnExistingRepositoryProducesAVerifiedChange() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String modelName = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        String goal = System.getProperty("swarmcoder.onramp.goal",
            "Add a percentage method to the Calculator: percent(value, percent) returns what that "
            + "percentage of the value is, so percent(200, 15) is 30.");

        Path repo = freshTargetRepo();
        System.out.println("[ONRAMP] repository: " + repo);
        System.out.println("[ONRAMP] goal: " + goal);
        String baseCommit = headCommit(repo);

        // --- 1. the on-ramp itself ------------------------------------------------------------
        Instant t0 = Instant.now();
        ToolchainDetector.Detection detection = ToolchainDetector.detect(repo);
        ContractProbe.Result probe = ContractProbe.probeCompile(repo, detection.proposed(), 600);
        assertThat(probe.compiles()).as(probe.logTail()).isTrue();
        writeContract(repo, detection);
        timings.put("on-ramp: detect, probe, write the contract",
            Duration.between(t0, Instant.now()));
        System.out.println("[ONRAMP] " + probe.describe());

        // --- 2. one plain-English goal, no requirements anywhere -------------------------------
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            VllmClient client = new VllmClient(baseUrl, "", modelName, true);
            CloudGate cloudGate = new CloudGate(50_000_000, null);
            GitService git = new GitService(repo);
            SwarmPolicy policy = new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff"));

            SwarmEngineImpl swarm = new SwarmEngineImpl(client, store,
                new InferenceScheduler(16, 1024L * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile(modelName,
                    new AgentRuntime.ModelEndpoint(baseUrl, "", modelName, 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                git, content -> null, cloudGate, () -> null);

            WorkflowEngine engine = new WorkflowEngine(new KoogAgentRuntime(), swarm, client, store,
                cloudGate, repo,
                new CloudRoles(new ArchitectClient(client, cloudGate, policy),
                    new DesignReviewerClient(client, cloudGate),
                    new TestAuthorClient(client, cloudGate)),
                git);
            engine.setEventLogger(message -> System.out.println("[ONRAMP] " + message));

            List<UUID> started = new ArrayList<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (g, k) -> startRun(engine, store, projectId, g, k, null, started),
                runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                .withStoryRuns((g, k, storyId) ->
                    startRun(engine, store, projectId, g, k, storyId, started)));

            // Exactly what typing a goal into the chat does — the freeform escape hatch, which is
            // the ONLY route in on a project with no requirement graph.
            Instant t1 = Instant.now();
            AdHocStory.Started adHoc = AdHocStory.start(ConsoleContext.get(), projectId,
                WorkflowKind.ENHANCEMENT.name(), goal);
            assertThat(adHoc).as("the freeform path must mint a work item and start a run").isNotNull();
            System.out.println("[ONRAMP] work item " + adHoc.story().key() + " — "
                + adHoc.story().title());

            // The story exists, has NO criteria, and is bound to the run from the first instant.
            Story story = store.getStory(adHoc.story().id());
            assertThat(story.origin()).isEqualTo(StoryOrigin.AD_HOC);
            assertThat(story.criterionIds()).isEmpty();
            assertThat(store.root().runs.get(adHoc.runId()).storyId())
                .as("the run must carry its story from the instant the engine gets it — attaching "
                    + "it afterwards is written to an object nobody reads again")
                .isEqualTo(story.id());

            Run finished = awaitRun(store, adHoc.runId(), Duration.ofMinutes(40));
            timings.put("the run itself, start to finish", Duration.between(t1, Instant.now()));
            System.out.println("[ONRAMP] run ended " + finished.state());

            dump(store, finished);

            assertThat(finished.state())
                .as("an unscoped run must reach DELIVERED — it is the only route in for a project "
                    + "with no requirement graph")
                .isEqualTo(RunState.DELIVERED);
            assertThat(finished.taskGraphId())
                .as("RunPersister refuses DELIVERED without a task graph, and it is right to")
                .isNotNull();

            // The point of the whole feature: the winner was CHECKED, not guessed at.
            List<CandidateSolution> verified = verifiedWinners(store, finished);
            assertThat(verified)
                .as("every selected candidate must carry a verification report — without one the "
                    + "swarm chose between N unchecked guesses")
                .isNotEmpty();
            assertThat(verified).allSatisfy(c -> {
                assertThat(c.verification()).isNotNull();
                assertThat(c.verification().compiles()).isTrue();
            });

            assertThat(headCommit(repo))
                .as("a real change must have landed in the repository")
                .isNotEqualTo(baseCommit);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * A throwaway copy of a real repository, with any contract it shipped REMOVED — the on-ramp has
     * to produce one, or these tests are only proving that a checked-in file can be read.
     */
    private Path freshTargetRepo() throws Exception {
        String named = System.getProperty("swarmcoder.onramp.repo");
        Path repo;
        if (named != null && !named.isBlank()) {
            repo = Path.of(named).toAbsolutePath().normalize();
            System.out.println("[ONRAMP] using the repository named by -Dswarmcoder.onramp.repo");
        } else {
            repo = DemoRepo.create(work.resolve("target-repo"));
            System.out.println("[ONRAMP] " + DemoRepo.describeSource());
        }
        Path contract = repo.resolve(VerifySpecLoader.SPEC_PATH);
        if (Files.exists(contract)) {
            Files.delete(contract);
            new GitService(repo).commitAll(repo,
                "Remove the checked-in verification contract so the on-ramp has to detect one");
            System.out.println("[ONRAMP] removed the contract this repository shipped");
        }
        return repo;
    }

    private static void writeContract(Path repo, ToolchainDetector.Detection detection)
            throws Exception {
        Path contract = repo.resolve(VerifySpecLoader.SPEC_PATH);
        Files.createDirectories(contract.getParent());
        Files.writeString(contract, ToolchainDetector.render(detection));
        // Committed, because every worker gets a worktree of this repository at some commit, and
        // an uncommitted file is not in one.
        new GitService(repo).commitAll(repo, "Verification contract detected by the on-ramp");
    }

    private static UUID startRun(WorkflowEngine engine, ArtifactStore store, UUID projectId,
                                 String goal, String kind, UUID storyId, List<UUID> started) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE, projectId, storyId,
            null, null, null, Instant.now(), new RunReport(runId, goal));
        // Persisted here so a caller can read the run back the instant it has the id — the engine
        // saves it too, on its own thread, and the assertion above must not race that.
        try {
            store.append(() -> {
                store.root().runs.put(runId, run);
                return null;
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not persist the run", e);
        }
        started.add(runId);
        engine.advance(run);
        return runId;
    }

    private static Run awaitRun(ArtifactStore store, UUID runId, Duration limit) throws Exception {
        Instant deadline = Instant.now().plus(limit);
        RunState last = null;
        while (Instant.now().isBefore(deadline)) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.state() != last) {
                last = run.state();
                System.out.println("[ONRAMP] state -> " + last + "  (+"
                    + Duration.between(run.startedAt(), Instant.now()).toSeconds() + "s)");
            }
            if (run != null && (run.state() == RunState.DELIVERED
                    || run.state() == RunState.ABORTED)) {
                return run;
            }
            Thread.sleep(1000);
        }
        Run run = store.root().runs.get(runId);
        throw new AssertionError("the run was still in " + (run == null ? "(missing)" : run.state())
            + " after " + limit.toMinutes() + " minutes");
    }

    private static List<CandidateSolution> verifiedWinners(ArtifactStore store, Run run) {
        List<CandidateSolution> winners = new ArrayList<>();
        TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            return winners;
        }
        for (Task task : graph.tasks()) {
            if (task.selectedCandidateId() == null) {
                continue;
            }
            for (CandidateSolution candidate : candidatesOf(store, task.id())) {
                if (task.selectedCandidateId().equals(candidate.id())) {
                    winners.add(candidate);
                }
            }
        }
        return winners;
    }

    private static void dump(ArtifactStore store, Run run) {
        TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            System.out.println("[ONRAMP] no task graph");
            return;
        }
        for (Task task : graph.tasks()) {
            System.out.println("[ONRAMP] task '" + task.title() + "' state=" + task.state()
                + " criteria=" + (task.criteria() == null ? 0 : task.criteria().size())
                + " commit=" + task.commitSha());
            for (CandidateSolution candidate : candidatesOf(store, task.id())) {
                var report = candidate.verification();
                System.out.println("[ONRAMP]   candidate " + candidate.workerIndex()
                    + " " + candidate.state()
                    + (report == null ? " UNVERIFIED (no report)"
                        : " compiles=" + report.compiles()
                          + " acceptance=" + describe(report.acceptance())
                          + " existing=" + describe(report.existing())));
            }
        }
    }

    /**
     * Every candidate stored for a task. {@code candidateArchives} is keyed by CANDIDATE id and its
     * values are Lazy, so the only way to ask "which belong to this task" is to walk it.
     */
    private static List<CandidateSolution> candidatesOf(ArtifactStore store, UUID taskId) {
        List<CandidateSolution> found = new ArrayList<>();
        for (org.eclipse.serializer.reference.Lazy<Object> lazy
                : store.root().candidateArchives.values()) {
            if (org.eclipse.serializer.reference.Lazy.get(lazy)
                    instanceof CandidateSolution candidate
                    && taskId.equals(candidate.taskId())) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static String headCommit(Path repo) throws Exception {
        List<String> argv = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git rev-parse HEAD")
            : List.of("sh", "-c", "git rev-parse HEAD");
        Process process = new ProcessBuilder(argv).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git rev-parse HEAD failed: " + out);
        }
        return out.strip();
    }

    private static String describe(com.swarmcoder.domain.TestResults results) {
        return results == null ? "(none)"
            : results.passed() + "p/" + results.failed() + "f/" + results.errored() + "e";
    }
}
