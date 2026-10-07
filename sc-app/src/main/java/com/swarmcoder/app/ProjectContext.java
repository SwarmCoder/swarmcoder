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

import com.swarmcoder.swarm.WorkerPersonas;
import com.swarmcoder.domain.Project;
import com.swarmcoder.git.GitService;
import com.swarmcoder.knowledge.ContextLedger;
import com.swarmcoder.knowledge.ExpertDesk;
import com.swarmcoder.knowledge.ExpertEscalation;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.knowledge.GuidelineExtractor;
import com.swarmcoder.knowledge.GuidelineFolderImport;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.ApiLookup;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.PathPolicy;
import java.nio.file.Files;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.ArchitectResearch;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import com.swarmcoder.app.config.ProjectConfig;
import com.swarmcoder.app.config.ProjectConfigLoader;
import com.swarmcoder.app.config.SwarmEngineConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.SwarmSizing;
import com.swarmcoder.domain.TurnAllowance;
import com.swarmcoder.app.config.BudgetsConfig;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.knowledge.KnowledgeExtractor;
import com.swarmcoder.knowledge.ResearcherAgent;
import com.swarmcoder.knowledge.WebAccess;

/**
 * Everything scoped to a single {@link Project}: its git repo, its Librarian (with the project's
 * read-only context folders), its guidelines, its swarm engine, and its workflow engine — all
 * built over the process-wide {@link SharedServices}. Constructing one per project is what lets a
 * single orchestrator run several projects at once (multi-project); today {@code DependencyGraph}
 * builds exactly one (the default project) and the Console will add more.
 */
public final class ProjectContext {

    private static final Logger log = LoggerFactory.getLogger(ProjectContext.class);

    private final Project project;
    private final GitService gitService;
    private final ProjectRules projectRules;
    private final Librarian librarian;
    private final ContextLedger contextLedger;
    private final CloudRoles cloudRoles;
    private final SwarmEngine swarmEngine;
    private final WorkflowEngine workflowEngine;
    private final VllmClient chatClient;
    private final VllmClient analystClient;
    private final VllmClient plannerClient;
    private final ResearcherAgent researcher;

    /**
     * @param protectedPaths the GLOBAL locked-down modules ({@code SwarmConfig.protectedPaths});
     *                       the project's own {@code project.yaml} entries are added to them here
     */
    public ProjectContext(Project project, SharedServices shared, RoleClients roles,
                          int maxGuidelineChars, boolean sandboxEnabled, boolean guidelineAutoPromote,
                          SwarmEngineConfig swarm, List<String> protectedPaths) {
        this(project, shared, roles, maxGuidelineChars, sandboxEnabled, guidelineAutoPromote,
            swarm, protectedPaths, null);
    }

    /**
     * @param protectedPaths the GLOBAL locked-down modules ({@code SwarmConfig.protectedPaths});
     *                       the project's own {@code project.yaml} entries are added to them here
     * @param budgets        the settings file's {@code budgets:} block, the global layer of the
     *                       per-worker turn allowance; null means nothing global was stated
     */
    public ProjectContext(Project project, SharedServices shared, RoleClients roles,
                          int maxGuidelineChars, boolean sandboxEnabled, boolean guidelineAutoPromote,
                          SwarmEngineConfig swarm, List<String> protectedPaths,
                          BudgetsConfig budgets) {
        // Two of the three sizing layers are resolved here, because this is the one place both
        // the settings file and the project's own project.yaml are in hand. The third — the story
        // — is applied when a run plans its tasks (GreenfieldWorkflow, PLAN), because that is the
        // first moment a story exists.
        SwarmPolicy taskPolicy = taskPolicyFrom(swarm, projectSwarmOf(project));
        // The turn allowance rides on the same policy and is resolved over the same two layers
        // here, for the same reason: this is the one place both files are in hand.
        TurnAllowance turns = TurnAllowance.resolve(null, null, projectTurnsOf(project),
            budgets == null ? null : budgets.statedMaxToolTurns());
        if (taskPolicy != null) {
            taskPolicy = taskPolicy.withTurns(turns);
        }
        this.project = project;
        if (taskPolicy != null) {
            log.info("Project '{}': {}", project.name(),
                taskPolicy.nSource() == null ? taskPolicy.n() + " workers on each piece of work"
                    : taskPolicy.n() + (taskPolicy.n() == 1 ? " worker" : " workers")
                        + " on each piece of work, because " + taskPolicy.nSource());
        }
        log.info("Project '{}': {}", project.name(), turns.sentence());
        // Locked-down modules for this project: global config + whatever the project's own
        // .swarmcoder/project.yaml adds. Resolved here because this is the one place both are
        // in hand, and handed to BOTH enforcement points below — the workers' toolboxes and
        // FINAL_INTEGRATION's diff audit.
        List<String> lockedPaths = lockedPathsFor(project, protectedPaths,
            gitForLocks(project), Boolean.getBoolean("swarmcoder.unlockTrustKernel"));
        if (!lockedPaths.isEmpty()) {
            log.info("Project '{}' locked modules (no worker may modify): {}",
                project.name(), lockedPaths);
        }

        // Git — workers branch from HEAD into per-candidate worktrees, so the project folder
        // must be a git repo with a commit. Auto-init one when it isn't (a plain folder the
        // operator pointed at): a run would otherwise fail every worker with "no worktree".
        Path projectPath = project.primaryPath() != null && !project.primaryPath().isBlank()
            ? Paths.get(project.primaryPath()) : null;
        boolean isGitRepo = projectPath != null && GitService.ensureRepo(projectPath);
        this.gitService = isGitRepo ? new GitService(projectPath) : GitService.disabled();
        if (isGitRepo) {
            log.info("Project '{}' repository: {}", project.name(), projectPath);
        } else if (projectPath != null) {
            log.warn("Project '{}' folder {} is not a usable git repo — runs cannot execute",
                project.name(), projectPath);
        }

        // The project's rules are store objects, keyed to the project (author decision 2026-09-02).
        // A checkout from before that may still carry rule FILES under .swarmcoder/guidelines/;
        // they are read into the store once, on the first open, and the folder is never consulted
        // again. The files stay — they are in the operator's git history.
        this.projectRules = new ProjectRules(shared.store(), project.id());
        GuidelineFolderImport.importOnce(shared.store(), project, projectRules,
            projectPath == null ? null : projectPath.resolve(".swarmcoder").resolve("guidelines"));

        // Librarian, primed with the project's read-only context folders (e.g. zeroz4j).
        // One list, two kinds of reference source: a folder on this machine, or the base URL of a
        // documentation site. A URL is stored beside the folders because it is the same thing to
        // the operator — "read this, do not write to it" — and because project settings are store
        // objects, so a second list would have been a second schema for no gain.
        List<String> configured = project.contextPaths() == null ? List.<String>of()
            : project.contextPaths().stream().filter(p -> p != null && !p.isBlank()).toList();
        List<Path> contextRoots = configured.stream()
            .filter(p -> !isDocumentationSite(p))
            .map(Paths::get)
            .collect(Collectors.toList());
        List<String> contextUrls = configured.stream()
            .filter(ProjectContext::isDocumentationSite)
            .collect(Collectors.toList());
        if (!contextRoots.isEmpty()) {
            log.info("Project '{}' context folders (read-only): {}", project.name(), contextRoots);
        }
        if (!contextUrls.isEmpty()) {
            log.info("Project '{}' documentation sites (read-only, fetched here and never by a "
                + "worker): {}", project.name(), contextUrls);
        }
        // Librarian v2: store-first curated knowledge + distilled framework primer (utility
        // model, disk-cached) + task-relevant full sources — no tree-sitter signature dumps.
        this.librarian = new Librarian(shared.context7(), shared.docsIndex(), contextRoots,
            projectPath, roles.utility(), null,
            () -> shared.store().listKnowledgeDocs(project.id()), null, contextUrls);
        // Sized for the room the workers really have (MaterialBudget, 2026-09-25): every figure in
        // the brief and in a worker's lookup_api answer was measured at 51,200 tokens and grows in
        // proportion above it, up to 262,144. The smallest worker family decides, because one
        // brief is shared by every worker of a task whichever family it runs on.
        MaterialBudget workerRoom = workerRoomOf(shared.profiles());
        librarian.sizedFor(workerRoom);
        // The Java language server (owner's decision, 2026-10-04). ONE for the project's own
        // checkout, read-only, asked by every planning role, the expert and - for what the
        // project is - the workers; started on the first question, not here. It runs on this
        // PC and executes nothing of the project: no build import, no plugin, no annotation
        // processor (see JdtLanguageServer). Off when none is installed.
        com.swarmcoder.lsp.LspService projectServer = projectPath == null
            ? com.swarmcoder.lsp.LspService.UNAVAILABLE
            : com.swarmcoder.lsp.LspServiceFactory.installed(
                com.swarmcoder.knowledge.ProjectClasspath::jarsOf, false).create(projectPath);
        librarian.curator().languageServer(projectServer, projectPath);
        boolean languageServerInstalled = projectServer.isInstalled();
        if (languageServerInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(projectServer::close,
                "jdtls-stop-" + project.name()));
        }
        log.info("Project '{}': {}", project.name(), languageServerInstalled
            ? "a Java language server is installed (" + com.swarmcoder.lsp.JdtLsInstall.locate()
                + "); roles and workers can ask it for usages, hierarchies, callers and the "
                + "members of library types, and workers can rename and check files with it."
            : "no Java language server is installed (looked in "
                + com.swarmcoder.lsp.JdtLsInstall.defaultToolsDir() + "); roles and workers have "
                + "the syntax tree only, and the members of a type in a library jar cannot be "
                + "listed.");
        // One per worker checkout, started only when that worker renames, organizes imports or
        // asks for a file's problems, and stopped with the worker. Same containment: no build.
        com.swarmcoder.lsp.LspServiceFactory checkoutServers =
            com.swarmcoder.lsp.LspServiceFactory.forCheckouts(
                com.swarmcoder.knowledge.ProjectClasspath::jarsOf, true);
        log.info("Project '{}': workers are handed {}.", project.name(), workerRoom.describe());
        // Say ONCE, loudly, when this project's knowledge sources are not what the operator
        // thinks they are. Failing quietly per lookup is right — one dead call must not kill a
        // run. Failing quietly at STARTUP is what cost ten workers a whole run (§32).
        EnvironmentChecks.reportKnowledgeSources(project.name(), contextRoots,
            librarian.curator().documentCount(), shared.context7().posture(),
            shared.context7().endpointUrl());

        // The help desk's expert tier (ExpertDesk javadoc): the utility role, falling back to
        // architect, spent through the run's cloud gate exactly like every other paid role. Null
        // when neither is configured — ExpertDesk already handles that by telling the worker so
        // plainly instead of escalating to an unconfigured default endpoint — logged ONCE here at
        // startup, rather than once per question the desk cannot escalate.
        String expertRole = expertRoleFor(roles);
        // The expert is an agent SESSION, not a chat call: same endpoint, same cloud gate, plus
        // read-only tools over this project's own code (ExpertTools) so it looks an API up instead
        // of remembering one. Built from the role's client, this project's curator (the repository
        // and every configured reference folder - the tools' whole world) and its Librarian.
        ExpertEscalation expertEscalation = expertRole == null ? null
            : new ExpertEscalation("utility".equals(expertRole) ? roles.utility() : roles.architect(),
                shared.cloudGate(), librarian.curator(), librarian, projectPath, List.of(),
                shared.runtime(), ExpertEscalation.MAX_TURNS);
        if (expertEscalation != null) {
            log.info("Project '{}': the help desk's expert tier escalates to the '{}' role when "
                + "the free tiers cannot answer a worker's question. It may take up to {} turns "
                + "and has these read-only tools over this project: {}.", project.name(),
                expertRole, ExpertEscalation.MAX_TURNS,
                String.join(", ", expertEscalation.toolNames()));
        } else {
            log.warn("Project '{}': no utility or architect role is configured, so the help "
                + "desk's expert tier has nothing to escalate to — a worker whose question the "
                + "free tiers cannot answer will be told plainly, not sent to an unconfigured "
                + "endpoint.", project.name());
        }
        // A fresh ExpertDesk per worker (its escalation cap lives on the instance — sharing one
        // would share the cap across every worker of a wave), grounded in the same reference
        // material the Librarian was built from. Contracts empty: request_skeleton falls back to
        // the nearest existing file of the right shape (ExpertDesk#nearestExampleFile), because the
        // design's ApiContracts are a run-level artifact that does not reach this per-project
        // wiring — the same simplification the desk itself makes for "no contract named it".
        BiFunction<String, String, String> escalationForWorkers = expertEscalation;
        List<String> frameworkPackages = frameworkPackagesOf(librarian);
        // The desk's free first step (2026-10-04): the workers' own local model reads a question
        // beside the answers the expert already researched in this run, and only what none of
        // them answers is researched again. Null - no worker model that costs nothing - leaves
        // the desk as it was.
        com.swarmcoder.knowledge.StoredAnswerJudge answerJudge = localAnswerJudgeOf(shared.profiles());
        log.info("Project '{}': a question to the expert is first checked against the answers "
            + "already researched in the run {}.", project.name(), answerJudge == null
            ? "by shared words only - no worker model that costs nothing is configured to read them"
            : "by the workers' local model, which costs nothing");
        Supplier<ExpertHelp> expertFactory = () -> new ExpertDesk(
            librarian.curator(), projectPath, List.of(), escalationForWorkers)
            .judgedBy(answerJudge).sizedFor(workerRoom);

        // Swarm engine — the rules are read from the store at each prefix assembly, in a
        // deterministic order, so the shared prefix is cache-safe and a rule switched off mid-run
        // stops being sent.
        // Not librarian::lookupApi: a method reference binds only to lookup(String), and the
        // exclusion/boost overload — "never hand this worker the same section twice" — only does
        // anything when the ApiLookup the worker's toolbox holds actually forwards to it.
        // One class for the product and the live harness (run 82: the harness wired a method
        // reference here and its workers' tree queries all answered "not configured").
        ApiLookup lookup = new WorkerLookups(librarian, projectPath, languageServerInstalled,
            checkoutServers);
        SwarmEngineImpl engine = new SwarmEngineImpl(roles.judge(), shared.store(), shared.scheduler(),
            shared.runtime(), shared.profiles(), gitService, shared.blobSink(), shared.cloudGate(),
            () -> projectRules.renderActive(maxGuidelineChars),
            lookup, expertFactory, frameworkPackages);
        // The folders a worker's container mounts read-only beside its checkout, and its read tool
        // accepts: the same reference roots the knowledge brief names.
        engine.setReferenceRoots(librarian.curator().referenceFolders());
        if (sandboxEnabled) {
            // Wired in whether or not Docker answers right now. When it does not, every candidate
            // fails with KillReason.SANDBOX_UNAVAILABLE and every build stops with a message.
            // Nothing falls back to this PC.
            engine.setSandbox(shared.sandbox());
            if (shared.sandbox().isAvailable()) {
                log.info("Builds in '{}' will run inside the safety box.", project.name());
            } else {
                log.error("Builds in '{}' will stop rather than run: the safety box cannot be "
                    + "made, and running without one is not allowed. See the block above for how "
                    + "to fix it.", project.name());
            }
        } else {
            // sandbox.enabled: false. The engine gets no container; DependencyGraph has switched
            // HostExecution on by name, which is the only reason it will run anything.
            log.warn("Builds in '{}' run with the safety box OFF: the models' commands, their "
                + "tests and the builds of their code run on this PC, as you.", project.name());
        }
        if (swarm != null) {
            engine.setDispatchTuning(swarm.taskGroupsAtOnce(),
                swarm.dispatch() == null ? 0 : swarm.dispatch().staggerMs());
        }
        // The process-wide back-off: fewer workers on a server whose workers keep running out of
        // room, each with more of it. Shared across projects because the server's pool is.
        engine.setConcurrencyController(shared.concurrency());
        // Locked modules reach every worker's toolbox through the dispatcher (setter style, as
        // with the sandbox and dispatch tuning above).
        engine.setProtectedPaths(lockedPaths);
        // House rules that declare a command proving they were obeyed. Resolved from the STORE —
        // the operator's decisions — and never from a candidate's worktree; .swarmcoder/ is in
        // PathPolicy.ALWAYS_PROTECTED at both enforcement points, so no worker can add or alter
        // one (DEVELOPER_CORRECTIONS §13.1, §21).
        engine.setGuidelineChecks(projectRules::activeChecks);
        // The same rules again, as objects, for the JUDGE. Its window holds a fraction of them, so
        // it has to choose which ones to read; handed only the rendered text it could do nothing
        // but take the first few thousand characters, which is how a project with 28,441
        // characters of rules had a tenth of them enforced and the rest silently dropped.
        engine.setActiveRules(projectRules::activeRules);
        // What a WORKER is sent: the rules of the whole project and the rules recorded for the
        // part its task may write (owner's decision 2026-10-07, section 65). A rule's part is
        // checked against the project's object graph, here and when the rule is stated. The two
        // lines above are not narrowed: the judge reads every rule and every check runs.
        projectRules.setParts(() -> com.swarmcoder.knowledge.ProjectParts.of(
            librarian.curator(), projectPath));
        engine.setWorkerRules(paths -> projectRules.briefingFor(paths, maxGuidelineChars));
        // Where an operator's answer to a question about a rule is remembered: every candidate
        // broke the same hard rule, or two disputed it with evidence (harness runs 53 and 55,
        // 2026-10-01). The product asks a person; only an unattended run answers on its own.
        engine.setRuleAmendments(com.swarmcoder.swarm.RuleQuestions.Amendments.over(projectRules));
        this.swarmEngine = engine;

        // Context Ledger — post-run learning: guideline extraction (guidelines.autoPromote,
        // §7 Q4) plus PROPOSED knowledge docs for the Console's Knowledge editor.
        GuidelineExtractor extractor = new GuidelineExtractor(roles.utility(), shared.cloudGate(),
            projectRules, guidelineAutoPromote);
        KnowledgeExtractor knowledgeExtractor =
            new KnowledgeExtractor(roles.utility(), shared.cloudGate(),
                shared.store(), project.id());
        this.contextLedger = new ContextLedger(shared.store(), shared.historyRag(), extractor,
            knowledgeExtractor, extractor != null || knowledgeExtractor != null);

        // Per-role cloud clients for the workflow front half. The architect stamps the
        // config-derived swarm policy (nPerTask/splitAcrossFamilies/temps) on every task.
        // Read-only reference material for the design roles. Without it the Architect designs
        // against frameworks it has never seen — the workers get the API in their knowledge brief,
        // but that is built at PLAN, two phases after the design was already decided.
        // Answered at the ARCHITECT's size, not the workers': the architect is its own cloud model
        // with its own room, and ArchitectClient cuts every result at that room's size — a lookup
        // sized for a 262,144-token worker would lose its whole code half at that cut.
        MaterialBudget architectRoom = MaterialBudget.of(roles.architect());
        ArchitectResearch research = librarian == null ? ArchitectResearch.NONE
            : new ArchitectResearch() {
                @Override
                public String reference(int maxChars) {
                    return librarian.contextApiReference(maxChars);
                }

                @Override
                public String lookupApi(String query) {
                    return librarian.lookupApi(query, architectRoom);
                }

                @Override
                public String searchCode(String query) {
                    return librarian.curator().searchCode(query, 40);
                }

                @Override
                public String readFile(String address) {
                    return librarian.curator().readFile(address, architectRoom.chars(12_000));
                }

                @Override
                public String listFolder(String address) {
                    return librarian.curator().listFolder(address);
                }

                @Override
                public String examples(List<com.swarmcoder.domain.ApiContract> contracts,
                                       String goal, int maxChars) {
                    return librarian.planExamples(contracts, goal, maxChars);
                }
            };

        this.cloudRoles = new CloudRoles(
            new ArchitectClient(roles.architect(), shared.cloudGate(), taskPolicy, research,
                shared.blobSink()),
            new DesignReviewerClient(roles.reviewer(), shared.cloudGate()),
            new TestAuthorClient(roles.testAuthor(), shared.cloudGate(), shared.blobSink()));

        // THE ARCHITECT, THE PLANNER AND THE TEST AUTHOR WORK AS AGENTS (owner decision,
        // 2026-10-02). Each used to get one prompt of pre-selected context and answer in one
        // reply, and nearly every failed live run of the two days before was one of them guessing
        // a fact it could have looked up. Now each works in a session with the expert's read-only
        // lookups over this project and its reference material, the Librarian on demand, the
        // workers' own help desk, and a check of its draft before it hands in; the one reply is
        // what it falls back to. -Dswarmcoder.roles.oneReply=true switches the sessions off.
        if (librarian != null && !Boolean.getBoolean("swarmcoder.roles.oneReply")) {
            LookupAgent roleAgent = new LookupAgent(librarian.curator(), librarian, projectPath,
                shared.cloudGate(), shared.runtime(), shared.scheduler(),
                endpoint -> workerPoolOf(shared.profiles(), endpoint))
                .withExpert(() -> new ExpertDesk(librarian.curator(), projectPath, List.of(),
                    escalationForWorkers).judgedBy(answerJudge));
            cloudRoles.architect().setLookupAgent(roleAgent);
            cloudRoles.testAuthor().setLookupAgent(roleAgent);
            // The reviewer's readings of a design, a plan and tests against the rules look facts
            // up too; what counts as an objection is unchanged.
            cloudRoles.reviewer().setLookupAgent(roleAgent);
            LookupAgent.Limits stops = LookupAgent.Limits.configured();
            log.info("Project '{}': the architect, the planner and the test author work as agents "
                + "- they look facts up ({}), ask the librarian and the expert, and check their "
                + "draft before handing it in. Safety stops per call: {} turns, {} lookups, {} "
                + "expert questions, the same call {} times.", project.name(),
                String.join(", ", roleAgent.lookupNames()), stops.maxTurns(), stops.maxLookups(),
                stops.maxExpertQuestions(), stops.maxRepeats());
        }

        this.workflowEngine = new WorkflowEngine(shared.runtime(), swarmEngine, shared.defaultClient(),
            shared.store(), shared.cloudGate(), isGitRepo ? projectPath : null, cloudRoles, gitService, librarian,
            lockedPaths);
        this.workflowEngine.setContextLedger(contextLedger);
        // The project's standing rules reach the ARCHITECT and the TEST AUTHOR from here, not only
        // the workers (author decision 2026-08-31). Until this line existed, guidelines reached
        // workers alone: the architect invented a source root the build does not compile, and the
        // test author wrote Spring Boot acceptance tests for a project with no Spring in it — and
        // no rule could have stopped either, because neither role was ever shown one. Read from
        // the store on every use, so a rule turned off mid-run stops being sent.
        this.workflowEngine.setProjectRules(() -> {
            String rules = projectRules.renderActive(maxGuidelineChars);
            return rules == null ? "" : rules;
        });
        this.chatClient = roles.chat();
        this.analystClient = roles.requirementsAnalyst();
        this.plannerClient = roles.storyPlanner();
        // The Researcher (author-approved web access — the ONLY web-touching role;
        // workers stay offline per spec §18).
        this.researcher = new ResearcherAgent(roles.utility(),
            shared.cloudGate(), shared.store(), project.id(), librarian.curator(), librarian,
            WebAccess.standard(), shared.mcpServers(), projectPath);
    }

    /** The Console chat coder for this project ({@code roles.chat} → utility fallback). */
    public VllmClient chatClient() {
        return chatClient;
    }

    /** The BRD author for this project ({@code roles.requirementsAnalyst} → chat → utility). */
    public VllmClient analystClient() {
        return analystClient;
    }

    /** The story planner for this project ({@code roles.storyPlanner} → chat → utility). */
    public VllmClient plannerClient() {
        return plannerClient;
    }

    /** The project's Researcher (web + Context7 + sources → PROPOSED knowledge docs). */
    public ResearcherAgent researcher() {
        return researcher;
    }

    /**
     * The locked-down modules in force for a project: the global {@code protectedPaths} UNION the
     * ones its own {@code .swarmcoder/project.yaml} declares.
     *
     * <p>Union, not replacement, and deliberately so. project.yaml lives inside the repository the
     * swarm writes to, so if a project's list replaced the global one, removing a line from a file
     * in the working tree would unlock a module the operator locked for every project — a lock
     * that can be released from inside the thing it is protecting is not a lock. Additive is the
     * only direction that cannot widen access: a project may lock more of itself, never less.
     *
     * <p>Order is stable (global first, then the project's own) and duplicates are collapsed,
     * because this list is also rendered into the workers' shared prompt prefix.
     */
    static List<String> lockedPathsFor(Project project, List<String> globalProtectedPaths) {
        return lockedPathsFor(project, globalProtectedPaths, null, false);
    }

    /**
     * A throwaway {@link GitService} used only to read lock markers, because locks must be resolved
     * before the fields this class builds — including {@link #gitService} itself — exist.
     */
    private static GitService gitForLocks(Project project) {
        String path = project.primaryPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path repo = Paths.get(path);
            return Files.isDirectory(repo.resolve(".git")) ? new GitService(repo) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param git             the project's repository, for reading in-source lock markers at the
     *                        BASE COMMIT; null skips marker collection
     * @param unlockTrustKernel operator override releasing the containment code itself — logged
     *                        loudly, because it is the one setting that can make every other
     *                        guarantee unenforceable
     */
    static List<String> lockedPathsFor(Project project, List<String> globalProtectedPaths,
                                       GitService git, boolean unlockTrustKernel) {
        LinkedHashSet<String> locked = new LinkedHashSet<>();
        if (globalProtectedPaths != null) {
            globalProtectedPaths.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::strip)
                .forEach(locked::add);
        }
        // The code the system's own guarantees rest on. Unconditional: for a project that does not
        // contain these paths they simply match nothing, which is cheaper and far more reliable
        // than trying to detect "is this SwarmCoder editing itself".
        if (unlockTrustKernel) {
            log.warn("TRUST KERNEL UNLOCKED for project '{}' — workers may modify the path policy, "
                + "the verification loader, the secret scanner and the integration audit. A change "
                + "to any of those disables the checks that would catch it.", project.name());
        } else {
            locked.addAll(PathPolicy.TRUST_KERNEL);
        }
        // In-source markers, read from HEAD rather than the working tree: a lock a worker can
        // delete in the same commit that violates it is not a lock.
        if (git != null && git.isEnabled()) {
            List<String> marked = git.grepFilesAtRef(PathPolicy.LOCK_MARKER, "HEAD");
            if (!marked.isEmpty()) {
                log.info("Project '{}' has {} file(s) marked '{}' at HEAD", project.name(),
                    marked.size(), PathPolicy.LOCK_MARKER);
                marked.stream().filter(p -> p != null && !p.isBlank()).forEach(locked::add);
            }
        }
        try {
            ProjectConfig projectConfig = ProjectConfigLoader.load(project.primaryPath());
            if (projectConfig != null) {
                projectConfig.protectedPaths().stream()
                    .filter(p -> p != null && !p.isBlank())
                    .map(String::strip)
                    .forEach(locked::add);
            }
        } catch (Exception e) {
            // An unreadable project.yaml must not silently drop the GLOBAL locks — keep those.
            log.warn("Could not read locked modules from project.yaml for {}: {}",
                project.name(), e.getMessage());
        }
        return List.copyOf(locked);
    }

    /** What a project's own {@code .swarmcoder/project.yaml} says about tool turns per worker. */
    static Integer projectTurnsOf(Project project) {
        try {
            ProjectConfig config = ProjectConfigLoader.load(project.primaryPath());
            return config == null ? null : config.statedMaxToolTurns();
        } catch (Exception e) {
            log.warn("Could not read the turn allowance from project.yaml for {}: {}",
                project.name(), e.getMessage());
            return null;
        }
    }

    /** What a project's own {@code .swarmcoder/project.yaml} says about workers per task. */
    static Integer projectSwarmOf(Project project) {
        try {
            ProjectConfig config = ProjectConfigLoader.load(project.primaryPath());
            return config == null ? null : config.statedWorkersPerTask();
        } catch (Exception e) {
            // An unreadable project.yaml must not stop a run: fall back to the global number and
            // say so, exactly as the locked-modules read above does.
            log.warn("Could not read the worker count from project.yaml for {}: {}",
                project.name(), e.getMessage());
            return null;
        }
    }

    /** Global layer only — kept for callers that have no project in hand. */
    static SwarmPolicy taskPolicyFrom(SwarmEngineConfig swarm) {
        return taskPolicyFrom(swarm, null);
    }

    /**
     * The swarm policy stamped on every planned task, from the {@code swarm:} config block
     * (spec §17) with the project's own number layered over it. Null (no block) means no
     * swarming — never swarm by surprise.
     *
     * <p>The policy carries the REASON for its worker count as well as the count, so the run log
     * and the stored task graph both say which layer won. A number whose origin is invisible is
     * how {@code splitAcrossFamilies} spent months reading as switched on while doing nothing.
     */
    static SwarmPolicy taskPolicyFrom(SwarmEngineConfig swarm, Integer projectWorkersPerTask) {
        if (swarm == null) {
            return null;
        }
        SwarmSizing sizing = SwarmSizing.resolve(null, null, projectWorkersPerTask,
            swarm.statedNPerTask());
        int n = sizing.workersPerTask();
        double tempMin = swarm.tempMin() > 0 ? swarm.tempMin() : 0.2;
        double tempMax = swarm.tempMax() >= tempMin ? swarm.tempMax() : tempMin;
        // Personas: config wins, otherwise the built-in rotation. The old default was
        // List.of("minimal-diff") — one persona handed to every worker in the group, i.e. a
        // diversity lever that did nothing. That mattered less with two model families on the
        // box; with one model it is one of only two levers left.
        List<String> personas = swarm.personaIds() != null && !swarm.personaIds().isEmpty()
            ? List.copyOf(swarm.personaIds()) : WorkerPersonas.DEFAULT_ROTATION;
        return new SwarmPolicy(n, swarm.splitAcrossFamilies(), tempMin, tempMax, personas,
            sizing.label());
    }

    public Project project() { return project; }
    public GitService gitService() { return gitService; }
    public ProjectRules projectRules() { return projectRules; }
    /** A reference source that is a documentation site rather than a folder on this machine. */
    static boolean isDocumentationSite(String entry) {
        String trimmed = entry == null ? "" : entry.strip().toLowerCase(java.util.Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    /**
     * Which role backs the help desk's expert tier: {@code "utility"} when it names its own
     * endpoint, else {@code "architect"} when THAT names its own endpoint, else {@code null} — the
     * desk then gets no escalation at all (ExpertDesk javadoc: null means never escalate). Kept as
     * its own pure function so the decision is testable without building a project.
     */
    static String expertRoleFor(RoleClients roles) {
        if (roles.utilityConfigured()) {
            return "utility";
        }
        if (roles.architectConfigured()) {
            return "architect";
        }
        return null;
    }

    /**
     * The working room the reference material handed to workers is sized by: the SMALLEST of the
     * worker models' rooms as resolved at startup (the figure {@code ServerCapabilities} derived
     * from each server, or the shape's own). The smallest, because one knowledge brief is shared
     * by every worker of a task, whichever family it runs on, and it must fit the tightest of them.
     * No worker models at all is the baseline — today's figures.
     *
     * <p>The back-off controller can later give a throttled family MORE room than this, never
     * less, so sizing to the configured figure is the conservative side.
     */
    /**
     * The scheduler pool of the worker model served at {@code endpoint}, or null when no worker
     * model is served there. A planning role on the workers' own server takes one of that
     * server's places for its session; a role on an endpoint of its own takes none.
     */
    static String workerPoolOf(ModelProfileRegistry profiles, String endpoint) {
        if (profiles == null || endpoint == null) {
            return null;
        }
        String wanted = endpoint.replaceAll("/+$", "").replaceAll("/v1$", "");
        for (ModelProfile worker : profiles.workers()) {
            String served = worker.endpoint() == null || worker.endpoint().baseUrl() == null ? ""
                : worker.endpoint().baseUrl().replaceAll("/+$", "").replaceAll("/v1$", "");
            if (served.equalsIgnoreCase(wanted)) {
                return worker.id();
            }
        }
        return null;
    }

    /**
     * The check of stored answers on the first worker model that costs nothing (a local server),
     * or null when every worker model is paid for - then the desk has no such step.
     */
    static com.swarmcoder.knowledge.StoredAnswerJudge localAnswerJudgeOf(
            ModelProfileRegistry profiles) {
        if (profiles == null) {
            return null;
        }
        for (ModelProfile worker : profiles.workers()) {
            if (worker.costPerMTokens() <= 0 && worker.endpoint() != null
                    && worker.endpoint().baseUrl() != null
                    && !worker.endpoint().baseUrl().isBlank()) {
                return new com.swarmcoder.knowledge.LocalAnswerJudge(new VllmClient(
                    worker.endpoint().baseUrl(), worker.endpoint().apiKey(),
                    worker.endpoint().modelId(), worker.quirks()));
            }
        }
        return null;
    }

    static MaterialBudget workerRoomOf(ModelProfileRegistry profiles) {
        if (profiles == null || profiles.workers().isEmpty()) {
            return MaterialBudget.BASELINE;
        }
        int smallest = Integer.MAX_VALUE;
        for (ModelProfile worker : profiles.workers()) {
            smallest = Math.min(smallest, worker.quirks().workingContextTokens());
        }
        return MaterialBudget.forWorkingContext(smallest);
    }

    /**
     * Package prefixes (group of two segments, e.g. {@code com.zeroz4j}) of everything the
     * Librarian's curator indexed EXCEPT the project's own code — which {@link Librarian} always
     * labels {@code "project"} (see its root list). Handed to every worker so a build error naming
     * a symbol from one of these packages can be told apart from a typo in the worker's own code
     * (see {@code WorkerToolbox#frameworkErrorHint}) and pointed at {@code ask_expert} instead.
     */
    static List<String> frameworkPackagesOf(Librarian librarian) {
        if (librarian == null) {
            return List.of();
        }
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (com.swarmcoder.knowledge.WorkedExamples.Shape shape : librarian.curator().shapes()) {
            if ("project".equals(shape.rootLabel())) {
                continue;
            }
            String pkg = shape.packageName();
            if (pkg == null || pkg.isBlank()) {
                continue;
            }
            String[] parts = pkg.split("\\.");
            prefixes.add(parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0]);
        }
        return List.copyOf(prefixes);
    }

    public Librarian librarian() { return librarian; }
    public ContextLedger contextLedger() { return contextLedger; }
    public CloudRoles cloudRoles() { return cloudRoles; }
    public SwarmEngine swarmEngine() { return swarmEngine; }
    public WorkflowEngine workflowEngine() { return workflowEngine; }
}
