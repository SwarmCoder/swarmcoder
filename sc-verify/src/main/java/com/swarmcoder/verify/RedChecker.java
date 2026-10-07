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

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TestResults;
import java.util.List;

/**
 * The mechanical red-check for TEST_AUTHORING (spec §14): freshly authored acceptance tests
 * MUST fail on the pre-change codebase before any swarm dispatches — otherwise verification-first
 * selection has no signal and the swarm degenerates to judge-only selection.
 */
public final class RedChecker {

    /**
     * @param red          true when the acceptance tests genuinely fail on the current tree
     * @param results      the parsed test results backing the verdict
     * @param note         human-readable explanation, for the workflow log / decision brief
     * @param missingTypes the types the compiler said do not exist, fully qualified, when the red
     *                     state is a compile failure. Empty otherwise, and empty whenever the
     *                     output could not be read — which callers treat as "we could not tell",
     *                     never as "nothing is missing". This is what lets a caller separate the
     *                     two reasons a fresh test does not compile: the code it names has not
     *                     been written yet (healthy), or the code it names is a type nobody in
     *                     the plan will ever write (a broken test).
     * @param selfInflicted true when the only thing this red state establishes is that the test
     *                     threw in its OWN frames, with nothing delivered anywhere on the stack.
     *                     A red like that is not TDD: nothing about the plan's code was measured,
     *                     and repairing it can make the test green without a line of delivered
     *                     code ever running — which is exactly how harness run 30 stamped a story
     *                     delivered on a test that implemented the contract itself. Read off
     *                     {@link com.swarmcoder.domain.TestFailure#insideTestItself()}, which
     *                     {@link Verdicts#summarizeTestFailures} stamps; this class neither
     *                     redefines that classification nor duplicates it.
     * @param brokenTypes  the subset of {@code missingTypes} that {@link #check(ExecTarget,
     *                     VerifySpec, List)} found nobody in the plan will ever create — never
     *                     populated by the two-argument {@link #check(ExecTarget, VerifySpec)},
     *                     which has no plan to ask. Non-empty means this red state is a broken
     *                     test wearing a healthy one's clothes, not TDD.
     * @param compileOutput everything the acceptance stage printed, when — and only when — the red
     *                     state is a compile failure; null otherwise. Kept so a caller can read the
     *                     compiler's errors themselves rather than only the missing type names: a
     *                     test that MISUSES a type which already exists (wrong argument type, wrong
     *                     arity, a signature that is not there) fails to compile exactly as a test
     *                     written before its code does, and only the compiler's own lines tell the
     *                     two apart. See {@link AcceptanceCompileErrors} (brownfield harness run 43,
     *                     2026-09-26).
     */
    public record RedCheckResult(boolean red, TestResults results, String note,
                                 List<String> missingTypes, List<String> brokenTypes,
                                 boolean selfInflicted, String compileOutput) {

        public RedCheckResult(boolean red, TestResults results, String note) {
            this(red, results, note, List.of(), List.of(), false, null);
        }

        public RedCheckResult(boolean red, TestResults results, String note, List<String> missingTypes) {
            this(red, results, note, missingTypes, List.of(), false, null);
        }

        public RedCheckResult(boolean red, TestResults results, String note,
                              List<String> missingTypes, List<String> brokenTypes) {
            this(red, results, note, missingTypes, brokenTypes, false, null);
        }

        public RedCheckResult(boolean red, TestResults results, String note,
                              List<String> missingTypes, List<String> brokenTypes,
                              boolean selfInflicted) {
            this(red, results, note, missingTypes, brokenTypes, selfInflicted, null);
        }

        /** True when this red state is the acceptance stage failing to compile. */
        public boolean compileFailure() {
            return compileOutput != null;
        }

        /** True when this red state is a broken test, not a healthy one — see {@link #brokenTypes}. */
        public boolean broken() {
            return !brokenTypes.isEmpty();
        }

        /**
         * This result without the given names among its missing types: the ones a task can supply
         * by adding a dependency, which is no type or package any task creates (see
         * {@link AcceptanceCompileErrors.DependencyNeed}).
         */
        public RedCheckResult withoutMissing(java.util.Collection<String> names) {
            if (names == null || names.isEmpty() || missingTypes == null) {
                return this;
            }
            List<String> kept = missingTypes.stream().filter(n -> !names.contains(n)).toList();
            return new RedCheckResult(red, results, note, kept, brokenTypes, selfInflicted,
                compileOutput);
        }

        private RedCheckResult withBrokenTypes(List<String> brokenTypes) {
            return new RedCheckResult(red, results, note, missingTypes, brokenTypes, selfInflicted,
                compileOutput);
        }

        private RedCheckResult asSelfInflicted() {
            return new RedCheckResult(red, results, note, missingTypes, brokenTypes, true,
                compileOutput);
        }
    }

    /**
     * Runs the acceptance stage and requires real failures. An infrastructure problem
     * (commands unrunnable, no reports produced) is NOT a valid red — a broken test setup
     * would otherwise masquerade as a failing test.
     */
    public RedCheckResult check(ExecTarget target, VerifySpec spec) {
        return check(target, spec, java.util.Set.of());
    }

    /**
     * The same, when the tree already holds earlier stories' acceptance tests (section 59):
     * only THIS story's tests are expected red. {@code earlierTestIds} are the earlier tests as
     * {@code pkg.Class#method}; they must stay green on this tree, and a failure among them is not
     * the red the story's own tests are there to show.
     */
    public RedCheckResult check(ExecTarget target, VerifySpec spec,
                                java.util.Set<String> earlierTestIds) {
        StringBuilder log = new StringBuilder();
        if (spec.acceptance() == null || spec.acceptance().isEmpty()) {
            return new RedCheckResult(false, new TestResults(0, 0, 0, 0, List.of()),
                "no acceptance commands configured — nothing to red-check");
        }
        TestStageRunner.StageOutcome outcome = TestStageRunner.run(target, "red-check",
            spec.acceptance(), spec.acceptanceReportDirs(), spec.effectiveTimeoutSeconds(), log);

        if (outcome.infrastructureError()) {
            // A COMPILE failure of the acceptance tests is a legitimate red state, not an
            // infrastructure error: the tests reference symbols the task will add (TDD), so
            // they cannot build or pass on the pre-change tree. Genuine infra problems
            // (maven missing, no network, plugin errors) lack these markers → not red.
            // Whether that really is "not-yet-implemented symbols" this method cannot tell without
            // the plan; the workflow asks AcceptanceCompileErrors, with the output kept here
            // (brownfield harness run 43, 2026-09-26: a test that misused an existing constructor
            // was confirmed red by this very sentence, and a whole swarm was spent on it).
            if (looksLikeCompileFailure(log.toString())) {
                return new RedCheckResult(true, outcome.results(),
                    "acceptance tests fail to compile against the pre-change tree (they reference "
                    + "not-yet-implemented symbols) — red state confirmed",
                    CompileFailureAttribution.missingTypes(log.toString()), List.of(), false,
                    log.toString());
            }
            return new RedCheckResult(false, outcome.results(),
                "infrastructure error — not a valid red state:\n" + log);
        }
        if (!outcome.reportsFound()) {
            return new RedCheckResult(false, outcome.results(),
                "no test reports produced — not a valid red state");
        }
        TestResults results = outcome.results();
        int failing = results.failed() + results.errored();
        List<com.swarmcoder.domain.TestFailure> earlierFailures = new java.util.ArrayList<>();
        if (results.failures() != null) {
            for (com.swarmcoder.domain.TestFailure failure : results.failures()) {
                if (isEarlier(failure.testId(), earlierTestIds)) {
                    earlierFailures.add(failure);
                }
            }
        }
        failing -= earlierFailures.size();
        if (!earlierFailures.isEmpty() && failing <= 0) {
            return new RedCheckResult(false, results, "earlier stories' acceptance tests fail on the "
                + "pre-change tree: " + earlierFailures.stream()
                    .map(com.swarmcoder.domain.TestFailure::testId).toList() + " - they must stay "
                + "green; only this story's own tests are expected red");
        }
        if (failing > 0) {
            RedCheckResult red = new RedCheckResult(true, results,
                failing + " acceptance test(s) fail on the pre-change tree — red state confirmed");
            // WHICH RED THIS IS (author decision, 2026-09-05, harness run 30). A test that fails
            // at an assertion or an exception INSIDE delivered code, or on a delivered behaviour
            // that is missing, has measured the plan's code and found it wanting: that is the red
            // TDD means. A test that threw only in its own frames, with nothing delivered anywhere
            // on the stack, has measured itself. Both look identical from the counts, and only the
            // second can be "repaired" into a green test that proves nothing.
            return selfInflicted(results, earlierTestIds) ? red.asSelfInflicted() : red;
        }
        return new RedCheckResult(false, results,
            "acceptance tests already pass on the pre-change tree — tests are not red; "
            + "TEST_AUTHORING must revise them before dispatch");
    }

    /**
     * {@link #check(ExecTarget, VerifySpec)}, then classifies a compile-failure red state: is
     * every missing symbol something {@code tasks} will actually deliver, or does at least one of
     * them name a type or package nobody in the plan either promises as a contract or may write?
     *
     * <p>Both readings of "does not compile" — the healthy TDD one and the broken-test one — look
     * identical to the plain two-argument check, because a compiler cannot know a plan. This is
     * the difference: given the plan's own tasks, a missing symbol that is nobody's job at all is
     * reported in {@link RedCheckResult#brokenTypes()}, and {@link RedCheckResult#broken()} is
     * true. See {@link TypeDeliverability} for how a symbol is judged deliverable.
     *
     * @param tasks every task that has not run yet — at TEST_AUTHORING, before any wave has
     *              started, that is the whole plan; at wave time it is this wave and the ones
     *              after it, since an earlier wave already ran and integrated whatever it had to
     *              deliver
     */
    public RedCheckResult check(ExecTarget target, VerifySpec spec, List<Task> tasks) {
        RedCheckResult result = check(target, spec);
        if (!result.red() || result.missingTypes().isEmpty()) {
            return result;
        }
        List<String> broken = TypeDeliverability.undeliverable(result.missingTypes(), tasks);
        return broken.isEmpty() ? result : result.withBrokenTypes(broken);
    }

    /**
     * The broken-at-startup reading of a red state that RAN, or null when it is a healthy red, a
     * compile failure, or nothing could be settled (live harness run 51, 2026-09-30 — see
     * {@link BrokenAtStartup} for the run and the rule). A test class that errors at class level, or
     * whose every test errors, with a stack that never enters the project's own code has not
     * measured the plan at all: it booted a framework or container that died, and no candidate can
     * change that. Treated like a broken compile: sent back to the test author, never a healthy red.
     *
     * @param tree  the tree the red check ran in, read for the project's own packages; may be null
     * @param tasks every task that has not run yet, whose write sets and contracts name packages the
     *              tree may not hold yet; may be null
     */
    public static BrokenAtStartup.Finding brokenAtStartup(RedCheckResult result, java.nio.file.Path tree,
                                                          List<Task> tasks) {
        if (result == null || !result.red() || result.compileFailure()) {
            return null;
        }
        return BrokenAtStartup.find(result.results(), BrokenAtStartup.projectPackages(tree, tasks));
    }

    /**
     * True when every failure this stage produced happened in the failing test's own frames, with
     * no delivered type on the stack.
     *
     * <p>The classification itself is {@link com.swarmcoder.domain.TestFailure#insideTestItself()},
     * stamped by {@link Verdicts#summarizeTestFailures} from {@code AcceptanceFailureAttribution}.
     * It is deliberately read rather than re-derived: there must be exactly one definition of
     * "this failure never reached the delivered code" in the project, and this is not it.
     */
    private static boolean isEarlier(String testId, java.util.Set<String> earlier) {
        if (testId == null || earlier == null || earlier.isEmpty()) {
            return false;
        }
        int cut = testId.length();
        for (char stop : new char[] {'(', '['}) {
            int at = testId.indexOf(stop);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return earlier.contains(testId.substring(0, cut));
    }

    private static boolean selfInflicted(TestResults results, java.util.Set<String> earlier) {
        Verdicts.summarizeTestFailures("acceptance", results); // stamps insideTestItself
        List<com.swarmcoder.domain.TestFailure> failures =
            results == null ? null : results.failures();
        if (failures == null || failures.isEmpty()) {
            return false; // no detail: nothing was established, so nothing is concluded
        }
        for (com.swarmcoder.domain.TestFailure failure : failures) {
            if (!isEarlier(failure.testId(), earlier)) {
                return failure.insideTestItself();
            }
        }
        return false;
    }

    /** Compiler-failure signatures (javac/Gradle/Maven) — a missing symbol the task will add. */
    private static boolean looksLikeCompileFailure(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase();
        return lower.contains("cannot find symbol")
            || lower.contains("compilation error")
            || lower.contains("compilation failure")
            || lower.contains("compilefailed")
            || lower.contains("error: package ")
            || lower.contains("cannot resolve symbol")   // Kotlin/IDEA-style
            || lower.contains("unresolved reference");    // Kotlin
    }
}

