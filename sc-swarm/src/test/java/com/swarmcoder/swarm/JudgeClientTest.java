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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeClientTest {

    @Test
    void extractsFirstBalancedObject() {
        assertThat(JudgeClient.extractJson("{\"score\":0.9,\"rationale\":\"ok\"}"))
            .isEqualTo("{\"score\":0.9,\"rationale\":\"ok\"}");
    }

    @Test
    void ignoresSecondObjectEmittedBackToBack() {
        assertThat(JudgeClient.extractJson("{\"score\":0.8}{\"score\":0.1}"))
            .isEqualTo("{\"score\":0.8}");
    }

    @Test
    void stripsProseAndCodeFences() {
        assertThat(JudgeClient.extractJson("Here is my verdict:\n```json\n{\"score\":0.7,\"rationale\":\"fine\"}\n```"))
            .isEqualTo("{\"score\":0.7,\"rationale\":\"fine\"}");
    }

    @Test
    void handlesBracesInsideStrings() {
        assertThat(JudgeClient.extractJson("{\"rationale\":\"uses {braces} and \\\"quotes\\\"\",\"score\":1}"))
            .isEqualTo("{\"rationale\":\"uses {braces} and \\\"quotes\\\"\",\"score\":1}");
    }

    /**
     * The new field is additive on purpose: the schema local models already cope with must keep
     * parsing exactly as it did (author decision, §21). This is the reply shape that has been
     * arriving for months.
     */
    @Test
    void aReplyWithNoViolationsFieldStillParsesAndReadsUnchanged() throws Exception {
        JudgeClient.JudgeVerdict verdict = new JudgeClient(null)
            .parseVerdict("{\"score\": 0.9, \"rationale\": \"clean and minimal\"}");

        assertThat(verdict.score).isEqualTo(0.9);
        assertThat(verdict.violations).isNull();
        assertThat(JudgeClient.rationaleWithViolations(verdict)).isEqualTo("clean and minimal");
    }

    /** A breach the judge names has to be readable by a person, not only visible as a low score. */
    @Test
    void aNamedViolationIsFoldedIntoTheRationaleTheOperatorReads() throws Exception {
        JudgeClient.JudgeVerdict verdict = new JudgeClient(null).parseVerdict(
            "{\"score\": 0.3, \"rationale\": \"works but breaks a rule\", "
            + "\"violations\": [\"no-java-records: Money.java declares a record\"]}");

        assertThat(verdict.score).isEqualTo(0.3);
        assertThat(JudgeClient.rationaleWithViolations(verdict))
            .contains("works but breaks a rule")
            .contains("HOUSE RULES BROKEN")
            .contains("Money.java declares a record");
    }

    /**
     * {@link SelectionLogic} ranks on the judge's structured {@code violations} list, never by
     * re-parsing the rationale text (author decision, §21 addendum). A verdict naming two broken
     * rules must come out of {@link JudgeClient#brokenRulesFrom} as two entries, verbatim and in
     * order, with nothing stripped but blanks.
     */
    @Test
    void twoNamedViolationsParseIntoTwoBrokenRuleEntries() throws Exception {
        JudgeClient.JudgeVerdict verdict = new JudgeClient(null).parseVerdict(
            "{\"score\": 0.4, \"rationale\": \"compiles but wrong shape\", "
            + "\"violations\": [\"Persistence uses EclipseStore: uses a HashMap instead\", "
            + "\"No REST endpoints: adds a JSON controller\"]}");

        assertThat(JudgeClient.brokenRulesFrom(verdict.violations))
            .containsExactly(
                "Persistence uses EclipseStore: uses a HashMap instead",
                "No REST endpoints: adds a JSON controller");
    }

    /** No violations named parses into an empty list, never null — {@code SelectionLogic} depends on it. */
    @Test
    void noViolationsNamedParsesIntoAnEmptyBrokenRulesList() throws Exception {
        JudgeClient.JudgeVerdict verdict = new JudgeClient(null)
            .parseVerdict("{\"score\": 0.9, \"rationale\": \"clean\"}");

        assertThat(JudgeClient.brokenRulesFrom(verdict.violations)).isEmpty();
    }

    /** A project with no house rules is judged by exactly the prompt it was judged by before. */
    @Test
    void theRuleAwareInstructionsAppearOnlyWhenThereAreRules() {
        assertThat(JudgeClient.systemPrompt(false))
            .doesNotContain("HOUSE RULES")
            .doesNotContain("violations");
        assertThat(JudgeClient.systemPrompt(true))
            .contains("HOUSE RULES")
            .contains("violations");
    }

    @Test
    void recoversFromDoubledOpeningBrace() throws Exception {
        // Observed live from Qwen 3.6: {{"score": 1.0, ...} — two opens, one close.
        JudgeClient.JudgeVerdict verdict = new JudgeClient(null)
            .parseVerdict("{{\"score\": 1.0, \"rationale\": \"correct and concise\"}");

        assertThat(verdict.score).isEqualTo(1.0);
        assertThat(verdict.rationale).contains("concise");
    }
}
