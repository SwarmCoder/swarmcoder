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

import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.git.BasePin;
import com.swarmcoder.git.GitService;
import com.swarmcoder.git.WorktreeSweeper;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.AdaptiveConcurrency;
import com.swarmcoder.inference.ServerCapabilities;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.SwarmDispatcher;
import com.swarmcoder.swarm.WorkerSlots;
import com.swarmcoder.swarm.WorkerPersonas;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.workflow.RunResumer;
import com.swarmcoder.workflow.StoryDelivery;
import com.swarmcoder.workflow.WorkflowEngine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.app.config.McpServerConfig;
import com.swarmcoder.app.config.ProjectConfig;
import com.swarmcoder.app.config.ProjectConfigLoader;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.app.config.ModelQuirksConfig;
import com.swarmcoder.app.config.SandboxConfig;
import com.swarmcoder.app.config.SwarmEngineConfig;
import com.swarmcoder.app.config.SparkInstance;
import com.swarmcoder.app.config.McpApiConfig;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.server.mcp.SwarmMcpServer;
import com.swarmcoder.console.UnattendedPilot;
import com.zeroz4j.server.Zeroz4jServer;
import com.zeroz4j.server.WasmRmiServerEngine;
import jakarta.enterprise.inject.spi.CDI;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.knowledge.ContextLedger;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.knowledge.HistoryRag;
import com.swarmcoder.knowledge.McpServers;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.BlobStore;
import com.swarmcoder.verify.BlobSink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.eclipse.serializer.reference.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyGraph {
    private static final Logger log = LoggerFactory.getLogger(DependencyGraph.class);

    /** Mutable so the Console's typed settings forms can apply saved config (restart-scoped). */
    public volatile SwarmConfig config;
    /** The MCP server, when the operator turned it on; null otherwise. */
    private volatile SwarmMcpServer mcpServer;
    public final ArtifactStore artifactStore;
    public final VllmClient vllmClient;
    public final InferenceScheduler inferenceScheduler;
    public final DockerSandboxManager sandboxManager;
    public final TraceHub traceHub;
    public final AgentRuntime agentRuntime;
    public final CloudGate cloudGate;
    public final SwarmEngine swarmEngine;
    public final GitService gitService;
    public final Context7Client context7Client;
    public final DocsIndex docsIndex;
    public final Librarian librarian;
    public final ProjectRules projectRules;
    public final HistoryRag historyRag;
    public final ContextLedger contextLedger;
    public final WorkflowEngine workflowEngine;
    /** The project {@code config.repoPath} names — where runs with no project of their own belong. */
    private final ProjectContext defaultContext;
    // Multi-project registry: shared services + per-project contexts (built lazily), and the
    // project the next intake is routed to. The public fields above point at the DEFAULT project
    // for backward compatibility; multi-project-aware code uses currentProject()/switchProject().
    private final SharedServices shared;
    private final int maxGuidelineChars;
    private final boolean sandboxEnabled;
    private final Map<UUID, ProjectContext> projectContexts = new ConcurrentHashMap<>();
    private volatile ProjectContext current;
    /** Applied to every project's engine, including ones built later — see {@link #setEventLogger}. */
    private volatile java.util.function.Consumer<String> eventLogger;

    public DependencyGraph() throws IOException {
        // 1. Config
        this.config = ConfigLoader.loadDefaultConfig();

        // 2. Store
        Path storePath = Paths.get(System.getProperty("user.home"), ".swarmcoder", "store");
        this.artifactStore = new ArtifactStore(storePath);

        // 2b. Sweep worker worktrees abandoned by runs that are no longer alive. A run tidies up
        //     after itself; a run that is KILLED cannot, and nothing had ever swept up after one —
        //     63 checkouts from July sessions were still sitting there in August. Runs still live
        //     or resumable are excluded by the check below, and anything holding uncommitted work
        //     is reported and left where it is.
        sweepAbandonedWorktrees();

        // 2c. Rules of projects that no longer exist. A project's rules are deleted with it now
        //     (2026-09-02); before that they were files, indexed here, and every deletion left the
        //     index entries behind under a dead project id. Nothing can reach them.
        int orphanedRules = this.artifactStore.removeRulesOfDeletedProjects();
        if (orphanedRules > 0) {
            log.info("Removed {} rule(s) that belonged to projects deleted earlier", orphanedRules);
        }

        // 3. Inference
        boolean useFormat = true;
        AgentModelConfig archConfig = this.config.roles() != null ? this.config.roles().architect() : null;
        String baseUrl = archConfig != null && archConfig.baseUrl() != null && !archConfig.baseUrl().isEmpty() ? archConfig.baseUrl() : "http://localhost:8000";
        String apiKey = archConfig != null ? archConfig.apiKey() : "";
        String modelName = archConfig != null && archConfig.modelName() != null && !archConfig.modelName().isEmpty() ? archConfig.modelName() : "vllm-model";

        this.vllmClient = new VllmClient(baseUrl, apiKey, modelName,
            archConfig != null ? archConfig.resolvedQuirks() : ModelQuirks.DEFAULTS);
        // Admission control (spec §7.3): one pool per configured Spark instance, sized from
        // config — maxNumSeqs slots, KV ceiling = maxNumSeqs full-context sequences at the
        // measured bytes/token estimate. Unregistered profile ids share the default pool.
        this.inferenceScheduler = new InferenceScheduler(10, 1024L * 1024 * 1024, 1024);
        // Ask each model server what it can actually do, once per endpoint, before any figure is
        // guessed. Two metadata GETs, no inference, a three-second timeout, and a server that is
        // not running simply yields nothing — SwarmCoder must start with every box switched off.
        ServerCapabilities.Cache serverCapabilities = new ServerCapabilities.Cache();
        if (this.config.spark() != null && this.config.spark().instances() != null) {
            for (SparkInstance instance : this.config.spark().instances()) {
                if (instance == null || instance.id() == null || instance.id().isBlank()) {
                    continue;
                }
                // Config first, then the server, then the old constants. The literals below are
                // what this block did before anything was discovered, and they still stand for a
                // server that says nothing.
                ServerCapabilities caps = serverCapabilities.forEndpoint(
                    instance.baseUrl(), instance.servedModelName());
                int maxNumSeqs = instance.maxNumSeqs() > 0 ? instance.maxNumSeqs()
                    : caps.maxConcurrentSequences() > 0 ? caps.maxConcurrentSequences() : 16;
                int kvPerToken = instance.kvBytesPerTokenEstimate() > 0
                    ? instance.kvBytesPerTokenEstimate() : 1024;
                int contextCeiling = instance.contextCeiling() > 0 ? instance.contextCeiling()
                    : caps.servedContextTokens() > 0 ? caps.servedContextTokens() : 65536;
                this.inferenceScheduler.registerPool(instance.id(), maxNumSeqs,
                    (long) maxNumSeqs * contextCeiling * kvPerToken, kvPerToken);
                log.info("Model server pool '{}' at {}: {} Admitting {} request(s) at once, "
                    + "reserving {} tokens for each.", instance.id(), instance.baseUrl(),
                    caps.discoveredAnything()
                        ? "the server reports " + caps.servedContextTokens()
                            + " tokens per request and " + caps.maxConcurrentSequences()
                            + " request(s) at once."
                        : "the server said nothing about itself, so the written-down figures stand.",
                    maxNumSeqs, contextCeiling);
            }
        }

        // 4. Sandbox (spec §8). The manager is always constructed (it connects lazily); limits,
        //    network policy and the required flag come from the config's sandbox: block,
        //    image/dockerHost/m2 from system properties. Sandboxed verification is only ENABLED
        //    below when -Dswarmcoder.sandbox.enabled=true. Defaults when the block is absent are
        //    the SAFE ones: no network, sandbox required.
        SandboxConfig sbx = config.sandbox() != null
            ? config.sandbox() : new SandboxConfig(2, 4, 2);
        this.sandboxManager = new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"),
            Math.max(1, sbx.cpus()), Math.max(1, sbx.memGb()),
            sbx.pidsLimitOrDefault(), sbx.networkPolicy(), sbx.allowedHosts(),
            sbx.requiredOrDefault(),
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            System.getProperty("swarmcoder.sandbox.m2",
                Paths.get(System.getProperty("user.home"), ".m2").toString()));

        // 5a. Observability spine: every agent session's every step flows through the
        //     TraceHub — live to listeners (observer UI), complete records to EclipseStore,
        //     and into the Context Ledger's searchable history (spec §12.3).
        BlobStore traceBlobs = new BlobStore(
            Paths.get(System.getProperty("user.home"), ".swarmcoder", "blobs"));
        this.historyRag = new HistoryRag(
            Paths.get(System.getProperty("user.home"), ".swarmcoder", "rag", "history"));
        this.traceHub = traceHubOver(artifactStore, traceBlobs, historyRag::index);

        // 5b. Agent runtime (Koog, confined behind AgentRuntime — rule R4) and the
        //     ModelProfile registry — the single source of model identity (spec §6.2).
        this.agentRuntime = new KoogAgentRuntime(this.traceHub);
        List<ModelProfile> profiles = new ArrayList<>();
        Set<String> profileIds = new HashSet<>();
        // Kept beside the profiles purely so startup can print "the server says X, we had written
        // Y, here is what is in force" for each model — the operator's only window into this.
        Map<String, ServerCapabilities> discovered = new java.util.LinkedHashMap<>();
        Map<String, ModelQuirks> declared = new java.util.LinkedHashMap<>();
        // One controller for the process, installed where the console can read it: a server's
        // pool is shared by every project, so the count that shares it has to be too.
        AdaptiveConcurrency concurrency = new AdaptiveConcurrency();
        AdaptiveConcurrency.install(concurrency);
        SwarmEngineConfig swarmForCeiling = this.config.swarm();
        int workerCeiling = swarmForCeiling == null
            ? SwarmEngineConfig.DEFAULT_MAX_CONCURRENT_WORKERS : swarmForCeiling.workerCeiling();
        boolean ceilingStated = swarmForCeiling != null && swarmForCeiling.maxConcurrentWorkers() != 0;
        if (this.config.roles() != null && this.config.roles().workerFamilies() != null) {
            for (AgentModelConfig worker : this.config.roles().workerFamilies()) {
                if (worker != null && worker.baseUrl() != null && !worker.baseUrl().isBlank()
                        && worker.modelName() != null && !worker.modelName().isBlank()) {
                    // A family IS its model id — a repeated workerFamilies entry (easy to do in
                    // the settings UI) is collapsed with a warning, never a startup crash.
                    if (!profileIds.add(worker.modelName())) {
                        log.warn("roles.workerFamilies lists model '{}' more than once — ignoring "
                            + "the duplicate entry", worker.modelName());
                        continue;
                    }
                    // Every model-specific behaviour rides on the profile from here: the
                    // generation cap, the reasoning switch and how to send it, the tool-history
                    // format, the HTTP version, both context ceilings and the scheduler figures.
                    // The served ceiling used to be the literal 65536 written here.
                    // What the box says about itself beats the shape's typed-in figures, and the
                    // operator's own config beats both. See ModelQuirksConfig.resolve.
                    ServerCapabilities caps = serverCapabilities.forEndpoint(
                        worker.baseUrl(), worker.modelName());
                    discovered.put(worker.modelName(), caps);
                    declared.put(worker.modelName(), worker.resolvedQuirks());
                    ModelQuirks quirks = worker.resolvedQuirks(caps);
                    profiles.add(new ModelProfile(
                        worker.modelName(),
                        new AgentRuntime.ModelEndpoint(worker.baseUrl(), worker.apiKey(),
                            worker.modelName(), quirks.servedContextTokens(), quirks),
                        ModelProfile.Kind.WORKER,
                        quirks, 0));
                    // Under the back-off controller from the figures actually in force. A room the
                    // operator wrote into the file is pinned: the controller will run fewer workers
                    // at once but never raise a number somebody typed on purpose.
                    boolean roomPinned = worker.quirks() != null
                        && worker.quirks().workingContextTokens() != null
                        && worker.quirks().workingContextTokens() > 0;
                    concurrency.register(worker.modelName(), worker.baseUrl(), quirks, caps,
                        roomPinned, workerCeiling, ceilingStated);
                }
            }
        }
        ModelProfileRegistry modelProfiles =
            new ModelProfileRegistry(profiles);

        // Admission control per worker model. The pool key is the profile id, which is what
        // WorkerLoop leases against — the spark.instances block above keys pools by ITS OWN ids,
        // so a model whose profile id did not happen to match an instance id was silently
        // admitted against the generic default pool. Both figures are per-model and both have to
        // be MEASURED: bytes of key/value cache per token does not follow from parameter count,
        // and how many sequences a server really serves at once is a property of that server.
        //
        // A WARNING ABOUT THE BYTE FIGURE, so nobody reads it as memory. The ceiling below is
        // maxConcurrentSequences * servedContextTokens * kvBytesPerToken, and WorkerLoop reserves
        // servedContextTokens * kvBytesPerToken for every lease. The bytes therefore cancel out
        // exactly: the ceiling admits precisely maxConcurrentSequences leases, no matter what
        // kvBytesPerToken is set to, and the semaphore alone decides. On the 2026-08 box the
        // product is 8 * 262144 * 1024 = about 2 terabytes, which is not any graphics card that
        // exists. It is an accounting currency, not a memory budget, and it is left as one on
        // purpose: only its INPUTS are corrected here. Changing it into a real memory figure would
        // silently change what the scheduler admits, and that is a separate, deliberate decision.
        for (ModelProfile profile : profiles) {
            this.inferenceScheduler.registerPool(profile.id(), profile.quirks());
            // And the same number for REQUESTS to that server, whoever sends them: a judge, an
            // expert or a test author configured on the workers' own server is counted with the
            // workers, so the server is never sent more than it serves at once. A role on an
            // endpoint of its own (a cloud model) is not registered and never waits.
            if (profile.endpoint() != null) {
                com.swarmcoder.inference.ServerPlaces.register(profile.endpoint().baseUrl(),
                    Math.max(1, profile.quirks().maxConcurrentSequences()));
            }
        }
        announceModelProfiles(profiles, this.config, discovered, declared);

        // Cloud budget gate (rule R6): breaching the cap parks a BUDGET_EXTENSION decision.
        // Limits per run, story and project, each for input, output or both (BudgetsConfig); the
        // decision names the project, story and run, and the answer "extend" is
        // cloudGate.extendForRun(decision.runId()).
        CloudGate.Limits cloudLimits = this.config.budgets() != null
            ? this.config.budgets().cloudLimits() : CloudGate.Limits.NONE;
        this.cloudGate = new CloudGate(cloudLimits, breach -> BudgetDecision.raise(artifactStore, breach));

        // 6. Shared knowledge/blob services — process-wide, shared by every project.
        BlobStore blobStore = new BlobStore(
            Paths.get(System.getProperty("user.home"), ".swarmcoder", "blobs"));
        BlobSink blobSink = content -> {
            try {
                return blobStore.storeBlob(content);
            } catch (IOException e) {
                return null;
            }
        };
        // MCP servers from config (default: a local Context7). The server named "context7"
        // also drives the Librarian's versioned-docs path; all are offered to the Researcher.
        List<McpServers.Server> mcpList = new ArrayList<>();
        String context7Url = SwarmConfig.DEFAULT_CONTEXT7_URL;
        // Whether the operator actually named a documentation server, as opposed to the built-in
        // address being used because the settings file says nothing. The two cases have different
        // fixes, and telling them apart at startup is the whole point (§32).
        boolean context7Configured = false;
        for (McpServerConfig server : this.config.mcpServers()) {
            if (server == null || !server.isEnabled() || server.url() == null || server.url().isBlank()) {
                continue;
            }
            mcpList.add(new McpServers.Server(
                server.name() == null ? server.url() : server.name(), server.url()));
            if ("context7".equalsIgnoreCase(server.name())) {
                context7Url = server.url();
                context7Configured = this.config.hasConfiguredMcpServers();
            }
        }
        // The credential comes from the ENVIRONMENT, never from the settings file. That file is
        // edited through the Console, read back onto a screen, logged and copied around; a key in
        // it reaches all four. `roles.*.apiKey` already lives there in plaintext — that is the
        // practice this deliberately does not extend. Read once, here, at startup: a process that
        // was already running when the variable was set will not see it.
        String context7Key = System.getenv("CONTEXT7_API_KEY");
        this.context7Client = new Context7Client(context7Url, context7Key, context7Configured);
        // Its own subfolder, matching HistoryRag's "rag/history" convention just above — sharing the
        // "rag" root itself with HistoryRag meant a fresh install's docs lookup opened a directory
        // that held nothing of its own but a "history" folder belonging to a different Lucene index
        // entirely, which is not what "no segments file found" was actually reporting.
        this.docsIndex = new DocsIndex(
            Paths.get(System.getProperty("user.home"), ".swarmcoder", "rag", "docs"));
        McpServers mcpServers = new McpServers(mcpList);

        // 7. Shared services bundle — everything a project's ProjectContext builds on.
        this.shared = new SharedServices(this.artifactStore, this.inferenceScheduler,
            this.agentRuntime, modelProfiles, this.cloudGate, blobSink, this.vllmClient,
            this.sandboxManager, this.historyRag, this.context7Client, this.docsIndex, mcpServers,
            concurrency);
        this.maxGuidelineChars = (this.config.guidelines() != null ? this.config.guidelines().maxPrefixTokens() : 3000) * 4;
        this.sandboxEnabled = resolveSandboxEnabled(this.config);
        announceSandboxPosture(this.sandboxEnabled, this.config, this.sandboxManager);

        // 8. Default project (multi-project): the configured repoPath + context folders become an
        //    explicit, persisted Project with a built ProjectContext; the Console adds/switches more.
        Project defaultProject = this.artifactStore.ensureProject(
            deriveProjectName(this.config.repoPath()), this.config.repoPath(), this.config.contextPaths());
        ProjectContext defaultContext = buildContext(defaultProject);
        this.projectContexts.put(defaultProject.id(), defaultContext);
        this.defaultContext = defaultContext;
        this.current = defaultContext;

        //    Then reopen wherever the operator last was. config.repoPath names the DEFAULT project,
        //    not the one they were working in, and starting somewhere other than where you left off
        //    means the first thing you do every session is fix it. A stale or deleted bookmark
        //    simply falls back to the default.
        UUID lastProjectId = this.artifactStore.lastProjectId();
        if (lastProjectId != null && !lastProjectId.equals(defaultProject.id())) {
            Project last = this.artifactStore.getProject(lastProjectId);
            if (last != null) {
                try {
                    ProjectContext lastContext = buildContext(last);
                    this.projectContexts.put(last.id(), lastContext);
                    this.current = lastContext;
                    log.info("Reopened last project '{}' ({})", last.name(), last.primaryPath());
                } catch (Exception e) {
                    // A project whose folder has since moved must not stop the app starting.
                    log.warn("Could not reopen last project {} ({}) — staying on the default: {}",
                        last.name(), lastProjectId, e.toString());
                }
            }
        }

        // Backward-compatible fields point at the default project.
        this.gitService = defaultContext.gitService();
        this.projectRules = defaultContext.projectRules();
        this.librarian = defaultContext.librarian();
        this.contextLedger = defaultContext.contextLedger();
        this.swarmEngine = defaultContext.swarmEngine();
        this.workflowEngine = defaultContext.workflowEngine();
    }

    /**
     * Sweeps worker worktrees left behind by runs that are no longer alive, and says what it did.
     *
     * <p>The judgement of "still wanted" is made here because it is the only place that can make
     * it: the sweeper knows what git owns, and the store knows which runs are still going or could
     * still be resumed. A worktree is kept when it belongs to a run that has not reached DELIVERED
     * or ABORTED, and also when nothing here can identify it at all — an unrecognised checkout is
     * somebody's, and "I do not know whose" is not a reason to delete it.
     */
    private void sweepAbandonedWorktrees() {
        Path worktreeRoot = Paths.get(System.getProperty("user.home"), ".swarmcoder", "wt");
        // Task ids belonging to runs that are still going, or parked and resumable, and the ids of
        // those runs — collected once, so the sweep is a handful of git calls and no store walking.
        Set<UUID> liveRunIds = new HashSet<>();
        Set<UUID> liveTaskIds = new HashSet<>();
        try {
            for (Run run : artifactStore.root().runs.values()) {
                if (run == null || run.state() == RunState.DELIVERED
                        || run.state() == RunState.ABORTED
                        || run.state() == RunState.ABANDONED) {
                    continue;
                }
                liveRunIds.add(run.id());
                TaskGraph graph = run.taskGraphId() == null ? null
                    : artifactStore.root().taskGraphs.get(run.taskGraphId());
                if (graph != null) {
                    graph.tasks().forEach(task -> liveTaskIds.add(task.id()));
                }
            }
        } catch (Exception e) {
            // Unable to tell which runs are live: sweep nothing rather than guess.
            log.warn("Skipping the worktree sweep — the run graph could not be read: {}", e.toString());
            return;
        }

        // On its own daemon thread. A first sweep over a large backlog is a hundred or so git
        // processes, and housekeeping must not be something the operator waits for to start work.
        // Nothing downstream depends on it, and the age guard means it cannot collide with a run
        // that starts while it is still going.
        Thread sweeper = new Thread(
            () -> report(WorktreeSweeper.sweep(worktreeRoot,
                stale -> isLive(stale, liveRunIds, liveTaskIds))),
            "worktree-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    /** Says what the sweep did — the whole point of sweeping rather than silently deleting. */
    private static void report(WorktreeSweeper.Result result) {
        if (result.total() == 0) {
            return;
        }
        log.info(result.summary());
        result.removed().forEach(line -> log.info("  removed {}", line));
        // Uncommitted work is the one thing a sweep must never quietly dispose of, so it is said
        // loudly and the checkout is left exactly where it is.
        result.withWork().forEach(line -> log.warn("  KEPT (uncommitted work) {}", line));
        result.failed().forEach(line -> log.warn("  could not sweep {}", line));
        result.kept().forEach(line -> log.debug("  kept {}", line));
    }

    /**
     * True when this worktree belongs to a run that is still live or resumable — or when it cannot
     * be identified, which is treated the same way.
     *
     * <p>Two shapes exist, and both are named by the code that creates them: an integration
     * worktree is {@code integration-<runId>} (FinalIntegrator) and a worker's is named by its
     * candidate id with the branch {@code swarm/<taskId>/<n>} (WorkerLoop). The branch is what git
     * reports, so the task — and through it the run — is reachable without the store having ever
     * recorded the candidate.
     */
    private static boolean isLive(WorktreeSweeper.Stale stale, Set<UUID> liveRunIds,
                                  Set<UUID> liveTaskIds) {
        String name = stale.name() == null ? "" : stale.name();
        if (name.startsWith("integration-")) {
            UUID runId = uuidOrNull(name.substring("integration-".length()));
            return runId == null || liveRunIds.contains(runId);
        }
        String branch = stale.branch();
        if (branch != null && branch.startsWith("swarm/")) {
            String[] parts = branch.split("/");
            UUID taskId = parts.length >= 2 ? uuidOrNull(parts[1]) : null;
            return taskId == null || liveTaskIds.contains(taskId);
        }
        return true; // unidentifiable: not ours to delete
    }

    private static UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Everything a just-deleted project's live workflow may still be doing, ended.
     *
     * <p>Two things, both reusing machinery that already exists rather than inventing new
     * cancellation: the cached {@link ProjectContext} is dropped, so {@link #workflowEngineFor} and
     * {@link #currentProject()} can no longer reach this project's engine — the same removal
     * {@link #invalidateProject} already does when a project's settings change, done here because
     * the project no longer exists at all. And its worktrees and branches are swept — the exact
     * cleanup a run leaves for itself when it finishes normally, and the same machinery
     * {@link #sweepAbandonedWorktrees} runs at start-up — except triggered immediately, with no age
     * guard, so a deleted project's checkouts do not sit under {@code ~/.swarmcoder/wt} for up to
     * the general sweep's 6 hours before anyone would have noticed them gone.
     *
     * <p><b>What this does NOT do</b> is stop a thread already inside {@link WorkflowEngine#advance}
     * for one of this project's runs — nothing in this codebase can interrupt one mid-stage. What it
     * removes is every place that thread's work could still land: its project record and its own run
     * records are gone by the time the operator sees "deleted" (the store side runs in
     * {@code ControlServiceImpl.deleteProject}), its engine is no longer reachable for anything NEW,
     * and its checkouts stop existing under the run's own feet. A worktree sweep is driven by git's
     * own bookkeeping, not the store, so it is correct regardless of which of the two happens first.
     */
    private void teardownDeletedProject(Project project) {
        if (project == null) {
            return;
        }
        this.projectContexts.remove(project.id());

        String repo = normalizedRepoPath(project.primaryPath());
        if (repo == null) {
            return; // no repository — nothing under ~/.swarmcoder/wt can be attributed to it
        }
        Path worktreeRoot = Paths.get(System.getProperty("user.home"), ".swarmcoder", "wt");
        // Off the calling thread: a Console delete call must not wait on a sweep, which is a git
        // process per worktree — the same reason the start-up sweep is backgrounded.
        Thread sweeper = new Thread(
            () -> report(WorktreeSweeper.sweep(worktreeRoot, stale -> !belongsToRepo(stale, repo),
                Duration.ZERO, WorktreeSweeper.DEFAULT_MAX_REMOVALS)),
            "worktree-sweeper-project-delete");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    /**
     * True when a worktree's owning repository is the deleted project's — the only worktrees a
     * deletion may remove. Anything else, including an orphan whose repository is gone entirely, is
     * left to the general start-up sweep: this one project's deletion is not licence to touch
     * checkouts nobody has connected to it.
     */
    private static boolean belongsToRepo(WorktreeSweeper.Stale stale, String normalizedRepo) {
        return stale.mainRepo() != null
            && normalizedRepo.equals(normalizedRepoPath(stale.mainRepo().toString()));
    }

    /**
     * Absolute, normalised, case-insensitive form of a repository path — the same normalisation
     * {@link ArtifactStore#ensureProject} applies before comparing two paths, needed again here
     * because Windows reports one repository several equivalent ways.
     */
    private static String normalizedRepoPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether worker commands run in a container: {@code sandbox.enabled} (default TRUE), with
     * {@code -Dswarmcoder.sandbox.enabled} overriding in EITHER direction when explicitly set.
     *
     * <p>The property used to be the only switch and defaulted to off, so the shipped behaviour was
     * model-authored shell commands running on the workstation with the operator's privileges.
     */
    /**
     * Says, at startup, exactly what each configured model has been told to do and which of those
     * settings nobody has measured.
     *
     * <p>The rule this exists for: a default that silently does the wrong thing for a new model is
     * worse than a setting the operator has to fill in. Every one of these values used to be a
     * constant or a JVM-wide system property tuned for ONE model, so a new model inherited a 27B
     * Qwen's conventions without a word being said. Anything unverified is logged at WARN, once
     * per model, every start, until somebody measures it and marks it so.
     */
    private static void announceModelProfiles(List<ModelProfile> profiles, SwarmConfig config,
                                              Map<String, ServerCapabilities> discovered,
                                              Map<String, ModelQuirks> declared) {
        if (profiles.isEmpty()) {
            log.warn("No model is set up for writing code, so nothing can be built yet. "
                + "Add one in the window: Setup, then \"Models & budgets\".");
        }
        for (ModelProfile profile : profiles) {
            ModelQuirks q = profile.quirks();
            log.info("Worker model '{}' at {} — {}: {}", profile.id(),
                profile.endpoint().baseUrl(), q.label(), q.describe());
            // One line per endpoint saying what the box reported against what was written down.
            // Every capacity figure used to be a constant nobody could see or check; this is the
            // sentence that makes the difference visible without opening the config.
            ServerCapabilities caps = discovered.get(profile.id());
            ModelQuirks before = declared.getOrDefault(profile.id(), q);
            if (caps != null) {
                log.info("Worker model '{}' asked its server: {}", profile.id(),
                    caps.describeAgainst(before, q));
            }
            if (caps != null && caps.discoveredAnything()
                    && before.workingContextTokens() != q.workingContextTokens()) {
                log.info("Worker model '{}': one session may now hold {} tokens instead of {} — "
                    + "worked out as its share of the memory the server reported ({} tokens shared "
                    + "between {} request(s)), not by dividing the longest allowed request.",
                    profile.id(), q.workingContextTokens(), before.workingContextTokens(),
                    caps.kvCachePoolTokens(), caps.maxConcurrentSequences());
            }
            // The relationship between the three sizes is checkable even though the working
            // budget itself is not yet enforced anywhere (see DEVELOPER_CORRECTIONS §14.4).
            // Getting it wrong is the failure that stalls a whole stage: a generation that can
            // legally run past what the server was started with.
            if (q.workingContextTokens() > q.servedContextTokens()) {
                log.warn("Worker model '{}': the working context ({}) is LARGER than the context "
                    + "the server was started with ({}). A session is allowed to grow past what "
                    + "the server can hold. Lower the working context, or correct the served "
                    + "figure to match how the server was actually launched.",
                    profile.id(), q.workingContextTokens(), q.servedContextTokens());
            }
            if (q.maxOutputTokens() > q.workingContextTokens()) {
                log.warn("Worker model '{}': one answer may be up to {} tokens, which is more than "
                    + "the whole working context ({}). One runaway answer can fill the session.",
                    profile.id(), q.maxOutputTokens(), q.workingContextTokens());
            }
            if (!q.verified()) {
                boolean fromServer = caps != null && caps.discoveredAnything();
                if (fromServer) {
                    // Three of the four figures now come from the server itself, so the old blanket
                    // warning would name numbers that ARE measured and hide the one that is not.
                    log.warn("Worker model '{}': the context sizes ({} served, {} per session) and "
                        + "the {} request(s) at once come from the server itself, but memory per "
                        + "token ({} bytes) is still a guess nobody has measured on this box. It is "
                        + "the figure the admission queue counts in. Measure it and set "
                        + "quirks.verified: true.",
                        profile.id(), q.servedContextTokens(), q.workingContextTokens(),
                        q.maxConcurrentSequences(), q.kvBytesPerToken());
                } else {
                    log.warn("Worker model '{}' is running on UNVERIFIED settings, and its server "
                        + "did not answer when asked about itself. Nobody has measured these "
                        + "against this model on this box: memory per token ({} bytes) and "
                        + "concurrent sequences ({}) decide how many workers are admitted at once, "
                        + "and the served ({}) and working ({}) context sizes must match how the "
                        + "server was actually started. Measure them and set quirks.verified: true.",
                        profile.id(), q.kvBytesPerToken(), q.maxConcurrentSequences(),
                        q.servedContextTokens(), q.workingContextTokens());
                }
            }
        }
        SwarmEngineConfig swarm = config.swarm();
        if (swarm != null) {
            int personaCount = swarm.personaIds() != null && !swarm.personaIds().isEmpty()
                ? swarm.personaIds().size() : WorkerPersonas.DEFAULT_ROTATION.size();
            String diversity = SwarmDispatcher.describeDiversity(swarm.splitAcrossFamilies(),
                profiles.stream().map(ModelProfile::id).toList(),
                swarm.tempMin() > 0 ? swarm.tempMin() : 0.2,
                swarm.tempMax() > 0 ? swarm.tempMax() : 0.2, personaCount);
            if (swarm.splitAcrossFamilies() && profiles.size() < 2) {
                log.warn("Swarm diversity: {}", diversity);
            } else {
                log.info("Swarm diversity: {}", diversity);
            }
            // The shape of the work, and the one number that bounds it. Said as a multiplication
            // because that is the only way the three settings can be read: a story or a project may
            // ask for more workers per task, and nothing but the ceiling stops the product growing.
            int perTask = swarm.workersPerTaskOrDefault();
            int groups = swarm.taskGroupsAtOnce();
            int stories = config.overnight() == null ? 1 : config.overnight().concurrencyOrDefault();
            int ceiling = swarm.workerCeiling();
            log.info("Swarm size: up to {} worker(s) on each piece of work, {} piece(s) of work at a "
                + "time in a build, up to {} build(s) at a time — so up to {} workers at once, "
                + "capped at {}. The first worker of every ready piece of work is started before "
                + "any second one; further workers only take places on the model server that "
                + "nothing else is waiting for.", perTask,
                groups == 0 ? "as many as the model server has places for" : groups, stories,
                groups == 0 ? "as many as there are places for" : perTask * groups * stories,
                ceiling == 0 ? "nothing" : ceiling);
            if (groups > 0) {
                log.info("The settings file says swarm.maxConcurrentTaskGroups: {}, so at most {} "
                    + "piece(s) of work are attempted at a time even when the model server has "
                    + "more places than that; the places left over go to further workers on the "
                    + "same piece(s) of work. Leave the line out to have every ready piece of "
                    + "work started.", groups, groups);
            }
            WorkerSlots.configure(ceiling);
        }
        log.info("Model shapes available when adding a model: {}",
            String.join(" | ", ModelShapes.descriptions()));
    }

    private static boolean resolveSandboxEnabled(SwarmConfig config) {
        String override = System.getProperty("swarmcoder.sandbox.enabled");
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override);
        }
        return config.sandbox() == null || config.sandbox().enabledOrDefault();
    }

    /**
     * Says plainly, at startup, how contained the workers actually are.
     *
     * <p>The unsandboxed case gets a loud block rather than a one-line warning because it is easy to
     * arrive at by accident — Docker not started, image not built — and the consequence is not
     * degraded functionality but model-authored commands running with the operator's privileges.
     * Whichever way it resolves, the message names the remedy rather than leaving it to be looked up.
     */
    private static void announceSandboxPosture(boolean enabled, SwarmConfig config,
                                               DockerSandboxManager sandbox) {
        if (config.sandbox() != null && config.sandbox().requiredSetToFalse()) {
            log.warn("sandbox.required: false in {} is ignored. It used to let the models' "
                + "commands run on this PC when Docker was not answering; nothing does that any "
                + "more. Remove the line.", ConfigLoader.configPath());
        }
        if (!enabled) {
            // The operator's explicit off-switch, and the only thing in the app that lets model
            // code out of a container. Named, so every later warning can say who allowed it.
            com.swarmcoder.domain.HostExecution.allow(
                "sandbox.enabled: false in " + ConfigLoader.configPath());
            log.warn("=================================================================");
            log.warn("THE SAFETY BOX IS SWITCHED OFF. CODE THE MODELS WRITE RUNS ON THIS PC.");
            log.warn("Their commands, the tests they write and the builds of their code all");
            log.warn("run directly on this machine, as you: your files on every drive, your");
            log.warn("saved passwords, your network. Nothing contains them.");
            log.warn("You switched this off yourself. Put these two lines back in");
            log.warn("{} to turn it on again:", ConfigLoader.configPath());
            log.warn("    sandbox:");
            log.warn("      enabled: true");
            log.warn("=================================================================");
            return;
        }
        if (sandbox.isAvailable()) {
            log.info("Safety box ready: the models' commands, their tests and the builds of their "
                + "code run inside a container that cannot reach your files or the network.");
            return;
        }
        // Enabled but unreachable. There is exactly one way on: fix Docker. Running without the
        // box is a separate, deliberate setting, and it is named so nobody arrives at it by
        // accident.
        //
        // The build line includes `mvn package` because the image copies a jar this project makes:
        // without it `docker build` fails on a missing file, which reads as a broken instruction.
        log.error("=================================================================");
        log.error("SwarmCoder runs the models' code inside a safety box, and the");
        log.error("program that makes one - Docker - is not answering.");
        log.error("Builds will STOP rather than run on this PC.");
        log.error("");
        log.error("Do this once, and builds work:");
        log.error("  1. Install Docker Desktop and start it.");
        log.error("  2. In the SwarmCoder folder, run these two commands:");
        log.error("       mvn -q -pl sc-sandbox-action-server -am package -DskipTests");
        log.error("       docker build -t swarmcoder-worker:latest sc-sandbox-action-server");
        log.error("");
        log.error("The only other way is to accept that the models' code runs straight on");
        log.error("this PC as you, by putting these two lines in {}:", ConfigLoader.configPath());
        log.error("    sandbox:");
        log.error("      enabled: false");
        log.error("=================================================================");
    }

    /** Why nobody is at the console right now, or null when a person is. Read live. */
    String unattendedReason() {
        if (com.swarmcoder.console.AutonomousMode.isRunning()) {
            return "an autonomous build-everything session is running";
        }
        SwarmConfig current = this.config;
        if (current != null && current.overnight() != null && current.overnight().enabled()) {
            return "the overnight setting is on";
        }
        return null;
    }

    /** Builds a project's context, resolving its per-role clients (global config + project.yaml). */
    private ProjectContext buildContext(Project project) {
        applyProjectContextPaths(project);
        ProjectContext context = new ProjectContext(project, shared, roleClientsFor(project),
            maxGuidelineChars, sandboxEnabled,
            config.guidelines() != null && config.guidelines().autoPromote(),
            config.swarm(), config.protectedPaths(), config.budgets());
        // Unattended modes warn and carry on instead of parking for a person who is not there
        // (owner decision, 2026-10-01). Asked at each decision, so it follows both live.
        if (context.swarmEngine() instanceof com.swarmcoder.swarm.SwarmEngineImpl impl) {
            impl.setUnattendedReason(this::unattendedReason);
        }
        // Every project's engine reports, not just the default one's. The logger used to be set on
        // a single engine at startup, so a second project's run made no entry anywhere.
        java.util.function.Consumer<String> logger = this.eventLogger;
        if (logger != null) {
            context.workflowEngine().setEventLogger(logger);
        }
        return context;
    }

    /**
     * Sends workflow events to {@code logger} for every project — the ones already built and the
     * ones built later.
     */
    public synchronized void setEventLogger(java.util.function.Consumer<String> logger) {
        this.eventLogger = logger;
        this.projectContexts.values().forEach(c -> c.workflowEngine().setEventLogger(logger));
    }

    /**
     * The engine a run of {@code projectId} must be driven by — its repository, its git service,
     * its locked modules. Null when the project is gone from the store.
     *
     * <p>The registry {@link RunResumer} routes through at startup. Resuming used to use the
     * DEFAULT project's engine for every unfinished run in the store, whoever it belonged to, so a
     * second project's run was continued against the first project's code with the first project's
     * protections.
     *
     * @param projectId null for a run persisted before multi-project existed — the default project
     *                  by definition, which is what {@code config.repoPath} names
     */
    public synchronized WorkflowEngine workflowEngineFor(UUID projectId) {
        if (projectId == null) {
            return this.defaultContext.workflowEngine();
        }
        ProjectContext known = this.projectContexts.get(projectId);
        if (known != null) {
            return known.workflowEngine();
        }
        Project project = this.artifactStore.getProject(projectId);
        if (project == null) {
            return null;
        }
        ProjectContext built = buildContext(project);
        this.projectContexts.put(projectId, built);
        return built.workflowEngine();
    }

    /**
     * Continues every run that was mid-flight when the last process died, each through its own
     * project's engine (rule R3), then frees the stories those runs left behind.
     *
     * <p>Order matters and is the same as it always was: stranded-story reconciliation runs AFTER
     * the resume, so a story whose run was genuinely revived is left alone and only the ones with
     * nothing driving them are freed. Reconciliation is a store-wide pass over stories and touches
     * no repository, so it is run once rather than per project.
     */
    public List<RunResumer.Resumed> resumeUnfinishedRuns() {
        List<RunResumer.Resumed> resumed =
            new RunResumer(this.artifactStore, this::workflowEngineFor).resumeAll();
        this.defaultContext.workflowEngine().reconcileStrandedStories();
        return resumed;
    }

    /**
     * Applies {@code contextPaths} from the project's own {@code .swarmcoder/project.yaml}.
     *
     * <p>The field was parsed and then never read by anything, so declaring reference folders in a
     * project's own config file did nothing at all — the only route that worked was the Console
     * form. Since the whole point is that a project declares which other codebases it is built
     * against, the file is the natural place to say it, and it belongs in the repo next to the code.
     */
    private void applyProjectContextPaths(Project project) {
        try {
            ProjectConfig pc = ProjectConfigLoader.load(project.primaryPath());
            List<String> declared = pc == null ? null : pc.contextPaths();
            if (declared != null && !declared.isEmpty() && !declared.equals(project.contextPaths())) {
                project.setContextPaths(List.copyOf(declared));
                this.artifactStore.saveProject(project);
                log.info("Project {} context folders from project.yaml: {}", project.name(), declared);
            }
        } catch (Exception e) {
            log.warn("Could not read context folders from project.yaml for {}: {}",
                project.name(), e.getMessage());
        }
    }

    /** Per-role clients for a project: project.yaml overrides layered over the global roles config. */
    private RoleClients roleClientsFor(Project project) {
        RolesConfig global = this.config.roles();
        ProjectConfig pc =
            ProjectConfigLoader.load(project.primaryPath());
        RolesConfig over = pc != null ? pc.roles() : null;
        AgentModelConfig chatRole =
            resolveRole(over, global, RolesConfig::chat);
        if (chatRole == null || chatRole.baseUrl() == null || chatRole.baseUrl().isBlank()) {
            chatRole = resolveRole(over, global, RolesConfig::utility);
        }
        // The two wizard roles fall back to CHAT (already resolved above, so they inherit its own
        // fallback to utility). That chain is what keeps every existing config working unchanged:
        // before these slots existed both wizards ran on the chat model, and an unset slot still
        // does exactly that. Setting one is how you point the top of the requirement chain at a
        // stronger model without moving the chat dock with it.
        AgentModelConfig utilityRole = resolveRole(over, global, RolesConfig::utility);
        AgentModelConfig architectRole = resolveRole(over, global, RolesConfig::architect);
        return new RoleClients(
            clientFor(architectRole),
            clientFor(resolveRole(over, global, RolesConfig::designReviewer)),
            clientFor(resolveRole(over, global, RolesConfig::testAuthor)),
            clientFor(resolveRole(over, global, RolesConfig::judge)),
            clientFor(utilityRole),
            clientFor(chatRole),
            clientFor(orElse(resolveRole(over, global, RolesConfig::requirementsAnalyst), chatRole)),
            clientFor(orElse(resolveRole(over, global, RolesConfig::storyPlanner), chatRole)),
            roleConfigured(utilityRole),
            roleConfigured(architectRole),
            // The task planner's own model when one is named, else the architect's - the
            // model it has always run on, so an unchanged configuration is unchanged.
            clientFor(orElse(resolveRole(over, global, RolesConfig::taskPlanner), architectRole)));
    }

    /**
     * Whether a role names its own endpoint, as opposed to {@link #clientFor} silently falling
     * back to the process-wide default client. The help desk's expert tier needs this at startup:
     * {@link #clientFor} makes {@code roles.utility()}/{@code roles.architect()} always non-null,
     * so a null check cannot tell "configured" from "fell back to the default endpoint" — and
     * escalating every unanswered question to an unconfigured default is not what "wired" means
     * (ExpertDesk javadoc).
     */
    private static boolean roleConfigured(AgentModelConfig role) {
        return role != null && role.baseUrl() != null && !role.baseUrl().isBlank()
            && role.modelName() != null && !role.modelName().isBlank();
    }

    /** {@code role} when it names an endpoint, else the fallback — the roles.x → chat → utility chain. */
    private static AgentModelConfig orElse(AgentModelConfig role, AgentModelConfig fallback) {
        return role == null || role.baseUrl() == null || role.baseUrl().isBlank() ? fallback : role;
    }

    /** Project override wins when it names an endpoint; otherwise the global role config. */
    private static AgentModelConfig resolveRole(
            RolesConfig over, RolesConfig global,
            Function<RolesConfig, AgentModelConfig> selector) {
        AgentModelConfig o = over != null ? selector.apply(over) : null;
        if (o != null && o.baseUrl() != null && !o.baseUrl().isBlank()) {
            return o;
        }
        return global != null ? selector.apply(global) : null;
    }

    private static String deriveProjectName(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            return "default";
        }
        Path name = Paths.get(repoPath).getFileName();
        return name != null ? name.toString() : "default";
    }

    /** Builds a client for a role config, falling back to the default endpoint when unconfigured. */
    private VllmClient clientFor(AgentModelConfig role) {
        if (role != null && role.baseUrl() != null && !role.baseUrl().isBlank()
                && role.modelName() != null && !role.modelName().isBlank()) {
            return new VllmClient(role.baseUrl(), role.apiKey(), role.modelName(), role.resolvedQuirks());
        }
        return this.vllmClient;
    }

    /**
     * The vision model used by BRD document intake, or null when {@code roles.vision} is unset.
     *
     * <p>Unlike every other role this does NOT fall back to the default endpoint. The text models
     * are not vision-capable, so a fallback would not fail — it would return a fluent description of
     * an image the model never received, and those hallucinated "requirements" would land in the BRD
     * looking exactly like real ones. Refusing the upload with a message naming the missing config
     * is the only safe behaviour.
     */
    private ConsoleContext.VisionModel visionModel() {
        RolesConfig roles = this.config.roles();
        AgentModelConfig vision = roles == null ? null : roles.vision();
        if (vision == null || vision.baseUrl() == null || vision.baseUrl().isBlank()
                || vision.modelName() == null || vision.modelName().isBlank()) {
            log.info("No model that can read pictures is set up, so uploading a screenshot or a "
                + "photo of a whiteboard will be refused. Text documents work either way.");
            return null;
        }
        // The vision role's quirks come from its own config like any other role. It used to be
        // given a hardcoded "no thinking flag" here; the "vision" shape says the same thing as
        // configuration, and a vision model that DOES take a reasoning switch can now be told so.
        VllmClient client = new VllmClient(vision.baseUrl(), vision.apiKey(), vision.modelName(),
            vision.shape() == null || vision.shape().isBlank()
                ? ModelQuirksConfig.resolve("vision", vision.quirks(), vision.thinking())
                : vision.resolvedQuirks());
        log.info("Vision model for BRD intake: {} at {}", vision.modelName(), vision.baseUrl());
        return new ConsoleContext.VisionModel() {
            @Override
            public String describe(String prompt, String imageDataUri) throws Exception {
                return client.as("analyst").describeImage(prompt, imageDataUri, 0.1);
            }

            @Override
            public String modelName() {
                return client.modelName();
            }
        };
    }

    // --- Multi-project registry -----------------------------------------------------------------

    /** All known (non-archived) projects. */
    public List<Project> listProjects() {
        return this.artifactStore.listProjects();
    }

    /** The project the next intake is routed to (and whose engines {@code currentProject()} exposes). */
    public ProjectContext currentProject() {
        return this.current;
    }

    /**
     * Registers a project (idempotent by primary path), building its {@link ProjectContext} on
     * first use. Does not change the current project — call {@link #switchProject} for that.
     */
    public synchronized ProjectContext addProject(String name, String primaryPath, List<String> contextPaths) {
        Project project = this.artifactStore.ensureProject(name, primaryPath, contextPaths);
        return this.projectContexts.computeIfAbsent(project.id(), id -> buildContext(project));
    }

    /**
     * Drops a project's built context so the next use (or switch) rebuilds it from its
     * current settings — called after .swarmcoder/project.yaml changes. If it is the
     * current project the rebuild happens immediately so intake keeps working.
     */
    public synchronized void invalidateProject(UUID projectId) {
        this.projectContexts.remove(projectId);
        if (this.current != null && projectId.equals(this.current.project().id())) {
            Project project = this.artifactStore.getProject(projectId);
            if (project != null) {
                ProjectContext rebuilt = buildContext(project);
                this.projectContexts.put(projectId, rebuilt);
                this.current = rebuilt;
            }
        }
    }

    /** Routes subsequent intake to {@code projectId}, building its context on first switch. */
    public synchronized ProjectContext switchProject(UUID projectId) {
        Project project = this.artifactStore.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("No such project: " + projectId);
        }
        ProjectContext ctx = this.projectContexts.computeIfAbsent(projectId, id -> buildContext(project));
        this.current = ctx;
        this.artifactStore.setLastProject(projectId);   // reopen here next start
        return ctx;
    }

    /** Starts a run — the single intake path used by the TUI and the Console. */
    public UUID startRun(String goal, WorkflowKind kind) {
        return startRun(goal, kind, null);
    }

    /**
     * Starts a run, optionally already bound to the backlog story it is an attempt at.
     *
     * <p>The binding has to happen HERE, before the engine is handed the run. The engine starts
     * advancing it on its own thread immediately and rebuilds the Run from its own copy at every
     * state transition, so a storyId attached by the caller a moment later is simply lost — the run
     * then invents its own requirements instead of using the BRD's, plans without criterion refs,
     * and finishes without moving the story to REVIEW or stamping any criterion.
     *
     * @param storyId the story this run delivers, or null for an ad-hoc run
     */
    public UUID startRun(String goal, WorkflowKind kind, UUID storyId) {
        // Refused here, at the one intake both the Console and the chat go through, rather than
        // trusted to stay off the menus. A kind this build does not perform must not be startable
        // by any route: the alternative is a run that reports work it never attempted.
        if (kind == null || !kind.isStartable()) {
            throw new IllegalArgumentException("SwarmCoder does not run " + kind + " work. "
                + "A run must be one of " + WorkflowKind.startable()
                + " — every one of those designs, plans, writes an acceptance test that must fail "
                + "first, builds it with real workers and verifies the result before it can say it "
                + "delivered anything.");
        }
        UUID runId = UUID.randomUUID();
        // The project and the story are handed to the constructor, not attached afterwards: a run
        // is driven against ITS project's repository and locks, so a run that exists without one for
        // even a moment is a run something could resume against the wrong tree.
        Run run = new Run(runId, kind, RunState.INTAKE,
            current.project().id(), storyId,
            null, null, null, Instant.now(),
            new RunReport(runId, goal));
        // The branch this run builds on, and the exact commit it is cut from, decided once here.
        // It is the repository's own HEAD, as it always was - only resolved to a commit instead of
        // being re-read later. That matters now that accepting a story moves the delivery branch: a
        // live HEAD would shift under a build that is still going, so the candidates of one task
        // would be built on a different tree from the candidates of the next. One implementation,
        // shared with the end-to-end harness, so the harness starts a run the way the Console does.
        String pinned = BasePin.pin(run, current.gitService());
        if (pinned != null) {
            log.info(pinned);
        }
        current.workflowEngine().advance(run);
        return runId;
    }

    public void approveRun(UUID runId) {
        transitionRun(runId, RunState.DELIVERED);
    }

    public void rejectRun(UUID runId) {
        transitionRun(runId, RunState.ABORTED);
    }

    private void transitionRun(UUID runId, RunState state) {
        try {
            // Awaited: approvals must be durable before the caller's next read.
            artifactStore.append(() -> {
                Run run = artifactStore.root().runs.get(runId);
                if (run != null) {
                    Run updated = run.withState(state);
                    artifactStore.root().runs.put(runId, updated);
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted persisting run transition", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to persist run transition", e.getCause());
        }
    }

    /**
     * Puts an accepted story's finished code on the project's delivery branch, and says plainly when
     * it could not.
     *
     * <p>The merge and its verification happen in {@code StoryDelivery}, which follows the same
     * discipline the task integrator already uses one level down: merge in a throwaway worktree, run
     * the project's full verification over the combined tree, and move the branch only when that is
     * green. Two stories can each pass alone and fail together, and this is the only place that shows
     * up.
     *
     * @return null when the code is on the branch; otherwise the reason, which stops the acceptance
     */
    public String deliverStory(UUID storyId) {
        Story story = artifactStore.getStory(storyId);
        if (story == null) {
            return "the story could not be found any more";
        }
        ProjectContext context = story.projectId() == null
            ? this.current : projectContextFor(story.projectId());
        if (context == null) {
            return "the project this story belongs to could not be opened";
        }
        GitService git = context.gitService();
        // The merged tree is verified in a container, like every other build of model-written
        // code; the project's engine knows which one and which reference folders it may read.
        com.swarmcoder.verify.BuildBoxes boxes =
            context.swarmEngine() instanceof com.swarmcoder.swarm.SwarmEngineImpl engine
                ? engine.buildBoxes() : com.swarmcoder.verify.BuildBoxes.none();
        StoryDelivery.Result result = new StoryDelivery(git, artifactStore, boxes)
            .deliver(story, story.integrationCommit() == null
                ? story.deliveredCommit() : story.integrationCommit(),
                git == null ? null : git.currentBranch());
        return result.failure();
    }

    /** The project context for a project, building it if this process has not needed it yet. */
    private synchronized ProjectContext projectContextFor(UUID projectId) {
        if (this.current != null && projectId.equals(this.current.project().id())) {
            return this.current;
        }
        ProjectContext known = this.projectContexts.get(projectId);
        if (known != null) {
            return known;
        }
        Project project = this.artifactStore.getProject(projectId);
        if (project == null) {
            return null;
        }
        ProjectContext built = buildContext(project);
        this.projectContexts.put(projectId, built);
        return built;
    }

    /**
     * Starts the watcher that lets the operator plan a set of stories, press go and go to bed.
     *
     * <p>Always started, and harmless when unattended running is off: every check then does nothing.
     * Starting it conditionally would mean the setting could only be switched on by restarting.
     */
    public UnattendedPilot startUnattendedPilot() {
        int concurrency = config.overnight() == null ? 1 : config.overnight().concurrencyOrDefault();
        UnattendedPilot pilot = new UnattendedPilot(artifactStore, concurrency);
        pilot.start();
        return pilot;
    }

    /**
     * True when a build would stop rather than run: the safety box is on and required, and Docker
     * is not answering.
     *
     * <p>Only sc-app can answer this — it is the one place that knows both what the settings ask
     * for and whether the daemon replied. Asked by the console (to explain why it will not offer a
     * build) and by the startup summary (to list it among the things still to do).
     */
    public boolean sandboxBlocksRuns() {
        return sandboxBlocker() != null;
    }

    /**
     * Why a build would stop rather than run, in words for the operator, or null when it would
     * run. Covers both ways there can be no container to put a worker in: Docker not answering,
     * and Docker answering without the image - which used to pass this check and then fail every
     * candidate one by one.
     */
    public String sandboxBlocker() {
        if (!sandboxEnabled) {
            return null; // explicitly switched off: runs work, and model code runs on this PC
        }
        // No "allowed anyway" branch: with the box on, no container means no run.
        return sandboxManager.whyUnusable().orElse(null);
    }

    /**
     * The observability spine over one store: a {@link TraceHub} that offloads oversized payloads
     * to {@code blobs}, files every finished session in the store, keeps a working run's
     * freshness stamp current, and records a worker's {@code exec} before it runs.
     *
     * <p>Package-private and static so the end-to-end harness builds its hub with THIS method
     * rather than a copy of it (2026-09-25). Before that the harness built a bare
     * {@code new TraceHub(null)}: no finished session ever reached its store, so a worker's
     * transcript vanished from every view the moment the worker ended, and no heartbeat was ever
     * stamped, so {@code swarm_status} would have called a harness run with four busy workers
     * dead. Neither mattered while nobody could look; both matter once the harness serves the
     * Console and the MCP server over its store.
     *
     * @param blobs         where payloads above the inline cap go; null truncates them instead
     * @param alsoWhenEnded anything else to do with a finished session (the app indexes it for
     *                      history search), or null
     */
    static TraceHub traceHubOver(ArtifactStore store, BlobStore blobs,
                                 java.util.function.Consumer<AgentSessionRecord> alsoWhenEnded) {
        TraceHub hub = new TraceHub(blobs == null ? null : content -> {
            try {
                return blobs.storeBlob(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return null;
            }
        });
        hub.addListener(new TraceHub.Listener() {
            @Override
            public void sessionEnded(AgentSessionRecord complete) {
                store.append(() -> {
                    store.root().agentSessions().put(complete.id(), Lazy.Reference(complete));
                    return null;
                });
                if (alsoWhenEnded != null) {
                    alsoWhenEnded.accept(complete);
                }
            }
        });
        // A run is alive while its workers are taking steps, and the store is where that fact has
        // to land: nothing transitions during a forty-five-minute swarm, so the run's freshness
        // stamp went stale and every reader concluded the build was dead. See RunHeartbeat.
        hub.addListener(new com.swarmcoder.workflow.RunHeartbeat(store));
        // A worker's exec call is recorded to disk BEFORE the command runs, so a harness death
        // mid-command (runs 21 and 29: a worker's shell command killed the harness JVM itself)
        // leaves evidence of which command it was, rather than the whole in-memory transcript
        // vanishing with the process. See PendingExecRecorder.
        hub.addListener(new com.swarmcoder.workflow.PendingExecRecorder(store));
        return hub;
    }

    /** Starts the embedded Console when {@code consolePort} is configured; null otherwise. */
    public Zeroz4jServer startConsole() {
        if (this.config.consolePort() == null) {
            return null;
        }
        installConsoleContext();
        return startConsoleServer();
    }

    /**
     * Everything the Console answers with, wired to this graph — and no socket.
     *
     * <p>Split out of {@link #startConsole()} so a test can drive the console's own view of the
     * machine without binding a port. Installing the context is what publishes readiness, so this
     * is the step that decides what a newcomer is TOLD; binding a port only decides where they read
     * it. Keeping them together meant the only way to assert the first-run message was to start a
     * real server on the real configured port, which on a developer's machine collides with the
     * SwarmCoder they already have running.
     */
    public void installConsoleContext() {
        BlobStore consoleBlobs;
        try {
            consoleBlobs = new BlobStore(
                Paths.get(System.getProperty("user.home"), ".swarmcoder", "blobs"));
        } catch (IOException e) {
            consoleBlobs = null;
        }
        ConsoleContext.set(new ConsoleContext(
            artifactStore, traceHub, consoleBlobs,
            (goal, kind) -> startRun(goal, WorkflowKind.valueOf(kind)),
            this::approveRun,
            this::rejectRun)
            // A backlog-started run carries its story from the first instant; see startRun.
            .withStoryRuns((goal, kind, storyId) ->
                startRun(goal, WorkflowKind.valueOf(kind), storyId))
            // Accepting a story puts its code on the project's delivery branch. Until this existed
            // nothing ever merged anything into the branch the operator works on, so a story could
            // never build on the one before it: every worktree is cut from that branch, and no
            // earlier story's work was ever on it.
            .withStoryDelivery(this::deliverStory)
            // Unattended running, read live from the settings file so switching it on or off does
            // not need a restart.
            .withUnattended(() -> this.config.overnight() != null
                && this.config.overnight().enabled())
            .withSettings(this::readConfigYaml, this::writeConfigYaml)
            // How to build a folder of code SwarmCoder has never seen. Without a contract every
            // candidate comes back unverified, which is the swarm choosing between untested
            // guesses — so this is the first thing an existing project needs.
            // The compile probe runs in a container on a throwaway copy of the folder.
            .withBuildContracts(new BuildContractBridge(new com.swarmcoder.verify.BuildBoxes(
                () -> this.sandboxEnabled ? this.sandboxManager : null, java.util.Map::of)))
            .withHistorySearch((query, max) -> contextLedger.searchHistory(query, max).stream()
                .map(HistoryRag.Hit::sessionId).toList())
            .withProjects(this::listProjects, () -> current.project().id(),
                (name, path, ctx) -> addProject(name, path, ctx).project(),
                this::switchProject)
            // Deleting a project must end what it left running, not just its store records —
            // see teardownDeletedProject. Called by ControlServiceImpl right before the project's
            // records are removed.
            .withProjectTeardown(this::teardownDeletedProject)
            // The chat coder streams through the CURRENT project's chat client (roles.chat →
            // utility fallback) so switching projects switches the model too. The context
            // supplier grounds the analyst prompt in the project's real folders/models.
            .withChat((messages, modelOverride) -> chatClientForModel(modelOverride)
                .as("chat").chatCompletionStream(messages, null, 0.4),
                this::chatProjectContext)
            .withChatModels(this::availableChatModels)
            // The two wizards stream through the CURRENT project's own clients, resolved per
            // project like the chat coder above. No model override: the operator picks a model per
            // CHAT, but a requirement graph is not a conversation to experiment in — which model
            // authored it is a property of the project's config, visible in Settings.
            .withAnalyst((messages, modelOverride) -> currentProject().analystClient()
                .as("analyst").chatCompletionStream(messages, null, 0.4))
            .withPlanner((messages, modelOverride) -> currentProject().plannerClient()
                .as("planner").chatCompletionStream(messages, null, 0.4))
            // Research tools: the analyst reads the project + context folders itself,
            // confined to the current project's knowledge roots.
            .withChatTools(new ConsoleContext.ChatTools() {
                @Override
                public String listFolder(String address) {
                    return currentProject().librarian().curator().listFolder(address);
                }
                @Override
                public String readFile(String address) {
                    return currentProject().librarian().curator().readFile(address, 12_000);
                }
                @Override
                public String searchCode(String query) {
                    return currentProject().librarian().curator().searchCode(query, 40);
                }
                @Override
                public String lookupDocs(String query) {
                    return currentProject().librarian().lookupApi(query);
                }
                @Override
                public List<String> mentionCandidates(String query) {
                    return currentProject().librarian().curator().mentionCandidates(query, 8);
                }
            })
            .withResearcher(new ConsoleContext.Researcher() {
                @Override
                public String start(String topic) {
                    return startResearch(topic);
                }
                @Override
                public String status() {
                    return researchStatus.getOrDefault(current.project().id(), "idle");
                }
            })
            .withConfigForms(new ConfigFormsBridge(this))
            // Why a run could not start for environmental reasons. Only sc-app knows this: whether
            // the sandbox is required, and whether Docker actually answered.
            .withSandboxBlocker(this::sandboxBlocker)
            // The project's rules: turning one on or off, giving one a check, and recording one
            // the operator stated in a technical document. It has to come through here because the
            // rules belong to the CURRENT project, which only this wiring knows; the console module
            // cannot reach the class that owns them.
            .withGuidelineControl(new ConsoleContext.GuidelineControl() {
                @Override
                public String setStatus(java.util.UUID id, String status) {
                    return currentProject().projectRules()
                        .setStatus(id, com.swarmcoder.domain.GuidelineStatus.valueOf(status));
                }
                @Override
                public String setCheck(java.util.UUID id, String command, int timeoutSeconds) {
                    return currentProject().projectRules().setCheck(id, command, timeoutSeconds);
                }
                @Override
                public String stateRule(String title, String body, String document) {
                    return currentProject().projectRules().stateRule(title, body, document);
                }
                @Override
                public String stateRule(String title, String body, String document,
                                        String excerpt) {
                    return currentProject().projectRules()
                        .stateRule(title, body, document, excerpt);
                }
                @Override
                public String stateRule(String title, String body, String document,
                                        String excerpt, String purpose, boolean hard) {
                    return currentProject().projectRules()
                        .stateRule(title, body, document, excerpt, purpose, hard);
                }
                @Override
                public String stateRule(String title, String body, String document,
                                        String excerpt, String purpose, boolean hard,
                                        java.util.List<String> appliesTo) {
                    return currentProject().projectRules()
                        .stateRule(title, body, document, excerpt, purpose, hard, appliesTo);
                }
                @Override
                public java.util.List<String> ruleScopes() {
                    return currentProject().projectRules().modules();
                }
                @Override
                public int supersedeRulesFrom(String document) {
                    return currentProject().projectRules().supersedeRulesFrom(document);
                }
            })
            .withVision(visionModel())
            .withHealth(new HealthProbe(cloudGate, sandboxEnabled, this::firstWorkerBaseUrl))
            // Nothing is open until the operator says so. The console starts by ASKING which
            // project to work on — the machinery has a current project because everything resolves
            // through one, but that is not the same as the operator having chosen it, and the two
            // used to be indistinguishable on screen.
            //
            // The suggestion is where they were last: it is pre-selected so the ordinary day is a
            // single keypress, and it still has to be pressed.
            .awaitingProjectChoice(rememberedProject()));
    }

    /**
     * The project to offer first in the picker: where the operator was last, or the configured one
     * on a very first run.
     *
     * <p>A bookmark that points at a project which has since been deleted suggests nothing rather
     * than suggesting something arbitrary.
     */
    private UUID rememberedProject() {
        UUID remembered = this.artifactStore.lastProjectId();
        if (remembered != null && this.artifactStore.getProject(remembered) != null) {
            return remembered;
        }
        return this.current == null || this.current.project() == null
            ? null : this.current.project().id();
    }

    /** Binds the port and connects live push. Assumes {@link #installConsoleContext()} has run. */
    private Zeroz4jServer startConsoleServer() {
        return bindConsole(this.config.consolePort());
    }

    /**
     * Binds the Console on {@code port} (0 for any free one) over whatever {@link ConsoleContext}
     * is installed, and connects live push.
     *
     * <p>Package-private and static so the end-to-end harness serves its run through exactly this
     * (2026-09-25), rather than a copy that would drift on the next change to how the Console is
     * brought up. Throws what {@link Zeroz4jServer#start} throws when the port cannot be had.
     */
    static Zeroz4jServer bindConsole(int port) {
        // The Console's RMI services are @Secured; this local single-operator tool authenticates
        // the browser client as the built-in dev admin (ConsoleApp sends dev credentials), so the
        // server must run in dev-auth mode or every secured call is denied and no data renders.
        if (System.getProperty("zeroz.security.mode") == null) {
            System.setProperty("zeroz.security.mode", "dev");
        }
        Zeroz4jServer server = Zeroz4jServer.start(port, "SwarmCoder Console");
        
        try {
            ConsoleContext context = ConsoleContext.get();
            WasmRmiServerEngine engine = CDI.current().select(WasmRmiServerEngine.class).get();
            // No TraceHub -> browser bridge any more. It broadcast every session start, every
            // session end and EVERY AGENT TURN on the "swarm-sessions" / "swarm-events" topics, and
            // its only subscriber was the global swarm board, which is deleted (UX v3 6): workers
            // live inside the build they belong to, reached from the story's own card. Serialising a
            // trace event per turn and pushing it to every connected browser to be discarded is a
            // cost paid for nothing. The run graph is unaffected - it has its own "run-graph"
            // publisher, driven by GraphServiceImpl.watch - and the store remains the record.
            context.setPushSink(engine::broadcastPush);
            log.info("Console live push connected (run graph / chat)");
        } catch (Exception e) {
            log.warn("Console live push not connected: {}", e.getMessage());
        }
        
        return server;
    }

    /**
     * Starts the MCP server when {@code mcpApi.enabled} is set, so an outside agent (Claude Code,
     * an IDE, CI) can ask what a run is doing. Returns its URL, or null when it is off — which is
     * the default, because opening a port changes what this product exposes and that is the
     * operator's decision, not a side effect of starting up.
     *
     * <p>Must be called AFTER {@link #startConsole()}: the three services it adapts are CDI beans
     * the Console's container builds, and it deliberately uses those and nothing else, so an
     * outside agent and the Console can never disagree about what is true.
     */
    public String startMcpServer() {
        McpApiConfig mcp = this.config.mcpApi();
        if (!mcp.isEnabled()) {
            return null;
        }
        try {
            SwarmMcpServer server = new SwarmMcpServer(
                CDI.current().select(ObserverService.class).get(),
                CDI.current().select(GraphService.class).get(),
                CDI.current().select(ControlService.class).get(),
                mcp.portOrDefault(), "", mcp.isReadOnly());
            String url = server.start();
            this.mcpServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stopMcpServer, "mcp-shutdown"));
            return url;
        } catch (Exception e) {
            // Never fatal. The MCP server is a window onto the work, not part of doing it; a
            // machine that cannot open the port must still build software.
            log.warn("The MCP server did not start, so nothing outside can watch this run: {}",
                e.toString());
            return null;
        }
    }

    /** Closes the MCP port. Idempotent; does nothing when the server was never started. */
    public void stopMcpServer() {
        SwarmMcpServer server = this.mcpServer;
        this.mcpServer = null;
        if (server != null) {
            server.stop();
        }
    }

    /** Distinct chat-selectable model names across the configured roles + worker families. */
    private List<String> availableChatModels() {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (AgentModelConfig model : allChatModelConfigs()) {
            if (model != null && model.modelName() != null && !model.modelName().isBlank()
                    && model.baseUrl() != null && !model.baseUrl().isBlank()) {
                models.add(model.modelName());
            }
        }
        return new ArrayList<>(models);
    }

    private List<AgentModelConfig> allChatModelConfigs() {
        List<AgentModelConfig> list = new ArrayList<>();
        RolesConfig roles = config.roles();
        if (roles != null) {
            list.add(roles.chat());
            list.add(roles.utility());
            list.add(roles.requirementsAnalyst());
            list.add(roles.storyPlanner());
            list.add(roles.architect());
            list.add(roles.taskPlanner());
            list.add(roles.designReviewer());
            list.add(roles.testAuthor());
            list.add(roles.judge());
            if (roles.workerFamilies() != null) {
                list.addAll(roles.workerFamilies());
            }
        }
        return list;
    }

    /** Resolves a per-chat model name to its endpoint client; the project default when unset/unknown. */
    private VllmClient chatClientForModel(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return currentProject().chatClient();
        }
        for (AgentModelConfig model : allChatModelConfigs()) {
            if (model != null && modelOverride.equals(model.modelName())
                    && model.baseUrl() != null && !model.baseUrl().isBlank()) {
                return new VllmClient(model.baseUrl(), model.apiKey(), model.modelName(),
                    model.resolvedQuirks());
            }
        }
        return currentProject().chatClient();
    }

    /** The first configured worker-family endpoint (the Spark) for the health probe; null if none. */
    private String firstWorkerBaseUrl() {
        if (config.roles() != null && config.roles().workerFamilies() != null) {
            for (AgentModelConfig worker : config.roles().workerFamilies()) {
                if (worker != null && worker.baseUrl() != null && !worker.baseUrl().isBlank()) {
                    return worker.baseUrl();
                }
            }
        }
        return null;
    }

    /** Per-project live research status ("idle" | "researching…" | last outcome). */
    private final Map<UUID, String> researchStatus =
        new ConcurrentHashMap<>();

    /** Runs the current project's Researcher on a background thread; one mission at a time. */
    private synchronized String startResearch(String topic) {
        ProjectContext project = current;
        UUID projectId = project.project().id();
        String state = researchStatus.get(projectId);
        if (state != null && state.startsWith("researching")) {
            return "error: a research mission is already running for this project";
        }
        researchStatus.put(projectId, "researching" + (topic == null || topic.isBlank()
            ? " (surveying the project's libraries)…" : ": " + topic + "…"));
        Thread mission = new Thread(() -> {
            String outcome;
            try {
                outcome = project.researcher().research(topic);
            } catch (Exception e) {
                outcome = "error: " + e.getMessage();
            }
            researchStatus.put(projectId, outcome);
        }, "researcher-" + projectId.toString().substring(0, 8));
        mission.setDaemon(true);
        mission.start();
        return "";
    }

    /** Grounding facts for the chat analyst's system prompt (design §5.2). */
    private String chatProjectContext() {
        Project project = currentProject().project();
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT FACTS: primary folder = ").append(project.primaryPath());
        if (project.contextPaths() != null && !project.contextPaths().isEmpty()) {
            sb.append("; read-only context folders = ").append(project.contextPaths());
        }
        if (config.roles() != null && config.roles().workerFamilies() != null
                && !config.roles().workerFamilies().isEmpty()) {
            sb.append("; worker models = ").append(config.roles().workerFamilies().stream()
                .map(w -> w == null ? "?" : String.valueOf(w.modelName())).toList());
        }
        sb.append(". Runs execute against the primary folder's git repository.\n");
        // The Librarian's distilled context-folder API reference: the analyst answers
        // framework questions from REAL signatures instead of asking the operator (or
        // inventing). Deterministic + capped → prefix-cache friendly.
        String reference = currentProject().librarian().contextApiReference(9_000);
        if (!reference.isBlank()) {
            sb.append("\nFRAMEWORK REFERENCE — real API signatures extracted from the "
                + "project's read-only context folders. Treat as ground truth: if a class "
                + "or method is not listed here, say it does not exist rather than "
                + "assuming.\n").append(reference);
        }
        return sb.toString();
    }

    private static Path configPath() {
        return Paths.get(System.getProperty("user.home"), ".swarmcoder", "config.yaml");
    }

    /** Raw config YAML for the Console Settings form (the file the TUI also edits). */
    private String readConfigYaml() {
        try {
            Path path = configPath();
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "# failed to read config: " + e.getMessage();
        }
    }

    /** Validates the YAML parses as SwarmConfig, then writes it. Returns "" or an error. */
    private String writeConfigYaml(String yaml) {
        try {
            new ObjectMapper(
                new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(yaml, SwarmConfig.class);
            Files.writeString(configPath(), yaml);
            return ""; // restart applies changes (wiring is constructed at startup)
        } catch (Exception e) {
            return "invalid config: " + e.getMessage();
        }
    }

    public void close() throws Exception {
        artifactStore.close();
    }
}