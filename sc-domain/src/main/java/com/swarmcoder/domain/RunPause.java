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

import java.time.Instant;

/**
 * When a build that is waiting for a model endpoint stops being the machine's problem and becomes
 * the operator's (UX v3 §2.4).
 *
 * <p>This lives in sc-domain because three layers need the same answer and rule 2 allows one
 * derivation: the workflow engine (which does the waiting), the readiness publisher (which counts
 * what the operator is owed), and the browser client (which draws the badge). Each of those used to
 * be a candidate for its own copy of the number, and a threshold that disagrees with itself across
 * screens is exactly the class of defect that produced "building now" and "stopped" about one story
 * at one moment.
 *
 * <p>Minutes rather than a {@code Duration} in the shared surface: the browser client is compiled by
 * TeaVM, and the less of {@code java.time} it has to support the better.
 */
public final class RunPause {

    /**
     * How long a build may wait for its endpoint before the card stops saying "resuming by itself"
     * and starts asking whether to keep waiting.
     *
     * <p>Generous on purpose (§2.4 says "~30 min"): the failure being avoided is an operator pulled
     * away from their work by a model server that restarted.
     */
    public static final long ESCALATE_AFTER_MINUTES = 30;

    private RunPause() {}

    /** Whether this run is waiting for a model endpoint to come back. */
    public static boolean paused(Run run) {
        return run != null && run.pausedSince() != null;
    }

    /**
     * Whether the pause has outlasted {@link #ESCALATE_AFTER_MINUTES}.
     *
     * <p>Derived from the pause's age rather than stored, so it cannot drift out of agreement with
     * the pause itself — and so that "keep waiting" needs no state at all: the engine goes on
     * retrying either way, and escalation changes only what the operator is told.
     */
    public static boolean escalated(Run run, Instant now) {
        return paused(run)
            && !run.pausedSince().plusSeconds(ESCALATE_AFTER_MINUTES * 60).isAfter(now);
    }

    /** Minutes since this run started waiting, from a millisecond timestamp; 0 when not waiting. */
    public static long pausedMinutes(long pausedSinceMillis, long nowMillis) {
        if (pausedSinceMillis <= 0) {
            return 0;
        }
        return Math.max(0, (nowMillis - pausedSinceMillis) / 60_000);
    }
}
