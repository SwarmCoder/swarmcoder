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
 * Whether a build is working, waiting for its endpoint, or not being driven at all — decided ONCE,
 * for everybody.
 *
 * <p>It lives in sc-domain because three layers need the same answer and rule 2 permits one
 * derivation: the browser client draws a badge from it, the readiness publisher counts from it, and
 * anything server-side that wants to know whether a story is genuinely progressing asks it too.
 *
 * <p>The cost of NOT sharing it was measured. The client judged liveness from a heartbeat age; the
 * server judged it from the story's state alone. So one story read "building now · 9h 5m" on one
 * screen and "stopped" on another at the same moment, and — after the boards were merged — the
 * guidance line at the top of the window said "1 story is in flight, nothing is waiting on you"
 * directly above a card reading "stopped — Build it again". Two answers to one question is not a
 * cosmetic problem: the operator cannot tell which to act on.
 *
 * <p>Deliberately built on primitives rather than on {@link Run}: the client holds a wire DTO with
 * millisecond timestamps and the server holds the domain object with {@code Instant}s, and forcing
 * either into the other's shape to share a decision would be a worse trade than passing four numbers.
 * It also keeps this class free of {@code java.time}, which matters because sc-domain is compiled to
 * JavaScript by TeaVM.
 */
public final class BuildHealth {

    /**
     * How long a build may go without persisting anything before it stops counting as live.
     *
     * <p>Generous on purpose. A single agent turn can take minutes, and accusing a slow build of
     * being dead is worse than being late to notice a dead one: the operator's response to either
     * message is to start it again, and one of those two starts a second swarm against work that is
     * still going.
     */
    public static final long STALLED_AFTER_MINUTES = 20;

    /** The four situations. See {@code BuildState.Kind}, which renders these one-for-one. */
    public enum Kind {
        /** Agents are at work and the build is reporting. Nothing is owed. */
        WORKING,
        /** Waiting for a model endpoint to come back. Resumes by itself; nothing is owed. */
        PAUSED,
        /** Something is owed: a pause that has outlasted the threshold, or a question. */
        NEEDS_YOU,
        /** Nothing is driving it. Whatever was, is gone. */
        STOPPED
    }

    private BuildHealth() {}

    /**
     * The health of a build, from the five facts that decide it.
     *
     * @param runState          the run's state name, or null/blank when the story has no run on record
     * @param heartbeatMillis   when a live process last persisted the run; 0 when never
     * @param pausedSinceMillis when it started waiting for an endpoint; 0 when it is not waiting
     * @param parkedAtMillis    when a workflow stage stopped this run and raised a question for the
     *                          operator; 0 when it is not parked
     * @param nowMillis         the caller's clock, passed in so this stays testable and side-effect free
     */
    public static Kind of(String runState, long heartbeatMillis, long pausedSinceMillis,
                          long parkedAtMillis, long nowMillis) {
        if (runState == null || runState.isBlank() || isTerminal(runState)) {
            // No run, or one that ended without handing the story back. Either way nothing is driving
            // it and the only way forward is to build it again.
            return Kind.STOPPED;
        }
        if (parkedAtMillis > 0) {
            // A stage ran to completion and decided it could not go on: nothing is driving this run
            // and no amount of waiting changes that, unlike a pause. Checked BEFORE the pause and the
            // heartbeat so the card says "stopped" the instant the workflow parks — a fresh heartbeat
            // stamped moments before parking would otherwise read as WORKING for up to
            // STALLED_AFTER_MINUTES, and the operator's own report was exactly that: a run that had
            // parked minutes earlier still reading as though it were building.
            return Kind.STOPPED;
        }
        if (pausedSinceMillis > 0) {
            // Checked BEFORE the heartbeat: a paused build is genuinely being driven — the retry loop
            // keeps stamping it — so it would otherwise read as working for the whole outage.
            return minutesSince(pausedSinceMillis, nowMillis) >= RunPause.ESCALATE_AFTER_MINUTES
                ? Kind.NEEDS_YOU : Kind.PAUSED;
        }
        if (heartbeatMillis > 0 && minutesSince(heartbeatMillis, nowMillis) >= STALLED_AFTER_MINUTES) {
            return Kind.STOPPED;
        }
        // A heartbeat of 0 is a run from before heartbeats existed, and it must NOT read as stalled:
        // a build that was never able to report has not gone quiet.
        return Kind.WORKING;
    }

    /** True when the run has ended, whichever way. */
    public static boolean isTerminal(String runState) {
        return "DELIVERED".equals(runState) || "ABORTED".equals(runState);
    }

    /** True when this health means the operator is on the hook for something. */
    public static boolean needsAPerson(Kind kind) {
        return kind == Kind.NEEDS_YOU || kind == Kind.STOPPED;
    }

    /** Whole minutes between a timestamp and now, never negative. */
    public static long minutesSince(long millis, long nowMillis) {
        if (millis <= 0) {
            return 0;
        }
        return Math.max(0, (nowMillis - millis) / 60_000);
    }
}
