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
import com.swarmcoder.domain.*;
import com.swarmcoder.git.GitService;
import com.swarmcoder.lsp.LspServiceFactory;
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
import java.util.ArrayList;

/** FINAL_INTEGRATION over a real git repo: topological merges, verify-after-each, conflicts park. */
@ModelCodeOnThisPc
class FinalIntegratorTest {

    @TempDir
    Path repo;
    @TempDir
    Path storeDir;

    private Task taskA;
    private Task taskB;

    private void initRepoWithWinners(boolean conflicting) throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("a.txt"), "alpha\n");
        Files.writeString(repo.resolve("b.txt"), "beta\n");
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"),
            "toolchain: gradle\ncompile:\n  - \"echo integration-ok\"\ntimeoutSeconds: 60\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");

        taskA = task("task-a", Set.of("a.txt"));
        taskB = task("task-b", Set.of(conflicting ? "a.txt" : "b.txt"));

        // Winner branch for task A: change a.txt.
        git("checkout -q -b swarm/" + taskA.id() + "/0");
        Files.writeString(repo.resolve("a.txt"), "alpha changed by A\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m taskA");
        git("checkout -q master");

        // Winner branch for task B: disjoint change, or a conflicting edit of a.txt.
        git("checkout -q -b swarm/" + taskB.id() + "/0");
        if (conflicting) {
            Files.writeString(repo.resolve("a.txt"), "alpha changed by B\n");
        } else {
            Files.writeString(repo.resolve("b.txt"), "beta changed by B\n");
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m taskB");
        git("checkout -q master");
    }

    @Test
    void mergesWinnersTopologicallyAndVerifiesGreen() throws Exception {
        initRepoWithWinners(false);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store);

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
            assertThat(result.integrationBranch()).isEqualTo("swarm/integration/" + run.id());
            assertThat(gitOut("show " + result.integrationBranch() + ":a.txt")).contains("changed by A");
            assertThat(gitOut("show " + result.integrationBranch() + ":b.txt")).contains("changed by B");
        }
    }

    /**
     * Section 70 (live run 95): final integration runs again for the same run - after a repair
     * round, after a corrected journey, after a pause for the model server - and every second
     * attempt died on "a branch named 'swarm/integration/<run>' already exists". The return
     * path of sections 63 and 69 had never run to its end. Here it runs three times on a real
     * repository: each attempt is made from the start, and the earlier ones are kept under a
     * numbered name for a person to look at.
     */
    @Test
    void finalIntegrationCanBeMadeAgainForTheSameRunAndKeepsTheEarlierAttempts()
            throws Exception {
        initRepoWithWinners(false);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store);
            List<String> told = new ArrayList<>();
            String branch = "swarm/integration/" + run.id();
            String earlier = "swarm/integration-attempt/" + run.id() + "/";

            FinalIntegrator.Result first =
                new FinalIntegrator(new GitService(repo), store).integrate(run);
            assertThat(first.ok()).as(String.valueOf(first.failure())).isTrue();
            String firstHead = gitOut("rev-parse " + branch).strip();

            // What a repair round does before the stage runs again: the task's choice changes.
            git("checkout -q swarm/" + taskB.id() + "/0");
            Files.writeString(repo.resolve("b.txt"), "beta repaired by B\n");
            git("add -A");
            git("-c user.email=t@t -c user.name=t commit -q -m repaired");
            git("checkout -q master");

            FinalIntegrator.Result second = new FinalIntegrator(new GitService(repo), store)
                .tellingTheRun(told::add).integrate(run);

            assertThat(second.ok()).as(String.valueOf(second.failure())).isTrue();
            assertThat(second.integrationBranch()).isEqualTo(branch);
            assertThat(gitOut("show " + branch + ":b.txt")).as("made again from the start, "
                + "with the repaired choice").contains("repaired by B");
            assertThat(gitOut("show " + branch + ":a.txt")).contains("changed by A");
            assertThat(gitOut("rev-parse " + earlier + "1").strip())
                .as("the earlier attempt is kept, not deleted").isEqualTo(firstHead);
            assertThat(told).anyMatch(line -> line.contains("made again")
                && line.contains(earlier + "1"));

            FinalIntegrator.Result third =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(third.ok()).as(String.valueOf(third.failure())).isTrue();
            assertThat(gitOut("branch --list"))
                .contains(branch, earlier + "1", earlier + "2");
            assertThat(gitOut("worktree list")).as("no integration worktree is left behind")
                .doesNotContain("integration-" + run.id());
        }
    }

    /** A killed attempt can leave the branch checked out in a worktree; that is cleared too. */
    @Test
    void anAttemptThatWasKilledWithItsWorktreeStillThereDoesNotStopTheNextOne() throws Exception {
        initRepoWithWinners(false);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store);
            String branch = "swarm/integration/" + run.id();
            Path left = Path.of(System.getProperty("user.home"), ".swarmcoder", "wt",
                "integration-" + run.id());
            new GitService(repo).addWorktree(branch, left, "HEAD");

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
            assertThat(gitOut("show " + branch + ":a.txt")).contains("changed by A");
            assertThat(Files.exists(left)).isFalse();
        }
    }

    @Test
    void secretInWinningDiffParksBeforeApproval() throws Exception {
        initRepoWithWinners(false);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store,
                "+String key = \"AKIAIOSFODNN7EXAMPLE\";\n");

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).isFalse();
            assertThat(result.failure()).contains("Secret scan").contains("task-a")
                .contains("known-token-format").doesNotContain("AKIAIOSFODNN7EXAMPLE");
        }
    }

    /**
     * The second enforcement point, for the file that now decides survival.
     *
     * <p>A house rule may declare a command verification runs, so a worker that could get one into
     * the operator's checkout would be handing the orchestrator a command to execute. The tool
     * checks cannot see what a worker's shell wrote; {@code git add -A} sweeps it into the diff,
     * and this audit is the last thing between that diff and the integration branch. The task's
     * write set is deliberately unrestricted here, which is the permissive case.
     */
    @Test
    void aHouseRulePlantedInTheWinningDiffParksInsteadOfMerging() throws Exception {
        initRepoWithWinners(false);
        taskA.setWriteSet(Set.of());
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store, """
                diff --git a/.swarmcoder/guidelines/project/planted.md b/.swarmcoder/guidelines/project/planted.md
                new file mode 100644
                --- /dev/null
                +++ b/.swarmcoder/guidelines/project/planted.md
                @@ -0,0 +1,3 @@
                +---
                +check: echo anything-i-like
                +---
                """);

            FinalIntegrator.Result result = new FinalIntegrator(new GitService(repo), store,
                LspServiceFactory.NONE, List.of()).integrate(run);

            assertThat(result.ok()).isFalse();
            assertThat(result.failure())
                .contains("task-a")
                .contains(".swarmcoder/guidelines/project/planted.md")
                .contains("protected");
        }
    }

    @Test
    void lockedModuleInWinningDiffParksInsteadOfMerging() throws Exception {
        initRepoWithWinners(false);
        // The task's write set explicitly covers the locked module — the operator's lock still
        // wins. A tool-checked write could never reach this point; a shell command inside the
        // worktree can, and `git add -A` sweeps it into the candidate diff.
        taskA.setWriteSet(Set.of("a.txt", "locked"));
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store, """
                diff --git a/locked/Core.java b/locked/Core.java
                --- a/locked/Core.java
                +++ b/locked/Core.java
                @@ -1,1 +1,2 @@
                 class Core {}
                +// slipped in by a shell command
                """);

            FinalIntegrator.Result result = new FinalIntegrator(new GitService(repo), store,
                LspServiceFactory.NONE, List.of("locked")).integrate(run);

            assertThat(result.ok()).isFalse();
            assertThat(result.failure()).contains("task-a").contains("locked module")
                .contains("locked/Core.java");
        }
    }

    /**
     * A winning diff that reaches outside its task's write set MERGES (2026-09-02).
     *
     * <p>It used to park the whole run here, with a brief saying a shell command must have written
     * it. That was true when the tools refused such writes; it is not true now that they record
     * them instead, so the same rule would only have moved the old kill from turn 10 to the last
     * stage of the run and thrown away every task's work rather than one candidate's. What still
     * parks is unchanged and is covered by the two tests above: the acceptance tests, a locked
     * module, {@code .swarmcoder/} and {@code .git/}.
     */
    @Test
    void aWinningDiffOutsideItsWriteSetMergesInsteadOfParking() throws Exception {
        initRepoWithWinners(false);
        taskA.setWriteSet(Set.of("a.txt"));
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store, """
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1,1 +1,2 @@
                 beta
                +touched a neighbouring file to make it compile
                """);

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
            assertThat(gitOut("show " + result.integrationBranch() + ":a.txt")).contains("changed by A");
        }
    }

    /**
     * The cost of that, and the answer to it.
     *
     * <p>Disjoint write sets used to make these merges conflict-free BY CONSTRUCTION. Allowing
     * out-of-set writes gives that guarantee up, so two winners can now reach the same file and the
     * second merge can conflict. That is the strongest argument against recording rather than
     * preventing, and this is what it costs: the run parks with a message that names the file and
     * both tasks that changed it. Every branch still exists and no work is lost - which is more
     * than could be said for the candidate the old rule killed at turn 102.
     */
    @Test
    void aConflictOverAFileNeitherTaskOwnsNamesBothTasks() throws Exception {
        initRepoWithWinners(true);   // both winner branches edit a.txt
        taskA.setWriteSet(Set.of("src/a"));
        taskB.setWriteSet(Set.of("src/b"));
        String touchesA = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,2 @@
             alpha
            +both of us needed this
            """;
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(taskA, taskB),
                List.of(new TaskEdge(taskA.id(), taskB.id())));
            Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD,
                RunState.FINAL_INTEGRATION, null, null, null, graph.id(), null,
                Instant.now(), null);
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                store.root().candidateArchives.put(UUID.randomUUID(),
                    Lazy.Reference(winner(taskA, "swarm/" + taskA.id() + "/0", touchesA)));
                store.root().candidateArchives.put(UUID.randomUUID(),
                    Lazy.Reference(winner(taskB, "swarm/" + taskB.id() + "/0", touchesA)));
                return null;
            }).get();

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).isFalse();
            assertThat(result.failure())
                .contains("conflicted")
                .contains("Two tasks changed the same file")
                .contains("a.txt")
                .contains("task-a")
                .contains("task-b")
                .contains("nothing is lost");
        }
    }

    @Test
    void conflictParksInsteadOfGuessing() throws Exception {
        initRepoWithWinners(true);
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Run run = storeGraphAndWinners(store);

            FinalIntegrator.Result result =
                new FinalIntegrator(new GitService(repo), store).integrate(run);

            assertThat(result.ok()).isFalse();
            assertThat(result.failure()).contains("conflicted").contains("task-b");
        }
    }

    private Run storeGraphAndWinners(ArtifactStore store) throws Exception {
        return storeGraphAndWinners(store, "diff");
    }

    private Run storeGraphAndWinners(ArtifactStore store, String taskADiff) throws Exception {
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(taskA, taskB),
            List.of(new TaskEdge(taskA.id(), taskB.id())));
        Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.FINAL_INTEGRATION,
            null, null, null, graph.id(), null, Instant.now(), null);
        store.append(() -> {
            store.root().taskGraphs.put(graph.id(), graph);
            store.root().candidateArchives.put(UUID.randomUUID(),
                Lazy.Reference(winner(taskA, "swarm/" + taskA.id() + "/0", taskADiff)));
            store.root().candidateArchives.put(UUID.randomUUID(),
                Lazy.Reference(winner(taskB, "swarm/" + taskB.id() + "/0", "diff")));
            return null;
        }).get();
        return run;
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
    }

    private static CandidateSolution winner(Task task, String branch, String diff) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), 0, branch,
            new SamplingConfig("m", 0.2, 0, "p", "s"), diff, null, null, null,
            CandidateState.SELECTED, null);
    }

    private void git(String args) throws Exception {
        String out = gitOut(args);
        // gitOut throws on non-zero exit; output ignored here.
    }

    private String gitOut(String args) throws Exception {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.addAll(List.of("cmd.exe", "/c", "git " + args));
        } else {
            command.addAll(List.of("sh", "-c", "git " + args));
        }
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args + " failed: " + out);
        }
        return out;
    }
}
