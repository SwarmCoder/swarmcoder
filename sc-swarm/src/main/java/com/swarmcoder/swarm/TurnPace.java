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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How long one worker turn takes in a run, measured (section 91).
 *
 * <p>A turn is one answer of the model and the tool calls it asked for, a client-side compile
 * included. Its length is a property of the model server and of how many conversations it is
 * serving, so it is measured from the turns the run's workers have really taken - first-round
 * workers and repair workers alike - and never assumed. A repair round's limits are derived from
 * it ({@link RepairRound}).
 *
 * <p>The mean of every turn recorded for the run since this process started. Nothing is stored.
 */
final class TurnPace {

    private static final Map<UUID, TurnPace> BY_RUN = new ConcurrentHashMap<>();

    private final AtomicLong turns = new AtomicLong();
    private final AtomicLong millis = new AtomicLong();

    TurnPace() {
    }

    /** The one measure of a run; a run with no id gets a measure nobody else reads. */
    static TurnPace forRun(UUID runId) {
        return runId == null ? new TurnPace() : BY_RUN.computeIfAbsent(runId, id -> new TurnPace());
    }

    /** One turn that took {@code tookMillis}. */
    void record(long tookMillis) {
        if (tookMillis < 0) {
            return;
        }
        turns.incrementAndGet();
        millis.addAndGet(tookMillis);
    }

    /** How many turns have been measured. */
    long turns() {
        return turns.get();
    }

    /** The mean length of a turn in milliseconds; 0 while none has been measured. */
    long millisPerTurn() {
        long counted = turns.get();
        return counted == 0 ? 0 : millis.get() / counted;
    }
}
