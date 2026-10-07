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

/**
 * The one rule about becoming agreed scope: a requirement may not be agreed unless something could
 * prove it (author decision, {@code docs/DEVELOPER_CORRECTIONS.md} §20.2).
 *
 * <p><b>What it stops.</b> A requirement is agreed scope when a person says "this is what must be
 * true". From that moment the pipeline is entitled to plan it, build it and claim it delivered — and
 * every one of those steps answers to a CHECK that names a TEST. A requirement carrying no such
 * check can never reach delivered, can never be sliced into a story, and cannot be verified by
 * anything. It is not an early stage of progress; it is a hole in the document.
 *
 * <p><b>Why here and not in the wizard's prompt.</b> The analyst was told at length to write
 * observable, testable checks, and on a vague sentence it asked a good question and then drafted the
 * vague sentence anyway, in the same pass (§19, §20). Advice to a model is not a gate. This is
 * mechanical and cannot be talked past.
 *
 * <p><b>Why agreement and not creation.</b> Blocking CREATION would break the ordinary way a person
 * writes: type the title, save, add the checks a minute later. It would also make the intake wizard
 * unable to hand the operator a draft to read. A draft that cannot be proved is a perfectly
 * reasonable thing to have on screen — it is only a defect once somebody calls it scope. So drafts
 * are free, and the moment of agreement is where the question is asked.
 *
 * <p><b>Where it deliberately does not apply.</b> Restoring an old revision reproduces history as it
 * was, including shapes today's rules refuse — the same decision {@link RequirementTree} took. A
 * requirement that reaches ACTIVE by evidence rather than by an operator's act (a criterion passing,
 * a story accepted) is already past this gate and is not re-examined.
 *
 * <p>Lives in sc-domain so the server enforces it and the browser can explain it BEFORE the operator
 * clicks, from one derivation rather than two that happen to agree.
 */
public final class AgreementGate {

    private AgreementGate() {}

    /**
     * Why this requirement cannot be agreed yet, in words for the operator, or null when it can.
     *
     * <p>Agreeing a requirement also accepts every check it carries that is still a proposal, so a
     * check counts here if it will be a gate afterwards: anything not RETIRED. A retired check was
     * deliberately taken out of the gate and must not be resurrected by the act of agreeing.
     */
    public static String rejectionFor(BrdRequirement requirement) {
        if (requirement == null) {
            return "There is no requirement here to agree.";
        }
        List<AcceptanceCriterion> criteria = requirement.criteria() == null
            ? List.of() : requirement.criteria();
        int live = 0;
        int named = 0;
        for (AcceptanceCriterion criterion : criteria) {
            if (criterion == null || criterion.status() == CriterionStatus.RETIRED) {
                continue;
            }
            live++;
            if (criterion.testClassOrFile() != null && !criterion.testClassOrFile().isBlank()) {
                named++;
            }
        }
        String handle = requirement.handle() == null ? "This requirement" : requirement.handle();
        if (live == 0) {
            return handle + " has no check on it, so nothing could ever show it was met. "
                + "Add at least one check — something you could observe being true — and name the "
                + "test that will prove it. Until then it stays a draft.";
        }
        if (named == 0) {
            return handle + " has " + (live == 1 ? "a check" : live + " checks")
                + " but none of them names a test, so nothing would ever run to prove "
                + (live == 1 ? "it" : "them") + ". Fill in the test name on at least one check. "
                + "Until then it stays a draft.";
        }
        return null;
    }

    /** True when this requirement could be agreed as it stands. */
    public static boolean mayBeAgreed(BrdRequirement requirement) {
        return rejectionFor(requirement) == null;
    }

}
