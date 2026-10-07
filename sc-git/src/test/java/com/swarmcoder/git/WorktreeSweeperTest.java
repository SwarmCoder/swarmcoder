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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep, against real git repositories and real linked worktrees.
 *
 * <p>Four things have to hold and none of them can be shown with mocks: an abandoned clean
 * worktree goes, a worktree holding uncommitted work stays and is reported, a worktree whose run is
 * still live is not touched, and a directory that merely looks like a worktree is left exactly
 * where it is.
 */
class WorktreeSweeperTest {

    @TempDir
    Path work;

    @Test
    void anAbandonedCleanWorktreeIsRemovedThroughGit() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path abandoned = root.resolve("candidate-1");
        new GitService(repo).addWorktree("swarm/task/0", abandoned, "HEAD");
        age(abandoned);

        WorktreeSweeper.Result result = WorktreeSweeper.sweep(root, stale -> false);

        assertThat(result.removed()).hasSize(1);
        assertThat(result.removed().get(0)).contains("candidate-1").contains("swarm/task/0");
        assertThat(Files.exists(abandoned)).isFalse();
        // And git agrees it is gone, rather than carrying a prunable entry for ever.
        assertThat(worktreeList(repo)).doesNotContain("candidate-1");
    }

    @Test
    void aWorktreeWithUncommittedWorkIsReportedAndKept() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path unfinished = root.resolve("candidate-2");
        new GitService(repo).addWorktree("swarm/task/1", unfinished, "HEAD");
        Files.writeString(unfinished.resolve("Half.java"), "class Half {}");
        age(unfinished);

        WorktreeSweeper.Result result = WorktreeSweeper.sweep(root, stale -> false);

        assertThat(result.removed()).isEmpty();
        assertThat(result.withWork()).hasSize(1);
        assertThat(result.withWork().get(0)).contains("candidate-2").contains("uncommitted");
        assertThat(Files.exists(unfinished.resolve("Half.java"))).isTrue();
    }

    @Test
    void aWorktreeOfALiveRunIsNeverTouched() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path live = root.resolve("candidate-3");
        new GitService(repo).addWorktree("swarm/task/2", live, "HEAD");
        age(live);

        // The caller's answer is final. Everything else about this worktree says "sweep me".
        WorktreeSweeper.Result result = WorktreeSweeper.sweep(root, stale -> true);

        assertThat(result.removed()).isEmpty();
        assertThat(result.kept().get(0)).contains("candidate-3").contains("still live");
        assertThat(Files.exists(live)).isTrue();
    }

    @Test
    void gitIsWhatIdentifiesAWorktreeAndTheBranchItHasCheckedOut() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path candidate = root.resolve("candidate-4");
        new GitService(repo).addWorktree("swarm/abc/7", candidate, "HEAD");
        age(candidate);

        StringBuilder seen = new StringBuilder();
        WorktreeSweeper.sweep(root, stale -> {
            seen.append(stale.name()).append('|').append(stale.branch())
                .append('|').append(stale.mainRepo());
            return true;
        });

        assertThat(seen.toString()).contains("candidate-4|swarm/abc/7|");
        assertThat(seen.toString()).contains(repo.getFileName().toString());
    }

    @Test
    void aFolderThatIsNotAGitWorktreeIsLeftWhereItIs() throws Exception {
        Path root = Files.createDirectories(work.resolve("wt"));
        Path lookalike = Files.createDirectories(root.resolve("not-a-worktree"));
        Files.writeString(lookalike.resolve("notes.txt"), "somebody's files");
        age(lookalike);

        WorktreeSweeper.Result result = WorktreeSweeper.sweep(root, stale -> false);

        assertThat(result.removed()).isEmpty();
        assertThat(result.kept().get(0)).contains("not a linked git worktree");
        assertThat(Files.exists(lookalike.resolve("notes.txt"))).isTrue();
    }

    @Test
    void aWorktreeWhoseRepositoryIsGoneIsRemovedBecauseNothingCanBeAskedAboutIt() throws Exception {
        Path repo = repo("doomed");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path orphan = root.resolve("candidate-5");
        new GitService(repo).addWorktree("swarm/task/0", orphan, "HEAD");
        deleteTree(repo);
        age(orphan);

        WorktreeSweeper.Result result = WorktreeSweeper.sweep(root, stale -> false);

        assertThat(result.removed()).hasSize(1);
        assertThat(result.removed().get(0)).contains("no longer exists");
        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    void nothingRecentIsTouchedHoweverDeadItLooks() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        Path fresh = root.resolve("candidate-6");
        new GitService(repo).addWorktree("swarm/task/0", fresh, "HEAD");
        // Not aged: a run that started seconds ago looks exactly like an abandoned one.

        WorktreeSweeper.Result result =
            WorktreeSweeper.sweep(root, stale -> false, Duration.ofHours(6), 100);

        assertThat(result.removed()).isEmpty();
        assertThat(result.kept().get(0)).contains("still going");
    }

    @Test
    void theSweepStopsAtItsPerStartLimit() throws Exception {
        Path repo = repo("project");
        Path root = Files.createDirectories(work.resolve("wt"));
        for (int i = 0; i < 3; i++) {
            Path candidate = root.resolve("candidate-" + i);
            new GitService(repo).addWorktree("swarm/task/" + i, candidate, "HEAD");
            age(candidate);
        }

        WorktreeSweeper.Result result =
            WorktreeSweeper.sweep(root, stale -> false, Duration.ofMinutes(1), 2);

        assertThat(result.removed()).hasSize(2);
        assertThat(result.kept()).anyMatch(line -> line.contains("per-start limit"));
    }

    // --- helpers --------------------------------------------------------------------------------

    private Path repo(String name) throws Exception {
        Path repo = Files.createDirectories(work.resolve(name));
        Files.writeString(repo.resolve("Hello.java"), "class Hello {}");
        assertThat(GitService.ensureRepo(repo)).isTrue();
        return repo;
    }

    /** Backdates the directory so the sweep's age guard does not spare it. */
    private static void age(Path directory) throws Exception {
        Files.setLastModifiedTime(directory,
            FileTime.from(Instant.now().minus(Duration.ofDays(30))));
    }

    private static String worktreeList(Path repo) throws Exception {
        Process process = new ProcessBuilder("git", "worktree", "list")
            .directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return out;
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort: the point is that git can no longer open it
                }
            });
        }
    }
}
