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
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The save-at-build snapshot's mechanics, proved without a model: a store and a git repository
 * saved at the moment a run is about to dispatch, put back somewhere else, twice, with the
 * snapshot itself untouched (2026-09-25).
 *
 * <p>The repository is built the way the product leaves one at that moment: a baseline commit, the
 * run's own tests branch {@code swarm/tests/<runId>} carrying a tests commit made in a LINKED
 * worktree, and — to prove the absolute-path hazard is handled — that worktree still registered
 * when the snapshot is taken. Worktrees here live under this test's own temp directory, never
 * under the real {@code ~/.swarmcoder/wt}, where a live run may be working.
 */
class HarnessSnapshotTest {

    @TempDir
    Path tmp;

    /** A repository at the dispatch seam: baseline, tests branch, a linked worktree. */
    private record Seam(Path repo, UUID runId, String baseCommit, String testsCommit,
                        Path linkedWorktree) {
    }

    private Seam repositoryAtTheSeam() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("original").resolve("bookshelf"));
        UUID runId = UUID.randomUUID();
        git(repo, "init -q -b master");
        Files.writeString(repo.resolve("pom.xml"), "<project/>\n");
        git(repo, "add -A");
        commit(repo, "Baseline");
        String base = git(repo, "rev-parse HEAD").strip();

        Path linked = tmp.resolve("wt").resolve("tests-" + runId);
        git(repo, "worktree add -q -b swarm/tests/" + runId + " \"" + linked + "\" " + base);
        Path test = linked.resolve("src/test/java/swarm/accept/ShelfAcceptTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "class ShelfAcceptTest {}\n");
        git(linked, "add -A");
        commit(linked, "Acceptance tests for run " + runId);
        String tests = git(linked, "rev-parse HEAD").strip();
        return new Seam(repo, runId, base, tests, linked);
    }

    private HarnessSnapshot.Manifest manifest(Seam seam, UUID projectId) {
        return new HarnessSnapshot.Manifest(HarnessSnapshot.FORMAT, "2026-09-25T10:00:00Z",
            seam.runId(), projectId, UUID.randomUUID(), "abc1234def", List.of("dev/notes.md"),
            "http://192.168.0.10:8000/v1", "deepseek-v4-flash", "deepseek-v4-flash-ds4", 2,
            "C:/work/swarmcoder/dev/bookshelf-demo", "0123456789", seam.baseCommit(),
            seam.testsCommit(), "C:/work/zeroz4j",
            List.of(new HarnessSnapshot.Link("a pristine copy of the demo project exists",
                    "a clone with 3 module(s)"),
                new HarnessSnapshot.Link("both documents ingest and yield text", "5000 chars")),
            HarnessSnapshot.registeredWorktrees(seam.repo()));
    }

    /** A store holding the project and the run in EXECUTING, as the seam finds it. */
    private ArtifactStore storeAtTheSeam(Path dir, Seam seam) throws Exception {
        ArtifactStore store = new ArtifactStore(dir);
        store.ensureProject("bookshelf", seam.repo().toString(), List.of());
        Run run = new Run(seam.runId(), WorkflowKind.GREENFIELD, RunState.EXECUTING,
            store.listProjects().get(0).id(), null, null, null, null, Instant.now(),
            new RunReport(seam.runId(), "rate a book"));
        run.setAcceptanceTestsCommit(seam.testsCommit());
        store.append(() -> {
            store.root().runs.put(seam.runId(), run);
            return null;
        }).get();
        return store;
    }

    @Test
    void aSavedRunIsRestoredTwiceWithItsStoreItsTestsCommitAndAWorkingRepositoryAndTheSnapshotNeverChanges()
            throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path snapshot = tmp.resolve("snapshots").resolve("rate-a-book");
        UUID projectId;
        try (ArtifactStore store = storeAtTheSeam(tmp.resolve("original").resolve("store"), seam)) {
            projectId = store.listProjects().get(0).id();
            HarnessSnapshot.Manifest manifest = manifest(seam, projectId);
            assertThat(manifest.worktreesNotCarried()).as("the linked worktree is registered at "
                + "the moment of saving, so the manifest names it").hasSize(1);
            HarnessSnapshot.save(snapshot, store, seam.repo(), manifest);
        }

        assertThat(snapshot.resolve("repo/.git/worktrees"))
            .as("a registration names an absolute path belonging to the saving run; not carried")
            .doesNotExist();
        try (Stream<Path> siblings = Files.list(snapshot.getParent())) {
            assertThat(siblings.map(p -> p.getFileName().toString()))
                .as("the partial directory was renamed into place, nothing half-written is left")
                .containsExactly("rate-a-book");
        }

        // The saving run's checkout is gone now, as it is at the seam in the product (the test
        // author removes its own worktree) — and debris of an earlier resume of this same run sits
        // under the worktree root, whose repository died with its test's temp directory.
        git(seam.repo(), "worktree remove --force \"" + seam.linkedWorktree() + "\"");
        Path wtRoot = tmp.resolve("wt");
        Path debris = Files.createDirectories(wtRoot.resolve("progress-" + seam.runId()));
        Files.writeString(debris.resolve(".git"), "gitdir: " + tmp.resolve("gone/.git/worktrees/x"));
        Path someoneElses = Files.createDirectories(wtRoot.resolve("progress-" + UUID.randomUUID()));
        Files.writeString(someoneElses.resolve(".git"), "gitdir: " + tmp.resolve("gone2"));

        Map<String, String> before = fingerprint(snapshot);

        HarnessSnapshot.Restored first = HarnessSnapshot.restore(snapshot, tmp.resolve("run1"), wtRoot);
        assertThat(first.cleared()).singleElement().asString()
            .startsWith("progress-" + seam.runId()).contains("no longer exists");
        assertThat(debris).doesNotExist();
        assertThat(someoneElses).as("only this run's leftovers are ever touched").exists();

        HarnessSnapshot.Restored second = HarnessSnapshot.restore(snapshot, tmp.resolve("run2"), wtRoot);
        assertThat(second.cleared()).isEmpty();

        for (HarnessSnapshot.Restored restored : List.of(first, second)) {
            Path repo = restored.repo();
            assertThat(repo).isEqualTo(restored.store().getParent().resolve("bookshelf"));
            assertThat(git(repo, "rev-parse swarm/tests/" + seam.runId()).strip())
                .as("the run's tests commit is on its own branch in the restored repository")
                .isEqualTo(seam.testsCommit());
            assertThat(git(repo, "show " + seam.testsCommit()
                    + ":src/test/java/swarm/accept/ShelfAcceptTest.java"))
                .contains("class ShelfAcceptTest");
            assertThat(git(repo, "worktree list --porcelain").lines()
                    .filter(l -> l.startsWith("worktree ")).toList())
                .as("the restored repository owns exactly one working tree: itself")
                .hasSize(1);
            assertThat(git(repo, "status --porcelain").strip()).isEmpty();
            // It works from where it now is: a new worktree can be cut from it, as a worker's is.
            Path worker = tmp.resolve("workers").resolve(repo.getParent().getFileName().toString());
            git(repo, "worktree add -q -b swarm/task/0 \"" + worker + "\" " + seam.testsCommit());
            assertThat(worker.resolve("src/test/java/swarm/accept/ShelfAcceptTest.java")).exists();

            try (ArtifactStore store = new ArtifactStore(restored.store())) {
                Run run = store.root().runs.get(seam.runId());
                assertThat(run.state()).isEqualTo(RunState.EXECUTING);
                assertThat(run.acceptanceTestsCommit()).isEqualTo(seam.testsCommit());
                Project moved = HarnessSnapshot.repointProject(store, projectId, repo);
                assertThat(store.ensureProject("bookshelf", repo.toString(), List.of()).id())
                    .as("found at its new path, not created a second time")
                    .isEqualTo(moved.id()).isEqualTo(projectId);
            }
        }

        // The two restores are independent of each other...
        commit(first.repo(), "only in the first", true);
        assertThat(git(second.repo(), "log --oneline").strip().lines().count())
            .isEqualTo(git(seam.repo(), "log --oneline").strip().lines().count());
        // ...and neither wrote a byte into the snapshot.
        assertThat(fingerprint(snapshot)).isEqualTo(before);
    }

    @Test
    void theManifestComesBackExactlyAsItWasWritten() throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path snapshot = tmp.resolve("snap");
        HarnessSnapshot.Manifest written;
        try (ArtifactStore store = storeAtTheSeam(tmp.resolve("store"), seam)) {
            written = manifest(seam, store.listProjects().get(0).id());
            HarnessSnapshot.save(snapshot, store, seam.repo(), written);
        }
        HarnessSnapshot.Manifest read = HarnessSnapshot.readManifest(snapshot);
        assertThat(read).isEqualTo(written);
        assertThat(read.provenance(snapshot)).contains("saved 2026-09-25T10:00:00Z")
            .contains("SwarmCoder abc1234d").contains("plus 1 uncommitted file(s)")
            .contains("deepseek-v4-flash");
    }

    @Test
    void aSnapshotOfAnotherFormatIsRefusedRatherThanMisread() throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path snapshot = tmp.resolve("snap");
        try (ArtifactStore store = storeAtTheSeam(tmp.resolve("store"), seam)) {
            HarnessSnapshot.save(snapshot, store, seam.repo(),
                manifest(seam, store.listProjects().get(0).id()));
        }
        Path manifest = snapshot.resolve(HarnessSnapshot.MANIFEST);
        Files.writeString(manifest, Files.readString(manifest)
            .replace("\"format\" : " + HarnessSnapshot.FORMAT, "\"format\" : 99"));
        assertThatThrownBy(() -> HarnessSnapshot.readManifest(snapshot))
            .hasMessageContaining("format 99");
    }

    @Test
    void aSnapshotIsNeverWrittenOverAnotherAndAFailedSaveLeavesNothingBehind() throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path occupied = Files.createDirectories(tmp.resolve("snaps").resolve("taken"));
        Files.writeString(occupied.resolve(HarnessSnapshot.MANIFEST), "{}");
        try (ArtifactStore store = storeAtTheSeam(tmp.resolve("store"), seam)) {
            HarnessSnapshot.Manifest manifest = manifest(seam, UUID.randomUUID());
            assertThatThrownBy(() -> HarnessSnapshot.save(occupied, store, seam.repo(), manifest))
                .hasMessageContaining("never written over another");
            assertThat(Files.readString(occupied.resolve(HarnessSnapshot.MANIFEST))).isEqualTo("{}");

            // The repository vanishes between the store backup and the copy: nothing is left
            // that could be mistaken for a snapshot, whole or partial.
            Path fresh = tmp.resolve("snaps").resolve("fresh");
            assertThatThrownBy(() -> HarnessSnapshot.save(fresh, store, tmp.resolve("no-such-repo"),
                manifest)).isInstanceOf(Exception.class);
            assertThat(fresh).doesNotExist();
            try (Stream<Path> left = Files.list(tmp.resolve("snaps"))) {
                assertThat(left.map(p -> p.getFileName().toString())).containsExactly("taken");
            }
        }
        assertThatThrownBy(() -> HarnessSnapshot.readManifest(tmp.resolve("snaps").resolve("nothing")))
            .hasMessageContaining("is not a whole snapshot");
    }

    @Test
    void aResumeRefusesWhileAnotherCopyOfTheRunStillOwnsACheckout() throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path snapshot = tmp.resolve("snap");
        try (ArtifactStore store = storeAtTheSeam(tmp.resolve("store"), seam)) {
            HarnessSnapshot.save(snapshot, store, seam.repo(),
                manifest(seam, store.listProjects().get(0).id()));
        }
        // The saving run's tests worktree is still registered in ITS repository, which still
        // exists: that copy of the run may be going. Nothing of it may be touched.
        assertThatThrownBy(() -> HarnessSnapshot.restore(snapshot, tmp.resolve("r"), tmp.resolve("wt")))
            .hasMessageContaining("tests-" + seam.runId())
            .hasMessageContaining("Another copy of this run may still be going");
        assertThat(seam.linkedWorktree().resolve("src/test/java/swarm/accept/ShelfAcceptTest.java"))
            .exists();
    }

    @Test
    void theParentOfACommitIsAskedForWithATildeBecauseCmdEatsATrailingCaret() throws Exception {
        Seam seam = repositoryAtTheSeam();
        Path linked = seam.linkedWorktree();
        String parent = seam.baseCommit();
        assertThat(git(linked, "rev-parse " + seam.testsCommit() + "~1").strip())
            .as("what link 16 now asks for: the delivered commit's parent")
            .isEqualTo(parent);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertThat(git(linked, "rev-parse " + seam.testsCommit() + "^").strip())
                .as("what it used to ask for, through cmd.exe: the commit ITSELF")
                .isEqualTo(seam.testsCommit());
        }
    }

    @Test
    void aRequestedRequirementThatIsNotTheSnapshotsOwnStopsTheResumedWalk() {
        List<HarnessSnapshot.AgreedRequirement> agreed = List.of(new HarnessSnapshot.AgreedRequirement(
            "R2", "Rate a book", "A reader can give a book one to five stars."));
        Path from = Path.of("snapshots", "1-requirements");

        assertThat(HarnessSnapshot.requirementMismatch(null, agreed, RestartPoint.REQUIREMENTS, from))
            .as("nothing requested, so the snapshot's own story is built").isNull();
        assertThat(HarnessSnapshot.requirementMismatch("  ", agreed, RestartPoint.REQUIREMENTS, from))
            .isNull();
        assertThat(HarnessSnapshot.requirementMismatch("RATE a book", agreed,
            RestartPoint.REQUIREMENTS, from))
            .as("the same requirement, matched as the full walk matches it").isNull();
        assertThat(HarnessSnapshot.requirementMismatch("delete a shelf", agreed,
            RestartPoint.REQUIREMENTS, from))
            .as("a different requirement is never silently replaced by the snapshot's")
            .contains("delete a shelf").contains("R2 'Rate a book'").contains("1-requirements")
            .contains("Nothing was built");
        assertThat(HarnessSnapshot.requirementMismatch("delete a shelf", List.of(),
            RestartPoint.REQUIREMENTS, from)).contains("no requirement that can be read");
    }

    /** Live run 75: pinned to "sorting", nothing matched, and another requirement was built. */
    @Test
    void aPinThatMatchesNoDraftedRequirementStopsTheWalkAndNamesTheDraftedTitles() {
        List<HarnessSnapshot.AgreedRequirement> drafted = List.of(
            new HarnessSnapshot.AgreedRequirement("R1", "Show all contacts in a sortable table",
                "The table lists every contact. Clicking a column header orders the rows."),
            new HarnessSnapshot.AgreedRequirement("R5", "Filter and search the table",
                "Typing in the search box narrows the sortable table."),
            new HarnessSnapshot.AgreedRequirement("R7", "Out-of-scope features are absent",
                "The logbook offers no ADIF import/export."));

        HarnessSnapshot.Pin none = HarnessSnapshot.pin("sorting", drafted);
        assertThat(none.index()).isEqualTo(-1);
        assertThat(none.failure()).contains("sorting").contains("matches none")
            .contains("R1 'Show all contacts in a sortable table'")
            .contains("R5 'Filter and search the table'")
            .contains("R7 'Out-of-scope features are absent'").contains("nothing was built");

        HarnessSnapshot.Pin inAChecksText = HarnessSnapshot.pin("ADIF IMPORT", drafted);
        assertThat(inAChecksText.index()).isEqualTo(2);
        assertThat(inAChecksText.failure()).isNull();
        assertThat(inAChecksText.note()).isNull();

        HarnessSnapshot.Pin several = HarnessSnapshot.pin("Sortable", drafted);
        assertThat(several.index()).as("the first in document order").isEqualTo(0);
        assertThat(several.failure()).isNull();
        assertThat(several.note()).contains("matches 2").contains("R1 '").contains("R5 '")
            .contains("is the one built");

        assertThat(HarnessSnapshot.pin(" ", drafted)).isEqualTo(new HarnessSnapshot.Pin(-1, null, null));
        assertThat(HarnessSnapshot.pin(null, drafted).failure()).isNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    private static String git(Path dir, String args) throws Exception {
        return BookshelfFixture.git(dir, args);
    }

    private static void commit(Path dir, String message) throws Exception {
        commit(dir, message, false);
    }

    private static void commit(Path dir, String message, boolean allowEmpty) throws Exception {
        git(dir, "-c user.email=t@local -c user.name=t commit -q " + (allowEmpty ? "--allow-empty " : "")
            + "-m \"" + message + "\"");
    }

    /** Every file under a tree, with its size, modification time and content hash. */
    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> prints = new TreeMap<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                prints.put(root.relativize(file).toString(), Files.size(file) + " "
                    + Files.getLastModifiedTime(file) + " "
                    + HexFormat.of().formatHex(sha.digest(Files.readAllBytes(file))));
            }
        }
        assertThat(prints).isNotEmpty();
        return prints;
    }
}
