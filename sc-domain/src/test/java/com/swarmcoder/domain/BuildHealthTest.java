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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parked run must read as STOPPED immediately — not after {@link BuildHealth#STALLED_AFTER_MINUTES}
 * of a heartbeat going quiet, which is how long it used to take before a parked run said anything
 * useful, and even then for the wrong reason (a stale heartbeat, not the question it had actually
 * raised). Reproduces the report this fix is for: a run parked at 07:23 still read "building now" at
 * 10:37, because parking never changed the run's state and the heartbeat from the moment it parked
 * was still fresh enough to pass for STALLED_AFTER_MINUTES.
 */
class BuildHealthTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void aParkedRunIsStoppedEvenWithAFreshHeartbeat() {
        long heartbeatJustNow = NOW - 5_000;
        long parkedJustNow = NOW - 5_000;
        assertThat(BuildHealth.of("EXECUTING", heartbeatJustNow, 0, parkedJustNow, NOW))
            .describedAs("a park is a recorded fact, not an inference from silence - the card must "
                + "not wait out a stale-heartbeat timer to say so")
            .isEqualTo(BuildHealth.Kind.STOPPED);
    }

    @Test
    void aParkedRunIsStoppedRegardlessOfHowLongAgoItParked() {
        // The old defect was the other direction (waiting for staleness), but a park read the
        // instant it happens must also go on reading STOPPED for as long as it stays parked.
        long parkedHoursAgo = NOW - 3 * 60 * 60 * 1000L;
        assertThat(BuildHealth.of("TEST_AUTHORING", NOW - 5_000, 0, parkedHoursAgo, NOW))
            .isEqualTo(BuildHealth.Kind.STOPPED);
    }

    @Test
    void anUnparkedRunWithAFreshHeartbeatIsStillWorking() {
        // parkedAtMillis stays 0 (never parked) - nothing about this test's change may make a
        // healthy, actively-building run read as stopped.
        assertThat(BuildHealth.of("EXECUTING", NOW - 5_000, 0, 0, NOW))
            .isEqualTo(BuildHealth.Kind.WORKING);
    }

    @Test
    void aTerminalRunIsStoppedWhetherOrNotItWasEverParked() {
        assertThat(BuildHealth.of("DELIVERED", NOW - 5_000, 0, 0, NOW))
            .isEqualTo(BuildHealth.Kind.STOPPED);
        assertThat(BuildHealth.of("ABORTED", NOW - 5_000, 0, NOW - 5_000, NOW))
            .describedAs("a terminal run reads STOPPED for the terminal reason regardless of a "
                + "leftover park mark - equally correct either way, since STOPPED is STOPPED")
            .isEqualTo(BuildHealth.Kind.STOPPED);
    }

    @Test
    void aParkIsCheckedBeforeAPause() {
        // These two facts should never coexist on a real run (a pause means the engine is still
        // driving it; a park means nothing is), but the park - a stop nothing waits out - must win
        // if they ever did, rather than reading as a self-healing pause that will resume by itself.
        assertThat(BuildHealth.of("EXECUTING", NOW - 5_000, NOW - 5_000, NOW - 5_000, NOW))
            .isEqualTo(BuildHealth.Kind.STOPPED);
    }
}
