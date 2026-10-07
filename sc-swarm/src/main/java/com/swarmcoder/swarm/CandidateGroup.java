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
import com.swarmcoder.domain.SamplingConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The candidates of one task, started as the model server's places allow (2026-10-02).
 *
 * <p><b>Different work first.</b> A server serves a fixed number of requests at once. Until now
 * every candidate of a task was started together, so a server with four places and four ready
 * tasks of two candidates each worked on two tasks twice while two tasks waited. Now:
 *
 * <ul>
 *   <li>the <b>first</b> candidate of a task waits its turn for a place, in the order asked, like
 *       every worker always did;
 *   <li>every <b>further</b> candidate is <b>spare</b>: it starts only on a place that is free at
 *       that instant with nothing waiting for it, and only once every ready task of the wave has
 *       its first candidate going ({@link WaveBoard}). It may start late, or never;
 *   <li>a spare candidate that has not started is <b>withdrawn</b> the moment its task has a
 *       candidate through verification - nothing is started just to have two;
 *   <li>when the candidates that ran all failed, the next one waiting stops being spare: it is
 *       <b>needed</b>, and waits its turn like a first candidate;
 *   <li>a spare candidate in its session is stopped when a task with nothing running needs its
 *       place ({@link SpareCandidates}), or when its own task has a passing candidate and nothing
 *       else in the wave is still looking for one (the engine decides that; see
 *       {@link #stopRunning}).
 * </ul>
 *
 * <p>The swarm is not made smaller by any of this. A server with places to spare runs every
 * candidate the policy asks for, exactly as before; what changes is who gets a place when there
 * are fewer places than candidates.
 */
final class CandidateGroup {

    /** How a candidate came to be started. */
    enum Start {
        /** The task's first candidate. */
        FIRST,
        /** A further candidate, on a place nothing else wanted. */
        SPARE,
        /** A further candidate, started because every earlier one had failed. */
        NEEDED
    }

    /**
     * One candidate's end.
     *
     * @param result null when it was withdrawn before it ever started
     * @param start  how it was started; {@link Start#SPARE} for one that never started
     */
    record Ended(int workerIndex, WorkerResult result, Start start) { }

    /** Told each candidate's end, on that candidate's own thread, after its places are free. */
    interface Listener {
        void ended(Ended ended);
    }

    private enum State { WAITING, RUNNING, REPORTING, ENDED }

    /** One candidate of the group. */
    final class Seat {
        final int index;
        final SamplingConfig sampling;
        /** Stops this one candidate at its next turn. */
        final GroupSignal signal = new GroupSignal();
        private State state = State.WAITING;
        private boolean withdrawn;
        private boolean needed;
        private Start startedAs;
        private SpareCandidates.Entry spare;

        private Seat(int index, SamplingConfig sampling) {
            this.index = index;
            this.sampling = sampling;
        }

        /** True for the task's first candidate, and for one that has become needed. */
        boolean waitsItsTurn() {
            synchronized (CandidateGroup.this) {
                return index == 0 || needed;
            }
        }

        boolean withdrawn() {
            synchronized (CandidateGroup.this) {
                return withdrawn;
            }
        }

        /**
         * This candidate has its places and starts its session.
         *
         * @return false when it was withdrawn in the meantime: the caller gives the places back
         */
        boolean begins(String profileId, String label) {
            synchronized (CandidateGroup.this) {
                if (withdrawn) {
                    return false;
                }
                state = State.RUNNING;
                startedAs = index == 0 ? Start.FIRST : needed ? Start.NEEDED : Start.SPARE;
                if (startedAs == Start.SPARE) {
                    spare = SpareCandidates.started(profileId, label, signal);
                }
                changed(); // a candidate waiting to look for a place looks now
                return true;
            }
        }

        Start startedAs() {
            synchronized (CandidateGroup.this) {
                return startedAs == null ? Start.SPARE : startedAs;
            }
        }
    }

    private final List<Seat> seats = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Listener listener;

    CandidateGroup(List<SamplingConfig> samplings, Listener listener) {
        for (int i = 0; i < samplings.size(); i++) {
            seats.add(new Seat(i, samplings.get(i)));
        }
        this.listener = listener == null ? ended -> { } : listener;
    }

    List<Seat> seats() {
        return List.copyOf(seats);
    }

    void runsOn(Thread thread) {
        synchronized (this) {
            threads.add(thread);
        }
    }

    List<Thread> threads() {
        synchronized (this) {
            return List.copyOf(threads);
        }
    }

    /**
     * A candidate's model session is over and it was not stopped: what is left is collecting its
     * change and handing it in. From here on it is not "in its session" - it can no longer be
     * stopped, and whoever decides to go on without the candidates still working waits for this
     * one, because its work is done and paid for.
     */
    void sessionOver(Seat seat) {
        synchronized (this) {
            if (seat.state == State.RUNNING) {
                seat.state = State.REPORTING;
                SpareCandidates.ended(seat.spare);
                seat.spare = null;
                changed();
            }
        }
    }

    /** A candidate's thread is done: it ran and ended, or was withdrawn. Tells the listener. */
    void ended(Seat seat, WorkerResult result) {
        Start start;
        synchronized (this) {
            // Out of its session, but not ENDED until the listener has had it: whoever waits for
            // allEnded() must not see the group over while a result is still being handed in.
            seat.state = State.REPORTING;
            SpareCandidates.ended(seat.spare);
            seat.spare = null;
            start = seat.startedAs == null ? Start.SPARE : seat.startedAs;
            changed();
        }
        try {
            listener.ended(new Ended(seat.index, result, start));
        } finally {
            synchronized (this) {
                seat.state = State.ENDED;
                changed();
            }
        }
    }

    /**
     * Wakes whoever is in {@link #awaitChange}. A lock and a condition of their own rather than
     * this object's monitor: the waiters are virtual threads, and waiting on a monitor would hold
     * a carrier thread for as long as a spare candidate waits for a place - which can be hours.
     */
    private final java.util.concurrent.locks.ReentrantLock changeLock =
        new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition somethingChanged = changeLock.newCondition();

    private void changed() {
        changeLock.lock();
        try {
            somethingChanged.signalAll();
        } finally {
            changeLock.unlock();
        }
    }

    /** Waits a moment for something about the group to change; for a spare candidate's loop. */
    void awaitChange(long millis) {
        changeLock.lock();
        try {
            somethingChanged.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            changeLock.unlock();
        }
    }

    /**
     * The task has a passing candidate, or is over: candidates that have not started never will.
     *
     * @return the worker indices withdrawn by this call
     */
    List<Integer> withdrawWaiting() {
        List<Integer> gone = new ArrayList<>();
        synchronized (this) {
            for (Seat seat : seats) {
                if (seat.state == State.WAITING && !seat.withdrawn) {
                    seat.withdrawn = true;
                    gone.add(seat.index);
                }
            }
            changed();
        }
        return gone;
    }

    /**
     * Stops every candidate still in its session, at its next turn.
     *
     * @return the worker indices told to stop by this call
     */
    List<Integer> stopRunning(KillReason reason) {
        List<Integer> stopped = new ArrayList<>();
        synchronized (this) {
            for (Seat seat : seats) {
                if (seat.state == State.RUNNING && seat.signal.stop(reason)) {
                    stopped.add(seat.index);
                }
            }
        }
        return stopped;
    }

    /**
     * A candidate ended without passing and the task still has none that did. Whatever is in its
     * session now is what the task depends on, so it may no longer be stopped for its place; and
     * when nothing is in its session, the next candidate waiting stops being spare.
     *
     * @return the worker index that became needed, or -1 when none did
     */
    int noPassingCandidateYet() {
        synchronized (this) {
            boolean running = false;
            for (Seat seat : seats) {
                if (seat.state == State.RUNNING && !seat.signal.isSuperseded()) {
                    running = true;
                    SpareCandidates.ended(seat.spare);
                    seat.spare = null;
                }
            }
            if (running) {
                return -1;
            }
            for (Seat seat : seats) {
                if (seat.state == State.WAITING && !seat.withdrawn && !seat.needed) {
                    seat.needed = true;
                    changed();
                    return seat.index;
                }
                if (seat.state == State.WAITING && !seat.withdrawn) {
                    return -1; // one is already on its way
                }
            }
            return -1;
        }
    }

    /** True once no candidate is waiting to start or in its session. */
    boolean allEnded() {
        synchronized (this) {
            for (Seat seat : seats) {
                if (seat.state != State.ENDED) {
                    return false;
                }
            }
            return true;
        }
    }

    /** True while some candidate is out of its session and its result is still being handed in. */
    boolean handingIn() {
        synchronized (this) {
            for (Seat seat : seats) {
                if (seat.state == State.REPORTING) {
                    return true;
                }
            }
            return false;
        }
    }

    /** How many candidates are in their session right now. */
    int running() {
        synchronized (this) {
            int n = 0;
            for (Seat seat : seats) {
                if (seat.state == State.RUNNING) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Waits until every candidate's thread has finished, listener included. */
    void awaitThreads() {
        List<Thread> all;
        synchronized (this) {
            all = List.copyOf(threads);
        }
        for (Thread thread : all) {
            boolean interrupted = false;
            while (true) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
