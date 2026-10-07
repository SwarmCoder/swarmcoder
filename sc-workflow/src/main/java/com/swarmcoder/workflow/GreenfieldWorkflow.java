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

import com.swarmcoder.domain.CarriedWarning;
import com.swarmcoder.domain.OpinionPolicy;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.SwarmEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChecksAlreadyProved;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.ReviewVerdict;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.SwarmSizing;
import com.swarmcoder.domain.TurnAllowance;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.UserFacingWording;
import com.swarmcoder.git.AcceptanceOverlay;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.domain.LibraryDoc;
import com.swarmcoder.knowledge.ContextLedger;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.knowledge.LibraryTypes;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.knowledge.ManifestParser;
import com.swarmcoder.knowledge.OfflineLibraryBrief;
import com.swarmcoder.knowledge.RulesVersusManifest;
import com.swarmcoder.lsp.LspServiceFactory;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.AcceptanceCompileErrors;
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.runtime.TestRepairNeeded;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.verify.BrokenAtStartup;
import com.swarmcoder.verify.RedChecker;
import com.swarmcoder.verify.TypeDeliverability;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import com.swarmcoder.inference.EndpointOutage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.serializer.reference.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GreenfieldWorkflow {
    private final AgentRuntime runtime;
    private final SwarmEngine swarmEngine;
    private final VllmClient vllmClient;
    private final ArtifactStore artifactStore;
    private final RunPersister persister;
    private final CloudGate cloudGate;
    private final Path repoPath; // nullable: red-check + test authoring need a target repo
    private final CloudRoles roles;
    private final GitService gitService;
    private final Librarian librarian; // nullable
    /** How this project must be built, rendered from its ACTIVE guidelines; null when unwired. */
    private java.util.function.Supplier<String> projectRules;
    /** Operator-declared locked modules, enforced again on the winning diffs at integration. */
    private final List<String> protectedPaths;
    private ContextLedger contextLedger; // nullable

    /**
     * How many real tries the planner gets before PLAN parks. Each one is a genuine model call
     * (spec §14) — never a silent skip and never a made-up plan standing in for one. Raised from 2
     * to 3 alongside making every retry a real one: the earlier two-attempt loop fed the planner
     * nothing about why attempt 1 was rejected, so attempt 2 was the same question asked again
     * verbatim; now each retry carries the previous reply and the exact objections, so the third
     * attempt is worth having.
     */
    static final int MAX_PLAN_ATTEMPTS = 3;

    /**
     * How many times DESIGN_REVIEW asks the architect to revise a design that conflicts with a
     * stated rule, before the run parks instead (2026-09-04, harness run 19). A design that breaks
     * a rule cannot be planned around — {@link ArchitectClient#plan} tells the planner the rules
     * are not negotiable per task, but that only holds a planner to a design that never asked it to
     * break one in the first place. Smaller than {@link #MAX_PLAN_ATTEMPTS} on purpose: this is one
     * document being corrected against one class of objection, not a plan being decomposed fresh
     * each time.
     */
    static final int MAX_DESIGN_RULE_REVISIONS = 2;

    /**
     * The target repository's real module and source-root layout, read fresh at PLAN time from the
     * operator's checkout. Undetermined when there is no target repository or its build files
     * cannot be read, and every use of it then does nothing at all.
     */
    private BuildLayout.Layout repoLayout() {
        if (repoPath == null) {
            return BuildLayout.read((Path) null, null);
        }
        String toolchain = VerifySpecLoader.load(repoPath).map(VerifySpec::toolchain).orElse(null);
        return BuildLayout.read(repoPath, toolchain);
    }

    /**
     * Which modules of the target repository run only in a browser, read from the operator's
     * checkout like {@link #repoLayout()} (harness run 37, 2026-09-25 — see
     * {@link AcceptanceTestReach}). {@link BrowserOnlyCode.Survey#NONE} when there is no
     * repository or its layout cannot be read; every use of it then changes nothing.
     */
    /** The object graph of the tree runs start from, kept while that tree's HEAD stays put. */
    private com.swarmcoder.knowledge.ReachableCode.Graph startTreeGraph;
    private String startTreeGraphAt;

    private synchronized com.swarmcoder.knowledge.ReachableCode.Graph startTreeGraph() {
        if (repoPath == null || !com.swarmcoder.knowledge.ReachableCode.enabled()) {
            return null;
        }
        String head = null;
        try {
            head = gitService != null && gitService.isEnabled()
                ? gitService.resolveCommit("HEAD") : null;
        } catch (RuntimeException unknown) {
            // no commit to key on: the tree is read again each time
        }
        if (startTreeGraph == null || head == null || !head.equals(startTreeGraphAt)) {
            startTreeGraph = com.swarmcoder.knowledge.ReachableCode.of(repoPath);
            startTreeGraphAt = head;
        }
        return startTreeGraph;
    }

    /**
     * The verdict with one more violation when the plan only adds files nothing the application
     * reaches could use (see {@link PlanConnectsWhatItAdds}); the verdict as it is otherwise.
     */
    private TaskGraphValidator.Verdict connectingWhatItAdds(TaskGraphValidator.Verdict verdict,
                                                           TaskGraph plan) {
        String objection;
        try {
            objection = PlanConnectsWhatItAdds.objection(plan, startTreeGraph(), repoPath);
        } catch (RuntimeException e) {
            return verdict; // what cannot be read is not held against the plan
        }
        if (objection == null) {
            return verdict;
        }
        List<String> violations = new ArrayList<>(verdict.violations());
        violations.add(objection);
        return new TaskGraphValidator.Verdict(violations, verdict.warnings());
    }

    private BrowserOnlyCode.Survey browserOnlySurvey(BuildLayout.Layout layout) {
        return repoPath == null ? BrowserOnlyCode.Survey.NONE : BrowserOnlyCode.survey(repoPath, layout);
    }

    /** The build paragraph the architect reads about browser-only modules; empty when there are none. */
    private String browserOnlyArchitectBrief() {
        BuildLayout.Layout layout = repoLayout();
        BrowserOnlyCode.Survey survey = browserOnlySurvey(layout);
        return AcceptanceTestReach.architectBrief(survey, AcceptanceTestLocation.resolve(layout).module());
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile BuildBoxes buildBoxes;
    // Advisory pre-compile LSP at FINAL_INTEGRATION (spec §S6). On whenever a JDT LS is
    // installed (JdtLsInstall: system property, tools.jdtLsHome, ~/.swarmcoder/tools/jdtls) -
    // since 2026-10-04; it used to need the system property. NONE is a clean no-op. One server
    // for the integration worktree, its store deleted when it is closed.
    private final LspServiceFactory lspFactory =
        LspServiceFactory.forCheckouts(com.swarmcoder.knowledge.ProjectClasspath::jarsOf, false);

    public GreenfieldWorkflow(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore artifactStore, RunPersister persister, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService) {
        this(runtime, swarmEngine, vllmClient, artifactStore, persister, cloudGate, repoPath, roles, gitService, null);
    }

    public GreenfieldWorkflow(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore artifactStore, RunPersister persister, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService, Librarian librarian) {
        this(runtime, swarmEngine, vllmClient, artifactStore, persister, cloudGate, repoPath, roles, gitService, librarian, List.of());
    }

    /**
     * @param protectedPaths operator-declared locked modules (config {@code protectedPaths}),
     *                       handed to {@link FinalIntegrator}: the worker's tools already refuse
     *                       them, but a shell can write what no tool checked, so the winning
     *                       diff is audited again before it is merged
     */
    public GreenfieldWorkflow(AgentRuntime runtime, SwarmEngine swarmEngine, VllmClient vllmClient, ArtifactStore artifactStore, RunPersister persister, CloudGate cloudGate, Path repoPath, CloudRoles roles, GitService gitService, Librarian librarian, List<String> protectedPaths) {
        this.librarian = librarian;
        // The test author is shown one real test from the reference material, on its first
        // prompt and on every repair. See TestAuthorClient.setTestExamples for what that prevents.
        if (librarian != null && roles != null && roles.testAuthor() != null) {
            roles.testAuthor().setTestExamples((task, design) ->
                librarian.testExample(task, design == null ? List.of() : design.contracts()));
        }
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        this.runtime = runtime;
        this.swarmEngine = swarmEngine;
        this.vllmClient = vllmClient;
        this.artifactStore = artifactStore;
        this.persister = persister;
        this.cloudGate = cloudGate;
        this.repoPath = repoPath;
        this.roles = roles != null ? roles : CloudRoles.allOn(vllmClient, cloudGate);
        this.gitService = gitService != null ? gitService : GitService.disabled();
        // The red-check for a wave after the first cannot be made at TEST_AUTHORING: the tree that
        // wave will be built on does not exist until the wave before it has a winner. So the engine
        // asks this workflow the question at the moment it CAN be answered, one wave at a time,
        // against the tree the tasks are about to be cut from.
        if (this.swarmEngine instanceof SwarmEngineImpl engine) {
            engine.setWaveGate((run, wave, waveBase) ->
                redCheckWave(run, scopeFor(run), wave, waveBase));
        }
        // The engine's own container manager and reference roots, as they are when a box is
        // opened. A stand-in engine has none: its red checks are refused unless a test allowed
        // this PC by name.
        this.buildBoxes = this.swarmEngine instanceof SwarmEngineImpl engine
            ? engine.buildBoxes() : BuildBoxes.none();
    }

    /**
     * Replaces where this workflow's builds of model-written code run. For a caller whose swarm
     * engine is not a {@link SwarmEngineImpl} and so brings no container of its own.
     */
    public void setBuildBoxes(BuildBoxes boxes) {
        this.buildBoxes = boxes == null ? BuildBoxes.none() : boxes;
    }

    /**
     * Where the acceptance stage of a red check or a draft compile runs. The command is the
     * operator's; the tests it compiles and runs were written by a model, so it runs in a
     * container that sees this one tree (owner decision, 2026-10-02). One container per tree,
     * reused by every command on that tree and removed by {@link #removeTree}.
     *
     * @throws com.swarmcoder.sandbox.DockerSandboxManager.SandboxException when no container can
     *         be had; never falls back to this PC
     */
    private ExecTarget buildTarget(Path tree) {
        return buildBoxes.use(tree, "The red check");
    }

    /** Removes a throwaway tree, and first the container that was building in it. */
    private void removeTree(Path tree) {
        buildBoxes.release(tree);
        gitService.removeWorktree(tree);
    }

    /** What a stage that could not get a container parks with: the reason, and that nothing ran. */
    private static String noContainerPark(String stage, RuntimeException e) {
        return stage + " could not be run, so the run stops here rather than carry on unchecked. "
            + e.getMessage();
    }

    /** Wires in the project's standing rules — see {@code ProjectContext}, which owns the files. */
    public void setProjectRules(java.util.function.Supplier<String> rules) {
        this.projectRules = rules;
        // A repair of an acceptance test is told the rules too, as the first writing always was.
        if (roles != null && roles.testAuthor() != null) {
            roles.testAuthor().setStandingRules(this::projectRules);
        }
        // And the librarian chooses examples by the types the rules name: the test harness a test
        // must use and the injected type an implementation must use appear nowhere else.
        if (librarian != null) {
            librarian.setProjectRules(this::projectRules);
        }
    }

    public static class LLMTask {
        public String id;
        public String title;
        public String instructions;
    }
    public static class LLMEdge {
        public String from;
        public String to;
    }
    public static class LLMTaskGraph {
        public List<LLMTask> tasks;
        public List<LLMEdge> edges;
    }

    private static final Logger logger =
        LoggerFactory.getLogger(GreenfieldWorkflow.class);

    private Consumer<String> eventLogger;
    public void setEventLogger(Consumer<String> eventLogger) {
        this.eventLogger = eventLogger;
    }

    public void setContextLedger(ContextLedger contextLedger) {
        this.contextLedger = contextLedger;
    }

    private void log(String msg) {
        logger.info(msg);
        if (eventLogger != null) {
            eventLogger.accept("SYSTEM: " + msg);
        }
    }

    /**
     * Drives the run to a terminal state, waiting out model-endpoint outages on the way.
     *
     * <p>The loop distinguishes the two ways a stage can fail to produce what it needed. A refusal —
     * the model answered and the answer was unusable — is handled inside the stage, as before, by
     * degrading or by parking with a question. An outage means the stage never happened: the state is
     * not advanced, the run is marked paused (visibly, on the story's card), and the SAME stage is
     * attempted again until the endpoint returns. No attempt limit, no budget spent, nothing
     * concluded — see {@link OutagePause} for why each of those is the way it is.
     */
    public void advance(Run run) {
        log("GreenfieldWorkflow advancing run: " + run.id() + " in state: " + run.state());
        if (!buildBoxes.contained()) {
            // Said on the Console at every drive of a run, not only once at startup: this is the
            // one fact about a run an operator must not be able to miss.
            com.swarmcoder.domain.HostExecution.allowedBy().ifPresent(by -> log(
                "WARNING: THE SAFETY BOX IS OFF. Code the models write in this run - their "
                + "commands, their tests, the builds of their code - runs on this PC, as you, "
                + "with your files and your network in reach. Allowed by " + by + "."));
        }
        if (run.parkedAt() != null) {
            // Something is taking this run up again — RunResumer at process start, today the only
            // way a parked run is ever handed back to a workflow. Clear the mark immediately, before
            // the stage that parked it is even retried: the card reads STOPPED from this field alone
            // (BuildHealth.of), and leaving it set until the stage finishes would have the card say
            // "stopped" for the whole of a retry that is visibly, actively building.
            log("Run " + run.id() + " was parked (" + run.parkReason() + ") — clearing the mark, "
                + "something is driving it again");
            run.setParkedAt(null);
            run.setParkReason(null);
            // Withdrawn here too, not only on a later successful transition: the stage that parked
            // is about to be tried again AT THE SAME STATE, and if it parks a second time, the same
            // state raises a SECOND BLOCKED_TASK decision. Leaving the first one PENDING through
            // that would show the card two questions where only the newest one is still true.
            withdrawStaleDecisions(run);
            // A test corrected by hand while the run was parked is the test from here on (live
            // run 63: the operator's commit on the tests branch was ignored by the retry).
            String adopted = OperatorCorrectedTests.adopt(gitService, run);
            if (adopted != null) {
                log(adopted);
            }
            run = persister.save(run);
        }
        int outageAttempts = 0;
        // Simple synchronous progression for the virtual thread
        while (run.state() != RunState.DELIVERED && run.state() != RunState.ABORTED) {
            RunState before = run.state();
            Run entered = run;
            // For the stage's calls on this thread (2026-10-02): the expert's answers to a role's
            // questions are remembered with this run's, and a draft design or plan is checked by
            // this run's own mechanical checks before the architect hands it in.
            try (LookupAgent.Scope inRun = LookupAgent.inRun(run.id());
                 ArchitectClient.Scope checking = checkingDrafts(entered)) {
                run = step(run);
            } catch (EndpointOutage outage) {
                // The endpoint went away mid-stage. Persisting the pause is what puts "paused —
                // resuming by itself" on the card; the state deliberately does not move, so the same
                // stage runs again when the endpoint answers.
                run = persister.save(OutagePause.pause(run, outage));
                if (!OutagePause.waitBeforeRetry(run, ++outageAttempts)) {
                    return; // shutting down — the run resumes from this state on next startup
                }
                continue;
            }
            if (run == null) {
                listCarriedWarnings(entered);
                return; // the stage parked the run with a question for the operator
            }
            if (run.state() != before) {
                // Real progress: whatever this run asked about while it was AT the state it just
                // left no longer applies — it got past it. Withdrawing here, keyed to this run's own
                // id, is what stops a card from saying "This build stopped and is asking you
                // something" about a run that is now demonstrably not stopped at all.
                withdrawStaleDecisions(run);
            }
            if (outageAttempts > 0 && run.state() != before) {
                // Progress at last: the endpoint is back, so stop telling the operator it is down.
                log("Endpoint recovered — resuming " + run.id() + " from " + before);
                run = OutagePause.resumed(run);
                outageAttempts = 0;
            }
            // Every transition is durable before the next stage runs (rule R3): crash-resume
            // restarts from the last state, and the Console reads run state from the store.
            run = persister.save(run);
        }
        log("GreenfieldWorkflow finished at: " + run.state());
        listCarriedWarnings(run);
    }

    /**
     * The reviewer's objections the design can answer. The ones about where already-existing
     * code lives are put on the run as warnings here, once each, and left out.
     */
    private List<String> answerableByTheDesign(Run run, List<String> objections,
                                               ExistingProjectTypes onTheStartTree,
                                               Set<String> alreadyCarried) {
        WhereExistingCodeLives.Split split =
            WhereExistingCodeLives.split(objections, onTheStartTree);
        for (String objection : split.carried()) {
            if (!alreadyCarried.add(objection)) {
                continue;
            }
            CarriedWarning warning = new CarriedWarning(WhereExistingCodeLives.CHECK,
                run.state().name(), objection, Instant.now());
            run.carryWarning(warning);
            warn("WARNING CARRIED (the type objected to already exists on the tree this run "
                + "starts from, so no design of this story can move it; the architect is not "
                + "asked to revise over it): " + warning.oneLine());
        }
        return new ArrayList<>(split.forTheArchitect());
    }

    /** Whether nobody is watching this run, so a model's objection at its limit is carried past. */
    private boolean carriesOpinions() {
        return swarmEngine != null
            && swarmEngine.opinionPolicy() == OpinionPolicy.WARN_AND_CARRY_ON;
    }

    /**
     * Records objections from an OPINION check on the run and logs each at WARN, in place of the
     * park they would have caused (owner decision, 2026-10-01 — see {@link OpinionPolicy}). Only
     * ever called for a model's judgement that has already had its bounded correction attempt;
     * a fact check never comes here.
     */
    private void carry(Run run, String check, List<String> objections) {
        for (String objection : objections) {
            CarriedWarning warning = new CarriedWarning(check, run.state().name(), objection,
                Instant.now());
            run.carryWarning(warning);
            warn("WARNING CARRIED (nobody is watching this run, so it is not parked): "
                + warning.oneLine());
        }
    }

    /** Every warning the run carried, said once more where the run stops: nothing is hidden. */
    private void listCarriedWarnings(Run run) {
        List<CarriedWarning> carried = run == null ? List.of() : run.carriedWarnings();
        if (carried.isEmpty()) {
            return;
        }
        warn("Run " + run.id() + " carried " + carried.size() + " warning(s) from checks that "
            + "are a model's opinion. Each would have stopped the run had a person been there "
            + "to ask:");
        for (int i = 0; i < carried.size(); i++) {
            warn("  " + (i + 1) + ". " + carried.get(i).oneLine());
        }
    }

    private void warn(String msg) {
        logger.warn(msg);
        if (eventLogger != null) {
            eventLogger.accept("SYSTEM: " + msg);
        }
    }

    /**
     * Whether a rule objection to these tasks is the free match of a technology the rules forbid by name - a forbidden
     * technology name — a FACT, which stops a run under any policy — rather than the reviewer
     * model's reading, which is an opinion. The two rule reviews return the mechanical finding
     * alone whenever there is one, so this tells which kind their objections were.
     */
    private static boolean namesForbiddenTechnology(String rulesBrief, List<Task> tasks) {
        return rulesBrief != null && !rulesBrief.isEmpty() && tasks != null
            && !ForbiddenTechGuard.check(rulesBrief, tasks).isEmpty();
    }

    /**
     * Runs exactly one stage and returns the run in its next state, or null when the stage parked the
     * run for an operator decision.
     *
     * <p>Split out of {@link #advance} so that "one stage" is a unit that can be retried whole: the
     * outage pause needs something it can attempt again without reasoning about how far into a stage
     * the endpoint died.
     */
    private Run step(Run run) {
        // Which acceptance tests are earlier stories' is read from the run's pinned base commit,
        // and said from the persisted run on every step - so it is said again after a restart,
        // when a repair must still keep them (DEVELOPER_CORRECTIONS section 60).
        if (repoPath != null && run.baseCommit() != null && !run.baseCommit().isBlank()) {
            EarlierAcceptanceTests.pin(repoPath, run.baseCommit());
        }
        switch (run.state()) {
                case INTAKE -> {
                    log("Processing INTAKE...");
                    return transition(run, RunState.DESIGN, run.designId(), run.taskGraphId());
                }
                case DESIGN -> {
                    log("Processing DESIGN...");
                    // Story-scoped when the run came from the backlog: the requirements are the
                    // BRD's, not the architect's, and the architect only reasons about HOW.
                    StoryScope scope = scopeFor(run);
                    // Which modules run only in a browser, by name (harness run 37, 2026-09-25):
                    // a design that makes a TeaVM client type the thing a check is proved through
                    // has written a test nobody can run before the test author has started.
                    String buildBrief = browserOnlyArchitectBrief();
                    if (!buildBrief.isEmpty()) {
                        log("DESIGN: the architect is told which modules run only in a browser, so "
                            + "no check is designed to be proved through them.");
                    }
                    // The project's own existing types, whatever the run's kind (live run 68,
                    // 2026-10-02: a second story was designed as if the repository were empty,
                    // with duplicates of the first story's types in a new package).
                    String existingTypes = existingTypesForArchitect(run, scope);
                    if (!existingTypes.isEmpty()) {
                        log("DESIGN: the architect is told to extend the code this project already has "
                            + "rather than create it again.");
                    }
                    ArchitectClient.ScopedDesign scoped = roles.architect().design(briefFor(run),
                        scope, isAChangeToExistingCode(run), buildBrief + existingTypes);
                    DesignDocument design = scoped.design();
                    if (!scope.isEmpty()) {
                        log("Design scoped to " + scope.story().key() + ": "
                            + scope.requirements().size() + " requirement(s), "
                            + scope.criteria().size() + " criteria, "
                            + scope.gatingNfrs().size() + " inherited quality gate(s).");
                    }
                    if (!scope.constraintBrief().isEmpty()) {
                        // Named separately from the requirement counts because it is a different
                        // kind of thing: nothing here is delivered, and no task is assigned one.
                        log("The architect is told this project's standing rules about how it "
                            + "must be built. No task will be asked to deliver any of them.");
                    }
                    proposeMissingRequirements(run, scoped.missing());
                    if (design == null) {
                        // Minimal fallback: the pipeline continues; the plan works from the raw goal.
                        // Only reached when the architect ANSWERED unusably — an unreachable endpoint
                        // now throws, and this run pauses rather than designing nothing.
                        design = new DesignDocument(UUID.randomUUID(), 1,
                            run.report().summary(), List.of(), List.of(),
                            List.of(), List.of(), null, Instant.now());
                        log("Architect produced no usable design — using minimal design.");
                    } else {
                        log("Design: " + design.requirements().size() + " requirements, "
                            + design.contracts().size() + " contracts, " + design.risks().size() + " risks.");
                    }
                    // A design with no contract naming a type is not buildable for a story that
                    // has checks (2026-09-04, harness run 21): the acceptance tests will touch real
                    // types, and a design that names none reaches PLAN with nothing for a task's
                    // deliversContracts to name, which is how a plan attempt then fails with nothing
                    // for the operator to act on. The contract-vocabulary rule already in the
                    // design prompt is not enough on this model on its own — one more, mechanical
                    // ask, with the story's own checks spelled out, before this parks.
                    if (!scope.isEmpty() && hasNoTypedContract(design)) {
                        String ask = missingContractsSentence(scope);
                        log("DESIGN: the design names no contract with a type, for a story that "
                            + "has checks — asking the architect to name the types");
                        ArchitectClient.ReviseAttempt attempt = roles.architect().revise(design,
                            List.of(ask + CONTRACT_SHAPE_ASK), existingTypes);
                        if (attempt.discarded()) {
                            log("DESIGN — " + attempt.discardReason() + "; keeping the original design");
                        }
                        design = attempt.design();
                        if (hasNoTypedContract(design)) {
                            log("DESIGN — parking run: the design still names no contract with a "
                                + "type for a story that has checks");
                            persistDesign(design);
                            parkRun(run, ask);
                            persister.save(run); // stays in DESIGN; resumable once the design names the types
                            return null;
                        }
                    }
                    persistDesign(design);
                    return transition(run, RunState.DESIGN_REVIEW, design.id(), run.taskGraphId());
                }
                case DESIGN_REVIEW -> {
                    log("Processing DESIGN_REVIEW...");
                    DesignDocument design = artifactStore.root().designs.get(run.designId());
                    if (design != null && !design.requirements().isEmpty()) {
                        // Told the project's rules, and that they outrank the story goal's wording
                        // about how anything is built (harness run 39, 2026-09-25: this review,
                        // told only the goal, objected to a server-side EclipseStore design for
                        // "deviating from" a story that said localStorage, against a rule that
                        // mandates EclipseStore).
                        String reviewRules = scopeFor(run).constraintBrief();
                        // Every revision below is written against the same existing code the
                        // design was shown (live run 68, 2026-10-02).
                        String existingTypes = existingTypesForArchitect(run, scopeFor(run));
                        DesignReviewerClient.Review review =
                            rubricOnly(roles.reviewer().review(design, reviewRules), reviewRules);
                        if (!review.approved && !review.objections.isEmpty()) {
                            // One revision loop, then proceed with recorded objections (spec §14).
                            // Only for THIS rubric — completeness, testability, partitionability.
                            // A rule conflict is not recorded and carried forward; it is handled
                            // below, separately, because "proceed anyway" is the wrong answer to it.
                            log("Design objections: " + review.objections);
                            ArchitectClient.ReviseAttempt attempt =
                                roles.architect().revise(design, review.objections,
                                    existingTypes);
                            if (attempt.discarded()) {
                                // The revision said less than the design it was meant to fix
                                // (2026-09-04, harness run 21) — keep the original rather than let
                                // the next review score an empty rendering as a new, worse problem.
                                log("DESIGN_REVIEW — " + attempt.discardReason()
                                    + "; keeping the original design");
                            }
                            design = attempt.design();
                            review = rubricOnly(roles.reviewer().review(design, reviewRules),
                                reviewRules);
                        }
                        // A design that breaks a stated rule cannot be planned around — it goes back
                        // to the architect here, before PLAN ever sees it (2026-09-04, harness run
                        // 19: a design that named browser-side localStorage against a rule requiring
                        // a server-side object graph reached PLAN unchanged, and three planner
                        // attempts in a row were rejected for a defect none of them could fix from
                        // where they were standing, because the design that fed all three still said
                        // to build it). Same detection {@link #reviewPlanAgainstRules} uses —
                        // {@link ForbiddenTechGuard} for free, then one model call
                        // ({@link DesignReviewerClient#reviewDesign}) — read against the design's
                        // own decisions and contracts instead of a plan's tasks.
                        //
                        // The same loop also holds every contract to types that exist (harness runs
                        // 44/45, 2026-09-27: a contract promised a com.zeroz4j.ui.ListView field,
                        // which ZeroZ Stack has never had, and no worker could deliver it). A
                        // contract reaches its task word for word, so this is the last place it can
                        // be corrected; see ContractsNameRealTypes. Free, and checked first.
                        //
                        // And to contracts that build ON the project's existing types (live run
                        // 68, 2026-10-02: duplicates of the first story's service and data types,
                        // in a new package, passed this review); see ExistingProjectTypes.
                        //
                        // And to contracts the project can deliver at all (live run 67,
                        // 2026-10-02: four contracts in the library's own packages reached PLAN,
                        // where no task can ever create them); see ContractsAreDeliverable.
                        StoryScope ruleScope = scopeFor(run);
                        design = withoutContractsForExistingLibraryTypes(design);
                        List<String> typeObjections = contractTypeObjections(design);
                        // An objection to where a type that already exists lives is carried at
                        // once and never sent to the architect (live run 74: two revisions, 21
                        // minutes, over two classes an earlier story had written). See
                        // WhereExistingCodeLives.
                        ExistingProjectTypes onTheStartTree = ExistingProjectTypes.of(repoPath);
                        Set<String> carriedPlacement = new java.util.LinkedHashSet<>();
                        List<String> ruleObjections = answerableByTheDesign(run,
                            reviewDesignAgainstRules(design, ruleScope), onTheStartTree,
                            carriedPlacement);
                        int revisionAttempt = 0;
                        String lastDiscardReason = null;
                        while ((!ruleObjections.isEmpty() || !typeObjections.isEmpty())
                                && revisionAttempt < MAX_DESIGN_RULE_REVISIONS) {
                            revisionAttempt++;
                            if (!ruleObjections.isEmpty()) {
                                log("Design conflicts with the project's rules: " + ruleObjections
                                    + " — asking the architect to revise (attempt " + revisionAttempt
                                    + " of " + MAX_DESIGN_RULE_REVISIONS + ")");
                            }
                            if (!typeObjections.isEmpty()) {
                                log("Design's contracts name types that do not exist: "
                                    + typeObjections + " — asking the architect to revise (attempt "
                                    + revisionAttempt + " of " + MAX_DESIGN_RULE_REVISIONS + ")");
                            }
                            List<String> feedback = new ArrayList<>(RuleConflictFeedback.enrich(
                                ruleObjections, ruleScope.constraintBrief(), librarian));
                            feedback.addAll(typeObjections);
                            ArchitectClient.ReviseAttempt attempt = roles.architect().revise(design, feedback, existingTypes);
                            lastDiscardReason = attempt.discarded() ? attempt.discardReason() : null;
                            if (attempt.discarded()) {
                                log("DESIGN_REVIEW — " + attempt.discardReason()
                                    + "; keeping the original design (attempt " + revisionAttempt
                                    + " of " + MAX_DESIGN_RULE_REVISIONS + " still counts)");
                            }
                            design = withoutContractsForExistingLibraryTypes(attempt.design());
                            typeObjections = contractTypeObjections(design);
                            ruleObjections = answerableByTheDesign(run,
                                reviewDesignAgainstRules(design, ruleScope), onTheStartTree,
                                carriedPlacement);
                        }
                        // The reviewer model's reading of the rules is an opinion. With nobody
                        // watching, and nothing mechanical also wrong, it is carried as a warning
                        // after its revisions rather than parked on (owner decision, 2026-10-01).
                        // A contract naming a type that does not exist, and a forbidden technology
                        // named outright, are facts and still stop the run below.
                        boolean carried = !ruleObjections.isEmpty() && typeObjections.isEmpty()
                            && carriesOpinions()
                            && !namesForbiddenTechnology(ruleScope.constraintBrief(),
                                List.of(designAsRuleCheckTask(design)));
                        if (carried) {
                            carry(run, "design against the project's rules (reviewer model), after "
                                + MAX_DESIGN_RULE_REVISIONS + " revision(s)", ruleObjections);
                        } else if (!ruleObjections.isEmpty() || !typeObjections.isEmpty()) {
                            StringBuilder brief = new StringBuilder();
                            if (!ruleObjections.isEmpty()) {
                                brief.append("The design still conflicts with the project's stated "
                                    + "rules after " + MAX_DESIGN_RULE_REVISIONS + " revision(s):\n\n  ")
                                    .append(String.join("\n  ", ruleObjections))
                                    .append("\n\nA design that breaks a stated rule cannot be planned "
                                        + "around: no amount of re-planning fixes a design that still "
                                        + "says to do the thing the rule forbids. Fix the design "
                                        + "yourself, or change the rule, then resume the run.");
                            }
                            if (!typeObjections.isEmpty()) {
                                brief.append(brief.isEmpty() ? "" : "\n\n")
                                    .append("The design's contracts still name types that do not "
                                        + "exist, or that no task can create, after "
                                        + MAX_DESIGN_RULE_REVISIONS
                                        + " revision(s):\n\n  ")
                                    .append(String.join("\n  ", typeObjections))
                                    .append("\n\nA contract is handed to its task word for word, so "
                                        + "no plan and no worker can correct it, and no worker can "
                                        + "deliver a member whose type does not exist. Fix the "
                                        + "contract in the design yourself, then resume the run. If "
                                        + "the type does exist in the version of the library this "
                                        + "project builds against, the reference checkout is a "
                                        + "different version: point it at the right one.");
                            }
                            if (lastDiscardReason != null) {
                                brief.append("\n\nThe last revision attempt was rejected before it "
                                    + "could even be reviewed: ").append(lastDiscardReason).append('.');
                            }
                            log("DESIGN_REVIEW — parking run: the design still "
                                + (ruleObjections.isEmpty() ? "" : "conflicts with the project's rules: "
                                    + ruleObjections + (typeObjections.isEmpty() ? "" : "; and it still "))
                                + (typeObjections.isEmpty() ? "" : "names types that do not exist: "
                                    + typeObjections));
                            persistDesign(withVerdict(design, ReviewVerdict.NEEDS_WORK));
                            parkRun(run, brief.toString());
                            persister.save(run); // stays in DESIGN_REVIEW; resumable once the design is fixed
                            return null;
                        }
                        design = withVerdict(design, review.approved && !carried
                            ? ReviewVerdict.APPROVED
                            : ReviewVerdict.NEEDS_WORK);
                        log("Design review: " + design.review()
                            + (review.objections.isEmpty() ? "" : " — recorded objections: " + review.objections));
                        persistDesign(design);
                    }
                    return transition(run, RunState.PLAN, run.designId(), run.taskGraphId());
                }
                case PLAN -> {
                    log("Processing PLAN...");
                    // Reject + regenerate on invariant violation (spec §14): up to MAX_PLAN_ATTEMPTS
                    // real LLM attempts, each fed the previous reply and exactly why it was
                    // rejected. There is no fallback plan any more — a plan that claims a slice it
                    // did not actually decompose is worse than a stop that says why (2026-09-03: a
                    // single fabricated "Implement Goal" task reached TEST_AUTHORING with an empty
                    // write set and no acceptance criteria, and parked three stages later for a
                    // reason that had nothing to do with the real problem).
                    DesignDocument design = artifactStore.root().designs.get(run.designId());
                    StoryScope planScope = scopeFor(run);
                    // A contract in a package no task's write set can ever create is the DESIGN's
                    // fault, and no planner attempt can repair it (live run 67, 2026-10-02: six
                    // attempts and two parks over four contracts in the library's packages).
                    // DESIGN_REVIEW now refuses such a design, so this only fires for one that was
                    // approved before that check existed or before the library's source was
                    // there to read (a resumed run, a kept snapshot). Free, and before the first
                    // planner call. Bounded: DESIGN_REVIEW passes a design on only when this very
                    // check is clean, and parks after its own revision cap otherwise — so the
                    // run comes back here at most once with nothing left to object to.
                    if (design != null) {
                        DesignDocument asStored = design;
                        design = withoutContractsForExistingLibraryTypes(design);
                        List<String> undeliverable = new ArrayList<>(
                            ContractsAreDeliverable.check(design, ProjectTypes.of(repoPath),
                                librarian == null ? LibraryTypes.NONE : librarian.libraryTypes())
                                .objections());
                        // A duplicate of an existing type is the design's fault in the same way
                        // (live run 68, 2026-10-02), and DESIGN_REVIEW holds a design to it too.
                        undeliverable.addAll(
                            ExistingProjectTypes.of(repoPath).duplicateObjections(design));
                        if (undeliverable.isEmpty()) {
                            if (design != asStored) {
                                persistDesign(design);
                            }
                        } else if (!design.requirements().isEmpty()) {
                            log("PLAN: the design has contract(s) no task can deliver — this is the "
                                + "design's fault, not a plan's, so it goes back to DESIGN_REVIEW "
                                + "instead of to the planner: " + undeliverable);
                            return transition(run, RunState.DESIGN_REVIEW, run.designId(),
                                run.taskGraphId());
                        } else {
                            // DESIGN_REVIEW passes a design with no requirements straight through,
                            // so sending this one back would only bring it here again.
                            log("PLAN — parking run: the design, not the plan, is at fault: "
                                + undeliverable);
                            parkRun(run, "The design is at fault, not the plan: it has contracts "
                                + "no task can deliver, so the planner was not asked.\n\n  "
                                + String.join("\n  ", undeliverable)
                                + "\n\nFix the design's contracts, then resume the run.");
                            persister.save(run); // stays in PLAN; resumable once the design is fixed
                            return null;
                        }
                    }
                    TaskGraph tg = null;
                    // Where this repository actually compiles from. The planner used to be told
                    // nothing about it and guessed a conventional layout, which is how a run once
                    // planned every task into a src/main/java at the root of a repository whose
                    // root pom only aggregates modules. Read once, used twice: the planner is shown
                    // it, and the validator holds the plan to it.
                    BuildLayout.Layout layout = repoLayout();
                    // Where the acceptance tests can actually be compiled and run, read from the
                    // same layout. Every task carries it, and three things then agree because they
                    // are one string: what the test author is told to write, what the path policy
                    // protects, and what the acceptance command selects.
                    AcceptanceTestLocation.Location acceptance =
                        AcceptanceTestLocation.resolve(layout);
                    // And which modules no acceptance test can ever run (harness run 37).
                    BrowserOnlyCode.Survey browserOnly = browserOnlySurvey(layout);
                    String layoutBrief = RepoLayoutBrief.render(layout, browserOnly, acceptance.module());
                    // One validator for the whole stage, told the same survey: every attempt, the
                    // last-attempt drop and the re-check of the stored plan must agree on which
                    // dependency edges count (harness runs 44/45, 2026-09-27 — an edge onto a task
                    // that uses none of an enabler's browser-only types does not).
                    TaskGraphValidator planValidator = new TaskGraphValidator(browserOnly);
                    if (layout.determined()) {
                        log("PLAN: this repository compiles from " + layout.sourceRoots());
                    } else {
                        log("PLAN: the repository's source roots could not be read — "
                            + layout.note() + " Write-set paths are not checked against the build.");
                    }
                    log("PLAN: " + acceptance.note());
                    if (browserOnly.any()) {
                        log("PLAN: " + browserOnly.browserOnlyDirs() + " run only in a browser; no "
                            + "acceptance test can execute their code, and the planner is told so.");
                    }
                    // Carried across attempts so a park after the last one can say exactly what the
                    // planner last did — never a stale attempt-1 reason once a later attempt said
                    // something different, and never silence when an attempt failed outright.
                    String previousReply = null;
                    List<String> lastObjections = List.of();
                    String lastReplyRef = null;
                    String lastFailureReason = null;
                    // Every objection of the attempts BEFORE the previous one (harness run 40,
                    // 2026-09-26): attempt 3 was shown only attempt 2's objection, fixed it by
                    // going back towards attempt 1, and brought attempt 1's objection back word for
                    // word. The planner is now told what it must not bring back, and which of the
                    // previous attempt's objections it had already been given once.
                    List<String> earlierObjections = new ArrayList<>();
                    for (int attempt = 1; attempt <= MAX_PLAN_ATTEMPTS && tg == null; attempt++) {
                        ArchitectClient.PlanAttempt result = roles.architect().planAttempt(
                            design, briefFor(run), planScope, layoutBrief,
                            previousReply, lastObjections, List.copyOf(earlierObjections));
                        earlierObjections.addAll(lastObjections);
                        boolean lastTry = attempt == MAX_PLAN_ATTEMPTS;
                        if (result.graph() == null) {
                            lastObjections = List.of();
                            lastReplyRef = result.replyRef();
                            lastFailureReason = result.failureReason();
                            previousReply = result.rawReply();
                            log("PLAN attempt " + attempt + ": the architect's call failed: "
                                + result.failureReason() + (lastTry ? " — parking" : " — retrying"));
                            continue;
                        }
                        TaskGraph candidate = result.graph();
                        withAcceptanceTestDir(candidate, acceptance.protectedDir());
                        // The build file of every module a task may write is part of that task's
                        // job (2026-09-03). Before validation, so what is checked is what runs.
                        BuildFilesInTheJob.expandWriteSets(candidate, layout, repoPath);
                        // And can the plan connect what it adds to the application that is
                        // there (2026-10-05)? See PlanConnectsWhatItAdds.
                        TaskGraphValidator.Verdict verdict = connectingWhatItAdds(
                            planValidator.validate(candidate, planScope, layout, design, repoPath),
                            candidate);
                        verdict.warnings().forEach(w -> log("PLAN warning: " + w));
                        if (!verdict.ok() && lastTry) {
                            // The last attempt's only fault is work no check needs: drop it rather
                            // than park the run over it (harness run 40, 2026-09-26). Safe cases
                            // only, and the smaller plan must pass every check on its own — see
                            // UnusedEnablers for why, and for when it refuses.
                            UnusedEnablers.Outcome drop = UnusedEnablers.dropIfSafe(
                                candidate, verdict, planScope, design, planValidator);
                            if (drop.droppedAny()) {
                                TaskGraphValidator.Verdict after = planValidator.validate(
                                    drop.graph(), planScope, layout, design, repoPath);
                                if (after.ok()) {
                                    for (String line : drop.dropped()) {
                                        log("PLAN attempt " + attempt + ": " + line);
                                    }
                                    candidate = drop.graph();
                                    verdict = after;
                                } else {
                                    log("PLAN attempt " + attempt + ": did not drop the unused "
                                        + "task(s) — the plan without them still fails: "
                                        + after.violations());
                                }
                            } else if (drop.refusal() != null) {
                                log("PLAN attempt " + attempt + ": " + drop.refusal());
                            }
                        }
                        if (verdict.ok()) {
                            // Shape is fine; does the plan itself contradict the rules it was told
                            // to build by (2026-09-03 addendum — harness run 10)? A design that
                            // never mentions browser storage can still decompose into a
                            // structurally sound plan whose second wave instructs a worker to add
                            // it, and neither the shape validator nor DESIGN_REVIEW ever reads a
                            // task's own instructions against a rule.
                            List<String> ruleObjections = reviewPlanAgainstRules(candidate, planScope);
                            if (ruleObjections.isEmpty()) {
                                tg = candidate;
                            } else if (lastTry && carriesOpinions() && !namesForbiddenTechnology(
                                    planScope.constraintBrief(), candidate.tasks())) {
                                // The plan's shape passed every mechanical check; what is left
                                // is the reviewer model's reading of the rules, on the last
                                // attempt, with nobody watching. Carried, not parked on.
                                carry(run, "plan against the project's rules (reviewer model), "
                                    + "on plan attempt " + attempt + " of " + MAX_PLAN_ATTEMPTS,
                                    ruleObjections);
                                tg = candidate;
                            } else {
                                lastReplyRef = result.replyRef();
                                lastFailureReason = null;
                                previousReply = result.rawReply();
                                log("PLAN attempt " + attempt + " rejected against the project's "
                                    + "rules: " + ruleObjections
                                    + (lastTry ? " — parking" : " — regenerating with objections fed back"));
                                // The raw objection names the rule; it does not say what the rule
                                // actually requires or show a working example of doing it — which
                                // is why three attempts against the same rule used to come back
                                // with the same defect worded three different ways (2026-09-04,
                                // harness run 19). The next attempt (and, if there is none, the
                                // park brief) reads the enriched version.
                                lastObjections = RuleConflictFeedback.enrich(ruleObjections,
                                    planScope.constraintBrief(), librarian);
                            }
                        } else {
                            lastObjections = verdict.violations();
                            lastReplyRef = result.replyRef();
                            lastFailureReason = null;
                            previousReply = result.rawReply();
                            log("PLAN attempt " + attempt + " rejected: " + verdict.violations()
                                + (lastTry ? " — parking" : " — regenerating with objections fed back"));
                        }
                    }
                    if (tg == null) {
                        String objectionsText = !lastObjections.isEmpty()
                            ? String.join("; ", lastObjections)
                            : (lastFailureReason != null ? lastFailureReason
                                : "the planner never returned a usable reply");
                        String replySentence = lastReplyRef != null
                            ? " Its last reply is kept as blob " + lastReplyRef + "." : "";
                        String brief = "The planner could not produce a plan that passes the checks "
                            + "after " + MAX_PLAN_ATTEMPTS + " attempts. Last objections: "
                            + objectionsText + "." + replySentence
                            + " Build it again to try once more, or change the story.";
                        log("PLAN — parking run: the planner could not produce a plan that passes "
                            + "the checks after " + MAX_PLAN_ATTEMPTS + " attempts");
                        parkRun(run, brief);
                        persister.save(run); // stays in PLAN; resumable once the plan is fixed
                        return null;
                    }

                    // Each worker is told, by fully-qualified name, the types its code uses that
                    // another task of this plan writes, and which task that is (harness run 39,
                    // 2026-09-25: a worker told only to "deliver BooksService{List<Book> ...}"
                    // went looking for Book in a package the knowledge brief's worked example
                    // happened to use, not the one task A was writing it into). On the accepted
                    // plan only, so a rejected attempt never carries the block into the next one.
                    for (String line : TypeDependencyOrder.annotate(tg, design)) {
                        log("PLAN: " + line);
                    }

                    // What this build declares, and what it COULD declare offline. Read once here
                    // and used twice: to turn a rule that names a missing dependency into work,
                    // and to tell every worker which libraries it may add.
                    BuildFacts build = readBuild(layout);
                    BuildFilesInTheJob.Outcome dependencies =
                        declareMissingDependencies(run, planScope, layout, build, tg);
                    if (dependencies.parks()) {
                        log("PLAN — parking run: the project's rules name a dependency that is "
                            + "not in the offline Maven repository, which no worker can fix");
                        parkRun(run, dependencies.parkBrief());
                        persister.save(run); // stays in PLAN; resumable once the artifact is there
                        return null;
                    }
                    tg = dependencies.graph();
                    for (BuildFilesInTheJob.Declaration declaration : dependencies.declarations()) {
                        log("PLAN: the plan will declare " + declaration.coordinate() + " in "
                            + declaration.buildFile() + " (version from " + declaration.managedBy()
                            + ") — " + declaration.reason());
                    }

                    tg = attachKnowledgeBriefs(run, tg, offlineLibraries(build));
                    // The last of the three sizing layers. The project's and the settings file's
                    // numbers were already resolved when this project's engine was built; the
                    // story's own number can only be applied here, because this is the first
                    // moment in a run where a story exists.
                    tg = withStorySizing(tg, planScope);
                    tg = withStoryTurnAllowance(tg, planScope);
                    final TaskGraph persistedGraph = tg;
                    try {
                        artifactStore.append(() -> {
                            artifactStore.root().taskGraphs.put(persistedGraph.id(), persistedGraph);
                            return null;
                        }).get();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to persist TaskGraph", e);
                    }

                    // The graph that was CHECKED must be the graph that is STORED. It was not:
                    // between the check above and this line the plan passes through two
                    // post-processing steps, and one of them used to rebuild every task through a
                    // constructor that carried none of its criterion, requirement or story links.
                    // The planner was right, the validator was right, and the run still reached the
                    // test author with nothing to test (run c7d6bcad, 2026-08-30).
                    //
                    // A violation that appears only here is therefore a defect in OUR
                    // post-processing, not in the planner: regenerating would produce another good
                    // plan and break it the same way. So the run stops and names the difference
                    // rather than dispatching a swarm at work nothing can prove.
                    TaskGraphValidator.Verdict stored =
                        planValidator.validate(persistedGraph, planScope, layout, design, repoPath);
                    if (!stored.ok()) {
                        String brief = "The plan was accepted and then damaged before it was "
                            + "stored. These are true of the stored plan and were not true of the "
                            + "plan that was checked:\n\n  " + String.join("\n  ", stored.violations())
                            + "\n\nNothing the planner did caused this, so re-planning will not "
                            + "help. This is a defect in SwarmCoder's own PLAN stage.";
                        log("PLAN — parking run: the stored plan no longer satisfies the "
                            + "invariants the accepted plan did: " + stored.violations());
                        parkRun(run, brief);
                        persister.save(run); // stays in PLAN; resumable once the defect is fixed
                        return null;
                    }

                    return transition(run, RunState.TEST_AUTHORING, run.designId(), persistedGraph.id());
                }
                case TEST_AUTHORING -> {
                    log("Processing TEST_AUTHORING...");
                    // The test author writes executable acceptance tests into the protected
                    // dir BEFORE any worker runs (spec §14) — committed on the run's OWN ref,
                    // swarm/tests/<runId>, cut from the pinned base. Not on the delivery branch:
                    // the run was pinned three stages ago, so a commit there is one no worktree
                    // of this run can see, and one that outlives the run. Each candidate is given
                    // exactly the tests its task claims at verification (AcceptanceOverlay), and
                    // the integration branch descends from this commit, so the tests reach the
                    // delivery branch only with the code that makes them pass.
                    // …and reports which test it wrote for which check. A check pointing at a test
                    // nobody wrote can never be proved, and this is the cheap moment to find out:
                    // no worker has run yet. The reference is NOT written back — see
                    // AuthoredTestAudit for why a silent repair is worse than the mistake.
                    String unwritten = authorAcceptanceTests(run);
                    if (unwritten != null) {
                        log("TEST_AUTHORING — parking run: the tests written do not match the "
                            + "checks the plan claims");
                        parkRun(run, unwritten);
                        persister.save(run); // stays in TEST_AUTHORING; resumable after the fix
                        return null;
                    }
                    // Mechanical red-check (spec §14), per task, on the SAME tree each task's
                    // candidates will be verified on: the pinned base plus that task's own tests.
                    // A task whose tests already pass there has no signal to select on; park.
                    String notRed = redCheckFailure(run, scopeFor(run));
                    if (notRed != null) {
                        log("Red-check FAILED — parking run: " + notRed);
                        parkRun(run, "The acceptance tests for run " + run.id()
                            + " are not in a valid red state:\n\n" + notRed
                            + "\n\nAcceptance tests must FAIL on the pre-change tree before the "
                            + "swarm dispatches (spec §14). The tests are on branch "
                            + testsBranch(run) + ". Fix the check in the Requirements editor, "
                            + "or the plan, then resume the run — the tests are written again.");
                        persister.save(run); // stays in TEST_AUTHORING; resumable after the fix
                        return null;
                    }
                    // And the journeys' red check (section 63): each must fail in a real browser
                    // on the application as the run found it. It is also where "the application
                    // cannot be started" or "there is no browser" is found - before the swarm.
                    String journeysNotRed = journeyRedCheck(run);
                    if (journeysNotRed != null) {
                        log("Red-check of the journeys FAILED — parking run: " + journeysNotRed);
                        parkRun(run, journeysNotRed);
                        persister.save(run); // stays in TEST_AUTHORING; resumable after the fix
                        return null;
                    }
                    return transition(run, RunState.EXECUTING, run.designId(), run.taskGraphId());
                }
                case EXECUTING -> {
                    log("Processing EXECUTING...");
                    if (run.nothingToBuild()) {
                        log("EXECUTING - nothing is built: every check of this run was already "
                            + "satisfied by the code it started from, so no worker is started. "
                            + "The unchanged tree is verified next.");
                        return transition(run, RunState.FINAL_INTEGRATION, run.designId(),
                            run.taskGraphId());
                    }
                    // Throws EndpointOutage when a task's whole wave never reached a model, so the
                    // swarm is re-dispatched after the pause instead of the repair round being spent
                    // on candidates that produced no evidence. Tasks that already have a winner are
                    // not dispatched again.
                    try {
                        run = swarmEngine.executeRun(run);
                    } catch (RunMustPark park) {
                        // A wave's winners would not merge onto each other, the tree they made
                        // together would not compile, or a later wave's tests were not red on the
                        // tree that wave is cut from. None of those is about one task, so none of
                        // them can be recorded against one: the run stops here, keeps every winner
                        // it already has, and resumes at this same stage once the operator has
                        // settled it. Nothing is re-swarmed.
                        log("EXECUTING - parking run: " + park.getMessage());
                        if (park.questionAlreadyRaised()) {
                            // Tasks waiting on a BLOCKED task (harness run 39, 2026-09-25): the
                            // blocked task already raised the one question that matters, so the run
                            // is marked stopped behind it and no second question is added.
                            run.setParkedAt(Instant.now());
                            run.setParkReason(park.brief());
                        } else {
                            parkRun(run, park.brief());
                        }
                        persister.save(run); // stays in EXECUTING; resumable after the fix
                        return null;
                    } catch (TestRepairNeeded fault) {
                        // Every candidate of one task died on a bug in the acceptance test's OWN
                        // code (author decision, 2026-09-05) — a worker can never fix that, since it
                        // may not edit that file. Sent back to the test author here, once; on
                        // success the run simply advances (EXECUTING is retried, and the swarm
                        // engine's own idempotence check skips every task that already has a
                        // winner); on failure the run parks, exactly as RunMustPark does above.
                        log("EXECUTING - " + fault.getMessage());
                        String park = repairFaultyAcceptanceTest(run, fault);
                        if (park != null) {
                            park += OperatorCorrectedTests.whereToCorrect(run);
                            log("EXECUTING - parking run: " + park);
                            parkRun(run, park);
                            persister.save(run); // stays in EXECUTING; resumable after the fix
                            return null;
                        }
                        return run; // stays EXECUTING; the next loop turn re-enters executeRun
                    }
                    return transition(run, RunState.FINAL_INTEGRATION, run.designId(), run.taskGraphId());
                }
                case FINAL_INTEGRATION -> {
                    log("Processing FINAL_INTEGRATION...");
                    FinalIntegrator.Result integration =
                        new FinalIntegrator(gitService, artifactStore, lspFactory, protectedPaths, buildBoxes)
                            .integrate(run);
                    if (!integration.ok() && repairAfterFailedJourney(run, integration)) {
                        // Stays in FINAL_INTEGRATION: the run is merged and verified again with
                        // the repaired candidate, and the journeys are made again.
                        return run;
                    }
                    if (!integration.ok()) {
                        log("Integration FAILED — parking run: " + integration.failure());
                        blockStoryOnIntegrationFailure(run, integration);
                        parkRun(run, "FINAL_INTEGRATION failed on branch "
                            + integration.integrationBranch() + ":\n\n" + integration.failure());
                        persister.save(run); // stays in FINAL_INTEGRATION; resumable after the fix
                        return null;
                    }
                    if (run.nothingToBuild()) {
                        log("NOTHING WAS BUILT: every check of this story was already satisfied "
                            + "by the code the run started from. The unchanged tree was verified "
                            + "once with the acceptance tests " + run.alreadySatisfiedTests()
                            + " on " + run.verificationPoint() + (integration.verification()
                                    == null ? "" : " and they passed")
                            + "; each criterion is stamped from that run and the story is "
                            + "delivered as it stands"
                            + (integration.integrationBranch() == null ? "."
                                : " (branch " + integration.integrationBranch()
                                    + " carries the tests).") );
                    } else if (integration.integrationBranch() != null) {
                        log("Integrated run onto branch " + integration.integrationBranch());
                    }
                    // Quality gates, then delivery. Evaluated AFTER integration because that is
                    // where the merged tree is verified — a gate can only be judged against the
                    // code that would actually ship.
                    boolean deliveredForJudgment = recordDelivery(run, integration);
                    // Context Ledger (spec §12.4): mine this run's sessions for durable
                    // guidelines (PROPOSED — they never touch prompts until promoted).
                    if (contextLedger != null) {
                        List<String> learned = contextLedger.extractGuidelines(run.id());
                        if (!learned.isEmpty()) {
                            log("Learned " + learned.size() + " PROPOSED guideline(s): " + learned);
                        }
                    }
                    // The build ends here, one way or the other. It does NOT park at a gate of its
                    // own (UX v3 §2.3): the only remaining question — "is this what I asked for?" —
                    // belongs to the story, which is already sitting in REVIEW carrying it, with the
                    // buttons that answer it on its own card. The old second gate had no button
                    // anywhere that could open it, which is how 37 runs parked here for a day.
                    return transition(run,
                        deliveredForJudgment ? RunState.DELIVERED : RunState.ABORTED,
                        run.designId(), run.taskGraphId());
                }
                case APPROVAL -> {
                    // A run persisted by an older build, parked at the gate that no longer exists.
                    // Its story was already moved to REVIEW or BLOCKED before it got here, so the
                    // operator's question is intact — only the run's dead-end state needs retiring.
                    log("Retiring the abolished APPROVAL park for run " + run.id());
                    return transition(run, RunState.DELIVERED, run.designId(), run.taskGraphId());
                }
                default -> {
                    log("Unhandled state: " + run.state());
                    return null;
                }
        }
    }

    /**
     * A state transition that keeps everything the run already knows.
     *
     * <p>This used to rebuild the run field by field through a constructor that carried neither the
     * project nor the story, so a run silently lost both on its very first transition. The copy now
     * lives on {@link Run#withState} — one implementation, no list of fields for a future
     * transition to forget, and no constructor left that can build a run without its project.
     */
    private static Run transition(Run run, RunState state, UUID designId, UUID taskGraphId) {
        return run.withState(state, designId, taskGraphId);
    }

    /**
     * The run's goal as the design roles should read it: the operator's words plus what the KIND of
     * run demands of the evidence. See {@link RunBrief} — it is the only difference between a
     * feature run, a bugfix run and a refactor run, and the reason those are one path and not three.
     */
    private String briefFor(Run run) {
        return RunBrief.forKind(run.kind(), goalOf(run), neighbourhoodBriefOf(run));
    }

    /**
     * The types this project's checkout already has, as the architect reads them for a design and
     * for every revision of one — whatever the run's kind (live run 68, 2026-10-02: a new-build run
     * on a repository that held an earlier story's code was told nothing of it). "" for an empty
     * repository, so a first story's prompts are what they always were.
     */
    private String existingTypesForArchitect(Run run, StoryScope scope) {
        if (repoPath == null) {
            return "";
        }
        ExistingProjectTypes existing = ExistingProjectTypes.of(repoPath);
        if (existing.isEmpty()) {
            return "";
        }
        if (roles.architect().worksAsAnAgent()) {
            // The architect looks types up in the project map and the tree queries; only the
            // rules about extending existing code are put in its prompt (section 54).
            return existing.architectRules();
        }
        StringBuilder about = new StringBuilder(goalOf(run));
        if (scope != null && !scope.isEmpty()) {
            for (BrdRequirement requirement : scope.requirements()) {
                about.append('\n').append(requirement.title()).append(' ').append(requirement.text());
            }
            for (AcceptanceCriterion criterion : scope.criteria()) {
                about.append('\n').append(criterion.text()).append(' ')
                    .append(criterion.testClassOrFile());
            }
        }
        int chars = ExistingProjectTypes.INVENTORY_CHARS;
        try {
            chars = roles.architect().room().chars(chars);
        } catch (RuntimeException noRoom) {
            // the baseline size, then
        }
        return existing.architectBrief(about.toString(), chars);
    }

    /** The operator's own words, which for a change request is the report verbatim. */
    private static String goalOf(Run run) {
        return run.report() == null || run.report().summary() == null ? ""
            : run.report().summary();
    }

    /**
     * Whether this run changes a repository that already exists, rather than building one.
     *
     * <p>The run's KIND is the whole test, and it is the right one: {@code BUGFIX} and
     * {@code ENHANCEMENT} are the two kinds a change request becomes (design §1.2), and a bugfix
     * against the demo project is as much a change to code that already exists as a bugfix against
     * jsoup. A GREENFIELD or REFACTOR run is untouched, so nothing a greenfield build sends changes
     * by a character.
     */
    private static boolean isAChangeToExistingCode(Run run) {
        return run != null && (run.kind() == WorkflowKind.BUGFIX
            || run.kind() == WorkflowKind.ENHANCEMENT);
    }

    /**
     * The neighbourhood of this change, read out of the repository — or "" for anything else.
     *
     * <p>Computed rather than carried, because the two things it needs are already here: the
     * request's own words (the run's goal) and the structural index the Librarian's curator
     * already builds and caches over the project root. It is asked for once per design or plan
     * attempt; a warm index answers in a fraction of a second and a cold one is a one-off parse
     * that {@code lookup_api} would have paid for anyway.
     *
     * <p><b>Fails open in every direction.</b> No librarian, a greenfield run, an index that could
     * not parse this target, a report naming nothing the repository declares — every one of those
     * gives "", and every brief is then byte-for-byte what it was before this existed.
     */
    private String neighbourhoodBriefOf(Run run) {
        if (librarian == null || !isAChangeToExistingCode(run)) {
            return "";
        }
        return librarian.neighbourhoodOf(changeRequestOf(run)).brief();
    }

    /** What the Librarian's neighbourhood channel is asked about, or null for a greenfield run. */
    private Librarian.ChangeRequest changeRequestOf(Run run) {
        if (!isAChangeToExistingCode(run)) {
            return null;
        }
        return new Librarian.ChangeRequest(goalOf(run), neighbourhoodChars);
    }

    /**
     * The ceiling on the neighbourhood in a worker's brief, in characters. 0 means
     * {@link com.swarmcoder.knowledge.ChangeNeighbourhood#MAX_CHARS}, which design §2.2 measured as
     * affordable (2.9% of a 51,200-token working context).
     *
     * <p>Settable because a smaller server gives a smaller window, and 6,000 characters is a bigger
     * share of that window than the design budgeted for. A caller that has actually discovered what
     * its endpoint serves states the size here; nobody who has not is asked to guess.
     */
    private int neighbourhoodChars;

    /** @see #neighbourhoodChars */
    public void setNeighbourhoodChars(int chars) {
        this.neighbourhoodChars = Math.max(0, chars);
    }

    /**
     * Records what the run delivered and applies the story's quality gates.
     *
     * @return true when the story came back for the operator's judgment (or there is no story to
     *         judge); false when the build did not deliver — a failed gate or an unsatisfied
     *         criterion — in which case the story is BLOCKED and carries the reason
     */
    /**
     * A journey of this run failed in the browser after the last merge (section 63): the task
     * that claims it goes back to the workers ONCE, in an ordinary repair round with the failing
     * step as its evidence. Journeys are made at final integration only (owner's decision,
     * 2026-10-05), so this is where a screen nobody can use is first seen, and the first place it
     * can be repaired from.
     *
     * @return true when a repaired candidate passed verification and is the task's choice now -
     *         the integration is then made again; false when the run stops on the failure
     */
    private boolean repairAfterFailedJourney(Run run, FinalIntegrator.Result integration) {
        FinalIntegrator.JourneyFailure failed = integration.journeyFailure();
        if (failed == null || failed.taskId() == null
                || !(swarmEngine instanceof SwarmEngineImpl engine)) {
            return false;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        Task task = graph == null ? null : graph.tasks().stream()
            .filter(t -> t.id().equals(failed.taskId())).findFirst().orElse(null);
        if (task == null) {
            return false;
        }
        if (task.journeyRepairAttempted()) {
            log("FINAL_INTEGRATION: the journey of task '" + task.title() + "' failed again "
                + "after its one repair round; the run stops on it.");
            return false;
        }
        log("FINAL_INTEGRATION: a journey of task '" + task.title() + "' failed in the browser. "
            + "The task goes back to the workers once, with the failing step:\n"
            + failed.evidence());
        CandidateSolution repaired = null;
        try {
            repaired = engine.repairAfterFailedJourney(task, run.id(), failed.evidence());
        } catch (EndpointOutage outage) {
            throw outage; // nothing was learned: the stage runs again when the endpoint answers
        } catch (RuntimeException e) {
            log("FINAL_INTEGRATION: the repair round after the failed journey did not finish: "
                + e.getMessage());
        }
        task.setJourneyRepairAttempted(true);
        artifactStore.saveTask(task);
        if (repaired == null) {
            log("FINAL_INTEGRATION: no repaired candidate of '" + task.title() + "' passed "
                + "verification; the run stops on the journey.");
            return false;
        }
        log("FINAL_INTEGRATION: a repaired candidate of '" + task.title() + "' passed "
            + "verification and is the task's choice now. The run is merged and verified "
            + "again, and the journeys are made again.");
        return true;
    }

    private boolean recordDelivery(Run run, FinalIntegrator.Result integration) {
        if (run.storyId() == null) {
            return true; // an ad-hoc run with no work item to update
        }
        Story story = artifactStore.getStory(run.storyId());
        if (story == null) {
            return true;
        }
        StoryScope scope = scopeFor(run);
        // selectedVerification() still runs unconditionally: it is where each task's winning
        // candidate id and commit sha get recorded, whatever report ends up deciding the criteria.
        VerificationReport candidateReport = selectedVerification(run);
        // But the report the criteria are actually judged against is FinalIntegrator's own — the
        // ONE report that ran the story's acceptance tests on the tree as merged, with every
        // winner's code together. A candidate's own report was verified alone, on its own branch,
        // before any other task's winner joined it, and reading THAT is the root cause of a story
        // dropping to BLOCKED on the strength of an early, since-fixed failure even though the
        // integrated build the operator would actually ship was green. Fall back to the
        // candidate-level report only when integration produced none — no git target, or no
        // verification contract to run one (the M1 allowance) — so every existing path keeps
        // working exactly as before.
        boolean fromIntegration = integration.verification() != null;
        VerificationReport report = fromIntegration ? integration.verification() : candidateReport;

        CriterionEvidence.GateVerdict gates = CriterionEvidence.evaluateGates(scope, report);
        gates.warnings().forEach(w -> log("Quality gate warning: " + w));
        if (gates.blocked()) {
            log("Delivery BLOCKED by quality gates: " + gates.blocking());
            story.setState(StoryState.BLOCKED);
            artifactStore.saveStory(story);
            queueDecision(run, "An enforced quality gate failed, so the work was integrated but "
                + "must not be delivered:\n\n- " + String.join("\n- ", gates.blocking())
                + "\n\nEither fix the code, or change the requirement — a gate that is ACTIVE is "
                + "one the operator promoted to enforced.");
            return false;
        }

        // The durable requirement→code link. The branch is archived after the run; the sha is not.
        String integrationSha = gitService.headSha(integration.integrationBranch());
        if (integrationSha != null) {
            story.setIntegrationCommit(integrationSha);
            if (story.deliveredCommit() == null) {
                story.setDeliveredCommit(integrationSha);
            }
        }
        List<UUID> runs = new ArrayList<>(story.runIds());
        if (!runs.contains(run.id())) {
            runs.add(run.id());
            story.setRunIds(runs);
        }

        boolean delivered = CriterionEvidence.allDelivered(scope, report);
        if (!delivered && requeueForMissingPiece(run, story)) {
            // Not a failure — a discovered dependency. The story is waiting again, and the workflow
            // has already recorded why. It must NOT fall through and be marked BLOCKED underneath.
            return false;
        }
        // REVIEW, never DONE: "the tests pass" and "this is what I asked for" are different
        // claims, and only the operator can make the second one.
        story.setState(delivered ? StoryState.REVIEW : StoryState.BLOCKED);
        if (delivered) {
            story.setWaitingReason(null);   // whatever it was stuck on before, it is not stuck now
        }
        artifactStore.saveStory(story);
        String outcomeText =
            deliveryOutcomeText(story, scope, report, integrationSha, delivered, fromIntegration);
        artifactStore.recordChange(story.projectId(), "system", ChangeEntityType.STORY, story.id(),
            ChangeKind.STATE_CHANGED, "state", "RUNNING", story.state().name(), outcomeText, run.id());
        // Each criterion named, with its outcome and the reason for it. "Not every criterion is
        // satisfied" is an answer nobody can act on, and the usual cause — a criterion pointing at
        // a test that never ran — is invisible without this.
        CriterionEvidence.explain(scope, report).forEach(line -> log("  criterion " + line));
        // An unscoped story reaching REVIEW must not read like a requirement that was proved.
        // Nothing here checked one — there is none — and saying so is the difference between an
        // honest escape hatch and a run that quietly claims more than it did.
        if (delivered && scope.isEmpty()) {
            log("Story " + story.key() + " → REVIEW. It answers to no requirement, so nothing here "
                + "proved one: the code was built, verified and merged, and whether it is what you "
                + "asked for is yours to say. Triage it onto a requirement if it should count "
                + "towards one.");
        } else {
            log("Story " + story.key() + " → " + (delivered ? "REVIEW: " : "BLOCKED: ") + outcomeText);
        }
        return delivered;
    }

    /**
     * The one sentence that describes what this build actually did — named here once so the
     * story's own change log and the operator-facing log line can never disagree.
     *
     * <p>It reads the commit and, per criterion, whether the FINAL build (not an early, since-fixed
     * attempt) proved it — the whole point of this method existing: harness run 13 sent a story to
     * BLOCKED quoting a compile failure from an hour earlier, on a task a later candidate had
     * already fixed, while the integration that just verified green sat unread. A candidate's
     * failed history belongs to the task's own record, never to the story's verdict, once a later
     * candidate went on to succeed and integration proved it.
     */
    private static String deliveryOutcomeText(Story story, StoryScope scope, VerificationReport report,
                                              String integrationSha, boolean delivered,
                                              boolean fromIntegration) {
        if (delivered && scope.isEmpty()) {
            return story.key()
                + " built and verified — awaiting acceptance (it answers to no requirement)";
        }
        if (!delivered) {
            return story.key() + " did not satisfy every criterion";
        }
        StringBuilder text = new StringBuilder(story.key()).append(" delivered");
        if (integrationSha != null) {
            text.append(" commit ").append(shortSha(integrationSha));
        }
        List<String> passedTests = new ArrayList<>();
        for (AcceptanceCriterion criterion : scope.criteria()) {
            CriterionEvidence.Evidence evidence =
                CriterionEvidence.evidenceFor(criterion.testClassOrFile(), report);
            if (evidence.outcome() == CriterionEvidence.Outcome.PASSED) {
                passedTests.add(criterion.testClassOrFile());
            }
        }
        if (!passedTests.isEmpty()) {
            text.append("; the acceptance test").append(passedTests.size() > 1 ? "s " : " ")
                .append(String.join(", ", passedTests))
                .append(passedTests.size() > 1 ? " each passed" : " passed")
                // Only true when this report is FinalIntegrator's own — the merged tree every
                // winner's code actually sits on. The candidate-level fallback (no git target, or
                // no verification contract) never merged anything, so it must not claim to.
                .append(fromIntegration ? " on the integrated tree" : "");
        }
        return text.append(" — awaiting acceptance").toString();
    }

    /** The commit sha as an operator would type it, not the 40-character form nobody reads. */
    private static String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    /**
     * Names the story's real BLOCKED reason as the integration failure that just happened, so it
     * can never be answered instead by a stale sentence about an earlier candidate that has since
     * been superseded.
     *
     * <p>Before this, a failed FINAL_INTEGRATION only parked the RUN — the story's own state and
     * {@code waitingReason} were left exactly as whatever an earlier stage last wrote there, which
     * could easily be nothing at all, or worse, a BLOCKED reason from a completely different
     * problem an earlier attempt hit and a later one already fixed. The run still parks the same
     * way afterwards — this only makes the story's own card tell the truth about why, in the same
     * breath.
     */
    private void blockStoryOnIntegrationFailure(Run run, FinalIntegrator.Result integration) {
        if (run.storyId() == null) {
            return;
        }
        Story story = artifactStore.getStory(run.storyId());
        if (story == null) {
            return;
        }
        String reason = "Final integration failed: " + integration.failure();
        story.setState(StoryState.BLOCKED);
        story.setWaitingReason(reason);
        artifactStore.saveStory(story);
        artifactStore.recordChange(story.projectId(), "system", ChangeEntityType.STORY, story.id(),
            ChangeKind.STATE_CHANGED, "state", "RUNNING", "BLOCKED",
            story.key() + " → BLOCKED: " + reason, run.id());
        log("Story " + story.key() + " → BLOCKED: " + reason);
    }

    /** How many times one story may be sent back to wait before it is stopped for a person. */
    private static final int MAX_DEPENDENCY_RETRIES = 3;

    /**
     * Handles the case the planner got wrong: the story failed because something it needed did not
     * exist yet.
     *
     * <p>The planner declares which story comes after which before any code is written, so it is
     * guessing, and a graph decided up front and never revised turns one wrong guess into a build
     * that dies at three in the morning. {@link MissingPieceDetector} reads the compilers' own output
     * from this run's candidates — no model is asked anything — and when independent workers all
     * failed on the same name that does not exist, that is a fact about the code.
     *
     * <p>What happens then depends on whether the missing thing can be named to something planned:
     *
     * <ul>
     *   <li><b>Another undelivered story's own wording names it.</b> That is what this story was
     *       waiting for. The edge is recorded as DISCOVERED — kept apart from the planner's declared
     *       ones so the operator can see it was inferred and disagree — and the story goes back to
     *       READY, where the scheduler will hold it until the other one is delivered and then build
     *       it again on top of that work.</li>
     *   <li><b>Nothing planned names it.</b> No edge is invented. The story stops and says which name
     *       it could not find, which is a failure somebody can act on in one reading.</li>
     * </ul>
     *
     * <p>Bounded, deliberately. A story that has already been sent back {@value #MAX_DEPENDENCY_RETRIES}
     * times stops for good and says what it was waiting for — a loop that never converges costs a
     * whole night and teaches nobody anything.
     *
     * @return true when the story has been put back to wait and the caller must not mark it stopped
     */
    private boolean requeueForMissingPiece(Run run, Story story) {
        if (story.projectId() == null) {
            return false;
        }
        TaskGraph graph = run.taskGraphId() == null
            ? null : artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            return false;
        }
        Set<UUID> taskIds = new HashSet<>();
        graph.tasks().forEach(task -> taskIds.add(task.id()));
        List<CandidateSolution> candidates = new ArrayList<>();
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && taskIds.contains(candidate.taskId())) {
                candidates.add(candidate);
            }
        }
        List<Story> others = new ArrayList<>();
        for (Story other : artifactStore.listStories(story.projectId())) {
            if (!other.id().equals(story.id())) {
                others.add(other);
            }
        }
        MissingPieceDetector.Finding finding = MissingPieceDetector.examine(candidates, others,
            artifactStore.getBrd(story.projectId()));
        if (!finding.any()) {
            return false;
        }
        if (!finding.hasProvider() || story.dependencyRetries() >= MAX_DEPENDENCY_RETRIES) {
            // A clean stop, with the thing it could not find named. Better than a re-queue that
            // cannot converge, and better than "not every criterion is satisfied".
            String reason = finding.hasProvider()
                ? finding.explanation() + " It has already been sent back to wait "
                    + story.dependencyRetries() + " times and still cannot be built, so it is "
                    + "stopped here for you."
                : finding.explanation();
            story.setState(StoryState.BLOCKED);
            story.setWaitingReason(reason);
            artifactStore.saveStory(story);
            artifactStore.recordChange(story.projectId(), "system", ChangeEntityType.STORY,
                story.id(), ChangeKind.STATE_CHANGED, "state", "RUNNING", "BLOCKED", reason, run.id());
            log("Story " + story.key() + " → BLOCKED: " + reason);
            return true;
        }
        List<UUID> discovered = new ArrayList<>(story.discoveredDependsOn());
        for (Story provider : finding.providers()) {
            if (!discovered.contains(provider.id())) {
                discovered.add(provider.id());
            }
        }
        story.setDiscoveredDependsOnStoryIds(discovered);
        story.setDependencyRetries(story.dependencyRetries() + 1);
        story.setWaitingReason(finding.explanation());
        // READY, not BLOCKED. BLOCKED means a person is needed; this needs nothing but time, and the
        // scheduler will hold it back until the story it is now waiting for has been delivered.
        story.setState(StoryState.READY);
        artifactStore.saveStory(story);
        artifactStore.recordChange(story.projectId(), "system", ChangeEntityType.STORY, story.id(),
            ChangeKind.STATE_CHANGED, "state", "RUNNING", "READY", finding.explanation(), run.id());
        log("Story " + story.key() + " → waiting: " + finding.explanation());
        return true;
    }

    /**
     * The verification report of the selected candidate, and the point where each task gets its
     * durable git link — only the SELECTED candidate's commit is meaningful, since N workers
     * produce N branches per task.
     */
    private VerificationReport selectedVerification(Run run) {
        TaskGraph graph = artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null) {
            return null;
        }
        // candidateArchives is keyed by CANDIDATE id, each entry holding one CandidateSolution.
        // This used to look it up by TASK id and expect a List, so it matched nothing, ever: the
        // report was always null, every criterion was therefore UNKNOWN rather than PASSED, and
        // allDelivered() could not return true — meaning a story could NEVER reach REVIEW and no
        // task ever got its commit sha. Same traversal as FinalIntegrator.winnersByTask, which is
        // the shape that was always correct.
        Set<UUID> taskIds = new HashSet<>();
        graph.tasks().forEach(task -> taskIds.add(task.id()));
        Map<UUID, CandidateSolution> winners = new HashMap<>();
        for (Lazy<Object> lazy : artifactStore.root().candidateArchives.values()) {
            Object value = Lazy.get(lazy);
            if (value instanceof CandidateSolution candidate
                    && candidate.state() == CandidateState.SELECTED
                    && taskIds.contains(candidate.taskId())) {
                winners.put(candidate.taskId(), candidate);
            }
        }
        VerificationReport selected = null;
        for (Task task : graph.tasks()) {
            CandidateSolution winner = winners.get(task.id());
            if (winner == null) {
                continue;
            }
            // The durable requirement→code link: the winning candidate's commit, per task.
            task.setSelectedCandidateId(winner.id());
            String sha = gitService.headSha(winner.branch());
            if (sha != null) {
                task.setCommitSha(sha);
            }
            artifactStore.saveTask(task);
            if (selected == null) {
                selected = winner.verification();
            }
        }
        return selected;
    }

    /**
     * The criteria one task must be tested against.
     *
     * <p>Two shapes, because a task acquires its criteria two different ways. An unscoped task owns
     * them, having had them invented by the planner. A story-scoped task owns none: it names, by
     * id, criteria that belong to a BRD requirement — which is the right arrangement, because a
     * requirement's wording must not be copied into an object that is archived with the run. Both
     * end up here as the same list.
     */
    private static List<AcceptanceCriterion> criteriaFor(Task task, StoryScope scope) {
        if (task.criterionIds() == null || task.criterionIds().isEmpty() || scope == null
                || scope.isEmpty()) {
            return task.criteria() == null ? List.of() : task.criteria();
        }
        List<AcceptanceCriterion> resolved = new ArrayList<>();
        for (AcceptanceCriterion criterion : scope.criteria()) {
            if (task.criterionIds().contains(criterion.id())) {
                resolved.add(criterion);
            }
        }
        return resolved;
    }

    /**
     * What this run is allowed to build, resolved from the project's BRD. Empty for an ad-hoc run
     * that answers to no requirement yet — the workflow then behaves exactly as before.
     */
    private StoryScope scopeFor(Run run) {
        String rules = projectRules();
        if (run.storyId() == null) {
            return StoryScope.resolve(null, null, rules);
        }
        Story story = artifactStore.getStory(run.storyId());
        if (story == null || story.projectId() == null) {
            return StoryScope.resolve(null, story, rules);
        }
        return StoryScope.resolve(artifactStore.getBrd(story.projectId()), story, rules);
    }

    /**
     * The project's standing rules, rendered, or "" when nothing wired them in.
     *
     * <p>A supplier rather than a field because guidelines live in the project's checkout and are
     * reconciled from disk before every prompt: a rule the operator turned off mid-run must stop
     * being sent, and one they stated must start. Unwired (tests, an ad-hoc engine) it is empty,
     * and every prompt is exactly what it was before rules existed.
     */
    private String projectRules() {
        if (projectRules == null) {
            return "";
        }
        try {
            String rules = projectRules.get();
            return rules == null ? "" : rules;
        } catch (Exception e) {
            // Never worth losing a run over. A missing briefing is the behaviour of every run
            // before 2026-08-31; a thrown exception here would be a new way to fail.
            logger.warn("Could not read this project's standing rules: {}", e.toString());
            return "";
        }
    }

    /**
     * Records requirements the architect believes are missing as DRAFT proposals for the operator
     * to triage. They are NOT added to the design and nothing is built from them — an agent that
     * could add requirements to the document would make the whole promotion gate decorative.
     */
    private void proposeMissingRequirements(Run run, List<ArchitectClient.LlmMissing> missing) {
        if (missing == null || missing.isEmpty() || run.storyId() == null) {
            return;
        }
        Story story = artifactStore.getStory(run.storyId());
        UUID projectId = story == null ? null : story.projectId();
        if (projectId == null) {
            return;
        }
        Brd brd = artifactStore.getBrd(projectId);
        if (brd == null) {
            return;
        }
        List<BrdRequirement> requirements = new ArrayList<>(brd.requirements());
        int added = 0;
        for (ArchitectClient.LlmMissing m : missing) {
            if (m == null || m.statement == null || m.statement.isBlank()) {
                continue;
            }
            BrdRequirement proposal = new BrdRequirement(UUID.randomUUID(), nextHandle(requirements),
                m.title == null || m.title.isBlank() ? "Proposed requirement" : m.title.trim(),
                m.statement.trim(), Priority.MEDIUM, RequirementStatus.DRAFT, null);
            requirements.add(proposal);
            artifactStore.recordChange(projectId, "agent", ChangeEntityType.REQUIREMENT,
                proposal.id(), ChangeKind.CREATED, "architect proposed " + proposal.handle()
                    + " while designing " + story.key()
                    + (m.why == null || m.why.isBlank() ? "" : ": " + m.why.trim()));
            added++;
        }
        if (added > 0) {
            brd.setRequirements(requirements);
            artifactStore.saveBrd(brd, "agent",
                "architect proposed " + added + " requirement(s) from " + story.key());
            log("Architect proposed " + added + " missing requirement(s) — DRAFT, awaiting triage.");
        }
    }

    private static String nextHandle(List<BrdRequirement> requirements) {
        int max = 0;
        for (BrdRequirement r : requirements) {
            String h = r.handle();
            if (h != null && h.matches("[Rr]\\d+")) {
                max = Math.max(max, Integer.parseInt(h.substring(1)));
            }
        }
        return "R" + (max + 1);
    }

    private void persistDesign(DesignDocument design) {
        try {
            artifactStore.append(() -> {
                artifactStore.root().designs.put(design.id(), design);
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist DesignDocument", e);
        }
    }

    private static DesignDocument withVerdict(
            DesignDocument design, ReviewVerdict verdict) {
        return new DesignDocument(design.id(), design.revision(), design.goal(),
            design.requirements(), design.decisions(), design.contracts(), design.risks(),
            verdict, design.createdAt());
    }

    /**
     * True when no contract in the design names a concrete type — see the DESIGN-stage check that
     * calls this (2026-09-04, harness run 21). Treats a null design as having none, the same "no
     * type vocabulary" outcome as an empty contracts list.
     */
    private static boolean hasNoTypedContract(DesignDocument design) {
        if (design == null) {
            return true;
        }
        for (ApiContract c : design.contracts()) {
            if (c != null && c.namesAType()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Appended to the "name the types" re-ask only (not to the reason a parked run shows its
     * operator): the exact JSON the architect is to answer with, since a model that cannot use a
     * structured-output endpoint otherwise invents its own shape (harness run 59, 2026-10-01).
     */
    static final String CONTRACT_SHAPE_ASK =
        " Reply with the contracts as JSON in exactly this shape: "
        + "{\"contracts\":[{\"name\":\"...\",\"description\":\"...\",\"signature\":\"...\","
        + "\"type\":\"fully.qualified.Name\",\"members\":[\"int rating\",\"String title()\"]}]}. "
        + "Every contract carries a \"type\" holding the fully-qualified name of a Java type "
        + "(for example com.acme.shop.Rating). The other sections of the design may be left out; "
        + "they stay as they are.";

    /**
     * The sentence sent to the architect when {@link #hasNoTypedContract} is true for a story that
     * has checks — the same "conflicts/objections" shape {@link ArchitectClient#revise} already
     * takes, naming the exact checks so the ask is mechanical rather than a repeat of the standing
     * contract-vocabulary rule the design prompt already carries (which, on this model, was not
     * enough on its own).
     */
    private static String missingContractsSentence(StoryScope scope) {
        List<AcceptanceCriterion> criteria = scope.criteria();
        List<String> refs = scope.criterionRefs();
        List<String> checks = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            AcceptanceCriterion c = criteria.get(i);
            StringBuilder sb = new StringBuilder();
            if (i < refs.size() && refs.get(i) != null) {
                sb.append(refs.get(i)).append(' ');
            }
            if (c != null && c.text() != null) {
                sb.append(c.text());
            }
            if (c != null && c.testClassOrFile() != null) {
                sb.append(" [test: ").append(c.testClassOrFile()).append(']');
            }
            checks.add(sb.toString());
        }
        return "Every type an acceptance test will touch must be a contract with its exact package "
            + "and members; you gave none. The checks are: " + String.join("; ", checks)
            + ". Name the types.";
    }

    /** Where the throwaway worktrees of a run's own stages live — the same root the integrator uses. */
    private static final Path WORKTREE_ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    /** The run-scoped ref holding a run's acceptance tests. */
    static String testsBranch(Run run) {
        return "swarm/tests/" + run.id();
    }

    /**
     * Writes and commits acceptance tests for every task with criteria (spec §14), records on each
     * task which files are its own, and checks that each check the run answers for now points at a
     * test that exists.
     *
     * <p><b>Where the tests go</b> (author decision, 2026-09-02). Into a worktree of the run's own,
     * cut from the run's pinned base, and committed there on {@code swarm/tests/<runId>}. They used
     * to be committed in the operator's checkout, on the delivery branch — which had two costs. The
     * run is pinned to a commit at intake, so that commit was invisible to every worktree of the
     * run: the acceptance stage selected nothing and reported 0 passed, 0 failed for every candidate
     * of every Console-started run for a week. And a dead run's tests stayed on the delivery branch
     * for ever: on 2026-09-02 the demo repository carried four such files from four runs, each
     * naming classes a different plan had invented, and {@code test-compile} failed on them before
     * any candidate's own code was looked at. Now a candidate receives exactly the files its task
     * claims at verification ({@code AcceptanceOverlay}), the integration branch descends from this
     * commit, and the tests reach the delivery branch only together with the code that passes them.
     * A run that dies leaves nothing behind but its own ref.
     *
     * <p><b>One file, one task.</b> A test class is one file, and a file is verified with one task,
     * so a file the author writes for a second task would overwrite the first task's — silently,
     * since each task's audit runs right after its own author. The plan validator keeps criteria
     * that name the same test class on the same task; this is the check behind it, and it parks
     * rather than guessing when the author wrote the same path for two tasks anyway.
     *
     * @return null when dispatch may proceed; otherwise the brief for a parked run
     */
    private String authorAcceptanceTests(Run run) {
        if (repoPath == null || run.taskGraphId() == null) {
            return null;
        }
        TaskGraph graph = artifactStore.root().taskGraphs.get(run.taskGraphId());
        DesignDocument design = artifactStore.root().designs.get(run.designId());
        if (graph == null) {
            return null;
        }
        // A story-scoped task references its criteria by id and owns no copies of them, so the
        // criteria have to be resolved from the story's slice here. Without this the test author
        // saw an empty list on every scoped task and wrote nothing at all.
        StoryScope scope = scopeFor(run);
        // How this project must be built. The test author is the role that suffered most from not
        // being told: it writes the gate every candidate is judged against, so a test written in
        // the wrong framework is not a bad test, it is a story that can never be delivered.
        String constraints = scope.constraintBrief();
        if (!constraints.isEmpty()) {
            log("TEST_AUTHORING: the test author is told this project's standing rules about how "
                + "it must be built.");
        }

        // WHICH TASKS CHANGE A SCREEN (section 63; the seven accepted stories whose screens no
        // user could open). Read off the build and the plan's write sets, with no model: a task
        // that may write shipped code of a browser-only module, or a page file of an application
        // the contract starts, is proved by a journey as well as by its tests. Decided HERE,
        // before a test is written or a worktree is cut: a project whose contract cannot start
        // its application cannot make a journey, and that is said now, not after the swarm.
        // Which modules run only in a browser is read once for the stage (harness run 37).
        BrowserOnlyCode.Survey browserOnly = browserOnlySurvey(repoLayout());
        // Section 64 (live run 89: a story about what a screen shows, whose plan wrote server
        // code only, got no journey). The write set is no longer the only evidence: the object
        // graph says whether browser-only code uses what a task writes, and in a project whose
        // contract starts an application every story is asked for a journey.
        com.swarmcoder.knowledge.ReachableCode.Graph treeGraph =
            browserOnly.any() ? startTreeGraph() : null;
        JourneysOfAPlan.Decision journeysDue = JourneysOfAPlan.decide(graph.tasks(),
            task -> !criteriaFor(task, scope).isEmpty(), browserOnly,
            repoPath == null ? null : VerifySpecLoader.load(repoPath).orElse(null),
            treeGraph == null || !treeGraph.determined() ? null
                : path -> treeGraph.usersThroughItsTypes(path,
                    user -> com.swarmcoder.verify.ScreenChange.inBrowserOnlyModule(user,
                        browserOnly)));
        if (journeysDue.stop() != null) {
            log("TEST_AUTHORING: this story changes a screen and the project's contract cannot "
                + "start the application, so no journey could be made; stopping before any test "
                + "is written.");
            return journeysDue.stop();
        }
        if (journeysDue.storyHasAScreen()) {
            log("TEST_AUTHORING: this story changes a screen ("
                + journeysDue.screens().stream().map(s -> "'" + s.task().title() + "'"
                    + (s.through().isEmpty() ? "" : " - " + s.through().get(0))).toList()
                + "); the test author also writes a journey for "
                + journeysDue.authoring().stream().map(t -> "'" + t.title() + "'").toList()
                + ", and it is made in a real browser after the last merge.");
        } else if (journeysDue.journeyAsked()) {
            log("TEST_AUTHORING: this project's application is used in a browser, so the test "
                + "author is asked for a journey for "
                + journeysDue.authoring().stream().map(t -> "'" + t.title() + "'").toList()
                + ". No task writes a screen and the object graph shows no browser code using "
                + "what the plan writes, so the author may put on record that no person sees a "
                + "difference instead.");
        }

        // The run's own worktree, when there is a repository to cut one from. Without one the
        // files are written into the checkout as before and nothing is committed.
        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        Path writeRoot = repoPath;
        boolean ownWorktree = false;
        if (gitService.isEnabled()) {
            try {
                removeTree(worktree); // a leftover from a killed attempt, if any
                gitService.addOrResumeWorktree(branch, worktree, run.startPoint(), run.id().toString());
                writeRoot = worktree;
                ownWorktree = true;
            } catch (Exception e) {
                log("TEST_AUTHORING: could not open the run's tests worktree (" + e.getMessage()
                    + "); writing into the checkout instead, uncommitted");
            }
        }

        // Whether anything in this project ever starts the application and looks at it. It is the
        // contract that says so, because the contract is what the browser stage actually reads —
        // and it is the only thing that can prove a criterion worded about a screen. See
        // TestAuthorClient.screenSteering.
        boolean webProject = repoPath != null
            && VerifySpecLoader.load(repoPath).map(VerifySpec::browser).isPresent();

        List<String> allWritten = new ArrayList<>();
        Map<String, Task> authoredBy = new HashMap<>();
        String parkFor = null;
        int criteriaOffered = 0;
        // What the test author itself said went wrong, per task — quoted in the park message
        // below instead of a guess at "the endpoint" for a failure that may have had nothing to
        // do with it (2026-09-03: it was a malformed reply, twice, and the endpoint was fine).
        List<String> authorFailures = new ArrayList<>();
        // Read once per module, not once per task: every task in a plan almost always shares the
        // one acceptance module AcceptanceTestLocation chose, and a pom read is not free.
        Map<String, List<String>> artifactIdsByModule = new java.util.concurrent.ConcurrentHashMap<>();
        // Which modules run only in a browser (harness run 37, 2026-09-25). Read once for the
        // stage. Their artifact ids come OFF the classpath list the author is shown: the server
        // module declares the TeaVM client only so it can package the bundle, and listing it as
        // importable is half of how run 37's author came to call it.
        Set<String> browserOnlyArtifacts = browserOnlyArtifactIds(browserOnly);
        if (browserOnly.any()) {
            log("TEST_AUTHORING: " + browserOnly.browserOnlyDirs() + " run only in a browser; the "
                + "test author is told so, and a test that calls them is sent back.");
        }
        // What the test author's compile_test compiles with, when it works as an agent: the red
        // check itself, on a draft, in a throwaway tree of its own (2026-10-02). Null otherwise.
        DraftRedCheck draftCheck = draftRedCheck(run, graph.tasks(), null, false);
        if (draftCheck != null) {
            log("TEST_AUTHORING: the test author works in steps with lookup tools, and compiles "
                + "each draft the way the red check will before it hands the test in.");
        }
        // THE TESTS OF SEVERAL TASKS ARE WRITTEN AT THE SAME TIME (2026-10-02). The author used to
        // be handed one task, and the next only when that one's tests were back - and since it
        // works in steps with lookup tools, one task takes minutes (harness run 66: 15.3 minutes
        // for the stage, one call in flight throughout, on a server that serves four). The calls
        // are independent: each writes its own task's files and nothing else. So the WRITING -
        // the author's session, and the reviewer holding its test to the project's rules - runs
        // side by side, as many at once as the author's server serves; everything decided FROM
        // what was written (the mismatch checks, one file one task, the records on the tasks)
        // still happens one task at a time, in plan order, exactly as before, as each task's
        // tests come back.
        record Written(List<AcceptanceCriterion> forTask, List<String> refs,
                       TestAuthorClient.Authored authored, TestsVersusRules.Outcome held) { }
        final Path treeToWriteIn = writeRoot;
        final int atOnce = testAuthoringAtOnce(graph.tasks().size());
        if (atOnce > 1) {
            log("TEST_AUTHORING: the tests of up to " + atOnce + " tasks are written at the same "
                + "time.");
        }
        java.util.concurrent.Semaphore turns = new java.util.concurrent.Semaphore(atOnce, true);
        java.util.concurrent.ExecutorService authoring =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try (TestAuthorClient.Scope compiling = roles.testAuthor() == null ? () -> { }
                : roles.testAuthor().compilingDraftsWith(draftCheck)) {
            List<java.util.concurrent.Future<Written>> writing = new ArrayList<>();
            for (Task task : graph.tasks()) {
                writing.add(authoring.submit(() -> {
                    turns.acquire();
                    // The draft compiler is the author's per THREAD, so each call sets its own.
                    try (TestAuthorClient.Scope mine =
                             roles.testAuthor().compilingDraftsWith(draftCheck)) {
                        List<AcceptanceCriterion> forTask = criteriaFor(task, scope);
                        List<String> refs = refsFor(forTask, scope);
                        // Said on the task BEFORE the author is called, and the result written
                        // onto it the moment the files are read back - per task, not at the end
                        // of the stage. This stage takes minutes and the graph used to show
                        // nothing for the whole of it, which is what a hung stage looks like too;
                        // the badges lighting up as tasks come back are the proof that something
                        // is happening. A task handed no checks is recorded as exactly that, which
                        // is how the graph tells "claims none" from "not reached yet".
                        recordTestAuthoring(task, AuthoredTests.started(forTask.size(), Instant.now()));
                        // What this test's own module can even import (author decision,
                        // 2026-09-05). Null when there is no repository to read a build file from;
                        // then the author is told nothing new, exactly as before.
                        String acceptanceModule = repoPath == null ? null
                            : AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
                        List<String> moduleArtifactIds = acceptanceModule == null ? List.of()
                            : artifactIdsByModule.computeIfAbsent(acceptanceModule,
                                    this::moduleArtifactIds)
                                .stream().filter(id -> !browserOnlyArtifacts.contains(id)).toList();
                        // A journey is asked of the tasks chosen above and of no other; a task
                        // that changes no screen is authored by the call it always was.
                        boolean journeyDue =
                            journeysDue.authoring().stream().anyMatch(due -> due == task);
                        TestAuthorClient.Authored authored = journeyDue
                            ? roles.testAuthor().authorTests(treeToWriteIn, task, design, forTask,
                                constraints, webProject, acceptanceModule, moduleArtifactIds,
                                browserOnly, graph.tasks(), new TestAuthorClient.JourneyAsk(true,
                                    journeysDue.mayWaive(), journeysDue.through(task)))
                            : roles.testAuthor().authorTests(treeToWriteIn, task, design, forTask,
                                constraints, webProject, acceptanceModule, moduleArtifactIds,
                                browserOnly, graph.tasks());
                        // THE TEST IS HELD TO THE PROJECT'S RULES (live harness runs 56 and 58,
                        // 2026-10-01): the author was told a service is never constructed by hand
                        // in a test, constructed it by hand, and two workers burned 80 minutes on
                        // a test no rule-keeping candidate could pass. Read HERE, before any of
                        // the checks below, so they all judge the corrected file. See
                        // TestsVersusRules.
                        TestsVersusRules.Outcome held = null;
                        if (authored.failureReason() == null && !authored.isEmpty()
                                && !constraints.isEmpty()) {
                            held = TestsVersusRules.hold(roles.reviewer(), roles.testAuthor(),
                                treeToWriteIn, task, design, authored.paths(), constraints,
                                librarian, line -> log("TEST_AUTHORING: " + line));
                            if (held.corrected()) {
                                authored = TestsVersusRules.rechecked(treeToWriteIn, design,
                                    authored, browserOnly, graph.tasks());
                            }
                        }
                        return new Written(forTask, refs, authored, held);
                    } finally {
                        turns.release();
                    }
                }));
            }
            for (int taskIndex = 0; taskIndex < graph.tasks().size(); taskIndex++) {
                Task task = graph.tasks().get(taskIndex);
                Written written0 = writtenBy(writing.get(taskIndex));
                List<AcceptanceCriterion> forTask = written0.forTask();
                List<String> refs = written0.refs();
                TestAuthorClient.Authored authored = written0.authored();
                criteriaOffered += forTask.size();
                if (authored.failureReason() != null) {
                    authorFailures.add("Task '" + task.title() + "': " + authored.failureReason());
                }
                if (written0.held() != null) {
                    TestsVersusRules.Outcome held = written0.held();
                    if (held.parks() && carriesOpinions()) {
                        // A reviewer model's reading, after the author's one correction, with
                        // nobody watching: a warning on the run, and the mechanical checks
                        // below still judge the file (owner decision, 2026-10-01; live run 60).
                        carry(run, "acceptance tests against the project's rules (reviewer "
                            + "model), task '" + task.title() + "', after one correction",
                            held.hard());
                    } else if (held.parks() && parkFor == null) {
                        parkFor = held.parkBrief();
                    }
                }
                // The test names something nobody will ever deliver, and the author was already
                // asked once to use the contracts instead. Parking HERE is the whole point: from
                // the next stage on, such a test is indistinguishable from a healthy one that has
                // not been implemented yet — both say "does not compile" — so nothing downstream
                // can tell them apart. See AcceptanceTestVocabulary.
                if (!authored.vocabularyIsAnswerable() && parkFor == null) {
                    log("TEST_AUTHORING MISMATCH: task '" + task.title() + "' has tests naming "
                        + authored.vocabulary().unknowns().stream()
                            .map(AcceptanceTestVocabulary.Unknown::typeName).toList()
                        + ", which no task in this plan delivers");
                    parkFor = AcceptanceTestVocabulary.brief(task.title(), authored.vocabulary());
                }
                // AND THE TEST THAT IMPLEMENTS THE CONTRACT ITSELF (author decision, 2026-09-05,
                // harness run 30). The vocabulary check cannot see this one: the type names are
                // perfect, and the test simply supplies its own version of the behaviour, so it
                // goes green with nothing delivered. Parking HERE for the same reason as above —
                // from the next stage on, such a test is indistinguishable from an honest one that
                // the candidates really did make pass. See SelfImplementedContract.
                if (!authored.provesDeliveredCode() && parkFor == null) {
                    log("TEST_AUTHORING MISMATCH: task '" + task.title() + "' has tests that "
                        + "implement " + authored.selfImplemented().contractTypes() + " themselves, "
                        + "so they can pass with no delivered code at all");
                    parkFor = SelfImplementedContract.brief(task.title(), authored.selfImplemented());
                }
                // AND THE TEST THAT CALLS CODE ONLY A BROWSER CAN RUN (harness run 37, 2026-09-25).
                // Parked HERE because from the next stage on it looks healthy: the class it calls
                // does not exist yet, so the red-check reads "does not compile", and the runtime
                // UnsatisfiedLinkError is first seen on a candidate. See AcceptanceTestReach.
                if (!authored.runsOnTheJvm() && parkFor == null) {
                    log("TEST_AUTHORING MISMATCH: task '" + task.title() + "' has tests calling "
                        + authored.reach().names() + ", which only a browser can run");
                    parkFor = AcceptanceTestReach.brief(task.title(), authored.reach());
                }
                // AND THE TEST THAT FINDS THE PROJECT'S MEMBERS BY REFLECTION (live harness run 54,
                // 2026-10-01). It guessed at a constructor it was never told, and every candidate
                // later died inside the test's own code. Parked HERE, after the one re-ask the
                // author already had, because three tasks were built before it showed. See
                // AcceptanceTestReflection.
                if (!authored.callsMembersDirectly() && parkFor == null) {
                    log("TEST_AUTHORING MISMATCH: task '" + task.title() + "' has tests that find "
                        + "the project's own members by reflection");
                    parkFor = AcceptanceTestReflection.brief(task.title(), authored.reflection());
                }
                allWritten.addAll(authored.paths());
                for (String path : authored.paths()) {
                    Task earlier = authoredBy.putIfAbsent(path, task);
                    if (earlier != null && earlier != task && parkFor == null
                            && !earlier.criterionIds().equals(task.criterionIds())) {
                        parkFor = "The test author wrote " + path + " for task '" + earlier.title()
                            + "' and then again for task '" + task.title() + "', and the second "
                            + "write replaced the first. One test file serves one task: each "
                            + "task's candidates are verified against the files it claims and "
                            + "nothing else, so a file two tasks both need can never be right "
                            + "for both.\n\nThe usual cause is two checks naming the same test "
                            + "class while the plan puts them on different tasks. Put every check "
                            + "of that class on one task, or give the checks separate test "
                            + "classes in the Requirements editor, then resume the run.";
                        log("TEST_AUTHORING MISMATCH: " + path + " written for both '"
                            + earlier.title() + "' and '" + task.title() + "'");
                    }
                }
                // Which files are THIS task's — the list its candidates are verified against.
                task.setAuthoredTestPaths(List.copyOf(authored.paths()));
                // And its journeys: files of the same commit, claimed the same way, kept apart
                // because a browser makes them and the build does not compile them.
                task.setJourneyPaths(List.copyOf(authored.journeys()));
                allWritten.addAll(authored.journeys());
                // The answer given in place of a journey (section 64) is taken only where the
                // graph left room for it, and is then on the task and in the log - never silent.
                String noJourney = authored.journeys().isEmpty() ? authored.journeyWaiver() : null;
                boolean asked = journeysDue.authoring().stream().anyMatch(due -> due == task);
                task.setJourneyWaiver(noJourney != null && asked && journeysDue.mayWaive()
                    ? noJourney : null);
                if (task.journeyWaiver() != null) {
                    log("TEST_AUTHORING: NO JOURNEY for task '" + task.title() + "' - its test "
                        + "author states that no person using the application in a browser "
                        + "sees or can do anything different: \"" + task.journeyWaiver() + "\"");
                } else if (noJourney != null) {
                    log("TEST_AUTHORING: the test author of task '" + task.title() + "' answered "
                        + "\"no visible effect\" in place of a journey; not taken, because "
                        + "browser code uses what the plan writes or no journey was asked.");
                }
                artifactStore.saveTask(task);
                if (!authored.isEmpty()) {
                    log("TEST_AUTHORING: task '" + task.title() + "' claims " + authored.paths());
                }
                if (!authored.journeys().isEmpty()) {
                    log("TEST_AUTHORING: task '" + task.title() + "' claims the journey(s) "
                        + authored.journeys());
                }

                // What was written, for which check. Reported at authoring time on purpose: a check
                // pointing at a test nobody wrote is obvious here and costs nothing, and is otherwise
                // only discovered at the end of a whole run, as an UNKNOWN.
                AuthoredTestAudit.Result audit = AuthoredTestAudit.audit(
                    forTask, refs, writeRoot, authored);
                audit.notes().forEach(note -> log("TEST_AUTHORING: " + note));
                audit.mapping().forEach(line -> log("TEST_AUTHORING: " + line));
                if (!audit.ok() && parkFor == null) {
                    audit.findings().forEach(f ->
                        log("TEST_AUTHORING MISMATCH: " + f.render().replace("\n", " ")));
                    parkFor = AuthoredTestAudit.brief(task.title(), audit);
                }
                AuthoredTests written = AuthoredTestAudit.written(forTask, refs, audit, authored,
                    task.authoredTests() == null ? null : task.authoredTests().startedAt(),
                    Instant.now());
                recordTestAuthoring(task, written);
                if (!forTask.isEmpty()) {
                    log("TEST_AUTHORING: task '" + task.title() + "' now shows "
                        + written.tests().size() + " test(s) proving " + written.checksProved()
                        + " of its " + forTask.size() + " check(s)");
                }
            }
            // THE BACKSTOP (section 63), with no model: a plan that changes a screen and whose
            // tasks claim no journey does not go on, whatever the author made of being asked.
            if (parkFor == null) {
                parkFor = JourneysOfAPlan.missing(journeysDue, graph.tasks(), browserOnly);
                if (parkFor != null) {
                    log("TEST_AUTHORING MISMATCH: the story changes a screen and no task claims "
                        + "a journey");
                }
            }
            if (allWritten.isEmpty()) {
                log(noTestsWrittenReason(graph.tasks().size(), criteriaOffered, scope, authorFailures));
                run.setAcceptanceTestsCommit(null);
                return parkFor;
            }
            if (!ownWorktree) {
                return parkFor;
            }
            try {
                gitService.commitAll(worktree, "Acceptance tests for run " + run.id()
                    + "\n\n" + String.join("\n", allWritten));
                String sha = gitService.headSha(branch);
                run.setAcceptanceTestsCommit(sha);
                log("Committed " + allWritten.size() + " acceptance test file(s) on " + branch
                    + " at " + (sha == null ? "?" : sha.substring(0, Math.min(8, sha.length())))
                    + " — the run's pinned base plus the tests. Each candidate is verified "
                    + "against the files its task claims, placed from this commit.");
            } catch (Exception e) {
                log("Failed to commit acceptance tests: " + e.getMessage());
                run.setAcceptanceTestsCommit(null);
            }
            return parkFor;
        } finally {
            // Nothing is left writing on a normal way out. On a failure the calls still going are
            // stopped, and waited for: the tree they write into is about to be removed.
            authoring.shutdownNow();
            try {
                authoring.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (draftCheck != null) {
                draftCheck.close();
            }
            if (ownWorktree) {
                removeTree(worktree); // the branch stays; the files are on it
                if (run.acceptanceTestsCommit() == null) {
                    gitService.deleteCandidateBranch(branch); // nothing on it worth keeping
                }
            }
        }
    }

    /** How many tasks' tests are written at once when nothing says otherwise. */
    static final int TEST_AUTHORING_AT_ONCE = 4;

    /**
     * How many tasks have their tests written at the same time: as many as the test author's
     * model server serves at once when that server is counted ({@code ServerPlaces} - it is, when
     * the author runs on the workers' own server), otherwise {@link #TEST_AUTHORING_AT_ONCE};
     * {@code -Dswarmcoder.testAuthoring.atOnce=N} states it outright, and 1 is the old one at a
     * time. Never more than there are tasks.
     *
     * <p>This is how many are STARTED side by side. Each request any of them sends still takes
     * one of its server's places, so the server is never sent more than it serves at once.
     */
    private int testAuthoringAtOnce(int tasks) {
        int stated = Integer.getInteger("swarmcoder.testAuthoring.atOnce", 0);
        int served = roles == null || roles.testAuthor() == null ? 0
            : com.swarmcoder.inference.ServerPlaces.atOnce(roles.testAuthor().endpoint());
        int atOnce = stated > 0 ? stated : served > 0 ? served : TEST_AUTHORING_AT_ONCE;
        return Math.max(1, Math.min(Math.max(1, tasks), atOnce));
    }

    /**
     * What one task's authoring call produced, waiting for it if it is still going. A failure
     * comes out as itself - an outage of the model server must reach the caller as an outage, so
     * the stage is paused and retried rather than failed.
     */
    private static <T> T writtenBy(java.util.concurrent.Future<T> call) {
        try {
            return call.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the acceptance tests were being "
                + "written", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause == null ? e.toString() : cause.toString(), cause);
        }
    }

    /**
     * A broken acceptance test goes back to the test author (author decision, 2026-09-05): every
     * candidate of one task died on a bug in the test's OWN code, and {@code SwarmEngineImpl}
     * stopped the run rather than run a repair round at candidates that were never broken. This is
     * the one bounded attempt to fix it: the author is asked once, the correction is red-checked and
     * committed on the run's own tests ref exactly as at TEST_AUTHORING, and the SAME candidates are
     * re-verified against it — no new swarm.
     *
     * @return null when the run may simply advance (EXECUTING is retried: the task now has a winner,
     *         or is BLOCKED for an ordinary reason unrelated to the test); otherwise the brief for a
     *         parked run
     */
    private String repairFaultyAcceptanceTest(Run run, TestRepairNeeded fault) {
        if (!fault.suspect()) {
            return repairFaultyAcceptanceTest(run, fault, null);
        }
        // A SUSPECT test (owner decision, 2026-10-04, harness run 79): every first candidate
        // compiled and failed it in the same way. The author is asked which side is wrong. A
        // correction goes through every check a repair does and the candidates already written
        // are re-verified against it. Anything else - the author stands by the test, cannot be
        // asked, or hands in a correction a check refuses - is what happened before this
        // existed: the repair round, then BLOCKED, now with the author's answer in both.
        SuspectReview review = new SuspectReview();
        String problem;
        try {
            problem = repairFaultyAcceptanceTest(run, fault, review);
        } catch (EndpointOutage outage) {
            if (review.settled) {
                throw outage; // the repair round's own outage, not the author's
            }
            problem = "the test author could not be reached: " + outage.getMessage();
        }
        if (review.settled) {
            return problem;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        Task task = graph == null ? null : graph.tasks().stream()
            .filter(t -> t.id().equals(fault.taskId())).findFirst().orElse(null);
        if (task == null || !(swarmEngine instanceof SwarmEngineImpl engine)) {
            return problem;
        }
        String note = review.answer == null
            ? "The test author could not be asked (" + problem + "), so the test stands as written."
            : review.corrected
                ? review.answer + " The correction was not used (" + problem + "), so the test "
                    + "stands as first written."
                : review.answer;
        log("EXECUTING: the acceptance test of '" + task.title() + "' was reviewed. " + note);
        recordTestReview(task, note);
        SwarmEngineImpl.TestRepairOutcome outcome =
            engine.resumeAfterTestReview(task, run.id(), false, note);
        return outcome.stillFaulty() ? outcome.faultMessage() : null;
    }

    /** What the review of a suspect test came to, filled in as the review goes. */
    private static final class SuspectReview {
        /** The author's answer in a sentence; null until the author has answered. */
        String answer;
        /** True once the author handed in a corrected file. */
        boolean corrected;
        /** True once a correction is committed and the engine has been handed the task back. */
        boolean settled;
    }

    /** Writes what the review of a suspect test came to onto the task, for the run report. */
    private void recordTestReview(Task task, String note) {
        AuthoredTests tests = task.authoredTests() == null ? new AuthoredTests() : task.authoredTests();
        tests.setReviewNote(note);
        recordTestAuthoring(task, tests);
    }

    /** The suspect test's author says which side is wrong; a correction, or a failed call. */
    private TestAuthorClient.Authored reviewedCorrection(Run run, Task task, DesignDocument design,
                                                         Path worktree, String targetPath,
                                                         String existingContent,
                                                         TestRepairNeeded fault,
                                                         SuspectReview review) {
        TestAuthorClient.Reviewed reviewed = roles.testAuthor().reviewSuspectTest(worktree, task,
            design, targetPath, existingContent, fault.suspectEvidence(),
            projectSignaturesNow(run, existingContent));
        if (!reviewed.answered()) {
            review.answer = "The test author gave no usable answer (" + reviewed.reason()
                + "), so the test stands as written.";
            return TestAuthorClient.Authored.failed(reviewed.reason());
        }
        if (!reviewed.testIsWrong()) {
            review.answer = "The test author answered that the test is right and the candidates "
                + "are wrong: " + reviewed.reason();
            return TestAuthorClient.Authored.failed("the test author stands by the test");
        }
        review.answer = "The test author answered that the test was wrong and corrected it: "
            + reviewed.reason();
        review.corrected = true;
        return reviewed.corrected();
    }

    /**
     * @param review null for a test known to be broken; for a suspect one, where the outcome of
     *               asking is written. A non-null return with {@code review.settled} false means
     *               no correction is in force.
     */
    private String repairFaultyAcceptanceTest(Run run, TestRepairNeeded fault,
                                              SuspectReview review) {
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        Task task = graph == null ? null : graph.tasks().stream()
            .filter(t -> t.id().equals(fault.taskId())).findFirst().orElse(null);
        if (task == null || repoPath == null || !gitService.isEnabled()) {
            return "the acceptance test '" + fault.testClass() + "' is at fault ("
                + faultInWords(fault) + "), but the task or the repository could not be reached "
                + "to repair it";
        }
        String simpleName = fault.testClass().contains(".")
            ? fault.testClass().substring(fault.testClass().lastIndexOf('.') + 1) : fault.testClass();
        String faultPath = fault.testPath() == null ? null : fault.testPath().replace('\\', '/');
        String targetPath = task.authoredTestPaths().stream()
            .filter(p -> faultPath != null && (faultPath.equals(p.replace('\\', '/'))
                || faultPath.endsWith("/" + p.replace('\\', '/'))))
            .findFirst()
            .orElse(task.authoredTestPaths().stream()
                .filter(p -> p.replace('\\', '/').endsWith("/" + simpleName + ".java"))
                .findFirst()
                .orElse(task.authoredTestPaths().isEmpty() ? null : task.authoredTestPaths().get(0)));
        if (targetPath == null) {
            return "the acceptance test '" + fault.testClass() + "' for task '" + task.title()
                + "' failed inside its own code (" + fault.failureMessage() + "), but this task "
                + "claims no test file to repair";
        }

        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        String redcheckBranch = "swarm/testrepair-redcheck/" + run.id();
        Path redcheckWorktree = WORKTREE_ROOT.resolve("testrepair-redcheck-" + run.id());
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(branch, worktree);
            String existingContent = Files.readString(worktree.resolve(targetPath));
            DesignDocument design = artifactStore.root().designs.get(run.designId());

            // Which of the two faults this is decides what the author is told (harness run 37,
            // 2026-09-25). "Your test crashed inside its own setup" would be false for a test that
            // compiled, called the TeaVM client, and hit a native method the JVM does not have.
            BrowserOnlyCode.Survey survey = browserOnlySurvey(repoLayout());
            // The third fault (brownfield harness run 43, 2026-09-26): the test never compiled for
            // any candidate, on a line that misuses code which already exists. The author is told
            // the compiler's words and the real signatures, read off the tests worktree — the
            // run's pinned base plus the tests, i.e. the code as it was before any candidate.
            // compile_test works in all three repairs: a throwaway tree in a container, like first
            // authoring (compilingRepairsOf).
            TestAuthorClient.Authored authored;
            try (TestAuthorClient.Scope compiling = compilingRepairsOf(run, task)) {
                authored = review != null
                    ? reviewedCorrection(run, task, design, worktree, targetPath, existingContent,
                        fault, review)
                    : fault.doesNotCompile()
                    ? roles.testAuthor().repairBrokenTest(worktree, task, design,
                        Map.of(targetPath, existingContent), compileFaultReask(fault, worktree,
                            task, graph.tasks()))
                    : fault.reachedBrowserOnlyCode()
                    ? roles.testAuthor().repairUnrunnableTest(worktree, task, design,
                        Map.of(targetPath, existingContent),
                        AcceptanceTestReach.reaskForFailure(fault.browserOnlyReason(),
                            describeFault(fault), survey))
                    : roles.testAuthor().repairFailingTest(
                        worktree, task, design, targetPath, existingContent, describeFault(fault),
                        projectSignaturesNow(run, existingContent));
            }
            if (authored.failureReason() != null) {
                return stillFaultyBrief(task, fault, "the test author's repair call did not "
                    + "produce a corrected file: " + authored.failureReason());
            }
            String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
            if (ruleBreak != null) {
                return ruleBreak;
            }

            // THE VOCABULARY CHECK, exactly as at TEST_AUTHORING: a correction may still name only
            // the design's contracts and what the checkout already holds.
            AcceptanceTestVocabulary.Check vocabulary =
                AcceptanceTestVocabulary.check(worktree, design, authored.paths(), graph.tasks());
            if (!vocabulary.ok()) {
                return stillFaultyBrief(task, fault, "the correction names "
                    + vocabulary.unknowns().size() + " type(s) nothing in this plan delivers: "
                    + vocabulary.unknowns().stream()
                        .map(AcceptanceTestVocabulary.Unknown::typeName).toList());
            }

            // AND THE SELF-IMPLEMENTATION CHECK, exactly as at TEST_AUTHORING: a correction may not
            // "fix" a failing test by supplying the behaviour itself. Harness run 30 is the whole
            // reason this method has this line — the repair there moved three lines of the test's
            // own anonymous BookService and both candidates then passed without running.
            SelfImplementedContract.Check selfImplemented =
                SelfImplementedContract.check(worktree, design, authored.paths(), graph.tasks());
            if (!selfImplemented.ok()) {
                return SelfImplementedContract.brief(task.title(), selfImplemented);
            }

            // AND THE REACH CHECK, exactly as at TEST_AUTHORING (harness run 37): a correction may
            // not keep calling code that only a browser can run — whichever fault sent it here.
            AcceptanceTestReach.Check reach = AcceptanceTestReach.check(worktree, survey, authored.paths());
            if (!reach.ok()) {
                return AcceptanceTestReach.brief(task.title(), reach);
            }

            // AND THE REFLECTION CHECK (live harness run 54): the repair there found a Store
            // constructor by reflection a second time. A correction may not guess either.
            AcceptanceTestReflection.Check reflection =
                AcceptanceTestReflection.check(worktree, authored.paths());
            if (!reflection.ok()) {
                return AcceptanceTestReflection.brief(task.title(), reflection);
            }

            // THE RED-CHECK, exactly as at TEST_AUTHORING: a throwaway worktree cut from the OLD
            // tests commit (== run.verificationPoint() before this method touches anything), reduced
            // to this task's own claimed files, with the correction dropped in over the old file —
            // it must still fail on the pre-change tree.
            Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
            if (spec.isPresent() && spec.get().acceptance() != null && !spec.get().acceptance().isEmpty()) {
                try {
                    removeTree(redcheckWorktree);
                    gitService.addOrResumeWorktree(redcheckBranch, redcheckWorktree,
                        run.verificationPoint(), run.id().toString());
                    AcceptanceOverlay.reduceTo(gitService, redcheckWorktree, task.acceptanceTestDir(),
                        run.acceptanceTestsCommit(), task.authoredTestPaths());
                    Path redcheckTarget = redcheckWorktree.resolve(targetPath);
                    Files.createDirectories(redcheckTarget.getParent());
                    Files.writeString(redcheckTarget, Files.readString(worktree.resolve(targetPath)));
                    RedChecker.RedCheckResult redResult = new RedChecker()
                        .check(buildTarget(redcheckWorktree), spec.get(),
                            earlierTestsIn(redcheckWorktree, task));
                    // A correction that does not compile for a reason no task can fix is not
                    // red, however the counts read (brownfield harness run 43) — whichever of the
                    // three faults it was meant to correct.
                    AcceptanceCompileErrors.Reading miscompiled =
                        miscompiledReading(redcheckWorktree, redResult, task, graph.tasks());
                    if (miscompiled != null) {
                        return stillFaultyBrief(task, fault, "the corrected test does not compile "
                            + "for a reason no task in this plan can fix:\n"
                            + miscompiled.quoted());
                    }
                    if (!redResult.red() && review != null) {
                        // A suspect test's correction gets no second go: the original stands.
                        return "the corrected test passes on the tree before any candidate's "
                            + "change, so it would prove nothing";
                    }
                    if (!redResult.red()) {
                        // A REPAIRED TEST THAT IS GREEN WITH NO CANDIDATE APPLIED IS A TAUTOLOGY
                        // (author decision, 2026-09-05, harness run 30). It measures itself: every
                        // candidate would "pass" it without a line of its own code being executed,
                        // and the story would be stamped delivered on it. The author gets the one
                        // bounded attempt it gets everywhere else in this file, and then the run
                        // parks. See SelfImplementedContract#tautologyReask.
                        String park = repairTautologicalTest(run, task, fault, design, targetPath,
                            worktree, redcheckWorktree, spec.get(), redResult.note(),
                            graph.tasks());
                        if (park != null) {
                            return park;
                        }
                    }
                } finally {
                    removeTree(redcheckWorktree);
                    gitService.deleteCandidateBranch(redcheckBranch);
                }
            }

            // Commit the correction on the run's own tests ref, and point the run at it — the same
            // ref and the same field authorAcceptanceTests wrote at TEST_AUTHORING.
            gitService.commitAll(worktree, "Repair acceptance test " + fault.testClass()
                + " for run " + run.id() + "\n\n" + faultInWords(fault));
            run.setAcceptanceTestsCommit(gitService.headSha(branch));
        } catch (IOException e) {
            return stillFaultyBrief(task, fault, "reading or writing the test file failed: "
                + e.getMessage());
        } finally {
            removeTree(worktree); // the branch stays; the correction is on it
        }

        String note = testRepairedSentence(fault);
        log("EXECUTING: " + note);
        recordTestRepair(task, note);
        if (review != null) {
            recordTestReview(task, review.answer);
        }

        if (!(swarmEngine instanceof SwarmEngineImpl engine)) {
            return "the acceptance test for task '" + task.title() + "' was repaired, but this "
                + "workflow's swarm engine cannot re-verify candidates against it";
        }
        if (review != null) {
            review.settled = true;
        }
        SwarmEngineImpl.TestRepairOutcome outcome = review == null
            ? engine.resumeAfterTestRepair(task, run.id())
            : engine.resumeAfterTestReview(task, run.id(), true, review.answer);
        if (outcome.stillFaulty()) {
            return "the acceptance test for task '" + task.title() + "' is broken and could not be "
                + "repaired: " + outcome.faultMessage();
        }
        return null; // the task now has a winner, or is BLOCKED for an ordinary reason
    }

    /**
     * The one bounded attempt to fix a repaired acceptance test that turned out to be GREEN on the
     * tree before any candidate ran (author decision, 2026-09-05, harness run 30).
     *
     * <p>A test that passes with nothing delivered proves nothing about anybody's code. It is not a
     * candidate's fault and it is not a compile problem; it is a test that measures itself, and it
     * is invisible from here on, because from the next stage every candidate simply "passes" it.
     * So the author is asked once — with the sentence that says how the application obtains the
     * delivered code — the correction is re-checked on the same throwaway tree, and a second miss
     * parks the run.
     *
     * @param worktree         the run's tests worktree, holding the repaired file the caller will
     *                         commit; the correction is written here, over it
     * @param redcheckWorktree the throwaway tree cut from the run's pinned base, already reduced to
     *                         this task's claimed files
     * @param planTasks        every task in this run's plan, so the wiring sentence and the
     *                         self-implementation re-check can recognise a concrete implementation
     *                         class a task's write set promises (harness run 38, 2026-09-25)
     * @return null when the correction is red again and the caller may carry on; otherwise the brief
     *         for a parked run
     */
    private String repairTautologicalTest(Run run, Task task, TestRepairNeeded fault,
                                          DesignDocument design, String targetPath, Path worktree,
                                          Path redcheckWorktree, VerifySpec spec, String firstNote,
                                          List<Task> planTasks)
            throws IOException {
        String wiring = SelfImplementedContract.wiringFor(worktree, design, planTasks);
        log("EXECUTING: the repaired acceptance test " + fault.testClass() + " PASSES with nothing "
            + "delivered — it proves nothing about any candidate. Asking its author once to make it "
            + "exercise the delivered code.");
        Map<String, String> existing = new LinkedHashMap<>();
        existing.put(targetPath, Files.readString(worktree.resolve(targetPath)));
        TestAuthorClient.Authored authored;
        try (TestAuthorClient.Scope compiling = compilingRepairsOf(run, task)) {
            authored = roles.testAuthor().repairBrokenTest(
                worktree, task, design, existing, SelfImplementedContract.tautologyReask(wiring));
        }
        if (authored.failureReason() != null) {
            return SelfImplementedContract.tautologyBrief(task.title(), fault.testClass(), wiring,
                "the test author's second repair call did not produce a corrected file: "
                    + authored.failureReason());
        }
        String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
        if (ruleBreak != null) {
            return ruleBreak;
        }
        SelfImplementedContract.Check selfImplemented =
            SelfImplementedContract.check(worktree, design, authored.paths(), planTasks);
        if (!selfImplemented.ok()) {
            return SelfImplementedContract.brief(task.title(), selfImplemented);
        }
        for (String path : authored.paths()) {
            Path target = redcheckWorktree.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, Files.readString(worktree.resolve(path)));
        }
        RedChecker.RedCheckResult second = new RedChecker()
            .check(buildTarget(redcheckWorktree), spec);
        if (!second.red()) {
            return SelfImplementedContract.tautologyBrief(task.title(), fault.testClass(), wiring,
                "the first repair was green on that tree (" + firstNote + ") and so is the second ("
                    + second.note() + ")");
        }
        log("EXECUTING: the acceptance test " + fault.testClass() + " was corrected by its author "
            + "and is red again on the tree before any candidate — it now measures delivered code.");
        return null;
    }

    /** The one sentence the run log, the task's badge and the judge all read — see {@code JudgeClient}. */
    private static String testRepairedSentence(TestRepairNeeded fault) {
        return "acceptance test " + fault.testClass()
            + (fault.testMethod() == null || fault.testMethod().isBlank() ? "" : "#" + fault.testMethod())
            + " was repaired by the test author after "
            + (fault.suspect()
                ? "every first candidate failed it with the same assertion: "
                    + fault.failureMessage()
                : fault.doesNotCompile()
                ? "no candidate could make it compile: " + fault.failureMessage()
                : fault.reachedBrowserOnlyCode()
                ? "every candidate showed that " + fault.browserOnlyReason()
                : "failing inside its own code: " + fault.failureMessage());
    }

    /** What was wrong with the test, in the words of whichever of the three faults this is. */
    private static String faultInWords(TestRepairNeeded fault) {
        return fault.suspect()
            ? "Every first candidate compiled and failed it with the same assertion: "
                + fault.failureMessage()
            : fault.doesNotCompile()
            ? "It did not compile for any candidate, on a line no candidate could fix: "
                + fault.failureMessage()
            : fault.reachedBrowserOnlyCode()
            ? "It reached code that can only run in a browser: " + fault.browserOnlyReason()
            : "Failed inside its own code: " + fault.failureMessage();
    }

    /**
     * What the test author is told about a test that did not compile for any candidate (brownfield
     * harness run 43, 2026-09-26): the verification's compiler lines, read back with
     * {@link AcceptanceCompileErrors} exactly as a red check's output is, so the words and the
     * real signatures are the same ones TEST_AUTHORING would have given. When the lines cannot be
     * read as a broken error (they always should be — verification raised this only for a misuse),
     * the compiler's lines are quoted as they are.
     */
    private String compileFaultReask(TestRepairNeeded fault, Path tree, Task task,
                                     List<Task> planTasks) {
        AcceptanceCompileErrors.Reading reading = MiscompiledAcceptanceTest.read(tree,
            fault.compilerLines(), task.authoredTestPaths(), planTasks);
        if (!reading.isBroken()) {
            reading = AcceptanceCompileErrors.classify(fault.compilerLines(),
                task.authoredTestPaths(), List.of(), AcceptanceCompileErrors.PlannedChanges.NONE,
                null);
        }
        if (!reading.isBroken()) {
            return "Your test does not compile for any candidate, and no candidate may edit it. "
                + "The compiler's own words:\n\n" + fault.compilerLines() + "\n\nCorrect the test "
                + "so it compiles against the code as it exists while proving the SAME check. "
                + "Reply with the same JSON object, with the corrected file(s).";
        }
        // With the library's real members, exactly as at wave start (live run 63, 2026-10-02: this
        // call read the project tree only, the type at fault lived in a jar, and the author
        // answered that it could not fix the test without knowing that type's API).
        return MiscompiledAcceptanceTest.reask(reading, signaturesFor(tree, reading, task));
    }

    /**
     * The public constructors and methods of the project types the failing test touches, read from
     * the tree the current wave was cut from (live harness run 54, 2026-10-01). By the time a test
     * fails inside its own code at EXECUTING, the earlier waves' code exists there — the code the
     * test author could not see when it first wrote the test. Blank when the run has no such tree
     * or it cannot be read; the repair prompt is then exactly what it was.
     */
    private String projectSignaturesNow(Run run, String testSource) {
        String point = run.progressPoint();
        if (point == null || point.isBlank() || repoPath == null) {
            return "";
        }
        String branch = "swarm/testrepair-signatures/" + run.id();
        Path tree = WORKTREE_ROOT.resolve("testrepair-signatures-" + run.id());
        try {
            removeTree(tree);
            gitService.addOrResumeWorktree(branch, tree, point, run.id().toString());
            return TouchedProjectTypes.signatures(tree, testSource);
        } catch (IOException | RuntimeException e) {
            log("could not read the project's current signatures for the test repair: "
                + e.getMessage());
            return "";
        } finally {
            try {
                removeTree(tree);
                gitService.deleteCandidateBranch(branch);
            } catch (RuntimeException ignored) {
                // a leftover is removed by the next attempt's removeWorktree
            }
        }
    }

    /**
     * The exception, its message and its kept stack frames, in the one paragraph both the test
     * author's repair prompt and a park brief read — the same shape
     * {@code Verdicts.FailureSummary.fullText()} gives a repair worker for an ordinary failure.
     */
    private static String describeFault(TestRepairNeeded fault) {
        StringBuilder sb = new StringBuilder(fault.testClass());
        if (fault.testMethod() != null && !fault.testMethod().isBlank()) {
            sb.append('#').append(fault.testMethod());
        }
        sb.append(" — ").append(fault.failureMessage() == null || fault.failureMessage().isBlank()
            ? "no message" : fault.failureMessage());
        if (fault.frames() != null && !fault.frames().isBlank()) {
            sb.append('\n').append(fault.frames());
        }
        return sb.toString();
    }

    /** Writes the repaired-test note onto the task's {@link AuthoredTests} so its badge shows it. */
    private void recordTestRepair(Task task, String note) {
        AuthoredTests tests = task.authoredTests() == null ? new AuthoredTests() : task.authoredTests();
        tests.setRepairedNote(note);
        recordTestAuthoring(task, tests);
    }

    /** The park brief for "the test author was asked once, and the acceptance test is still broken". */
    private static String stillFaultyBrief(Task task, TestRepairNeeded fault, String why) {
        return "the acceptance test for task '" + task.title() + "' is broken and could not be "
            + "repaired: " + why + " (" + (fault.suspect()
                ? "originally every first candidate failed it with the same assertion: "
                    + fault.failureMessage()
                : fault.doesNotCompile()
                ? "originally it did not compile for any candidate: " + fault.failureMessage()
                : fault.reachedBrowserOnlyCode()
                ? "originally " + fault.browserOnlyReason()
                : "it originally failed inside its own code: " + fault.failureMessage()) + ")";
    }

    /**
     * Writes what the test author is doing, or did, onto the task, so the run graph can show it.
     *
     * <p>A display fact, never a reason to stop: a store that will not take it costs the badge and
     * nothing else. The task object is the one inside the persisted graph, so the graph publisher
     * sees the change on its next poll without anything being re-read.
     */
    private void recordTestAuthoring(Task task, AuthoredTests record) {
        task.setAuthoredTests(record);
        try {
            artifactStore.saveTask(task);
        } catch (RuntimeException e) {
            log("TEST_AUTHORING: could not record the test-authoring progress of task '"
                + task.title() + "' for the run graph: " + e.getMessage());
        }
    }

    /**
     * Why nothing was written — as one of two separate answers, never both at once.
     *
     * <p>This line used to read "Test author wrote no acceptance tests (no criteria or author
     * unavailable)". Those are two unrelated failures with completely different owners: a plan that
     * links no checks to any task, and a test-author endpoint that is down. On 2026-08-30 it was
     * the first, both endpoints were healthy, and the sentence sent the diagnosis at the endpoints
     * for an afternoon. A message that names two causes names none.
     *
     * <p>It also used to end "check the testAuthor endpoint" whenever the plan was fine — a
     * second guess, and wrong at least once (2026-09-03): the endpoint answered every time, but
     * its reply was not valid JSON. {@code authorFailures} is what the author itself said went
     * wrong, one entry per task that failed, and is quoted here instead of guessed at.
     */
    static String noTestsWrittenReason(int taskCount, int criteriaOffered, StoryScope scope,
                                       List<String> authorFailures) {
        if (criteriaOffered > 0) {
            String why = authorFailures == null || authorFailures.isEmpty()
                ? "the test author produced no files, and gave no reason why."
                : "the test author's own words: " + String.join(" ", authorFailures);
            return "No acceptance tests were written even though the test author was given "
                + criteriaOffered + " check(s) across " + taskCount + " task(s). The plan is fine; "
                + why;
        }
        String answersFor = scope == null || scope.isEmpty()
            ? "This run answers for no checks of its own"
            : "This run answers for " + scope.criteria().size() + " check(s) — "
                + String.join(", ", scope.criterionRefs()) + " — and the plan links none of them "
                + "to a task";
        return "No acceptance tests were written because not one of the " + taskCount
            + " task(s) in this plan carries an acceptance check to write a test for. "
            + answersFor + ". The test author was never asked for anything, so this is a PLAN "
            + "defect, not a test-author failure.";
    }

    /**
     * Why a task that answers for checks has no acceptance-test file to run them against —
     * checked per task, immediately before dispatch (see {@link #redCheckFailure}).
     *
     * <p>Used to end "Check the testAuthor endpoint, then resume the run" whenever the plan
     * itself was the cause — a second guess, and wrong at least once (2026-09-03): the endpoint
     * answered every time. Two honest causes are told apart instead, the same way
     * {@link #noTestsWrittenReason} tells apart its two: the plan put this check on more than one
     * task, so the file exists but belongs to a DIFFERENT task than the one being checked here
     * ({@link TaskGraphValidator#validate} now normalises this away for a live plan, so seeing it
     * means an older stored graph, not a fresh PLAN defect); or nothing was written for the check
     * anywhere, which is a test-authoring question, not this method's to answer.
     *
     * <p>For that last case, "not this method's to answer" used to mean a fixed sentence blaming
     * nothing in particular ("Nothing recorded during test authoring explains why it was
     * skipped") — run 16, 2026-09-04: the test author returned a reply that parsed but named no
     * files, twice, and the honest reason it gave up was thrown away the moment the stage moved
     * on, leaving this method with nothing to quote but the generic line. {@link TestAuthorClient}
     * now carries that reason onto {@link AuthoredTests#failureReason()} at authoring time (see
     * {@link AuthoredTestAudit#written}), and it is quoted here in place of the guess whenever one
     * was recorded.
     */
    static String noTestFileReason(Task task, List<AcceptanceCriterion> checks, StoryScope scope,
                                   List<Task> allTasks) {
        String refs = String.join(", ", refsFor(checks, scope));
        for (AcceptanceCriterion check : checks) {
            CriterionEvidence.TestRef ref = CriterionEvidence.TestRef.parse(check.testClassOrFile());
            String simple = ref == null || ref.className() == null || ref.className().isBlank()
                ? null : ref.className().substring(ref.className().lastIndexOf('.') + 1);
            if (simple == null) {
                continue;
            }
            for (Task other : allTasks) {
                if (other == task || other.authoredTestPaths().stream().noneMatch(p -> p.contains(simple))) {
                    continue;
                }
                String checkRef = scope == null ? null : scope.refFor(check.id());
                return "Task '" + task.title() + "' answers for " + checks.size() + " check(s) — "
                    + refs + " — but the test file for " + (checkRef == null ? "one of them"
                    : checkRef) + " was written for task '" + other.title() + "' instead. The plan "
                    + "put this check on more than one task; a check is answered by exactly one "
                    + "task, so only that task ever gets a test file. Fix the plan, then resume "
                    + "the run.";
            }
        }
        String failureReason = task.authoredTests() == null ? null : task.authoredTests().failureReason();
        String why = failureReason != null ? failureReason
            : "Nothing recorded during test authoring explains why it was skipped — look at this "
                + "task's TEST_AUTHORING log, then resume the run.";
        return "Task '" + task.title() + "' answers for " + checks.size() + " check(s) — " + refs
            + " — and the test author wrote no test file for it. Nothing could ever prove those "
            + "checks, so every candidate of this task would fail verification after a full "
            + "swarm. " + why;
    }

    /** The human refs (R7:C1) for a task's criteria, parallel to them; blank where unknown. */
    private static List<String> refsFor(List<AcceptanceCriterion> criteria, StoryScope scope) {
        List<String> refs = new ArrayList<>();
        for (AcceptanceCriterion criterion : criteria) {
            String ref = scope == null ? null : scope.refFor(criterion.id());
            refs.add(ref == null ? "(a check this task owns)" : ref);
        }
        return refs;
    }

    /**
     * The Librarian front-loads knowledge per task (spec §13): manifest coordinates plus
     * internal API signatures for the read set, persisted as KnowledgeBriefs and joined to
     * tasks so dispatch renders them into the shared prefix.
     */
    private TaskGraph attachKnowledgeBriefs(Run run, TaskGraph tg) {
        return attachKnowledgeBriefs(run, tg, null);
    }

    /**
     * @param run     the run these tasks belong to — its kind and its goal decide whether the
     *                brief carries the neighbourhood of a change (design §6). A GREENFIELD or
     *                REFACTOR run carries none, so its brief is byte-for-byte what it was.
     * @param offline the "libraries you may ADD" list, or null when this project has no local Maven
     *                repository to check against — the brief is then exactly what it was
     */
    private TaskGraph attachKnowledgeBriefs(Run run, TaskGraph tg, OfflineLibraryBrief offline) {
        if (librarian == null || repoPath == null) {
            return tg;
        }
        Librarian.ChangeRequest change = changeRequestOf(run);
        List<Task> withBriefs = new ArrayList<>();
        for (Task task : tg.tasks()) {
            KnowledgeBrief brief = librarian.assembleBrief(repoPath, task, offline, change);
            if (brief.renderedMarkdown() == null || brief.renderedMarkdown().isBlank()) {
                withBriefs.add(task);
                continue;
            }
            try {
                artifactStore.append(() -> {
                    artifactStore.root().briefs.put(brief.id(), brief);
                    return null;
                }).get();
            } catch (Exception e) {
                log("Failed to persist KnowledgeBrief: " + e.getMessage());
                withBriefs.add(task);
                continue;
            }
            withBriefs.add(withKnowledgeBrief(task, brief.id()));
            log("KnowledgeBrief attached to '" + task.title() + "' ("
                + brief.libraries().size() + " libraries, " + brief.internalApis().size() + " internal APIs)");
        }
        return new TaskGraph(tg.id(), tg.revision(), tg.designId(), withBriefs, tg.dependencies());
    }

    /**
     * Joins a knowledge brief to a task <b>without losing anything else the task carries</b>.
     *
     * <p>This used to build a replacement task with {@code new Task(…, brief.id(), …)}. That
     * constructor takes twelve of the task's seventeen fields, so the replacement silently arrived
     * with no {@code criterionIds}, no {@code requirementIds} and no {@code storyId} — the three
     * links that say which acceptance checks the task is answerable for, which requirements it
     * traces to, and which story owns it.
     *
     * <p>It broke every greenfield run, invisibly, because it happens AFTER the plan is validated.
     * Run {@code c7d6bcad} on 2026-08-30: the planner claimed all three of the story's checks
     * correctly, {@link TaskGraphValidator} accepted the graph without a single warning, this
     * method then stripped the claims off all three tasks, and the graph that reached the store
     * linked nothing. The test author was handed an empty criteria list for every task, wrote no
     * tests, and the red-check parked the run for an operator whose acceptance selector was fine.
     *
     * <p>Attaching the brief in place is deliberate: a partial copy is the whole defect, and there
     * is no copy here to get wrong. {@code ArtifactStore.copyOf(Task)} remains the one place that
     * copies a task, and it copies all seventeen fields.
     */
    static Task withKnowledgeBrief(Task task, UUID briefId) {
        task.setKnowledgeBriefId(briefId);
        return task;
    }

    /**
     * Applies the story's own worker count to every task of the graph, and says out loud which of
     * the three layers decided it.
     *
     * <p>Three layers, most specific first: the story, then the project's {@code project.yaml},
     * then the settings file. The lower two are already baked into the policy the architect
     * stamped, together with the words explaining which of them won, so this only has to look at
     * the story — and it has to SAY the answer either way. A number that silently comes from
     * somewhere is how {@code splitAcrossFamilies} spent months reading as switched on while doing
     * nothing at all.
     *
     * <p>The sentence goes to the run log, which is the run's own record, and the reason is stamped
     * on each task's policy, which is persisted with the task graph. So "why did this get four
     * attempts?" is answerable from a finished run months later, with nobody having kept a console
     * window open.
     */
    private TaskGraph withStorySizing(TaskGraph graph, StoryScope scope) {
        SwarmPolicy base = roles.architect().taskPolicy();
        Story story = scope == null ? null : scope.story();
        Integer stated = story == null ? null : story.workersPerTask();
        if (stated == null || stated < SwarmSizing.MINIMUM) {
            if (base != null) {
                log("Each piece of work gets " + base.n() + (base.n() == 1 ? " worker" : " workers")
                    + (base.nSource() == null ? "." : ", because " + base.nSource() + "."));
            }
            return graph;
        }
        SwarmSizing sizing = SwarmSizing.resolve(stated, story.key(), null, null);
        for (Task task : graph.tasks()) {
            SwarmPolicy policy = task.swarmPolicy() != null ? task.swarmPolicy() : base;
            if (policy != null) {
                task.setSwarmPolicy(policy.withWorkers(sizing));
            }
        }
        log(sizing.sentence() + "."
            + (base == null || base.n() == sizing.workersPerTask() ? ""
                : " Without that it would have been " + base.n() + "."));
        return graph;
    }

    /**
     * Applies the story's own per-worker TURN allowance to every task of the graph, and says which
     * of the three layers decided it.
     *
     * <p>Exactly the shape of {@link #withStorySizing}, for exactly the same reason: the project
     * and settings layers are already baked into the policy the architect stamped, and the story is
     * the first layer that only exists here. Separate from the worker count because a story can
     * want one without the other — a long grind against a slow build needs turns, not attempts.
     */
    private TaskGraph withStoryTurnAllowance(TaskGraph graph, StoryScope scope) {
        SwarmPolicy base = roles.architect().taskPolicy();
        Story story = scope == null ? null : scope.story();
        Integer stated = story == null ? null : story.maxToolTurns();
        if (stated == null || stated <= 0) {
            if (base != null && base.turnSource() != null) {
                log("Each worker gets " + base.maxToolTurns() + " tool turns, because "
                    + base.turnSource() + ".");
            }
            return graph;
        }
        TurnAllowance allowance = TurnAllowance.resolve(stated, story.key(), null, null);
        for (Task task : graph.tasks()) {
            SwarmPolicy policy = task.swarmPolicy() != null ? task.swarmPolicy() : base;
            if (policy != null) {
                task.setSwarmPolicy(policy.withTurns(allowance));
            }
        }
        log(allowance.sentence() + "."
            + (base == null || base.maxToolTurns() == allowance.maxToolTurns() ? ""
                : " Without that it would have been " + base.maxToolTurns() + "."));
        return graph;
    }

    /**
     * Stamps the acceptance-test directory this repository can actually run tests in onto every
     * task in a planner-produced graph.
     *
     * <p>Applied here rather than in {@link ArchitectClient} because this is the only place that
     * knows the operator's checkout. There used to be a second graph source — a single-task
     * fallback used when the planner could not produce anything usable — and the two drifted apart
     * once: the fallback left the directory null and silently disabled the test author on exactly
     * the path that existed to rescue a failed plan. The fallback itself is gone (2026-09-03): a
     * plan that claims a slice it did not actually decompose is worse than PLAN parking and saying
     * so, so there is now exactly one graph source and nothing left to drift.
     */
    private static TaskGraph withAcceptanceTestDir(TaskGraph graph, String dir) {
        if (graph == null || dir == null || dir.isBlank()) {
            return graph;
        }
        for (Task task : graph.tasks()) {
            task.setAcceptanceTestDir(dir);
        }
        return graph;
    }

    /**
     * Null when dispatch may proceed; otherwise the human-readable reason the acceptance tests are
     * not in a valid red state.
     *
     * <p><b>Per task, on the tree that task will be verified on.</b> Each task's candidates are
     * verified against exactly the tests the task claims, placed onto the run's pinned base — so
     * that is what the red-check looks at, task by task: a throwaway worktree cut from the run's
     * tests commit, reduced to one task's files at a time. It used to run once over the operator's
     * live checkout, which held every task's tests together plus whatever earlier runs had left
     * there; a compile failure in any of those read as "red" for all of them, and a task whose own
     * tests already passed could hide behind a neighbour's failure. Two stages looking at different
     * trees is how the acceptance stage executed nothing for a week without anybody noticing.
     *
     * <p>Skips silently when no repo or no acceptance commands are configured (M1 allowance) and
     * when a task's tests genuinely fail (= red). Parks when a task claims checks and has no test
     * to fail — the test author wrote nothing for it, and dispatching a swarm at it would burn the
     * whole swarm and a repair round before dying with the same message a stage later.
     */
    String redCheckFailure(Run run, StoryScope scope) {
        if (repoPath == null) {
            return null;
        }
        Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
        if (spec.isEmpty() || spec.get().acceptance() == null || spec.get().acceptance().isEmpty()) {
            return null;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (!gitService.isEnabled() || graph == null || graph.tasks() == null) {
            // No repository to cut a worktree from: the only tree there is, as it always was.
            try {
                return redCheckOne(buildTarget(repoPath), spec.get(), scope, null, run,
                    null, List.of()).park();
            } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
                log("Red-check was not run: " + e.getMessage());
                return noContainerPark("The red check", e);
            } finally {
                buildBoxes.release(repoPath);
            }
        }

        List<Task> claiming = new ArrayList<>();
        for (Task task : graph.tasks()) {
            if (!task.authoredTestPaths().isEmpty()) {
                claiming.add(task);
                continue;
            }
            List<AcceptanceCriterion> checks = criteriaFor(task, scope);
            if (!checks.isEmpty()) {
                return noTestFileReason(task, checks, scope, graph.tasks());
            }
        }
        if (claiming.isEmpty()) {
            if (scope != null && !scope.isEmpty()) {
                return "No task in this plan has an acceptance test to run, yet the run answers "
                    + "for " + scope.criteria().size() + " check(s): "
                    + String.join(", ", scope.criterionRefs()) + ". Dispatching the swarm now "
                    + "would select between candidates on no evidence whatever.";
            }
            log("Red-check skipped: no task claims a check, so there is nothing to be red");
            return null;
        }

        // Every claiming task, on the pinned base plus its own tests, before any worker runs. This
        // is the CHEAP gate and it stays exactly where it was: a test that already passes here, or
        // a stage that executes nothing, is a reason to stop before a swarm is spent, whichever
        // wave the task is in.
        //
        // For a task in a LATER wave it is not the whole story, and it cannot be. Its test names
        // classes an earlier task has not delivered yet, so the only verdict available here is
        // "does not compile", which counts as red and says nothing about whether the test is a
        // real test. That question is asked again when the task's own wave starts, on the tree the
        // wave is cut from - see redCheckWave.
        List<List<Task>> waves = SwarmEngineImpl.topologicalWaves(graph);
        List<Task> later = waves.size() < 2 ? List.of()
            : claiming.stream().filter(task -> !waves.get(0).contains(task)).toList();
        if (!later.isEmpty()) {
            log("Red-check: " + later.size() + " task(s) build on work that has not happened yet, "
                + "so they are checked AGAIN when their own wave starts, on the tree that wave is "
                + "cut from: " + later.stream().map(Task::title).toList());
        }
        // The classifier at TEST_AUTHORING needs to know who might still deliver a missing symbol,
        // and at this point NOTHING has run yet - so that is every task in the plan, not just the
        // "claiming" ones (an enabler task with no test of its own can still deliver a contract a
        // later task's test needs).
        nothingLeftToProve.remove(run.id());
        String park =
            redCheckTasks(run, scope, claiming, run.verificationPoint(), "0", null, graph.tasks());
        if (park != null) {
            return park;
        }
        return dropTasksWithNothingLeftToProve(run, graph);
    }

    /**
     * Per run: the tasks whose every check was accepted at TEST_AUTHORING as already satisfied by
     * the start tree and whose delivered contracts are already in that tree. Filled by {@link
     * #recordAlreadySatisfied}, read once by {@link #dropTasksWithNothingLeftToProve}.
     */
    private final Map<UUID, Set<UUID>> nothingLeftToProve =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Writes down that a task's checks are already satisfied by the code the run starts from,
     * with the evidence: the tests that passed and the commit they passed on. Never throws, like
     * {@link #recordAlreadyProved}, whose record this shares - the judge is told the same way.
     */
    private void recordAlreadySatisfied(Run run, StoryScope scope, Task task, VerifySpec spec,
                                        Path tree, AlreadySatisfied.Evidence evidence,
                                        TestResults results) {
        List<AcceptanceCriterion> checks = criteriaFor(task, scope);
        List<String> refs = refsFor(checks, scope);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            lines.add((i < refs.size() ? refs.get(i) : "(a check this task owns)")
                + " — " + checks.get(i).text());
        }
        boolean userFacing = UserFacingWording.anyUserFacing(checks);
        // nothingDeliveredYet stays false: that flag fails a candidate for surviving on a test
        // that measures itself, and this record exists because the test was shown not to.
        ChecksAlreadyProved record = new ChecksAlreadyProved(Instant.now(), run.verificationPoint(),
            evidence.tests(), lines, results == null ? evidence.tests().size() : results.passed(),
            userFacing, whereTheRestIsProved(userFacing, checks, spec), false);
        record.setBeforeTheRun(true);
        task.setChecksAlreadyProved(record);
        try {
            artifactStore.saveTask(task);
        } catch (RuntimeException e) {
            log("Could not record that the checks of task '" + task.title() + "' are already "
                + "satisfied: " + e.getMessage() + ". The run carries on; only the record is lost.");
        }
        log(record.describe(task.title()));
        // Whether the task has anything left to build is decided once every task has been
        // looked at; what is established here is only that it has nothing left to PROVE. A
        // contract it was to deliver that is not in the tree yet is still work.
        boolean contractsPresent = true;
        try {
            contractsPresent = task.deliveredContracts() == null
                || task.deliveredContracts().isEmpty() || tree == null
                ? task.deliveredContracts() == null || task.deliveredContracts().isEmpty()
                : com.swarmcoder.knowledge.ContractDelivery
                    .shortfalls(tree, task.deliveredContracts()).isEmpty();
        } catch (RuntimeException e) {
            contractsPresent = false;
        }
        if (contractsPresent) {
            nothingLeftToProve.computeIfAbsent(run.id(),
                id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(task.id());
        } else {
            log("Task '" + task.title() + "' stays in the plan: a contract it delivers is not in "
                + "the tree yet.");
        }
    }

    /**
     * Drops the tasks that have nothing left to prove and that nothing kept builds on (see
     * {@link AlreadySatisfied#droppable}). Their test files move onto the run, so the final
     * integration still runs them on the merged tree.
     *
     * @return null to go on; the reason to park when every task of the plan would be dropped -
     *         there is then nothing to build and nothing to integrate, and whether the story is
     *         simply delivered is not this stage's to decide
     */
    private String dropTasksWithNothingLeftToProve(Run run, TaskGraph graph) {
        Set<UUID> eligible = nothingLeftToProve.remove(run.id());
        if (eligible == null || eligible.isEmpty()) {
            return null;
        }
        DesignDocument design = run.designId() == null ? null
            : artifactStore.root().designs.get(run.designId());
        Set<UUID> drop = AlreadySatisfied.droppable(graph, design, eligible);
        if (drop.isEmpty()) {
            return null;
        }
        List<Task> dropped = graph.tasks().stream().filter(t -> drop.contains(t.id())).toList();
        List<Task> kept = graph.tasks().stream().filter(t -> !drop.contains(t.id())).toList();
        if (kept.isEmpty()) {
            // Owner decision, 2026-10-03: this used to park ("nothing left to build"). The
            // story is delivered without building anything instead: nothing is dispatched, the
            // unchanged tree is verified once with these tests at the final integration, and
            // each criterion is stamped from that run. The plan is left as it is - its tasks
            // are what the criteria are claimed by - and none of them is ever dispatched.
            StringBuilder said = new StringBuilder("NOTHING IS BUILT FOR THIS RUN: every check "
                + "of it is already satisfied by the code it starts from.");
            List<String> tests = new ArrayList<>(run.alreadySatisfiedTests());
            for (Task task : dropped) {
                ChecksAlreadyProved proved = task.checksAlreadyProved();
                said.append("\n- Task '").append(task.title()).append("': ")
                    .append(proved == null ? "its tests" : String.join(", ", proved.tests()))
                    .append(" passed on ").append(run.verificationPoint())
                    .append(" before anything was built.");
                for (String path : task.authoredTestPaths()) {
                    if (!tests.contains(path)) {
                        tests.add(path);
                    }
                }
            }
            said.append("\nNo worker is started. The unchanged tree is verified once with these "
                + "tests in the final integration, and the story is delivered as it stands if "
                + "they pass there. If the story was meant to add something these checks do not "
                + "measure, add a check that says so and build again.");
            log(said.toString());
            run.setAlreadySatisfiedTests(tests);
            run.setNothingToBuild(true);
            return null;
        }
        List<TaskEdge> keptEdges = new ArrayList<>();
        for (TaskEdge edge : graph.dependencies() == null
                ? List.<TaskEdge>of() : graph.dependencies()) {
            if (edge != null && !drop.contains(edge.from()) && !drop.contains(edge.to())) {
                keptEdges.add(edge);
            }
        }
        TaskGraph smaller = new TaskGraph(graph.id(), graph.revision(), graph.designId(),
            new ArrayList<>(kept), keptEdges);
        try {
            artifactStore.append(() -> {
                artifactStore.root().taskGraphs.put(smaller.id(), smaller);
                return null;
            }).get();
        } catch (Exception e) {
            log("Could not store the plan without the task(s) that have nothing left to build ("
                + e.getMessage() + "); the plan is left as it was.");
            return null;
        }
        List<String> tests = new ArrayList<>(run.alreadySatisfiedTests());
        for (Task task : dropped) {
            for (String path : task.authoredTestPaths()) {
                if (!tests.contains(path)) {
                    tests.add(path);
                }
            }
            log("Dropped task '" + task.title() + "': every check it answers for is already "
                + "satisfied by the code the run starts from, and no remaining task builds on it. "
                + "Its test(s) " + task.authoredTestPaths() + " stay in the run and are run again "
                + "on the merged tree.");
        }
        run.setAlreadySatisfiedTests(tests);
        return null;
    }

    /**
     * The red-check for one wave after the first, against the tree that wave is cut from.
     *
     * <p>Registered on the swarm engine as its wave gate, and deliberately a SECOND look rather
     * than a replacement for the one at TEST_AUTHORING. The tree here is the run's pinned base plus
     * every earlier wave's winner, so a test that names a class the previous wave delivered
     * compiles, and failing it means what it is supposed to mean: the behaviour is not there yet.
     * Before the wave ran, the same test could only fail to COMPILE, which counts as red and is no
     * evidence at all about whether the test is a real test.
     *
     * @return null to dispatch the wave; otherwise the reason the run must park
     */
    private String redCheckWave(Run run, StoryScope scope, List<Task> wave, String waveBase) {
        if (repoPath == null || wave == null || wave.isEmpty()) {
            return null;
        }
        Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
        if (spec.isEmpty() || spec.get().acceptance() == null || spec.get().acceptance().isEmpty()) {
            return null;
        }
        List<Task> claiming = wave.stream()
            .filter(task -> !task.authoredTestPaths().isEmpty()).toList();
        if (claiming.isEmpty()) {
            return null; // enablers only: nothing to be red, exactly as at TEST_AUTHORING
        }
        // Everything that has NOT run yet: this wave and the ones after it. A task in an earlier
        // wave has already been built and integrated, so it can no longer deliver anything that is
        // missing from the tree this wave is cut from - see UndeliverableType.
        List<Task> stillToRun = new ArrayList<>();
        TaskGraph graph = artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph != null) {
            List<List<Task>> waves = SwarmEngineImpl.topologicalWaves(graph);
            boolean reached = false;
            for (List<Task> each : waves) {
                reached = reached || each.stream().anyMatch(t -> wave.contains(t));
                if (reached) {
                    stillToRun.addAll(each);
                }
            }
        }
        if (stillToRun.isEmpty()) {
            stillToRun.addAll(wave);
        }
        String park = redCheckTasks(run, scope, claiming, waveBase,
            String.valueOf(Math.abs(String.join(",", wave.stream().map(t -> t.id().toString())
                .toList()).hashCode())), waveBase, stillToRun);
        return park == null ? null : park + OperatorCorrectedTests.whereToCorrect(run);
    }

    /**
     * The red check of the journeys the plan's tasks claim (section 63): the tree the run starts
     * from is built with the contract's own commands, the application is started the way the
     * contract says, and a real browser makes each journey. Every one must FAIL - one that passes
     * before the story is built proves nothing about it.
     *
     * <p>All of it runs in a container with a browser; no model is called. It is cut from the
     * run's start point, not its tests commit: the new acceptance tests are red by design, and a
     * build that stops on them would leave nothing to start.
     *
     * @return null to go on; otherwise why the run stops
     */
    String journeyRedCheck(Run run) {
        if (repoPath == null || !JourneyFile.enabled() || !gitService.isEnabled()) {
            return null;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null) {
            return null;
        }
        String testsCommit = run.acceptanceTestsCommit();
        List<String> unreadable = new ArrayList<>();
        List<JourneysOfAPlan.Claimed> claimed = JourneysOfAPlan.claimed(graph.tasks(), path -> {
            byte[] content = testsCommit == null ? null : gitService.fileAt(testsCommit, path);
            return content == null ? null
                : new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }, unreadable);
        if (!unreadable.isEmpty()) {
            return "The run's tests commit does not hold the journey file(s) " + unreadable
                + " its tasks claim, or they are not well formed there. This is a defect in "
                + "SwarmCoder's own TEST_AUTHORING stage.";
        }
        if (claimed.isEmpty()) {
            return null;
        }
        VerifySpec spec = VerifySpecLoader.load(repoPath).orElse(null);
        List<JourneyFile.Journey> journeys = claimed.stream()
            .map(JourneysOfAPlan.Claimed::journey).toList();
        String branch = "swarm/redcheck/" + run.id() + "/journeys";
        Path worktree = WORKTREE_ROOT.resolve("redcheck-" + run.id() + "-journeys");
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addOrResumeWorktree(branch, worktree, run.startPoint(), run.id().toString());
            String buildFailed = null;
            JourneyRunner.Outcome outcome;
            if (!JourneyFile.canRun(spec)) {
                outcome = JourneyRunner.run(null, spec, journeys, com.swarmcoder.verify.BlobSink.NONE, new StringBuilder());
            } else {
                ExecTarget target = buildBoxes.use(worktree, "The red check of the journeys", true);
                List<String> build = new ArrayList<>();
                if (spec.compile() != null) {
                    build.addAll(spec.compile());
                }
                if (spec.existing() != null) {
                    build.addAll(spec.existing());
                }
                log("Red-check of the journeys: building the tree the run starts from ("
                    + build.size() + " command(s)) and starting the application in a container "
                    + "with a browser, for " + journeys.size() + " journey(s).");
                for (String command : build) {
                    com.swarmcoder.verify.ExecResult built =
                        target.exec(command, spec.effectiveTimeoutSeconds());
                    if (!built.succeeded()) {
                        String said = built.output() == null ? "" : built.output().strip();
                        buildFailed = "$ " + command + " (exit " + built.exitCode() + ")\n"
                            + (said.length() <= 1500 ? said : said.substring(said.length() - 1500));
                        break;
                    }
                }
                outcome = JourneyRunner.run(target, spec, journeys, com.swarmcoder.verify.BlobSink.NONE,
                    new StringBuilder());
            }
            String notRed = JourneysOfAPlan.notRed(claimed, outcome, buildFailed);
            if (notRed == null) {
                for (JourneyFile.Result result : outcome.results()) {
                    log("Red-check of the journeys: \"" + result.journey().name() + "\" ("
                        + result.journey().path() + ") fails on the start tree, as it must - "
                        + result.failure());
                }
            }
            return notRed;
        } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
            log("Red-check of the journeys was not run: " + e.getMessage());
            return noContainerPark("The red check of the journeys", e);
        } catch (Exception e) {
            // Not passed over for good: the journeys are made after the last merge, and a
            // journey that cannot be made there stops the run.
            log("Red-check of the journeys could not be run (" + e.getMessage() + "); they are "
                + "made after the last merge");
            return null;
        } finally {
            removeTree(worktree);
            gitService.deleteCandidateBranch(branch);
        }
    }

    /**
     * One throwaway worktree cut from {@code startPoint}, reduced to one task's claimed tests at a
     * time, red-checked task by task.
     *
     * @param label distinguishes this worktree and branch from the other waves' - they are created
     *              and destroyed at different moments in the same run
     */
    private String redCheckTasks(Run run, StoryScope scope, List<Task> claiming, String startPoint,
                                 String label, String waveBase) {
        return redCheckTasks(run, scope, claiming, startPoint, label, waveBase, List.of());
    }

    private String redCheckTasks(Run run, StoryScope scope, List<Task> claiming, String startPoint,
                                 String label, String waveBase, List<Task> stillToRun) {
        if (claiming.isEmpty()) {
            return null;
        }
        Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
        if (spec.isEmpty()) {
            return null;
        }
        String branch = "swarm/redcheck/" + run.id() + "/" + label;
        Path worktree = WORKTREE_ROOT.resolve("redcheck-" + run.id() + "-" + label);
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addOrResumeWorktree(branch, worktree, startPoint, run.id().toString());
            for (Task task : claiming) {
                AcceptanceOverlay.Outcome placed = AcceptanceOverlay.reduceTo(gitService, worktree,
                    task.acceptanceTestDir(), run.acceptanceTestsCommit(), task.authoredTestPaths());
                if (!placed.missing().isEmpty()) {
                    return "Task '" + task.title() + "' claims test file(s) the run's tests commit "
                        + "does not hold: " + placed.missing() + ". This is a defect in "
                        + "SwarmCoder's own TEST_AUTHORING stage.";
                }
                RedGate gate = redCheckOne(buildTarget(worktree), spec.get(),
                    scope, task, run, waveBase, stillToRun);
                if (gate.unrunnable() != null) {
                    // Only ever set at TEST_AUTHORING (redCheckOne parks at wave time): the one
                    // bounded re-ask, exactly like a broken type below (harness run 37).
                    String stillBroken = reauthorUnrunnableAcceptanceTest(run, task,
                        gate.unrunnable(), gate.unrunnableFailure(), spec.get(), worktree);
                    if (stillBroken != null) {
                        return stillBroken;
                    }
                    continue;
                }
                if (gate.startup() != null) {
                    // Only ever set at TEST_AUTHORING (redCheckOne parks at wave time): the test
                    // compiled and died starting a server or container, before any delivered code
                    // ran (live harness run 51). The same one bounded re-ask.
                    String stillBroken = reauthorStartupAcceptanceTest(run, task, gate.startup(),
                        spec.get(), worktree, stillToRun);
                    if (stillBroken != null) {
                        return stillBroken;
                    }
                    continue;
                }
                if (gate.miscompiled() != null) {
                    // At TEST_AUTHORING and at wave start alike: the test does not compile for a
                    // reason no task can fix - it misuses code that already exists (brownfield
                    // harness run 43; at wave time live run 62). The same one bounded re-ask.
                    String stillBroken = reauthorMiscompiledAcceptanceTest(run, scope, task,
                        gate.miscompiled(), spec.get(), worktree, stillToRun, waveBase);
                    if (stillBroken != null) {
                        return stillBroken;
                    }
                    continue;
                }
                if (gate.brokenTypes().isEmpty()) {
                    if (gate.park() != null) {
                        return gate.park();
                    }
                    continue;
                }
                // BROKEN, NOT RED (author decision, 2026-09-05, harness run 26): the test does not
                // compile for a reason no task can ever fix. Only at TEST_AUTHORING - before any
                // worker has run - does re-asking the author cost nothing; a wave already under
                // way keeps UndeliverableType's immediate park.
                if (waveBase == null) {
                    String stillBroken = reauthorBrokenAcceptanceTest(run, task, gate.brokenTypes(),
                        spec.get(), worktree);
                    if (stillBroken != null) {
                        return stillBroken;
                    }
                    continue; // corrected and re-committed; move on to the next task
                }
                return gate.park();
            }
            return null;
        } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
            // Not an absent instrument: the red check runs tests a model wrote, there was no
            // container to run them in, and this PC is not an alternative. Stop and say so.
            log("Red-check was not run: " + e.getMessage());
            return noContainerPark("The red check", e);
        } catch (Exception e) {
            log("Red-check could not be run: " + e.getMessage());
            return null; // an absent instrument is not a verdict; the same rule as everywhere else
        } finally {
            removeTree(worktree);
            gitService.deleteCandidateBranch(branch);
        }
    }

    /**
     * What one {@link #redCheckOne} came back with: {@code park} is the reason to stop, when there
     * is one; {@code brokenTypes} is non-empty only at TEST_AUTHORING, when the red state is a
     * broken test rather than a healthy one — see {@link TypeDeliverability}. At most one of the
     * two is ever populated.
     */
    private record RedGate(String park, List<String> brokenTypes, String unrunnable,
                           String unrunnableFailure, AcceptanceCompileErrors.Reading miscompiled,
                           BrokenAtStartup.Finding startup) {
        static final RedGate OK = new RedGate(null, List.of(), null, null, null, null);

        static RedGate park(String message) {
            return new RedGate(message, List.of(), null, null, null, null);
        }

        static RedGate broken(List<String> types) {
            return new RedGate(null, types, null, null, null, null);
        }

        /**
         * The test does not compile, and at least one of its errors is one no task in the plan
         * can make go away — it misuses code that already exists, or names a member nobody adds
         * (brownfield harness run 43, 2026-09-26). At TEST_AUTHORING only; at wave time the same
         * finding is a {@link #park}.
         */
        static RedGate miscompiled(AcceptanceCompileErrors.Reading reading) {
            return new RedGate(null, List.of(), null, null, reading, null);
        }

        /**
         * The test RAN and reached code that can only run in a browser (harness run 37) — at
         * TEST_AUTHORING only; at wave time the same finding is a {@link #park}.
         *
         * @param reason  {@link BrowserOnlyCode#reachedIn}'s sentence
         * @param failure the failure as the runner reported it, for the author's re-ask
         */
        static RedGate unrunnable(String reason, String failure) {
            return new RedGate(null, List.of(), reason, failure, null, null);
        }

        /**
         * The test compiled and RAN, and died before any code under test did - a class-level error
         * in a server or container it started (live harness run 51, 2026-09-30; see
         * {@link BrokenAtStartup}). At TEST_AUTHORING only; at wave time the same finding is a
         * {@link #park}.
         */
        static RedGate startup(BrokenAtStartup.Finding finding) {
            return new RedGate(null, List.of(), null, null, null, finding);
        }
    }

    /**
     * The broken reading of a compile-failure red state, or null when it is a healthy red, not a
     * compile failure at all, or nothing could be settled (brownfield harness run 43, 2026-09-26 —
     * see {@link AcceptanceCompileErrors} for the rule and {@link MiscompiledAcceptanceTest} for
     * what it is told about the checkout). Fails open: no tree, no task, or no plan to ask means
     * null, never "broken".
     *
     * @param tree       the tree the red-check compiled
     * @param stillToRun every task that has not run yet — the whole plan at TEST_AUTHORING
     */
    private AcceptanceCompileErrors.Reading miscompiledReading(Path tree,
            RedChecker.RedCheckResult result, Task task, List<Task> stillToRun) {
        return miscompiledReading(tree, result, task,
            task == null ? List.of() : task.authoredTestPaths(), stillToRun, false);
    }

    /**
     * @param testPaths the test files read: the task's authored tests, or a draft's own files
     * @param draft     true while the test author is still compiling a draft: the same reading,
     *                  with nothing written to the run's log and no dependency note saved on a task
     *                  - a draft is not the run's test yet
     */
    private AcceptanceCompileErrors.Reading miscompiledReading(Path tree,
            RedChecker.RedCheckResult result, Task task, Collection<String> testPaths,
            List<Task> stillToRun, boolean draft) {
        if (tree == null || task == null || result == null || !result.red()
                || !result.compileFailure() || stillToRun == null || stillToRun.isEmpty()) {
            return null;
        }
        Consumer<String> say = draft ? line -> { } : this::log;
        AcceptanceCompileErrors.Reading reading = MiscompiledAcceptanceTest.read(tree,
            result.compileOutput(), testPaths, stillToRun);
        if (!draft) {
            provideDependencyNeeds(reading, task);
        }
        if (!reading.isBroken()) {
            // THE ERRORS BEHIND THE MISSING TYPES (live run 63, 2026-10-02). In a build with an
            // annotation processor javac reports a missing import and stops before it looks at a
            // method body, so a test that also calls a library method
            // which does not exist reads as a healthy red until the missing type is delivered -
            // eighty minutes of workers later. Compile once more with the types the plan will
            // deliver stubbed in, and read what the compiler says then. See RedCheckStubs.
            RedCheckStubs.Outcome behind = RedCheckStubs.read(tree, result.compileOutput(),
                testPaths, stillToRun, this::compileRedCheckTree);
            if (behind != null) {
                say.accept("Red-check for task '" + task.title() + "': compiled again with "
                    + behind.stubbed().size() + " not-yet-written type(s) stubbed in "
                    + behind.stubbed() + " (the stubs are deleted again and never committed) - "
                    + (behind.reading().isBroken()
                        ? "behind them the test does not compile for a reason no task can fix: "
                            + behind.reading().headline()
                        : "nothing else is wrong that a task cannot fix"));
                if (behind.reading().isBroken()) {
                    return behind.reading();
                }
            }
        }
        if (!reading.isBroken()) {
            // Said out loud, because "red state confirmed" is otherwise all the log ever shows of
            // this judgement (live run 57: a test that invented a library method passed it).
            say.accept("Red-check for task '" + task.title() + "': " + reading.inTestFiles().size()
                + " compile error(s) in its test files, each one a task in the plan can supply"
                + (reading.inTestFiles().isEmpty() ? " (none located in them)" : ": "
                    + reading.inTestFiles().get(0).fileName() + ":"
                    + reading.inTestFiles().get(0).line() + " "
                    + reading.inTestFiles().get(0).message()));
        }
        return reading.isBroken() ? reading : null;
    }

    /**
     * The acceptance stage's red-check, with the missing packages a build-file task can supply
     * taken out of the result's missing types (live run 64, 2026-10-02). The compiler lists a
     * package that is not on the module's classpath yet next to the types nobody has written, and
     * every check of "can a task create this?" that reads those names would call a dependency
     * undeliverable: a dependency is a line in the build file, and a task whose write set holds
     * that file can add it. See {@link AcceptanceCompileErrors.DependencyNeed}.
     */
    /** Earlier stories' acceptance tests (class#method) the tree's HEAD holds; section 59. */
    private static java.util.Set<String> earlierTestsIn(Path tree, Task task) {
        return tree == null || task == null ? java.util.Set.of()
            : EarlierAcceptanceTests.ids(tree, task.acceptanceTestDir());
    }

    private RedChecker.RedCheckResult redCheck(ExecTarget target, VerifySpec spec,
                                               Task task, List<Task> stillToRun) {
        return redCheck(target, spec, task, task == null ? List.of() : task.authoredTestPaths(),
            stillToRun);
    }

    /**
     * @param testPaths the test files this red state is about: the task's authored tests, or - for
     *                  a draft the test author is still compiling - the draft's own files
     */
    private RedChecker.RedCheckResult redCheck(ExecTarget target, VerifySpec spec,
                                               Task task, Collection<String> testPaths,
                                               List<Task> stillToRun) {
        RedChecker.RedCheckResult result = new RedChecker().check(target, spec,
            earlierTestsIn(target.localRoot().orElse(null), task));
        if (task == null || stillToRun == null || !result.red() || !result.compileFailure()
                || result.missingTypes().isEmpty() || testPaths.isEmpty()) {
            return result;
        }
        boolean anyOwnsABuildFile = false;
        for (Task candidate : stillToRun) {
            if (candidate != null && candidate.writeSet() != null) {
                for (String entry : candidate.writeSet()) {
                    if (entry != null && BuildFilesInTheJob.isBuildFile(entry)) {
                        anyOwnsABuildFile = true;
                    }
                }
            }
        }
        if (!anyOwnsABuildFile) {
            return result;
        }
        AcceptanceCompileErrors.Reading reading = MiscompiledAcceptanceTest.read(
            target.localRoot().orElse(null), result.compileOutput(), testPaths, stillToRun);
        List<String> supplied = new ArrayList<>();
        for (AcceptanceCompileErrors.DependencyNeed need : reading.dependencyNeeds()) {
            supplied.add(need.packageName());
            for (String type : need.types()) {
                supplied.add(need.packageName() + "." + type);
            }
        }
        return result.withoutMissing(supplied);
    }

    /**
     * Says, in the log and in the instructions of the task that owns the module's build file, that
     * the test needs a package the module does not depend on yet. The task's workers read their
     * instructions; the dependency is theirs to add, in the build file their write set holds. The
     * artifact that declares the package is named when the reference checkouts hold its source.
     * Does nothing for a reading with no such need.
     */
    private void provideDependencyNeeds(AcceptanceCompileErrors.Reading reading, Task task) {
        for (AcceptanceCompileErrors.DependencyNeed need : reading.dependencyNeeds()) {
            if (need.owners().isEmpty()) {
                continue;
            }
            Task owner = need.owners().get(0);
            for (Task candidate : need.owners()) {
                if (task != null && candidate.id() != null && candidate.id().equals(task.id())) {
                    owner = candidate;
                }
            }
            String artifact = librarian == null ? null
                : librarian.libraryTypes().declaringArtifact(need.packageName(), need.types())
                    .orElse(null);
            String instruction = dependencyInstruction(need, artifact);
            log("Red-check for task '" + (task == null ? "?" : task.title()) + "': package "
                + need.packageName() + (need.types().isEmpty() ? "" : " " + need.types())
                + " is not on the classpath of the module its test is in, and task '"
                + owner.title() + "' has " + need.buildFile() + " in its write set, so it can add "
                + "the dependency" + (artifact == null ? "" : " (" + artifact + ")")
                + " - a healthy red state, not a broken test");
            String current = owner.instructions() == null ? "" : owner.instructions();
            if (!current.contains(instruction)) {
                owner.setInstructions(current.isEmpty() ? instruction
                    : current + "\n\n" + instruction);
                try {
                    artifactStore.saveTask(owner);
                } catch (RuntimeException e) {
                    log("Could not save the dependency note on task '" + owner.title() + "': "
                        + e.getMessage());
                }
            }
        }
    }

    /** The sentence a worker is told: what the acceptance test needs, and which file to put it in. */
    static String dependencyInstruction(AcceptanceCompileErrors.DependencyNeed need, String artifact) {
        return "The acceptance test needs package " + need.packageName()
            + (need.types().isEmpty() ? "" : " (type " + String.join(", ", need.types()) + ")")
            + ", which is not on this module's classpath yet; add the dependency that provides it"
            + (artifact == null ? "" : " (" + artifact + ")") + " to " + need.buildFile() + ".";
    }

    /**
     * Runs the acceptance stage in a red-check tree that holds stubs, and returns what it printed
     * when that was a compile failure - null for anything else. The stage a red check runs, so the
     * build tool, the classpath and the module layout are the project's own.
     */
    private String compileRedCheckTree(Path tree) {
        Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
        if (spec.isEmpty()) {
            return null;
        }
        RedChecker.RedCheckResult result =
            new RedChecker().check(buildTarget(tree), spec.get());
        return result.compileFailure() ? result.compileOutput() : null;
    }

    /**
     * The first failure of this red state that shows the test reached browser-only code, as
     * {@code [sentence, failure text]}; null when there is none. Read the same way the swarm engine
     * reads a candidate's failure, so the red-check and verification cannot disagree about it.
     */
    private static String[] browserOnlyFailure(RedChecker.RedCheckResult result) {
        if (result == null || result.results() == null || result.results().failures() == null) {
            return null;
        }
        for (com.swarmcoder.domain.TestFailure failure : result.results().failures()) {
            String reached = BrowserOnlyCode.reachedIn(failure);
            if (reached != null) {
                String text = (failure.testId() == null ? "" : failure.testId() + " — ")
                    + (failure.message() == null ? "" : failure.message())
                    + (failure.truncatedTrace() == null || failure.truncatedTrace().isBlank() ? ""
                        : "\n" + failure.truncatedTrace());
                return new String[] {reached, text};
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------
    // compile_test: the red check, on a draft the test author has not handed in yet (2026-10-02)
    // -------------------------------------------------------------------------------------------

    /**
     * What the test author's {@code compile_test} compiles with: the draft is placed in a throwaway
     * tree and red-checked exactly as {@link #redCheckOne} would check it once handed in - the same
     * acceptance stage, the same reading of a compile failure, the same {@link RedCheckStubs} pass
     * for the types the plan has not written yet - and the verdict comes back in the words the
     * author's one re-ask would have used. Nothing here is logged on the run, saved on a task or
     * committed: a draft is not the run's test yet, and the checks after hand-in are unchanged.
     *
     * <p>Closing it removes the tree, when this made one.
     */
    private final class DraftRedCheck implements TestAuthorClient.DraftCompiler, AutoCloseable {

        private final Run run;
        private final VerifySpec spec;
        private final List<Task> stillToRun;
        /** True at a wave's start: green is then no fault, and "nobody delivers" reads the wave. */
        private final boolean atWaveStart;
        /** The red-check tree a repair is already standing in; null to cut one from the base. */
        private final Path givenTree;
        private Path ownTree;
        private String ownBranch;

        DraftRedCheck(Run run, VerifySpec spec, List<Task> stillToRun, Path givenTree,
                      boolean atWaveStart) {
            this.run = run;
            this.spec = spec;
            this.stillToRun = stillToRun == null ? List.of() : stillToRun;
            this.givenTree = givenTree;
            this.atWaveStart = atWaveStart;
        }

        @Override
        public synchronized TestAuthorTools.Verdict compile(Task task, Map<String, String> files) {
            Path tree;
            try {
                tree = tree(task);
                for (Map.Entry<String, String> file : files.entrySet()) {
                    Path target = tree.resolve(file.getKey()).normalize();
                    if (!target.startsWith(tree.resolve(task.acceptanceTestDir()).normalize())) {
                        return new TestAuthorTools.Verdict(false, "Refused: " + file.getKey()
                            + " is not under " + task.acceptanceTestDir() + ".");
                    }
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue());
                }
            } catch (Exception e) {
                return new TestAuthorTools.Verdict(true, "NOT COMPILED: no tree could be prepared "
                    + "to compile the draft in (" + e.getMessage() + "). Hand it in with "
                    + "report_done; it is checked again after hand-in.");
            }
            return draftVerdict(tree, spec, task, List.copyOf(files.keySet()), stillToRun,
                atWaveStart);
        }

        /** The tree a draft is compiled in: the repair's own, or one cut from the run's base. */
        private Path tree(Task task) throws Exception {
            if (givenTree != null) {
                return givenTree;
            }
            if (ownTree == null) {
                String branch = "swarm/draftcheck/" + run.id();
                Path worktree = WORKTREE_ROOT.resolve("draftcheck-" + run.id());
                removeTree(worktree); // a leftover from a killed attempt, if any
                gitService.addOrResumeWorktree(branch, worktree, run.startPoint(),
                    run.id().toString());
                ownTree = worktree;
                ownBranch = branch;
            }
            // Only this task's draft may be in the acceptance directory, as at the red check,
            // where the tree is reduced to one task's tests at a time.
            Path acceptance = ownTree.resolve(task.acceptanceTestDir());
            if (Files.isDirectory(acceptance)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(acceptance)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            return ownTree;
        }

        @Override
        public synchronized void close() {
            if (ownTree != null) {
                removeTree(ownTree);
                gitService.deleteCandidateBranch(ownBranch);
                ownTree = null;
            }
        }
    }

    /**
     * The compiler for the drafts of one stage's authoring calls, or null when there is nothing to
     * compile them with: no repository, no git, or no acceptance stage configured.
     */
    private DraftRedCheck draftRedCheck(Run run, List<Task> stillToRun, Path givenTree,
                                        boolean atWaveStart) {
        if (repoPath == null || !gitService.isEnabled() || roles.testAuthor() == null
                || !roles.testAuthor().worksAsAnAgent()) {
            return null;
        }
        Optional<VerifySpec> spec = VerifySpecLoader.load(repoPath);
        if (spec.isEmpty() || spec.get().acceptance() == null || spec.get().acceptance().isEmpty()) {
            return null;
        }
        return new DraftRedCheck(run, spec.get(), stillToRun, givenTree, atWaveStart);
    }

    /**
     * {@code compile_test} for a repair call made outside first authoring: the repair during
     * execution, the browser-only repair, and the repair of a correction that broke a rule. Until
     * these were scoped the tool answered "NOT COMPILED" in them. Each gets a throwaway tree of its
     * own, in a container like first authoring (the tree is red-checked through the same
     * {@link #redCheck} and {@link BuildBoxes}), removed when the scope closes.
     *
     * The plan's tasks are passed so a type the plan has not written yet is stubbed; the one task
     * alone when the plan cannot be read.
     */
    private TestAuthorClient.Scope compilingRepairsOf(Run run, Task task) {
        if (roles.testAuthor() == null) {
            return () -> { };
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : artifactStore.root().taskGraphs.get(run.taskGraphId());
        DraftRedCheck check = draftRedCheck(run,
            graph == null || graph.tasks() == null ? List.of(task) : graph.tasks(), null, false);
        TestAuthorClient.Scope scope = roles.testAuthor().compilingDraftsWith(check);
        return () -> {
            scope.close();
            if (check != null) {
                check.close();
            }
        };
    }

    /**
     * {@link #redCheckOne}'s verdict on a draft, in words for its author. The same order of
     * questions and the same classifiers; the answers are the re-ask each fault already has.
     */
    private TestAuthorTools.Verdict draftVerdict(Path tree, VerifySpec spec, Task task,
                                                 List<String> paths, List<Task> stillToRun,
                                                 boolean atWaveStart) {
        RedChecker.RedCheckResult result;
        try {
            result = redCheck(buildTarget(tree), spec, task, paths, stillToRun);
        } catch (RuntimeException e) {
            return new TestAuthorTools.Verdict(true, "NOT COMPILED: the build's check could not be "
                + "run (" + e.getMessage() + "). Hand the draft in with report_done; it is "
                + "checked again after hand-in.");
        }
        String fixAndRetry = "\n\nFix the test - look up whatever this shows you guessed - and "
            + "call compile_test again.";
        if (result.red()) {
            String[] unrunnable = browserOnlyFailure(result);
            if (unrunnable != null) {
                return new TestAuthorTools.Verdict(false, "BROKEN TEST.\n"
                    + AcceptanceTestReach.reaskForFailure(unrunnable[0], unrunnable[1],
                        browserOnlySurvey(repoLayout())) + fixAndRetry);
            }
            BrokenAtStartup.Finding startup = RedChecker.brokenAtStartup(result, tree, stillToRun);
            if (startup != null) {
                return new TestAuthorTools.Verdict(false, "BROKEN TEST.\n"
                    + AcceptanceTestStartup.reask(startup) + fixAndRetry);
            }
            List<String> undeliverable = atWaveStart
                ? (UndeliverableType.park(task, stillToRun, result.missingTypes()) == null
                    ? List.of() : result.missingTypes())
                : TypeDeliverability.undeliverable(result.missingTypes(), stillToRun);
            if (!undeliverable.isEmpty()) {
                String module = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
                return new TestAuthorTools.Verdict(false, "BROKEN TEST.\n"
                    + BrokenAcceptanceTest.reask(undeliverable, module, moduleArtifactIds(module))
                    + fixAndRetry);
            }
            AcceptanceCompileErrors.Reading miscompiled =
                miscompiledReading(tree, result, task, paths, stillToRun, true);
            if (miscompiled != null) {
                return new TestAuthorTools.Verdict(false, "BROKEN TEST.\n"
                    + MiscompiledAcceptanceTest.reask(miscompiled,
                        MiscompiledAcceptanceTest.signatures(tree, miscompiled, paths,
                            librarian == null ? LibraryTypes.NONE : librarian.libraryTypes()))
                    + fixAndRetry);
            }
            return new TestAuthorTools.Verdict(true, "HEALTHY: the draft fails, and only because "
                + "the code this plan will write is not there yet - " + result.note()
                + ". That is what the build's own check wants. Hand it in with report_done.");
        }
        if (result.results() != null && result.results().passed() > 0) {
            if (atWaveStart) {
                return new TestAuthorTools.Verdict(true, "HEALTHY: the draft compiles and passes on "
                    + "the work already delivered - " + result.note() + ". Hand it in with "
                    + "report_done.");
            }
            return new TestAuthorTools.Verdict(false, "BROKEN TEST: it passes on the code as it is "
                + "now, before any of this task's work is done, so it proves nothing about that "
                + "work - " + result.note() + ". A test must fail until the task is implemented: "
                + "assert the behaviour the task adds." + fixAndRetry);
        }
        if (result.results() != null && result.results().ranNothing()) {
            return new TestAuthorTools.Verdict(false, "BROKEN TEST: the build ran no test at all "
                + "from this file - " + result.note() + ". The usual cause is the wrong directory "
                + "or package, or a class with no @Test method: write it as "
                + ArchitectClient.acceptanceWriteDir(task.acceptanceTestDir())
                + "/<ClassName>.java in package swarm.accept." + fixAndRetry);
        }
        return new TestAuthorTools.Verdict(true, "NOT CONCLUSIVE: " + result.note()
            + ". Nothing was found wrong with the draft. Hand it in with report_done.");
    }

    /**
     * One red-check over one tree. {@link RedGate#OK} when red, when nothing conclusive could be
     * measured, or — at wave time only — when the task's tests are already green because the waves
     * in front of it delivered what they measure; otherwise the reason to park, naming the task
     * when there is one, or — at TEST_AUTHORING only — the names a broken test cannot compile for.
     *
     * @param waveBase the tree this wave is cut from, and the marker that this is the WAVE-time
     *                 check rather than the one at TEST_AUTHORING. Null for TEST_AUTHORING.
     */
    private RedGate redCheckOne(ExecTarget target, VerifySpec spec, StoryScope scope,
                               Task task, Run run, String waveBase, List<Task> stillToRun) {
        String who = task == null ? "The acceptance tests" : "Task '" + task.title() + "': its tests";
        RedChecker.RedCheckResult result = redCheck(target, spec, task, stillToRun);
        if (result.red() && task != null) {
            // RED, BUT NOT A RED ANY CODE CAN TURN GREEN (harness run 37, 2026-09-25). The test ran
            // and died with UnsatisfiedLinkError, or on a browser runtime class this JVM cannot
            // load: it reached code that exists only as JavaScript in a page. Counted as "fails",
            // that is indistinguishable from a healthy red — and every worker dispatched at it
            // would die the same way. Asked before the TypeDeliverability question, because a test
            // that ran has already compiled.
            String[] unrunnable = browserOnlyFailure(result);
            if (unrunnable != null) {
                log("Red-check for task '" + task.title() + "': " + unrunnable[0]
                    + " — a broken test, not a healthy red state");
                if (waveBase == null) {
                    return RedGate.unrunnable(unrunnable[0], unrunnable[1]);
                }
                // At wave time the same rule as for an undeliverable type: park before the wave is
                // dispatched, never re-ask with a wave under way.
                return RedGate.park(AcceptanceTestReach.failureBrief(task.title(), unrunnable[0],
                    "It was found on the tree this wave is cut from, before any worker was "
                        + "dispatched", browserOnlySurvey(repoLayout())));
            }
        }
        if (result.red() && task != null) {
            // RED, BUT THE TEST NEVER REACHED THE PLAN'S CODE (live harness run 51, 2026-09-30). A
            // test that boots a server or container and dies at class level, in frames none of
            // which belong to this project, has measured nothing: counted as "fails" it looked
            // like a healthy red, both candidates then died on the same start-up error, and a
            // worker may not edit the test. Treated exactly like a test that cannot compile.
            BrokenAtStartup.Finding startup = RedChecker.brokenAtStartup(result,
                target.localRoot().orElse(null), stillToRun);
            if (startup != null) {
                log("Red-check for task '" + task.title() + "': " + startup.reason()
                    + " - a broken test, not a healthy red state");
                if (waveBase == null) {
                    return RedGate.startup(startup);
                }
                return RedGate.park(AcceptanceTestStartup.park(task.title(), startup,
                    "It was found on the tree this wave is cut from, before any worker was "
                        + "dispatched."));
            }
        }
        if (result.red()) {
            // RED IS NOT ALWAYS HEALTHY (author decision, 2026-09-03). A freshly written test that
            // does not compile is exactly what a test written before its code looks like - and it
            // is also exactly what a test naming a type nobody will ever build looks like. At wave
            // time the two are separable, because the plan says who may still write what. Asked
            // BEFORE the swarm is dispatched: the whole cost of run 13 was finding this out after.
            if (waveBase != null && task != null) {
                String undeliverable = UndeliverableType.park(task, stillToRun, result.missingTypes());
                if (undeliverable != null) {
                    log("Red-check STOP for task '" + task.title() + "': its tests need "
                        + result.missingTypes() + ", and nothing left to run can create "
                        + "them — not dispatching the wave");
                    return RedGate.park(undeliverable);
                }
                // Every missing type is deliverable; is every OTHER error? (brownfield harness run
                // 43, 2026-09-26). A test that misuses code that exists is a BROKEN TEST, not a
                // reason to strand the waves already built (live harness run 62, 2026-10-01: red at
                // TEST_AUTHORING because javac never got past the missing types, then a wrong
                // library call found here, after ninety minutes of delivered work). Nothing has
                // been dispatched for THIS wave, so the one bounded re-ask costs nothing: it goes
                // back to the test author exactly as at TEST_AUTHORING, and only a correction that
                // still cannot compile parks the run - see reauthorMiscompiledAcceptanceTest.
                AcceptanceCompileErrors.Reading miscompiled = miscompiledReading(
                    target.localRoot().orElse(null), result, task, stillToRun);
                if (miscompiled != null) {
                    log("Red-check for task '" + task.title() + "' on the tree its wave is cut from: "
                        + "its tests do not compile for a reason no task left to run can fix - "
                        + miscompiled.headline() + " - a broken test; sending it back to the test "
                        + "author once before the wave is dispatched");
                    return RedGate.miscompiled(miscompiled);
                }
                log("Red-check passed for task '" + task.title() + "': " + result.note());
                return RedGate.OK;
            }
            // THE SAME QUESTION, ASKED ONE STAGE EARLIER (author decision, 2026-09-05, harness run
            // 26). Nothing has run yet, so every task in the plan is still able to deliver a
            // missing symbol - that is exactly the "stillToRun" this call was handed. A symbol
            // none of them can ever create or reach is not TDD; it is a test broken from the
            // moment it was written, and it must be told apart here, before a swarm is spent
            // finding out the hard way (see UiPresentationTest, the acceptance test that imported
            // a client-only package into a server module that cannot see it).
            if (task != null) {
                List<String> broken = TypeDeliverability.undeliverable(result.missingTypes(), stillToRun);
                if (!broken.isEmpty()) {
                    log("Red-check for task '" + task.title() + "': " + broken
                        + " is not delivered by any task and not in any task's write set — this is "
                        + "a broken test, not a healthy red state");
                    return RedGate.broken(broken);
                }
                // AND THE ERRORS THAT ARE NOT MISSING TYPES AT ALL (brownfield harness run 43,
                // 2026-09-26). "Does not compile" was read as "references not-yet-implemented
                // symbols" whatever the compiler said. Run 43's test passed a Parser where jsoup's
                // existing Document constructor takes a String — nothing was missing, so the
                // question above found nothing wrong, and a whole swarm was spent on a line no
                // candidate could ever make compile. AcceptanceCompileErrors reads every error in
                // the test file and asks, of each, whether any task in the plan could fix it.
                AcceptanceCompileErrors.Reading miscompiled = miscompiledReading(
                    target.localRoot().orElse(null), result, task, stillToRun);
                if (miscompiled != null) {
                    log("Red-check for task '" + task.title() + "': its tests do not compile for "
                        + "a reason no task in this plan can fix — " + miscompiled.headline()
                        + " — this is a broken test, not a healthy red state");
                    return RedGate.miscompiled(miscompiled);
                }
            }
            log("Red-check passed" + (task == null ? "" : " for task '" + task.title() + "'")
                + ": " + result.note());
            return RedGate.OK;
        }
        // Not red: only block when tests actually ran and passed — an empty acceptance
        // suite (nothing authored yet) is the M1 allowance, not a violation.
        if (result.results() != null && result.results().passed() > 0) {
            // AT WAVE TIME THIS IS A FACT, NOT A FAULT (author decision, 2026-09-03; see
            // ChecksAlreadyProved). The waves in front of this one just delivered what these tests
            // measure, so of course they pass. Parking here interrupts a person at the last step of
            // a run that went right, and tells them to revise a test that is doing its job. It is
            // written onto the task, said in the log, and carried to the judge — which is where the
            // difference it makes belongs, because the tests no longer separate this task's
            // candidates from each other.
            if (waveBase != null && task != null) {
                recordAlreadyProved(run, scope, task, spec, waveBase, result.results());
                return RedGate.OK;
            }
            // AT TEST_AUTHORING IT IS A FACT TOO, WHEN THE TEST IS SHOWN TO MEASURE THE PROJECT'S
            // CODE (owner decision after the audit of 2026-10-02; see AlreadySatisfied). The code
            // earlier stories delivered already satisfies the check: it is recorded with the
            // evidence, the task is no longer obliged to turn the test green, and the run goes on.
            if (task != null) {
                Path tree = target.localRoot().orElse(null);
                DesignDocument design = run.designId() == null ? null
                    : artifactStore.root().designs.get(run.designId());
                AlreadySatisfied.Evidence evidence = AlreadySatisfied.assess(tree, design, task,
                    stillToRun, result.results());
                if (evidence.accepted()) {
                    recordAlreadySatisfied(run, scope, task, spec, tree, evidence,
                        result.results());
                    return RedGate.OK;
                }
                log("Red-check for task '" + task.title() + "': its tests already pass on the "
                    + "tree the run starts from, and that is not accepted as proof - "
                    + evidence.refusal());
                // Without that evidence it IS a fault and still parks, as before: nothing in the
                // run has touched the tree yet, so a green acceptance test is evidence about the
                // TEST, and re-running the test author is what fixes it.
                return RedGate.park(who + " — " + result.note() + "\n\nThis was not recorded "
                    + "as a check the existing code already satisfies: " + evidence.refusal()
                    + ".");
            }
            return RedGate.park(who + " — " + result.note());
        }
        // The same defect as §17.1, one stage earlier and still live until now. A red-check whose
        // acceptance stage RAN and executed nothing found no failures, so it "skipped" and the
        // swarm dispatched with no signal at all — every candidate then dies at verification for
        // the same reason, a whole swarm later. When the run answers for checks, a stage that
        // measured nothing is a reason to stop, not a reason to carry on quietly.
        if (result.results() != null && result.results().ranNothing() && scope != null
                && !scope.isEmpty()) {
            List<AcceptanceCriterion> checks = task == null ? scope.criteria() : criteriaFor(task, scope);
            return RedGate.park(who + " executed NO tests at all, so nothing was proved either way "
                + "about the " + checks.size() + " check(s) " + (task == null ? "this run" : "it")
                + " answers for: " + String.join(", ", refsFor(checks, scope)) + ".\n\n"
                + (task == null ? "" : "The test file(s) " + task.authoredTestPaths()
                    + " were in the tree, so ")
                + "the usual cause is the acceptance selector in .swarmcoder/verify.yaml matching "
                + "no file, or the files being outside every module the build compiles tests in. "
                + "Dispatching the swarm now would select between candidates on no evidence "
                + "whatever.");
        }
        log("Red-check skipped" + (task == null ? "" : " for task '" + task.title() + "'")
            + ": " + result.note());
        return RedGate.OK;
    }

    /**
     * Sends one task's broken acceptance test(s) back to its author, once, before the run parks
     * (author decision, 2026-09-05; see {@link BrokenAcceptanceTest}). The correction is re-checked
     * on the SAME throwaway tree the caller is already standing in, and — whether or not it is
     * still broken — is committed back onto the run's own tests ref, exactly as {@code
     * authorAcceptanceTests} committed the original.
     *
     * @return null when the correction is no longer broken (the caller's loop continues to the
     *         next task); otherwise the brief for a parked run
     */
    private String reauthorBrokenAcceptanceTest(Run run, Task task, List<String> undeliverableTypes,
                                                VerifySpec spec, Path redcheckWorktree) {
        DesignDocument design = artifactStore.root().designs.get(run.designId());
        String acceptanceModule = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
        List<String> moduleArtifactIds = moduleArtifactIds(acceptanceModule);
        String reaskMessage = BrokenAcceptanceTest.reask(undeliverableTypes, acceptanceModule,
            moduleArtifactIds);

        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(branch, worktree);
            Map<String, String> existing = new LinkedHashMap<>();
            for (String path : task.authoredTestPaths()) {
                existing.put(path, Files.readString(worktree.resolve(path)));
            }
            TestAuthorClient.Authored authored;
            TaskGraph planOf = run.taskGraphId() == null ? null
                : artifactStore.root().taskGraphs.get(run.taskGraphId());
            try (TestAuthorClient.Scope compiling = roles.testAuthor().compilingDraftsWith(
                    draftRedCheck(run, planOf == null ? List.of(task) : planOf.tasks(),
                        redcheckWorktree, false))) {
                authored = roles.testAuthor()
                    .repairBrokenTest(worktree, task, design, existing, reaskMessage);
            }
            if (authored.failureReason() != null) {
                return BrokenAcceptanceTest.park(task.title(), undeliverableTypes, acceptanceModule,
                    moduleArtifactIds, "the test author's repair call did not produce a corrected "
                        + "file: " + authored.failureReason());
            }
            String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
            if (ruleBreak != null) {
                return ruleBreak;
            }
            // redcheckWorktree already holds this task's ORIGINAL (broken) files - the caller's loop
            // reduced it to exactly them before calling redCheckOne. Overwrite with the correction.
            for (String path : authored.paths()) {
                Files.createDirectories(redcheckWorktree.resolve(path).getParent());
                Files.writeString(redcheckWorktree.resolve(path), Files.readString(worktree.resolve(path)));
            }
            TaskGraph graph = run.taskGraphId() == null ? null
                : artifactStore.root().taskGraphs.get(run.taskGraphId());
            RedChecker.RedCheckResult second = redCheck(buildTarget(redcheckWorktree),
                spec, task, graph == null ? List.of() : graph.tasks());
            List<String> stillBroken = second.red()
                ? TypeDeliverability.undeliverable(second.missingTypes(),
                    graph == null ? List.of() : graph.tasks())
                : List.of();
            if (!stillBroken.isEmpty()) {
                return BrokenAcceptanceTest.park(task.title(), stillBroken, acceptanceModule,
                    moduleArtifactIds, "the corrected test still names " + stillBroken
                        + ", which this plan does not deliver and this module cannot see");
            }
            // A correction that stops naming the undeliverable type by misusing one that exists
            // has swapped one broken test for another (brownfield harness run 43).
            String miscompiled = miscompiledCorrection(task, second, redcheckWorktree,
                graph == null ? List.of() : graph.tasks(), "named a type this plan does not deliver");
            if (miscompiled != null) {
                return miscompiled;
            }
            gitService.commitAll(worktree, "Correct broken acceptance test for task '"
                + task.title() + "' in run " + run.id() + "\n\n" + String.join("\n", authored.paths()));
            run.setAcceptanceTestsCommit(gitService.headSha(branch));
            log("TEST_AUTHORING: broken acceptance test(s) " + authored.paths() + " for task '"
                + task.title() + "' were corrected by the test author and are red again");
            return null;
        } catch (IOException e) {
            return BrokenAcceptanceTest.park(task.title(), undeliverableTypes, acceptanceModule,
                moduleArtifactIds, "reading or writing the test file failed: " + e.getMessage());
        } finally {
            removeTree(worktree); // the branch stays; the correction is on it
        }
    }

    /**
     * Sends one task's acceptance test(s) back to their author, once, because they compiled and
     * then died before any code under test ran (live harness run 51, 2026-09-30; see
     * {@link BrokenAtStartup} and {@link AcceptanceTestStartup}). The same shape as
     * {@link #reauthorMiscompiledAcceptanceTest}: one repair call carrying the failure itself, the
     * correction overwritten into the SAME throwaway tree and red-checked again. Still dying at
     * start-up, not compiling for a reason no task can fix, or naming a type nobody delivers: park.
     *
     * @return null when the correction holds; otherwise the brief for a parked run
     */
    private String reauthorStartupAcceptanceTest(Run run, Task task, BrokenAtStartup.Finding finding,
                                                 VerifySpec spec, Path redcheckWorktree,
                                                 List<Task> planTasks) {
        DesignDocument design = artifactStore.root().designs.get(run.designId());
        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(branch, worktree);
            Map<String, String> existing = new LinkedHashMap<>();
            for (String path : task.authoredTestPaths()) {
                existing.put(path, Files.readString(worktree.resolve(path)));
            }
            TestAuthorClient.Authored authored;
            try (TestAuthorClient.Scope compiling = roles.testAuthor().compilingDraftsWith(
                    draftRedCheck(run, planTasks, redcheckWorktree, false))) {
                authored = roles.testAuthor().repairStartupTest(worktree, task,
                    design, existing, AcceptanceTestStartup.reask(finding));
            }
            if (authored.failureReason() != null) {
                return AcceptanceTestStartup.park(task.title(), finding, "The test author was asked "
                    + "once to correct it, and its repair call did not produce a corrected file: "
                    + authored.failureReason());
            }
            String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
            if (ruleBreak != null) {
                return ruleBreak;
            }
            for (String path : authored.paths()) {
                Files.createDirectories(redcheckWorktree.resolve(path).getParent());
                Files.writeString(redcheckWorktree.resolve(path), Files.readString(worktree.resolve(path)));
            }
            RedChecker.RedCheckResult second = redCheck(buildTarget(redcheckWorktree),
                spec, task, planTasks);
            BrokenAtStartup.Finding still = RedChecker.brokenAtStartup(second, redcheckWorktree, planTasks);
            if (still != null) {
                return AcceptanceTestStartup.park(task.title(), still, "The test author was asked "
                    + "once to correct it, and the corrected test still fails before any code under "
                    + "test runs.");
            }
            String miscompiled = miscompiledCorrection(task, second, redcheckWorktree, planTasks,
                "died while starting a server or container");
            if (miscompiled != null) {
                return miscompiled;
            }
            if (!second.red() && second.results() != null && second.results().passed() > 0) {
                // Green on a tree nothing in this run has touched: the correction measures nothing
                // new - the same verdict the red-check itself gives at TEST_AUTHORING.
                return "Task '" + task.title() + "': its acceptance test died at start-up, and the "
                    + "test author's correction passes on the tree before any work was done, so it "
                    + "proves nothing about the work - " + second.note();
            }
            List<String> undeliverable = second.red()
                ? TypeDeliverability.undeliverable(second.missingTypes(), planTasks)
                : List.of();
            if (!undeliverable.isEmpty()) {
                String module = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
                return BrokenAcceptanceTest.park(task.title(), undeliverable, module,
                    moduleArtifactIds(module), "the correction of a test that died at start-up "
                        + "names " + undeliverable + " instead, which this plan does not deliver "
                        + "and this module cannot see");
            }
            gitService.commitAll(worktree, "Correct acceptance test that dies at start-up for task '"
                + task.title() + "' in run " + run.id() + "\n\n"
                + String.join("\n", authored.paths()));
            run.setAcceptanceTestsCommit(gitService.headSha(branch));
            log("TEST_AUTHORING: acceptance test(s) " + authored.paths() + " for task '"
                + task.title() + "' died before any code under test ran; the test author corrected "
                + "them" + (second.red() ? " and they are red again" : ""));
            return null;
        } catch (IOException e) {
            return AcceptanceTestStartup.park(task.title(), finding,
                "Reading or writing the test file failed: " + e.getMessage());
        } finally {
            removeTree(worktree); // the branch stays; the correction is on it
        }
    }

    /**
     * Sends one task's acceptance test(s) back to their author, once, because they do not compile
     * for a reason no task in the plan can fix (brownfield harness run 43, 2026-09-26; see
     * {@link AcceptanceCompileErrors} and {@link MiscompiledAcceptanceTest}). The same shape as
     * {@link #reauthorBrokenAcceptanceTest}: one repair call carrying the compiler's own lines and
     * the real signatures of what the test misused (a project type's from the tree, a library
     * type's from the librarian's reference checkouts), the correction overwritten into the SAME
     * throwaway tree, the red check run again and the reading re-taken. Still broken, green on
     * the untouched tree, or naming a type nobody delivers → park. Otherwise the correction is
     * committed onto the run's tests ref and the run carries on.
     *
     * <p><b>At wave start too</b> ({@code waveBase} non-null; live harness run 62, 2026-10-01): the
     * red check at TEST_AUTHORING passed because javac reported only missing types and never got to
     * a wrongly-called library method; two waves later the real error showed. The same re-ask, the
     * correction re-checked on the tree the wave is cut from. Two verdicts differ there, because
     * the tree carries earlier waves' work: a correction that PASSES is not a tautology but
     * "already proved" (recorded as such, never a park), and a type nobody still to run can
     * deliver is judged by {@link UndeliverableType}.
     *
     * @param waveBase the tree the wave is cut from, or null at TEST_AUTHORING
     * @return null when the correction holds; otherwise the brief for a parked run
     */
    private String reauthorMiscompiledAcceptanceTest(Run run, StoryScope scope, Task task,
                                                     AcceptanceCompileErrors.Reading reading,
                                                     VerifySpec spec, Path redcheckWorktree,
                                                     List<Task> planTasks, String waveBase) {
        DesignDocument design = artifactStore.root().designs.get(run.designId());
        String signatures = signaturesFor(redcheckWorktree, reading, task);
        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(branch, worktree);
            Map<String, String> existing = new LinkedHashMap<>();
            for (String path : task.authoredTestPaths()) {
                existing.put(path, Files.readString(worktree.resolve(path)));
            }
            TestAuthorClient.Authored authored;
            try (TestAuthorClient.Scope compiling = roles.testAuthor().compilingDraftsWith(
                    draftRedCheck(run, planTasks, redcheckWorktree, waveBase != null))) {
                authored = roles.testAuthor().repairBrokenTest(worktree, task,
                    design, existing, MiscompiledAcceptanceTest.reask(reading, signatures));
            }
            if (authored.failureReason() != null) {
                return MiscompiledAcceptanceTest.park(task.title(), reading, signatures,
                    "The test author was asked once to correct it, and its repair call did not "
                        + "produce a corrected file: " + authored.failureReason());
            }
            String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
            if (ruleBreak != null) {
                return ruleBreak;
            }
            for (String path : authored.paths()) {
                Files.createDirectories(redcheckWorktree.resolve(path).getParent());
                Files.writeString(redcheckWorktree.resolve(path), Files.readString(worktree.resolve(path)));
            }
            RedChecker.RedCheckResult second = redCheck(buildTarget(redcheckWorktree),
                spec, task, planTasks);
            String still = miscompiledCorrection(task, second, redcheckWorktree, planTasks, null);
            if (still != null) {
                return still;
            }
            boolean greenNow = !second.red() && second.results() != null
                && second.results().passed() > 0;
            if (greenNow && waveBase == null) {
                // Green on a tree nothing in this run has touched: the correction measures nothing
                // new — the same verdict the red-check itself gives at TEST_AUTHORING.
                return "Task '" + task.title() + "': its acceptance test did not compile, and the "
                    + "test author's correction passes on the tree before any work was done, so it "
                    + "proves nothing about the work — " + second.note();
            }
            if (waveBase != null && second.red()) {
                String nobodyDelivers = UndeliverableType.park(task, planTasks, second.missingTypes());
                if (nobodyDelivers != null) {
                    return nobodyDelivers;
                }
            }
            List<String> undeliverable = second.red() && waveBase == null
                ? TypeDeliverability.undeliverable(second.missingTypes(), planTasks)
                : List.of();
            if (!undeliverable.isEmpty()) {
                String module = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
                return BrokenAcceptanceTest.park(task.title(), undeliverable, module,
                    moduleArtifactIds(module), "the correction of a test that did not compile "
                        + "names " + undeliverable + " instead, which this plan does not deliver "
                        + "and this module cannot see");
            }
            gitService.commitAll(worktree, "Correct acceptance test that cannot compile for task '"
                + task.title() + "' in run " + run.id() + "\n\n"
                + String.join("\n", authored.paths()));
            run.setAcceptanceTestsCommit(gitService.headSha(branch));
            log((waveBase == null ? "TEST_AUTHORING" : "Wave start") + ": acceptance test(s) "
                + authored.paths() + " for task '"
                + task.title() + "' did not compile for a reason no task could fix; the test "
                + "author corrected them" + (second.red() ? " and they are red again" : ""));
            if (greenNow) {
                // On the wave's tree the earlier waves already delivered what it measures.
                recordAlreadyProved(run, scope, task, spec, waveBase, second.results());
            }
            return null;
        } catch (IOException e) {
            return MiscompiledAcceptanceTest.park(task.title(), reading, signatures,
                "Reading or writing the test file failed: " + e.getMessage());
        } finally {
            removeTree(worktree); // the branch stays; the correction is on it
        }
    }

    /**
     * The real signatures of what a miscompiled test misused: project types from {@code tree}, and
     * library types from the librarian's reference checkouts when there is a librarian.
     */
    private String signaturesFor(Path tree, AcceptanceCompileErrors.Reading reading, Task task) {
        return MiscompiledAcceptanceTest.signatures(tree, reading, task.authoredTestPaths(),
            librarian == null ? LibraryTypes.NONE : librarian.libraryTypes());
    }

    /**
     * The park brief when a CORRECTED acceptance test — corrected for whatever reason — still does
     * not compile for a reason no task can fix; null when it compiles, or its failure to compile is
     * a healthy red (brownfield harness run 43). Every correction path runs this, because each one
     * can trade the fault it was asked to fix for this one.
     *
     * @param originalFault what the correction was for, in words, or null when it was for this
     */
    private String miscompiledCorrection(Task task, RedChecker.RedCheckResult second,
                                         Path redcheckWorktree, List<Task> planTasks,
                                         String originalFault) {
        AcceptanceCompileErrors.Reading still =
            miscompiledReading(redcheckWorktree, second, task, planTasks);
        if (still == null) {
            return null;
        }
        return MiscompiledAcceptanceTest.park(task.title(), still,
            signaturesFor(redcheckWorktree, still, task),
            originalFault == null
                ? "The test author was asked once to correct it, and the corrected test still "
                    + "does not compile."
                : "The test author was asked once to correct a test that " + originalFault
                    + ", and the correction does not compile for a reason no task can fix.");
    }

    /**
     * Sends one task's acceptance test(s) back to their author, once, because the red-check RAN them
     * and they reached code that can only run in a browser (harness run 37, 2026-09-25; see
     * {@link AcceptanceTestReach}). The same shape as {@link #reauthorBrokenAcceptanceTest}: the
     * correction is held to the static reach check, re-checked on the same throwaway tree, and
     * committed onto the run's tests ref only when it no longer reaches browser code and names
     * nothing this plan cannot deliver.
     *
     * @return null when the correction holds; otherwise the brief for a parked run
     */
    private String reauthorUnrunnableAcceptanceTest(Run run, Task task, String reason,
                                                    String failureText, VerifySpec spec,
                                                    Path redcheckWorktree) {
        DesignDocument design = artifactStore.root().designs.get(run.designId());
        BrowserOnlyCode.Survey survey = browserOnlySurvey(repoLayout());
        String branch = testsBranch(run);
        Path worktree = WORKTREE_ROOT.resolve("tests-" + run.id());
        try {
            removeTree(worktree); // a leftover from a killed attempt, if any
            gitService.addWorktreeAt(branch, worktree);
            Map<String, String> existing = new LinkedHashMap<>();
            for (String path : task.authoredTestPaths()) {
                existing.put(path, Files.readString(worktree.resolve(path)));
            }
            TestAuthorClient.Authored authored;
            try (TestAuthorClient.Scope compiling = compilingRepairsOf(run, task)) {
                authored = roles.testAuthor().repairUnrunnableTest(worktree, task, design, existing,
                    AcceptanceTestReach.reaskForFailure(reason, failureText, survey));
            }
            if (authored.failureReason() != null) {
                return AcceptanceTestReach.failureBrief(task.title(), reason, "The test author was "
                    + "asked once to correct it, and its repair call did not produce a corrected "
                    + "file: " + authored.failureReason(), survey);
            }
            String ruleBreak = correctionBreaksARule(run, task, worktree, authored.paths());
            if (ruleBreak != null) {
                return ruleBreak;
            }
            AcceptanceTestReach.Check reach = AcceptanceTestReach.check(worktree, survey, authored.paths());
            if (!reach.ok()) {
                return AcceptanceTestReach.brief(task.title(), reach);
            }
            for (String path : authored.paths()) {
                Files.createDirectories(redcheckWorktree.resolve(path).getParent());
                Files.writeString(redcheckWorktree.resolve(path), Files.readString(worktree.resolve(path)));
            }
            TaskGraph planGraph = run.taskGraphId() == null ? null
                : artifactStore.root().taskGraphs.get(run.taskGraphId());
            RedChecker.RedCheckResult second = redCheck(buildTarget(redcheckWorktree),
                spec, task, planGraph == null ? List.of() : planGraph.tasks());
            String[] stillUnrunnable = second.red() ? browserOnlyFailure(second) : null;
            if (stillUnrunnable != null) {
                return AcceptanceTestReach.failureBrief(task.title(), stillUnrunnable[0], "The test "
                    + "author was asked once to correct it, and the corrected test still does",
                    survey);
            }
            if (!second.red() && second.results() != null && second.results().passed() > 0) {
                // Green on a tree nothing in this run has touched: the correction measures nothing
                // new — the same verdict the red-check itself gives at TEST_AUTHORING.
                return "Task '" + task.title() + "': its acceptance test reached code that can only "
                    + "run in a browser, and the test author's correction passes on the tree "
                    + "before any work was done, so it proves nothing about the work — "
                    + second.note();
            }
            TaskGraph graph = run.taskGraphId() == null ? null
                : artifactStore.root().taskGraphs.get(run.taskGraphId());
            List<String> undeliverable = second.red()
                ? TypeDeliverability.undeliverable(second.missingTypes(),
                    graph == null ? List.of() : graph.tasks())
                : List.of();
            if (!undeliverable.isEmpty()) {
                String module = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
                return BrokenAcceptanceTest.park(task.title(), undeliverable, module,
                    moduleArtifactIds(module), "the correction of a test that reached "
                        + "browser-only code names " + undeliverable + " instead, which this plan "
                        + "does not deliver and this module cannot see");
            }
            String miscompiled = miscompiledCorrection(task, second, redcheckWorktree,
                graph == null ? List.of() : graph.tasks(), "reached code that can only run in a browser");
            if (miscompiled != null) {
                return miscompiled;
            }
            gitService.commitAll(worktree, "Correct acceptance test that reached browser-only code "
                + "for task '" + task.title() + "' in run " + run.id() + "\n\n"
                + String.join("\n", authored.paths()));
            run.setAcceptanceTestsCommit(gitService.headSha(branch));
            log("TEST_AUTHORING: acceptance test(s) " + authored.paths() + " for task '"
                + task.title() + "' reached browser-only code; the test author corrected them"
                + (second.red() ? " and they are red again" : ""));
            return null;
        } catch (IOException e) {
            return AcceptanceTestReach.failureBrief(task.title(), reason,
                "Reading or writing the test file failed: " + e.getMessage(), survey);
        } finally {
            removeTree(worktree); // the branch stays; the correction is on it
        }
    }

    /**
     * Writes down that this task's checks were already proved before its own wave started, and says
     * so in the log. Never throws: a store that will not take it costs the record and nothing else,
     * exactly like {@link #recordTestAuthoring}.
     *
     * <p>The task object here is the one inside the persisted task graph and the one the swarm
     * engine is about to hand to its workers and its judge, so setting the field is what carries
     * the fact into judging — see {@code JudgeClient.testsDoNotSeparateLine}.
     */
    private void recordAlreadyProved(Run run, StoryScope scope, Task task, VerifySpec spec,
                                     String waveBase, TestResults results) {
        List<AcceptanceCriterion> checks = criteriaFor(task, scope);
        List<String> refs = refsFor(checks, scope);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            lines.add((i < refs.size() ? refs.get(i) : "(a check this task owns)")
                + " — " + checks.get(i).text());
        }
        boolean userFacing = UserFacingWording.anyUserFacing(checks);
        // WHICH OF THE TWO THIS IS (author decision, 2026-09-05, harness run 30). "Green because
        // the waves in front delivered it" is success and is what this record is for. "Green on a
        // tree this run has not touched" is a test that measures itself, and a candidate must not
        // survive on it — see Verdicts#assess. The tree says which: the run's pinned base carries
        // nothing this run delivered.
        boolean nothingDelivered = waveBase != null && waveBase.equals(run.verificationPoint());
        ChecksAlreadyProved record = new ChecksAlreadyProved(Instant.now(), waveBase,
            results == null ? List.of() : results.passedIds(), lines,
            results == null ? 0 : results.passed(), userFacing,
            whereTheRestIsProved(userFacing, checks, spec), nothingDelivered);
        task.setChecksAlreadyProved(record);
        try {
            artifactStore.saveTask(task);
        } catch (RuntimeException e) {
            log("Could not record that the checks of task '" + task.title() + "' were already "
                + "proved: " + e.getMessage() + ". The run carries on; only the record is lost.");
        }
        log(record.describe(task.title()));
    }

    /**
     * Where the half of the check its JUnit test cannot reach is proved — the sentence the operator
     * reads, and the one the judge is given.
     *
     * <p><b>Only the browser stage ever runs the product.</b> An acceptance test lives in the one
     * module that sees every other module (see {@link AcceptanceTestLocation}) and is a JUnit test:
     * it can drive a service, and it cannot open a browser. So a check worded about what somebody
     * SEES is, by construction, only half-provable by the test that claims it — and until the
     * browser block in {@code .swarmcoder/verify.yaml} exists, the other half is proved by nothing
     * at all except a person reading the diff.
     *
     * <p>What the browser stage does today, stated rather than implied: it runs at
     * FINAL_INTEGRATION after every merge, on a local worktree, so it always gets its chance there;
     * and it runs for an individual candidate only where this process can reach the application it
     * started, which a candidate verified inside a sandbox container cannot offer — that case is
     * recorded as "could not try" and never fails the candidate. See
     * {@code BrowserVerifier} and {@code Verdicts.browserFailure}.
     */
    private static String whereTheRestIsProved(boolean userFacing,
                                               List<AcceptanceCriterion> checks, VerifySpec spec) {
        if (!userFacing) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (AcceptanceCriterion check : checks) {
            for (String word : UserFacingWording.wordsFound(check.text())) {
                if (!words.contains(word)) {
                    words.add(word);
                }
            }
        }
        String because = "One of these checks is worded about what a person SEES (\""
            + String.join("\", \"", words) + "\"), and an acceptance test here is a JUnit test in "
            + "the module that sees every other module — it can drive the service and it cannot "
            + "open a browser. So the green test above is genuinely only half of that check.";
        if (spec != null && spec.browser() != null) {
            return because + " The other half is the browser stage: it starts this application for "
                + "real and drives a headless browser at it. It runs at FINAL_INTEGRATION after "
                + "every merge, and for a single candidate wherever this machine can reach the "
                + "application it started (a candidate verified inside a sandbox container cannot "
                + "be reached, and that is recorded as 'could not try' rather than held against "
                + "it). Until then the screen work is judged by reading the diff.";
        }
        return because + " NOTHING in this project's verification contract "
            + "(.swarmcoder/verify.yaml) starts the application, so no browser looks at a screen at "
            + "any point in this run — not for a candidate and not at final integration. The only "
            + "reading of the screen work is the judge reading the diff. Adding a browser block to "
            + "the contract is what changes that.";
    }

    /**
     * What this repository's build says about itself, read once at PLAN.
     *
     * <p>Every compiling module's OWN pom — never the aggregator root as a MODULE, which compiles
     * nothing and whose {@code <dependencyManagement>} pins versions without declaring a dependency
     * for anybody. The root IS read for the declarable catalogue, because that is where a BOM is
     * imported and a pinned version is exactly what makes an artifact declarable.
     *
     * @param declaredByModule how many dependencies each module declares — the tie-breaker when a
     *                         rule names an artifact without saying which module it belongs in
     * @param declaredCoordinatesByModule each module's own declared dependency coordinates —
     *                         what {@link BuildFilesInTheJob#moduleFor} reads to tell which TIER
     *                         (server-side persistence, client-side UI, …) a module already belongs
     *                         to, before falling back to the write-set/fewest-dependencies rule
     * @param catalog          what could be declared offline; empty when there is no local Maven
     *                         repository on this machine, which is a fact, not a failure
     * @param ownBuild         what the build IS rather than what it depends on — every compiling
     *                         module's own artifactId, the root aggregator's, and their group ids
     *                         (see {@link RulesVersusManifest.OwnBuild}), so a rule that merely
     *                         names one of the build's own modules is never mistaken for naming a
     *                         dependency the build must declare
     */
    private record BuildFacts(List<LibraryDoc> declared, Map<String, Integer> declaredByModule,
                              Map<String, List<String>> declaredCoordinatesByModule,
                              List<String> inspectedPoms, DeclarableArtifacts.Catalog catalog,
                              RulesVersusManifest.OwnBuild ownBuild) {

        static final BuildFacts NONE = new BuildFacts(List.of(), Map.of(), Map.of(), List.of(),
            null, RulesVersusManifest.OwnBuild.NONE);

        boolean readable() {
            return !inspectedPoms.isEmpty();
        }
    }

    /** {@link BuildFacts#NONE} when there is no repository, no readable layout, or no Maven. */
    private BuildFacts readBuild(BuildLayout.Layout layout) {
        if (repoPath == null || layout == null || !layout.determined()
                || !"maven".equals(layout.toolchain()) || layout.compilingModules().isEmpty()) {
            return BuildFacts.NONE;
        }
        List<LibraryDoc> declared = new ArrayList<>();
        List<String> inspectedPoms = new ArrayList<>();
        Map<String, Integer> declaredByModule = new java.util.LinkedHashMap<>();
        Map<String, List<String>> declaredCoordinatesByModule = new java.util.LinkedHashMap<>();
        Set<String> ownArtifactIds = new LinkedHashSet<>();
        Set<String> ownGroupIds = new LinkedHashSet<>();
        recordOwnCoordinate(ManifestParser.ownCoordinate(repoPath), ownArtifactIds, ownGroupIds);
        for (String dir : layout.compilingModules()) {
            Path moduleDir = dir.isEmpty() ? repoPath : repoPath.resolve(dir);
            List<LibraryDoc> ofModule = ManifestParser.parse(moduleDir);
            declared.addAll(ofModule);
            declaredByModule.put(dir, ofModule.size());
            declaredCoordinatesByModule.put(dir,
                ofModule.stream().map(LibraryDoc::coordinate).toList());
            inspectedPoms.add(dir.isEmpty() ? "pom.xml" : dir + "/pom.xml");
            recordOwnCoordinate(ManifestParser.ownCoordinate(moduleDir), ownArtifactIds, ownGroupIds);
        }
        DeclarableArtifacts.Catalog catalog = DeclarableArtifacts.scan(repoPath,
            layout.compilingModules(), DeclarableArtifacts.defaultLocalRepository());
        RulesVersusManifest.OwnBuild ownBuild = new RulesVersusManifest.OwnBuild(
            ownArtifactIds, ownGroupIds, repoRootEntries());
        return new BuildFacts(declared, declaredByModule, declaredCoordinatesByModule,
            inspectedPoms, catalog, ownBuild);
    }

    /** Adds one module's own coordinate (never one of its dependencies') to the running sets that
     * become {@link RulesVersusManifest.OwnBuild}. Null when the pom could not be read; nothing to
     * add then. */
    private static void recordOwnCoordinate(ManifestParser.Coordinate coordinate,
                                             Set<String> artifactIds, Set<String> groupIds) {
        if (coordinate == null) {
            return;
        }
        if (coordinate.artifactId() != null && !coordinate.artifactId().isBlank()) {
            artifactIds.add(coordinate.artifactId());
        }
        if (coordinate.groupId() != null && !coordinate.groupId().isBlank()) {
            groupIds.add(coordinate.groupId());
        }
    }

    /**
     * The artifact ids one module's own {@code pom.xml} declares as dependencies — everything a
     * JUnit test written in that module can actually import, sibling modules of this same reactor
     * included (a sibling is just another {@code <dependency>} entry). Empty, never a failure,
     * when there is no repository or the pom cannot be read.
     */
    /**
     * The artifact ids of the build's own browser-only modules — what comes off the list the test
     * author is told it can import (harness run 37). Empty, never a failure, when there are none or
     * a module's pom cannot be read.
     */
    private Set<String> browserOnlyArtifactIds(BrowserOnlyCode.Survey survey) {
        if (repoPath == null || survey == null || !survey.any()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (BrowserOnlyCode.Module module : survey.browserOnly()) {
            Path moduleDir = module.dir().isEmpty() ? repoPath : repoPath.resolve(module.dir());
            ManifestParser.Coordinate own = ManifestParser.ownCoordinate(moduleDir);
            if (own != null && own.artifactId() != null && !own.artifactId().isBlank()) {
                ids.add(own.artifactId());
            }
        }
        return ids;
    }

    private List<String> moduleArtifactIds(String module) {
        if (repoPath == null) {
            return List.of();
        }
        Path moduleDir = module == null || module.isBlank() ? repoPath : repoPath.resolve(module);
        List<String> ids = new ArrayList<>();
        for (LibraryDoc library : ManifestParser.parse(moduleDir)) {
            String coordinate = library.coordinate();
            if (coordinate == null || coordinate.isBlank()) {
                continue;
            }
            int colon = coordinate.indexOf(':');
            String artifactId = colon < 0 ? coordinate : coordinate.substring(colon + 1);
            if (!artifactId.isBlank() && !ids.contains(artifactId)) {
                ids.add(artifactId);
            }
        }
        return List.copyOf(ids);
    }

    /** Names of the entries directly under the repository root, so a backticked path naming one of
     * them (a folder like {@code dev} or {@code docs}, a top-level file) is recognised as the
     * repository, not a dependency. Empty, never a failure, when the directory cannot be listed. */
    private Set<String> repoRootEntries() {
        if (repoPath == null || !java.nio.file.Files.isDirectory(repoPath)) {
            return Set.of();
        }
        try (var entries = java.nio.file.Files.list(repoPath)) {
            Set<String> names = new LinkedHashSet<>();
            entries.forEach(p -> names.add(p.getFileName().toString()));
            return names;
        } catch (java.io.IOException e) {
            return Set.of();
        }
    }

    /**
     * The rules say this project uses a library; the build does not declare it. That used to park
     * the run for a person (2026-09-03, run {@code ede2068b}). It is now WORK.
     *
     * <p>The declaration goes to the task that already writes that module — the build file is in
     * its write set — or, when no task does, to a small task of its own ahead of everything. The
     * run parks on exactly one thing: an artifact the offline Maven repository does not hold.
     * Candidate builds run with no network, so that is an environment fact and no amount of work
     * inside the run can change it.
     *
     * <p><b>The rules are the analyst's restatement, not the paper itself</b> (2026-09-03, run
     * {@code ede2068b}). A stated rule's body is what the analyst wrote down at intake, and an
     * analyst that paraphrases "Persistence uses EclipseStore through
     * {@code zerozstack-store-eclipsestore}" as "Persistence via EclipseStore object graph" drops
     * the one backticked token this comparison exists to catch. The comparison therefore also
     * scans the full text of every technical document the project ingested — the one place the
     * artifact was actually named — so a paraphrase at intake can no longer hide a missing
     * dependency from this check.
     */
    /**
     * The rubric review without the objections that rest on a standing rule (live run 75): those
     * are the rules check's question, asked next of what the design itself changes. A review left
     * with nothing to object to is an approval. See {@link RubricObjections}.
     */
    private DesignReviewerClient.Review rubricOnly(DesignReviewerClient.Review review,
                                                   String rulesBrief) {
        if (review == null || review.approved || review.objections == null) {
            return review;
        }
        RubricObjections.Split split = RubricObjections.split(review.objections, rulesBrief);
        if (split.restingOnARule().isEmpty()) {
            return review;
        }
        log("DESIGN_REVIEW: " + split.restingOnARule().size() + " objection(s) of the rubric "
            + "review rest on a standing rule and are not sent to the architect - whether the "
            + "design keeps the rules is the next check's question, asked of what this design "
            + "itself adds or changes: " + split.restingOnARule());
        DesignReviewerClient.Review kept = new DesignReviewerClient.Review();
        kept.objections = split.forTheArchitect();
        kept.approved = kept.objections.isEmpty();
        return kept;
    }

    /**
     * PLAN, once {@link TaskGraphValidator} has already accepted the graph's shape: does this
     * plan's own tasks ask for anything the project's stated rules forbid (2026-09-03 addendum)?
     * Two layers, cheapest first — {@link ForbiddenTechGuard} runs for free against the rules' own
     * vocabulary of forbidden technology names; only when it finds nothing does this spend the one
     * model call {@link DesignReviewerClient#reviewPlan} costs, because most real conflicts are not
     * a banned keyword, they are a plan that quietly does the opposite of what a rule says — which
     * only a reader can catch.
     *
     * @return objections to feed back through the same re-ask path a shape violation already uses;
     *         empty when the plan is clean, when there are no stated rules to check it against, or
     *         when the reviewer itself failed (logged, and treated as clean rather than blocking
     *         the run — a broken reviewer must never be the reason PLAN cannot proceed)
     */
    private List<String> reviewPlanAgainstRules(TaskGraph candidate, StoryScope planScope) {
        String rulesBrief = planScope == null ? "" : planScope.constraintBrief();
        if (rulesBrief.isEmpty()) {
            return List.of();
        }
        List<Task> tasks = candidate.tasks() == null ? List.of() : candidate.tasks();
        List<String> deterministic = ForbiddenTechGuard.check(rulesBrief, tasks);
        if (!deterministic.isEmpty()) {
            log("PLAN: the plan names a technology the project's rules forbid: " + deterministic);
            return deterministic;
        }
        DesignReviewerClient.Review review = roles.reviewer().reviewPlan(rulesBrief, tasks);
        List<String> objections = review.objections == null ? List.of() : review.objections;
        int ruleCount = ForbiddenTechGuard.ruleCount(rulesBrief);
        if (review.approved) {
            if (objections.isEmpty()) {
                log("PLAN: reviewed against " + ruleCount + " rule(s): no objections");
            } else {
                // A fail-soft note from the reviewer itself (unavailable, or twice malformed) —
                // never a real objection, and never worth parking a run over.
                log("PLAN: " + String.join("; ", objections));
            }
            return List.of();
        }
        log("PLAN: reviewed against " + ruleCount + " rule(s): " + objections);
        return objections;
    }

    /**
     * DESIGN_REVIEW: does this design's OWN decisions and contracts ask for anything the project's
     * stated rules forbid (2026-09-04, harness run 19)? Same shape as {@link
     * #reviewPlanAgainstRules}, cheapest first — {@link ForbiddenTechGuard} against the design's
     * own text for free, then {@link DesignReviewerClient#reviewDesign} for everything else — read
     * against a design instead of a plan's tasks, because a design that decides to do the
     * forbidden thing produces a plan that decomposes that decision faithfully, and neither the
     * rubric review nor {@link TaskGraphValidator} ever reads a design's decisions against a rule.
     *
     * @return objections, in the same "conflicts with rule '&lt;title&gt;'" shape {@link
     *         #reviewPlanAgainstRules} raises; empty when the design is clean, when there are no
     *         stated rules, or when the reviewer itself failed (treated as clean, never blocking)
     */
    private List<String> reviewDesignAgainstRules(DesignDocument design, StoryScope scope) {
        String rulesBrief = scope == null ? "" : scope.constraintBrief();
        if (rulesBrief.isEmpty() || design == null) {
            return List.of();
        }
        Task probe = designAsRuleCheckTask(design);
        List<String> deterministic = ForbiddenTechGuard.check(rulesBrief, List.of(probe));
        if (!deterministic.isEmpty()) {
            log("DESIGN_REVIEW: the design names a technology the project's rules forbid: "
                + deterministic);
            return deterministic;
        }
        DesignReviewerClient.Review review = roles.reviewer().reviewDesign(rulesBrief, design);
        List<String> objections = review.objections == null ? List.of() : review.objections;
        int ruleCount = ForbiddenTechGuard.ruleCount(rulesBrief);
        if (review.approved) {
            if (objections.isEmpty()) {
                log("DESIGN_REVIEW: reviewed against " + ruleCount + " rule(s): no objections");
            } else {
                // A fail-soft note (unavailable, or twice malformed) — never a real objection.
                log("DESIGN_REVIEW: " + String.join("; ", objections));
            }
            return List.of();
        }
        log("DESIGN_REVIEW: reviewed against " + ruleCount + " rule(s): " + objections);
        return objections;
    }

    /**
     * A correction the test author just wrote, for whatever fault, is read against the project's
     * rules before it is red-checked or committed (see {@link TestsVersusRules#afterReauthoring}).
     *
     * @return the brief for a parked run when the correction breaks a HARD rule; otherwise null
     */
    private String correctionBreaksARule(Run run, Task task, Path worktree, List<String> paths) {
        String rules = scopeFor(run).constraintBrief();
        if (rules.isEmpty()) {
            return null;
        }
        TestsVersusRules.Outcome outcome = TestsVersusRules.reauthored(roles.reviewer(), worktree,
            task, paths, rules, line -> log("TEST_AUTHORING: " + line));
        if (outcome.parks() && carriesOpinions()) {
            carry(run, "corrected acceptance test against the project's rules (reviewer model), "
                + "task '" + task.title() + "'", outcome.hard());
            return null;
        }
        return outcome.parkBrief();
    }

    /**
     * check_design and check_plan for the stage about to run on this thread (2026-10-02): the
     * mechanical checks DESIGN_REVIEW and PLAN hold a design and a plan to after hand-in, run on
     * a draft the architect has not handed in yet. The SAME methods, told to say nothing on the
     * run's log - a draft is not the run's design. Model-read checks (the reviewer's rubric and
     * its reading of the rules) are not here: they cost a model call and are not mechanical. The
     * checks after hand-in are unchanged, so this can only save a revision round, never pass
     * something they would refuse.
     */
    private ArchitectClient.Scope checkingDrafts(Run run) {
        if (roles == null || roles.architect() == null || !roles.architect().worksAsAnAgent()) {
            return () -> { };
        }
        return roles.architect().checkingDraftsWith(new ArchitectClient.DraftChecks() {
            @Override
            public List<String> design(DesignDocument draft) {
                StoryScope scope = scopeFor(run);
                Consumer<String> silent = line -> { };
                DesignDocument design = withoutContractsForExistingLibraryTypes(draft, silent);
                List<String> objections = new ArrayList<>();
                if (!scope.isEmpty() && hasNoTypedContract(design)) {
                    objections.add(missingContractsSentence(scope) + CONTRACT_SHAPE_ASK);
                }
                objections.addAll(contractTypeObjections(design, silent));
                String rules = scope.constraintBrief();
                if (!rules.isEmpty()) {
                    objections.addAll(
                        ForbiddenTechGuard.check(rules, List.of(designAsRuleCheckTask(design))));
                }
                return objections;
            }

            @Override
            public List<String> plan(TaskGraph draft, DesignDocument design) {
                StoryScope scope = scopeFor(run);
                BuildLayout.Layout layout = repoLayout();
                withAcceptanceTestDir(draft, AcceptanceTestLocation.resolve(layout).protectedDir());
                BuildFilesInTheJob.expandWriteSets(draft, layout, repoPath);
                TaskGraphValidator.Verdict verdict = connectingWhatItAdds(
                    new TaskGraphValidator(browserOnlySurvey(layout))
                        .validate(draft, scope, layout, design, repoPath), draft);
                List<String> objections = new ArrayList<>(verdict.violations());
                String rules = scope == null ? "" : scope.constraintBrief();
                if (verdict.ok() && !rules.isEmpty() && draft.tasks() != null) {
                    objections.addAll(ForbiddenTechGuard.check(rules, draft.tasks()));
                }
                return objections;
            }
        });
    }

    /**
     * DESIGN_REVIEW: the library types this design's contracts name that exist nowhere — not in
     * the design, the project, the JDK, or the reference checkout the library's source is in
     * (harness runs 44/45, 2026-09-27; see {@link ContractsNameRealTypes}) — plus every contract
     * that names a type with no package at all ({@link ContractsNameAQualifiedType}, added
     * 2026-09-27 alongside the harness-run-46 fix to {@code ArchitectClient.resolveTypeName}: that
     * recovery can legitimately land on a simple name such as "Book" when no package is honestly
     * recoverable, and a contract keyed on a simple name is one {@code ContractDelivery} and
     * {@link TaskGraphValidator} can never prove a worker delivered, since any package would match
     * it). Free: source-text indexes and a string check, no model call. Empty when there is
     * nothing to object to.
     */
    private List<String> contractTypeObjections(DesignDocument design) {
        return contractTypeObjections(design, this::log);
    }

    /** @param say where the findings are said: the run's log, or nowhere for a draft */
    private List<String> contractTypeObjections(DesignDocument design, Consumer<String> say) {
        return DesignFactChecks.objections(design, repoPath,
            librarian == null ? LibraryTypes.NONE : librarian.libraryTypes(), say);
    }

    /**
     * {@code design} without the contracts that name a type a reference library or the JDK already
     * has (live run 67, 2026-10-02; see {@link ContractsAreDeliverable}). Such a type is used, not
     * delivered: left in, the plan check demands a task that creates it, and none can. The same
     * design when there is nothing to remove. Free: source-text indexes, no model call.
     */
    private DesignDocument withoutContractsForExistingLibraryTypes(DesignDocument design) {
        return withoutContractsForExistingLibraryTypes(design, this::log);
    }

    private DesignDocument withoutContractsForExistingLibraryTypes(DesignDocument design,
                                                                    Consumer<String> say) {
        if (design == null) {
            return null;
        }
        LibraryTypes library = librarian == null ? LibraryTypes.NONE : librarian.libraryTypes();
        ContractsAreDeliverable.Outcome outcome =
            ContractsAreDeliverable.check(design, ProjectTypes.of(repoPath), library);
        if (outcome.existing().isEmpty()) {
            return design;
        }
        say.accept("Design: " + outcome.existing().size() + " contract(s) name a type the library "
            + "already has — an existing type, not something a task delivers; taken out of the "
            + "contracts: " + outcome.existing().stream().map(c -> c.typeName().strip()).toList());
        return new DesignDocument(design.id(), design.revision(), design.goal(),
            design.requirements(), design.decisions(), outcome.remaining(design), design.risks(),
            design.review(), design.createdAt());
    }

    /**
     * The design's decisions and contracts, dressed as the one thing both {@link ForbiddenTechGuard}
     * and {@link DesignReviewerClient#reviewPlan}'s own detection already know how to read — never
     * built, never dispatched, never anything but the argument to that one call.
     */
    private Task designAsRuleCheckTask(DesignDocument design) {
        return designAsRuleCheckTask(design, roles.architect().taskPolicy());
    }

    /** The design's decisions and contract sketches as one task, so the plan's rule checks read it. */
    static Task designAsRuleCheckTask(DesignDocument design, SwarmPolicy policy) {
        StringBuilder instructions = new StringBuilder();
        for (ArchDecision d : design.decisions()) {
            if (d.decision() == null || d.decision().isBlank()) {
                continue;
            }
            instructions.append(d.decision());
            if (d.rationale() != null && !d.rationale().isBlank()) {
                instructions.append(" — ").append(d.rationale().strip());
            }
            instructions.append('\n');
        }
        for (ApiContract c : design.contracts()) {
            instructions.append(c.name()).append(": ").append(c.signatureSketch()).append('\n');
        }
        String title = design.goal() == null || design.goal().isBlank() ? "Design" : design.goal();
        return new Task(UUID.randomUUID(), 1, title, instructions.toString(),
            new HashSet<>(), new HashSet<>(), List.of(), ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            policy, TaskState.PENDING);
    }

    private BuildFilesInTheJob.Outcome declareMissingDependencies(
            Run run, StoryScope scope, BuildLayout.Layout layout, BuildFacts build,
            TaskGraph graph) {
        String rules = scope == null ? "" : scope.constraintBrief();
        List<RulesVersusManifest.NamedDocument> documents = technicalDocuments(run);
        if ((rules.isEmpty() && documents.isEmpty()) || !build.readable()) {
            return BuildFilesInTheJob.Outcome.nothingToDo(graph);
        }
        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(rules, documents,
            build.declared(), build.inspectedPoms(), build.ownBuild());
        for (RulesVersusManifest.Finding finding : findings) {
            log("PLAN: `" + finding.artifact() + "` is named in " + finding.sourceLabel()
                + ", and no inspected pom declares it (\"" + finding.ruleExcerpt() + "\")");
        }
        return BuildFilesInTheJob.declareMissing(graph, findings, build.catalog(), layout,
            build.declaredByModule(), build.declaredCoordinatesByModule(), repoPath);
    }

    /**
     * Every technical document this run's project has ingested, in full — the paper a stated rule
     * may only have paraphrased.
     *
     * <p>"Technical" is not a fact {@link SourceDocument} itself carries; it is the tick an
     * operator made on one document of one {@link GuidedFlow} attachment, at intake. So every
     * flow of the project is read and every document any of them ever marked technical is
     * collected, deduplicated by document id — a document ticked technical once stays technical
     * for this comparison even after the flow that ticked it has moved on to APPLIED.
     */
    private List<RulesVersusManifest.NamedDocument> technicalDocuments(Run run) {
        if (run == null || run.storyId() == null) {
            return List.of();
        }
        Story story = artifactStore.getStory(run.storyId());
        if (story == null || story.projectId() == null) {
            return List.of();
        }
        Set<UUID> technicalIds = new LinkedHashSet<>();
        for (GuidedFlow flow : artifactStore.listGuidedFlows(story.projectId())) {
            for (FlowDocument entry : flow.documents()) {
                if (entry.technical()) {
                    technicalIds.add(entry.documentId());
                }
            }
        }
        List<RulesVersusManifest.NamedDocument> documents = new ArrayList<>();
        for (UUID documentId : technicalIds) {
            SourceDocument document = artifactStore.getSourceDocument(documentId);
            if (document != null && document.extractedText() != null
                    && !document.extractedText().isBlank()) {
                documents.add(new RulesVersusManifest.NamedDocument(document.filename(),
                    document.extractedText()));
            }
        }
        return documents;
    }

    /** The "libraries you may ADD" list for every worker's brief, or null when nothing is known. */
    private OfflineLibraryBrief offlineLibraries(BuildFacts build) {
        if (build == null || build.catalog() == null || build.catalog().isEmpty()) {
            return null;
        }
        return new OfflineLibraryBrief(build.catalog(), build.declared(), projectRules());
    }

    /**
     * Parks the run with a brief the operator can act on.
     *
     * <p>The brief is the caller's, in full. It used to be wrapped in a fixed "TEST_AUTHORING
     * red-check failed" preamble regardless of where the call came from, so a failed integration
     * and a failed quality gate both announced themselves as a red-check problem — the one thing
     * they were not.
     */
    private void queueDecision(Run run, String brief) {
        UUID decisionId = UUID.randomUUID();
        artifactStore.append(() -> {
            artifactStore.root().decisions.put(decisionId, new Decision(
                decisionId, run.id(), DecisionKind.BLOCKED_TASK, brief,
                DecisionState.PENDING, null, Instant.now()));
            return null;
        });
    }

    /**
     * Stops the run at its current stage and raises the question that explains why — the ONE place
     * every genuine mid-workflow park in this class goes through (PLAN, the TEST_AUTHORING mismatch,
     * the red-check, {@code RunMustPark} at EXECUTING, and a failed FINAL_INTEGRATION), so the card's
     * health and its sentence can never be built from a different fact than the question was.
     *
     * <p>Deliberately not used by the quality-gate refusal inside {@link #recordDelivery}: that one
     * ends the run at ABORTED in the same breath, which {@link BuildHealth} already reads as stopped
     * on its own — there is no mid-workflow park to mark, and marking one would say a resumable
     * stage is waiting here when none is.
     */
    private void parkRun(Run run, String reason) {
        run.setParkedAt(Instant.now());
        run.setParkReason(reason);
        queueDecision(run, reason);
    }

    /**
     * Resolves every still-pending question this run raised while it was at the state it has just
     * left, now that it has genuinely moved on.
     *
     * <p>Recorded the same way a human's own answer is ({@code ControlServiceImpl.resolveDecision}:
     * the row is rewritten RESOLVED with a note, never deleted) so there is one decision lifecycle in
     * this codebase, not a second one invented for the system's own answers. Scoped to
     * {@code DecisionKind.BLOCKED_TASK} and this run's own id — the only kind {@link #parkRun} ever
     * raises — so an unrelated decision that happens to name the same run is never touched.
     *
     * <p>This is what makes the card stop asking a question about a run that is now visibly building
     * again: {@code PipelineBoard.questionsFor} reads only {@code DecisionState.PENDING} rows, and a
     * run resumed through {@link RunResumer} at process start is, today, the one way this ever fires
     * for the SAME run — a fresh "Build it again" starts a new run instead, and that new run's own
     * progress raises and withdraws its own questions the same way.
     */
    private void withdrawStaleDecisions(Run run) {
        List<UUID> stale = new ArrayList<>();
        for (Decision decision : artifactStore.root().decisions.values()) {
            if (decision != null && decision.state() == DecisionState.PENDING
                    && decision.kind() == DecisionKind.BLOCKED_TASK
                    && run.id().equals(decision.runId())) {
                stale.add(decision.id());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        log("Run " + run.id() + " moved past the state that raised " + stale.size()
            + " question(s) — withdrawing them; nothing is waiting on an answer any more.");
        artifactStore.append(() -> {
            for (UUID id : stale) {
                Decision decision = artifactStore.root().decisions.get(id);
                if (decision != null && decision.state() == DecisionState.PENDING) {
                    artifactStore.root().decisions.put(id, new Decision(decision.id(),
                        decision.runId(), decision.kind(), decision.briefMarkdown(),
                        DecisionState.RESOLVED, "Withdrawn — the run moved on.",
                        decision.createdAt()));
                }
            }
            return null;
        });
    }
}
