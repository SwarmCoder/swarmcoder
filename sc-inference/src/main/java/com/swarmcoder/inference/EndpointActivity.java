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
package com.swarmcoder.inference;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When each model endpoint last actually answered something — the evidence that tells a slow
 * request apart from a dead server.
 *
 * <p><b>Why this exists.</b> {@link EndpointOutage} classifies a transport failure from the failure
 * alone, and from a single request a server that is too slow to answer really is indistinguishable
 * from a server that has gone. That reasoning is sound for a connect failure and wrong for a
 * request the server accepted and is still working on: on 2026-09-01 six workers were killed by the
 * client's fifteen-minute request timeout, every one of them recorded as an endpoint outage, while
 * the endpoint was serving the other workers in the same wave normally. The run then waited
 * indefinitely for a server that was answering fine.
 *
 * <p><b>What counts as evidence.</b> A completed model call, nothing weaker. Not a {@code
 * /v1/models} probe: an HTTP front end can answer that in six milliseconds while the inference
 * engine behind it is wedged, and calling that "up" would turn a real outage into workers blamed
 * for it. A finished completion proves the engine itself is serving.
 *
 * <p><b>Process-wide on purpose.</b> SwarmCoder runs a wave of workers plus role calls against the
 * same endpoint at once, so one worker's stuck request is judged against what every other caller in
 * the process saw during that same stretch of time. That cross-worker view is the whole point; a
 * per-session record would have no evidence to offer exactly when it is needed.
 */
public final class EndpointActivity {

    private static final Map<String, AtomicLong> LAST_SUCCESS_MILLIS = new ConcurrentHashMap<>();

    private EndpointActivity() {}

    /**
     * Records that this endpoint completed a call. Called from every path that finishes one —
     * the role client and the worker's agent loop.
     */
    public static void succeeded(String endpoint) {
        String key = key(endpoint);
        if (key == null) {
            return;
        }
        LAST_SUCCESS_MILLIS.computeIfAbsent(key, k -> new AtomicLong())
            .accumulateAndGet(System.currentTimeMillis(), Math::max);
    }

    /** When this endpoint last completed a call, or null if it never has in this process. */
    public static Instant lastSuccess(String endpoint) {
        String key = key(endpoint);
        AtomicLong at = key == null ? null : LAST_SUCCESS_MILLIS.get(key);
        return at == null ? null : Instant.ofEpochMilli(at.get());
    }

    /**
     * Did anything at all get a complete answer out of this endpoint at or after {@code since}?
     *
     * <p>Asked with the instant the failing request was SENT, this answers the only question worth
     * asking: was the server serving while my request sat there unanswered? Absence of evidence
     * returns false, which leaves the failure classified exactly as it is today.
     */
    public static boolean succeededSince(String endpoint, Instant since) {
        if (since == null) {
            return false;
        }
        Instant last = lastSuccess(endpoint);
        return last != null && !last.isBefore(since);
    }

    /** Test seam — the map is process-wide, so a test that asserts on it must be able to reset it. */
    public static void forget() {
        LAST_SUCCESS_MILLIS.clear();
    }

    /**
     * One endpoint, however it was spelled. The same box is configured as {@code http://host:8000}
     * in one place and {@code http://host:8000/v1/} in another, and evidence gathered under one
     * spelling must count for the other.
     */
    static String key(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String url = endpoint.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url.toLowerCase(java.util.Locale.ROOT);
    }
}
