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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.SamplingConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One repair round: how many workers it gets, how they differ, when it is stopped, and what it
 * came to (section 91).
 *
 * <p>Live supervised build, 2026-10-12/13, one local model server whose measured best is four
 * conversations at once. A repair round given on an instruction started two workers while
 * nothing else was running, so half the server's places were empty for the whole round; and the
 * round was stopped by a fixed 30 minute ceiling after 8 and 11 turns, at about three minutes a
 * turn, while both workers were still changing files. The same instruction with 90 minutes
 * succeeded.
 *
 * <h2>How many workers</h2>
 *
 * <p>As many as a first round of the same task gets: the task's workers per task, cut to what
 * its model server is currently allowed when that is fewer ({@code SwarmDispatcher.waveSize}).
 * They take places as a first round's candidates do: the first waits its turn, each further one
 * starts on a place nothing else is waiting for, and when no worker of the round is running the
 * next one waits its turn. They are spread over the round's seeds in turn ({@link #spread}), and
 * no two of one seed are alike ({@link #sampling}).
 *
 * <h2>When it is stopped</h2>
 *
 * <p>When no worker of the round has made progress for a stretch, or at a ceiling
 * ({@link #due}). Progress is what a worker's own stall stop already counts
 * ({@code WorkerToolbox.progressMarks}): a turn after which it had changed a file or been shown
 * a tool result it had not seen before - a build or a test run that came out differently is
 * one. A verification verdict for a worker of the round counts too. Both limits are so many
 * turns' worth of the time a turn takes in this run ({@link TurnPace}), with a floor; a setting
 * replaces either.
 *
 * <p>Everything here is counted or timed; nothing reads text.
 */
final class RepairRound {

    /** Minutes a repair round may run at most; set, it replaces the derived ceiling. */
    static final String ROUND_MINUTES_PROPERTY = "swarmcoder.repair.roundMinutes";

    /** The same in milliseconds, so a test can state a ceiling shorter than a minute. */
    static final String ROUND_MILLIS_PROPERTY = "swarmcoder.repair.roundMillis";

    /** The ceiling is never shorter than this, however fast the server. */
    static final int ROUND_FLOOR_MINUTES = 30;

    /** How many turns' worth of time the ceiling is. */
    static final String ROUND_TURNS_PROPERTY = "swarmcoder.repair.roundTurns";

    /**
     * Thirty: the round this class was written for was stopped at 30 minutes after 8 and 11
     * turns and succeeded when given 90, at about three minutes a turn.
     */
    static final int DEFAULT_ROUND_TURNS = 30;

    /** Minutes without progress after which a round is stopped; set, it replaces the derived one. */
    static final String QUIET_MINUTES_PROPERTY = "swarmcoder.repair.quietMinutes";

    /** The stretch without progress is never shorter than this. */
    static final int QUIET_FLOOR_MINUTES = 15;

    /** Why a round ended. Not stored. */
    enum Ended {
        A_REPAIR_PASSED("a repaired candidate passed verification"),
        EVERY_WORKER_ENDED("every worker ended by itself or by its own stop"),
        NO_PROGRESS("no worker had made progress for the stretch a round may be quiet"),
        TIME_LIMIT("it reached its time limit"),
        INTERRUPTED("the run was interrupted"),
        NOT_STARTED("no worker was started");

        private final String words;

        Ended(String words) {
            this.words = words;
        }

        String words() {
            return words;
        }
    }

    // --- how many, and how they differ ---------------------------------------------------------

    /**
     * How many workers each seed gets: {@code workers} dealt out one at a time in the order of
     * the seeds, a seed taking no more than it has room for.
     *
     * @param workers how many the round gets in all
     * @param room    for each seed, how many more workers can be named after it
     */
    static int[] spread(int workers, int[] room) {
        int[] share = new int[room.length];
        int left = Math.max(0, workers);
        boolean gave = true;
        while (left > 0 && gave) {
            gave = false;
            for (int seed = 0; seed < room.length && left > 0; seed++) {
                if (share[seed] < room[seed]) {
                    share[seed]++;
                    left--;
                    gave = true;
                }
            }
        }
        return share;
    }

    /** The persona of the first two workers of a seed, as it always was; then the others. */
    static final List<String> PERSONAS = personas();

    private static List<String> personas() {
        List<String> order = new ArrayList<>(List.of("defensive-edges"));
        for (String persona : WorkerPersonas.DEFAULT_ROTATION) {
            if (!order.contains(persona)) {
                order.add(persona);
            }
        }
        return List.copyOf(order);
    }

    /**
     * What makes the {@code nth} worker of one seed different from the others of that seed.
     *
     * <p>The first two are what a seed's two repair workers always were: temperature 0.4 and
     * 0.8, both asked to handle edge cases. Each further pair has the same two temperatures and
     * the next persona; when the personas are used up, temperature 0.6 with each persona in
     * turn. No two of the first twelve are alike, and a seed can have ten.
     */
    static SamplingConfig sampling(String family, int nth, int workerIndex) {
        int pairs = PERSONAS.size();
        double temperature;
        String persona;
        if (nth < 2 * pairs) {
            temperature = 0.4 + 0.4 * (nth % 2);
            persona = PERSONAS.get(nth / 2);
        } else {
            temperature = 0.6;
            persona = PERSONAS.get((nth - 2 * pairs) % pairs);
        }
        return new SamplingConfig(family, temperature, workerIndex * 100L, persona, "full-files");
    }

    // --- the limits ----------------------------------------------------------------------------

    /**
     * The longest a round may run, in milliseconds.
     *
     * @param millisPerTurn what a worker turn takes in this run; 0 when none has been measured
     */
    static long ceilingMillis(long millisPerTurn) {
        long millis = Long.getLong(ROUND_MILLIS_PROPERTY, 0L);
        if (millis > 0) {
            return millis;
        }
        int minutes = Integer.getInteger(ROUND_MINUTES_PROPERTY, 0);
        if (minutes > 0) {
            return minutes * 60_000L;
        }
        int turns = Math.max(1, Integer.getInteger(ROUND_TURNS_PROPERTY, DEFAULT_ROUND_TURNS));
        return Math.max(ROUND_FLOOR_MINUTES * 60_000L, turns * Math.max(0, millisPerTurn));
    }

    /** The longest a round may go without any worker making progress, in milliseconds. */
    static long quietMillis(long millisPerTurn) {
        int minutes = Integer.getInteger(QUIET_MINUTES_PROPERTY, 0);
        if (minutes > 0) {
            return minutes * 60_000L;
        }
        return Math.max(QUIET_FLOOR_MINUTES * 60_000L,
            EarlyKillEnforcer.IDLE_TURNS_BEFORE_KILL * Math.max(0, millisPerTurn));
    }

    // --- one round -----------------------------------------------------------------------------

    private static final class Worker {
        long lastProgressAt;
        int turns;
        int progressTurns;
        boolean ended;
    }

    private final String kind;
    private final int wanted;
    private final int placesCeiling;
    private final int placesBusy;
    private final LongSupplier clock;
    private final long openedAt;
    private final Map<Integer, Worker> workers = new LinkedHashMap<>();

    private int planned;
    private boolean firstPlaced;
    private long firstStartedAt;
    private long lastProgressAt;
    private Ended ended;
    private long endedAt;
    private long limitMillis;
    private int stoppedWorkers;
    private int stoppedTurns;
    private boolean progressing;
    private boolean survivor;

    /**
     * @param kind          what the round follows, for its report line
     * @param wanted        how many workers a first round of the task gets
     * @param placesCeiling the most workers that may run at once; 0 when there is no ceiling
     * @param placesBusy    how many places other work held when the round was planned
     */
    RepairRound(String kind, int wanted, int placesCeiling, int placesBusy, LongSupplier clock) {
        this.kind = kind;
        this.wanted = wanted;
        this.placesCeiling = Math.max(0, placesCeiling);
        this.placesBusy = Math.max(0, placesBusy);
        this.clock = clock;
        this.openedAt = clock.getAsLong();
    }

    synchronized void planned(int workersInAll) {
        this.planned = workersInAll;
    }

    /** A worker of the round has its places and starts. */
    synchronized void started(int workerIndex) {
        long now = clock.getAsLong();
        Worker worker = new Worker();
        worker.lastProgressAt = now;
        workers.put(workerIndex, worker);
        if (firstStartedAt == 0) {
            firstStartedAt = now;
        }
        lastProgressAt = now;
    }

    /** The round's first worker has its places, or will never have them. */
    synchronized void firstPlaced() {
        firstPlaced = true;
    }

    synchronized boolean isFirstPlaced() {
        return firstPlaced;
    }

    /** True when no worker of the round is in a session: the next one then waits its turn. */
    synchronized boolean nobodyInSession() {
        return inSession() == 0;
    }

    private int inSession() {
        int running = 0;
        for (Worker worker : workers.values()) {
            if (!worker.ended) {
                running++;
            }
        }
        return running;
    }

    /** One turn of a worker of the round; {@code progressed} as its own stall stop counts it. */
    synchronized void turn(int workerIndex, boolean progressed) {
        Worker worker = workers.get(workerIndex);
        if (worker == null) {
            return;
        }
        worker.turns++;
        if (progressed) {
            long now = clock.getAsLong();
            worker.progressTurns++;
            worker.lastProgressAt = now;
            lastProgressAt = now;
        }
    }

    /** A worker's session is over, however it ended. */
    synchronized void sessionOver(int workerIndex) {
        Worker worker = workers.get(workerIndex);
        if (worker != null) {
            worker.ended = true;
        }
    }

    /** Verification has a verdict for a worker of the round: a new result. */
    synchronized void verdict() {
        lastProgressAt = clock.getAsLong();
    }

    /**
     * Whether the round is to be stopped now, and why; null while it is not. The ceiling counts
     * from when the round's first worker started, not from when it began to wait for a place.
     */
    synchronized Ended due(long millisPerTurn) {
        if (ended != null || firstStartedAt == 0) {
            return null;
        }
        long now = clock.getAsLong();
        if (now - firstStartedAt >= ceilingMillis(millisPerTurn)) {
            return Ended.TIME_LIMIT;
        }
        if (inSession() > 0 && now - lastProgressAt >= quietMillis(millisPerTurn)) {
            return Ended.NO_PROGRESS;
        }
        return null;
    }

    /**
     * The round is stopped: what the workers still in a session had done is kept, before they
     * are told. A worker was still making progress when it has a turn that counted as progress
     * and the last such turn is more recent than the stretch a round may be quiet.
     */
    synchronized void stopped(Ended why, long millisPerTurn) {
        if (ended != null) {
            return;
        }
        long now = clock.getAsLong();
        ended = why;
        limitMillis = why == Ended.TIME_LIMIT ? ceilingMillis(millisPerTurn)
            : why == Ended.NO_PROGRESS ? quietMillis(millisPerTurn) : 0;
        long quiet = quietMillis(millisPerTurn);
        for (Worker worker : workers.values()) {
            if (worker.ended) {
                continue;
            }
            stoppedWorkers++;
            stoppedTurns += worker.turns;
            if (worker.progressTurns > 0 && now - worker.lastProgressAt < quiet) {
                progressing = true;
            }
        }
    }

    /** Every worker has ended and has its verdict. */
    synchronized Outcome finished(boolean aRepairPassed) {
        survivor = aRepairPassed;
        if (ended == null) {
            ended = workers.isEmpty() ? Ended.NOT_STARTED
                : aRepairPassed ? Ended.A_REPAIR_PASSED : Ended.EVERY_WORKER_ENDED;
        }
        endedAt = clock.getAsLong();
        return outcome();
    }

    synchronized Outcome outcome() {
        int turns = 0;
        for (Worker worker : workers.values()) {
            turns += worker.turns;
        }
        long end = endedAt == 0 ? clock.getAsLong() : endedAt;
        return new Outcome(kind, placesCeiling, placesBusy, wanted, planned, workers.size(), turns,
            end - (firstStartedAt == 0 ? openedAt : firstStartedAt), ended, limitMillis,
            stoppedWorkers, stoppedTurns, progressing, survivor);
    }

    /**
     * What a round came to.
     *
     * @param placesCeiling  the most workers that may run at once; 0 when there is no ceiling
     * @param placesBusy     places other work held when the round was planned
     * @param wanted         workers a first round of the task gets
     * @param planned        workers the round set out to start
     * @param started        workers that got a place and ran
     * @param turns          turns of all its workers together
     * @param millis         from its first worker's start to its last verdict
     * @param limitMillis    the limit it was stopped by; 0 when it was not stopped by one
     * @param stoppedWorkers workers still in a session when the round was stopped
     * @param stoppedTurns   the turns those workers had taken
     * @param progressing    whether one of those was still making progress
     * @param aRepairPassed  whether a repaired candidate passed verification
     */
    record Outcome(String kind, int placesCeiling, int placesBusy, int wanted, int planned,
                   int started, int turns, long millis, Ended ended, long limitMillis,
                   int stoppedWorkers, int stoppedTurns, boolean progressing,
                   boolean aRepairPassed) {

        /** Places nothing else held when the round was planned; -1 when there is no ceiling. */
        int placesFree() {
            return placesCeiling == 0 ? -1 : Math.max(0, placesCeiling - placesBusy);
        }

        /**
         * True when the time limit is the only reason the round has no repaired candidate: it
         * was stopped by the ceiling, a stopped worker was still making progress, and nothing
         * passed. More time is then the remedy, not a different instruction.
         */
        boolean onlyTheTimeLimit() {
            return ended == Ended.TIME_LIMIT && progressing && !aRepairPassed;
        }

        /** The round in one line, for the run's log. */
        String line(String taskTitle) {
            return "Repair round for task '" + taskTitle + "' (" + kind + "): places "
                + (placesCeiling == 0 ? "not limited"
                    : placesFree() + " of " + placesCeiling + " free when it was planned")
                + "; workers " + started + " started of " + planned + " planned (a first round "
                + "of this task gets " + wanted + "); " + turns + " turn(s) in "
                + minutes(millis) + " minute(s); ended because "
                + (ended == null ? "it has not ended" : ended.words())
                + (limitMillis > 0 ? " (" + minutes(limitMillis) + " minutes)" : "")
                + (stoppedWorkers > 0 ? ", which stopped " + stoppedWorkers + " worker(s) after "
                    + stoppedTurns + " turn(s) that "
                    + (progressing ? "were still making progress" : "had stopped making progress")
                    : "")
                + (aRepairPassed ? "; a repaired candidate passed." : "; no repaired candidate "
                    + "passed.");
        }

        static String minutes(long millis) {
            return String.format(Locale.ROOT, "%.1f", millis / 60_000.0);
        }
    }
}
