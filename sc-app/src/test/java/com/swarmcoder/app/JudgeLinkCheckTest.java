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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Chain link 13 after the judge stopped being called for a lone survivor (harness run 85). */
class JudgeLinkCheckTest {

    private static final String BRIEF = "You are a code-review judge.\nVerification: compiled\n";

    private final UUID task = UUID.randomUUID();

    @Test
    void aLoneVerifiedSurvivorOfARepairRoundHoldsWithoutAJudgeCall() {
        // Run 85: two first candidates failed, three repair workers were killed, one passed.
        List<CandidateSolution> run85 = List.of(
            candidate(task, 0, CandidateState.FAILED, true, false),
            candidate(task, 1, CandidateState.FAILED, true, false),
            candidate(task, 100, CandidateState.SELECTED, true, false),
            candidate(task, 101, CandidateState.KILLED, false, false));

        JudgeLinkCheck.Verdict verdict = JudgeLinkCheck.evaluate(run85, null);

        assertThat(verdict.ok()).as(verdict.detail()).isTrue();
        assertThat(verdict.detail()).contains("1 task(s) had exactly one candidate");
    }

    @Test
    void twoSurvivorsNeverJudgedBreakTheLink() {
        List<CandidateSolution> unjudged = List.of(
            candidate(task, 0, CandidateState.SELECTED, true, false),
            candidate(task, 1, CandidateState.SURVIVED, true, false));

        JudgeLinkCheck.Verdict verdict = JudgeLinkCheck.evaluate(unjudged, null);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.detail()).contains("the judge should have run");
    }

    @Test
    void oneTaskSkippedAndAnotherJudgedHolds() {
        UUID other = UUID.randomUUID();
        List<CandidateSolution> mixed = List.of(
            candidate(task, 0, CandidateState.SELECTED, true, false),
            candidate(other, 0, CandidateState.SELECTED, true, true),
            candidate(other, 1, CandidateState.SURVIVED, true, true));

        assertThat(JudgeLinkCheck.evaluate(mixed, BRIEF).ok()).isTrue();
    }

    @Test
    void aJudgeToldNotRunAlthoughReportsExistStillBreaks() {
        List<CandidateSolution> judged = List.of(
            candidate(task, 0, CandidateState.SELECTED, true, true),
            candidate(task, 1, CandidateState.SURVIVED, true, true));

        assertThat(JudgeLinkCheck.evaluate(judged,
            "You are a code-review judge.\nVerification: NOT RUN\n").ok()).isFalse();
        assertThat(JudgeLinkCheck.evaluate(judged, "You are a code-review judge.").ok()).isFalse();
        assertThat(JudgeLinkCheck.evaluate(judged, BRIEF).ok()).isTrue();
    }

    @Test
    void noSurvivorAndALoneSurvivorWithNoReportBothBreak() {
        assertThat(JudgeLinkCheck.evaluate(List.of(
            candidate(task, 0, CandidateState.FAILED, true, false)), null).ok()).isFalse();
        assertThat(JudgeLinkCheck.evaluate(List.of(
            candidate(task, 0, CandidateState.SELECTED, false, false)), null).ok()).isFalse();
    }

    private static CandidateSolution candidate(UUID taskId, int worker, CandidateState state,
                                               boolean verified, boolean judged) {
        VerificationReport report = null;
        if (verified) {
            TestResults acceptance = new TestResults(1, 0, 0, 0, List.of());
            acceptance.setStageOutcome(TestStageOutcome.EXECUTED);
            report = new VerificationReport(UUID.randomUUID(), true, true, acceptance, null, null,
                null, null, null, null);
        }
        return new CandidateSolution(UUID.randomUUID(), taskId, worker, "branch", null, "diff",
            report, null, judged ? new JudgeScore(0.8, "ok", "m") : null, state, null);
    }
}
