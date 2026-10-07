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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A house rule that declares how to prove itself is part of verification (author decision, §21).
 *
 * <p>The stage sits between the compile and the tests, and these tests pin exactly that, because
 * where it sits is a cost decision that is easy to undo by accident: a rule check is milliseconds
 * against minutes for a test suite, and a candidate doomed by a rule must not first buy a full
 * test run — but a candidate that does not compile has a real reason to die that a rule check
 * would only bury.
 */
class GuidelineCheckStageTest {

    private static final String PASSING_XML = """
        <testsuite name="s" tests="1" failures="0" errors="0" skipped="0">
          <testcase classname="A" name="t1"/>
        </testsuite>
        """;

    private final CommandPipelineVerifier verifier = new CommandPipelineVerifier();

    private static VerifySpec spec() {
        return new VerifySpec("gradle",
            List.of("gradlew compileJava"),
            List.of("gradlew acceptanceTest"),
            List.of("gradlew test"),
            List.of(),
            null, null, 60, null);
    }

    private static GuidelineCheck noRecords(String command) {
        return new GuidelineCheck("no-java-records", "PROJECT",
            "Never declare a Java record; use a final class with explicit accessors.",
            command, 30);
    }

    @Test
    void aBrokenRuleFailsTheCandidateAndSkipsTheTestSuite() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptOutput("check-no-records", 1, "src/main/java/Money.java: public record Money");

        VerificationReport report = verifier.verify(target, null, spec(),
            List.of(noRecords("check-no-records")));

        assertThat(report.compiles()).isTrue();
        assertThat(report.guidelineChecks()).hasSize(1);
        assertThat(report.guidelineChecks().get(0).passed()).isFalse();
        assertThat(target.executedCommands)
            .as("the test suite is never started for a candidate that is already dead")
            .containsExactly("gradlew compileJava", "check-no-records");

        Verdicts.Verdict verdict = Verdicts.assess(report, List.of());
        assertThat(verdict.survived()).isFalse();
        assertThat(verdict.reason())
            .as("the operator is told the rule in its own words, plus the command and its output")
            .contains("no-java-records")
            .contains("Never declare a Java record")
            .contains("check-no-records")
            .contains("public record Money");
    }

    @Test
    void anObeyedRuleLetsTheRestOfThePipelineRun() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .script("check-no-records", 0)
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/a.xml", PASSING_XML)
            .scriptProducing("gradlew test", 0, "build/test-results/b.xml", PASSING_XML);

        VerificationReport report = verifier.verify(target, null, spec(),
            List.of(noRecords("check-no-records")));

        assertThat(report.guidelineChecks()).hasSize(1);
        assertThat(report.guidelineChecks().get(0).passed()).isTrue();
        assertThat(target.executedCommands)
            .containsExactly("gradlew compileJava", "check-no-records",
                "gradlew acceptanceTest", "gradlew test");
        assertThat(Verdicts.survived(report)).isTrue();
    }

    @Test
    void aRuleCheckNeverRunsWhenTheCandidateDoesNotCompile() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 1);

        VerificationReport report = verifier.verify(target, null, spec(),
            List.of(noRecords("check-no-records")));

        assertThat(target.executedCommands).containsExactly("gradlew compileJava");
        assertThat(report.guidelineChecks()).isEmpty();
        assertThat(Verdicts.assess(report, List.of()).reason())
            .as("the candidate dies of the real cause, not of a rule check on code that "
                + "does not build")
            .contains("does not compile");
    }

    /** Every broken rule is named, not just the first: an operator fixing one wants the list. */
    @Test
    void allBrokenRulesAreReportedNotJustTheFirst() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .script("check-a", 1)
            .script("check-b", 1);

        VerificationReport report = verifier.verify(target, null, spec(), List.of(
            new GuidelineCheck("rule-a", "PROJECT", "Rule A.", "check-a", 30),
            new GuidelineCheck("rule-b", "GLOBAL", "Rule B.", "check-b", 30)));

        assertThat(report.guidelineChecks()).hasSize(2);
        assertThat(Verdicts.assess(report, List.of()).reason())
            .contains("broke 2 house rules")
            .contains("rule-a")
            .contains("rule-b");
    }

    /** No rules declared a check: the pipeline is byte-for-byte what it was before this existed. */
    @Test
    void noDeclaredChecksMeansNoStageAtAll() {
        FakeExecTarget target = new FakeExecTarget()
            .script("gradlew compileJava", 0)
            .scriptProducing("gradlew acceptanceTest", 0, "build/test-results/a.xml", PASSING_XML)
            .scriptProducing("gradlew test", 0, "build/test-results/b.xml", PASSING_XML);

        VerificationReport report = verifier.verify(target, null, spec());

        assertThat(report.guidelineChecks()).isEmpty();
        assertThat(Verdicts.survived(report)).isTrue();
    }

    /**
     * A report from before this existed carries no results at all. That must read as "no rule
     * declared a check", never as "a rule was broken" — the same fail-open-on-no-evidence rule
     * §17.1 settled for the empty acceptance stage.
     */
    @Test
    void anOldReportWithNoGuidelineResultsStillSurvives() {
        VerificationReport old = new VerificationReport(java.util.UUID.randomUUID(), true, true,
            null, null, null, null, java.time.Duration.ZERO, "", null);

        assertThat(old.guidelineChecks()).isNull();
        assertThat(Verdicts.survived(old)).isTrue();
    }
}
