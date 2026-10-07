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
package com.swarmcoder.app;

import com.swarmcoder.verify.ExecResult;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.VerifySpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link the end-to-end harness was missing when harness run 30 stamped a story delivered on a
 * test that implemented the contract itself: <b>the story's acceptance test must be RED on the tree
 * before the delivered commit and GREEN on it.</b>
 *
 * <p>Every other link in that chain held. The tests were written where the build compiles them, the
 * stage executed a non-zero number of tests, candidates were produced, verified and judged, a
 * winner was selected and merged. None of it asked whether the test would have failed without the
 * delivered code — and it would not have.
 *
 * <p>Nothing here needs a repository, a model or a build: the two runs are driven through a fake
 * exec target that answers with JUnit XML, so the judgement can be proved in milliseconds. The live
 * harness pays for exactly one extra suite run, because the delivered side was already measured
 * when the winning candidate was verified.
 */
class DeliveredCodeIsWhatPassedCheckTest {

    private static final VerifySpec SPEC = new VerifySpec("maven",
        List.of("mvn -q compile"), List.of("mvn -q test -Dtest=BookManagementTest"),
        List.of(), List.of(), List.of(),
        new VerifySpec.TestReportsSpec(List.of("target/surefire-reports"), List.of()),
        600, null);

    @Test
    void redBeforeAndGreenOnTheDeliveredCommitIsWhatAWorkingRunLooksLike() {
        DeliveredCodeIsWhatPassedCheck.Outcome outcome = DeliveredCodeIsWhatPassedCheck.measure(
            tree(1, 1, 0), tree(1, 0, 0), SPEC);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.observed())
            .contains("before the delivered commit: 2 test(s) executed, 1 failing — RED")
            .contains("on the delivered commit: 1 test(s) executed, 0 failing — GREEN")
            .contains("the acceptance suite ran and everything in it passed");
    }

    @Test
    void aTestThatIsGreenInBothStatesIsTheTautologyAndBreaksTheLink() {
        // This is harness run 30 exactly: green before, green after, delivered on nothing.
        DeliveredCodeIsWhatPassedCheck.Outcome outcome = DeliveredCodeIsWhatPassedCheck.measure(
            tree(1, 0, 0), tree(1, 0, 0), SPEC);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.observed())
            .contains("before the delivered commit: 1 test(s) executed, 0 failing — NOT RED")
            .contains("The acceptance test passes WITHOUT the delivered code")
            .contains("it measures itself");
    }

    @Test
    void aTestStillFailingOnTheDeliveredCommitBreaksTheLinkWithItsOwnSentence() {
        DeliveredCodeIsWhatPassedCheck.Outcome outcome = DeliveredCodeIsWhatPassedCheck.measure(
            tree(1, 1, 0), tree(1, 0, 1), SPEC);


        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.observed())
            .contains("still not green on the delivered commit")
            .contains("stamped delivered on a check that does not hold");
    }

    @Test
    void aStageThatSelectedNothingOnTheDeliveredCommitIsNotGreen() {
        // The defect this whole harness exists for: a suite that runs zero tests reports zero
        // failures and reads exactly like a green one.
        DeliveredCodeIsWhatPassedCheck.Outcome outcome = DeliveredCodeIsWhatPassedCheck.measure(
            tree(1, 1, 0), emptyStage(), SPEC);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.observed()).contains("0 test(s) executed").contains("NOT GREEN");
    }

    @Test
    void oneRunReportsWhatItMeasuredWhicheverWayItWent() {
        DeliveredCodeIsWhatPassedCheck.Side side =
            DeliveredCodeIsWhatPassedCheck.measure(tree(2, 1, 0), SPEC);

        assertThat(side.executed()).isEqualTo(3);
        assertThat(side.failing()).isEqualTo(1);
        assertThat(side.red()).isTrue();
        assertThat(side.green()).isFalse();
        assertThat(side.note()).contains("red state confirmed");
    }

    /** A tree whose acceptance command writes one JUnit XML report with these counts. */
    private static ExecTarget tree(int passed, int failed, int errored) {
        return new XmlEmittingTarget(surefireXml(passed, failed, errored));
    }

    /** A tree whose acceptance command exits 0 and writes no report at all. */
    private static ExecTarget emptyStage() {
        return new XmlEmittingTarget(null);
    }

    private static String surefireXml(int passed, int failed, int errored) {
        StringBuilder xml = new StringBuilder("<testsuite name=\"swarm.accept.BookManagementTest\" "
            + "tests=\"" + (passed + failed + errored) + "\" failures=\"" + failed + "\" errors=\""
            + errored + "\" skipped=\"0\">");
        for (int i = 0; i < passed; i++) {
            xml.append("<testcase classname=\"swarm.accept.BookManagementTest\" name=\"passes")
                .append(i).append("\"/>");
        }
        for (int i = 0; i < failed; i++) {
            xml.append("<testcase classname=\"swarm.accept.BookManagementTest\" name=\"assignsRating")
                .append(i).append("\"><failure message=\"expected: &lt;5&gt; but was: &lt;0&gt;\">")
                .append("org.opentest4j.AssertionFailedError</failure></testcase>");
        }
        for (int i = 0; i < errored; i++) {
            xml.append("<testcase classname=\"swarm.accept.BookManagementTest\" name=\"errors")
                .append(i).append("\"><error message=\"boom\">java.lang.RuntimeException</error>")
                .append("</testcase>");
        }
        return xml.append("</testsuite>").toString();
    }

    /**
     * An exec target that runs no process: every command "succeeds", and the report directory holds
     * whatever XML the test handed it — which is all {@code RedChecker} reads to decide.
     */
    private static final class XmlEmittingTarget implements ExecTarget {

        private final Map<String, String> files;
        private final List<String> ran = new ArrayList<>();

        XmlEmittingTarget(String xml) {
            this.files = xml == null ? Map.of()
                : Map.of("target/surefire-reports/BookManagementTest.xml", xml);
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) {
            ran.add(command);
            return new ExecResult(0, "ran " + command, false, Duration.ofMillis(1));
        }

        @Override
        public String readFile(String relativePath, int maxBytes) {
            return files.get(relativePath);
        }

        @Override
        public List<String> listFiles(String relativeDir, String suffix) {
            return files.keySet().stream()
                .filter(p -> p.startsWith(relativeDir) && p.endsWith(suffix)).sorted().toList();
        }

        @Override
        public void deleteDir(String relativePath) {
            // the reports are handed in, not produced, so there is nothing to clear
        }

        @Override
        public ServiceHandle startService(String command) {
            throw new UnsupportedOperationException("no services in this fake");
        }
    }
}
