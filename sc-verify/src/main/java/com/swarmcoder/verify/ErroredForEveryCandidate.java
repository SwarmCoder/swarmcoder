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
import com.swarmcoder.domain.TestResults;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An acceptance test that errors, for every candidate alike, before any candidate's code runs
 * (section 86): a broken test, not a red one.
 *
 * <p><b>Why (live supervised build, 2026-10-12).</b> A test went back to its author, who
 * rewrote how it starts. From then on every test method errored in the test's own set-up. The
 * correction was taken because it "failed" on the tree before any candidate, the four
 * candidates were verified again and each read {@code compiles=true}, no test passed, no test
 * failed, and a repair round of four workers was started on a file none of them may change.
 *
 * <p><b>The rule.</b> Read from the verification reports of one task's candidates, all against
 * the same test. It holds when there is at least one report and in EVERY report
 * <ul>
 *   <li>no acceptance test passed and none failed an assertion, and at least one errored;</li>
 *   <li>every recorded error is one no candidate's code took part in: not an assertion, no
 *       frame of its stack in a package of the project's own code, and no type of the project
 *       named in it - the same reading {@link BrokenAtStartup} makes of one failure;</li>
 *   <li>the first error is the same test with the same exception type as in the first
 *       report.</li>
 * </ul>
 * Messages are not compared: a port or a temporary folder in one differs from run to run.
 *
 * <p>Wider than {@link BrokenAtStartup#find} in one way and narrower in another. That one reads
 * ONE run on a tree with nothing built, where an error thrown from the test's own lines is
 * what a healthy test looks like (a stub returned null), so it wants the error thrown from
 * outside the test class. Here the candidates' code is in the tree, so an error thrown from the
 * test's own set-up counts too - but only when every candidate, each written independently,
 * shows the same one.
 *
 * <p>Pure functions over what verification recorded.
 */
public final class ErroredForEveryCandidate {

    private ErroredForEveryCandidate() {
    }

    /**
     * @param testId        the first errored test, as the runner named it
     * @param exceptionType the exception's fully-qualified type; "" when the report has none
     * @param message       the exception's message as the runner reported it; may be blank
     * @param failureText   the test, the message and the kept stack, for the test's author
     * @param candidates    how many candidates showed it
     * @param errored       how many tests errored in the first report
     */
    public record Finding(String testId, String exceptionType, String message, String failureText,
                          int candidates, int errored) {

        /** One sentence for a log line, a question and a stop. */
        public String reason() {
            return "all " + candidates + " candidate(s) compiled and none of their code ran: "
                + errored + " acceptance test(s) errored, none passed and none failed. The first, "
                + testId + ", threw " + (exceptionType.isBlank() ? "an exception" : exceptionType)
                + (message == null || message.isBlank() ? "" : ": " + oneLine(message))
                + ", with no frame of the project's own code on its stack";
        }
    }

    /**
     * The finding, or null when the reports do not all show it.
     *
     * @param acceptance      each candidate's acceptance results, in any order; a null entry
     *                        (a candidate with no acceptance stage) means no finding
     * @param projectPackages the packages of the project's own main code and of the code the
     *                        plan writes; see {@link BrokenAtStartup#projectPackages}
     */
    public static Finding find(List<TestResults> acceptance, Collection<String> projectPackages) {
        if (acceptance == null || acceptance.isEmpty()) {
            return null;
        }
        if (!sameError(acceptance)) {
            return null;
        }
        Set<String> project = projectPackages == null ? Set.of()
            : new LinkedHashSet<>(projectPackages);
        for (TestResults results : acceptance) {
            for (TestFailure failure : results.failures()) {
                if (failure == null || failure.testId() == null
                        || !BrokenAtStartup.startupFailure(failure, project)) {
                    return null;
                }
            }
        }
        TestFailure first = acceptance.get(0).failures().get(0);
        String firstType = exceptionType(first);
        String trace = first.truncatedTrace() == null ? "" : first.truncatedTrace().strip();
        String message = first.message() == null ? "" : first.message().strip();
        return new Finding(first.testId(), firstType, message,
            first.testId() + (message.isEmpty() ? "" : " - " + message)
                + (trace.isEmpty() ? "" : "\n" + trace),
            acceptance.size(), acceptance.get(0).errored());
    }

    /**
     * True when every report shows the same error and nothing else: no test passed, none failed
     * an assertion, at least one errored, and the first error is the same test with the same
     * exception type in each. Says nothing about whose code threw it.
     */
    public static boolean sameError(List<TestResults> acceptance) {
        if (acceptance == null || acceptance.isEmpty()) {
            return false;
        }
        TestFailure first = null;
        for (TestResults results : acceptance) {
            if (results == null || results.passed() > 0 || results.failed() > 0
                    || results.errored() == 0 || results.failures() == null
                    || results.failures().isEmpty() || results.failures().get(0) == null
                    || results.failures().get(0).testId() == null) {
                return false;
            }
            TestFailure mine = results.failures().get(0);
            if (first == null) {
                first = mine;
            } else if (!Objects.equals(first.testId(), mine.testId())
                    || !Objects.equals(exceptionType(first), exceptionType(mine))) {
                return false;
            }
        }
        return true;
    }

    /** The exception's fully-qualified type, read from the first line of the kept stack; "" when none. */
    public static String exceptionType(TestFailure failure) {
        String type = failure == null ? null : AcceptanceFailureAttribution.exceptionFullType(
            failure.truncatedTrace() == null ? "" : failure.truncatedTrace());
        return type == null ? "" : type;
    }

    private static String oneLine(String message) {
        String line = message.strip().replaceAll("\\s+", " ");
        return line.length() <= 300 ? line : line.substring(0, 300) + "...";
    }
}
