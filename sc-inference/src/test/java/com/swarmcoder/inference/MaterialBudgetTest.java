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
package com.swarmcoder.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one scaling rule every material cap uses (2026-09-25): today's constants exactly at the
 * room they were measured in and below it, five-and-an-eighth times them at the DeepSeek workers'
 * 262,144, and no further above that.
 */
class MaterialBudgetTest {

    @Test
    void theRoomTheConstantsWereMeasuredInGivesTheConstantsExactly() {
        MaterialBudget qwen = MaterialBudget.forWorkingContext(51_200);
        assertThat(qwen.atBaseline()).isTrue();
        for (int constant : new int[] {18_000, 14_000, 9_000, 6_000, 4_000, 3_500, 3_000, 900}) {
            assertThat(qwen.chars(constant)).isEqualTo(constant);
        }
    }

    @Test
    void aSmallerRoomKeepsTodaysFiguresRatherThanShrinkingThem() {
        // The generic shape the cloud roles run on. They run today with exactly these figures.
        MaterialBudget generic = MaterialBudget.of(ModelQuirks.DEFAULTS);
        assertThat(generic.chars(9_000)).isEqualTo(9_000);
        assertThat(MaterialBudget.forWorkingContext(16_384).chars(6_000)).isEqualTo(6_000);
    }

    @Test
    void anUnknownRoomIsTheBaseline() {
        assertThat(MaterialBudget.forWorkingContext(0)).isEqualTo(MaterialBudget.BASELINE);
        assertThat(MaterialBudget.forWorkingContext(-1)).isEqualTo(MaterialBudget.BASELINE);
        assertThat(MaterialBudget.of((ModelQuirks) null)).isEqualTo(MaterialBudget.BASELINE);
        assertThat(MaterialBudget.of((VllmClient) null)).isEqualTo(MaterialBudget.BASELINE);
    }

    @Test
    void theDeepSeekWorkersRoomGetsProportionallyMore() {
        MaterialBudget deepSeek = MaterialBudget.of(ModelShapes.get("deepseek-v4-flash-ds4"));
        assertThat(deepSeek.workingContextTokens()).isEqualTo(262_144);
        assertThat(deepSeek.chars(18_000)).isEqualTo(92_160);
        assertThat(deepSeek.chars(14_000)).isEqualTo(71_680);
        assertThat(deepSeek.chars(6_000)).isEqualTo(30_720);
        assertThat(deepSeek.chars(3_000)).isEqualTo(15_360);
        assertThat(deepSeek.chars(900)).isEqualTo(4_608);
        assertThat(deepSeek.atBaseline()).isFalse();
    }

    @Test
    void aRoomBetweenTheTwoIsLinear() {
        // 65,536 is 1.28 times the baseline.
        assertThat(MaterialBudget.forWorkingContext(65_536).chars(18_000)).isEqualTo(23_040);
    }

    @Test
    void aMillionTokenRoomIsHeldAtTheCeiling() {
        MaterialBudget served = MaterialBudget.forWorkingContext(1_048_576);
        assertThat(served.effectiveTokens()).isEqualTo(MaterialBudget.CEILING_TOKENS);
        assertThat(served.chars(18_000)).isEqualTo(92_160);
        assertThat(served.describe()).contains("ceiling");
    }

    @Test
    void theBriefStaysTheShareOfTheRoomItAlwaysWas() {
        // 18,000 characters at four to a token is 8.8% of 51,200; the same at the ceiling.
        double baselineShare = 18_000 / 4.0 / 51_200;
        double ceilingShare = MaterialBudget.forWorkingContext(262_144).chars(18_000) / 4.0 / 262_144;
        assertThat(ceilingShare).isCloseTo(baselineShare, org.assertj.core.data.Offset.offset(1e-9));
    }
}
