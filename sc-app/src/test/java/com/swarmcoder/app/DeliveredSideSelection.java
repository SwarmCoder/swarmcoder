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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TestResults;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which candidate's verification report is actually the story's delivered-side acceptance
 * measurement — as opposed to just "a winner", which is the choice link 16 (L_PROVES in
 * {@link EndToEndLoopTest}) got wrong in harness run 41 (2026-09-26).
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Run 41 planned four tasks for one story. Only the LAST one ('Create persistent
 * BookshelfServiceImpl') claimed the story's acceptance criteria — the other three were enablers
 * with an empty {@link Task#criterionIds()}, so the test author never placed the story's
 * acceptance test into their candidates' worktrees, and their winners' verification reports
 * correctly say "0 acceptance tests executed": they were never handed the file. Link 16 picked a
 * report by scanning {@code selected} candidates in worker-index order and stopping at the first
 * non-null one it found — an early task's winner, not the task that actually proves the story —
 * and reported "0 executed, NOT GREEN" for a story that had, in fact, been delivered: the fourth
 * task's winner had run the real acceptance test, 1 executed, 0 failed.
 *
 * <h2>The fix</h2>
 *
 * <p>The delivered side must be measured from the SELECTED candidate(s) of the task(s) that claim
 * the story's acceptance criteria — {@link #claimingTasks} finds them by a non-empty
 * {@code criterionIds()} on the story's own tasks; {@link #winnersOfClaimingTasks} matches them to
 * a selected candidate by {@link CandidateSolution#taskId()}; {@link #combinedSide} folds one or
 * several such reports (several, if more than one task claims part of the story) into the single
 * {@link DeliveredCodeIsWhatPassedCheck.Side} the comparison already knows how to judge. When no
 * claiming task can be matched to a candidate carrying an acceptance report at all — a graph with
 * no claiming task, or a claiming task whose winner was never verified — {@link #combinedSide}
 * returns empty, which tells the caller to stop reusing a report that does not exist and instead
 * run the acceptance suite on the delivered commit directly (see
 * {@code EndToEndLoopTest#provesTheDeliveredCode}).
 */
final class DeliveredSideSelection {

    private DeliveredSideSelection() {}

    /**
     * The tasks, out of a run's whole graph, that claim one of the story's acceptance criteria. An
     * ENABLER task legitimately claims none and is excluded — it never had the story's acceptance
     * test placed in front of it, so its winner's report has nothing to say about the story.
     *
     * @param storyId the run's own story; a task whose {@code storyId} does not match is not this
     *                 story's task even if the same graph somehow carried one (defensive — every
     *                 task in a run's graph belongs to that run's one story today)
     */
    static List<Task> claimingTasks(TaskGraph graph, UUID storyId) {
        if (graph == null || graph.tasks() == null) {
            return List.of();
        }
        return graph.tasks().stream()
            .filter(t -> storyId == null || storyId.equals(t.storyId()))
            .filter(t -> !t.criterionIds().isEmpty())
            .toList();
    }

    /**
     * The SELECTED candidate for each claiming task, matched by {@link CandidateSolution#taskId()}.
     * Shorter than {@code claimingTasks} when a claiming task has no selected candidate yet (the
     * run parked before selection reached it) — that task is silently absent, and it is
     * {@link #combinedSide} that decides whether the ones found are enough to answer with.
     */
    static List<CandidateSolution> winnersOfClaimingTasks(List<Task> claimingTasks,
                                                          List<CandidateSolution> candidates) {
        List<CandidateSolution> winners = new ArrayList<>();
        for (Task task : claimingTasks) {
            for (CandidateSolution candidate : candidates) {
                if (task.id().equals(candidate.taskId())) {
                    winners.add(candidate);
                    break;
                }
            }
        }
        return winners;
    }

    /**
     * Folds every claiming task's winner that carries an acceptance report into one
     * {@link DeliveredCodeIsWhatPassedCheck.Side}: counts summed, red if any one of them is red
     * (matching {@link DeliveredCodeIsWhatPassedCheck.Side#green()}'s own all-or-nothing rule for a
     * single run) — a story is not delivered if ANY of the tasks that claim its criteria failed
     * its share of them, even if another claiming task's share is clean.
     *
     * @return empty when not one claiming task has a matched candidate carrying an acceptance
     *     report — nothing here to reuse, so the caller must measure the delivered commit itself
     */
    static Optional<DeliveredCodeIsWhatPassedCheck.Side> combinedSide(List<CandidateSolution> winners) {
        List<CandidateSolution> withReports = winners.stream()
            .filter(c -> c.verification() != null && c.verification().acceptance() != null)
            .toList();
        if (withReports.isEmpty()) {
            return Optional.empty();
        }
        int executed = 0;
        int failing = 0;
        List<String> notes = new ArrayList<>();
        for (CandidateSolution candidate : withReports) {
            TestResults acceptance = candidate.verification().acceptance();
            int failed = acceptance.failed() + acceptance.errored();
            executed += acceptance.executed();
            failing += failed;
            notes.add("worker " + candidate.workerIndex() + "'s verification: stage "
                + acceptance.stageOutcome() + ", " + acceptance.passed() + " passed / " + failed
                + " failed or errored");
        }
        boolean red = failing > 0 || executed == 0;
        return Optional.of(new DeliveredCodeIsWhatPassedCheck.Side(executed, failing, red,
            String.join("; ", notes)));
    }
}
