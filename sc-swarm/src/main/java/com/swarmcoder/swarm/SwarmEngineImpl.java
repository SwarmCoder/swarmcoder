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

import com.swarmcoder.domain.*;
import com.swarmcoder.git.AcceptanceOverlay;
import com.swarmcoder.git.GitService;
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.EndpointReachability;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.runtime.TestRepairNeeded;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.AcceptanceCompileErrors;
import com.swarmcoder.verify.BaseTreeChecks;
import com.swarmcoder.verify.BlobSink;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.CommandPipelineVerifier;
import com.swarmcoder.verify.GuidelineCheckRunner;
import com.swarmcoder.verify.LocalProcessExecTarget;
import com.swarmcoder.verify.UnifiedDiffPaths;
import com.swarmcoder.verify.Verdicts;
import com.swarmcoder.verify.VerificationBaseline;
import com.swarmcoder.verify.Verifier;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.sandbox.MavenRepoCache;
import com.swarmcoder.syntax.Language;
import com.swarmcoder.syntax.TreeSitterSyntaxService;
import com.swarmcoder.verify.ExecTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.eclipse.serializer.reference.Lazy;

/**
 * Task execution pipeline. The stage order is fixed by rule R2 (DEVELOPER_CORRECTIONS.md):
 * dispatch → VERIFY ALL → (repair round on zero survivors, spec §11.5) → cluster survivors
 * → judge representatives → select. A candidate that has not passed verification never
 * reaches the judge. Tasks run in TaskGraph topological waves so dependents never execute
 * before their prerequisites.
 */
public class SwarmEngineImpl implements SwarmEngine {

    private static final Logger log = LoggerFactory.getLogger(SwarmEngineImpl.class);

    /** Repair shape (spec §11.5): up to 2 seeds × 2 workers, one round only — opinionated. */
    private static final int REPAIR_SEEDS = 2;
    private static final int REPAIR_WORKERS_PER_SEED = 2;

    private final VllmClient judgeClient;
    private final ArtifactStore artifactStore;
    private final InferenceScheduler inferenceScheduler;
    private final GitService gitService;
    private final SwarmDispatcher dispatcher;
    private final Verifier verifier;
    private final CloudGate cloudGate;
    /** Rendered ACTIVE guidelines for the shared prefix (spec §5.3); null = none. */
    private final Supplier<String> guidelinesProvider;
    /**
     * The house rules that declared a command proving they were obeyed (author decision, §21).
     *
     * <p>Resolved from the OPERATOR'S checkout by whoever supplies it, never from a candidate's
     * worktree — the same boundary {@code VerifySpecLoader.loadTrusted} draws, and for the same
     * reason (§13.1). Set by the wiring, defaulting to none so every existing construction of this
     * engine behaves exactly as it did.
     */
    private Supplier<List<GuidelineCheck>> guidelineChecksProvider = List::of;
    /**
     * The same ACTIVE rules the workers were given, as objects rather than as one rendered string.
     *
     * <p>The judge's window holds a fraction of a real project's rulebook, so something has to
     * decide which rules go into it, and the text alone cannot be that something: it arrives
     * already concatenated and the only available cut is the first n characters. Handing the judge
     * the resolved rules lets {@link JudgeRules} pick the ones that bear on the diff in front of it
     * and NAME the rest. Defaults to none, so an engine nobody wired this into judges exactly as it
     * did before.
     */
    private Supplier<List<LearnedGuideline>> activeRulesProvider = List::of;
    /** The rules a worker is sent for the paths its task may write; null sends every rule. */
    private java.util.function.Function<java.util.Collection<String>, RuleScope.Briefing>
        workerRules;
    /**
     * How an answer to a question about a rule is remembered for the project (harness runs 53 and
     * 55, 2026-10-01 — see {@link RuleQuestions}). Defaults to none, so an engine nobody wired
     * this into can raise the question but never change a rule.
     */
    private volatile RuleQuestions.Amendments ruleAmendments = RuleQuestions.Amendments.NONE;

    /**
     * "run|path" → the task that was given that earlier task's file to repair, for the wave now
     * running. Two tasks of one wave repairing the same file would be two winners editing one
     * file, which will not merge; the first to ask gets it. Cleared when the wave ends.
     */
    private final Map<String, UUID> siblingRepairClaims = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tasks being finished outside a wave, where there is no base to build them again from. */
    private final Set<UUID> noSiblingRepair = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * What happens when a model's judgement objects at its limit — a question about a rule, or
     * every candidate judged to break one. The product asks a person. See {@link OpinionPolicy}.
     */
    private final EffectiveOpinionPolicy opinionPolicy = new EffectiveOpinionPolicy();
    /** The runs being executed right now, so a carried warning lands on the run's own record. */
    private final Map<UUID, Run> runsInFlight = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Per-candidate Docker sandbox (spec §8); null = verify locally (the default). When set,
     * each candidate's verification runs inside a hardened container with the worktree
     * bind-mounted, driven via {@link com.swarmcoder.verify.SandboxExecTarget}.
     */
    private volatile DockerSandboxManager sandbox;
    private volatile java.util.Map<String, Path> referenceRoots = java.util.Map.of();
    private final com.swarmcoder.verify.BuildBoxes buildBoxes =
        new com.swarmcoder.verify.BuildBoxes(() -> this.sandbox, () -> this.referenceRoots);

    /**
     * One Maven-cache warm per orchestrator process. Set before the work starts, not after, so two
     * runs beginning together do not both pay for the same discovery build.
     */
    private volatile boolean mavenCacheWarmed;
    /** The worker endpoints, kept so the pre-flight check knows what to probe. */
    private final ModelProfileRegistry profiles;
    /**
     * The shared reachability probe. Its own TTL cache is what keeps a per-run check cheap, and it is
     * the same class the Console's health indicator reads, so a paused build and a red dot are one fact.
     */
    private EndpointReachability reachability = new EndpointReachability();

    public SwarmEngineImpl(VllmClient judgeClient, ArtifactStore artifactStore,
                       InferenceScheduler inferenceScheduler,
                       AgentRuntime agentRuntime,
                       ModelProfileRegistry profiles,
                       GitService gitService,
                       BlobSink blobSink,
                       CloudGate cloudGate) {
        this(judgeClient, artifactStore, inferenceScheduler, agentRuntime, profiles, gitService,
            blobSink, cloudGate, null);
    }

    public SwarmEngineImpl(VllmClient judgeClient, ArtifactStore artifactStore,
                       InferenceScheduler inferenceScheduler,
                       AgentRuntime agentRuntime,
                       ModelProfileRegistry profiles,
                       GitService gitService,
                       BlobSink blobSink,
                       CloudGate cloudGate,
                       Supplier<String> guidelinesProvider) {
        this(judgeClient, artifactStore, inferenceScheduler, agentRuntime, profiles, gitService,
            blobSink, cloudGate, guidelinesProvider, ApiLookup.UNAVAILABLE);
    }

    public SwarmEngineImpl(VllmClient judgeClient, ArtifactStore artifactStore,
                       InferenceScheduler inferenceScheduler,
                       AgentRuntime agentRuntime,
                       ModelProfileRegistry profiles,
                       GitService gitService,
                       BlobSink blobSink,
                       CloudGate cloudGate,
                       Supplier<String> guidelinesProvider,
                       ApiLookup apiLookup) {
        this(judgeClient, artifactStore, inferenceScheduler, agentRuntime, profiles, gitService,
            blobSink, cloudGate, guidelinesProvider, apiLookup, () -> ExpertHelp.UNAVAILABLE, List.of());
    }

    /**
     * @param expertFactory  builds the help desk's expert tier (spec: ExpertDesk javadoc) fresh for
     *                       EACH worker — its per-worker escalation cap is per INSTANCE, so sharing
     *                       one across workers would share the cap across them too. Called once per
     *                       worker dispatched, by {@link SwarmDispatcher}. Defaults to a factory that
     *                       always returns {@link ExpertHelp#UNAVAILABLE}, so an engine nobody wires
     *                       this into behaves exactly as it always has: the desk answers everything
     *                       it can for free and tells a worker plainly when it cannot escalate.
     * @param frameworkPackages package prefixes of the project's reference material, so a build
     *                       error naming one of them can point the worker at {@code ask_expert}
     *                       instead of its own guessing (see {@code WorkerToolbox.frameworkErrorHint}).
     */
    public SwarmEngineImpl(VllmClient judgeClient, ArtifactStore artifactStore,
                       InferenceScheduler inferenceScheduler,
                       AgentRuntime agentRuntime,
                       ModelProfileRegistry profiles,
                       GitService gitService,
                       BlobSink blobSink,
                       CloudGate cloudGate,
                       Supplier<String> guidelinesProvider,
                       ApiLookup apiLookup,
                       Supplier<ExpertHelp> expertFactory,
                       List<String> frameworkPackages) {
        this.judgeClient = judgeClient;
        this.artifactStore = artifactStore;
        this.inferenceScheduler = inferenceScheduler;
        this.gitService = gitService;
        this.cloudGate = cloudGate;
        this.guidelinesProvider = guidelinesProvider;
        this.profiles = profiles;
        this.dispatcher = new SwarmDispatcher(inferenceScheduler, gitService, agentRuntime, profiles,
            apiLookup, expertFactory == null ? () -> ExpertHelp.UNAVAILABLE : expertFactory,
            frameworkPackages == null ? List.of() : List.copyOf(frameworkPackages));
        this.verifier = new CommandPipelineVerifier(blobSink);
        this.dispatcher.setTestsCommitOf(id -> {
            Run run = artifactStore.root().runs.get(id);
            return run == null ? null : run.acceptanceTestsCommit();
        });
    }

    /** For tests: a probe whose answers the test controls. */
    void setReachability(EndpointReachability reachability) {
        this.reachability = reachability == null ? new EndpointReachability() : reachability;
    }

    /**
     * The container manager every worker command, every candidate verification and every build
     * this engine runs goes through. With none set the engine REFUSES to run them (see
     * {@link SandboxAttach} and {@link com.swarmcoder.verify.BuildBoxes}); it does not use this
     * PC instead. A container that fails to start fails the candidate.
     */
    public void setSandbox(DockerSandboxManager sandbox) {
        this.sandbox = sandbox;
        // Workers' exec tool is sandboxed under the same flag: the dispatcher launches a
        // per-candidate sandbox for generation, this class launches one for verification.
        this.dispatcher.setSandbox(sandbox);
    }

    /**
     * Operator-declared locked-down modules (config {@code protectedPaths}, spec §18): paths no
     * worker may modify however its write set reads. Held by the dispatcher, which hands them to
     * every {@code WorkerToolbox} it builds; the decision itself stays in {@code PathPolicy}.
     */
    /**
     * Supplies the house rules that carry a proof command. Setter rather than another constructor
     * parameter, matching {@code setSandbox} and {@code setProtectedPaths}: this engine already has
     * three constructors and the checks are optional everywhere.
     */
    public void setGuidelineChecks(Supplier<List<GuidelineCheck>> provider) {
        this.guidelineChecksProvider = provider == null ? List::of : provider;
    }

    /**
     * Supplies the project's ACTIVE rules as objects, for the judge to choose from. Same setter
     * style and the same trust boundary as {@link #setGuidelineChecks}: resolved from the
     * operator's checkout, never from a candidate's worktree.
     */
    public void setActiveRules(Supplier<List<LearnedGuideline>> provider) {
        this.activeRulesProvider = provider == null ? List::of : provider;
    }

    /**
     * Supplies the rules a WORKER is sent, for the paths its task may write (owner's decision
     * 2026-10-07, section 65): the rules of the whole project and the rules recorded for a part
     * the task touches. Without it a worker is sent every rule, as before.
     *
     * <p>Only the workers' opening reads this. The judge is still handed every rule
     * ({@link #setActiveRules}) and verification still runs every rule's check
     * ({@link #setGuidelineChecks}), so what a worker is told never narrows what its result is
     * held to.
     */
    public void setWorkerRules(
            java.util.function.Function<java.util.Collection<String>, RuleScope.Briefing> rules) {
        this.workerRules = rules;
    }

    /** The span a run report reads the rules sent to a task's workers from. */
    public static final String WORKER_RULES_SPAN = "worker rules|";

    /**
     * The rules text for the workers of {@code task}: what {@link #setWorkerRules} chooses for
     * the task's write set and the folder its acceptance tests are in, or {@code all} when
     * nothing chooses. Says so once per dispatch, in the log and as a span for the run report.
     */
    String rulesForWorkersOf(Task task, String all) {
        var choose = workerRules;
        if (choose == null) {
            return all;
        }
        List<String> paths = new ArrayList<>();
        if (task.writeSet() != null) {
            task.writeSet().stream().sorted().forEach(paths::add);
        }
        if (!paths.isEmpty() && task.acceptanceTestDir() != null
                && !task.acceptanceTestDir().isBlank()) {
            paths.add(task.acceptanceTestDir());
        }
        RuleScope.Briefing briefing;
        try {
            briefing = choose.apply(paths);
        } catch (RuntimeException e) {
            log.warn("Task '{}': the rules for its part of the project could not be chosen ({}) "
                + "- its workers are sent every rule", task.title(), e.toString());
            return all;
        }
        if (briefing == null) {
            return all;
        }
        log.info("Task '{}': its workers are sent {} of the project's {} rule(s) - the rules of "
            + "the whole project and the rules for {}. Its result is still judged and checked "
            + "against every rule.", task.title(), briefing.sent(), briefing.inForce(),
            paths.isEmpty() ? "any part of it (the task has no write set)" : paths);
        com.swarmcoder.inference.RunMeter.span(WORKER_RULES_SPAN + task.id() + "|"
            + briefing.sent() + "|" + briefing.inForce(), System.currentTimeMillis());
        return briefing.text();
    }

    /**
     * Where an answer to a question about a rule is applied — the project's own rules. Without it
     * the question is still raised, and can only be answered by hand on the Guidelines screen.
     */
    public void setRuleAmendments(RuleQuestions.Amendments amendments) {
        this.ruleAmendments = amendments == null ? RuleQuestions.Amendments.NONE : amendments;
    }

    /**
     * The one switch for every opinion check, here and in the workflow driving this engine (which
     * reads it through {@link #opinionPolicy()}). An unattended run (the live harness) sets
     * {@link OpinionPolicy#WARN_AND_CARRY_ON}: a question about a rule is answered by rewording
     * the rule, any other model objection at its limit becomes a warning on the run, and the run
     * carries on instead of parking for a person who is not there. Fact checks are not affected.
     */
    public void setOpinionPolicy(OpinionPolicy policy) {
        this.opinionPolicy.setExplicit(policy);
    }

    /**
     * Tells the engine when nobody is at the console (the overnight setting, an autonomous
     * session). The answer is read at every decision, so the policy follows those modes live.
     *
     * @param reason returns why the machine is unattended right now, or null when a person is
     */
    public void setUnattendedReason(Supplier<String> reason) {
        this.opinionPolicy.setUnattendedReason(reason);
    }

    @Override
    public OpinionPolicy opinionPolicy() {
        return opinionPolicy.get();
    }

    /**
     * Carries a model's objection past instead of parking, when nobody is there to ask.
     *
     * @return false under {@link OpinionPolicy#ASK_THE_OPERATOR}: nothing was recorded and the
     *         caller parks the run exactly as it always did
     */
    private boolean carriedAsWarning(UUID runId, Task task, String check, String objection) {
        if (opinionPolicy() != OpinionPolicy.WARN_AND_CARRY_ON) {
            return false;
        }
        CarriedWarning warning = new CarriedWarning(check, RunState.EXECUTING.name(),
            "Task '" + task.title() + "': " + objection, Instant.now());
        Run run = runsInFlight.get(runId);
        if (run != null) {
            run.carryWarning(warning);
        }
        log.warn("WARNING CARRIED (nobody is watching this run, so it is not parked): {}",
            warning.oneLine());
        return true;
    }

    /**
     * The controller that runs fewer workers on a model server whose workers keep running out of
     * room, each with more of it — see {@link com.swarmcoder.inference.AdaptiveConcurrency}. It
     * acts at dispatch, between waves, and never touches a wave already running.
     */
    public void setConcurrencyController(com.swarmcoder.inference.AdaptiveConcurrency controller) {
        this.dispatcher.setConcurrencyController(controller);
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.dispatcher.setProtectedPaths(protectedPaths);
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    }

    /** The same paths, kept here so a write set is never widened onto one of them. */
    private volatile List<String> protectedPaths = List.of();

    /**
     * The project's read-only reference folders, by the label its knowledge brief uses. Each
     * worker's container mounts them read-only at {@code /reference/<label>} and each worker's
     * {@code read} tool accepts that address. Without this a contained worker has documentation
     * it is told about and cannot open.
     */
    public void setReferenceRoots(java.util.Map<String, Path> roots) {
        this.referenceRoots = roots == null ? java.util.Map.of() : java.util.Map.copyOf(roots);
        this.dispatcher.setReferenceRoots(roots);
    }

    /**
     * Where the product's own builds and tests of model-written code run for this engine's
     * project: the red check, the test author's compile, the wave compile, final integration,
     * story delivery. Follows {@link #setSandbox} and {@link #setReferenceRoots} as they are at
     * the moment a container is started.
     */
    public com.swarmcoder.verify.BuildBoxes buildBoxes() {
        return buildBoxes;
    }

    /**
     * Dispatch shaping from the {@code swarm:} config block (spec §17):
     * {@code maxConcurrentTaskGroups} caps how many task groups execute at once within a
     * wave (0 = unlimited), {@code dispatch.staggerMs} spaces worker launches inside a group.
     */
    public void setDispatchTuning(int maxConcurrentTaskGroups, long staggerMs) {
        this.maxConcurrentTaskGroups = Math.max(0, maxConcurrentTaskGroups);
        this.dispatcher.setStaggerMs(staggerMs);
    }

    private volatile int maxConcurrentTaskGroups;

    /**
     * Asked once before every wave AFTER the first, with the tree that wave will be cut from.
     *
     * <p>It exists for the red-check, and the red-check could not be asked any earlier. Acceptance
     * tests are written at TEST_AUTHORING, three stages before a worker runs, and a test for a task
     * that depends on another task names classes that do not exist until the earlier task has won -
     * so measuring it there measures the wrong tree. Returning a non-null string parks the run with
     * that text; the workflow owns what parking means, which is why this stays a question.
     */
    @FunctionalInterface
    public interface WaveGate {
        /** Null to dispatch the wave; otherwise the operator-facing reason the run must park. */
        String beforeWave(Run run, List<Task> wave, String waveBase);
    }

    private volatile WaveGate waveGate;

    /**
     * Registers the check made before each wave after the first (see {@link WaveGate}). An engine
     * nobody sets one on dispatches every wave exactly as it did before.
     */
    public void setWaveGate(WaveGate waveGate) {
        this.waveGate = waveGate;
    }

    @Override
    public Run executeRun(Run run) {
        runsInFlight.put(run.id(), run);
        try {
            return executeWaves(run);
        } finally {
            // Candidates that were told to stop wind down behind their tasks; the run is not
            // over until they have, because their worktrees and records are written as they end.
            awaitCandidatesStillEnding(run.id());
            groupsOfRun.remove(run.id());
            runsInFlight.remove(run.id());
        }
    }

    private Run executeWaves(Run run) {
        log.info("Executing run {}", run.id());

        TaskGraph graph = artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks().isEmpty()) {
            log.error("No task graph found for run {}", run.id());
            return run;
        }

        // Before anything is built: is there a model server to build against?
        requireWorkerEndpoint();

        // And, off to one side, make the sandbox's Maven builds stop crawling. See
        // warmMavenRepoCacheOnce.
        warmMavenRepoCacheOnce();

        // Once per run, on the untouched tree, before any worker runs: which house-rule check
        // commands cannot run at all, and which of the project's own tests already fail. Neither
        // is something a candidate did. See BaseTreeChecks.
        establishOnBaseTree(run, true, true);

        // Topological waves: tasks inside a wave run in parallel; a wave starts only when
        // every prerequisite wave finished. Disjoint write sets among concurrent tasks are
        // guaranteed by the PLAN validator.
        Semaphore groupSlots = maxConcurrentTaskGroups > 0
            ? new Semaphore(maxConcurrentTaskGroups) : null;
        List<List<Task>> waves = topologicalWaves(graph);
        // Out-of-write-set path -> the task whose winner reached it first, for the WHOLE run. Two
        // winners of one wave are the only pair that can collide (their write sets are disjoint by
        // construction, so a conflict has to be over a path at least one of them did not own), and
        // when one does this is what names both sides of it.
        Map<String, String> claimedBy = new java.util.LinkedHashMap<>();
        WaveIntegrator integrator = new WaveIntegrator(gitService, buildBoxes);
        // A task waits while anything it depends on has no winner (harness run 39, 2026-09-25: the
        // implementation of an interface was dispatched after the interface's own task had gone
        // BLOCKED). See WaitingOnDependencies.
        WaitingOnDependencies gate = new WaitingOnDependencies(graph);
        for (int index = 0; index < waves.size(); index++) {
            List<Task> planned = waves.get(index);
            List<Task> wave = gate.dispatchable(planned);
            if (wave.size() < planned.size()) {
                List<String> held = planned.stream().filter(t -> !wave.contains(t))
                    .map(Task::title).toList();
                log.warn("Wave {} of {}: not dispatching {} — a task they depend on has no winner, "
                    + "so their code could not compile", index + 1, waves.size(), held);
            }
            if (wave.isEmpty()) {
                continue; // nothing to build, nothing to merge; the next base is unchanged
            }
            // THE CHANGE OF 2026-09-02: not run.startPoint(). A wave is cut from the run's pinned
            // base plus every earlier wave's winner, so a task can build on the task it depends on.
            // Wave 0 has nothing in front of it and therefore still gets the bare pinned base.
            final String waveBase = run.progressPoint();
            log.info("Wave {} of {}: {} task(s) {}, cut from {}", index + 1, waves.size(),
                wave.size(), wave.stream().map(Task::title).toList(), shortSha(waveBase));
            // The tests of a later wave's task cannot even compile against the pinned base - the
            // classes they name are what the earlier wave just delivered - so the check that they
            // are genuinely red is made HERE, against the tree the task will really be verified on,
            // rather than at TEST_AUTHORING where that tree does not exist yet. Wave 0 was checked
            // there, against the same commit, and is not checked twice.
            if (index > 0 && waveGate != null) {
                String park = waveGate.beforeWave(run, wave, waveBase);
                if (park != null) {
                    throw new RunMustPark(park);
                }
            }
            List<CompletableFuture<CandidateSolution>> futures = new ArrayList<>();
            final long waveStarted = System.currentTimeMillis();
            final String waveName = "wave " + (index + 1) + " of " + waves.size();
            // What the wave's tasks know about each other: whether every task that can start has
            // its first candidate going (second candidates wait for that), and whether any other
            // task is still looking for a passing candidate (see WaveBoard).
            final WaveBoard board = new WaveBoard(wave.size(), maxConcurrentTaskGroups);
            if (groupSlots != null && wave.size() > maxConcurrentTaskGroups) {
                log.info("Wave {} of {} has {} tasks and the settings allow {} at a time "
                    + "(swarm.maxConcurrentTaskGroups), so the others wait for a task to end even "
                    + "while the model server has free places; those places go to further "
                    + "candidates of the task(s) running. Leave the setting out to have every "
                    + "ready task started, as many at once as the server has places.", index + 1,
                    waves.size(), wave.size(), maxConcurrentTaskGroups);
            }
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Task task : wave) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        if (groupSlots != null) {
                            groupSlots.acquireUninterruptibly();
                        }
                        try {
                            board.started(task.id());
                            return executeTask(task, run.id(), waveBase, board);
                        } finally {
                            board.ended(task.id());
                            if (groupSlots != null) {
                                groupSlots.release();
                            }
                        }
                    }, executor));
                }
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (CompletionException e) {
                    // An outage inside a task must reach the workflow, which is the only layer that
                    // can pause and retry the stage. join() wraps it, so unwrap or the distinction
                    // this whole path exists to preserve is lost at the very last hop.
                    throw rethrowOutage(e);
                } finally {
                    com.swarmcoder.inference.RunMeter.span(waveName, waveStarted);
                    siblingRepairClaims.keySet().removeIf(key -> key.startsWith(run.id() + "|"));
                }
            }
            if (index == waves.size() - 1) {
                break; // nothing comes after it, so there is no next base to build
            }
            List<WaveIntegrator.Winner> winners = new ArrayList<>();
            for (int i = 0; i < wave.size(); i++) {
                CandidateSolution winner = futures.get(i).join();
                if (winner == null) {
                    gate.finishedWithoutWinner(wave.get(i));
                }
                if (winner != null && winner.branch() != null) {
                    winners.add(new WaveIntegrator.Winner(wave.get(i), winner));
                }
            }
            final long mergeStarted = System.currentTimeMillis();
            try {
                advanceProgress(run, integrator, winners, claimedBy, index);
            } finally {
                com.swarmcoder.inference.RunMeter.span("integration after " + waveName,
                    mergeStarted);
            }
            // The baseline of already-failing tests is reused across waves unless a merged
            // winner changed one of those tests: then it is no longer the test that was measured.
            boolean testsAgain = touchesABaselineTest(run.baselineFailingTests(), winners);
            if (testsAgain) {
                log.info("Run {}: a winner of wave {} changed a test that already failed on the "
                    + "start tree, so the already-failing tests are established again on the "
                    + "merged tree.", run.id(), index + 1);
            }
            // The next wave's candidates are cut from the merged tree, so what its compile does
            // is measured on that tree: it is what an error in a file a candidate did not touch
            // is read against (live run 74).
            if (testsAgain || (index + 1 < waves.size() && !winners.isEmpty())) {
                establishOnBaseTree(run, false, testsAgain);
            }
        }

        if (gate.anyWaiting()) {
            // Every task that could run has run. What is left needs a task that has no winner, and
            // that task already raised its own question — so the run stops behind it, without a
            // second one, and resumes from here once it is settled.
            log.warn("Run {}: {} task(s) were not started because a task they depend on has no "
                + "winner: {}", run.id(), gate.waitingTitles().size(), gate.waitingTitles());
            throw new RunMustPark(gate.parkBrief(), true);
        }

        log.info("All tasks in TaskGraph {} executed", run.taskGraphId());
        // The SAME run, not a copy of it. This used to rebuild it through the 8-arg constructor,
        // which carries neither projectId nor storyId — so every run came out of EXECUTING having
        // forgotten which story it was building, and the delivery check that follows then treated it
        // as an ad-hoc run "with no work item to update" and left the story in RUNNING for ever.
        // That is the same defect already documented on GreenfieldWorkflow.transition, one layer
        // down. Nothing about the run changes here, so there is nothing to copy.
        return run;
    }

    /**
     * Mirrors the artifacts this project's builds resolve into the sandbox's fast, read-only
     * Maven cache — once per orchestrator process, in the background.
     *
     * <p><b>Why.</b> A sandbox build of the demo project took 41-70 s against 10-12 s for the same
     * command on the workstation. Almost none of that gap is CPU, container start-up or the
     * workspace mount: it is Maven reading artifacts out of the operator's {@code ~/.m2} across
     * the Windows-to-Linux filesystem bridge, at 8-44 ms per file, on every build. A worker runs
     * about ten build-fix cycles and there are four workers per task, so it sets the pace of the
     * whole run. With the cache in front, the same build takes 11-13 s. The full measurements are
     * in {@link com.swarmcoder.sandbox.MavenRepoCache}.
     *
     * <p><b>Off the critical path on purpose.</b> The first warm for a project costs a couple of
     * minutes (it has to run one build to find out what the build resolves). Candidates dispatched
     * while it is still running simply resolve through the host repository as before — an empty or
     * half-filled cache changes how fast an artifact is found, never which artifact is found — so
     * there is nothing to wait for and nothing to fail.
     */
    private void warmMavenRepoCacheOnce() {
        if (sandbox == null || mavenCacheWarmed || gitService == null || !gitService.isEnabled()) {
            return;
        }
        Path repoPath = gitService.repoPath();
        if (repoPath == null) {
            return;
        }
        // The contract from the OPERATOR'S tree, like everywhere else: these commands are about to
        // be run, and a candidate that could rewrite them could choose what gets cached.
        Optional<VerifySpec> spec = VerifySpecLoader.loadTrusted(repoPath, repoPath);
        if (spec.isEmpty()) {
            return;
        }
        List<String> commands = new ArrayList<>();
        if (spec.get().compile() != null) {
            commands.addAll(spec.get().compile());
        }
        if (spec.get().existing() != null) {
            commands.addAll(spec.get().existing());
        }
        if (commands.isEmpty()) {
            return;
        }
        mavenCacheWarmed = true;
        Thread.ofVirtual().name("maven-repo-cache-warm").start(() -> {
            try {
                MavenRepoCache.Report report =
                    sandbox.warmMavenRepoCache(repoPath.toAbsolutePath().toString(), commands);
                if (report.ran()) {
                    log.info("Sandbox Maven cache warmed: {}", report.detail());
                } else {
                    log.info("Sandbox Maven cache not warmed ({}); builds keep resolving straight "
                        + "from the host repository, which is correct but slower", report.detail());
                }
            } catch (Exception e) {
                log.warn("Sandbox Maven cache warm failed ({}); builds keep resolving straight from "
                    + "the host repository, which is correct but slower", e.toString());
            }
        });
    }

    /**
     * Throws {@link EndpointOutage} when the worker endpoints are configured and none of them answer,
     * so a build waits for the model server instead of failing eight times to discover it is not there.
     *
     * <p><b>Why a pre-flight check when the reactive one already works.</b> It does work — a wave that
     * loses everybody to the endpoint throws, and the run pauses and retries. But by then the run has
     * created a worktree per candidate, launched a container per candidate when the sandbox is on,
     * opened and traced eight agent sessions, and written eight {@code ENDPOINT_OUTAGE} candidates into
     * the archive that is also the evaluation dataset. None of that is evidence about the task; it is
     * evidence that a box was switched off. Asking one cheap question first turns all of it into a
     * pause.
     *
     * <p><b>UNKNOWN proceeds.</b> An unconfigured or unprobeable endpoint is not an outage: some setups
     * reach their workers through something this cannot see, and pausing those runs for ever while
     * waiting for a box nobody named would be a far worse failure than the waste being avoided. Only a
     * definite configured-and-unreachable pauses.
     *
     * <p>Nothing is persisted here and no state is changed: throwing is the whole effect. The caller's
     * pause loop owns what a pause means, which is why this stays a question and not a decision.
     */
    private void requireWorkerEndpoint() {
        List<String> endpoints = new ArrayList<>();
        for (ModelProfile profile : profiles.workers()) {
            if (profile.endpoint() != null && profile.endpoint().baseUrl() != null) {
                endpoints.add(profile.endpoint().baseUrl());
            }
        }
        EndpointReachability.Probe probe = reachability.probeAny(endpoints);
        if (!probe.status().isOutage()) {
            return;
        }
        log.warn("Worker endpoint {} is not answering — pausing before dispatch rather than launching "
            + "a swarm that cannot reach a model: {}", probe.endpoint(), probe.detail());
        throw new EndpointOutage(probe.endpoint(),
            "the model server is not answering, so no workers were started", null);
    }

    /**
     * Unwraps a {@link CompletionException} so an {@link EndpointOutage} keeps its identity, and
     * returns it for the caller to throw.
     */
    private static RuntimeException rethrowOutage(CompletionException wrapper) {
        Throwable cause = wrapper.getCause();
        if (cause instanceof EndpointOutage outage) {
            return outage;
        }
        return cause instanceof RuntimeException runtime ? runtime : wrapper;
    }

    /**
     * Dependency levels via Kahn's algorithm; validated-acyclic graphs always terminate.
     *
     * <p>The algorithm itself moved to {@link Waves} in the domain module, unchanged, so that
     * scheduling STORIES uses the same code rather than a second copy of it. The console owns the
     * backlog and may not depend on this module, so the shared piece had to sit below both — see
     * {@link com.swarmcoder.domain.StoryGraph}. A cycle that slipped past validation still flushes
     * the remaining tasks into one wave instead of hanging; that is {@link Waves.Result#stalled()},
     * and the log line telling somebody about it stays here.
     */
    public static List<List<Task>> topologicalWaves(TaskGraph graph) {
        List<Waves.Edge> edges = new ArrayList<>();
        if (graph.dependencies() != null) {
            for (TaskEdge edge : graph.dependencies()) {
                edges.add(new Waves.Edge(edge.from(), edge.to()));
            }
        }
        Waves.Result<Task> result = Waves.of(graph.tasks(), Task::id, edges);
        if (result.stalled()) {
            log.error("Task graph wave computation stalled (cycle?); flushing remaining tasks");
        }
        return result.waves();
    }

    /**
     * Merges the wave's winners onto the run's progress branch and records where the NEXT wave is
     * cut from. Parks the run when they will not merge, or when the tree they make together will
     * not compile.
     *
     * <p><b>A wave with no winner at all does not stop the run.</b> Every task in it is already
     * BLOCKED with its own pending decision, and the next wave then starts from exactly where this
     * one did - which is what used to happen for every wave.
     */
    /** Per run: the house rules whose check command cannot run and is skipped (unattended). */
    private final Map<UUID, Set<String>> skippedRuleChecks =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Runs whose untouched tree has been looked at in this process; it is done once. */
    private final Set<UUID> baseTreeEstablished = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What the compile stage did on the tree the current wave's candidates are cut from. */
    private final Map<UUID, VerificationBaseline.StartCompile> startCompileOfRun =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Establishes, once per run and in a container, what is already true of the tree the run
     * starts from (owner decisions after the audit of 2026-10-02; see {@link BaseTreeChecks}).
     *
     * <p>A house-rule check command that cannot run there parks the run with the command and the
     * reason named, or - when nobody is at the console - is skipped for this run with a carried
     * warning. The project's own tests that fail there are recorded on the run, logged once and
     * carried as a warning; a candidate is failed by the existing-tests stage only for a test
     * that is not among them.
     *
     * <p>Nothing here can fail a run for want of a container or a repository: when the tree
     * cannot be looked at, nothing is established and candidates are judged as they were before.
     *
     * @param first true before the first wave (rules and tests); false to establish the
     *              already-failing tests again after a winner changed one of them
     */
    private void establishOnBaseTree(Run run, boolean first, boolean tests) {
        if (first && !baseTreeEstablished.add(run.id())) {
            return; // re-entered after a pause: already established in this process
        }
        if (gitService == null || !gitService.isEnabled()) {
            return;
        }
        List<GuidelineCheck> checks = first ? guidelineChecksProvider.get() : List.of();
        Optional<VerifySpec> spec =
            VerifySpecLoader.loadTrusted(gitService.repoPath(), gitService.repoPath());
        boolean hasExistingStage = spec.isPresent() && spec.get().existing() != null
            && !spec.get().existing().isEmpty();
        // A run taken up again after its first wave keeps the baseline it recorded: the tree it
        // is on now carries its own winners, and what they must not break was measured before.
        boolean measureTests = tests && hasExistingStage
            && !(first && run.progressCommit() != null && !run.progressCommit().isBlank());
        boolean hasChecks = checks != null && !checks.isEmpty();
        // What the compile stage does on the tree the next candidates are cut from (live run 74):
        // measured before every wave, kept for this process only - a run taken up again measures
        // it again.
        boolean measureCompile = spec.isPresent() && spec.get().compile() != null
            && !spec.get().compile().isEmpty();
        if (!hasChecks && !measureTests && !measureCompile) {
            return;
        }
        startCompileOfRun.remove(run.id());
        String commit = run.progressPoint();
        Path worktree = Path.of(System.getProperty("user.home"), ".swarmcoder", "wt",
            "base-" + run.id());
        final long started = System.currentTimeMillis();
        try {
            gitService.removeWorktree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(commit, worktree);
        } catch (Exception e) {
            log.warn("Run {}: the tree the run starts from could not be checked out to look at "
                + "it first ({}), so nothing is established about it.", run.id(), e.getMessage());
            if (first) {
                baseTreeEstablished.remove(run.id());
            }
            return;
        }
        List<BaseTreeChecks.UnrunnableCheck> unrunnable = List.of();
        BaseTreeChecks.ExistingBaseline existing = null;
        try {
            SandboxAttach.Attachment attachment = SandboxAttach.attach(sandbox,
                worktree.toAbsolutePath().toString(), "", "Checks on the tree the run starts from");
            if (attachment.refused()) {
                log.warn("Run {}: {} Nothing is established about the tree the run starts from.",
                    run.id(), attachment.refusal());
                if (first) {
                    baseTreeEstablished.remove(run.id());
                }
                return;
            }
            try {
                ExecTarget target = attachment.target() != null
                    ? attachment.target() : new LocalProcessExecTarget(worktree);
                StringBuilder logText = new StringBuilder();
                if (hasChecks) {
                    unrunnable = BaseTreeChecks.unrunnable(target, checks, logText);
                }
                // Not measured when the run is about to park anyway: it is paid for on resume.
                boolean goingOn = unrunnable.isEmpty()
                    || opinionPolicy() == OpinionPolicy.WARN_AND_CARRY_ON;
                VerificationBaseline.StartCompile compiled = null;
                if (measureCompile && goingOn) {
                    compiled = BaseTreeChecks.compile(target, spec.get(), logText);
                    startCompileOfRun.put(run.id(), compiled);
                    log.info("Run {}: the compile stage on {}, the tree the next candidates are "
                        + "cut from: {}.", run.id(), shortSha(commit),
                        !compiled.established() ? "could not be measured"
                            : compiled.compiles() ? "passes"
                            : "fails in " + compiled.errorFiles().size() + " file(s)"
                                + (compiled.mainCompiles() ? ", main code compiles" : ""));
                }
                if (measureTests && goingOn) {
                    existing = BaseTreeChecks.existingTests(target, spec.get(), logText, compiled);
                }
                log.debug("Run {}: checks on the tree the run starts from:\n{}", run.id(), logText);
            } finally {
                SandboxAttach.release(sandbox, attachment);
            }
        } finally {
            gitService.removeWorktree(worktree);
            com.swarmcoder.inference.RunMeter.span("base tree checks", started);
        }
        if (!unrunnable.isEmpty()) {
            ruleChecksThatCannotRun(run, unrunnable);
        }
        if (existing != null) {
            alreadyFailingTests(run, existing, commit);
        }
    }

    private void ruleChecksThatCannotRun(Run run, List<BaseTreeChecks.UnrunnableCheck> unrunnable) {
        if (opinionPolicy() != OpinionPolicy.WARN_AND_CARRY_ON) {
            baseTreeEstablished.remove(run.id()); // asked again when the run is taken up
            StringBuilder brief = new StringBuilder("A house rule's check command cannot run in "
                + "the build container. It would have failed every candidate whatever the "
                + "candidate did, so nothing was started.\n");
            for (BaseTreeChecks.UnrunnableCheck one : unrunnable) {
                brief.append("\n- Rule '").append(one.check().slug()).append("'\n  Command: ")
                    .append(one.check().command()).append("\n  Why it cannot run: ")
                    .append(one.reason()).append('\n');
            }
            brief.append("\nCorrect the command so it runs in the container (or add the tool it "
                + "needs to the container image), or take the check off the rule, then resume "
                + "the run.");
            throw new RunMustPark(brief.toString());
        }
        Set<String> skipped = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (BaseTreeChecks.UnrunnableCheck one : unrunnable) {
            skipped.add(one.check().slug());
            CarriedWarning warning = new CarriedWarning("house-rule check command that cannot run",
                RunState.EXECUTING.name(), one.sentence() + ". The check is skipped for this run; "
                    + "the rule still stands and is still read by the workers and the judge.",
                Instant.now());
            run.carryWarning(warning);
            log.warn("WARNING CARRIED (nobody is watching this run, so it is not parked): {}",
                warning.oneLine());
        }
        skippedRuleChecks.put(run.id(), skipped);
        recordOnRun(run);
    }

    private void alreadyFailingTests(Run run, BaseTreeChecks.ExistingBaseline existing,
                                     String commit) {
        if (!existing.established()) {
            log.info("Run {}: which of the project's own tests already fail on {} could not be "
                + "established ({}), so every failing test is held against the candidate as "
                + "before.", run.id(), shortSha(commit), existing.note());
            return;
        }
        List<String> failing = existing.failingIds();
        run.setBaselineFailingTests(failing);
        if (failing.isEmpty()) {
            log.info("Run {}: every one of the project's own tests passes on {}, the tree the "
                + "run starts from.", run.id(), shortSha(commit));
        } else {
            CarriedWarning warning = new CarriedWarning(
                "tests that already failed before this run changed anything",
                RunState.EXECUTING.name(), failing.size() + " of the project's own test(s) "
                    + "already fail on " + shortSha(commit) + ", the tree this run started from, "
                    + "and are not held against any candidate: " + String.join(", ", failing),
                Instant.now());
            run.carryWarning(warning);
            log.warn("Run {}: {}", run.id(), warning.oneLine());
        }
        recordOnRun(run);
    }

    private void recordOnRun(Run run) {
        try {
            artifactStore.updateRun(run);
        } catch (Exception e) {
            log.warn("Failed to record what was established about the start tree on run {}: {}",
                run.id(), e.getMessage());
        }
    }

    /**
     * Whether a merged winner changed the file of a test that is on the baseline. A test id is
     * {@code class#method}; the file is recognised by the class's simple name, whatever the
     * language's extension.
     */
    static boolean touchesABaselineTest(List<String> failingTests,
                                        List<WaveIntegrator.Winner> winners) {
        if (failingTests == null || failingTests.isEmpty() || winners == null) {
            return false;
        }
        Set<String> classes = new java.util.HashSet<>();
        for (String id : failingTests) {
            String type = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
            type = type.substring(type.lastIndexOf('.') + 1);
            if (type.contains("$")) {
                type = type.substring(0, type.indexOf('$'));
            }
            if (!type.isBlank()) {
                classes.add(type);
            }
        }
        for (WaveIntegrator.Winner winner : winners) {
            if (winner == null || winner.candidate() == null) {
                continue;
            }
            for (String path : UnifiedDiffPaths.addedOrChanged(winner.candidate().diffUnified())) {
                String name = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
                int dot = name.indexOf('.');
                if (classes.contains(dot < 0 ? name : name.substring(0, dot))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The checks that decide a candidate: every declared one but those skipped for this run. */
    private List<GuidelineCheck> runnableChecks(UUID runId, List<GuidelineCheck> checks) {
        Set<String> skipped = runId == null ? null : skippedRuleChecks.get(runId);
        if (skipped == null || skipped.isEmpty() || checks == null) {
            return checks;
        }
        return checks.stream().filter(c -> c == null || !skipped.contains(c.slug())).toList();
    }

    /**
     * The verdict sentence when the candidate removes public code the run's start commit had and
     * no agreed check of the run's story asks for a removal; null otherwise, and whenever there
     * is nothing to compare or nothing agreed to read. See {@link RemovedExistingApi}.
     */
    private String removedExistingApi(UUID runId, CandidateSolution sol, Path workspace) {
        if (!RemovedExistingApi.enabled() || runId == null || workspace == null
                || gitService == null || !gitService.isEnabled()) {
            return null;
        }
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        if (run == null || run.storyId() == null || run.baseCommit() == null
                || run.baseCommit().isBlank()) {
            return null;
        }
        Story story = artifactStore.getStory(run.storyId());
        if (story == null) {
            return null;
        }
        List<String> agreed = new ArrayList<>();
        for (Brd brd : artifactStore.root().brds().values()) {
            if (brd == null || brd.requirements() == null) {
                continue;
            }
            for (BrdRequirement requirement : brd.requirements()) {
                if (requirement.criteria() == null) {
                    continue;
                }
                for (AcceptanceCriterion criterion : requirement.criteria()) {
                    if (criterion != null && criterion.text() != null
                            && story.criterionIds().contains(criterion.id())) {
                        agreed.add(criterion.text().strip());
                    }
                }
            }
        }
        if (agreed.isEmpty() || RemovedExistingApi.asksForRemoval(agreed)) {
            return null;
        }
        String base = run.baseCommit();
        List<String> removed;
        try {
            removed = RemovedExistingApi.removed(sol.diffUnified(), path -> {
                byte[] bytes = gitService.fileAt(base, path);
                return bytes == null ? null
                    : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }, path -> {
                try {
                    Path file = workspace.resolve(path);
                    return java.nio.file.Files.isRegularFile(file)
                        ? java.nio.file.Files.readString(file) : null;
                } catch (java.io.IOException | RuntimeException unreadable) {
                    return null;
                }
            });
        } catch (RuntimeException e) {
            log.warn("Candidate {}: what it removed could not be established ({}); not judged "
                + "on it", sol.id(), e.toString());
            return null;
        }
        return RemovedExistingApi.objection(removed, agreed);
    }

    /**
     * The verdict sentence when the candidate adds production source files that nothing reachable
     * in its tree uses; null otherwise, and whenever that cannot be established. Only a task no
     * other task depends on is asked, and only about the files its own change adds: what an
     * earlier task added and nothing ever used is found when the run's merged tree is looked at
     * ({@code FinalIntegrator}).
     */
    private String unreachableAddedCode(UUID runId, Task task, CandidateSolution sol,
                                        Path workspace) {
        if (!ReachableCode.enabled() || runId == null || task == null || workspace == null
                || gitService == null || !gitService.isEnabled()) {
            return null;
        }
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        if (run == null || run.baseCommit() == null || run.baseCommit().isBlank()) {
            return null;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph != null && graph.dependencies() != null) {
            for (TaskEdge edge : graph.dependencies()) {
                if (task.id().equals(edge.from())) {
                    return null;
                }
            }
        }
        String base = run.baseCommit();
        try {
            Set<String> own = new java.util.LinkedHashSet<>();
            for (String path : UnifiedDiffPaths.addedOrChanged(sol.diffUnified())) {
                String file = path.replace('\\', '/');
                if (ReachableCode.isProduction(file) && gitService.fileAt(base, file) == null) {
                    own.add(file);
                }
            }
            if (own.isEmpty()) {
                return null;
            }
            ReachableCode.Finding finding = ReachableCode.of(workspace)
                .judge(file -> gitService.fileAt(base, file) == null)
                .only(own::contains);
            if (finding.status() == ReachableCode.Status.UNDETERMINED
                    || !finding.note().isEmpty()) {
                log.info("Candidate {}: whether what it adds can be reached - {}", sol.id(),
                    finding.note());
            }
            return ReachableCode.objection(finding, "the candidate adds");
        } catch (RuntimeException e) {
            log.warn("Candidate {}: whether what it adds can be reached could not be "
                + "established ({}); not judged on it", sol.id(), e.toString());
            return null;
        }
    }

    /** A file's text as committed at the run's start commit; null when that cannot be read. */
    private java.util.function.Function<String, String> startFileOf(UUID runId) {
        if (runId == null || gitService == null || !gitService.isEnabled()) {
            return null;
        }
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        if (run == null || run.baseCommit() == null || run.baseCommit().isBlank()) {
            return null;
        }
        String base = run.baseCommit();
        return path -> {
            byte[] bytes = gitService.fileAt(base, path);
            return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    private VerificationBaseline baselineOf(UUID runId) {
        if (runId == null) {
            return VerificationBaseline.NONE;
        }
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        VerificationBaseline baseline = run == null ? VerificationBaseline.NONE
            : VerificationBaseline.of(run.baselineFailingTests());
        VerificationBaseline.StartCompile compiled = startCompileOfRun.get(runId);
        return compiled == null ? baseline : baseline.withStartCompile(compiled);
    }

    private void advanceProgress(Run run, WaveIntegrator integrator,
                                 List<WaveIntegrator.Winner> winners,
                                 Map<String, String> claimedBy, int waveIndex) {
        if (winners.isEmpty()) {
            log.warn("Wave {} produced no winner, so the next wave starts from the same tree this "
                + "one did", waveIndex + 1);
            return;
        }
        WaveIntegrator.Result result =
            integrator.integrateWave(run.id(), run.startPoint(), winners, claimedBy);
        if (!result.ok()) {
            throw new RunMustPark(result.failure());
        }
        if (result.commit() == null) {
            return; // git is off - nothing was merged and nothing needs recording
        }
        run.setProgressCommit(result.commit());
        try {
            artifactStore.updateRun(run);
        } catch (Exception e) {
            // The commit exists whether or not this line worked; what is lost is a RESUMED run
            // knowing about it, which would put the remaining waves back on the pinned base.
            log.warn("Failed to record the progress commit {} on run {}: {}", result.commit(),
                run.id(), e.getMessage());
        }
        log.info("Run {}: everything from wave {} and before is now on {} - the next wave builds "
            + "on it", run.id(), waveIndex + 1, shortSha(result.commit()));
    }

    private static String shortSha(String sha) {
        return sha == null ? "HEAD" : sha.substring(0, Math.min(8, sha.length()));
    }

    /**
     * Thrown out of a task's build when its workers showed that an earlier task's file is what
     * stands in their way, and the task has just been given that file to repair (see
     * {@link SiblingDefects}). Caught by {@link #executeTask}, which builds the task again — once:
     * {@link Task#siblingRepairPaths()} is set before this is thrown, and a task that has it is
     * never widened a second time.
     */
    private static final class RebuildWithSiblingRepair extends RuntimeException {
        RebuildWithSiblingRepair() {
            super("the task is built again with an earlier task's file to repair", null, false,
                false);
        }
    }

    private CandidateSolution executeTask(Task task, UUID runId, String startPoint,
                                          WaveBoard board) {
        try {
            return executeTaskOnce(task, runId, startPoint, board);
        } catch (RebuildWithSiblingRepair again) {
            log.info("Task '{}': building it again, now allowed to repair {}", task.title(),
                task.siblingRepairPaths());
            // The second build reuses the first one's branch names, so nothing of the first may
            // still be going when it starts.
            awaitCandidatesStillEnding(runId);
            return executeTaskOnce(task, runId, startPoint, board);
        }
    }

    /**
     * The candidate groups started for each run in flight, so that a candidate told to stop -
     * which only does so at its next turn - is waited for before the run is called over.
     */
    private final Map<UUID, java.util.Queue<CandidateGroup>> groupsOfRun =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Waits for every candidate of {@code runId} that was told to stop and has not ended yet.
     *
     * <p>A task does not wait for these: it goes on to judging and the wave goes on to the next
     * wave while they wind down, each for at most the turn it is in. But their worktrees and
     * their archive rows are written when they end, so the RUN waits for them once, here, before
     * it is reported finished.
     */
    private void awaitCandidatesStillEnding(UUID runId) {
        java.util.Queue<CandidateGroup> groups = groupsOfRun.get(runId);
        if (groups == null) {
            return;
        }
        CandidateGroup group;
        while ((group = groups.poll()) != null) {
            int running = group.running();
            if (running > 0) {
                log.info("Waiting for {} candidate(s) that were told to stop to reach the end of "
                    + "the turn they are in.", running);
            }
            group.awaitThreads();
        }
    }

    /** One task's first round of candidates: what has ended, and what is still being verified. */
    private static final class FirstRound {
        final List<CandidateSolution> verified = new ArrayList<>();
        int verifying;
        boolean passed;
        boolean closed;
        boolean verifyingSaid;
        RuntimeException failure;
    }

    /**
     * Dispatches a task's candidates and verifies each one as it ends (2026-10-02).
     *
     * <p>Until now every candidate ran to its end, then every one was verified, then the task went
     * on. A task whose first candidate had passed therefore waited for its second for no reason
     * but to have two. Now the task goes on as soon as it has a candidate that passed AND nothing
     * else in the wave is still looking for one: candidates that never started are withdrawn, and
     * candidates still in their session are stopped ({@code SUPERSEDED}) and wind down behind it.
     * While another task of the wave is still looking, the wave could not end anyway, so the
     * further candidates run on - on places that would otherwise be idle - and the judge gets to
     * choose among whatever passed. A candidate whose session has ended is always verified and
     * waited for: its work is done and paid for.
     *
     * @return every candidate that ended and was verified, in worker order
     */
    private List<CandidateSolution> firstRound(Task task, UUID runId, String startPoint,
                                               String knowledgeBrief, String guidelines,
                                               List<GuidelineCheck> guidelineChecks,
                                               List<WorkerResult> allResults, WaveBoard board) {
        FirstRound round = new FirstRound();
        // The workers are sent the rules for their task's part of the project; everything after
        // them in this method and its callers keeps `guidelines`, every rule (section 65).
        CandidateGroup group = dispatcher.start(task, runId, knowledgeBrief,
            rulesForWorkersOf(task, guidelines), startPoint, board, ended -> {
                if (ended.result() == null) {
                    return; // withdrawn before it started
                }
                boolean late;
                boolean sayVerifying = false;
                synchronized (round) {
                    late = round.closed;
                    if (!late) {
                        round.verifying++;
                        allResults.add(ended.result());
                        sayVerifying = !round.verifyingSaid;
                        round.verifyingSaid = true;
                    }
                }
                if (late) {
                    endedBehindItsTask(task, ended.result());
                    return;
                }
                if (sayVerifying) {
                    // VERIFY ALL — before anything model-judged sees a candidate (rule R2).
                    markTaskState(task, TaskState.VERIFYING);
                }
                CandidateSolution candidate = null;
                RuntimeException failure = null;
                try {
                    // Candidates verify in their own worktrees (sandboxes when enabled), so the
                    // verifications are independent and run side by side.
                    candidate = verifyOne(task, runId, ended.result(), guidelineChecks);
                } catch (RuntimeException e) {
                    failure = e;
                }
                synchronized (round) {
                    round.verifying--;
                    if (failure != null) {
                        round.failure = round.failure == null ? failure : round.failure;
                    } else {
                        round.verified.add(candidate);
                        if (candidate.state() == CandidateState.SURVIVED && !round.passed) {
                            round.passed = true;
                            if (board != null) {
                                board.hasPassingCandidate(task.id());
                            }
                        }
                    }
                }
            });
        groupsOfRun.computeIfAbsent(runId,
            id -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(group);

        boolean stoppedForOthers = false;
        boolean withdrewWaiting = false;
        int failedSoFar = 0;
        // Looked at every tenth of a second rather than waited for on the monitor: this is a
        // virtual thread, and it may be here for as long as the workers take.
        boolean over = false;
        while (!over) {
            synchronized (round) {
                if (round.failure != null) {
                    over = true;
                } else {
                    if (round.passed && !withdrewWaiting) {
                        // Nothing is started just to have two.
                        withdrewWaiting = true;
                        group.withdrawWaiting();
                    }
                    if (!round.passed && round.verifying == 0 && !group.handingIn()
                            && round.verified.size() > failedSoFar) {
                        // Something ended without passing, and nothing is still being verified.
                        // What is in its session now is what the task depends on; when nothing
                        // is, the next candidate waiting is.
                        failedSoFar = round.verified.size();
                        int needed = group.noPassingCandidateYet();
                        if (needed >= 0) {
                            log.info("Task '{}': nothing has passed and nothing is running, so worker "
                                + "{} no longer waits for a spare place - it waits its turn like a "
                                + "first candidate.", task.title(), needed);
                        }
                    }
                    if (round.verifying == 0 && group.allEnded()) {
                        over = true; // everything that started has ended and been verified
                    } else if (round.passed && round.verifying == 0 && !group.handingIn()
                            && (board == null || !board.othersStillLooking(task.id()))) {
                        stoppedForOthers = true;
                        over = true; // only duplicates are left; nothing is gained by waiting for them
                    }
                }
                if (over) {
                    round.closed = true;
                }
            }
            if (!over) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    synchronized (round) {
                        round.closed = true;
                    }
                    over = true;
                }
            }
        }
        List<Integer> withdrawn = group.withdrawWaiting();
        List<Integer> stopped = group.stopRunning(KillReason.SUPERSEDED);
        if (stoppedForOthers && (!withdrawn.isEmpty() || !stopped.isEmpty())) {
            log.info("Task '{}' has a candidate that passed verification and no other task of this "
                + "wave is still looking for one, so it goes on without its duplicates: worker(s) "
                + "{} stop at their next turn, worker(s) {} are not started.", task.title(),
                stopped, withdrawn);
        }
        if (round.failure != null) {
            // The task is failing as a whole. Its clean-up removes every worktree, so nothing of
            // it may still be working in one.
            group.awaitThreads();
            throw round.failure;
        }
        List<CandidateSolution> verified;
        synchronized (round) {
            verified = new ArrayList<>(round.verified);
        }
        verified.sort(Comparator.comparingInt(CandidateSolution::workerIndex));
        return verified;
    }

    /**
     * A candidate that was told to stop and ended after its task had gone on. Its record is
     * kept - the archive is the evaluation dataset, and "stopped because another attempt had
     * passed" is a fact about the run - and its worktree is removed here, since the task's own
     * clean-up has already been and gone.
     */
    private void endedBehindItsTask(Task task, WorkerResult result) {
        CandidateSolution candidate = result.candidate();
        if (candidate.state() == CandidateState.SURVIVED) {
            // It finished in the very turn it was told to stop. Nobody verified it and nobody
            // will: recorded as stopped, never as a candidate that passed.
            candidate = new CandidateSolution(candidate.id(), candidate.taskId(),
                candidate.workerIndex(), candidate.branch(), candidate.sampling(),
                candidate.diffUnified(), null, null, null, CandidateState.KILLED,
                KillReason.SUPERSEDED).carryingAuditFrom(candidate);
        }
        log.info("Task '{}': worker {} ended behind its task ({}) and is archived as that.",
            task.title(), candidate.workerIndex(),
            candidate.killReason() == null ? candidate.state() : candidate.killReason());
        archiveCandidates(List.of(candidate), null);
        if (result.workspace() != null && gitService.isEnabled()) {
            gitService.removeWorktree(result.workspace());
        }
    }

    private CandidateSolution executeTaskOnce(Task task, UUID runId, String startPoint,
                                              WaveBoard board) {
        // A retry after an outage re-enters executeRun from the top, so a task that already has a
        // winner must not be swarmed again. Auto-retry is only permitted because it stays inside the
        // original budget (UX v3 rule 6); re-dispatching finished tasks is how it would leave it.
        CandidateSolution alreadyWon = archivedWinner(task);
        if (alreadyWon != null) {
            log.info("Task '{}' already selected a winner — not dispatching again", task.title());
            return alreadyWon;
        }
        // A question about a rule this task raised before the run stopped: answered, it is applied
        // and the task re-selects from the candidates it already has; unanswered, the run stops
        // behind it again rather than paying for a second swarm nobody asked for.
        CandidateSolution reselected = answeredRuleQuestion(task, runId);
        if (reselected != null) {
            return reselected;
        }
        // BLOCKED a moment ago by resumeAfterTestReview, question raised: the workflow re-enters
        // executeRun for the run's other tasks, and this one must not be swarmed a second time.
        if (blockedAfterReview.remove(task.id())) {
            log.info("Task '{}' was BLOCKED after its test's review and its repair round - not "
                + "dispatching again", task.title());
            return null;
        }
        log.info("Processing task: {}", task.title());

        List<WorkerResult> allResults = Collections.synchronizedList(new ArrayList<>());
        String knowledgeBrief = resolveBrief(task);
        String guidelines = guidelinesProvider == null ? null : guidelinesProvider.get();
        // Resolved ONCE for the whole task, from the operator's tree, before any worker runs. Every
        // candidate of this task is then measured against the identical rule set — re-reading per
        // candidate would let a mid-task edit judge two workers by different rules.
        List<GuidelineCheck> guidelineChecks = guidelineChecksProvider.get();
        if (guidelineChecks != null && !guidelineChecks.isEmpty()) {
            log.info("Task '{}': {} house rule(s) declare a check that decides survival: {}",
                task.title(), guidelineChecks.size(),
                guidelineChecks.stream().map(GuidelineCheck::slug).sorted().toList());
        }
        try {
            // 1. Dispatch the swarm, and 2. VERIFY each candidate as it ends — before anything
            // model-judged sees it (rule R2). See firstRound for when the task goes on.
            markTaskState(task, TaskState.DISPATCHED);
            List<CandidateSolution> verified = firstRound(task, runId, startPoint, knowledgeBrief,
                guidelines, guidelineChecks, allResults, board);
            markTaskState(task, TaskState.VERIFYING);
            List<CandidateSolution> survivors = filterSurvived(verified);
            log.info("Task '{}': {}/{} candidates survived verification",
                task.title(), survivors.size(), verified.size());

            List<CandidateSolution> archivePool = new ArrayList<>(verified);
            // Archived HERE, as soon as the verdicts exist, and archived again at the end with the
            // judged copies (the store is keyed by candidate id, so the second write replaces the
            // first). Until this line the only archive happened after judging, which meant that
            // for the whole of a repair round - minutes, since repair workers write code - the
            // Console had no record of the wave that had just failed. The operator watched four
            // chips go red-less and vanish, and read four new ones as a restart. A verdict that
            // exists and is not published is a verdict nobody has.
            archiveCandidates(verified, null);

            // 2b. Repair round — one, then BLOCKED (spec §11.5). Not entered when the wave was lost
            // to an outage: there is nothing to repair and no evidence to repair it from.
            if (survivors.isEmpty()) {
                failIfLostToOutage(task, verified);
                // Every candidate died on the SAME bug, and it is the acceptance test's own — never
                // the candidates' (author decision, 2026-09-05). A repair round here would spend
                // workers "fixing" code that was never exercised, chasing a cause they have no path
                // to reach, because the test they cannot edit is what is broken. Bounded to one
                // attempt per task by testRepairNeededFor itself.
                TestRepairNeeded testFault = testRepairNeededFor(task, verified);
                if (testFault != null) {
                    task.setTestRepairAttempted(true);
                    try {
                        artifactStore.storeChanged(task).get();
                    } catch (Exception e) {
                        log.warn("Failed to persist task '{}' testRepairAttempted: {}",
                            task.title(), e.getMessage());
                    }
                    log.warn("Task '{}': {} — sending the test back to its author {}: {}",
                        task.title(),
                        testFault.suspect()
                            ? "every first candidate compiled and failed the same acceptance "
                                + "test with the same assertion"
                            : testFault.reachedBrowserOnlyCode()
                            ? "every verified candidate died because the acceptance test reached "
                                + "code that can only run in a browser"
                            : testFault.doesNotCompile()
                                ? "every verified candidate failed on the same compile error in "
                                    + "the acceptance test, one no candidate can fix"
                                : "every candidate failed inside the acceptance test's own code",
                        testFault.suspect() ? "before any repair round, to say which side is wrong"
                            : "instead of repairing candidates that were never broken",
                        testFault.getMessage());
                    throw testFault;
                }
                // Every candidate failed on the same compile error in a file the task may not
                // write (live run 74, 2026-10-03): a repair round with the same write set cannot
                // succeed, so none is started. See RepairCannotHelp.
                RepairCannotHelp.Finding outside =
                    RepairCannotHelp.find(task, verified, protectedPaths);
                if (outside != null) {
                    widenOrBlameThePlan(task, runId, outside, archivePool);
                    return null;
                }
                // Every candidate wrote the same source file of a task that has not run yet
                // (live run 90, 2026-10-07): the plan ran the two in the wrong order, and a
                // repair round with the same order cannot succeed. See FileOfATaskNotYetRun.
                FileOfATaskNotYetRun.Finding wrongOrder =
                    FileOfATaskNotYetRun.find(task, verified, wavesOf(runId));
                if (wrongOrder != null) {
                    log.warn("Task '{}': every verified candidate ({}) wrote {}, outside the "
                        + "task's write set and owned by {}, which the plan runs beside or after "
                        + "it. No repair round - it could not succeed. BLOCKED: the plan is at "
                        + "fault, not the candidates.", task.title(), wrongOrder.candidates(),
                        wrongOrder.files().keySet(), wrongOrder.owners());
                    markTaskState(task, TaskState.BLOCKED);
                    queueBlockedDecision(task, runId, archivePool,
                        FileOfATaskNotYetRun.planBlame(task, wrongOrder));
                    archiveCandidates(archivePool, null);
                    return null;
                }
                final long repairStarted = System.currentTimeMillis();
                List<CandidateSolution> repaired = repairRound(task, runId, verified, allResults,
                    knowledgeBrief, guidelines, guidelineChecks);
                com.swarmcoder.inference.RunMeter.span("repair round|" + task.id(), repairStarted);
                archivePool.addAll(repaired);
                survivors = filterSurvived(repaired);
                if (survivors.isEmpty()) {
                    // The endpoint can also die mid-repair; same rule.
                    failIfLostToOutage(task, repaired);
                    // Nothing survived, and the workers said — with evidence — that an earlier
                    // task's file is why: one more build with that file to repair, before BLOCKED.
                    repairSiblingInsteadOfBlocking(task, runId, archivePool);
                    log.warn("Task '{}': zero survivors after repair — BLOCKED", task.title());
                    markTaskState(task, TaskState.BLOCKED);
                    // Workers that found nothing to change after a verification objection say so,
                    // with the objection quoted (harness run 72); still BLOCKED, never passed.
                    String noChange = NoChangeRepair.headline(task.title(), verified, repaired);
                    if (noChange != null) {
                        log.warn("Task '{}': every repair worker found nothing to change; the "
                            + "objection is a suspected false alarm in the check", task.title());
                        queueBlockedDecision(task, runId, archivePool, noChange);
                    } else {
                        queueBlockedDecision(task, runId, archivePool);
                    }
                    archiveCandidates(archivePool, null);
                    return null;
                }
            }

            return finishTask(task, runId, survivors, archivePool, knowledgeBrief, guidelines,
                guidelineChecks, allResults);
        } finally {
            // Worktrees are per-candidate scratch space; branches remain for archival.
            synchronized (allResults) {
                for (WorkerResult result : allResults) {
                    if (result.workspace() != null && gitService.isEnabled()) {
                        gitService.removeWorktree(result.workspace());
                    }
                }
            }
        }
    }

    /**
     * The repair round (spec §11.5): seed small swarms from the most promising failed
     * candidates' branches, with the failure evidence in the prompt. Repairs are verified
     * AS THEY COMPLETE; the first survivor supersedes every other in-flight repair worker.
     */
    /** The task's KnowledgeBrief markdown, when the Librarian attached one (spec §13). */
    private String resolveBrief(Task task) {
        if (task.knowledgeBriefId() == null) {
            return null;
        }
        KnowledgeBrief brief = artifactStore.root().briefs.get(task.knowledgeBriefId());
        return brief == null ? null : brief.renderedMarkdown();
    }

    /** Minutes a repair round may run before its unfinished workers are stopped. */
    static final String REPAIR_ROUND_MINUTES_PROPERTY = "swarmcoder.repair.roundMinutes";
    static final int DEFAULT_REPAIR_ROUND_MINUTES = 30;

    /** Milliseconds, so a test can state a ceiling shorter than a minute. */
    static final String REPAIR_ROUND_MILLIS_PROPERTY = "swarmcoder.repair.roundMillis";

    static long repairRoundCeilingMillis() {
        long millis = Long.getLong(REPAIR_ROUND_MILLIS_PROPERTY, 0L);
        if (millis > 0) {
            return millis;
        }
        int minutes = Integer.getInteger(REPAIR_ROUND_MINUTES_PROPERTY,
            DEFAULT_REPAIR_ROUND_MINUTES);
        return (minutes <= 0 ? DEFAULT_REPAIR_ROUND_MINUTES : minutes) * 60_000L;
    }

    private List<CandidateSolution> repairRound(Task task, UUID runId,
                                                List<CandidateSolution> failed,
                                                List<WorkerResult> allResults,
                                                String knowledgeBrief, String guidelines,
                                                List<GuidelineCheck> guidelineChecks) {
        List<CandidateSolution> seeds = failed.stream()
            .filter(c -> c.verification() != null && c.branch() != null)
            .sorted((a, b) -> {
                int byAcceptance = Integer.compare(passed(b), passed(a));
                if (byAcceptance != 0) {
                    return byAcceptance;
                }
                return Boolean.compare(b.verification().compiles(), a.verification().compiles());
            })
            .limit(REPAIR_SEEDS)
            .toList();
        if (seeds.isEmpty()) {
            log.warn("Task '{}': no repairable candidates (none verified) — skipping repair", task.title());
            return List.of();
        }

        // The phase the box was silent about. A task being repaired is not a task being checked,
        // and the difference is the whole of what the operator was missing.
        markTaskState(task, TaskState.REPAIRING);
        GroupSignal signal = new GroupSignal();
        List<CandidateSolution> repaired = Collections.synchronizedList(new ArrayList<>());
        // Repair workers are workers: the rules for the task's part of the project (section 65).
        String workerRulesText = rulesForWorkersOf(task, guidelines);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> chained = new ArrayList<>();
            for (CandidateSolution seed : seeds) {
                // Named after the attempt it is fixing, not after its rank in the sort above:
                // w120 and w121 are the two goes at what w2 got wrong. The rank is an internal
                // fact and encoding it produced numbers nothing outside this method could read.
                int indexOffset = RepairIndex.of(seed.workerIndex(), 0);
                // A seed that passed its own verification and is repaired because a journey
                // failed has no failure of its own to show: the journey's is the evidence.
                String journey = journeyFailures.get(task.id());
                for (CompletableFuture<WorkerResult> future : dispatcher.dispatchRepair(
                        task, runId, seed.branch(),
                        journey != null ? journey : failureEvidence(seed) + reviewEvidence(task),
                        REPAIR_WORKERS_PER_SEED, indexOffset, signal, executor, knowledgeBrief,
                        workerRulesText)) {
                    chained.add(future.thenAccept(result -> {
                        allResults.add(result);
                        CandidateSolution candidate = verifyOne(task, runId, result, guidelineChecks);
                        repaired.add(candidate);
                        if (candidate.state() == CandidateState.SURVIVED) {
                            // First green repair wins the wave; siblings die SUPERSEDED.
                            signal.supersede();
                        }
                    }));
                }
            }
            CompletableFuture<Void> all =
                CompletableFuture.allOf(chained.toArray(new CompletableFuture[0]));
            // A safety stop, not a budget (live run 74: two repair rounds ran 74 and 66 minutes).
            // At the ceiling the workers still going are stopped; each one's change, if it made
            // one, is verified like any other stopped worker's (StoppedWork), so the join below
            // still waits for every verdict.
            long ceiling = repairRoundCeilingMillis();
            try {
                all.get(ceiling, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException late) {
                if (signal.stop(KillReason.ROUND_TIME_UP)) {
                    log.warn("Task '{}': the repair round has run {} minutes, its ceiling. The "
                        + "repair workers still going are stopped, and whatever each has "
                        + "changed is verified. (-D{} changes the ceiling.)", task.title(),
                        ceiling / 60_000, REPAIR_ROUND_MINUTES_PROPERTY);
                    com.swarmcoder.inference.RunMeter.span("repair round stopped at its ceiling|"
                        + task.id(), System.currentTimeMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                signal.stop(KillReason.SUPERSEDED);
            } catch (java.util.concurrent.ExecutionException e) {
                // surfaced by the join below, as it always was
            }
            all.join();
        }
        log.info("Task '{}': repair produced {}/{} survivors", task.title(),
            filterSurvived(repaired).size(), repaired.size());
        return new ArrayList<>(repaired);
    }

    /**
     * Null unless every candidate verified for this task failed on the acceptance test's own bug —
     * every one of them, and every one of THOSE failures never left the failing test's own class
     * (see {@link com.swarmcoder.verify.AcceptanceFailureAttribution} and
     * {@link com.swarmcoder.domain.TestFailure#insideTestItself()}). Also null when this task has
     * already had its one bounded attempt ({@link Task#testRepairAttempted()}) — a second broken
     * test gets the normal repair round (futile as that usually is), not a second trip to the
     * author.
     *
     * <p><b>The second shape: the test reached code that can only run in a browser</b> (harness
     * run 37, 2026-09-25). Both candidates of "Create the client BookStore singleton" died with
     * {@code UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()} (Native Method): the
     * acceptance test called the TeaVM client module from a JUnit test on a plain JVM. The trace ran
     * through each candidate's own BookStore, so the rule above — "never left the test's own class"
     * — was false, and the run spent a repair round of two more workers on a test no code could
     * ever pass. {@link BrowserOnlyCode#reachedIn} recognises that failure, and when every candidate
     * that produced a verdict died on it, the test goes back to its author exactly as a crashed test
     * does. A candidate killed before it was verified (run 37's third worker, {@code NO_PROGRESS})
     * produced no verdict and is not counted against the evidence of the ones that did; at least
     * one verified candidate is required.
     *
     * <p>Package-private and static so it can be proved directly against hand-built candidates —
     * see {@code TestRepairNeededTest} — the same way {@link #failureEvidence} is.
     */
    static TestRepairNeeded testRepairNeededFor(Task task, List<CandidateSolution> verified) {
        if (task == null || task.testRepairAttempted() || verified == null || verified.isEmpty()) {
            return null;
        }
        TestRepairNeeded insideTheTest = insideTheTestFault(task, verified);
        if (insideTheTest != null) {
            return insideTheTest;
        }
        TestRepairNeeded browserOnly = browserOnlyFault(task, verified);
        if (browserOnly != null) {
            return browserOnly;
        }
        TestRepairNeeded miscompiled = miscompiledFault(task, verified);
        // The fourth shape (run 79): not a broken test but a suspect one - see the class.
        return miscompiled != null ? miscompiled : SameFailureForEveryCandidate.find(task, verified);
    }

    /**
     * <b>The third shape: the test can never compile</b> (brownfield harness run 43, 2026-09-26).
     * Both candidates of "Guard Document.ensureMetaCharsetElement against empty XML documents"
     * wrote the right guard and FAILED verification with "the tree does not compile before this
     * candidate's change: src/test/java/swarm/accept/DocumentTest.java:16 incompatible types:
     * org.jsoup.parser.Parser cannot be converted to java.lang.String". The repair round then sent
     * four workers at it; each found the guard already in its checkout and the test outside what
     * it may edit, and finished without a change. The task was BLOCKED.
     *
     * <p>Raised when every candidate that produced a verdict failed to compile, the compile failure
     * was attributed to the tree rather than to the candidate ({@link CompileFailureCause#PRE_EXISTING}),
     * the file is the task's protected acceptance test, every candidate names the SAME file, line and
     * message, and that message is a MISUSE — everything it names exists and the test uses it
     * wrongly — rather than an absence ({@link AcceptanceCompileErrors#isMisuseMessage}). An absence
     * ("cannot find symbol", "does not exist") stays with the candidates: a symbol this task was
     * meant to deliver and did not is their fault, and the ordinary repair round is right for it.
     *
     * <p>Honest about its one blind spot: a task whose contract changes an existing signature, where
     * every candidate identically failed to change it, would read the same. The test author's
     * correction is then red-checked on the pre-change tree and re-classified there, with the plan's
     * promised changes in hand ({@code GreenfieldWorkflow.repairFaultyAcceptanceTest}), so a
     * correction that rewrote the test to the old signature cannot slip through as a tautology.
     */
    private static TestRepairNeeded miscompiledFault(Task task, List<CandidateSolution> verified) {
        CompileFailure first = null;
        for (CandidateSolution candidate : verified) {
            if (candidate == null || candidate.verification() == null) {
                continue; // no verdict, no evidence either way
            }
            CompileFailure failure = miscompiledTest(task, candidate);
            if (failure == null) {
                return null; // this candidate did not die on the test's compile error
            }
            if (first == null) {
                first = failure;
            } else if (!java.util.Objects.equals(first.file(), failure.file())
                    || first.line() != failure.line()
                    || !java.util.Objects.equals(first.message(), failure.message())) {
                return null; // not the SAME error for everybody
            }
        }
        if (first == null) {
            return null;
        }
        String where = first.file() + (first.line() > 0 ? ":" + first.line() : "");
        // In javac's own format, so the workflow can read it back with AcceptanceCompileErrors
        // exactly as it reads a red check's compile output.
        String lines = first.file() + ":" + Math.max(first.line(), 0) + ": error: "
            + javacWording(first.message())
            + (first.failingFiles() != null && first.failingFiles().size() > 1
                ? "\n(the compiler also reported errors in " + (first.failingFiles().size() - 1)
                    + " more file(s): " + first.failingFiles().subList(1, first.failingFiles().size())
                    + ")"
                : "");
        return TestRepairNeeded.doesNotCompile(task.id(), testClassOfPath(first.file()), first.file(),
            where + " " + first.message(), lines);
    }

    /** The candidate's compile failure when it is a misuse in the protected acceptance test; else null. */
    private static CompileFailure miscompiledTest(Task task, CandidateSolution candidate) {
        VerificationReport report = candidate.verification();
        if (report == null || report.compiles()) {
            return null;
        }
        CompileFailure failure = report.compileFailure();
        if (failure == null || failure.cause() != CompileFailureCause.PRE_EXISTING
                || !failure.acceptanceTest() || failure.file() == null
                || !(AcceptanceCompileErrors.isMisuseMessage(failure.message())
                    || AcceptanceCompileErrors.absenceNoTaskCanSupply(failure.message(),
                        failure.file(), task))) {
            return null;
        }
        return failure;
    }

    /**
     * The verifier words an absence as {@code cannot find symbol: method foo(int) in class a.B};
     * javac prints it over three lines. Put back into javac's shape so the workflow reads the
     * missing member and its owner with the same code that reads a red check's output; any other
     * message passes through unchanged.
     */
    private static String javacWording(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^cannot find symbol:\\s+(\\S+)\\s+(.+?)\\s+in\\s+"
                + "((?:class|interface|enum|record|@interface|variable)\\s+.+)$")
            .matcher(message == null ? "" : message.strip());
        return m.matches()
            ? "cannot find symbol\n  symbol:   " + m.group(1) + " " + m.group(2)
                + "\n  location: " + m.group(3)
            : message;
    }

    /** {@code src/test/java/swarm/accept/DocumentTest.java} → {@code swarm.accept.DocumentTest}. */
    static String testClassOfPath(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        for (String root : List.of("src/test/java/", "src/test/kotlin/", "src/test/")) {
            int at = p.indexOf(root);
            if (at >= 0) {
                p = p.substring(at + root.length());
                break;
            }
        }
        int dot = p.lastIndexOf('.');
        if (dot > p.lastIndexOf('/')) {
            p = p.substring(0, dot);
        }
        return p.replace('/', '.');
    }

    /**
     * True when every re-verified candidate still fails on a misuse compile error in the protected
     * acceptance test — the correction of a test that could not compile did not hold (harness run
     * 43). The same reading {@link #miscompiledFault} gives, minus the "same line for everybody"
     * requirement: any such error in the corrected test is the test's.
     */
    private static boolean allMiscompiled(Task task, List<CandidateSolution> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (CandidateSolution candidate : candidates) {
            if (candidate == null || candidate.verification() == null
                    || miscompiledTest(task, candidate) == null) {
                return false;
            }
        }
        return true;
    }

    /** The original rule: every candidate's first failure never left the test's own class. */
    private static TestRepairNeeded insideTheTestFault(Task task, List<CandidateSolution> verified) {
        TestFailure first = null;
        for (CandidateSolution candidate : verified) {
            TestFailure failure = firstAcceptanceFailure(candidate);
            if (failure == null || !failure.insideTestItself()) {
                return null; // not every candidate died on the SAME kind of failure
            }
            if (first == null) {
                first = failure;
            }
        }
        String testId = first.testId() == null ? "" : first.testId();
        int hash = testId.indexOf('#');
        String testClass = hash >= 0 ? testId.substring(0, hash) : testId;
        String testMethod = hash >= 0 ? testId.substring(hash + 1) : null;
        return new TestRepairNeeded(task.id(), testClass, testMethod, first.message(),
            first.truncatedTrace());
    }

    /**
     * Every candidate that was verified died because the test reached browser-only code — see the
     * javadoc of {@link #testRepairNeededFor}. Null otherwise.
     */
    private static TestRepairNeeded browserOnlyFault(Task task, List<CandidateSolution> verified) {
        TestFailure first = null;
        String reason = null;
        for (CandidateSolution candidate : verified) {
            if (candidate == null || candidate.verification() == null) {
                continue; // no verdict, no evidence either way
            }
            TestFailure failure = firstAcceptanceFailure(candidate);
            String reached = BrowserOnlyCode.reachedIn(failure);
            if (reached == null) {
                return null; // this candidate failed for a reason of its own
            }
            if (first == null) {
                first = failure;
                reason = reached;
            }
        }
        if (first == null) {
            return null;
        }
        String testId = first.testId() == null ? "" : first.testId();
        int hash = testId.indexOf('#');
        String testClass = hash >= 0 ? testId.substring(0, hash) : testId;
        String testMethod = hash >= 0 ? testId.substring(hash + 1) : null;
        return new TestRepairNeeded(task.id(), testClass, testMethod, first.message(),
            first.truncatedTrace(), reason);
    }

    /** The failing test's class name out of its testId ({@code <class>#<method>}); "(unknown)" for null. */
    private static String testClassOf(TestFailure failure) {
        if (failure == null || failure.testId() == null) {
            return "(unknown)";
        }
        int hash = failure.testId().indexOf('#');
        return hash >= 0 ? failure.testId().substring(0, hash) : failure.testId();
    }

    /** The first acceptance failure a candidate's verification recorded, or null. */
    private static TestFailure firstAcceptanceFailure(CandidateSolution candidate) {
        if (candidate == null || candidate.verification() == null) {
            return null;
        }
        TestResults acceptance = candidate.verification().acceptance();
        if (acceptance == null || acceptance.failures() == null || acceptance.failures().isEmpty()) {
            return null;
        }
        return acceptance.failures().get(0);
    }

    /**
     * Resumes a task whose acceptance test was sent back to its author and corrected, on the SAME
     * candidates already verified against the broken test — no new swarm (author decision,
     * 2026-09-05). Called by {@code GreenfieldWorkflow} once the corrected test is committed and
     * {@code run.acceptanceTestsCommit()} points at it.
     *
     * <p>Each candidate is rebuilt from its own branch (worktrees are scratch space and were removed
     * at the end of the failed attempt; branches are archival and were kept), re-verified with
     * {@link #verifyOne} — which reads the run's {@code acceptanceTestsCommit} fresh from the store,
     * so it places the CORRECTED test without any change here — and, on a survivor, finished exactly
     * as {@link #executeTask} would from that point on: cluster, judge, select.
     *
     * @return the task's winner and whether the corrected test is still at fault; {@code winner()}
     *         is null either when nothing survived for an ordinary reason (BLOCKED, exactly as
     *         {@link #executeTask} records it) or when the correction did not hold — told apart by
     *         {@link TestRepairOutcome#stillFaulty()}, which the caller turns into a run park
     */
    public TestRepairOutcome resumeAfterTestRepair(Task task, UUID runId) {
        List<CandidateSolution> previous = new ArrayList<>();
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && task.id().equals(candidate.taskId()) && candidate.branch() != null) {
                previous.add(candidate);
            }
        }
        if (previous.isEmpty()) {
            log.warn("Task '{}': no archived candidate with a branch to re-verify against the "
                + "corrected test", task.title());
            return new TestRepairOutcome(null, true,
                "no candidate of task '" + task.title() + "' has a branch left to re-verify");
        }
        String knowledgeBrief = resolveBrief(task);
        String guidelines = guidelinesProvider == null ? null : guidelinesProvider.get();
        List<GuidelineCheck> guidelineChecks = guidelineChecksProvider.get();
        List<WorkerResult> allResults = Collections.synchronizedList(new ArrayList<>());
        List<CandidateSolution> reverified = new ArrayList<>();
        for (CandidateSolution candidate : previous) {
            reverified.add(reverifyOne(task, runId, candidate, guidelineChecks, allResults));
        }
        List<CandidateSolution> survivors = filterSurvived(reverified);
        log.info("Task '{}': re-verification against the corrected acceptance test produced {}/{} "
            + "survivor(s)", task.title(), survivors.size(), reverified.size());
        if (survivors.isEmpty()) {
            archiveCandidates(reverified, null);
            // testRepairNeededFor itself would refuse here — task.testRepairAttempted() is already
            // true, set right before the ORIGINAL throw — which is exactly right for deciding
            // whether to try the test author a second time (never), but not for deciding whether
            // THIS correction held. allInsideTestItself asks the one question that matters here.
            if (allMiscompiled(task, reverified)) {
                CompileFailure failure = reverified.get(0).verification().compileFailure();
                String message = "acceptance test " + testClassOfPath(failure.file())
                    + " still does not compile for any candidate: " + failure.file()
                    + (failure.line() > 0 ? ":" + failure.line() : "") + " " + failure.message();
                log.warn("Task '{}': the corrected acceptance test is STILL at fault: {}",
                    task.title(), message);
                return new TestRepairOutcome(null, true, message);
            }
            if (allInsideTestItself(reverified) || allReachedBrowserOnlyCode(reverified)) {
                TestFailure failure = firstAcceptanceFailure(reverified.get(0));
                String browserOnly = BrowserOnlyCode.reachedIn(failure);
                String message = "acceptance test " + testClassOf(failure)
                    + (browserOnly != null ? " still cannot run on the JVM: " + browserOnly
                        : " still fails inside its own code"
                            + (failure == null || failure.message() == null
                                || failure.message().isBlank() ? "" : ": " + failure.message()));
                log.warn("Task '{}': the corrected acceptance test is STILL at fault: {}",
                    task.title(), message);
                return new TestRepairOutcome(null, true, message);
            }
            // The candidates now fail for a real reason of their own — the fix held, it simply
            // found nothing good enough. That is the ordinary BLOCKED path, not a still-faulty test.
            log.warn("Task '{}': zero survivors against the corrected test — BLOCKED", task.title());
            markTaskState(task, TaskState.BLOCKED);
            queueBlockedDecision(task, runId, reverified);
            return new TestRepairOutcome(null, false, null);
        }
        List<CandidateSolution> archivePool = new ArrayList<>(reverified);
        // There is no wave base in hand here to build the task again from, so an earlier task's
        // defective file is not repaired on this path: a dispute over it is the plain question.
        noSiblingRepair.add(task.id());
        try {
            CandidateSolution winner = finishTask(task, runId, survivors, archivePool,
                knowledgeBrief, guidelines, guidelineChecks, allResults);
            return new TestRepairOutcome(winner, false, null);
        } finally {
            noSiblingRepair.remove(task.id());
        }
    }

    /** What the browser said about a task's failed journey, while its repair round runs. */
    private final Map<UUID, String> journeyFailures = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sends a task back to the workers because the journey it claims failed in a real browser
     * after the last merge (section 63; owner's decision 2026-10-05: journeys are made at final
     * integration only, and a failure there is repaired like any failed verification).
     *
     * <p>One ordinary repair round, seeded from the task's chosen candidate, with the failing
     * step as the evidence. Each repaired candidate is verified exactly as any candidate is -
     * the journey itself is not made per candidate - and a survivor is finished as usual and
     * becomes the task's choice; the earlier choice is kept aside. The caller then merges and
     * verifies the run again, where the journey is made again.
     *
     * @return the task's new choice, or null when nothing survived; the earlier choice then
     *         stands and the caller stops the run on the journey's failure
     */
    public CandidateSolution repairAfterFailedJourney(Task task, UUID runId, String evidence) {
        CandidateSolution chosen = archivedWinner(task);
        if (chosen == null || chosen.branch() == null || chosen.verification() == null
                || gitService == null || !gitService.isEnabled()) {
            log.warn("Task '{}': no chosen candidate with a branch to repair after its journey "
                + "failed", task.title());
            return null;
        }
        String knowledgeBrief = resolveBrief(task);
        String guidelines = guidelinesProvider == null ? null : guidelinesProvider.get();
        List<GuidelineCheck> guidelineChecks = guidelineChecksProvider.get();
        List<WorkerResult> allResults = Collections.synchronizedList(new ArrayList<>());
        TaskState before = task.state();
        journeyFailures.put(task.id(), evidence == null ? "" : evidence);
        noSiblingRepair.add(task.id());
        try {
            final long repairStarted = System.currentTimeMillis();
            List<CandidateSolution> repaired = repairRound(task, runId, List.of(chosen), allResults,
                knowledgeBrief, guidelines, guidelineChecks);
            com.swarmcoder.inference.RunMeter.span("repair round|" + task.id(), repairStarted);
            List<CandidateSolution> survivors = filterSurvived(repaired);
            if (survivors.isEmpty()) {
                failIfLostToOutage(task, repaired);
                log.warn("Task '{}': no repair after the failed journey passed verification; "
                    + "the earlier choice stands", task.title());
                archiveCandidates(repaired, null);
                markTaskState(task, before == null ? TaskState.SELECTED : before);
                return null;
            }
            List<CandidateSolution> archivePool = new ArrayList<>(repaired);
            CandidateSolution winner = finishTask(task, runId, survivors, archivePool,
                knowledgeBrief, guidelines, guidelineChecks, allResults);
            if (winner == null) {
                // Nothing was chosen after all: the earlier choice is still the task's.
                markTaskState(task, before == null ? TaskState.SELECTED : before);
                return null;
            }
            // A task has one choice. The earlier one is kept aside only now that there is a new
            // one, so a repair that stops half way leaves the task with the choice it had.
            CandidateSolution aside = new CandidateSolution(chosen.id(), chosen.taskId(),
                chosen.workerIndex(), chosen.branch(), chosen.sampling(), chosen.diffUnified(),
                chosen.verification(), chosen.cluster(), chosen.judge(), CandidateState.ARCHIVED,
                chosen.killReason()).carryingAuditFrom(chosen);
            archiveCandidates(List.of(aside), null);
            return winner;
        } finally {
            journeyFailures.remove(task.id());
            noSiblingRepair.remove(task.id());
            synchronized (allResults) {
                for (WorkerResult result : allResults) {
                    if (result.workspace() != null && gitService.isEnabled()) {
                        gitService.removeWorktree(result.workspace());
                    }
                }
            }
        }
    }

    /** What the test author answered about a suspect test, by task, while its repair round runs. */
    private final Map<UUID, String> testReviews = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tasks {@link #resumeAfterTestReview} left BLOCKED, until executeRun next meets them. */
    private final Set<UUID> blockedAfterReview = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What a repair worker is told about the review of the test it failed; "" when none. */
    private String reviewEvidence(Task task) {
        String review = testReviews.get(task.id());
        return review == null ? "" : "\n--- the acceptance test was reviewed ---\n" + review + "\n";
    }

    /**
     * Carries on with a task whose SUSPECT acceptance test went back to its author
     * ({@link SameFailureForEveryCandidate}; owner decision, 2026-10-04).
     *
     * <p>When the author corrected the test, the candidates already written are re-verified
     * against the correction, exactly as {@link #resumeAfterTestRepair} does, and a survivor is
     * finished as usual. When nothing survives, or the author answered that the test is right,
     * or the author could not be asked, the task gets what it would have had without the review:
     * the one repair round and then BLOCKED - with the author's answer in every repair worker's
     * evidence and in the operator's question.
     *
     * @param corrected true when a corrected test is committed and the run points at it
     * @param review    what the review came to, in one or two sentences the operator can read
     */
    public TestRepairOutcome resumeAfterTestReview(Task task, UUID runId, boolean corrected,
                                                   String review) {
        List<CandidateSolution> previous = new ArrayList<>();
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && task.id().equals(candidate.taskId()) && candidate.branch() != null) {
                previous.add(candidate);
            }
        }
        if (previous.isEmpty()) {
            return new TestRepairOutcome(null, true,
                "no candidate of task '" + task.title() + "' has a branch left to carry on from");
        }
        String knowledgeBrief = resolveBrief(task);
        String guidelines = guidelinesProvider == null ? null : guidelinesProvider.get();
        List<GuidelineCheck> guidelineChecks = guidelineChecksProvider.get();
        List<WorkerResult> allResults = Collections.synchronizedList(new ArrayList<>());
        if (review != null && !review.isBlank()) {
            testReviews.put(task.id(), review.strip());
        }
        noSiblingRepair.add(task.id());
        try {
            List<CandidateSolution> current = previous;
            List<CandidateSolution> archivePool = new ArrayList<>();
            List<CandidateSolution> survivors = List.of();
            if (corrected) {
                List<CandidateSolution> reverified = new ArrayList<>();
                for (CandidateSolution candidate : previous) {
                    reverified.add(reverifyOne(task, runId, candidate, guidelineChecks, allResults));
                }
                survivors = filterSurvived(reverified);
                log.info("Task '{}': re-verification against the corrected acceptance test "
                    + "produced {}/{} survivor(s)", task.title(), survivors.size(),
                    reverified.size());
                archiveCandidates(reverified, null);
                current = reverified;
            }
            archivePool.addAll(current);
            if (survivors.isEmpty()) {
                final long repairStarted = System.currentTimeMillis();
                List<CandidateSolution> repaired = repairRound(task, runId, current, allResults,
                    knowledgeBrief, guidelines, guidelineChecks);
                com.swarmcoder.inference.RunMeter.span("repair round|" + task.id(), repairStarted);
                archivePool.addAll(repaired);
                survivors = filterSurvived(repaired);
                if (survivors.isEmpty()) {
                    failIfLostToOutage(task, repaired);
                    log.warn("Task '{}': zero survivors after the test's review and the repair "
                        + "round — BLOCKED", task.title());
                    markTaskState(task, TaskState.BLOCKED);
                    queueBlockedDecision(task, runId, archivePool,
                        "Task BLOCKED after swarm + repair round: '" + task.title() + "'\n\n"
                            + reviewedHeadline(task));
                    archiveCandidates(archivePool, null);
                    blockedAfterReview.add(task.id());
                    return new TestRepairOutcome(null, false, null);
                }
            }
            CandidateSolution winner = finishTask(task, runId, survivors, archivePool,
                knowledgeBrief, guidelines, guidelineChecks, allResults);
            return new TestRepairOutcome(winner, false, null);
        } finally {
            noSiblingRepair.remove(task.id());
            testReviews.remove(task.id());
            synchronized (allResults) {
                for (WorkerResult result : allResults) {
                    if (result.workspace() != null && gitService.isEnabled()) {
                        gitService.removeWorktree(result.workspace());
                    }
                }
            }
        }
    }

    /** The paragraph of the operator's question that says the test was reviewed, and how. */
    private String reviewedHeadline(Task task) {
        String review = testReviews.get(task.id());
        return "Every first candidate compiled and failed the same acceptance test with the same "
            + "assertion, so the test went back to the test author before the repair round. "
            + (review == null ? "The test author could not be asked."
                : review) + "\n\n";
    }

    /** True when every one of these candidates' first acceptance failure is inside its own class. */
    private static boolean allInsideTestItself(List<CandidateSolution> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (CandidateSolution candidate : candidates) {
            TestFailure failure = firstAcceptanceFailure(candidate);
            if (failure == null || !failure.insideTestItself()) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when every re-verified candidate's first acceptance failure still shows the corrected
     * test reaching browser-only code (harness run 37) — the correction did not hold, and it is the
     * test, not the candidates, that is still at fault. False when there is nothing to read.
     */
    private static boolean allReachedBrowserOnlyCode(List<CandidateSolution> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (CandidateSolution candidate : candidates) {
            if (BrowserOnlyCode.reachedIn(firstAcceptanceFailure(candidate)) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-verifies one previously-failed candidate against whatever acceptance-tests commit the run
     * currently carries, by recreating its worktree from its own (archival) branch. The candidate is
     * reset to look like a worker that just finished — {@link #verifyOne} otherwise short-circuits
     * anything not in that state and returns it unchanged.
     */
    private CandidateSolution reverifyOne(Task task, UUID runId, CandidateSolution candidate,
                                          List<GuidelineCheck> guidelineChecks,
                                          List<WorkerResult> allResults) {
        CandidateSolution reset = new CandidateSolution(candidate.id(), candidate.taskId(),
            candidate.workerIndex(), candidate.branch(), candidate.sampling(),
            candidate.diffUnified(), null, null, null, CandidateState.SURVIVED, null)
            .carryingAuditFrom(candidate);
        if (gitService == null || !gitService.isEnabled()) {
            return reset; // no repository to recreate a worktree from — leave it as reset
        }
        Path worktree = Path.of(System.getProperty("user.home"), ".swarmcoder", "wt",
            "retest-" + runId + "-" + candidate.id());
        try {
            gitService.removeWorktree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(candidate.branch(), worktree);
        } catch (Exception e) {
            log.warn("Candidate {}: could not recreate its worktree from branch {} to re-verify "
                + "against the corrected test ({}) — UNVERIFIED", candidate.id(), candidate.branch(),
                e.getMessage());
            return reset;
        }
        WorkerResult result = new WorkerResult(reset, worktree);
        allResults.add(result);
        try {
            return verifyOne(task, runId, result, guidelineChecks);
        } finally {
            gitService.removeWorktree(worktree); // scratch space; the branch stays for archival
        }
    }

    /**
     * @param winner      the task's winner, or null when nothing was delivered
     * @param stillFaulty true when nothing survived because the corrected acceptance test is STILL
     *                    at fault — the caller ({@code GreenfieldWorkflow}) parks the run on this,
     *                    rather than recording an ordinary BLOCKED task
     * @param faultMessage the still-faulty test's own error, quoted in the park message; null unless
     *                     {@code stillFaulty}
     */
    public record TestRepairOutcome(CandidateSolution winner, boolean stillFaulty, String faultMessage) {}

    /**
     * The tail every finished task shares, whether it got here on its first pass or after a repair:
     * cluster survivors, judge them, run the rule-break repair round if every one of them broke a
     * stated rule, select a winner, and archive everything. Extracted so
     * {@link #resumeAfterTestRepair} can finish a task exactly the way {@link #executeTask} does,
     * rather than a second copy of this logic drifting from the first.
     */
    private CandidateSolution finishTask(Task task, UUID runId, List<CandidateSolution> survivors,
                                         List<CandidateSolution> archivePool, String knowledgeBrief,
                                         String guidelines, List<GuidelineCheck> guidelineChecks,
                                         List<WorkerResult> allResults) {
        // 3. Cluster survivors (behavioral probes arrive in M4, spec §11.4)
        SyntacticClusterer clusterer = new SyntacticClusterer(
            new TreeSitterSyntaxService(), candidate -> List.of());
        List<CandidateSolution> clustered = clusterer.cluster(survivors, Language.JAVA);

        // 4. Judge (real LLM scoring; neutral fallback on failure; budget-gated)
        markTaskState(task, TaskState.JUDGING);
        // The judge is shown the same house rules the workers were (author decision, §21) —
        // resolved ONCE for the whole task, exactly like guidelineChecks above, so every
        // candidate of this task is judged against the identical rule set. It is also shown
        // the same task-relevant documentation slice the worker's own brief carried, so it
        // stops inventing framework facts the project's own guides already answer.
        List<LearnedGuideline> rules = activeRulesProvider.get();
        JudgeClient judge = new JudgeClient(judgeClient, cloudGate, guidelines, rules,
            knowledgeBrief);
        List<CandidateSolution> firstJudged = judgeSurvivors(task, judge, clustered);

        // 4a. Two or more workers independently disputed the same HARD rule with evidence, and
        // none kept it (harness runs 53 and 55, 2026-10-01): that is a question about the RULE.
        // No repair round — the workers already said, with evidence, that it cannot be met here.
        RuleQuestions.Question disputed = RuleQuestions.find(task, firstJudged, rules, false);
        if (disputed != null) {
            List<CandidateSolution> allowed =
                askAboutRule(disputed, runId, firstJudged, rules, archivePool);
            firstJudged = allowed;
        }

        // 4b. Every surviving candidate broke a stated house rule, in the judge's reading (harness
        // run 14, spec §21 addendum). NO repair round (2026-10-02): these candidates passed
        // verification, and a model's opinion of them is not a fact that undoes that. It is a
        // question about the rule, or a warning on the run, or a park — see everySurvivorBrokeARule.
        final List<CandidateSolution> judged =
            !firstJudged.isEmpty() && firstJudged.stream().allMatch(SelectionLogic::brokeStatedRule)
                ? everySurvivorBrokeARule(task, runId, firstJudged, archivePool, rules)
                : firstJudged;

        // 5. Select, then archive every candidate — archived candidates with their
        //    VerificationReports are the eval harness's labeled dataset (spec §8.9).
        SelectionLogic.Selection selection = new SelectionLogic().select(judged);
        CandidateSolution winner = selection.winner();
        archivePool.removeIf(c -> judged.stream().anyMatch(j -> j.id().equals(c.id())));
        archivePool.addAll(judged);
        if (winner == null) {
            repairSiblingInsteadOfBlocking(task, runId, archivePool);
        }
        markTaskState(task, winner != null ? TaskState.SELECTED : TaskState.BLOCKED);
        if (winner == null) {
            // Selection refusing everything must reach the OPERATOR, not just the log. Without
            // this the task went BLOCKED with no pending decision, which is a task that stops
            // and never says why — the failure mode that makes a block worse than it needs to
            // be. The zero-survivor path above has always queued one; this path had not.
            // Queued AFTER the pool holds the judged copies, so the evidence list is the one
            // the operator will also see in the Gallery.
            queueBlockedDecision(task, runId, archivePool,
                "Task BLOCKED — nothing was good enough to deliver: '" + task.title() + "'\n\n"
                    + selection.reason() + "\n");
        }
        archiveCandidates(archivePool, winner);
        return winner;
    }

    /**
     * What happens when every candidate that passed verification BROKE A STATED HOUSE RULE in the
     * judge's reading: a question about the rule, a warning on the run, or a park — and no repair
     * round.
     *
     * <p><b>What harness run 14 showed, and why this exists.</b> "Implement BookService on the
     * server" produced two candidates that both survived verification, and the judge scored both —
     * 0.70 and 0.40 — for not using the mandated EclipseStore persistence. The higher score won and
     * was delivered. {@link SelectionLogic}'s tier (see {@link SelectionLogic#brokeStatedRule})
     * stops that whenever at least one candidate kept the rule; this method is what happens when
     * NONE did — the task is not allowed to conclude silently that an in-memory map is the answer
     * just because it was the only thing on offer.
     *
     * <p><b>Why there is no repair round any more (2026-10-02).</b> This used to dispatch four
     * repair workers first, seeded from the rule-breakers with the judge's objections as evidence.
     * Harness run 66: both first candidates of a task passed verification, the judge marked both
     * down for leaving an enum without {@code @DataModel}, four repair workers were started, all
     * four found — as the first two had — that the library refuses the annotation on an enum, all
     * four failed, and the question about the rule was raised anyway, eighteen minutes and 1.4
     * million tokens later. A task that has a candidate through verification is not rebuilt on
     * an opinion: only a fact that invalidates what passed (the acceptance test itself changing)
     * does that. The judge's objection is recorded, and the task moves on.
     *
     * @return the candidates selection should choose among. Never returns when the question is
     *         left for a person, or when nobody may carry the objection as a warning: it throws
     *         {@link RunMustPark} instead, because silently delivering the least-bad rule-breaker
     *         is exactly the defect this method exists to stop.
     */
    private List<CandidateSolution> everySurvivorBrokeARule(Task task, UUID runId,
                                                            List<CandidateSolution> broken,
                                                            List<CandidateSolution> archivePool,
                                                            List<LearnedGuideline> rules) {
        log.warn("Task '{}': every candidate that passed verification broke a stated house rule "
            + "in the judge's reading. No repair round - they passed, and an opinion does not undo "
            + "that; it is recorded and the task moves on to the question it raises.", task.title());
        // Every candidate broke the SAME hard rule: a question about the rule, not a verdict on
        // the code (harness runs 53 and 55, 2026-10-01). Under the unattended policy it is
        // answered here and selection carries on; otherwise it parks.
        RuleQuestions.Question question = RuleQuestions.find(task, broken, rules, true);
        if (question != null) {
            return askAboutRule(question, runId, broken, rules, archivePool);
        }
        // The judge's reading of the rules is a model's opinion. Nobody watching: the best
        // of these is delivered with the objection on the run's record, not parked on.
        if (carriedAsWarning(runId, task, "candidates against the project's rules (judge model)",
                ruleBreakBlockedMessage(task, broken))) {
            return broken;
        }
        markTaskState(task, TaskState.BLOCKED);
        // The pool still holds the pre-judge form of every candidate named above (verified but
        // not yet judged); swap in the judged copies — the ones the park message quotes — so the
        // archive the operator opens from the Gallery carries the same evidence the message did,
        // exactly as the ordinary end-of-task archive does for a winner.
        archivePool.removeIf(c -> broken.stream().anyMatch(j -> j.id().equals(c.id())));
        archivePool.addAll(broken);
        archiveCandidates(archivePool, null);
        throw new RunMustPark(ruleBreakBlockedMessage(task, broken));
    }

    /**
     * The judge's verdicts on the candidates that passed verification (2026-10-02).
     *
     * <p><b>Why a judge call took 3.7 minutes in harness run 66.</b> Eight calls, 1771 seconds,
     * about 3,400 completion tokens each for a verdict of under two hundred: the rest is the
     * model reasoning before it answers, on a server that cannot switch reasoning off, at the
     * fifteen to twenty-five tokens a second that server gives one stream while workers share it.
     * And the calls for one task ran one after the other. So:
     *
     * <ul>
     *   <li><b>One candidate passed: the judge is not called.</b> There is nothing to choose
     *       between. What is given up is the judge's reading of the house rules for that one
     *       candidate; the rules that declare a check are still enforced at verification.
     *   <li><b>Candidates with the identical change are judged once</b> and share the verdict.
     *   <li><b>The rest are judged side by side</b>, each call taking one of the server's places
     *       like any other request when the judge is on the workers' own server.
     * </ul>
     *
     * <p>It also says in the log when the candidates cannot be told apart by the mechanical
     * evidence the judge is shown, and when the judge then did not tell them apart either.
     */
    private List<CandidateSolution> judgeSurvivors(Task task, JudgeClient judge,
                                                   List<CandidateSolution> clustered) {
        if (clustered.size() == 1) {
            CandidateSolution only = clustered.get(0);
            log.info("Task '{}': exactly one candidate passed verification (worker {}), so there "
                + "is nothing for the judge to choose between and it is not called.", task.title(),
                only.workerIndex());
            com.swarmcoder.inference.RunMeter.span("judge skipped|" + task.id() + "|"
                + only.workerIndex(), System.currentTimeMillis());
            return new ArrayList<>(clustered);
        }
        String sameEvidence = JudgeClient.sameMechanicalEvidence(clustered, task);
        if (sameEvidence != null) {
            log.info("Task '{}': the {} candidates that passed verification are indistinguishable "
                + "by the mechanical evidence the judge is given ({}). Only its reading of the "
                + "diffs can separate them.", task.title(), clustered.size(), sameEvidence);
        }
        // One representative per identical change, lowest worker first.
        Map<String, CandidateSolution> representative = new java.util.LinkedHashMap<>();
        List<CandidateSolution> inWorkerOrder = new ArrayList<>(clustered);
        inWorkerOrder.sort(Comparator.comparingInt(CandidateSolution::workerIndex));
        for (CandidateSolution candidate : inWorkerOrder) {
            representative.putIfAbsent(changeKey(candidate), candidate);
        }
        List<CandidateSolution> judgedOnce =
            judge.judgeAll(new ArrayList<>(representative.values()), task);
        Map<String, CandidateSolution> verdictFor = new java.util.HashMap<>();
        for (CandidateSolution judged : judgedOnce) {
            verdictFor.put(changeKey(judged), judged);
        }
        List<CandidateSolution> out = new ArrayList<>();
        for (CandidateSolution candidate : clustered) {
            CandidateSolution judged = verdictFor.get(changeKey(candidate));
            if (judged == null) {
                out.add(candidate); // cannot happen; left unjudged rather than lost
            } else if (judged.id().equals(candidate.id())) {
                out.add(judged);
            } else {
                log.info("Task '{}': worker {} made the identical change to worker {}, so it "
                    + "shares that verdict and the judge is not called a second time for it.",
                    task.title(), candidate.workerIndex(), judged.workerIndex());
                com.swarmcoder.inference.RunMeter.span("judge skipped|" + task.id() + "|"
                    + candidate.workerIndex(), System.currentTimeMillis());
                out.add(new CandidateSolution(candidate.id(), candidate.taskId(),
                    candidate.workerIndex(), candidate.branch(), candidate.sampling(),
                    candidate.diffUnified(), candidate.verification(), candidate.cluster(),
                    judged.judge(), candidate.state(), candidate.killReason())
                    .carryingAuditFrom(candidate));
            }
        }
        if (out.stream().map(SwarmEngineImpl::judgeScoreOf).distinct().count() == 1) {
            log.info("Task '{}': the judge gave all {} candidates the same score ({}), so it did "
                + "not separate them either; selection falls to what it ranks below the score - "
                + "kept rules, identical changes, files outside the write set, size of the change.",
                task.title(), out.size(),
                String.format(java.util.Locale.ROOT, "%.2f", judgeScoreOf(out.get(0))));
        }
        return out;
    }

    /** What makes two candidates the same change: the cluster they were put in, else themselves. */
    private static String changeKey(CandidateSolution candidate) {
        return candidate.cluster() == null || candidate.cluster().behavioralHash() == null
            ? String.valueOf(candidate.id()) : candidate.cluster().behavioralHash();
    }

    /**
     * Raises a question about a rule (see {@link RuleQuestions}) and either answers it on the spot
     * — the unattended policy — or parks the run behind it.
     *
     * @return the candidates selection should choose among, with the reworded rule's breaks
     *         moved out of the way. Never returns when the question is left for a person: it
     *         queues the decision and throws {@link RunMustPark}.
     */
    private List<CandidateSolution> askAboutRule(RuleQuestions.Question asked, UUID runId,
                                                 List<CandidateSolution> candidates,
                                                 List<LearnedGuideline> rules,
                                                 List<CandidateSolution> archivePool) {
        Task task = asked.task();
        // First: is it about the rule at all? When the disputes name a file an earlier task of
        // this run delivered, that file is the defect (harness run 65, 2026-10-02). Somebody
        // watching: giving the task that file to repair is the first answer offered.
        //
        // Nobody watching: it used to be given the file and built again on the spot. Not any
        // more (2026-10-02). Every candidate here passed verification, and a dispute raised by
        // candidates that passed is not a reason to build the task a second time. It is recorded
        // and the task moves on: the rule STANDS (rewording it for the whole project because one
        // earlier file is wrong is what run 65 did, and was the mistake), the best of these
        // candidates is delivered, and the run carries a warning naming the file. A task with
        // NOTHING that passed is still rebuilt with the earlier task's file - see
        // repairSiblingInsteadOfBlocking.
        SiblingDefects.Defect defect = siblingDefect(task, runId,
            asked.disputes().stream().map(RuleQuestions.WorkerDispute::dispute).toList());
        if (defect != null && opinionPolicy() == OpinionPolicy.WARN_AND_CARRY_ON
                && carriedAsWarning(runId, task, "an earlier task's output, not the rule '"
                    + asked.ruleName() + "'", "the workers' evidence points at " + defect.named()
                    + ", which this task could not edit. Decided without a person: the rule "
                    + "stands, and because this task has candidates that passed verification it "
                    + "was not built again - the best of them is delivered as it is. That "
                    + "earlier file should be looked at.")) {
            return candidates;
        }
        RuleQuestions.Question question = defect == null ? asked : asked.offering(defect);
        String brief = question.brief();
        log.warn("Task '{}': a question about the rule '{}' rather than the code — {}",
            task.title(), question.ruleName(), brief.lines().skip(2).findFirst().orElse(""));
        if (opinionPolicy() == OpinionPolicy.WARN_AND_CARRY_ON && question.rule() != null) {
            String wording = question.suggestedRewording();
            String result = ruleAmendments.reword(question.rule().id(), wording,
                "decided without a person, task '" + task.title() + "'");
            if (result == null || result.isEmpty()) {
                String answer = "Decided without a person (unattended policy): reword — "
                    + wording.replace('\n', ' ');
                queueRuleDecision(runId, brief, DecisionState.RESOLVED, answer);
                carriedAsWarning(runId, task, "question about the rule '" + question.ruleName()
                    + "' (judge model)", answer);
                return RuleQuestions.allowedUnder(question.ruleName(), question.rule(), rules,
                    candidates, "allowed after the rule was reworded without a person");
            }
            log.warn("Task '{}': the unattended policy could not reword '{}' ({}) — asking instead",
                task.title(), question.ruleName(), result);
        }
        // Nothing could reword the rule and nobody is there to ask: selection chooses among the
        // candidates as the judge left them, and the question stays on the run as a warning.
        if (carriedAsWarning(runId, task, "question about the rule '" + question.ruleName()
                + "' (judge model)", brief)) {
            queueRuleDecision(runId, brief, DecisionState.RESOLVED, "Decided without a person "
                + "(unattended policy): the rule could not be reworded, so the run carried on "
                + "with a warning.");
            return candidates;
        }
        markTaskState(task, TaskState.BLOCKED);
        // The judged copies replace the pre-judge ones, so the archive the operator opens carries
        // the same evidence the question quotes.
        archivePool.removeIf(c -> candidates.stream().anyMatch(j -> j.id().equals(c.id())));
        archivePool.addAll(candidates);
        archiveCandidates(archivePool, null);
        queueRuleDecision(runId, brief, DecisionState.PENDING, null);
        throw new RunMustPark(brief, true);
    }

    /**
     * The earlier task's file these disputes point at, or null — also null when this task was
     * already widened once, when the run or its plan cannot be read, when the task is being
     * finished outside a wave ({@link #resumeAfterTestRepair}), and when another task of the same
     * wave is already repairing one of the files (two winners of one wave editing one file is a
     * merge conflict between waves, which parks the whole run).
     */
    private SiblingDefects.Defect siblingDefect(Task task, UUID runId, List<RuleDispute> disputes) {
        if (disputes == null || disputes.isEmpty() || !task.siblingRepairPaths().isEmpty()
                || noSiblingRepair.contains(task.id())) {
            return null;
        }
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            return null;
        }
        // Only a task of a STRICTLY EARLIER wave: its winner is merged into the tree this task was
        // cut from, which is the only way this task's workers can have met its file.
        List<SiblingDefects.Delivered> delivered = new ArrayList<>();
        for (List<Task> wave : topologicalWaves(graph)) {
            if (wave.stream().anyMatch(t -> t.id().equals(task.id()))) {
                break;
            }
            for (Task earlier : wave) {
                CandidateSolution winner = archivedWinner(earlier);
                if (winner != null) {
                    delivered.add(new SiblingDefects.Delivered(earlier, winner));
                }
            }
        }
        SiblingDefects.Defect defect = SiblingDefects.find(task, delivered, disputes);
        if (defect == null) {
            return null;
        }
        for (String file : defect.files()) {
            UUID owner = siblingRepairClaims.get(runId + "|" + file);
            if (owner != null && !owner.equals(task.id())) {
                log.info("Task '{}': {} looks at fault, but another task of this wave is already "
                    + "repairing it", task.title(), file);
                return null;
            }
        }
        return defect;
    }

    /**
     * Gives {@code task} an earlier task's files to repair: they join its write set, the workers'
     * evidence joins its instructions, and the widening is recorded on the task so it can never
     * happen twice. Persisted before anything is dispatched again.
     *
     * @return false when another task of the same wave claimed one of the files first
     */
    private boolean widenForSiblingRepair(Task task, UUID runId, List<String> files, String note) {
        if (files == null || files.isEmpty() || !task.siblingRepairPaths().isEmpty()) {
            return false;
        }
        for (String file : files) {
            UUID owner = siblingRepairClaims.putIfAbsent(runId + "|" + file, task.id());
            if (owner != null && !owner.equals(task.id())) {
                return false;
            }
        }
        // New collections, not the old ones mutated: the store persists a task's fields by
        // reference, and a set changed in place is a change it is never told about.
        Set<String> widened = new java.util.LinkedHashSet<>(
            task.writeSet() == null ? Set.of() : task.writeSet());
        widened.addAll(files);
        task.setWriteSet(widened);
        task.setSiblingRepairPaths(new ArrayList<>(files));
        task.setInstructions((task.instructions() == null ? "" : task.instructions())
            + (note == null ? "" : note));
        try {
            artifactStore.storeChanged(task).get();
        } catch (Exception e) {
            log.warn("Failed to persist task '{}' after widening its write set: {}", task.title(),
                e.getMessage());
        }
        log.warn("Task '{}': it may now also edit {} and is built again once", task.title(),
            files);
        return true;
    }

    /** The plan's waves for a run, or null when the plan cannot be read. */
    private List<List<Task>> wavesOf(UUID runId) {
        Run run = runsInFlight.get(runId);
        if (run == null) {
            run = artifactStore.root().runs.get(runId);
        }
        TaskGraph graph = run == null || run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        return graph == null ? null : topologicalWaves(graph);
    }

    /**
     * No repair round for {@code task}: its write set is widened to the file every candidate's
     * compile failed in and the task is built again once ({@link RebuildWithSiblingRepair}), or,
     * where that is not possible, the task stops with a message that blames the plan.
     */
    private void widenOrBlameThePlan(Task task, UUID runId, RepairCannotHelp.Finding outside,
                                     List<CandidateSolution> pool) {
        String whyNot = noSiblingRepair.contains(task.id())
            ? "the task is being finished outside its wave" : null;
        if (whyNot == null) {
            Run run = runsInFlight.get(runId);
            if (run == null) {
                run = artifactStore.root().runs.get(runId);
            }
            TaskGraph graph = run == null || run.taskGraphId() == null ? null
                : artifactStore.root().taskGraphs.get(run.taskGraphId());
            whyNot = RepairCannotHelp.whyNotWidened(task,
                graph == null ? null : topologicalWaves(graph), outside.files());
        }
        if (whyNot == null
                && !widenForSiblingRepair(task, runId, outside.files(), outside.instructions())) {
            whyNot = "another task of this wave is already changing one of those files";
        }
        if (whyNot == null) {
            log.warn("Task '{}': every verified candidate ({}) failed on the same compile error "
                + "in {}, a file outside the task's write set. A repair round with the same "
                + "write set cannot succeed, so none is started: the write set now includes {} "
                + "and the task is built again once.", task.title(), outside.candidates(),
                outside.file(), outside.files());
            com.swarmcoder.inference.RunMeter.span("write set widened|" + task.id(),
                System.currentTimeMillis());
            archiveCandidates(pool, null);
            throw new RebuildWithSiblingRepair();
        }
        log.warn("Task '{}': every verified candidate ({}) failed on the same compile error in "
            + "{}, a file outside the task's write set, and the write set could not be widened "
            + "({}). No repair round - it could not succeed. BLOCKED: the plan is at fault, not "
            + "the candidates.", task.title(), outside.candidates(), outside.file(), whyNot);
        markTaskState(task, TaskState.BLOCKED);
        queueBlockedDecision(task, runId, pool, RepairCannotHelp.planBlame(task, outside, whyNot));
        archiveCandidates(pool, null);
    }

    /**
     * The blocked-task half of {@link SiblingDefects}: a task about to be BLOCKED whose workers
     * disputed a rule over an earlier task's file is built once more with that file to repair,
     * when nobody is there to ask. With an operator present it blocks as before — the decision
     * they are shown is theirs to make.
     */
    private void repairSiblingInsteadOfBlocking(Task task, UUID runId,
                                                List<CandidateSolution> pool) {
        if (opinionPolicy() != OpinionPolicy.WARN_AND_CARRY_ON) {
            return;
        }
        SiblingDefects.Defect defect = siblingDefect(task, runId, SiblingDefects.disputesOf(pool));
        if (defect == null
                || !widenForSiblingRepair(task, runId, defect.files(), defect.instructions())) {
            return;
        }
        carriedAsWarning(runId, task, "an earlier task's output", "nothing could be delivered and "
            + "the workers' evidence points at " + defect.named() + ", which this task could not "
            + "edit. Decided without a person: the task was given that file to repair and is "
            + "built again once.");
        archiveCandidates(pool, null);
        throw new RebuildWithSiblingRepair();
    }

    /** A question about a rule, on the run's record — pending for a person, or already answered. */
    private void queueRuleDecision(UUID runId, String brief, DecisionState state, String answer) {
        UUID decisionId = UUID.randomUUID();
        try {
            artifactStore.append(() -> {
                artifactStore.root().decisions.put(decisionId, new Decision(decisionId, runId,
                    DecisionKind.GUIDELINE_REVIEW, brief, state, answer, Instant.now()));
                return null;
            }).get();
        } catch (Exception e) {
            log.warn("Failed to record the question about a rule on run {}: {}", runId,
                e.getMessage());
        }
    }

    /**
     * The latest question about a rule this task raised on this run, applied when it has been
     * answered — the "the affected task resumes with it" half of {@link RuleQuestions}.
     *
     * @return the winner re-selected from the task's own archived candidates under the answer,
     *         or null to build the task in the ordinary way (no question, "keep", or nothing
     *         selectable even under the answer)
     * @throws RunMustPark when the question is still unanswered: the run stops behind it again
     */
    private CandidateSolution answeredRuleQuestion(Task task, UUID runId) {
        Decision latest = null;
        for (Decision decision : artifactStore.root().decisions.values()) {
            if (decision != null && decision.kind() == DecisionKind.GUIDELINE_REVIEW
                    && runId.equals(decision.runId())
                    && task.id().equals(RuleQuestions.taskOf(decision.briefMarkdown()))
                    && (latest == null || decision.createdAt() != null && latest.createdAt() != null
                        && decision.createdAt().isAfter(latest.createdAt()))) {
                latest = decision;
            }
        }
        if (latest == null) {
            return null;
        }
        if (latest.state() == DecisionState.PENDING) {
            log.info("Task '{}': its question about a rule is still unanswered — not building it "
                + "again", task.title());
            throw new RunMustPark(latest.briefMarkdown(), true);
        }
        String response = latest.humanResponse() == null ? "" : latest.humanResponse();
        UUID ruleId = RuleQuestions.ruleOf(latest.briefMarkdown());
        if (response.startsWith("Decided without a person")) {
            return null; // already applied while the run was going
        }
        String brief = latest.briefMarkdown();
        RuleQuestions.Answer answer = RuleQuestions.parse(response);
        if (answer.kind() == RuleQuestions.AnswerKind.REPAIR) {
            // The rule stands; the task is built again with the earlier task's file to repair.
            String named = RuleQuestions.lineOf(brief, RuleQuestions.REPAIR_FILES_PREFIX);
            List<String> files = named.isBlank() ? List.of()
                : java.util.Arrays.stream(named.split(",")).map(String::strip)
                    .filter(f -> !f.isEmpty()).toList();
            if (files.isEmpty()) {
                log.warn("Task '{}': the answer was 'repair' but the question named no file to "
                    + "repair — the rule stands and the task is built again as it was",
                    task.title());
            } else if (task.siblingRepairPaths().isEmpty()) {
                String note = RuleQuestions.lineOf(brief, RuleQuestions.REPAIR_NOTE_PREFIX);
                widenForSiblingRepair(task, runId, files, note.isBlank() ? null : "\n\n" + note);
            }
            return null;
        }
        if (ruleId == null) {
            return null; // nothing to apply the answer to
        }
        String result = switch (answer.kind()) {
            case REPAIR -> ""; // handled above
            case KEEP -> ruleAmendments.keep(ruleId);
            case REWORD -> ruleAmendments.reword(ruleId, answer.wording().isBlank()
                    ? RuleQuestions.lineOf(brief, RuleQuestions.SUGGESTED_PREFIX)
                    : answer.wording(), "the operator's answer, task '" + task.title() + "'");
            case ALLOW -> ruleAmendments.allowException(ruleId,
                RuleQuestions.lineOf(brief, RuleQuestions.EXCEPTION_PREFIX));
        };
        log.info("Task '{}': the question about a rule was answered '{}'{}", task.title(),
            answer.kind(), result == null || result.isEmpty() ? "" : " — " + result);
        if (answer.kind() == RuleQuestions.AnswerKind.KEEP) {
            return null;
        }
        List<LearnedGuideline> rules = activeRulesProvider.get();
        LearnedGuideline rule = rules.stream().filter(r -> ruleId.equals(r.id())).findFirst()
            .orElse(null);
        if (rule == null) {
            return null;
        }
        List<CandidateSolution> archived = new ArrayList<>();
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && task.id().equals(candidate.taskId()) && candidate.judge() != null) {
                archived.add(candidate);
            }
        }
        List<CandidateSolution> allowed = RuleQuestions.allowedUnder(RuleMatch.nameOf(rule), rule,
            rules, archived, "allowed by the operator's answer to the question about this rule");
        SelectionLogic.Selection selection = new SelectionLogic().select(allowed);
        if (selection.winner() == null) {
            return null;
        }
        markTaskState(task, TaskState.SELECTED);
        archiveCandidates(allowed, selection.winner());
        log.info("Task '{}': re-selected from its own candidates under the answer: {}",
            task.title(), selection.reason().lines().findFirst().orElse(""));
        return selection.winner();
    }

    /** The judge's score, or -1.0 for a candidate nobody judged, so it sorts last as a repair seed. */
    private static double judgeScoreOf(CandidateSolution candidate) {
        return candidate.judge() == null ? -1.0 : candidate.judge().score();
    }

    /**
     * What the operator reads when a repair round could not find one candidate that kept the rule
     * every one of its siblings broke. Names the rule and quotes every candidate's own sentence
     * about it, because "the task is blocked" with nothing else said is how this project spent a
     * week blocked before ({@link #nothingSelectableReason} in {@code SelectionLogic} exists for
     * exactly the same reason).
     */
    private static String ruleBreakBlockedMessage(Task task, List<CandidateSolution> broken) {
        StringBuilder sb = new StringBuilder("every candidate broke the stated rule '")
            .append(ruleTitleOf(broken)).append("' for task '").append(task.title()).append("':\n");
        for (CandidateSolution candidate : broken) {
            String sentence = firstBrokenRuleSentence(candidate);
            sb.append("  - worker ").append(candidate.workerIndex()).append(": ")
                .append(sentence.isEmpty() ? "(no rationale)" : sentence).append('\n');
        }
        sb.append("\nNothing was delivered for this task; fix the plan (the task text allowed an "
            + "in-memory shortcut) or the rule.");
        return sb.toString();
    }

    /** The name of the rule every candidate broke, taken from the judge's own first violation. */
    private static String ruleTitleOf(List<CandidateSolution> broken) {
        for (CandidateSolution candidate : broken) {
            String sentence = firstBrokenRuleSentence(candidate);
            if (!sentence.isEmpty()) {
                int colon = sentence.indexOf(':');
                return colon > 0 ? sentence.substring(0, colon).strip() : sentence;
            }
        }
        return "a stated rule";
    }

    /** The first rule the judge said this candidate broke, in its own words, or "" when it named none. */
    private static String firstBrokenRuleSentence(CandidateSolution candidate) {
        if (candidate.judge() == null || candidate.judge().brokenRules().isEmpty()) {
            return "";
        }
        return candidate.judge().brokenRules().get(0);
    }

    /**
     * Throws {@link EndpointOutage} when a wave with no survivors lost anybody to the endpoint being
     * down — so the workflow pauses and retries instead of concluding anything.
     *
     * <p>The test is "zero survivors AND at least one candidate never reached a model", not "every
     * candidate was an outage". If three of eight workers were cut off, what those three would have
     * produced is simply unknown, and the honest response to an unknown is to ask again rather than
     * to spend the run's one repair round on the five that did answer. Erring this way costs a
     * re-dispatch that the original budget already covers; erring the other way costs the repair
     * round, the story's progress, and an operator's afternoon.
     *
     * <p>A wave with even one survivor is never an outage: selection has something real to work on.
     */
    private void failIfLostToOutage(Task task, List<CandidateSolution> candidates) {
        List<CandidateSolution> lost;
        synchronized (candidates) {
            lost = candidates.stream()
                .filter(c -> c.killReason() == KillReason.ENDPOINT_OUTAGE)
                .toList();
        }
        if (lost.isEmpty()) {
            return;
        }
        // The branches these workers created hold nothing, and their deterministic names would make
        // `worktree add -b` refuse on every retry — which would quietly turn auto-resume into a
        // permanent failure.
        if (gitService != null && gitService.isEnabled()) {
            lost.forEach(c -> gitService.deleteCandidateBranch(c.branch()));
        }
        throw new EndpointOutage(null, lost.size() + " of " + candidates.size()
            + " workers on task '" + task.title() + "' never reached a model", null);
    }

    /**
     * The winner already archived for this task, or null when it has none — the idempotence check
     * that makes {@code executeRun} safe to call again after an outage pause.
     */
    private CandidateSolution archivedWinner(Task task) {
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            Object value = Lazy.get(lazy);
            if (value instanceof CandidateSolution candidate
                    && candidate.state() == CandidateState.SELECTED
                    && task.id().equals(candidate.taskId())) {
                return candidate;
            }
        }
        return null;
    }

    private static int passed(CandidateSolution candidate) {
        return candidate.verification().acceptance() == null ? 0
            : candidate.verification().acceptance().passed();
    }

    /** Compact evidence brief a repair worker (and later the Architect) can act on. */
    static String failureEvidence(CandidateSolution candidate) {
        StringBuilder sb = new StringBuilder();
        var verification = candidate.verification();
        sb.append("compiles=").append(verification.compiles());
        sb.append(testFailureEvidence("acceptance", verification.acceptance()));
        sb.append(testFailureEvidence("existing", verification.existing()));
        if (verification.logTail() != null) {
            String[] lines = verification.logTail().split("\n");
            int from = Math.max(0, lines.length - 30);
            sb.append("\n--- verification log tail ---\n");
            for (int i = from; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * "" when the stage is clean; otherwise the same failure text {@code Verdicts} put in the
     * verdict and {@code JudgeClient} put in the judge's verification block — via the one shared
     * formatter, {@code Verdicts.summarizeTestFailures} — with the stack frames a repair worker
     * needs to diagnose the cause, and an instruction it was never given before harness run 22: an
     * acceptance test that threw was handed to two repair workers with nothing saying which
     * exception, in which method, at which line, so both repaired a test they were never shown.
     */
    static String testFailureEvidence(String stage, TestResults results) {
        Verdicts.FailureSummary summary = Verdicts.summarizeTestFailures(stage, results);
        if (summary == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nFAILED ").append(summary.fullText())
            .append("\nThe acceptance test threw this; fix the cause, not the test.");
        if (summary.insideTestItself()) {
            sb.append(" This trace never leaves the test's own class, which looks like a bug in "
                + "the acceptance test itself rather than in your code. You cannot edit that test "
                + "(it is protected) — re-check your implementation against the task requirement "
                + "rather than chasing a change the test cannot see; if the test really is wrong, "
                + "say so in your report instead of trying to make it pass.");
        }
        return sb.toString();
    }

    private void queueBlockedDecision(Task task, UUID runId, List<CandidateSolution> evidence) {
        queueBlockedDecision(task, runId, evidence,
            "Task BLOCKED after swarm + repair round: '" + task.title() + "'\n\n");
    }

    /**
     * @param headline what the operator reads first — why this task stopped. There are two reasons
     *                 now: nothing survived verification, and nothing was good enough to deliver
     *                 (every candidate scored 0.0). They read completely differently to whoever has
     *                 to act on them, so the headline is passed in rather than assumed.
     */
    private void queueBlockedDecision(Task task, UUID runId, List<CandidateSolution> evidence,
                                      String headline) {
        StringBuilder brief = new StringBuilder();
        brief.append(headline);
        for (CandidateSolution candidate : evidence) {
            brief.append("- worker ").append(candidate.workerIndex())
                .append(" [").append(candidate.state())
                .append(candidate.killReason() != null ? "/" + candidate.killReason() : "")
                .append("]");
            if (candidate.verification() != null) {
                brief.append(" compiles=").append(candidate.verification().compiles());
                if (candidate.verification().acceptance() != null) {
                    brief.append(" acceptance ").append(candidate.verification().acceptance().passed())
                        .append("p/").append(candidate.verification().acceptance().failed()).append('f');
                }
            }
            brief.append('\n');
        }
        brief.append("\nRe-decompose the task, fix the environment, or adjust the acceptance tests, "
            + "then re-run (spec §11.5).");

        UUID decisionId = UUID.randomUUID();
        artifactStore.append(() -> {
            artifactStore.root().decisions.put(decisionId, new Decision(decisionId, runId,
                DecisionKind.BLOCKED_TASK, brief.toString(), DecisionState.PENDING,
                null, Instant.now()));
            return null;
        });
    }

    /**
     * Advances a task through its lifecycle (PENDING → DISPATCHED → VERIFYING → JUDGING →
     * SELECTED, or BLOCKED) and persists the transition so the run graph reflects live
     * progress. The Task instances are the ones reachable from the persisted TaskGraph, so
     * {@code store(task)} is the correct EclipseStore update for an already-known object.
     */
    private void markTaskState(Task task, TaskState state) {
        task.setState(state);
        try {
            artifactStore.storeChanged(task).get();
        } catch (Exception e) {
            log.warn("Failed to persist task '{}' state {}: {}", task.title(), state, e.getMessage());
        }
    }

    private static List<CandidateSolution> filterSurvived(List<CandidateSolution> candidates) {
        synchronized (candidates) {
            return candidates.stream().filter(c -> c.state() == CandidateState.SURVIVED).toList();
        }
    }

    private void archiveCandidates(List<CandidateSolution> candidates, CandidateSolution winner) {
        try {
            artifactStore.append(() -> {
                for (CandidateSolution candidate : candidates) {
                    CandidateSolution stored = winner != null && candidate.id().equals(winner.id())
                        ? winner : candidate;
                    artifactStore.root().candidateArchives.put(stored.id(),
                        Lazy.Reference(stored));
                }
                return null;
            }).get();
        } catch (Exception e) {
            log.warn("Failed to archive {} candidates: {}", candidates.size(), e.getMessage());
        }
    }

    /**
     * Runs the verification pipeline for one candidate in its worktree and stamps
     * SURVIVED/FAILED from {@link Verdicts#survived}. Candidates without a workspace or
     * without a {@code .swarmcoder/verify.yaml} pass through unverified — loudly. This is a
     * temporary M1 allowance; per architecture §8.1 unverifiable tasks must not swarm at all.
     *
     * <p><b>The M1 allowance does not extend to house rules.</b> A repository with no verification
     * contract still gets its declared rule checks run (author decision, §21) — otherwise the one
     * situation in which nothing at all is checked would also be the one in which the operator's
     * own rules stop applying, and "this repo has no verify.yaml yet" would silently mean "the
     * rules are off". The candidate stays UNVERIFIED in every other respect, exactly as before.
     */
    private CandidateSolution verifyOne(Task task, UUID runId, WorkerResult result,
                                        List<GuidelineCheck> guidelineChecks) {
        CandidateSolution verified = verifyUnrecorded(task, runId, result, guidelineChecks);
        // What became of the work that followed each answer the expert gave this worker - the
        // run's record of expert answers (2026-10-02). Evidence about the answer, not proof.
        if (runId != null && verified != null) {
            com.swarmcoder.runtime.ExpertAnswerLog.forRun(runId).workFollowed(
                com.swarmcoder.runtime.ExpertAnswerLog.Asker.worker(task.id(), task.title(),
                    verified.workerIndex()),
                verified.state() == CandidateState.SURVIVED);
        }
        return verified;
    }

    private CandidateSolution verifyUnrecorded(Task task, UUID runId, WorkerResult result,
                                               List<GuidelineCheck> guidelineChecks) {
        CandidateSolution stopped = result.candidate();
        if (StoppedWork.worthVerifying(stopped) && result.workspace() != null) {
            return verifyStopped(task, runId, result, guidelineChecks);
        }
        return verifyFinished(task, runId, result, guidelineChecks);
    }

    /**
     * A worker that was stopped, and left a change behind: verified as if it had ended by itself
     * (see {@link StoppedWork}). It survives only on a verdict - a tree nothing could verify
     * leaves it stopped - and one that does not pass keeps the reason it was stopped for, with
     * the report beside it.
     */
    private CandidateSolution verifyStopped(Task task, UUID runId, WorkerResult result,
                                            List<GuidelineCheck> guidelineChecks) {
        CandidateSolution stopped = result.candidate();
        KillReason why = stopped.killReason();
        CandidateSolution asFinished = new CandidateSolution(stopped.id(), stopped.taskId(),
            stopped.workerIndex(), stopped.branch(), stopped.sampling(), stopped.diffUnified(),
            null, stopped.cluster(), stopped.judge(), CandidateState.SURVIVED, null)
            .carryingAuditFrom(stopped);
        log.info("Task '{}': worker {} was stopped ({}) and left a change, so the change is "
            + "verified like any other.", task.title(), stopped.workerIndex(), why);
        CandidateSolution verdict =
            verifyFinished(task, runId, result.withCandidate(asFinished), guidelineChecks);
        boolean survived = verdict.state() == CandidateState.SURVIVED
            && verdict.verification() != null;
        com.swarmcoder.inference.RunMeter.span(StoppedWork.SPAN + why.name() + "|" + task.id()
            + "|" + stopped.workerIndex() + "|" + (survived ? "survived" : "failed"),
            System.currentTimeMillis());
        if (survived) {
            log.info("Task '{}': worker {} was stopped ({}) with its work complete - the change "
                + "passed verification and stands as a candidate.", task.title(),
                stopped.workerIndex(), why);
            return verdict;
        }
        log.info("Task '{}': worker {} was stopped ({}) and its change did not pass verification; "
            + "it stays stopped.", task.title(), stopped.workerIndex(), why);
        return new CandidateSolution(stopped.id(), stopped.taskId(), stopped.workerIndex(),
            stopped.branch(), stopped.sampling(), stopped.diffUnified(), verdict.verification(),
            stopped.cluster(), stopped.judge(), CandidateState.KILLED, why)
            .carryingAuditFrom(stopped);
    }

    private CandidateSolution verifyFinished(Task task, UUID runId, WorkerResult result,
                                             List<GuidelineCheck> declaredChecks) {
        // A rule whose check command cannot run in the container was skipped for this run.
        List<GuidelineCheck> guidelineChecks = runnableChecks(runId, declaredChecks);
        CandidateSolution sol = result.candidate();
        if (sol.state() != CandidateState.SURVIVED) {
            return sol; // already failed/killed during the worker loop
        }
        if (result.workspace() == null) {
            log.warn("Candidate {} has no workspace — UNVERIFIED (configure repoPath)", sol.id());
            return sol;
        }
        // The spec comes from the OPERATOR'S tree, not the candidate's: a worker that can write
        // .swarmcoder/verify.yaml would otherwise rewrite the commands that judge it.
        Optional<VerifySpec> spec = VerifySpecLoader.loadTrusted(
            gitService == null ? null : gitService.repoPath(), result.workspace());
        if (spec.isEmpty()) {
            log.warn("Candidate {}: no {} in workspace — UNVERIFIED", sol.id(), VerifySpecLoader.SPEC_PATH);
            return guidelineChecksOnly(task, sol, result, guidelineChecks);
        }
        // The tests this task claims, and only those, into the tree about to be verified. The
        // worktree was cut from the run's pinned base, which predates every acceptance test, so
        // without this the acceptance stage selects nothing and reads as green (§17.1 in the other
        // direction). See AcceptanceOverlay for why the protected tree is cleared before placing.
        String overlayFailure = placeClaimedTests(task, runId, result.workspace(), sol);
        if (overlayFailure != null) {
            // The tree could not be brought to the state the verdict is defined against, so no
            // verdict can be honest. The report exists only to carry the reason to the Gallery.
            VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
                null, null, null, null, java.time.Duration.ZERO, "", null);
            recordVerdict(report, overlayFailure);
            log.error("Candidate {} FAILED verification: {}", sol.id(), overlayFailure);
            return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(), sol.branch(),
                sol.sampling(), sol.diffUnified(), report, sol.cluster(), sol.judge(),
                CandidateState.FAILED, KillReason.NO_ACCEPTANCE_EVIDENCE).carryingAuditFrom(sol);
        }
        VerificationReport report;
        try {
            report = verifyWith(task, spec.get(), result.workspace(), guidelineChecks,
                UnifiedDiffPaths.addedOrChanged(sol.diffUnified()), baselineOf(runId));
        } catch (DockerSandboxManager.SandboxException e) {
            // Required sandbox, no container: the candidate is unverifiable, not unverified.
            // Failing it is the point — the alternative is running the repo's build and test
            // commands unsandboxed on the workstation.
            log.error("Candidate {} FAILED: {}", sol.id(), e.getMessage());
            return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(), sol.branch(),
                sol.sampling(), sol.diffUnified(), null, sol.cluster(), sol.judge(),
                CandidateState.FAILED, KillReason.SANDBOX_UNAVAILABLE).carryingAuditFrom(sol);
        }
        // The checks this task answers for. Empty for an ENABLER task that answers to no
        // requirement, which keeps the M1 empty-acceptance-suite allowance exactly as it was.
        List<String> claimedChecks = artifactStore.describeClaimedChecks(task);
        Verdicts.Verdict verdict =
            Verdicts.assess(report, claimedChecks, acceptanceProvenance(task));
        // The contracts this task was told to deliver, checked against what is actually in its
        // tree (author decision, 2026-09-03). An enabler claims no test, so nothing else about it
        // is verified by running anything - and a contract it renamed or left out is a hole a
        // LATER wave falls into, as a test that cannot compile and a candidate blamed for a tree
        // it did not break. Asked only of a candidate that otherwise survived: a candidate already
        // failing for a real reason must keep that reason.
        if (verdict.survived()) {
            String shortfall = contractShortfall(task, result.workspace(), startFileOf(runId));
            if (shortfall != null) {
                verdict = new Verdicts.Verdict(false, shortfall);
            }
        }
        // Source files changed outside the task's write set (live run 74): such a candidate
        // never counts as one that passed. Asked only of one that otherwise survived, so a
        // candidate failing for another reason keeps that reason. See SourceOutsideWriteSet.
        if (verdict.survived()) {
            String outside = SourceOutsideWriteSet.objection(task, sol);
            if (outside != null) {
                verdict = new Verdicts.Verdict(false, outside);
            }
        }
        // Public code that existed when the run started and is gone from this candidate (live
        // run 75): not a change a story makes unless one of its agreed checks says something is
        // to be removed. See RemovedExistingApi.
        if (verdict.survived()) {
            String destructive = removedExistingApi(runId, sol, result.workspace());
            if (destructive != null) {
                verdict = new Verdicts.Verdict(false, destructive);
            }
        }
        // Production code this candidate adds that nothing in the application can reach (the
        // seven accepted stories whose screens no user could open, 2026-10-05). Asked of the last
        // task along its line only: a task something later builds on may be connected by that
        // later task. See ReachableCode.
        if (verdict.survived()) {
            String unreachable = unreachableAddedCode(runId, task, sol, result.workspace());
            if (unreachable != null) {
                verdict = new Verdicts.Verdict(false, unreachable);
            }
        }
        // A task that claims no check is verified by nothing but "it compiles", so the one defect
        // a compiler cannot see and a later task cannot work around is looked for here: a class
        // nobody else can use (harness run 65 — see UnusableDeliveredType).
        if (verdict.survived() && claimedChecks.isEmpty() && task.authoredTestPaths().isEmpty()) {
            String unusable = UnusableDeliveredType.in(result.workspace(), sol.diffUnified());
            if (unusable != null) {
                verdict = new Verdicts.Verdict(false, unusable);
            }
        }
        // The PASSED count belongs here as much as the failures. An acceptance stage that selected
        // no tests reports no failures, so it reads exactly like one that ran and was green — which
        // is how a wrong -Dtest pattern survived unnoticed while every candidate "passed" without a
        // single acceptance test ever running.
        log.info("Candidate {} verification: compiles={} acceptance={}p/{}f/{}e claimedChecks={} "
                + "survived={}",
            sol.id(), report.compiles(),
            report.acceptance() == null ? 0 : report.acceptance().passed(),
            report.acceptance() == null ? 0 : report.acceptance().failed(),
            report.acceptance() == null ? 0 : report.acceptance().errored(),
            claimedChecks.size(), verdict.survived());
        if (!verdict.survived()) {
            String reason = survivalReason(verdict, report, claimedChecks);
            log.warn("Candidate {} FAILED verification: {}", sol.id(), reason);
            // Into the report as well as the log. The log scrolls past; the report is what the
            // Gallery renders when somebody asks why this candidate died.
            recordVerdict(report, reason);
        }
        return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(), sol.branch(),
            sol.sampling(), sol.diffUnified(), report, sol.cluster(), sol.judge(),
            verdict.survived() ? CandidateState.SURVIVED : CandidateState.FAILED,
            killReasonFor(report, claimedChecks, sol.killReason())).carryingAuditFrom(sol);
    }

    /**
     * A verdict is never blank (harness run 25, 2026-09-05). {@code verdict.reason()} when it said
     * one; otherwise a sentence that names the bug instead of leaving the operator staring at
     * nothing after the colon.
     *
     * <p>This run's own candidates were killed for a real, legitimate reason — three delivered
     * contracts, each missing its promised {@code @DataModel} annotation — and {@link
     * #contractShortfall} said so correctly. The reason still LOOKED blank, because that method's
     * own formatting put an empty line first (fixed above). This guard is the backstop for that
     * whole class of mistake: whatever produces a {@code survived=false} verdict with nothing
     * usable in {@code reason()} — today or in a path not yet written — the operator gets a
     * sentence naming the bug and the report's own counts, never silence.
     *
     * <p>Package-private and static so every path into it can be proved without a live pipeline —
     * see {@code AVerdictIsNeverBlankTest}.
     */
    /**
     * What this task's own acceptance tests did BEFORE any candidate's diff existed — the run's
     * pre-change execution, recorded once per task at the wave gate, reused here rather than run
     * again (author decision, 2026-09-05, harness run 30; see {@code Verdicts.AcceptanceProvenance}).
     *
     * <p>{@link ChecksAlreadyProved} is the only thing that ever records "these tests were already
     * green", and it records WHICH kind: green because the waves in front delivered what they
     * measure, which is success and stays a fact rather than a fault, or green on a tree carrying
     * nothing this run delivered, which is a test that measures itself. Only the second is carried
     * into the verdict. No record at all means nothing was established, and nothing is concluded.
     */
    private static Verdicts.AcceptanceProvenance acceptanceProvenance(Task task) {
        ChecksAlreadyProved proved = task == null ? null : task.checksAlreadyProved();
        if (proved == null) {
            return Verdicts.AcceptanceProvenance.UNKNOWN;
        }
        if (!proved.nothingDeliveredYet()) {
            return Verdicts.AcceptanceProvenance.UNKNOWN;
        }
        return Verdicts.AcceptanceProvenance.provesNothing(
            (proved.tests().isEmpty() ? proved.passed() + " acceptance test(s)"
                : String.join(", ", proved.tests()))
            + " passed on the tree this run started from, before any worker ran");
    }

    static String survivalReason(Verdicts.Verdict verdict, VerificationReport report,
                                 List<String> claimedChecks) {
        if (verdict.survived()) {
            return null;
        }
        String reason = verdict.reason();
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return "verification failed without a recorded reason — this is a bug in SwarmCoder; "
            + "compiles=" + (report == null ? "unknown" : report.compiles()) + ", acceptance="
            + (report == null || report.acceptance() == null ? "none"
                : report.acceptance().passed() + "p/" + report.acceptance().failed() + "f/"
                    + report.acceptance().errored() + "e")
            + ", claimedChecks=" + (claimedChecks == null ? 0 : claimedChecks.size());
    }

    /**
     * Null when this candidate delivered every contract its task promised, or when the task
     * promised none; otherwise the sentence naming what is missing.
     *
     * <p>A source scan, no model and no compiler, over the candidate's own workspace. It fails
     * open at every step it cannot settle - a contract naming no type, a file that will not parse,
     * a workspace that is not there - because a candidate must never be killed by a scanner's
     * guess. What it does say, it says concretely: this type is nowhere, or this member is not on
     * it, and this is what the task was told to build.
     *
     * <p><b>Bug fixed 2026-09-05 (harness run 25).</b> This used to prepend {@code "\n  - "} before
     * {@link ContractDelivery#describe} whenever there was more than one shortfall — meant to look
     * like a bullet, but {@code describe} already opens every item after the first with its own
     * {@code "\n  - "} and never prefixes the first. The extra newline landed IN FRONT of that first
     * item instead, so a task with two or more missing contracts (this run had three) produced a
     * reason whose first line was empty: the console showed "FAILED verification: " with nothing
     * after the colon, and the real explanation only became visible on the lines below it. The
     * candidates themselves were correctly failed — they really had left out a promised member —
     * but the verdict looked silent about why. Package-private and static so the multi-shortfall
     * case can be proved directly; see {@code AVerdictIsNeverBlankTest}.
     */
    static String contractShortfall(Task task, Path workspace) {
        return contractShortfall(task, workspace, null);
    }

    /**
     * As above, for a run that knows its start commit. {@code startFile} gives a repository-relative
     * path's text as committed at the run's start, or null when it was not there (null function:
     * unknown, nothing is excused).
     *
     * <p>Harness run 78: a contract on a type that ALREADY existed said it carried an annotation
     * the real class never had. Every candidate failed as "not delivered" and the repair round
     * ran its full time on a statement the task could not have made true without being asked to.
     * A type whose file is exactly as it was at the run's start was not touched by this task, so
     * a mismatch on it is not this task's failure and is not reported (it is logged). A type that
     * existed and that the task DID change is still held to the contract, and the sentence says
     * the type already existed and must be changed, not created.
     */
    static String contractShortfall(Task task, Path workspace,
                                    java.util.function.Function<String, String> startFile) {
        if (task == null || workspace == null || task.deliveredContracts().isEmpty()) {
            return null;
        }
        List<ContractDelivery.Shortfall> shortfalls =
            ContractDelivery.shortfalls(workspace, task.deliveredContracts());
        if (startFile != null && !shortfalls.isEmpty()) {
            com.swarmcoder.knowledge.ProjectTypes tree =
                com.swarmcoder.knowledge.ProjectTypes.of(workspace);
            List<ContractDelivery.Shortfall> kept = new ArrayList<>();
            for (ContractDelivery.Shortfall shortfall : shortfalls) {
                if (shortfall.missingType()) {
                    kept.add(shortfall);
                    continue;
                }
                Path file = tree.fileOf(shortfall.contract().typeName());
                String before = null;
                String now = null;
                if (file != null) {
                    try {
                        String relative = workspace.toAbsolutePath().normalize()
                            .relativize(file.toAbsolutePath().normalize()).toString()
                            .replace('\\', '/');
                        before = startFile.apply(relative);
                        now = java.nio.file.Files.readString(file);
                    } catch (java.io.IOException | RuntimeException unreadable) {
                        before = null;
                    }
                }
                if (before == null) {
                    // Not at the start commit as a hand-written file (the task made it, or the
                    // build did): the task owns it, as before.
                    kept.add(shortfall);
                } else if (before.equals(now)) {
                    log.warn("Contract {} is not met by a type that existed when the run started "
                        + "and that this task left as it was; not held against the candidate: {}",
                        shortfall.contract().typeName(), shortfall.render());
                } else {
                    kept.add(shortfall.onAnExistingType());
                }
            }
            shortfalls = kept;
        }
        if (shortfalls.isEmpty()) {
            return null;
        }
        return ContractDelivery.describe(shortfalls)
            + "\n\nThe design fixes these type names so that everything built against them - the "
            + "acceptance tests of the tasks that come after this one above all - is written "
            + "against the same words. A task that renames one, or leaves one out, cannot be "
            + "corrected later: by then the tests naming it have been written and committed.";
    }

    /**
     * Brings the candidate's worktree to exactly the acceptance tests its task claims (author
     * decision, 2026-09-02: a task is verified against the tests it claims, and only those).
     *
     * <p>Skipped entirely when git is off or the run recorded no tests commit — a run persisted by
     * a build from before the tests lived on a run ref, whose worktrees were cut from a HEAD that
     * already had them. Otherwise the tree is CLEARED of every acceptance test it inherited (the
     * base carries whatever earlier runs left, and a dead run's test that names classes this plan
     * never creates breaks test-compile for every candidate regardless of its own code) and the
     * task's own files are written from the tests commit. A task claiming none gets an empty tree:
     * the acceptance stage then runs nothing and the M1 allowance applies, exactly as before.
     *
     * @return null when the tree is ready; otherwise why it could not be made ready
     */
    private String placeClaimedTests(Task task, UUID runId, Path workspace, CandidateSolution sol) {
        if (gitService == null || !gitService.isEnabled() || runId == null) {
            return null;
        }
        Run run = artifactStore.root().runs.get(runId);
        String testsCommit = run == null ? null : run.acceptanceTestsCommit();
        if (testsCommit == null || testsCommit.isBlank()) {
            log.info("Candidate {}: the run recorded no acceptance-tests commit, so its worktree "
                + "is verified as cut", sol.id());
            return null;
        }
        try {
            AcceptanceOverlay.Outcome outcome = AcceptanceOverlay.reduceTo(gitService, workspace,
                task.acceptanceTestDir(), testsCommit, task.authoredTestPaths());
            log.info("Candidate {}: acceptance tests for task '{}' — {}", sol.id(), task.title(),
                outcome.describe());
            return null;
        } catch (IOException e) {
            return "the acceptance tests this task claims could not be placed into the "
                + "candidate's worktree (" + e.getMessage() + "), so nothing here could verify "
                + "them and no verdict about the candidate would be honest";
        }
    }

    /**
     * Names WHY, when verification is the thing that killed the candidate. A blank kill reason on a
     * failed candidate sends whoever reads the run looking for a cause that is sitting in the
     * report; both reasons below are ones the counts alone cannot express.
     */
    private static KillReason killReasonFor(VerificationReport report, List<String> claimedChecks,
                                            KillReason existing) {
        if (report != null && report.buildReachability() != null
            && report.buildReachability().orphaned()) {
            return KillReason.UNBUILT_FILES;
        }
        if (emptyCheckKill(report, claimedChecks)) {
            return KillReason.NO_ACCEPTANCE_EVIDENCE;
        }
        return existing;
    }

    /**
     * True when the ONLY thing wrong with this candidate is that nothing verified the checks it
     * claims — so the kill reason can say that rather than leaving it blank.
     */
    private static boolean emptyCheckKill(VerificationReport report, List<String> claimedChecks) {
        return !claimedChecks.isEmpty()
            && Verdicts.survived(report)
            && !Verdicts.assess(report, claimedChecks).survived();
    }

    /** Appends the verdict to the report's log tail, which is what the Gallery shows. */
    private static void recordVerdict(VerificationReport report, String reason) {
        String tail = report.logTail() == null ? "" : report.logTail();
        report.setLogTail(tail + "\n[verdict] NOT SURVIVED — " + reason + "\n");
    }

    /**
     * Verifies a candidate's worktree — inside a Docker sandbox when one is configured (spec §8),
     * otherwise locally. The sandbox bind-mounts the worktree at {@code /workspace}; verification
     * commands run in the container over the Docker Engine exec API (the only transport that
     * survives {@code network: none} — see {@link com.swarmcoder.sandbox.DockerSandboxManager}).
     *
     * <p>A container that will not start never falls back to this PC: verification runs the
     * repo's own build and test commands, which the candidate's diff can influence. With no
     * sandbox configured at all the candidate is refused the same way, unless
     * {@code HostExecution} was switched on by name. See {@link SandboxAttach}.
     *
     * @throws DockerSandboxManager.SandboxException when there is no container to verify in
     */
    private VerificationReport verifyWith(Task task, VerifySpec spec, Path workspace,
                                          List<GuidelineCheck> guidelineChecks,
                                          Set<String> changedFiles, VerificationBaseline baseline) {
        String writeSetEnv = task.writeSet() == null ? "" : String.join(",", task.writeSet());
        SandboxAttach.Attachment attachment = SandboxAttach.attach(
            sandbox, workspace.toAbsolutePath().toString(), writeSetEnv,
            "Verification of task '" + task.title() + "'");
        if (attachment.refused()) {
            throw new DockerSandboxManager.SandboxException(attachment.refusal());
        }
        try {
            ExecTarget target = attachment.target() != null
                ? attachment.target() : new LocalProcessExecTarget(workspace);
            return verifier.verify(target, task, spec, guidelineChecks, changedFiles, baseline);
        } finally {
            SandboxAttach.release(sandbox, attachment);
        }
    }

    /**
     * The house-rule checks alone, for a candidate whose repository declares no verification
     * contract. Runs on the SAME exec target the full pipeline would have used — the sandbox when
     * one is configured — because a rule's command is model-adjacent code running against a
     * candidate's tree, and "there was no verify.yaml" is not a reason to run it on the host.
     *
     * <p>A broken rule fails the candidate and says which. Everything else about the candidate is
     * still unverified, so it is returned untouched when the rules are clean.
     */
    private CandidateSolution guidelineChecksOnly(Task task, CandidateSolution sol,
                                                  WorkerResult result,
                                                  List<GuidelineCheck> guidelineChecks) {
        if (guidelineChecks == null || guidelineChecks.isEmpty()) {
            return sol;
        }
        List<GuidelineCheckResult> results;
        StringBuilder logText = new StringBuilder();
        SandboxAttach.Attachment attachment = SandboxAttach.attach(
            sandbox, result.workspace().toAbsolutePath().toString(),
            task.writeSet() == null ? "" : String.join(",", task.writeSet()),
            "House-rule checks for task '" + task.title() + "'");
        if (attachment.refused()) {
            log.error("Candidate {} FAILED: {}", sol.id(), attachment.refusal());
            return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(), sol.branch(),
                sol.sampling(), sol.diffUnified(), null, sol.cluster(), sol.judge(),
                CandidateState.FAILED, KillReason.SANDBOX_UNAVAILABLE).carryingAuditFrom(sol);
        }
        try {
            ExecTarget target = attachment.target() != null
                ? attachment.target() : new LocalProcessExecTarget(result.workspace());
            results = GuidelineCheckRunner.run(target, guidelineChecks, logText);
        } finally {
            SandboxAttach.release(sandbox, attachment);
        }
        if (!GuidelineCheckRunner.anyBroken(results)) {
            log.info("Candidate {}: {} house rule(s) obeyed; everything else UNVERIFIED "
                + "(no {})", sol.id(), results.size(), VerifySpecLoader.SPEC_PATH);
            return sol;
        }
        // A report exists only to carry the evidence: this candidate was never compiled or tested,
        // so nothing here may claim it was. compiles=false would be a lie in the other direction,
        // which is why the verdict reason, not the flags, is what the operator is pointed at.
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            null, null, null, null, java.time.Duration.ZERO, logText.toString(), null);
        report.setGuidelineChecks(results);
        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        String reason = survivalReason(verdict, report, List.of());
        log.warn("Candidate {} FAILED verification: {}", sol.id(), reason);
        recordVerdict(report, reason);
        return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(), sol.branch(),
            sol.sampling(), sol.diffUnified(), report, sol.cluster(), sol.judge(),
            CandidateState.FAILED, sol.killReason()).carryingAuditFrom(sol);
    }
}
