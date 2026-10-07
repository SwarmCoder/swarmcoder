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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudGateTest {

    @Test
    void allowsSpendUnderTheCap() {
        CloudGate gate = new CloudGate(1000, null);
        gate.charge(400);
        gate.charge(400);
        assertThat(gate.used()).isEqualTo(800);
        assertThat(gate.exhausted()).isFalse();
    }

    @Test
    void throwsAndSignalsExactlyOnceWhenExhausted() {
        AtomicInteger signalled = new AtomicInteger();
        CloudGate gate = new CloudGate(100, signalled::incrementAndGet);

        assertThatThrownBy(() -> gate.charge(150)).isInstanceOf(CloudGate.BudgetExhaustedException.class);
        assertThatThrownBy(() -> gate.charge(1)).isInstanceOf(CloudGate.BudgetExhaustedException.class);

        assertThat(signalled.get()).as("BUDGET_EXTENSION decision parked once, not per call").isEqualTo(1);
        assertThat(gate.exhausted()).isTrue();
    }

    @Test
    void zeroCapDisablesEnforcement() {
        CloudGate gate = new CloudGate(0, null);
        gate.charge(Long.MAX_VALUE / 4);
        assertThat(gate.exhausted()).isFalse();
    }

    @Test
    void estimatesTokensFromText() {
        assertThat(CloudGate.estimateTokens("x".repeat(400))).isEqualTo(100);
        assertThat(CloudGate.estimateTokens(null)).isZero();
        assertThat(CloudGate.estimateTokens("ab")).isEqualTo(1);
    }
}
