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
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CriterionStatus;
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
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A finished build hands its story back for judgment, on its own (UX v3 §2.3).
 *
 * <p>This path had no test, which is how it came to be structurally impossible. The delivery check
 * reads the winning candidate's verification report out of {@code candidateArchives} — and it was
 * looking the archive up by TASK id expecting a list, while the map is keyed by CANDIDATE id holding
 * one candidate each. So the report was always null, every criterion was therefore UNKNOWN rather
 * than PASSED, {@code allDelivered} could never return true, and every single build sent its story to
 * BLOCKED. No test noticed because the only coverage of "story reaches REVIEW" set the state by hand.
 *
 * <p>The two outcomes below are the whole of the second human gate: either the story comes back to be
 * judged, or it stopped and says why. There is no third state where a build waits at a gate of its
 * own with no button to open it.
 */
class DeliveryHandbackTest {

    @TempDir
    Path storeDir;
    @TempDir
    Path scriptDir;

    private ArtifactStore store;
    private ScriptedLlm llm;
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        store = new ArtifactStore(storeDir);
        llm = new ScriptedLlm(conversation -> "not used at this stage");
    }

    @AfterEach
    void close() throws Exception {
        if (llm != null) {
            llm.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void aBuildWhoseChecksPassedComesBackForJudgment() throws Exception {
        Fixture fixture = fixture(passing());

        advanceFromIntegration(fixture);

        Story story = store.getStory(fixture.storyId);
        assertThat(story.state())
            .as("REVIEW is the second human gate: 'the tests pass' and 'this is what I asked for' "
                + "are different claims, and only the operator can make the second")
            .isEqualTo(StoryState.REVIEW);

        Run run = store.root().runs.get(fixture.runId);
        assertThat(run.state())
            .as("the build is finished — it does not also park at a gate of its own")
            .isEqualTo(RunState.DELIVERED);

        // The archive lookup really ran: this is the durable requirement→code link, and it was dead
        // for as long as the lookup was wrong.
        Task task = store.root().taskGraphs.get(fixture.graphId).tasks().get(0);
        assertThat(task.selectedCandidateId())
            .as("the winning candidate must be recorded against its task")
            .isEqualTo(fixture.candidateId);
    }

    @Test
    void aBuildWhoseChecksFailedStopsAndSaysSo() throws Exception {
        Fixture fixture = fixture(failing());

        advanceFromIntegration(fixture);

        Story story = store.getStory(fixture.storyId);
        assertThat(story.state())
            .as("a criterion that failed is a genuine stop, not something to judge")
            .isEqualTo(StoryState.BLOCKED);
        assertThat(store.changeHistory(projectId, fixture.storyId))
            .as("a state change with no recorded reason looks like it happened by itself")
            .isNotEmpty();

        Run run = store.root().runs.get(fixture.runId);
        assertThat(run.state())
            .as("the build ended without delivering; the story carries the reason")
            .isEqualTo(RunState.ABORTED);
    }

    /**
     * Runs EXECUTING → FINAL_INTEGRATION and waits for the run to finish.
     *
     * <p>Git is disabled, so integration is a no-op that reports success and the delivery check is
     * what actually decides the outcome — which is the part under test.
     *
     * <p>It starts at EXECUTING so the run crosses a real stage transition before the delivery check,
     * rather than being handed straight to it. The engine's own duty to return a run that still knows
     * its story is pinned where it lives, in {@code SwarmEngineFakeVllmTest}.
     */
    private void advanceFromIntegration(Fixture fixture) throws Exception {
        AgentRuntime unused = spec -> {
            throw new UnsupportedOperationException("no agents at this stage");
        };
        WorkflowEngine engine = new WorkflowEngine(unused, run -> run,
            new VllmClient(llm.baseUrl(), "", "test-model", true), store);

        Run run = new Run(fixture.runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
            projectId, fixture.storyId, null, fixture.graphId, null, Instant.now(),
            new RunReport(fixture.runId, "deliver S1"));
        engine.advance(run);

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run persisted = store.root().runs.get(fixture.runId);
            if (persisted != null && (persisted.state() == RunState.DELIVERED
                    || persisted.state() == RunState.ABORTED)) {
                return;
            }
            Thread.sleep(50);
        }
        Run last = store.root().runs.get(fixture.runId);
        throw new AssertionError("run never finished; last state: "
            + (last == null ? "never persisted" : last.state()));
    }

    /**
     * The named acceptance test ran and passed, so the criterion claiming it is satisfied.
     *
     * <p>The id has to be here. This fixture used to report "1 passed" and no ids, which said only
     * that SOMETHING ran green — and the run came back for judgment on the strength of it, without
     * the criterion's own test existing anywhere.
     */
    private static TestResults passing() {
        return new TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.MultiplyAcceptTest#multiplies"), List.of(), false);
    }

    /** The named acceptance test failed — evidence AGAINST the criterion, not absence of evidence. */
    private static TestResults failing() {
        return new TestResults(0, 1, 0, 0, List.of(
            new TestFailure("swarm.accept.MultiplyAcceptTest#multiplies", "expected 6 but was 5", "")));
    }

    private record Fixture(UUID storyId, UUID graphId, UUID runId, UUID candidateId) {}

    /**
     * One requirement, one ACCEPTED criterion naming a test, a story claiming it, a one-task graph,
     * and an archived SELECTED candidate carrying the given acceptance results.
     */
    private Fixture fixture(TestResults acceptance) throws Exception {
        Project project = new Project();
        project.setId(projectId);
        project.setName("demo");
        project.setCreatedAt(Instant.now());
        store.saveProject(project);

        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "multiplying two numbers works", "swarm.accept.MultiplyAcceptTest#multiplies");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R1", "Multiply",
            "The calculator can multiply", Priority.HIGH, RequirementStatus.ACTIVE, null);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        store.saveBrd(new Brd(UUID.randomUUID(), projectId, 1, "Business Requirements",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now()),
            "test", "fixture");

        Story story = new Story(UUID.randomUUID(), projectId, "S1", StoryKind.DELIVERY,
            "Multiply", null, StoryState.RUNNING, new ArrayList<>(List.of(requirement.id())),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        store.saveStory(story);

        UUID graphId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 1, "Add multiply", "implement it",
            Set.of("src"), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.SELECTED);
        TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());

        UUID candidateId = UUID.randomUUID();
        CandidateSolution winner = new CandidateSolution(candidateId, task.id(), 0,
            "swarm/" + task.id() + "/0", null, "a diff",
            new VerificationReport(UUID.randomUUID(), true, true, acceptance, null, null, null,
                null, "log", null),
            null, null, CandidateState.SELECTED, null);

        store.append(() -> {
            store.root().taskGraphs.put(graphId, graph);
            // Keyed by CANDIDATE id, holding one candidate — the real shape, which is what the
            // delivery check was failing to read.
            store.root().candidateArchives.put(candidateId, Lazy.Reference(winner));
            return null;
        }).get();

        return new Fixture(story.id(), graphId, UUID.randomUUID(), candidateId);
    }
}
