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
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.verify.Verdicts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 22, 00:50: two candidates compiled, their one claimed acceptance test threw, and
 * the repair wave they seeded got only {@code "FAILED <testId>: <message>"} — no exception type,
 * no stack, and no instruction. Both repair workers spent their turns on a test error they were
 * never shown. {@link SwarmEngineImpl#failureEvidence} is what {@code dispatchRepair} bakes into
 * the repair wave's shared prefix (via {@code SwarmDispatcher.buildBundle}'s REPAIR CONTEXT
 * block); these pin what it now carries.
 */
class RepairPromptCarriesTheFailureTest {

    private static final String TRACE = """
        java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
        \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
        \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";

    private static CandidateSolution candidateWithAcceptanceFailure(TestFailure failure) {
        TestResults acceptance = new TestResults(0, 0, 1, 0, List.of(failure))
            .withStageOutcome(TestStageOutcome.EXECUTED);
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            acceptance, null, null, null, Duration.ZERO, "", null);
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/w0", null,
            "--- a/Book.java\n+++ b/Book.java\n", report, new ClusterId("hash", 1), null,
            CandidateState.FAILED, null);
    }

    @Test
    void namesTheExceptionAndTheStackAndTellsTheWorkerToFixTheCauseNotTheTest() {
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", TRACE);

        String evidence = SwarmEngineImpl.failureEvidence(candidateWithAcceptanceFailure(failure));

        assertThat(evidence)
            .contains("swarm.accept.BookRatingTest#assignsRatingToBook")
            .contains("NullPointerException")
            .contains("BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)")
            .contains("The acceptance test threw this; fix the cause, not the test.")
            .doesNotContain("looks like a bug in the acceptance test itself");
    }

    @Test
    void wordsTheFailureExactlyLikeTheVerdictAndTheJudgeDo() {
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", TRACE);
        TestResults acceptance = new TestResults(0, 0, 1, 0, List.of(failure))
            .withStageOutcome(TestStageOutcome.EXECUTED);

        String evidence = SwarmEngineImpl.failureEvidence(candidateWithAcceptanceFailure(failure));
        Verdicts.FailureSummary summary = Verdicts.summarizeTestFailures("acceptance", acceptance);

        assertThat(evidence).contains(summary.fullText());
    }

    @Test
    void whenTheFailureNeverLeavesTheTestsOwnClassTheRepairWorkerIsToldNotToChaseIt() {
        String traceInsideTest = """
            java.lang.NullPointerException: fixture not initialised
            \tat swarm.accept.BookRatingTest.loadFixture(BookRatingTest.java:15)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "fixture not initialised", traceInsideTest);

        String evidence = SwarmEngineImpl.failureEvidence(candidateWithAcceptanceFailure(failure));

        assertThat(evidence)
            .contains("the error is inside the acceptance test, not the candidate's code")
            .contains("You cannot edit that test (it is protected)");
    }

    /** {@code testFailureEvidence} is "" for a clean stage, so a compiling-but-untested candidate's
     *  evidence carries no stray "FAILED" line. */
    @Test
    void aCleanStageAddsNothing() {
        assertThat(SwarmEngineImpl.testFailureEvidence("acceptance", null)).isEmpty();
        TestResults clean = new TestResults(2, 0, 0, 0, List.of())
            .withStageOutcome(TestStageOutcome.EXECUTED);
        assertThat(SwarmEngineImpl.testFailureEvidence("acceptance", clean)).isEmpty();
    }

    /** The REPAIR CONTEXT block a repair worker actually receives carries this same text. */
    @Test
    void thePromptBundleWrapsTheEvidenceAsRepairContext() {
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", TRACE);
        String evidence = SwarmEngineImpl.failureEvidence(candidateWithAcceptanceFailure(failure));

        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("Client module: BookShelfApp UI with rating input");
        task.setInstructions("Add a rating input to the book detail view.");

        PromptBundle bundle = SwarmDispatcher.buildBundle(task, evidence, null, null, List.of());

        assertThat(bundle.sharedText())
            .contains("REPAIR CONTEXT")
            .contains("swarm.accept.BookRatingTest#assignsRatingToBook")
            .contains("NullPointerException")
            .contains("The acceptance test threw this; fix the cause, not the test.");
    }
}
