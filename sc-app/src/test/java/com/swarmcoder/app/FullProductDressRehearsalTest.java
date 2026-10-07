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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.GuidedFlowState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.TestRefOrigin;
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
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.workflow.ArchitectClient;
import com.swarmcoder.workflow.CloudRoles;
import com.swarmcoder.workflow.DesignReviewerClient;
import com.swarmcoder.workflow.TestAuthorClient;
import com.swarmcoder.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full-product dress rehearsal: a document goes in one end and a requirement comes out the
 * other marked IMPLEMENTED, carrying the commit and the test run that prove it.
 *
 * <p><b>What is real here.</b> A real git repository with real history. A real Maven build. Real
 * JUnit, producing real surefire XML that the real parser reads and the real evidence machinery
 * judges. The real Console services — document intake, the requirements wizard, the promotion gate,
 * the planning wizard, the backlog, the acceptance gate. The real workflow engine through every
 * stage, the real worker tool loop over real HTTP, real write-set enforcement, real verification,
 * real clustering and judging, a real merge onto a real integration branch.
 *
 * <p><b>What is simulated.</b> The model's words, and nothing else. {@link ScriptedModel} answers
 * an in-process OpenAI-compatible endpoint; every client, every parser and every decision between
 * the endpoint and the requirement's final status is the product's own.
 *
 * <p>That division is the point. Until now the chain from document to IMPLEMENTED was pinned by a
 * test that wrote the model's output directly INTO the store and skipped the middle — so it proved
 * the links, never the machine. The machine had only ever been run with a live model, which is
 * unavailable when it matters and unrepeatable when it is not.
 *
 * <p><b>The live run is one flag.</b> {@code -Dswarmcoder.live.baseUrl=…} runs the identical
 * journey against a real model server; the scaffolding, the assertions and the repository are
 * shared, so the only new variable is the model.
 *
 * <pre>
 * mvn -o test -pl sc-app -am -Dtest=FullProductDressRehearsalTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8000/v1 -Dswarmcoder.live.model=qwen36-27b
 * </pre>
 */
class FullProductDressRehearsalTest {

    /** The document the operator drops in. Deliberately vague in one place, so a question is due. */
    private static final String DOCUMENT = """
        # Calculator

        The calculator must multiply two whole numbers and give back the product.

        Multiplication has to behave sensibly at the edges of the range we support.
        """;

    /** Generous: the journey runs several real Maven builds. Bounded so it fails rather than hangs. */
    private static final long RUN_TIMEOUT_MILLIS = 900_000;
    private static final long FLOW_TIMEOUT_MILLIS = 120_000;

    @TempDir
    Path work;

    @AfterEach
    void clearContext() {
        // Installed statically; left behind it would point later tests at a closed store.
        ConsoleContext.set(null);
    }

    @Test
    void aDocumentBecomesARequirementMarkedImplementedByARealBuild() throws Exception {
        ScriptedModel model = new ScriptedModel();
        try (FakeVllm endpoint = new FakeVllm(model)) {
            Trace trace = journey(endpoint.baseUrl(), "fake-vllm", false, true);

            // Every role really was asked — a journey that silently skipped a stage would
            // otherwise still look green.
            assertThat(model.sawPromptContaining("requirements analyst reading"))
                .as("the requirements wizard asked its questions").isTrue();
            assertThat(model.sawPromptContaining("You are a delivery planner"))
                .as("the planning wizard planned a story").isTrue();
            assertThat(model.sawPromptContaining("The requirements are FIXED"))
                .as("the architect designed against the BRD, not against its own invention").isTrue();
            assertThat(model.sawPromptContaining("You are a test author"))
                .as("the test author was invoked — it is skipped entirely if a scoped task's "
                    + "criteria cannot be resolved").isTrue();
            assertThat(model.sawPromptContaining("software engineering worker agent"))
                .as("a worker actually ran").isTrue();
            assertThat(model.sawPromptContaining("code-review judge"))
                .as("the judge scored the survivors").isTrue();

            System.out.println("[DRESS] " + trace);
        }
    }

    /**
     * The same journey, same assertions, against a live model server. One flag: everything else —
     * the repository, the Console services, the workflow, the evidence machinery — is shared with
     * the scripted run above, so the only thing that changed is who is answering.
     */
    @Test
    @RunsWhen(Need.LIVE_MODEL)
    void theSameJourneyAgainstALiveModel() throws Exception {
        Trace trace = journey(System.getProperty("swarmcoder.live.baseUrl"),
            System.getProperty("swarmcoder.live.model", "qwen36-27b"), false, false);
        System.out.println("[DRESS] " + trace);
    }

    /**
     * The same journey with each candidate verified inside a Docker container instead of on the
     * workstation — the sandboxed half of the pipeline (spec §8), which nothing else exercises
     * without a live model.
     *
     * <p>It runs by itself on any machine with a Docker daemon and the worker image, and no
     * flag switches it on. It used to need {@code SWARMCODER_SANDBOX_TESTS=true}, chosen
     * because an empty {@code <properties>} block in a pom silently overrides a CLI
     * {@code -D} inside the surefire fork and had already made one gate skip without saying
     * so. That reasoning was right about the trap and wrong about the answer: an environment
     * variable nobody sets skips just as silently. {@code @RunsWhen} is immune to the trap
     * from the other side — a property that fails to reach the fork now makes the test RUN,
     * never disappear.
     *
     * <p>Measured cost: about 98 seconds, and it is the only offline proof that a candidate
     * really verifies inside a container.
     */
    @Test
    @RunsWhen(Need.DOCKER)
    void theSameJourneyWithCandidatesVerifiedInDocker() throws Exception {
        // Live when an endpoint is given, scripted otherwise. Sandboxed verification is the half
        // of the pipeline nothing else exercises, and running it only against a scripted model
        // would prove the container starts, not that a real candidate verifies inside one.
        String live = System.getProperty("swarmcoder.live.baseUrl");
        if (live != null && !live.isBlank()) {
            Trace trace = journey(live,
                System.getProperty("swarmcoder.live.model", "qwen36-27b"), true, false);
            System.out.println("[DRESS] " + trace);
            return;
        }
        try (FakeVllm endpoint = new FakeVllm(new ScriptedModel())) {
            journey(endpoint.baseUrl(), "fake-vllm", true, true);
        }
    }

    // --- the journey ------------------------------------------------------------------------

    /** What the journey produced, for the log — every link of the chain in one line. */
    private record Trace(String documentName, String requirementHandle, String storyKey,
                         UUID runId, String commit, List<String> testRefs) {}

    /**
     * @param scripted true when {@link ScriptedModel} is answering, and every word the model says
     *                 is therefore known in advance. A live model is a competent stranger: it may
     *                 legitimately split the document differently, write more checks, and name its
     *                 test class whatever it likes. So the word-for-word assertions are gated on
     *                 this flag and the chain assertions are not, and the scripted journey keeps
     *                 exactly the strictness it had.
     */
    private Trace journey(String baseUrl, String modelName, boolean sandboxed, boolean scripted)
            throws Exception {
        UUID projectId = UUID.randomUUID();
        Path repo = DemoRepo.create(work.resolve("target-repo"));
        System.out.println("[DRESS] target repository: " + repo + " — " + DemoRepo.describeSource());
        // Where the repository stood before the swarm touched it, so "a worker landed a real
        // change" can be measured rather than assumed.
        String baseCommit = headCommit(repo);

        try (ArtifactStore store = new ArtifactStore(work.resolve("store"))) {
            VllmClient roleClient = new VllmClient(baseUrl, "", modelName, true);
            CloudGate cloudGate = new CloudGate(50_000_000, null);
            GitService git = new GitService(repo);

            // Two workers per task, so dispatch, verify-all, clustering, judging and selection are
            // all really exercised rather than short-circuited by there being one candidate.
            SwarmPolicy swarmPolicy =
                new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff"));
            CloudRoles roles = new CloudRoles(
                new ArchitectClient(roleClient, cloudGate, swarmPolicy),
                new DesignReviewerClient(roleClient, cloudGate),
                new TestAuthorClient(roleClient, cloudGate));

            SwarmEngineImpl swarm = new SwarmEngineImpl(
                roleClient, store, new InferenceScheduler(16, 1024L * 1024 * 1024, 1024),
                new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile(modelName,
                    new AgentRuntime.ModelEndpoint(baseUrl, "", modelName, 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                git, content -> null, cloudGate, () -> null);
            // Both variants: a real model's commands and the builds of its code run in a
            // container. An engine with none refuses to run them, so there is no other way.
            swarm.setSandbox(HarnessSandbox.required());
            System.out.println("[DRESS] workers and candidates run inside Docker"
                + (sandboxed ? " (the sandboxed variant)" : ""));

            WorkflowEngine engine = new WorkflowEngine(new KoogAgentRuntime(), swarm, roleClient,
                store, cloudGate, repo, roles, git);
            engine.setEventLogger(message -> System.out.println("[DRESS] " + message));

            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> startRun(engine, projectId, goal, kind, null),
                runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (name, path, ctx) -> null, id -> { })
                .withStoryRuns((goal, kind, storyId) ->
                    startRun(engine, projectId, goal, kind, storyId))
                // The wizards talk to the endpoint through the same client the app uses.
                .withAnalyst((messages, override) ->
                    roleClient.chatCompletionStream(messages, null, 0.4))
                .withPlanner((messages, override) ->
                    roleClient.chatCompletionStream(messages, null, 0.4)));

            GuidedFlowServiceImpl intake = new GuidedFlowServiceImpl();
            PlanningFlowServiceImpl planning = new PlanningFlowServiceImpl();
            BacklogServiceImpl backlog = new BacklogServiceImpl();
            BrdServiceImpl brdService = new BrdServiceImpl();

            // --- 1. INTAKE: the operator drops a requirements document in --------------------
            DocumentIngest.Result ingested = DocumentIngest.ingest(store, null, projectId,
                "calculator-requirements.md", "text/markdown",
                DOCUMENT.getBytes(StandardCharsets.UTF_8));
            assertThat(ingested.failed()).as(ingested.error()).isFalse();
            SourceDocument document = ingested.document();

            // --- 2. ANALYSIS: the requirements wizard reads it and proposes requirements ------
            String intakeFlow = intake.intake().flow().id().toString();
            assertThat(intake.addDocument(intakeFlow, document.id().toString(),
                "authoritative for arithmetic")).isEmpty();
            assertThat(intake.start(intakeFlow)).isEmpty();

            // WHETHER the analyst asks anything is its own judgement, and its own instructions say
            // so: "asking nothing is a correct and common answer". A live analyst answered
            // {"questions":[]} to this document on 2026-08-28 and went straight to drafting, which
            // is allowed. What the product promises is that IF it asks, the operator's answer
            // reaches the drafting call and nothing is applied until they say so. The scripted
            // journey pins the answer round exactly; here it is exercised when it happens.
            GuidedFlow asked = scripted
                ? awaitFlow(store, intakeFlow, GuidedFlowState.AWAITING_ANSWERS)
                : awaitFlow(store, intakeFlow, GuidedFlowState.AWAITING_ANSWERS,
                    GuidedFlowState.REVIEW);
            var questions = store.listFlowQuestions(asked.id());
            if (scripted) {
                assertThat(questions).as("the analyst asked what the document left open")
                    .isNotEmpty();
            }
            if (!questions.isEmpty()) {
                System.out.println("[INTAKE] answering " + questions.size() + " question(s)");
                assertThat(intake.answer(intakeFlow, questions.get(0).id().toString(),
                    "whole numbers including zero and negatives")).isEmpty();
                assertThat(intake.submitAnswers(intakeFlow)).isEmpty();
            } else {
                System.out.println("[INTAKE] the analyst asked nothing and drafted straight away");
            }

            awaitFlow(store, intakeFlow, GuidedFlowState.REVIEW);
            assertThat(store.listFlowProposals(asked.id()))
                .as("the analyst proposed at least one requirement").isNotEmpty();
            assertThat(intake.setAllAccepted(intakeFlow, true)).isEmpty();
            assertThat(intake.apply(intakeFlow)).isEmpty();

            // What the analyst actually produced, in full. Without this a live failure at intake
            // says only "expected 1 but was 2" and the reasoning behind the second requirement —
            // which is the whole question — is discarded with the flow.
            dumpIntake(store, projectId, asked.id());

            // The BRD the analyst filled in. How many requirements it split the document into is
            // the analyst's call, not the product's promise — a document with one clear sentence
            // and one vague one may honestly become one requirement or two. What the product does
            // promise is that NOTHING it wrote is in scope until a person says so.
            Brd draftedBrd = store.getBrd(projectId);
            assertThat(draftedBrd.requirements()).as("intake produced requirements").isNotEmpty();
            assertThat(draftedBrd.requirements())
                .as("nothing an agent wrote is in scope until a person says so")
                .allMatch(r -> r.status() == RequirementStatus.DRAFT);
            if (scripted) {
                assertThat(draftedBrd.requirements()).hasSize(1);
            }

            BrdRequirement drafted = multiplyRequirement(store, projectId);
            assertThat(drafted.criteria()).as("the requirement came with checks").isNotEmpty();
            if (scripted) {
                assertThat(drafted.criteria()).hasSize(2);
            }
            assertThat(drafted.sourceRef().documentId())
                .as("each requirement remembers the document it came from")
                .isEqualTo(document.id());

            // The wizard now PROPOSES the test each check will be proved by, and every proposal is
            // marked as one. This used to be null at both of the wizard's call sites, so the single
            // string the whole requirement-to-commit trace hangs on was typed by a person into the
            // editor and validated against nothing.
            assertThat(drafted.criteria())
                .as("the wizard proposed the name of the test that will prove each check")
                .allMatch(criterion -> criterion.testClassOrFile() != null
                    && criterion.testRefOrigin() == TestRefOrigin.PROPOSED);
            if (scripted) {
                assertThat(drafted.criteria().get(0).testClassOrFile())
                    .isEqualTo(ScriptedModel.CRITERION_ONE_TEST);
                assertThat(drafted.criteria().get(1).testClassOrFile())
                    .as("and the second suggestion is NOT what the test author will write")
                    .isEqualTo(ScriptedModel.CRITERION_TWO_PROPOSED);
            }

            // --- 3. AGREEMENT: the operator promotes it and settles the test names -----------
            // Only this one. Anything else the analyst proposed stays DRAFT, which is the ordinary
            // operator move — agree the piece you want built now — and it holds the planner's
            // scope to exactly the requirement this journey is about.
            for (BrdRequirement other : draftedBrd.requirements()) {
                if (!other.id().equals(drafted.id())) {
                    System.out.println("[DRESS] left in draft: " + other.handle() + " "
                        + other.title());
                }
            }
            assertThat(brdService.promoteRequirement(drafted.id().toString())).isEmpty();

            // The operator disagrees with what the wizard suggested and types the name they want.
            // That is the whole point of a proposal, and the difference has to survive: a guess and
            // a decision must not look alike in the editor. Only a real CHANGE flips the origin, so
            // the assertion below is against exactly the references that changed.
            List<UUID> operatorOwned = scripted
                ? nameTests(brdService, store, drafted.id(),
                    Map.of(1, ScriptedModel.CRITERION_TWO_TEST))
                : nameTests(brdService, store, drafted.id(), oneClassForAll(drafted));

            BrdRequirement agreed = requirement(store, drafted.id());
            assertThat(agreed.status()).isEqualTo(RequirementStatus.ACTIVE);
            assertThat(agreed.criteria()).allMatch(c -> c.status() == CriterionStatus.ACCEPTED);
            assertThat(operatorOwned)
                .as("the operator changed at least one test name, so both origins are really "
                    + "exercised").isNotEmpty();
            for (AcceptanceCriterion criterion : agreed.criteria()) {
                boolean theirs = operatorOwned.contains(criterion.id());
                assertThat(criterion.testRefOrigin())
                    .as(theirs ? "the operator typed this one, so it is theirs"
                               : "untouched, so still the wizard's suggestion")
                    .isEqualTo(theirs ? TestRefOrigin.OPERATOR : TestRefOrigin.PROPOSED);
            }

            // --- 4. PLANNING: stories slice exactly those checks out of the requirement -------
            String planFlow = planning.planning().flow().id().toString();
            assertThat(planning.start(planFlow)).isEmpty();
            GuidedFlow planned = awaitFlow(store, planFlow, GuidedFlowState.REVIEW);
            assertThat(store.listFlowProposals(planned.id())).isNotEmpty();
            assertThat(planning.setAllAccepted(planFlow, true)).isEmpty();
            assertThat(planning.apply(planFlow)).isEmpty();

            List<Story> stories = store.listStories(projectId);
            assertThat(stories).as("the planner sliced the agreed requirement into a story")
                .isNotEmpty();
            List<UUID> agreedCriteria =
                agreed.criteria().stream().map(AcceptanceCriterion::id).toList();
            if (scripted) {
                assertThat(stories).hasSize(1);
            }
            List<UUID> claimed = new ArrayList<>();
            for (Story sliced : stories) {
                System.out.println("[DRESS] story '" + sliced.title() + "' claims "
                    + sliced.criterionIds().size() + " check(s)");
                assertThat(sliced.criterionIds())
                    .as("the story delivers the requirement's own criteria, not a paraphrase of "
                        + "them: story '" + sliced.title() + "'")
                    .isSubsetOf(agreedCriteria);
                claimed.addAll(sliced.criterionIds());
            }
            assertThat(claimed)
                .as("every agreed check is claimed by a story — an unclaimed one can never be "
                    + "proved, and the requirement could never become IMPLEMENTED")
                .containsAll(agreedCriteria);

            // --- 5. THE BUILD: the swarm delivers every story ---------------------------------
            List<UUID> runIds = new ArrayList<>();
            Map<UUID, String> provenBy = new LinkedHashMap<>();
            for (Story story : stories) {
                assertThat(story.state()).isEqualTo(StoryState.DRAFT);
                assertThat(backlog.promoteStory(story.id().toString())).isEmpty();

                String startResult = backlog.startSession(story.id().toString());
                assertThat(startResult).as(startResult).doesNotStartWith("error:");
                UUID runId = UUID.fromString(startResult);
                runIds.add(runId);

                RunState terminal = awaitRun(store, runId);
                dumpRun(store, runId);
                assertThat(terminal)
                    .as("the run finished and handed the story back for judgment")
                    .isEqualTo(RunState.DELIVERED);

                // The acceptance stage really ran real tests, and the criteria really matched them.
                Story delivered = store.getStory(story.id());
                assertThat(delivered.state())
                    .as("REVIEW, never DONE: only a person can say this is what they asked for")
                    .isEqualTo(StoryState.REVIEW);
                assertThat(delivered.deliveredCommit())
                    .as("the story carries the commit that delivered it").isNotBlank();
                assertThat(delivered.integrationCommit()).isNotBlank();
                delivered.criterionIds()
                    .forEach(id -> provenBy.put(id, delivered.deliveredCommit()));

                // The change is really in the repository, on a real integration branch.
                if (scripted) {
                    assertThat(readAtCommit(repo, delivered.integrationCommit(),
                        "src/main/java/com/example/calc/Calculator.java"))
                        .as("the merged tree really contains the method the requirement asked for")
                        .contains("multiply");
                    assertThat(readAtCommit(repo, delivered.integrationCommit(),
                        "src/test/java/swarm/accept/MultiplyAcceptTest.java"))
                        .as("and the acceptance tests that proved it")
                        .contains("multipliesTwoPositiveNumbers");
                } else {
                    // WHICH files a live model touches, and what it calls its test class, are the
                    // model's own choices. That a worker really wrote product code, and that the
                    // acceptance tests really landed where the runner looks for them, are the
                    // product's promises — so those are what is asserted.
                    List<String> touched =
                        filesChanged(repo, baseCommit, delivered.integrationCommit());
                    System.out.println("[DRESS] integration commit touched: " + touched);
                    assertThat(touched)
                        .as("a worker really landed a change in the product code")
                        .anyMatch(file -> file.startsWith("src/main/java/"));
                    assertThat(touched)
                        .as("and the acceptance tests that proved it are in the merged tree")
                        .anyMatch(file -> file.startsWith("src/test/java/swarm/accept/"));
                }

                // --- 6. ACCEPTANCE: the operator's definition of done ------------------------
                assertThat(backlog.acceptStory(story.id().toString())).isEmpty();
                assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.DONE);
                assertThat(store.getStory(story.id()).runIds()).contains(runId);

                // The task that did the work carries the winning candidate's commit — the git end
                // of the link, independent of the story's own.
                TaskGraph graph =
                    store.root().taskGraphs.get(store.root().runs.get(runId).taskGraphId());
                assertThat(graph.tasks()).allSatisfy(task -> {
                    assertThat(task.commitSha()).as("the winning candidate's commit").isNotBlank();
                    assertThat(task.selectedCandidateId()).isNotNull();
                });
            }

            // --- the trace, end to end -------------------------------------------------------
            BrdRequirement implemented = requirement(store, drafted.id());
            assertThat(implemented.status())
                .as("every accepted criterion passed, so the requirement is implemented")
                .isEqualTo(RequirementStatus.IMPLEMENTED);

            for (AcceptanceCriterion criterion : implemented.criteria()) {
                assertThat(criterion.verification()).isEqualTo(CriterionState.PASSING);
                assertThat(criterion.lastVerifiedCommit()).isEqualTo(provenBy.get(criterion.id()));
                assertThat(runIds).contains(criterion.lastVerifiedRunId());
                List<CriterionVerification> journal = store.listVerifications(criterion.id());
                assertThat(journal).hasSize(1);
                assertThat(journal.get(0).testRef())
                    .as("the test the runner executed is the one the criterion names")
                    .isEqualTo(criterion.testClassOrFile());
                assertThat(journal.get(0).commitSha()).isEqualTo(provenBy.get(criterion.id()));
            }

            // Document -> requirement -> criterion -> story -> run -> commit, every hop reachable.
            assertThat(store.getSourceDocument(implemented.sourceRef().documentId()).filename())
                .isEqualTo("calculator-requirements.md");

            Story first = store.getStory(stories.get(0).id());
            return new Trace("calculator-requirements.md", implemented.handle(),
                first.key(), runIds.get(0), first.deliveredCommit(),
                implemented.criteria().stream()
                    .map(AcceptanceCriterion::testClassOrFile).toList());
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Starts a run already carrying its project and its story.
     *
     * <p>The story has to be attached BEFORE the engine sees the run: the engine advances it on
     * another thread immediately and rebuilds the Run at every transition from its own copy, so a
     * storyId attached afterwards is lost and the run silently stops being about the requirement.
     * This mirrors {@code DependencyGraph.startRun}, which is the production path.
     */
    private static UUID startRun(WorkflowEngine engine, UUID projectId, String goal, String kind,
                                 UUID storyId) {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE, projectId, storyId,
            null, null, null, Instant.now(), new RunReport(runId, goal));
        engine.advance(run);
        return runId;
    }

    /**
     * Everything the analyst said, printed. The questions it asked, the proposals it made, and the
     * requirements those became — titles, statements, checks, test references and who wrote each
     * one. A journey that disagrees with the script has to be readable, not just red.
     */
    private static void dumpIntake(ArtifactStore store, UUID projectId, UUID flowId) {
        for (var question : store.listFlowQuestions(flowId)) {
            System.out.println("[INTAKE] QUESTION [" + question.subject() + "] " + question.text());
            System.out.println("[INTAKE]   quoting: " + question.sourceQuote());
            System.out.println("[INTAKE]   because: " + question.background());
            System.out.println("[INTAKE]   kind=" + question.kind() + " options=" + question.options());
        }
        for (var proposal : store.listFlowProposals(flowId)) {
            System.out.println("[INTAKE] PROPOSAL " + proposal.kind() + " handle=" + proposal.handle()
                + " title=" + proposal.title());
            System.out.println("[INTAKE]   because: " + proposal.rationale());
            System.out.println("[INTAKE]   after: "
                + String.valueOf(proposal.after()).replaceAll("\\s+", " | "));
        }
        Brd brd = store.getBrd(projectId);
        System.out.println("[INTAKE] the BRD now holds " + brd.requirements().size()
            + " requirement(s)");
        for (BrdRequirement requirement : brd.requirements()) {
            System.out.println("[INTAKE] " + requirement.handle() + " [" + requirement.status()
                + "/" + requirement.kind() + "] " + requirement.title());
            System.out.println("[INTAKE]   statement: " + requirement.text());
            for (AcceptanceCriterion criterion : requirement.criteria()) {
                System.out.println("[INTAKE]   check [" + criterion.status() + "] "
                    + criterion.text() + "  proved by " + criterion.testClassOrFile()
                    + " (" + criterion.testRefOrigin() + ")");
            }
        }
    }

    /**
     * The requirement this journey is about: the one the analyst wrote about multiplication.
     *
     * <p>A live analyst may split the document into one requirement or several, and it may call
     * them what it likes. Which of its choices it made is not a promise the product makes; that
     * the document's central sentence became an agreed, buildable requirement is.
     */
    private static BrdRequirement multiplyRequirement(ArtifactStore store, UUID projectId) {
        List<BrdRequirement> all = store.getBrd(projectId).requirements();
        for (BrdRequirement candidate : all) {
            String haystack = (candidate.title() + " " + candidate.text())
                .toLowerCase(java.util.Locale.ROOT);
            if (haystack.contains("multipl") || haystack.contains("product")) {
                return candidate;
            }
        }
        throw new AssertionError("no requirement mentions multiplication; the analyst wrote "
            + all.stream().map(BrdRequirement::title).toList());
    }

    private static BrdRequirement requirement(ArtifactStore store, UUID requirementId) {
        UUID projectId = ConsoleContext.get().currentProjectId();
        for (BrdRequirement candidate : store.getBrd(projectId).requirements()) {
            if (candidate.id().equals(requirementId)) {
                return candidate;
            }
        }
        throw new AssertionError("requirement " + requirementId + " is gone from the BRD");
    }

    /**
     * The operator's edit for a live run: put every check of this requirement in ONE test class,
     * keeping each method name. It is what the wizard is told to do and what an operator does when
     * it did not, and it changes at least one reference in practice, which is what makes the
     * PROPOSED/OPERATOR distinction testable without dictating the model's wording.
     */
    private static Map<Integer, String> oneClassForAll(BrdRequirement requirement) {
        List<AcceptanceCriterion> criteria = requirement.criteria();
        Map<Integer, String> wanted = new LinkedHashMap<>();
        for (int i = 0; i < criteria.size(); i++) {
            String ref = criteria.get(i).testClassOrFile();
            int hash = ref == null ? -1 : ref.indexOf('#');
            String method = hash < 0 ? "check" + (i + 1) : ref.substring(hash + 1);
            wanted.put(i, "swarm.accept.MultiplyAcceptTest#" + method);
        }
        return wanted;
    }

    /**
     * Sets test references through the requirements editor the operator really uses.
     *
     * @return the ids of the criteria whose reference actually CHANGED — the ones the operator now
     *         owns. Saving a row without changing the reference must not convert the wizard's
     *         proposal into the operator's decision, so only these may read as OPERATOR.
     */
    private static List<UUID> nameTests(BrdServiceImpl brdService, ArtifactStore store,
                                        UUID requirementId, Map<Integer, String> wanted) {
        List<UUID> changed = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : wanted.entrySet()) {
            BrdRequirement current = requirement(store, requirementId);
            AcceptanceCriterion criterion = current.criteria().get(entry.getKey());
            if (entry.getValue().equals(criterion.testClassOrFile())) {
                continue;
            }
            AcceptanceCriterion edited =
                new AcceptanceCriterion(criterion.id(), criterion.text(), entry.getValue());
            edited.setStatus(criterion.status());
            String result = brdService.saveCriterion(requirementId.toString(), edited);
            assertThat(result).as(result).isEmpty();
            changed.add(criterion.id());
        }
        return changed;
    }

    /** The files a merge really touched — proof a worker landed a change, not just a commit. */
    private static List<String> filesChanged(Path repo, String from, String to) throws Exception {
        String out = git(repo, "diff --name-only " + from + " " + to);
        return out.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String headCommit(Path repo) throws Exception {
        return git(repo, "rev-parse HEAD").strip();
    }


    /** Polls the persisted flow: the wizards run on their own threads, and the store is the truth. */
    private static GuidedFlow awaitFlow(ArtifactStore store, String flowId,
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
                    throw new AssertionError("flow failed while waiting for " + List.of(wanted)
                        + ": " + flow.error());
                }
            }
            sleep(50);
        }
        throw new AssertionError("flow never reached " + List.of(wanted)
            + " — last state was " + seen);
    }

    private static RunState awaitRun(ArtifactStore store, UUID runId) {
        long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MILLIS;
        RunState last = null;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null) {
                if (run.state() != last) {
                    System.out.println("[DRESS] run state -> " + run.state());
                    last = run.state();
                }
                if (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED) {
                    return run.state();
                }
            }
            // A parked run is waiting for an operator who is never coming, and it keeps its
            // stage's state while it waits. Without this, a park costs the whole fifteen-minute
            // budget and then reports only "expected DELIVERED but was TEST_AUTHORING" — the
            // reason it parked, which the product wrote down in full, was never printed.
            String parked = pendingDecision(store, runId);
            if (parked != null) {
                throw new AssertionError("the run parked in " + last
                    + " and is waiting for an operator decision that will never come:\n" + parked);
            }
            sleep(500);
        }
        return last; // timed out mid-pipeline: the last state seen is the finding
    }

    /** The brief of the first decision this run is parked on, or null while it is still moving. */
    private static String pendingDecision(ArtifactStore store, UUID runId) {
        for (var decision : store.root().decisions.values()) {
            if (runId.equals(decision.runId()) && decision.state() == DecisionState.PENDING) {
                return decision.briefMarkdown();
            }
        }
        return null;
    }

    /** Everything the run parked on, so a failure says why instead of only where. */
    private static void dumpRun(ArtifactStore store, UUID runId) {
        Run run = store.root().runs.get(runId);
        if (run == null) {
            System.out.println("[DRESS] run was never persisted");
            return;
        }
        TaskGraph graph = run.taskGraphId() == null ? null
            : store.root().taskGraphs.get(run.taskGraphId());
        if (graph != null) {
            for (Task task : graph.tasks()) {
                System.out.println("[DRESS] task '" + task.title() + "' writeSet=" + task.writeSet()
                    + " criterionIds=" + task.criterionIds().size()
                    + " commit=" + task.commitSha());
            }
        }
        store.root().decisions.values().forEach(decision ->
            System.out.println("[DRESS] DECISION " + decision.kind() + ": "
                + decision.briefMarkdown().replaceAll("\\s+", " ")));
    }

    /** The file's content at a commit — proof the change is in the repository, not just in memory. */
    private static String readAtCommit(Path repo, String commit, String path) throws Exception {
        return git(repo, "show " + commit + ":" + path);
    }

    /** One git command in the target repository; its output, or an assertion naming what failed. */
    private static String git(Path repo, String command) throws Exception {
        List<String> argv = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + command)
            : List.of("sh", "-c", "git " + command);
        Process process = new ProcessBuilder(argv).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String content = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git " + command + " failed:\n" + content);
        }
        return content;
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
