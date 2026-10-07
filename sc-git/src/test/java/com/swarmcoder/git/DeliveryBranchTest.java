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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving the project's delivery branch onto finished work — the step that makes "delivered" mean the
 * code is where the next story's workers will find it, rather than a state change in a database.
 *
 * <p>This is the one operation that touches the operator's own checkout, so most of these tests are
 * about what it REFUSES. A machine that merged over somebody's unsaved work while they slept would
 * be a worse failure than any scheduling problem it was fixing.
 */
class DeliveryBranchTest {

    @TempDir
    Path repo;

    private GitService git;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(repo.resolve("Hello.java"), "class Hello {}");
        GitService.ensureRepo(repo);
        git = new GitService(repo);
    }

    @Test
    void finishedWorkReachesTheBranchTheOperatorWorksOn() throws Exception {
        String branch = git.currentBranch();
        assertThat(branch).isNotNull();
        String before = git.resolveCommit(branch);

        Path worktree = repo.resolveSibling("wt-" + System.nanoTime());
        git.addWorktree("swarm/integration/one", worktree, before);
        Files.writeString(worktree.resolve("Book.java"), "class Book {}");
        git.commitAll(worktree, "add the book record");
        String finished = git.headSha("swarm/integration/one");
        git.removeWorktree(worktree);

        assertThat(git.fastForwardBranchTo(branch, finished)).isNull();
        assertThat(git.resolveCommit(branch)).isEqualTo(finished);
        // …and the operator's own checkout really has the file, which is the whole point: this is
        // what the next story's worktrees are cut from.
        assertThat(repo.resolve("Book.java")).exists();
    }

    @Test
    void acceptingTheSameWorkTwiceIsNotAnError() throws Exception {
        String branch = git.currentBranch();
        String tip = git.resolveCommit(branch);
        assertThat(git.fastForwardBranchTo(branch, tip)).isNull();
        assertThat(git.resolveCommit(branch)).isEqualTo(tip);
    }

    @Test
    void uncommittedWorkInTheOperatorsCheckoutStopsIt() throws Exception {
        String branch = git.currentBranch();
        String before = git.resolveCommit(branch);

        Path worktree = repo.resolveSibling("wt-" + System.nanoTime());
        git.addWorktree("swarm/integration/two", worktree, before);
        Files.writeString(worktree.resolve("Book.java"), "class Book {}");
        git.commitAll(worktree, "add the book record");
        String finished = git.headSha("swarm/integration/two");
        git.removeWorktree(worktree);

        // The operator left something half-written in their own copy.
        Files.writeString(repo.resolve("Hello.java"), "class Hello { /* mid-edit */ }");

        String refused = git.fastForwardBranchTo(branch, finished);
        assertThat(refused).contains("not committed");
        assertThat(git.resolveCommit(branch)).isEqualTo(before);
        assertThat(Files.readString(repo.resolve("Hello.java"))).contains("mid-edit");
    }

    @Test
    void workThatIsNotOnTopOfTheBranchIsRefusedRatherThanForced() throws Exception {
        String branch = git.currentBranch();
        String before = git.resolveCommit(branch);

        // Finished on the old tip…
        Path worktree = repo.resolveSibling("wt-" + System.nanoTime());
        git.addWorktree("swarm/integration/three", worktree, before);
        Files.writeString(worktree.resolve("Book.java"), "class Book {}");
        git.commitAll(worktree, "add the book record");
        String finished = git.headSha("swarm/integration/three");
        git.removeWorktree(worktree);

        // …but the branch has moved on since.
        Files.writeString(repo.resolve("Other.java"), "class Other {}");
        Path second = repo.resolveSibling("wt2-" + System.nanoTime());
        git.addWorktree("swarm/integration/four", second, before);
        Files.writeString(second.resolve("Other.java"), "class Other {}");
        git.commitAll(second, "something else");
        String moved = git.headSha("swarm/integration/four");
        git.removeWorktree(second);
        Files.deleteIfExists(repo.resolve("Other.java"));
        assertThat(git.fastForwardBranchTo(branch, moved)).isNull();

        String refused = git.fastForwardBranchTo(branch, finished);
        assertThat(refused).contains("no longer sits directly on top of");
        assertThat(git.resolveCommit(branch)).isEqualTo(moved);
    }

    @Test
    void knowsWhetherWorkIsAlreadyInTheBranch() throws Exception {
        String branch = git.currentBranch();
        String tip = git.resolveCommit(branch);
        assertThat(git.isAncestor(tip, tip)).isTrue();
    }
}
