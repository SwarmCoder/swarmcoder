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

import com.swarmcoder.domain.KillReason;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The spare candidates running right now, on this machine, across every task, run and project -
 * so that one of them can be asked to give its place up.
 *
 * <p>A spare candidate is a second (or further) attempt on a task that already has an attempt
 * going. It was started because a place on the model server would otherwise have sat idle
 * (see {@link CandidateGroup}). The moment a task with NO attempt running has to wait for a
 * place, that is no longer true of one of them: the place is wanted for different work, and
 * different work comes first. The most recently started spare is the one stopped, because it has
 * the least work to lose. It stops at its next turn, with {@link KillReason#PLACE_NEEDED}.
 *
 * <p><b>Duplicates go first</b> (section 98). A candidate still in its session after another
 * candidate of its task has passed verification is a duplicate, whether it was started as the
 * task's first candidate or as a spare. It is the first to give its place up, and it gives it
 * up to more than a task with nothing running: a further candidate of a task that has nothing
 * through verification yet may ask for it too ({@link #giveUpADuplicateFor}), which an ordinary
 * spare is never asked for. It stops with {@link KillReason#SUPERSEDED}.
 *
 * <p>Process-wide for the reason {@link WorkerSlots} is: the places belong to the machine and its
 * model server, not to a task or a project.
 */
final class SpareCandidates {

    private static final Logger log = LoggerFactory.getLogger(SpareCandidates.class);

    /** One spare candidate in its session. */
    static final class Entry {
        final String profileId;
        final String label;
        final GroupSignal signal;
        /** True when another candidate of its task has already passed verification. */
        final boolean duplicate;
        final long startedAt;

        private Entry(String profileId, String label, GroupSignal signal, boolean duplicate,
                      long startedAt) {
            this.startedAt = startedAt;
            this.profileId = profileId;
            this.label = label;
            this.signal = signal;
            this.duplicate = duplicate;
        }
    }

    private static final List<Entry> RUNNING = new ArrayList<>();

    private SpareCandidates() { }

    /** A spare candidate has started its session and may be asked to give its place up. */
    static Entry started(String profileId, String label, GroupSignal signal) {
        Entry entry = new Entry(profileId, label, signal, false, System.nanoTime());
        synchronized (RUNNING) {
            RUNNING.add(entry);
        }
        return entry;
    }

    /**
     * A candidate is in its session and another candidate of its task has passed verification:
     * its place is the first to be given up.
     *
     * @param startedAt when its session began ({@link System#nanoTime}): of two duplicates the
     *                  one that began later has less to lose
     */
    static Entry duplicate(String profileId, String label, GroupSignal signal, long startedAt) {
        Entry entry = new Entry(profileId, label, signal, true, startedAt);
        synchronized (RUNNING) {
            RUNNING.add(entry);
        }
        return entry;
    }

    /** How many duplicates are in their session right now. */
    static int duplicatesRunning() {
        synchronized (RUNNING) {
            int n = 0;
            for (Entry entry : RUNNING) {
                if (entry.duplicate) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * It has ended, or it has become the attempt its task depends on and may no longer be stopped
     * for its place. Harmless to call twice, and with null.
     */
    static void ended(Entry entry) {
        if (entry == null) {
            return;
        }
        synchronized (RUNNING) {
            RUNNING.remove(entry);
        }
    }

    /** How many spare candidates are in their session right now. */
    static int running() {
        synchronized (RUNNING) {
            return RUNNING.size();
        }
    }

    /**
     * Asks one spare candidate to give its place up for {@code neededBy}, which has no place and
     * has to wait: a duplicate before any other spare, then the most recently started one, on
     * the same model, or on any model when that model has none (the machine's own ceiling is
     * shared by all of them).
     *
     * @return true when one was told to stop; its request to the model is abandoned
     */
    static boolean giveUpOneFor(String profileId, String neededBy) {
        return giveUp(profileId, neededBy, false);
    }

    /**
     * Asks one duplicate - and never an ordinary spare - to give its place up for
     * {@code neededBy}: a further candidate of a task that has nothing through verification
     * yet. Such a candidate takes only places nothing else wants, so it may not stop a spare
     * candidate of another task that is still looking; a candidate whose task already has what
     * it needs is another matter.
     *
     * @return true when one was told to stop
     */
    static boolean giveUpADuplicateFor(String profileId, String neededBy) {
        return giveUp(profileId, neededBy, true);
    }

    private static boolean giveUp(String profileId, String neededBy, boolean duplicatesOnly) {
        Entry chosen = null;
        synchronized (RUNNING) {
            for (int pass = 0; pass < 2 && chosen == null; pass++) {
                for (Entry entry : RUNNING) {
                    boolean sameModel = profileId != null && profileId.equals(entry.profileId);
                    if ((pass != 1 && !sameModel) || entry.signal.isSuperseded()
                            || (duplicatesOnly && !entry.duplicate)) {
                        continue;
                    }
                    if (chosen == null || (entry.duplicate && !chosen.duplicate)
                            || (entry.duplicate == chosen.duplicate
                                && entry.startedAt >= chosen.startedAt)) {
                        chosen = entry;
                    }
                }
            }
            if (chosen == null || !chosen.signal.stop(
                    chosen.duplicate ? KillReason.SUPERSEDED : KillReason.PLACE_NEEDED)) {
                return false;
            }
            RUNNING.remove(chosen);
        }
        if (chosen.duplicate) {
            log.info("{} is stopped: another attempt on its task has already passed, and {} is "
                + "waiting for a place.", chosen.label, neededBy);
        } else {
            log.info("{} is a spare attempt and gives its place up: {} has nothing running and "
                + "is waiting for a place. It stops at its next turn.", chosen.label, neededBy);
        }
        return true;
    }
}
