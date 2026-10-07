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

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Objects;

/**
 * A worker saying, with evidence, that one of the project's stated rules cannot be met here or is
 * wrong for this case — recorded on its candidate and shown to the judge next to the code.
 *
 * <p><b>Why a worker may say so at all</b> (harness runs 53 and 55, 2026-10-01, HamBook on ZeroZ
 * Stack 0.9.1). Rules are stated by the analyst from the technical document, before anybody has
 * met the library. Two of them turned out not to fit it: "all user-visible text lives in resource
 * bundles" — the stack has no browser-side message catalog yet, so nothing could obey it — and
 * "every type that crosses the wire is a {@code @DataModel}" — the stack serializes enums natively,
 * so a candidate that rightly left an enum unannotated was marked as breaking it. The workers were
 * the ones who met the library's real behaviour, and until this existed they could only obey,
 * silently break the rule, or contort the code. Each of those runs parked hours in with every
 * candidate "breaking" a rule that was the actual problem.
 *
 * <p>A dispute is a claim, not a verdict. The judge decides whether the evidence holds; a dispute
 * without evidence is refused by the tool before it is ever recorded.
 *
 * <p>A mutable POJO, not a record: it is persisted inside {@link CandidateSolution} in EclipseStore,
 * and a record in this package is banned by {@code StoreArchitectureTest}.
 */
@JsonTypeName("RuleDispute")
public class RuleDispute {
    /** The rule as the worker named it — ideally its title exactly as the brief listed it. */
    private String rule;
    /** Why it cannot be met, or why it does not fit this case, in the worker's words. */
    private String reason;
    /**
     * What shows it: a compiler or test output excerpt, an excerpt of the library's own source or
     * documentation, or a file path (with the start of that file attached by the tool).
     */
    private String evidence;

    public RuleDispute() {}

    public RuleDispute(String rule, String reason, String evidence) {
        this.rule = rule;
        this.reason = reason;
        this.evidence = evidence;
    }

    public String rule() { return rule == null ? "" : rule; }
    public String getRule() { return rule(); }
    public void setRule(String rule) { this.rule = rule; }
    public String reason() { return reason == null ? "" : reason; }
    public String getReason() { return reason(); }
    public void setReason(String reason) { this.reason = reason; }
    public String evidence() { return evidence == null ? "" : evidence; }
    public String getEvidence() { return evidence(); }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    /** One line for a log or a list: the rule and the reason, without the evidence. */
    public String headline() {
        return rule().strip() + " — " + reason().strip();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleDispute that = (RuleDispute) o;
        return Objects.equals(rule, that.rule) && Objects.equals(reason, that.reason)
            && Objects.equals(evidence, that.evidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule, reason, evidence);
    }
}
