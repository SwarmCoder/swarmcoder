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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.ContractInUse;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A project made over an empty folder has its verification contract before its first
 * acceptance test is written, and a run that owes proof and can get none waits there</b>
 * (section 107, live local build of 2026-10-14).
 *
 * <p>That build ran from its plan to its final integration with no contract: nothing in the
 * folder said how the project is built, and nothing asked. The repository here holds one empty
 * commit and the run stands at TEST_AUTHORING with a stored plan, as it does the moment its
 * plan is accepted. No model is used: what is asserted is what the product does before it
 * would ask one.
 */
@ModelCodeOnThisPc
class ARunOwingProofHasItsContractBeforeAnythingIsBuiltTest {

    private static final String CHECK = "A book can be added to the shelf";

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private UUID runId;
    private UUID graphId;
    private Project project;
    private Brd brd;
    private Story story;

    @Test
    void aPlanThatNamesNoBuildFileStopsTheRunBeforeATestIsWrittenOrACandidateStarted()
            throws Exception {
        Run run = emptyProjectAtTestAuthoring();
        Task task = task("Write the shelf", Set.of("src/main/java/com/x"), true);
        AtomicInteger modelCalls = new AtomicInteger();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                 modelCalls.incrementAndGet();
                 return "not used";
             });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            store(store, new TaskGraph(graphId, 1, null, List.of(task), List.of()));
            engine(llm, store).advance(run);
            awaitStopped(store);

            Run stopped = store.root().runs.get(runId);
            assertThat(stopped.state()).as("it waits at the stage, to go on from it")
                .isEqualTo(RunState.TEST_AUTHORING);
            assertThat(stopped.parkReason()).contains("NOTHING CAN BE VERIFIED")
                .contains("There is no verification contract")
                .contains(VerifySpecLoader.SPEC_PATH).contains("1 check(s) are claimed")
                .contains("build-contract dialog");
            assertThat(stopped.verificationContract()).contains("NONE");
            assertThat(modelCalls).as("no test author and no worker was asked").hasValue(0);
            assertThat(repo.resolve(VerifySpecLoader.SPEC_PATH)).doesNotExist();
        }
    }

    @Test
    void aPlanThatNamesMavenModulesGivesTheEmptyProjectItsContractAtTheStartOfTestAuthoring()
            throws Exception {
        Run run = emptyProjectAtTestAuthoring();
        Task task = task("Create the build and the shelf",
            Set.of("pom.xml", "shelf-core/pom.xml", "shelf-app/pom.xml",
                "shelf-core/src/main/java"), true);

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "not used");
             ArtifactStore store = new ArtifactStore(storeDir)) {
            store(store, new TaskGraph(graphId, 1, null, List.of(task), List.of()));
            java.util.concurrent.Future<?> moving = engine(llm, store).advanceAsync(run);
            awaitContractOnTheRun(store);
            try {
                moving.get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                // what the stage does after the contract needs a test author; none is here
            }

            ContractInUse contract = VerifySpecLoader.inUse(repo, null);
            assertThat(contract.origin()).isEqualTo(ContractInUse.GENERATED);
            assertThat(contract.basis()).contains("the plan names").contains("shelf-core/pom.xml");
            assertThat(contract.spec().compile()).containsExactly("mvn -o -B -q test-compile");
            Run now = store.root().runs.get(runId);
            assertThat(now.verificationContract())
                .as("which contract, and where it came from, is on the run's record")
                .contains("generated by SwarmCoder").contains("the plan names")
                .contains("mvn -o -B -q test-compile");
            assertThat(String.valueOf(now.parkReason()))
                .as("it did not stop for want of a contract")
                .doesNotContain("NOTHING CAN BE VERIFIED");
        }
    }

    @Test
    void acceptanceTestsOfAPlannedReactorGoInTheModuleThePlanBuildsLast() {
        Task build = task("Create the build", Set.of("pom.xml"), false);
        Task core = task("Write the core", Set.of("shelf-core/pom.xml",
            "shelf-core/src/main/java"), false);
        Task app = task("Write the application", Set.of("shelf-app/pom.xml",
            "shelf-app/src/main/java"), true);
        TaskGraph plan = new TaskGraph(UUID.randomUUID(), 1, null, List.of(build, core, app),
            List.of(new TaskEdge(build.id(), core.id()), new TaskEdge(core.id(), app.id())));
        BuildLayout.Layout nothingToRead = BuildLayout.read((Path) null, null);

        AcceptanceTestLocation.Location place =
            ContractFromTheStart.acceptanceLocation(nothingToRead, plan);

        assertThat(place.protectedDir())
            .as("not the repository root: an aggregator compiles nothing")
            .isEqualTo("shelf-app/src/test/java/swarm");
        assertThat(place.seesEveryModule()).isTrue();
        assertThat(ContractFromTheStart.plannedBuildFiles(plan))
            .containsExactlyInAnyOrder("pom.xml", "shelf-core/pom.xml", "shelf-app/pom.xml");

        TaskGraph single = new TaskGraph(UUID.randomUUID(), 1, null, List.of(build), List.of());
        assertThat(ContractFromTheStart.acceptanceLocation(nothingToRead, single).protectedDir())
            .isEqualTo("src/test/java/swarm");
        TaskGraph noBuildFile = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(task("Write a class", Set.of("src/main/java"), false)), List.of());
        assertThat(ContractFromTheStart.acceptanceLocation(nothingToRead, noBuildFile)
            .protectedDir()).as("nothing says otherwise: the conventional place")
            .isEqualTo("src/test/java/swarm");
    }

    // --- the fixture ----------------------------------------------------------------------------

    private WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("no agents in this test");
        };
        return new WorkflowEngine(unusedRuntime, r -> r,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store,
            new CloudGate(0, null), repo, new CloudRoles(null, null, null), new GitService(repo));
    }

    private static Task task(String title, Set<String> writeSet, boolean claimsTheCheck) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(),
            claimsTheCheck ? List.of(new AcceptanceCriterion(UUID.randomUUID(), CHECK,
                "swarm.accept.ShelfTest#adds")) : List.of(),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());
        return task;
    }

    /** One story with one check, and a run at TEST_AUTHORING over a repository with nothing in it. */
    private Run emptyProjectAtTestAuthoring() throws Exception {
        runId = UUID.randomUUID();
        graphId = UUID.randomUUID();
        git("init -q");
        git("-c user.email=t@t -c user.name=t commit -q --allow-empty -m empty");
        String base = git("rev-parse HEAD").strip();

        project = new Project();
        UUID projectId = UUID.randomUUID();
        project.setId(projectId);
        project.setName("shelf");
        project.setCreatedAt(Instant.now());

        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), CHECK,
            "swarm.accept.ShelfTest#adds");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "The shelf",
            "A reader keeps books on a shelf", Priority.HIGH, RequirementStatus.ACTIVE, null);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        brd = new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());

        UUID storyId = UUID.randomUUID();
        story = new Story(storyId, projectId, "S1", StoryKind.DELIVERY, "Keep books", null,
            StoryState.RUNNING, new ArrayList<>(List.of(requirement.id())),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.TEST_AUTHORING, projectId,
            storyId, null, graphId, null, Instant.now(), new RunReport(runId, "deliver S1"));
        run.setBaseCommit(base);
        return run;
    }

    private void store(ArtifactStore store, TaskGraph graph) throws Exception {
        store.saveProject(project);
        store.saveBrd(brd, "test", "fixture");
        store.saveStory(story);
        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            return null;
        }).get();
    }

    private void awaitContractOnTheRun(ArtifactStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.verificationContract() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no contract was recorded on the run");
    }

    /** The run has stopped with a question or has ended. */
    private void awaitStopped(ArtifactStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            boolean ended = run != null
                && (run.state() == RunState.DELIVERED || run.state() == RunState.ABORTED);
            boolean asked = run != null && run.parkedAt() != null
                && store.root().decisions.values().stream().anyMatch(d ->
                    runId.equals(d.runId()) && d.state() == DecisionState.PENDING);
            if (ended || asked) {
                Thread.sleep(500); // the save that follows the question
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the run neither stopped nor ended");
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
