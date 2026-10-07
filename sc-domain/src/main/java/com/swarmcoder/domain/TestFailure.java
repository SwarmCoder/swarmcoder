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

import java.util.Objects;

public class TestFailure {
    private String testId;
    private String message;
    private String truncatedTrace;
    /**
     * True when every surviving project frame in {@link #truncatedTrace} belongs to this failing
     * test's own class — nothing in the trace points at the candidate's code at all. Computed by
     * {@code AcceptanceFailureAttribution.describe} and stamped here (append-only: false on any
     * failure recorded before this field existed, which is the honest reading — an old report never
     * positively established that the failure was the test's own fault).
     *
     * <p>This is the flag {@code SwarmEngineImpl} reads to tell "every candidate of this task died
     * on a bug in the acceptance test itself" from an ordinary code failure, and to send the test
     * back to its author (a {@code TestRepairNeeded}) instead of spending a repair round on
     * candidates that were never broken.
     */
    private boolean insideTestItself;

    public TestFailure() {}

    public TestFailure(String testId, String message, String truncatedTrace) {
        this.testId = testId;
        this.message = message;
        this.truncatedTrace = truncatedTrace;
    }

    public String testId() { return testId; }
    public String getTestId() { return testId; }
    public void setTestId(String testId) { this.testId = testId; }
    public String message() { return message; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String truncatedTrace() { return truncatedTrace; }
    public String getTruncatedTrace() { return truncatedTrace; }
    public void setTruncatedTrace(String truncatedTrace) { this.truncatedTrace = truncatedTrace; }
    public boolean insideTestItself() { return insideTestItself; }
    public boolean getInsideTestItself() { return insideTestItself; }
    public void setInsideTestItself(boolean insideTestItself) { this.insideTestItself = insideTestItself; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestFailure that = (TestFailure) o;
        return Objects.equals(this.testId, that.testId) && Objects.equals(this.message, that.message) && Objects.equals(this.truncatedTrace, that.truncatedTrace) && this.insideTestItself == that.insideTestItself;
    }

    @Override
    public int hashCode() {
        return Objects.hash(testId, message, truncatedTrace, insideTestItself);
    }
}

