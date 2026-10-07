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

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The author decision of 2026-08-27 (DEVELOPER_CORRECTIONS §17.1): <b>when a task claims
 * requirement-checks and the acceptance stage executed zero tests, that is a verification FAILURE,
 * not a pass.</b>
 *
 * <p>Each case here is one of the ways the old rule could be wrong, and one of the ways the new
 * rule must not become wrong in the other direction. The pair that matters most is the last two: a
 * stage that positively ran and executed nothing FAILS, and a stage whose result could not be read
 * — including every report written before this rule existed — does not.
 */
class EmptyCheckIsNotAPassTest {

    private static final List<String> TWO_CHECKS = List.of(
        "R1:C1 multiplying two positive numbers returns their product  "
            + "[test: swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers]",
        "R1:C2 multiplying by zero returns zero  "
            + "[test: swarm.accept.MultiplyAcceptTest#multiplyingByZeroGivesZero]");

    @Test
    void aStageThatRanNoTestsFailsWhenTheTaskClaimsChecks() {
        VerificationReport report = report(stage(0, 0, 0, TestStageOutcome.EXECUTED));

        Verdicts.Verdict verdict = Verdicts.assess(report, TWO_CHECKS);

        assertThat(verdict.survived())
            .as("no tests means no failures, which is exactly why this used to pass").isFalse();
        assertThat(verdict.reason())
            .contains("executed NO tests")
            .as("the run has to say WHICH checks nothing verified, not just that something is red")
            .contains("R1:C1").contains("R1:C2")
            .contains("swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers");
    }

    @Test
    void aStageThatRanNoTestsStillPassesWhenTheTaskClaimsNone() {
        // The documented M1 allowance (§5): a repository with no acceptance suite yet still swarms.
        // Collapsing this into the case above would stop every enabler task in the product.
        VerificationReport report = report(stage(0, 0, 0, TestStageOutcome.EXECUTED));

        assertThat(Verdicts.assess(report, List.of()).survived()).isTrue();
        assertThat(Verdicts.survived(report)).isTrue();
    }

    @Test
    void aVerifyContractWithNoAcceptanceStageFailsWhenChecksAreClaimed() {
        VerificationReport report = report(stage(0, 0, 0, TestStageOutcome.NOT_CONFIGURED));

        Verdicts.Verdict verdict = Verdicts.assess(report, TWO_CHECKS);

        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason()).contains("declares no acceptance stage").contains("R1:C1");
    }

    @Test
    void aReportFromBeforeThisRuleExistedIsNotReadAsZeroTestsRan() {
        // No stage outcome at all — every report already in the store. It cannot establish that the
        // stage ran and executed nothing, so it must not fail a candidate that used to survive.
        TestResults old = new TestResults(0, 0, 0, 0, List.of());
        assertThat(old.stageOutcome()).isEqualTo(TestStageOutcome.INCONCLUSIVE);
        assertThat(old.ranNothing()).isFalse();

        assertThat(Verdicts.assess(report(old), TWO_CHECKS).survived()).isTrue();
    }

    @Test
    void anUnreadableStageDoesNotKillTheCandidateByItself() {
        // "The commands ran but nothing came back" is not evidence that zero tests ran. Inventing a
        // third failure mode out of it would fail candidates on a broken report reader.
        VerificationReport report = report(stage(0, 0, 0, TestStageOutcome.INCONCLUSIVE));

        assertThat(Verdicts.assess(report, TWO_CHECKS).survived()).isTrue();
    }

    @Test
    void aTruncatedIdListCannotBeMistakenForAnEmptyRun() {
        // Truncation only happens above MAX_IDS ids, so the counts are large; the rule reads counts,
        // never the id list, and so cannot be fooled by one.
        TestResults truncated = new TestResults(5000, 0, 0, 0, List.of(), List.of("a#b"),
            List.of(), true).withStageOutcome(TestStageOutcome.EXECUTED);

        assertThat(truncated.ranNothing()).isFalse();
        assertThat(Verdicts.assess(report(truncated), TWO_CHECKS).survived()).isTrue();
    }

    @Test
    void aStageThatRanOnlySkippedTestsCountsAsHavingRunNothing() {
        // A disabled test proves nothing, so it must not be what satisfies "some test ran" —
        // otherwise @Disabled becomes the cheapest way through this gate.
        TestResults skippedOnly =
            new TestResults(0, 0, 0, 3, List.of(), List.of(), List.of("a#b", "a#c", "a#d"), false)
                .withStageOutcome(TestStageOutcome.EXECUTED);

        assertThat(skippedOnly.ranNothing()).isTrue();
        assertThat(Verdicts.assess(report(skippedOnly), TWO_CHECKS).survived()).isFalse();
    }

    @Test
    void anOrdinaryGreenRunIsUntouched() {
        TestResults green = new TestResults(2, 0, 0, 0, List.of(),
            List.of("swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers",
                "swarm.accept.MultiplyAcceptTest#multiplyingByZeroGivesZero"),
            List.of(), false).withStageOutcome(TestStageOutcome.EXECUTED);

        assertThat(Verdicts.assess(report(green), TWO_CHECKS).survived()).isTrue();
    }

    @Test
    void aRealFailureStillLosesAndSaysWhy() {
        TestResults red = new TestResults(1, 1, 0, 0,
            List.of(new TestFailure("swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers",
                "expected 12 but was 7", "")))
            .withStageOutcome(TestStageOutcome.EXECUTED);

        Verdicts.Verdict verdict = Verdicts.assess(report(red), TWO_CHECKS);

        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason()).contains("acceptance test(s) failed");
    }

    // Harness run 22, 00:50: two candidates compiled, their one claimed acceptance test threw, and
    // the verdict said only "0 acceptance test(s) failed and 1 errored" — no exception, no method,
    // no line, so the repair workers sent after them were never shown what actually broke.
    @Test
    void theVerdictNamesTheFailingTestItsExceptionAndWhereItThrew() {
        String trace = """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
            \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestResults errored = new TestResults(0, 0, 1, 0,
            List.of(new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
                "Cannot invoke \"String.length()\" because \"title\" is null", trace)))
            .withStageOutcome(TestStageOutcome.EXECUTED);

        Verdicts.Verdict verdict = Verdicts.assess(report(errored), TWO_CHECKS);

        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason()).isEqualTo("0 acceptance test(s) failed and 1 errored: "
            + "swarm.accept.BookRatingTest#assignsRatingToBook — NullPointerException: "
            + "Cannot invoke \"String.length()\" because \"title\" is null, "
            + "at com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating"
            + "(BookServiceServerImpl.java:41)");
        // The whole point: this is the sentence a hover card shows in full, because a consumer
        // that only keeps the first line of a longer reason must not lose the detail to a newline.
        assertThat(verdict.reason()).doesNotContain("\n");
    }

    @Test
    void whenTheFailureNeverLeavesTheTestsOwnClassTheVerdictSaysSo() {
        // The candidate's code is never reached: the test's own helper throws first. Blaming "the
        // cause" would send a repair worker chasing a bug that is not in anything it can fix.
        String trace = """
            java.lang.NullPointerException: fixture not initialised
            \tat swarm.accept.BookRatingTest.loadFixture(BookRatingTest.java:15)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestResults errored = new TestResults(0, 0, 1, 0,
            List.of(new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
                "fixture not initialised", trace)))
            .withStageOutcome(TestStageOutcome.EXECUTED);

        Verdicts.Verdict verdict = Verdicts.assess(report(errored), TWO_CHECKS);

        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason())
            .contains("the error is inside the acceptance test, not the candidate's code");
    }

    @Test
    void aCandidateThatDoesNotCompileIsNotReportedAsAnEmptyCheck() {
        // The acceptance stage is SKIPPED when compile fails. That must read as "we never got
        // there", not as "the checks went unverified" — the actionable fact is the compile error.
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false,
            stage(0, 0, 0, TestStageOutcome.SKIPPED), stage(0, 0, 0, TestStageOutcome.SKIPPED),
            null, null, Duration.ZERO, "", null);

        assertThat(Verdicts.assess(report, TWO_CHECKS).reason()).isEqualTo("the candidate does not compile");
    }

    private static TestResults stage(int passed, int failed, int errored, TestStageOutcome outcome) {
        return new TestResults(passed, failed, errored, 0, List.of()).withStageOutcome(outcome);
    }

    private static VerificationReport report(TestResults acceptance) {
        return new VerificationReport(UUID.randomUUID(), true, true, acceptance,
            stage(3, 0, 0, TestStageOutcome.EXECUTED), null, null, Duration.ZERO, "", null);
    }
}
