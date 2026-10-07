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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 72: four repair workers spent 34 minutes, each finishing with "already done, nothing
 * to change", and the task was blocked with a generic sentence. The question must say the workers
 * found nothing to change and quote the objection that failed the candidate.
 */
class NoChangeRepairSaysSoTest {

    private static final String OBJECTION = "the contract `com.hambook.Qso_Rules` was not delivered";
    private static final String VERDICT = "build ok\n[verdict] NOT SURVIVED — " + OBJECTION + ".\n";

    private static CandidateSolution seed(boolean compiles, String logTail) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, compiles, null,
            null, null, null, Duration.ZERO, logTail, null);
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "b", null, "diff",
            report, null, null, CandidateState.FAILED, null);
    }

    private static CandidateSolution worker(String diff, CandidateState state, KillReason kill) {
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 100, "b", null, diff,
            null, null, null, state, kill);
    }

    @Test
    void workersThatChangedNothingAfterAnObjectionQuoteTheObjection() {
        String headline = NoChangeRepair.headline("Annotate Qso.call", List.of(seed(true, VERDICT)),
            List.of(worker("", CandidateState.FAILED, null),
                worker(null, CandidateState.KILLED, KillReason.NO_PROGRESS)));

        assertThat(headline).contains("Annotate Qso.call").contains("found nothing to change")
            .contains(OBJECTION).contains("false alarm").contains("stays BLOCKED");
    }

    @Test
    void aWorkerThatChangedSomethingIsTheOrdinaryCase() {
        assertThat(NoChangeRepair.headline("t", List.of(seed(true, VERDICT)),
            List.of(worker("", CandidateState.FAILED, null),
                worker("diff --git a b", CandidateState.FAILED, null)))).isNull();
    }

    @Test
    void aFailedBuildIsNotAnObjection() {
        assertThat(NoChangeRepair.headline("t", List.of(seed(false, VERDICT)),
            List.of(worker("", CandidateState.FAILED, null)))).isNull();
    }

    @Test
    void aWorkerThatDiedForAnotherReasonIsNotNoChange() {
        assertThat(NoChangeRepair.headline("t", List.of(seed(true, VERDICT)),
            List.of(worker("", CandidateState.KILLED, KillReason.TIMEOUT)))).isNull();
    }

    @Test
    void aCandidateWithNoRecordedVerdictGivesNothingToQuote() {
        assertThat(NoChangeRepair.headline("t", List.of(seed(true, "build ok")),
            List.of(worker("", CandidateState.FAILED, null)))).isNull();
    }
}
