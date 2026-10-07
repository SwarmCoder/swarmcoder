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
package com.swarmcoder.domain;

import java.util.List;
import java.util.Objects;

public class JudgeScore {
    private double score;
    private String rationale;
    private String judgeModelId;
    /**
     * The stated house rules this candidate's diff was found to break, verbatim from the judge's
     * own {@code violations} list — never re-derived by parsing {@link #rationale}. Empty when the
     * judge named none, which includes both "the diff keeps every rule" and "no rule-aware prompt
     * was used" (a project with no rules). Never null after {@link #setBrokenRules}.
     *
     * <p>Added so {@link com.swarmcoder.swarm.SelectionLogic} can rank a candidate that broke a
     * stated rule below one that did not, without re-parsing the rationale text: the judge already
     * produces this as structured data ({@code JudgeClient.JudgeVerdict#violations}), and reading
     * the summary string a second time to look for it would be fragile where the list already
     * isn't.
     *
     * <p>Since 2026-10-01 only HARD-rule breaks land here — see {@link #preferenceBreaks} and
     * {@link #disputedRules} for the other two kinds. When the judge was handed no rule objects
     * to classify against, every break still lands here, exactly as before.
     */
    private List<String> brokenRules = List.of();
    /**
     * Rules the judge found broken that are PREFERENCES, not hard rules — in its own words. They
     * lowered the score and nothing else: they never move a candidate down a selection tier,
     * never trigger the rule-break repair round and never park a run (harness runs 53 and 55,
     * 2026-10-01: a run parked hours in over a rule that was never meant to stop anything).
     * Never null.
     */
    private List<String> preferenceBreaks = List.of();
    /**
     * Rules this candidate's worker DISPUTED with evidence the judge found credible, each with the
     * judge's reason. A disputed rule is not a violation: it is a question about the rule, which
     * {@code RuleQuestions} raises when siblings dispute the same one. Never null.
     */
    private List<String> disputedRules = List.of();

    public JudgeScore() {}

    public JudgeScore(double score, String rationale, String judgeModelId) {
        this.score = score;
        this.rationale = rationale;
        this.judgeModelId = judgeModelId;
    }

    public double score() { return score; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String rationale() { return rationale; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String judgeModelId() { return judgeModelId; }
    public String getJudgeModelId() { return judgeModelId; }
    public void setJudgeModelId(String judgeModelId) { this.judgeModelId = judgeModelId; }
    public List<String> brokenRules() { return brokenRules == null ? List.of() : brokenRules; }
    public List<String> getBrokenRules() { return brokenRules(); }
    public void setBrokenRules(List<String> brokenRules) {
        this.brokenRules = brokenRules == null ? List.of() : List.copyOf(brokenRules);
    }

    public List<String> preferenceBreaks() { return preferenceBreaks == null ? List.of() : preferenceBreaks; }
    public List<String> getPreferenceBreaks() { return preferenceBreaks(); }
    public void setPreferenceBreaks(List<String> preferenceBreaks) {
        this.preferenceBreaks = preferenceBreaks == null ? List.of() : List.copyOf(preferenceBreaks);
    }
    public List<String> disputedRules() { return disputedRules == null ? List.of() : disputedRules; }
    public List<String> getDisputedRules() { return disputedRules(); }
    public void setDisputedRules(List<String> disputedRules) {
        this.disputedRules = disputedRules == null ? List.of() : List.copyOf(disputedRules);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JudgeScore that = (JudgeScore) o;
        return this.score == that.score && Objects.equals(this.rationale, that.rationale) && Objects.equals(this.judgeModelId, that.judgeModelId) && Objects.equals(this.brokenRules(), that.brokenRules()) && Objects.equals(this.preferenceBreaks(), that.preferenceBreaks()) && Objects.equals(this.disputedRules(), that.disputedRules());
    }

    @Override
    public int hashCode() {
        return Objects.hash(score, rationale, judgeModelId, brokenRules(), preferenceBreaks(), disputedRules());
    }
}

