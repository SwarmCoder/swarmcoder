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
package com.swarmcoder.inference;

import com.swarmcoder.domain.KillReason;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The back-off arithmetic on the owner's own numbers: 462103 key/value cache tokens shared by 8
 * requests on the 2026-08 Spark, 51200 tokens of room each in force at startup, a 262144-token
 * single-request ceiling. Every figure asserted below is that pool divided by a smaller count,
 * less the 10% headroom, rounded down to a whole 1024 — the same derivation
 * {@link ServerCapabilities} used at startup, with the divisor turned.
 */
class AdaptiveConcurrencyTest {

    private static final String SPARK = "http://192.168.0.10:8002/v1";
    private static final String CLOUD = "https://api.example.com/v1";
    private static final int POOL = 462_103;
    private static final int SERVED = 262_144;
    private static final int AT_EIGHT = 51_200;
    private static final int AT_FOUR = 103_424;   // 462103/4 = 115525 * 0.9 = 103972 -> 101 * 1024
    private static final int AT_TWO = 207_872;    // 462103/2 = 231051 * 0.9 = 207945 -> 203 * 1024
    private static final int AT_ONE = SERVED;     // 415892 derived, capped at the single-request ceiling

    private static final ServerCapabilities SPARK_CAPS =
        new ServerCapabilities("http://192.168.0.10:8002", SERVED, 8, POOL, "answered");

    private static ModelQuirks inForce(int working, int concurrency) {
        return new ModelQuirks("spark model", 4096, false, null, null, true, true, false,
            SERVED, working, 1024, concurrency, true);
    }

    private static final ContextDeath TIMED_OUT = new ContextDeath(KillReason.TIMEOUT, 70);
    private static final ContextDeath COMPACTION_FAILED = new ContextDeath(KillReason.BUDGET_EXCEEDED, 41);

    private static AdaptiveConcurrency spark() {
        AdaptiveConcurrency controller = new AdaptiveConcurrency();
        controller.register("qwen", SPARK, inForce(AT_EIGHT, 8), SPARK_CAPS, false, 8, false);
        return controller;
    }

    /** One wave's worth of room deaths: every one of them ran at the count in force when it began. */
    private static void deaths(AdaptiveConcurrency c, String profile, int n) {
        int ranAt = c.plan(profile).concurrency();
        for (int i = 0; i < n; i++) {
            c.completed(profile, "worker " + i, KillReason.BUDGET_EXCEEDED, COMPACTION_FAILED, ranAt);
        }
    }

    /** One wave's worth of clean finishes, likewise all at the count in force when it began. */
    private static void clean(AdaptiveConcurrency c, String profile, int n) {
        int ranAt = c.plan(profile).concurrency();
        for (int i = 0; i < n; i++) {
            c.completed(profile, "worker " + i, null, null, ranAt);
        }
    }

    @Test
    void startsExactlyWhereTheProcessStartedAndNeverBelowOne() {
        AdaptiveConcurrency c = spark();
        AdaptiveConcurrency.Plan plan = c.plan("qwen");
        assertThat(plan.concurrency()).isEqualTo(8);
        assertThat(plan.workingContextTokens()).isEqualTo(AT_EIGHT);
        assertThat(plan.throttled()).isFalse();
        assertThat(c.throttled()).isEmpty();
        assertThat(c.plan("nobody-registered-this")).isSameAs(AdaptiveConcurrency.Plan.UNMANAGED);
    }

    @Test
    void halfOfAWaveDyingOfRoomHalvesTheCountAndDoublesTheRoom() {
        AdaptiveConcurrency c = spark();
        // The 2026-09-01 incident: the trailing eight completions held four room deaths.
        clean(c, "qwen", 4);
        deaths(c, "qwen", 3);
        assertThat(c.plan("qwen").concurrency()).as("three of eight is not yet half").isEqualTo(8);
        deaths(c, "qwen", 1);
        AdaptiveConcurrency.Plan plan = c.plan("qwen");
        assertThat(plan.concurrency()).isEqualTo(4);
        assertThat(plan.workingContextTokens()).isEqualTo(AT_FOUR);
        assertThat(plan.throttled()).isTrue();
        assertThat(c.throttled()).singleElement().asString()
            .contains("4 workers at a time instead of 8")
            .contains(AT_FOUR + " tokens of room instead of " + AT_EIGHT)
            .contains("4 of the last 8 workers on it ran out of room");

        // Two of the next four at the new size, and it halves again; then one of two; then floor.
        deaths(c, "qwen", 2);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(2);
        assertThat(c.plan("qwen").workingContextTokens()).isEqualTo(AT_TWO);
        deaths(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(1);
        assertThat(c.plan("qwen").workingContextTokens())
            .as("one worker gets the whole pool, capped at the single-request ceiling")
            .isEqualTo(AT_ONE);
        deaths(c, "qwen", 3);
        assertThat(c.plan("qwen").concurrency()).as("one is the floor").isEqualTo(1);
    }

    @Test
    void theDeathsThatTriggeredAStepDoNotTriggerTheNextOne() {
        AdaptiveConcurrency c = spark();
        // A whole wave of eight dying: the fourth death is the step, and the four that land after
        // it ran at eight too, so they are about eight and do not cascade four to two to one.
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        deaths(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        deaths(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(2);
    }

    @Test
    void endingsThatSayNothingAboutRoomAreNeitherDeathsNorCleanFinishes() {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        // An outage wave, a superseded worker, a git failure: none of them advance a recovery.
        for (int i = 0; i < 20; i++) {
            c.completed("qwen", "w", KillReason.ENDPOINT_OUTAGE, null, 4);
            c.completed("qwen", "w", KillReason.WORKER_ERROR, null, 4);
            c.completed("qwen", "w", KillReason.SUPERSEDED, null, 4);
            c.completed("qwen", "w", KillReason.SANDBOX_UNAVAILABLE, null, 4);
        }
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        // A worker killed for something else entirely IS a finish that fitted its room.
        for (int i = 0; i < 8; i++) {
            c.completed("qwen", "w", KillReason.NO_PROGRESS, null, 4);
        }
        assertThat(c.plan("qwen").concurrency()).isEqualTo(8);
    }

    /**
     * The 2026-09-03 split, seen from this class's side: a worker killed {@code TURN_CAP} used up
     * its turn allowance, not its room, so it must count exactly like {@code NO_PROGRESS} above — a
     * finish that fitted its room, never a death, and never ignored either. Before the split this
     * ending arrived as {@code BUDGET_EXCEEDED} and this class had no way to tell it apart from a
     * real compaction failure, so a run full of workers merely running long could halve the
     * concurrency for a server that was never actually short of room.
     */
    @Test
    void turnCapCountsAsARoomFitLikeAnyOtherNonRoomKill() {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);

        // ContextDeath.of, exactly as WorkerLoop calls it: a TURN_CAP ending is never a death.
        for (int i = 0; i < 8; i++) {
            KillReason reason = KillReason.TURN_CAP;
            ContextDeath death = ContextDeath.of(reason, 121, 120, false);
            assertThat(death).isNull();
            c.completed("qwen", "w", reason, death, 4);
        }
        assertThat(c.plan("qwen").concurrency())
            .describedAs("eight workers that ran out of turns, not room, recovered the count "
                + "exactly the way eight NO_PROGRESS finishes do")
            .isEqualTo(8);
    }

    @Test
    void recoversOneStepAfterTwoCleanWavesAtTheCurrentSize() {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        clean(c, "qwen", 7);
        assertThat(c.plan("qwen").concurrency()).as("seven clean is short of two waves of four").isEqualTo(4);
        clean(c, "qwen", 1);
        AdaptiveConcurrency.Plan plan = c.plan("qwen");
        assertThat(plan.concurrency()).isEqualTo(8);
        assertThat(plan.workingContextTokens()).as("back where it started").isEqualTo(AT_EIGHT);
        assertThat(plan.throttled()).isFalse();
        assertThat(c.throttled()).isEmpty();
    }

    @Test
    void aDeathDuringRecoveryStartsTheCleanCountAgain() {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);
        clean(c, "qwen", 6);
        deaths(c, "qwen", 1);   // one of four: no step down, but the clean run is broken
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        clean(c, "qwen", 7);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        clean(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(8);
    }

    @Test
    void aFailedProbeDoublesTheWaitAndAProbeThatHoldsResetsIt() {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);            // 8 -> 4
        clean(c, "qwen", 8);             // 4 -> 8: a probe
        assertThat(c.plan("qwen").concurrency()).isEqualTo(8);
        deaths(c, "qwen", 4);            // the probe failed: 8 -> 4
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        clean(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).as("two waves no longer suffice").isEqualTo(4);
        clean(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).as("four waves do").isEqualTo(8);
        // A probe that fails again would push the wait to eight waves.
        deaths(c, "qwen", 4);
        clean(c, "qwen", 8 * 4 - 1);
        assertThat(c.plan("qwen").concurrency()).as("seven waves of four are not eight").isEqualTo(4);
        clean(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(8);
        // This time the eight holds for its own requirement (eight waves of eight): it was not a
        // failed probe, so the next back-off is a fresh one and recovers after two waves again.
        clean(c, "qwen", 8 * 8);
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        clean(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).as("the wait is back to two waves").isEqualTo(8);
    }

    @Test
    void theOperatorsCeilingIsWhereItStartsAndTheMostItEverGoesBackTo() {
        AdaptiveConcurrency c = new AdaptiveConcurrency();
        c.register("qwen", SPARK, inForce(AT_EIGHT, 8), SPARK_CAPS, false, 3, true);
        AdaptiveConcurrency.Plan start = c.plan("qwen");
        assertThat(start.concurrency()).isEqualTo(3);
        assertThat(start.workingContextTokens())
            .as("nothing changes at the start, whatever the ceiling").isEqualTo(AT_EIGHT);

        deaths(c, "qwen", 2);            // half of three, rounded up, is two: 3 -> 1
        assertThat(c.plan("qwen").concurrency()).isEqualTo(1);
        assertThat(c.plan("qwen").workingContextTokens()).isEqualTo(AT_ONE);
        clean(c, "qwen", 2);             // 1 -> 2
        assertThat(c.plan("qwen").concurrency()).isEqualTo(2);
        assertThat(c.plan("qwen").workingContextTokens()).isEqualTo(AT_TWO);
        clean(c, "qwen", 4);             // 2 -> 3, not 4
        assertThat(c.plan("qwen").concurrency()).isEqualTo(3);
        assertThat(c.plan("qwen").workingContextTokens()).isEqualTo(AT_EIGHT);
        clean(c, "qwen", 40);
        assertThat(c.plan("qwen").concurrency()).as("never above the ceiling").isEqualTo(3);
    }

    @Test
    void aRoomTheOperatorPinnedIsNeverRaised() {
        AdaptiveConcurrency c = new AdaptiveConcurrency();
        c.register("qwen", SPARK, inForce(30_000, 8), SPARK_CAPS, true, 8, false);
        deaths(c, "qwen", 8);
        AdaptiveConcurrency.Plan plan = c.plan("qwen");
        assertThat(plan.concurrency()).isEqualTo(4);
        assertThat(plan.workingContextTokens()).isEqualTo(30_000);
    }

    @Test
    void aServerThatNeverReportedAPoolGetsFewerWorkersButNoMoreRoom() {
        AdaptiveConcurrency c = new AdaptiveConcurrency();
        c.register("qwen", SPARK, inForce(32_768, 8), ServerCapabilities.NOTHING, false, 8, false);
        deaths(c, "qwen", 8);
        AdaptiveConcurrency.Plan plan = c.plan("qwen");
        assertThat(plan.concurrency()).isEqualTo(4);
        assertThat(plan.workingContextTokens()).isEqualTo(32_768);
    }

    @Test
    void oneEndpointsDeathsNeverThrottleAnother() {
        AdaptiveConcurrency c = spark();
        c.register("deepseek", CLOUD, inForce(AT_EIGHT, 8), ServerCapabilities.NOTHING, false, 8, false);
        deaths(c, "qwen", 8);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        assertThat(c.plan("deepseek").concurrency()).isEqualTo(8);
        assertThat(c.plan("deepseek").throttled()).isFalse();
        assertThat(c.throttled()).hasSize(1);
    }

    @Test
    void twoProfilesOnOneServerShareItsState() {
        AdaptiveConcurrency c = spark();
        c.register("qwen-b", SPARK + "/", inForce(AT_EIGHT, 8), SPARK_CAPS, false, 8, false);
        deaths(c, "qwen", 3);
        deaths(c, "qwen-b", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(4);
        assertThat(c.plan("qwen-b").concurrency()).isEqualTo(4);
        assertThat(c.plan("qwen-b").workingContextTokens()).isEqualTo(AT_FOUR);
    }

    @Test
    void onlyAsManyWorkersAsTheCountRunAtOnce() throws Exception {
        AdaptiveConcurrency c = spark();
        deaths(c, "qwen", 8);
        deaths(c, "qwen", 2);
        deaths(c, "qwen", 1);
        assertThat(c.plan("qwen").concurrency()).isEqualTo(1);

        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            Thread.ofVirtual().start(() -> {
                c.holding("qwen", "w", () -> {
                    int now = inside.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(1);
        assertThat(c.holding("nobody", "w", () -> "ran")).isEqualTo("ran");
    }

    @Test
    void onlyTheTwoRoomDeathsCount() {
        assertThat(ContextDeath.of(KillReason.TIMEOUT, 70, 120, false)).isNotNull();
        assertThat(ContextDeath.of(KillReason.BUDGET_EXCEEDED, 41, 120, false))
            .as("the compaction's stop").isNotNull();
        assertThat(ContextDeath.of(KillReason.BUDGET_EXCEEDED, 121, 120, false))
            .as("defensive: BUDGET_EXCEEDED is never actually seen one turn past the allowance "
                + "any more, since 2026-09-03 that ending is its own KillReason.TURN_CAP, but the "
                + "sanity check stays").isNull();
        assertThat(ContextDeath.of(KillReason.BUDGET_EXCEEDED, 41, 120, true))
            .as("the worker's own token-budget guard").isNull();
        assertThat(ContextDeath.of(KillReason.TURN_CAP, 121, 120, false))
            .as("the turn cap is its own reason now and is never a death - it says nothing about "
                + "whether the room it had was enough").isNull();
        assertThat(ContextDeath.of(KillReason.NO_PROGRESS, 41, 120, false)).isNull();
        assertThat(ContextDeath.of(KillReason.ENDPOINT_OUTAGE, 41, 120, false)).isNull();
        assertThat(ContextDeath.of(null, 12, 120, false)).isNull();
        // since live run 74 the sentence names both causes: the answer too long, or the conversation too big
        assertThat(TIMED_OUT.sentence()).contains("70 turns").contains("too long to finish")
            .contains("too big to read");
        assertThat(ContextDeath.of(KillReason.TIMEOUT, 70, 120, false, true))
            .as("a timeout on a small conversation says nothing about room: the answer was too "
                + "long for the time allowed, so concurrency must not back off for it").isNull();
        assertThat(ContextDeath.of(KillReason.TIMEOUT, 70, 120, false, false)).isNotNull();
    }

    @Test
    void theDerivationIsTheStartupOneWithTheDivisorTurned() {
        assertThat(SPARK_CAPS.derivedWorkingContextTokens()).isEqualTo(AT_EIGHT);
        assertThat(SPARK_CAPS.derivedWorkingContextTokens(8)).isEqualTo(AT_EIGHT);
        assertThat(SPARK_CAPS.derivedWorkingContextTokens(4)).isEqualTo(AT_FOUR);
        assertThat(SPARK_CAPS.derivedWorkingContextTokens(2)).isEqualTo(AT_TWO);
        assertThat(SPARK_CAPS.derivedWorkingContextTokens(1)).isEqualTo(SERVED);
        assertThat(ServerCapabilities.NOTHING.derivedWorkingContextTokens(1)).isZero();
    }
}
