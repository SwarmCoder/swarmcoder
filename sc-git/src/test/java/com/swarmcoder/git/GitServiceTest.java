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

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Auto-init: a plain project folder becomes a usable repo so workers can create worktrees. */
class GitServiceTest {

    @TempDir
    Path folder;

    @Test
    void ensureRepoInitializesAPlainFolderWithACommit() throws Exception {
        // A plain folder with a source file but no .git — the exact case that failed every
        // worker with "no worktree".
        Files.writeString(folder.resolve("Hello.java"), "class Hello {}");
        assertThat(folder.resolve(".git").toFile().exists()).isFalse();

        assertThat(GitService.ensureRepo(folder)).isTrue();
        assertThat(folder.resolve(".git").toFile().exists()).isTrue();
        assertThat(Files.readString(folder.resolve(".gitignore"))).contains("target/");

        // A worker can now branch a worktree from HEAD — proof the repo has a commit.
        GitService git = new GitService(folder);
        assertThat(git.isEnabled()).isTrue();
        Path worktree = folder.resolveSibling("wt-" + System.nanoTime());
        git.addWorktree("swarm/test/0", worktree);
        assertThat(worktree.resolve("Hello.java").toFile().exists()).isTrue();
        git.removeWorktree(worktree);
    }

    @Test
    void ensureRepoIsIdempotentOnAnExistingRepo() {
        assertThat(GitService.ensureRepo(folder)).isTrue();
        assertThat(GitService.ensureRepo(folder)).isTrue(); // no error, still ready
        assertThat(new GitService(folder).isEnabled()).isTrue();
    }

    /**
     * The exact failure from the 2026-08-29 incident: a run is resumed after its process was
     * killed, and the resumed worker dispatches the SAME deterministic branch name
     * ({@code swarm/<taskId>/<workerIndex>}) the dead attempt already created. Reproduced against a
     * real repository — a fresh-repo unit test cannot fail on this the way the log did, which is
     * exactly why the bug shipped.
     *
     * <p>This covers the state the startup worktree sweeper leaves behind: the OLD worktree
     * directory is already gone (swept, or simply never re-created after a crash), but the branch it
     * was checked out on is still there — {@code addWorktree} alone refuses it outright.
     */
    @Test
    void resumingARunArchivesAPreExistingBranchLeftFromAKilledAttempt() throws Exception {
        assertThat(GitService.ensureRepo(folder)).isTrue();
        GitService git = new GitService(folder);
        String branch = "swarm/deadbeef-task/3";
        String firstRunId = UUID.randomUUID().toString();

        // The killed attempt: a worktree was created and (for whatever reason — the crash, or a
        // sweep since) is no longer there, but the branch it made survives.
        Path oldWorktree = folder.resolveSibling("wt-old-" + System.nanoTime());
        String priorHead;
        try (Git repo = Git.open(folder.toFile())) {
            priorHead = repo.getRepository().resolve("HEAD").getName();
        }
        git.addWorktree(branch, oldWorktree, "HEAD");
        git.removeWorktree(oldWorktree); // worktree gone; branch remains — the sweeper's own doc

        // The resumed attempt dispatches the identical branch name into a brand new worktree path.
        Path newWorktree = folder.resolveSibling("wt-new-" + System.nanoTime());
        Path result = git.addOrResumeWorktree(branch, newWorktree, "HEAD", firstRunId);

        assertThat(result).isEqualTo(newWorktree);
        assertThat(newWorktree.toFile().exists()).isTrue();
        assertThat(git.headSha(branch)).isNotNull(); // the branch exists again — freshly recreated

        // The old branch's commit is not lost: it is reachable from the archive ref this run's id
        // names, not deleted outright.
        try (Git verify = Git.open(folder.toFile())) {
            String archived = verify.getRepository()
                .resolve("refs/swarm-archive/" + firstRunId + "/" + branch).getName();
            assertThat(archived).isEqualTo(priorHead);
        }
        git.removeWorktree(newWorktree);
    }

    /**
     * The state the worktree sweeper leaves it in when it has NOT yet run (dispatch races the
     * sweeper on startup): the old worktree directory is still there, with the branch physically
     * checked out in it. {@code git branch -D} refuses a checked-out branch, so archiving must
     * detach that worktree first — this is the case that proves it does.
     */
    @Test
    void resumingARunDetachesAWorktreeStillCheckedOutOnTheOldBranch() throws Exception {
        assertThat(GitService.ensureRepo(folder)).isTrue();
        GitService git = new GitService(folder);
        String branch = "swarm/deadbeef-task/7";

        Path oldWorktree = folder.resolveSibling("wt-live-" + System.nanoTime());
        git.addWorktree(branch, oldWorktree, "HEAD"); // left checked out — not removed

        Path newWorktree = folder.resolveSibling("wt-live-new-" + System.nanoTime());
        assertThatCode(() ->
            git.addOrResumeWorktree(branch, newWorktree, "HEAD", UUID.randomUUID().toString()))
            .doesNotThrowAnyException();

        assertThat(newWorktree.toFile().exists()).isTrue();
        // The stale worktree's own directory is gone too: `worktree remove` clears the git-side
        // registration AND the folder in one step.
        assertThat(oldWorktree.toFile().exists()).isFalse();
        git.removeWorktree(newWorktree);
    }

    /**
     * Killed twice in a row on the same slot must not be any different from killed once: the
     * second resume finds the branch the FIRST resume just (re-)created and archives that one too,
     * rather than failing or looping.
     */
    @Test
    void resumingTwiceInARowOnTheSameSlotStaysSafe() throws Exception {
        assertThat(GitService.ensureRepo(folder)).isTrue();
        GitService git = new GitService(folder);
        String branch = "swarm/deadbeef-task/9";

        Path first = folder.resolveSibling("wt-1-" + System.nanoTime());
        git.addOrResumeWorktree(branch, first, "HEAD", UUID.randomUUID().toString());
        git.removeWorktree(first);

        Path second = folder.resolveSibling("wt-2-" + System.nanoTime());
        assertThatCode(() ->
            git.addOrResumeWorktree(branch, second, "HEAD", UUID.randomUUID().toString()))
            .doesNotThrowAnyException();
        assertThat(second.toFile().exists()).isTrue();
        git.removeWorktree(second);
    }
}
