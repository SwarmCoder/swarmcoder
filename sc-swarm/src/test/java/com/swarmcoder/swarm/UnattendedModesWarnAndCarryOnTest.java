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

import com.swarmcoder.domain.OpinionPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The policy in force follows the overnight setting and an autonomous session, live. */
class UnattendedModesWarnAndCarryOnTest {

    private final AtomicBoolean overnight = new AtomicBoolean();
    private final AtomicBoolean autonomous = new AtomicBoolean();

    private EffectiveOpinionPolicy policy() {
        EffectiveOpinionPolicy p = new EffectiveOpinionPolicy();
        p.setUnattendedReason(() -> autonomous.get() ? "an autonomous session is running"
            : overnight.get() ? "the overnight setting is on" : null);
        return p;
    }

    @Test
    void aPersonAtTheConsoleIsAskedUntilOvernightIsSwitchedOn() {
        EffectiveOpinionPolicy p = policy();
        assertEquals(OpinionPolicy.ASK_THE_OPERATOR, p.get());
        overnight.set(true);
        assertEquals(OpinionPolicy.WARN_AND_CARRY_ON, p.get());
        overnight.set(false);
        assertEquals(OpinionPolicy.ASK_THE_OPERATOR, p.get());
    }

    @Test
    void anAutonomousSessionWarnsOnlyWhileItRunsAndNeverSwitchesOvernightOff() {
        EffectiveOpinionPolicy p = policy();
        autonomous.set(true);
        assertEquals(OpinionPolicy.WARN_AND_CARRY_ON, p.get());
        autonomous.set(false);
        assertEquals(OpinionPolicy.ASK_THE_OPERATOR, p.get());

        overnight.set(true);
        autonomous.set(true);
        assertEquals(OpinionPolicy.WARN_AND_CARRY_ON, p.get());
        autonomous.set(false);
        assertEquals(OpinionPolicy.WARN_AND_CARRY_ON, p.get(), "overnight is still on");
    }

    @Test
    void theHarnessSettingStaysWarnWhateverTheModesSay() {
        EffectiveOpinionPolicy p = policy();
        p.setExplicit(OpinionPolicy.WARN_AND_CARRY_ON);
        assertEquals(OpinionPolicy.WARN_AND_CARRY_ON, p.get());
    }
}
