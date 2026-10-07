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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;
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
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A worker that was stopped with its change complete is verified, and can win</b> (owner
 * decision after the audit of 2026-10-02).
 *
 * <p>A worker stopped at its turn cap, for making no progress, out of room, or because its place
 * was needed was never verified, whatever it had written. Here one scripted worker writes the
 * whole change in its first turn and then only reads until its turns run out. Its change is
 * verified like any other: the one that delivers what the task promised is selected, the one
 * that does not stays stopped for the reason it was stopped, with the verdict beside it.
 */
@ModelCodeOnThisPc
class AStoppedWorkersFinishedWorkIsVerifiedTest {

    private static final String BOOK = "src/main/java/com/x/Book.java";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;

    @AfterEach
    void meterOff() {
        RunMeter.disable();
        RunMeter.reset();
    }

    @Test
    void aStoppedWorkerWhoseChangeIsCompleteSurvivesAndIsSelected() throws Exception {
        RunMeter.reset();
        RunMeter.enable();
        CandidateSolution only = runOneWorker(true);

        assertThat(only.state())
            .as("stopped at its turn cap with the whole change written")
            .isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
        assertThat(only.killReason()).isNull();
        assertThat(only.verification()).as("it survived on a verdict, not for want of one")
            .isNotNull();
        assertThat(RunMeter.spans()).extracting(RunMeter.Span::name)
            .filteredOn(name -> name.startsWith(StoppedWork.SPAN))
            .singleElement().asString().endsWith("|0|survived");
    }

    @Test
    void aStoppedWorkerWhoseChangeFallsShortStaysStopped() throws Exception {
        RunMeter.reset();
        RunMeter.enable();
        CandidateSolution only = runOneWorker(false);

        assertThat(only.state()).isEqualTo(CandidateState.KILLED);
        assertThat(only.killReason()).as("the reason it was stopped for is kept").isNotNull()
            .isNotEqualTo(KillReason.SUPERSEDED);
        assertThat(only.verification()).as("and what verification said is beside it").isNotNull();
        assertThat(only.verification().logTail())
            .contains("the contract `com.x.Book` was delivered without the member int rating");
        assertThat(RunMeter.spans()).extracting(RunMeter.Span::name)
            .filteredOn(name -> name.startsWith(StoppedWork.SPAN))
            .singleElement().asString().endsWith("|0|failed");
    }

    @Test
    void whatIsNotWorthASecondVerdict() {
        String diff = "diff --git a/x b/x\n+x\n";
        assertThat(StoppedWork.worthVerifying(KillReason.TURN_CAP, diff)).isTrue();
        assertThat(StoppedWork.worthVerifying(KillReason.NO_PROGRESS, diff)).isTrue();
        assertThat(StoppedWork.worthVerifying(KillReason.BUDGET_EXCEEDED, diff)).isTrue();
        assertThat(StoppedWork.worthVerifying(KillReason.PLACE_NEEDED, diff)).isTrue();
        assertThat(StoppedWork.worthVerifying(KillReason.TURN_CAP, " ")).as("no change").isFalse();
        assertThat(StoppedWork.worthVerifying(KillReason.SUPERSEDED, diff))
            .as("its task already has a candidate that passed").isFalse();
        assertThat(StoppedWork.worthVerifying(KillReason.WRITESET_VIOLATION, diff)).isFalse();
    }

    private CandidateSolution runOneWorker(boolean complete) throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo();
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf",
            "Book", "com.x.Book", List.of("int rating"));
        Task task = new Task(UUID.randomUUID(), 1, "Deliver the shared Book type",
            "Create " + BOOK + ".", Set.of("src/main/java/com/x"), Set.of(), List.of(),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 3),
            new SwarmPolicy(1, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());
        task.setDeliveredContracts(List.of(book));

        try (FakeVllm fake = new FakeVllm(conversation -> route(conversation, complete));
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
            try {
                engine.executeRun(run);
            } catch (RuntimeException e) {
                // A task with nothing that passed ends its run; what is asserted is the candidate.
            }
            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .filter(c -> c.workerIndex() == 0).toList();
            assertThat(archived).hasSize(1);
            return archived.get(0);
        }
    }

    /** Writes the whole change at once, then reads until it is stopped; never reports done. */
    private static FakeVllm.Reply route(String conversation, boolean complete) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("read", new ObjectMapper().createObjectNode()
                .put("path", BOOK).toString());
        }
        String content = complete
            ? "package com.x;\npublic class Book {\n    public String title;\n    public int rating;\n}\n"
            : "package com.x;\npublic class Book {\n    public String title;\n}\n";
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", BOOK).put("content", content).toString());
    }

    private void initRepo() throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main/java/com/x"));
        Files.writeString(repoDir.resolve("src/main/java/com/x/.keep"), "");
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
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
