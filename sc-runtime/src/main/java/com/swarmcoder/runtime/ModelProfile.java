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
package com.swarmcoder.runtime;

import com.swarmcoder.inference.ModelQuirks;

/**
 * Everything the system knows about one model endpoint (spec §6.2). Profiles are pure
 * config — model identity MUST come from here, never from string literals in code
 * (correction S1). The worker slot is deliberately model-agnostic: swapping models is a
 * config change, and every archived candidate carries its profile id via SamplingConfig.
 *
 * <p>Until 2026-08-27 this record carried only identity and two numbers, while every actual
 * BEHAVIOUR the model needed — the generation cap, the reasoning switch, whether tool-call history
 * can go natively, the HTTP version, the context ceilings, the scheduler figures — was hardcoded
 * or lived in a JVM-wide system property that could not differ between two models running at once.
 * {@link #quirks()} is where all of that now lives, so a second model is a second profile and
 * nothing else.
 *
 * @param id       config key and SamplingConfig.modelProfileId
 * @param endpoint OpenAI-compatible endpoint (vLLM instance or cloud)
 * @param role     what this profile serves as
 * @param quirks   the model's own behaviour: generation cap, reasoning switch, tool-history
 *                 format, JSON response format, HTTP version, context ceilings and the
 *                 scheduler's admission-control figures
 * @param costPerMTokens cloud cost estimate for budget accounting; 0 for local
 */
public record ModelProfile(
    String id,
    AgentRuntime.ModelEndpoint endpoint,
    Kind role,
    ModelQuirks quirks,
    double costPerMTokens
) {
    public enum Kind { WORKER, CLOUD, UTILITY }

    public ModelProfile {
        quirks = quirks == null ? ModelQuirks.DEFAULTS : quirks;
    }

    /**
     * The pre-2026-08 shape, kept so existing call sites and tests compile unchanged. The
     * memory-per-token estimate now lives in {@link ModelQuirks#kvBytesPerToken()}, taken from the
     * endpoint's own quirks; the {@code kvBytesPerTokenEstimate} argument is ignored because a
     * per-model figure that disagrees with the profile it belongs to is worse than no figure.
     */
    public ModelProfile(String id, AgentRuntime.ModelEndpoint endpoint, Kind role,
                        double kvBytesPerTokenEstimate, double costPerMTokens) {
        this(id, endpoint, role,
            endpoint != null && endpoint.quirks() != null ? endpoint.quirks() : ModelQuirks.DEFAULTS,
            costPerMTokens);
    }

    /** The scheduler's admission-control currency (spec §7.3) — bytes of KV cache per token. */
    public double kvBytesPerTokenEstimate() {
        return quirks.kvBytesPerToken();
    }
}
