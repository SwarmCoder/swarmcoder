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
package com.swarmcoder.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one test stage reported: the counts, the failures with their ids, and — the part that makes
 * evidence possible — <b>which tests actually ran</b>.
 *
 * <p>The id lists exist because counts alone cannot answer the only question a requirement's
 * acceptance criterion asks: "did MY test run, and did it pass?" Without them the best any consumer
 * could do was "some tests ran and none of the failures look like mine, so mine passed" — which
 * silently certifies a test that was never written, never selected by the runner, or disabled.
 * See {@code CriterionEvidence}.
 *
 * <p>Ids are exactly what the runner emitted, in the form {@code <classname>#<name>} — including
 * the awkward spellings: {@code Outer$Inner} for a nested class, {@code method(int)[2]} for one
 * invocation of a parameterised test.
 */
public class TestResults {

    /**
     * Ceiling on ids kept per stage. A story's acceptance slice is a handful of tests; a whole
     * regression suite can be thousands, and these are persisted per candidate. Truncation is
     * recorded rather than silent, because a consumer that cannot see the ids must fall back to
     * "unknown", never to "passed".
     */
    public static final int MAX_IDS = 5000;

    private int passed;
    private int failed;
    private int errored;
    private int skipped;
    private List<TestFailure> failures;
    /** Ids of the tests that ran and passed. */
    private List<String> passedIds;
    /** Ids of the tests the runner skipped (disabled, unmet assumption) — they did NOT run. */
    private List<String> skippedIds;
    /** True when {@link #MAX_IDS} was hit, so the id lists are incomplete. */
    private boolean idsTruncated;
    /**
     * What the stage actually did. Null on any report written before this field existed, which
     * reads as {@link TestStageOutcome#INCONCLUSIVE} — an old report must never be mistaken for
     * one that positively established that zero tests ran.
     */
    private TestStageOutcome stageOutcome;

    public TestResults() {}

    public TestResults(int passed, int failed, int errored, int skipped, List<TestFailure> failures) {
        this(passed, failed, errored, skipped, failures, null, null, false);
    }

    public TestResults(int passed, int failed, int errored, int skipped, List<TestFailure> failures,
                       List<String> passedIds, List<String> skippedIds, boolean idsTruncated) {
        this.passed = passed;
        this.failed = failed;
        this.errored = errored;
        this.skipped = skipped;
        this.failures = failures;
        this.passedIds = passedIds;
        this.skippedIds = skippedIds;
        this.idsTruncated = idsTruncated;
    }

    public int passed() { return passed; }
    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }
    public int failed() { return failed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int errored() { return errored; }
    public int getErrored() { return errored; }
    public void setErrored(int errored) { this.errored = errored; }
    public int skipped() { return skipped; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public List<TestFailure> failures() { return failures; }
    public List<TestFailure> getFailures() { return failures; }
    public void setFailures(List<TestFailure> failures) { this.failures = failures; }

    /** Ids of tests that ran and passed; never null, so callers need no guard. */
    public List<String> passedIds() { return passedIds == null ? List.of() : passedIds; }
    public List<String> getPassedIds() { return passedIds; }
    public void setPassedIds(List<String> passedIds) { this.passedIds = passedIds; }

    /** Ids of tests the runner skipped; never null. */
    public List<String> skippedIds() { return skippedIds == null ? List.of() : skippedIds; }
    public List<String> getSkippedIds() { return skippedIds; }
    public void setSkippedIds(List<String> skippedIds) { this.skippedIds = skippedIds; }

    public boolean idsTruncated() { return idsTruncated; }
    public boolean isIdsTruncated() { return idsTruncated; }
    public void setIdsTruncated(boolean idsTruncated) { this.idsTruncated = idsTruncated; }

    /**
     * Null-safe: an absent outcome is {@link TestStageOutcome#INCONCLUSIVE}, never
     * {@link TestStageOutcome#EXECUTED}. Reports persisted before this field existed carry no
     * outcome, and reading one of those as "the stage ran and executed nothing" would fail a
     * candidate on the strength of a field that did not exist when it was written.
     */
    public TestStageOutcome stageOutcome() {
        return stageOutcome == null ? TestStageOutcome.INCONCLUSIVE : stageOutcome;
    }
    public TestStageOutcome getStageOutcome() { return stageOutcome; }
    public void setStageOutcome(TestStageOutcome stageOutcome) { this.stageOutcome = stageOutcome; }

    /**
     * How many tests the runner actually executed: passed + failed + errored. Skipped tests are
     * excluded on purpose — a disabled test did not run, and counting it here would let disabling
     * a test satisfy the "some test ran" bar.
     */
    public int executed() { return passed + failed + errored; }

    /**
     * True when this stage positively established that it ran and executed <b>no test at all</b>.
     *
     * <p>Only {@link TestStageOutcome#EXECUTED} can answer this. A stage that was skipped, was
     * never configured, or could not be read has established nothing, and returns false.
     */
    public boolean ranNothing() {
        return stageOutcome() == TestStageOutcome.EXECUTED && executed() == 0;
    }

    /** A copy of these results tagged with what the stage did. */
    public TestResults withStageOutcome(TestStageOutcome outcome) {
        TestResults copy = new TestResults(passed, failed, errored, skipped, failures,
            passedIds, skippedIds, idsTruncated);
        copy.setStageOutcome(outcome);
        return copy;
    }

    /**
     * Every id this stage reported — passed, failed, errored and skipped alike.
     *
     * <p>The point of the whole set is to distinguish "your test ran and was fine" from "nothing
     * here is your test". A report written before ids were recorded returns only its failure ids,
     * which is exactly why a consumer must treat "no match" as unknown rather than as a pass.
     */
    public List<String> allReportedIds() {
        List<String> ids = new ArrayList<>(passedIds());
        if (failures != null) {
            for (TestFailure f : failures) {
                if (f != null && f.testId() != null) {
                    ids.add(f.testId());
                }
            }
        }
        ids.addAll(skippedIds());
        return ids;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestResults that = (TestResults) o;
        return this.passed == that.passed && this.failed == that.failed
            && this.errored == that.errored && this.skipped == that.skipped
            && this.idsTruncated == that.idsTruncated
            && this.stageOutcome == that.stageOutcome
            && Objects.equals(this.failures, that.failures)
            && Objects.equals(this.passedIds, that.passedIds)
            && Objects.equals(this.skippedIds, that.skippedIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(passed, failed, errored, skipped, failures, passedIds, skippedIds,
            idsTruncated, stageOutcome);
    }
}
