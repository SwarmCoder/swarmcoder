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
import com.swarmcoder.console.ChangeRequestIntake;
import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.TurnAllowance;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.knowledge.ChangeNeighbourhood;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.knowledge.ExpertDesk;
import com.swarmcoder.knowledge.ExpertEscalation;
import com.swarmcoder.runtime.ExpertHelp;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.knowledge.SemanticIndex;
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
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.RunBrief;
import com.swarmcoder.workflow.StoryScope;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>Point SwarmCoder at a repository nobody wrote for it, ask for one real change, and check what
 * it produced against the test the maintainer actually shipped.</b>
 *
 * <p>Seventeen links, from "the target repository is checked out at the parent of the known fix" to
 * "the maintainer's own test passes on the merged tree", against real closed issues of a real
 * third-party project ({@code dev/brownfield/jsoup-cases.json}).
 *
 * <ol>
 *   <li>the target repository is checked out at the parent of the known fix, and the fix is not on
 *       it;</li>
 *   <li>the build and test contract is detected and really builds this repository;</li>
 *   <li>the project's own suite is green before anything starts;</li>
 *   <li>the change request becomes one agreed requirement carrying checks;</li>
 *   <li>the neighbourhood of the change names real types, with files and lines;</li>
 *   <li>the architect's design states contracts naming types that exist;</li>
 *   <li>every agreed check is claimed by a task, and no task was planned for nothing;</li>
 *   <li>every task writes only to a source root the build compiles;</li>
 *   <li>the acceptance test lands where the build runs it;</li>
 *   <li>the acceptance stage executes a non-zero number of tests;</li>
 *   <li>a worker produced a candidate that changes a file;</li>
 *   <li>verification reported a real verdict, not an empty pass;</li>
 *   <li>a winner was selected, or a clear reason given;</li>
 *   <li>the winner is merged;</li>
 *   <li>the swarm's own test passes on the merged tree;</li>
 *   <li><b>the maintainer's test passes on the merged tree;</b></li>
 *   <li>the project's whole suite is green on the merged tree.</li>
 * </ol>
 *
 * <h2>Two tests, because they cost different things</h2>
 *
 * <p>{@link #theGroundUnderThisCaseIsSolid} walks links 1-3 for <b>every</b> case and needs no
 * model at all — it is git and the project's build tool, so it runs in an ordinary build and
 * reports what the ground costs per case. {@link #theWholeChainWalksFromOneIssueToTheMaintainersTest}
 * walks all seventeen for <b>one</b> case, dispatches live workers, and is gated on
 * {@link Need#LIVE_MODEL}.
 *
 * <h2>Why the maintainer's test is the measurement and the swarm's own is not</h2>
 *
 * <p>The swarm writes its own reproduction from the same issue text, so "the swarm's test passes"
 * proves only that it satisfied its own reading of the report. The maintainer's test was written
 * from the fix and encodes the reading that was actually right. The two agreeing is the evidence.
 * {@link HumanFixOracle} is where that check lives, and it is test-only on purpose: the product
 * must never contain code that reads a known-good answer.
 *
 * <p><b>The fix's source half is never applied, read or rendered anywhere.</b> The tree is cut at
 * the fix's parent, only the commit's test files are ever extracted, and
 * {@code TheFixIsNeverShownToTheSwarmTest} proves it over every case with the model scripted.
 *
 * <h2>What it measures rather than asserts</h2>
 *
 * <p>Through {@link ChainLedger}, so each link records one observation phrased to read correctly
 * whether it held or not — the commit the tree is at, the probe's seconds, the wall clock of the
 * project's own suite and how many tests it executed, the size of the brief the worker was handed,
 * and the four oracle lines. The recurring failure this whole harness exists to catch is a stage
 * that exits 0 having done nothing, so the numbers are the evidence and the boolean is the alarm.
 *
 * <p><b>A red oracle is a result, not a failure of the harness.</b> The chain breaks at the link
 * that was not true and the report prints all four lines whatever they say, because the point of
 * running this is to find out.
 *
 * <h2>Nothing here can spend money</h2>
 *
 * <p>Links 1-3 open no socket at all. The end-to-end walk copies
 * {@link EndToEndLoopTest}'s guard verbatim: it refuses to start unless the endpoint is plain HTTP
 * on a private address, and it never reads the operator's {@code ~/.swarmcoder/config.yaml}, whose
 * roles point at billed accounts.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * # links 1-3, all five cases, no model:
 * mvn -o test -pl sc-app -am -Dtest=BrownfieldLoopTest#theGroundUnderThisCaseIsSolid
 *
 * # all seventeen, one case, live:
 * mvn -o test -pl sc-app -am -Dtest=BrownfieldLoopTest  *     -Dswarmcoder.live.baseUrl=http://192.168.0.10:8000/v1 -Dswarmcoder.live.model=deepseek-v4-flash -Dswarmcoder.live.shape=deepseek-v4-flash-ds4
 * </pre>
 *
 * <p>Knobs: {@code -Dswarmcoder.brownfield.case=2187} selects the case (the end-to-end walk
 * defaults to {@link BrownfieldCases#EASIEST_CASE} and refuses to run more than one at a time);
 * {@code -Dswarmcoder.brownfield.targets=<dir>} moves the clone and the case trees off
 * {@code ~/.swarmcoder/targets}; {@code -Dswarmcoder.brownfield.suiteMinutes} caps the suite; the
 * {@code -Dswarmcoder.e2e.*} budget knobs are the greenfield harness's and mean the same here.
 *
 * <p>The first run clones the target (jsoup: 12 MB, about two seconds) into
 * {@code ~/.swarmcoder/targets/jsoup} and clones a case tree per case beside it. Every run after that
 * reuses both, and needs no network.
 */
@RunsWhen(Need.MAVEN)   // links 1-3; the end-to-end walk adds Need.LIVE_MODEL on its own method
class BrownfieldLoopTest {

    // --- the chain, named once, in order, in the words of the design ----------------------

    private static final String L_TREE = "the target repository is checked out at the parent of "
        + "the known fix, and the fix is not on it";
    private static final String L_CONTRACT = "the build and test contract is detected and really "
        + "builds this repository";
    private static final String L_SUITE = "the project's own suite is green before anything starts";
    private static final String L_TASKS = "every agreed check is claimed by a task, and no task "
        + "was planned for nothing";
    private static final String L_WRITESETS = "every task writes only to a source root the build "
        + "compiles";
    private static final String L_TESTS_WRITTEN = "the acceptance test lands where the build runs it";
    private static final String L_TESTS_RUN = "the acceptance stage executes a non-zero number of "
        + "tests";
    private static final String L_CANDIDATE = "a worker produced a candidate that changes a file";
    private static final String L_VERIFIED = "verification reported a real verdict, not an empty pass";
    private static final String L_WINNER = "a winner was selected, or a clear reason given";
    private static final String L_INTEGRATED = "the winner is merged";
    private static final String L_SWARM_TEST = "the swarm's own test passes on the merged tree";
    private static final String L_HUMAN_TEST = "the maintainer's test passes on the merged tree";
    private static final String L_WHOLE_SUITE = "the project's whole suite is green on the merged "
        + "tree";

    /** Links 1 to 3 — the ground, walked for every case with no model at all. */
    private static final List<String> GROUND = List.of(L_TREE, L_CONTRACT, L_SUITE);

    /**
     * All seventeen, in the order they are WALKED.
     *
     * <p>Links 4, 5 and 6 are {@link BrownfieldUnderstanding}'s and it walks the neighbourhood
     * before the requirement — the analyst is shown the neighbourhood, so measuring it afterwards
     * would measure what the requirement was already written from. A ledger prints its plan in
     * order and marks what it never reached, so the plan has to be the walk rather than the
     * numbering.
     */
    private static final List<String> WHOLE_CHAIN = concat(
        List.of(L_TREE, L_CONTRACT, L_SUITE),
        BrownfieldUnderstanding.chainLinksBeforeTheDesign(),
        List.of(BrownfieldUnderstanding.L_CONTRACTS, L_TASKS, L_WRITESETS, L_TESTS_WRITTEN,
            L_TESTS_RUN, L_CANDIDATE, L_VERIFIED, L_WINNER, L_INTEGRATED,
            L_SWARM_TEST, L_HUMAN_TEST, L_WHOLE_SUITE));

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return List.copyOf(all);
    }

    /** How long the project's own suite may take before the harness stops waiting for it. */
    private static final String SUITE_MINUTES_PROPERTY = "swarmcoder.brownfield.suiteMinutes";

    /** jsoup's whole suite is minutes, not tens of minutes; a target needing more says so. */
    private static final int DEFAULT_SUITE_MINUTES = 30;

    /** What every case measured, printed as one table at the end. */
    private static final Map<String, String> MEASURED = new LinkedHashMap<>();

    /** The clone, paid for by whichever case ran first. */
    private static BrownfieldTarget.Clone clone;

    static List<BrownfieldCases.Case> cases() throws IOException {
        return BrownfieldCases.selected();
    }

    /**
     * Links 1 to 3 for one case: the tree is at the fix's parent, the contract really builds it,
     * and the suite is green — with the suite's wall clock and test count recorded either way.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theGroundUnderThisCaseIsSolid(BrownfieldCases.Case one) throws Exception {
        ChainLedger chain = new ChainLedger(GROUND);
        System.out.println("\n[BROWNFIELD] ===== " + one.name() + " — " + one.title() + " =====");
        System.out.println("[BROWNFIELD] why this case qualifies: " + one.why());

        try {
            Path tree = theTreeIsAtTheFixesParentAndCarriesNoFix(chain, one);
            VerifySpec spec = theContractIsDetectedAndReallyBuildsIt(chain, one, tree);
            theProjectsOwnSuiteIsGreen(chain, one, tree, spec);
        } finally {
            System.out.println(chain.report());
        }
    }


    // =========================================================================================
    // Wave 3: all seventeen links, one case, live
    // =========================================================================================

    /**
     * <b>One real issue, end to end, and then the maintainer's own test on what came out.</b>
     *
     * <p>The whole chain for one case: the ground (links 1-3), the understanding (4-6), the
     * delivery the greenfield harness already proves (7-14), and then the three that are the point
     * of the whole exercise — the swarm's own reproduction, the maintainer's test, and the
     * project's whole suite, all on the tree the run merged.
     *
     * <p><b>Full suite per candidate, no slicing</b> (design decision 3). Wave 3 pays the full cost
     * so that a failing case can never be blamed on a test selection that narrowed too far. jsoup's
     * suite is 20 to 50 seconds, so on this target the wall clock is model turns, not builds.
     *
     * <p><b>A red oracle is a result.</b> The chain breaks at whichever link was not true and the
     * four lines are printed whatever they say, because finding out is why this is run.
     */
    @RunsWhen({Need.LIVE_MODEL, Need.MAVEN})
    @Test
    void theWholeChainWalksFromOneIssueToTheMaintainersTest() throws Exception {
        BrownfieldCases.Case one = BrownfieldCases.theOneToWalkEndToEnd();
        String baseUrl = EndToEndLoopTest.refuseAnythingButAFreeLocalEndpoint();
        String model = System.getProperty("swarmcoder.live.model", "deepseek-v4-flash");
        int workers = Integer.getInteger("swarmcoder.e2e.workers", 2);
        TurnAllowance allowance = EndToEndLoopTest.resolveTurnAllowance();
        HarnessRunBudget.Budget runBudget = HarnessRunBudget.resolve(workers, allowance);
        long deadline = System.currentTimeMillis() + runBudget.millis();

        ChainLedger chain = new ChainLedger(WHOLE_CHAIN);
        System.out.println("\n[BROWNFIELD] ===== " + one.name() + " — " + one.title() + " =====");
        System.out.println("[BROWNFIELD] why this case qualifies: " + one.why());
        System.out.println("[BROWNFIELD] endpoint=" + baseUrl + " model=" + model + " workers="
            + workers + " turns=" + allowance.maxToolTurns() + " (" + allowance.label()
            + ") budget=" + runBudget.minutes() + "min (" + runBudget.sentence() + ")");

        HumanFixOracle.Report oracle = null;
        try {
            Path tree = theTreeIsAtTheFixesParentAndCarriesNoFix(chain, one);
            VerifySpec spec = theContractIsDetectedAndReallyBuildsIt(chain, one, tree);
            theProjectsOwnSuiteIsGreen(chain, one, tree, spec);
            oracle = deliverAndCheckAgainstTheHuman(chain, one, tree, spec, baseUrl, model,
                workers, allowance, runBudget, deadline);
        } catch (OracleReached reached) {
            // The oracle ran and one of its three links broke. That is a RESULT — the four lines
            // are the whole point of the wave — so it is carried out here to be printed before the
            // chain's own break is rethrown.
            oracle = reached.report;
            throw reached.broken;
        } finally {
            System.out.println(chain.report());
            printTheFourLines(one, oracle);
        }
    }

    /** Carries a finished oracle report out past a broken chain link, so it still gets printed. */
    private static final class OracleReached extends RuntimeException {
        private final transient HumanFixOracle.Report report;
        private final transient ChainLedger.Broken broken;

        OracleReached(HumanFixOracle.Report report, ChainLedger.Broken broken) {
            super(broken.getMessage(), broken);
            this.report = report;
            this.broken = broken;
        }
    }

    private static void printTheFourLines(BrownfieldCases.Case one, HumanFixOracle.Report oracle) {
        if (oracle == null) {
            System.out.println("\n[BROWNFIELD] the run never reached a merged tree, so the "
                + "maintainer's test was never applied to anything. " + HumanFixOracle
                    .neverDelivered(one, "the chain broke before integration").verdict() + "\n");
            return;
        }
        System.out.println("\n============ THE FOUR LINES, " + one.name() + " ============");
        oracle.fourLines().forEach(line -> System.out.println("  " + line));
        System.out.println("  ----");
        System.out.println("  " + oracle.verdict());
        System.out.println("======================================================"
            + "======\n");
    }

    /**
     * Everything from the change request to the oracle, with the product wired the way the
     * operator's own app wires it.
     *
     * <p>Deliberately close to {@link EndToEndLoopTest}'s own walk, and different in exactly three
     * places, each of which is what this wave is about:
     *
     * <ul>
     *   <li><b>the front half.</b> No documents, no analyst wizard, no planner: one pasted issue
     *       through {@link ChangeRequestIntake}, which is wave 2's work;</li>
     *   <li><b>no reference folder.</b> The target IS the reference (design §2.1) — the Librarian's
     *       project root is the case tree and there are no context roots at all, so every
     *       worked example and every {@code lookup_api} answer comes out of the target's own
     *       source. That is the case the design is about, and it needs no ZeroZ checkout;</li>
     *   <li><b>the three links at the end.</b> {@link HumanFixOracle}.</li>
     * </ul>
     */
    private HumanFixOracle.Report deliverAndCheckAgainstTheHuman(
            ChainLedger chain, BrownfieldCases.Case one, Path repo, VerifySpec spec, String baseUrl,
            String model, int workers, TurnAllowance allowance, HarnessRunBudget.Budget runBudget,
            long deadline) throws Exception {

        // Before anything is spent: every command a worker writes runs in a container, and with
        // no Docker or no image the run is refused here rather than run on this machine.
        com.swarmcoder.sandbox.DockerSandboxManager workerSandbox = HarnessSandbox.required();

        BuildLayout.Layout layout = BuildLayout.read(repo, spec.toolchain());
        AcceptanceTestLocation.Location acceptanceHome = AcceptanceTestLocation.resolve(layout);
        System.out.println("[BROWNFIELD] the build compiles " + layout.compilingModules()
            + " and acceptance tests belong in " + acceptanceHome.writeDir() + " — "
            + acceptanceHome.note());

        // What the endpoint actually serves, discovered the way DependencyGraph does at startup —
        // never the 32,768-token ModelQuirks default, which is a different and easier product than
        // the one the operator runs.
        HarnessModelBudget.Discovery modelBudget = HarnessModelBudget.discover(
            java.net.http.HttpClient.newHttpClient(), baseUrl, model);
        Integer working = modelBudget.quirks().workingContextTokens();
        int neighbourhoodChars =
            ChangeNeighbourhood.forWorkingContext(working == null ? 0 : working);
        System.out.println("[BROWNFIELD] " + modelBudget.describe());
        System.out.println("[BROWNFIELD] the code this change is about may take up to "
            + neighbourhoodChars + " characters of a worker's brief (about "
            + neighbourhoodChars / 4 + " tokens), sized against the working context this server "
            + "really offers rather than against the design's flat ceiling of "
            + ChangeNeighbourhood.MAX_CHARS);

        Path work = Files.createTempDirectory("brownfield-" + one.issue() + "-");
        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            EndToEndLoopTest.Recorder roleClient = new EndToEndLoopTest.Recorder(baseUrl, model, modelBudget.quirks());
            CloudGate cloudGate = new CloudGate(50_000_000, null);
            GitService git = new GitService(repo);
            Project project = store.ensureProject(one.target(), repo.toString(), List.of());
            UUID projectId = project.id();
            TraceHub traceHub = new TraceHub(null);

            // The target is its own reference: no context roots, project root is the case
            // case tree. Design §2.1 — on a brownfield target the structural index and the
            // worked-example chooser are entirely about the target, and that is correct.
            Librarian librarian = new Librarian(
                new Context7Client("https://mcp.context7.com/mcp", null, false),
                new DocsIndex(work.resolve("docs-index")),
                List.of(), repo, null, work.resolve("primers"));
            // Sized for the room this server really offers, as ProjectContext sizes it from the
            // resolved worker profiles (MaterialBudget, 2026-09-25).
            MaterialBudget workerRoom = MaterialBudget.of(modelBudget.quirks());
            librarian.sizedFor(workerRoom);
            System.out.println("[BROWNFIELD] workers are handed " + workerRoom.describe());
            System.out.println("[BROWNFIELD] the librarian is grounded in the target itself: "
                + librarian.curator().documentCount() + " document(s) under " + repo
                + ", and every worked example comes out of that tree");

            ModelQuirks workerQuirks = modelBudget.quirks();
            ExpertEscalation expertEscalation = new ExpertEscalation(roleClient, cloudGate,
                librarian.curator(), librarian, repo, List.of(),
                new KoogAgentRuntime(traceHub), ExpertEscalation.MAX_TURNS);
            Supplier<ExpertHelp> expertFactory = () -> new ExpertDesk(
                librarian.curator(), repo, List.of(), expertEscalation).sizedFor(workerRoom);
            InferenceScheduler scheduler = InferenceScheduler.forWorkerModel(model, workerQuirks,
                baseUrl);
            SwarmEngineImpl swarm = new SwarmEngineImpl(roleClient, store,
                scheduler,
                new KoogAgentRuntime(traceHub),
                new ModelProfileRegistry(List.of(new ModelProfile(model,
                    new AgentRuntime.ModelEndpoint(baseUrl, "", model,
                        workerQuirks.servedContextTokens(), workerQuirks),
                    ModelProfile.Kind.WORKER, workerQuirks, 0))),
                git, content -> null, cloudGate, () -> "", librarian::lookupApi,
                expertFactory, ProjectContext.frameworkPackagesOf(librarian));
            // The container sees the worker's own checkout and the Maven repository, read-only,
            // and nothing else of this machine. The target is its own reference here, so there
            // is no reference folder to mount.
            swarm.setReferenceRoots(librarian.curator().referenceFolders());
            swarm.setSandbox(workerSandbox);

            SwarmPolicy policy = new SwarmPolicy(workers, false, 0.2, 0.8, List.of("minimal-diff"));
            TokenBudget budget = new TokenBudget(32_000, 4_000, 400_000, allowance.maxToolTurns());
            CloudRoles roles = new CloudRoles(
                new EndToEndLoopTest.BudgetStampingArchitect(roleClient, cloudGate, policy, budget),
                new DesignReviewerClient(roleClient, cloudGate),
                new TestAuthorClient(roleClient, cloudGate));
            // The architect, the planner and the test author work as agents, as ProjectContext
            // wires them (owner decision, 2026-10-02). -Dswarmcoder.roles.oneReply=true runs
            // them as they were.
            if (!Boolean.getBoolean("swarmcoder.roles.oneReply")) {
                LookupAgent roleAgent = new LookupAgent(librarian.curator(), librarian, repo,
                    cloudGate, new KoogAgentRuntime(traceHub), scheduler, endpoint -> model)
                    .withExpert(() -> new ExpertDesk(librarian.curator(), repo, List.of(),
                        expertEscalation));
                roles.architect().setLookupAgent(roleAgent);
                roles.testAuthor().setLookupAgent(roleAgent);
                System.out.println("[BROWNFIELD] the architect, the planner and the test author "
                    + "work as agents with these lookups: " + roleAgent.lookupNames());
            }

            WorkflowEngine engine = new WorkflowEngine(new KoogAgentRuntime(traceHub), swarm,
                roleClient, store, cloudGate, repo, roles, git, librarian);
            engine.setEventLogger(message -> System.out.println("[BROWNFIELD] " + message));
            engine.setNeighbourhoodChars(neighbourhoodChars);

            ConsoleContext.set(new ConsoleContext(store, traceHub,
                (goal, kind) -> EndToEndLoopTest.startRun(engine, git, projectId, goal, kind, null),
                runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (name, path, ctx) -> null, id -> { })
                .withStoryRuns((goal, kind, storyId) ->
                    EndToEndLoopTest.startRun(engine, git, projectId, goal, kind, storyId))
                .withAnalyst((messages, override) ->
                    roleClient.chatCompletionStream(messages, null, 0.4)));

            // --- links 5 and 4: the neighbourhood, then the requirement -----------------------
            SemanticIndex index = librarian.curator().semanticIndex();
            ChangeNeighbourhood.Neighbourhood neighbourhood =
                BrownfieldUnderstanding.neighbourhoodOf(one.title(), one.issueText(), index,
                    BrownfieldUnderstanding.testRootsOf(layout), neighbourhoodChars);
            BrownfieldUnderstanding.recordTheNeighbourhood(chain, neighbourhood);

            ChangeRequestIntake.Started change = BrownfieldUnderstanding.walkTheChangeRequest(
                chain, ConsoleContext.get(), projectId,
                new ChangeRequestIntake.Request(one.title(), one.issueText(), one.kind()),
                neighbourhood.brief(), "");
            Story story = change.story();
            UUID runId = change.runId();

            EndToEndLoopTest.Outcome outcome =
                EndToEndLoopTest.awaitRun(store, traceHub, runId, deadline, runBudget.minutes());
            System.out.println("[BROWNFIELD] run " + runId + " " + outcome.describe());
            Run run = store.root().runs.get(runId);

            // --- link 6: the design states contracts naming types that exist ------------------
            DesignDocument design = run == null || run.designId() == null ? null
                : store.root().designs.get(run.designId());
            System.out.println("[BROWNFIELD] " + whatTheArchitectWasTold(roleClient));
            BrownfieldUnderstanding.recordTheContracts(chain, design, index);

            // --- link 7: the plan claims every agreed check -----------------------------------
            TaskGraph graph = run == null || run.taskGraphId() == null ? null
                : store.root().taskGraphs.get(run.taskGraphId());
            if (graph == null) {
                throw chain.fail(L_TASKS, "no plan was ever stored — " + outcome.describe());
            }
            StoryScope storedScope = StoryScope.resolve(store.getBrd(projectId),
                store.getStory(story.id()));
            PlanTaskLinkageCheck.Verdict linkage = PlanTaskLinkageCheck.evaluate(graph, storedScope);
            chain.require(L_TASKS, linkage.ok() && !graph.tasks().isEmpty(),
                linkage.detail() + ": " + graph.tasks().stream()
                    .map(t -> "'" + t.title() + "' (" + t.criterionIds().size() + " check(s), "
                        + (t.deliveredContracts() == null ? 0 : t.deliveredContracts().size())
                        + " contract(s), writes " + t.writeSet() + ")").toList());

            // --- link 8: write sets -----------------------------------------------------------
            List<String> orphans = new ArrayList<>();
            for (Task task : graph.tasks()) {
                Set<String> writeSet = task.writeSet() == null ? Set.<String>of() : task.writeSet();
                for (String path : writeSet) {
                    if (!WriteSetLinkageCheck.isAccepted(path, writeSet, layout)) {
                        orphans.add("'" + task.title() + "' -> " + path);
                    }
                }
            }
            chain.require(L_WRITESETS, orphans.isEmpty(),
                "the build compiles from " + layout.sourceRoots() + "; the plan writes to "
                    + graph.tasks().stream().map(Task::writeSet).toList()
                    + (orphans.isEmpty() ? " — all inside a compiled root or a compiled module's "
                        + "build file" : " — OUTSIDE every one of them: " + orphans));

            // What the worker was actually handed. Not a link — a measurement, and the one wave 3
            // exists to make: whether the code this change is about, and the nearest existing
            // example of it, really reached a dispatched worker's prompt.
            System.out.println("[BROWNFIELD] " + whatTheWorkerWasHanded(store, graph));

            // --- link 9: the acceptance test landed where the build runs it --------------------
            String testsCommit = run.acceptanceTestsCommit();
            List<String> authored = testsCommit == null ? List.of()
                : EndToEndLoopTest.filesInCommit(repo, testsCommit).stream()
                    .filter(p -> p.startsWith(
                        EndToEndLoopTest.normalise(acceptanceHome.writeDir()) + "/")).toList();
            chain.require(L_TESTS_WRITTEN, !authored.isEmpty(),
                "the build compiles acceptance tests in " + acceptanceHome.writeDir()
                    + "; the run's tests commit "
                    + EndToEndLoopTest.shortSha(testsCommit) + " holds " + authored.size()
                    + " file(s) there " + authored
                    + (authored.isEmpty() ? ". The run " + outcome.describe() : ""));

            // --- link 10: that stage really executed something ---------------------------------
            List<CandidateSolution> candidates = EndToEndLoopTest.archived(store);
            int executed = 0;
            List<String> perCandidate = new ArrayList<>();
            for (CandidateSolution candidate : candidates) {
                TestResults acceptance = candidate.verification() == null ? null
                    : candidate.verification().acceptance();
                if (acceptance == null) {
                    perCandidate.add("worker " + candidate.workerIndex()
                        + ": no acceptance results");
                    continue;
                }
                executed = Math.max(executed, acceptance.executed());
                perCandidate.add("worker " + candidate.workerIndex() + ": stage "
                    + acceptance.stageOutcome() + ", " + acceptance.executed() + " executed ("
                    + acceptance.passed() + " passed / " + acceptance.failed() + " failed)");
            }
            chain.require(L_TESTS_RUN, executed > 0,
                (perCandidate.isEmpty() ? "no candidate ever reached verification"
                    : String.join("; ", perCandidate))
                    + (outcome.parkedBrief() == null ? ""
                        : " | the run stopped here: "
                            + EndToEndLoopTest.oneLine(outcome.parkedBrief())));

            // --- link 11: a worker changed a file ----------------------------------------------
            List<CandidateSolution> changing = candidates.stream()
                .filter(c -> c.diffUnified() != null && !c.diffUnified().isBlank()).toList();
            chain.require(L_CANDIDATE, !changing.isEmpty(),
                candidates.size() + " candidate(s) archived, " + changing.size()
                    + " carrying a non-empty diff; states "
                    + EndToEndLoopTest.stateHistogram(candidates)
                    + (changing.isEmpty() ? " — " + outcome.describe() : ""));

            // --- link 12: verification returned a real verdict ---------------------------------
            List<CandidateSolution> reported = candidates.stream()
                .filter(c -> c.verification() != null).toList();
            chain.require(L_VERIFIED, !reported.isEmpty(), reported.size() + " of "
                + candidates.size() + " candidate(s) carry a verification report"
                + (reported.isEmpty() ? " — a candidate with no report was never compiled and "
                    + "never tested, and survives verification automatically" : ""));

            // --- link 13: a winner --------------------------------------------------------------
            List<CandidateSolution> selected = candidates.stream()
                .filter(c -> c.state() == CandidateState.SELECTED).toList();
            chain.require(L_WINNER, !selected.isEmpty(),
                selected.size() + " candidate(s) SELECTED out of " + candidates.size()
                    + "; states " + EndToEndLoopTest.stateHistogram(candidates)
                    + (selected.isEmpty() ? "; the reason on record: "
                        + EndToEndLoopTest.oneLine(outcome.parkedBrief()) : ""));

            // --- link 14: it is merged ----------------------------------------------------------
            Story delivered = store.getStory(story.id());
            String integrationCommit = delivered.integrationCommit();
            List<String> mergedFiles = integrationCommit == null || integrationCommit.isBlank()
                ? List.of()
                : EndToEndLoopTest.filesChanged(repo, one.parentCommit(), integrationCommit);
            chain.require(L_INTEGRATED,
                integrationCommit != null && !integrationCommit.isBlank()
                    && mergedFiles.stream().anyMatch(f -> f.contains("/main/")),
                "story '" + delivered.title() + "' is " + delivered.state()
                    + ", integration commit " + EndToEndLoopTest.shortSha(integrationCommit)
                    + " touching " + mergedFiles.size() + " file(s): " + mergedFiles
                    + " (run ended " + outcome.describe() + ")");

            // --- links 15, 16, 17: the maintainer's own test ------------------------------------
            int suiteSeconds =
                Integer.getInteger(SUITE_MINUTES_PROPERTY, DEFAULT_SUITE_MINUTES) * 60;
            HumanFixOracle.Report oracle = HumanFixOracle.run(repo, one, spec, integrationCommit,
                testsCommit, one.parentCommit(), suiteSeconds);
            try {
                chain.require(L_SWARM_TEST, oracle.swarmTestPassed(), oracle.swarmTestLine());
                chain.require(L_HUMAN_TEST, oracle.humanTestPassed(), oracle.humanTestLine());
                chain.require(L_WHOLE_SUITE, oracle.wholeSuiteGreen(),
                    oracle.suiteLine() + " | " + oracle.filesLine());
            } catch (ChainLedger.Broken broken) {
                throw new OracleReached(oracle, broken);
            }
            return oracle;
        } finally {
            // Installed statically; left behind it points a later test at a closed store.
            ConsoleContext.set(null);
        }
    }

    // --- the two measurements wave 3 is really about ---------------------------------------

    /**
     * Whether the architect was told to state a contract for every type this change touches, and
     * whether the code the change is about reached that prompt.
     *
     * <p>Both are prompt lines, so both can be true in the source and absent from the wire — a
     * flag never read, a brief assembled and dropped. This reads what was actually sent.
     */
    private static String whatTheArchitectWasTold(EndToEndLoopTest.Recorder client) {
        String prompt = client.firstContaining("You are a software architect");
        if (prompt == null) {
            return "the architect was never called at all";
        }
        boolean toldItIsExistingCode =
            prompt.contains("THIS CHANGE IS AGAINST A CODEBASE THAT ALREADY EXISTS");
        boolean carriedTheNeighbourhood = prompt.contains(RunBrief.NEIGHBOURHOOD_HEADING);
        return "the architect's brief was " + prompt.length() + " characters and "
            + (toldItIsExistingCode
                ? "DID tell it to state a contract for every type this change touches, existing "
                    + "or new"
                : "did NOT tell it this is a change to code that already exists, so it will state "
                    + "contracts only for types it invents and every task delivering none gets no "
                    + "worked example")
            + "; it " + (carriedTheNeighbourhood
                ? "carried the types this change is about, read out of the repository"
                : "carried NO neighbourhood, so it designed from the report's prose alone");
    }

    /**
     * What a dispatched worker's knowledge brief actually contained: the code this change is
     * about, the nearest existing example, and whether that example is a file the worker is
     * changing or a pattern to copy.
     *
     * <p>This is the design's own lever (§2.3) and the whole reason wave 3 touches the Librarian,
     * so it is measured rather than assumed. A brief that was built and never attached, or
     * attached with the example channel empty, is the failure this reports in words.
     */
    private static String whatTheWorkerWasHanded(ArtifactStore store, TaskGraph graph) {
        List<String> perTask = new ArrayList<>();
        for (Task task : graph.tasks()) {
            KnowledgeBrief brief = task.knowledgeBriefId() == null ? null
                : store.root().briefs.get(task.knowledgeBriefId());
            if (brief == null || brief.renderedMarkdown() == null) {
                perTask.add("'" + task.title() + "': NO knowledge brief was attached, so this "
                    + "worker was shown no code from this project at all");
                continue;
            }
            String text = brief.renderedMarkdown();
            boolean hasNeighbourhood = text.contains(Librarian.NEIGHBOURHOOD_HEADING);
            boolean hasExample = text.contains("the nearest working example, in full");
            boolean saysItIsYourFile = text.contains("is a file you are changing")
                || text.contains("are files you are changing");
            perTask.add("'" + task.title() + "': " + text.length() + " characters (~"
                + text.length() / 4 + " tokens); "
                + (hasNeighbourhood ? "carries the code this change is about"
                    : "carries NO neighbourhood section")
                + "; " + (hasExample
                    ? "carries the nearest existing example in full, labelled "
                        + (saysItIsYourFile ? "as a file this task is changing"
                            : "as a pattern to copy elsewhere")
                    : "carries NO worked example — the channel the unseen-code experiment measured "
                        + "is OFF for this task"));
        }
        return "what each worker was handed — " + String.join(" | ", perTask);
    }

    // --- link 1 ---------------------------------------------------------------------------

    /**
     * Clones (or reuses) the case's own tree and proves the fix is not on it.
     *
     * <p>Two independent checks, because they fail differently. The ancestry check catches a tree
     * cut at the wrong commit. The content check catches a tree cut at the right commit that
     * nonetheless has the change on it — a rebase, a cherry-pick, or a case file naming a parent
     * that is not really before the fix. Only the second reads what a worker would actually see.
     */
    private Path theTreeIsAtTheFixesParentAndCarriesNoFix(
            ChainLedger chain, BrownfieldCases.Case one) throws Exception {

        BrownfieldCases.File file = BrownfieldCases.file();
        if (clone == null) {
            clone = BrownfieldTarget.cloneOnce(file.target(), file.cloneUrl());
            System.out.println("[BROWNFIELD] " + clone.describe());
        }

        BrownfieldTarget.CaseTree cut = BrownfieldTarget.caseTreeAt(clone.path(), one);
        System.out.println("[BROWNFIELD] " + cut.describe());

        boolean atParent = one.parentCommit().equals(cut.headSha());
        boolean fixIsAncestor = isAncestor(cut.path(), one.fixCommit());
        List<String> leaked = maintainersTestsPresentIn(cut.path(), one);

        String observation = "the case tree is at " + shortSha(cut.headSha())
            + (atParent ? " — the fix " + shortSha(one.fixCommit()) + "'s parent, as the case file "
                + "names it" : " — but the case file names the fix's parent as "
                + shortSha(one.parentCommit()))
            + "; the fix is " + (fixIsAncestor ? "AN ANCESTOR of this tree" : "not an ancestor")
            + "; " + (leaked.isEmpty()
                ? "none of the maintainer's " + one.humanTests().size()
                    + " test method(s) appear anywhere in it"
                : "its test method(s) " + String.join(", ", leaked) + " ARE already in it");

        chain.require(L_TREE, atParent && !fixIsAncestor && leaked.isEmpty(), observation);
        return cut.path();
    }

    // --- link 2 ---------------------------------------------------------------------------

    /**
     * Detects the contract, runs its compile command for real, writes it and commits it — then
     * reads it back across the boundary the orchestrator actually asks across.
     */
    private VerifySpec theContractIsDetectedAndReallyBuildsIt(
            ChainLedger chain, BrownfieldCases.Case one, Path tree) throws Exception {

        BrownfieldCases.ExistingCorrection correction =
            BrownfieldCases.file().existingCorrection();
        TargetRepository.Correction fix = correction == null ? null
            : new TargetRepository.Correction(correction.commands(), correction.reason());
        if (fix != null) {
            System.out.println("[BROWNFIELD] the case file corrects the detected existing-test "
                + "command before it is saved: " + correction.reason());
        }

        // In a container, like everything else this harness builds: the tree is the target
        // project's, and by the end of a run it holds what the swarm wrote.
        TargetRepository.Registration registration = TargetRepository.register(
            tree, TargetRepository.DEFAULT_PROBE_TIMEOUT_SECONDS, true, fix,
            HarnessSandbox.boxes());
        System.out.println("[BROWNFIELD] " + registration.describe());
        if (registration.detection() != null) {
            registration.detection().evidence()
                .forEach(e -> System.out.println("[BROWNFIELD]   detected from: " + e));
            registration.detection().warnings()
                .forEach(w -> System.out.println("[BROWNFIELD]   decide: " + w));
        }

        TargetRepository.Toolchain toolchain = TargetRepository.toolchain(tree,
            registration.detection() == null ? "unknown" : registration.detection().toolchain(),
            HarnessSandbox.boxes());
        System.out.println("[BROWNFIELD] toolchain: " + toolchain.describe());

        // The contract must come back from the OPERATOR'S tree, not a worker's worktree — a worker
        // that could edit the commands that judge it could certify itself green.
        Path pretendWorktree = Files.createTempDirectory("brownfield-not-the-operators-tree");
        VerifySpec loaded = null;
        try {
            loaded = VerifySpecLoader.loadTrusted(tree, pretendWorktree).orElse(null);
        } catch (Exception e) {
            System.out.println("[BROWNFIELD] the committed contract did not load back: "
                + e.getMessage());
        }

        boolean built = registration.ready();
        // The design's own note: jsoup's baseline is Java 8, but its multi-release profile needs 11
        // or later to build the shipped jar. Asserted here rather than discovered inside a
        // candidate's build, where it reads like a candidate fault.
        boolean jdkNewEnough = toolchain.javaAtLeast(11);
        boolean loadsBack = loaded != null && loaded.existing() != null
            && !loaded.existing().isEmpty();

        String compileCommand = registration.spec() == null || registration.spec().compile() == null
            ? "none" : String.join(" ; ", registration.spec().compile());
        String observation = (registration.detection() == null ? "nothing detected"
                : "detected " + registration.detection().toolchain())
            + "; compile command \"" + compileCommand + "\" "
            + (registration.probe() == null ? "was never run"
                : registration.probe().compiles()
                    ? "ran and exited 0 in " + registration.probe().duration().toSeconds() + "s"
                    : "ran and FAILED with exit " + registration.probe().exitCode())
            + "; contract " + (registration.contractWritten()
                ? "committed at " + shortSha(registration.contractCommit())
                : "NOT written")
            + "; it loads back from the operator's tree naming "
            + (loadsBack ? String.join(" ; ", loaded.existing()) : "NO existing-test command")
            + "; running on " + toolchain.describe()
            + (jdkNewEnough ? "" : " — which is older than the JDK 11 this target needs");

        if (!built && registration.probe() != null && !registration.probe().compiles()) {
            System.out.println("[BROWNFIELD] --- probe output ---\n"
                + registration.probe().logTail());
        }
        chain.require(L_CONTRACT, built && jdkNewEnough && loadsBack, observation);
        return loaded;
    }

    // --- link 3 ---------------------------------------------------------------------------

    /**
     * Runs the project's whole suite once on the untouched tree, and records what it cost.
     *
     * <p>Green is not enough on its own: a build tool that selected nothing exits 0 too, so the
     * JUnit reports are counted and a suite that executed zero tests breaks this link.
     */
    private void theProjectsOwnSuiteIsGreen(ChainLedger chain, BrownfieldCases.Case one,
                                            Path tree, VerifySpec spec) {
        int minutes = Integer.getInteger(SUITE_MINUTES_PROPERTY, DEFAULT_SUITE_MINUTES);
        System.out.println("[BROWNFIELD] running the project's own suite (up to " + minutes
            + " minutes) — this is the number every later wave's budget is built on…");

        TargetRepository.SuiteRun suite =
            TargetRepository.runSuite(tree, spec, minutes * 60, HarnessSandbox.boxes());
        MEASURED.put(one.name(), suite.describe());
        System.out.println("[BROWNFIELD] suite: " + suite.describe());
        if (!suite.green() && !suite.logTail().isBlank()) {
            System.out.println("[BROWNFIELD] --- suite output ---\n" + suite.logTail());
        }

        String observation = "the untouched tree's own suite ran \""
            + String.join(" ; ", suite.commands()) + "\" and came back " + suite.describe()
            + " (wall clock " + human(suite.duration()) + ")";
        chain.require(L_SUITE, suite.greenAndNotEmpty(), observation);
    }

    // --- the table somebody actually reads --------------------------------------------------

    @AfterAll
    static void whatTheGroundCost() {
        if (MEASURED.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder(
            "\n============ BROWNFIELD WAVE 1 — what the ground cost ============\n");
        MEASURED.forEach((name, measurement) ->
            out.append(String.format("  %-14s %s%n", name, measurement)));
        out.append("  clone: ").append(clone == null ? "never made" : clone.describe()).append('\n');
        out.append("=================================================================\n");
        System.out.println(out);
    }

    // --- the small measurements the links are made of ---------------------------------------

    /** True when the fix is reachable from this tree's HEAD — i.e. the answer is on the tree. */
    private static boolean isAncestor(Path tree, String commit) {
        try {
            BookshelfFixture.git(tree, "merge-base --is-ancestor " + commit + " HEAD");
            return true;
        } catch (Exception e) {
            // git exits non-zero for "not an ancestor" and BookshelfFixture.git turns that into an
            // exception. A commit the tree has never heard of also lands here, and that is the same
            // answer for this link's purpose: it is not on the tree.
            return false;
        }
    }

    /**
     * Which of the maintainer's test methods are already present in the tree's own test files.
     *
     * <p>Reads the files a worker would read, rather than trusting git history — the check that
     * catches a case file whose "parent" is not really before the fix.
     */
    private static List<String> maintainersTestsPresentIn(Path tree, BrownfieldCases.Case one)
            throws IOException {
        List<String> found = new ArrayList<>();
        for (String test : one.humanTests()) {
            String method = test.contains("#") ? test.substring(test.indexOf('#') + 1) : test;
            for (String file : one.humanTestFiles()) {
                Path path = tree.resolve(file);
                if (Files.isRegularFile(path) && Files.readString(path).contains(method)) {
                    found.add(method);
                    break;
                }
            }
        }
        return found;
    }

    private static String human(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds < 60 ? seconds + "s"
            : (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }
}
