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

import com.swarmcoder.domain.SwarmSizing;
import com.swarmcoder.domain.TurnAllowance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheap, model-free proof of {@link HarnessRunBudget} — the seam that closes harness run 12 (two
 * workers dispatched with the product's 120-turn allowance, waited on for only the harness's flat
 * 25-minute default, both still visibly working when the deadline fired at 1,501 seconds) and
 * harness run 22 (a repair round of four workers still visibly running when the build-round-only
 * formula's 90-minute budget expired).
 *
 * <p>No live model, no worktree, no store — this only proves the arithmetic and the sentence link
 * 1's ledger line states, exactly the way {@code HarnessTurnAllowanceTest} proves the turn
 * allowance beside it.
 */
class HarnessRunBudgetTest {

    private static final TurnAllowance BUILT_IN =
        new TurnAllowance(TurnAllowance.BUILT_IN_MAX_TOOL_TURNS, SwarmSizing.Layer.BUILT_IN, "test");

    @BeforeEach
    @AfterEach
    void noOverrideInForce() {
        System.clearProperty("swarmcoder.e2e.minutes");
        System.clearProperty("swarmcoder.e2e.waves");
        System.clearProperty("swarmcoder.e2e.maxMinutes");
    }

    @Test
    void oneWaveTwoWorkersOneHundredAndTwentyTurnsCoversBothBuildAndRepair() {
        // The harness's own defaults: 1 wave, 2 workers, the product's 120-turn allowance.
        // Build round: 120 turns x 90s = 10,800s = 180 minutes of worker time.
        // Repair round: the same 120 turns x 90s = another 180 minutes, because run 22's repair
        // round is its own independent worker allowance, not a fraction of the build round's.
        // 180 + 180 = 360 minutes of work, +10 minutes overhead = 370 minutes. A high cap is
        // passed here so this test proves the uncapped arithmetic, not the cap.
        HarnessRunBudget.Budget budget = HarnessRunBudget.compute(1, 2, BUILT_IN, 1000);

        assertThat(budget.minutes())
            .describedAs("180 min build + 180 min repair + 10 min overhead")
            .isEqualTo(370);
        assertThat(budget.sentence())
            .contains("370 minutes")
            .contains("120 turns")
            .contains("1 wave(s)")
            .contains("build round 2 worker(s)")
            .contains("repair round 4 worker(s)");
    }

    @Test
    void thisIsFarMoreRoomThanTheOldFlatTwentyFiveMinuteDefaultGave() {
        // Harness run 12 died at 1,501 seconds (25 minutes) with both workers still alive and
        // writing. The computed budget for the same scenario must clear that mark by a wide
        // margin, or the fix does not fix anything.
        HarnessRunBudget.Budget budget = HarnessRunBudget.compute(1, 2, BUILT_IN,
            HarnessRunBudget.DEFAULT_MAX_MINUTES);

        assertThat(budget.millis()).isGreaterThan(25 * 60_000L);
    }

    @Test
    void moreWavesCostMoreButStayUnderTheCap() {
        HarnessRunBudget.Budget oneWave = HarnessRunBudget.compute(1, 2, BUILT_IN, 5000);
        HarnessRunBudget.Budget twoWaves = HarnessRunBudget.compute(2, 2, BUILT_IN, 5000);

        assertThat(twoWaves.millis()).isGreaterThan(oneWave.millis());
    }

    @Test
    void theCapWins() {
        HarnessRunBudget.Budget budget = HarnessRunBudget.compute(4, 4, BUILT_IN, 60);

        assertThat(budget.minutes()).isEqualTo(60);
        assertThat(budget.sentence()).contains("capped at 60 minutes");
    }

    @Test
    void threeWavesTwoWorkersOneHundredTwentyTurnsLandsOnTheNewDefaultCap() {
        // Harness run 22's own shape: three waves, two workers per task, the product's 120-turn
        // allowance. Build + repair work alone is 3 x (180 + 180) = 1,080 minutes, plus 10
        // minutes overhead — 1,090 minutes, which the new 180-minute default cap holds down.
        HarnessRunBudget.Budget budget = HarnessRunBudget.compute(3, 2, BUILT_IN,
            HarnessRunBudget.DEFAULT_MAX_MINUTES);

        assertThat(budget.minutes()).isEqualTo(180);
        assertThat(budget.sentence())
            .contains("180 minutes")
            .contains("capped at 180 minutes");
    }

    @Test
    void aStatedMinutesOverrideWinsOutright() {
        System.setProperty("swarmcoder.e2e.minutes", "17");
        try {
            HarnessRunBudget.Budget budget = HarnessRunBudget.resolve(2, BUILT_IN);
            assertThat(budget.minutes()).isEqualTo(17);
            assertThat(budget.sentence()).contains("17 minutes");
        } finally {
            System.clearProperty("swarmcoder.e2e.minutes");
        }
    }

    @Test
    void withNoOverridesResolveMatchesTheDefaultFormula() {
        // 1 wave, 2 workers: 370 uncapped minutes, held down by the new 180-minute default cap.
        HarnessRunBudget.Budget resolved = HarnessRunBudget.resolve(2, BUILT_IN);

        assertThat(resolved.minutes()).isEqualTo(180);
    }

    @Test
    void aWavesOverrideIsHonoured() {
        System.setProperty("swarmcoder.e2e.waves", "2");
        System.setProperty("swarmcoder.e2e.maxMinutes", "5000");
        try {
            HarnessRunBudget.Budget resolved = HarnessRunBudget.resolve(2, BUILT_IN);
            // 2 waves x (180 min build + 180 min repair) + 10 min overhead = 730 minutes.
            assertThat(resolved.minutes()).isEqualTo(730);
        } finally {
            System.clearProperty("swarmcoder.e2e.waves");
            System.clearProperty("swarmcoder.e2e.maxMinutes");
        }
    }
}
