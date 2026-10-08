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
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.ContextDeath;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.LocalProcessExecTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.PromptBundle;

/**
 * One worker candidate attempt: its own branch, its own linked worktree, a real agent session
 * with the spec §11.2 tool loop, and early-kill rules (spec §11.3) enforced mechanically every
 * turn via the session's TurnGuard. The produced diff is captured from the worktree and flows
 * into the verify → cluster → judge → select pipeline.
 */
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final Path WORKTREE_ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");
    private static final long DEFAULT_MAX_TOTAL_TOKENS = 2_500_000;

    private final Task task;
    private final SamplingConfig config;
    private final int workerIndex;
    private final InferenceScheduler scheduler;
    private final GitService gitService;
    private final AgentRuntime agentRuntime;
    private final AgentRuntime.ModelEndpoint endpoint; // null => no live endpoint configured
    private final UUID runId;
    private final PromptBundle bundle;
    private final String startPoint;   // branch/ref the worktree starts from; null = HEAD
    private final GroupSignal groupSignal; // nullable
    private final ApiLookup apiLookup;
    /**
     * Where this worker's own questions go. Never null; the default answers "nothing configured",
     * which is honest and lets every existing caller keep working unchanged.
     */
    private volatile ExpertHelp expert = ExpertHelp.UNAVAILABLE;
    /** Package prefixes of the project's reference material, for the framework-error hint. */
    private volatile List<String> frameworkPackages = List.of();
    /** Per-candidate Docker sandbox for the model's exec tool; null = run exec locally. */
    private final DockerSandboxManager sandbox;
    /** Operator-declared locked modules (config {@code protectedPaths}); never null. */
    private final List<String> protectedPaths;
    private final EarlyKillEnforcer earlyKill = new EarlyKillEnforcer();

    public WorkerLoop(Task task, SamplingConfig config, int workerIndex,
                      InferenceScheduler scheduler, GitService gitService,
                      AgentRuntime agentRuntime, AgentRuntime.ModelEndpoint endpoint,
                      UUID runId, PromptBundle bundle) {
        this(task, config, workerIndex, scheduler, gitService, agentRuntime, endpoint,
            runId, bundle, null, null, ApiLookup.UNAVAILABLE, null);
    }

    public WorkerLoop(Task task, SamplingConfig config, int workerIndex,
                      InferenceScheduler scheduler, GitService gitService,
                      AgentRuntime agentRuntime, AgentRuntime.ModelEndpoint endpoint,
                      UUID runId, PromptBundle bundle,
                      String startPoint, GroupSignal groupSignal,
                      ApiLookup apiLookup) {
        this(task, config, workerIndex, scheduler, gitService, agentRuntime, endpoint,
            runId, bundle, startPoint, groupSignal, apiLookup, null);
    }

    public WorkerLoop(Task task, SamplingConfig config, int workerIndex,
                      InferenceScheduler scheduler, GitService gitService,
                      AgentRuntime agentRuntime, AgentRuntime.ModelEndpoint endpoint,
                      UUID runId, PromptBundle bundle,
                      String startPoint, GroupSignal groupSignal,
                      ApiLookup apiLookup, DockerSandboxManager sandbox) {
        this(task, config, workerIndex, scheduler, gitService, agentRuntime, endpoint,
            runId, bundle, startPoint, groupSignal, apiLookup, sandbox, List.of());
    }

    /**
     * @param protectedPaths operator-declared locked modules the toolbox refuses for every write,
     *                       regardless of the task's write set (config {@code protectedPaths})
     */
    public WorkerLoop(Task task, SamplingConfig config, int workerIndex,
                      InferenceScheduler scheduler, GitService gitService,
                      AgentRuntime agentRuntime, AgentRuntime.ModelEndpoint endpoint,
                      UUID runId, PromptBundle bundle,
                      String startPoint, GroupSignal groupSignal,
                      ApiLookup apiLookup, DockerSandboxManager sandbox,
                      List<String> protectedPaths) {
        this.task = task;
        this.config = config;
        this.workerIndex = workerIndex;
        this.scheduler = scheduler;
        this.gitService = gitService;
        this.agentRuntime = agentRuntime;
        this.endpoint = endpoint;
        this.runId = runId;
        this.bundle = bundle;
        this.startPoint = startPoint;
        this.groupSignal = groupSignal;
        this.apiLookup = apiLookup == null ? ApiLookup.UNAVAILABLE : apiLookup;
        this.sandbox = sandbox;
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    }

    /**
     * Wires the worker's 911 and the package prefixes that mark a build error as a framework one.
     *
     * <p>A setter rather than another constructor argument: {@link WorkerLoop} already has five
     * overloads and every caller that does not have an expert to give should keep working
     * unchanged.
     */
    /**
     * The read-only reference folders, by label: mounted into this worker's container at
     * {@code /reference/<label>} and readable through its {@code read} tool at the same address.
     */
    public WorkerLoop withAcceptanceSource(java.util.function.Function<String, String> source) {
        this.acceptanceSource = source;
        return this;
    }

    /** The run's acceptance test file by path, for the worker's read-only lookup; may be null. */
    private volatile java.util.function.Function<String, String> acceptanceSource;

    /**
     * Who else of the plan holds a file this task did not reserve (section 73); null asks
     * nobody. See {@link ReservationBook}.
     */
    private volatile com.swarmcoder.runtime.PathPolicy.OtherTasks otherTasks;

    public WorkerLoop withOtherTasks(com.swarmcoder.runtime.PathPolicy.OtherTasks otherTasks) {
        this.otherTasks = otherTasks;
        return this;
    }

    public WorkerLoop withReferenceRoots(java.util.Map<String, Path> roots) {
        this.referenceRoots = roots == null ? java.util.Map.of() : java.util.Map.copyOf(roots);
        return this;
    }

    private volatile java.util.Map<String, Path> referenceRoots = java.util.Map.of();

    public WorkerLoop withExpert(ExpertHelp expert, List<String> frameworkPackages) {
        this.expert = expert == null ? ExpertHelp.UNAVAILABLE : expert;
        this.frameworkPackages = frameworkPackages == null ? List.of()
            : List.copyOf(frameworkPackages);
        return this;
    }

    /**
     * A place on the model server the dispatcher has already taken for this worker, so that it -
     * not this class - decides whether the worker waits for a place or only uses a free one.
     * Given back when {@link #run} ends, however it ends. Without one the worker takes its own,
     * waiting its turn, exactly as before.
     */
    public WorkerLoop withLease(InferenceScheduler.Lease lease) {
        this.heldLease = lease;
        return this;
    }

    private volatile InferenceScheduler.Lease heldLease;

    /**
     * Told the moment this worker's model session has ended WITHOUT being stopped - before its
     * change is collected and committed, which takes a while and is no longer something a stop
     * signal can reach. The dispatcher uses it to know which candidates are still in a session.
     */
    public WorkerLoop whenSessionIsOver(Runnable told) {
        this.whenSessionIsOver = told;
        return this;
    }

    private volatile Runnable whenSessionIsOver;

    /**
     * How old tool results are kept small in this worker's conversation (see
     * {@link #TIDY_ABOVE_TOKENS}). Null leaves the conversation as it always was.
     */
    public WorkerLoop withSessionOptions(AgentRuntime.SessionOptions options) {
        this.sessionOptions = options;
        return this;
    }

    /**
     * A worker's old tool results are replaced by their first lines (2026-10-02), with the
     * mechanism the expert's sessions already use ({@code HistoryTrim.tidy}).
     *
     * <p><b>Why.</b> Harness run 66 sent the workers' model 5,419,009 prompt tokens to have it
     * write 102,036: every turn resends every file body and every build log an earlier turn
     * produced. A worker that read a file six turns ago and has edited it since does not need
     * that text again - the file on disk is the truth, and one {@code read} gets it back.
     *
     * <p><b>The figures.</b> Results outside the last four turns are left alone until they add up
     * to more than {@link #TIDY_ABOVE_TOKENS}; then the oldest are cut to their first
     * {@link #DIGEST_CHARS} characters until {@link #TIDY_TO_TOKENS} of them are left whole. Two
     * thresholds far apart on purpose: every rewrite changes the conversation from the rewritten
     * message on, so a server that keeps a conversation's prefix warm must read the rest again.
     * At about two thousand tokens a turn this rewrites once every four or five turns, not every
     * turn. The system prompt - task, rules, knowledge brief - is never touched, so the prefix
     * every worker of a task shares stays byte-identical for the whole session.
     *
     * <p>Not a limit: nothing is stopped by it, and the compaction at the real room
     * ({@code HistoryTrim.trim}) is unchanged.
     */
    static final int TIDY_ABOVE_TOKENS = 12_000;
    static final int TIDY_TO_TOKENS = 3_000;
    static final int DIGEST_CHARS = 600;

    private volatile AgentRuntime.SessionOptions sessionOptions = new AgentRuntime.SessionOptions(
        TIDY_ABOVE_TOKENS, TIDY_TO_TOKENS, DIGEST_CHARS, null);

    public WorkerResult run() {
        try {
            return runHoldingItsPlace();
        } finally {
            InferenceScheduler.Lease given = heldLease;
            if (given != null) {
                scheduler.releaseLease(given); // harmless when run() already gave it back
            }
        }
    }

    private WorkerResult runHoldingItsPlace() {
        UUID candidateId = UUID.randomUUID();
        String branch = "swarm/" + task.id() + "/" + workerIndex;
        Path workspace = null;

        log.info("Worker {} starting (profile={}, temp={})", workerIndex, config.modelProfileId(), config.temperature());
        try {
            if (gitService.isEnabled()) {
                // addOrResumeWorktree, not addWorktree: branch names are deterministic
                // (swarm/<taskId>/<workerIndex>), so a RESUMED run dispatches the exact branch name
                // a now-dead attempt already created. Failing outright on "branch already exists" is
                // what made every resumed run's workers die instantly (DEVELOPER_CORRECTIONS.md).
                workspace = gitService.addOrResumeWorktree(branch,
                    WORKTREE_ROOT.resolve(candidateId.toString()),
                    startPoint == null ? "HEAD" : startPoint, runId.toString());
            } else {
                log.warn("Worker {}: no target repo configured — running without a worktree; "
                    + "candidate cannot be verified", workerIndex);
            }

            // The reservation is the model's SERVED context ceiling, which is a per-model number:
            // it used to be a hardcoded 65536, so a model served with a different ceiling was
            // admitted against a figure that had nothing to do with it.
            int reservedTokens = endpoint != null
                ? endpoint.quirks().servedContextTokens() : ModelQuirks.DEFAULTS.servedContextTokens();
            InferenceScheduler.Lease given = heldLease;
            InferenceScheduler.Lease lease = given != null ? given
                : scheduler.acquireLease(config.modelProfileId(), reservedTokens);
            try {
                if (workspace == null || endpoint == null) {
                    log.error("Worker {}: live mode needs a worktree and a worker model endpoint "
                        + "(repoPath + roles.workerFamilies in config)", workerIndex);
                    // A missing repo or endpoint is a configuration gap, not a slow worker — nothing
                    // here ever waited on a clock.
                    return new WorkerResult(candidate(candidateId, branch, "",
                        null, CandidateState.FAILED, KillReason.WORKER_ERROR), workspace);
                }
                return runLiveSession(candidateId, branch, workspace);
            } finally {
                scheduler.releaseLease(lease);
            }
        } catch (Exception e) {
            // An unreachable endpoint is not a slow worker. Labelling it TIMEOUT is what made an
            // outage look like eight candidates that had genuinely tried and failed, which is what
            // sent the engine into a repair round on nothing.
            //
            // Nothing else caught here is a timeout either — this branch is everything BEFORE or
            // AROUND the model session: git worktree setup, sandbox attach, an unexpected exception
            // from the plumbing. None of it ever waited on a clock, so WORKER_ERROR (not TIMEOUT) is
            // the default for "not an outage" — see DEVELOPER_CORRECTIONS.md for the incident where a
            // git worktree failure that took under a second was reported as a timeout and sent an
            // operator looking for slowness that was never there.
            KillReason reason = EndpointOutage.isOutage(e)
                ? KillReason.ENDPOINT_OUTAGE : KillReason.WORKER_ERROR;
            log.error("Worker {} failed ({}): {}", workerIndex, reason, e.getMessage(), e);
            return new WorkerResult(candidate(candidateId, branch, "",
                null, CandidateState.FAILED, reason), workspace);
        }
    }

    private WorkerResult runLiveSession(UUID candidateId, String branch, Path workspace) throws Exception {
        // The model's exec tool runs in a per-candidate Docker sandbox when one is configured
        // (spec §8): its worktree is bind-mounted at /workspace and arbitrary model commands run
        // in the container, not on the workstation. File ops (read/apply_diff/write_file) stay
        // host-side on the shared worktree regardless.
        //
        // Neither a launch failure nor a missing sandbox ever becomes "run it on this PC":
        // SandboxAttach fails the candidate, unless HostExecution was switched on by name.
        String writeSetEnv = task.writeSet() == null ? "" : String.join(",", task.writeSet());
        String who = "Worker " + workerIndex + " (" + task.title() + ")";
        // Everything the container can see of this machine, besides the Maven repository: its own
        // checkout, read-write, and the reference folders, read-only. Sorted, so the order of
        // the mounts never depends on a map's iteration order.
        List<DockerSandboxManager.ReadOnlyMount> reference = referenceRoots.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(root -> new DockerSandboxManager.ReadOnlyMount(
                root.getValue().toAbsolutePath().toString(),
                DockerSandboxManager.referenceMountPath(root.getKey())))
            .toList();
        SandboxAttach.Attachment attachment = SandboxAttach.attach(
            sandbox, workspace.toAbsolutePath().toString(), writeSetEnv, who, reference);
        if (attachment.target() instanceof com.swarmcoder.verify.SandboxExecTarget inContainer) {
            // A model's commands, not the product's: bash where the image has it.
            inContainer.preferBash();
        }
        if (attachment.refused()) {
            return new WorkerResult(candidate(candidateId, branch, "", null,
                CandidateState.FAILED, KillReason.SANDBOX_UNAVAILABLE), workspace);
        }
        try {
            if (attachment.handle() != null) {
                log.info("Worker {} exec sandboxed in container {}", workerIndex,
                    attachment.handle().containerId());
            }
            return runLiveSessionWith(candidateId, branch, workspace, attachment.target());
        } finally {
            SandboxAttach.release(sandbox, attachment);
        }
    }

    /**
     * How many tool turns this worker gets, most specific source first.
     *
     * <p>The task's own {@link com.swarmcoder.domain.TokenBudget} wins when it states one — that is
     * the per-task escape hatch, and what the tests set. Otherwise the swarm policy, which carries
     * the number resolved over story, project and settings file (see
     * {@link com.swarmcoder.domain.TurnAllowance}). Otherwise the built-in default.
     *
     * <p><b>What this replaced, and why it matters.</b> Until 2026-09-01 this read a hardcoded 30
     * that nothing could change: {@code task.budget()} was null on every task the product builds,
     * because no production code ever constructed a {@code TokenBudget}. Workers were dying at
     * turn 31 with BUDGET_EXCEEDED after spending 71,000 of 2,500,000 permitted tokens. Turns are
     * not the expensive dimension; tokens are, and {@code maxTokens} above already bounds those
     * and is checked every single turn. This cap is a runaway backstop and nothing more.
     */
    static int maxTurnsFor(Task task) {
        if (task.budget() != null && task.budget().maxToolTurns() > 0) {
            return task.budget().maxToolTurns();
        }
        if (task.swarmPolicy() != null && task.swarmPolicy().maxToolTurns() > 0) {
            return task.swarmPolicy().maxToolTurns();
        }
        return TurnAllowance.BUILT_IN_MAX_TOOL_TURNS;
    }

    private WorkerResult runLiveSessionWith(UUID candidateId, String branch, Path workspace,
                                            ExecTarget commandTarget) throws Exception {
        WorkerToolbox toolbox = new WorkerToolbox(workspace, task, apiLookup,
            gitService.isEnabled() ? gitService.repoPath() : null, commandTarget, protectedPaths);
        // Names this worker/task on every exec-start/exit log line and, for a Docker sandbox
        // launch failure that falls back to the host, on the local target actually driving the
        // model's shell commands. See LocalProcessExecTarget#setLogContext.
        toolbox.setExecLogContext("Worker " + workerIndex + " (" + task.title() + ")");
        // What this worker can read instead of guessing, named in its own words. Used only when
        // the worker has investigated too long without writing anything (§32).
        toolbox.setReferenceHint(referenceHint(bundle));
        toolbox.setExpert(expert);
        toolbox.setAcceptanceSource(acceptanceSource);
        toolbox.setOtherTasks(otherTasks);
        toolbox.setReferenceRoots(referenceRoots);
        toolbox.setFrameworkPackages(frameworkPackages);
        // This worker's own room — the one its session is compacted against — so a lookup answer
        // the Librarian sized for the workers is not cut back to the baseline on the way in.
        toolbox.setMaterialBudget(endpoint == null ? MaterialBudget.BASELINE
            : MaterialBudget.of(endpoint.quirks()));
        long maxTokens = task.budget() != null ? task.budget().maxTotalTokens() : DEFAULT_MAX_TOTAL_TOKENS;
        int maxTurns = maxTurnsFor(task);
        // Whether it was THIS guard's token-budget rule that stopped the worker. The compaction
        // throws the same BUDGET_EXCEEDED when a history no longer fits its room, and only one of
        // the two is evidence about room — see ContextDeath.
        java.util.concurrent.atomic.AtomicBoolean guardStoppedIt =
            new java.util.concurrent.atomic.AtomicBoolean();

        AgentRuntime.TurnGuard guard = new AgentRuntime.TurnGuard() {
            /** {@link WorkerToolbox#progressMarks()} when the previous turn was checked. */
            private int marksAtLastTurn = -1;
            private int idleTurns;

            @Override
            public Optional<KillReason> check(TurnInfo info) {
                if (groupSignal != null && groupSignal.isSuperseded()) {
                    return Optional.of(groupSignal.reason());
                }
                // blockingViolations(), not "anything outside the write set": a worker that
                // reaches one neighbouring file to do its job is no longer stopped for it. Only
                // protected places - .git, .swarmcoder, a locked module, the acceptance tests -
                // still count toward the kill. See PathPolicy.Verdict.
                KillReason state = earlyKill.checkState(info.consecutiveTextTurns(),
                    toolbox.blockingViolations(), info.tokensUsed(), maxTokens);
                if (state != null) {
                    if (state == KillReason.BUDGET_EXCEEDED) {
                        guardStoppedIt.set(true);
                    }
                    return Optional.of(state);
                }
                KillReason stall = stalled(info);
                return Optional.ofNullable(stall != null ? stall : idle(info));
            }

            /**
             * The turn-counting safety stop (live run 74): turn after turn that changed no file
             * and brought back nothing new. Looked at when a turn's answer arrives, so what is
             * compared is what the turns BEFORE it achieved.
             */
            private KillReason idle(TurnInfo info) {
                int marks = toolbox.progressMarks();
                if (marksAtLastTurn < 0 || marks != marksAtLastTurn) {
                    idleTurns = 0;
                } else {
                    idleTurns++;
                }
                marksAtLastTurn = marks;
                KillReason reason = earlyKill.checkIdleTurns(idleTurns);
                if (reason != null) {
                    log.warn("Worker {} stopped on task '{}': {} turns in a row changed no file "
                        + "and brought back no tool result it had not already seen, after {} "
                        + "turns and {} tokens. Its work so far is verified if it changed "
                        + "anything.", workerIndex, task.title(), idleTurns, info.turnIndex(),
                        info.tokensUsed());
                }
                return reason;
            }

            /**
             * The stall guard (§32): a worker that has stopped converging on a change is stopped
             * here instead of at its token ceiling eighty thousand tokens later.
             *
             * <p>Which reason it gets matters more than that it is stopped. When the documentation
             * search has been answering different questions with the same section, the worker had
             * no documented route left and the run is evidence about the SEARCH — so the red chip
             * on the graph must say that, not that the worker misbehaved.
             */
            private KillReason stalled(TurnInfo info) {
                int looking = toolbox.investigationToolCalls();
                int fruitless = toolbox.fruitlessCallsInARow();
                KillReason reason = earlyKill.checkProgress(looking, fruitless);
                if (reason == null) {
                    return null;
                }
                boolean docsFailed = toolbox.documentationLookupIsNotDiscriminating();
                log.warn("Worker {} stopped on task '{}': {}, after {} turns and {} tokens. {}{}",
                    workerIndex, task.title(),
                    earlyKill.progressDiagnosis(looking, fruitless, toolbox.readingPaused()),
                    info.turnIndex(), info.tokensUsed(),
                    docsFailed
                        ? "lookup_api had been answering different questions with the same section, "
                          + "so this worker had no documented route left - fix the documentation "
                          + "search, not the worker."
                        : "It had reference documentation available and was not converging on a "
                          + "change.",
                    docsFailed ? asLines(formatLookupEvidence(toolbox.lookupHistory())) : "");
                return docsFailed ? KillReason.DOCS_DEAD_END : reason;
            }

            @Override
            public Optional<String> steeringGiven() {
                return Optional.ofNullable(toolbox.drainSteer());
            }
        };

        // Shared prefix first, per-worker persona strictly after (prefix-cache alignment).
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(
            "worker-" + workerIndex,
            bundle.forWorker(persona(config.personaId()), null),
            endpoint,
            config.temperature(),
            maxTurns,
            toolbox.bindings(),
            guard,
            new AgentRuntime.SessionMeta(UUID.randomUUID(), runId, task.id(), candidateId, workerIndex))
            .with(sessionOptions);

        AgentRuntime.SessionResult result;
        Runnable noLongerListening = () -> { };
        boolean timedOutSmall = false;
        try (AgentRuntime.AgentSession session = agentRuntime.open(spec)) {
            // Stopped (another attempt passed, the round's time is up, its place is needed):
            // the model request in flight is abandoned, not waited for.
            if (groupSignal != null) {
                noLongerListening = groupSignal.onStop(session::cancel);
            }
            result = session.run("Begin now. Implement the task from your instructions; "
                + "call report_done when the change is complete and verified.");
            timedOutSmall = session.timedOutOnASmallConversation();
            Runnable over = whenSessionIsOver;
            if (over != null && result.killReason().isEmpty()) {
                over.run();
            }
        } finally {
            noLongerListening.run();
            // Any server the worker started in the background (a trailing `&`/`nohup` command,
            // translated into a real background launch on Windows — see
            // LocalProcessExecTarget#startBackground) dies with this worker rather than the
            // workstation. Runs whether the worker finished, was killed, or threw.
            toolbox.stopBackgroundProcesses();
            toolbox.leaveLanguageServer();
        }

        String diff = captureDiff(workspace);
        // Recorded on EVERY outcome, killed ones included. A candidate that died still tells the
        // operator what it had reached, which is exactly what the old kill destroyed.
        List<String> outOfWriteSet = toolbox.outOfWriteSetPaths();
        List<String> helpEvidence = formatHelpCalls(toolbox.helpCalls());
        String helpSummary = helpChip(toolbox.helpCalls());
        // A worker that met a rule the library cannot honour says so with evidence (harness runs
        // 53 and 55, 2026-10-01); kept on every outcome, like the help calls, for the judge.
        List<com.swarmcoder.domain.RuleDispute> disputes = toolbox.ruleDisputes();
        if (!disputes.isEmpty()) {
            log.info("Worker {} disputed {} rule(s): {}", workerIndex, disputes.size(),
                disputes.stream().map(com.swarmcoder.domain.RuleDispute::headline).toList());
        }
        if (!helpEvidence.isEmpty()) {
            log.info("Worker {} asked for help {} time(s): {}", workerIndex, helpEvidence.size(),
                String.join("; ", helpEvidence));
        }
        if (!outOfWriteSet.isEmpty()) {
            log.info("Worker {} changed {} file(s) outside its write set: {}",
                workerIndex, outOfWriteSet.size(), String.join(", ", outOfWriteSet));
        }
        if (result.killReason().isPresent()) {
            KillReason reason = result.killReason().get();
            ContextDeath death = ContextDeath.of(reason, result.turns(), maxTurns, guardStoppedIt.get(),
                timedOutSmall);
            log.info("Worker {} killed: {} after {} turns{}", workerIndex, reason, result.turns(),
                death == null ? "" : " — " + death.sentence());
            // The same evidence the kill line above carries, kept on the candidate itself: a run
            // ends, its WorkerToolbox goes away, and without this the ONLY place "which questions,
            // which section" ever existed was a log line nobody but this process had read.
            List<String> docsEvidence = reason == KillReason.DOCS_DEAD_END
                ? formatLookupEvidence(toolbox.lookupHistory()) : List.of();
            if (StoppedWork.worthVerifying(reason, diff)) {
                // Its change is verified like any other (see StoppedWork), and a candidate that
                // may be selected needs its work on its branch.
                gitService.commitAll(workspace, "swarm candidate " + workerIndex + " for: "
                    + task.title() + "\n\n(stopped: " + reason + ")");
            }
            return new WorkerResult(disputing(candidate(candidateId, branch, diff, null,
                CandidateState.KILLED, reason, outOfWriteSet, docsEvidence, helpEvidence,
                helpSummary), disputes), workspace, death);
        }
        if (diff.isBlank()) {
            // The model's own account is the best evidence of WHY nothing changed
            // (apply_diff rejections, write-set confusion, "already implemented", …).
            String finalOutput = result.finalOutput() == null ? "(no final output)"
                : result.finalOutput().strip();
            log.warn("Worker {} finished without producing any change after {} turns / {} tokens; "
                + "final output: {}", workerIndex, result.turns(), result.tokensUsed(),
                finalOutput.length() > 500 ? finalOutput.substring(0, 500) + "…" : finalOutput);
            return new WorkerResult(disputing(candidate(candidateId, branch, diff, null,
                CandidateState.FAILED, null, outOfWriteSet, List.of(), helpEvidence, helpSummary),
                disputes), workspace);
        }
        gitService.commitAll(workspace, "swarm candidate " + workerIndex + " for: " + task.title()
            + "\n\n" + result.finalOutput());
        log.info("Worker {} done in {} turns, {} tokens", workerIndex, result.turns(), result.tokensUsed());
        return new WorkerResult(disputing(candidate(candidateId, branch, diff, null,
            CandidateState.SURVIVED, null, outOfWriteSet, List.of(), helpEvidence, helpSummary),
            disputes), workspace);
    }

    private static CandidateSolution disputing(CandidateSolution candidate,
                                               List<com.swarmcoder.domain.RuleDispute> disputes) {
        candidate.setRuleDisputes(disputes);
        return candidate;
    }

    private static String persona(String personaId) {
        return WorkerPersonas.text(personaId);
    }

    /**
     * The names of the reference documents this worker was given, as one sentence — read back
     * out of the knowledge brief's document catalogue so there is exactly one source of truth
     * for "what material exists". Blank when the brief lists none, which is the signal that
     * reverse-engineering may genuinely be the only route.
     */
    static String referenceHint(PromptBundle bundle) {
        String brief = bundle.segments().stream()
            .filter(segment -> segment.kind() == PromptBundle.SegmentKind.KNOWLEDGE_BRIEF)
            .map(PromptBundle.Segment::content)
            .findFirst().orElse("");
        List<String> documents = new java.util.ArrayList<>();
        for (String line : brief.split("\n")) {
            String trimmed = line.strip();
            // The catalogue's lines are "- <root>/<path>.md — <title>".
            if (trimmed.startsWith("- ") && trimmed.contains(".md")) {
                String address = trimmed.substring(2, trimmed.indexOf(".md") + 3).strip();
                if (!address.contains(" ") && documents.size() < 5 && !documents.contains(address)) {
                    documents.add(address);
                }
            }
        }
        if (documents.isEmpty()) {
            return "";
        }
        return "Your knowledge brief lists real documentation for this project, including "
            + String.join(", ", documents) + ".";
    }

    private String captureDiff(Path workspace) {
        try {
            LocalProcessExecTarget target = new LocalProcessExecTarget(workspace);
            ExecResult add = target.exec("git add -A", 60);
            if (!add.succeeded()) {
                log.warn("git add failed in {}: {}", workspace, add.output());
            }
            ExecResult diff = target.exec("git diff --cached", 60);
            return diff.succeeded() ? diff.output() : "";
        } catch (Exception e) {
            log.warn("Diff capture failed in {}: {}", workspace, e.getMessage());
            return "";
        }
    }

    private CandidateSolution candidate(UUID id, String branch, String diff,
                                        VerificationReport verification, CandidateState state, KillReason kill) {
        return candidate(id, branch, diff, verification, state, kill, List.of());
    }

    private CandidateSolution candidate(UUID id, String branch, String diff,
                                        VerificationReport verification, CandidateState state,
                                        KillReason kill, List<String> outOfWriteSet) {
        return candidate(id, branch, diff, verification, state, kill, outOfWriteSet, List.of());
    }

    private CandidateSolution candidate(UUID id, String branch, String diff,
                                        VerificationReport verification, CandidateState state,
                                        KillReason kill, List<String> outOfWriteSet,
                                        List<String> docsDeadEndEvidence) {
        return candidate(id, branch, diff, verification, state, kill, outOfWriteSet,
            docsDeadEndEvidence, List.of());
    }

    private CandidateSolution candidate(UUID id, String branch, String diff,
                                        VerificationReport verification, CandidateState state,
                                        KillReason kill, List<String> outOfWriteSet,
                                        List<String> docsDeadEndEvidence, List<String> helpCalls) {
        return candidate(id, branch, diff, verification, state, kill, outOfWriteSet,
            docsDeadEndEvidence, helpCalls, "");
    }

    private CandidateSolution candidate(UUID id, String branch, String diff,
                                        VerificationReport verification, CandidateState state,
                                        KillReason kill, List<String> outOfWriteSet,
                                        List<String> docsDeadEndEvidence, List<String> helpCalls,
                                        String helpSummary) {
        CandidateSolution candidate = new CandidateSolution(id, task.id(), workerIndex, branch,
            config, diff, verification, null, null, state, kill);
        candidate.setOutOfWriteSetPaths(outOfWriteSet);
        candidate.setDocsDeadEndEvidence(docsDeadEndEvidence);
        candidate.setHelpCalls(helpCalls);
        candidate.setHelpSummary(helpSummary);
        return candidate;
    }

    /**
     * {@code toolbox.lookupHistory()} rendered as the numbered lines the kill line and the
     * candidate's diagnosis both show: {@code N. "question" → section}. One formatting so a WARN
     * in the log and the evidence on the archived candidate never say it two different ways.
     */
    /**
     * One line per help call: where the answer came from and what it cost.
     *
     * <p>Deliberately says the source. A deterministic answer came out of code that compiles in
     * this codebase today, and a model answer did not — the operator reading a diagnosis, and the
     * judge reading a candidate, should be able to tell those apart at a glance.
     */
    static List<String> formatHelpCalls(List<ExpertHelp.Answer> calls) {
        List<String> lines = new ArrayList<>();
        int n = 1;
        for (ExpertHelp.Answer answer : calls) {
            lines.add((n++) + ". " + switch (answer.source()) {
                case DETERMINISTIC -> "answered from this codebase's own code (free)";
                case MODEL -> "answered by the expert model" + howItWorked(answer)
                    + " (" + answer.tokens() + " tokens)";
                case NONE -> "could not be answered" + howItWorked(answer);
            } + whyReason(answer));
        }
        return List.copyOf(lines);
    }

    /**
     * Why the desk's OWN decision came out this way — {@code [free: covered 0.8 of the question]}
     * or {@code [escalated: free answer covered 0.2]}. Not about the answer's content, about
     * whether the free tier's coverage of the question was enough to count as an answer; empty
     * when the desk did not record one.
     */
    private static String whyReason(ExpertHelp.Answer answer) {
        return answer.reason() == null || answer.reason().isBlank() ? ""
            : " [" + answer.reason() + "]";
    }

    /**
     * What the expert DID before it answered — the part that separates a looked-up answer from a
     * recalled one.
     *
     * <p>The expert is an agent session with read-only tools over this codebase now, so an answer
     * can carry the number of turns it took and the lookups it made. Both are on the record because
     * both change how it should be read: an answer reached after reading four files in this project
     * is a different thing from one a model produced in a single turn out of its own memory, and
     * the judge and the operator are the two readers who need to be able to tell them apart. Empty
     * for a free answer and for an expert that answered without looking anything up — there is
     * nothing to say then, and a parenthesis saying "0 lookups" on every line is noise.
     */
    private static String howItWorked(ExpertHelp.Answer answer) {
        if (answer.expertTurns() <= 0 && answer.toolsUsed().isEmpty()) {
            return "";
        }
        int lookups = answer.toolsUsed().size();
        return " after " + answer.expertTurns() + (answer.expertTurns() == 1 ? " turn" : " turns")
            + " and " + lookups + (lookups == 1 ? " lookup" : " lookups")
            + (lookups == 0 ? "" : " (" + String.join(", ", answer.toolsUsed()) + ")");
    }

    /**
     * The one line the run graph puts on a worker's card: how often it asked, and how hard the
     * expert worked on those asks.
     *
     * <p>"asked the expert 2x (7 lookups)" is the whole sentence, and it is deliberately not a
     * warning. Asking is the behaviour this system wants; the alternative, measured five times in a
     * plain harness, is a worker that spends sixty-six shell commands disassembling the framework's
     * jars and delivers nothing. What the operator actually needs to see on the card is whether the
     * answers it got were LOOKED UP or recalled, and the lookup count is that.
     */
    static String helpChip(List<ExpertHelp.Answer> calls) {
        if (calls == null || calls.isEmpty()) {
            return "";
        }
        int lookups = calls.stream().mapToInt(a -> a.toolsUsed().size()).sum();
        String asked = "asked the expert " + calls.size() + "x";
        return lookups == 0 ? asked
            : asked + " (" + lookups + (lookups == 1 ? " lookup)" : " lookups)");
    }

    static List<String> formatLookupEvidence(List<WorkerToolbox.LookupQuestion> history) {
        List<String> lines = new ArrayList<>();
        int n = 1;
        for (WorkerToolbox.LookupQuestion question : history) {
            lines.add((n++) + ". \"" + question.question() + "\" → "
                + (question.section().isBlank() ? "(no matching section)" : question.section()));
        }
        return lines;
    }

    /** Formatted evidence lines, indented and newline-led, ready to append to a log sentence. */
    private static String asLines(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("\n  ").append(line);
        }
        return sb.toString();
    }
}
