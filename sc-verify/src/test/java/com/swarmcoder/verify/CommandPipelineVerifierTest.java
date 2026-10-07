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

import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPipelineVerifierTest {

    private static final String PASSING_XML = """
        <testsuite name="s" tests="2" failures="0" errors="0" skipped="0">
          <testcase classname="A" name="t1"/>
          <testcase classname="A" name="t2"/>
        </testsuite>
        """;

    private static final String FAILING_XML = """
        <testsuite name="s" tests="2" failures="1" errors="0" skipped="0">
          <testcase classname="A" name="t1"/>
          <testcase classname="A" name="t2">
            <failure message="boom">trace</failure>
          </testcase>
        </testsuite>
        """;

    private final CommandPipelineVerifier verifier = new CommandPipelineVerifier();

    private static VerifySpec gradleSpec() {
        return new VerifySpec("gradle",
            List.of("gradlew compileJava"),
            List.of("gradlew acceptanceTest"),
            List.of("gradlew test"),
            List.of("gradlew checkstyleMain"),
            null, null, 60, null);
    }

    @Test
    void greenPathSurvives() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/acc.xml", PASSING_XML)
            .scriptProducing("gradlew test", 0, "build/test-results/reg.xml", PASSING_XML)
            .script("gradlew checkstyleMain", 0);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(report.compiles()).isTrue();
        assertThat(report.acceptance().passed()).isEqualTo(2);
        assertThat(report.existing().passed()).isEqualTo(2);
        assertThat(report.lint().errors()).isZero();
        assertThat(Verdicts.survived(report)).isTrue();
    }

    @Test
    void compileFailureShortCircuitsEverything() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 1);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(report.compiles()).isFalse();
        assertThat(target.executedCommands).containsExactly("gradlew compileJava");
        assertThat(Verdicts.survived(report)).isFalse();
    }

    @Test
    void acceptanceFailureShortCircuitsExistingAndLint() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew acceptanceTest", 1, "build/test-results/acc.xml", FAILING_XML);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(report.compiles()).isTrue();
        assertThat(report.acceptance().failed()).isEqualTo(1);
        assertThat(report.acceptance().failures().get(0).testId()).isEqualTo("A#t2");
        assertThat(target.executedCommands)
            .containsExactly("gradlew compileJava", "gradlew acceptanceTest");
        assertThat(Verdicts.survived(report)).isFalse();
    }

    @Test
    void testCommandFailingWithoutReportsIsInfrastructureError() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .script("gradlew acceptanceTest", 1);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(report.acceptance().errored()).isEqualTo(1);
        assertThat(Verdicts.survived(report)).isFalse();
    }

    @Test
    void staleReportsAreClearedBeforeTestStages() {
        FakeExecTarget target = new FakeExecTarget()
            .file("build/test-results/stale.xml", FAILING_XML)
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/acc.xml", PASSING_XML)
            .scriptProducing("gradlew test", 0, "build/test-results/reg.xml", PASSING_XML)
            .script("gradlew checkstyleMain", 0);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(target.deletedDirs).contains("build/test-results");
        assertThat(report.acceptance().failed()).isZero();
        assertThat(Verdicts.survived(report)).isTrue();
    }

    @Test
    void lintFailureIsRecordedButAdvisory() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/acc.xml", PASSING_XML)
            .scriptProducing("gradlew test", 0, "build/test-results/reg.xml", PASSING_XML)
            .script("gradlew checkstyleMain", 2);

        VerificationReport report = verifier.verify(target, null, gradleSpec());

        assertThat(report.lint().errors()).isEqualTo(1);
        assertThat(Verdicts.survived(report)).isTrue();
    }
}
