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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
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
import com.swarmcoder.store.ArtifactStore;
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

/**
 * The decided design, on the real swarm stack: <b>a task told to deliver a design contract is
 * verified against it — the type, its package and its members — even though it claims no test.</b>
 *
 * <h2>Why an enabler needs a gate of its own</h2>
 *
 * <p>An enabler claims no acceptance check; that is what makes it an enabler. So nothing about it
 * is proved by running anything, and until now nothing looked at it except a judge reading a diff
 * against a prose instruction. On run 13 that is where the run was lost: the wave that was meant
 * to deliver the shared vocabulary delivered a differently-shaped version of it, verification had
 * no opinion, the judge had nothing to compare against, and the mistake only surfaced two waves
 * later as an acceptance test that could not compile — blamed, honestly and uselessly, on
 * candidates that had not touched the file.
 *
 * <p>The contract is a written promise with a name and a member list. Comparing it against the
 * candidate's own tree is a file read.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>One enabler task, told to deliver {@code com.x.Book{int rating; }}, and two scripted workers
 * that differ in exactly one line: one writes the class with the field, the other writes it
 * without. The first survives; the second fails, and the reason names the contract rather than
 * saying something a person cannot act on.
 */
@ModelCodeOnThisPc
class AnEnablerDeliversItsContractsTest {

    private static final String BOOK = "src/main/java/com/x/Book.java";
    /** The persona of worker 1 — the only per-worker text in a worker's prompt. */
    private static final String WORKER_ONE = "implement exactly what the acceptance tests require";

    @TempDir
    Path repoDir;
    @TempDir
    Path storeDir;

    private String base;

    @Test
    void theCandidateThatOmitsAContractMemberFailsNamingTheContractAndTheOtherSurvives()
            throws Exception {
        UUID runId = UUID.randomUUID();
        initRepo();

        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf",
            "Book", "com.x.Book", List.of("int rating"));
        Task enabler = task("Deliver the shared Book type", "Create " + BOOK + ".",
            Set.of("src/main/java/com/x"));
        enabler.setDeliveredContracts(List.of(book));

        List<CandidateSolution> archived;
        try (FakeVllm fake = new FakeVllm(AnEnablerDeliversItsContractsTest::route);
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID graphId = UUID.randomUUID();
            TaskGraph graph = new TaskGraph(graphId, 1, null, List.of(enabler), List.of());
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
            engine.executeRun(run);

            archived = store.root().candidateArchives.values().stream()
                .map(lazy -> (CandidateSolution) Lazy.get(lazy)).toList();
        }

        assertThat(archived).hasSize(2);
        CandidateSolution withRating = archived.stream()
            .filter(c -> c.diffUnified().contains("int rating")).findFirst().orElseThrow();
        CandidateSolution withoutRating = archived.stream()
            .filter(c -> !c.diffUnified().contains("int rating")).findFirst().orElseThrow();

        // --- the candidate that omitted the member did not survive, and the reason says why ----
        assertThat(withoutRating.state())
            .as("a contract it was told to deliver, delivered without the member the later "
                + "wave's tests are written against")
            .isEqualTo(CandidateState.FAILED);
        String reason = withoutRating.verification().logTail();
        assertThat(reason)
            .contains("the contract `com.x.Book` was delivered without the member int rating")
            .contains("com.x.Book{int rating; }")
            .contains("cannot be corrected later");

        // --- and the one that delivered it is untouched ---------------------------------------
        assertThat(withRating.state())
            .as("delivering the contract as written is all this gate asks")
            .isIn(CandidateState.SURVIVED, CandidateState.SELECTED);
    }

    /**
     * The same task, and a candidate that never writes the type at all: the harsher half of the
     * same rule, and the one that produces the sentence an operator reads first.
     */
    @Test
    void aCandidateThatNeverWritesTheContractTypeIsToldSo() throws Exception {
        Task enabler = task("Deliver the shared Rating type", "Create it.",
            Set.of("src/main/java/com/x"));
        ApiContract rating = new ApiContract(UUID.randomUUID(), "Rating", "a book's rating",
            "Rating", "com.x.Rating", List.of("int stars"));
        enabler.setDeliveredContracts(List.of(rating));

        Path tree = repoDir.resolve("empty-tree");
        Files.createDirectories(tree.resolve("src/main/java/com/x"));
        Files.writeString(tree.resolve("src/main/java/com/x/Book.java"),
            "package com.x;\npublic class Book { public int rating; }\n");

        var shortfalls = com.swarmcoder.knowledge.ContractDelivery.shortfalls(
            tree, enabler.deliveredContracts());
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).missingType()).isTrue();
        assertThat(shortfalls.get(0).render())
            .isEqualTo("the contract `com.x.Rating` was not delivered: no type of that name "
                + "exists anywhere in this candidate's tree. The task was told to create it as "
                + "com.x.Rating{int stars; }.");
    }

    // --- the repository -----------------------------------------------------------------------

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

    /** Two workers, told apart by their persona: one delivers the field, the other does not. */
    private static FakeVllm.Reply route(String conversation) {
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text("{\"score\": 0.9, \"rationale\": \"does the job\"}");
        }
        if (conversation.contains("wrote " + BOOK)) {
            return FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"Book delivered\"}");
        }
        String content = conversation.contains(WORKER_ONE)
            ? "package com.x;\npublic class Book {\n    public String title;\n}\n"
            : "package com.x;\npublic class Book {\n    public String title;\n    public int rating;\n}\n";
        return FakeVllm.Reply.toolCall("write_file", new ObjectMapper().createObjectNode()
            .put("path", BOOK).put("content", content).toString());
    }

    private static Task task(String title, String instructions, Set<String> writeSet) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 100000, 12),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
        task.setAuthoredTestPaths(List.of());
        return task;
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
