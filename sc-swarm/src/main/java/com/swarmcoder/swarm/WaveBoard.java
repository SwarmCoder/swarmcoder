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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the tasks of one wave know about each other, which is two things.
 *
 * <p><b>Whether every task that can start has its first candidate going.</b> A second candidate
 * of any task waits for that: the first candidate of every ready task is started before any
 * second one, so a server with four places and four ready tasks works on four tasks, not on two
 * tasks twice.
 *
 * <p><b>Whether any OTHER task is still looking for its first passing candidate.</b> A task that
 * already has a candidate through verification lets its remaining candidates run on only while
 * that is so - while the wave could not end anyway, and their places would otherwise be idle.
 * The moment no other task is still looking, the wave is waiting for nothing but duplicates, so
 * they are stopped: a task never waits for its own duplicate.
 *
 * <p>A task counts from the moment it is allowed to start (it has passed the operator's
 * ceiling on tasks at once, when there is one) until it has a passing candidate or has ended.
 */
final class WaveBoard {

    /** How many tasks start at once: the first candidates expected before any is placed. */
    private final int startingAtOnce;
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger firstsExpected;
    private final Set<UUID> firstPlaced = ConcurrentHashMap.newKeySet();
    private final Set<UUID> looking = ConcurrentHashMap.newKeySet();

    /**
     * @param tasks       how many tasks the wave has
     * @param tasksAtOnce the operator's ceiling on tasks worked on at once; 0 for none
     */
    WaveBoard(int tasks, int tasksAtOnce) {
        this.startingAtOnce = tasksAtOnce > 0 ? Math.min(tasks, tasksAtOnce) : tasks;
        this.firstsExpected = new AtomicInteger(startingAtOnce);
    }

    /** {@code task} may start now: it is past the ceiling on tasks at once, if there is one. */
    void started(UUID task) {
        if (started.incrementAndGet() > startingAtOnce) {
            firstsExpected.incrementAndGet(); // it took the place of a task that has ended
        }
        looking.add(task);
    }

    /** {@code task}'s first candidate has its place - or the task will never need one. */
    void firstPlaced(UUID task) {
        firstPlaced.add(task);
    }

    /** True once every task that may start has its first candidate going. */
    boolean allFirstsPlaced() {
        return firstPlaced.size() >= firstsExpected.get();
    }

    /** {@code task} has a candidate that passed verification. */
    void hasPassingCandidate(UUID task) {
        looking.remove(task);
    }

    /** {@code task} has ended, with a winner or without. */
    void ended(UUID task) {
        firstPlaced.add(task);
        looking.remove(task);
    }

    /** True while some other task of this wave has started and has no passing candidate yet. */
    boolean othersStillLooking(UUID task) {
        for (UUID other : looking) {
            if (!other.equals(task)) {
                return true;
            }
        }
        return false;
    }
}
