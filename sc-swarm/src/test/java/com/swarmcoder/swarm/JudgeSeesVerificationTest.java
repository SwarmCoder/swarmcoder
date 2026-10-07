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
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.TestStageOutcome;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge must know whether anything was ever built or run, and must not be able to award full
 * marks to a candidate nobody checked.
 *
 * <p><b>Where this test comes from.</b> Every number and reply string below was read out of the
 * operator's real EclipseStore, not invented. It holds 776 archived candidates; exactly ONE of them
 * carries a {@link VerificationReport}, and all fifteen that were ever judged carry none — so the
 * old {@code judgeBrief}, which wrote the verification block only when a report existed, put no
 * verification information in front of the judge on any judging call that has ever happened. Two of
 * those fifteen came back at a flat 1.0 with the rationale below, and one of them was SELECTED.
 *
 * <p>These tests fail against that behaviour twice over: the brief contained no verification
 * section at all, and the score came back 1.0.
 */
class JudgeSeesVerificationTest {

    /** Verbatim from the store: the reply that scored an unverified candidate a flat 1.0. */
    private static final String FLAT_TOP_MARK =
        "{\"score\": 1.0, \"rationale\": \"The change correctly implements the greeting with a "
        + "random number as required by the task.\"}";

    private static Task task() {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setTitle("Define book domain model");
        t.setInstructions("Define the Book record with title, author and publication year.");
        return t;
    }

    private static CandidateSolution candidate(VerificationReport report) {
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/w0",
            null, "--- a/Book.java\n+++ b/Book.java\n+class Book {}\n", report,
            new ClusterId("hash", 1), null, CandidateState.SURVIVED, null);
    }

    private static VerificationReport compiling(boolean compiles) {
        return new VerificationReport(UUID.randomUUID(), true, compiles, null, null, null, null,
            Duration.ZERO, "", null);
    }

    /**
     * The defect itself. A candidate nothing was ever compiled or tested against must reach the
     * judge carrying that fact in words, and must not come back at the top of the scale.
     */
    @Test
    void aCandidateNothingWasEverRunAgainstIsToldSoAndCannotScoreFullMarks() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(candidate(null), task());

            assertThat(fake.requests).hasSize(1);
            assertThat(fake.requests.get(0))
                .as("the judge must be told nothing was built or run")
                .contains("Verification: NOT RUN")
                .contains("no test was executed");
            assertThat(judged.judge().score())
                .as("full marks are reserved for something that was actually proven")
                .isLessThanOrEqualTo(JudgeClient.UNVERIFIED_CEILING)
                .isEqualTo(1.0 * JudgeClient.UNVERIFIED_CEILING);
            assertThat(judged.judge().rationale())
                .as("the operator reading the Gallery must see why the number is capped")
                .contains("SCORE LIMITED")
                .contains("nothing was ever compiled or tested");
        }
    }

    /** A candidate the build could not compile is held further down still. */
    @Test
    void aCandidateThatDoesNotCompileIsHeldBelowTheCompileCeiling() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(candidate(compiling(false)), task());

            assertThat(fake.requests.get(0)).contains("THE BUILD COULD NOT COMPILE THIS CANDIDATE");
            assertThat(judged.judge().score())
                .isLessThanOrEqualTo(JudgeClient.DOES_NOT_COMPILE_CEILING);
            assertThat(judged.judge().rationale()).contains("does not compile");
        }
    }

    private static VerificationReport withCompileFailure(CompileFailureCause cause, String file,
            boolean acceptanceTest, boolean testFile) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false, null,
            null, null, null, Duration.ZERO, "", null);
        report.setCompileFailure(new CompileFailure(cause, file, 9,
            "refers to swarm.Book, which does not exist", List.of(file), testFile, acceptanceTest,
            true, null));
        return report;
    }

    /**
     * The defect this branch was opened for. A candidate whose own code was fine, in a tree that
     * was already broken by an acceptance test some earlier run committed, must not be told "this
     * does not compile" and must not be held to the same ceiling as a candidate that actually wrote
     * broken code — nothing was measured about THIS candidate's code, which is the same situation as
     * a candidate nobody ever built, not the same as one caught failing.
     */
    @Test
    void aTreeBrokenBeforeTheCandidateStartedIsNotBlamedOnTheCandidate() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));
            VerificationReport report = withCompileFailure(CompileFailureCause.PRE_EXISTING,
                "swarm/accept/BookTest.java", true, true);

            CandidateSolution judged = judge.judge(candidate(report), task());

            assertThat(fake.requests.get(0))
                .as("the brief must carry the attributed sentence, not the bare flag")
                .doesNotContain("compiles=false")
                .contains("the tree does not compile before this candidate's change")
                .contains("swarm/accept/BookTest.java:9")
                .contains("This is an acceptance test committed before this candidate ran, not "
                    + "this candidate's work");
            assertThat(judged.judge().score())
                .as("nothing was measured about this candidate's own code, so it gets the "
                    + "unverified ceiling, not the does-not-compile one")
                .isEqualTo(1.0 * JudgeClient.UNVERIFIED_CEILING)
                .isGreaterThan(JudgeClient.DOES_NOT_COMPILE_CEILING);
            assertThat(judged.judge().rationale())
                .contains("SCORE LIMITED")
                .contains("already broken before this candidate's change");
        }
    }

    /** A failure in the candidate's OWN file stays at the strict ceiling, and the sentence names it. */
    @Test
    void aFailureInTheCandidatesOwnFileStaysAtTheStrictCeiling() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));
            VerificationReport report = withCompileFailure(CompileFailureCause.CANDIDATE,
                "swarm/Book.java", false, false);

            CandidateSolution judged = judge.judge(candidate(report), task());

            assertThat(fake.requests.get(0))
                .contains("the candidate does not compile")
                .contains("swarm/Book.java:9");
            assertThat(judged.judge().score())
                .isEqualTo(1.0 * JudgeClient.DOES_NOT_COMPILE_CEILING);
            assertThat(judged.judge().rationale()).contains("SCORE LIMITED").contains("does not compile");
        }
    }

    /** The ceiling must not punish a candidate that WAS proven — 1.0 stays reachable. */
    @Test
    void aCandidateThatCompiledKeepsTheJudgesOwnScore() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(candidate(compiling(true)), task());

            assertThat(fake.requests.get(0))
                .contains("Verification: COMPILED: yes (the whole build, offline, in the sandbox).");
            assertThat(judged.judge().score()).isEqualTo(1.0);
            assertThat(judged.judge().rationale()).doesNotContain("SCORE LIMITED");
        }
    }

    /**
     * The cap must not flatten the swarm. The store's rationales do separate candidates well when
     * the judge is given a diff — 0.0, 0.1, 0.2, 0.3, 0.35, 0.4, 0.8 and 1.0 all appear — and that
     * ordering is the only thing selection has to work with when nothing was verified. Scaling
     * keeps it; truncating with {@code min} would have destroyed it.
     */
    @Test
    void cappingKeepsUnverifiedCandidatesInOrder() {
        double good = 0.9 * JudgeClient.verificationCeiling(null);
        double weak = 0.3 * JudgeClient.verificationCeiling(null);

        assertThat(good).isGreaterThan(weak);
        assertThat(good).isLessThanOrEqualTo(JudgeClient.UNVERIFIED_CEILING);
    }

    /** The unverified cap must stay distinguishable from the "the judge never answered" score. */
    @Test
    void theUnverifiedCapIsNotTheSameNumberAsAFailedJudging() {
        assertThat(JudgeClient.UNVERIFIED_CEILING).isNotEqualTo(0.5);
    }

    /** An unanchored 0-to-1 scale is what drifts to the top; the anchors have to be in the prompt. */
    @Test
    void theScaleTheJudgeIsGivenHasAnchorsOnIt() {
        assertThat(JudgeClient.systemPrompt(false))
            .contains("1.0 is reserved")
            .contains("BUILT AND TESTED")
            .contains("never score above the band");
        assertThat(JudgeClient.systemPrompt(true)).contains("1.0 is reserved");
    }

    // --- the first defect this branch fixes: harness run 13 misreading "no checks claimed" as
    // --- "not built or tested" -------------------------------------------------------------

    private static Task enablerTask() {
        return task(); // task() above declares no criteria and no criterionIds — claims zero checks
    }

    private static Task taskClaiming(int checkCount) {
        Task t = task();
        Set<UUID> ids = new java.util.HashSet<>();
        for (int i = 0; i < checkCount; i++) {
            ids.add(UUID.randomUUID());
        }
        t.setCriterionIds(ids);
        return t;
    }

    /**
     * The defect itself, reproduced verbatim from harness run 13: {@code compiles=true},
     * {@code survived=true}, {@code claimedChecks=0} — an enabler that did exactly what it was
     * supposed to — and the OLD verification line read as "not actually built or tested". The new
     * line must be unambiguous: compiling is stated as proof, and the absence of acceptance tests
     * is stated as CORRECT for this task, not as missing evidence.
     */
    @Test
    void anEnablerCandidateProducesTheExactUnambiguousLine() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            judge.judge(candidate(compiling(true)), enablerTask());

            assertThat(fake.requests.get(0))
                .as("the enabler verification line must be exact and unambiguous")
                .contains("Verification: COMPILED: yes (the whole build, offline, in the "
                    + "sandbox). ACCEPTANCE TESTS: this task claims none, so none were due and "
                    + "none ran — that is correct for an enabler, do not treat it as unproven. "
                    + "BROWSER: not configured for this project.");
        }
    }

    /** Same assertion directly against the unit under test, with no LLM round trip involved. */
    @Test
    void verificationLineForAnEnablerMatchesExactly() {
        VerificationReport report = compiling(true);

        String line = JudgeClient.verificationLine(report, enablerTask());

        assertThat(line).isEqualTo("Verification: COMPILED: yes (the whole build, offline, in "
            + "the sandbox). ACCEPTANCE TESTS: this task claims none, so none were due and none "
            + "ran — that is correct for an enabler, do not treat it as unproven. BROWSER: not "
            + "configured for this project.");
    }

    /** A task that DOES claim checks gets the counted line, not the enabler line. */
    @Test
    void verificationLineForThreeClaimedChecksAllPassingMatchesExactly() {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(3, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
            null, null, null, Duration.ZERO, "", null);

        String line = JudgeClient.verificationLine(report, taskClaiming(3));

        assertThat(line).isEqualTo("Verification: COMPILED: yes (the whole build, offline, in "
            + "the sandbox). ACCEPTANCE TESTS: 3 due, 3 ran, 3 passed. BROWSER: not configured "
            + "for this project.");
    }

    /**
     * The judge's verification block must name a failed acceptance test the same way the verdict
     * does — {@code Verdicts.summarizeTestFailures} is the one place that renders it, so the two
     * cannot come out worded differently for the same failure.
     */
    @Test
    void verificationLineForAFailedAcceptanceTestNamesItLikeTheVerdictDoes() {
        String trace = """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
            \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestResults acceptance = new TestResults(0, 0, 1, 0,
            List.of(new com.swarmcoder.domain.TestFailure(
                "swarm.accept.BookRatingTest#assignsRatingToBook",
                "Cannot invoke \"String.length()\" because \"title\" is null", trace)))
            .withStageOutcome(TestStageOutcome.EXECUTED);
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            acceptance, null, null, null, Duration.ZERO, "", null);

        String line = JudgeClient.verificationLine(report, taskClaiming(1));

        assertThat(line)
            .contains("ACCEPTANCE TESTS: 1 due, 1 ran, 0 passed.")
            .contains(com.swarmcoder.verify.Verdicts.summarizeTestFailures("acceptance", acceptance)
                .oneLineSentence());
    }

    /** The same counted line, reached through the real judge call. */
    @Test
    void aCandidateWithThreeClaimedChecksAllPassingProducesTheCountedLine() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(FLAT_TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));
            VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
                new TestResults(3, 0, 0, 0, List.of()).withStageOutcome(TestStageOutcome.EXECUTED),
                null, null, null, Duration.ZERO, "", null);

            judge.judge(candidate(report), taskClaiming(3));

            assertThat(fake.requests.get(0)).contains("ACCEPTANCE TESTS: 3 due, 3 ran, 3 passed.");
        }
    }

    /**
     * Telling the candidate's own brief is not enough on its own (this class exists because it
     * was not) — the judge's STANDING instructions must also say an enabler is proved by
     * compiling, so the rule holds even against a verification line the judge skims past.
     */
    @Test
    void theJudgesRulesBlockContainsTheEnablerSentence() {
        assertThat(JudgeClient.systemPrompt(false))
            .contains("A task that claims no acceptance checks (an enabler) has its survival "
                + "proved by COMPILING, not by tests");
        assertThat(JudgeClient.systemPrompt(true))
            .contains("proved by COMPILING, not by tests");
    }
}
