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

import com.swarmcoder.domain.GuidelineCheck;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is established once on the untouched tree of a run (owner decisions after the audit of
 * 2026-10-02): a house-rule check command that cannot run is not a rule a candidate broke, and a
 * test that already failed is not a candidate's regression.
 */
class BaseTreeChecksTest {

    private static final String ONE_OLD_FAILURE = """
        <testsuite name="s" tests="2" failures="1" errors="0" skipped="0">
          <testcase classname="com.x.OldTest" name="fine"/>
          <testcase classname="com.x.OldTest" name="broken">
            <failure message="was always wrong">trace</failure>
          </testcase>
        </testsuite>
        """;

    private static final String OLD_AND_NEW_FAILURE = """
        <testsuite name="s" tests="2" failures="2" errors="0" skipped="0">
          <testcase classname="com.x.OldTest" name="fine">
            <failure message="the candidate broke this">trace</failure>
          </testcase>
          <testcase classname="com.x.OldTest" name="broken">
            <failure message="was always wrong">trace</failure>
          </testcase>
        </testsuite>
        """;

    private static VerifySpec spec() {
        return new VerifySpec("gradle", List.of("gradlew compileJava"), List.of(),
            List.of("gradlew test"), List.of(), null, null, 60, null);
    }

    private static GuidelineCheck rule(String slug, String command) {
        return new GuidelineCheck(slug, "PROJECT", "the rule in words", command, 30);
    }

    // ---- a rule's check command that cannot run -----------------------------------------------

    @Test
    void aCommandThatIsNotInstalledCannotRun() {
        FakeExecTarget target = new FakeExecTarget()
            .scriptOutput("checkstyle-cli src", 127, "sh: 1: checkstyle-cli: not found")
            .scriptOutput("Select-String record src", 1,
                "'Select-String' is not recognized as an internal or external command,\n"
                    + "operable program or batch file.")
            .scriptOutput("./tools/check.sh", 126, "sh: 1: ./tools/check.sh: Permission denied")
            .scriptOutput("if [[ -f x ]] then", 2,
                "bash: -c: line 1: syntax error near unexpected token `then'")
            .scriptOutput("lint-missing | grep -q bad", 1, "sh: 1: lint-missing: not found");

        List<BaseTreeChecks.UnrunnableCheck> found = BaseTreeChecks.unrunnable(target, List.of(
            rule("style", "checkstyle-cli src"), rule("no-records", "Select-String record src"),
            rule("script", "./tools/check.sh"), rule("bashism", "if [[ -f x ]] then"),
            rule("piped", "lint-missing | grep -q bad")), new StringBuilder());

        assertThat(found).extracting(u -> u.check().slug())
            .containsExactly("style", "no-records", "script", "bashism", "piped");
        assertThat(found.get(0).sentence())
            .as("the command and the reason are both named")
            .contains("'style'").contains("checkstyle-cli src").contains("not found");
    }

    @Test
    void aCommandThatRunsAndFailsIsNotUnrunnable() {
        FakeExecTarget target = new FakeExecTarget()
            .scriptOutput("grep-records", 1, "src/Money.java: public record Money")
            .scriptOutput("grep -r record nowhere", 2, "grep: nowhere: No such file or directory")
            .script("fine", 0);

        assertThat(BaseTreeChecks.unrunnable(target, List.of(rule("a", "grep-records"),
            rule("b", "grep -r record nowhere"), rule("c", "fine")), null))
            .as("a check that ran has said something about the tree, whatever it exited with")
            .isEmpty();
        assertThat(BaseTreeChecks.cannotRun(
            new ExecResult(1, "", true, Duration.ofSeconds(30)))).as("a timeout ran").isNull();
    }

    // ---- tests that already fail ----------------------------------------------------------------

    @Test
    void theTestsThatFailOnTheStartTreeAreNamed() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew test", 1, "build/test-results/test/old.xml", ONE_OLD_FAILURE);

        BaseTreeChecks.ExistingBaseline baseline =
            BaseTreeChecks.existingTests(target, spec(), new StringBuilder());

        assertThat(baseline.established()).isTrue();
        assertThat(baseline.failingIds()).containsExactly("com.x.OldTest#broken");
    }

    @Test
    void aStartTreeThatCannotBeMeasuredEstablishesNothing() {
        FakeExecTarget doesNotCompile = new FakeExecTarget().script("gradlew compileJava", 1);
        FakeExecTarget noReports = new FakeExecTarget()
            .script("gradlew compileJava", 0).script("gradlew test", 1);

        assertThat(BaseTreeChecks.existingTests(doesNotCompile, spec(), null).established())
            .isFalse();
        assertThat(BaseTreeChecks.existingTests(noReports, spec(), null).established()).isFalse();
        assertThat(BaseTreeChecks.existingTests(new FakeExecTarget(), new VerifySpec("gradle",
            List.of(), List.of(), List.of(), List.of(), null, null, 60, null), null).established())
            .isFalse();
    }

    @Test
    void aCandidateIsNotFailedForATestThatAlreadyFailed() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew test", 1, "build/test-results/test/old.xml", ONE_OLD_FAILURE);
        VerificationBaseline baseline = VerificationBaseline.of(List.of("com.x.OldTest#broken"));

        VerificationReport without = new CommandPipelineVerifier()
            .verify(target, null, spec(), List.of(), Set.of());
        VerificationReport with = new CommandPipelineVerifier()
            .verify(target, null, spec(), List.of(), Set.of(), baseline);

        assertThat(Verdicts.survived(without)).as("today: every candidate fails on it").isFalse();
        assertThat(Verdicts.survived(with)).isTrue();
        assertThat(with.existing().failed()).isZero();
        assertThat(with.existing().passed()).isEqualTo(1);
        assertThat(with.logTail()).contains("already fail on the tree this run started from")
            .contains("com.x.OldTest#broken");
    }

    @Test
    void aCandidateIsStillFailedForATestItBroke() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew test", 1, "build/test-results/test/old.xml",
                OLD_AND_NEW_FAILURE);

        VerificationReport report = new CommandPipelineVerifier().verify(target, null, spec(),
            List.of(), Set.of(), VerificationBaseline.of(List.of("com.x.OldTest#broken")));

        assertThat(Verdicts.survived(report)).isFalse();
        assertThat(report.existing().failed()).isEqualTo(1);
        assertThat(report.existing().failures()).singleElement()
            .extracting(f -> f.testId()).isEqualTo("com.x.OldTest#fine");
    }
}
