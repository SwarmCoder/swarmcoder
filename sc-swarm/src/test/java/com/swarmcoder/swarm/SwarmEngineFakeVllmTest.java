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

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.*;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.TraceHub;

/**
 * The spec §20 deterministic swarm test: the REAL stack — KoogAgentRuntime over HTTP, worker
 * loop, write-set enforcement, verification, clustering, judging, selection, archival —
 * against a scripted {@link FakeVllm}. No Spark, no Docker, no network beyond localhost.
 */
@ModelCodeOnThisPc
class SwarmEngineFakeVllmTest {

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private static final String PATCH = """
        diff --git a/README.md b/README.md
        --- a/README.md
        +++ b/README.md
        @@ -1 +1,2 @@
         hello
        +improved by worker
        """;

    private void initRepo() throws Exception {
        initRepo("echo compile-ok");
    }

    private void initRepo(String compileCommand) throws Exception {
        run("git init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "%s"
            timeoutSeconds: 60
            """.formatted(compileCommand));
        run("git add -A");
        run("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"clean minimal change\"}");
        }
        if (conversation.contains("applied cleanly")) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added line to README\"}");
        }
        return FakeVllm.Reply.toolCall("apply_diff",
            new ObjectMapper().createObjectNode()
                .put("unifiedDiff", PATCH).toString());
    }

    /**
     * {@code route}, with every worker's last request held until two are in together - so the
     * two candidates of a task end their sessions in the same instant and both are verified.
     *
     * <p>Needed since 2026-10-02: a task with a candidate through verification no longer waits
     * for a second one that is still working (see {@code PlacesGoToDifferentWorkFirstTest}), so
     * a test about what happens with TWO verified candidates has to make sure there are two.
     */
    static java.util.function.Function<String, FakeVllm.Reply> finishingInPairs(
            java.util.function.Function<String, FakeVllm.Reply> route) {
        java.util.concurrent.CyclicBarrier pair = new java.util.concurrent.CyclicBarrier(2);
        return conversation -> {
            if (conversation.contains("applied cleanly")
                    && !conversation.contains("code-review judge")) {
                try {
                    pair.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception alone) {
                    // nobody to pair with: carry on
                }
            }
            return route.apply(conversation);
        };
    }

    private Task task(Set<String> writeSet, int n) {
        return new Task(UUID.randomUUID(), 1, "Improve README", "Add a line to README.md",
            writeSet, Set.of(), List.of(), null, null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(n, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private CandidateSolution runEngine(FakeVllm fake, Task task, int expectedArchived) throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                return null;
            }).get();

            ModelProfileRegistry profiles = new ModelProfileRegistry(
                List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0)));

            // Observability wired exactly as in the app: session traces land in the store.
            TraceHub traceHub = new TraceHub(null);
            traceHub.addListener(new TraceHub.Listener() {
                @Override
                public void sessionEnded(AgentSessionRecord complete) {
                    store.append(() -> {
                        store.root().agentSessions().put(complete.id(), Lazy.Reference(complete));
                        return null;
                    });
                }
            });

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true),
                store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
                new KoogAgentRuntime(traceHub),
                profiles,
                new GitService(repoDir),
                content -> null,
                new CloudGate(1_000_000, null));

            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            engine.executeRun(run);

            List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                .toList();
            assertThat(archived).hasSize(expectedArchived);
            return archived.stream()
                .filter(c -> c.state() == CandidateState.SELECTED)
                .findFirst().orElse(null);
        }
    }

    @Test
    void fullPipelineSelectsVerifiedJudgedWinner() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(finishingInPairs(SwarmEngineFakeVllmTest::route))) {
            CandidateSolution winner = runEngine(fake, task(Set.of("README.md"), 2), 2);

            assertThat(winner).as("a winner must be selected").isNotNull();
            assertThat(winner.diffUnified()).contains("improved by worker");
            assertThat(winner.verification()).isNotNull();
            assertThat(winner.verification().compiles()).isTrue();
            assertThat(winner.judge().score()).isEqualTo(0.9);
            assertThat(winner.judge().judgeModelId()).isEqualTo("fake-judge");

            // The winning branch really exists in the repo with the committed change.
            Process p = new ProcessBuilder(gitCmd("git branch --list swarm/*"))
                .directory(repoDir.toFile()).redirectErrorStream(true).start();
            String branches = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            assertThat(branches).contains("swarm/" + winner.taskId());
        }

        // Observability: complete session traces survive a store reopen — every prompt,
        // response, tool call and result of both workers is available for post-analysis.
        try (ArtifactStore reopened = new ArtifactStore(storeDir)) {
            List<AgentSessionRecord> sessions =
                reopened.root().agentSessions().values().stream()
                    .map(lazy -> (AgentSessionRecord) Lazy.get(lazy))
                    .toList();
            assertThat(sessions).hasSize(2);
            AgentSessionRecord session = sessions.get(0);
            assertThat(session.outcome()).isEqualTo("COMPLETED");
            assertThat(session.taskId()).isNotNull();
            assertThat(session.events())
                .extracting(TraceEvent::kind)
                .contains(TraceEventKind.SESSION_OPENED,
                    TraceEventKind.TOOL_CALL,
                    TraceEventKind.TOOL_RESULT,
                    TraceEventKind.DONE,
                    TraceEventKind.SESSION_CLOSED);
            assertThat(session.events().stream()
                .filter(e -> e.kind() == TraceEventKind.TOOL_CALL)
                .map(TraceEvent::label))
                .contains("apply_diff");
        }
    }

    /**
     * The whole point of the 2026-09-02 change, proved end to end: a worker that writes outside its
     * write set FINISHES, is verified, is judged, is selected, and the file it reached is written
     * on the archived candidate for the operator to read.
     *
     * <p>This test used to assert the opposite - that the second such write killed the candidate -
     * and on the operator's live run that rule killed three of thirteen candidates, none of them
     * misbehaving. Two were re-creating a model class the parallel task in the same wave had not
     * delivered yet; one died at turn 102 because the EclipseStore data file its own test run had
     * written counted as violation number two. The write set is decided by the architect from a
     * plan before any code exists, so being outside it is evidence to weigh, not proof of anything.
     *
     * <p>The record surviving all the way to the archive is half of what is being tested here.
     * Clustering, verification, judging and selection each rebuild the candidate, and each would
     * silently drop a field it does not name - which is what {@code carryingAuditFrom} exists to
     * stop.
     */
    @Test
    void aWorkerThatWritesOutsideItsWriteSetSurvivesWithTheFilesRecorded() throws Exception {
        initRepo();
        // Write set is "docs"; the scripted worker patches README.md, which is not in it.
        try (FakeVllm fake = new FakeVllm(SwarmEngineFakeVllmTest::route)) {
            CandidateSolution winner = runEngine(fake, task(Set.of("docs"), 1), 1);

            assertThat(winner).as("the candidate must survive being outside its write set")
                .isNotNull();
            assertThat(winner.state()).isEqualTo(CandidateState.SELECTED);
            assertThat(winner.killReason()).isNull();
            assertThat(winner.diffUnified()).contains("improved by worker");
            assertThat(winner.outOfWriteSetPaths()).containsExactly("README.md");

            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                    .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                    .toList();
                assertThat(archived).hasSize(1);
                assertThat(archived.get(0).outOfWriteSetPaths()).containsExactly("README.md");
            }
        }
    }

    /**
     * What is still lethal, proved the same way: writing the verification contract.
     *
     * <p>{@code .swarmcoder/verify.yaml} holds the commands the orchestrator runs ON THE HOST,
     * unsandboxed, to decide whether this candidate passed. A worker able to write it can certify
     * itself green and run arbitrary code on the operator's machine, so it is refused, never
     * written, and the second attempt still kills. The task's acceptance-test directory, an
     * operator-locked module, {@code .git/} and anything outside the repository are the same case
     * and are covered by {@code PathPolicyTest}.
     */
    @Test
    void writingTheVerificationContractStillKillsTheCandidate() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(conversation -> {
            if (conversation.contains("code-review judge")) {
                return FakeVllm.Reply.text("{\"score\": 0.1, \"rationale\": \"n/a\"}");
            }
            return FakeVllm.Reply.toolCall("write_file",
                new ObjectMapper().createObjectNode()
                    .put("path", ".swarmcoder/verify.yaml")
                    .put("content", "compile:\n  - \"echo green\"\n").toString());
        })) {
            CandidateSolution winner = runEngine(fake, task(Set.of("docs"), 1), 1);

            assertThat(winner).as("no candidate should survive").isNull();
            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                    .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                    .toList();
                assertThat(archived).hasSize(1);
                assertThat(archived.get(0).state()).isEqualTo(CandidateState.KILLED);
                assertThat(archived.get(0).killReason()).isEqualTo(KillReason.WRITESET_VIOLATION);
            }
            // And the contract on disk is untouched - refused means never written.
            assertThat(Files.readString(repoDir.resolve(".swarmcoder/verify.yaml")))
                .contains("compile-ok");
        }
    }

    private static final String FIX_PATCH = """
        diff --git a/fix.txt b/fix.txt
        new file mode 100644
        --- /dev/null
        +++ b/fix.txt
        @@ -0,0 +1 @@
        +fixed
        """;

    /**
     * Repair round (spec §11.5): initial candidate commits a change that fails verification
     * (compile requires fix.txt in HEAD); the repair wave — seeded from the failed branch,
     * carrying the failure evidence — creates fix.txt and survives.
     */
    @Test
    void repairRoundRecoversFromZeroSurvivors() throws Exception {
        initRepo("git cat-file -e HEAD:fix.txt");
        try (FakeVllm fake = new FakeVllm(conversation -> {
            if (conversation.contains("code-review judge")) {
                return FakeVllm.Reply.text("{\"score\": 0.8, \"rationale\": \"repaired\"}");
            }
            boolean repair = conversation.contains("REPAIR CONTEXT");
            if (conversation.contains("applied cleanly")) {
                return FakeVllm.Reply.toolCall("report_done",
                    "{\"summary\": \"" + (repair ? "created fix.txt" : "edited README") + "\"}");
            }
            String patch = repair ? FIX_PATCH : PATCH;
            return FakeVllm.Reply.toolCall("apply_diff",
                new ObjectMapper().createObjectNode()
                    .put("unifiedDiff", patch).toString());
        })) {
            CandidateSolution winner = runEngine(fake, task(Set.of("README.md", "fix.txt"), 1), 3);

            assertThat(winner).as("a repair candidate must be selected").isNotNull();
            assertThat(winner.workerIndex()).as("winner comes from the repair wave").isGreaterThanOrEqualTo(100);
            assertThat(winner.diffUnified()).contains("fix.txt");
            assertThat(winner.verification().compiles()).isTrue();
        }
    }

    /** Total failure: repair also fails verification — the task parks a BLOCKED_TASK decision. */
    @Test
    void blockedDecisionQueuedWhenRepairAlsoFails() throws Exception {
        initRepo("git cat-file -e HEAD:fix.txt");
        try (FakeVllm fake = new FakeVllm(conversation -> {
            if (conversation.contains("code-review judge")) {
                return FakeVllm.Reply.text("{\"score\": 0.1, \"rationale\": \"n/a\"}");
            }
            if (conversation.contains("applied cleanly")) {
                return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"still wrong\"}");
            }
            return FakeVllm.Reply.toolCall("apply_diff",
                new ObjectMapper().createObjectNode()
                    .put("unifiedDiff", PATCH).toString());
        })) {
            CandidateSolution winner = runEngine(fake, task(Set.of("README.md", "fix.txt"), 1), 3);

            assertThat(winner).isNull();
            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                assertThat(store.root().decisions.values())
                    .anyMatch(decision -> decision.kind() == DecisionKind.BLOCKED_TASK
                        && decision.briefMarkdown().contains("Improve README"));
            }
        }
    }

    /**
     * The harness run 14 defect, reproduced end to end through the real engine: every candidate
     * survives verification and every one is judged as breaking the same stated house rule. The
     * task must not deliver the least-bad rule-breaker: the whole RUN parks, through
     * {@link com.swarmcoder.runtime.RunMustPark}, with a message naming the rule and quoting every
     * candidate's own sentence about it.
     *
     * <p>And it parks WITHOUT a repair round (2026-10-02, harness run 66): the candidates passed
     * verification, and the judge's opinion of them is not a fact that undoes that. The four
     * repair workers this used to start are not started.
     */
    @Test
    void everyCandidateBreakingTheSameRuleParksTheRunWithoutARepairRound() throws Exception {
        initRepo();
        try (FakeVllm fake = new FakeVllm(finishingInPairs(conversation -> {
            if (conversation.contains("code-review judge")) {
                return FakeVllm.Reply.text("{\"score\": 0.6, \"rationale\": \"compiles but wrong "
                    + "shape\", \"violations\": [\"Persistence uses EclipseStore through "
                    + "zerozstack-store-eclipsestore: uses an in-memory HashMap instead\"]}");
            }
            if (conversation.contains("applied cleanly")) {
                return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added a line\"}");
            }
            return FakeVllm.Reply.toolCall("apply_diff",
                new ObjectMapper().createObjectNode()
                    .put("unifiedDiff", PATCH).toString());
        }))) {
            try (ArtifactStore store = new ArtifactStore(storeDir)) {
                UUID graphId = UUID.randomUUID();
                Task task = task(Set.of("README.md"), 2);
                TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
                store.append(() -> {
                    store.root().taskGraphs.put(graphId, graph);
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
                    new GitService(repoDir),
                    content -> null,
                    new CloudGate(1_000_000, null));

                Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                    null, null, null, graphId, null, Instant.now(), null);

                assertThatThrownBy(() -> engine.executeRun(run))
                    .as("a task where every candidate broke the same stated rule must park the "
                        + "run rather than deliver the least-bad rule-breaker")
                    .isInstanceOf(com.swarmcoder.runtime.RunMustPark.class)
                    .satisfies(thrown -> {
                        String brief = ((com.swarmcoder.runtime.RunMustPark) thrown).brief();
                        // Since harness runs 53 and 55 (2026-10-01) a unanimous break is a question
                        // about the RULE — keep, reword or allow — raised as its own decision.
                        assertThat(brief)
                            .contains("A QUESTION ABOUT A RULE")
                            .contains("every candidate broke the stated rule")
                            .contains("Persistence uses EclipseStore")
                            .contains("Nothing was delivered for this task")
                            .contains("keep —").contains("reword:").contains("allow —");
                        assertThat(((com.swarmcoder.runtime.RunMustPark) thrown)
                            .questionAlreadyRaised()).isTrue();
                    });
                assertThat(store.root().decisions.values())
                    .anyMatch(d -> d.kind() == DecisionKind.GUIDELINE_REVIEW
                        && d.state() == com.swarmcoder.domain.DecisionState.PENDING);

                List<CandidateSolution> archived = store.root().candidateArchives.values().stream()
                    .map(lazy -> (CandidateSolution) Lazy.get(lazy))
                    .toList();
                assertThat(archived)
                    .as("the first wave's two candidates must be archived even though nothing won")
                    .hasSizeGreaterThanOrEqualTo(2);
                assertThat(archived)
                    .as("no repair round: these candidates passed verification, and a task "
                        + "with a passing candidate is not rebuilt on the judge's opinion")
                    .noneMatch(c -> c.workerIndex() >= com.swarmcoder.domain.RepairIndex.BASE);
                assertThat(archived)
                    .as("every archived candidate that reached the judge must be recorded broken")
                    .allSatisfy(c -> {
                        if (c.judge() != null) {
                            assertThat(c.judge().brokenRules()).isNotEmpty();
                        }
                    });
            }
        }
    }

    /**
     * A wave lost to a dead model endpoint must not spend the story's one repair round (UX v3 §2.4).
     *
     * <p>This is the exact shape of the worst session: the Spark went away, all N workers died, and
     * the engine — unable to tell that from N candidates that had genuinely tried — seeded repair
     * swarms from failures containing no evidence, burned the round, and parked a BLOCKED_TASK
     * decision. The only correct reading is that nothing was learned, so nothing may be concluded.
     *
     * <p>It runs against the REAL worker stack (KoogAgentRuntime over HTTP) rather than a stubbed
     * failure on purpose: the classifier has to recognise a transport failure as it arrives through
     * the agent framework's own wrapping, and that is precisely the part that cannot be verified by
     * reasoning about it.
     */
    @Test
    void aWaveLostToADeadEndpointPausesRatherThanSpendingTheRepairRound() throws Exception {
        initRepo();
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        String dead = "http://localhost:" + deadPort;

        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            Task task = task(Set.of("README.md"), 2);
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                return null;
            }).get();

            GitService git = new GitService(repoDir);
            // Counts sessions actually OPENED. This is the only observable that separates the two ways
            // this can end: the pre-flight check asks the endpoint one question and throws before a
            // worker exists, whereas the older reactive path dispatched the whole wave and worked it out
            // from N dead candidates. Both throw EndpointOutage, so without this the test cannot tell
            // which happened, and the pre-flight check could silently stop working.
            java.util.concurrent.atomic.AtomicInteger opened =
                new java.util.concurrent.atomic.AtomicInteger();
            TraceHub traceHub = new TraceHub(null);
            traceHub.addListener(new TraceHub.Listener() {
                @Override
                public void sessionStarted(com.swarmcoder.domain.AgentSessionRecord snapshot) {
                    opened.incrementAndGet();
                }
            });
            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(dead, "", "fake-judge", true),
                store,
                new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
                new KoogAgentRuntime(traceHub),
                new ModelProfileRegistry(List.of(new ModelProfile("fake",
                    new AgentRuntime.ModelEndpoint(dead, "", "fake-vllm", 65536),
                    ModelProfile.Kind.WORKER, 0, 0))),
                git,
                content -> null,
                new CloudGate(1_000_000, null));

            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);

            assertThatThrownBy(() -> engine.executeRun(run))
                .as("the workflow layer is the only one that can pause and retry, so it must be told")
                .isInstanceOf(EndpointOutage.class);

            // Nothing was STARTED. Discovering the outage reactively costs a worktree per candidate, a
            // container per candidate when the sandbox is on, N traced sessions, and N archived
            // ENDPOINT_OUTAGE candidates written into what is also the evaluation dataset — none of
            // which is evidence about the task, all of it evidence that a box was switched off.
            assertThat(opened.get())
                .as("the endpoint is asked once, before a single worker is launched")
                .isZero();

            // Nothing was concluded: no repair wave, no archived verdict, and above all no decision
            // asking the operator to re-decompose a task that never reached a model.
            assertThat(store.root().candidateArchives).isEmpty();
            assertThat(store.root().decisions.values())
                .as("an outage is not a blocked task")
                .noneMatch(decision -> decision.kind() == DecisionKind.BLOCKED_TASK);

            // And the retry can actually happen: candidate branch names are deterministic, so the
            // empty branches left behind would make `worktree add -b` refuse for ever.
            Process p = new ProcessBuilder(gitCmd("git branch --list swarm/*"))
                .directory(repoDir.toFile()).redirectErrorStream(true).start();
            String branches = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            assertThat(branches.trim())
                .as("branches from an outage hold nothing and must not block the retry")
                .isEmpty();
        }
    }

    /**
     * A task that already has a winner is not swarmed again, and the run it hands back still knows
     * which story it is building.
     *
     * <p>Both halves are what make the outage auto-retry affordable and correct. Re-entering
     * {@code executeRun} after a pause must not re-dispatch work that is finished, or auto-retry
     * would leave the original budget it is only permitted to stay inside (UX v3 rule 6). And the run
     * that comes out must still carry {@code projectId} and {@code storyId}: this method used to
     * rebuild it through the 8-arg constructor, which carries neither, so every run reached the
     * delivery check having forgotten its story and was written off as an ad-hoc run with nothing to
     * hand back — making "the build comes back for judgment on its own" impossible.
     *
     * <p>No model is configured at all, which is the assertion: nothing was dispatched.
     */
    @Test
    void reEnteringAFinishedTaskDispatchesNothingAndKeepsTheRunsIdentity() throws Exception {
        initRepo();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            Task task = task(Set.of("README.md"), 2);
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(task), List.of());
            UUID winnerId = UUID.randomUUID();
            CandidateSolution winner = new CandidateSolution(winnerId, task.id(), 0,
                "swarm/" + task.id() + "/0", null, "a diff", null, null, null,
                CandidateState.SELECTED, null);
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                store.root().candidateArchives.put(winnerId, Lazy.Reference(winner));
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                null, store, new InferenceScheduler(8, 1024 * 1024 * 1024, 1024),
                spec -> {
                    throw new AssertionError("a task with a winner must never be dispatched again");
                },
                new ModelProfileRegistry(List.of()),
                new GitService(repoDir), content -> null, new CloudGate(1_000_000, null));

            UUID projectId = UUID.randomUUID();
            UUID storyId = UUID.randomUUID();
            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
                projectId, storyId, null, graphId, null, Instant.now(), null);

            Run after = engine.executeRun(run);

            assertThat(after.storyId())
                .as("a run that forgets its story cannot hand it back for judgment")
                .isEqualTo(storyId);
            assertThat(after.projectId())
                .as("a run that forgets its project disappears from every project-scoped view")
                .isEqualTo(projectId);
        }
    }

    private void run(String command) throws Exception {
        Process p = new ProcessBuilder(gitCmd(command))
            .directory(repoDir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }

    private static List<String> gitCmd(String command) {
        return System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command)
            : List.of("sh", "-c", command);
    }
}
