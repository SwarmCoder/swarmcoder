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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.ServerCapabilities;

/**
 * Per-agent model endpoint, plus everything model-specific about it.
 *
 * <p>{@code thinking} (nullable) says whether this role reasons before answering — true to let it
 * reason, false/null for direct answers. It was the FIRST of the model quirks to become a per-role
 * setting, and the rest followed the same shape in 2026-08: {@code shape} names a starting profile
 * from the shipped catalogue ({@code qwen36-27b}, {@code qwen38-flash-next-125b}, {@code vision},
 * {@code generic-openai}), and {@code quirks} overrides individual settings on top of it. Both
 * absent means the conservative generic shape, which is the behaviour SwarmCoder shipped with.
 */
public record AgentModelConfig(
    @JsonProperty("protocol") String protocol,
    @JsonProperty("baseUrl") String baseUrl,
    @JsonProperty("apiKey") String apiKey,
    @JsonProperty("modelName") String modelName,
    @JsonProperty("thinking") Boolean thinking,
    /** A {@link com.swarmcoder.inference.ModelShapes} id — the starting point for this model. */
    @JsonProperty("shape") String shape,
    /** Per-setting overrides on top of the shape; every field nullable, null = inherit. */
    @JsonProperty("quirks") ModelQuirksConfig quirks
) {

    /** The pre-2026-08 shape, kept so existing code and configs construct this unchanged. */
    public AgentModelConfig(String protocol, String baseUrl, String apiKey, String modelName,
                            Boolean thinking) {
        this(protocol, baseUrl, apiKey, modelName, thinking, null, null);
    }

    /** The named shape, or when none is named the one picked by host and model, else null. */
    public String effectiveShape() {
        if (shape != null && !shape.isBlank()) {
            return shape;
        }
        return ModelShapes.inferredFor(baseUrl, modelName);
    }

    /** This role's resolved model-specific behaviour: shape, then overrides, then thinking. */
    public ModelQuirks resolvedQuirks() {
        return ModelQuirksConfig.resolve(effectiveShape(), quirks, thinking);
    }

    /**
     * The same, with the live server's own account of its limits slotted in between the shape and
     * this block's overrides — so a figure read off the running box beats a shape somebody typed,
     * and anything written here beats both.
     */
    public ModelQuirks resolvedQuirks(ServerCapabilities discovered) {
        return ModelQuirksConfig.resolve(effectiveShape(), quirks, thinking, discovered);
    }
}
