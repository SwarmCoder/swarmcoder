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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one failed or errored acceptance test's message and stack — already filtered to project
 * frames by {@link JUnitXmlParser} — and says what a person can act on: a one-line identifying
 * summary (test, exception, message, first frame) safe for a hover card that only ever shows one
 * line, a fuller rendering with the kept frames for the judge and the repair prompt, and whether
 * the failure looks like a bug in the acceptance test itself rather than in the candidate's code.
 *
 * <p>Named after {@link CompileFailureAttribution}, which does the same job for a failed compile:
 * read the evidence the tool already produced and turn it into a sentence, rather than handing a
 * consumer bare counts and leaving it to guess.
 *
 * <p><b>Why "inside the test itself" is worth saying.</b> A repair worker cannot edit the
 * acceptance test — it is protected — so telling it "fix the cause" when the only frames in the
 * trace belong to the test class itself sends it chasing a fix it has no path to make. This class
 * is the one place that judgement is made, so the verdict, the judge and the repair prompt cannot
 * disagree about it.
 *
 * <p><b>An assertion failure is never "inside the test itself" (harness run 30, 13:04).</b> The
 * check above this one — "does the trace ever leave the test's own class" — was built for a test
 * whose OWN setup throws before any candidate code runs (an NPE from a null fixture, run 23/28).
 * It over-matched: {@code assignsRatingToBook} asserted a rating of 5 and got 0 — the candidates'
 * code ran, the test evaluated it, and it fell short. Every frame in a one-line assertion trace is
 * necessarily the test's own {@code assertEquals}/{@code assertThat} call site, so the same
 * all-frames-are-the-test-class check that correctly reads a crashed fixture as "the test's own
 * bug" would just as wrongly read every ordinary failed assertion as one too, and send the test
 * back to its author to be "repaired" — which the author can only do by weakening it until it
 * passes. An {@link org.opentest4j.AssertionFailedError} (JUnit 5, and AssertJ when opentest4j is
 * on the classpath), a bare {@link AssertionError} (JUnit 3/4, Hamcrest, AssertJ without
 * opentest4j), a JUnit {@code ComparisonFailure}, or an {@code org.opentest4j.MultipleFailuresError}
 * means the check ran and the candidate did not satisfy it — never that the test itself is broken —
 * whatever frames its trace carries.
 */
final class AcceptanceFailureAttribution {

    private AcceptanceFailureAttribution() {}

    /** {@code at fully.qualified.Class.method(File.java:41)} — the class is group 1. */
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w.$]+)\\.[\\w$<>]+\\(.*$");

    /**
     * Simple class names that always mean "an assertion ran and did not hold" — JUnit 3/4's
     * {@code ComparisonFailure} and {@code AssertionError} (also thrown by Hamcrest's
     * {@code MatcherAssert} and by AssertJ when opentest4j is not on the classpath), and JUnit 5's
     * own names, kept here in case a trace's header is ever truncated to just the simple name.
     */
    private static final Set<String> ASSERTION_SIMPLE_NAMES =
        Set.of("AssertionError", "AssertionFailedError", "ComparisonFailure", "MultipleFailuresError");

    /**
     * @param oneLine          test id, exception, message and first frame, guaranteed to carry no
     *                         newline — this is what a consumer that shows only the first line of
     *                         a longer reason (a hover card) will show in full
     * @param fullText         {@code oneLine} plus the kept stack frames, one per line, for a
     *                         consumer with room for a paragraph (the judge, the repair prompt)
     * @param insideTestItself true when the throwable is not an assertion type AND every surviving
     *                         project frame belongs to the failing test's own class — nothing in
     *                         the trace points at the candidate's code at all. Always false for an
     *                         assertion failure (see the class javadoc): that always means the
     *                         candidate failed the check, never that the test is broken.
     */
    record Detail(String oneLine, String fullText, boolean insideTestItself) {}

    static Detail describe(TestFailure failure) {
        String testId = failure.testId() == null ? "" : failure.testId();
        String testClass = testId.contains("#") ? testId.substring(0, testId.indexOf('#')) : testId;
        String trace = failure.truncatedTrace() == null ? "" : failure.truncatedTrace();

        List<String> frameClasses = new ArrayList<>();
        String firstFrame = null;
        for (String line : trace.split("\r?\n")) {
            Matcher m = FRAME.matcher(line);
            if (m.matches()) {
                frameClasses.add(m.group(1));
                if (firstFrame == null) {
                    firstFrame = line.strip();
                }
            }
        }
        String exceptionFullType = exceptionFullType(trace);
        boolean isAssertion = isAssertionType(exceptionFullType);
        // An assertion failure is NEVER "inside the test itself" (harness run 30) — see the class
        // javadoc. Only a non-assertion throwable that never left the failing test's own class
        // (an NPE from a broken fixture, run 23/28) reads as the test's own bug.
        boolean insideTest = !isAssertion && !frameClasses.isEmpty()
            && frameClasses.stream().allMatch(testClass::equals);

        String exceptionType = simpleName(exceptionFullType);
        String message = failure.message() == null ? "" : failure.message();

        StringBuilder oneLine = new StringBuilder(testId).append(" — ");
        if (isAssertion) {
            // Named for what it is, not what threw it: the candidate was measured against the
            // check and did not satisfy it. Saying "AssertionFailedError" here reads as a crash;
            // it is not one.
            oneLine.append("the candidate did not satisfy the check");
            if (!message.isBlank()) {
                oneLine.append(": ").append(message);
            }
        } else if (!exceptionType.isBlank()) {
            oneLine.append(exceptionType);
            if (!message.isBlank()) {
                oneLine.append(": ").append(message);
            }
        } else if (!message.isBlank()) {
            oneLine.append(message);
        } else {
            oneLine.append("no message");
        }
        if (firstFrame != null) {
            oneLine.append(", at ").append(stripLeadingAt(firstFrame));
        }
        if (insideTest) {
            oneLine.append(" — the error is inside the acceptance test, not the candidate's code");
        }

        StringBuilder full = new StringBuilder(oneLine);
        if (!trace.isBlank()) {
            full.append('\n').append(trace);
        }
        return new Detail(oneLine.toString(), full.toString(), insideTest);
    }

    /**
     * The exception's fully-qualified type from the trace's own header line — the line before the
     * first {@code at ...} frame, e.g. {@code org.opentest4j.AssertionFailedError: expected: <5>
     * but was: <0>} yields {@code org.opentest4j.AssertionFailedError}. Null-safe: an empty or
     * frame-only trace yields "".
     */
    static String exceptionFullType(String trace) {
        if (trace == null || trace.isBlank()) {
            return "";
        }
        String first = trace.split("\r?\n", 2)[0].strip();
        if (first.isEmpty() || FRAME.matcher(first).matches()) {
            return "";
        }
        int colon = first.indexOf(':');
        return colon > 0 ? first.substring(0, colon).strip() : first;
    }

    /** The simple name off a (possibly fully-qualified) type; "" stays "". */
    private static String simpleName(String type) {
        if (type.isEmpty()) {
            return "";
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /**
     * True for any exception type that means "a check ran and did not hold" rather than "something
     * crashed" — {@code org.opentest4j.*} (JUnit 5's {@code AssertionFailedError} and
     * {@code MultipleFailuresError}, and AssertJ's own failures once opentest4j is on the
     * classpath), a bare {@code AssertionError} (JUnit 3/4, Hamcrest, AssertJ without opentest4j),
     * and JUnit's {@code ComparisonFailure}. See the class javadoc for why this can never be read
     * as "inside the test itself".
     */
    static boolean isAssertionType(String fullyQualifiedType) {
        if (fullyQualifiedType.isEmpty()) {
            return false;
        }
        if (fullyQualifiedType.startsWith("org.opentest4j.")) {
            return true;
        }
        return ASSERTION_SIMPLE_NAMES.contains(simpleName(fullyQualifiedType));
    }

    private static String stripLeadingAt(String frameLine) {
        String s = frameLine.strip();
        return s.startsWith("at ") ? s.substring(3).strip() : s;
    }
}
