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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Task;
import com.swarmcoder.git.AcceptanceOverlay;
import com.swarmcoder.git.GitService;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Merges the winners of ONE wave onto the run's progress branch, so the next wave's worktrees are
 * cut from a tree that already contains them.
 *
 * <p><b>The defect this exists to remove.</b> Every worker worktree of a run was cut from the
 * commit pinned at intake, and winners were merged only at the very end, after every task had run.
 * A task in the second wave therefore never saw the first wave's selected winner: on 2026-09-02 the
 * workers of a task that needed a service class went looking for it, found it only on somebody
 * else's candidate branch, and were killed for making no progress — twice in one day, once for a
 * shared data class that a sister task had already finished and won. A plan with any dependency
 * edge in it failed at the second task, and the failure was recorded against the workers.
 *
 * <p><b>The branch is the run's own, and it is code only.</b> {@code swarm/progress/<runId>}
 * descends from the run's pinned base — NOT from the acceptance-tests commit. A worker worktree cut
 * from a tree carrying every task's acceptance tests would fail its own {@code test-compile} on
 * tests for work three tasks away, which is the same breakage {@link AcceptanceOverlay} exists to
 * undo. The tests reach a tree only where they are due: a candidate's, at verification, reduced to
 * exactly what its task claims; and the integration branch at the end, which descends from the
 * tests commit so they reach the delivery branch with the code that passes them.
 *
 * <p><b>What it does not do.</b> It does not run the acceptance suite. Every winner it merges was
 * already verified green on exactly this base, and the end-of-run integration still verifies fully
 * after every single merge. What it does check is that the merged tree still COMPILES, because that
 * is the one thing the individual verifications cannot have established and it is what the next
 * wave's workers are about to build on: dispatching a swarm at a tree that does not compile burns
 * the whole swarm and then blames the workers for it.
 */
public final class WaveIntegrator {

    private static final Logger log = LoggerFactory.getLogger(WaveIntegrator.class);
    private static final Path WORKTREE_ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    /**
     * @param commit  the progress branch's new head — the ref the NEXT wave is cut from; null when
     *                nothing was merged or the merge failed
     * @param failure null on success; otherwise the operator-facing reason the run must park
     */
    public record Result(String commit, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final GitService git;
    private final com.swarmcoder.verify.BuildBoxes boxes;

    /** With no container: the compile check is refused unless host execution was allowed by name. */
    public WaveIntegrator(GitService git) {
        this(git, com.swarmcoder.verify.BuildBoxes.none());
    }

    /**
     * @param boxes where the merged tree is compiled. The command is the operator's, the build
     *              files and sources it compiles are the winners' - model-written - so it runs in
     *              a container that sees that tree and nothing else of this PC
     */
    public WaveIntegrator(GitService git, com.swarmcoder.verify.BuildBoxes boxes) {
        this.git = git;
        this.boxes = boxes == null ? com.swarmcoder.verify.BuildBoxes.none() : boxes;
    }

    /** The run's progress branch name. */
    public static String branchOf(UUID runId) {
        return "swarm/progress/" + runId;
    }

    /**
     * Merges every winner of one wave onto the progress branch and returns its new head.
     *
     * @param runId     the run
     * @param base      the run's pinned base — where the progress branch starts on the first wave
     * @param winners   the wave's winning candidates, in the order they should be merged, paired
     *                  with the task each belongs to
     * @param claimedBy path -> the task whose winner reached it first, accumulated across the WHOLE
     *                  run so far; this method adds to it, and reads it to name both sides of a
     *                  conflict
     */
    public Result integrateWave(UUID runId, String base, List<Winner> winners,
                                Map<String, String> claimedBy) {
        if (git == null || !git.isEnabled() || winners == null || winners.isEmpty()) {
            return new Result(null, null);
        }
        String branch = branchOf(runId);
        Path worktree = WORKTREE_ROOT.resolve("progress-" + runId);
        // A branch that already exists is the normal case from the second wave onwards, and after a
        // resume. Its head is where this wave's winners go on top; only the very first wave starts
        // the branch off the pinned base.
        boolean alreadyStarted = git.headSha(branch) != null;
        try {
            git.removeWorktree(worktree); // a leftover from a killed attempt, if any
            if (alreadyStarted) {
                git.addWorktreeAt(branch, worktree);
            } else {
                git.addWorktree(branch, worktree, base);
            }
            // Audited up front, for every winner of this wave, before any of them is merged. This
            // is what lets a stray file be told apart from a genuine collision below: a path only
            // ONE winner reaches is somebody's own scratch file (safe to drop once merged), a path
            // TWO winners both reach is exactly the ambiguous case MergeConflictBrief exists to
            // surface — and that distinction can only be drawn by looking at every winner's diff
            // before any of them has touched the worktree.
            // Also unioned up front: every write set this wave's OWN tasks declared. A path inside
            // some other task's write set is that task's real, legitimate destination — dropping it
            // because ONE OTHER winner's diff merely brushed past it on the way to compiling would
            // turn that other task's later merge into a spurious modify/delete conflict, which is
            // worse than leaving a stray file alone.
            List<PathPolicy.Audit> audits = new ArrayList<>(winners.size());
            Map<String, Integer> outOfWriteSetOccurrences = new HashMap<>();
            Set<String> writeSetUnion = new LinkedHashSet<>();
            for (Winner winner : winners) {
                // No operator-locked modules passed: the audit is read here ONLY to know which
                // paths a winner reached that its task does not own, so a conflict can name both
                // sides. Refusing a diff that touches a locked module stays where it was, in the
                // end-of-run integration, which is the one place that decision is made.
                PathPolicy.Audit audit = PathPolicy.audit(
                    WorkerToolbox.touchedPaths(winner.candidate().diffUnified()),
                    winner.task().writeSet(), winner.task().acceptanceTestDir(), List.of());
                audits.add(audit);
                for (String path : audit.outOfWriteSet()) {
                    outOfWriteSetOccurrences.merge(path, 1, Integer::sum);
                }
                if (winner.task().writeSet() != null) {
                    writeSetUnion.addAll(winner.task().writeSet());
                }
            }
            for (int i = 0; i < winners.size(); i++) {
                Winner winner = winners.get(i);
                PathPolicy.Audit audit = audits.get(i);
                for (String path : audit.outOfWriteSet()) {
                    claimedBy.putIfAbsent(path, winner.task().title());
                }
                try {
                    git.mergeBranch(worktree, winner.candidate().branch());
                } catch (Exception e) {
                    return new Result(null, MergeConflictBrief.of(winner.candidate().branch(),
                        winner.task(), audit.outOfWriteSet(), claimedBy, e.getMessage())
                        + "\n\nThis happened BETWEEN waves, not at the end: the tasks that have "
                        + "not run yet are cut from this branch, so there is no honest tree to "
                        + "start them from until the two changes above are reconciled. Every task "
                        + "that already has a winner keeps it — resuming the run does not swarm "
                        + "them again.");
                }
                log.info("Run {}: merged winner of '{}' ({}) onto {}", runId,
                    winner.task().title(), winner.candidate().branch(), branch);
                // Only a path uncontested within this wave, and not owned by any task's write
                // set, is dropped as stray — one another winner also reached, or one some task
                // in this wave is meant to write, is left alone, whatever it looks like: two
                // winners fighting over the same file is the conflict case above, and a task's
                // own destination is never this integrator's to remove.
                List<String> uncontested = audit.outOfWriteSet().stream()
                    .filter(path -> outOfWriteSetOccurrences.getOrDefault(path, 0) <= 1)
                    .filter(path -> !coveredByAnyWriteSet(path, writeSetUnion))
                    .toList();
                dropStrayFiles(worktree, winner.task().title(), uncontested);
            }
            String compileFailure = compiles(worktree, winners);
            if (compileFailure != null) {
                return new Result(null, compileFailure);
            }
            String head = git.headSha(branch);
            log.info("Run {}: the next wave builds on {} ({} winner(s) merged)", runId,
                head == null ? branch : head.substring(0, Math.min(8, head.length())),
                winners.size());
            return new Result(head, null);
        } catch (Exception e) {
            return new Result(null, "The winners of this wave could not be merged onto "
                + branch + ", so there is nothing for the next wave to build on: " + e.getMessage()
                + "\n\nEvery candidate branch is still there and nothing is lost.");
        } finally {
            git.removeWorktree(worktree);
        }
    }

    /**
     * True when {@code path} sits inside SOME task's declared write set, in the union built above
     * — its real, legitimate destination, whichever task that turns out to be. An empty union
     * (no task in this wave declared one) protects nothing, matching {@link PathPolicy#check} of
     * an empty write set: unrestricted, not "everything is somebody's".
     */
    private static boolean coveredByAnyWriteSet(String path, Set<String> writeSetUnion) {
        return !writeSetUnion.isEmpty()
            && PathPolicy.check(path, writeSetUnion, null, List.of()).allowed();
    }

    /**
     * Refuses to let a stray non-source file — a helper script, a scratch note, a generated
     * artefact, anything {@link StrayFileCheck} would flag — ride onto the progress branch just
     * because it was part of a winning diff (harness run 11, 2026-09-03: a worker's
     * {@code insert_dep.py} did exactly that, and the judge scored the candidate 1.00 without
     * ever being told the file was there). Removed from the merged worktree and committed here,
     * on the same branch every later wave is cut from, so the file never reaches a delivered
     * tree. A genuinely needed out-of-write-set file — a neighbouring class, say — is never
     * touched; only what {@link StrayFileCheck#isStray} flags is.
     */
    private void dropStrayFiles(Path worktree, String taskTitle, List<String> outOfWriteSet) {
        List<String> stray = StrayFileCheck.strayPathsIn(outOfWriteSet);
        if (stray.isEmpty()) {
            return;
        }
        List<String> dropped = new ArrayList<>();
        for (String path : stray) {
            try {
                if (Files.deleteIfExists(worktree.resolve(path))) {
                    dropped.add(path);
                }
            } catch (Exception e) {
                log.warn("Could not delete stray file {} from {}: {}", path, worktree, e.getMessage());
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        try {
            git.commitAll(worktree, "SwarmCoder: drop stray file(s) left by the winner of '"
                + taskTitle + "'\n\n" + String.join(", ", dropped) + " reached the winning diff "
                + "but is not a source or resource file and was not in the task's write set.");
        } catch (Exception e) {
            log.warn("Could not commit the removal of stray file(s) {} from the winner of '{}': "
                + "{}", dropped, taskTitle, e.getMessage());
        }
        for (String path : dropped) {
            log.info("dropped {} from the winner of '{}': not a source file and not in the "
                + "write set", path, taskTitle);
        }
    }

    /**
     * Null when the merged tree compiles; otherwise the brief for parking the run.
     *
     * <p>Compile only — not the acceptance suite. Each of these winners was verified green on this
     * exact base a moment ago, so re-running its tests here would measure the same thing twice; what
     * was never measured is the two of them TOGETHER, and a tree that will not compile is a tree the
     * next wave's workers cannot get a single build out of.
     *
     * <p>The acceptance-test trees are cleared first, for the same reason a candidate's are: the
     * pinned base carries whatever earlier runs left there, and a dead run's test naming classes
     * this plan never creates would fail {@code test-compile} on work that is perfectly fine. The
     * clearing is working-tree only; nothing is committed here.
     */
    private String compiles(Path worktree, List<Winner> winners) {
        Optional<VerifySpec> spec = VerifySpecLoader.loadTrusted(git.repoPath(), worktree);
        if (spec.isEmpty() || spec.get().compile() == null || spec.get().compile().isEmpty()) {
            return null; // no verification contract — the same allowance as everywhere else
        }
        for (Winner winner : winners) {
            try {
                AcceptanceOverlay.reduceTo(git, worktree, winner.task().acceptanceTestDir(),
                    null, List.of());
            } catch (Exception e) {
                log.warn("Could not clear the acceptance-test trees before the wave compile "
                    + "check: {}", e.getMessage());
            }
        }
        com.swarmcoder.verify.BuildBoxes.Box box;
        try {
            box = boxes.open(worktree, "The wave compile check");
        } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
            // Not "an absent instrument": the instrument is there and was refused a place to run.
            // Carrying on would dispatch the next wave onto a tree nobody compiled.
            return e.getMessage() + "\n\nEvery candidate branch is still there and nothing is "
                + "lost. Resume the run once a container can be started.";
        }
        try (box) {
            return compilesIn(box.target(), spec.get(), winners);
        }
    }

    private String compilesIn(com.swarmcoder.verify.ExecTarget target, VerifySpec contract,
                              List<Winner> winners) {
        Optional<VerifySpec> spec = Optional.of(contract);
        for (String command : spec.get().compile()) {
            ExecResult result;
            try {
                result = target.exec(command, spec.get().effectiveTimeoutSeconds());
            } catch (Exception e) {
                // The compiler could not be RUN. That is a fact about the machine, not about the
                // merged tree, and an absent instrument is never a verdict here - the same rule the
                // red-check and every verification stage follow.
                log.warn("The wave compile check could not run '{}': {}. The next wave is "
                    + "dispatched anyway.", command, e.getMessage());
                return null;
            }
            if (!result.succeeded()) {
                StringBuilder brief = new StringBuilder(
                    "The winners of this wave were each green on their own, but the tree they make "
                    + "together does not compile, so the tasks that come next have nothing to build "
                    + "on. What was merged:");
                winners.forEach(w -> brief.append("\n  - '").append(w.task().title())
                    .append("' (").append(w.candidate().branch()).append(')'));
                brief.append("\n\nThe command that failed: ").append(command)
                    .append("\n\n").append(tail(result.output()))
                    .append("\n\nEvery candidate branch is still there and nothing is lost. Fix the "
                        + "clash by hand on ").append(WaveIntegrator.class.getSimpleName().isEmpty()
                        ? "" : "the progress branch")
                    .append(", or re-plan, then resume the run — the tasks that already have a "
                        + "winner are not swarmed again.");
                return brief.toString();
            }
        }
        return null;
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String[] lines = output.split("\\R");
        int from = Math.max(0, lines.length - 40);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** One selected candidate and the task it won for. */
    public record Winner(Task task, CandidateSolution candidate) {}
}
