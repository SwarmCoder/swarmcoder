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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.runtime.TestRepairNeeded;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every first candidate of a task compiled and failed the same acceptance test method(s) with the
 * same assertion: the test is suspect (owner decision, 2026-10-04).
 *
 * <p>Harness run 79: both candidates of "Server-side logbook listing" failed only
 * {@code swarm.accept.LogbookTableTest#sortsByAnyColumn} with "QSL_STATUS ascending reorders by
 * that column", each returning the exact reverse of what the test expected. The test was wrong -
 * it assumed five contacts had different QSL states, the existing code gives every new contact
 * the same one, and both candidates implemented the tie-break the design states. A repair round
 * of four workers ran 30 minutes to its ceiling and the task was BLOCKED.
 *
 * <p>Two workers that wrote their code independently and are told the same thing by the same
 * test are evidence about the test as much as about themselves. This does not say the test is
 * wrong: it says the test author is asked, once, before any repair worker is started. An
 * assertion failing identically for everybody used to go straight to the repair round (harness
 * run 30); that still happens when the author answers that the test is right.
 */
final class SameFailureForEveryCandidate {

    /** How many candidates must agree before their agreement means anything. */
    static final int MIN_CANDIDATES = 2;

    /** How much of one candidate's change the test author is shown. */
    static final int DIFF_CHARS = 6_000;

    private static final Pattern UUID_VALUE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern IDENTITY_HASH = Pattern.compile("@[0-9a-fA-F]{5,}");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{12,}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{9,}\\b");

    private SameFailureForEveryCandidate() {}

    /**
     * A failure message with the values that differ from run to run - ids, timestamps, object
     * identities - replaced by a placeholder, so two candidates that failed the same assertion
     * over their own generated data read the same.
     */
    static String normalised(String message) {
        if (message == null) {
            return "";
        }
        String out = UUID_VALUE.matcher(message).replaceAll("<id>");
        out = TIMESTAMP.matcher(out).replaceAll("<time>");
        out = IDENTITY_HASH.matcher(out).replaceAll("@<id>");
        out = LONG_NUMBER.matcher(out).replaceAll("<n>");
        out = LONG_HEX.matcher(out).replaceAll("<id>");
        return out.strip();
    }

    /**
     * The fault, or null: at least {@link #MIN_CANDIDATES} candidates produced a verdict, every
     * one of them compiled, failed nothing but acceptance tests, and failed the same test ids
     * with the same normalised messages. A candidate with no verdict (killed before it was
     * verified) is no evidence either way and is not counted.
     */
    static TestRepairNeeded find(Task task, List<CandidateSolution> verified) {
        if (task == null || verified == null) {
            return null;
        }
        TreeMap<String, String> shared = null;
        List<CandidateSolution> agreeing = new ArrayList<>();
        for (CandidateSolution candidate : verified) {
            if (candidate == null || candidate.verification() == null) {
                continue;
            }
            TreeMap<String, String> failures = failuresOf(candidate.verification());
            if (failures == null) {
                return null; // did not compile, failed elsewhere too, or failed nothing readable
            }
            if (shared == null) {
                shared = failures;
            } else if (!shared.equals(failures)) {
                return null;
            }
            agreeing.add(candidate);
        }
        if (shared == null || agreeing.size() < MIN_CANDIDATES) {
            return null;
        }
        TestFailure first = agreeing.get(0).verification().acceptance().failures().get(0);
        String testId = first.testId() == null ? "" : first.testId();
        int hash = testId.indexOf('#');
        return TestRepairNeeded.suspect(task.id(),
            hash >= 0 ? testId.substring(0, hash) : testId,
            hash >= 0 ? testId.substring(hash + 1) : null,
            first.message(), agreeing.size(), evidence(shared, agreeing));
    }

    /** Test id to normalised message; null when this verdict is not "compiled, failed only these". */
    private static TreeMap<String, String> failuresOf(VerificationReport verification) {
        if (!verification.compiles()) {
            return null;
        }
        TestResults existing = verification.existing();
        if (existing != null && (existing.failed() > 0 || existing.errored() > 0)) {
            return null;
        }
        TestResults acceptance = verification.acceptance();
        if (acceptance == null || acceptance.failures() == null || acceptance.failures().isEmpty()) {
            return null;
        }
        TreeMap<String, String> failures = new TreeMap<>();
        for (TestFailure failure : acceptance.failures()) {
            if (failure == null || failure.testId() == null || failure.testId().isBlank()) {
                return null;
            }
            failures.put(failure.testId(), normalised(failure.message()));
        }
        return failures;
    }

    private static String evidence(TreeMap<String, String> shared,
                                   List<CandidateSolution> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append(candidates.size()).append(" candidates, written independently of each other, "
            + "all compiled and all failed exactly these test method(s) with the same "
            + "assertion:\n");
        shared.forEach((test, message) -> sb.append("- ").append(test).append(": ")
            .append(message).append('\n'));
        for (CandidateSolution candidate : candidates) {
            sb.append("\nCANDIDATE ").append(candidate.workerIndex()).append(" - what it failed "
                + "with, in full:\n");
            for (TestFailure failure : candidate.verification().acceptance().failures()) {
                sb.append("  ").append(failure.testId()).append(": ")
                    .append(failure.message() == null ? "" : failure.message()).append('\n');
                if (failure.truncatedTrace() != null && !failure.truncatedTrace().isBlank()) {
                    sb.append(failure.truncatedTrace().strip().indent(4));
                }
            }
            String diff = candidate.diffUnified();
            sb.append("CANDIDATE ").append(candidate.workerIndex()).append(" - its change:\n");
            if (diff == null || diff.isBlank()) {
                sb.append("  (no diff was recorded)\n");
            } else if (diff.length() > DIFF_CHARS) {
                sb.append(diff, 0, DIFF_CHARS).append("\n... (").append(diff.length() - DIFF_CHARS)
                    .append(" more characters of this change are not shown)\n");
            } else {
                sb.append(diff).append(diff.endsWith("\n") ? "" : "\n");
            }
        }
        return sb.toString();
    }
}
