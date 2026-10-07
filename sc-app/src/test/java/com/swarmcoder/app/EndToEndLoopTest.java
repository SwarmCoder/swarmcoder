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

import com.swarmcoder.swarm.HarnessSandbox;
import com.swarmcoder.console.BacklogServiceImpl;
import com.swarmcoder.console.BrdServiceImpl;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.console.DocumentIngest;
import com.swarmcoder.console.GuidedFlowServiceImpl;
import com.swarmcoder.console.PlanningFlowServiceImpl;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.TurnAllowance;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.BasePin;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.ExpertDesk;
import com.swarmcoder.knowledge.ExpertEscalation;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.knowledge.GuidelineFolderImport;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import com.swarmcoder.swarm.RuleQuestions;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.LocalProcessExecTarget;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.RunResumer;
import com.swarmcoder.workflow.StoryScope;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.util.Optional;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * <b>The whole journey, walked in one command, saying where it broke.</b>
 *
 * <p>A document goes in one end of SwarmCoder and a merged commit comes out the other. Between them
 * are twelve links: documents ingest, a technical document becomes project rules, a business
 * document becomes requirements with checks, the checks are agreed, the planner slices them into
 * stories, the architect plans tasks against them, the tasks write only where the build compiles,
 * a test author writes acceptance tests somewhere the build runs them, that stage executes a
 * non-zero number of tests, workers produce candidates, verification returns a real verdict, the
 * judge is told what verification found, a winner is selected, and the winner is merged.
 *
 * <p><b>Every one of those broke separately in the week of 2026-08-25.</b> Each break was proved
 * fixed in isolation, and each hid the next, because the operator was the integration test: set a
 * project up by hand, watch it stop, report back, twenty minutes a round, ten rounds in a day, one
 * defect found per round. This is that loop, unattended.
 *
 * <h2>What makes it different from the harnesses it stands beside</h2>
 *
 * <ul>
 *   <li>{@link FullProductDressRehearsalTest} walks the same journey and asserts more strictly
 *       about the requirement graph — but against a hand-built one-module calculator repository,
 *       an inline five-line document, and no technical document at all, so project rules, the
 *       multi-module acceptance-test location and the "which module compiles this" question can
 *       never come up. Its failures are AssertJ failures, one line of expected/actual.</li>
 *   <li>{@code LiveEndToEndTest} (sc-workflow) starts from a goal STRING: no project, no document,
 *       no requirement, no story, so the whole front half of the chain is absent.</li>
 *   <li>{@code LiveM1Test} (sc-swarm) starts from a hand-written Task: only the swarm half.</li>
 *   <li>{@code ConfigDrivenE2ETest} (sc-app) is the only one that uses the operator's real config,
 *       and that config bills a cloud account on every call, so it must never run here.</li>
 * </ul>
 *
 * <p>This one uses the REAL demo project ({@code dev/bookshelf-demo}: three Maven modules, an
 * aggregator root that compiles nothing, committed project guidelines, a real verification
 * contract), the REAL documents ({@code dev/bookshelf-requirements.md} and
 * {@code dev/bookshelf-tech-requirements.md}), and it measures COUNTS AND LOCATIONS at every link
 * rather than exit status — because the failure that keeps happening is a stage that exits 0
 * having done nothing.
 *
 * <h2>No paid call, ever</h2>
 *
 * <p>The operator's {@code ~/.swarmcoder/config.yaml} points seven roles at a billed endpoint. This
 * test never loads that file. It builds every client itself against one free local endpoint, and
 * {@link #refuseAnythingButAFreeLocalEndpoint} refuses to start unless that endpoint is plain HTTP
 * on a private address — so a mistyped flag cannot quietly spend money.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=EndToEndLoopTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8000/v1 -Dswarmcoder.live.model=deepseek-v4-flash \
 *   -Dswarmcoder.live.shape=deepseek-v4-flash-ds4
 * </pre>
 *
 * <p>{@code swarmcoder.live.shape} names the {@code ModelShapes} entry for the model; leave it out
 * and the generic shape asks for json_object, which ds4 refuses. Do not use
 * {@code dev/e2e-loop.ps1}: it is stale.
 *
 * <h3>Watching it while it runs (2026-09-25)</h3>
 *
 * <p>Once the harness has wired itself it serves the product's own Console and its own MCP server
 * over THIS run's temp store ({@link HarnessWindow}), and prints where, one line each:
 *
 * <pre>
 * [E2E] &gt;&gt;&gt; WATCH IN A BROWSER: http://localhost:9090/   (this run's own store; watch-only)
 * [E2E] &gt;&gt;&gt; WATCH OVER MCP (read-only): http://127.0.0.1:8931/mcp
 * </pre>
 *
 * <p>The ports are the app's own defaults, because the app is never running during a harness run
 * (they would compete for the one model server) and the Claude session's MCP client is already
 * pointed at 8931. On by default; {@code -Dswarmcoder.e2e.observe=false} turns it off, and
 * {@code -Dswarmcoder.e2e.consolePort} / {@code -Dswarmcoder.e2e.mcpPort} move it. A port that is
 * taken gets an {@code [E2E] !!! NO BROWSER VIEW} or {@code NO MCP VIEW} line and the walk carries
 * on without that view — it never breaks the chain. Both views only watch: the MCP server offers no
 * tool that changes anything, and the Console refuses a browser's attempt to start, approve or
 * answer anything, run a wizard or change a rule (the harness's own calls go through). Both close
 * when the walk ends, on a verdict or a break; {@code -Dswarmcoder.e2e.holdMinutes=N} prints the
 * chain report and keeps them up N more minutes first, because once the walk is over its temp
 * store is gone and there is nothing left to look at.
 *
 * <h3>Saving the front half, and starting from it (2026-09-25)</h3>
 *
 * <p>On DeepSeek V4 Flash, links 1 to 9 — everything before the workers — take 20 to 40 minutes
 * and come out nearly the same every run, while the work being iterated on is the workers.
 *
 * <ul>
 *   <li>{@code -Dswarmcoder.e2e.saveAtBuild=<dir>} — an ordinary full walk that, at the moment the
 *       run enters EXECUTING and before any worker is dispatched ({@link DispatchSeam}), writes a
 *       snapshot to {@code <dir>}: the store (EclipseStore's own backup of the open store), the
 *       fixture repository with the run's tests commit, and a manifest naming the run, the
 *       SwarmCoder commit, the model and links 1 to 9 with what was measured at each. It saves only
 *       if those nine links held, never over an existing snapshot, and then carries on to link
 *       16.</li>
 *   <li>{@code -Dswarmcoder.e2e.resumeFrom=<dir>} — copies the snapshot into this test's temp
 *       directory (the snapshot is only ever read, so it can be resumed from again and again),
 *       wires everything exactly as a full walk does ({@link #wire}), resumes the run in EXECUTING
 *       through the product's own start-up path ({@code RunResumer}), and walks links 10 to 16.
 *       Links 1 to 9 are printed {@code [CHAIN saved]} with the saving run's observations and
 *       never as held; the verdict says the chain was whole FROM A SNAPSHOT. Link 10 is walked,
 *       not restored: its evidence is the candidates' own acceptance results, which only exist
 *       once the workers have run.</li>
 * </ul>
 *
 * <h3>Four restart points (2026-10-01)</h3>
 *
 * <p>{@code -Dswarmcoder.e2e.saveAt=<dir>} saves one sub-folder per {@link RestartPoint} as the
 * run passes it: {@code 1-requirements} (documents ingested, rules stated, requirement agreed,
 * story planned; the run just entered DESIGN), {@code 2-design} (the design passed review; the run
 * entered PLAN), {@code 3-plan} (the plan accepted; the run entered TEST_AUTHORING) and
 * {@code 4-build} (the original {@code saveAtBuild} point, which keeps working and, when given,
 * names that folder instead). Each holds the store backup, the fixture clone and a manifest with the
 * point, the SwarmCoder commit, the run id and the SHA-256 of both input documents.
 * {@code -Dswarmcoder.e2e.resumeFrom=<dir>/<point>} restores one and resumes the run in the state
 * it was saved in, through the product's own start-up path. Links before the point are printed
 * {@code [CHAIN saved]}; the rest are walked. A resume from points 1 to 3 walks links 7 to 9 after
 * the run, as a full walk does, since the plan and the tests are made after it. A document whose
 * hash differs from the manifest's is a warning, not a refusal. The three earlier points are saved
 * through {@link StageSeam} (a subclass of the store that fires after a state transition is
 * persisted), with no product change.
 *
 * <p>A resume may be given {@code saveAt} (and {@code saveAtBuild}) too: a run resumed from point N
 * saves every LATER point as it passes it, with the same manifest as a full run (links before N
 * are the restored observations, the input document hashes are the resumed manifest's, and
 * {@code resumedFrom} names the snapshot it started from). It never rewrites point N or earlier,
 * never overwrites an existing folder (the loud warning), and {@code saveAt} may be the directory
 * the resume point lives in.
 *
 * <p><b>When a snapshot is stale.</b> A resume prints a loud warning when the SwarmCoder checkout
 * differs from the one that saved the snapshot in any file that shapes links 1 to 9 — the
 * wizards, the design/plan/test-authoring workflow, knowledge, verification layout, the domain and
 * store, the inference clients, this harness's front half, the two documents
 * ({@link SnapshotStaleness#FRONT_HALF}) — and when the model or shape differs. It is a warning,
 * not a refusal: the plan and tests in it are what an older product made, and a green resumed walk
 * says nothing about the front half as it is now. Save a new one when that matters. A snapshot
 * also cannot be resumed while another copy of its run still has a checkout under
 * {@code ~/.swarmcoder/wt} whose repository exists; the resume stops and names it. The brownfield
 * harness does not support either property.
 *
 * <h2>What was cut to make it runnable, and what was not</h2>
 *
 * <p>A full nine-story build of Bookshelf takes hours. Three things are turned down, and no stage
 * is skipped:
 *
 * <ul>
 *   <li><b>One requirement is agreed, not all of them.</b> The analyst reads the whole business
 *       document and proposes everything it sees; the operator then promotes exactly one — the
 *       smallest, by number of checks — and leaves the rest in DRAFT. That is the ordinary operator
 *       move, and it holds the planner's scope to one piece of work.</li>
 *   <li><b>One story is built.</b> If the planner slices that requirement into more than one, the
 *       first is built and the rest are named in the log.</li>
 *   <li><b>Two workers per task, the product's own turn allowance</b> (defaults; see the
 *       properties below). Two workers rather than one because clustering, judging and
 *       selection are all short-circuited by there being a single candidate, and those are
 *       three of the links being tested. The turn allowance is resolved through
 *       {@link TurnAllowance#resolve} exactly as production resolves it — nothing here states a
 *       number, so every worker gets {@link TurnAllowance#BUILT_IN_MAX_TOOL_TURNS}. This harness
 *       used to hardcode a default of twenty-four, a number the product never gives a worker
 *       anywhere: harness run 11 (2026-09-02) killed two workers at exactly turn 25 with
 *       {@code BUDGET_EXCEEDED} and no compaction ever having run — the harness's own cut-down
 *       cap, not a fact about the model or the task. Twelve was tried before twenty-four and is
 *       below the floor: on 2026-08-31 every worker of the first task hit twelve having written
 *       nothing at all, so the swarm produced no candidate and the run parked. A budget that
 *       starves every worker measures the budget, not the product — so this harness no longer
 *       states one of its own.</li>
 * </ul>
 *
 * <h3>Another project (2026-09-30)</h3>
 *
 * <p>The first run against a second project, HamBook, found the harness could only ever walk
 * Bookshelf. Four properties point it elsewhere, each defaulting to today's behaviour:
 * {@code -Dswarmcoder.e2e.fixtureRepo=<git repo>} (its HEAD is cloned as the target),
 * {@code -Dswarmcoder.e2e.businessDoc=<file>}, {@code -Dswarmcoder.e2e.technicalDoc=<file>} and
 * {@code -Dswarmcoder.e2e.verifySpec=<verify.yaml>} (placed at {@code .swarmcoder/verify.yaml} in
 * the clone and committed into the base commit — for a target that has no contract of its own).
 * The project is registered under the target repository's directory name; the clone stays in
 * {@code work/bookshelf}, which is the directory a snapshot is saved from and restored to.
 *
 * <p>Knobs, all with defaults: {@code -Dswarmcoder.e2e.workers}, {@code -Dswarmcoder.e2e.turns}
 * (states a number the way {@code budgets.maxToolTurnsPerWorker} would — leave it unset to get the
 * built-in allowance), {@code -Dswarmcoder.e2e.minutes}, {@code -Dswarmcoder.e2e.requirement=<substring>} (a RESUMED walk stops at the start when it names a different requirement from the snapshot's; see {@link HarnessSnapshot#requirementMismatch}),
 * {@code -Dswarmcoder.e2e.reference=<path>} (default {@code C:/work/zeroz4j} — the reference
 * documentation the workers are given; see {@link HarnessReferenceRoot}),
 * {@code -Dswarmcoder.e2e.saveAtBuild=<dir>} and {@code -Dswarmcoder.e2e.resumeFrom=<dir>} (above),
 * {@code -Dswarmcoder.e2e.observe} (default true), {@code -Dswarmcoder.e2e.consolePort} (9090),
 * {@code -Dswarmcoder.e2e.mcpPort} (8931) and {@code -Dswarmcoder.e2e.holdMinutes} (0) (above).
 */
@RunsWhen({Need.LIVE_MODEL, Need.MAVEN})
class EndToEndLoopTest {

    static {
        // A live harness run uses the Java language server when one is installed; the build's
        // unit tests do not (the pom runs them with it switched off).
        System.setProperty(com.swarmcoder.lsp.JdtLsInstall.SWITCH, "on");
    }

    // NOTE ON VISIBILITY. A handful of members below are package-private rather than private,
    // because BrownfieldLoopTest walks the same links 7-14 against a third-party repository and
    // shares them: the free-endpoint refusal, the run-outcome record and its wait loop, the
    // prompt-recording client, the budget-stamping architect, the pinned run starter, and the
    // small readers over the store and the repository. Copying them would give this repository two
    // definitions of "the harness refuses a billed endpoint" and two of "how a run is started",
    // and the second copy of each is the one that drifts. Nothing outside this package sees any of
    // them.

    // --- the chain, named once, in order ---------------------------------------------------

    private static final String L_FIXTURE = "a pristine copy of the demo project exists";
    private static final String L_INGEST = "both documents ingest and yield text";
    private static final String L_RULES = "the technical document becomes project rules";
    private static final String L_REQS = "the business document becomes requirements carrying checks";
    private static final String L_AGREED = "one requirement is agreed and every check names a test";
    private static final String L_STORIES = "the planner slices the agreed checks into stories";
    private static final String L_TASKS = "every agreed check is claimed, and no task was planned "
        + "for nothing";
    private static final String L_WRITESETS = "every task writes only to a source root the build compiles";
    private static final String L_TESTS_WRITTEN = "acceptance tests land where the build compiles them";
    private static final String L_TESTS_RUN = "the acceptance stage executes a non-zero number of tests";
    private static final String L_CANDIDATE = "a worker produced a candidate that changes a file";
    private static final String L_VERIFIED = "verification reported a real verdict, not an empty pass";
    private static final String L_JUDGE = "the judge was told whether the candidate was verified";
    private static final String L_WINNER = "a winner was selected, or a clear reason given";
    private static final String L_INTEGRATED = "the winner is merged into the repository";
    private static final String L_PROVES = "the story's acceptance test is red on the tree before "
        + "the delivered commit and green on it";

    private static final List<String> CHAIN = List.of(L_FIXTURE, L_INGEST, L_RULES, L_REQS,
        L_AGREED, L_STORIES, L_TASKS, L_WRITESETS, L_TESTS_WRITTEN, L_TESTS_RUN, L_CANDIDATE,
        L_VERIFIED, L_JUDGE, L_WINNER, L_INTEGRATED, L_PROVES);

    /** The wizards run on their own threads; a local 27B reading a 5KB document is not instant. */
    private static final long FLOW_TIMEOUT_MILLIS = Long.getLong("swarmcoder.e2e.flowMinutes", 10)
        * 60_000;

    @TempDir
    Path work;

    private final ChainLedger chain = new ChainLedger(CHAIN);

    /** Stage changes, parks and the wizard stages, timed for the run report at the end. */
    private final HarnessRunReport.Clock clock = new HarnessRunReport.Clock();

    /** True once the chain report is out — a hold prints it before it waits (HarnessWindow). */
    private boolean reported;

    /**
     * The coordinator standing in for the operator when the run parks, or null (the default) when
     * {@code -Dswarmcoder.e2e.askDir} is absent — then a park ends the walk as it always did.
     */
    private final CoordinatorAsk ask = CoordinatorAsk.fromProperties(System.out::println);

    @AfterEach
    void clearContext() {
        // Installed statically; left behind it points later tests at a closed store.
        ConsoleContext.set(null);
        printReportOnce();
    }

    private void printReportOnce() {
        if (!reported) {
            reported = true;
            System.out.println(chain.report());
            if (ask != null) {
                System.out.println(ask.report());
            }
        }
    }

    @Test
    void theWholeChainWalksFromTwoDocumentsToAMergedCommit() throws Exception {
        String baseUrl = refuseAnythingButAFreeLocalEndpoint();
        String model = System.getProperty("swarmcoder.live.model", "deepseek-v4-flash");
        int workers = Integer.getInteger("swarmcoder.e2e.workers", 2);
        TurnAllowance allowance = resolveTurnAllowance();
        HarnessRunBudget.Budget runBudget = HarnessRunBudget.resolve(workers, allowance);
        long deadline = System.currentTimeMillis() + runBudget.millis();
        System.out.println("[E2E] endpoint=" + baseUrl + " model=" + model + " workers=" + workers
            + " turns=" + allowance.maxToolTurns() + " (" + allowance.label() + ") budget="
            + runBudget.minutes() + "min (" + runBudget.sentence() + ")");
        // Roles taken from the operator's config instead of the local server
        // (-Dswarmcoder.e2e.rolesFromConfig). Resolved before anything is spent; a bad name or a
        // role with no settings fails here.
        HarnessRoleServers.Choice roleServers;
        try {
            roleServers = HarnessRoleServers.fromSystemProperty();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AssertionError(e.getMessage());
        }
        HarnessRoleServers.Server localServer =
            new HarnessRoleServers.Server(baseUrl, "", model, null, true);
        roleServers.startUpLines(localServer).forEach(System.out::println);
        this.servers = roleServers.forReport(localServer);

        // Every model call of this walk is kept in memory, with the server's own token counts,
        // for the run report written at the end (HarnessRunReport).
        com.swarmcoder.inference.RunMeter.enable();
        com.swarmcoder.inference.RunMeter.reset();
        com.swarmcoder.inference.LookupMeter.reset();

        Map<RestartPoint, Path> saves = HarnessSnapshot.savePlan();
        Path resumeFrom = HarnessSnapshot.resumeSource();
        Settings settings = new Settings(baseUrl, model, workers, allowance, runBudget, deadline,
            roleServers);
        if (resumeFrom != null) {
            resumeWalk(resumeFrom, settings, saves);
        } else {
            warnAboutExisting(saves);
            walk(settings, saves);
        }
    }

    private static void warnAboutExisting(Map<RestartPoint, Path> saves) {
        saves.forEach((point, target) -> {
            if (Files.exists(target)) {
                System.out.println("[E2E] !!! " + target + " already exists, so the snapshot at "
                    + point.folder() + " will NOT be saved (a snapshot is never written over "
                    + "another). Name a new directory or delete that one.");
            }
        });
    }

    /**
     * How many tool turns this harness's workers get — production's own resolution, not a
     * harness default, and cheap enough ({@link TurnAllowance#resolve} touches nothing but system
     * properties) to be called from a test that needs no live model at all: see
     * {@code HarnessTurnAllowanceTest}.
     *
     * <p>Nothing here states a story or project number, so an unset {@code -Dswarmcoder.e2e.turns}
     * falls through to {@link TurnAllowance#BUILT_IN_MAX_TOOL_TURNS} exactly the way an ordinary
     * planned task does in production ({@code WorkerLoop.maxTurnsFor}). Before 2026-09-03 this
     * harness hardcoded a default of 24, a number production never gives a worker anywhere — and
     * harness run 11 killed two workers at exactly turn 25 with {@code BUDGET_EXCEEDED} and no
     * compaction in sight, which was this cut-down cap, not a fact about the model or the task.
     */
    static TurnAllowance resolveTurnAllowance() {
        return TurnAllowance.resolve(null, null, null, Integer.getInteger("swarmcoder.e2e.turns", null));
    }

    /** What the command line asked for, resolved once, for both kinds of walk. */
    private record Settings(String baseUrl, String model, int workers, TurnAllowance allowance,
                            HarnessRunBudget.Budget runBudget, long deadline,
                            HarnessRoleServers.Choice roleServers) {
    }

    /** Which server each role ran on, for the run report; all local when no role is from config. */
    private HarnessRunReport.Servers servers = HarnessRunReport.Servers.ALL_LOCAL;

    /** Where the product cuts its checkouts — the root a resume clears this run's leftovers from. */
    private static final Path WORKTREE_ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    // --- the walk ----------------------------------------------------------------------------

    /**
     * The whole chain, links 1 to 16.
     *
     * @param saves where to save a snapshot at each {@link RestartPoint} the run passes, empty for
     *              none; see {@link HarnessSnapshot}
     */
    private void walk(Settings settings, Map<RestartPoint, Path> saves) throws Exception {
        Path saveTo = saves.get(RestartPoint.BUILD);

        // --- 1. the fixture ------------------------------------------------------------------
        BookshelfFixture.Fixture fixture;
        try {
            fixture = BookshelfFixture.create(work.resolve("bookshelf"));
        } catch (Exception e) {
            throw chain.fail(L_FIXTURE, "the target project could not be copied: " + e.getMessage());
        }
        Path repo = fixture.repo();
        keptTests = fixture.keptTests();
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        AcceptanceTestLocation.Location acceptanceHome = AcceptanceTestLocation.resolve(layout);
        Environment env = prepare(repo, settings, L_FIXTURE);

        chain.require(L_FIXTURE, layout.determined() && !layout.compilingModules().isEmpty(),
            fixture.describe() + "; the build compiles " + layout.compilingModules().size()
                + " module(s) " + layout.compilingModules() + " and acceptance tests belong in "
                + acceptanceHome.writeDir() + " — " + acceptanceHome.note()
                + "; " + env.reference().describe() + "; " + env.modelBudget().describe()
                + "; the help desk's expert tier is wired to " + settings.baseUrl() + " ("
                + settings.model() + "), the "
                + "same free local model the workers run on — production escalates to the "
                + "configured utility/architect role instead — and it is an agent session with "
                + ExpertEscalation.MAX_TURNS + " turns and these read-only tools over this "
                + "project: " + String.join(", ", ExpertTools.toolNames(
                    env.reference().librarian().curator(), env.reference().librarian()))
                + "; " + settings.allowance().sentence() + "; " + settings.runBudget().sentence());

        // The stage seam is handed the store before the store exists, so its actions reach it
        // through this holder; they only ever run after the store is open and wired.
        ArtifactStore[] opened = new ArtifactStore[1];
        SaveBasis basis = new SaveBasis(fixture.origin(), fixture.baseCommit(), List.of(), null,
            HarnessSnapshot.documentHash(documentText(true)),
            HarnessSnapshot.documentHash(documentText(false)));
        Map<RunState, java.util.function.Consumer<Run>> atStages = new java.util.EnumMap<>(RunState.class);
        saves.forEach((point, target) -> {
            if (point != RestartPoint.BUILD) {
                atStages.put(point.runState(), entered -> saveSnapshot(point, target, opened[0],
                    repo, entered, layout, acceptanceHome, basis, env, settings));
            }
        });
        StageSeam stageSeam = new StageSeam(atStages).observing(clock::seen);
        try (ArtifactStore store = new StageSeam.Store(work.resolve("store"), stageSeam);
             HarnessWindow window = HarnessWindow.fromProperties(System.out::println)) {
            opened[0] = store;
            DispatchSeam seam = saveTo == null ? DispatchSeam.NONE
                : new DispatchSeam(atDispatch -> saveSnapshot(RestartPoint.BUILD, saveTo, store,
                    repo, atDispatch, layout, acceptanceHome, basis, env, settings));
            Wiring wiring = wire(store, repo, settings, env, seam, window);
            UUID projectId = wiring.projectId();
            Recorder roleClient = wiring.roleClient();
            StatedRules statedRules = wiring.statedRules();

            GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
            PlanningFlowServiceImpl planning = new PlanningFlowServiceImpl();
            BacklogServiceImpl backlog = new BacklogServiceImpl();
            BrdServiceImpl brdService = new BrdServiceImpl();

            // --- 2. both documents ingest ----------------------------------------------------
            SourceDocument business = ingest(store, projectId,
                BookshelfFixture.businessDocumentName(), BookshelfFixture.businessDocument());
            SourceDocument technical = ingest(store, projectId,
                BookshelfFixture.technicalDocumentName(), BookshelfFixture.technicalDocument());
            chain.require(L_INGEST,
                business != null && technical != null
                    && text(store, business).length() > 200 && text(store, technical).length() > 200,
                describeIngest(store, business, technical));

            // --- 3+4. the analyst reads both: rules out of one, requirements out of the other --
            final long requirementsStarted = System.currentTimeMillis();
            String flowId = intake.intake().flow().id().toString();
            expectEmpty(L_RULES, intake.addDocument(flowId, business.id().toString(),
                "what the app must do"));
            expectEmpty(L_RULES, intake.addDocument(flowId, technical.id().toString(),
                "how it must be built"));
            expectEmpty(L_RULES, intake.setDocumentTechnical(flowId, technical.id().toString(), true));
            expectEmpty(L_RULES, intake.start(flowId));

            GuidedFlow asked = awaitFlow(store, flowId, L_REQS,
                GuidedFlowState.AWAITING_ANSWERS, GuidedFlowState.REVIEW);
            var questions = store.listFlowQuestions(asked.id());
            if (!questions.isEmpty()) {
                // Whether the analyst asks anything is its own judgement. When it does, the
                // operator's answer must reach the drafting call — so answer the first and submit.
                System.out.println("[E2E] the analyst asked " + questions.size() + " question(s)");
                expectEmpty(L_RULES, intake.answer(flowId, questions.get(0).id().toString(),
                    "One person, one browser. Keep it as small as the document says."));
                expectEmpty(L_RULES, intake.submitAnswers(flowId));
            }
            awaitFlow(store, flowId, L_REQS, GuidedFlowState.REVIEW);
            var proposals = store.listFlowProposals(asked.id());
            expectEmpty(L_RULES, intake.setAllAccepted(flowId, true));
            expectEmpty(L_RULES, intake.apply(flowId));
            clock.stage("requirements (analyst wizard)", requirementsStarted);

            List<LearnedGuideline> ruleFiles = activeRules(store, projectId);
            chain.require(L_RULES, !statedRules.stated.isEmpty(),
                "the analyst made " + proposals.size() + " proposal(s) from the two documents and "
                    + "stated " + statedRules.stated.size() + " rule(s) out of the technical one "
                    + firstFew(statedRules.stated) + "; the project's active rules went from "
                    + wiring.rulesBefore() + " to " + ruleFiles.size()
                    + " (a rule it already had is matched, not duplicated)");

            Brd brd = store.getBrd(projectId);
            List<BrdRequirement> drafted = brd.requirements();
            long withChecks = drafted.stream()
                .filter(r -> r.criteria() != null && !r.criteria().isEmpty()).count();
            chain.require(L_REQS, !drafted.isEmpty() && withChecks == drafted.size(),
                drafted.size() + " requirement(s) drafted from the business document, "
                    + withChecks + " of them carrying acceptance criteria: "
                    + drafted.stream().map(r -> r.handle() + " '" + r.title() + "' ("
                        + (r.criteria() == null ? 0 : r.criteria().size()) + " check(s))").toList());

            // --- 5. the operator agrees ONE requirement --------------------------------------
            // A pinned requirement that is not among the drafted ones stops the walk here,
            // before anything is agreed (live run 75). See HarnessSnapshot.pin.
            HarnessSnapshot.Pin pin = HarnessSnapshot.pin(
                System.getProperty("swarmcoder.e2e.requirement"),
                drafted.stream().map(EndToEndLoopTest::asAgreed).toList());
            if (pin.failure() != null) {
                throw chain.fail(L_AGREED, pin.failure());
            }
            if (pin.note() != null) {
                System.out.println("[E2E] " + pin.note());
            }
            BrdRequirement chosen = pin.index() >= 0 ? drafted.get(pin.index()) : smallest(drafted);
            for (BrdRequirement other : drafted) {
                if (!other.id().equals(chosen.id())) {
                    System.out.println("[E2E] left in draft: " + other.handle() + " " + other.title());
                }
            }
            expectEmpty(L_AGREED, brdService.promoteRequirement(chosen.id().toString()));
            BrdRequirement agreed = requirement(store, projectId, chosen.id());
            List<UUID> agreedCriteria = agreed.criteria().stream()
                .map(AcceptanceCriterion::id).toList();
            long named = agreed.criteria().stream()
                .filter(c -> c.testClassOrFile() != null && !c.testClassOrFile().isBlank()).count();
            chain.require(L_AGREED,
                agreed.status() == RequirementStatus.ACTIVE
                    && agreed.criteria().stream().allMatch(c -> c.status() == CriterionStatus.ACCEPTED)
                    && named == agreed.criteria().size(),
                "agreed " + agreed.handle() + " '" + agreed.title() + "' [" + agreed.status()
                    + "] with " + agreed.criteria().size() + " check(s), " + named
                    + " naming a test: " + agreed.criteria().stream()
                        .map(c -> c.text() + " -> " + c.testClassOrFile() + " (" + c.status() + ")")
                        .toList());

            // --- 6. the planner slices it into stories ---------------------------------------
            final long planningStarted = System.currentTimeMillis();
            String planFlow = planning.planning().flow().id().toString();
            expectEmpty(L_STORIES, planning.start(planFlow));
            // The PLANNER asks questions as readily as the analyst does, and the first run of this
            // harness sat in AWAITING_ANSWERS for its whole ten-minute allowance because it waited
            // only for REVIEW. Nothing had gone wrong: the wizard was waiting for an operator, and
            // an unattended harness has to be that operator or it can never get past this stage.
            GuidedFlow planned = awaitFlow(store, planFlow, L_STORIES,
                GuidedFlowState.AWAITING_ANSWERS, GuidedFlowState.REVIEW);
            var planQuestions = store.listFlowQuestions(planned.id());
            if (!planQuestions.isEmpty()) {
                System.out.println("[E2E] the planner asked " + planQuestions.size()
                    + " question(s); answering the first and submitting");
                expectEmpty(L_STORIES, planning.answer(planFlow, planQuestions.get(0).id().toString(),
                    "Slice it as small as the agreed checks allow. One story is fine."));
                for (int i = 1; i < planQuestions.size(); i++) {
                    planning.skip(planFlow, planQuestions.get(i).id().toString());
                }
                expectEmpty(L_STORIES, planning.submitAnswers(planFlow));
                planned = awaitFlow(store, planFlow, L_STORIES, GuidedFlowState.REVIEW);
            }
            int planProposals = store.listFlowProposals(planned.id()).size();
            expectEmpty(L_STORIES, planning.setAllAccepted(planFlow, true));
            expectEmpty(L_STORIES, planning.apply(planFlow));
            clock.stage("story planning (planner wizard)", planningStarted);

            List<Story> stories = store.listStories(projectId);
            List<UUID> claimed = new ArrayList<>();
            stories.forEach(s -> claimed.addAll(s.criterionIds()));
            // Checks the planner claimed that the operator never agreed to. They belong to
            // requirements still in DRAFT, and a story that promises one can never legitimately
            // reach REVIEW — the requirement it answers for is not in scope.
            List<UUID> notAgreed = claimed.stream()
                .filter(id -> !agreedCriteria.contains(id)).distinct().toList();
            chain.require(L_STORIES,
                !stories.isEmpty() && claimed.containsAll(agreedCriteria) && notAgreed.isEmpty(),
                planProposals + " proposal(s) became " + stories.size() + " story/stories claiming "
                    + claimed.size() + " check(s); " + agreedCriteria.size()
                    + " requirement check(s) were agreed and " + notAgreed.size()
                    + " of the claimed ones belong to requirements still in DRAFT"
                    + (notAgreed.isEmpty() ? "" : " (" + draftOwners(store, projectId, notAgreed)
                        + ")") + ": " + stories.stream().map(s -> "'" + s.title() + "' ("
                        + s.criterionIds().size() + ")").toList());

            // --- the build: ONE story, so the loop stays under half an hour -------------------
            Story story = stories.get(0);
            if (stories.size() > 1) {
                System.out.println("[E2E] building only the first story; not built this run: "
                    + stories.stream().skip(1).map(Story::title).toList());
            }
            expectEmpty(L_TASKS, backlog.promoteStory(story.id().toString()));
            String started = backlog.startSession(story.id().toString());
            if (started.startsWith("error:")) {
                throw chain.fail(L_TASKS, "the story would not start: " + started);
            }
            UUID runId = UUID.fromString(started);
            Outcome[] ended = new Outcome[1];
            try {
                Outcome outcome = awaitRun(store, wiring.traceHub(), runId, settings.deadline(),
                    settings.runBudget().minutes(), ask);
                ended[0] = outcome;
                System.out.println("[E2E] run " + runId + " " + outcome.describe());
                saves.forEach((point, target) -> {
                    boolean reached = point == RestartPoint.BUILD ? seam.reached(runId)
                        : stageSeam.reached(runId, point.runState());
                    if (!reached) {
                        System.out.println("[E2E] no snapshot was saved at " + point.folder()
                            + ": the run never reached " + point.description() + " — "
                            + outcome.describe());
                    }
                });
                afterTheRun(store, repo, roleClient, runId, story, layout, acceptanceHome,
                    fixture.baseCommit(), outcome);
            } finally {
                // Written whatever the chain said: a run that broke a link still cost something.
                writeRunReport(store, repo, runId, fixture.baseCommit(), ended[0], null);
            }
        }
    }

    /**
     * Links 7 to 16 from a run that is over — the same for a full walk and for a walk resumed from
     * a point before the build, so the two can never come to measure the plan, the tests and the
     * workers differently.
     */
    private void afterTheRun(ArtifactStore store, Path repo, Recorder roleClient, UUID runId,
                             Story story, BuildLayout.Layout layout,
                             AcceptanceTestLocation.Location acceptanceHome, String baseCommit,
                             Outcome outcome) {
        // --- 7, 8, 9. the plan, its write sets, and where the tests landed -------------------
        Run run = store.root().runs.get(runId);
        if (run == null || run.taskGraphId() == null
                || store.root().taskGraphs.get(run.taskGraphId()) == null) {
            throw chain.fail(L_TASKS, "no plan was ever stored — " + outcome.describe());
        }
        for (Measured link : beforeTheWorkers(store, repo, run, story, layout, acceptanceHome,
                outcome)) {
            chain.require(link.name(), link.held(), link.observation());
        }

        // --- 10 to 16. the build -------------------------------------------------------------
        walkTheBuild(store, repo, roleClient, run, story, baseCommit, outcome);
    }

    /**
     * Links 10 to 16, from a run over — the same for a full walk and for a walk resumed from a
     * snapshot, so that the two can never come to measure the workers differently.
     *
     * <p>Link 10 is measured here, never restored from a snapshot, although it names the
     * acceptance stage: its evidence is the acceptance results each candidate's verification
     * carries, and candidates exist only after the workers have run — which, in a resumed walk,
     * is in this run.
     */
    /** Test methods the clone already held at the base commit (earlier stories'). */
    private int keptTests;

    private void walkTheBuild(ArtifactStore store, Path repo, Recorder roleClient, Run run,
                              Story story, String baseCommit, Outcome outcome) {
        String testsCommit = run.acceptanceTestsCommit();
        // The run's own test files only: an earlier story's kept test is on the base tree
        // already and is not "the story's acceptance test" (run 88).
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        AcceptanceFilesLink.Verdict files = acceptanceFilesOf(repo, run,
            AcceptanceTestLocation.resolve(layout).writeDir());
        List<String> inCommit = new ArrayList<>(files.authored());
        inCommit.addAll(files.strays());

        // --- 10. did that stage actually run anything? -----------------------------------
        // A candidate's acceptance stage holds the files its task claims and nothing else, so
        // what counts is the run's own tests among those executed - never "more than the base
        // commit holds" (run 89: 3 of the run's ran and passed, the base held 5, the link broke).
        List<CandidateSolution> candidates = archived(store);
        AcceptanceEvidence acceptance = acceptanceEvidence(candidates, outcome, files.authored());
        chain.require(L_TESTS_RUN, acceptance.executed() > 0,
            acceptance.describe() + " (counted: executed tests of the run's own "
                + files.authored().size() + " test file(s); a candidate's stage holds no earlier"
                + " story's test)");

        // --- 11. a worker changed a file -------------------------------------------------
        List<CandidateSolution> changing = candidates.stream()
            .filter(c -> c.diffUnified() != null && !c.diffUnified().isBlank()).toList();
        chain.require(L_CANDIDATE, !changing.isEmpty(),
            candidates.size() + " candidate(s) archived, " + changing.size()
                + " carrying a non-empty diff; states " + stateHistogram(candidates)
                + (changing.isEmpty() ? " — " + outcome.describe()
                    : "; the largest diff touches " + touchedBy(changing)));

        // --- 12. verification returned a real verdict ------------------------------------
        List<CandidateSolution> reported = candidates.stream()
            .filter(c -> c.verification() != null).toList();
        chain.require(L_VERIFIED, !reported.isEmpty(), reported.size() + " of "
            + candidates.size() + " candidate(s) carry a verification report: "
            + reported.stream().map(EndToEndLoopTest::describeReport).toList()
            + (reported.isEmpty()
                ? " — a candidate with no report was never compiled and never tested, and "
                    + "survives verification automatically" : ""));

        // --- 13. the judge was told ------------------------------------------------------
        // Measured per task: a task whose only candidate to pass verification has nothing to be
        // compared with is not judged (run 85 broke here on exactly that). See JudgeLinkCheck.
        List<CandidateSolution> judged = candidates.stream()
            .filter(c -> c.judge() != null).toList();
        String judgePrompt = roleClient.firstContaining("code-review judge");
        JudgeLinkCheck.Verdict judgeLink = JudgeLinkCheck.evaluate(candidates, judgePrompt);
        chain.require(L_JUDGE, judgeLink.ok(),
            judgeLink.detail() + "; scores " + judged.stream()
                .map(c -> c.judge().score() + " (" + oneLine(c.judge().rationale()) + ")").toList()
                + "; the judge's brief said \"" + verificationLineOf(judgePrompt) + "\"");

        // --- 14. a winner -----------------------------------------------------------------
        List<CandidateSolution> selected = candidates.stream()
            .filter(c -> c.state() == CandidateState.SELECTED).toList();
        chain.require(L_WINNER, !selected.isEmpty(),
            selected.size() + " candidate(s) SELECTED out of " + candidates.size()
                + "; states " + stateHistogram(candidates)
                + (selected.isEmpty() ? "; the reason on record: " + outcome.parkedBrief() : ""));

        // --- 15. and it is in the repository ---------------------------------------------
        Story delivered = store.getStory(story.id());
        String integrationCommit = delivered.integrationCommit();
        List<String> mergedFiles;
        try {
            mergedFiles = integrationCommit == null || integrationCommit.isBlank()
                ? List.of() : filesChanged(repo, baseCommit, integrationCommit);
        } catch (Exception e) {
            throw chain.fail(L_INTEGRATED, "the merged diff could not be read: " + e.getMessage());
        }
        chain.require(L_INTEGRATED,
            integrationCommit != null && !integrationCommit.isBlank()
                && mergedFiles.stream().anyMatch(f -> f.contains("src/main/")),
            "story '" + delivered.title() + "' is " + delivered.state() + ", delivered commit "
                + shortSha(delivered.deliveredCommit()) + ", integration commit "
                + shortSha(integrationCommit) + " touching " + mergedFiles.size()
                + " file(s): " + firstFew(mergedFiles)
                + " (run ended " + outcome.describe() + ")");

        // --- 16. and the test that passed would have FAILED without it -------------------
        // Harness run 30 walked every link above it and delivered nothing: the acceptance test
        // implemented the contract itself, so it was green with or without any candidate's
        // code. See DeliveredCodeIsWhatPassedCheck. One extra suite run — the delivered side
        // was already measured when the winner was verified.
        //
        // "The winner" is not one candidate, though: run 41 (2026-09-26) had four tasks, and
        // only the last one claimed the story's acceptance criteria — the other three were
        // enablers that were never handed the story's acceptance test at all. See
        // DeliveredSideSelection for why the delivered side must come from the SELECTED
        // candidate(s) of the task(s) that claim the story's criteria, not from "a winner"
        // found by scanning worker indices.
        TaskGraph graph = run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        DeliveredCodeIsWhatPassedCheck.Outcome proves = provesTheDeliveredCode(repo, baseCommit,
            delivered.deliveredCommit(), testsCommit, inCommit, graph, story.id(), selected,
            candidates);
        chain.require(L_PROVES, proves.ok(), proves.observed());
    }

    // --- links 7 to 9, measurable the moment the run is about to dispatch --------------------

    /** One link's verdict and what was measured, before it is put on the ledger. */
    record Measured(String name, boolean held, String observation) {
    }

    /**
     * Links 7, 8 and 9 — the plan, where it writes, and where its acceptance tests landed —
     * measured from the store and the repository.
     *
     * <p>A function rather than inline ledger calls because it is needed at two moments. A full
     * walk puts them on the ledger after the run, as it always has. A walk that saves a snapshot
     * ALSO measures them at the dispatch seam, before any worker exists, and records them in the
     * snapshot; everything they look at is already final by then (the plan is stored, the tests are
     * committed on the run's own ref). One implementation, so a resumed walk's restored links say
     * exactly what a full walk would have said.
     *
     * @param outcome how the run ended, or null at the seam where it has not
     */
    static List<Measured> beforeTheWorkers(ArtifactStore store, Path repo, Run run, Story story,
                                           BuildLayout.Layout layout,
                                           AcceptanceTestLocation.Location acceptanceHome,
                                           Outcome outcome) {
        List<Measured> links = new ArrayList<>();
        TaskGraph graph = run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            links.add(new Measured(L_TASKS, false, "no plan was ever stored"
                + (outcome == null ? "" : " — " + outcome.describe())));
            return links;
        }

        // --- 7. the plan --------------------------------------------------------------
        // Re-validated against the STORED graph, not the one the planner was shown — the class
        // of bug PlanLinksItsCriteriaTest exists for: a graph that passed TaskGraphValidator
        // when it was produced, then had its links silently dropped before it reached the
        // store. "Every task carries a check" is not the rule: an ENABLER task (shared code
        // later tasks build on) legitimately claims none, and is fine as long as something
        // depends on it. See PlanTaskLinkageCheck.
        StoryScope storedScope = StoryScope.resolve(store.getBrd(run.projectId()), story);
        PlanTaskLinkageCheck.Verdict linkage = PlanTaskLinkageCheck.evaluate(graph, storedScope,
            com.swarmcoder.verify.BrowserOnlyCode.survey(repo, layout));
        links.add(new Measured(L_TASKS, linkage.ok() && !graph.tasks().isEmpty(),
            linkage.detail() + ": " + graph.tasks().stream()
                .map(t -> "'" + t.title() + "' (" + t.criterionIds().size() + " check(s))")
                .toList()));

        // --- 8. write sets --------------------------------------------------------------
        // A write-set entry is accepted when it sits under a source root the build compiles,
        // OR when it is the build file (pom.xml, build.gradle[.kts]) of a module the build
        // already compiles — regardless of whether that same task (or any task) also writes
        // that module's sources, so a pre-flight enabler task that only declares a dependency
        // in a pom (harness run 10, 2026-09-03) is accepted on its own. See
        // WriteSetLinkageCheck for why that predicate is not simply called from sc-workflow.
        List<String> orphans = new ArrayList<>();
        for (Task task : graph.tasks()) {
            Set<String> writeSet = task.writeSet() == null ? Set.<String>of() : task.writeSet();
            for (String path : writeSet) {
                if (!WriteSetLinkageCheck.isAccepted(path, writeSet, layout)) {
                    orphans.add("'" + task.title() + "' -> " + path);
                }
            }
        }
        List<String> realRoots = layout.sourceRoots().stream()
            .filter(root -> Files.exists(repo.resolve(root))).toList();
        links.add(new Measured(L_WRITESETS, orphans.isEmpty(),
            "the build compiles from " + realRoots + " (build files of those modules are "
                + "allowed); the plan writes to "
                + graph.tasks().stream().map(Task::writeSet).toList()
                + (orphans.isEmpty() ? " — all inside a compiled root or a compiled module's "
                    + "build file" : " — OUTSIDE any compiled root or a compiled module's "
                    + "build file: " + orphans)));

        // --- 9. where the acceptance tests landed ---------------------------------------
        // On the run's own ref, not in the checkout: a run commits its tests on
        // swarm/tests/<runId>, cut from its pinned base, and each candidate is given the
        // files its task claims at verification. The delivery branch is untouched until the
        // story is accepted, so this looks at the tests commit — and says so if there is none.
        // "The run's" is measured against the run's pinned base commit (run 88): with earlier
        // stories' acceptance tests kept in the clone, the base commit holds test files of its
        // own, and those are neither this run's tests nor leftovers of it. A file the run wrote
        // outside its tests commit - new on disk, or a base file changed on disk - still
        // breaks the link. See AcceptanceFilesLink.
        String testsCommit = run.acceptanceTestsCommit();
        AcceptanceFilesLink.Verdict files = acceptanceFilesOf(repo, run, acceptanceHome.writeDir());
        List<String> authored = files.authored();
        List<String> strays = files.strays();
        List<String> leftInCheckout = files.leftInCheckout();
        links.add(new Measured(L_TESTS_WRITTEN, files.held(),
            "the build compiles acceptance tests in " + acceptanceHome.writeDir() + "; the run's "
                + "tests commit " + shortSha(testsCommit) + " adds or changes " + authored.size()
                + " file(s) there " + authored
                + (strays.isEmpty() ? " and none anywhere the build ignores"
                    : " and " + strays.size() + " where nothing compiles them: " + strays)
                + (files.kept().isEmpty() ? "" : "; " + files.kept().size() + " file(s) of "
                    + "earlier stories were already in the base commit " + shortSha(run.startPoint())
                    + " and are unchanged " + files.kept())
                + (leftInCheckout.isEmpty() ? "; the checkout itself carries none the base "
                    + "commit does not hold"
                    : "; but the checkout carries " + leftInCheckout + ", not as the base commit "
                        + "holds it, which would outlive the run")
                + ". Every task carries protected dir " + graph.tasks().stream()
                    .map(Task::acceptanceTestDir).distinct().toList()
                + "; per task: " + graph.tasks().stream()
                    .map(t -> "'" + t.title() + "' -> " + t.authoredTestPaths()).toList()
                + (authored.isEmpty() && outcome != null ? ". The run " + outcome.describe() : "")));
        return links;
    }

    /**
     * The run's acceptance-test files against its pinned base commit: what its tests commit adds
     * or changes, what the base already held, and what the checkout carries beyond the base.
     */
    static AcceptanceFilesLink.Verdict acceptanceFilesOf(Path repo, Run run, String writeDir) {
        String testsCommit = run.acceptanceTestsCommit();
        String base = run.startPoint();
        List<String> atBase = acceptanceFilesInCommit(repo, base);
        List<String> changedByCommit;
        List<String> changedOnDisk;
        List<String> onDisk;
        try {
            changedByCommit = testsCommit == null ? List.of()
                : filesChanged(repo, base, testsCommit);
            // One revision: the working tree against the base commit.
            changedOnDisk = BookshelfFixture.git(repo, "diff --name-only " + base).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
            onDisk = acceptanceFilesOutside(repo, "");
        } catch (Exception e) {
            // Unreadable is a finding, not a pass: every file on record counts as the run's.
            return AcceptanceFilesLink.evaluate(writeDir, List.of(),
                acceptanceFilesInCommit(repo, testsCommit), List.of(),
                List.of("(the checkout could not be compared with its base commit: "
                    + e.getMessage() + ")"), List.of());
        }
        return AcceptanceFilesLink.evaluate(writeDir, atBase,
            acceptanceFilesInCommit(repo, testsCommit), changedByCommit, onDisk, changedOnDisk);
    }

    /** The acceptance-test files a run's tests commit holds, repo-relative. */
    private static List<String> acceptanceFilesInCommit(Path repo, String testsCommit) {
        return testsCommit == null ? List.of()
            : filesInCommit(repo, testsCommit).stream()
                .filter(p -> p.contains("src/test/java/swarm/")).toList();
    }

    // --- saving at the seam, and resuming from what was saved --------------------------------

    /**
     * Saves a snapshot at the dispatch seam — on the workflow's own thread, with the run in
     * EXECUTING, persisted, and no worker started. See {@link DispatchSeam} for why that moment and
     * {@link HarnessSnapshot} for what is written.
     *
     * <p>Only a snapshot whose first nine links all held is saved. Links 1 to 6 are on the ledger
     * already; 7 to 9 are measured here by the same code the walk uses after the run. A snapshot of
     * a chain that was already broken would hand every later resume a front half that failed, with
     * its links printed as saved — so instead it says which link would not hold, and saves nothing.
     * The run carries on either way.
     *
     * <p>The three earlier points ({@link RestartPoint}, saved through {@link StageSeam} when the
     * run enters DESIGN, PLAN and TEST_AUTHORING) come through here too: they carry links 1 to 6,
     * which are on the ledger by then, and nothing is measured at them — the plan and the tests
     * either do not exist yet or are measured by the walk after the run, in a resumed walk as in a
     * full one.
     */
    private void saveSnapshot(RestartPoint point, Path target, ArtifactStore store, Path repo,
                              Run run, BuildLayout.Layout layout,
                              AcceptanceTestLocation.Location acceptanceHome,
                              SaveBasis basis, Environment env, Settings settings) {
        System.out.println("[E2E] run " + run.id() + " is at " + point.description()
            + " — saving the " + point.folder() + " snapshot to " + target);
        Story story = store.getStory(run.storyId());
        List<HarnessSnapshot.Link> links = new ArrayList<>(basis.restoredLinks());
        for (ChainLedger.Held held : chain.heldSoFar()) {
            links.add(new HarnessSnapshot.Link(held.name(), held.observation()));
        }
        if (point == RestartPoint.BUILD) {
            for (Measured measured : beforeTheWorkers(store, repo, run, story, layout,
                    acceptanceHome, null)) {
                if (!measured.held()) {
                    System.out.println("[E2E] !!! NO SNAPSHOT SAVED: at the moment of dispatch the "
                        + "link [" + measured.name() + "] would not hold — "
                        + measured.observation());
                    return;
                }
                links.add(new HarnessSnapshot.Link(measured.name(), measured.observation()));
            }
        }
        List<String> expected = CHAIN.subList(0, point.links());
        List<String> recorded = links.stream().map(HarnessSnapshot.Link::name).toList();
        if (!recorded.equals(expected)) {
            System.out.println("[E2E] !!! NO SNAPSHOT SAVED at " + point.folder() + ": the links "
                + "walked so far were " + recorded + ", not the first " + point.links()
                + " of the chain");
            return;
        }
        Path swarmcoder = SnapshotStaleness.swarmcoderRoot();
        String swarmcoderCommit;
        String originHead;
        try {
            swarmcoderCommit = BookshelfFixture.git(swarmcoder, "rev-parse HEAD").strip();
        } catch (Exception e) {
            swarmcoderCommit = null;
        }
        try {
            originHead = BookshelfFixture.git(Path.of(basis.fixtureOrigin()), "rev-parse HEAD").strip();
        } catch (Exception e) {
            originHead = null;
        }
        HarnessSnapshot.Manifest manifest = new HarnessSnapshot.Manifest(HarnessSnapshot.FORMAT,
            Instant.now().toString(), run.id(), run.projectId(), run.storyId(),
            swarmcoderCommit, SnapshotStaleness.uncommitted(swarmcoder),
            settings.baseUrl(), settings.model(), System.getProperty("swarmcoder.live.shape"),
            settings.workers(), basis.fixtureOrigin(), originHead, basis.fixtureBaseCommit(),
            run.acceptanceTestsCommit(), env.reference().root().toString(), links,
            HarnessSnapshot.registeredWorktrees(repo), point.folder(),
            basis.businessSha256(), basis.technicalSha256(), basis.resumedFrom());
        long started = System.currentTimeMillis();
        try {
            HarnessSnapshot.save(target, store, repo, manifest);
        } catch (Exception e) {
            System.out.println("[E2E] !!! NO SNAPSHOT SAVED at " + point.folder() + ": " + e);
            return;
        }
        System.out.println("[E2E] snapshot saved to " + target + " in "
            + (System.currentTimeMillis() - started) / 1000 + "s: run " + run.id() + ", links 1 to "
            + point.links() + " recorded, SwarmCoder " + shortSha(swarmcoderCommit)
            + (manifest.swarmcoderUncommitted().isEmpty() ? ""
                : " plus " + manifest.swarmcoderUncommitted().size() + " uncommitted file(s)")
            + ". Resume from it with -D" + HarnessSnapshot.RESUME_PROPERTY + "=" + target
            + ". The run carries on.");
    }

    /**
     * What a snapshot records that is not read off the run: where the fixture came from, the links
     * a resumed walk restored rather than walked (they come first, in chain order), the snapshot
     * the walk was resumed from (null for a full walk), and the input document hashes. A full walk
     * takes the hashes from the documents it reads; a resumed walk takes them from the manifest it
     * resumed from, because the rules, requirements and story it carries were made from THOSE
     * documents whatever the files say now.
     */
    private record SaveBasis(String fixtureOrigin, String fixtureBaseCommit,
                             List<HarnessSnapshot.Link> restoredLinks, String resumedFrom,
                             String businessSha256, String technicalSha256) {
    }

    /** The text of the business (or technical) document this run is given; null if unreadable. */
    private static String documentText(boolean business) {
        try {
            return business ? BookshelfFixture.businessDocument()
                : BookshelfFixture.technicalDocument();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A walk started from a snapshot: links 1 to 9 put on the ledger as restored, the run resumed
     * in EXECUTING through the product's own start-up path ({@link RunResumer}), and links 10 to 16
     * walked as a full walk walks them.
     */
    private void resumeWalk(Path from, Settings settings, Map<RestartPoint, Path> allSaves)
            throws Exception {
        HarnessSnapshot.Manifest manifest = HarnessSnapshot.readManifest(from);
        System.out.println("[E2E] RESUMING from " + manifest.provenance(from) + ": run "
            + manifest.runId() + " of project " + manifest.projectId() + ", saved with "
            + manifest.workers() + " worker(s) at shape " + manifest.shape());
        String stale = SnapshotStaleness.compare(SnapshotStaleness.swarmcoderRoot(),
            manifest.swarmcoderCommit(), manifest.swarmcoderUncommitted())
            .warning(manifest.swarmcoderCommit());
        if (stale != null) {
            System.out.println(stale);
        }
        warnIfDifferent("the model", manifest.model(), settings.model());
        warnIfDifferent("the model shape", manifest.shape(),
            System.getProperty("swarmcoder.live.shape"));
        RestartPoint point = manifest.restartPoint();
        Map<RestartPoint, Path> saves = HarnessSnapshot.savePlanAfter(allSaves, point);
        if (!allSaves.isEmpty()) {
            System.out.println("[E2E] resumed from " + point.folder() + ": "
                + (saves.isEmpty() ? "no later point is left to save"
                    : "will save " + saves.keySet().stream().map(RestartPoint::folder).toList()
                        + " as the run passes them")
                + "; " + point.folder() + " and earlier are never rewritten.");
            warnAboutExisting(saves);
        }
        if (manifest.workers() != settings.workers()
                && (point == RestartPoint.PLAN || point == RestartPoint.BUILD)) {
            System.out.println("[E2E] note: the plan in the snapshot was stamped for "
                + manifest.workers() + " worker(s) per task; this walk dispatches "
                + settings.workers() + ".");
        }
        HarnessSnapshot.documentWarnings(manifest, documentText(true), documentText(false))
            .forEach(System.out::println);
        List<String> expected = CHAIN.subList(0, point.links());
        List<String> recorded = manifest.links().stream().map(HarnessSnapshot.Link::name).toList();
        if (!recorded.equals(expected)) {
            throw new AssertionError("the snapshot records the links " + recorded + " but this "
                + "harness's chain begins " + expected + ". The chain has changed since it was "
                + "saved; save a new one.");
        }

        HarnessSnapshot.Restored restored = HarnessSnapshot.restore(from, work, WORKTREE_ROOT);
        restored.cleared().forEach(line ->
            System.out.println("[E2E] cleared a leftover checkout of this run: " + line));
        String provenance = manifest.provenance(from);
        for (HarnessSnapshot.Link link : manifest.links()) {
            chain.restored(link.name(), link.observation(), provenance);
        }
        Path repo = restored.repo();

        // The first link this walk measures itself: a failure before any is its fault.
        String firstWalked = CHAIN.get(point.links());
        SaveBasis basis = new SaveBasis(manifest.fixtureOrigin(), manifest.fixtureBaseCommit(),
            manifest.links(), provenance, manifest.businessDocSha256(),
            manifest.technicalDocSha256());
        BuildLayout.Layout savedLayout = saves.isEmpty() ? null : BuildLayout.read(repo, "maven");
        AcceptanceTestLocation.Location savedHome = savedLayout == null ? null
            : AcceptanceTestLocation.resolve(savedLayout);
        ArtifactStore[] opened = new ArtifactStore[1];
        Environment[] prepared = new Environment[1];
        Map<RunState, java.util.function.Consumer<Run>> atStages = new java.util.EnumMap<>(RunState.class);
        saves.forEach((later, target) -> {
            if (later != RestartPoint.BUILD) {
                atStages.put(later.runState(), entered -> saveSnapshot(later, target, opened[0],
                    repo, entered, savedLayout, savedHome, basis, prepared[0], settings));
            }
        });
        Path saveTo = saves.get(RestartPoint.BUILD);
        try (ArtifactStore store = new StageSeam.Store(restored.store(),
                 new StageSeam(atStages).observing(clock::seen));
             HarnessWindow window = HarnessWindow.fromProperties(System.out::println)) {
            opened[0] = store;
            HarnessSnapshot.repointProject(store, manifest.projectId(), repo);
            Environment env = prepare(repo, settings, firstWalked);
            prepared[0] = env;
            DispatchSeam seam = saveTo == null ? DispatchSeam.NONE
                : new DispatchSeam(atDispatch -> saveSnapshot(RestartPoint.BUILD, saveTo, store,
                    repo, atDispatch, savedLayout, savedHome, basis, env, settings));
            Wiring wiring = wire(store, repo, settings, env, seam, window);
            if (!manifest.projectId().equals(wiring.projectId())) {
                throw chain.fail(firstWalked, "the restored store opened project "
                    + wiring.projectId() + " for " + repo + ", not the snapshot's "
                    + manifest.projectId());
            }
            Run run = store.root().runs.get(manifest.runId());
            if (run == null || run.state() != point.runState()) {
                throw chain.fail(firstWalked, "the snapshot's run " + manifest.runId() + " is "
                    + (run == null ? "not in its store" : "in " + run.state())
                    + ", not " + point.runState() + " — nothing to resume at "
                    + point.description());
            }
            Story story = store.getStory(run.storyId());

            // A requested requirement must be the snapshot's own: its story, design, plan and
            // tests were made from the one it was saved with. Stop here, before anything runs,
            // rather than build that story under a request for another.
            List<HarnessSnapshot.AgreedRequirement> agreedInSnapshot = new ArrayList<>();
            if (story != null && story.requirementIds() != null) {
                for (UUID requirementId : story.requirementIds()) {
                    for (BrdRequirement candidate : store.getBrd(manifest.projectId()).requirements()) {
                        if (candidate.id().equals(requirementId)) {
                            agreedInSnapshot.add(asAgreed(candidate));
                        }
                    }
                }
            }
            String mismatch = HarnessSnapshot.requirementMismatch(
                System.getProperty("swarmcoder.e2e.requirement"), agreedInSnapshot, point, from);
            if (mismatch != null) {
                throw chain.fail(firstWalked, mismatch);
            }

            // Exactly what the app does at start-up (DependencyGraph.resumeUnfinishedRuns): every
            // unfinished run through its own project's engine, then free the stories nothing is
            // driving. Here the only engine is the one wired above, for the snapshot's project.
            List<RunResumer.Resumed> resumed = new RunResumer(store,
                projectId -> manifest.projectId().equals(projectId) ? wiring.engine() : null)
                .resumeAll();
            wiring.engine().reconcileStrandedStories();
            if (resumed.stream().noneMatch(r -> r.runId().equals(run.id()))) {
                throw chain.fail(firstWalked, "the product's resume path did not pick the run up: "
                    + "it resumed " + resumed);
            }
            System.out.println("[E2E] resumed run " + run.id() + " from " + run.state()
                + " against " + repo + " through the product's start-up resume path");

            Outcome[] ended = new Outcome[1];
            try {
                Outcome outcome = awaitRun(store, wiring.traceHub(), run.id(), settings.deadline(),
                    settings.runBudget().minutes(), ask);
                ended[0] = outcome;
                System.out.println("[E2E] run " + run.id() + " " + outcome.describe());
                if (point == RestartPoint.BUILD) {
                    Run after = store.root().runs.get(run.id());
                    walkTheBuild(store, repo, wiring.roleClient(), after, story,
                        manifest.fixtureBaseCommit(), outcome);
                } else {
                    // The plan, its write sets and the tests were not made yet (or not measured)
                    // at this point: they are made and measured now, exactly as a full walk does.
                    BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
                    afterTheRun(store, repo, wiring.roleClient(), run.id(), story, layout,
                        AcceptanceTestLocation.resolve(layout), manifest.fixtureBaseCommit(),
                        outcome);
                }
            } finally {
                writeRunReport(store, repo, run.id(), manifest.fixtureBaseCommit(), ended[0],
                    "resumed from the snapshot saved at " + point.description() + " (" + from
                        + "): everything before that point — the requirements and story-planning "
                        + "wizards and every run stage before " + point.runState() + ", with "
                        + (point == RestartPoint.BUILD ? "the test authoring already done, " : "")
                        + "their model calls, tokens and time — was restored, not run, and is "
                        + "missing from every table here");
            }
        }
    }

    private static void warnIfDifferent(String what, String then, String now) {
        if (!java.util.Objects.equals(then, now)) {
            System.out.println("[E2E] !!! " + what + " differs from the snapshot's: the front half "
                + "was produced with " + then + ", the workers now run on " + now + ".");
        }
    }

    // --- wiring, shared by both walks --------------------------------------------------------

    /** The reference documentation and the room the workers get — built the same for both walks. */
    private record Environment(HarnessReferenceRoot.Setup reference,
                               HarnessModelBudget.Discovery modelBudget, MaterialBudget workerRoom) {
    }

    /**
     * @param failAt the link a failure here breaks: link 1 in a full walk, whose observation
     *               reports all of this; the first link a resumed walk measures itself
     */
    private Environment prepare(Path repo, Settings settings, String failAt) throws Exception {
        // The reference documentation the owner's real project gives its workers (spec §13). Wave
        // 3 of run 4 (2026-09-03) lost every worker to NO_PROGRESS with the nudge log reading
        // "reference material: none" — nothing in this harness had ever built a Librarian, so
        // every lookup_api call answered ApiLookup.UNAVAILABLE and no knowledge brief was ever
        // attached to any task (GreenfieldWorkflow.attachKnowledgeBriefs short-circuits on a null
        // librarian). Required, not optional: a harness that silently ran without documentation
        // was testing a product the owner does not run.
        HarnessReferenceRoot.Setup reference;
        try {
            // Before anything is spent: a run whose workers have no container to run in is
            // refused here, not after the design and the plan have been paid for.
            HarnessSandbox.required();
            HarnessRoleServers.Server primer = settings.roleServers().fromConfig()
                .get(HarnessRoleServers.Role.LIBRARIAN);
            reference = HarnessReferenceRoot.build(repo, work.resolve("knowledge"),
                primer == null ? null : new Recorder(primer.baseUrl(), primer.apiKey(),
                    primer.model(), primer.quirks(), null));
        } catch (IllegalStateException e) {
            throw chain.fail(failAt, e.getMessage());
        }

        // The working-context budget a worker actually gets, discovered off the live endpoint the
        // same way DependencyGraph does at startup — not the ModelQuirks.DEFAULTS 32768-token
        // fallback this harness silently ran on before. See HarnessModelBudget's class doc: harness
        // run 9 compacted both workers on the persistence task at turn 15 and 17 because nothing
        // here had ever asked the server what room it actually has.
        HarnessModelBudget.Discovery modelBudget;
        try {
            modelBudget = HarnessModelBudget.discover(
                java.net.http.HttpClient.newHttpClient(), settings.baseUrl(), settings.model());
        } catch (IllegalStateException e) {
            throw chain.fail(failAt, e.getMessage());
        }
        // The reference material sized for that room, as ProjectContext sizes it from the resolved
        // worker profiles: the brief, lookup_api and the desk's answers grow with the room above
        // the 51,200 tokens every figure was measured in (MaterialBudget, 2026-09-25).
        MaterialBudget workerRoom = MaterialBudget.of(modelBudget.quirks());
        reference.librarian().sizedFor(workerRoom);
        System.out.println("[E2E] workers are handed " + workerRoom.describe());
        return new Environment(reference, modelBudget, workerRoom);
    }

    /** Everything {@link #wire} built that a walk goes on to use. */
    private record Wiring(Recorder roleClient, TraceHub traceHub, WorkflowEngine engine,
                          UUID projectId, StatedRules statedRules, int rulesBefore) {
    }

    /**
     * Every client, the swarm, the workflow engine and the Console context, built once for both
     * walks — so a resumed walk's workers run on exactly what a full walk's run on: the same
     * clients and quirks, the same librarian and expert, the same rules, budgets and policy. A
     * second copy of this method is the one that would drift.
     *
     * <p>The project is found or created by path ({@code ensureProject}); a resumed walk repoints
     * the restored project at the restored repository first, so it is found, not duplicated. The
     * fixture's rule FILES are imported once, and a restored project remembers that they were.
     */
    private Wiring wire(ArtifactStore store, Path repo, Settings settings, Environment env,
                        DispatchSeam seam, HarnessWindow window) throws IOException {
        String baseUrl = settings.baseUrl();
        String model = settings.model();
        HarnessReferenceRoot.Setup reference = env.reference();
        Recorder roleClient = new Recorder(baseUrl, model, env.modelBudget().quirks());
        // A role taken from the operator's config gets the client the product builds for that
        // config entry (its own endpoint, key, model and quirks); every other role is roleClient.
        // Only the local server is registered with ServerPlaces and the scheduler below, so a
        // call to any other server is neither counted against nor throttled by the Spark's places.
        HarnessRoleServers.Choice chosen = settings.roleServers();
        java.util.function.Function<HarnessRoleServers.Role, Recorder> clientOf = role -> {
            HarnessRoleServers.Server server = chosen.fromConfig().get(role);
            return server == null ? roleClient
                : new Recorder(server.baseUrl(), server.apiKey(), server.model(), server.quirks(),
                    roleClient);
        };
        Recorder analystClient = clientOf.apply(HarnessRoleServers.Role.ANALYST);
        Recorder plannerClient = clientOf.apply(HarnessRoleServers.Role.PLANNER);
        CloudGate cloudGate = new CloudGate(50_000_000, null);
        GitService git = new GitService(repo);
        // The fixture's checkout still carries rule FILES from before rules were store
        // objects; they are imported once, exactly as opening the project in the app does.
        Project project = store.ensureProject(BookshelfFixture.projectName(), repo.toString(), List.of());
        UUID projectId = project.id();
        ProjectRules rules = new ProjectRules(store, projectId);
        GuidelineFolderImport.importOnce(store, project, rules,
            repo.resolve(".swarmcoder").resolve("guidelines"));
        int rulesBefore = activeRules(store, projectId).size();
        StatedRules statedRules = new StatedRules(rules);

        // Shared with both AgentRuntimes below AND with ConsoleContext, exactly as
        // DependencyGraph wires the operator's own — one hub, so a live worker snapshot taken
        // here (awaitRun, on a timeout) is reading the same sessions swarm_status would. A
        // KoogAgentRuntime built with no TraceHub silently reports to TraceHub.NONE, which
        // begin() short-circuits into never registering an active session at all — so before
        // this, activeSessions() here would always have come back empty.
        //
        // Built by the app's own method since 2026-09-25, not as a bare new TraceHub(null): that
        // hub had no listeners, so no finished session ever reached this store — a worker's
        // transcript vanished from the Console and from MCP the moment the worker ended — and no
        // heartbeat was stamped, so swarm_status would have called a run with busy workers dead.
        // Oversized payloads go to blobs in this test's own temp directory, never ~/.swarmcoder.
        BlobStore blobs = new BlobStore(work.resolve("blobs"));
        TraceHub traceHub = DependencyGraph.traceHubOver(store, blobs, null);

        ModelQuirks workerQuirks = env.modelBudget().quirks();
        // The help desk's expert tier (ExpertDesk javadoc), wired here exactly as production
        // wires it — a fresh desk per worker, grounded in the harness's own reference root —
        // except the escalation is the SAME free local endpoint the workers run on (via the
        // harness's own Recorder client) rather than a paid utility/architect role. That makes
        // this a weaker expert than production's, never a stronger one, and it is what lets a
        // harness run exercise the full ask_expert/request_skeleton path with no paid call.
        ExpertEscalation expertEscalation = new ExpertEscalation(clientOf.apply(HarnessRoleServers.Role.EXPERT), cloudGate,
            reference.librarian().curator(), reference.librarian(), repo, List.of(),
            new KoogAgentRuntime(traceHub), ExpertEscalation.MAX_TURNS);
        // The desk's free first step, on the local server whatever the expert runs on.
        com.swarmcoder.knowledge.StoredAnswerJudge answerJudge =
            new com.swarmcoder.knowledge.LocalAnswerJudge(roleClient);
        Supplier<ExpertHelp> expertFactory = () -> new ExpertDesk(
            reference.librarian().curator(), repo, List.of(), expertEscalation)
            .judgedBy(answerJudge).sizedFor(env.workerRoom());
        List<String> frameworkPackages = ProjectContext.frameworkPackagesOf(reference.librarian());
        InferenceScheduler scheduler = InferenceScheduler.forWorkerModel(model, workerQuirks,
            baseUrl);
        SwarmEngineImpl swarm = new DispatchSeam.SwarmEngineAtTheSeam(seam, clientOf.apply(HarnessRoleServers.Role.JUDGE), store,
            scheduler, new KoogAgentRuntime(traceHub),
            new ModelProfileRegistry(List.of(new ModelProfile(model,
                new AgentRuntime.ModelEndpoint(baseUrl, "", model,
                    workerQuirks.servedContextTokens(), workerQuirks),
                ModelProfile.Kind.WORKER, workerQuirks, 0))),
            git, content -> null, cloudGate,
            () -> rules.renderActive(12_000),
            // Not reference.librarian()::lookupApi: a method reference binds lookup(String)
            // only, and in run 82 every tree query of every worker answered "not configured".
            // The same object the product builds, with the installed language server.
            WorkerLookups.withTheInstalledServer(reference.librarian(), repo),
            expertFactory, frameworkPackages);
        // Every command a worker writes runs in a container that sees its own checkout, the
        // reference folder read-only and the Maven repository read-only, and nothing else of
        // this machine; candidates are verified in one too. As ProjectContext wires it, with no
        // way to switch it off here: no Docker, no run.
        swarm.setReferenceRoots(reference.librarian().curator().referenceFolders());
        swarm.setSandbox(HarnessSandbox.required());
        swarm.setGuidelineChecks(rules::activeChecks);
        // The rules as objects, as ProjectContext wires them: without them the judge cannot tell a
        // hard rule from a preference, nor read why a rule exists.
        swarm.setActiveRules(rules::activeRules);
        // What a worker is sent, as ProjectContext wires it (section 65): the rules of the whole
        // project and the rules recorded for the part its task may write.
        rules.setParts(() -> com.swarmcoder.knowledge.ProjectParts.of(
            reference.librarian().curator(), repo));
        swarm.setWorkerRules(paths -> rules.briefingFor(paths, 12_000));
        // Nobody is watching a harness run, so a question about a rule — every candidate broke the
        // same hard rule, or two disputed it with evidence — is answered by rewording the rule to
        // allow what the evidence showed, and the run carries on instead of parking (harness runs
        // 53 and 55, 2026-10-01 parked hours in on exactly this). The decision stays on the run.
        // The same one switch covers every other check that is a model's opinion — a design, a
        // plan or an acceptance test read against the rules by the reviewer (run 60 parked on
        // that): its objection becomes a warning on the run, printed at the end. Checks that are
        // facts still stop the run. The workflow reads this policy from the engine.
        swarm.setRuleAmendments(RuleQuestions.Amendments.over(rules));
        swarm.setOpinionPolicy(com.swarmcoder.domain.OpinionPolicy.WARN_AND_CARRY_ON);

        // Two workers, so clustering, judging and selection are exercised rather than
        // short-circuited; the turn allowance stamped onto every planned task is the one
        // resolved above through TurnAllowance, the same production path, not a harness number.
        SwarmPolicy policy = new SwarmPolicy(settings.workers(), false, 0.2, 0.8,
            List.of("minimal-diff"));
        TokenBudget budget = new TokenBudget(32_000, 4_000, 400_000,
            settings.allowance().maxToolTurns());
        CloudRoles roles = new CloudRoles(
            new BudgetStampingArchitect(clientOf.apply(HarnessRoleServers.Role.ARCHITECT), cloudGate, policy, budget),
            new DesignReviewerClient(clientOf.apply(HarnessRoleServers.Role.DESIGN_REVIEWER), cloudGate),
            new TestAuthorClient(clientOf.apply(HarnessRoleServers.Role.TEST_AUTHOR), cloudGate));
        // The architect, the planner and the test author work as agents, as ProjectContext wires
        // them (owner decision, 2026-10-02): lookups over the repository and the reference
        // material, the librarian and the workers' help desk on demand, a check of the draft
        // before hand-in. Their sessions take a place on the one model server from the workers'
        // own scheduler. -Dswarmcoder.roles.oneReply=true runs them as they were.
        if (!Boolean.getBoolean("swarmcoder.roles.oneReply")) {
            LookupAgent roleAgent = new LookupAgent(reference.librarian().curator(),
                reference.librarian(), repo, cloudGate, new KoogAgentRuntime(traceHub), scheduler,
                endpoint -> HarnessRoleServers.poolOf(baseUrl, model, endpoint))
                .withExpert(() -> new ExpertDesk(reference.librarian().curator(), repo, List.of(),
                    expertEscalation).judgedBy(answerJudge));
            roles.architect().setLookupAgent(roleAgent);
            roles.testAuthor().setLookupAgent(roleAgent);
            System.out.println("[E2E] the architect, the planner and the test author work as "
                + "agents with these lookups: " + roleAgent.lookupNames());
        }

        WorkflowEngine engine = new WorkflowEngine(new KoogAgentRuntime(traceHub), swarm,
            roleClient, store, cloudGate, repo, roles, git, reference.librarian());
        engine.setEventLogger(message -> System.out.println("[E2E] " + message));
        engine.setProjectRules(() -> {
            String rendered = rules.renderActive(12_000);
            return rendered == null ? "" : rendered;
        });

        // The Console's view of this run, watch-only (HarnessWindow): its one project listed and
        // selected, approve and reject inert, and a browser's attempt to act refused.
        ConsoleContext.set(HarnessWindow.consoleOver(store, traceHub, blobs, projectId,
                (goal, kind) -> startRun(engine, git, projectId, goal, kind, null))
            .withStoryRuns((goal, kind, storyId) ->
                startRun(engine, git, projectId, goal, kind, storyId))
            .withGuidelineControl(statedRules)
            .withAnalyst((messages, override) ->
                analystClient.as("analyst").chatCompletionStream(messages, null, 0.4))
            .withPlanner((messages, override) ->
                plannerClient.as("planner").chatCompletionStream(messages, null, 0.4)));
        // A parked run's answer goes through the Console's own control service, and the run is
        // handed back to its engine as RunResumer hands back a parked run at start-up.
        if (ask != null) {
            ask.bind(new com.swarmcoder.console.ControlServiceImpl(), id -> {
                Run parked = store.root().runs.get(id);
                if (parked != null) {
                    engine.advanceAsync(parked);
                }
            }, repo, WORKTREE_ROOT);
        }
        // The browser and MCP views of the installed context, from here until the walk ends.
        window.beforeHolding(this::printReportOnce);
        window.open();
        return new Wiring(roleClient, traceHub, engine, projectId, statedRules, rulesBefore);
    }

    /**
     * Runs the run's own acceptance test(s) once more, on the tree as it was BEFORE the delivered
     * commit, and compares that with what those tests did on the delivered code.
     *
     * <p>The "before" tree is a detached worktree at the delivered commit's parent — the run's cut
     * commit when the delivered commit has no parent — with the run's final acceptance test files
     * placed into it from the run's own tests ref, exactly as verification places them for a
     * candidate. The delivered side is, in the ordinary case, not run again: the SELECTED
     * candidate(s) of the task(s) that claim the story's acceptance criteria already carry their
     * own acceptance results, measured against the delivered code — see
     * {@link DeliveredSideSelection}. A task that claims none of the story's criteria (an ENABLER)
     * is not one of those, and its winner's report — typically "0 executed" because it was never
     * handed the story's acceptance test — is not read as the delivered side. This is the fix for
     * harness run 41 (2026-09-26): a graph of four tasks where only the last one claimed the
     * story's acceptance criteria, and link 16 had been picking the first winner it found by
     * worker index regardless of which task it belonged to.
     *
     * <p>When no claiming task can be matched to a candidate that carries an acceptance report at
     * all, there is nothing to reuse, and reusing "no report" as an empty/red Side would say the
     * story was never delivered even when it plainly was measured, just not by anything this
     * method can see. In that case the acceptance suite is run a second time, directly on the
     * delivered commit itself, exactly as it is for the "before" side.
     *
     * @return what to record on the chain: whether the claim held, and what ran in both states
     */
    private static DeliveredCodeIsWhatPassedCheck.Outcome provesTheDeliveredCode(
            Path repo, String baseCommit, String deliveredCommit, String testsCommit,
            List<String> testFiles,
            TaskGraph graph, UUID storyId, List<CandidateSolution> selected,
            List<CandidateSolution> candidates) {
        Optional<VerifySpec> spec = VerifySpecLoader.load(repo);
        if (spec.isEmpty() || deliveredCommit == null || deliveredCommit.isBlank()
                || testsCommit == null || testFiles.isEmpty()) {
            return new DeliveredCodeIsWhatPassedCheck.Outcome(false, "nothing to measure: verification contract "
                + (spec.isEmpty() ? "missing" : "present") + ", delivered commit "
                + shortSha(deliveredCommit) + ", tests commit " + shortSha(testsCommit)
                + ", " + testFiles.size() + " acceptance file(s) on it");
        }
        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);
        List<CandidateSolution> looking = selected.isEmpty() ? candidates : selected;
        List<CandidateSolution> winners = DeliveredSideSelection.winnersOfClaimingTasks(claiming, looking);
        Optional<DeliveredCodeIsWhatPassedCheck.Side> combined = DeliveredSideSelection.combinedSide(winners);
        Path before = repo.getParent().resolve("before-delivered-" + shortSha(deliveredCommit));
        Path onDeliveredTree = combined.isPresent() ? null
            : repo.getParent().resolve("on-delivered-" + shortSha(deliveredCommit));
        try {
            // The "before" tree is the commit the run started from, not the delivered commit's
            // parent. Harness run 52, 2026-09-30: final integration merges one commit per task,
            // the delivered commit was the LAST merge (the browser UI), so its parent already held
            // the merged server task that makes the tests pass — "not red" on a story that was
            // red at the start and delivered for real.
            String parent;
            try {
                // "~1", never "^": BookshelfFixture.git runs through cmd.exe on Windows, and cmd
                // eats a trailing caret, so "rev-parse <sha>^" answered the delivered commit
                // ITSELF and the "before" tree was the delivered tree (found 2026-09-25).
                parent = baseCommit != null && !baseCommit.isBlank()
                    ? BookshelfFixture.git(repo, "rev-parse " + baseCommit).strip()
                    : BookshelfFixture.git(repo, "rev-parse " + deliveredCommit + "~1").strip();
            } catch (Exception noParent) {
                parent = BookshelfFixture.git(repo, "rev-parse " + deliveredCommit).strip();
            }
            BookshelfFixture.git(repo, "worktree add --detach \"" + before + "\" " + parent);
            placeAcceptanceTests(repo, before, testsCommit, testFiles);
            DeliveredCodeIsWhatPassedCheck.Side red = DeliveredCodeIsWhatPassedCheck.measure(
                new LocalProcessExecTarget(before), spec.get());

            DeliveredCodeIsWhatPassedCheck.Side onDelivered;
            String deliveredSideNote;
            if (combined.isPresent()) {
                onDelivered = combined.get();
                deliveredSideNote = claiming.size() + " task(s) claim the story's criteria, "
                    + winners.size() + " winner(s) carrying an acceptance report";
            } else {
                // Bug fix, harness run 41 (2026-09-26): nothing to reuse — measure the delivered
                // commit itself rather than report "not green" on the strength of a report that
                // was never about this question.
                BookshelfFixture.git(repo,
                    "worktree add --detach \"" + onDeliveredTree + "\" " + deliveredCommit);
                placeAcceptanceTests(repo, onDeliveredTree, testsCommit, testFiles);
                onDelivered = DeliveredCodeIsWhatPassedCheck.measure(
                    new LocalProcessExecTarget(onDeliveredTree), spec.get());
                deliveredSideNote = claiming.size() + " task(s) claim the story's criteria, none had "
                    + "a matched winner carrying an acceptance report — ran the suite directly on "
                    + "the delivered commit instead";
            }

            DeliveredCodeIsWhatPassedCheck.Outcome outcome =
                DeliveredCodeIsWhatPassedCheck.compare(red, onDelivered);
            return new DeliveredCodeIsWhatPassedCheck.Outcome(outcome.ok(), "the story's acceptance test(s) "
                + testFiles + " placed on " + shortSha(parent) + " (the tree before delivered "
                + "commit " + shortSha(deliveredCommit) + "); delivered side: " + deliveredSideNote
                + "; " + outcome.observed());
        } catch (Exception e) {
            return new DeliveredCodeIsWhatPassedCheck.Outcome(false, "could not measure whether the acceptance "
                + "test needed the delivered code: " + e);
        } finally {
            removeWorktreeQuietly(repo, before);
            if (onDeliveredTree != null) {
                removeWorktreeQuietly(repo, onDeliveredTree);
            }
        }
    }

    /** Writes the run's acceptance test file(s), as they sit in {@code testsCommit}, into a tree. */
    private static void placeAcceptanceTests(Path repo, Path tree, String testsCommit,
                                             List<String> testFiles) throws Exception {
        for (String path : testFiles) {
            Path target = tree.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, BookshelfFixture.git(repo, "show " + testsCommit + ":" + path));
        }
    }

    private static void removeWorktreeQuietly(Path repo, Path tree) {
        try {
            BookshelfFixture.git(repo, "worktree remove --force \"" + tree + "\"");
        } catch (Exception alreadyGone) {
            // a worktree that will not come off costs a directory, not a finding
        }
    }

    // --- refusing to spend money -------------------------------------------------------------

    /**
     * The endpoint every role in this test talks to, checked before a single call is made.
     *
     * <p>The operator's config points the analyst, architect, reviewer, test author, judge,
     * approver, librarian and utility roles at a billed cloud account. This test never reads that
     * file — but a flag is easy to mistype, and a mistyped flag that reached a cloud endpoint would
     * spend real money on a harness meant to be run after every merge. So the address itself is
     * checked: plain HTTP (no TLS, so not a public API) to a loopback or private-range host.
     */
    static String refuseAnythingButAFreeLocalEndpoint() {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AssertionError("swarmcoder.live.baseUrl was not set — @RunsWhen should have "
                + "skipped this test rather than starting it");
        }
        URI uri = URI.create(baseUrl);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean localOnly = "localhost".equals(host) || host.startsWith("127.")
            || host.startsWith("10.") || host.startsWith("192.168.")
            || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
        if (!"http".equals(uri.getScheme()) || !localOnly) {
            throw new AssertionError("this harness runs only against a free local model server. "
                + baseUrl + " is not plain HTTP on a private address, so it may be a billed "
                + "endpoint. Refusing to start rather than risk spending money.");
        }
        return baseUrl;
    }

    // --- what the run cost ---------------------------------------------------------------------

    /**
     * Writes the run report (see {@link HarnessRunReport}) to a file and prints it as the
     * {@code [E2E] cost:} block; then, with {@code -Dswarmcoder.e2e.keepRepoAt=<dir>}, copies the
     * fixture clone there so the delivered code outlives the JUnit temp directory.
     *
     * <p>The file is {@code -Dswarmcoder.e2e.reportAt=<file>}; without it, {@code run-report.md}
     * next to the coordinator's log ({@code -Dswarmcoder.e2e.askLog}), else in this test's temp
     * directory. Nothing here may fail the walk or hide the failure it is reporting on.
     *
     * @param outcome  how the run ended, or null when the walk failed before it knew
     * @param restored on a resumed walk, what was restored and so not measured; null otherwise
     */
    private void writeRunReport(ArtifactStore store, Path repo, UUID runId, String baseCommit,
                                Outcome outcome, String restored) {
        String delivered = null;
        try {
            Run run = store.root().runs.get(runId);
            TaskGraph graph = run == null || run.taskGraphId() == null ? null
                : store.root().taskGraphs.get(run.taskGraphId());
            List<Task> tasks = graph == null ? List.of() : graph.tasks();
            List<List<UUID>> waves = graph == null ? List.of()
                : SwarmEngineImpl.topologicalWaves(graph).stream()
                    .map(wave -> wave.stream().map(Task::id).toList()).toList();
            java.util.Set<UUID> taskIds = new java.util.HashSet<>();
            tasks.forEach(task -> taskIds.add(task.id()));
            List<CandidateSolution> candidates = archived(store).stream()
                .filter(c -> taskIds.contains(c.taskId())).toList();
            List<AgentSessionRecord> sessions = new ArrayList<>();
            for (Lazy<Object> lazy : store.root().agentSessions().values()) {
                if (Lazy.get(lazy) instanceof AgentSessionRecord session
                        && runId.equals(session.runId())) {
                    sessions.add(session);
                }
            }
            List<Decision> decisions = store.root().decisions.values().stream()
                .filter(d -> d != null && runId.equals(d.runId()))
                .sorted(Comparator.comparing(Decision::createdAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))).toList();

            // What was delivered, against the commit the run was cut from.
            Story story = run == null || run.storyId() == null ? null : store.getStory(run.storyId());
            String note;
            if (story != null && story.integrationCommit() != null
                    && !story.integrationCommit().isBlank()) {
                delivered = story.integrationCommit();
                note = "integration commit " + shortSha(delivered) + " against the run base "
                    + shortSha(baseCommit);
            } else if (run != null && run.progressCommit() != null
                    && !run.progressCommit().isBlank()) {
                delivered = run.progressCommit();
                note = "NOTHING WAS DELIVERED; this is the run's progress commit "
                    + shortSha(delivered) + " (the winners merged so far) against the run base "
                    + shortSha(baseCommit);
            } else {
                note = "nothing was delivered and no winner was merged";
            }
            if (run != null && run.nothingToBuild()) {
                note = "NOTHING WAS BUILT: every check of the story was already satisfied by the "
                    + "code the run started from; the unchanged tree was verified once with the "
                    + "acceptance tests " + run.alreadySatisfiedTests() + " and the story "
                    + "delivered as it stands - " + note;
            }
            List<HarnessRunReport.FileStat> stats = null;
            if (delivered != null && baseCommit != null) {
                try {
                    stats = HarnessRunReport.statsOfNumstat(BookshelfFixture.git(repo,
                        "diff --numstat " + baseCommit + " " + delivered));
                } catch (Exception e) {
                    note += "; the diff could not be read: " + oneLine(e.getMessage());
                }
            }
            String text = HarnessRunReport.render(new HarnessRunReport.Input(
                com.swarmcoder.inference.RunMeter.calls(), com.swarmcoder.inference.RunMeter.spans(),
                tasks, waves, candidates, sessions, decisions,
                run == null ? List.of() : run.carriedWarnings(), clock.seen(), clock.stages(),
                clock.startMillis, System.currentTimeMillis(), stats, note, restored,
                outcome == null ? "the walk failed before the run's end was observed"
                    : outcome.describe(),
                ask == null ? null : ask.report(),
                run == null ? com.swarmcoder.runtime.ExpertAnswerLog.allRuns()
                    : com.swarmcoder.runtime.ExpertAnswerLog.forRun(run.id()).entries(),
                servers));

            String reportAt = System.getProperty("swarmcoder.e2e.reportAt");
            String askLog = System.getProperty(CoordinatorAsk.LOG_PROPERTY);
            Path file = reportAt != null && !reportAt.isBlank() ? Path.of(reportAt)
                : askLog != null && !askLog.isBlank()
                    ? Path.of(askLog).toAbsolutePath().resolveSibling("run-report.md")
                    : work.resolve("run-report.md");
            try {
                if (file.toAbsolutePath().getParent() != null) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                }
                Files.writeString(file, text, StandardCharsets.UTF_8);
                System.out.println("[E2E] run report written to " + file.toAbsolutePath());
            } catch (Exception e) {
                System.out.println("[E2E] the run report could not be written to " + file + ": " + e);
            }
            System.out.println("[E2E] cost:");
            text.lines().forEach(line -> System.out.println("[E2E]   " + line));
        } catch (Throwable e) {
            System.out.println("[E2E] the run report could not be made: " + e);
            e.printStackTrace(System.out);
        }
        keepRepo(repo, delivered);
    }

    /**
     * {@code -Dswarmcoder.e2e.keepRepoAt=<dir>}: the fixture clone, {@code .git} included, copied
     * to {@code <dir>} with the delivered commit checked out there (or named, when it could not
     * be). The directory must be empty or absent: nothing of the operator's is overwritten.
     */
    private static void keepRepo(Path repo, String deliveredCommit) {
        String keepAt = System.getProperty("swarmcoder.e2e.keepRepoAt");
        if (keepAt == null || keepAt.isBlank()) {
            return;
        }
        Path target = Path.of(keepAt).toAbsolutePath();
        try {
            if (Files.exists(target)) {
                try (java.util.stream.Stream<Path> inside = Files.list(target)) {
                    if (inside.findAny().isPresent()) {
                        System.out.println("[E2E] keepRepoAt: " + target + " is not empty, so the "
                            + "repository was NOT copied there");
                        return;
                    }
                }
            }
            Files.createDirectories(target);
            Files.walkFileTree(repo, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws java.io.IOException {
                    Files.createDirectories(target.resolve(repo.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws java.io.IOException {
                    Files.copy(file, target.resolve(repo.relativize(file).toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            String at = "its working tree is as the run left it";
            if (deliveredCommit != null && !deliveredCommit.isBlank()) {
                try {
                    // The copy still lists the run's worktrees, which live elsewhere and are gone.
                    BookshelfFixture.git(target, "worktree prune");
                    BookshelfFixture.git(target, "checkout -q -f --detach " + deliveredCommit);
                    at = "the delivered code is checked out (detached at " + deliveredCommit + ")";
                } catch (Exception e) {
                    at = "the delivered code is commit " + deliveredCommit + " — it could not be "
                        + "checked out (" + oneLine(e.getMessage()) + "), so check it out by hand";
                }
            } else {
                at = "nothing was delivered; " + at;
            }
            System.out.println("[E2E] repository kept at " + target + ": " + at);
        } catch (Exception e) {
            System.out.println("[E2E] keepRepoAt: the repository could not be copied to " + target
                + ": " + e);
        }
    }

    // --- the run ------------------------------------------------------------------------------

    /** How the run ended, in the only three ways it can: finished, parked, or ran out of time. */
    record Outcome(RunState last, String parkedBrief, boolean timedOut, String liveWorkersNote) {

        String describe() {
            if (parkedBrief != null) {
                return "PARKED in " + last + " waiting for an operator: " + oneLine(parkedBrief);
            }
            if (timedOut) {
                return liveWorkersNote != null ? liveWorkersNote : "RAN OUT OF TIME in " + last
                    + " with no worker still running";
            }
            return "reached " + last;
        }
    }

    /**
     * Waits for the run, and never longer than the harness's whole budget.
     *
     * <p>A parked run is waiting for an operator who is never coming; it keeps its stage's state
     * while it waits, so without noticing the park a run costs the entire budget and then reports
     * only its state. The brief the product wrote is the finding, so it is captured and returned
     * rather than thrown — every later link can then say "…and here is why it stopped".
     *
     * <p>On a timeout, this reads the live workers straight off {@code traceHub} — the same source
     * {@code SwarmMcpTools.swarmStatus} reads through {@code ObserverService} — so a run that is
     * still visibly being worked on says so honestly instead of reporting emptily. Before this, the
     * acceptance-stage link (L_TESTS_RUN) said "no candidate ever reached verification" on a
     * timeout with two workers still writing — true of the STORE, which nothing had been archived
     * to yet, and false of what was actually happening.
     */
    static Outcome awaitRun(ArtifactStore store, TraceHub traceHub, UUID runId, long deadline,
                            long budgetMinutes) {
        return awaitRun(store, traceHub, runId, deadline, budgetMinutes, null);
    }

    /**
     * {@link #awaitRun} with an optional coordinator. With one, a parked run (marked parked AND
     * holding an unanswered decision) is put to the coordinator instead of ending the wait: an
     * answer is applied and the walk goes on; no answer, a bad one or {@code stop} ends it as a
     * park always did, with the reason added. The time spent waiting is added to the deadline, and
     * a pending decision on a run that is NOT parked (one task blocked, the rest still working) no
     * longer ends the wait, because the run may yet finish or park properly.
     */
    static Outcome awaitRun(ArtifactStore store, TraceHub traceHub, UUID runId, long deadline,
                            long budgetMinutes, CoordinatorAsk ask) {
        RunState last = null;
        java.util.Set<UUID> ruleDecisionsShown = new java.util.HashSet<>();
        while (System.currentTimeMillis() < deadline + (ask == null ? 0 : ask.waitedMillis())) {
            printRuleDecisions(store, runId, ruleDecisionsShown);
            Run run = store.root().runs.get(runId);
            if (run != null) {
                if (run.state() != last) {
                    System.out.println("[E2E] run state -> " + run.state());
                    last = run.state();
                }
                if (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED) {
                    printCarriedWarnings(store, runId);
                    return new Outcome(run.state(), null, false, null);
                }
            }
            if (ask != null) {
                CoordinatorAsk.Park park = CoordinatorAsk.findPark(store, runId);
                if (park != null) {
                    CoordinatorAsk.Handled handled = ask.handle(store, runId, park);
                    if (!handled.carryOn()) {
                        printCarriedWarnings(store, runId);
                        return new Outcome(last, park.brief() + " [asked the coordinator: "
                            + handled.note() + "]", false, null);
                    }
                    last = null; // the resumed stage announces itself again
                    continue;
                }
                sleep(1000);
                continue;
            }
            String parked = pendingDecision(store, runId);
            if (parked != null) {
                printCarriedWarnings(store, runId);
                return new Outcome(last, parked, false, null);
            }
            sleep(1000);
        }
        printCarriedWarnings(store, runId);
        return new Outcome(last, pendingDecision(store, runId), true,
            liveWorkersNote(traceHub, runId, budgetMinutes));
    }

    /**
     * What to say instead of silence when the deadline fires with workers still on the clock:
     * "the harness stopped waiting after N minutes while W worker(s) were still running (turns so
     * far: …)". Null when nothing is live any more — the deadline firing with nobody running is a
     * different, and separately honest, finding ("RAN OUT OF TIME … with no worker still running").
     */
    private static String liveWorkersNote(TraceHub traceHub, UUID runId, long budgetMinutes) {
        List<AgentSessionRecord> stillRunning = traceHub.activeSessions().stream()
            .filter(session -> runId.equals(session.runId()))
            .toList();
        if (stillRunning.isEmpty()) {
            return null;
        }
        List<String> turns = stillRunning.stream()
            .map(session -> "worker " + session.workerIndex() + ": turn " + session.turns())
            .toList();
        return "the harness stopped waiting after " + budgetMinutes + " minutes while "
            + stillRunning.size() + " worker(s) were still running (turns so far: " + turns + ")";
    }

    /**
     * Every question about a rule the run decided without a person, printed once as it appears —
     * the unattended policy changes a project rule, and that must be visible in the run's output,
     * not only in the store.
     */
    private static void printRuleDecisions(ArtifactStore store, UUID runId, java.util.Set<UUID> shown) {
        for (Decision decision : store.root().decisions.values()) {
            if (runId.equals(decision.runId())
                    && decision.kind() == com.swarmcoder.domain.DecisionKind.GUIDELINE_REVIEW
                    && decision.state() == DecisionState.RESOLVED && shown.add(decision.id())) {
                System.out.println("[E2E] a question about a rule, decided without a person: "
                    + decision.humanResponse());
                System.out.println("[E2E]   " + decision.briefMarkdown().replace("\n", "\n[E2E]   "));
            }
        }
    }

    /**
     * Every objection from a check that is a model's opinion which this run carried past instead
     * of parking on, printed where the run stops: the check, the stage and the objection. Nothing
     * the unattended policy let through is hidden.
     */
    private static void printCarriedWarnings(ArtifactStore store, UUID runId) {
        Run run = store.root().runs.get(runId);
        List<com.swarmcoder.domain.CarriedWarning> carried =
            run == null ? List.of() : run.carriedWarnings();
        System.out.println("[E2E] warnings carried past instead of parking the run: "
            + (carried.isEmpty() ? "none" : carried.size()));
        for (com.swarmcoder.domain.CarriedWarning warning : carried) {
            System.out.println("[E2E]   " + warning.oneLine());
        }
    }

    private static String pendingDecision(ArtifactStore store, UUID runId) {
        for (Decision decision : store.root().decisions.values()) {
            if (runId.equals(decision.runId()) && decision.state() == DecisionState.PENDING) {
                return decision.briefMarkdown();
            }
        }
        return null;
    }

    // --- the acceptance stage's own numbers ---------------------------------------------------

    /**
     * What the acceptance stage did, which is the one thing counts alone cannot say.
     *
     * <p>Two sources, because the stage runs twice and either can be the last word. Every
     * candidate's verification report holds one run of it; the run's park brief holds the RED CHECK,
     * which runs the same stage on the pre-change tree before any worker starts and stops the run
     * when it executed nothing. When no candidate ever got that far, the park brief is all there is
     * — and it is exactly the message the operator is looking at today.
     */
    private record AcceptanceEvidence(int executed, String describe) {}

    private static AcceptanceEvidence acceptanceEvidence(List<CandidateSolution> candidates,
                                                         Outcome outcome,
                                                         List<String> authored) {
        int best = 0;
        List<String> perCandidate = new ArrayList<>();
        for (CandidateSolution candidate : candidates) {
            VerificationReport report = candidate.verification();
            TestResults acceptance = report == null ? null : report.acceptance();
            if (acceptance == null) {
                perCandidate.add("worker " + candidate.workerIndex() + ": no acceptance results");
                continue;
            }
            best = Math.max(best, AcceptanceFilesLink.ownTestsExecuted(acceptance, authored));
            perCandidate.add("worker " + candidate.workerIndex() + ": stage "
                + acceptance.stageOutcome() + ", " + acceptance.executed() + " executed ("
                + acceptance.passed() + " passed / " + acceptance.failed() + " failed / "
                + acceptance.skipped() + " skipped)");
        }
        // A deadline that fired while workers were still visibly running is not the same finding
        // as a run that genuinely produced nothing — harness run 12 said "no candidate ever
        // reached verification" with two workers alive and writing at minute 17, which reads as a
        // dead swarm when it was a harness that stopped waiting too soon.
        String note;
        if (!perCandidate.isEmpty()) {
            note = String.join("; ", perCandidate);
        } else if (outcome.timedOut() && outcome.liveWorkersNote() != null) {
            note = outcome.liveWorkersNote();
        } else {
            note = "no candidate ever reached verification";
        }
        String park = outcome.parkedBrief() == null ? ""
            : " | the run stopped here: " + oneLine(outcome.parkedBrief());
        return new AcceptanceEvidence(best, note + park);
    }

    // --- small readers -------------------------------------------------------------------------

    private static SourceDocument ingest(ArtifactStore store, UUID projectId, String relative,
                                         String text) throws IOException {
        DocumentIngest.Result result = DocumentIngest.ingest(store, null, projectId, relative,
            "text/markdown", text.getBytes(StandardCharsets.UTF_8));
        if (result.failed()) {
            return null;
        }
        return result.document();
    }

    private static String text(ArtifactStore store, SourceDocument document) {
        String extracted = document == null ? null : document.extractedText();
        return extracted == null ? "" : extracted;
    }

    private static String describeIngest(ArtifactStore store, SourceDocument business,
                                         SourceDocument technical) {
        return "business document " + (business == null ? "FAILED TO INGEST"
                : "'" + business.filename() + "' " + text(store, business).length() + " chars")
            + "; technical document " + (technical == null ? "FAILED TO INGEST"
                : "'" + technical.filename() + "' " + text(store, technical).length() + " chars");
    }

    private static List<LearnedGuideline> activeRules(ArtifactStore store, UUID projectId) {
        List<LearnedGuideline> active = new ArrayList<>();
        for (LearnedGuideline guideline : store.root().guidelines.values()) {
            if (guideline.status() == GuidelineStatus.ACTIVE) {
                active.add(guideline);
            }
        }
        return active;
    }

    /** A requirement as the pin and the snapshot check read it: title, text and every check. */
    private static HarnessSnapshot.AgreedRequirement asAgreed(BrdRequirement requirement) {
        StringBuilder text = new StringBuilder(requirement.text() == null ? "" : requirement.text());
        if (requirement.criteria() != null) {
            requirement.criteria().forEach(c -> text.append(' ').append(c.text()));
        }
        return new HarnessSnapshot.AgreedRequirement(requirement.handle(), requirement.title(),
            text.toString());
    }

    /**
     * The requirement to build: the one with the fewest checks, ties broken by the shortest text.
     *
     * <p>Deterministic, and it is the smallest real piece of the document rather than a piece
     * invented for the test. {@code -Dswarmcoder.e2e.requirement=<substring>} overrides it when
     * somebody wants to reproduce a failure on a particular one; that is decided before this is
     * called, by {@link HarnessSnapshot#pin}.
     */
    private static BrdRequirement smallest(List<BrdRequirement> requirements) {
        return requirements.stream()
            .min(Comparator.<BrdRequirement>comparingInt(
                    r -> r.criteria() == null ? 0 : r.criteria().size())
                .thenComparingInt(r -> r.text() == null ? 0 : r.text().length()))
            .orElseThrow();
    }

    /**
     * Which requirements the unagreed checks belong to, and what state those are in.
     *
     * <p>Named because the number alone reads as a miscount. It is not: the planner is shown the
     * WHOLE requirements document with a sentence saying that only ACTIVE requirements are agreed
     * scope, and the code that turns its {@code R3:C2} references into criteria never checks a
     * requirement's status. The gate is a line of prompt, so whether a plan stays inside what the
     * operator agreed is the model's choice.
     */
    private static String draftOwners(ArtifactStore store, UUID projectId, List<UUID> criteria) {
        List<String> owners = new ArrayList<>();
        for (BrdRequirement requirement : store.getBrd(projectId).requirements()) {
            if (requirement.criteria() == null) {
                continue;
            }
            boolean owns = requirement.criteria().stream()
                .anyMatch(c -> criteria.contains(c.id()));
            if (owns) {
                owners.add(requirement.handle() + " [" + requirement.status() + "]");
            }
        }
        return String.join(", ", owners);
    }

    private static BrdRequirement requirement(ArtifactStore store, UUID projectId, UUID id) {
        for (BrdRequirement candidate : store.getBrd(projectId).requirements()) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        throw new AssertionError("requirement " + id + " is gone from the requirements document");
    }

    @SuppressWarnings("unchecked")
    static List<CandidateSolution> archived(ArtifactStore store) {
        List<CandidateSolution> all = new ArrayList<>();
        for (Lazy<Object> lazy : store.root().candidateArchives.values()) {
            Object value = Lazy.get(lazy);
            if (value instanceof CandidateSolution candidate) {
                all.add(candidate);
            }
        }
        all.sort(Comparator.comparingInt(CandidateSolution::workerIndex));
        return all;
    }

    static String stateHistogram(List<CandidateSolution> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CandidateSolution candidate : candidates) {
            String key = String.valueOf(candidate.state())
                + (candidate.killReason() == null ? "" : "/" + candidate.killReason());
            counts.merge(key, 1, Integer::sum);
        }
        return counts.toString();
    }

    private static String describeReport(CandidateSolution candidate) {
        VerificationReport report = candidate.verification();
        StringBuilder sb = new StringBuilder("worker ").append(candidate.workerIndex())
            .append(" compiles=").append(report.compiles());
        if (report.acceptance() != null) {
            sb.append(" acceptance[").append(report.acceptance().stageOutcome()).append(' ')
              .append(report.acceptance().executed()).append(" executed]");
        }
        if (report.existing() != null) {
            sb.append(" existing[").append(report.existing().stageOutcome()).append(' ')
              .append(report.existing().executed()).append(" executed, ")
              .append(report.existing().failed()).append(" failed]");
        }
        return sb.toString();
    }

    private static String touchedBy(List<CandidateSolution> candidates) {
        CandidateSolution biggest = candidates.stream()
            .max(Comparator.comparingInt(c -> c.diffUnified().length())).orElseThrow();
        List<String> files = new ArrayList<>();
        for (String line : biggest.diffUnified().split("\n")) {
            if (line.startsWith("+++ b/")) {
                files.add(line.substring(6).strip());
            }
        }
        return files.isEmpty() ? "(no +++ headers in the diff)" : firstFew(files);
    }

    /** The judge's own words about verification, pulled out of the brief it was actually sent. */
    private static String verificationLineOf(String prompt) {
        if (prompt == null) {
            return "(the judge was never called)";
        }
        int at = prompt.indexOf("Verification:");
        if (at < 0) {
            return "(no verification line at all — the judge was told nothing about whether this "
                + "code builds)";
        }
        int end = prompt.indexOf('\n', at);
        return oneLine(prompt.substring(at, end < 0 ? Math.min(prompt.length(), at + 300) : end));
    }

    // --- the repository -------------------------------------------------------------------------

    /** Every file a commit holds, repo-relative — what a run's tests ref carries. */
    static List<String> filesInCommit(Path repo, String commit) {
        try {
            return BookshelfFixture.git(repo, "ls-tree -r --name-only " + commit).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Acceptance tests anywhere except the one directory the build compiles them in.
     *
     * <p>This is the orphan-path defect, checked directly: the demo project's root pom is an
     * aggregator with no sources, so a test written at {@code src/test/java/swarm/accept} there is
     * compiled by nothing and run by nothing, and the stage that "ran" it reports zero tests while
     * exiting 0.
     */
    private static List<String> acceptanceFilesOutside(Path repo, String writeDir)
            throws IOException {
        return walkFiles(repo, repo).stream()
            .filter(p -> p.contains("src/test/java/swarm/"))
            .filter(p -> !p.startsWith(normalise(writeDir) + "/"))
            .toList();
    }

    /**
     * Every regular file under {@code from}, repo-relative — skipping {@code .git} and surviving a
     * tree that is being written while it is read.
     *
     * <p>Both halves are load-bearing, and both were learned on the first live run. A run that
     * PARKS leaves the workflow's own threads alive — the engine has no shutdown — so the swarm
     * carries on making branches and worktrees in the very repository this harness is inspecting.
     * {@code Files.walk} descended into {@code .git/worktrees}, that directory vanished between
     * the listing and the read, and the harness died with a {@code NoSuchFileException} instead of
     * reporting the park. The finding was lost to an accident of timing, and nothing under
     * {@code .git} was ever wanted here in the first place.
     */
    private static List<String> walkFiles(Path repo, Path from) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.getFileName() != null && dir.getFileName().toString().equals(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(repo.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE; // it moved under us; that is not a finding
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    static List<String> filesChanged(Path repo, String from, String to) throws Exception {
        return BookshelfFixture.git(repo, "diff --name-only " + from + " " + to)
            .lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    // --- scale levers ---------------------------------------------------------------------------

    /**
     * The architect, with a {@link TokenBudget} stamped on every task it plans — a tight TOKEN
     * ceiling (400,000 against production's 2.5 million per candidate), and the turn allowance
     * resolved above through {@link TurnAllowance}, which is the product's own number, not this
     * harness's.
     *
     * <p>The planner leaves {@code budget} null and the worker loop then falls back to
     * {@link TurnAllowance#BUILT_IN_MAX_TOOL_TURNS} turns and 2.5 million tokens per candidate —
     * right for an overnight build, far too many tokens for a harness meant to run after every
     * merge. Overriding the one method the workflow calls is the only seam: the budget is a
     * property of the planned Task and nothing between PLAN and dispatch can set it. No task is
     * added, removed or re-scoped, so the plan the validator checks is the plan the planner wrote.
     *
     * <p><b>What this used to get wrong.</b> Until 2026-09-03 the fourth field of this budget was a
     * harness-only default of 24 tool turns, not the product's number, and nothing here ever
     * resolved it through {@link TurnAllowance}. Harness run 11 killed two workers at exactly turn
     * 25 with {@code BUDGET_EXCEEDED} — the harness's own cap, reported as if the room had run out.
     */
    static final class BudgetStampingArchitect extends ArchitectClient {

        private final TokenBudget budget;

        BudgetStampingArchitect(VllmClient client, CloudGate gate, SwarmPolicy policy,
                                TokenBudget budget) {
            super(client, gate, policy);
            this.budget = budget;
        }

        @Override
        public TaskGraph plan(DesignDocument design, String goal, StoryScope scope,
                              String repoLayoutBrief) {
            TaskGraph graph = super.plan(design, goal, scope, repoLayoutBrief);
            if (graph != null && graph.tasks() != null) {
                graph.tasks().forEach(task -> task.setBudget(budget));
            }
            return graph;
        }
    }

    /**
     * The project's rules, counting what the intake wizard states into them.
     *
     * <p>The count is the measurement, not the size of the rule set. {@code dev/bookshelf-demo}
     * ships 46 committed guideline files and 21 of them are already ACTIVE and already marked as
     * stated, so "the project has stated rules" is true of the fixture before this harness starts.
     * What is being tested is that the TECHNICAL DOCUMENT produced them on this run, and the only
     * place that is observable is the call the wizard makes when the operator presses Apply.
     */
    private static final class StatedRules implements ConsoleContext.GuidelineControl {

        private final ProjectRules sync;
        private final List<String> stated = Collections.synchronizedList(new ArrayList<>());

        StatedRules(ProjectRules sync) {
            this.sync = sync;
        }

        @Override
        public String setStatus(UUID guidelineId, String status) {
            return sync.setStatus(guidelineId, GuidelineStatus.valueOf(status));
        }

        @Override
        public String setCheck(UUID guidelineId, String command, int timeoutSeconds) {
            return sync.setCheck(guidelineId, command, timeoutSeconds);
        }

        @Override
        public String stateRule(String title, String body, String document) {
            String result = sync.stateRule(title, body, document);
            if (result == null || result.isEmpty()) {
                stated.add(title + " (from " + document + ")");
            }
            return result;
        }

        @Override
        public String stateRule(String title, String body, String document, String excerpt) {
            return stateRule(title, body, document, excerpt, null, false);
        }

        /** The excerpt, purpose and strength reach the rule, as they do through DependencyGraph. */
        @Override
        public String stateRule(String title, String body, String document, String excerpt,
                                String purpose, boolean hard) {
            return stateRule(title, body, document, excerpt, purpose, hard, null);
        }

        /** The part of the project a rule applies to reaches it, as through DependencyGraph. */
        @Override
        public String stateRule(String title, String body, String document, String excerpt,
                                String purpose, boolean hard, List<String> appliesTo) {
            String result = sync.stateRule(title, body, document, excerpt, purpose, hard,
                appliesTo);
            if (result == null || result.isEmpty()) {
                stated.add(title + " (from " + document + ")");
            }
            return result;
        }

        @Override
        public List<String> ruleScopes() {
            return sync.modules();
        }

        @Override
        public int supersedeRulesFrom(String document) {
            return sync.supersedeRulesFrom(document);
        }
    }

    /**
     * Every role's client, recording the prompts it sends.
     *
     * <p>A stage that was never asked anything and a stage that answered badly leave the same
     * evidence in the store: nothing. This is how "the judge was told whether the candidate was
     * verified" can be checked against a live model — the words are the model's, but the brief is
     * the product's, and the brief is what is being tested.
     */
    static final class Recorder extends VllmClient {

        private final List<String> prompts;

        /**
         * The roles talk to the same model the workers do, so they get the same quirks. Before
         * 2026-09-25 this hardcoded json_object and thinking off, which was right for one Qwen
         * and fails every JSON call on a server without structured output.
         */
        Recorder(String baseUrl, String modelName, ModelQuirks quirks) {
            super(baseUrl, "", modelName, quirks);
            this.prompts = Collections.synchronizedList(new ArrayList<>());
        }

        /**
         * A role on a server of its own (taken from the operator's config): that server's key and
         * quirks, and the SAME record of prompts as {@code shareWith}, so the checks that look for
         * what a role was told still find it whichever server the role ran on.
         */
        Recorder(String baseUrl, String apiKey, String modelName, ModelQuirks quirks,
                 Recorder shareWith) {
            super(baseUrl, apiKey, modelName, quirks);
            this.prompts = shareWith == null ? Collections.synchronizedList(new ArrayList<>())
                : shareWith.prompts;
        }

        @Override
        public Stream<String> chatCompletionStream(List<Map<String, String>> messages,
                                                   Class<?> targetSchemaClass, double temperature)
                throws Exception {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                sb.append(message.get("role")).append(": ").append(message.get("content"))
                  .append('\n');
            }
            prompts.add(sb.toString());
            return super.chatCompletionStream(messages, targetSchemaClass, temperature);
        }

        /** The first prompt containing {@code needle}, or null when no role was ever sent one. */
        String firstContaining(String needle) {
            synchronized (prompts) {
                return prompts.stream().filter(p -> p.contains(needle)).findFirst().orElse(null);
            }
        }
    }

    // --- plumbing --------------------------------------------------------------------------------

    /**
     * Starts a run the way the Console does — pinned to the tip of the delivery branch.
     *
     * <p>This used to build the run and hand it to the engine without pinning it, so every
     * worktree of the harness's run was cut from a live {@code HEAD}. That HEAD happened to carry
     * the acceptance tests, so the harness walked its whole chain green while every Console-started
     * run — pinned at intake, three stages before a test existed — executed zero acceptance tests
     * for a week. A harness that starts a run differently from the product measures something other
     * than the product; {@link BasePin} is now the one implementation both go through.
     */
    static UUID startRun(WorkflowEngine engine, GitService git, UUID projectId, String goal,
                         String kind, UUID storyId) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE, projectId,
            storyId, null, null, null, Instant.now(), new RunReport(runId, goal));
        String pinned = BasePin.pin(run, git);
        System.out.println("[E2E] " + (pinned == null ? "run " + runId + " is not pinned" : pinned));
        engine.advance(run);
        return runId;
    }

    /**
     * Waits for a wizard, and breaks the chain AT ITS OWN LINK when it does not arrive.
     *
     * <p>The link name is a parameter rather than a guess, because a wizard that stalls is the
     * failure of the stage it belongs to. Without it the first run of this harness reported only
     * "5 of 15 links walked" and a stack trace — true, and one line short of useful.
     */
    private GuidedFlow awaitFlow(ArtifactStore store, String flowId, String link,
                                 GuidedFlowState... wanted) {
        UUID id = UUID.fromString(flowId);
        long deadline = System.currentTimeMillis() + FLOW_TIMEOUT_MILLIS;
        GuidedFlowState seen = null;
        while (System.currentTimeMillis() < deadline) {
            GuidedFlow flow = store.getGuidedFlow(id);
            if (flow != null) {
                seen = flow.state();
                if (List.of(wanted).contains(seen)) {
                    return flow;
                }
                if (seen == GuidedFlowState.FAILED) {
                    throw chain.fail(link, "the wizard FAILED while it was waited on for "
                        + List.of(wanted) + ": " + oneLine(flow.error()));
                }
            }
            sleep(200);
        }
        throw chain.fail(link, "the wizard never reached " + List.of(wanted) + " in "
            + (FLOW_TIMEOUT_MILLIS / 60_000) + " minutes — it stopped at " + seen
            + (seen == GuidedFlowState.AWAITING_ANSWERS
                ? ", which means it asked the operator something and nobody answered" : ""));
    }

    /**
     * A Console call that must succeed, attributed to the link it belongs to.
     *
     * <p>Every one of these is an operator's click, and the Console answers "" or a sentence saying
     * why not. That sentence is the finding — so it goes in the chain's one line rather than into a
     * stack trace. It matters: the third live run of this harness died here on
     * {@code apply} refusing the analyst's whole proposal set over one dependency edge pointing at
     * a handle that did not exist, and a bare AssertionError would have said only that a link was
     * "never reached".
     */
    private void expectEmpty(String link, String result) {
        if (result != null && !result.isEmpty()) {
            throw chain.fail(link, "the Console refused an operator action that must succeed: "
                + oneLine(result));
        }
    }

    static String normalise(String path) {
        String cleaned = path.replace('\\', '/').strip();
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "(none)" : sha.substring(0, Math.min(8, sha.length()));
    }

    static String oneLine(String text) {
        if (text == null) {
            return "(nothing)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }

    static String firstFew(List<String> items) {
        return items.size() <= 6 ? items.toString()
            : items.subList(0, 6) + " (+" + (items.size() - 6) + " more)";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
