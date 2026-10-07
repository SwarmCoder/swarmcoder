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

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.Run;
import com.swarmcoder.git.GitService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test an operator corrected on the run's tests branch while the run was parked is the test the
 * resumed run uses (live run 63, 2026-10-02: the question named that branch, the operator
 * committed a corrected test to it and answered "retry", and the retry placed the old file into
 * the red-check tree and every candidate, because the run reads a recorded commit, not the branch).
 */
class ATestCorrectedByHandIsUsedOnResumeTest {

    private static final String TEST_FILE = "src/test/java/swarm/accept/EditTest.java";

    @TempDir
    Path world;

    @Test
    void theRunFollowsItsTestsBranchWhenAnOperatorCommittedToIt() throws Exception {
        Path repo = world.resolve("repo");
        assertThat(GitService.ensureRepo(repo)).isTrue();
        GitService git = new GitService(repo);
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setBaseCommit(git.resolveCommit("HEAD"));
        String branch = GreenfieldWorkflow.testsBranch(run);

        // what TEST_AUTHORING does: the tests are committed on the run's own branch
        Path worktree = world.resolve("tests-wt");
        git.addWorktree(branch, worktree, run.startPoint());
        Files.createDirectories(worktree.resolve(TEST_FILE).getParent());
        Files.writeString(worktree.resolve(TEST_FILE), "class EditTest { /* broken */ }\n");
        git.commitAll(worktree, "Acceptance tests");
        String recorded = git.headSha(branch);
        run.setAcceptanceTestsCommit(recorded);

        assertThat(OperatorCorrectedTests.adopt(git, run)).as("nothing moved, nothing said").isNull();
        assertThat(run.acceptanceTestsCommit()).isEqualTo(recorded);

        // the operator corrects the file on that branch while the run is parked
        Files.writeString(worktree.resolve(TEST_FILE), "class EditTest { /* corrected */ }\n");
        git.commitAll(worktree, "Correct the test by hand");
        String corrected = git.headSha(branch);
        git.removeWorktree(worktree);
        assertThat(corrected).isNotEqualTo(recorded);

        String said = OperatorCorrectedTests.adopt(git, run);

        assertThat(run.acceptanceTestsCommit()).isEqualTo(corrected);
        assertThat(new String(git.fileAt(run.acceptanceTestsCommit(), TEST_FILE)))
            .as("what the red check and every candidate are now given").contains("corrected");
        assertThat(said).contains(branch).contains("correction made by hand");
        assertThat(OperatorCorrectedTests.whereToCorrect(run)).contains(branch).contains("resume");
    }
}
