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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Brings a worktree's acceptance-test tree to EXACTLY the files one task claims.
 *
 * <p><b>Why a worktree needs this at all.</b> A run is pinned to one commit at intake, and every
 * worktree of the run — each worker's, and therefore each candidate's — is cut from it. The
 * acceptance tests are written three stages later and committed on the run's own ref, so no
 * worktree has them. They reach a candidate here, at verification, and only the ones its task
 * claims: a test written for the task that delivers the behaviour three tasks from now does not
 * compile against an enabler's tree, and failing the enabler for it would be the same mistake as
 * failing a candidate whose model endpoint was down — no evidence about the thing being judged.
 *
 * <p><b>Why "exactly", and not "at least".</b> The protected tree is also where every earlier run
 * left its tests. On 2026-09-02 the demo repository's delivery branch carried four acceptance-test
 * files from four different dead runs, each importing classes a different plan had invented and
 * the current plan never creates; {@code test-compile} failed on them before any candidate's own
 * code was looked at, and all four candidates of the first task died with "does not compile" for
 * code that was fine. So the whole protected tree is cleared first — every directory with that
 * shape anywhere in the worktree, because earlier runs also left strays at the repository root and
 * in other modules — and then the claimed files, and nothing else, are written from the run's
 * tests commit. A task claiming nothing ends with an empty tree and an acceptance stage that runs
 * nothing, which is the documented allowance for an enabler.
 *
 * <p>The files come from the object database ({@link GitService#fileAt}), never from another
 * worktree, and are written as plain files — nothing here touches the index or any branch, so a
 * worktree that is later committed or merged carries none of this unless it descends from the
 * tests commit already.
 */
public final class AcceptanceOverlay {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceOverlay.class);

    /**
     * The directory shape every acceptance-test location shares across modules: the protected
     * package's tree under a module's test sources. A stray from another run lives at this path
     * under the repository root or under some other module, and is cleared from there too.
     */
    static final String PROTECTED_TAIL = "src/test/java/swarm";

    private static final Set<String> NEVER_ENTERED =
        Set.of(".git", "target", "build", "node_modules", ".swarmcoder");

    private AcceptanceOverlay() {}

    /**
     * @param cleared every file removed from the worktree's protected trees before placing
     * @param placed  the claimed files now in the worktree, repo-relative
     * @param missing claimed files the tests commit does not hold — a defect upstream, named
     */
    public record Outcome(List<String> cleared, List<String> placed, List<String> missing) {

        /** One line for a log: what left, what arrived, what could not. */
        public String describe() {
            return "cleared " + cleared.size() + " acceptance-test file(s), placed " + placed.size()
                + (placed.isEmpty() ? "" : " " + placed)
                + (missing.isEmpty() ? "" : "; NOT FOUND in the tests commit: " + missing);
        }
    }

    /**
     * Clears every protected acceptance-test tree in the worktree, then writes {@code claimedPaths}
     * from {@code testsCommit}.
     *
     * @param protectedDir the task's own protected directory (e.g. {@code server/src/test/java/swarm});
     *                     cleared even when it does not have the shared tail
     * @param testsCommit  the run's acceptance-tests commit; may be null when there is none, in which
     *                     case the tree is cleared and nothing is placed
     */
    public static Outcome reduceTo(GitService git, Path worktree, String protectedDir,
                                   String testsCommit, Collection<String> claimedPaths)
            throws IOException {
        List<String> cleared = clearProtectedTrees(worktree, protectedDir);
        List<String> placed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String path : new LinkedHashSet<>(claimedPaths == null ? List.<String>of() : claimedPaths)) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String relative = path.replace('\\', '/');
            byte[] content = testsCommit == null ? null : git.fileAt(testsCommit, relative);
            if (content == null) {
                missing.add(relative);
                continue;
            }
            Path target = worktree.resolve(relative).normalize();
            if (!target.startsWith(worktree.toAbsolutePath().normalize())) {
                missing.add(relative); // a path that leaves the worktree is not placed anywhere
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            placed.add(relative);
        }
        Outcome outcome = new Outcome(List.copyOf(cleared), List.copyOf(placed), List.copyOf(missing));
        if (!missing.isEmpty()) {
            log.warn("Acceptance overlay in {}: {}", worktree, outcome.describe());
        }
        return outcome;
    }

    /**
     * Removes every protected acceptance-test tree from the worktree: the task's own, and any
     * directory anywhere in the tree with the shared {@link #PROTECTED_TAIL} shape.
     *
     * @return the files removed, repo-relative
     */
    static List<String> clearProtectedTrees(Path worktree, String protectedDir) throws IOException {
        Path root = worktree.toAbsolutePath().normalize();
        List<Path> trees = new ArrayList<>();
        if (protectedDir != null && !protectedDir.isBlank()) {
            Path own = root.resolve(protectedDir.replace('\\', '/')).normalize();
            if (own.startsWith(root) && !own.equals(root) && Files.isDirectory(own)) {
                trees.add(own);
            }
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (NEVER_ENTERED.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (relative(root, dir).endsWith(PROTECTED_TAIL)) {
                    trees.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<String> removed = new ArrayList<>();
        for (Path tree : new LinkedHashSet<>(trees)) {
            if (!Files.isDirectory(tree)) {
                continue; // already removed as part of another tree
            }
            try (Stream<Path> walk = Files.walk(tree)) {
                List<Path> entries = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry)) {
                        removed.add(relative(root, entry));
                    }
                    Files.deleteIfExists(entry);
                }
            }
        }
        return removed;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
