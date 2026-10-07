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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.runtime.TestRepairNeeded;
import com.swarmcoder.verify.Verdicts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 23, 01:59: a task's acceptance test never initialised its own {@code root} field, so
 * both candidates errored with an NPE INSIDE the test's own method before either one's code ever
 * ran — and the repair round that followed sent workers to fix code that was never broken, because
 * a worker may not edit the protected test that was actually at fault.
 *
 * <p>{@link SwarmEngineImpl#testRepairNeededFor} is the gate that stops that: null unless EVERY
 * candidate died on the SAME acceptance test failure and every one of those failures never left the
 * test's own class (see {@code AcceptanceFailureAttribution}). These pin it directly, the same way
 * {@link RepairPromptCarriesTheFailureTest} pins {@code failureEvidence} — hand-built candidates, no
 * swarm, no git.
 */
class TestRepairNeededTest {

    private static final String INSIDE_TEST_TRACE = """
        java.lang.NullPointerException: Cannot read field "books" because "this.root" is null
        \tat swarm.accept.BookManagementTest.removesBook(BookManagementTest.java:108)""";

    private static final String CANDIDATE_FAULT_TRACE = """
        java.lang.AssertionError: expected the book to be removed
        \tat com.demo.server.BookServiceServerImpl.removeBook(BookServiceServerImpl.java:22)
        \tat swarm.accept.BookManagementTest.removesBook(BookManagementTest.java:110)""";

    /**
     * Harness run 30, 13:04, exact shape: an assertion whose only surviving frame is the test's
     * own {@code assertEquals} call site — the candidates ran and were measured, they just did not
     * deliver. Same shape as {@code INSIDE_TEST_TRACE} (every frame is the test's own class), but
     * an assertion, not a crash.
     */
    private static final String ASSERTION_ONLY_FRAME_TRACE = """
        org.opentest4j.AssertionFailedError: expected: <5> but was: <0>
        \tat swarm.accept.BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)""";

    @Test
    void raisesTheFaultWhenEveryCandidateDiedInsideTheSameTest() {
        Task task = task();
        CandidateSolution a = candidateWithAcceptanceFailure(task, 0,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE));
        CandidateSolution b = candidateWithAcceptanceFailure(task, 1,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE));

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.taskId()).isEqualTo(task.id());
        assertThat(fault.testClass()).isEqualTo("swarm.accept.BookManagementTest");
        assertThat(fault.testMethod()).isEqualTo("removesBook");
        assertThat(fault.failureMessage()).contains("this.root");
        assertThat(fault.frames()).contains("BookManagementTest.java:108");
    }

    @Test
    void mixedCauseIsNotATestFaultAndGoesToTheOrdinaryRepairRound() {
        Task task = task();
        // One candidate never left the test's own class; its sibling's trace names the
        // candidate's own server code — a real bug, not the test's.
        CandidateSolution insideTest = candidateWithAcceptanceFailure(task, 0,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE));
        CandidateSolution ownCode = candidateWithAcceptanceFailure(task, 1,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "expected the book to be removed", CANDIDATE_FAULT_TRACE));

        TestRepairNeeded fault =
            SwarmEngineImpl.testRepairNeededFor(task, List.of(insideTest, ownCode));

        assertThat(fault).as("not every candidate died on the test's own bug").isNull();
    }

    @Test
    void nullWhenThisTaskAlreadyHadItsOneBoundedAttempt() {
        Task task = task();
        task.setTestRepairAttempted(true);
        CandidateSolution a = candidateWithAcceptanceFailure(task, 0,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE));
        CandidateSolution b = candidateWithAcceptanceFailure(task, 1,
            new TestFailure("swarm.accept.BookManagementTest#removesBook",
                "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE));

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b))).isNull();
    }

    /**
     * Harness run 30, 13:04: an ordinary assertion failure - {@code expected: <5> but was: <0>} -
     * is never declared the test's own bug, even when every candidate hit the exact same one and
     * its only frame is inside the test class. Since 2026-10-04 (harness run 79, owner decision)
     * it is not sent straight to the repair round either: the test is SUSPECT, and its author is
     * asked which side is wrong before any repair worker starts.
     */
    @Test
    void anAssertionFailureIdenticalAcrossEveryCandidateMakesTheTestSuspectNotBroken() {
        Task task = task();
        CandidateSolution a = candidateWithAcceptanceFailure(task, 0,
            new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
                "expected: <5> but was: <0>", ASSERTION_ONLY_FRAME_TRACE));
        CandidateSolution b = candidateWithAcceptanceFailure(task, 1,
            new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
                "expected: <5> but was: <0>", ASSERTION_ONLY_FRAME_TRACE));

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.suspect()).as("asked about, not declared broken").isTrue();
        assertThat(fault.testClass()).isEqualTo("swarm.accept.BookManagementTest");
        assertThat(fault.testMethod()).isEqualTo("assignsRatingToBook");
    }

    /** An assertion failing for one candidate but passing for another is an ordinary verdict split. */
    @Test
    void anAssertionFailingForOneCandidateAndPassingForAnotherIsNotATestFault() {
        Task task = task();
        CandidateSolution failed = candidateWithAcceptanceFailure(task, 0,
            new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
                "expected: <5> but was: <0>", ASSERTION_ONLY_FRAME_TRACE));
        VerificationReport greenReport = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(1, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
            null, null, null, Duration.ZERO, "", null);
        CandidateSolution survived = new CandidateSolution(UUID.randomUUID(), task.id(), 1,
            "swarm/w1", null, "diff", greenReport, null, null, CandidateState.SURVIVED, null);

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(failed, survived));

        assertThat(fault).as("one candidate had no acceptance failure at all").isNull();
    }

    @Test
    void nullWhenNoCandidateHasAnAcceptanceFailureAtAll() {
        Task task = task();
        VerificationReport clean = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(1, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
            null, null, null, Duration.ZERO, "", null);
        CandidateSolution survived = new CandidateSolution(UUID.randomUUID(), task.id(), 0,
            "swarm/w0", null, "diff", clean, null, null, CandidateState.SURVIVED, null);

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(survived))).isNull();
    }

    /**
     * Harness run 37, 2026-09-25, exact shape: the acceptance test called the TeaVM client's
     * {@code BookStore}, so every candidate's trace runs through its OWN BookStore and ends in a
     * native TeaVM method the JVM does not have. The old rule ("never left the test's own class")
     * is false for it, and run 37 spent a repair round of two more workers on a test no code could
     * ever pass. It goes back to the test author instead, saying why.
     */
    private static final String RUN_37_TRACE = """
        java.lang.UnsatisfiedLinkError: 'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'
        \tat org.teavm.jso.browser.Window.current(Native Method)
        \tat com.swarmcoder.demo.bookshelf.client.BookStore.<init>(BookStore.java:31)
        \tat com.swarmcoder.demo.bookshelf.client.BookStore.getInstance(BookStore.java:22)
        \tat swarm.accept.BookPersistenceTest.dataSurvivesBrowserRestart(BookPersistenceTest.java:26)""";

    @Test
    void run37EveryCandidateReachedBrowserOnlyCodeSoTheTestGoesBackToItsAuthor() {
        Task task = task();
        CandidateSolution a = candidateWithAcceptanceFailure(task, 0, run37Failure());
        CandidateSolution b = candidateWithAcceptanceFailure(task, 1, run37Failure());
        assertThat(a.verification().acceptance().failures().get(0).insideTestItself())
            .as("the trace ran through the candidate's own BookStore").isFalse();

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.reachedBrowserOnlyCode()).isTrue();
        assertThat(fault.testClass()).isEqualTo("swarm.accept.BookPersistenceTest");
        assertThat(fault.testMethod()).isEqualTo("dataSurvivesBrowserRestart");
        assertThat(fault.browserOnlyReason()).contains("can only run in a browser")
            .contains("UnsatisfiedLinkError");
        assertThat(fault.getMessage()).contains("can never pass on the JVM");
        assertThat(fault.frames()).contains("Window.current(Native Method)");
    }

    /** Run 37's third worker was killed NO_PROGRESS before it was verified: no verdict, no vote. */
    @Test
    void aCandidateKilledBeforeVerificationDoesNotHideTheBrowserOnlyFault() {
        Task task = task();
        CandidateSolution a = candidateWithAcceptanceFailure(task, 0, run37Failure());
        CandidateSolution killed = new CandidateSolution(UUID.randomUUID(), task.id(), 110,
            "swarm/w110", null, "", null, null, null, CandidateState.KILLED, null);

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, killed));

        assertThat(fault).isNotNull();
        assertThat(fault.reachedBrowserOnlyCode()).isTrue();
    }

    @Test
    void oneCandidateReachingTheBrowserAndAnotherFailingAnAssertionIsNotATestFault() {
        Task task = task();
        CandidateSolution browser = candidateWithAcceptanceFailure(task, 0, run37Failure());
        CandidateSolution measured = candidateWithAcceptanceFailure(task, 1,
            new TestFailure("swarm.accept.BookPersistenceTest#dataSurvivesBrowserRestart",
                "expected: <1> but was: <0>", """
                org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
                \tat swarm.accept.BookPersistenceTest.dataSurvivesBrowserRestart(BookPersistenceTest.java:44)"""));

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(browser, measured))).isNull();
    }

    @Test
    void onlyKilledCandidatesProveNothing() {
        Task task = task();
        CandidateSolution killed = new CandidateSolution(UUID.randomUUID(), task.id(), 0,
            "swarm/w0", null, "", null, null, null, CandidateState.KILLED, null);

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(killed))).isNull();
    }

    /**
     * Brownfield harness run 43, 2026-09-26, exact shape: both candidates wrote the right guard in
     * Document.java and failed verification on the same line of the acceptance test they may not
     * edit — "incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String".
     * A repair round of four workers followed, each finding the guard already there. That is the
     * test author's to fix, not the candidates'.
     */
    @Test
    void run43EveryCandidateFailedOnTheSameMisuseInTheTestSoItGoesToItsAuthor() {
        Task task = task();
        CandidateSolution a = candidateThatDoesNotCompile(task, 0, run43CompileFailure());
        CandidateSolution b = candidateThatDoesNotCompile(task, 1, run43CompileFailure());

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.doesNotCompile()).isTrue();
        assertThat(fault.reachedBrowserOnlyCode()).isFalse();
        assertThat(fault.testClass()).isEqualTo("swarm.accept.DocumentTest");
        assertThat(fault.testPath()).isEqualTo("src/test/java/swarm/accept/DocumentTest.java");
        assertThat(fault.compilerLines()).isEqualTo("src/test/java/swarm/accept/DocumentTest.java:16: "
            + "error: incompatible types: org.jsoup.parser.Parser cannot be converted to "
            + "java.lang.String");
        assertThat(fault.getMessage()).contains("does not compile for any candidate")
            .contains("DocumentTest.java:16");
    }

    /** A missing symbol the task was meant to deliver is the candidates' fault: ordinary repair. */
    @Test
    void everyCandidateMissingTheSameSymbolIsStillTheirsNotTheTests() {
        Task task = task();
        CompileFailure missing = new CompileFailure(CompileFailureCause.PRE_EXISTING,
            "src/test/java/swarm/accept/DocumentTest.java", 18,
            "cannot find symbol: method isEmptyXml in variable doc of type org.jsoup.nodes.Document",
            List.of("src/test/java/swarm/accept/DocumentTest.java"), true, true, true, "");
        CandidateSolution a = candidateThatDoesNotCompile(task, 0, missing);
        CandidateSolution b = candidateThatDoesNotCompile(task, 1, missing);

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b))).isNull();
    }

    @Test
    void differentCompileErrorsOrACandidatesOwnErrorAreNotATestFault() {
        Task task = task();
        CompileFailure otherLine = new CompileFailure(CompileFailureCause.PRE_EXISTING,
            "src/test/java/swarm/accept/DocumentTest.java", 21,
            "incompatible types: int cannot be converted to java.lang.String",
            List.of("src/test/java/swarm/accept/DocumentTest.java"), true, true, true, "");
        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(
            candidateThatDoesNotCompile(task, 0, run43CompileFailure()),
            candidateThatDoesNotCompile(task, 1, otherLine)))).isNull();

        CompileFailure own = new CompileFailure(CompileFailureCause.CANDIDATE,
            "src/main/java/org/jsoup/nodes/Document.java", 311,
            "incompatible types: int cannot be converted to boolean",
            List.of("src/main/java/org/jsoup/nodes/Document.java"), false, false, false, "");
        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(
            candidateThatDoesNotCompile(task, 0, own),
            candidateThatDoesNotCompile(task, 1, own)))).isNull();
    }

    @Test
    void theCompileFaultIsAlsoBoundedToOneAttempt() {
        Task task = task();
        task.setTestRepairAttempted(true);

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(
            candidateThatDoesNotCompile(task, 0, run43CompileFailure())))).isNull();
    }

    /**
     * Live run 57, 2026-10-01: the test called {@code TestServer.builder().storeDir(Path)}, a
     * method the library class does not have. Both candidates failed on that one line, and a repair
     * wave of workers followed, none of whom may edit the test. A missing member of a type the task
     * neither writes nor names in a contract is the test's fault, whatever the library is.
     */
    @Test
    void run57EveryCandidateMissingTheSameMethodOfALibraryTypeTheTaskDoesNotWriteGoesToTheAuthor() {
        Task task = fileOnlyTask();
        CompileFailure missing = libraryMethodFailure();
        CandidateSolution a = candidateThatDoesNotCompile(task, 0, missing);
        CandidateSolution b = candidateThatDoesNotCompile(task, 1, missing);

        TestRepairNeeded fault = SwarmEngineImpl.testRepairNeededFor(task, List.of(a, b));

        assertThat(fault).isNotNull();
        assertThat(fault.doesNotCompile()).isTrue();
        assertThat(fault.testClass()).isEqualTo("swarm.accept.EditContactTest");
        assertThat(fault.compilerLines())
            .contains("EditContactTest.java:24: error: cannot find symbol")
            .contains("symbol:   method storeDir(java.nio.file.Path)")
            .contains("location: class com.acme.testkit.TestServer.Builder");
    }

    @Test
    void aMissingMethodOnATypeTheTaskWritesStaysWithTheCandidates() {
        Task task = task(); // may write all of src/main, so it may add the method
        CompileFailure missing = libraryMethodFailure();

        assertThat(SwarmEngineImpl.testRepairNeededFor(task, List.of(
            candidateThatDoesNotCompile(task, 0, missing),
            candidateThatDoesNotCompile(task, 1, missing)))).isNull();
    }

    private static CompileFailure libraryMethodFailure() {
        return new CompileFailure(CompileFailureCause.PRE_EXISTING,
            "hambook-server/src/test/java/swarm/accept/EditContactTest.java", 24,
            "cannot find symbol: method storeDir(java.nio.file.Path) in class "
                + "com.acme.testkit.TestServer.Builder",
            List.of("hambook-server/src/test/java/swarm/accept/EditContactTest.java"), true, true,
            true, "every error is in a file this candidate did not add or change");
    }

    private static Task fileOnlyTask() {
        return new Task(UUID.randomUUID(), 1, "Implement logbook service",
            "Implement the logbook.", Set.of(
                "hambook-server/src/main/java/com/hambook/server/logbook/LogbookServiceImpl.java",
                "hambook-server/pom.xml"), Set.of(), List.of(), null,
            null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
    }

    private static CompileFailure run43CompileFailure() {
        return new CompileFailure(CompileFailureCause.PRE_EXISTING,
            "src/test/java/swarm/accept/DocumentTest.java", 16,
            "incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String",
            List.of("src/test/java/swarm/accept/DocumentTest.java"), true, true, true,
            "every error is in a file this candidate did not add or change");
    }

    private static CandidateSolution candidateThatDoesNotCompile(Task task, int workerIndex,
                                                                 CompileFailure failure) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false,
            null, null, null, null, Duration.ZERO, "", null);
        report.setCompileFailure(failure);
        return new CandidateSolution(UUID.randomUUID(), task.id(), workerIndex,
            "swarm/w" + workerIndex, null, "--- a/Document.java\n+++ b/Document.java\n",
            report, null, null, CandidateState.FAILED, null);
    }

    private static TestFailure run37Failure() {
        return new TestFailure("swarm.accept.BookPersistenceTest#dataSurvivesBrowserRestart",
            "'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'", RUN_37_TRACE);
    }

    /** The stamping this whole gate relies on: {@code Verdicts} sets the flag as a side effect. */
    @Test
    void theFlagVerdictsStampsIsWhatThisGateReads() {
        TestFailure failure = new TestFailure("swarm.accept.BookManagementTest#removesBook",
            "Cannot read field \"books\" because \"this.root\" is null", INSIDE_TEST_TRACE);
        TestResults acceptance = new TestResults(0, 0, 1, 0, List.of(failure))
            .withStageOutcome(TestStageOutcome.EXECUTED);

        assertThat(failure.insideTestItself()).as("not stamped until assessed").isFalse();
        Verdicts.summarizeTestFailures("acceptance", acceptance);
        assertThat(failure.insideTestItself()).isTrue();
    }

    private static CandidateSolution candidateWithAcceptanceFailure(Task task, int workerIndex,
                                                                    TestFailure failure) {
        TestResults acceptance = new TestResults(0, 0, 1, 0, List.of(failure))
            .withStageOutcome(TestStageOutcome.EXECUTED);
        // The stamping every real run relies on: Verdicts sets the flag as a side effect of
        // building the verdict sentence — see theFlagVerdictsStampsIsWhatThisGateReads above.
        Verdicts.summarizeTestFailures("acceptance", acceptance);
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            acceptance, null, null, null, Duration.ZERO, "", null);
        return new CandidateSolution(UUID.randomUUID(), task.id(), workerIndex,
            "swarm/w" + workerIndex, null, "--- a/BookService.java\n+++ b/BookService.java\n",
            report, null, null, CandidateState.FAILED, null);
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Add removeBook to BookService",
            "Implement removeBook on the server.", Set.of("src/main"), Set.of(), List.of(), null,
            null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.READY);
    }
}
