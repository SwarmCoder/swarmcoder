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
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.RepairIndex;
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
import com.swarmcoder.inference.ModelQuirks;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model server's places go to different work first (2026-10-02).
 *
 * <p><b>The measurement.</b> Harness run 66: 45% of the tokens and 51% of the worker minutes went
 * to candidates that were not selected, and the first candidate alone would have delivered four
 * tasks of four. Every candidate of a task was started together, every one ran to its end, and a
 * task waited for all of them before it went on.
 *
 * <p><b>What this pins,</b> through the real engine and the real worker stack against a scripted
 * model server:
 *
 * <ul>
 *   <li>the first candidate of every ready task has a place before any second candidate does;
 *   <li>a task that has a candidate through verification does not start a second one just to
 *       have two, and with one candidate there is nothing for the judge to choose: it is not
 *       called;
 *   <li>a second candidate still working when its task has a passing candidate, with nothing
 *       else in the wave looking for one, is stopped - the task does not wait for its duplicate;
 *   <li>when the first candidate fails, the next one is started: the swarm is still there;
 *   <li>a spare candidate gives its place up to a task that has nothing running.
 * </ul>
 */
@ModelCodeOnThisPc
class PlacesGoToDifferentWorkFirstTest {

    /** The two personas the tasks here rotate: the scripted server tells first from second by them. */
    private static final String FIRST = "make the smallest change that satisfies the task";
    private static final String SECOND = "implement exactly what the acceptance tests require";

    private static final long PATIENCE_SECONDS = 20;

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    @AfterEach
    void noCeilingLeftBehind() {
        WorkerSlots.configure(0);
    }

    @Test
    void theFirstCandidateOfEveryReadyTaskHasAPlaceBeforeAnySecondCandidate() throws Exception {
        initRepo("echo compile-ok", "a.md", "b.md", "c.md", "d.md");
        WorkerSlots.configure(4);
        List<Task> tasks = List.of(task("Improve a", "a.md", 2), task("Improve b", "b.md", 2),
            task("Improve c", "c.md", 2), task("Improve d", "d.md", 2));

        // What arrived at the server, in order: "<file>" for a first candidate, "<file>+" for a
        // second. Every worker's first request is held until four are in flight together.
        List<String> arrivals = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch fourIn = new CountDownLatch(4);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<CandidateSolution> archived = run(tasks, 4, 0, conversation -> {
            String file = fileOf(conversation);
            if (conversation.contains("applied cleanly")) {
                return done();
            }
            arrivals.add(file + (conversation.contains(SECOND) ? "+" : ""));
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            fourIn.countDown();
            try {
                fourIn.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return patch(file);
        });

        assertThat(peak.get()).as("the server's four places were all in use at once").isEqualTo(4);
        assertThat(arrivals.subList(0, 4))
            .as("the first four requests the server saw: the first candidate of each of the four "
                + "tasks, and no second candidate of any")
            .containsExactlyInAnyOrder("a.md", "b.md", "c.md", "d.md");
        assertThat(archived.stream().filter(c -> c.state() == CandidateState.SELECTED)
            .map(CandidateSolution::taskId))
            .as("and every task got its winner")
            .containsExactlyInAnyOrderElementsOf(tasks.stream().map(Task::id).toList());
    }

    @Test
    void aTaskWithAPassingCandidateDoesNotStartASecondOneAndIsNotJudged() throws Exception {
        initRepo("echo compile-ok", "alpha.md", "beta.md");
        // One place. Whichever task's first candidate gets it is "early"; the other task's first
        // candidate waits for the place and then holds it until the early task is finished, so
        // the early task's second candidate never finds the place free while it could matter.
        WorkerSlots.configure(1);
        Task alpha = task("Improve alpha", "alpha.md", 2);
        Task beta = task("Improve beta", "beta.md", 2);
        java.util.concurrent.atomic.AtomicReference<String> earlyFile =
            new java.util.concurrent.atomic.AtomicReference<>();

        List<CandidateSolution> archived = run(List.of(alpha, beta), 1, 0, conversation -> {
            if (conversation.contains("applied cleanly")) {
                return done();
            }
            String file = fileOf(conversation);
            earlyFile.compareAndSet(null, file);
            if (!file.equals(earlyFile.get())) {
                Task early = earlyFile.get().equals("alpha.md") ? alpha : beta;
                await(() -> early.state() == TaskState.SELECTED);
            }
            return patch(file);
        });

        Task early = earlyFile.get().equals("alpha.md") ? alpha : beta;
        Task late = early == alpha ? beta : alpha;
        List<CandidateSolution> ofEarly = archived.stream()
            .filter(c -> c.taskId().equals(early.id())).toList();
        assertThat(ofEarly).as("its first candidate passed, so its second was never started")
            .hasSize(1);
        assertThat(ofEarly.get(0).workerIndex()).isZero();
        assertThat(ofEarly.get(0).state()).isEqualTo(CandidateState.SELECTED);
        assertThat(ofEarly.get(0).judge())
            .as("with one candidate there is nothing for the judge to choose between").isNull();
        assertThat(requests).as("no second candidate of that task ever reached the server")
            .noneMatch(r -> r.contains(earlyFile.get()) && r.contains(SECOND));
        assertThat(requests).as("and the judge was not called for it")
            .noneMatch(r -> r.contains("code-review judge") && r.contains(earlyFile.get()));
        assertThat(archived.stream().filter(c -> c.state() == CandidateState.SELECTED)
            .map(CandidateSolution::taskId)).as("the other task was delivered too")
            .contains(late.id());
    }

    @Test
    void aDuplicateStillWorkingIsStoppedWhenNothingElseIsLookingForACandidate() throws Exception {
        initRepo("echo compile-ok", "alpha.md");
        WorkerSlots.configure(4);
        Task alpha = task("Improve alpha", "alpha.md", 2);

        List<CandidateSolution> archived = run(List.of(alpha), 4, 0, conversation -> {
            if (conversation.contains("applied cleanly")) {
                return done();
            }
            if (conversation.contains(SECOND)) {
                // The second candidate is still thinking when the first has passed and won.
                await(() -> alpha.state() == TaskState.SELECTED);
            }
            return patch("alpha.md");
        });

        assertThat(archived).as("both candidates are on record").hasSize(2);
        CandidateSolution first = archived.stream().filter(c -> c.workerIndex() == 0).findFirst()
            .orElseThrow();
        CandidateSolution second = archived.stream().filter(c -> c.workerIndex() == 1).findFirst()
            .orElseThrow();
        assertThat(first.state()).as("the task did not wait for its duplicate")
            .isEqualTo(CandidateState.SELECTED);
        assertThat(second.state()).isEqualTo(CandidateState.KILLED);
        assertThat(second.killReason()).as("the duplicate was stopped, and says why")
            .isEqualTo(KillReason.SUPERSEDED);
        assertThat(requests).as("one candidate passed, so the judge was not called")
            .noneMatch(r -> r.contains("code-review judge"));
    }

    @Test
    void whenTheFirstCandidateFailsTheNextOneIsStarted() throws Exception {
        // Verification passes only for a candidate that created fix.txt.
        initRepo("git cat-file -e HEAD:fix.txt", "alpha.md");
        WorkerSlots.configure(1);
        Task alpha = new Task(UUID.randomUUID(), 1, "Improve alpha", "Add a line to alpha.md",
            Set.of("alpha.md", "fix.txt"), Set.of(), List.of(), null, null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff", "test-literalist")),
            TaskState.READY);

        List<CandidateSolution> archived = run(List.of(alpha), 1, 0, conversation -> {
            if (conversation.contains("applied cleanly")) {
                return done();
            }
            return conversation.contains(SECOND) ? diff(FIX_PATCH) : patch("alpha.md");
        });

        assertThat(archived).as("the first candidate and the one started after it failed")
            .hasSize(2);
        assertThat(archived).as("no repair round: the task's own second candidate was enough")
            .noneMatch(c -> RepairIndex.isRepair(c.workerIndex()));
        CandidateSolution winner = archived.stream()
            .filter(c -> c.state() == CandidateState.SELECTED).findFirst().orElseThrow();
        assertThat(winner.workerIndex()).isEqualTo(1);
        assertThat(winner.diffUnified()).contains("fix.txt");
    }

    @Test
    void aSpareCandidateGivesItsPlaceUpToATaskThatHasNothingRunning() throws Exception {
        initRepo("echo compile-ok", "alpha.md", "beta.md");
        WorkerSlots.configure(2);
        ModelQuirks quirks = quirks(2);
        Task alpha = task("Improve alpha", "alpha.md", 2);
        Task beta = task("Improve beta", "beta.md", 1);
        AtomicInteger alphaIn = new AtomicInteger();

        try (FakeVllm fake = new FakeVllm(conversation -> {
            if (conversation.contains("applied cleanly")) {
                return done();
            }
            String file = fileOf(conversation);
            if (file.equals("alpha.md")) {
                // Both of alpha's candidates are in their session, holding both places, until
                // beta's first candidate is waiting for one.
                alphaIn.incrementAndGet();
                await(() -> WorkerSlots.shared().waiting() > 0);
            }
            return patch(file);
        })) {
            SwarmDispatcher dispatcher = new SwarmDispatcher(
                InferenceScheduler.forWorkerModel("fake", quirks), new GitService(repoDir),
                new KoogAgentRuntime(), profiles(fake, quirks));
            UUID runId = UUID.randomUUID();
            List<CandidateGroup.Ended> ofAlpha = Collections.synchronizedList(new ArrayList<>());
            List<CandidateGroup.Ended> ofBeta = Collections.synchronizedList(new ArrayList<>());

            CandidateGroup groupAlpha = dispatcher.start(alpha, runId, null, null, null, null,
                ofAlpha::add);
            await(() -> alphaIn.get() == 2);
            CandidateGroup groupBeta = dispatcher.start(beta, runId, null, null, null, null,
                ofBeta::add);
            groupAlpha.awaitThreads();
            groupBeta.awaitThreads();
            // Nobody but this test owns these worktrees: the engine that removes them is not here.
            GitService git = new GitService(repoDir);
            for (List<CandidateGroup.Ended> ended : List.of(ofAlpha, ofBeta)) {
                for (CandidateGroup.Ended one : List.copyOf(ended)) {
                    if (one.result() != null && one.result().workspace() != null) {
                        git.removeWorktree(one.result().workspace());
                    }
                }
            }

            CandidateGroup.Ended spare = ofAlpha.stream().filter(e -> e.workerIndex() == 1)
                .findFirst().orElseThrow();
            assertThat(spare.start()).isEqualTo(CandidateGroup.Start.SPARE);
            assertThat(spare.result().candidate().killReason())
                .as("alpha's second candidate was a spare, and beta had nothing running")
                .isEqualTo(KillReason.PLACE_NEEDED);
            assertThat(ofAlpha.stream().filter(e -> e.workerIndex() == 0).findFirst().orElseThrow()
                .result().candidate().state())
                .as("alpha's first candidate was not touched").isEqualTo(CandidateState.SURVIVED);
            assertThat(ofBeta).hasSize(1);
            assertThat(ofBeta.get(0).result().candidate().state())
                .as("and beta's first candidate ran on the place it gave up")
                .isEqualTo(CandidateState.SURVIVED);
        }
    }

    // ------------------------------------------------------------------------------------------

    private static final String FIX_PATCH = """
        diff --git a/fix.txt b/fix.txt
        new file mode 100644
        --- /dev/null
        +++ b/fix.txt
        @@ -0,0 +1 @@
        +fixed
        """;

    private static FakeVllm.Reply done() {
        return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"added a line\"}");
    }

    private static FakeVllm.Reply diff(String unifiedDiff) {
        return FakeVllm.Reply.toolCall("apply_diff", new ObjectMapper().createObjectNode()
            .put("unifiedDiff", unifiedDiff).toString());
    }

    private static FakeVllm.Reply patch(String file) {
        return diff("""
            diff --git a/%1$s b/%1$s
            --- a/%1$s
            +++ b/%1$s
            @@ -1 +1,2 @@
             hello
            +improved by worker
            """.formatted(file));
    }

    /** Which task's file a worker's conversation is about: the one its task instructions name. */
    private static String fileOf(String conversation) {
        int at = conversation.indexOf("Add a line to ");
        int end = conversation.indexOf(".md", at);
        return at < 0 || end < 0 ? "" : conversation.substring(at + "Add a line to ".length(), end + 3);
    }

    private static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Task task(String title, String file, int candidates) {
        return new Task(UUID.randomUUID(), 1, title, "Add a line to " + file,
            Set.of(file), Set.of(), List.of(), null, null,
            new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(candidates, false, 0.2, 0.8, List.of("minimal-diff", "test-literalist")),
            TaskState.READY);
    }

    /** The scripted server's model, served {@code atOnce} requests at a time. */
    private static ModelQuirks quirks(int atOnce) {
        ModelQuirks d = ModelQuirks.DEFAULTS;
        return new ModelQuirks("a scripted model served " + atOnce + " at once",
            d.maxOutputTokens(), d.thinking(), d.thinkingKwarg(), d.noThinkDirective(),
            d.textualToolHistory(), d.jsonResponseFormat(), d.http2(),
            d.servedContextTokens(), d.workingContextTokens(), d.kvBytesPerToken(), atOnce, false);
    }

    private static ModelProfileRegistry profiles(FakeVllm fake, ModelQuirks quirks) {
        return new ModelProfileRegistry(List.of(new ModelProfile("fake",
            new AgentRuntime.ModelEndpoint(fake.baseUrl(), "", "fake-vllm",
                quirks.servedContextTokens(), quirks),
            ModelProfile.Kind.WORKER, quirks, 0)));
    }

    /** Every conversation the scripted server was sent in the last {@link #run}, judge calls too. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    /**
     * Runs one wave of {@code tasks} through the real engine; returns everything archived. The
     * script answers the workers; a judge call gets a plain verdict.
     */
    private List<CandidateSolution> run(List<Task> tasks, int serverServesAtOnce, int tasksAtOnce,
                                        Function<String, FakeVllm.Reply> script) throws Exception {
        ModelQuirks quirks = quirks(serverServesAtOnce);
        try (FakeVllm fake = new FakeVllm(conversation -> {
                 requests.add(conversation);
                 return conversation.contains("code-review judge")
                     ? FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"clean change\"}")
                     : script.apply(conversation);
             });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            // No edges: every task is in the same wave.
            TaskGraph graph = new TaskGraph(graphId, 1, null, tasks, List.of());
            store.append(() -> {
                store.root().taskGraphs.put(graphId, graph);
                return null;
            }).get();

            SwarmEngineImpl engine = new SwarmEngineImpl(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), store,
                InferenceScheduler.forWorkerModel("fake", quirks), new KoogAgentRuntime(),
                profiles(fake, quirks), new GitService(repoDir), content -> null,
                new CloudGate(1_000_000, null));
            engine.setDispatchTuning(tasksAtOnce, 0);

            engine.executeRun(new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD,
                RunState.EXECUTING, null, null, null, graphId, null, Instant.now(), null));

            return store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }
    }

    private void initRepo(String compileCommand, String... files) throws Exception {
        exec("git init -q");
        for (String file : files) {
            Files.writeString(repoDir.resolve(file), "hello\n");
        }
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "%s"
            timeoutSeconds: 60
            """.formatted(compileCommand));
        exec("git add -A");
        exec("git -c user.email=t@t -c user.name=t commit -q -m init");
    }

    private void exec(String command) throws Exception {
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(command + " failed: " + out);
        }
    }
}
