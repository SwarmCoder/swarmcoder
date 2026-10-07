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
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.runtime.TestRepairNeeded;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 79 (2026-10-04): both candidates of one task compiled and failed only
 * {@code LogbookTableTest#sortsByAnyColumn} with the same assertion over their own generated
 * ids. The test was wrong; a repair round of four workers ran to its ceiling and the task was
 * BLOCKED. Now that agreement is recognised and the test's author is asked first.
 */
class SameFailureForEveryCandidateTest {

    private static final String TEST = "swarm.accept.LogbookTableTest#sortsByAnyColumn";
    private static final String TRACE = """
        org.opentest4j.AssertionFailedError: QSL_STATUS ascending reorders by that column
        \tat com.hambook.server.LogbookServiceImpl.getLogbookSorted(LogbookServiceImpl.java:41)
        \tat swarm.accept.LogbookTableTest.sortsByAnyColumn(LogbookTableTest.java:88)""";

    /** The run's message, over ids this candidate's own run generated. */
    private static String reversed() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        return "QSL_STATUS ascending reorders by that column ==> expected: <[" + a + ", " + b
            + ", " + c + "]> but was: <[" + c + ", " + b + ", " + a + "]>";
    }

    @Test
    void run79TwoCandidatesFailingTheSameAssertionOverTheirOwnIdsMakeTheTestSuspect() {
        Task task = task();
        CandidateSolution a = failing(task, 0, true, null, new TestFailure(TEST, reversed(), TRACE));
        CandidateSolution b = failing(task, 1, true, null, new TestFailure(TEST, reversed(), TRACE));

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.suspect()).isTrue();
        assertThat(fault.testClass()).isEqualTo("swarm.accept.LogbookTableTest");
        assertThat(fault.testMethod()).isEqualTo("sortsByAnyColumn");
        assertThat(fault.suspectEvidence())
            .as("the author is shown what each candidate failed with and what each one wrote")
            .contains("2 candidates").contains(TEST)
            .contains("CANDIDATE 0 - its change").contains("CANDIDATE 1 - its change")
            .contains("change of worker 0").contains("change of worker 1")
            .contains("LogbookServiceImpl.getLogbookSorted");
    }

    @Test
    void aCandidateKilledBeforeItWasVerifiedIsNoEvidenceEitherWay() {
        Task task = task();
        CandidateSolution killed = new CandidateSolution(UUID.randomUUID(), task.id(), 2,
            "swarm/w2", null, "", null, null, null, CandidateState.KILLED, null);
        CandidateSolution a = failing(task, 0, true, null, new TestFailure(TEST, reversed(), TRACE));
        CandidateSolution b = failing(task, 1, true, null, new TestFailure(TEST, reversed(), TRACE));

        assertThat(SameFailureForEveryCandidate.find(task, List.of(killed, a, b))).isNotNull();
        assertThat(SameFailureForEveryCandidate.find(task, List.of(killed, a)))
            .as("one candidate agreeing with itself says nothing about the test").isNull();
    }

    @Test
    void candidatesThatFailDifferentlyAreTheirOwnFault() {
        Task task = task();
        CandidateSolution a = failing(task, 0, true, null, new TestFailure(TEST, reversed(), TRACE));

        assertThat(SameFailureForEveryCandidate.find(task, List.of(a, failing(task, 1, true, null,
            new TestFailure(TEST, "CALLSIGN ascending reorders by that column", TRACE)))))
            .as("another assertion of the same test").isNull();
        assertThat(SameFailureForEveryCandidate.find(task, List.of(a, failing(task, 1, true, null,
            new TestFailure(TEST, reversed(), TRACE),
            new TestFailure("swarm.accept.LogbookTableTest#listsNewestFirst", "wrong", TRACE)))))
            .as("one of them failed a second test as well").isNull();
        assertThat(SameFailureForEveryCandidate.find(task, List.of(a, failing(task, 1, false,
            null, new TestFailure(TEST, reversed(), TRACE)))))
            .as("one of them did not compile").isNull();
        assertThat(SameFailureForEveryCandidate.find(task, List.of(a, failing(task, 1, true,
            new TestResults(3, 1, 0, 0, List.of(new TestFailure("com.hambook.OldTest#works",
                "broke it", ""))), new TestFailure(TEST, reversed(), TRACE)))))
            .as("one of them also broke a test the project already had").isNull();
    }

    @Test
    void theOneSendBackPerTaskIsNotRepeated() {
        Task task = task();
        task.setTestRepairAttempted(true);
        CandidateSolution a = failing(task, 0, true, null, new TestFailure(TEST, reversed(), TRACE));
        CandidateSolution b = failing(task, 1, true, null, new TestFailure(TEST, reversed(), TRACE));

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b))).isNull();
    }

    @Test
    void valuesThatDifferFromRunToRunAreNotPartOfTheMessage() {
        assertThat(SameFailureForEveryCandidate.normalised(
            "expected: <2026-10-04T02:13:57.123Z> but was: <2026-10-04T02:13:58Z> for "
                + "Qso@1f2e3d4c id 5439e136-19c4-41f7-8508-a27073e7878e at 1759543200123"))
            .isEqualTo("expected: <<time>> but was: <<time>> for Qso@<id> id <id> at <n>");
        assertThat(SameFailureForEveryCandidate.normalised("expected: <5> but was: <0>"))
            .as("ordinary values are the assertion itself and stay")
            .isEqualTo("expected: <5> but was: <0>");
    }

    private static CandidateSolution failing(Task task, int worker, boolean compiles,
                                             TestResults existing, TestFailure... failures) {
        TestResults acceptance = new TestResults(1, failures.length, 0, 0, List.of(failures))
            .withStageOutcome(TestStageOutcome.EXECUTED);
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, compiles,
            acceptance, existing, null, null, Duration.ZERO, "", null);
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "swarm/w" + worker,
            null, "--- a/LogbookServiceImpl.java\n+++ b/LogbookServiceImpl.java\n+// change of "
                + "worker " + worker + "\n", report, null, null, CandidateState.FAILED, null);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Server-side logbook listing",
            "Implement getLogbookSorted on the server.", Set.of("src/main"), Set.of(), List.of(),
            null, null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
    }
}
