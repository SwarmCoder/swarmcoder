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
package com.swarmcoder.app;

import com.swarmcoder.domain.TestResults;
import com.swarmcoder.verify.ExecTarget;
import com.swarmcoder.verify.RedChecker;
import com.swarmcoder.verify.VerifySpec;

/**
 * The two states a story's acceptance test must be in for "delivered" to mean anything: <b>RED on
 * the tree before the delivered commit, GREEN on it.</b>
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 30, 2026-09-05. Story "rate a book"; the acceptance test wrote its own anonymous
 * implementation of the {@code BookService} contract and asserted against that. It failed on both
 * candidates for a bug in its own field-initialisation order, was "repaired" by moving three lines,
 * and then passed for both — with no candidate's code ever executed. At 13:10 the story was stamped
 * delivered on it, and every link in this harness's chain was green, because not one of them asked
 * the only question that separates a delivered story from a tautology: <i>would that test have
 * failed without the delivered code?</i>
 *
 * <h2>Why the check is shaped like this</h2>
 *
 * <p>The expensive half is already paid for. The delivered side was measured when the winning
 * candidate was verified — those are its own acceptance results, run against the delivered code —
 * so the harness only has to run the test suite ONE extra time, on the tree as it was before the
 * delivered commit. {@link #measure(ExecTarget, VerifySpec)} is that one run;
 * {@link #compare(Side, Side)} is the pure judgement over the two, so both can be proved without a
 * repository, a model or a live build (see {@code DeliveredCodeIsWhatPassedCheckTest}).
 *
 * <p>"Red" here is the {@link RedChecker}'s own definition, deliberately: a test that fails to
 * compile because the delivered types are not there yet is red, and so is one that runs and fails.
 * Both mean the same thing for this question — without the delivered code, this test does not pass.
 */
final class DeliveredCodeIsWhatPassedCheck {

    private DeliveredCodeIsWhatPassedCheck() {}

    /**
     * What the acceptance suite did in one of the two states.
     *
     * @param executed how many tests ran; zero when the stage selected nothing or would not compile
     * @param failing  failures plus errors
     * @param red      the {@link RedChecker}'s verdict: this suite does NOT pass on this tree
     * @param note     what ran and what happened, in the words the chain ledger prints
     */
    record Side(int executed, int failing, boolean red, String note) {

        /** The suite ran, everything in it passed, and something was actually selected. */
        boolean green() {
            return !red && executed > 0 && failing == 0;
        }
    }

    /**
     * @param ok       true only when the test was red before the delivered commit and green on it
     * @param observed the sentence the chain ledger records — what ran, and what happened, in BOTH
     *                 states, so a broken link says which half of the claim failed
     */
    record Outcome(boolean ok, String observed) {}

    /** One acceptance run on one prepared tree. */
    static Side measure(ExecTarget target, VerifySpec spec) {
        RedChecker.RedCheckResult result = new RedChecker().check(target, spec);
        TestResults results = result.results();
        int executed = results == null ? 0 : results.executed();
        int failing = results == null ? 0 : results.failed() + results.errored();
        // The RedChecker's own "not red" note is written for TEST_AUTHORING ("the tests must be
        // revised"), which reads as an accusation in this context — here a green suite may be
        // exactly right. Say what happened instead.
        String note = !result.red() && executed > 0 && failing == 0
            ? "the acceptance suite ran and everything in it passed"
            : result.note();
        return new Side(executed, failing, result.red(), note);
    }

    /** Both runs, for a caller that has no measured result for the delivered side. */
    static Outcome measure(ExecTarget beforeTree, ExecTarget deliveredTree, VerifySpec spec) {
        return compare(measure(beforeTree, spec), measure(deliveredTree, spec));
    }

    /**
     * The judgement itself, over two already-measured states. Pure: no process, no repository.
     *
     * <p>A tautological test is green on BOTH sides — that is what "it proves nothing" looks like
     * from here, and it is the case this whole check exists to fail. A test that is red on both
     * sides is a different fault (the delivered code does not satisfy it), and it fails too, with
     * its own half of the sentence saying so.
     */
    static Outcome compare(Side before, Side onDelivered) {
        boolean ok = before.red() && onDelivered.green();
        StringBuilder observed = new StringBuilder("before the delivered commit: ")
            .append(before.executed()).append(" test(s) executed, ").append(before.failing())
            .append(" failing — ").append(before.red() ? "RED" : "NOT RED")
            .append(" (").append(before.note()).append("); on the delivered commit: ")
            .append(onDelivered.executed()).append(" test(s) executed, ")
            .append(onDelivered.failing()).append(" failing — ")
            .append(onDelivered.green() ? "GREEN" : "NOT GREEN")
            .append(" (").append(onDelivered.note()).append(')');
        if (!ok) {
            observed.append(". ").append(explain(before, onDelivered));
        }
        return new Outcome(ok, observed.toString());
    }

    private static String explain(Side before, Side onDelivered) {
        if (!before.red() && onDelivered.green()) {
            return "The acceptance test passes WITHOUT the delivered code, so the delivered code is "
                + "not what made it pass. A test like that goes green for every candidate and for "
                + "no candidate alike: it measures itself. The story was stamped delivered on "
                + "nothing.";
        }
        if (before.red() && !onDelivered.green()) {
            return "The acceptance test is still not green on the delivered commit, so the story "
                + "was stamped delivered on a check that does not hold.";
        }
        return "The acceptance test neither failed before the delivered commit nor passed on it, "
            + "so nothing here establishes that the delivered code was ever exercised.";
    }
}
