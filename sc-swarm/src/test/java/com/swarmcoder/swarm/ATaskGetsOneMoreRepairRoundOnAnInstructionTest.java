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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.InstructedRepair;
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
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.runtime.RunMustPark;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task that is BLOCKED after its swarm and its repair round can be given one more repair
 * round, told what to change (section 84, {@link com.swarmcoder.domain.InstructedRepair}).
 *
 * <p>Live supervised build, 2026-10-12: twice a build stopped on something its workers had to
 * change, and the only answers were to run the stage again - the same stop - or to send the
 * whole story back to be designed again.
 *
 * <p>The shape here: one task whose acceptance test needs two files. Its workers write one of
 * them, in the first round and in the ordinary repair round, so the task is BLOCKED and asks.
 * The question is marked on the task as one that takes the answer repair. A round given with an
 * instruction is seeded from the candidates the task already has; its workers are told the
 * instruction before the failure, write the second file, and the task has a chosen candidate.
 */
@ModelCodeOnThisPc
class ATaskGetsOneMoreRepairRoundOnAnInstructionTest {

    private static final String ACCEPT_DIR = "src/test/java/swarm/accept";
    private static final String SERVICE_TEST = ACCEPT_DIR + "/BooksServiceTest.java";
    private static final String SERVICE = "src/main/BooksService.java";
    /** What the test also needs and no worker writes until it is told to. */
    private static final String SCREEN = "src/main/books-screen.html";
    private static final String INSTRUCTION = "The books screen is missing: create " + SCREEN;

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;
    private String testsCommit;

    @Test
    void aBlockedTaskIsRepairedByARoundThatIsToldWhatToChange() throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo(runId);
        Task service = task("Create the books service and its screen",
            "Create " + SERVICE + ".", Set.of(SERVICE, SCREEN),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "the service exists",
                "swarm.accept.BooksServiceTest#proves")),
            List.of(SERVICE_TEST));

        try (FakeVllm fake = new FakeVllm(ATaskGetsOneMoreRepairRoundOnAnInstructionTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(service), List.of());
            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.EXECUTING,
                null, null, null, graphId, null, Instant.now(), null);
            run.setBaseCommit(base);
            run.setAcceptanceTestsCommit(testsCommit);
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
            engine.setWaveGate((asked, wave, waveBase) -> null);

            try {
                engine.executeRun(run);
            } catch (RunMustPark stoppedBehindTheBlockedTask) {
                // either way the task is BLOCKED and has asked
            }

            assertThat(service.state()).isEqualTo(TaskState.BLOCKED);
            List<Decision> asked = store.root().decisions.values().stream()
                .filter(d -> d.state() == DecisionState.PENDING
                    && d.kind() == DecisionKind.BLOCKED_TASK).toList();
            assertThat(asked).hasSize(1);
            assertThat(service.repairOfferedBy())
                .as("the task's own question is the one that takes the answer repair")
                .isEqualTo(asked.get(0).id());
            assertThat(service.instructedRepairRounds()).isZero();
            Set<Integer> before = workerIndicesOf(store, service);

            boolean chosen = engine.repairOnInstruction(service, runId, INSTRUCTION);

            assertThat(chosen).as("a repaired candidate passed and was chosen").isTrue();
            assertThat(service.state()).isEqualTo(TaskState.SELECTED);
            assertThat(service.instructedRepairRounds()).isEqualTo(1);
            Set<Integer> added = new java.util.TreeSet<>(workerIndicesOf(store, service));
            added.removeAll(before);
            assertThat(added).as("the round's workers are new workers").isNotEmpty()
                .allSatisfy(index -> {
                    assertThat(RepairIndex.isRepair(index)).isTrue();
                    assertThat(RepairIndex.attempt(index))
                        .as("after the ordinary round's two goes at the same seed, so no "
                            + "branch of an earlier repair is taken over")
                        .isGreaterThanOrEqualTo(InstructedRepair.WORKERS_PER_SEED);
                });
            assertThat(new ArrayList<>(fake.requests).stream()
                    .anyMatch(text -> text.contains(INSTRUCTION)
                        && text.contains("instruction for this repair round")))
                .as("the repair workers were given the instruction").isTrue();
        }
    }

    @Test
    void theSettingCapsTheRoundsAndTheirWorkersHaveIndicesOfTheirOwn() {
        Task task = task("T", "", Set.of(SERVICE), List.of(), List.of());
        assertThat(InstructedRepair.limit()).isEqualTo(1);
        assertThat(InstructedRepair.roundLeft(task)).isTrue();
        task.setInstructedRepairRounds(1);
        assertThat(InstructedRepair.roundLeft(task)).isFalse();

        System.setProperty(InstructedRepair.LIMIT_PROPERTY, "99");
        try {
            assertThat(InstructedRepair.limit()).isEqualTo(InstructedRepair.MOST);
            assertThat(InstructedRepair.roundLeft(task)).isTrue();
        } finally {
            System.clearProperty(InstructedRepair.LIMIT_PROPERTY);
        }
        for (int round = 1; round <= InstructedRepair.MOST; round++) {
            int first = InstructedRepair.firstAttemptOf(round);
            assertThat(first).isEqualTo(2 * round);
            assertThat(RepairIndex.attempt(RepairIndex.of(3, first + 1))).isEqualTo(first + 1);
        }
        assertThat(InstructedRepair.brief("  change the screen ")).contains("change the screen")
            .startsWith("--- instruction for this repair round ---");
    }

    private static Set<Integer> workerIndicesOf(ArtifactStore store, Task task) {
        Set<Integer> indices = new java.util.TreeSet<>();
        for (var lazy : store.root().candidateArchives.values()) {
            CandidateSolution candidate = (CandidateSolution) Lazy.get(lazy);
            if (task.id().equals(candidate.taskId())) {
                indices.add(candidate.workerIndex());
            }
        }
        return indices;
    }

    /** A worker writes the service; one that was given the instruction writes the screen too. */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        String path = SERVICE;
        if (conversation.contains("instruction for this repair round")
                && conversation.contains("wrote " + SERVICE)) {
            path = SCREEN;
        }
        if (conversation.contains("wrote " + path)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"done\"}");
        }
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", path).put("content", "class X { }\n").toString());
    }

    // --- the repository --------------------------------------------------------------------------

    private void initRepo(UUID runId) throws Exception {
        git("init -q");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        Files.createDirectories(repoDir.resolve("src/main"));
        Files.writeString(repoDir.resolve("src/main/.keep"), "");
        Files.createDirectories(repoDir.resolve("tools"));
        Files.writeString(repoDir.resolve("tools/Accept.java"), ACCEPT_STAGE);
        Files.createDirectories(repoDir.resolve(".swarmcoder"));
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        Files.writeString(repoDir.resolve(".swarmcoder/verify.yaml"), """
            toolchain: gradle
            compile:
              - "echo compile-ok"
            acceptance:
              - "\\"%s\\" tools/Accept.java"
            timeoutSeconds: 60
            """.formatted(java));
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();

        git("checkout -q -b swarm/tests/" + runId);
        write(SERVICE_TEST, "package swarm.accept;\n// needs: " + SERVICE + "\n// needs: " + SCREEN
            + "\nclass BooksServiceTest { void proves() {} }\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m tests");
        testsCommit = git("rev-parse HEAD").strip();
        git("checkout -q master");
    }

    /** The stand-in acceptance stage {@code ALaterWaveBuildsOnEarlierWinnersTest} uses. */
    private static final String ACCEPT_STAGE = """
        import java.nio.file.*;
        import java.util.*;

        public class Accept {
            public static void main(String[] args) throws Exception {
                Path dir = Path.of("src/test/java/swarm/accept");
                List<Path> files = new ArrayList<>();
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) {
                        s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                    }
                }
                if (files.isEmpty()) {
                    System.out.println("no acceptance test present; nothing run");
                    return;
                }
                StringBuilder xml = new StringBuilder();
                int failures = 0;
                for (Path file : files) {
                    String name = file.getFileName().toString().replace(".java", "");
                    boolean ok = true;
                    for (String line : Files.readString(file).split("\\n")) {
                        if (line.trim().startsWith("// needs:")) {
                            ok &= Files.exists(Path.of(line.trim().substring(9).trim()));
                        }
                    }
                    xml.append("<testcase classname=\\"swarm.accept.").append(name)
                       .append("\\" name=\\"proves\\">");
                    if (!ok) {
                        failures++;
                        // A different message per candidate: identical failures from every candidate would
                        // (rightly) send the test back to its author, not into the repair round.
                        String tag = Long.toString(System.nanoTime(), 36);
                        tag = tag.substring(tag.length() - 4);
                        xml.append("<failure message=\\"missing ").append(tag)
                           .append("\\">the file it needs is missing</failure>");
                    }
                    xml.append("</testcase>\\n");
                    System.out.println("ran swarm.accept." + name + " -> " + (ok ? "pass" : "FAIL"));
                }
                Files.createDirectories(Path.of("build/test-results"));
                Files.writeString(Path.of("build/test-results/TEST-accept.xml"),
                    "<testsuite name=\\"accept\\" tests=\\"" + files.size() + "\\" failures=\\"" + failures
                    + "\\" errors=\\"0\\" skipped=\\"0\\">\\n" + xml + "</testsuite>\\n");
                System.exit(failures > 0 ? 1 : 0);
            }
        }
        """;


    private static Task task(String title, String instructions, Set<String> writeSet,
                             List<AcceptanceCriterion> criteria, List<String> authoredTestPaths) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            criteria, "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
        task.setAuthoredTestPaths(authoredTestPaths);
        return task;
    }

    private void write(String relative, String content) throws Exception {
        Path file = repoDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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
