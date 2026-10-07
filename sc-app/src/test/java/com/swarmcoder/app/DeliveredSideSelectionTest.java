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
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 41 (2026-09-26): a graph of four tasks for one story, where only the LAST task
 * ('Create persistent BookshelfServiceImpl') claimed the story's acceptance criteria. The other
 * three were enablers — {@link Task#criterionIds()} empty — so the test author never placed the
 * story's acceptance test into their candidates' worktrees, and their winners correctly reported
 * "0 acceptance tests executed". Link 16 (L_PROVES in {@code EndToEndLoopTest}) scanned all four
 * winners in worker-index order and stopped at the first non-null verification report, which
 * belonged to an enabler task — reporting "NOT GREEN" for a story that was, in fact, delivered:
 * the fourth task's winner had run the real acceptance test, 1 executed, 0 failed.
 *
 * <p>These tests reproduce that exact shape and the two edge cases the fix has to get right: more
 * than one claiming task, and no claiming task's winner carrying a report at all.
 */
class DeliveredSideSelectionTest {

    private final UUID storyId = UUID.randomUUID();

    @Test
    void onlyTheLastOfFourTasksClaimsTheStorysCriteria() {
        Task enabler1 = task("Create shared Book model", Set.of());
        Task enabler2 = task("Wire persistence config", Set.of());
        Task enabler3 = task("Create BookshelfService contract", Set.of());
        Task claims = task("Create persistent BookshelfServiceImpl", Set.of(UUID.randomUUID()));
        TaskGraph graph = graph(List.of(enabler1, enabler2, enabler3, claims));

        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);

        assertThat(claiming).containsExactly(claims);
    }

    @Test
    void theClaimingTasksWinnerIsTheDeliveredSideEvenWhenEarlierWinnersReportZero() {
        Task enabler = task("Create shared Book model", Set.of());
        Task claims = task("Create persistent BookshelfServiceImpl", Set.of(UUID.randomUUID()));
        TaskGraph graph = graph(List.of(enabler, claims));

        CandidateSolution enablerWinner = winner(enabler.id(), 0, report(0, 0, 0));
        CandidateSolution claimingWinner = winner(claims.id(), 1, report(1, 0, 0));
        List<CandidateSolution> selected = List.of(enablerWinner, claimingWinner);

        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);
        List<CandidateSolution> winners = DeliveredSideSelection.winnersOfClaimingTasks(claiming, selected);
        Optional<DeliveredCodeIsWhatPassedCheck.Side> side = DeliveredSideSelection.combinedSide(winners);

        assertThat(winners).containsExactly(claimingWinner);
        assertThat(side).isPresent();
        assertThat(side.get().executed()).isEqualTo(1);
        assertThat(side.get().failing()).isEqualTo(0);
        assertThat(side.get().green()).isTrue();
    }

    @Test
    void severalClaimingTasksAreCombinedAndAnyOneFailingMakesTheWholeSideRed() {
        Task claimsA = task("Add rating endpoint", Set.of(UUID.randomUUID()));
        Task claimsB = task("Add rating UI", Set.of(UUID.randomUUID()));
        TaskGraph graph = graph(List.of(claimsA, claimsB));

        CandidateSolution winnerA = winner(claimsA.id(), 0, report(1, 0, 0));
        CandidateSolution winnerB = winner(claimsB.id(), 1, report(1, 1, 0));
        List<CandidateSolution> selected = List.of(winnerA, winnerB);

        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);
        List<CandidateSolution> winners = DeliveredSideSelection.winnersOfClaimingTasks(claiming, selected);
        Optional<DeliveredCodeIsWhatPassedCheck.Side> side = DeliveredSideSelection.combinedSide(winners);

        assertThat(side).isPresent();
        assertThat(side.get().executed()).isEqualTo(3);
        assertThat(side.get().failing()).isEqualTo(1);
        assertThat(side.get().red()).isTrue();
        assertThat(side.get().green()).isFalse();
    }

    @Test
    void noClaimingTaskWithAMatchedReportIsAbsentRatherThanAFalseZero() {
        Task claims = task("Create persistent BookshelfServiceImpl", Set.of(UUID.randomUUID()));
        TaskGraph graph = graph(List.of(claims));

        // The claiming task's winner was never verified at all (verification == null) — this must
        // NOT be read as "0 executed, red"; it must tell the caller nothing was measured here.
        CandidateSolution unverifiedWinner = new CandidateSolution(UUID.randomUUID(), claims.id(), 0,
            "branch", null, "diff", null, null, null, CandidateState.SELECTED, null);

        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);
        List<CandidateSolution> winners =
            DeliveredSideSelection.winnersOfClaimingTasks(claiming, List.of(unverifiedWinner));
        Optional<DeliveredCodeIsWhatPassedCheck.Side> side = DeliveredSideSelection.combinedSide(winners);

        assertThat(side).isEmpty();
    }

    @Test
    void aGraphWithNoClaimingTaskAtAllIsAlsoAbsent() {
        Task enabler = task("Create shared Book model", Set.of());
        TaskGraph graph = graph(List.of(enabler));
        CandidateSolution enablerWinner = winner(enabler.id(), 0, report(0, 0, 0));

        List<Task> claiming = DeliveredSideSelection.claimingTasks(graph, storyId);
        List<CandidateSolution> winners =
            DeliveredSideSelection.winnersOfClaimingTasks(claiming, List.of(enablerWinner));
        Optional<DeliveredCodeIsWhatPassedCheck.Side> side = DeliveredSideSelection.combinedSide(winners);

        assertThat(claiming).isEmpty();
        assertThat(side).isEmpty();
    }

    private Task task(String title, Set<UUID> criterionIds) {
        Task t = new Task(UUID.randomUUID(), 1, title, "do " + title, Set.of("src/main/java"),
            Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        t.setCriterionIds(criterionIds);
        t.setStoryId(storyId);
        return t;
    }

    private static TaskGraph graph(List<Task> tasks) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, List.<TaskEdge>of());
    }

    private static CandidateSolution winner(UUID taskId, int workerIndex, VerificationReport report) {
        return new CandidateSolution(UUID.randomUUID(), taskId, workerIndex, "branch", null, "diff",
            report, null, null, CandidateState.SELECTED, null);
    }

    private static VerificationReport report(int passed, int failed, int errored) {
        TestResults acceptance = new TestResults(passed, failed, errored, 0, List.of());
        acceptance.setStageOutcome(TestStageOutcome.EXECUTED);
        return new VerificationReport(UUID.randomUUID(), true, true, acceptance, null, null, null,
            null, null, null);
    }
}
