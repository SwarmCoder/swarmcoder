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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three small pieces behind "different work first" (2026-10-02), each on its own: a spare
 * candidate only ever takes a place nothing is waiting for ({@link WorkerSlots#tryEnter}), the
 * most recently started spare is the one that gives its place up ({@link SpareCandidates}), and
 * the tasks of a wave know whether anyone is still looking for a passing candidate
 * ({@link WaveBoard}).
 */
class SparePlacesTest {

    @AfterEach
    void noCeilingLeftBehind() {
        WorkerSlots.configure(0);
    }

    @Test
    void aSparePlaceIsOnlyOneThatIsFreeWithNothingWaitingForIt() throws Exception {
        WorkerSlots.configure(1);
        WorkerSlots.Place taken = WorkerSlots.shared().enter("first candidate");
        assertThat(WorkerSlots.shared().tryEnter()).as("the one place is busy").isNull();

        // A first candidate of another task queues for the place.
        Thread queued = Thread.ofVirtual().start(() -> WorkerSlots.shared().enter("another task")
            .close());
        while (WorkerSlots.shared().waiting() == 0) {
            Thread.sleep(5);
        }
        taken.close();
        queued.join();

        WorkerSlots.Place spare = WorkerSlots.shared().tryEnter();
        assertThat(spare).as("free now, and nothing is waiting").isNotNull();
        spare.close();
        spare.close(); // giving it back twice gives back one place, not two
        assertThat(WorkerSlots.shared().running()).isZero();
        WorkerSlots.Place again = WorkerSlots.shared().tryEnter();
        assertThat(again).isNotNull();
        assertThat(WorkerSlots.shared().tryEnter()).as("still exactly one place").isNull();
        again.close();
    }

    @Test
    void theMostRecentlyStartedSpareGivesItsPlaceUpAndOnlyThatOne() {
        GroupSignal older = new GroupSignal();
        GroupSignal newer = new GroupSignal();
        GroupSignal otherModel = new GroupSignal();
        SpareCandidates.Entry first = SpareCandidates.started("model-a", "older spare", older);
        SpareCandidates.Entry second = SpareCandidates.started("model-a", "newer spare", newer);
        SpareCandidates.Entry third = SpareCandidates.started("model-b", "other model", otherModel);
        try {
            assertThat(SpareCandidates.giveUpOneFor("model-a", "a task with nothing running"))
                .isTrue();
            assertThat(newer.reason()).as("the newest has the least work to lose")
                .isEqualTo(KillReason.PLACE_NEEDED);
            assertThat(older.isSuperseded()).isFalse();
            assertThat(otherModel.isSuperseded()).isFalse();

            assertThat(SpareCandidates.giveUpOneFor("model-a", "a second task")).isTrue();
            assertThat(older.reason()).isEqualTo(KillReason.PLACE_NEEDED);

            // No spare of its own model is left: the machine's ceiling is shared, so another
            // model's spare is asked.
            assertThat(SpareCandidates.giveUpOneFor("model-a", "a third task")).isTrue();
            assertThat(otherModel.reason()).isEqualTo(KillReason.PLACE_NEEDED);
            assertThat(SpareCandidates.giveUpOneFor("model-a", "a fourth task"))
                .as("nothing left to ask").isFalse();
        } finally {
            SpareCandidates.ended(first);
            SpareCandidates.ended(second);
            SpareCandidates.ended(third);
        }
    }

    @Test
    void aStoppedCandidateKeepsTheFirstReasonItWasGiven() {
        GroupSignal signal = new GroupSignal();
        assertThat(signal.isSuperseded()).isFalse();
        assertThat(signal.stop(KillReason.PLACE_NEEDED)).isTrue();
        signal.supersede();
        assertThat(signal.reason()).isEqualTo(KillReason.PLACE_NEEDED);
    }

    @Test
    void secondCandidatesWaitUntilEveryTaskThatCanStartHasItsFirstGoing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // Three tasks, the operator allows two at a time.
        WaveBoard board = new WaveBoard(3, 2);
        board.started(a);
        board.started(b);
        board.firstPlaced(a);
        assertThat(board.allFirstsPlaced()).as("b's first candidate has no place yet").isFalse();
        board.firstPlaced(b);
        assertThat(board.allFirstsPlaced()).isTrue();

        // A ends; C takes its turn and its first candidate is expected before any second one.
        board.ended(a);
        board.started(c);
        assertThat(board.allFirstsPlaced()).isFalse();
        board.firstPlaced(c);
        assertThat(board.allFirstsPlaced()).isTrue();
    }

    @Test
    void aTaskKnowsWhetherAnotherIsStillLookingForAPassingCandidate() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        WaveBoard board = new WaveBoard(2, 0);
        board.started(a);
        assertThat(board.othersStillLooking(a)).as("b has not started").isFalse();
        board.started(b);
        assertThat(board.othersStillLooking(a)).isTrue();
        board.hasPassingCandidate(a);
        assertThat(board.othersStillLooking(b)).as("a has its candidate").isFalse();
        assertThat(board.othersStillLooking(a)).as("b is still looking").isTrue();
        board.ended(b);
        assertThat(board.othersStillLooking(a)).isFalse();
    }
}
