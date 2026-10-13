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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 91. Live supervised build, 2026-10-12/13, one local model server whose measured best
 * is four conversations at once: a repair round started two workers while nothing else ran, and
 * a fixed 30 minute ceiling stopped it after 8 and 11 turns, at about three minutes a turn,
 * while both workers were still changing files. The same instruction with 90 minutes succeeded.
 */
class ARepairRoundFillsThePlacesAndFollowsProgressTest {

    private static final long MINUTE = 60_000L;

    @AfterEach
    void clear() {
        System.clearProperty(RepairRound.ROUND_MINUTES_PROPERTY);
        System.clearProperty(RepairRound.ROUND_MILLIS_PROPERTY);
        System.clearProperty(RepairRound.ROUND_TURNS_PROPERTY);
        System.clearProperty(RepairRound.QUIET_MINUTES_PROPERTY);
    }

    // --- how many, and how they differ ---------------------------------------------------------

    @Test
    void theWorkersOfARoundAreDealtOutOverItsSeeds() {
        assertThat(RepairRound.spread(4, new int[] {10})).as("one seed gets them all")
            .containsExactly(4);
        assertThat(RepairRound.spread(4, new int[] {10, 10})).as("as it was with two seeds")
            .containsExactly(2, 2);
        assertThat(RepairRound.spread(3, new int[] {10, 10})).containsExactly(2, 1);
        assertThat(RepairRound.spread(1, new int[] {10, 10})).containsExactly(1, 0);
        assertThat(RepairRound.spread(4, new int[] {1, 10}))
            .as("a seed with one worker number left takes one").containsExactly(1, 3);
        assertThat(RepairRound.spread(4, new int[] {2})).as("never more than there is room for")
            .containsExactly(2);
    }

    @Test
    void noTwoWorkersOfOneSeedAreAlikeAndTheFirstTwoAreWhatTheyAlwaysWere() {
        SamplingConfig first = RepairRound.sampling("m", 0, 120);
        SamplingConfig second = RepairRound.sampling("m", 1, 121);
        assertThat(first.temperature()).isEqualTo(0.4);
        assertThat(second.temperature()).isEqualTo(0.8);
        assertThat(first.personaId()).isEqualTo("defensive-edges");
        assertThat(second.personaId()).isEqualTo("defensive-edges");

        Set<String> seen = new HashSet<>();
        for (int nth = 0; nth < 10; nth++) {
            SamplingConfig cfg = RepairRound.sampling("m", nth, 120 + nth);
            assertThat(seen.add(cfg.temperature() + " " + cfg.personaId()))
                .as("worker %s of a seed differs from every earlier one", nth).isTrue();
            assertThat(WorkerPersonas.DEFAULT_ROTATION).contains(cfg.personaId());
        }
    }

    // --- the limits ----------------------------------------------------------------------------

    @Test
    void theCeilingIsSoManyTurnsWorthOfThisRunsTurnsAndNeverUnderItsFloor() {
        assertThat(RepairRound.ceilingMillis(0)).as("no turn measured yet").isEqualTo(30 * MINUTE);
        assertThat(RepairRound.ceilingMillis(20_000)).as("a fast server keeps the floor")
            .isEqualTo(30 * MINUTE);
        assertThat(RepairRound.ceilingMillis(3 * MINUTE))
            .as("three minutes a turn: the 90 minutes the stopped round needed")
            .isEqualTo(90 * MINUTE);
        System.setProperty(RepairRound.ROUND_TURNS_PROPERTY, "10");
        assertThat(RepairRound.ceilingMillis(6 * MINUTE)).isEqualTo(60 * MINUTE);
        System.setProperty(RepairRound.ROUND_MINUTES_PROPERTY, "45");
        assertThat(RepairRound.ceilingMillis(6 * MINUTE)).as("the setting replaces it")
            .isEqualTo(45 * MINUTE);
    }

    @Test
    void theQuietStretchIsWhatAWorkersOwnStallStopCountsInTurns() {
        assertThat(RepairRound.quietMillis(0)).isEqualTo(15 * MINUTE);
        assertThat(RepairRound.quietMillis(4 * MINUTE))
            .isEqualTo(EarlyKillEnforcer.IDLE_TURNS_BEFORE_KILL * 4 * MINUTE);
        System.setProperty(RepairRound.QUIET_MINUTES_PROPERTY, "7");
        assertThat(RepairRound.quietMillis(4 * MINUTE)).isEqualTo(7 * MINUTE);
    }

    /** The round of the live build: two workers, three minutes a turn, every turn a change. */
    @Test
    void workersThatKeepMakingProgressAreNotStoppedAtThirtyMinutes() {
        AtomicLong clock = new AtomicLong(1_000);
        RepairRound round = new RepairRound("on an instruction", 4, 4, 0, clock::get);
        round.planned(2);
        assertThat(round.due(3 * MINUTE)).as("nothing has started: no limit runs yet").isNull();
        round.started(120);
        round.started(121);
        for (int turn = 1; turn <= 11; turn++) {
            clock.addAndGet(3 * MINUTE);
            round.turn(120, true);
            if (turn <= 8) {
                round.turn(121, true);
            }
            assertThat(round.due(3 * MINUTE)).as("after %s turns", turn).isNull();
        }
        assertThat(clock.get()).as("past the old fixed ceiling").isGreaterThan(30 * MINUTE);

        clock.set(1_000 + 90 * MINUTE);
        assertThat(round.due(3 * MINUTE)).as("thirty turns' worth is the ceiling")
            .isEqualTo(RepairRound.Ended.TIME_LIMIT);
    }

    @Test
    void aRoundStoppedAtItsCeilingSaysAfterHowManyTurnsAndThatItsWorkersWereProgressing() {
        System.setProperty(RepairRound.ROUND_MINUTES_PROPERTY, "30");
        AtomicLong clock = new AtomicLong(1_000);
        RepairRound round = new RepairRound("on an instruction", 4, 4, 0, clock::get);
        round.planned(2);
        round.started(120);
        round.started(121);
        for (int turn = 1; turn <= 11; turn++) {
            clock.addAndGet(turn == 11 ? 2 * MINUTE : 3 * MINUTE - 20_000);
            round.turn(120, true);
            if (turn <= 8) {
                round.turn(121, turn < 8);
            }
        }
        clock.set(1_000 + 30 * MINUTE);
        assertThat(round.due(3 * MINUTE)).isEqualTo(RepairRound.Ended.TIME_LIMIT);
        round.stopped(RepairRound.Ended.TIME_LIMIT, 3 * MINUTE);
        RepairRound.Outcome outcome = round.finished(false);

        assertThat(outcome.started()).isEqualTo(2);
        assertThat(outcome.turns()).isEqualTo(19);
        assertThat(outcome.stoppedWorkers()).isEqualTo(2);
        assertThat(outcome.stoppedTurns()).isEqualTo(19);
        assertThat(outcome.onlyTheTimeLimit())
            .as("the time limit is the only reason nothing passed").isTrue();
        assertThat(BlockedReason.timeLimit(outcome, true))
            .contains("stopped by the time limit (30.0 minutes) after 19 turns; the workers "
                + "were still making progress")
            .contains("not counted")
            .contains(RepairRound.ROUND_MINUTES_PROPERTY);
        assertThat(BlockedReason.timeLimit(outcome, false)).doesNotContain("not counted");
        assertThat(outcome.line("T"))
            .contains("places 4 of 4 free")
            .contains("workers 2 started of 2 planned (a first round of this task gets 4)")
            .contains("19 turn(s) in 30.0 minute(s)")
            .contains("it reached its time limit")
            .contains("were still making progress");
    }

    @Test
    void aRoundWhoseWorkersHadGoneQuietIsNotCalledProgressing() {
        System.setProperty(RepairRound.ROUND_MINUTES_PROPERTY, "30");
        System.setProperty(RepairRound.QUIET_MINUTES_PROPERTY, "20");
        AtomicLong clock = new AtomicLong(1_000);
        RepairRound round = new RepairRound("after every candidate failed", 4, 4, 2, clock::get);
        round.started(100);
        clock.addAndGet(5 * MINUTE);
        round.turn(100, true);
        clock.addAndGet(25 * MINUTE);
        round.stopped(RepairRound.Ended.TIME_LIMIT, MINUTE);
        RepairRound.Outcome outcome = round.finished(false);
        assertThat(outcome.onlyTheTimeLimit()).isFalse();
        assertThat(BlockedReason.timeLimit(outcome, true))
            .contains("after 1 turns; the workers had stopped making progress")
            .doesNotContain("not counted");
        assertThat(outcome.placesFree()).isEqualTo(2);
    }

    @Test
    void aRoundIsStoppedWhenNoWorkerHasMadeProgressForTheStretch() {
        System.setProperty(RepairRound.ROUND_MINUTES_PROPERTY, "300");
        AtomicLong clock = new AtomicLong(1_000);
        RepairRound round = new RepairRound("after every candidate failed", 2, 0, 0, clock::get);
        round.started(100);
        round.started(101);
        clock.addAndGet(14 * MINUTE);
        assertThat(round.due(MINUTE)).isNull();
        round.turn(101, false);
        clock.addAndGet(MINUTE);
        assertThat(round.due(MINUTE)).as("a turn that moved nothing is not progress")
            .isEqualTo(RepairRound.Ended.NO_PROGRESS);
        round.turn(100, true);
        assertThat(round.due(MINUTE)).as("one worker's progress is the round's").isNull();

        clock.addAndGet(14 * MINUTE);
        round.verdict();
        clock.addAndGet(2 * MINUTE);
        assertThat(round.due(MINUTE)).as("a verification verdict is a new result").isNull();

        round.sessionOver(100);
        round.sessionOver(101);
        clock.addAndGet(20 * MINUTE);
        assertThat(round.due(MINUTE))
            .as("nobody is in a session: only verification is left, and it is not stopped")
            .isNull();

        RepairRound.Outcome outcome = round.finished(false);
        assertThat(outcome.ended()).isEqualTo(RepairRound.Ended.EVERY_WORKER_ENDED);
        assertThat(BlockedReason.timeLimit(outcome, true)).isEmpty();
        assertThat(outcome.line("T")).contains("places not limited");
    }

    @Test
    void theRunsTimePerTurnIsTheMeanOfTheTurnsMeasured() {
        TurnPace pace = new TurnPace();
        assertThat(pace.millisPerTurn()).isZero();
        pace.record(2 * MINUTE);
        pace.record(4 * MINUTE);
        assertThat(pace.millisPerTurn()).isEqualTo(3 * MINUTE);
        assertThat(pace.turns()).isEqualTo(2);
    }

    @Test
    void theNewStopReasonIsAppendedAndItsWorkIsVerified() {
        KillReason[] all = KillReason.values();
        assertThat(all[all.length - 1]).as("stored by ordinal: appended last")
            .isEqualTo(KillReason.ROUND_QUIET);
        assertThat(KillReason.ROUND_TIME_UP.ordinal()).isEqualTo(16);
        assertThat(KillReason.PLAN_FAULT.ordinal()).isEqualTo(17);
        assertThat(StoppedWork.worthVerifying(KillReason.ROUND_QUIET, "diff --git a/x b/x\n+x\n"))
            .isTrue();
        assertThat(KillReason.ROUND_QUIET.sentence()).contains("whatever it had changed");
    }
}
