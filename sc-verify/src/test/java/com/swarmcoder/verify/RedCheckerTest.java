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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedCheckerTest {

    private static final String FAILING_XML = """
        <testsuite name="s" tests="1" failures="1" errors="0" skipped="0">
          <testcase classname="A" name="notYetImplemented">
            <failure message="expected feature missing">trace</failure>
          </testcase>
        </testsuite>
        """;

    private static final String PASSING_XML = """
        <testsuite name="s" tests="1" failures="0" errors="0" skipped="0">
          <testcase classname="A" name="alreadyPasses"/>
        </testsuite>
        """;

    private static VerifySpec spec() {
        return new VerifySpec("gradle", null, List.of("gradlew acceptanceTest"), null, null,
            null, null, 60, null);
    }

    private final RedChecker redChecker = new RedChecker();

    @Test
    void failingAcceptanceTestsAreAValidRedState() {
        FakeExecTarget target = new FakeExecTarget()
            .scriptProducing("gradlew acceptanceTest", 1, "build/test-results/r.xml", FAILING_XML);

        RedChecker.RedCheckResult result = redChecker.check(target, spec());

        assertThat(result.red()).isTrue();
        assertThat(result.results().failed()).isEqualTo(1);
    }

    @Test
    void passingAcceptanceTestsAreNotRed() {
        FakeExecTarget target = new FakeExecTarget()
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/r.xml", PASSING_XML);

        RedChecker.RedCheckResult result = redChecker.check(target, spec());

        assertThat(result.red()).isFalse();
        assertThat(result.note()).contains("already pass");
    }

    @Test
    void infrastructureFailureIsNotAValidRed() {
        FakeExecTarget target = new FakeExecTarget()
            .scriptOutput("gradlew acceptanceTest", 1, "Could not resolve dependencies; connection refused");

        RedChecker.RedCheckResult result = redChecker.check(target, spec());

        assertThat(result.red()).isFalse();
        assertThat(result.note()).contains("not a valid red state");
    }

    @Test
    void acceptanceTestCompileFailureIsAValidRedState() {
        // Tests reference multiply() which the task will add — they fail to compile pre-change.
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            "CalculatorTest.java:12: error: cannot find symbol\n  symbol: method multiply(int,int)");

        RedChecker.RedCheckResult result = redChecker.check(target, spec());

        assertThat(result.red()).as("compile-fail from a missing symbol is a valid TDD red").isTrue();
        assertThat(result.note()).contains("fail to compile");
    }

    private static final String EARLIER_FAILS_XML = """
        <testsuite name="s" tests="2" failures="1" errors="0" skipped="0">
          <testcase classname="swarm.accept.LogbookTest" name="earlierStory"><failure message="m">t</failure></testcase>
          <testcase classname="swarm.accept.LogbookTest" name="thisStory"/>
        </testsuite>
        """;

    private static final String BOTH_FAIL_XML = """
        <testsuite name="s" tests="2" failures="2" errors="0" skipped="0">
          <testcase classname="swarm.accept.LogbookTest" name="earlierStory"><failure message="m">t</failure></testcase>
          <testcase classname="swarm.accept.LogbookTest" name="thisStory"><failure message="m">t</failure></testcase>
        </testsuite>
        """;

    @Test
    void anEarlierStorysTestFailingIsNotTheRedThisStoryIsThereToShow() {
        FakeExecTarget target = new FakeExecTarget().scriptProducing("gradlew acceptanceTest", 1,
            "build/test-results/r.xml", EARLIER_FAILS_XML);

        RedChecker.RedCheckResult result = redChecker.check(target, spec(),
            java.util.Set.of("swarm.accept.LogbookTest#earlierStory"));

        assertThat(result.red()).isFalse();
        assertThat(result.note()).contains("earlier stories' acceptance tests fail")
            .contains("swarm.accept.LogbookTest#earlierStory");
    }

    @Test
    void failingNewTestsAreRedEvenWhenAnEarlierOneFailsToo() {
        FakeExecTarget target = new FakeExecTarget().scriptProducing("gradlew acceptanceTest", 1,
            "build/test-results/r.xml", BOTH_FAIL_XML);

        RedChecker.RedCheckResult result = redChecker.check(target, spec(),
            java.util.Set.of("swarm.accept.LogbookTest#earlierStory"));

        assertThat(result.red()).isTrue();
    }
}
