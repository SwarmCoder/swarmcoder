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

import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.git.GitService;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.BlobSink;
import com.swarmcoder.verify.CommandPipelineVerifier;
import com.swarmcoder.verify.BuildBoxes;
import com.swarmcoder.verify.Verdicts;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import com.swarmcoder.lsp.LspService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Puts an accepted story's code on the project's delivery branch — the step that did not exist, and
 * whose absence is why a story could not build on the one before it.
 *
 * <p><b>What was missing.</b> A run merged its task winners onto {@code swarm/integration/<runId>}
 * and stopped there. Nothing in the product ever merged anything into the branch the operator works
 * on. So the nine stories started together on 2026-08-28 could not have helped each other even if
 * they had waited politely in turn: each worktree was cut from the delivery branch, and no earlier
 * story's work was ever on it. Accepting a story was a state change in a database and nothing more.
 *
 * <p><b>The same discipline one level up.</b> {@link FinalIntegrator} already solved this problem for
 * task winners inside a run: merge in dependency order, use a real git merge so a configured merge
 * driver applies, verify the whole tree after every merge, and park rather than proceed when a merge
 * conflicts or the result goes red. A story merge deserves no weaker treatment, so this is the same
 * shape:
 *
 * <ul>
 *   <li><b>Order</b> comes free. A story is only accepted once every story it builds on is accepted,
 *       and acceptance is what lands code here — so story merges happen in dependency order by
 *       construction, without a second scheduler.</li>
 *   <li><b>The merge happens in a throwaway worktree</b> cut from the current delivery-branch tip,
 *       never in the operator's own checkout. Nothing they can see moves until the result is
 *       verified.</li>
 *   <li><b>Full verification after the merge</b>, through the project's own verification contract.
 *       Two stories can each be green alone and red together; this is the only place that shows up,
 *       and it is exactly the same reason the integrator verifies after every task merge.</li>
 *   <li><b>Red or conflicted stops.</b> The worktree is thrown away, the delivery branch never moved,
 *       and the reason comes back in words. In unattended mode that story is left for the morning and
 *       the queue carries on with whatever does not depend on it.</li>
 * </ul>
 *
 * <p>When the result is green the delivery branch is fast-forwarded onto the verified merge commit —
 * arithmetic, not a second merge, because the work being landed was built and verified on top of
 * that exact tip.
 *
 * <p><b>This is where a run's acceptance tests reach the delivery branch, and the only place.</b>
 * The integration commit descends from the run's tests commit ({@code swarm/tests/<runId>}), so
 * merging it lands every acceptance test the run wrote together with the code that passes them —
 * and the verification here runs the whole tree, those tests included, beside every earlier
 * story's. A run that never gets this far leaves nothing on the delivery branch: no test from a
 * dead run can break the next run's test-compile, which is what four such files did on 2026-09-02.
 */
public final class StoryDelivery {

    private static final Logger log = LoggerFactory.getLogger(StoryDelivery.class);
    private static final Path WORKTREE_ROOT =
        Path.of(System.getProperty("user.home"), ".swarmcoder", "wt");

    /**
     * @param deliveredCommit the commit now on the delivery branch, or null when there was nothing
     *                        to deliver
     * @param failure         null when the work is on the delivery branch; otherwise the
     *                        plain-English reason it is not
     */
    public record Result(String deliveredCommit, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final GitService git;
    private final ArtifactStore store;

    private final BuildBoxes boxes;

    public StoryDelivery(GitService git, ArtifactStore store) {
        this(git, store, BuildBoxes.none());
    }

    /**
     * @param boxes where the merged tree is verified: a container that sees that tree and nothing
     *              else of this PC, because what is built and tested there is model-written code.
     *              With {@link BuildBoxes#none()} the delivery is refused unless host execution
     *              was allowed by name
     */
    public StoryDelivery(GitService git, ArtifactStore store, BuildBoxes boxes) {
        this.git = git;
        this.store = store;
        this.boxes = boxes == null ? BuildBoxes.none() : boxes;
    }

    /**
     * Merges {@code integrationCommit} into the delivery branch, verifies the merged tree, and moves
     * the branch onto it.
     *
     * @param branch the project's delivery branch; null means "whatever the repository is on"
     */
    public Result deliver(Story story, String integrationCommit, String branch) {
        if (git == null || !git.isEnabled()) {
            // No target repository — there is nothing to merge into and nothing to claim. This is
            // the shape a test harness and a brand-new project both have.
            return new Result(null, null);
        }
        String base = branch == null || branch.isBlank() ? git.currentBranch() : branch;
        if (base == null) {
            return new Result(null, "the project's repository is not on any branch, so there is "
                + "nowhere to put the finished work. Check out the branch you build on and accept "
                + "again.");
        }
        if (integrationCommit == null || integrationCommit.isBlank()) {
            return new Result(null, "this story has no finished code recorded against it, so there "
                + "is nothing to deliver. Build it again before accepting it.");
        }
        String tip = git.resolveCommit(base);
        if (tip == null) {
            return new Result(null, "the branch " + base + " does not exist in the project's "
                + "repository, so the finished work cannot be put anywhere.");
        }
        if (git.isAncestor(integrationCommit, tip)) {
            return new Result(tip, null);   // already delivered; accepting twice must not fail
        }

        String key = story == null || story.key() == null ? "story" : story.key();
        UUID id = story == null || story.id() == null ? UUID.randomUUID() : story.id();
        String deliveryBranch = "swarm/delivery/" + id;
        Path worktree = WORKTREE_ROOT.resolve("delivery-" + id);
        try {
            git.addWorktree(deliveryBranch, worktree, tip);
            try {
                git.mergeBranch(worktree, integrationCommit);
            } catch (Exception e) {
                return new Result(null, "the code built for " + key + " no longer fits with what is "
                    + "already on " + base + " — the same files were changed twice and git cannot "
                    + "combine them. Nothing was changed on " + base + ", and this needs a person:\n"
                    + e.getMessage());
            }
            String red = verify(worktree, story);
            if (red != null) {
                return new Result(null, "the work for " + key + " passed on its own, but once "
                    + "combined with what is already on " + base + " the project no longer passes:\n"
                    + red + "\n\nNothing was changed on " + base + ".");
            }
            String merged = git.headSha(deliveryBranch);
            if (merged == null) {
                return new Result(null, "the merge of " + key + " produced no commit that could be "
                    + "found again, so nothing was delivered.");
            }
            git.removeWorktree(worktree);
            String refused = git.fastForwardBranchTo(base, merged);
            if (refused != null) {
                return new Result(null, refused);
            }
            log.info("Story {} delivered onto {} at {}", key, base, merged);
            return new Result(merged, null);
        } catch (Exception e) {
            return new Result(null, "the finished work for " + key + " could not be put on " + base
                + ": " + e.getMessage());
        } finally {
            git.removeWorktree(worktree);
            git.deleteCandidateBranch(deliveryBranch);
        }
    }

    /**
     * The project's own verification contract, run over the merged tree. Null when green.
     *
     * <p>Read from the operator's trusted root rather than from the merged worktree, for the reason
     * the integrator reads it that way: a verification contract that the code being verified is
     * allowed to rewrite verifies nothing.
     */
    private static String keyOf(Story story) {
        return story == null || story.key() == null ? "a story" : story.key();
    }

    private String verify(Path worktree, Story story) {
        java.util.Optional<VerifySpec> spec =
            VerifySpecLoader.loadTrusted(git.repoPath(), worktree);
        if (spec.isEmpty()) {
            return null;    // no verification contract — the same allowance the integrator makes
        }
        Task subject = anyTaskOf(story);
        VerificationReport report;
        try (BuildBoxes.Box box = boxes.open(worktree, "Delivery of " + keyOf(story),
                FinalIntegrator.servesABrowserCheck(spec.get()))) {
            report = new CommandPipelineVerifier(BlobSink.NONE, LspService.UNAVAILABLE)
                .verify(box.target(), subject, spec.get());
        } catch (com.swarmcoder.sandbox.DockerSandboxManager.SandboxException e) {
            // No container, and this PC is not an alternative: the story is not delivered.
            return "it could not be checked. " + e.getMessage();
        }
        // No claimed-check list here on purpose. Whether this story proved its own requirement-checks
        // was decided at the end of its run, against its own tree, and is not re-litigated. The
        // question at this merge is narrower and different: does the project still build and still
        // pass its tests now that this work sits beside everything else.
        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        if (verdict.survived()) {
            return null;
        }
        return verdict.reason()
            + "\n\ncompiles=" + report.compiles()
            + (report.acceptance() != null ? ", acceptance " + report.acceptance().passed()
                + " passed / " + report.acceptance().failed() + " failed" : "")
            + (report.existing() != null ? ", existing " + report.existing().failed() + " failed" : "")
            + "\n" + (report.logTail() == null ? "" : report.logTail());
    }

    /**
     * A task of this story, for the verifier's per-task plumbing (its LSP precheck and its log
     * naming). Any of them will do: the tree being verified is the whole project, not one task's
     * slice. Null when the story has none, which the verifier tolerates.
     */
    private Task anyTaskOf(Story story) {
        if (story == null) {
            return null;
        }
        List<Task> tasks = store.storyTasks(story.id());
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(tasks.size() - 1);
        }
        for (UUID runId : story.runIds()) {
            com.swarmcoder.domain.Run run = store.root().runs.get(runId);
            TaskGraph graph = run == null || run.taskGraphId() == null
                ? null : store.root().taskGraphs.get(run.taskGraphId());
            if (graph != null && graph.tasks() != null && !graph.tasks().isEmpty()) {
                return graph.tasks().get(graph.tasks().size() - 1);
            }
        }
        return null;
    }
}
