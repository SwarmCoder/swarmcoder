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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.LibraryDoc;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.DeclarableArtifacts;
import com.swarmcoder.knowledge.ManifestParser;
import com.swarmcoder.knowledge.RulesVersusManifest;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.verify.BuildLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's rules name a library the build does not declare. The machine declares it.
 *
 * <p><b>What this test used to assert, and why that was wrong.</b> Run {@code ede2068b}
 * (2026-09-03): the operator's tech-requirements document said persistence goes through
 * {@code zerozstack-store-eclipsestore}, the server module's pom declared no such dependency, and
 * four workers each spent 15-22 turns rediscovering that by hand before being killed with nothing
 * written — one concluding "the poms are locked, so the server must use an in-memory root". The
 * first fix was to catch the contradiction at PLAN and park the run. That was still wrong: it moved
 * the same dead end twenty minutes earlier and still ended with a person editing a pom. The
 * operator's verdict was blunt: a swarm that cannot add a dependency to a pom file is useless.
 *
 * <p>So the run no longer parks on this. The plan gains the declaration as work, the task that
 * writes that module is told to do it, and its write set contains the build file. Exactly one thing
 * still parks: an artifact the offline Maven repository does not hold, which no worker could obtain
 * because candidate builds run with no network at all.
 *
 * <p>No paid model call anywhere: every role is pointed at {@link ScriptedLlm}, which answers every
 * prompt unparseably so DESIGN and PLAN take their offline fallback (exactly as
 * {@link WorkflowPersistenceTest} and {@link ParkWithdrawnOnResumeTest} do) — this test is about
 * what PLAN does with the comparison, not about what a model said.
 */
@ModelCodeOnThisPc
class TheSwarmDeclaresItsOwnDependencyTest {

    @TempDir
    Path storeDir;
    @TempDir
    Path repo;
    @TempDir
    Path m2;

    /** The system property the application also hands the sandbox as its read-only repository. */
    private static final String M2_PROPERTY = "swarmcoder.sandbox.m2";
    private String previousM2;
    private boolean m2Overridden;

    @AfterEach
    void restoreTheRealRepository() {
        if (!m2Overridden) {
            return;
        }
        if (previousM2 == null) {
            System.clearProperty(M2_PROPERTY);
        } else {
            System.setProperty(M2_PROPERTY, previousM2);
        }
    }

    private void useMavenRepository(Path repository) {
        previousM2 = System.getProperty(M2_PROPERTY);
        m2Overridden = true;
        System.setProperty(M2_PROPERTY, repository.toString());
    }

    /** The rule, in {@link com.swarmcoder.domain.ConstraintBrief}'s own rendered shape. */
    private static final String RULE_NAMING_ECLIPSESTORE =
        "HOW THIS PROJECT MUST BE BUILT — read this before you decide anything.\n\n"
        + "- Storage is an object graph, not a database\n"
        + "  Persistence uses EclipseStore through `zerozstack-store-eclipsestore`. The server "
        + "keeps the live Java objects in memory.\n";

    /**
     * A real, valid, scoped-empty plan (no story on these ad-hoc runs, so the planner's unscoped
     * schema applies — see {@code FrontHalfWorkflowTest#PLAN_JSON} for the same shape). Claims no
     * criteria, exactly as the run's design carries no requirements to claim: nothing here is about
     * whether PLAN can produce a graph, it is about what {@link BuildFilesInTheJob} then does with
     * one that writes into the server module.
     */
    private static final String PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"persist the shelf","instructions":"Persist to EclipseStore",
          "writeSet":["server/src/main/java/com/example/server"],"readSet":[],"criteria":[]}],
         "edges":[]}
        """;

    /** DESIGN still answers unparseably (there is nothing to design against on these runs), but
     * PLAN gets a real, working reply — every test in this file is about what happens AFTER PLAN
     * succeeds, not about PLAN's own retry-and-park behaviour, which {@link WorkflowPersistenceTest}
     * and {@code GreenfieldWorkflowPlanRetryTest} cover. */
    private static ScriptedLlm workingPlanner() throws java.io.IOException {
        return new ScriptedLlm(conversation ->
            conversation.contains("AI planner") ? PLAN_JSON : "I decline to produce JSON.");
    }

    // ---------------------------------------------------------------------- it becomes work

    @Test
    void aRuleNamingAnUndeclaredArtifactThatIsAvailableOfflineBecomesWorkInThePlan()
            throws Exception {
        useMavenRepository(m2);
        writeReactorMissingEclipseStore();
        writeBom();
        install("com.zeroz4j", "zerozstack-store-eclipsestore", "0.8.0-SNAPSHOT");
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = workingPlanner();
             ArtifactStore store = new ArtifactStore(storeDir)) {
            advance(engine(llm, store, run -> run), runId);

            awaitState(store, runId, RunState.DELIVERED);
            Run finished = store.root().runs.get(runId);
            assertThat(finished.parkedAt())
                .as("the artifact is on the disk the sandbox mounts, so nothing needs a human")
                .isNull();

            TaskGraph graph = store.root().taskGraphs.get(finished.taskGraphId());
            assertThat(graph.tasks()).isNotEmpty();
            String instructions = graph.tasks().stream()
                .map(Task::instructions).reduce("", (a, b) -> a + "\n" + b);
            assertThat(instructions)
                .contains("Also declare `com.zeroz4j:zerozstack-store-eclipsestore` "
                    + "in server/pom.xml")
                .contains("NO <version> element")
                .contains("Storage is an object graph");
        }
    }

    /**
     * The write-set half. Telling a worker to edit a file the path policy then refuses would be the
     * old failure with extra steps, so the two are asserted together: the build file is in the
     * write set, and the policy allows it.
     */
    @Test
    void aTaskThatWritesTheModulesSourcesCarriesItsBuildFileAndMayWriteIt() throws Exception {
        writeReactorMissingEclipseStore();
        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1L, null,
            List.of(new Task(UUID.randomUUID(), 1L, "persist the shelf", "write it",
                new LinkedHashSet<>(List.of("server/src/main/java/com/example/server")),
                Set.of(), List.of(), "src/test/java/swarm", null, null, null, TaskState.PENDING)),
            List.of());

        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        Set<String> writeSet = graph.tasks().get(0).writeSet();
        assertThat(writeSet).contains("server/pom.xml");
        assertThat(PathPolicy.check("server/pom.xml", writeSet, "src/test/java/swarm", List.of())
            .allowed()).isTrue();
    }

    /**
     * The whole chain, proved rather than reasoned about. {@link BuildFilesInTheJob} is called the
     * same way {@link GreenfieldWorkflow} calls it, so this starts from the SAME task the two tests
     * above prove PLAN produces — instructions naming the artifact, {@code server/pom.xml} in the
     * write set — and hands that exact task to the real {@link SwarmEngineImpl}, the engine
     * production EXECUTING runs. A scripted worker (no paid endpoint, see {@link FakeVllm}) does
     * the declaration, the candidate is verified and judged, and the winner selected is the one
     * whose diff is the two-line pom edit the instruction asked for.
     */
    @Test
    void theScriptedWorkerDeclaresItAndTheWinnersDiffCarriesThePomLine() throws Exception {
        useMavenRepository(m2);
        writeReactorMissingEclipseStore();
        writeBom();
        install("com.zeroz4j", "zerozstack-store-eclipsestore", "0.8.0-SNAPSHOT");

        BuildLayout.Layout layout = BuildLayout.read(repo, "maven");
        Task task = new Task(UUID.randomUUID(), 1L, "persist the shelf",
            "Walk the stored graph and render the reader's shelf.",
            new LinkedHashSet<>(List.of("server/src/main/java/com/example/server")),
            Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1L, null,
            new ArrayList<>(List.of(task)), new ArrayList<>());
        BuildFilesInTheJob.expandWriteSets(graph, layout, repo);

        // The same comparison GreenfieldWorkflow runs at PLAN: what the rules say against what the
        // build actually declares.
        List<LibraryDoc> declared = new ArrayList<>(ManifestParser.parse(repo));
        declared.addAll(ManifestParser.parse(repo.resolve("server")));
        List<RulesVersusManifest.Finding> findings = RulesVersusManifest.check(
            RULE_NAMING_ECLIPSESTORE, declared, List.of("pom.xml", "server/pom.xml"));
        assertThat(findings)
            .as("the rule names exactly the one artifact the server pom is missing")
            .hasSize(1);

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2.resolve("repository"));
        BuildFilesInTheJob.Outcome outcome = BuildFilesInTheJob.declareMissing(
            graph, findings, catalog, layout, Map.of("server", 1), Map.of(), repo);
        assertThat(outcome.parks()).isFalse();

        Task planned = outcome.graph().tasks().get(0);
        assertThat(planned.writeSet())
            .as("the pom is now the worker's to write")
            .contains("server/pom.xml");
        assertThat(planned.instructions())
            .contains("Also declare `com.zeroz4j:zerozstack-store-eclipsestore` in server/pom.xml");

        // Turn the fixture into a real git repository so the real swarm engine can dispatch a
        // worker at it, exactly as SwarmEngineFakeVllmTest does. The build check is a stand-in —
        // "echo compile-ok" — never a real Maven build; nothing here calls a paid model either.
        gitInitWithVerify("echo compile-ok");

        String originalPom = Files.readString(repo.resolve("server/pom.xml"));
        String declaredPom = originalPom.replaceFirst("<dependencies>\\r?\\n",
            "<dependencies>\n    <dependency>\n      <groupId>com.zeroz4j</groupId>\n"
                + "      <artifactId>zerozstack-store-eclipsestore</artifactId>\n    </dependency>\n");
        assertThat(declaredPom)
            .as("the fixture actually changed — otherwise this proves nothing")
            .isNotEqualTo(originalPom);

        try (FakeVllm fake = new FakeVllm(conversation -> {
            if (conversation.contains("code-review judge")) {
                return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"declares it\"}");
            }
            if (conversation.contains("wrote server/pom.xml")) {
                return FakeVllm.Reply.toolCall("report_done",
                    "{\"summary\": \"declared zerozstack-store-eclipsestore in server/pom.xml\"}");
            }
            return FakeVllm.Reply.toolCall("write_file",
                new ObjectMapper().createObjectNode()
                    .put("path", "server/pom.xml")
                    .put("content", declaredPom).toString());
        })) {
            CandidateSolution winner = runSwarm(fake, outcome.graph());

            assertThat(winner).as("a winner must be selected").isNotNull();
            assertThat(winner.diffUnified())
                .as("the winning candidate's diff is the declaration the instruction asked for")
                .contains("+    <dependency>")
                .contains("+      <groupId>com.zeroz4j</groupId>")
                .contains("+      <artifactId>zerozstack-store-eclipsestore</artifactId>");
            assertThat(winner.verification()).isNotNull();
            assertThat(winner.verification().compiles()).isTrue();
        }
    }

    // ------------------------------------------------------------ the one thing that parks

    @Test
    void anArtifactTheOfflineRepositoryDoesNotHoldStillParksTheRunNamingItAndTheDirectory()
            throws Exception {
        useMavenRepository(m2);
        writeReactorMissingEclipseStore();
        writeBom();
        // Deliberately NOT installed: the BOM pins its version, the disk does not have the files.
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = workingPlanner();
             ArtifactStore store = new ArtifactStore(storeDir)) {
            // If EXECUTING is ever reached, a swarm was dispatched at work no worker could do.
            advance(engine(llm, store, run -> {
                throw new AssertionError("a worker was dispatched — the run should have parked at "
                    + "PLAN and never reached the swarm");
            }), runId);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);
            assertThat(parked.state())
                .as("a park does not move the run's recorded state — it stays resumable at PLAN")
                .isEqualTo(RunState.PLAN);
            assertThat(parked.parkReason())
                .contains("zerozstack-store-eclipsestore")
                .contains(m2.resolve("repository").toString())
                .contains("NO network")
                .contains("Storage is an object graph, not a database");

            List<Decision> pending = store.root().decisions.values().stream()
                .filter(d -> d.state() == DecisionState.PENDING
                    && d.kind() == DecisionKind.BLOCKED_TASK && runId.equals(d.runId()))
                .toList();
            assertThat(pending)
                .as("the same BLOCKED_TASK decision every other PLAN-stage park raises")
                .hasSize(1);
            assertThat(pending.get(0).briefMarkdown()).isEqualTo(parked.parkReason());
        }
    }

    // ------------------------------------------------------------------ nothing else changed

    @Test
    void whenTheBuildAlreadyDeclaresWhatTheRuleNamesNothingIsAddedAndNothingStops()
            throws Exception {
        useMavenRepository(m2);
        writeReactorDeclaringEclipseStore();
        writeBom();
        install("com.zeroz4j", "zerozstack-store-eclipsestore", "0.8.0-SNAPSHOT");
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = workingPlanner();
             ArtifactStore store = new ArtifactStore(storeDir)) {
            advance(engine(llm, store, run -> run), runId);

            awaitState(store, runId, RunState.DELIVERED);
            Run finished = store.root().runs.get(runId);
            assertThat(finished.parkedAt()).isNull();
            assertThat(store.root().taskGraphs.get(finished.taskGraphId()).tasks())
                .allSatisfy(task ->
                    assertThat(task.instructions()).doesNotContain("Also declare"));
        }
    }

    /**
     * No local Maven repository on this machine at all. Nothing is offered, nothing is declared,
     * and — the point — nothing is parked either: a missing repository is a fact about the machine
     * and must never be read as a contradiction in the project.
     */
    @Test
    void noLocalMavenRepositoryMeansNoOfferAndNoPark() throws Exception {
        useMavenRepository(m2.resolve("nothing-here"));
        writeReactorMissingEclipseStore();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = workingPlanner();
             ArtifactStore store = new ArtifactStore(storeDir)) {
            advance(engine(llm, store, run -> run), runId);

            awaitState(store, runId, RunState.DELIVERED);
            assertThat(store.root().runs.get(runId).parkedAt()).isNull();
        }
    }

    /** What is consulted must be what the sandbox mounts; one property, two readers. */
    @Test
    void theRepositoryConsultedIsTheOneTheSandboxMounts() {
        useMavenRepository(m2);
        assertThat(DeclarableArtifacts.defaultLocalRepository())
            .isEqualTo(m2.resolve("repository"));
    }

    // ------------------------------------------------------------------------------- fixtures

    private WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store, UnaryOperator<Run> swarm) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        WorkflowEngine engine = new WorkflowEngine(unusedRuntime, swarm::apply,
            new VllmClient(llm.baseUrl(), "", "test-model", true),
            store, new CloudGate(0, null), repo);
        engine.setProjectRules(() -> RULE_NAMING_ECLIPSESTORE);
        return engine;
    }

    private static void advance(WorkflowEngine engine, UUID runId) {
        engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
            null, null, null, null, null, Instant.now(), new RunReport(runId, "demo goal")));
    }

    /** A one-module Maven reactor whose server module does NOT declare EclipseStore. */
    private void writeReactorMissingEclipseStore() throws Exception {
        writeRootPom();
        writeServerPom("");
    }

    /** The same reactor, this time WITH the dependency the rule names. */
    private void writeReactorDeclaringEclipseStore() throws Exception {
        writeRootPom();
        writeServerPom("""
                <dependency>
                  <groupId>com.zeroz4j</groupId>
                  <artifactId>zerozstack-store-eclipsestore</artifactId>
                </dependency>
            """);
    }

    private void writeServerPom(String extraDependencies) throws Exception {
        Path server = repo.resolve("server");
        Files.createDirectories(server.resolve("src/main/java"));
        Files.writeString(server.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>server</artifactId>
              <dependencies>
            %s    <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """.formatted(extraDependencies));
    }

    private void writeRootPom() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <zeroz4j.version>0.8.0-SNAPSHOT</zeroz4j.version>
              </properties>
              <modules>
                <module>server</module>
              </modules>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.zeroz4j</groupId>
                    <artifactId>zerozstack-bom</artifactId>
                    <version>${zeroz4j.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    }

    /** The BOM in the fake local repository: it pins the version, so nothing has to invent one. */
    private void writeBom() throws Exception {
        Path dir = Files.createDirectories(
            m2.resolve("repository/com/zeroz4j/zerozstack-bom/0.8.0-SNAPSHOT"));
        Files.writeString(dir.resolve("zerozstack-bom-0.8.0-SNAPSHOT.pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.zeroz4j</groupId>
              <artifactId>zerozstack-bom</artifactId>
              <version>0.8.0-SNAPSHOT</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.zeroz4j</groupId>
                    <artifactId>zerozstack-store-eclipsestore</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    }

    /** Makes {@code repo} a real git repository with a verify.yaml, so a real swarm can run at it. */
    private void gitInitWithVerify(String compileCommand) throws Exception {
        runGit("git init -q");
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"), """
            toolchain: maven
            compile:
              - "%s"
            timeoutSeconds: 60
            """.formatted(compileCommand));
        runGit("git add -A");
        runGit("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    /** Dispatches {@code graph}'s one task through the real {@link SwarmEngineImpl}. */
    private CandidateSolution runSwarm(FakeVllm fake, TaskGraph graph) throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                return null;
            }).get();

            ModelProfileRegistry profiles = new ModelProfileRegistry(
                List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0)));

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true),
                store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
                new KoogAgentRuntime(new TraceHub(null)),
                profiles,
                new GitService(repo),
                content -> null,
                new CloudGate(1_000_000, null));

            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graph.id(), null, Instant.now(), null);
            engine.executeRun(run);

            return store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .filter(c -> c.state() == CandidateState.SELECTED)
                .findFirst().orElse(null);
        }
    }

    private void runGit(String command) throws Exception {
        Process p = new ProcessBuilder(gitCommand(command))
            .directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }

    private static List<String> gitCommand(String command) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? List.of("cmd.exe", "/c", command)
            : List.of("sh", "-c", command);
    }

    private void install(String groupId, String artifactId, String version) throws Exception {
        Path dir = Files.createDirectories(m2.resolve("repository")
            .resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".jar"), "jar");
    }

    private static void awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }

    private static void awaitState(ArtifactStore store, UUID runId, RunState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.state() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("Run never reached " + expected + "; last persisted state: "
            + (last == null ? "never persisted" : last.state())
            + (last == null || last.parkReason() == null ? "" : "; parked: " + last.parkReason()));
    }
}
