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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CarriedWarning;
import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.OpinionPolicy;
import com.swarmcoder.domain.Run;
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
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <b>Before any worker runs, a run looks once at the tree it starts from</b> (owner decisions
 * after the audit of 2026-10-02).
 *
 * <p>Two things that are true of that tree used to be blamed on every candidate: a house rule
 * whose check command cannot run at all, and a test of the project's own that already fails. The
 * repository here has both - a rule that calls a tool that does not exist, and a saved test
 * report with one failing test that the existing-tests stage restores on every tree. Attended,
 * the run parks before starting anything and names the command; unattended, the check is skipped
 * with a warning, the old failure is carried as a warning, and the candidate survives.
 */
@ModelCodeOnThisPc
class ARunLooksAtItsStartTreeFirstTest {

    private static final String BOOK = "src/main/java/com/x/Book.java";
    private static final String NO_SUCH_TOOL = "swarmcoder-no-such-tool-4711 --check src";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;

    @Test
    void attendedARuleCheckThatCannotRunParksTheRunBeforeAnyWorkerStarts() throws Exception {
        Outcome outcome = run(OpinionPolicy.ASK_THE_OPERATOR);

        assertThat(outcome.thrown).isInstanceOf(RunMustPark.class);
        assertThat(((RunMustPark) outcome.thrown).brief())
            .contains("no-such-tool-rule").contains(NO_SUCH_TOOL).contains("Why it cannot run");
        assertThat(outcome.candidates).as("nothing was dispatched").isEmpty();
    }

    @Test
    void unattendedTheCheckIsSkippedAndAnAlreadyFailingTestFailsNobody() throws Exception {
        Outcome outcome = run(OpinionPolicy.WARN_AND_CARRY_ON);

        assertThat(outcome.thrown).isNull();
        assertThat(outcome.candidates).singleElement()
            .extracting(CandidateSolution::state)
            .as("neither the unrunnable check nor the old failing test is held against it")
            .isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
        assertThat(outcome.run.baselineFailingTests()).containsExactly("com.x.OldTest#broken");
        assertThat(outcome.run.carriedWarnings()).extracting(CarriedWarning::oneLine)
            .anySatisfy(line -> assertThat(line).contains("no-such-tool-rule")
                .contains(NO_SUCH_TOOL).contains("skipped for this run"))
            .anySatisfy(line -> assertThat(line).contains("com.x.OldTest#broken")
                .contains("not held against any candidate"));
    }

    @Test
    void theBaselineIsMeasuredAgainOnlyWhenAWinnerChangedOneOfItsTests() {
        List<String> failing = List.of("com.x.OldTest#broken", "com.x.Outer$Inner#nested");
        assertThat(SwarmEngineImpl.touchesABaselineTest(failing, List.of(winner(
            "diff --git a/src/test/java/com/x/OldTest.java b/src/test/java/com/x/OldTest.java\n"
                + "--- a/src/test/java/com/x/OldTest.java\n"
                + "+++ b/src/test/java/com/x/OldTest.java\n@@ -1 +1 @@\n-a\n+b\n")))).isTrue();
        assertThat(SwarmEngineImpl.touchesABaselineTest(failing, List.of(winner(
            "diff --git a/src/main/java/com/x/Book.java b/src/main/java/com/x/Book.java\n"
                + "--- a/src/main/java/com/x/Book.java\n"
                + "+++ b/src/main/java/com/x/Book.java\n@@ -1 +1 @@\n-a\n+b\n")))).isFalse();
        assertThat(SwarmEngineImpl.touchesABaselineTest(List.of(), List.of())).isFalse();
    }

    private static WaveIntegrator.Winner winner(String diff) {
        return new WaveIntegrator.Winner(null, new CandidateSolution(UUID.randomUUID(),
            UUID.randomUUID(), 0, "b", null, diff, null, null, null, CandidateState.SELECTED, null));
    }

    private record Outcome(Throwable thrown, List<CandidateSolution> candidates, Run run) {}

    private Outcome run(OpinionPolicy policy) throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo();
        Task task = new Task(UUID.randomUUID(), 1, "Deliver the Book type", "Create " + BOOK + ".",
            Set.of("src/main/java/com/x"), Set.of(), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());

        try (FakeVllm fake = new FakeVllm(ARunLooksAtItsStartTreeFirstTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().runs.put(runId, run);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024), new KoogAgentRuntime(),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));
            engine.setGuidelineChecks(() -> List.of(new GuidelineCheck("no-such-tool-rule",
                "PROJECT", "The style tool must pass.", NO_SUCH_TOOL, 30)));
            engine.setOpinionPolicy(policy);
            Throwable thrown = catchThrowable(() -> engine.executeRun(run));
            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
            return new Outcome(thrown, archived, run);
        }
    }

    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"Book delivered\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", BOOK)
            .put("content", "package com.x;\npublic class Book {\n    public String title;\n}\n")
            .toString());
    }

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main/java/com/x"));
        Files.writeString(repoDir.resolve("src/main/java/com/x/.keep"), "");
        // A saved report with one test that fails: the existing-tests stage restores it on every
        // tree, so it "fails" on the start tree and on every candidate alike.
        Files.createDirectories(repoDir.resolve("test-results"));
        Files.writeString(repoDir.resolve("test-results/old.xml"), """
            <testsuite name="s" tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="com.x.OldTest" name="fine"/>
              <testcase classname="com.x.OldTest" name="broken">
                <failure message="was always wrong">trace</failure>
              </testcase>
            </testsuite>
            """);
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: node
            compile:
              - "echo compile-ok"
            existing:
              - "git checkout -- test-results"
            timeoutSeconds: 60
            """);
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();
    }

    private String git(String args) throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", "git " + args) : List.of("sh", "-c", "git " + args);
        Process p = new ProcessBuilder(command).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
