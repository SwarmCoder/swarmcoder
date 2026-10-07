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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * <b>The target repository on this machine: cloned once, and a throwaway tree per case.</b>
 *
 * <h2>Where it lives, and why not anywhere else</h2>
 *
 * <p>{@code ~/.swarmcoder/targets/<name>} for the clone, {@code ~/.swarmcoder/targets/<name>-cases/
 * <issue>} for each case's own tree. Three places it deliberately is not:
 *
 * <ul>
 *   <li><b>Not under {@code C:/work/worktrees/}.</b> That directory is for worktrees of our own
 *       repositories. A third-party checkout there would be indistinguishable from work in
 *       progress on a branch somebody owns.</li>
 *   <li><b>Not inside the SwarmCoder checkout.</b> A 30,000-line third-party tree in the repository
 *       would be walked by the semantic index and the worked-example chooser, and committed by
 *       accident the first time somebody ran {@code git add -A}.</li>
 *   <li><b>Not a {@code @TempDir}.</b> A clone per case is network the harness should pay once, and
 *       a temp directory throws the clone away between cases.</li>
 * </ul>
 *
 * <h2>Idempotent, and offline after the first run</h2>
 *
 * <p>{@link #cloneOnce} clones only when there is no clone; an existing one is reused as it stands
 * and nothing is fetched. That is on purpose: the case file names commits by their full SHA, so a
 * clone that already has them needs no network, and a harness that fetched on every run would stop
 * working the first time the machine was offline. When a named commit is genuinely absent — a clone
 * made before the fix landed — {@link #caseTreeAt} fetches once, for that commit, and says so.
 *
 * <p>{@link #caseTreeAt} reuses a case tree that is already at the right commit and rebuilds one
 * that is not, so a re-run costs nothing and a stale tree from an interrupted run cannot be
 * mistaken for a fresh one. It is a <b>clone</b> of the clone rather than a linked worktree, and
 * its javadoc says at length why — a linked worktree is a shape JGit cannot resolve refs in, and
 * pointing a run at one parks it blaming a stage that is working.
 *
 * <h2>What the case tree is cut at, and what it therefore cannot contain</h2>
 *
 * <p>The <b>parent</b> of the fix commit. The fix is not on the tree, so no worker can read it, and
 * the maintainer's test is not on it either — which is what makes that test an independent oracle
 * when a later wave applies it to what the swarm produced.
 */
final class BrownfieldTarget {

    /** Overrides where the clone and the case trees live. */
    static final String TARGETS_PROPERTY = "swarmcoder.brownfield.targets";

    /** Only used now to clear away the branches wave 1's linked worktrees left behind. */
    private static final String CASE_BRANCH_PREFIX = "swarmcoder-case-";

    private BrownfieldTarget() {
    }

    /** {@code ~/.swarmcoder/targets}, or whatever {@value #TARGETS_PROPERTY} names. */
    static Path targetsDir() {
        String declared = System.getProperty(TARGETS_PROPERTY);
        if (declared != null && !declared.isBlank()) {
            return Path.of(declared).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".swarmcoder", "targets");
    }

    /**
     * The clone, and whether this run paid for it.
     *
     * @param path           where it is
     * @param freshlyCloned  true when this call created it; false when an existing clone was reused
     * @param duration       what the clone cost, or zero when one was reused
     * @param headSha        what the clone's default branch points at, for the record
     */
    record Clone(Path path, boolean freshlyCloned, Duration duration, String headSha) {

        String describe() {
            return (freshlyCloned
                ? "cloned in " + duration.toSeconds() + "s"
                : "reused the clone already here")
                + " at " + path + ", default branch at " + shortSha(headSha);
        }
    }

    /**
     * Clones the target once, or reuses the clone already there.
     *
     * @param name the directory name under {@link #targetsDir()} — the target's short name
     * @param url  where to clone from, used only when there is nothing to reuse
     */
    static Clone cloneOnce(String name, String url) throws Exception {
        Path targets = targetsDir();
        Path clone = targets.resolve(name);
        if (Files.isDirectory(clone.resolve(".git"))) {
            return new Clone(clone, false, Duration.ZERO, headOf(clone));
        }
        Files.createDirectories(targets);
        Instant start = Instant.now();
        // Full clone, not shallow: the cases name commits years apart by SHA, and a shallow clone
        // has none of them. jsoup is 12 MB, so paying for the whole history once is cheaper than
        // fetching five commits separately and far cheaper than being unable to run offline.
        BookshelfFixture.git(targets, "clone -q \"" + url + "\" \"" + name + "\"");
        return new Clone(clone, true, Duration.between(start, Instant.now()), headOf(clone));
    }

    /**
     * A case's own tree, and whether this run had to build it.
     *
     * @param path             where it is
     * @param headSha          what it is actually checked out at — measured, not assumed
     * @param freshlyCreated   true when this call built it
     * @param duration         what building it cost, or zero when one was reused
     * @param fetched          true when the commit was missing from the clone and had to be fetched
     */
    record CaseTree(Path path, String headSha, boolean freshlyCreated, Duration duration,
                    boolean fetched) {

        String describe() {
            return (freshlyCreated ? "cloned a fresh case tree in " + duration.toSeconds() + "s"
                : "reused the case tree already at this commit")
                + (fetched ? " (the commit was missing from the clone and was fetched)" : "")
                + " at " + path + ", HEAD " + shortSha(headSha);
        }
    }

    /**
     * A checkout of {@code clone} at the case's {@link BrownfieldCases.Case#parentCommit}.
     *
     * <p>Reused when one is already there at exactly that commit; rebuilt when it is at anything
     * else, because a tree left over from an interrupted run reads exactly like a fresh one.
     *
     * <h2>Why this is a CLONE and not a linked worktree, which is what the design asked for</h2>
     *
     * <p>Design §5.2 says "a git worktree of that clone", and wave 1 built exactly that. It works
     * for links 1 to 3, which only read. It does not work for a run, and the way it fails is worth
     * recording because nothing about it points at the cause.
     *
     * <p><b>JGit cannot resolve a ref in a linked worktree.</b> A linked worktree's {@code .git} is
     * a file reading {@code gitdir: <path>}, its HEAD lives in that per-worktree directory, and its
     * branches live in the shared one it names through {@code commondir}. JGit 6.9 follows the
     * pointer far enough to answer {@code getBranch()} and then looks for {@code refs/heads/…}
     * under the per-worktree directory, where it is not — measured directly: {@code getBranch()}
     * returns the branch name and {@code resolve("HEAD")} returns <b>null</b> on the same
     * repository. {@code GitService} opens every repository with {@code Git.open}, so on a worktree
     * every one of its JGit answers is null or false, each caught, each one WARN in a log.
     *
     * <p><b>What that cost, on this harness, on 2026-09-05.</b> The acceptance-test stage wrote its
     * file and committed it through the git CLI — which works; {@code GitService.commitAll} shells
     * out for this very reason — then asked JGit for the sha and got null. The run logged
     * "Committed 1 acceptance test file(s) on swarm/tests/… at ?", the overlay that places those
     * files onto each candidate's tree found nothing to place, and the red-check parked the run
     * saying "the test author claims test file(s) the run's tests commit does not hold … This is a
     * defect in SwarmCoder's own TEST_AUTHORING stage." The test author had done its job perfectly.
     *
     * <p><b>So the harness stops using worktrees, and the JGit limitation is recorded rather than
     * worked around in the product.</b> Making {@code GitService} worktree-aware means giving JGit
     * a ref database rooted at the common directory while the index and HEAD stay per-worktree, and
     * {@code FileRepositoryBuilder} has no way to say that — it is a piece of work with its own
     * design, not a line in a brownfield wave. Meanwhile a clone costs what the ledger already
     * measured: jsoup is 12 MB and two seconds, and a local clone hardlinks its objects, so five
     * case trees are seconds and almost no disk. The harness must measure the product, not an
     * unrelated gap in it.
     */
    static CaseTree caseTreeAt(Path clone, BrownfieldCases.Case one) throws Exception {
        Path root = targetsDir().resolve(one.target() + "-cases");
        Path tree = root.resolve(String.valueOf(one.issue()));
        String wanted = one.parentCommit();

        if (Files.isDirectory(tree.resolve(".git"))) {
            String at = headOfQuietly(tree);
            if (wanted.equals(at)) {
                return new CaseTree(tree, at, false, Duration.ZERO, false);
            }
            deleteTree(tree);
        } else if (Files.exists(tree)) {
            // Wave 1 left linked worktrees here. Their .git is a file, so the branch above does not
            // match, and git still has them registered — drop both the registration and the folder.
            remove(clone, tree);
            deleteTree(tree);
        }

        Instant start = Instant.now();
        boolean fetched = false;
        if (!hasCommit(clone, wanted)) {
            // Only reached by a clone made before this commit existed. Named explicitly rather than
            // fetching everything, so the reason shows up in the ledger line.
            BookshelfFixture.git(clone, "fetch -q origin " + wanted);
            fetched = true;
        }
        Files.createDirectories(root);
        // Cloned from the local clone, so this costs no network and git hardlinks the objects.
        BookshelfFixture.git(root, "clone -q \"" + clone.toString().replace('\\', '/')
            + "\" \"" + tree.toString().replace('\\', '/') + "\"");
        // Detached at the fix's parent. A branch here would only be a name for the same commit, and
        // the run makes its own branches off it.
        BookshelfFixture.git(tree, "checkout -q --detach " + wanted);
        String at = headOf(tree);
        return new CaseTree(tree, at, true, Duration.between(start, Instant.now()), fetched);
    }

    /** Unregisters a linked worktree wave 1 left here, and its branch, leaving the clone alone. */
    static void remove(Path clone, Path tree) {
        try {
            BookshelfFixture.git(clone, "worktree remove --force \""
                + tree.toString().replace('\\', '/') + "\"");
        } catch (Exception e) {
            // A worktree git has already forgotten, or a directory a process still holds open on
            // Windows. Neither is a finding about the product, and the caller is about to rebuild
            // it anyway, so it must not break the run.
            try {
                deleteTree(tree);
                BookshelfFixture.git(clone, "worktree prune");
            } catch (Exception ignored) {
                // Nothing further to try; the clone below will refuse a non-empty directory and say so.
            }
        }
    }

    /** True when the clone already has this commit, so nothing needs fetching. */
    private static boolean hasCommit(Path clone, String sha) {
        try {
            return "commit".equals(BookshelfFixture.git(clone, "cat-file -t " + sha).strip());
        } catch (Exception e) {
            return false;
        }
    }

    private static String headOf(Path dir) throws Exception {
        return BookshelfFixture.git(dir, "rev-parse HEAD").strip();
    }

    private static String headOfQuietly(Path dir) {
        try {
            return headOf(dir);
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best effort; git's own -f handles what is left.
                }
            }
        }
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }

    /** Reads a file from a case tree, for the checks that prove the fix is not on it. */
    static String read(Path tree, String relative) throws Exception {
        Path path = tree.resolve(relative);
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }
}
