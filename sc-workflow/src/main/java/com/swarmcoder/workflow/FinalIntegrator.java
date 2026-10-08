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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.git.AcceptanceOverlay;
import com.swarmcoder.git.GitService;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.lsp.LspService;
import com.swarmcoder.lsp.LspServiceFactory;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.swarm.MergeConflictBrief;
import com.swarmcoder.swarm.StrayFileCheck;
import com.swarmcoder.swarm.SwarmEngineImpl;
import com.swarmcoder.swarm.WorkerToolbox;
import com.swarmcoder.verify.CommandPipelineVerifier;
import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import com.swarmcoder.verify.SecretScanner;
import com.swarmcoder.verify.Verdicts;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.eclipse.serializer.reference.Lazy;
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
import java.util.UUID;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.BlobSink;
import java.util.HashSet;
import java.util.Set;

/**
 * FINAL_INTEGRATION (spec §8.4/§14): merges the winning candidate branch of every task into
 * one integration branch, in TaskGraph topological order, with a FULL verification run after
 * every merge — not just at the end. Disjoint write sets make these merges conflict-free by
 * construction; a conflict or a red verification is exceptional and parks the run.
 */
public class FinalIntegrator {

    /**
     * @param failure      null on success; otherwise the human-readable reason the run must park
     * @param verification the LAST full verification run against the integration tree — after the
     *                     final merge on success, or the merge that turned it red on failure. This
     *                     is the report that actually ran the story's acceptance tests against the
     *                     merged code, and it is what the delivery decision must read: not a
     *                     candidate's own pre-merge report, which was verified alone, on its own
     *                     branch, before any other winner joined it. Null only when the run had
     *                     nothing to integrate, or no verification contract exists to run one
     *                     (the M1 allowance) — in both cases nobody ran anything against the tree.
     */
    public record Result(String integrationBranch, String failure, VerificationReport verification,
                         JourneyFailure journeyFailure, OwnedRefusal ownedRefusal,
                         boolean journeyCorrected) {
        public Result(String integrationBranch, String failure) {
            this(integrationBranch, failure, null, null, null, false);
        }
        public Result(String integrationBranch, String failure, VerificationReport verification) {
            this(integrationBranch, failure, verification, null, null, false);
        }
        public Result(String integrationBranch, String failure, VerificationReport verification,
                      JourneyFailure journeyFailure) {
            this(integrationBranch, failure, verification, journeyFailure, null, false);
        }
        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * A journey this run's own task claims failed in the browser after the last merge (section
     * 63). Unlike every other failure of this stage it names a task and a step, so the task can
     * go back to the workers once before the run stops.
     *
     * @param taskId   the task that claims the failed journey
     * @param evidence what a repair worker is told: the journey, the failing step, what the
     *                 browser said
     */
    public record JourneyFailure(UUID taskId, String evidence) {}

    /**
     * A refusal of this stage that names files one task of this run added - code nothing can
     * reach (section 69). Like a failed journey it has an owner and a reason, so the owner can
     * go back to the workers once before the run stops; before this it could only park.
     *
     * @param taskId   the task whose chosen candidate added the first file named
     * @param evidence what a repair worker is told: the refusal as the run was told it
     */
    public record OwnedRefusal(UUID taskId, String evidence) {}

    /**
     * Where a failed journey goes before any worker repairs anything: back to its author
     * (owner's decision, 2026-10-08, section 69). Asked while the container that holds the
     * merged, built tree is still there, so a correction can be made in it at once.
     */
    public interface JourneySendBack {
        /**
         * @param task         the task that claims the failed journeys
         * @param failed       its journeys that failed, each with the failing step and what the
         *                     page showed
         * @param onMergedTree makes journeys in the merged tree's container: the application
         *                     is started again and a browser carries them out
         * @return true when a corrected journey was taken and committed with the run's tests -
         *         the integration is then made again from the start; false when the journeys
         *         stand as they were
         */
        boolean review(Task task, List<JourneyFile.Result> failed,
                       java.util.function.Function<List<JourneyFile.Journey>,
                           JourneyRunner.Outcome> onMergedTree);
    }

    private static final Logger log = LoggerFactory.getLogger(FinalIntegrator.class);
    private static final Path WORKTREE_ROOT = Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    private final GitService gitService;
    private final ArtifactStore store;
    private final LspServiceFactory lspFactory;
    private final SecretScanner secretScanner = new SecretScanner();
    private final List<String> protectedPaths;
    private final BuildBoxes boxes;
    /** Where a sentence for the run's own record goes; nothing by default. */
    private java.util.function.Consumer<String> runRecord = sentence -> { };
    /** Who is asked about a failed journey before a worker is; nobody by default. */
    private JourneySendBack journeySendBack;

    /** A failed journey of this run goes back to its author through {@code sendBack} first. */
    public FinalIntegrator sendingJourneysBackTo(JourneySendBack sendBack) {
        this.journeySendBack = sendBack;
        return this;
    }

    /**
     * Sends what this stage could not establish and did not refuse to the run's own record
     * (live run 93: added types taken as found by a framework), beside the log.
     */
    public FinalIntegrator tellingTheRun(java.util.function.Consumer<String> record) {
        this.runRecord = record == null ? sentence -> { } : record;
        return this;
    }

    public FinalIntegrator(GitService gitService, ArtifactStore store) {
        this(gitService, store, LspServiceFactory.NONE);
    }

    /**
     * @param lspFactory builds an advisory LSP server scoped to the integration worktree (spec
     *                   §S6). {@link LspServiceFactory#NONE} (the default) disables it entirely.
     *                   Integration is the correct place for LSP: one server on one workspace,
     *                   not one per candidate.
     */
    public FinalIntegrator(GitService gitService, ArtifactStore store, LspServiceFactory lspFactory) {
        this(gitService, store, lspFactory, List.of());
    }

    /**
     * @param protectedPaths operator-declared locked modules; a winning diff that touches one is
     *                       parked rather than merged, even though the worker's tools already
     *                       refused it — because a shell can write what no tool checked
     */
    public FinalIntegrator(GitService gitService, ArtifactStore store, LspServiceFactory lspFactory,
                           List<String> protectedPaths) {
        this(gitService, store, lspFactory, protectedPaths, BuildBoxes.none());
    }

    /**
     * @param boxes where the merged tree is built, tested and looked at with a browser. All of it
     *              is model-written code, so it runs in a container that sees the integration
     *              tree and nothing else of this PC (owner decision, 2026-10-02). With
     *              {@link BuildBoxes#none()} the verification is refused unless host execution was
     *              allowed by name
     */
    public FinalIntegrator(GitService gitService, ArtifactStore store, LspServiceFactory lspFactory,
                           List<String> protectedPaths, BuildBoxes boxes) {
        this.boxes = boxes == null ? BuildBoxes.none() : boxes;
        this.gitService = gitService;
        this.store = store;
        this.lspFactory = lspFactory == null ? LspServiceFactory.NONE : lspFactory;
        this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    }

    public Result integrate(Run run) {
        if (!gitService.isEnabled()) {
            return new Result(null, null); // nothing to integrate without a target repo
        }
        TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null) {
            return new Result(null, null);
        }
        Map<UUID, CandidateSolution> winners = winnersByTask(graph);
        if (winners.isEmpty()) {
            if (run.nothingToBuild() && !graph.tasks().isEmpty()) {
                return verifyTheUnchangedTree(run, graph);
            }
            log.info("Run {}: no selected candidates — nothing to integrate", run.id());
            return new Result(null, null);
        }

        String integrationBranch = "swarm/integration/" + run.id();
        Path worktree = WORKTREE_ROOT.resolve("integration-" + run.id());
        // Out-of-write-set path -> the task whose winner reached it first. Only these can collide:
        // two winners' own write sets are disjoint by construction, so a merge conflict between
        // them has to involve a path at least one of them did not own.
        Map<String, String> claimedBy = new HashMap<>();
        // Audited up front, for every winner in the run, before any of them is merged. This is
        // what lets a stray file be told apart from a genuine collision below: a path only ONE
        // task's winner reaches is that task's own scratch file (safe to drop once merged), a
        // path TWO winners both reach is exactly the ambiguous case MergeConflictBrief exists to
        // surface — already logged above via claimedBy — and that distinction can only be drawn
        // by looking at every winner's diff before any of them has touched the worktree.
        // Also unioned up front: every write set ANY task in the run declared. A path inside some
        // other task's write set is that task's real, legitimate destination — dropping it because
        // one earlier winner's diff merely brushed past it on the way to compiling would turn that
        // other task's later merge into a spurious modify/delete conflict, which is worse than
        // leaving a stray file alone.
        Map<UUID, PathPolicy.Audit> auditByTask = new HashMap<>();
        Map<String, Integer> outOfWriteSetOccurrences = new HashMap<>();
        Set<String> writeSetUnion = new LinkedHashSet<>();
        for (List<Task> wave : SwarmEngineImpl.topologicalWaves(graph)) {
            for (Task task : wave) {
                if (task.writeSet() != null) {
                    writeSetUnion.addAll(task.writeSet());
                }
                CandidateSolution winner = winners.get(task.id());
                if (winner == null) {
                    continue;
                }
                PathPolicy.Audit audit = PathPolicy.audit(
                    WorkerToolbox.touchedPaths(winner.diffUnified()),
                    task.writeSet(), task.acceptanceTestDir(), protectedPaths);
                auditByTask.put(task.id(), audit);
                for (String path : audit.outOfWriteSet()) {
                    outOfWriteSetOccurrences.merge(path, 1, Integer::sum);
                }
            }
        }
        try {
            // The run's own tests commit — its pinned base plus the acceptance tests — not a live
            // HEAD. The winners were built on that base, so integrating them anywhere else would
            // merge them onto a tree they were never verified against; and once accepting a story
            // lands work on the delivery branch, a live HEAD moves under a run that is still going.
            // Descending from the TESTS commit rather than the bare base is what carries the run's
            // acceptance tests onto the integration branch, and from there to the delivery branch
            // together with the code that passes them — the only route by which they reach it.
            // Still the TESTS commit, and NOT the run's progress branch, even though the winners
            // have already been merged onto each other wave by wave and most of these merges now
            // bring nothing new. The acceptance tests reach the delivery branch by exactly one
            // route, and this is it; descending from the progress branch would deliver the code
            // and leave the tests that prove it behind.
            gitService.addWorktree(integrationBranch, worktree, run.verificationPoint());
            // Every journey the tree holds, read now: the reductions below clear the protected
            // trees of the working copy. They are made once, after the last merge.
            Map<String, String> journeysHeld = journeysIn(worktree, graph);
            // Which acceptance tests are due so far. After each merge the worktree is reduced to
            // the tests claimed by the tasks merged up to that point — the same rule each
            // candidate was verified by, applied cumulatively — so a test for a task three merges
            // ahead does not fail (or fail to compile against) the tree of the task before it.
            // After the last merge every claimed test is present, which is where all of them are
            // finally due.
            String testsCommit = run.acceptanceTestsCommit();
            Set<String> testsDue = new java.util.LinkedHashSet<>();
            // Tests of checks the start tree already satisfied, whose task was dropped: no
            // task claims them, and they are due from the first merge on - the merged tree must
            // keep them green, and running them here is what stamps their criteria.
            testsDue.addAll(run.alreadySatisfiedTests());
            // The most recent full verification report — after the last merge on success, or the
            // merge that turned it red on failure. This is what actually ran the story's acceptance
            // tests against the merged tree, and it travels out on the Result so the delivery
            // decision reads THIS, not a candidate's own pre-merge, verified-alone report.
            VerificationReport[] lastVerification = new VerificationReport[1];
            // One LSP server for the whole integration worktree (spec §S6), reused across the
            // per-merge verifications and closed when integration finishes. NONE by default.
            try (LspService lsp = lspFactory.create(worktree)) {
                for (List<Task> wave : SwarmEngineImpl.topologicalWaves(graph)) {
                    for (Task task : wave) {
                        CandidateSolution winner = winners.get(task.id());
                        if (winner == null) {
                            log.warn("Run {}: task '{}' has no winner — integrating without it",
                                run.id(), task.title());
                            continue;
                        }
                        // Path audit before the merge. The tool-level write-set check only sees
                        // what the TOOLS did; a worker's shell can write anything in the worktree,
                        // and `git add -A` then sweeps it into the candidate diff. This is the last
                        // point at which an out-of-bounds change can be caught, and until now
                        // nothing looked — touchedPaths() existed and was never called.
                        PathPolicy.Audit audit = auditByTask.get(task.id());
                        if (!audit.blocking().isEmpty()) {
                            StringBuilder brief = new StringBuilder("The winning diff of task '")
                                .append(task.title()).append("' changes files it was not allowed to:");
                            audit.blocking().forEach(v -> brief.append("\n  ").append(v));
                            brief.append("\n\nThis reached the diff without passing a tool check, "
                                + "which means a shell command wrote it. Review the candidate before "
                                + "integrating it by hand.");
                            return new Result(integrationBranch, brief.toString());
                        }
                        // Outside the task's write set is NOT a reason to park (2026-09-02). The
                        // workers are no longer killed for it, so it now reaches integration by
                        // design, and parking here would only move the same wrong verdict from
                        // turn 10 to the last stage of the run. What it IS is the one fact that
                        // explains a conflicting merge, so it is remembered and named if one
                        // happens - see MergeConflictBrief.
                        for (String path : audit.outOfWriteSet()) {
                            String earlier = claimedBy.putIfAbsent(path, task.title());
                            if (earlier != null) {
                                log.warn("Run {}: '{}' and '{}' both change {}, which neither task "
                                    + "owns - the second merge may conflict",
                                    run.id(), earlier, task.title(), path);
                            }
                        }
                        if (!audit.outOfWriteSet().isEmpty()) {
                            log.info("Run {}: winner of '{}' changes {} file(s) outside its write "
                                + "set: {}", run.id(), task.title(), audit.outOfWriteSet().size(),
                                String.join(", ", audit.outOfWriteSet()));
                        }
                        // Secret scan on the integrated diff before APPROVAL (spec §18). The
                        // winners' write sets are disjoint, so scanning each winning diff here
                        // covers the whole integration; a hit parks the run for a human.
                        List<SecretScanner.Finding> secrets = secretScanner.scan(winner.diffUnified());
                        if (!secrets.isEmpty()) {
                            StringBuilder brief = new StringBuilder("Secret scan flagged the winning diff of task '")
                                .append(task.title()).append("' — remove the credential or override by hand:");
                            secrets.forEach(f -> brief.append("\n  [").append(f.kind()).append("] ").append(f.line()));
                            return new Result(integrationBranch, brief.toString());
                        }
                        if (testsCommit != null) {
                            // The previous reduction left working-tree deletions under the
                            // protected trees; the merge must start from the committed tree.
                            gitService.restoreTree(worktree, ".");
                        }
                        try {
                            gitService.mergeBranch(worktree, winner.branch());
                        } catch (Exception e) {
                            // The same sentence a conflict BETWEEN waves produces - one wording,
                            // one place, so the two moments a merge can fail cannot give the
                            // operator two different accounts of the same event.
                            return new Result(integrationBranch, MergeConflictBrief.of(
                                winner.branch(), task, audit.outOfWriteSet(), claimedBy,
                                e.getMessage()));
                        }
                        // Only a path uncontested across the whole run, and not owned by any
                        // task's write set, is dropped as stray — one another task's winner also
                        // reached, or one some task is meant to write, is left alone, whatever it
                        // looks like: two winners fighting over the same file is the conflict
                        // case above (claimedBy), and a task's own destination is never this
                        // integrator's to remove.
                        List<String> uncontested = audit.outOfWriteSet().stream()
                            .filter(path -> outOfWriteSetOccurrences.getOrDefault(path, 0) <= 1)
                            .filter(path -> !coveredByAnyWriteSet(path, writeSetUnion))
                            .toList();
                        dropStrayFiles(worktree, task.title(), uncontested);
                        if (testsCommit != null) {
                            testsDue.addAll(task.authoredTestPaths());
                            AcceptanceOverlay.Outcome placed = AcceptanceOverlay.reduceTo(gitService,
                                worktree, task.acceptanceTestDir(), testsCommit, testsDue);
                            log.info("Run {}: after merging '{}' the integration tree holds the "
                                + "acceptance tests due so far — {}", run.id(), task.title(),
                                placed.describe());
                        }
                        // Full verification after EVERY merge (spec §14) — a regression caused by
                        // combining independently green tasks must surface at the merge that did it.
                        VerifyOutcome outcome = verifyIntegration(worktree, task, lsp,
                            com.swarmcoder.verify.VerificationBaseline.of(
                                run.baselineFailingTests()));
                        if (outcome.report() != null) {
                            lastVerification[0] = outcome.report();
                        }
                        if (outcome.failure() != null) {
                            return new Result(integrationBranch, "Verification failed after merging task '"
                                + task.title() + "':\n" + outcome.failure(), outcome.report());
                        }
                        markTaskDone(task);
                        log.info("Run {}: merged '{}' ({}) — integration verified green",
                            run.id(), task.title(), winner.branch());
                    }
                }
            }
            // Every winner is merged and the merged tree is green. One thing no test of it can
            // show is still asked: can the application reach what the run added (the seven
            // accepted stories whose screens no user could open, 2026-10-05)?
            UUID[] addedBy = new UUID[1];
            String unreachable = unreachableAddedCode(run, worktree, winners.values(), addedBy);
            if (unreachable != null) {
                return new Result(integrationBranch, unreachable, lastVerification[0], null,
                    addedBy[0] == null ? null : new OwnedRefusal(addedBy[0],
                        "--- final integration refused what this task added ---\n"
                            + "Every task of this run was merged and every acceptance test "
                            + "passed. Then this was found, on the merged tree:\n" + unreachable
                            + "\n\nYour checkout holds this task's chosen change. Make the "
                            + "application reach what it added - from code this task may "
                            + "write - or take out what nothing needs.\n"), false);
            }
            // And the other thing no test of the code can show: that a person can reach and use
            // what was built. The journeys are made here, once, in a real browser (section 63).
            Result journeys = makeJourneys(run, graph, worktree, integrationBranch, journeysHeld,
                lastVerification[0]);
            if (journeys != null) {
                return journeys;
            }
            return new Result(integrationBranch, null, lastVerification[0]);
        } catch (Exception e) {
            return new Result(integrationBranch, "Integration setup failed: " + e.getMessage());
        } finally {
            // The container first: it has the tree mounted.
            boxes.release(worktree);
            gitService.removeWorktree(worktree);
        }
    }

    /**
     * The failure sentence when the merged tree holds production source files this run added and
     * nothing reachable uses; null otherwise, and whenever that cannot be established. Read from
     * the object graph of the integration worktree ({@link ReachableCode}): no model, nothing run.
     * {@code -Dswarmcoder.verify.unreachableAddedCode=off} switches it off.
     */
    private String unreachableAddedCode(Run run, Path worktree,
                                        java.util.Collection<CandidateSolution> winners,
                                        UUID[] addedBy) {
        String base = run.baseCommit();
        if (!ReachableCode.enabled() || base == null || base.isBlank()) {
            return null;
        }
        try {
            boolean addsProductionCode = false;
            for (CandidateSolution winner : winners) {
                for (String path : WorkerToolbox.touchedPaths(winner.diffUnified())) {
                    String file = path.replace('\\', '/');
                    if (ReachableCode.isProduction(file) && Files.isRegularFile(worktree.resolve(file))
                            && gitService.fileAt(base, file) == null) {
                        addsProductionCode = true;
                    }
                }
            }
            if (!addsProductionCode) {
                return null; // nothing to ask, and no reason to parse the tree
            }
            ReachableCode.Finding finding = ReachableCode.of(worktree)
                .judge(file -> gitService.fileAt(base, file) == null);
            if (finding.status() == ReachableCode.Status.UNDETERMINED || !finding.note().isEmpty()) {
                log.info("Run {}: whether what the run added can be reached - {}", run.id(),
                    finding.note());
            }
            String believed = ReachableCode.takenAsFoundNote(finding);
            if (!believed.isEmpty()) {
                runRecord.accept("FINAL_INTEGRATION: " + believed + ".");
            }
            String objection = ReachableCode.objection(finding, "This run adds");
            if (objection != null) {
                log.warn("Run {}: {}", run.id(), objection);
                // Whose it is: the task whose chosen change added the first file named.
                for (ReachableCode.Orphan orphan : finding.orphans()) {
                    String file = orphan.file().replace((char) 92, '/');
                    for (CandidateSolution winner : winners) {
                        if (addedBy[0] == null && WorkerToolbox.touchedPaths(winner.diffUnified())
                                .stream().anyMatch(path -> path.replace((char) 92, '/').equals(file))) {
                            addedBy[0] = winner.taskId();
                        }
                    }
                }
                return objection + ".\n\nEvery acceptance test passed, and none of them shows "
                    + "this: a test that calls the new code directly is green whether or not the "
                    + "application ever does. (-D" + ReachableCode.SWITCH + "=off accepts the "
                    + "run as it is.)";
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Run {}: whether what the run added can be reached could not be established "
                + "({}); not judged on it", run.id(), e.toString());
            return null;
        }
    }

    /**
     * The journey files of the tree, path to content: every {@code <name>.journey.yaml} under a
     * task's protected acceptance directory or under any directory of that shape. Those of this
     * run are there because the integration branch is cut from the run's tests commit; the others
     * were delivered with earlier stories, and the application must still pass them.
     */
    static Map<String, String> journeysIn(Path worktree, TaskGraph graph) {
        Map<String, String> held = new java.util.TreeMap<>();
        if (!JourneyFile.enabled() || worktree == null || !Files.isDirectory(worktree)) {
            return held;
        }
        Set<String> protectedDirs = new HashSet<>();
        for (Task task : graph.tasks()) {
            if (task.acceptanceTestDir() != null && !task.acceptanceTestDir().isBlank()) {
                protectedDirs.add(task.acceptanceTestDir().replace('\\', '/'));
            }
        }
        Set<String> skipped = Set.of(".git", "target", "build", "node_modules", ".swarmcoder");
        try {
            Files.walkFileTree(worktree, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    return !dir.equals(worktree)
                            && skipped.contains(dir.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                        : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs)
                        throws java.io.IOException {
                    String path = worktree.relativize(file).toString().replace('\\', '/');
                    if (JourneyFile.isJourney(path) && (path.contains("/src/test/java/swarm/")
                            || path.startsWith("src/test/java/swarm/")
                            || protectedDirs.stream().anyMatch(dir -> path.startsWith(dir + "/")))) {
                        held.put(path, Files.readString(file));
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("The journeys of {} could not be listed: {}", worktree, e.toString());
        }
        return held;
    }

    /**
     * Makes every journey the merged tree holds, in the container the tree was just built and
     * verified in. Null when all passed or there is none; otherwise the failure the run stops on.
     *
     * <p>A journey that could not be made is not passed over: the story has a screen, and
     * nothing else shows a person can use it. A journey of this run that fails names the task
     * that claims it, so that task can be sent back to the workers with the failing step.
     */
    private Result makeJourneys(Run run, TaskGraph graph, Path worktree, String integrationBranch,
                                Map<String, String> held, VerificationReport verification) {
        if (held.isEmpty()) {
            return null;
        }
        Map<String, Task> claimedBy = new HashMap<>();
        for (Task task : graph.tasks()) {
            task.journeyPaths().forEach(path -> claimedBy.put(path.replace('\\', '/'), task));
        }
        List<JourneyFile.Journey> journeys = new ArrayList<>();
        for (Map.Entry<String, String> file : held.entrySet()) {
            JourneyFile.Read read = JourneyFile.read(file.getKey(), file.getValue());
            if (read.ok()) {
                journeys.add(read.journey());
            } else {
                log.warn("Run {}: {} is not a well-formed journey and is not made: {}", run.id(),
                    file.getKey(), read.objection().replace('\n', ' '));
            }
        }
        if (journeys.isEmpty()) {
            return null;
        }
        Optional<VerifySpec> spec = VerifySpecLoader.loadTrusted(gitService.repoPath(), worktree);
        StringBuilder browserLog = new StringBuilder();
        JourneyRunner.Outcome outcome;
        ExecTarget target = null;
        if (spec.isEmpty() || !JourneyFile.canRun(spec.get())) {
            outcome = JourneyRunner.run(null, spec.orElse(null), journeys, BlobSink.NONE, browserLog);
        } else {
            target = boxes.use(worktree, "Final integration", true);
            outcome = JourneyRunner.run(target, spec.get(), journeys, BlobSink.NONE, browserLog);
        }
        if (outcome.couldNotRun() != null || outcome.didNotStart() != null) {
            String why = outcome.couldNotRun() != null ? outcome.couldNotRun()
                : "the application did not start in the merged tree: " + outcome.didNotStart();
            log.warn("Run {}: {} journey(s) were not made - {}", run.id(), journeys.size(), why);
            return new Result(integrationBranch, "Every acceptance test passed on the merged "
                + "tree, and the " + journeys.size() + " journey(s) of this project could not be "
                + "made: " + why + ".\n\nA journey is what shows that a person can reach and use "
                + "the screens: " + journeys.stream().map(JourneyFile.Journey::path).toList()
                + ". One that was not made shows nothing, so the story is not delivered on it. "
                + "Nothing was run on this PC instead. (-D" + JourneyFile.SWITCH + "=off "
                + "delivers without journeys.)", verification);
        }
        List<JourneyFile.Result> failed = outcome.failed();
        log.info("Run {}: {} journey(s) made in the browser after the last merge, {} failed",
            run.id(), journeys.size(), failed.size());
        if (failed.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("Every acceptance test passed on the merged "
            + "tree, and " + failed.size() + " of " + journeys.size() + " journey(s) failed in "
            + "a real browser - the application was started and used from its entry page:\n");
        Task owner = null;
        List<JourneyFile.Result> ofOwner = new ArrayList<>();
        for (JourneyFile.Result result : failed) {
            Task claiming = claimedBy.get(result.journey().path());
            text.append("\n- journey \"").append(result.journey().name()).append("\" (")
                .append(result.journey().path()).append(claiming == null
                    ? ", delivered with an earlier story" : ", task '" + claiming.title() + "'")
                .append("): ").append(result.failure());
            if (claiming != null && (owner == null || owner == claiming)) {
                owner = claiming;
                ofOwner.add(result);
            }
        }
        text.append("\n\nA journey fails when a person cannot do what it describes: the screen "
            + "is not reachable from the entry page, or what the step names is not on it.");
        log.warn("Run {}: {}", run.id(), text);
        // Back to its author before any worker (section 69), while this container still holds
        // the built tree: a correction is made in it at once.
        if (owner != null && journeySendBack != null && target != null) {
            ExecTarget live = target;
            VerifySpec contract = spec.get();
            if (journeySendBack.review(owner, List.copyOf(ofOwner), again -> JourneyRunner.run(
                    live, contract, again, BlobSink.NONE, new StringBuilder()))) {
                return new Result(integrationBranch, text + "\n\nThe journey went back to its "
                    + "author, who corrected it. The correction is committed with the run's "
                    + "tests and the integration is made again.", verification, null, null,
                    true);
            }
        }
        return new Result(integrationBranch, text.toString(), verification, owner == null ? null
            : new JourneyFailure(owner.id(), JourneysOfAPlan.repairEvidence(ofOwner)));
    }

    /**
     * A run with no winner because nothing had to be built (owner decision, 2026-10-03): every
     * check of the plan was already satisfied by the tree the run started from. That tree is
     * verified ONCE here, unchanged, in the same container and by the same pipeline a merged
     * tree is, with the acceptance tests of those checks placed in it. The report is what the
     * delivery decision stamps each criterion from - a real run of the tests on a named commit,
     * not the red-check's word for it.
     *
     * <p>The integration branch is cut from the run's tests commit and nothing is merged onto
     * it, so delivering it carries the acceptance tests and no code. A pass that fails returns
     * the failure with the report beside it, and the run parks on that evidence.
     */
    private Result verifyTheUnchangedTree(Run run, TaskGraph graph) {
        String integrationBranch = "swarm/integration/" + run.id();
        Path worktree = WORKTREE_ROOT.resolve("integration-" + run.id());
        Task first = graph.tasks().get(0);
        try {
            gitService.addWorktree(integrationBranch, worktree, run.verificationPoint());
            String testsCommit = run.acceptanceTestsCommit();
            Set<String> testsDue = new java.util.LinkedHashSet<>(run.alreadySatisfiedTests());
            for (Task task : graph.tasks()) {
                testsDue.addAll(task.authoredTestPaths());
            }
            if (testsCommit != null) {
                AcceptanceOverlay.Outcome placed = AcceptanceOverlay.reduceTo(gitService, worktree,
                    first.acceptanceTestDir(), testsCommit, testsDue);
                log.info("Run {}: nothing was built; the unchanged tree holds the acceptance "
                    + "tests of the checks it already satisfies — {}", run.id(), placed.describe());
            }
            List<String> claimed = new ArrayList<>();
            for (Task task : graph.tasks()) {
                for (String check : store.describeClaimedChecks(task)) {
                    if (!claimed.contains(check)) {
                        claimed.add(check);
                    }
                }
            }
            VerifyOutcome outcome;
            try (LspService lsp = lspFactory.create(worktree)) {
                outcome = verifyIntegration(worktree, first, lsp,
                    com.swarmcoder.verify.VerificationBaseline.of(run.baselineFailingTests()),
                    claimed);
            }
            if (outcome.failure() != null) {
                return new Result(integrationBranch, "Nothing was built for this run, because "
                    + "every check of it had passed on the code it starts from. The one "
                    + "verification of that unchanged tree (" + run.verificationPoint()
                    + ") with the acceptance tests " + testsDue + " did NOT pass, so the story "
                    + "is not delivered:\n" + outcome.failure(), outcome.report());
            }
            for (Task task : graph.tasks()) {
                markTaskDone(task);
            }
            log.info("Run {}: NOTHING WAS BUILT - the unchanged tree {} was verified once with "
                + "{} acceptance test file(s) and passed; {} task(s) are done without a winner.",
                run.id(), run.verificationPoint(), testsDue.size(), graph.tasks().size());
            return new Result(integrationBranch, null, outcome.report());
        } catch (Exception e) {
            return new Result(integrationBranch, "The unchanged tree could not be verified: "
                + e.getMessage());
        } finally {
            boxes.release(worktree);
            gitService.removeWorktree(worktree);
        }
    }

    /**
     * True when {@code path} sits inside SOME task's declared write set, in the union built above
     * — its real, legitimate destination, whichever task that turns out to be. An empty union (no
     * task in the run declared one) protects nothing, matching {@link PathPolicy#check} of an
     * empty write set: unrestricted, not "everything is somebody's".
     */
    private static boolean coveredByAnyWriteSet(String path, Set<String> writeSetUnion) {
        return !writeSetUnion.isEmpty()
            && PathPolicy.check(path, writeSetUnion, null, List.of()).allowed();
    }

    /**
     * Refuses to let a stray non-source file — a helper script, a scratch note, a generated
     * artefact, anything {@link StrayFileCheck} would flag — reach the delivery branch just
     * because it was part of a winning diff (harness run 11, 2026-09-03: a worker's
     * {@code insert_dep.py} did exactly that, and the judge scored the candidate 1.00 without
     * ever being told the file was there). Removed from the integration worktree and committed
     * here, before this merge's verification runs. A genuinely needed out-of-write-set file — a
     * neighbouring class, say — is never touched; only what {@link StrayFileCheck#isStray} flags
     * is. The same rule {@code WaveIntegrator} applies between waves, applied again here because a
     * task's winner reaches this stage independently of whether it ever went through a wave merge.
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
            gitService.commitAll(worktree, "SwarmCoder: drop stray file(s) left by the winner of '"
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

    /** Marks a fully-integrated task DONE and persists it (the object is reachable from the graph). */
    private void markTaskDone(Task task) {
        task.setState(TaskState.DONE);
        try {
            store.storeChanged(task).get();
        } catch (Exception e) {
            log.warn("Failed to persist task '{}' DONE state: {}", task.title(), e.getMessage());
        }
    }

    private Map<UUID, CandidateSolution> winnersByTask(TaskGraph graph) {
        Set<UUID> taskIds = new HashSet<>();
        graph.tasks().forEach(task -> taskIds.add(task.id()));
        Map<UUID, CandidateSolution> winners = new HashMap<>();
        for (Lazy<Object> lazy : store.root().candidateArchives.values()) {
            Object value = Lazy.get(lazy);
            if (value instanceof CandidateSolution candidate
                    && candidate.state() == CandidateState.SELECTED
                    && taskIds.contains(candidate.taskId())) {
                winners.put(candidate.taskId(), candidate);
            }
        }
        return winners;
    }

    /**
     * @param report  the full verification report the merge produced, or null when there was no
     *                 verification contract to run one (the M1 allowance)
     * @param failure null when green; otherwise the failure summary
     */
    private record VerifyOutcome(VerificationReport report, String failure) {}

    /** True when the contract looks at an application or built pages with a browser; both run in the UI image. */
    static boolean servesABrowserCheck(VerifySpec spec) {
        return spec != null && spec.browser() != null && spec.browser().serve() != null;
    }

    private VerifyOutcome verifyIntegration(Path worktree, Task task, LspService lsp,
                                            com.swarmcoder.verify.VerificationBaseline baseline) {
        return verifyIntegration(worktree, task, lsp, baseline, store.describeClaimedChecks(task));
    }

    /** The same, held to {@code claimedChecks} - several tasks' when no single task was merged. */
    private VerifyOutcome verifyIntegration(Path worktree, Task task, LspService lsp,
                                            com.swarmcoder.verify.VerificationBaseline baseline,
                                            List<String> claimedChecks) {
        // Trusted root, not the integration worktree — same reason as in the swarm engine.
        Optional<VerifySpec> spec = VerifySpecLoader.loadTrusted(gitService.repoPath(), worktree);
        if (spec.isEmpty()) {
            return new VerifyOutcome(null, null); // no verification contract — the M1 allowance
        }
        // One container for the whole integration: this runs after every merge, on the same tree.
        // It is started from the image with a browser in it when the contract has a browser check
        // of a served application, and that check then runs inside it. A container that cannot be
        // started throws, and integrate() reports that as its failure: nothing runs on this PC.
        ExecTarget target = boxes.use(worktree, "Final integration", servesABrowserCheck(spec.get()));
        VerificationReport report = new CommandPipelineVerifier(BlobSink.NONE, lsp)
            // The tests that already failed on the tree the run started from are not held
            // against the merged tree either - the same baseline the candidates were judged by.
            .verify(target, task, spec.get(), List.of(), java.util.Set.of(), baseline);
        // The same rule as per candidate (§17.1), and it belongs here at least as much: THIS is the
        // report the delivery decision reads when it stamps each criterion. An integration whose
        // acceptance stage executed no test would otherwise merge, deliver, and leave every
        // criterion UNKNOWN at the very end of the run — the expensive way to find out.
        Verdicts.Verdict verdict = Verdicts.assess(report, claimedChecks);
        if (verdict.survived()) {
            return new VerifyOutcome(report, null);
        }
        return new VerifyOutcome(report, verdict.reason() + "\n\ncompiles=" + report.compiles()
            + (report.acceptance() != null ? ", acceptance " + report.acceptance().passed()
                + " passed / " + report.acceptance().failed() + " failed" : "")
            + (report.existing() != null ? ", existing " + report.existing().failed() + " failed" : "")
            + "\n" + (report.logTail() == null ? "" : report.logTail()));
    }
}
