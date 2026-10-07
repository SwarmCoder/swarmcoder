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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Removes worker worktrees left behind by runs that are no longer alive.
 *
 * <p>A run cleans up after itself. A run that is <em>killed</em> does not: the process dies between
 * {@code git worktree add} and {@code git worktree remove}, and the checkout stays under
 * {@code ~/.swarmcoder/wt} for ever. 63 of them had accumulated by August 2026, from sessions in
 * July, and nothing in the product had ever looked at them.
 *
 * <p><b>It asks git, it does not recognise shapes.</b> Every decision here comes from git's own
 * bookkeeping: the {@code .git} pointer file each linked worktree carries, and
 * {@code git worktree list} answered by the repository that owns it. A directory that merely looks
 * like a worktree is left exactly where it is and reported. Removal goes through
 * {@code git worktree remove} without {@code --force}, so git itself refuses if the checkout has
 * work in it.
 *
 * <p><b>The one place it deletes a directory itself</b> is a worktree whose repository is gone —
 * the {@code .git} pointer names a repository that no longer exists or that git will not open. No
 * repository can be asked to remove it, and git will never mention it again; the folder is all
 * that is left. Any commits it once held died with the object database, so there is nothing to
 * rescue and nothing to ask about.
 *
 * <p>Bounded on purpose: nothing younger than {@code minAge}, at most {@code max} removals per
 * start, and a live-or-resumable check the caller supplies. Startup must not turn into a
 * housekeeping job, and a sweep must never be able to delete the worktrees of a run in flight.
 */
public final class WorktreeSweeper {

    private static final Logger log = LoggerFactory.getLogger(WorktreeSweeper.class);

    /** Nothing this young is touched: it may belong to a run that started moments ago. */
    public static final Duration DEFAULT_MIN_AGE = Duration.ofHours(6);
    /** Ceiling per start, so a first sweep over a large backlog cannot stall the boot. */
    public static final int DEFAULT_MAX_REMOVALS = 200;

    private static final int GIT_TIMEOUT_SECONDS = 30;

    private WorktreeSweeper() {}

    /**
     * One candidate for removal, as git describes it.
     *
     * @param path       the worktree directory
     * @param name       its directory name — a candidate id, or {@code integration-<runId>}
     * @param mainRepo   the main working tree of the repository that owns it; null when orphaned
     * @param branch     the branch checked out in it (e.g. {@code swarm/<taskId>/3}); may be null
     * @param orphaned   true when the owning repository is gone
     */
    public record Stale(Path path, String name, Path mainRepo, String branch, boolean orphaned) {}

    /**
     * What one sweep did. Every list is human-readable lines, because the point of the sweep is
     * that somebody can read what it removed.
     */
    public record Result(List<String> removed, List<String> kept, List<String> withWork,
                         List<String> failed) {

        public int total() {
            return removed.size() + kept.size() + withWork.size() + failed.size();
        }

        /** One line for the log; the detail lists carry the rest. */
        public String summary() {
            return "worktree sweep: " + removed.size() + " removed, " + kept.size() + " kept, "
                + withWork.size() + " left alone because they hold uncommitted work, "
                + failed.size() + " could not be read";
        }
    }

    public static Result sweep(Path root, Predicate<Stale> stillWanted) {
        return sweep(root, stillWanted, DEFAULT_MIN_AGE, DEFAULT_MAX_REMOVALS);
    }

    /**
     * @param root        the worktree root ({@code ~/.swarmcoder/wt})
     * @param stillWanted true for a worktree whose run is still live or resumable. Called only for
     *                    worktrees git still owns, and its answer is final: a "yes" is never
     *                    overridden by age or by anything else here.
     * @param minAge      leave anything modified more recently than this alone
     * @param max         stop after this many removals
     */
    public static Result sweep(Path root, Predicate<Stale> stillWanted, Duration minAge, int max) {
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> withWork = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return new Result(List.of(), List.of(), List.of(), List.of());
        }

        List<Path> directories = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString))
                .forEach(directories::add);
        } catch (IOException e) {
            log.warn("Could not list the worktree root {}: {}", root, e.toString());
            return new Result(List.of(), List.of(), List.of(),
                List.of(root + " could not be listed: " + e));
        }

        Instant cutoff = Instant.now().minus(minAge);
        for (Path directory : directories) {
            if (removed.size() >= max) {
                kept.add(directory.getFileName() + " — the sweep's per-start limit of " + max
                    + " was reached; it will be looked at next time");
                continue;
            }
            try {
                if (Files.getLastModifiedTime(directory).toInstant().isAfter(cutoff)) {
                    kept.add(directory.getFileName() + " — changed within the last "
                        + minAge.toHours() + "h, so it may belong to a run that is still going");
                    continue;
                }
            } catch (IOException e) {
                failed.add(directory + " — its age could not be read: " + e);
                continue;
            }

            String pointer = gitPointer(directory);
            if (pointer == null) {
                // No `.git` pointer file: git has never called this a linked worktree, so neither
                // do we. Deleting a directory on the strength of where it sits would be exactly
                // the shape-recognition this class refuses to do.
                kept.add(directory.getFileName() + " — not a linked git worktree, left alone");
                continue;
            }

            List<String> listing = git(directory, "worktree", "list", "--porcelain");
            if (listing == null) {
                // git will not open the repository this worktree points at. It is gone, and with
                // it every commit the worktree ever made — there is nothing to remove it FROM and
                // nothing left to rescue.
                if (deleteTree(directory)) {
                    removed.add(directory.getFileName() + " — its repository (" + pointer
                        + ") no longer exists, so git could not be asked and nothing in it was "
                        + "recoverable");
                } else {
                    failed.add(directory + " — orphaned, but the folder could not be deleted");
                }
                continue;
            }

            Path mainRepo = firstWorktree(listing);
            String branch = branchOf(listing, directory);
            Stale stale = new Stale(directory, String.valueOf(directory.getFileName()),
                mainRepo, branch, false);

            if (stillWanted.test(stale)) {
                kept.add(directory.getFileName() + " — belongs to a run that is still live or "
                    + "resumable" + (branch == null ? "" : " (" + branch + ")"));
                continue;
            }

            List<String> status = git(directory, "status", "--porcelain");
            if (status == null) {
                failed.add(directory + " — git could not report its status, so it was left alone");
                continue;
            }
            if (!status.isEmpty()) {
                // Reported, never deleted. A crashed run may be the only place this work exists.
                withWork.add(directory.getFileName() + " — holds " + status.size()
                    + " uncommitted change(s)"
                    + (branch == null ? "" : " on " + branch) + "; NOT removed");
                continue;
            }

            // No --force: git refuses if anything is still in there, which is the second guard
            // behind the status check above.
            Path from = mainRepo == null ? directory : mainRepo;
            if (git(from, "worktree", "remove", directory.toString()) == null) {
                failed.add(directory + " — git refused to remove it; left alone");
                continue;
            }
            git(from, "worktree", "prune");
            removed.add(directory.getFileName() + " — abandoned worktree of "
                + (branch == null ? "an unknown branch" : branch) + " in " + from);
        }
        return new Result(List.copyOf(removed), List.copyOf(kept), List.copyOf(withWork),
            List.copyOf(failed));
    }

    /** The repository a linked worktree points at, from its own {@code .git} file; null if none. */
    private static String gitPointer(Path directory) {
        Path pointer = directory.resolve(".git");
        if (!Files.isRegularFile(pointer)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(pointer)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("gitdir:")) {
                    return trimmed.substring("gitdir:".length()).trim();
                }
            }
        } catch (IOException e) {
            log.debug("Could not read {}: {}", pointer, e.toString());
        }
        return null;
    }

    /** The main working tree — always the first entry of {@code worktree list --porcelain}. */
    private static Path firstWorktree(List<String> listing) {
        for (String line : listing) {
            if (line.startsWith("worktree ")) {
                return Path.of(line.substring("worktree ".length()).trim());
            }
        }
        return null;
    }

    /** The branch git says is checked out in {@code directory}, or null. */
    private static String branchOf(List<String> listing, Path directory) {
        boolean ours = false;
        for (String line : listing) {
            if (line.startsWith("worktree ")) {
                ours = samePath(line.substring("worktree ".length()).trim(), directory);
            } else if (ours && line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).trim();
                return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            }
        }
        return null;
    }

    /** Windows writes the same path several ways; compare them normalised and case-insensitively. */
    private static boolean samePath(String reported, Path directory) {
        return normalise(reported).equals(normalise(directory.toString()));
    }

    private static String normalise(String path) {
        String value = path.replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** The command's output lines, or null when git failed — never an exception. */
    private static List<String> git(Path in, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            if (in != null && Files.isDirectory(in)) {
                pb.directory(in.toFile());
            }
            process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.debug("git {} in {} failed: {}", String.join(" ", args), in, output.trim());
                return null;
            }
            List<String> lines = new ArrayList<>();
            for (String line : output.split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
            return lines;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /** Depth-first delete of an orphaned worktree folder. False if anything survived. */
    private static boolean deleteTree(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.debug("Could not walk {}: {}", directory, e.toString());
        }
        return !Files.exists(directory);
    }
}
