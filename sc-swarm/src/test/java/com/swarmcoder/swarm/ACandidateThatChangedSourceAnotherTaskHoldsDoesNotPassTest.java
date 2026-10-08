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
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: a repair worker implemented the new methods in a file of the next wave's task and
 * created its own copies of classes other tasks were writing; it was selected, and the delivery
 * holds dead duplicates. A source file ANOTHER TASK of the plan holds still fails the candidate.
 *
 * <p>Since section 73 (owner's decision, 2026-10-08) that is the whole of the rule: a source file
 * outside the write set that no other task holds is the task's to take, and the candidate
 * passes. Until then every source file outside the write set failed it, and run 90 lost a whole
 * build to a write set the planner had guessed one file short.
 */
class ACandidateThatChangedSourceAnotherTaskHoldsDoesNotPassTest {

    private static final String API = "shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "server/src/main/java/org/example/shop/server/OrderServiceImpl.java";
    private static final String COPY =
        "server/src/main/java/org/example/shop/server/CancelCommand.java";
    private static final String HELPER =
        "server/src/main/java/org/example/shop/server/OrderIds.java";

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet),
            Set.of(), List.of(), null, null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static CandidateSolution candidate(Task task, String diff, String... outside) {
        CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), task.id(), 110,
            "b", null, diff, null, null, null, CandidateState.SURVIVED, null);
        candidate.setOutOfWriteSetPaths(List.of(outside));
        return candidate;
    }

    private static String diffOf(String... paths) {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("--- a/").append(path).append("\n+++ b/").append(path).append("\n+x\n");
        }
        return sb.toString();
    }

    /** Run 74's plan: the interface first; its implementation and a command, side by side, after. */
    private final Task extend = task("Extend OrderService", API);
    private final Task implement = task("Implement cancel", IMPL);
    private final Task command = task("Add the cancel command", COPY);
    private final ReservationBook run74 =
        ReservationBook.of(List.of(List.of(extend), List.of(implement, command)));

    @Test
    void sourceATaskNotYetRunHoldsIsAnObjectionThatNamesTheFilesAndTheirTasks() {
        String objection = SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, IMPL, COPY), IMPL, COPY), run74);

        assertThat(objection).contains("2 source files that another task of this plan holds")
            .contains(IMPL + " (held by 'Implement cancel', which the plan runs later)")
            .contains(COPY + " (held by 'Add the cancel command', which the plan runs later)")
            .contains("never selected");
    }

    @Test
    void sourceATaskBuiltAtTheSameTimeHoldsIsAnObjectionToo() {
        String objection = SourceOutsideWriteSet.objection(implement,
            candidate(implement, diffOf(IMPL, COPY), COPY), run74);

        assertThat(objection).contains("a source file that another task of this plan holds")
            .contains(COPY + " (held by 'Add the cancel command', built at the same time)");
    }

    @Test
    void aSourceFileNoOtherTaskHoldsIsTheTasksToTake() {
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, HELPER), HELPER), run74))
            .as("run 90's case: the write set was a file short and nobody else wanted the file")
            .isNull();
        assertThat(SourceOutsideWriteSet.paths(extend,
            candidate(extend, diffOf(API, HELPER), HELPER)))
            .as("it is still listed, for the judge and for the record on the task")
            .containsExactly(HELPER);
    }

    @Test
    void theSelectedCandidatesExtraFileIsRecordedOnTheTaskAndJoinsItsReservation() {
        Task task = task("Extend OrderService", API);
        CandidateSolution selected = candidate(task, diffOf(API, HELPER, "notes.txt"),
            HELPER, "notes.txt");

        assertThat(SourceOutsideWriteSet.growReservation(task, selected)).containsExactly(HELPER);

        assertThat(task.takenBeyondPlan()).as("shown in the run report").containsExactly(HELPER);
        assertThat(task.writeSet()).containsExactlyInAnyOrder(API, HELPER);
        assertThat(SourceOutsideWriteSet.growReservation(task, selected))
            .as("asked again, there is nothing more to take").isEmpty();
        assertThat(task.takenBeyondPlan()).containsExactly(HELPER);

        Task inside = task("Stays inside", API);
        assertThat(SourceOutsideWriteSet.growReservation(inside, candidate(inside, diffOf(API))))
            .isEmpty();
        assertThat(inside.takenBeyondPlan()).isEmpty();
        assertThat(inside.writeSet()).containsExactly(API);
    }

    @Test
    void theFileOfATaskThatAlreadyRanIsNotHeld() {
        assertThat(SourceOutsideWriteSet.objection(implement,
            candidate(implement, diffOf(IMPL, API), API), run74))
            .as("the earlier task's work is merged; this task builds on it").isNull();
    }

    @Test
    void aPlanThatCannotBeReadHoldsNothing() {
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, IMPL), IMPL), null)).isNull();
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, IMPL), IMPL), ReservationBook.of(null))).isNull();
    }

    @Test
    void aWriteSetWidenedByTheProductCoversTheFile() {
        Task task = task("Extend OrderService", API);
        task.setWriteSet(Set.of(API, IMPL));
        task.setSiblingRepairPaths(List.of(IMPL));
        ReservationBook book =
            ReservationBook.of(List.of(List.of(task), List.of(implement, command)));

        assertThat(SourceOutsideWriteSet.objection(task,
            candidate(task, diffOf(API, IMPL), IMPL), book)).isNull();
    }

    @Test
    void whatIsNotASourceChangeIsLeftAsItWas() {
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, "notes.txt"), "notes.txt"), run74))
            .as("a non-source stray is dropped at integration, as before").isNull();
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API, "server/pom.xml"), "server/pom.xml"), run74))
            .as("a build file is left to the judge").isNull();
        assertThat(SourceOutsideWriteSet.objection(extend,
            candidate(extend, diffOf(API), IMPL), run74))
            .as("written and put back: not part of the change").isNull();
        assertThat(SourceOutsideWriteSet.objection(extend, candidate(extend, diffOf(API)), run74))
            .isNull();
        Task open = task("Anything");
        assertThat(SourceOutsideWriteSet.objection(open, candidate(open, diffOf(IMPL), IMPL),
            ReservationBook.of(List.of(List.of(open), List.of(implement)))))
            .as("an empty write set is unrestricted").isNull();
    }
}
