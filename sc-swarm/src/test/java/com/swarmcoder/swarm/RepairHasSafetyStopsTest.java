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

import com.swarmcoder.domain.KillReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 74: two repair rounds ran 74 and 66 minutes, four workers each, every turn thousands
 * of tokens of reasoning and no file changed. Two safety stops, neither a tight budget.
 */
class RepairHasSafetyStopsTest {

    @AfterEach
    void clear() {
        System.clearProperty(SwarmEngineImpl.REPAIR_ROUND_MINUTES_PROPERTY);
        System.clearProperty(SwarmEngineImpl.REPAIR_ROUND_MILLIS_PROPERTY);
    }

    @Test
    void turnsThatMoveNothingStopTheWorkerOnlyAfterSeveralInARow() {
        EarlyKillEnforcer guard = new EarlyKillEnforcer();
        assertThat(EarlyKillEnforcer.IDLE_TURNS_BEFORE_KILL).isEqualTo(5);
        assertThat(guard.checkIdleTurns(0)).isNull();
        assertThat(guard.checkIdleTurns(4)).isNull();
        assertThat(guard.checkIdleTurns(5)).isEqualTo(KillReason.NO_PROGRESS);
    }

    @Test
    void aRepairRoundHasAGenerousCeilingThatCanBeChanged() {
        assertThat(SwarmEngineImpl.repairRoundCeilingMillis()).isEqualTo(30 * 60_000L);
        System.setProperty(SwarmEngineImpl.REPAIR_ROUND_MINUTES_PROPERTY, "90");
        assertThat(SwarmEngineImpl.repairRoundCeilingMillis()).isEqualTo(90 * 60_000L);
        System.setProperty(SwarmEngineImpl.REPAIR_ROUND_MINUTES_PROPERTY, "0");
        assertThat(SwarmEngineImpl.repairRoundCeilingMillis()).isEqualTo(30 * 60_000L);
    }

    @Test
    void aWorkerStoppedAtTheCeilingHasItsChangeVerified() {
        String diff = "diff --git a/x b/x\n+x\n";
        assertThat(StoppedWork.worthVerifying(KillReason.ROUND_TIME_UP, diff)).isTrue();
        assertThat(StoppedWork.worthVerifying(KillReason.ROUND_TIME_UP, "")).isFalse();
        assertThat(KillReason.ROUND_TIME_UP.sentence()).contains("whatever it had changed");
        assertThat(KillReason.ROUND_TIME_UP.ordinal())
            .as("appended last: the enum is stored by ordinal")
            .isEqualTo(KillReason.values().length - 1);
        assertThat(com.swarmcoder.inference.ContextDeath.of(KillReason.ROUND_TIME_UP, 9, 30, false))
            .as("it says nothing about room").isNull();
    }
}
