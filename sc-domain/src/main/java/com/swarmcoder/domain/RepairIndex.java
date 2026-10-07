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

/**
 * The worker index of a repair attempt: which failed attempt it is fixing, and which try it is.
 *
 * <p>When every candidate on a task fails verification, the engine does not give up - it dispatches
 * a repair round, seeded from the most promising failures with the failure evidence in the prompt
 * (spec §11.5). Those workers need indices of their own, and they get them from here.
 *
 * <h2>Why the scheme lives in one class</h2>
 *
 * <p>It was a literal in the engine and nothing else could read it. The operator watched four
 * workers become four workers numbered <b>w100, w101, w110, w111</b> and said: "Now they seem to
 * have restarted? More workers on the same task? This is confusing?" The system had done the right
 * thing; the number was the only thing on screen and it meant nothing to anybody outside the
 * method that produced it.
 *
 * <p>So the number now encodes the attempt it is REPAIRING rather than the seed's rank in an
 * internal sort - the two are not the same, and only the first is a fact anybody outside the engine
 * can use. w120 and w121 are the two goes at fixing what w2 got wrong. The engine writes them
 * through {@link #of}, and the run graph reads them back through {@link #isRepair},
 * {@link #repairOf} and {@link #attempt}, so there is one definition of the scheme rather than a
 * literal at each end.
 *
 * <h2>Its limits, stated</h2>
 *
 * <p>One decade per repaired attempt, starting at 100: w1<b>2</b><b>1</b> is the second go at w2.
 * Any number of first-wave workers can be named this way - w10 gets the decade at 200 - so the one
 * real limit is ten goes at a single failed attempt, and the engine takes two. {@link #of} refuses
 * an eleventh rather than producing a number that decodes to the wrong worker.
 */
public final class RepairIndex {

    /** Repair indices start here, well clear of any first-wave worker. */
    public static final int BASE = 100;

    /** How many indices each repaired attempt owns. */
    public static final int PER_ATTEMPT = 10;

    private RepairIndex() {
    }

    /**
     * The index for one repair worker.
     *
     * @param repairing the worker index of the failed attempt this one is fixing
     * @param attempt   which go this is, counted from zero
     * @throws IllegalArgumentException when there is no such worker, or when this is the
     *                                  eleventh go at one failed attempt
     */
    public static int of(int repairing, int attempt) {
        if (repairing < 0) {
            throw new IllegalArgumentException(
                "cannot name a repair of worker " + repairing + ": there is no such worker");
        }
        if (attempt < 0 || attempt >= PER_ATTEMPT) {
            throw new IllegalArgumentException(
                "cannot name repair attempt " + attempt + ": the scheme has room for "
                    + PER_ATTEMPT + " goes at each failed attempt");
        }
        return BASE + repairing * PER_ATTEMPT + attempt;
    }

    /** Whether this worker index belongs to a repair round rather than to a first wave. */
    public static boolean isRepair(int workerIndex) {
        return workerIndex >= BASE;
    }

    /**
     * The worker whose failure this one is repairing, or -1 when it is not a repair at all.
     */
    public static int repairOf(int workerIndex) {
        return isRepair(workerIndex) ? (workerIndex - BASE) / PER_ATTEMPT : -1;
    }

    /** Which go at that repair this is, counted from zero; -1 when it is not a repair at all. */
    public static int attempt(int workerIndex) {
        return isRepair(workerIndex) ? (workerIndex - BASE) % PER_ATTEMPT : -1;
    }
}
