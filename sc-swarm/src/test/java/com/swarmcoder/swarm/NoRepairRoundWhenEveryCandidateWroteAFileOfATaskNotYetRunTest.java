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
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 90 (DEVELOPER_CORRECTIONS section 66): the plan ran "use the new type" before "create
 * the new type". Both first candidates compiled and failed for having created the type outside
 * their write set; a repair round of four workers was then spent on the same write set and the
 * same order. Such a task gets no repair round: it stops, and the message names the pair.
 */
class NoRepairRoundWhenEveryCandidateWroteAFileOfATaskNotYetRunTest {

    private static final String UTC = "shared/src/main/java/com/log/UtcDateTime.java";
    private static final String ADD = "client/src/main/java/com/log/client/AddScreen.java";
    private static final String TEXTS = "client/src/main/java/com/log/client/i18n/AddTexts.java";

    private final Task screens = task("Use UtcDateTime in add/edit contact screens", Set.of(), ADD);
    private final Task create = task("Add shared UtcDateTime utility", Set.of(), UTC);
    private final Task texts = task("Change UTC label constants", Set.of(), TEXTS);
    /** As run 90 ran them: the screens first, the task that creates the type after. */
    private final List<List<Task>> run90 = List.of(List.of(screens), List.of(create, texts));

    @Test
    void run90sFirstRoundIsFoundAndTheMessageNamesThePair() {
        FileOfATaskNotYetRun.Finding finding = FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), wrote(screens, 1, UTC), noVerdict(screens, 2)), run90);

        assertThat(finding).isNotNull();
        assertThat(finding.candidates()).isEqualTo(2);
        assertThat(finding.files()).containsEntry(UTC, "Add shared UtcDateTime utility");
        assertThat(FileOfATaskNotYetRun.planBlame(screens, finding))
            .startsWith("Task BLOCKED by its plan, not by its candidates")
            .contains(UTC + " - in the write set of 'Add shared UtcDateTime utility'")
            .contains("none was started")
            .contains("'Use UtcDateTime in add/edit contact screens' depends on "
                + "'Add shared UtcDateTime utility'");
    }

    @Test
    void aFileOfATaskRunningBesideItIsFoundToo() {
        assertThat(FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), wrote(screens, 1, UTC)),
            List.of(List.of(screens, create, texts)))).isNotNull();
    }

    @Test
    void anOrdinaryFailureIsLeftToTheRepairRound() {
        assertThat(FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), failedOtherwise(screens, 1)), run90))
            .as("one candidate failed for another reason: not every candidate").isNull();
        assertThat(FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), wrote(screens, 1, TEXTS)), run90))
            .as("two candidates, two different files: no file every candidate needed").isNull();
        assertThat(FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), survived(screens, 1)), run90))
            .as("one candidate did it inside the write set").isNull();
        assertThat(FileOfATaskNotYetRun.find(screens, List.of(wrote(screens, 0,
                "client/src/main/java/com/log/client/Helper.java"), wrote(screens, 1,
                "client/src/main/java/com/log/client/Helper.java")), run90))
            .as("a file no task of the plan owns is not a fault in the plan's order").isNull();
        assertThat(FileOfATaskNotYetRun.find(create,
            List.of(wrote(create, 0, ADD), wrote(create, 1, ADD)), run90))
            .as("the owner already ran: its file is in the tree, which is another fault").isNull();
        assertThat(FileOfATaskNotYetRun.find(screens,
            List.of(wrote(screens, 0, UTC), wrote(screens, 1, UTC)), null))
            .as("a plan that cannot be read says nothing").isNull();
    }

    @Test
    void oneCandidateAloneCountsOnlyWhenTheTaskItselfNamesTheFileInItsReadSet() {
        assertThat(FileOfATaskNotYetRun.find(screens, List.of(wrote(screens, 0, UTC)), run90))
            .as("one worker writing into another task's file is that worker's mistake")
            .isNull();

        Task told = task(screens.title(), Set.of(UTC), ADD);
        assertThat(FileOfATaskNotYetRun.find(told, List.of(wrote(told, 0, UTC)),
            List.of(List.of(told), List.of(create, texts)))).isNotNull();
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static Task task(String title, Set<String> readSet, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), readSet,
            List.of(), "server/src/test/java/swarm/accept", null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static VerificationReport compiled() {
        return new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
            Duration.ZERO, "", null);
    }

    /** A candidate that compiled and failed for a source file outside its write set. */
    private static CandidateSolution wrote(Task task, int worker, String outside) {
        CandidateSolution candidate = new CandidateSolution(UUID.randomUUID(), task.id(), worker,
            "b" + worker, null, "diff --git a/" + outside + " b/" + outside + "\n+x\n", compiled(),
            null, null, CandidateState.FAILED, null);
        candidate.setOutOfWriteSetPaths(List.of(outside));
        return candidate;
    }

    private static CandidateSolution failedOtherwise(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            "diff", compiled(), null, null, CandidateState.FAILED, null);
    }

    private static CandidateSolution survived(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            "diff", compiled(), null, null, CandidateState.SURVIVED, null);
    }

    private static CandidateSolution noVerdict(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null, "",
            null, null, null, CandidateState.KILLED, null);
    }
}
