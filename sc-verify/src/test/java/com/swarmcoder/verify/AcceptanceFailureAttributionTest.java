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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceFailureAttributionTest {

    @Test
    void namesTheExceptionTheMessageAndTheFirstCandidateFrame() {
        String trace = """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
            \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.oneLine())
            .contains("swarm.accept.BookRatingTest#assignsRatingToBook")
            .contains("NullPointerException")
            .contains("Cannot invoke")
            .contains("BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)")
            .doesNotContain("\n");
        assertThat(detail.fullText()).contains(detail.oneLine()).contains(trace);
        assertThat(detail.insideTestItself()).isFalse();
    }

    @Test
    void whenEveryKeptFrameIsInTheTestsOwnClassTheDiagnosisSaysSo() {
        // The candidate's code is never called: the test's own @BeforeEach helper throws before
        // anything of the candidate's runs. Blaming "the cause" would send a repair worker looking
        // for a bug that is not in anything it can fix.
        String trace = """
            java.lang.NullPointerException: fixture not initialised
            \tat swarm.accept.BookRatingTest.loadFixture(BookRatingTest.java:15)
            \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)""";
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "fixture not initialised", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isTrue();
        assertThat(detail.oneLine())
            .contains("the error is inside the acceptance test, not the candidate's code");
    }

    @Test
    void aTraceWithNoFramesAtAllIsNeverReadAsInsideTheTest() {
        TestFailure failure = new TestFailure("swarm.accept.BookRatingTest#assignsRatingToBook",
            "something went wrong", "");

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isFalse();
        assertThat(detail.oneLine()).contains("something went wrong");
    }

    /**
     * Harness run 30, 13:04, exact shape: {@code assignsRatingToBook} asserted a rating of 5, got 0.
     * Every frame in the trace is the test's own {@code assertEquals} call site — the same shape
     * that {@link #whenEveryKeptFrameIsInTheTestsOwnClassTheDiagnosisSaysSo} correctly reads as
     * "inside the test" for a crashing fixture — but an assertion is never that: the candidates'
     * code ran, the check evaluated it, and it fell short.
     */
    @Test
    void anAssertionFailureIsNeverInsideTheTestItselfEvenWhenEveryFrameIsTheTests() {
        String trace = """
            org.opentest4j.AssertionFailedError: expected: <5> but was: <0>
            \tat swarm.accept.BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)""";
        TestFailure failure = new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
            "expected: <5> but was: <0>", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isFalse();
        assertThat(detail.oneLine())
            .contains("the candidate did not satisfy the check")
            .contains("expected: <5> but was: <0>")
            .contains("BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)")
            .doesNotContain("the error is inside the acceptance test");
    }

    /** A bare {@code java.lang.AssertionError} (JUnit 3/4, Hamcrest, AssertJ without opentest4j). */
    @Test
    void aBareAssertionErrorIsAlsoNeverInsideTheTestItself() {
        String trace = """
            java.lang.AssertionError: expected [5] but found [0]
            \tat swarm.accept.BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)""";
        TestFailure failure = new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
            "expected [5] but found [0]", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isFalse();
        assertThat(detail.oneLine()).contains("the candidate did not satisfy the check");
    }

    /**
     * An {@code org.opentest4j.MultipleFailuresError} (JUnit 5's {@code assertAll}) with every
     * frame in the test's own class is likewise never "inside the test itself".
     */
    @Test
    void aMultipleFailuresErrorIsNeverInsideTheTestItself() {
        String trace = """
            org.opentest4j.MultipleFailuresError: Multiple Failures (1 failure)
            \tat swarm.accept.BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)""";
        TestFailure failure = new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
            "Multiple Failures (1 failure)", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isFalse();
    }

    /** An NPE thrown from a project-main frame the test called — a real candidate bug, not the test's. */
    @Test
    void anNpeThrownFromProjectMainCodeIsNotInsideTheTestItself() {
        String trace = """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
            \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
            \tat swarm.accept.BookManagementTest.assignsRatingToBook(BookManagementTest.java:29)""";
        TestFailure failure = new TestFailure("swarm.accept.BookManagementTest#assignsRatingToBook",
            "Cannot invoke \"String.length()\" because \"title\" is null", trace);

        AcceptanceFailureAttribution.Detail detail = AcceptanceFailureAttribution.describe(failure);

        assertThat(detail.insideTestItself()).isFalse();
    }
}
