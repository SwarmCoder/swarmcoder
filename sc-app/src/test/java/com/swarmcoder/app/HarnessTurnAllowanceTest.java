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

import com.swarmcoder.domain.TurnAllowance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheap, model-free proof of {@link EndToEndLoopTest#resolveTurnAllowance()} — the seam that
 * closes harness run 11: two workers killed at exactly turn 25 with {@code BUDGET_EXCEEDED} and
 * no compaction anywhere in sight, because this harness used to hardcode a default of 24 tool
 * turns instead of resolving the allowance production actually gives a worker.
 *
 * <p>No live model, no worktree, no store — this only proves the number and the sentence that
 * ends up in the run's ledger line (see {@code EndToEndLoopTest.walk}, {@code L_FIXTURE}), which
 * is the harness's own equivalent of {@code HarnessModelBudgetTest}'s
 * {@code theLedgerLineNamesServedContextWorkingContextConcurrencyAndTheCompactionTrigger}.
 */
class HarnessTurnAllowanceTest {

    @BeforeEach
    @AfterEach
    void noOverrideInForce() {
        // A leaked -Dswarmcoder.e2e.turns from another test or the invoking command line would
        // make this test pass or fail for the wrong reason, so both directions are guarded.
        System.clearProperty("swarmcoder.e2e.turns");
    }

    @Test
    void withNoConfigOverrideEveryWorkerGetsTheBuiltInOneHundredAndTwenty() {
        TurnAllowance allowance = EndToEndLoopTest.resolveTurnAllowance();

        assertThat(allowance.maxToolTurns())
            .describedAs("the harness's own number, not production's, is exactly the bug harness "
                + "run 11 hit: two workers killed at turn 25 with no compaction anywhere")
            .isEqualTo(TurnAllowance.BUILT_IN_MAX_TOOL_TURNS)
            .isEqualTo(120);
    }

    @Test
    void theLedgerLineNamesTheAllowanceNextToTheContextBudget() {
        // What EndToEndLoopTest.walk appends to the L_FIXTURE ledger line, verbatim: "; " +
        // allowance.sentence(), right after modelBudget.describe(). Proving the sentence itself
        // carries the number is what proves the ledger states it, without needing a live model to
        // walk the whole chain and read the ledger back out.
        String sentence = EndToEndLoopTest.resolveTurnAllowance().sentence();

        assertThat(sentence)
            .describedAs("a future reader must see the turn allowance without opening a log, the "
                + "same way the working-context budget already reads in this ledger line")
            .contains("120 tool turns")
            .contains("each worker");
    }

    @Test
    void aStatedOverrideStillWins() {
        // The harness's own knob (-Dswarmcoder.e2e.turns) is not removed, only no longer the
        // default: it resolves through the SAME TurnAllowance.resolve seam production uses, at the
        // settings-file layer.
        System.setProperty("swarmcoder.e2e.turns", "40");
        try {
            TurnAllowance allowance = EndToEndLoopTest.resolveTurnAllowance();
            assertThat(allowance.maxToolTurns()).isEqualTo(40);
        } finally {
            System.clearProperty("swarmcoder.e2e.turns");
        }
    }
}
