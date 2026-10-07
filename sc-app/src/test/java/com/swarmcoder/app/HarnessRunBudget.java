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

/**
 * How long this harness will wait for a run before it gives up — sized to the workers it is
 * actually about to dispatch, not to a number that predates the turn allowance they get.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Harness run 12 (2026-09-03) dispatched two workers with the product's own turn allowance
 * ({@link TurnAllowance#BUILT_IN_MAX_TOOL_TURNS}, 120), then waited only the harness's flat
 * 25-minute default. At minute 17 both workers were alive and visibly working — writing files,
 * recovering from a rejected {@code apply_diff} on {@code pom.xml}, nudges resetting after every
 * write — and at 1,501 seconds the deadline fired anyway. The 25-minute number was sized for the
 * harness's old, hardcoded 24-turn default (see {@link EndToEndLoopTest#resolveTurnAllowance()}'s
 * javadoc); nothing recomputed it when that cap was raised to production's real number.
 *
 * <p>Harness run 22 (2026-09-03) then showed the first formula, sized for a single build round,
 * still under-counted: three waves built cleanly in 47 minutes, but the last wave's two candidates
 * both failed their acceptance test, so a repair round dispatched — two workers per failed seed,
 * four workers total for two seeds — and with thinking on and four workers sharing one GPU, each
 * turn took about 90 seconds, not the 40s the first formula assumed. At 90 minutes the harness
 * stopped with workers still visibly turning over: "stopped waiting after 90 minutes while 4
 * worker(s) were still running (turns so far: [worker 111: turn 22, worker 110: turn 14, worker
 * 100: turn 10, worker 101: turn 18])". The honest stop line worked; the budget it stopped at did
 * not cover the repair round the run actually needed.
 *
 * <h2>The arithmetic</h2>
 *
 * <p>{@code budget = overhead + buildWork + repairWork}, capped by
 * {@code -Dswarmcoder.e2e.maxMinutes} (default {@value #DEFAULT_MAX_MINUTES}):
 *
 * <ul>
 *   <li><b>overhead</b> ({@value #LINKS_OVERHEAD_MINUTES} minutes) — the eleven links before and
 *       after the swarm itself: document ingest, the analyst, the planner, the architect, the
 *       acceptance-test author, verification, the judge, selection, integration. Each waits on a
 *       live model call of its own.</li>
 *   <li><b>waves</b> ({@code -Dswarmcoder.e2e.waves}, default 1) — how many sequential rounds of
 *       tasks the architect's plan is expected to need. This harness builds exactly ONE story, and
 *       every run logged so far has planned it as one task (run 12: "one task, two workers"), so
 *       one wave is the harness's own documented case, not a guess pulled from nowhere. A story
 *       that plans an enabler task ahead of the main one needs {@code -Dswarmcoder.e2e.waves=2}.</li>
 *   <li><b>workersPerTask</b> — {@code -Dswarmcoder.e2e.workers} (default 2), the same number
 *       already dispatched per task's build round.</li>
 *   <li><b>turns</b> — {@link TurnAllowance#maxToolTurns()}, resolved through the exact seam
 *       production uses (see {@link EndToEndLoopTest#resolveTurnAllowance()}), not a harness
 *       number.</li>
 *   <li><b>secondsPerTurn</b> ({@value #OBSERVED_SECONDS_PER_TURN}s) — measured on harness run 22,
 *       with thinking on and four workers sharing one GPU; the earlier 40s figure was measured
 *       with only two workers competing for the same card.</li>
 *   <li><b>buildWork</b> — {@code waves × workersPerTask × turns × secondsPerTurn / parallelism},
 *       with parallelism taken to equal {@code workersPerTask} itself (this harness's workers run
 *       genuinely concurrently; see {@link HarnessModelBudget}'s class doc), so the two terms
 *       cancel and this reduces to {@code waves × turns × secondsPerTurn} — one worker's full
 *       allowance, since the rest run alongside it for free.</li>
 *   <li><b>repairWork</b> — run 22's repair round dispatched two workers per failed seed, i.e.
 *       {@code 2 × workersPerTask} workers for that round, all running concurrently, so its
 *       parallelism is {@code 2 × workersPerTask} too. Those terms cancel exactly as the build
 *       round's do, leaving the same {@code waves × turns × secondsPerTurn} again — a second,
 *       independent worker-allowance's worth of wall clock, because a repair round is not
 *       optional and a run that never needs one still finishes early against its deadline.</li>
 * </ul>
 *
 * <p>For the harness's own defaults — one wave, two workers, the product's 120-turn allowance —
 * that is 120 × 90s = 10,800s = 180 minutes of build time, plus another 180 minutes of repair
 * time, plus 10 minutes of overhead: 370 minutes, which the default cap of
 * {@value #DEFAULT_MAX_MINUTES} minutes holds down. A run that needs more than that says so by
 * naming {@code -Dswarmcoder.e2e.maxMinutes} explicitly rather than by silently running for
 * however long the arithmetic happens to produce.
 *
 * <p>{@code -Dswarmcoder.e2e.minutes} — the harness's pre-existing knob that STATES a budget
 * directly — still wins outright when set, exactly as before; this class only replaces what
 * happens when nothing states one.
 */
final class HarnessRunBudget {

    private HarnessRunBudget() {
    }

    /** Ten minutes for the links before and after the swarm — see the class doc. */
    static final int LINKS_OVERHEAD_MINUTES = 10;

    /**
     * Measured on harness run 22: thinking on, four workers sharing one GPU during the repair
     * round, about 90 seconds per turn — see the class doc.
     */
    static final int OBSERVED_SECONDS_PER_TURN = 90;

    /** The ceiling nothing may exceed without saying so explicitly. */
    static final int DEFAULT_MAX_MINUTES = 180;

    /** What was computed, together with the sentence link 1's ledger line states it with. */
    record Budget(long millis, String sentence) {

        long minutes() {
            return millis / 60_000;
        }
    }

    /**
     * The budget this run gets: {@code -Dswarmcoder.e2e.minutes} verbatim when it names one, else
     * the formula above, read off system properties for {@code -Dswarmcoder.e2e.waves} (default
     * 1) and {@code -Dswarmcoder.e2e.maxMinutes} (default {@value #DEFAULT_MAX_MINUTES}).
     *
     * @param workersPerTask how many workers this run dispatches per task's build round
     * @param allowance      the turn allowance resolved for this run's workers
     */
    static Budget resolve(int workersPerTask, TurnAllowance allowance) {
        Long statedMinutes = Long.getLong("swarmcoder.e2e.minutes");
        if (statedMinutes != null && statedMinutes > 0) {
            return new Budget(statedMinutes * 60_000,
                "this run may take up to " + statedMinutes + " minutes, because "
                    + "-Dswarmcoder.e2e.minutes states that directly");
        }
        int waves = Integer.getInteger("swarmcoder.e2e.waves", 1);
        int maxMinutes = Integer.getInteger("swarmcoder.e2e.maxMinutes", DEFAULT_MAX_MINUTES);
        return compute(waves, workersPerTask, allowance, maxMinutes);
    }

    /** The arithmetic itself, cheap enough to call from a test that needs no live model at all. */
    static Budget compute(int waves, int workersPerTask, TurnAllowance allowance, int maxMinutesCap) {
        int wavesUsed = Math.max(1, waves);
        int workers = Math.max(1, workersPerTask);
        // The build round: this harness's build workers run genuinely in parallel (see the class
        // doc), so parallelism is taken to equal workersPerTask — the two terms cancel and only
        // the per-wave worker time survives.
        int buildParallelism = workers;
        // The repair round: run 22 dispatched two workers per failed seed, so this round runs
        // 2 x workersPerTask workers, all concurrently — its parallelism is that same doubled
        // count, and it cancels the same way.
        int repairWorkers = 2 * workers;
        int repairParallelism = repairWorkers;
        long perTurnMillis = OBSERVED_SECONDS_PER_TURN * 1000L;
        long overheadMillis = LINKS_OVERHEAD_MINUTES * 60_000L;
        long buildMillis = (long) wavesUsed * workers * allowance.maxToolTurns() * perTurnMillis
            / buildParallelism;
        long repairMillis = (long) wavesUsed * repairWorkers * allowance.maxToolTurns() * perTurnMillis
            / repairParallelism;
        long workMillis = buildMillis + repairMillis;
        long uncappedMillis = overheadMillis + workMillis;
        long capMillis = (long) maxMinutesCap * 60_000L;
        long millis = Math.min(uncappedMillis, capMillis);

        StringBuilder sentence = new StringBuilder("this run may take up to ")
            .append(millis / 60_000).append(" minutes: ")
            .append(allowance.maxToolTurns()).append(" turns × ").append(OBSERVED_SECONDS_PER_TURN)
            .append("s/turn × ").append(wavesUsed).append(" wave(s), build round ").append(workers)
            .append(" worker(s) / ").append(buildParallelism).append(" parallel = ")
            .append(buildMillis / 60_000).append(" min + repair round ").append(repairWorkers)
            .append(" worker(s) / ").append(repairParallelism).append(" parallel = ")
            .append(repairMillis / 60_000).append(" min, + ").append(LINKS_OVERHEAD_MINUTES)
            .append(" min overhead");
        if (uncappedMillis > capMillis) {
            sentence.append(" (capped at ").append(maxMinutesCap)
                .append(" minutes by -Dswarmcoder.e2e.maxMinutes)");
        }
        return new Budget(millis, sentence.toString());
    }
}
