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
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.nio.file.StandardOpenOption;

/**
 * Git plumbing for the swarm (spec §8.4). JGit for branch/ref/merge operations; worktree
 * creation shells out to the {@code git} CLI because JGit cannot create linked worktrees
 * (documented deviation, see DEVELOPER_CORRECTIONS.md §4/F2).
 *
 * <p>A GitService is bound to an explicit target repository. When no {@code repoPath} is
 * configured, use {@link #disabled()} — operations then fail loudly instead of silently
 * running against whatever the process working directory happens to be.
 */
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private final Path repoDir; // null = disabled

    public GitService(Path repoDir) {
        if (repoDir == null) {
            throw new IllegalArgumentException("repoDir must not be null; use GitService.disabled()");
        }
        this.repoDir = repoDir.toAbsolutePath().normalize();
    }

    private GitService() {
        this.repoDir = null;
    }

    /** A GitService with no target repository: {@link #isEnabled()} is false and operations throw. */
    public static GitService disabled() {
        return new GitService();
    }

    /**
     * Ensures {@code repoPath} is a usable git repository with at least one commit — workers
     * branch from HEAD into per-candidate worktrees, so a repo with no commits is as useless
     * as no repo. Creates the folder, {@code git init}s it, writes a default {@code .gitignore}
     * (so {@code target/} never enters candidate diffs), and makes an initial commit if the
     * working tree has no HEAD yet. Idempotent; returns whether the repo is ready.
     */
    public static boolean ensureRepo(Path repoPath) {
        if (repoPath == null) {
            return false;
        }
        try {
            Files.createDirectories(repoPath);
            if (!repoPath.resolve(".git").toFile().exists()) {
                runGitCli(repoPath, "init", "-q");
                log.info("Initialized a new git repository at {}", repoPath);
            }
            Path gitignore = repoPath.resolve(".gitignore");
            if (!Files.exists(gitignore)) {
                Files.writeString(gitignore, "target/\nbuild/\n*.class\n.idea/\n");
            }
            boolean hasHead;
            try {
                runGitCli(repoPath, "rev-parse", "--verify", "-q", "HEAD");
                hasHead = true;
            } catch (IOException noHead) {
                hasHead = false;
            }
            if (!hasHead) {
                runGitCli(repoPath, "add", "-A");
                runGitCli(repoPath, "-c", "user.name=SwarmCoder",
                    "-c", "user.email=swarm@swarmcoder.local",
                    "commit", "-q", "--allow-empty", "-m", "SwarmCoder: initial commit");
                log.info("Created an initial commit in {}", repoPath);
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not initialize git repository at {}: {}", repoPath, e.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return repoDir != null;
    }

    /** The absolute repository root, or null when disabled. */
    public Path repoPath() {
        return repoDir;
    }

    private Path requireRepo() {
        if (repoDir == null) {
            throw new IllegalStateException(
                "No target repository configured — set 'repoPath' in ~/.swarmcoder/config.yaml");
        }
        return repoDir;
    }

    /**
     * Creates branch {@code branchName} at HEAD with its own linked worktree at
     * {@code worktreePath}. This is the only sanctioned way for a worker to get a checkout:
     * concurrent checkouts on a shared working tree are forbidden.
     */
    public Path addWorktree(String branchName, Path worktreePath) throws IOException {
        return addWorktree(branchName, worktreePath, "HEAD");
    }

    /**
     * Worktree variant starting from an arbitrary ref — repair swarms seed from a failed
     * candidate's branch instead of HEAD (spec §11.5).
     */
    public Path addWorktree(String branchName, Path worktreePath, String startPoint) throws IOException {
        Path repo = requireRepo();
        Files.createDirectories(worktreePath.getParent());
        // One at a time per repository. Two `git worktree add` at the same instant can meet in
        // .git/worktrees: one lists the other's half-made entry and dies with "failed to read
        // .git/worktrees/<id>/commondir" (seen 2026-10-02 with two candidates of one task
        // starting in the same millisecond). The worker it cost never opened a session.
        java.util.concurrent.locks.Lock adds = worktreeAdds(repo);
        adds.lock();
        try {
            runGitCli(repo, "worktree", "add", "-b", branchName, worktreePath.toString(), startPoint);
        } finally {
            adds.unlock();
        }
        return worktreePath;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.Lock>
        WORKTREE_ADDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The lock every {@code git worktree add} in {@code repo} holds, in this process. A lock
     * object rather than {@code synchronized}: the callers are virtual threads, and a git
     * process is waited for while it is held.
     */
    private static java.util.concurrent.locks.Lock worktreeAdds(Path repo) {
        return WORKTREE_ADDS.computeIfAbsent(repo.toAbsolutePath().normalize().toString(),
            key -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * A worktree on a branch that ALREADY exists, checked out at its current head.
     *
     * <p>{@link #addWorktree(String, Path, String)} passes {@code -b}, which git refuses when the
     * branch is already there. The run's progress branch — where each wave's winners are merged so
     * the next wave can be cut from them — is created once and then reopened at every later wave
     * and after every resume, so it needs the form of the command that does not create anything.
     */
    public Path addWorktreeAt(String branchName, Path worktreePath) throws IOException {
        Path repo = requireRepo();
        Files.createDirectories(worktreePath.getParent());
        java.util.concurrent.locks.Lock adds = worktreeAdds(repo);
        adds.lock();
        try {
            runGitCli(repo, "worktree", "add", worktreePath.toString(), branchName);
        } finally {
            adds.unlock();
        }
        return worktreePath;
    }

    /**
     * Worktree creation for a RESUMED attempt: same as {@link #addWorktree(String, Path, String)}
     * except that a pre-existing {@code branchName} is archived out of the way first instead of
     * failing.
     *
     * <p>Candidate branch names are deterministic ({@code swarm/<taskId>/<workerIndex>}), so when a
     * run is resumed after the process was killed, the engine dispatches the exact same worker
     * indices for the exact same tasks — the exact same branch names a now-dead attempt already
     * created. {@code addWorktree} refuses an existing branch outright ({@code git worktree add -b}
     * exits 255), so on every resume every worker died before doing anything, and the process kept
     * reporting it as a {@code TIMEOUT} — which it never was.
     *
     * <p>The old branch may hold real work: a candidate that finished and committed in the moments
     * before the kill. It is never just deleted for that reason — it is archived to
     * {@code refs/swarm-archive/<runId>/...} (the same retirement every losing candidate gets),
     * which frees the branch name for reuse while keeping the commit reachable forever. If the
     * branch is still checked out in a worktree left over from before the kill, git refuses to
     * delete it until that checkout is gone, so that worktree is detached first; the directory
     * itself is left for {@link WorktreeSweeper} to collect on its own schedule, since deleting
     * arbitrary folders is not this method's job.
     *
     * <p>Safe to call twice, and safe on a run that was never interrupted at all: when no branch of
     * that name exists this behaves exactly like {@link #addWorktree(String, Path, String)}.
     *
     * @param runId the resumed run's id, used only to name the archive ref
     */
    public Path addOrResumeWorktree(String branchName, Path worktreePath, String startPoint,
                                    String runId) throws IOException {
        Path repo = requireRepo();
        if (headSha(branchName) != null) {
            detachWorktreeOf(repo, branchName);
            try {
                archiveCandidateBranch(branchName, runId);
                log.info("Archived the pre-existing branch {} (left over from an earlier, killed "
                    + "attempt) before recreating it for this one", branchName);
            } catch (Exception e) {
                // Archiving failed for some reason other than "the branch is gone" (already handled
                // inside archiveCandidateBranch). Fall back to a plain delete so this attempt can at
                // least proceed — a candidate that could not be archived is better lost than left
                // blocking every future resume of this same slot forever.
                log.warn("Could not archive the pre-existing branch {}; deleting it instead so this "
                    + "attempt can proceed: {}", branchName, e.getMessage());
                forceDeleteBranch(branchName);
            }
        }
        return addWorktree(branchName, worktreePath, startPoint);
    }

    /**
     * Moves an existing branch out of the way under a numbered name, so its own name is free
     * again and what it holds can still be looked at: {@code <asidePrefix>/1}, {@code /2}, ...
     * - the first number not taken.
     *
     * <p>For a stage that makes the same branch every time it runs. Final integration cuts
     * {@code swarm/integration/<run>} afresh, and it runs again for the same run after a repair
     * round, after a corrected journey, and after a pause for the model server (live run 95:
     * every second attempt died on "a branch named ... already exists"). The earlier attempt is
     * what a person reads to see why it failed, so it is renamed, never deleted.
     *
     * <p>A worktree a killed attempt left with the branch checked out is removed first; git
     * refuses to rename over nothing but would leave that checkout pointing at the old name.
     *
     * @return the name the branch has now, or null when there was no such branch
     */
    public String setBranchAside(String branchName, String asidePrefix) throws IOException {
        Path repo = requireRepo();
        if (headSha(branchName) == null) {
            return null;
        }
        detachWorktreeOf(repo, branchName);
        int attempt = 1;
        while (headSha(asidePrefix + "/" + attempt) != null) {
            attempt++;
        }
        String aside = asidePrefix + "/" + attempt;
        runGitCli(repo, "branch", "-M", branchName, aside);
        log.info("Branch {} of an earlier attempt is kept as {}", branchName, aside);
        return aside;
    }

    /** Force-removes whatever linked worktree currently has {@code branchName} checked out, if any. */
    private void detachWorktreeOf(Path repo, String branchName) {
        List<String> listing = gitOutputLines(repo, "worktree", "list", "--porcelain");
        Path stale = worktreePathForBranch(listing, branchName);
        if (stale == null || stale.equals(repo)) {
            return;
        }
        try {
            runGitCli(repo, "worktree", "remove", "--force", stale.toString());
            log.info("Removed the orphaned worktree {} that still had {} checked out", stale, branchName);
        } catch (IOException e) {
            log.warn("Could not remove the orphaned worktree {} checked out on {}: {}",
                stale, branchName, e.getMessage());
        }
    }

    /** The worktree directory {@code git worktree list} says has {@code branchName} checked out. */
    private static Path worktreePathForBranch(List<String> listing, String branchName) {
        String currentPath = null;
        for (String line : listing) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).trim();
            } else if (line.startsWith("branch ") && currentPath != null) {
                String ref = line.substring("branch ".length()).trim();
                String name = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
                if (name.equals(branchName)) {
                    return Path.of(currentPath);
                }
            }
        }
        return null;
    }

    /** Best-effort, unconditional branch delete — used only when archiving itself failed. */
    private void forceDeleteBranch(String branchName) {
        try (Git git = Git.open(requireRepo().toFile())) {
            if (git.getRepository().resolve("refs/heads/" + branchName) != null) {
                git.branchDelete().setBranchNames(branchName).setForce(true).call();
            }
        } catch (Exception e) {
            log.warn("Could not delete branch {}: {}", branchName, e.getMessage());
        }
    }

    /** Lines of a read-only git command's output, or an empty list when it failed — never throws. */
    private static List<String> gitOutputLines(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repo.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : output.split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Merges a branch into the checkout at {@code worktreePath} via the git CLI — so the
     * Mergiraf merge driver applies when configured (JGit cannot invoke merge drivers).
     * Throws with the conflict output on failure; the caller aborts the merge and parks a
     * decision (spec §8.4: conflicts between disjoint-write-set tasks are exceptional).
     */
    public void mergeBranch(Path worktreePath, String branchName) throws IOException {
        requireRepo();
        try {
            runGitCli(worktreePath, "-c", "user.name=SwarmCoder", "-c", "user.email=swarm@swarmcoder.local",
                "merge", "--no-edit", branchName);
        } catch (IOException e) {
            try {
                runGitCli(worktreePath, "merge", "--abort");
            } catch (IOException abortFailure) {
                log.warn("merge --abort failed in {}: {}", worktreePath, abortFailure.getMessage());
            }
            throw e;
        }
    }

    /** Removes a linked worktree (keeps its branch; branches are archived separately). */
    public void removeWorktree(Path worktreePath) {
        try {
            runGitCli(requireRepo(), "worktree", "remove", "--force", worktreePath.toString());
        } catch (IOException e) {
            String message = e.getMessage();
            if (message != null && message.contains("is not a working tree")) {
                // Every caller here also calls this BEFORE creating the worktree, to sweep away
                // whatever a killed attempt left behind. When there is nothing there at all, git
                // saying so is the pre-clean succeeding, not failing — a WARN for the normal case
                // is exactly the kind of noise that hides a real one.
                log.debug("Nothing to remove at {} ({})", worktreePath, message);
            } else {
                log.warn("Failed to remove worktree {}: {}", worktreePath, message);
            }
        }
    }

    /**
     * Stages and commits everything in the given worktree. Uses the git CLI: JGit cannot open
     * linked worktrees ("repository not found" on the gitdir pointer file). Candidate commits
     * carry a bot identity so they never depend on host git config.
     */
    public void commitAll(Path worktreePath, String message) throws IOException {
        requireRepo();
        runGitCli(worktreePath, "add", "-A");
        // Message goes via file: multi-line arguments do not survive Windows process creation.
        Path messageFile = Files.createTempFile("sc-commit", ".txt");
        try {
            Files.writeString(messageFile, message);
            runGitCli(worktreePath, "-c", "user.name=SwarmCoder", "-c", "user.email=swarm@swarmcoder.local",
                "commit", "-F", messageFile.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(messageFile);
        }
    }

    /**
     * Archives a candidate branch under {@code refs/swarm-archive/<runId>/...} and deletes the
     * branch. Uses a direct RefUpdate — the previous branchRename-based implementation produced
     * broken refs like {@code refs/heads/refs/swarm-archive/...}.
     */
    /**
     * The commit a branch currently points at, or null when git is disabled or the branch is gone.
     *
     * <p>This is the durable link from a work item to the code that satisfied it: branches are
     * deleted or archived after a run, but the sha stays valid forever.
     */
    public String headSha(String branchName) {
        if (repoDir == null || branchName == null || branchName.isBlank()) {
            return null;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            ObjectId tip = git.getRepository().resolve("refs/heads/" + branchName);
            return tip == null ? null : tip.getName();
        } catch (Exception e) {
            log.warn("Could not resolve head of {}: {}", branchName, e.getMessage());
            return null;
        }
    }

    /**
     * The branch the repository is currently on — the project's delivery branch, and the thing every
     * run builds on. Null when git is disabled or the checkout is on a detached head.
     */
    public String currentBranch() {
        if (repoDir == null) {
            return null;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            String branch = git.getRepository().getBranch();
            return branch == null || branch.isBlank() ? null : branch;
        } catch (Exception e) {
            log.warn("Could not read the current branch: {}", e.getMessage());
            return null;
        }
    }

    /** The commit any ref — branch, tag or sha — points at, or null when it does not resolve. */
    public String resolveCommit(String ref) {
        if (repoDir == null || ref == null || ref.isBlank()) {
            return null;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            ObjectId id = git.getRepository().resolve(ref);
            return id == null ? null : id.getName();
        } catch (Exception e) {
            log.warn("Could not resolve {}: {}", ref, e.getMessage());
            return null;
        }
    }

    /** True when the working tree and index have no changes of their own. */
    public boolean isWorkingTreeClean() {
        if (repoDir == null) {
            return false;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            return git.status().call().isClean();
        } catch (Exception e) {
            log.warn("Could not read the working tree status: {}", e.getMessage());
            return false;
        }
    }

    /** True when {@code maybeAncestor} is already contained in {@code descendant}'s history. */
    public boolean isAncestor(String maybeAncestor, String descendant) {
        if (repoDir == null || maybeAncestor == null || descendant == null) {
            return false;
        }
        try {
            runGitCli(requireRepo(), "merge-base", "--is-ancestor", maybeAncestor, descendant);
            return true;
        } catch (IOException e) {
            return false;   // exit 1 is the answer "no", not a failure
        }
    }

    /**
     * Moves the project's delivery branch forward onto work that has already been merged and
     * verified somewhere else — the step that makes "delivered" mean something outside SwarmCoder's
     * own store.
     *
     * <p><b>Why this exists.</b> Until now nothing ever reached the branch the operator works on. A
     * run's winners were merged onto {@code swarm/integration/<runId>} and left there, and accepting
     * a story only wrote a state change into the store. So a story that builds on an earlier one
     * could not see the earlier one's code no matter how long it waited, and the operator's morning
     * job was to find and merge a pile of integration branches by hand — the manual work this whole
     * change exists to remove.
     *
     * <p><b>Why it is a fast-forward and nothing else.</b> This is the one operation that touches the
     * operator's own checkout, so it does nothing clever and it never resolves anything. The merge
     * itself, and the full verification of its result, happen first in a throwaway worktree
     * ({@code StoryDelivery}) — exactly the way task winners are merged and verified before they
     * count. By the time this is called, the commit is already a descendant of the branch tip and
     * moving the branch is arithmetic. Anything that is not a clean fast-forward onto a clean tree is
     * refused with the reason, and nothing is changed.
     *
     * @return null when the branch now points at {@code commit}; otherwise a plain-English reason it
     *         does not, safe to show to the operator
     */
    public String fastForwardBranchTo(String branch, String commit) {
        if (repoDir == null) {
            return null;    // no repository configured — there is nothing to land into
        }
        if (branch == null || branch.isBlank() || commit == null || commit.isBlank()) {
            return "there is no branch or no commit to deliver — this run produced nothing";
        }
        String already = resolveCommit(branch);
        if (commit.equals(already) || isAncestor(commit, already)) {
            return null;    // already there; accepting twice must not fail
        }
        String on = currentBranch();
        if (on == null) {
            return "the project's repository is not on any branch (a detached checkout), so the "
                + "delivered code cannot be put on " + branch + ". Check out " + branch
                + " and accept again.";
        }
        if (!on.equals(branch)) {
            return "the project's repository is on " + on + ", not " + branch + ". Switch it to "
                + branch + " and accept again — nothing was changed.";
        }
        if (!isWorkingTreeClean()) {
            return "the project's repository has changes that are not committed. Commit them or put "
                + "them aside, then accept again — nothing was changed, so nothing of yours was "
                + "touched.";
        }
        try {
            runGitCli(requireRepo(), "merge", "--ff-only", commit);
            log.info("Delivery branch {} moved to {}", branch, commit);
            return null;
        } catch (IOException e) {
            log.warn("Could not move {} to {}: {}", branch, commit, e.getMessage());
            return "the delivered code no longer sits directly on top of " + branch
                + " — something else moved that branch in the meantime. Nothing was changed, and "
                + "this needs a person: " + e.getMessage();
        }
    }

    /**
     * Deletes a candidate branch without archiving it, for a candidate that produced nothing worth
     * keeping — specifically one lost to a model-endpoint outage.
     *
     * <p>Needed because candidate branch names are deterministic ({@code swarm/<taskId>/<index>})
     * and {@code worktree add -b} refuses an existing branch. Without this, the auto-retry after an
     * outage would fail at worktree creation on every attempt, for ever: the feature would look like
     * it worked and then never recover. Archiving them instead would fill the archive refs with
     * empty branches from a rebooting Spark.
     *
     * <p>Best-effort and quiet: a branch that is not there is the desired end state.
     */
    public void deleteCandidateBranch(String branchName) {
        if (!isEnabled() || branchName == null || branchName.isBlank()) {
            return;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            if (git.getRepository().resolve("refs/heads/" + branchName) == null) {
                return;
            }
            git.branchDelete().setBranchNames(branchName).setForce(true).call();
            // "nothing to keep" is the whole honest claim here: this runs for any candidate that
            // produced nothing worth archiving, not only one lost to an outage — naming a specific
            // cause this method was never told is a guess the caller cannot back up.
            log.info("Deleted candidate branch {} (nothing to keep)", branchName);
        } catch (Exception e) {
            log.warn("Could not delete candidate branch {}: {}", branchName, e.getMessage());
        }
    }

    public void archiveCandidateBranch(String branchName, String runId) throws IOException, GitAPIException {
        Path repo = requireRepo();
        try (Git git = Git.open(repo.toFile())) {
            ObjectId tip = git.getRepository().resolve("refs/heads/" + branchName);
            if (tip == null) {
                log.warn("Cannot archive missing branch {}", branchName);
                return;
            }
            String archiveRef = "refs/swarm-archive/" + runId + "/" + branchName;
            RefUpdate update = git.getRepository().updateRef(archiveRef);
            update.setNewObjectId(tip);
            update.setRefLogMessage("swarmcoder archive of " + branchName, false);
            RefUpdate.Result result = update.forceUpdate();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                    && result != RefUpdate.Result.NO_CHANGE) {
                throw new IOException("Archiving " + branchName + " failed: " + result);
            }
            git.branchDelete().setBranchNames(branchName).setForce(true).call();
        }
    }

    /**
     * Configures Mergiraf as a merge driver — only when a real binary exists on PATH or in
     * {@code ~/.swarmcoder/bin}. Patterns go to {@code .git/info/attributes} (local, not the
     * working tree), idempotently, and are language-scoped rather than {@code *}.
     *
     * <p>Never fabricates a binary: the previous implementation created an empty executable
     * and routed every merge through it, breaking all merges.
     *
     * @return true when Mergiraf was configured; false when no binary was found (logged, no-op)
     */
    public boolean configureMergirafDriver() throws IOException {
        Path repo = requireRepo();
        Path mergiraf = findMergiraf();
        if (mergiraf == null) {
            log.warn("Mergiraf binary not found on PATH or in ~/.swarmcoder/bin — "
                + "structural merge driver NOT configured; line-based merge will be used");
            return false;
        }

        try (Git git = Git.open(repo.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("merge", "mergiraf", "name", "mergiraf");
            config.setString("merge", "mergiraf", "driver",
                mergiraf + " merge --git %O %A %B -s %S -x %X -y %Y -p %P -l %L");
            config.save();
        }

        Path attributes = repo.resolve(".git").resolve("info").resolve("attributes");
        Files.createDirectories(attributes.getParent());
        List<String> existing = Files.exists(attributes)
            ? Files.readAllLines(attributes) : List.of();
        List<String> toAdd = new ArrayList<>();
        for (String pattern : MERGIRAF_PATTERNS) {
            if (existing.stream().noneMatch(line -> line.trim().equals(pattern))) {
                toAdd.add(pattern);
            }
        }
        if (!toAdd.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (!existing.isEmpty() && !existing.get(existing.size() - 1).isBlank()) {
                sb.append('\n');
            }
            toAdd.forEach(p -> sb.append(p).append('\n'));
            Files.writeString(attributes, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        log.info("Mergiraf merge driver configured ({})", mergiraf);
        return true;
    }

    /** Languages mergiraf supports that overlap SwarmCoder's target stacks. */
    private static final List<String> MERGIRAF_PATTERNS = List.of(
        "*.java merge=mergiraf",
        "*.rs merge=mergiraf",
        "*.ts merge=mergiraf",
        "*.tsx merge=mergiraf",
        "*.js merge=mergiraf",
        "*.py merge=mergiraf",
        "*.html merge=mergiraf",
        "*.css merge=mergiraf",
        "*.json merge=mergiraf",
        "*.yaml merge=mergiraf",
        "*.yml merge=mergiraf",
        "*.xml merge=mergiraf");

    private static Path findMergiraf() {
        String binName = isWindows() ? "mergiraf.exe" : "mergiraf";
        Path local = Path.of(System.getProperty("user.home"), ".swarmcoder", "bin", binName);
        if (Files.isExecutable(local) && isRealBinary(local)) {
            return local;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(entry).resolve(binName);
                if (Files.isExecutable(candidate) && isRealBinary(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Guards against the zero-byte placeholder the old stub used to create. */
    private static boolean isRealBinary(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Merges a candidate/task branch into the target branch. Executed in the primary
     * working tree; a conflict surfaces as an exception for the caller to convert into a
     * BLOCKED task + decision (spec §8.4).
     *
     * <p>Limitation: JGit does not invoke custom merge drivers, so the Mergiraf configuration
     * applies only to merges run through the git CLI. If structural merging becomes load-bearing
     * for integration, switch this method to a CLI merge.
     */
    public void integrateCandidate(String branchName, String targetBranch) throws IOException, GitAPIException {
        Path repo = requireRepo();
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setName(targetBranch).call();
            MergeResult result = git.merge()
                .include(git.getRepository().exactRef("refs/heads/" + branchName))
                .call();
            if (!result.getMergeStatus().isSuccessful()) {
                throw new IllegalStateException(
                    "Merge of " + branchName + " into " + targetBranch + " failed: " + result.getMergeStatus());
            }
        }
    }

    /**
     * Repo-relative paths of the files that contain {@code pattern} AT A GIT REF — not in the
     * working tree.
     *
     * <p>The ref is the whole point. A lock declared inside a source file is only a lock if the
     * declaration cannot be removed by the thing being restrained; reading the working tree would
     * mean a worker could delete the marker comment and unlock the file in the same commit. Reading
     * the base commit makes the marker set fixed for the duration of the run.
     *
     * <p>Returns an empty list when git is disabled or the grep finds nothing — {@code git grep}
     * exits 1 for "no matches", which is not an error.
     */
    public List<String> grepFilesAtRef(String pattern, String ref) {
        if (repoDir == null || pattern == null || pattern.isBlank()) {
            return List.of();
        }
        try {
            List<String> command = List.of("git", "grep", "-l", "--fixed-strings", pattern,
                ref == null || ref.isBlank() ? "HEAD" : ref);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() > 1) {
                log.warn("git grep for '{}' failed: {}", pattern, output.trim());
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // "git grep <ref>" prefixes each hit with "<ref>:" — strip it back to a path.
                int colon = trimmed.indexOf(':');
                paths.add(colon >= 0 ? trimmed.substring(colon + 1) : trimmed);
            }
            return paths;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("git grep for '{}' failed: {}", pattern, e.getMessage());
            return List.of();
        }
    }

    /**
     * The bytes of one file as committed at {@code commit}, or null when the commit or the path is
     * not there. Reads the object database, never a working tree, so the answer is the same
     * whichever worktree asks and whatever any of them has done to its copy.
     */
    public byte[] fileAt(String commit, String path) {
        if (repoDir == null || commit == null || commit.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        try (Git git = Git.open(requireRepo().toFile())) {
            Repository repository = git.getRepository();
            ObjectId id = repository.resolve(commit + ":" + path.replace('\\', '/'));
            if (id == null) {
                return null;
            }
            return repository.open(id).getBytes();
        } catch (Exception e) {
            log.warn("Could not read {} at {}: {}", path, commit, e.getMessage());
            return null;
        }
    }

    /**
     * Puts every tracked file under {@code relativeDir} in the worktree back to what its HEAD
     * commit holds, and removes the untracked ones there. Only that directory is touched; nothing
     * else in the worktree moves. Quiet when there is nothing tracked under it.
     */
    public void restoreTree(Path worktreePath, String relativeDir) throws IOException {
        requireRepo();
        String dir = relativeDir == null || relativeDir.isBlank() ? "." : relativeDir.replace('\\', '/');
        // "checkout HEAD -- <dir>" fails on a pathspec that matches nothing tracked, which is the
        // ordinary state of a protected directory in a repository that has no tests yet.
        try {
            runGitCli(worktreePath, "checkout", "-q", "HEAD", "--", dir);
        } catch (IOException e) {
            if (!e.getMessage().contains("did not match")) {
                throw e;
            }
        }
        runGitCli(worktreePath, "clean", "-q", "-fd", "--", dir);
    }

    private static void runGitCli(Path repo, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        List.of(args).forEach(command::add);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + String.join(" ", args) + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted running git " + String.join(" ", args), e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit "
                + process.exitValue() + "): " + output.trim());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
