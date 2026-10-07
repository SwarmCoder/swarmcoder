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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: both first candidates failed on a compile error in a file outside the task's write
 * set, and two repair rounds of four workers (74 and 66 minutes) were spent on it. Such a task
 * gets no repair round: its write set is widened once, or the plan is blamed.
 */
class NoRepairRoundWhenTheBrokenFileIsOutsideTheWriteSetTest {

    private static final String API = "shared/src/main/java/org/example/shop/OrderService.java";
    private static final String IMPL =
        "server/src/main/java/org/example/shop/server/OrderServiceImpl.java";

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), Set.of(),
            List.of(), "server/src/test/java/swarm/accept", null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static CandidateSolution failedOn(Task task, int worker, CompileFailureCause cause,
                                              String file, boolean testFile) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false, null,
            null, null, null, Duration.ZERO, "", null);
        report.setCompileFailure(new CompileFailure(cause, file, 18,
            "OrderServiceImpl is not abstract and does not override abstract method cancel",
            List.of(file), testFile, false, false, "it changed OrderService"));
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            "diff", report, null, null, CandidateState.FAILED, null);
    }

    private static CandidateSolution noVerdict(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null, "",
            null, null, null, CandidateState.KILLED, null);
    }

    @Test
    void everyCandidateFailingInTheSameFileOutsideTheWriteSetIsFound() {
        Task extend = task("Extend OrderService", API);
        RepairCannotHelp.Finding finding = RepairCannotHelp.find(extend, List.of(
            failedOn(extend, 0, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false),
            failedOn(extend, 1, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false),
            noVerdict(extend, 2)), List.of());

        assertThat(finding).isNotNull();
        assertThat(finding.file()).isEqualTo(IMPL);
        assertThat(finding.files()).containsExactly(IMPL);
        assertThat(finding.candidates()).isEqualTo(2);
        assertThat(finding.instructions()).contains("ALSO CHANGES " + IMPL)
            .contains("does not override abstract method cancel");
    }

    @Test
    void aFileInsideTheWriteSetOrDifferentFilesOrATestOrAProtectedPathIsAnOrdinaryFailure() {
        Task extend = task("Extend OrderService", API);
        Task wide = task("Extend OrderService", API, "server/src/main/java");

        assertThat(RepairCannotHelp.find(wide, List.of(
            failedOn(wide, 0, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false)), List.of()))
            .as("the task may write the file: a repair round can help").isNull();
        assertThat(RepairCannotHelp.find(extend, List.of(
            failedOn(extend, 0, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false),
            failedOn(extend, 1, CompileFailureCause.CANDIDATE, API, false)), List.of()))
            .as("one candidate failed in its own file").isNull();
        assertThat(RepairCannotHelp.find(extend, List.of(
            failedOn(extend, 0, CompileFailureCause.PRE_EXISTING,
                "server/src/test/java/swarm/accept/CancelTest.java", true)), List.of()))
            .as("an acceptance test is the test author's").isNull();
        assertThat(RepairCannotHelp.find(extend, List.of(
            failedOn(extend, 0, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false)),
            List.of("server"))).as("a protected module is never handed to a worker").isNull();
        assertThat(RepairCannotHelp.find(extend, List.of(noVerdict(extend, 0)), List.of()))
            .as("no verdict is no evidence").isNull();
    }

    @Test
    void aFileOfALaterTaskOrOfNoTaskMayBeAddedToTheWriteSet() {
        Task extend = task("Extend OrderService", API);
        Task implement = task("Implement cancel", IMPL);
        TaskGraph plan = new TaskGraph(UUID.randomUUID(), 1, null, List.of(extend, implement),
            List.of(new TaskEdge(extend.id(), implement.id())));

        assertThat(RepairCannotHelp.whyNotWidened(extend,
            SwarmEngineImpl.topologicalWaves(plan), List.of(IMPL))).isNull();
        assertThat(RepairCannotHelp.whyNotWidened(extend,
            SwarmEngineImpl.topologicalWaves(plan), List.of("server/src/main/java/Other.java")))
            .isNull();
    }

    @Test
    void aFileOfATaskBesideItOrBeforeItOrASecondWideningBlamesThePlan() {
        Task extend = task("Extend OrderService", API);
        Task beside = task("Implement cancel", IMPL);
        TaskGraph sameWave = new TaskGraph(UUID.randomUUID(), 1, null, List.of(extend, beside),
            List.of());
        assertThat(RepairCannotHelp.whyNotWidened(extend,
            SwarmEngineImpl.topologicalWaves(sameWave), List.of(IMPL)))
            .contains("runs beside this task");

        TaskGraph before = new TaskGraph(UUID.randomUUID(), 1, null, List.of(beside, extend),
            List.of(new TaskEdge(beside.id(), extend.id())));
        assertThat(RepairCannotHelp.whyNotWidened(extend,
            SwarmEngineImpl.topologicalWaves(before), List.of(IMPL)))
            .contains("earlier task 'Implement cancel'");

        extend.setSiblingRepairPaths(List.of("x/Y.java"));
        String why = RepairCannotHelp.whyNotWidened(extend,
            SwarmEngineImpl.topologicalWaves(sameWave), List.of(IMPL));
        assertThat(why).contains("already widened once");

        RepairCannotHelp.Finding finding = RepairCannotHelp.find(extend, List.of(
            failedOn(extend, 0, CompileFailureCause.CAUSED_BY_CHANGE, IMPL, false)), List.of());
        assertThat(RepairCannotHelp.planBlame(extend, finding, why))
            .contains("BLOCKED by its plan, not by its candidates")
            .contains(IMPL)
            .contains("cannot succeed, so none was started")
            .contains("Plan it again with " + IMPL);
    }
}
