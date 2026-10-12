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
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build, 2026-10-12: eight candidates compiled, passed 4 of 4 acceptance tests
 * and were failed by one verification objection. The question of the blocked task listed them
 * as "[FAILED] compiles=true acceptance 4p/0f/0e" and gave no reason.
 */
class AVerificationObjectionIsTheBlockedReasonTest {

    private static final String OBJECTION = "the contract `com.demo.server.BookQueries` was "
        + "delivered without the members public Search(), List<Book> execute(Object root). "
        + "The type already existed in the project before this task began.";
    private static final String HEADLINE = "Task BLOCKED after swarm + repair round: 't'\n\n";

    @Test
    void theObjectionSharedByEveryCandidateIsTheReason() {
        String reason = BlockedReason.of(null, List.of(passing(0, OBJECTION),
            passing(1, OBJECTION), passing(100, OBJECTION)), HEADLINE);

        assertThat(reason).isEqualTo("Why: it compiles and passes every acceptance test "
            + "(4p/0f/0e); verification failed it with this objection - " + OBJECTION
            + "\nAll 3 verified candidate(s) were failed by that same objection.\n\n");
    }

    @Test
    void aLongObjectionIsCutToTheLengthOfTheOtherReasons() {
        String reason = BlockedReason.of(null,
            List.of(passing(0, OBJECTION + " " + "x".repeat(400))), HEADLINE);

        assertThat(reason).contains(OBJECTION).endsWith("...\n\n").doesNotContain("All 1");
        assertThat(reason.length()).isLessThan(BlockedReason.MESSAGE_LIMIT + 120);
    }

    @Test
    void candidatesFailedByDifferentObjectionsAreNotSaidToShareOne() {
        String reason = BlockedReason.of(null, List.of(passing(0, OBJECTION),
            passing(1, "it changes a file another task holds")), HEADLINE);

        assertThat(reason).startsWith("Why: it compiles and passes every acceptance test")
            .contains(OBJECTION).doesNotContain("that same objection");
    }

    @Test
    void aHeadlineThatAlreadyQuotesTheObjectionIsNotRepeated() {
        assertThat(BlockedReason.of(null, List.of(passing(0, OBJECTION)),
            HEADLINE + "It was failed by this verification objection:\n\n> " + OBJECTION + "\n\n"))
            .isEmpty();
    }

    @Test
    void aFailingTestIsStillTheReasonBeforeAnyObjection() {
        VerificationReport failing = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(3, 1, 0, 0, List.of(new TestFailure("swarm.accept.T#finds",
                "expected: <1> but was: <0>", ""))), null, null, null, Duration.ZERO,
            "[verdict] NOT SURVIVED — 1 acceptance test failed\n", null);

        assertThat(BlockedReason.of(null, List.of(candidate(0, failing)), HEADLINE))
            .isEqualTo("Why: acceptance test swarm.accept.T#finds fails - "
                + "expected: <1> but was: <0>\n\n");
    }

    @Test
    void candidatesWithNoRecordedObjectionGiveNoReason() {
        VerificationReport clean = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(4, 0, 0, 0, List.of()), null, null, null, Duration.ZERO, "build ok",
            null);

        assertThat(BlockedReason.of(null, List.of(candidate(0, clean)), HEADLINE)).isEmpty();
    }

    private static CandidateSolution passing(int worker, String objection) {
        return candidate(worker, new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(4, 0, 0, 0, List.of()), null, null, null, Duration.ZERO,
            "build ok\n[verdict] NOT SURVIVED — " + objection + "\n", null));
    }

    private static CandidateSolution candidate(int worker, VerificationReport report) {
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), worker, "b", null,
            "diff", report, null, null, CandidateState.FAILED, null);
    }
}
