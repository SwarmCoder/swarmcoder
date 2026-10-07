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

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one number that cannot be exceeded: how many workers may be running at the same instant on
 * this machine, counting every task, every run and every project.
 *
 * <p><b>Why this exists at all.</b> Workers per task and tasks at once are both settable, and one
 * of them is now settable per story and per project — so their product is not something any single
 * setting controls. Four workers on two tasks is eight, which is what the machine already carries;
 * four workers on two tasks in each of three projects is twenty-four, which is not. §10 measured
 * that at ten-way concurrency the wall time was dominated by ten simultaneous local {@code mvn}
 * runs on the workstation, not by the model server. Total workers is therefore the number that
 * governs load, and it needs a real limit rather than an arithmetic hope.
 *
 * <p><b>What happens at the ceiling.</b> The extra workers WAIT, they are not dropped and the
 * swarm is not shrunk. Each one holds a virtual thread, which costs nothing while parked, and
 * starts the moment a slot frees. So a task asking for more workers than the machine allows runs
 * them in batches and produces exactly the candidates it was asked for, a little later. Nothing
 * can deadlock on this: a worker never waits for another worker, so every held slot is held by
 * something that is making progress or about to fail.
 *
 * <p><b>Why the ceiling is process-wide state.</b> It is a fact about the workstation, not about a
 * project, and a per-project limit is precisely the thing that cannot bound a product across
 * projects. It is set once, at startup, from {@code swarm.maxConcurrentWorkers}; left unset it is
 * unlimited, which is what every test and every embedded use gets and is the behaviour that
 * existed before this class.
 */
public final class WorkerSlots {

    private static final Logger log = LoggerFactory.getLogger(WorkerSlots.class);

    private static volatile WorkerSlots shared = new WorkerSlots(0);

    private final int ceiling;
    private final Semaphore slots;      // null when unlimited
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();

    private WorkerSlots(int ceiling) {
        this.ceiling = Math.max(0, ceiling);
        this.slots = this.ceiling > 0 ? new Semaphore(this.ceiling, true) : null;
    }

    /**
     * Sets the ceiling for this process. Called once at startup; 0 or less means no ceiling.
     *
     * <p>Replaces the holder rather than resizing it, so workers already running keep the
     * permits they took under the old ceiling and simply do not return them to the new one.
     * That cannot over-admit: the new holder starts empty and fills to its own limit.
     */
    public static void configure(int maxConcurrentWorkers) {
        shared = new WorkerSlots(maxConcurrentWorkers);
        if (shared.ceiling > 0) {
            log.info("At most {} workers will be building at the same time on this machine, "
                + "however many tasks or projects are going. Anything beyond that waits for a "
                + "free place rather than being skipped.", shared.ceiling);
        } else {
            log.info("No ceiling on how many workers build at once — every task dispatches its "
                + "whole group immediately.");
        }
    }

    /** The process-wide holder. */
    public static WorkerSlots shared() {
        return shared;
    }

    /** How many workers may run at once; 0 means no ceiling. */
    public int ceiling() {
        return ceiling;
    }

    /** How many workers hold a place right now. */
    public int running() {
        return running.get();
    }

    /** How many workers are queued for a place right now. */
    public int waiting() {
        return waiting.get();
    }

    /** One of the machine's worker places, held until closed. Closing it twice is harmless. */
    public interface Place extends AutoCloseable {
        @Override
        void close();
    }

    private Place taken() {
        running.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean given =
            new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (given.compareAndSet(false, true)) {
                running.decrementAndGet();
                if (slots != null) {
                    slots.release();
                }
            }
        };
    }

    /**
     * {@link #withSlot} for a caller that cannot wrap its work in a lambda: waits for a place when
     * the machine is full, in the order asked.
     */
    public Place enter(String what) {
        if (slots == null) {
            return taken();
        }
        if (!slots.tryAcquire()) {
            int queued = waiting.incrementAndGet();
            log.info("{} is waiting for a free place — {} of {} places are busy, {} waiting.",
                what, running.get(), ceiling, queued);
            try {
                slots.acquireUninterruptibly();
            } finally {
                waiting.decrementAndGet();
            }
        }
        return taken();
    }

    /**
     * A place only when one is free this instant and nobody is waiting for one; otherwise null,
     * at once. For a spare candidate, which may only ever use a place that would otherwise be
     * idle (2026-10-02).
     */
    public Place tryEnter() {
        if (slots == null) {
            return taken();
        }
        try {
            // The timed form with no time: unlike the bare tryAcquire(), it keeps to the queue.
            return slots.tryAcquire(0, java.util.concurrent.TimeUnit.SECONDS) ? taken() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Runs {@code work} holding one of the machine's worker places, waiting for one if the machine
     * is full. Uninterruptible on purpose: a worker that gave up its place on an interrupt would
     * have to be counted as a failed candidate, and "the machine was busy" is not evidence about
     * a task.
     */
    public <T> T withSlot(String what, java.util.function.Supplier<T> work) {
        if (slots == null) {
            running.incrementAndGet();
            try {
                return work.get();
            } finally {
                running.decrementAndGet();
            }
        }
        boolean got = slots.tryAcquire();
        if (!got) {
            int queued = waiting.incrementAndGet();
            log.info("{} is waiting for a free place — {} of {} places are busy, {} waiting.",
                what, running.get(), ceiling, queued);
            try {
                slots.acquireUninterruptibly();
            } finally {
                waiting.decrementAndGet();
            }
        }
        running.incrementAndGet();
        try {
            return work.get();
        } finally {
            running.decrementAndGet();
            slots.release();
        }
    }
}
