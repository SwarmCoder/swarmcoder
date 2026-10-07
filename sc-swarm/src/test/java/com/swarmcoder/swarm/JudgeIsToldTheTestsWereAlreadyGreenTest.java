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
import com.swarmcoder.domain.ChecksAlreadyProved;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a task's acceptance tests were already green before its own wave started, the judge is the
 * only thing left that can separate its candidates — so it has to be TOLD, and the one part of the
 * question that is mechanical has to be enforced in code.
 *
 * <p><b>Where this comes from.</b> 2026-09-03, story "Assign and display a book rating". The two
 * waves in front of the user-interface task delivered the data model and the server-side update, so
 * the acceptance test for "a rating can be assigned to a book and is displayed alongside the book's
 * details" — a JUnit test, which proves that through the service — was green before a single worker
 * of the third task ran. Every candidate of that task therefore passes every test, including one
 * that changes nothing. A green acceptance run, which is normally the strongest evidence this system
 * produces, is worth exactly nothing here, and a judge that is not told so will read it as proof.
 */
class JudgeIsToldTheTestsWereAlreadyGreenTest {

    /** A judge that awards the top of the scale to everything, so only the ceilings can show. */
    private static final String TOP_MARK =
        "{\"score\": 1.0, \"rationale\": \"implements the task\"}";

    private static Task uiTask(boolean alreadyProved) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("Update client UI to display and assign book ratings");
        task.setInstructions("Render each book's rating and let the reader set it.");
        if (alreadyProved) {
            task.setChecksAlreadyProved(new ChecksAlreadyProved(Instant.now(), "abc1234",
                List.of("swarm.accept.BookRatingTest#assignsRatingToBook"),
                List.of("R4:C1 — A rating can be assigned to a book and is displayed alongside "
                    + "the book's details"),
                1, true, "The browser stage is what proves the screen half."));
        }
        return task;
    }

    private static CandidateSolution candidate(String diff) {
        VerificationReport green = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(1, 0, 0, 0, List.of(),
                List.of("swarm.accept.BookRatingTest#assignsRatingToBook"), List.of(), false),
            null, null, null, Duration.ZERO, "", null);
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/w0",
            null, diff, green, new ClusterId("hash", 1), null, CandidateState.SURVIVED, null);
    }

    private static final String REAL_WORK =
        "--- a/src/main/client/BookList.java\n+++ b/src/main/client/BookList.java\n"
        + "+class BookList {\n+    String renderStars(int rating) { return \"*\".repeat(rating); }\n+}\n";

    /** One comment line and nothing else — the candidate a green test run cannot tell from a real one. */
    private static final String COMMENT_ONLY =
        "--- a/src/main/client/BookList.java\n+++ b/src/main/client/BookList.java\n"
        + "+// TODO: show the rating here one day\n";

    /** The defect: a green test that was green before the candidate, read as evidence for it. */
    @Test
    void theBriefSaysTheTestsWereGreenBeforeThisCandidateAndWhatToScoreInstead() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            judge.judge(candidate(REAL_WORK), uiTask(true));

            assertThat(fake.requests).hasSize(1);
            assertThat(fake.requests.get(0))
                .contains("THE TESTS WERE GREEN BEFORE THIS CANDIDATE EXISTED")
                .contains("swarm.accept.BookRatingTest#assignsRatingToBook")
                .contains("A rating can be assigned to a book")
                .as("and it is told what to score instead: the diff against the instructions")
                .contains("does it deliver what the task's instructions above actually ask for");
        }
    }

    /** A task in the normal case is judged by exactly the brief it was judged by before. */
    @Test
    void anOrdinaryTaskGetsNoSuchLine() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(TOP_MARK))) {
            new JudgeClient(new VllmClient(fake.baseUrl(), "", "fake-judge", true))
                .judge(candidate(REAL_WORK), uiTask(false));

            assertThat(fake.requests.get(0)).doesNotContain("THE TESTS WERE GREEN BEFORE");
        }
    }

    /**
     * The half of the fix that no wording can talk away: a candidate whose diff carries no code is
     * capped, even though it compiles and every test is green.
     */
    @Test
    void aCandidateWhoseDiffIsOnlyACommentIsCappedLikeAnUnverifiedOne() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(TOP_MARK))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true));

            CandidateSolution judged = judge.judge(candidate(COMMENT_ONLY), uiTask(true));

            assertThat(judged.judge().score())
                .as("it passed everything, and it did nothing")
                .isEqualTo(JudgeClient.DELIVERED_NOTHING_CEILING);
            assertThat(judged.judge().rationale())
                .as("and the operator reading the Gallery is told why the number is capped")
                .contains("SCORE LIMITED")
                .contains("delivered nothing");
        }
    }

    /** The candidate that did the work is not held back by any of it. */
    @Test
    void aCandidateThatWroteRealCodeKeepsTheJudgesOwnNumber() throws Exception {
        try (FakeVllm fake = new FakeVllm(conversation -> FakeVllm.Reply.text(TOP_MARK))) {
            CandidateSolution judged = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true))
                .judge(candidate(REAL_WORK), uiTask(true));

            assertThat(judged.judge().score()).isEqualTo(1.0);
            assertThat(judged.judge().rationale()).doesNotContain("SCORE LIMITED");
        }
    }

    /** What counts as "changed no code", stated as cases rather than left to the pattern. */
    @Test
    void whatCountsAsHavingDeliveredNothing() {
        assertThat(JudgeClient.deliveredNothing(null)).isTrue();
        assertThat(JudgeClient.deliveredNothing("")).isTrue();
        assertThat(JudgeClient.deliveredNothing(
            "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1,2 @@\n+\n"))
            .as("a blank added line is not work").isTrue();
        assertThat(JudgeClient.deliveredNothing("--- a/A.java\n+++ b/A.java\n+// a note\n"))
            .as("nor is a comment").isTrue();
        assertThat(JudgeClient.deliveredNothing("--- a/A.java\n+++ b/A.java\n+int x = 1;\n"))
            .as("one real line is").isFalse();
        assertThat(JudgeClient.deliveredNothing("--- a/A.java\n+++ b/A.java\n-int x = 1;\n"))
            .as("and so is a deletion").isFalse();
        assertThat(JudgeClient.deliveredNothing("--- a/s.py\n+++ b/s.py\n+# rate(book, 5)\n"))
            .as("'#' opens a statement in Python, so it is never read as a comment here")
            .isFalse();
    }
}
