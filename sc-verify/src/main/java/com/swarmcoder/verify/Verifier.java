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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.VerificationReport;

import java.util.List;
import java.util.Set;

/**
 * Verification harness contract (spec §10.1). Runs the fail-fast pipeline
 * compile → guideline checks → acceptance → existing → lint against an {@link ExecTarget} and
 * produces the structured report that selection is grounded in.
 *
 * <p>The two inputs are deliberately separate. {@link VerifySpec} is the TARGET REPOSITORY'S
 * contract — how it builds and tests itself. {@link GuidelineCheck}s are the OPERATOR'S house
 * rules, which have nothing to do with the repository's build and are resolved from a different
 * file in a different place. Folding them into one object would have made a rule's command
 * something {@code verify.yaml} could also declare, and that file is the thing §13.1 is about.
 */
public interface Verifier {

    /** Verification with no house rules to prove — the target repository's contract only. */
    default VerificationReport verify(ExecTarget target, Task task, VerifySpec spec) {
        return verify(target, task, spec, List.of());
    }

    /**
     * @param guidelineChecks house rules that declared a proof command, resolved from the
     *                        operator's checkout. A candidate that fails one does not survive.
     */
    default VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                                      List<GuidelineCheck> guidelineChecks) {
        return verify(target, task, spec, guidelineChecks, Set.of());
    }

    /**
     * @param changedFiles repo-relative paths this candidate ADDED OR CHANGED, taken from its own
     *                     diff — not from the task's write set, which is only the plan's claim
     *                     about where the work would go. The build-reachability stage compares
     *                     these against the source roots the repository actually compiles, because
     *                     a compile command exiting 0 says the commands succeeded and nothing
     *                     whatever about whether these files were among what they built. Empty
     *                     means the caller does not know; the stage then establishes nothing and
     *                     fails nobody.
     */
    VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                              List<GuidelineCheck> guidelineChecks, Set<String> changedFiles);

    /**
     * As above, with what was already wrong on the tree the run started from (see
     * {@link VerificationBaseline}). A verifier that knows nothing of baselines ignores it.
     */
    default VerificationReport verify(ExecTarget target, Task task, VerifySpec spec,
                                      List<GuidelineCheck> guidelineChecks, Set<String> changedFiles,
                                      VerificationBaseline baseline) {
        return verify(target, task, spec, guidelineChecks, changedFiles);
    }
}
