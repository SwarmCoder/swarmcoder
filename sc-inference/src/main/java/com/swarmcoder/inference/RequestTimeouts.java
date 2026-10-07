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

/**
 * How long a worker's or a research session's model request is waited for.
 *
 * <p>Until live run 74 (2026-10-03) this was the agent framework's own default, 900 seconds,
 * written down nowhere here. It is the figure the per-turn output allowance is derived from
 * ({@link ModelQuirks#turnOutputTokens(int)}), so it is stated once and can be changed:
 * {@code -Dswarmcoder.worker.requestTimeoutSeconds=1800}.
 */
public final class RequestTimeouts {

    /** The system property that sets it. */
    public static final String PROPERTY = "swarmcoder.worker.requestTimeoutSeconds";

    /** Thirty minutes; it was 900 seconds until the owner raised it after live run 74. */
    public static final int DEFAULT_SECONDS = 1800;

    private RequestTimeouts() {
    }

    /** Seconds a session's model request may take; never below one minute. */
    public static int workerRequestSeconds() {
        int stated = Integer.getInteger(PROPERTY, DEFAULT_SECONDS);
        return stated <= 0 ? DEFAULT_SECONDS : Math.max(60, stated);
    }
}
