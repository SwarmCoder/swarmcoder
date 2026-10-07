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
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness runs 53 and 55 (2026-10-01, HamBook on ZeroZ Stack 0.9.1) parked hours in because the
 * rule check — the judge — held every candidate to each rule's literal words. These pin the three
 * things the judge now does differently: a worker's dispute, with its evidence, is in front of it
 * next to the code, and a credible one is not a violation; it is told why each rule exists; and a
 * broken PREFERENCE only costs score, never a selection tier.
 */
class RulesAreJudgedByEvidenceAndPurposeTest {

    @TempDir
    Path worktree;

    private static final String TEXT_RULE = "Text in bundles";

    private static LearnedGuideline rule(String title, String body, String purpose, boolean hard) {
        LearnedGuideline rule = new LearnedGuideline(UUID.randomUUID(), 1, GuidelineScope.PROJECT,
            title.toLowerCase().replace(' ', '-'), body, new Provenance("stated", null), 1.0,
            Instant.now(), 0, GuidelineStatus.ACTIVE, UUID.randomUUID(), null, 0);
        rule.setTitle(title);
        rule.setPurpose(purpose);
        rule.setHard(hard);
        return rule;
    }

    private static Task task() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("Show the menu");
        task.setInstructions("Render the main menu with its labels.");
        return task;
    }

    private static CandidateSolution candidate(List<RuleDispute> disputes) {
        CandidateSolution sol = new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0,
            "swarm/w0", null, "--- a/Menu.java\n+++ b/Menu.java\n+label(\"Start\");\n",
            new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
                Duration.ZERO, "", null),
            new ClusterId("hash", 1), null, CandidateState.SURVIVED, null);
        sol.setRuleDisputes(disputes);
        return sol;
    }

    private static final String JUDGE_FLAGS_AND_ACCEPTS = "{\"score\": 0.7, \"rationale\": "
        + "\"works\", \"violations\": [\"Text in bundles: labels are hard-coded in Menu.java\"], "
        + "\"disputed\": [\"Text in bundles: the evidence shows the stack has no browser catalog\"]}";

    /** The tool exists, records a dispute with its evidence, and refuses one without evidence. */
    @Test
    void aWorkerCanDisputeARuleOnlyWithEvidence() throws Exception {
        Files.writeString(worktree.resolve("CATALOG.md"), "The browser client has no message "
            + "catalog in this release.");
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task());

        assertThat(toolbox.bindings().stream().map(ToolBinding::name)).contains("dispute_rule");
        assertThat(toolbox.disputeRule(TEXT_RULE, "no browser catalog", "  "))
            .startsWith("error:");
        assertThat(toolbox.ruleDisputes()).as("a dispute without evidence is never recorded")
            .isEmpty();

        assertThat(toolbox.disputeRule(TEXT_RULE, "no browser-side catalog", "CATALOG.md"))
            .startsWith("Recorded.");
        assertThat(toolbox.ruleDisputes()).hasSize(1);
        RuleDispute dispute = toolbox.ruleDisputes().get(0);
        assertThat(dispute.rule()).isEqualTo(TEXT_RULE);
        assertThat(dispute.evidence())
            .as("a file named as evidence carries its content, so the judge reads the evidence")
            .contains("CATALOG.md")
            .contains("has no message catalog");
    }

    /** The dispute reaches the rule check next to the code, and a credible one is not a break. */
    @Test
    void aDisputeIsShownToTheRuleCheckAndACredibleOneIsNotAViolation() throws Exception {
        LearnedGuideline text = rule(TEXT_RULE, "All user-visible text lives in resource bundles.",
            "so the text can be translated", true);
        try (FakeVllm fake = new FakeVllm(c -> FakeVllm.Reply.text(JUDGE_FLAGS_AND_ACCEPTS))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), null, null, List.of(text));

            CandidateSolution judged = judge.judge(candidate(List.of(new RuleDispute(TEXT_RULE,
                "the stack has no browser-side message catalog", "zeroz-ui/README: no i18n yet"))),
                task());

            String brief = fake.requests.get(0);
            assertThat(brief)
                .contains("THE WORKER DISPUTED 1 RULE(S), WITH EVIDENCE")
                .contains("the stack has no browser-side message catalog")
                .contains("zeroz-ui/README: no i18n yet");
            assertThat(brief.indexOf("THE WORKER DISPUTED"))
                .as("the dispute is read before the diff it explains")
                .isLessThan(brief.indexOf("Candidate diff:"));
            assertThat(judged.judge().brokenRules())
                .as("a break the worker disputed with evidence the judge accepted is not a violation")
                .isEmpty();
            assertThat(judged.judge().disputedRules()).singleElement().asString()
                .startsWith(TEXT_RULE);
            assertThat(judged.judge().rationale()).contains("RULES DISPUTED WITH CREDIBLE EVIDENCE");
        }
    }

    /** The judge cannot invent a dispute the worker never raised: the break stands. */
    @Test
    void aDisputeTheWorkerNeverRaisedDoesNotExcuseTheBreak() throws Exception {
        LearnedGuideline text = rule(TEXT_RULE, "All user-visible text lives in resource bundles.",
            "so the text can be translated", true);
        try (FakeVllm fake = new FakeVllm(c -> FakeVllm.Reply.text(JUDGE_FLAGS_AND_ACCEPTS))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), null, null, List.of(text));

            CandidateSolution judged = judge.judge(candidate(List.of()), task());

            assertThat(judged.judge().brokenRules()).singleElement().asString()
                .startsWith(TEXT_RULE);
            assertThat(judged.judge().disputedRules()).isEmpty();
            assertThat(SelectionLogic.brokeStatedRule(judged)).isTrue();
        }
    }

    /** The rule check is told why each rule exists, and to judge by that. */
    @Test
    void theRuleCheckIsToldEachRulesPurpose() throws Exception {
        LearnedGuideline wire = rule("Wire types", "Every type that crosses the wire is a "
            + "@DataModel.", "so both ends can always decode what crosses the wire", true);
        try (FakeVllm fake = new FakeVllm(c -> FakeVllm.Reply.text(
                "{\"score\": 0.9, \"rationale\": \"fine\", \"violations\": []}"))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), null, null, List.of(wire));

            judge.judge(candidate(List.of()), task());

            assertThat(fake.requests.get(0))
                .contains("Why it exists: so both ends can always decode what crosses the wire")
                .contains("A HARD rule")
                .contains("PURPOSE")
                .contains("flag a break only when this change causes the problem");
        }
    }

    /** A broken preference lowers the score only: it is never a hard break. */
    @Test
    void aPreferenceBreakIsNeverAHardBreak() throws Exception {
        LearnedGuideline text = rule(TEXT_RULE, "User-visible text lives in resource bundles.",
            "so the text can be translated later", false);
        try (FakeVllm fake = new FakeVllm(c -> FakeVllm.Reply.text("{\"score\": 0.6, "
                + "\"rationale\": \"ok\", \"violations\": [\"Text in bundles: labels hard-coded\", "
                + "\"Something nobody stated: a guess\"]}"))) {
            JudgeClient judge = new JudgeClient(
                new VllmClient(fake.baseUrl(), "", "fake-judge", true), null, null, List.of(text));

            CandidateSolution judged = judge.judge(candidate(List.of()), task());

            assertThat(judged.judge().brokenRules()).isEmpty();
            assertThat(judged.judge().preferenceBreaks())
                .as("a preference, and a sentence pinned to no rule a person marked hard")
                .hasSize(2);
            assertThat(SelectionLogic.brokeStatedRule(judged))
                .as("a preference never moves a candidate down a selection tier").isFalse();
            assertThat(judged.judge().rationale()).contains("PREFERENCES NOT FOLLOWED");
        }
    }

    /** With no rule objects to classify against, every break is hard — exactly as before. */
    @Test
    void withNoRuleObjectsEveryBreakStaysHard() {
        JudgeClient.JudgeVerdict verdict = new JudgeClient.JudgeVerdict();
        verdict.violations = List.of("Persistence uses EclipseStore: uses a HashMap");

        JudgeClient.RuleFindings findings = JudgeClient.classify(verdict, List.of(), List.of());

        assertThat(findings.hard()).containsExactly("Persistence uses EclipseStore: uses a HashMap");
        assertThat(findings.preference()).isEmpty();
    }
}
