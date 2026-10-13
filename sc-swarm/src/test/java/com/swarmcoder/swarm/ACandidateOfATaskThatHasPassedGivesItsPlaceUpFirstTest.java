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
import com.swarmcoder.domain.SamplingConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Once a task has a candidate through verification, every candidate of it that is still in its
 * session is a duplicate (section 98): it keeps its place only while nothing waits for one. It is
 * the first to be stopped for a task that has nothing running, and - unlike a spare candidate of
 * a task still looking - it is also stopped for a further candidate of a task that has nothing
 * through verification yet. While nothing waits it runs on, so the judge still compares whatever
 * passes on places that would otherwise be empty.
 */
class ACandidateOfATaskThatHasPassedGivesItsPlaceUpFirstTest {

    private static CandidateGroup group(int candidates) {
        List<SamplingConfig> samplings = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            samplings.add(new SamplingConfig("model-a", 0.2, i, "persona", "full-files"));
        }
        return new CandidateGroup(samplings, ended -> { });
    }

    private static void end(CandidateGroup group) {
        for (CandidateGroup.Seat seat : group.seats()) {
            group.ended(seat, null);
        }
    }

    @Test
    void everyCandidateStillInItsSessionBecomesADuplicateAndTheNewestIsStoppedFirst() {
        CandidateGroup passedTask = group(3);
        List<CandidateGroup.Seat> seats = passedTask.seats();
        try {
            assertThat(seats.get(0).begins("model-a", "worker 0")).isTrue();
            assertThat(seats.get(1).begins("model-a", "worker 1")).isTrue();
            assertThat(SpareCandidates.giveUpADuplicateFor("model-a", "a further candidate"))
                .as("nothing of this task has passed: nobody is a duplicate").isFalse();
            assertThat(seats.get(1).signal.isSuperseded()).isFalse();

            // Worker 2 never started; workers 0 and 1 are in their sessions when one of the
            // task's candidates (it does not matter which) is through verification.
            assertThat(passedTask.hasPassingCandidate()).containsExactly(0, 1);
            assertThat(passedTask.passed()).isTrue();
            assertThat(passedTask.hasPassingCandidate()).as("told once").isEmpty();
            assertThat(SpareCandidates.duplicatesRunning()).isEqualTo(2);
            assertThat(seats.get(0).signal.isSuperseded())
                .as("nothing waits for a place: both run on").isFalse();
            assertThat(seats.get(1).signal.isSuperseded()).isFalse();

            // A further candidate of another task, which has nothing through verification.
            assertThat(SpareCandidates.giveUpADuplicateFor("model-a", "a further candidate"))
                .isTrue();
            assertThat(seats.get(1).signal.reason()).as("the newest has the least to lose")
                .isEqualTo(KillReason.SUPERSEDED);
            assertThat(seats.get(0).signal.isSuperseded()).isFalse();

            // The task's FIRST candidate is a duplicate too, though it was never a spare.
            assertThat(SpareCandidates.giveUpADuplicateFor("model-a", "another one")).isTrue();
            assertThat(seats.get(0).signal.reason()).isEqualTo(KillReason.SUPERSEDED);
            assertThat(SpareCandidates.giveUpADuplicateFor("model-a", "a third")).isFalse();
        } finally {
            end(passedTask);
        }
        assertThat(SpareCandidates.duplicatesRunning()).isZero();
    }

    @Test
    void aDuplicateGoesBeforeANewerSpareAndASpareIsNeverStoppedForAFurtherCandidate() {
        CandidateGroup passedTask = group(1);
        GroupSignal spareOfATaskStillLooking = new GroupSignal();
        SpareCandidates.Entry spare = null;
        try {
            assertThat(passedTask.seats().get(0).begins("model-a", "worker 0")).isTrue();
            passedTask.hasPassingCandidate();
            spare = SpareCandidates.started("model-a", "a newer spare", spareOfATaskStillLooking);

            assertThat(SpareCandidates.giveUpOneFor("model-a", "a task with nothing running"))
                .isTrue();
            assertThat(passedTask.seats().get(0).signal.reason())
                .as("the candidate whose task already has what it needs goes first")
                .isEqualTo(KillReason.SUPERSEDED);
            assertThat(spareOfATaskStillLooking.isSuperseded()).isFalse();

            assertThat(SpareCandidates.giveUpADuplicateFor("model-a", "a further candidate"))
                .as("an ordinary spare gives way only to a task with nothing running").isFalse();
            assertThat(spareOfATaskStillLooking.isSuperseded()).isFalse();

            assertThat(SpareCandidates.giveUpOneFor("model-a", "a second task")).isTrue();
            assertThat(spareOfATaskStillLooking.reason()).isEqualTo(KillReason.PLACE_NEEDED);
        } finally {
            SpareCandidates.ended(spare);
            end(passedTask);
        }
    }

    @Test
    void aCandidateThatGetsItsPlaceAsItsTaskPassesIsADuplicateFromTheStart() {
        CandidateGroup passedTask = group(2);
        try {
            passedTask.hasPassingCandidate();
            assertThat(passedTask.seats().get(1).begins("model-a", "worker 1")).isTrue();
            assertThat(SpareCandidates.duplicatesRunning()).isEqualTo(1);

            passedTask.sessionOver(passedTask.seats().get(1));
            assertThat(SpareCandidates.duplicatesRunning())
                .as("its session is over: it hands in and can no longer be stopped").isZero();
        } finally {
            end(passedTask);
        }
    }
}
