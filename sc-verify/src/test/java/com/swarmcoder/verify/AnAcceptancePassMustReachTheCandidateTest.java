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
package com.swarmcoder.verify;

import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design: <b>an acceptance pass that would have happened anyway is not a pass.</b>
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 30, 2026-09-05. The story's acceptance test supplied its own implementation of the
 * {@code BookService} contract, so it was green on an empty tree. Both candidates "passed" it
 * without a line of their own code being executed, and the story was stamped delivered on it.
 * "The acceptance stage was green" had never meant "this candidate's code ran", and here the two
 * came apart completely.
 *
 * <p>The evidence used is the run's own pre-change execution of the same tests, which every task
 * already has — no extra run, no new instrument.
 *
 * <h2>And what must NOT change</h2>
 *
 * <p>A test that is green before a wave because the waves in FRONT of it delivered what it measures
 * is success, not a fault: that is {@code ChecksAlreadyProved}, and stopping there interrupts a
 * person at the last step of a run that went right. So only a pass on a tree carrying nothing this
 * run delivered kills a candidate. And an enabler task, which claims no check at all, is untouched.
 */
class AnAcceptancePassMustReachTheCandidateTest {

    private static final List<String> ONE_CHECK = List.of(
        "R1:C1 A user can assign a rating to a book  "
            + "[test: swarm.accept.BookManagementTest#assignsRatingToBook]");

    @Test
    void aGreenStageThatWasAlreadyGreenWithNothingDeliveredDoesNotSaveTheCandidate() {
        Verdicts.Verdict verdict = Verdicts.assess(report(green()), ONE_CHECK,
            Verdicts.AcceptanceProvenance.provesNothing(
                "swarm.accept.BookManagementTest#assignsRatingToBook passed on the tree this run "
                + "started from, before any worker ran"));

        assertThat(verdict.survived())
            .as("the test proves nothing about this candidate, so it cannot certify it").isFalse();
        assertThat(verdict.reason())
            .contains("the acceptance test passes without this candidate's change; it proves "
                + "nothing about it")
            .as("and it names the check that is left proved by nothing")
            .contains("R1:C1")
            .contains("swarm.accept.BookManagementTest#assignsRatingToBook")
            .contains("measures only itself");
    }

    @Test
    void anEnablerTaskWithNoChecksIsUntouched() {
        // No check was ever claimed, so no acceptance test was meant to prove anything here. The
        // documented M1 allowance must survive this rule exactly as it survived the last four.
        assertThat(Verdicts.assess(report(green()), List.of(),
            Verdicts.AcceptanceProvenance.provesNothing("nothing was claimed")).survived()).isTrue();
    }

    @Test
    void aTestAnEarlierWaveLegitimatelyMadeGreenStillLetsTheCandidateThrough() {
        // ChecksAlreadyProved: the waves in front delivered what these tests measure. That is
        // success. It is recorded, the judge is told the tests no longer separate the candidates,
        // and the run finishes — it is NOT a reason to fail anybody.
        Verdicts.AcceptanceProvenance earlierWaveDeliveredIt =
            new Verdicts.AcceptanceProvenance(true, true, false,
                "green on the tree this wave was cut from, which carries wave 1's winner");

        assertThat(Verdicts.assess(report(green()), ONE_CHECK, earlierWaveDeliveredIt).survived())
            .isTrue();
    }

    @Test
    void aTestThatWasRedBeforeTheDiffIsExactlyWhatShouldPass() {
        assertThat(Verdicts.assess(report(green()), ONE_CHECK,
            Verdicts.AcceptanceProvenance.redWithoutTheDiff("1 test failed on the pre-change tree"))
            .survived()).isTrue();
    }

    @Test
    void withNothingMeasuredNothingIsConcluded() {
        // An absent instrument is not a verdict — the same line every other gate in this class
        // draws. Every existing caller of the two-argument assess lands here.
        assertThat(Verdicts.assess(report(green()), ONE_CHECK).survived()).isTrue();
        assertThat(Verdicts.assess(report(green()), ONE_CHECK,
            Verdicts.AcceptanceProvenance.UNKNOWN).survived()).isTrue();
    }

    @Test
    void aCandidateThatFailedForARealReasonKeepsThatReason() {
        TestResults red = new TestResults(0, 1, 0, 0, List.of())
            .withStageOutcome(TestStageOutcome.EXECUTED);

        Verdicts.Verdict verdict = Verdicts.assess(report(red), ONE_CHECK,
            Verdicts.AcceptanceProvenance.provesNothing("green before the diff"));

        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason())
            .as("a real failure must not be re-worded as a tautology")
            .contains("acceptance test(s) failed")
            .doesNotContain("proves nothing about it");
    }

    private static TestResults green() {
        return new TestResults(1, 0, 0, 0, List.of(),
            List.of("swarm.accept.BookManagementTest#assignsRatingToBook"), List.of(), false)
            .withStageOutcome(TestStageOutcome.EXECUTED);
    }

    private static VerificationReport report(TestResults acceptance) {
        return new VerificationReport(UUID.randomUUID(), true, true, acceptance,
            new TestResults(3, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
            null, null, Duration.ZERO, "", null);
    }
}
