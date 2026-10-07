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
package com.swarmcoder.app;

import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ServerCapabilities;
import com.swarmcoder.runtime.HistoryTrim;

import java.net.http.HttpClient;

/**
 * Asks the live endpoint what it can do, the same way {@code DependencyGraph} does at startup, so
 * this harness's workers get the same working-context budget the product gives them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Harness run 9 (the night of 2026-09-02/03) compacted both workers on the persistence task —
 * "26069 -&gt; 13258 tokens" at turn 15, "24695 -&gt; 16904 tokens" at turn 17. Compaction fires at
 * {@link HistoryTrim#HIGH_WATER_PERCENT}% of the working context, so a compaction around 25-26k
 * tokens means a working context of about 32-34k, which is exactly {@link ModelQuirks#DEFAULTS}'s
 * 32768 — because {@code EndToEndLoopTest} built its {@code AgentRuntime.ModelEndpoint} with the
 * 4-argument constructor, which silently defaults to {@link ModelQuirks#DEFAULTS} and never asks
 * the model server anything.
 *
 * <p>Production asks. {@code DependencyGraph}'s {@code roles.workerFamilies} loop calls
 * {@code ServerCapabilities.forEndpoint(worker.baseUrl(), worker.modelName())} and then
 * {@code worker.resolvedQuirks(caps)} — {@link ServerCapabilities} reads the server's key/value
 * cache pool, and {@link AgentModelConfig#resolvedQuirks(ServerCapabilities)} divides it by the
 * server's own reported concurrency (90% headroom, capped at the served ceiling — see
 * {@link ServerCapabilities#derivedWorkingContextTokens()}). This class runs that SAME resolution
 * — the same two calls, not a re-implementation of the arithmetic — against whatever endpoint the
 * harness is pointed at, so the harness measures the room an operator's workers actually get
 * rather than a starved stand-in for it.
 *
 * <h2>The concurrency divisor is the server's, not the harness's</h2>
 *
 * <p>The two workers per task this harness dispatches ({@code -Dswarmcoder.e2e.workers}) never
 * enters this arithmetic, and neither would an operator's {@code swarm.maxConcurrentWorkers} —
 * production doesn't divide the pool by either. {@link ServerCapabilities#applyTo} divides the
 * discovered key/value cache pool by the server's OWN reported {@code max_running_requests} (8 on
 * the 2026-08 box), because that is what genuinely shares the cache; a dispatch or ceiling count
 * only decides how many of those already-sized budgets run at once (see
 * {@code AdaptiveConcurrency}, which this harness does not wire up — it never registers a profile
 * with it, so nothing here needs to either).
 */
final class HarnessModelBudget {

    private HarnessModelBudget() {
    }

    /** What was discovered and what it resolved to, together with the ledger line for link 1. */
    record Discovery(ServerCapabilities caps, ModelQuirks quirks) {

        /**
         * States the numbers a future reader would otherwise have to go and dig for: served
         * context, working context per worker, the concurrency that divided it, and the token
         * count at which a worker's history gets compacted.
         */
        String describe() {
            int highWater = (int) ((long) quirks.workingContextTokens()
                * HistoryTrim.HIGH_WATER_PERCENT / 100);
            return "model budget discovered at " + caps.endpoint() + ": served context "
                + quirks.servedContextTokens() + " tokens, working context "
                + quirks.workingContextTokens() + " tokens per worker, divided across "
                + caps.maxConcurrentSequences() + " request(s) at once (the server's own reported "
                + "concurrency, not this harness's worker-dispatch count — matching production); "
                + "a worker's history compacts once it passes " + highWater + " tokens ("
                + HistoryTrim.HIGH_WATER_PERCENT + "% of the working context).";
        }
    }

    /**
     * @param http      the client to discover with — injectable so a test needs no real server
     * @param baseUrl   the endpoint under test ({@code -Dswarmcoder.live.baseUrl})
     * @param modelName the served model name ({@code -Dswarmcoder.live.model})
     * @throws IllegalStateException when the endpoint answers nothing. Silently falling back to
     *                                {@link ModelQuirks#DEFAULTS} here is exactly the bug this
     *                                class exists to close, so a dead endpoint fails loudly with
     *                                the reason rather than quietly running the starved product.
     */
    static Discovery discover(HttpClient http, String baseUrl, String modelName) {
        ServerCapabilities caps = ServerCapabilities.discover(http, baseUrl, modelName);
        if (!caps.discoveredAnything()) {
            throw new IllegalStateException("could not discover what " + baseUrl + " can do ("
                + caps.note() + "), so the working-context budget the product would actually give "
                + "its workers is unknown here. This harness must not silently fall back to "
                + ModelQuirks.DEFAULTS.workingContextTokens() + "-token defaults and call that a "
                + "measurement of the product — point -Dswarmcoder.live.baseUrl at a running "
                + "server, or fix the one at " + baseUrl + ".");
        }
        // The same starting point DependencyGraph gives a workerFamilies entry that names a
        // baseUrl, a modelName and, optionally, a shape (-Dswarmcoder.live.shape). No shape means
        // the generic one — which asks for json_object, and a server without structured output
        // (DeepSeek V4 Flash on ds4) refuses every JSON call with HTTP 400. Name the shape.
        AgentModelConfig config = new AgentModelConfig(null, baseUrl, "", modelName, null,
            System.getProperty("swarmcoder.live.shape"), null);
        ModelQuirks quirks = config.resolvedQuirks(caps);
        return new Discovery(caps, quirks);
    }
}
