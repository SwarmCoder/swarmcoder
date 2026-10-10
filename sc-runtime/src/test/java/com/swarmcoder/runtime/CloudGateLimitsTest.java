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
package com.swarmcoder.runtime;

import com.swarmcoder.runtime.CloudGate.Breach;
import com.swarmcoder.runtime.CloudGate.BudgetExhaustedException;
import com.swarmcoder.runtime.CloudGate.Cap;
import com.swarmcoder.runtime.CloudGate.Direction;
import com.swarmcoder.runtime.CloudGate.Level;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.CloudGate.Where;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three limits (run, story, project), input and output counted apart, and extending one. */
class CloudGateLimitsTest {

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();

    private Where run(UUID runId) {
        return new Where(project, story, runId);
    }

    @Test
    void aRunsCounterStartsAtZeroWhateverEarlierRunsSpent() {
        CloudGate gate = new CloudGate(new Limits(Cap.total(1000), Cap.NONE, Cap.NONE), b -> { });
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var in = CloudGate.enter(run(first))) {
            gate.charge(900);
        }
        try (var in = CloudGate.enter(run(second))) {
            gate.charge(900);   // would be 1,800 on the old one-counter-per-process gate
        }
        assertThat(gate.spendOfRun(first).total()).isEqualTo(900);
        assertThat(gate.spendOfRun(second).total()).isEqualTo(900);
    }

    @Test
    void inputAndOutputAreLimitedApart() {
        List<Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(new Limits(new Cap(0, 0, 100), Cap.NONE, Cap.NONE), raised::add);
        UUID runId = UUID.randomUUID();
        try (var in = CloudGate.enter(run(runId))) {
            gate.charge(10_000);          // any amount of input is fine: only output is limited
            gate.chargeOutput(100);       // exactly at the limit
            assertThatThrownBy(() -> gate.chargeOutput(1)).isInstanceOf(BudgetExhaustedException.class);
        }
        assertThat(raised).hasSize(1);
        Breach breach = raised.get(0);
        assertThat(breach.level()).isEqualTo(Level.RUN);
        assertThat(breach.direction()).isEqualTo(Direction.OUTPUT);
        assertThat(breach.limit()).isEqualTo(100);
        assertThat(breach.used().input()).isEqualTo(10_000);
        assertThat(breach.used().output()).isEqualTo(101);
        assertThat(breach.runId()).isEqualTo(runId);
        assertThat(breach.storyId()).isEqualTo(story);
        assertThat(breach.projectId()).isEqualTo(project);
    }

    @Test
    void aStoryLimitSumsItsRunsAndAProjectLimitSumsItsStories() {
        List<Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(
            new Limits(Cap.NONE, Cap.total(500), Cap.total(700)), raised::add);
        try (var in = CloudGate.enter(run(UUID.randomUUID()))) {
            gate.charge(300);
        }
        try (var in = CloudGate.enter(run(UUID.randomUUID()))) {
            assertThatThrownBy(() -> gate.charge(300)).isInstanceOf(BudgetExhaustedException.class);
        }
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).level()).isEqualTo(Level.STORY);

        UUID otherStory = UUID.randomUUID();
        try (var in = CloudGate.enter(new Where(project, otherStory, UUID.randomUUID()))) {
            gate.charge(100);    // the project now holds 700, which is at its limit, not over it
            assertThatThrownBy(() -> gate.charge(1)).isInstanceOf(BudgetExhaustedException.class);
        }
        assertThat(raised).hasSize(2);
        assertThat(raised.get(1).level()).isEqualTo(Level.PROJECT);
        assertThat(gate.spendOfProject(project).total()).isEqualTo(300 + 300 + 101);
    }

    @Test
    void extendingRaisesTheLimitThatParkedTheRunByTheSameAmountAgain() {
        List<Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(new Limits(Cap.total(100), Cap.NONE, Cap.NONE), raised::add);
        UUID runId = UUID.randomUUID();
        try (var in = CloudGate.enter(run(runId))) {
            assertThatThrownBy(() -> gate.charge(150)).isInstanceOf(BudgetExhaustedException.class);
            assertThat(gate.parkingBreach(run(runId))).isPresent();

            var extension = gate.extendForRun(runId).orElseThrow();
            assertThat(extension.level()).isEqualTo(Level.RUN);
            assertThat(extension.limitNow().total()).isEqualTo(200);
            assertThat(gate.standing(run(runId))).as("150 is under 200 now").isEmpty();

            gate.charge(50);             // 200, at the limit
            assertThatThrownBy(() -> gate.charge(1)).isInstanceOf(BudgetExhaustedException.class);
        }
        assertThat(raised).as("one question per limit passed, the second after the extension")
            .hasSize(2);
        assertThat(raised.get(1).limit()).isEqualTo(200);
        assertThat(gate.extendForRun(UUID.randomUUID())).as("nothing on record for another run")
            .isEmpty();
    }

    @Test
    void aRunDrivenAgainWhileItsStoryIsOverGetsAQuestionOfItsOwn() {
        List<Breach> raised = new ArrayList<>();
        CloudGate gate = new CloudGate(new Limits(Cap.NONE, Cap.total(100), Cap.NONE), raised::add);
        UUID tripped = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        try (var in = CloudGate.enter(run(tripped))) {
            assertThatThrownBy(() -> gate.charge(150)).isInstanceOf(BudgetExhaustedException.class);
        }
        assertThat(gate.parkingBreach(run(bystander))).isPresent();
        assertThat(raised).extracting(Breach::runId).containsExactly(tripped, bystander);
    }

    @Test
    void workOutsideAnyRunIsCountedApartAndLimitedByNothing() {
        CloudGate gate = new CloudGate(new Limits(Cap.total(10), Cap.total(10), Cap.total(10)), b -> { });
        gate.charge(1_000_000);
        gate.chargeOutput(1_000_000);
        assertThat(gate.outsideRuns().total()).isEqualTo(2_000_000);
        assertThat(gate.spendOfRun(UUID.randomUUID()).total()).isZero();
    }

    @Test
    void aChildThreadChargesTheRunThatStartedIt() throws Exception {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        UUID runId = UUID.randomUUID();
        try (var in = CloudGate.enter(run(runId))) {
            Thread.ofVirtual().start(() -> gate.chargeOutput(7)).join();
        }
        assertThat(gate.spendOfRun(runId).output()).isEqualTo(7);
    }

    @Test
    void theReportLineSaysWhatTheRunSpentAndWhichLimitsApply() {
        CloudGate gate = new CloudGate(new Limits(Cap.total(1000), new Cap(0, 0, 400), Cap.NONE), b -> { });
        UUID runId = UUID.randomUUID();
        try (var in = CloudGate.enter(run(runId))) {
            gate.charge(30);
            gate.chargeOutput(12);
        }
        assertThat(gate.spendLine(run(runId)))
            .isEqualTo("Cloud tokens this run: input 30 / output 12; limits in force: "
                + "run (total 1000); story (output 400)");
    }
}
