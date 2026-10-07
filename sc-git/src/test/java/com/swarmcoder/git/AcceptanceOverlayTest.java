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
package com.swarmcoder.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worktree ends up holding exactly the acceptance tests one task claims — nothing it inherited,
 * nothing another task claims — and nothing about the branch changes.
 *
 * <p>The inherited files are the real case from 2026-09-02: four test files from four dead runs
 * committed on the demo repository's delivery branch, each naming classes the current plan never
 * creates, breaking test-compile for every candidate before its own code was looked at.
 *
 * <p><b>Worktree paths live under their own {@code @TempDir}, not a fixed name beside {@code repo}.</b>
 * Proven on {@code ac5f538}: {@code repo} is one, but a path built from {@code repo.resolveSibling(
 * "wt-a")} resolves against {@code repo}'s PARENT — the shared OS temp root — so "wt-a" was the same
 * absolute path on every run of this test, forever, on one machine. A second run, or one after a
 * crash that skipped cleanup, hit an existing non-empty directory and {@code git worktree add -b}
 * exited 128. {@code worktrees} below is its own {@code @TempDir}, freshly named by JUnit every run,
 * so a name reused inside it can never collide across runs. {@link #removeAnyWorktreesThisTestMade}
 * also removes the git-side worktree registration explicitly and prunes stale ones — the directory
 * disappearing on its own (e.g. JUnit deleting a failed run's {@code @TempDir}) is not enough,
 * because the administrative entry under {@code repo/.git/worktrees/<name>} can outlive it and is
 * exactly what turns the next {@code worktree add} into another exit 128.
 */
class AcceptanceOverlayTest {

    private static final String SERVER = "server/src/test/java/swarm";
    private static final List<String> WORKTREE_NAMES = List.of("wt-a", "wt-b", "wt-c", "wt-d");

    @TempDir
    Path repo;

    /** Every worktree this test creates lives here — its own {@code @TempDir}, never a fixed path. */
    @TempDir
    Path worktrees;

    private GitService git;
    private String base;
    private String tests;

    @BeforeEach
    void aRepositoryWithStaleTestsAndARunRef() throws Exception {
        Cli.git(repo, "init", "-q");
        write("README.md", "hello\n");
        // What earlier runs left behind: in the module, at the root, and in another module.
        write(SERVER + "/accept/StaleFromRunOne.java", "package swarm.accept; class StaleFromRunOne {}");
        write("src/test/java/swarm/accept/RootStray.java", "package swarm.accept; class RootStray {}");
        write("client/src/test/java/swarm/accept/ClientStray.java", "package swarm.accept; class ClientStray {}");
        // A neighbour that is NOT an acceptance-test tree and must be left alone.
        write("server/src/test/java/demo/UnitTest.java", "package demo; class UnitTest {}");
        Cli.git(repo, "add", "-A");
        Cli.git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "base");
        base = Cli.git(repo, "rev-parse", "HEAD").strip();

        // The run's own tests commit: the base plus two tasks' files.
        Cli.git(repo, "checkout", "-q", "-b", "swarm/tests/run-1");
        write(SERVER + "/accept/FeatureTest.java", "package swarm.accept; class FeatureTest { /* task A */ }");
        write(SERVER + "/accept/ListingTest.java", "package swarm.accept; class ListingTest { /* task B */ }");
        Cli.git(repo, "add", "-A");
        Cli.git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "tests");
        tests = Cli.git(repo, "rev-parse", "HEAD").strip();
        Cli.git(repo, "checkout", "-q", "master");
        git = new GitService(repo);
    }

    /**
     * Removes the git-side worktree registration for every path this test might have created, and
     * prunes anything left dangling — even on a failed test, so a thrown assertion can never be
     * what leaves the next run to hit exit 128. Safe to call for a name this particular test never
     * used: {@link GitService#removeWorktree} only logs a warning when there is nothing there.
     */
    @AfterEach
    void removeAnyWorktreesThisTestMade() {
        if (git == null) {
            return;
        }
        for (String name : WORKTREE_NAMES) {
            git.removeWorktree(worktrees.resolve(name));
        }
        try {
            Cli.git(repo, "worktree", "prune");
        } catch (Exception e) {
            // best effort — nothing left to prune if the repo itself never finished setting up
        }
    }

    @Test
    void aTaskGetsItsOwnFilesAndNothingElse() throws Exception {
        Path worktree = worktrees.resolve("wt-a");
        git.addWorktree("swarm/task-a/0", worktree, base);

        AcceptanceOverlay.Outcome outcome = AcceptanceOverlay.reduceTo(git, worktree, SERVER, tests,
            List.of(SERVER + "/accept/FeatureTest.java"));

        assertThat(outcome.placed()).containsExactly(SERVER + "/accept/FeatureTest.java");
        assertThat(outcome.missing()).isEmpty();
        assertThat(outcome.cleared()).containsExactlyInAnyOrder(
            SERVER + "/accept/StaleFromRunOne.java",
            "src/test/java/swarm/accept/RootStray.java",
            "client/src/test/java/swarm/accept/ClientStray.java");
        assertThat(Files.readString(worktree.resolve(SERVER + "/accept/FeatureTest.java")))
            .contains("task A");
        assertThat(worktree.resolve(SERVER + "/accept/ListingTest.java"))
            .as("the other task's file is not placed").doesNotExist();
        assertThat(worktree.resolve(SERVER + "/accept/StaleFromRunOne.java"))
            .as("a dead run's test is gone").doesNotExist();
        assertThat(worktree.resolve("src/test/java/swarm")).as("the root stray tree is gone").doesNotExist();
        assertThat(worktree.resolve("client/src/test/java/swarm")).doesNotExist();
        assertThat(worktree.resolve("server/src/test/java/demo/UnitTest.java"))
            .as("an ordinary unit test beside the protected tree is untouched").exists();
        // Nothing about the branch moved: the overlay is working-tree only.
        assertThat(Cli.git(worktree, "rev-parse", "HEAD").strip()).isEqualTo(base);
        assertThat(Cli.git(worktree, "diff", "--cached", "--name-only")).isBlank();
    }

    @Test
    void aTaskClaimingNothingEndsWithAnEmptyTree() throws Exception {
        Path worktree = worktrees.resolve("wt-b");
        git.addWorktree("swarm/task-b/0", worktree, base);

        AcceptanceOverlay.Outcome outcome =
            AcceptanceOverlay.reduceTo(git, worktree, SERVER, tests, List.of());

        assertThat(outcome.placed()).isEmpty();
        assertThat(outcome.cleared()).hasSize(3);
        assertThat(worktree.resolve(SERVER)).doesNotExist();
    }

    @Test
    void aFileTheTestsCommitDoesNotHoldIsNamedNotInvented() throws Exception {
        Path worktree = worktrees.resolve("wt-c");
        git.addWorktree("swarm/task-c/0", worktree, base);

        AcceptanceOverlay.Outcome outcome = AcceptanceOverlay.reduceTo(git, worktree, SERVER, tests,
            List.of(SERVER + "/accept/FeatureTest.java", SERVER + "/accept/NeverWritten.java"));

        assertThat(outcome.placed()).containsExactly(SERVER + "/accept/FeatureTest.java");
        assertThat(outcome.missing()).containsExactly(SERVER + "/accept/NeverWritten.java");
        assertThat(outcome.describe()).contains("NOT FOUND").contains("NeverWritten");
    }

    @Test
    void aWorktreeCutFromTheTestsCommitCanBeReducedAndRestored() throws Exception {
        // The red-check and the integrator cut from the tests commit itself, reduce per task, and
        // put the tree back before the next merge.
        Path worktree = worktrees.resolve("wt-d");
        git.addWorktree("swarm/redcheck/run-1", worktree, tests);

        AcceptanceOverlay.reduceTo(git, worktree, SERVER, tests, List.of(SERVER + "/accept/ListingTest.java"));
        assertThat(worktree.resolve(SERVER + "/accept/FeatureTest.java")).doesNotExist();
        assertThat(worktree.resolve(SERVER + "/accept/ListingTest.java")).exists();

        git.restoreTree(worktree, ".");
        assertThat(worktree.resolve(SERVER + "/accept/FeatureTest.java")).exists();
        assertThat(worktree.resolve(SERVER + "/accept/StaleFromRunOne.java")).exists();
        assertThat(Cli.git(worktree, "status", "--porcelain")).isBlank();
    }

    @Test
    void aCandidateThatAlteredItsTestIsVerifiedAgainstTheCommittedOne() throws Exception {
        // Workers may now read the test; reading must never become writing a different one.
        Path worktree = worktrees.resolve("wt-d");
        git.addWorktree("swarm/task-d/0", worktree, base);
        writeIn(worktree, SERVER + "/accept/FeatureTest.java",
            "package swarm.accept; class FeatureTest { /* weakened by the candidate */ }");
        writeIn(worktree, SERVER + "/accept/ExtraTest.java", "package swarm.accept; class ExtraTest {}");

        AcceptanceOverlay.Outcome outcome = AcceptanceOverlay.reduceTo(git, worktree, SERVER, tests,
            List.of(SERVER + "/accept/FeatureTest.java"));

        assertThat(outcome.placed()).containsExactly(SERVER + "/accept/FeatureTest.java");
        assertThat(Files.readString(worktree.resolve(SERVER + "/accept/FeatureTest.java")))
            .contains("task A").doesNotContain("weakened");
        assertThat(worktree.resolve(SERVER + "/accept/ExtraTest.java"))
            .as("a test file the candidate added is gone").doesNotExist();
    }

    private static void writeIn(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** The git CLI, for setting up and reading what the class under test may not go through. */
    static final class Cli {
        static String git(Path dir, String... args) throws Exception {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            }
            return out;
        }
    }
}
