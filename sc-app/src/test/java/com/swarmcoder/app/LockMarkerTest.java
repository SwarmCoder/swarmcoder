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
package com.swarmcoder.app;

import com.swarmcoder.domain.Project;
import com.swarmcoder.git.GitService;
import com.swarmcoder.runtime.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-source lock markers, and the property that makes them worth having: they are read from the
 * BASE COMMIT, so the thing being restrained cannot remove its own restraint.
 */
class LockMarkerTest {

    @Test
    void aMarkedFileIsLockedAndDeletingTheMarkerDoesNotUnlockIt(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.invalid");
        git(repo, "config", "user.name", "test");
        Path locked = repo.resolve("src/Ledger.java");
        Files.createDirectories(locked.getParent());
        Files.writeString(locked,
            "// swarmcoder:locked  reason: settlement maths\npublic class Ledger {}\n");
        Files.writeString(repo.resolve("src/Other.java"), "public class Other {}\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "initial");

        Project project = new Project(UUID.randomUUID(), "demo", repo.toString(),
            List.of(), Instant.now(), false);
        GitService git = new GitService(repo);

        List<String> locks = ProjectContext.lockedPathsFor(project, List.of(), git, false);
        assertThat(locks).contains("src/Ledger.java");
        assertThat(locks).doesNotContain("src/Other.java");

        // A worker strips the marker in its worktree. The lock is resolved from HEAD, so this
        // changes nothing — which is the entire point: a guard the guarded thing can delete is
        // not a guard.
        Files.writeString(locked, "public class Ledger {}\n");
        assertThat(ProjectContext.lockedPathsFor(project, List.of(), git, false))
            .contains("src/Ledger.java");
    }

    @Test
    void theTrustKernelIsLockedByDefaultAndUnlockingItIsExplicit(@TempDir Path repo) {
        Project project = new Project(UUID.randomUUID(), "demo", repo.toString(),
            List.of(), Instant.now(), false);

        // Default: the containment code is locked without anyone configuring it, because a worker
        // modifying it disables the checks that would catch the modification.
        assertThat(ProjectContext.lockedPathsFor(project, List.of(), null, false))
            .containsAll(PathPolicy.TRUST_KERNEL);

        // Released only deliberately.
        assertThat(ProjectContext.lockedPathsFor(project, List.of(), null, true))
            .doesNotContainAnyElementsOf(PathPolicy.TRUST_KERNEL);
    }

    @Test
    void globalAndProjectLocksAreUnionedWithTheKernel(@TempDir Path repo) {
        Project project = new Project(UUID.randomUUID(), "demo", repo.toString(),
            List.of(), Instant.now(), false);

        List<String> locks = ProjectContext.lockedPathsFor(project,
            List.of("src/main/java/com/acme/payments"), null, false);

        assertThat(locks).contains("src/main/java/com/acme/payments");
        assertThat(locks).containsAll(PathPolicy.TRUST_KERNEL);
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
        }
    }
}
