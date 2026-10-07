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
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.git.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The progress branch a later wave is cut from: what it holds when the wave's winners merge, and
 * what the operator is told when they do not.
 *
 * <p>A conflict here is a NEW moment. Winners used to be merged once, at the end of the run, so a
 * conflict could only ever stop a run that had already finished building. Now they are merged
 * between waves, and a conflict stops a run that still has work in front of it — which is why the
 * brief has to say that the work already done is safe and that resuming will not repeat it.
 */
@ModelCodeOnThisPc
class WaveIntegratorTest {

    @TempDir
    Path repo;

    private String base;
    private boolean conflicting;
    private Task first;
    private Task second;

    @Test
    void bothWinnersLandOnTheProgressBranchAndThatIsWhatTheNextWaveIsCutFrom() throws Exception {
        initRepo(false);
        UUID runId = UUID.randomUUID();

        WaveIntegrator.Result result = new WaveIntegrator(new GitService(repo)).integrateWave(
            runId, base, List.of(winner(first), winner(second)), new LinkedHashMap<>());

        assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
        assertThat(result.commit()).isNotNull();
        assertThat(git("show " + result.commit() + ":a.txt")).contains("changed by first");
        assertThat(git("show " + result.commit() + ":b.txt")).contains("changed by second");
    }

    @Test
    void aConflictBetweenTwoWinnersParksTheRunNamingBothTasksAndTheSharedFile() throws Exception {
        initRepo(true);
        UUID runId = UUID.randomUUID();
        Map<String, String> claimedBy = new LinkedHashMap<>();

        WaveIntegrator.Result result = new WaveIntegrator(new GitService(repo)).integrateWave(
            runId, base, List.of(winner(first), winner(second)), claimedBy);

        assertThat(result.ok()).isFalse();
        assertThat(result.commit()).isNull();
        assertThat(result.failure())
            .as("both pieces of work are named, and so is the file neither of them owns")
            .contains("Deliver the first thing")
            .contains("Deliver the second thing")
            .contains("shared/notes.txt");
        assertThat(result.failure())
            .as("and it says the run has work in front of it, which an end-of-run conflict "
                + "never has")
            .contains("BETWEEN waves")
            .contains("nothing is lost");
    }

    /**
     * The defect from harness run 11 (2026-09-03): a worker's helper script,
     * {@code insert_dep.py}, rode along in the winning diff onto the progress branch, where the
     * next wave's worktrees are cut from it. The script never belonged in the repository — it is
     * not a source or resource file and not in the task's write set — so it must not survive the
     * merge, and the operator's log must say it was removed and why.
     */
    @Test
    void aStrayFileFromTheWinningDiffIsDroppedBeforeReachingTheProgressBranch() throws Exception {
        git("init -q");
        Files.writeString(repo.resolve("a.txt"), "alpha\n");
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"),
            "toolchain: gradle\ncompile:\n  - \"echo wave-compile-ok\"\ntimeoutSeconds: 60\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        String strayBase = git("rev-parse HEAD").strip();

        Task strayTask = task("Add the dependency", Set.of("a.txt"));
        String branch = branchOf(strayTask);
        git("checkout -q -b " + branch);
        Files.writeString(repo.resolve("a.txt"), "alpha changed\n");
        Files.writeString(repo.resolve("insert_dep.py"), "# a worker's scratch helper script\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m stray");
        git("checkout -q master");

        String diff = unifiedHeader("a.txt") + unifiedHeader("insert_dep.py");
        CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), strayTask.id(), 0,
            branch, new SamplingConfig("fake", 0.2, 0L, "minimal-diff", "full-files"),
            diff, null, null, null, CandidateState.SELECTED, null);
        WaveIntegrator.Winner winner = new WaveIntegrator.Winner(strayTask, candidate);

        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WaveIntegrator.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            WaveIntegrator.Result result = new WaveIntegrator(new GitService(repo)).integrateWave(
                UUID.randomUUID(), strayBase, List.of(winner), new LinkedHashMap<>());

            assertThat(result.ok()).as(String.valueOf(result.failure())).isTrue();
            assertThat(result.commit()).isNotNull();
            assertThat(git("show " + result.commit() + ":a.txt"))
                .as("the winner's real change still lands")
                .contains("alpha changed");
            assertThatThrownBy(() -> git("show " + result.commit() + ":insert_dep.py"))
                .as("the stray script must not reach the progress branch")
                .isInstanceOf(IllegalStateException.class);
            assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage))
                .as("the operator's log must say the file was dropped and why")
                .anyMatch(message -> message.contains("dropped insert_dep.py from the winner of")
                    && message.contains("not a source file and not in the write set"));
        } finally {
            logger.detachAppender(captured);
        }
    }

    // --- the repository --------------------------------------------------------------------------

    /**
     * A base commit, then one winner branch per task. When {@code conflicting}, both winners create
     * the SAME file that neither task's write set covers — the only way two winners of one wave can
     * collide, since their own paths are disjoint by construction.
     */
    private void initRepo(boolean conflicting) throws Exception {
        this.conflicting = conflicting;
        git("init -q");
        Files.writeString(repo.resolve("a.txt"), "alpha\n");
        Files.writeString(repo.resolve("b.txt"), "beta\n");
        Files.createDirectories(repo.resolve(".swarmcoder"));
        Files.writeString(repo.resolve(".swarmcoder/verify.yaml"),
            "toolchain: gradle\ncompile:\n  - \"echo wave-compile-ok\"\ntimeoutSeconds: 60\n");
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m base");
        base = git("rev-parse HEAD").strip();

        first = task("Deliver the first thing", Set.of("a.txt"));
        second = task("Deliver the second thing", Set.of("b.txt"));

        git("checkout -q -b " + branchOf(first));
        Files.writeString(repo.resolve("a.txt"), "alpha changed by first\n");
        if (conflicting) {
            Files.createDirectories(repo.resolve("shared"));
            Files.writeString(repo.resolve("shared/notes.txt"), "the first task's note\n");
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m first");
        git("checkout -q master");

        git("checkout -q -b " + branchOf(second));
        Files.writeString(repo.resolve("b.txt"), "beta changed by second\n");
        if (conflicting) {
            Files.createDirectories(repo.resolve("shared"));
            Files.writeString(repo.resolve("shared/notes.txt"), "the second task's note\n");
        }
        git("add -A");
        git("-c user.email=t@t -c user.name=t commit -q -m second");
        git("checkout -q master");
    }

    private static String branchOf(Task task) {
        return "swarm/" + task.id() + "/0";
    }

    /**
     * The winner as the engine hands it over: a branch and the diff it produced. The diff is what
     * the audit reads, so the stray file has to appear in it the way git writes one.
     */
    private WaveIntegrator.Winner winner(Task task) {
        String own = task.writeSet().iterator().next();
        StringBuilder diff = new StringBuilder(unifiedHeader(own));
        if (conflicting) {
            diff.append(unifiedHeader("shared/notes.txt"));
        }
        CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), task.id(), 0,
            branchOf(task), new SamplingConfig("fake", 0.2, 0L, "minimal-diff", "full-files"),
            diff.toString(), null, null, null, CandidateState.SELECTED, null);
        return new WaveIntegrator.Winner(task, candidate);
    }

    private static String unifiedHeader(String path) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
            + "\n@@ -1 +1 @@\n-old\n+new\n";
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
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
