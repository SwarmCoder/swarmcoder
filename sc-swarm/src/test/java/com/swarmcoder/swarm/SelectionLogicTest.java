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
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selection must deliver a candidate the evidence supports, and must say so when there isn't one.
 *
 * <p>The numbers in {@link #realCompileAllJavaFilesCase()} are not invented. They were read out of
 * the operator's own store at {@code ~/.swarmcoder/store} on 2026-08-31: the task "Compile all Java
 * files" produced three candidates, workers 8, 4 and 9, every one judged 0.0, behavioural clusters
 * of 2, 2 and 1, and not one of them carrying a verification report. Worker 8 was SELECTED and
 * delivered, and its own judge rationale says the diff does not compile anything. The rationales
 * below are the ones in that store, quoted.
 *
 * <p>No model is called anywhere in this file. Selection never talks to one.
 */
class SelectionLogicTest {

    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** The three candidates of the real "Compile all Java files" task, in dispatch order. */
    private static List<CandidateSolution> realCompileAllJavaFilesCase() {
        return List.of(
            candidate(8, 0.0, 2, null,
                "The diff only adds library JAR files but does not include any compilation command "
                    + "or build configuration to compile the listed Java files to the bin directory."),
            candidate(4, 0.0, 2, null,
                "Only adds JAR dependencies but does not include the javac command to compile "
                    + "source files and output to bin directory."),
            candidate(9, 0.0, 1, null,
                "The diff only adds dependency JARs but does not include any compilation command "
                    + "or script to compile the Java files, so it fails to implement the task."));
    }

    @Test
    @DisplayName("the real three-candidate case delivers nothing and says why")
    void zeroScoredCandidatesAreNeverDelivered() {
        SelectionLogic.Selection selection =
            new SelectionLogic().select(realCompileAllJavaFilesCase());

        // This is the assertion that fails against the old SelectionLogic, which returned worker 8.
        assertNull(selection.winner(),
            "a candidate the judge scored 0.0 — 'does not do the task at all' on the judge's own "
                + "scale — must never be delivered");
        assertTrue(selection.reason().contains("No candidate can be delivered"),
            "the operator must be told plainly, not left with a silent block: " + selection.reason());
        assertTrue(selection.reason().contains("javac"),
            "the reason must carry the judge's own words about each candidate: " + selection.reason());
    }

    @Test
    @DisplayName("a verified candidate beats an unverified one at the same judge score")
    void verificationOutranksAnEqualJudgeScore() {
        CandidateSolution unverified = candidate(1, 0.6, 3, null, "looks right");
        CandidateSolution verified = candidate(2, 0.6, 1, greenReport(), "looks right");

        CandidateSolution winner = new SelectionLogic()
            .selectWinner(List.of(unverified, verified));

        assertNotNull(winner);
        assertEquals(2, winner.workerIndex(),
            "the verified candidate must win even though the unverified one has the bigger cluster");
        assertEquals(CandidateState.SELECTED, winner.state());
    }

    @Test
    @DisplayName("a verified candidate beats an unverified one that the judge scored higher")
    void verificationOutranksAHigherJudgeScore() {
        CandidateSolution unverified = candidate(1, 0.9, 1, null, "unproven but impressive");
        CandidateSolution verified = candidate(2, 0.5, 1, greenReport(), "proven and adequate");

        CandidateSolution winner = new SelectionLogic()
            .selectWinner(List.of(unverified, verified));

        assertEquals(2, winner.workerIndex(),
            "proof outranks opinion: a measurement beats a higher number with nothing behind it");
    }

    /**
     * The harness run 14 defect, reproduced directly: two candidates for "Implement BookService on
     * the server" both broke the rule that persistence goes through EclipseStore, scored 0.70 and
     * 0.40, and the higher-scoring rule-breaker was delivered. A candidate that kept the rule must
     * now win even at a lower score, and the reason must say so in words, not just in a number.
     */
    @Test
    @DisplayName("a candidate that kept every stated rule beats one that broke a rule, even at a lower score")
    void aRuleKeeperBeatsARuleBreakerEvenAtALowerScore() {
        CandidateSolution ruleBreaker = candidateWithBrokenRule(1, 0.9, 1, greenReport(),
            "works but does not use EclipseStore",
            "Persistence uses EclipseStore through zerozstack-store-eclipsestore: uses an "
                + "in-memory HashMap instead");
        CandidateSolution ruleKeeper = candidate(2, 0.6, 1, greenReport(), "uses EclipseStore");

        SelectionLogic.Selection selection =
            new SelectionLogic().select(List.of(ruleBreaker, ruleKeeper));

        assertEquals(2, selection.winner().workerIndex(),
            "the candidate that kept the rule must win even though the rule-breaker scored higher");
        assertTrue(selection.reason().contains("kept every stated rule")
                || selection.reason().contains("keeping every stated rule"),
            "the reason must say the win was about keeping the rule: " + selection.reason());
    }

    @Test
    @DisplayName("a tie on score and cluster is broken by the smaller diff, not by dispatch order")
    void tieIsBrokenByDiffSizeNotWorkerIndex() {
        CandidateSolution bloated = candidateWithDiff(3, 0.5, 2, null, bigDiff());
        CandidateSolution tight = candidateWithDiff(7, 0.5, 2, null, smallDiff());

        assertEquals(7, new SelectionLogic().selectWinner(List.of(bloated, tight)).workerIndex());
        assertEquals(7, new SelectionLogic().selectWinner(List.of(tight, bloated)).workerIndex(),
            "the same field in the other dispatch order must give the same winner");
    }

    @Test
    @DisplayName("candidates equal on every measure select the same winner whatever order they arrive in")
    void fullyTiedFieldIsStillDeterministic() {
        CandidateSolution a = candidate(4, 0.5, 2, null, "same");
        CandidateSolution b = candidate(8, 0.5, 2, null, "same");

        UUID first = new SelectionLogic().selectWinner(List.of(a, b)).id();
        UUID second = new SelectionLogic().selectWinner(List.of(b, a)).id();

        assertEquals(first, second, "selection must not depend on which worker was dispatched first");
    }

    @Test
    @DisplayName("a candidate that was never clustered does not blow selection up")
    void nullClusterIsNotDereferenced() {
        CandidateSolution noCluster = candidate(1, 0.7, -1, null, "never clustered");
        CandidateSolution clustered = candidate(2, 0.7, 2, null, "clustered");

        CandidateSolution winner = new SelectionLogic()
            .selectWinner(List.of(noCluster, clustered));

        assertEquals(2, winner.workerIndex(),
            "the clustered candidate wins; the unclustered one counts as a cluster of nobody");
    }

    @Test
    @DisplayName("an empty field delivers nothing rather than throwing")
    void emptyFieldHasNoWinner() {
        assertNull(new SelectionLogic().selectWinner(List.of()));
        assertNull(new SelectionLogic().selectWinner(null));
    }

    /** A verification report that {@link com.swarmcoder.verify.Verdicts} calls survived. */
    private static VerificationReport greenReport() {
        return new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(4, 0, 0, 4, List.of()), new TestResults(9, 0, 0, 9, List.of()),
            null, null, Duration.ofSeconds(3), "", null);
    }

    private static CandidateSolution candidate(int workerIndex, double score, int clusterSize,
                                               VerificationReport report, String rationale) {
        return candidateWithDiff(workerIndex, score, clusterSize, report, rationale, "");
    }

    private static CandidateSolution candidateWithDiff(int workerIndex, double score,
                                                       int clusterSize, VerificationReport report,
                                                       String diff) {
        return candidateWithDiff(workerIndex, score, clusterSize, report, "same", diff);
    }

    /** A cluster size below zero means "never clustered" — the candidate carries a null cluster. */
    private static CandidateSolution candidateWithDiff(int workerIndex, double score,
                                                       int clusterSize, VerificationReport report,
                                                       String rationale, String diff) {
        return new CandidateSolution(
            UUID.nameUUIDFromBytes(("worker-" + workerIndex).getBytes()), TASK, workerIndex,
            "sc/cand-" + workerIndex, null, diff, report,
            clusterSize < 0 ? null : new ClusterId("hash-" + clusterSize, clusterSize),
            new JudgeScore(score, rationale, "scripted-judge"), CandidateState.SURVIVED, null);
    }

    /** A candidate the judge scored, but also found breaking one stated rule, quoted verbatim. */
    private static CandidateSolution candidateWithBrokenRule(int workerIndex, double score,
                                                              int clusterSize,
                                                              VerificationReport report,
                                                              String rationale, String brokenRule) {
        CandidateSolution base = candidate(workerIndex, score, clusterSize, report, rationale);
        base.judge().setBrokenRules(List.of(brokenRule));
        return base;
    }

    private static String smallDiff() {
        return """
            --- a/A.java
            +++ b/A.java
            @@
            -old
            +new
            """;
    }

    private static String bigDiff() {
        StringBuilder sb = new StringBuilder("--- a/A.java\n+++ b/A.java\n@@\n");
        for (int i = 0; i < 200; i++) {
            sb.append("+line ").append(i).append('\n');
        }
        return sb.toString();
    }
}
