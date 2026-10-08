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
import com.swarmcoder.inference.AdaptiveConcurrency;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.knowledge.ExpertDesk;
import com.swarmcoder.knowledge.RunAnswerCache;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.verify.LocalProcessExecTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.PromptBundle;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.LoggerFactory;

public class SwarmDispatcher {

    private final InferenceScheduler scheduler;
    private final GitService gitService;
    private final AgentRuntime agentRuntime;
    private final ModelProfileRegistry profiles;
    private final ApiLookup apiLookup;
    /**
     * Builds the help desk's expert tier (ExpertDesk) fresh for EACH worker — called once per
     * worker dispatched, never shared, because the desk's per-worker escalation cap lives on the
     * instance. Defaults to a factory returning {@link ExpertHelp#UNAVAILABLE}, so a dispatcher
     * nobody wires this into behaves exactly as it always has.
     */
    private final Supplier<ExpertHelp> expertFactory;
    /** Package prefixes of the project's reference material, for the framework-error hint. */
    private final List<String> frameworkPackages;
    private volatile long staggerMs;
    /** Per-candidate sandbox for the workers' exec tool (spec §8); null = run exec locally. */
    private volatile com.swarmcoder.sandbox.DockerSandboxManager sandbox;
    /** Operator-declared locked-down modules; handed to every worker's toolbox. Never null. */
    private volatile List<String> protectedPaths = List.of();
    /**
     * Fewer workers on a server whose workers keep running out of room, each with more of it. A
     * controller nobody registered an endpoint with is a no-op, which is what every test and
     * embedded use gets — see {@link AdaptiveConcurrency}.
     */
    private volatile AdaptiveConcurrency concurrency = new AdaptiveConcurrency();

    public SwarmDispatcher(InferenceScheduler scheduler, GitService gitService,
                           AgentRuntime agentRuntime, ModelProfileRegistry profiles) {
        this(scheduler, gitService, agentRuntime, profiles, ApiLookup.UNAVAILABLE);
    }

    public SwarmDispatcher(InferenceScheduler scheduler, GitService gitService,
                           AgentRuntime agentRuntime, ModelProfileRegistry profiles,
                           ApiLookup apiLookup) {
        this(scheduler, gitService, agentRuntime, profiles, apiLookup,
            () -> ExpertHelp.UNAVAILABLE, List.of());
    }

    public SwarmDispatcher(InferenceScheduler scheduler, GitService gitService,
                           AgentRuntime agentRuntime, ModelProfileRegistry profiles,
                           ApiLookup apiLookup, Supplier<ExpertHelp> expertFactory,
                           List<String> frameworkPackages) {
        this.scheduler = scheduler;
        this.gitService = gitService;
        this.agentRuntime = agentRuntime;
        this.profiles = profiles;
        this.apiLookup = apiLookup == null ? ApiLookup.UNAVAILABLE : apiLookup;
        this.expertFactory = expertFactory == null ? () -> ExpertHelp.UNAVAILABLE : expertFactory;
        this.frameworkPackages = frameworkPackages == null ? List.of() : List.copyOf(frameworkPackages);
    }

    /**
     * Launch spacing between workers of a group ({@code swarm.dispatch.staggerMs}, spec §17):
     * the first worker's prefill warms the vLLM prefix cache so the rest hit it instead of
     * prefilling the identical shared prefix concurrently. 0 (default) = no stagger.
     */
    public void setStaggerMs(long staggerMs) {
        this.staggerMs = Math.max(0, staggerMs);
    }

    /**
     * Enables per-candidate sandboxing of the workers' {@code exec} tool (spec §8). With no
     * sandbox set, workers run exec on the workstation - the app sets one by default and so do
     * the live harnesses. A sandbox that fails to launch fails the worker; see
     * {@link SandboxAttach}.
     */
    public void setSandbox(com.swarmcoder.sandbox.DockerSandboxManager sandbox) {
        this.sandbox = sandbox;
    }

    /** Reference folders by label; see {@link WorkerLoop#withReferenceRoots}. Never null. */
    private volatile java.util.Map<String, java.nio.file.Path> referenceRoots = java.util.Map.of();

    /**
     * The run's acceptance-tests commit, by run id (null or blank when it has none yet). A worker
     * reads the test its task claims from it through {@code acceptance_test}, read-only.
     */
    private volatile java.util.function.Function<UUID, String> testsCommitOf = id -> null;

    public void setTestsCommitOf(java.util.function.Function<UUID, String> testsCommitOf) {
        this.testsCommitOf = testsCommitOf == null ? id -> null : testsCommitOf;
    }

    /**
     * The run's reservations, by run id: who of the plan holds which file (section 73). Null,
     * or a run it knows nothing of, asks nobody.
     */
    private volatile java.util.function.Function<UUID, ReservationBook> reservationsOf = id -> null;

    void setReservationsOf(java.util.function.Function<UUID, ReservationBook> reservationsOf) {
        this.reservationsOf = reservationsOf == null ? id -> null : reservationsOf;
    }

    /** The decision a worker's writes outside its task's reservation are held to; may be null. */
    private com.swarmcoder.runtime.PathPolicy.OtherTasks otherTasksFor(UUID runId, Task task,
                                                                      int worker) {
        ReservationBook book = runId == null ? null : reservationsOf.apply(runId);
        return book == null ? null : book.forWorker(task, String.valueOf(worker));
    }

    /** One file of the run's tests commit as text, or null; evaluated when the worker asks. */
    java.util.function.Function<String, String> acceptanceSourceOf(UUID runId) {
        return path -> {
            String commit = runId == null ? null : testsCommitOf.apply(runId);
            if (commit == null || commit.isBlank() || gitService == null) {
                return null;
            }
            byte[] bytes = gitService.fileAt(commit, path);
            return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    public void setReferenceRoots(java.util.Map<String, java.nio.file.Path> roots) {
        this.referenceRoots = roots == null ? java.util.Map.of() : java.util.Map.copyOf(roots);
    }

    /**
     * What a worker is told about the shell its exec tool runs, for THIS dispatcher's workers:
     * the container when one is configured, the workstation's own shell otherwise. It has to
     * follow where the commands really run - a worker told "cmd.exe" whose commands run in a
     * Linux container writes `dir /s /b` at bash, and one told the opposite writes
     * `cd /c/Users/...` at cmd.exe.
     */
    String shellNote() {
        return (sandbox == null ? hostShellNote() : containerShellNote(referenceRoots.keySet()))
            + (apiLookup.languageServer() ? LANGUAGE_SERVER_NOTE : "");
    }

    /**
     * What a worker is told about the Java language server's tools, when one is installed.
     * Static text: the same for every worker of this machine, so the shared prefix does not
     * depend on worker identity.
     */
    static final String LANGUAGE_SERVER_NOTE =
        "The Java language server answers what the syntax tree does not, also at once: "
        + "find_symbol for a type by part of its name, implementations_of and supertypes_of for "
        + "a type's hierarchy, callers_of for who calls a method, doc_of for a symbol's "
        + "signature and documentation - and shape_of lists a type from a library jar with its "
        + "real signatures, so never call a library method you have not seen listed. In your "
        + "own checkout: problems_in tells you in seconds whether a file you changed compiles, "
        + "rename_symbol renames a type, method or field everywhere it is used, and "
        + "organize_imports fixes a file's imports.\n";

    /** Static per host, identical for every worker - the prefix hash does not depend on it. */
    static String hostShellNote() {
        return LocalProcessExecTarget.isWindows()
            ? "Your exec tool runs commands through Windows' cmd.exe. If a command's "
                + "output ever shows your checkout's path spelled the POSIX way — like "
                + "`/c/Users/dev/...` — that names the exact same folder as "
                + "`C:\\Users\\dev\\...`; it does not mean you are in a POSIX shell, and "
                + "cmd.exe will reject a `cd` written in that spelling (\"The system "
                + "cannot find the path specified\"). Write paths the Windows way: "
                + "`C:\\Users\\...` or `C:/Users/...`, and prefer not to `cd` at all.\n"
            : "Your exec tool runs commands through a POSIX shell (`sh -c`).\n";
    }

    /**
     * Identical for every worker of a project (the labels are sorted), so the shared prefix still
     * does not depend on worker identity.
     */
    static String containerShellNote(java.util.Collection<String> referenceLabels) {
        StringBuilder note = new StringBuilder(
            "Your exec tool runs commands inside a Linux container, through bash. Write every "
            + "command for Linux: no cmd.exe, no PowerShell, no `dir`, no drive letters, no "
            + "backslash paths. Inside it your checkout is `/workspace`, which is already the "
            + "working directory of every command and the only place you can write.\n");
        if (referenceLabels != null && !referenceLabels.isEmpty()) {
            note.append("The reference material is there too, read-only: ");
            note.append(referenceLabels.stream().sorted()
                .map(label -> "`" + com.swarmcoder.sandbox.DockerSandboxManager
                    .referenceMountPath(label) + "`")
                .collect(java.util.stream.Collectors.joining(", ")));
            note.append(". A document your knowledge brief lists as `<root>/<path>` is the file "
                + "`/reference/<root>/<path>`, for `grep`, `find` and the read tool alike.\n");
        }
        note.append("Nothing else of this machine exists in the container - do not search for "
            + "files outside those folders, there are none - and it has no network. Maven runs "
            + "offline against the dependencies already downloaded. git does not work inside "
            + "the container and you do not need it: your changes are collected for you.\n");
        return note.toString();
    }

    /**
     * Locked-down modules from config ({@code protectedPaths}): every worker's toolbox refuses
     * them through {@link com.swarmcoder.runtime.PathPolicy}, write set or no write set. Empty
     * (the default) means only {@code PathPolicy.ALWAYS_PROTECTED} applies.
     */
    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    }

    /**
     * The controller that decides, once per wave, how many workers a model server runs at once and
     * how much room each gets. Null restores the no-op.
     */
    public void setConcurrencyController(AdaptiveConcurrency controller) {
        this.concurrency = controller == null ? new AdaptiveConcurrency() : controller;
    }

    /** The controller in force, for the engine's log lines. */
    public AdaptiveConcurrency concurrencyController() {
        return concurrency;
    }

    public List<WorkerResult> dispatch(Task task, UUID runId) {
        return dispatch(task, runId, null);
    }

    public List<WorkerResult> dispatch(Task task, UUID runId, String knowledgeBrief) {
        return dispatch(task, runId, knowledgeBrief, null);
    }

    public List<WorkerResult> dispatch(Task task, UUID runId, String knowledgeBrief, String projectRules) {
        return dispatch(task, runId, knowledgeBrief, projectRules, null);
    }

    /**
     * @param startPoint the commit every worker's worktree is cut from — the run's own pinned base.
     *                   Null means the repository's current HEAD, which is what a run started before
     *                   base pinning existed has. Pinning it matters because accepting a story now
     *                   moves the delivery branch: with a live HEAD, one story being accepted would
     *                   shift the ground under the workers of another still going, and the candidates
     *                   of one task would be built on a different tree from the candidates of the
     *                   next.
     */
    public List<WorkerResult> dispatch(Task task, UUID runId, String knowledgeBrief,
                                       String projectRules, String startPoint) {
        // Every candidate the policy asks for, to its end: nobody here verifies anything, so no
        // candidate is ever known to have passed and none is withdrawn. The further candidates
        // still start only on places nothing else wants - see CandidateGroup.
        List<WorkerResult> results = new ArrayList<>();
        CandidateGroup group = start(task, runId, knowledgeBrief, projectRules, startPoint, null,
            ended -> {
                if (ended.result() != null) {
                    synchronized (results) {
                        results.add(ended.result());
                    }
                }
            });
        group.awaitThreads();
        return results;
    }

    /**
     * Starts one task's candidates as the places allow and returns at once: the first candidate
     * waits its turn for a place, every further one starts only on a place that would otherwise be
     * idle. See {@link CandidateGroup} for the rules and {@link WaveBoard} for what the tasks of a
     * wave know about each other.
     *
     * @param board    the wave this task is dispatched in, or null when it is dispatched alone
     * @param listener told each candidate's end on that candidate's own thread, after its places
     *                 have been given back - so whatever it does next (verification) holds none
     */
    CandidateGroup start(Task task, UUID runId, String knowledgeBrief, String projectRules,
                         String startPoint, WaveBoard board, CandidateGroup.Listener listener) {
        SwarmPolicy policy = task.swarmPolicy();
        List<SamplingConfig> configs = new ArrayList<>();

        // Diversity matrix (spec §11.1). Family ids come from the ModelProfile registry,
        // in config order — never from string literals (correction S1).
        List<ModelProfile> workers = profiles.workers();
        String familyA = workers.isEmpty() ? "unconfigured" : workers.get(0).id();
        String familyB = workers.size() > 1 ? workers.get(1).id() : familyA;

        // How many may run at once on each family's server, and with how much room — asked ONCE
        // here, so every worker of this wave gets the same answer and a change decided while the
        // wave runs waits for the next one. A running worker's room cannot grow.
        AdaptiveConcurrency.Plan planA = concurrency.plan(familyA);
        AdaptiveConcurrency.Plan planB = policy.splitAcrossFamilies()
            ? concurrency.plan(familyB) : planA;
        int n = waveSize(task, policy.n(), planA, planB);

        // Shared prompt prefix — built ONCE per group, byte-identical for every worker so
        // vLLM's prefix cache pays the prefill a single time (spec §6.3). Diversity content
        // (persona) is appended after the prefix inside WorkerLoop.
        PromptBundle bundle = buildBundle(task, null, knowledgeBrief, projectRules, protectedPaths,
            shellNote());
        LoggerFactory.getLogger(SwarmDispatcher.class)
            // The size rides beside the hash because it is the other half of the same fact: the
            // hash says the prefill is shared, the token count says how much of every worker's
            // working context is spent before it starts.
            .info("Dispatching task '{}' n={} prefixHash={} prefixTokens={} ({})",
                task.title(), n, bundle.prefixHash(), bundle.estimatedTokens(),
                bundle.sizeSummary());
        openingSpan(task, "first", bundle);

        // Say out loud what diversity this group actually has. `splitAcrossFamilies: true` with a
        // single configured model used to do nothing at all, silently, while the config still read
        // as though diversity across models was on.
        LoggerFactory.getLogger(SwarmDispatcher.class)
            .info("Task '{}' diversity: {}", task.title(), describeDiversity(policy, workers));

        // No personas configured used to mean "minimal-diff" for EVERY worker — a lever that
        // quietly did nothing. The default is now the full rotation, so the workers of a group
        // really are asked for different things.
        List<String> personas = policy.personaIds() != null && !policy.personaIds().isEmpty()
            ? policy.personaIds() : WorkerPersonas.DEFAULT_ROTATION;

        for (int i = 0; i < n; i++) {
            String profileId = policy.splitAcrossFamilies() && i % 2 != 0 ? familyB : familyA;
            double temp = policy.tempMin() + (policy.tempMax() - policy.tempMin()) * ((double) i / Math.max(1, n - 1));
            String persona = personas.get(i % personas.size());
            // The seed is ARCHIVED, NOT SENT. Neither request path has a seed parameter — the
            // agent framework's LLMParams has no such field — so a per-worker seed cannot reach
            // the model. It is recorded so a candidate's row is complete, and it must NOT be
            // counted as a diversity lever until something actually transmits it.
            configs.add(new SamplingConfig(profileId, temp, i * 100L, persona, "full-files"));
            LoggerFactory.getLogger(SwarmDispatcher.class)
                .info("  worker {} -> model={} temperature={} persona={}{}", i, profileId,
                    String.format(java.util.Locale.ROOT, "%.2f", temp), persona,
                    i == 0 ? "" : " (starts only on a place nothing else is waiting for)");
        }

        CandidateGroup group = new CandidateGroup(configs, listener);
        for (CandidateGroup.Seat seat : group.seats()) {
            final SamplingConfig cfg = seat.sampling;
            final int idx = seat.index;
            Thread thread = Thread.ofVirtual().name("candidate-" + task.id() + "-" + idx)
                .unstarted(() -> {
                    WorkerResult result = null;
                    try {
                        if (staggerMs > 0 && idx > 0) {
                            Thread.sleep(idx * staggerMs); // virtual thread — parking is free
                        }
                        ModelProfile profile = profiles.find(cfg.modelProfileId());
                        AdaptiveConcurrency.Plan plan = policy.splitAcrossFamilies()
                            && cfg.modelProfileId().equals(familyB) ? planB : planA;
                        AgentRuntime.ModelEndpoint endpoint = endpointFor(profile, plan);
                        String label = "Worker " + idx + " of '" + task.title() + "'";
                        // One of the machine's worker places, one of the places its model server
                        // is currently allowed, and one of the server's sequences — always in
                        // this order, so no two workers can each hold what the other waits for.
                        // The first candidate waits its turn for them; a further one takes them
                        // only when all three are free and nothing is waiting.
                        Places places;
                        try {
                            places = placesFor(group, seat, board, cfg.modelProfileId(), label,
                                reservedTokens(endpoint));
                        } finally {
                            if (idx == 0 && board != null) {
                                board.firstPlaced(task.id()); // placed, or never going to be
                            }
                        }
                        if (places == null) {
                            com.swarmcoder.inference.RunMeter.span("candidate not started|"
                                + task.id() + "|" + idx, System.currentTimeMillis());
                            LoggerFactory.getLogger(SwarmDispatcher.class).info("{} was not "
                                + "started: the task already has a candidate that passed, and "
                                + "nothing is started just to have a second one.", label);
                            return;
                        }
                        try {
                            com.swarmcoder.inference.RunMeter.span("candidate started|"
                                + seat.startedAs().name().toLowerCase(java.util.Locale.ROOT) + "|"
                                + task.id() + "|" + idx, System.currentTimeMillis());
                            if (idx > 0) {
                                LoggerFactory.getLogger(SwarmDispatcher.class).info("{} starts {}",
                                    label, seat.startedAs() == CandidateGroup.Start.NEEDED
                                        ? "because every earlier candidate of its task failed."
                                        : "on a place nothing else was waiting for.");
                            }
                            WorkerLoop loop = new WorkerLoop(task, cfg, idx, scheduler, gitService,
                                agentRuntime, endpoint, runId, bundle,
                                startPoint, seat.signal, apiLookup, sandbox, protectedPaths);
                            loop.withReferenceRoots(referenceRoots);
                            loop.withAcceptanceSource(acceptanceSourceOf(runId));
                            loop.withOtherTasks(otherTasksFor(runId, task, idx));
                            loop.withLease(places.lease);
                            loop.whenSessionIsOver(() -> group.sessionOver(seat));
                            // A fresh desk per worker — see expertFactory's javadoc on why one
                            // cannot be shared across the wave. What IS shared across the wave,
                            // and across every other wave of this run, is the run's own
                            // RunAnswerCache — see expertFor.
                            loop.withExpert(expertFor(runId, idx, bundle, task), frameworkPackages);
                            result = loop.run();
                        } finally {
                            places.close();
                        }
                        concurrency.completed(cfg.modelProfileId(), label,
                            result.candidate().killReason(), result.contextDeath(),
                            plan.concurrency());
                        KillReason stopped = result.candidate().killReason();
                        if (stopped == KillReason.SUPERSEDED || stopped == KillReason.PLACE_NEEDED) {
                            com.swarmcoder.inference.RunMeter.span("candidate cancelled|"
                                + stopped.name() + "|" + task.id() + "|" + idx,
                                System.currentTimeMillis());
                        }
                    } catch (Throwable t) {
                        // A worker death must produce evidence, never silence — Errors from the
                        // agent-framework stack (linkage, serialization) land here. The evidence has
                        // to say WHICH kind of death, because the engine reacts differently: an
                        // outage is waited out, anything else is repaired.
                        //
                        // WORKER_ERROR, not TIMEOUT: nothing that reaches this catch waited on a
                        // clock, and reporting it as TIMEOUT is exactly what made a git worktree
                        // failure that took under a second read as a slow worker.
                        KillReason reason = EndpointOutage.isOutage(t)
                            ? KillReason.ENDPOINT_OUTAGE : KillReason.WORKER_ERROR;
                        LoggerFactory.getLogger(SwarmDispatcher.class)
                            .error("Worker {} died ({}): {}", idx, reason, t.toString(), t);
                        result = new WorkerResult(new CandidateSolution(UUID.randomUUID(),
                            task.id(), idx, "swarm/" + task.id() + "/" + idx, cfg, "",
                            null, null, null, CandidateState.FAILED, reason), null);
                    } finally {
                        group.ended(seat, result);
                    }
                });
            group.runsOn(thread);
        }
        for (Thread thread : group.threads()) {
            thread.start();
        }
        return group;
    }

    /** What one worker holds while it runs: a machine place, a server place and a sequence. */
    private static final class Places implements AutoCloseable {
        private final WorkerSlots.Place slot;
        private final AdaptiveConcurrency.Held permit;
        final InferenceScheduler.Lease lease;

        Places(WorkerSlots.Place slot, AdaptiveConcurrency.Held permit,
               InferenceScheduler.Lease lease) {
            this.slot = slot;
            this.permit = permit;
            this.lease = lease;
        }

        @Override
        public void close() {
            if (lease != null) {
                lease.close();
            }
            permit.close();
            slot.close();
        }
    }

    private static int reservedTokens(AgentRuntime.ModelEndpoint endpoint) {
        // The reservation is the model's SERVED context ceiling - what WorkerLoop always reserved.
        return endpoint != null ? endpoint.quirks().servedContextTokens()
            : com.swarmcoder.inference.ModelQuirks.DEFAULTS.servedContextTokens();
    }

    /**
     * The places one candidate runs on, or null when it was withdrawn before it got any.
     *
     * <p>A spare candidate looks, takes what is free, and looks again a moment later when it is
     * not: it is never in a queue, so it can never be ahead of anything that is.
     */
    private Places placesFor(CandidateGroup group, CandidateGroup.Seat seat, WaveBoard board,
                             String profileId, String label, int reservedTokens)
            throws InterruptedException {
        while (true) {
            if (seat.withdrawn()) {
                return null;
            }
            Places places = null;
            if (seat.waitsItsTurn()) {
                places = waitingItsTurn(profileId, label, reservedTokens);
            } else if (board == null || board.allFirstsPlaced()) {
                places = onlyIfFree(profileId, reservedTokens);
            }
            if (places != null) {
                if (seat.begins(profileId, label)) {
                    return places;
                }
                places.close();
                return null;
            }
            group.awaitChange(SPARE_LOOKS_AGAIN_MILLIS);
        }
    }

    /** How often a spare candidate looks for a free place. Parked on a virtual thread between. */
    private static final long SPARE_LOOKS_AGAIN_MILLIS = 200;

    /**
     * Places for work a task depends on: waits for each in the order asked. When it has to wait,
     * one spare candidate somewhere is asked to give its place up — once, however many of the
     * three it waits for.
     */
    private Places waitingItsTurn(String profileId, String label, int reservedTokens)
            throws InterruptedException {
        boolean asked = false;
        WorkerSlots.Place slot = WorkerSlots.shared().tryEnter();
        if (slot == null) {
            asked = SpareCandidates.giveUpOneFor(profileId, label);
            slot = WorkerSlots.shared().enter(label);
        }
        AdaptiveConcurrency.Held permit = null;
        try {
            permit = concurrency.tryHold(profileId);
            if (permit == null) {
                asked = asked || SpareCandidates.giveUpOneFor(profileId, label);
                permit = concurrency.hold(profileId, label);
            }
            InferenceScheduler.Lease lease = scheduler.tryAcquireLease(profileId, reservedTokens);
            if (lease == null) {
                if (!asked) {
                    SpareCandidates.giveUpOneFor(profileId, label);
                }
                lease = scheduler.acquireLease(profileId, reservedTokens);
            }
            return new Places(slot, permit, lease);
        } catch (InterruptedException | RuntimeException e) {
            if (permit != null) {
                permit.close();
            }
            slot.close();
            throw e;
        }
    }

    /** Places for a spare candidate: all three free this instant and nothing waiting, or null. */
    private Places onlyIfFree(String profileId, int reservedTokens) {
        WorkerSlots.Place slot = WorkerSlots.shared().tryEnter();
        if (slot == null) {
            return null;
        }
        AdaptiveConcurrency.Held permit = concurrency.tryHold(profileId);
        if (permit == null) {
            slot.close();
            return null;
        }
        InferenceScheduler.Lease lease;
        try {
            lease = scheduler.tryAcquireLease(profileId, reservedTokens);
        } catch (RuntimeException e) {
            permit.close();
            slot.close();
            throw e;
        }
        if (lease == null) {
            permit.close();
            slot.close();
            return null;
        }
        return new Places(slot, permit, lease);
    }

    /**
     * Repair wave (spec §11.5): small swarms seeded from failed candidates' branches, the
     * failure evidence baked into the (per-seed) shared prefix. Returns futures so the
     * engine can verify repairs as they complete and supersede the rest on first survivor.
     */
    public List<CompletableFuture<WorkerResult>> dispatchRepair(
            Task task, UUID runId, String seedBranch, String failureEvidence,
            int n, int indexOffset, GroupSignal signal, ExecutorService executor,
            String knowledgeBrief, String projectRules) {
        List<ModelProfile> workers = profiles.workers();
        String family = workers.isEmpty() ? "unconfigured" : workers.get(0).id();

        PromptBundle bundle = buildBundle(task, failureEvidence, knowledgeBrief, projectRules,
            protectedPaths, shellNote());
        // Once per wave, as in dispatch(): the whole repair wave runs at one size.
        AdaptiveConcurrency.Plan plan = concurrency.plan(family);
        LoggerFactory.getLogger(SwarmDispatcher.class)
            .info("Repair wave for task '{}' from {} n={} prefixHash={} prefixTokens={} ({}){}",
                task.title(), seedBranch, n, bundle.prefixHash(), bundle.estimatedTokens(),
                bundle.sizeSummary(), plan.throttled()
                    ? " — its server is running " + plan.concurrency() + " at a time with "
                        + plan.workingContextTokens() + " tokens of room each, because "
                        + plan.because()
                    : "");

        openingSpan(task, "repair", bundle);

        List<CompletableFuture<WorkerResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = indexOffset + i; // unique per seed — worker index names the branch
            final SamplingConfig cfg = new SamplingConfig(family, 0.4 + 0.4 * i, idx * 100L,
                "defensive-edges", "full-files");
            futures.add(CompletableFuture.supplyAsync(() -> {
                ModelProfile profile = profiles.find(cfg.modelProfileId());
                AgentRuntime.ModelEndpoint endpoint = endpointFor(profile, plan);
                WorkerLoop loop = new WorkerLoop(task, cfg, idx, scheduler, gitService,
                    agentRuntime, endpoint, runId, bundle,
                    seedBranch, signal, apiLookup, sandbox, protectedPaths);
                loop.withReferenceRoots(referenceRoots);
                loop.withAcceptanceSource(acceptanceSourceOf(runId));
                loop.withOtherTasks(otherTasksFor(runId, task, idx));
                loop.withExpert(expertFor(runId, idx, bundle, task), frameworkPackages);
                String label = "Repair worker " + idx + " of '" + task.title() + "'";
                // Repair workers count against the same ceiling as first-wave ones: they are the
                // same kind of load, and a repair wave arriving while other tasks are still going
                // is exactly the moment the machine is fullest. And against the same per-server
                // count: the engine sizes a repair wave itself, so its workers are not cut down,
                // but they queue for the server's places and get the room those places carry.
                // A repair is work its task depends on - the task has nothing that passed - so
                // like a first candidate it waits its turn, and a spare candidate of some other
                // task gives its place up when it has to wait.
                WorkerResult result;
                Places places;
                try {
                    places = waitingItsTurn(cfg.modelProfileId(), label, reservedTokens(endpoint));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(label + " was interrupted waiting for a place", e);
                }
                try {
                    loop.withLease(places.lease);
                    result = loop.run();
                } finally {
                    places.close();
                }
                concurrency.completed(cfg.modelProfileId(), label,
                    result.candidate().killReason(), result.contextDeath(), plan.concurrency());
                return result;
            }, executor));
        }
        return futures;
    }

    /**
     * Builds one worker's own help desk and wires it into the run: the same {@link RunAnswerCache}
     * as every other worker of {@code runId} (so a question the expert already answered for
     * another worker of this run is served free to this one instead of escalating twice for the
     * same topic), this worker's own index (named in a LATER worker's cache-hit reason), and this
     * task's own shared prompt prefix (so its words are dropped from a question's distinguishing
     * tokens — a word already in the brief cannot be evidence a free answer is about the question).
     *
     * <p>Anything the factory hands back that is not an {@link ExpertDesk} — the harness's plain
     * fakes, {@link ExpertHelp#UNAVAILABLE} — is returned exactly as built: none of this is a
     * contract of {@link ExpertHelp}, only extra wiring {@link ExpertDesk} itself understands.
     */
    private ExpertHelp expertFor(UUID runId, int workerIndex, PromptBundle bundle, Task task) {
        ExpertHelp expertHelp = expertFactory.get();
        if (expertHelp instanceof ExpertDesk desk) {
            // The run's one cache, which its planning roles ask through too (2026-10-02).
            desk.sharedAcrossRun(RunAnswerCache.forRun(runId), workerIndex);
            // And the run's one record of what was asked, what it cost and what became of it.
            desk.recordedIn(com.swarmcoder.runtime.ExpertAnswerLog.forRun(runId),
                com.swarmcoder.runtime.ExpertAnswerLog.Asker.worker(
                    task == null ? null : task.id(), task == null ? null : task.title(),
                    workerIndex));
            desk.excludingBriefWords(bundle == null ? null : bundle.sharedText());
        }
        return expertHelp;
    }

    /**
     * How many workers this wave gets: what the policy asks for, cut down to what the server is
     * currently allowed to run at once when that is fewer — and said out loud when it is.
     *
     * <p>Cut rather than queued, unlike the machine ceiling in {@link WorkerSlots}. That ceiling
     * exists because the machine is busy, and a worker that waits for it produces a candidate a
     * little later. This one exists because workers at the larger size were dying without producing
     * anything: four candidates that run out of room are worth less than two that finish, and the
     * two that run get the room the other two would have taken. The policy's own number is never
     * touched — the next wave asks again, and a recovered server gets it back.
     */
    static int waveSize(Task task, int requested, AdaptiveConcurrency.Plan planA,
                        AdaptiveConcurrency.Plan planB) {
        int allowed = requested;
        AdaptiveConcurrency.Plan binding = null;
        for (AdaptiveConcurrency.Plan plan : List.of(planA, planB)) {
            if (plan.throttled() && plan.concurrency() < allowed) {
                allowed = plan.concurrency();
                binding = plan;
            }
        }
        if (binding != null) {
            LoggerFactory.getLogger(SwarmDispatcher.class)
                .info("Task '{}' gets {} workers instead of {}: the model server for '{}' is "
                    + "running {} at a time instead of {}, each with {} tokens of room instead of "
                    + "{}, because {}.", task.title(), allowed, requested, binding.profileId(),
                    binding.concurrency(), binding.startConcurrency(),
                    binding.workingContextTokens(), binding.startWorkingContextTokens(),
                    binding.because());
        } else if (planA.throttled() || planB.throttled()) {
            AdaptiveConcurrency.Plan plan = planA.throttled() ? planA : planB;
            LoggerFactory.getLogger(SwarmDispatcher.class)
                .info("Task '{}' keeps its {} workers, and each gets {} tokens of room instead of "
                    + "{}: the model server for '{}' is running {} at a time instead of {}, because "
                    + "{}.", task.title(), requested, plan.workingContextTokens(),
                    plan.startWorkingContextTokens(), plan.profileId(), plan.concurrency(),
                    plan.startConcurrency(), plan.because());
        }
        return Math.max(1, allowed);
    }

    /**
     * The endpoint a worker of this wave talks to: the profile's own, with the room the plan gives
     * it. Identical to the profile's endpoint whenever the plan changes nothing, so an unmanaged
     * profile hands out exactly the object it always did.
     */
    static AgentRuntime.ModelEndpoint endpointFor(ModelProfile profile, AdaptiveConcurrency.Plan plan) {
        if (profile == null) {
            return null;
        }
        AgentRuntime.ModelEndpoint endpoint = profile.endpoint();
        if (endpoint == null || !plan.managed() || plan.workingContextTokens() <= 0
                || endpoint.quirks().workingContextTokens() == plan.workingContextTokens()) {
            return endpoint;
        }
        return new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), endpoint.apiKey(),
            endpoint.modelId(), endpoint.contextLength(),
            endpoint.quirks().withWorkingContextTokens(plan.workingContextTokens()));
    }

    private static String describeDiversity(SwarmPolicy policy, List<ModelProfile> workers) {
        int personaCount = policy.personaIds() != null && !policy.personaIds().isEmpty()
            ? policy.personaIds().size() : WorkerPersonas.DEFAULT_ROTATION.size();
        return describeDiversity(policy.splitAcrossFamilies(),
            workers.stream().map(ModelProfile::id).toList(),
            policy.tempMin(), policy.tempMax(), personaCount);
    }

    /**
     * What diversity a swarm group will actually have, in words, for the run log, the startup
     * announcement and the settings screen — the same sentence everywhere so they cannot disagree.
     *
     * <p>The design assumed two model families side by side on the box, so that ten workers on the
     * same task would disagree in useful ways. When only one model is configured, the
     * {@code splitAcrossFamilies} switch has nothing to split across: it does nothing, and it used
     * to do nothing SILENTLY while the config still read as though model diversity was on. This is
     * the sentence that stops that.
     */
    public static String describeDiversity(boolean splitAcrossFamilies, List<String> familyIds,
                                           double tempMin, double tempMax, int personaCount) {
        StringBuilder sb = new StringBuilder();
        if (familyIds.isEmpty()) {
            sb.append("NO worker model is configured — workers cannot run at all");
        } else if (familyIds.size() == 1) {
            sb.append("one worker model (").append(familyIds.get(0))
              .append("), so every worker runs the SAME model");
            if (splitAcrossFamilies) {
                sb.append("; the 'split across families' setting has nothing to split across and "
                    + "is doing nothing");
            }
        } else if (splitAcrossFamilies) {
            sb.append("workers alternate between ").append(familyIds.get(0))
              .append(" and ").append(familyIds.get(1));
        } else {
            sb.append(familyIds.size()).append(" worker models configured, but 'split across "
                + "families' is off, so every worker runs ").append(familyIds.get(0));
        }
        sb.append(". Temperature spread ").append(String.format(java.util.Locale.ROOT, "%.2f", tempMin))
          .append("-").append(String.format(java.util.Locale.ROOT, "%.2f", tempMax))
          .append(", ").append(personaCount).append(" persona(s) rotated across the workers");
        // Named because it is recorded on every candidate and looks like a lever it is not.
        sb.append(". The per-worker seed is recorded but never sent to the model");
        sb.append('.');
        return sb.toString();
    }

    /** The span a run report reads the size of a task's worker opening from. */
    public static final String WORKER_OPENING_SPAN = "worker opening|";

    /**
     * Records what the workers of one dispatch are opened with, for the run report (section 65):
     * {@code worker opening|<task>|first or repair|<estimated tokens>|<tokens per segment>}. The
     * tool definitions are sent beside the opening and are not in this figure.
     */
    private static void openingSpan(Task task, String dispatch, PromptBundle bundle) {
        com.swarmcoder.inference.RunMeter.span(WORKER_OPENING_SPAN + task.id() + "|" + dispatch
            + "|" + bundle.estimatedTokens() + "|" + bundle.sizeSummary(),
            System.currentTimeMillis());
        // How much of the opening is the architect's findings (section 73):
        // worker handover|<task>|first or repair|<findings>|<characters>.
        com.swarmcoder.inference.RunMeter.span(WORKER_HANDOVER_SPAN + task.id() + "|" + dispatch
            + "|" + task.architectFindings().size() + "|"
            + com.swarmcoder.domain.DesignFinding.sizeOf(task.architectFindings()),
            System.currentTimeMillis());
    }

    /** The span a run report reads the architect's findings given to a task's workers from. */
    public static final String WORKER_HANDOVER_SPAN = "worker handover|";

    /**
     * Deterministic shared segments for the task's whole group.
     *
     * @param projectRules the project's standing rules, rendered by {@code ConstraintBrief} from
     *                     its ACTIVE guidelines. Null or blank when the project has none, and the
     *                     shared prefix is then byte-for-byte what it was before rules existed, so
     *                     no such project's prefill cache goes cold.
     */
    static PromptBundle buildBundle(Task task, String failureEvidence, String knowledgeBrief,
                                    String projectRules, List<String> protectedPaths) {
        return buildBundle(task, failureEvidence, knowledgeBrief, projectRules, protectedPaths,
            hostShellNote());
    }

    /**
     * @param shellNote which shell the workers' exec tool runs and what it can see; see
     *                  {@link #shellNote()}
     */
    static PromptBundle buildBundle(Task task, String failureEvidence, String knowledgeBrief,
                                    String projectRules, List<String> protectedPaths,
                                    String shellNote) {
        StringBuilder instructions = new StringBuilder();
        instructions.append("Task: ").append(task.title()).append('\n')
            .append(task.instructions() == null ? "" : task.instructions()).append('\n');
        // What the architect established for this task, word for word (owner's decision,
        // 2026-10-08; section 73). The task's instructions say WHAT it delivers; how this
        // project and its framework do it comes from the role that looked it up, not from the
        // planner's prose. Nothing for a task the architect kept nothing about - it starts
        // from the librarian's brief, as before.
        String established = com.swarmcoder.domain.DesignFinding.renderAll(
            task.architectFindings());
        if (!established.isEmpty()) {
            instructions.append('\n').append(established).append('\n');
        }
        if (task.writeSet() != null && !task.writeSet().isEmpty()) {
            // Sorted: write sets are Sets — iteration order must not perturb the prefix hash.
            // A reservation, not a wall (section 73): a file outside it that no other task of
            // the plan holds is the task's when it needs it. The sentence is a courtesy; the
            // decision is made at the write, from the plan.
            instructions.append("These paths are reserved for this task: ")
                .append(task.writeSet().stream().sorted().toList())
                .append(". When the task cannot be done without a file outside them, write "
                    + "it: a file no other task holds becomes this task's and is recorded; a "
                    + "file another task holds is refused, and the refusal names that task.\n");
        }
        if (task.acceptanceTestDir() != null && !task.acceptanceTestDir().isBlank()) {
            // Owner's decision 2026-10-05 (section 61): a worker may READ the test its task
            // must satisfy. Run 88 paid a repair round of 1,031,748 tokens for a rule that
            // existed only in the hidden test, and 11 shell reads were looking for it. The
            // prompt names the tool; it does not carry the test (CLAUDE.md section 1).
            instructions.append("Acceptance tests in ").append(task.acceptanceTestDir())
                .append(" are protected; never modify them. Read the test your task must "
                    + "satisfy with acceptance_test (its claimed test methods and the helpers "
                    + "they use) before you write code; verification places the committed "
                    + "test, so nothing you write there counts.\n");
            if (!task.journeyPaths().isEmpty()) {
                // Section 69: the journey was written before the screen, so the names in its
                // selectors bind the screen, not the other way round. One sentence; the journey
                // itself is behind the lookup.
                instructions.append("This task also claims a journey (acceptance_test shows "
                    + "it): the screen must expose exactly the roles, accessible names and "
                    + "texts its selectors use, and be reachable from the entry page by its "
                    + "steps.\n");
            }
        }
        if (protectedPaths != null && !protectedPaths.isEmpty()) {
            // COURTESY, NOT ENFORCEMENT. Naming the locked modules saves the model the turns it
            // would otherwise burn on writes that PathPolicy refuses anyway — and gives it a
            // reason it can act on instead of a mystery error. The containment itself lives in
            // WorkerToolbox (every write) and in FinalIntegrator (the winning diff); the prompt
            // is precisely the thing that must never be trusted to hold a boundary, so nothing
            // here may ever become the only place a path is protected.
            // Sorted for the same reason as the write set: the prefix hash must not depend on
            // the order the operator happened to type them in.
            instructions.append("These modules are LOCKED and cannot be modified by any task "
                    + "(writes to them are rejected mechanically): ")
                .append(protectedPaths.stream().sorted().toList()).append('\n');
        }
        if (failureEvidence != null && !failureEvidence.isBlank()) {
            // "was rejected", not "failed verification" — a rule-break repair (spec §21 addendum,
            // SwarmEngineImpl.ruleBreakEvidence) seeds from a candidate that DID pass verification
            // and was rejected by the judge instead; the evidence below says which, specifically.
            instructions.append("\nREPAIR CONTEXT — a previous attempt was rejected. ")
                .append("Your checkout already contains that attempt's changes. Diagnose and fix:\n")
                .append(failureEvidence).append('\n');
        }
        return PromptBundle.builder()
            .systemRole("You are a software engineering worker agent. Implement exactly the task "
                + "described below in the repository you have tools for.")
            // Run 88 (section 60): this first sentence said "inspect with read/exec; modify
            // with write_file (full file content - reliable) or apply_diff", and that is what
            // the workers did - 61 whole files read, 4 diffs - whatever the paragraphs further
            // down said about the tree and the member edits. The order here is the order of
            // CLAUDE.md section 1.
            .workflowRules("Work in small steps: learn what you need from the project's syntax "
                + "tree (shape_of, body_of, types_in, usages_of - they answer about your checkout "
                + "as it is now); change existing Java with replace_member, add_member or "
                + "remove_member, which need no read of the file; write a NEW file with write_file; verify with "
                + "problems_in when you have it and by running builds/tests with exec; then call "
                + "report_done with a short summary when the change is complete and verified. "
                + "apply_diff takes a unified diff; if it is rejected, do not retry the diff - "
                + "use replace_member or add_member. ALWAYS use repository-RELATIVE "
                + "paths (e.g. `pom.xml`, `src/main/java/App.java`) — never absolute paths; your "
                + "tools already operate at the repository root.\n"
                // Static text, identical for every worker of THIS host — determined once from
                // the harness JVM's own OS, never from anything a worker says or does, so the
                // prefix hash still does not depend on worker identity. Added after harness run
                // 41 (2026-09-26): a worker ran `pwd`, was told (correctly, by whatever tool on
                // this machine's PATH answered it) that its checkout was
                // "/c/Users/dev/.swarmcoder/wt/<id>", and reasonably concluded it was in a
                // POSIX shell — then spent the rest of its turns retrying
                // `cd /c/Users/dev/... && mvn ...`, which cmd.exe rejects outright, until it
                // was killed for making no progress. Two facts fix that: exec never needs a cd at
                // all, and a path spelled the POSIX way is not a hint about which shell to write
                // for.
                + "Every exec command already runs with its working directory set to the root of "
                + "your checkout, every single time — you never need to `cd` there, or anywhere "
                + "else, before running a build or test command.\n"
                + shellNote
                // Static, identical for every worker — the prefix hash is unaffected. It exists
                // because four workers of run ede2068b (2026-09-03) each spent 15-22 turns
                // discovering that the project's rules required a library its pom did not declare,
                // could not add it, and one concluded "the poms are locked, so the server must use
                // an in-memory root". The build file of a module you may write is now yours.
                + "You MAY declare a dependency in a module's build file (its pom.xml or "
                + "build.gradle) when your task needs one — but ONLY an artifact your knowledge "
                + "brief lists as available: the build runs offline, and anything else cannot be "
                + "resolved and fails.\n"
                // SAID ONCE (section 65). Until 2026-10-07 the six paragraphs that stood here
                // repeated, tool by tool, what each tool's own description says and is sent beside
                // this text on every call: what shape_of, body_of, types_in, usages_of, build_of
                // and resources_of return, that replace_member, add_member and remove_member need
                // no read of the file, that documents are read by section, what ask_expert,
                // request_skeleton and find_example give back, and what dispute_rule wants as
                // evidence. Run 89: 1,390 tokens of workflow rules on each of 27 calls. What is
                // left here is what no single tool can say: the order to ask in, and what is
                // refused. Each sentence still has its history:
                // - ask, do not disassemble: ten workers once spent a whole run inside `jar xf`
                //   and `javap` on a framework whose documentation was in a folder they had
                //   (section 32); handed working code, a model still ran 66 shell commands to be
                //   sure of it and wrote nothing in 92 turns;
                // - find_example: the librarian's by-use example the planning roles already had;
                // - dispute_rule: harness runs 53 and 55 (2026-10-01) parked because every worker
                //   met a rule the library could not honour and had no way to say so;
                // - the tree before the shell (owner's rule, 2026-10-04; run 82 read 36 files
                //   whole, most of them to rewrite them).
                // Static text, identical for every worker - the prefix hash is unaffected.
                + "You will meet APIs that are not in your training data. That is expected and it "
                + "is not your failure: call lookup_api FIRST (your knowledge brief lists the "
                + "documents by name), then ask_expert with the exact question, and "
                + "request_skeleton when you do not know how to begin a file. Asking is the fast "
                + "path; do not guess. Do NOT unpack or decompile jars (`jar xf`, `javap`, a "
                + "decompiler) to work an API out — those commands are REFUSED. Only when "
                + "lookup_api has told you there is no documentation, inspect compiled classes "
                + "for a few turns at most before writing your best attempt and letting the "
                + "build correct you.\n"
                + "When you know what to build and want to see how this project already does it, "
                + "call find_example.\n"
                + "If a project rule cannot be met here, or is plainly wrong for this case, call "
                + "dispute_rule and do what the evidence supports. A rule you could follow, you "
                + "follow.\n"
                + "Do not use find, grep, ls or cat through exec to learn where things are or "
                + "what a type has, and read a whole file only when you need a body no tree "
                + "query returns.\n"
                // Static text, identical for every worker in every group — the prefix hash is
                // unaffected. The nudges at eight and sixteen reads without a write were not
                // enough on their own: a worker that decided to ignore them simply kept reading
                // until the twenty-four call backstop killed it with nothing written. This says
                // in advance what will actually happen, so it is not a surprise mid-run.
                + "After sixteen reads without a write, reading pauses until you write: read, "
                + "exec and lookup_api stop returning real results (except a build or test "
                + "command, which still runs) and instead tell you to write. Write your best "
                + "attempt as soon as you are told this (replace_member or add_member for "
                + "existing Java, write_file for a new file) — a wrong "
                + "file the compiler can correct is worth far more than another page read, and "
                + "reading only resumes after your first write.")
            .taskInstructions(instructions.toString())
            .knowledgeBrief(knowledgeBrief)
            // The project's rules, above the task: a worker that reads its task first has already
            // decided which frameworks it is reaching for. One segment, because there is one place
            // rules live — the architect and the test author are handed this same text.
            .projectConstraints(projectRules)
            .build();
    }
}
