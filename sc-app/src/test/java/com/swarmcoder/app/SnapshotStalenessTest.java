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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale-snapshot warning: raised when the code that decides links 1 to 9 has moved since the
 * snapshot was saved, silent when only the worker side has (2026-09-25).
 *
 * <p>Proved on a throwaway repository laid out like SwarmCoder's, so it does not depend on what
 * this checkout's own history happens to contain.
 */
class SnapshotStalenessTest {

    @TempDir
    Path tmp;

    @Test
    void theFrontHalfIsThePlannerTheWizardsAndTheStoredShapesNotTheWorkers() {
        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-workflow/src/main/java/com/swarmcoder/workflow/ArchitectClient.java")).isTrue();
        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-console/src/main/java/com/swarmcoder/console/PlanningFlowServiceImpl.java")).isTrue();
        assertThat(SnapshotStaleness.affectsFrontHalf("sc-domain\\src\\main\\java\\X.java")).isTrue();
        assertThat(SnapshotStaleness.affectsFrontHalf("dev/bookshelf-requirements.md")).isTrue();
        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-app/src/test/java/com/swarmcoder/app/EndToEndLoopTest.java")).isTrue();

        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-swarm/src/main/java/com/swarmcoder/swarm/WorkerLoop.java")).isFalse();
        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-runtime/src/main/java/com/swarmcoder/runtime/KoogAgentRuntime.java")).isFalse();
        assertThat(SnapshotStaleness.affectsFrontHalf(
            "sc-workflow/src/test/java/com/swarmcoder/workflow/SomeTest.java")).isFalse();
        assertThat(SnapshotStaleness.affectsFrontHalf("docs/TESTING.md")).isFalse();
    }

    @Test
    void aCommitToTheWorkersOnlyRaisesNoWarning() throws Exception {
        Path repo = repo();
        String saved = head(repo);
        write(repo, "sc-swarm/src/main/java/Worker.java", "class Worker { int turns; }");
        commitAll(repo, "workers");

        SnapshotStaleness.Finding finding = SnapshotStaleness.compare(repo, saved, List.of());
        assertThat(finding.comparable()).isTrue();
        assertThat(finding.frontHalf()).isEmpty();
        assertThat(finding.warning(saved)).isNull();
    }

    @Test
    void aCommitToThePlannerAnUncommittedChangeAndWhatWasUncommittedAtSavingAreAllNamed()
            throws Exception {
        Path repo = repo();
        String saved = head(repo);
        write(repo, "sc-workflow/src/main/java/Architect.java", "class Architect { int more; }");
        write(repo, "sc-swarm/src/main/java/Worker.java", "class Worker { int turns; }");
        commitAll(repo, "planner and workers");
        write(repo, "sc-console/src/main/java/Wizard.java", "class Wizard { int edited; }");

        SnapshotStaleness.Finding finding = SnapshotStaleness.compare(repo, saved,
            List.of("dev/bookshelf-requirements.md"));
        assertThat(finding.frontHalf()).containsExactly(
            "dev/bookshelf-requirements.md",
            "sc-console/src/main/java/Wizard.java",
            "sc-workflow/src/main/java/Architect.java");
        assertThat(finding.warning(saved))
            .contains("STALE SNAPSHOT WARNING")
            .contains("links 1 to 9 has changed")
            .contains("3 file(s)")
            .contains("sc-workflow/src/main/java/Architect.java")
            .doesNotContain("Worker.java");
    }

    @Test
    void aCommitThisCheckoutDoesNotKnowIsSaidToBeUncomparable() throws Exception {
        Path repo = repo();
        SnapshotStaleness.Finding finding = SnapshotStaleness.compare(repo,
            "0123456789abcdef0123456789abcdef01234567", List.of());
        assertThat(finding.comparable()).isFalse();
        assertThat(finding.warning("0123456789abcdef0123456789abcdef01234567"))
            .contains("STALE SNAPSHOT WARNING").contains("cannot be told");
    }

    // --- helpers ------------------------------------------------------------------------------

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("swarmcoder"));
        BookshelfFixture.git(repo, "init -q -b master");
        write(repo, "sc-workflow/src/main/java/Architect.java", "class Architect {}");
        write(repo, "sc-console/src/main/java/Wizard.java", "class Wizard {}");
        write(repo, "sc-swarm/src/main/java/Worker.java", "class Worker {}");
        commitAll(repo, "start");
        return repo;
    }

    private static void write(Path repo, String relative, String text) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void commitAll(Path repo, String message) throws Exception {
        BookshelfFixture.git(repo, "add -A");
        BookshelfFixture.git(repo, "-c user.email=t@local -c user.name=t commit -q -m \""
            + message + "\"");
    }

    private static String head(Path repo) throws Exception {
        return BookshelfFixture.git(repo, "rev-parse HEAD").strip();
    }
}
