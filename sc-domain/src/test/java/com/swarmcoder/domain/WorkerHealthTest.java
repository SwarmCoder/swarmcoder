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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether a worker still running is heading for trouble.
 *
 * <p>The shape of this test is the measurement it was written from. On 2026-09-01 eight workers
 * finished (8 to 21 turns, 16k to 42k tokens) and four died (62, 72, 74 and 99 turns), every one of
 * the four killed by a single request that outlasted the 15-minute timeout. So the cases below are
 * a healthy worker, a worker past the context its model allows, and a worker most of the way
 * through the only time one request gets.
 */
class WorkerHealthTest {

    private static final long NOW = 1_800_000_000_000L;
    /** What the deployed model profile allows one session — {@code ModelQuirks.workingContextTokens}. */
    private static final long BUDGET = 32_768;

    @Test
    void aWorkerInsideItsBudgetAndTalkingRecentlyIsFine() {
        assertThat(WorkerHealth.of(20_461, BUDGET, "TOOL_RESULT", NOW - 30_000, NOW))
            .describedAs("11 turns and 20k tokens is what a worker that FINISHES looks like")
            .isEqualTo(WorkerHealth.Kind.FINE);
        assertThat(WorkerHealth.isWarning(WorkerHealth.Kind.FINE)).isFalse();
    }

    @Test
    void aConversationPastWhatTheModelAllowsIsMarked() {
        WorkerHealth.Kind kind =
            WorkerHealth.of(42_000, BUDGET, "TOOL_RESULT", NOW - 5_000, NOW);
        assertThat(kind).isEqualTo(WorkerHealth.Kind.OVER_BUDGET);
        assertThat(WorkerHealth.isWarning(kind))
            .describedAs("nothing enforces the budget, so passing it is the operator's warning "
                + "and not the system's - this is how a worker reaches 99 turns")
            .isTrue();
        assertThat(WorkerHealth.warning(kind, 42_000, BUDGET, NOW - 5_000, NOW))
            .describedAs("and the words say both numbers, because one without the other is "
                + "not something anybody can act on")
            .contains("42k").contains("33k");
    }

    @Test
    void aRequestMostOfTheWayThroughItsOnlyTimeBudgetIsMarked() {
        long silent = WorkerHealth.SILENT_WARN_MILLIS + 1000;
        assertThat(WorkerHealth.REQUEST_TIMEOUT_MILLIS)
            .describedAs("the agent framework's own request timeout, used unmodified - "
                + "the wall every worker that died was killed against")
            .isEqualTo(900_000L);
        WorkerHealth.Kind kind =
            WorkerHealth.of(9_000, BUDGET, "TOOL_RESULT", NOW - silent, NOW);
        assertThat(kind).isEqualTo(WorkerHealth.Kind.WAITING_TOO_LONG);
        assertThat(WorkerHealth.warning(kind, 9_000, BUDGET, NOW - silent, NOW))
            .contains("waiting on the model").contains("15m");
    }

    /**
     * The false positive that would have made the colour worthless.
     *
     * <p>Between a tool call and its result the worker is waiting for a BUILD, and a ten-minute
     * Maven run is an ordinary thing. Counting that silence would paint every honest compile
     * orange, and a warning that fires on healthy work is worth less than no warning at all.
     */
    @Test
    void aLongToolRunIsNotAWorkerInTrouble() {
        long silent = WorkerHealth.REQUEST_TIMEOUT_MILLIS;
        assertThat(WorkerHealth.of(9_000, BUDGET, "TOOL_CALL", NOW - silent, NOW))
            .isEqualTo(WorkerHealth.Kind.FINE);
        assertThat(WorkerHealth.of(9_000, BUDGET, "LLM_RESPONSE", NOW - silent, NOW))
            .isEqualTo(WorkerHealth.Kind.FINE);
    }

    @Test
    void aWorkerWhoseNumbersHaveNotArrivedIsNeitherWellNorSick() {
        assertThat(WorkerHealth.of(0, BUDGET, "SESSION_OPENED", NOW - 2_000, NOW))
            .describedAs("no token count yet - every worker's first seconds")
            .isEqualTo(WorkerHealth.Kind.UNKNOWN);
        assertThat(WorkerHealth.of(90_000, 0, "TOOL_RESULT", NOW - 2_000, NOW))
            .describedAs("a token count with nothing to measure it against says nothing: 90k is "
                + "comfortable on a model allowed 262k and fatal on one allowed 32k")
            .isEqualTo(WorkerHealth.Kind.UNKNOWN);
        assertThat(WorkerHealth.isWarning(WorkerHealth.Kind.UNKNOWN)).isFalse();
        assertThat(WorkerHealth.warning(WorkerHealth.Kind.UNKNOWN, 0, 0, 0, NOW)).isEmpty();
    }

    /**
     * A worker that is BOTH over budget and stuck in a long request reads as stuck.
     *
     * <p>The operator gets one sentence and has to be able to act on it. Over budget is danger;
     * eleven minutes into a fifteen-minute request is nearly dead.
     */
    @Test
    void theMoreUrgentOfTheTwoIsWhatIsSaid() {
        long silent = WorkerHealth.SILENT_WARN_MILLIS + 1;
        assertThat(WorkerHealth.of(120_000, BUDGET, "TOOL_RESULT", NOW - silent, NOW))
            .isEqualTo(WorkerHealth.Kind.WAITING_TOO_LONG);
    }

    @Test
    void tokenCountsAreShortEnoughForAChip() {
        assertThat(WorkerHealth.tokensWords(0)).isEqualTo("—");
        assertThat(WorkerHealth.tokensWords(840)).isEqualTo("840");
        assertThat(WorkerHealth.tokensWords(4_240)).isEqualTo("4.2k");
        assertThat(WorkerHealth.tokensWords(41_600)).isEqualTo("42k");
        assertThat(WorkerHealth.tokensWords(32_768)).isEqualTo("33k");
    }

    /**
     * The third number: how much of the conversation is the fixed head of the prompt.
     *
     * <p>The head is what every worker on one task sends identically and what no compaction ever
     * rewrites, so the model server prefills it once for the whole group. "8/42k" says eight
     * thousand of the forty-two were paid for once and thirty-four belong to this worker alone.
     * The head loses its unit and borrows the total's because the chip has room for nine characters
     * and not eleven.
     */
    @Test
    void theSplitSaysHowMuchOfTheConversationNeverChanges() {
        assertThat(WorkerHealth.splitWords(8_000, 41_600)).isEqualTo("8/42k");
        assertThat(WorkerHealth.splitWords(8_000, 12_000)).isEqualTo("8/12k");
        assertThat(WorkerHealth.splitWords(8_000, 9_000))
            .describedAs("the total keeps its decimal below ten thousand; the head never has one")
            .isEqualTo("8/9.0k");
        assertThat(WorkerHealth.splitWords(400, 41_600))
            .describedAs("a head too small to round to a thousand still says it is not nothing")
            .isEqualTo("<1/42k");
    }

    /**
     * And every way it can be missing draws exactly the chip that was there before it existed.
     *
     * <p>Which is most chips: a worker that has stopped is never measured, because the head is a
     * fact about a conversation still being sent.
     */
    @Test
    void aMissingOrNonsensicalSplitCostsTheSplitAndNothingElse() {
        assertThat(WorkerHealth.splitWords(0, 41_600))
            .describedAs("no measurement - every worker that has already stopped")
            .isEqualTo("42k");
        assertThat(WorkerHealth.splitWords(50_000, 41_600))
            .describedAs("a head bigger than the whole conversation is an estimate the model "
                + "server's own count has not overtaken yet, and would read as a lie")
            .isEqualTo("42k");
        assertThat(WorkerHealth.splitWords(8_000, 400))
            .describedAs("and a conversation too small for the ratio to mean anything")
            .isEqualTo("400");
        assertThat(WorkerHealth.hasPrefill(0, 41_600)).isFalse();
        assertThat(WorkerHealth.hasPrefill(50_000, 41_600)).isFalse();
        assertThat(WorkerHealth.hasPrefill(8_000, 41_600)).isTrue();
    }
}
