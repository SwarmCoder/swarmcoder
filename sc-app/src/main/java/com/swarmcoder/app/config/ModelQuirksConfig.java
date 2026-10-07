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
 * The YAML face of {@link ModelQuirks}: one nullable field per model-specific behaviour, so an
 * operator overrides exactly what they know and leaves the rest to the named shape.
 *
 * <p>Every field is boxed and nullable on purpose. {@code null} means "not stated here" and falls
 * back to the shape, which falls back to the conservative generic shape. That is what makes a
 * config written before any of this existed keep behaving identically.
 *
 * <p>The two reasoning fields take the literal string {@code none} to mean "this model has no such
 * mechanism, send nothing" — which is different from leaving them out, and is how a model that
 * would choke on a chat-template argument it does not know is described.
 */
public record ModelQuirksConfig(
    @JsonProperty("maxOutputTokens") Integer maxOutputTokens,
    /** The chat-template argument that turns reasoning off (Qwen: enable_thinking). "none" = send nothing. */
    @JsonProperty("thinkingKwarg") String thinkingKwarg,
    /** Text appended to the system prompt to turn reasoning off (Qwen: /no_think). "none" = append nothing. */
    @JsonProperty("noThinkDirective") String noThinkDirective,
    /** True when the chat template rejects native tool-call history and needs it as plain JSON text. */
    @JsonProperty("textualToolHistory") Boolean textualToolHistory,
    /** Whether the endpoint honours response_format json_object; false uses vLLM's guided_json. */
    @JsonProperty("jsonResponseFormat") Boolean jsonResponseFormat,
    /** "1.1" (default) or "2". HTTP/2 broke request bodies on one vLLM build — see ModelQuirks. */
    @JsonProperty("httpVersion") String httpVersion,
    @JsonProperty("servedContextTokens") Integer servedContextTokens,
    @JsonProperty("workingContextTokens") Integer workingContextTokens,
    /** Bytes of key/value cache per token — the scheduler's admission currency. MEASURE per model. */
    @JsonProperty("kvBytesPerToken") Integer kvBytesPerToken,
    /** How many requests this server really serves at once. MEASURE per model. */
    @JsonProperty("maxConcurrentSequences") Integer maxConcurrentSequences,
    /** Set true only once the numbers above were measured against this model on this box. */
    @JsonProperty("verified") Boolean verified
) {

    /** The literal an operator writes to say "this model has no such mechanism". */
    public static final String NONE = "none";

    /**
     * Resolves to a complete {@link ModelQuirks}: the named shape first, then whatever this block
     * states, then the role's own thinking override.
     *
     * @param shapeId  a {@link ModelShapes} id, or blank for the conservative generic shape
     * @param thinking the role's {@code thinking} flag (nullable) — kept separate because it was
     *                 already a per-role setting before any of this existed
     */
    public static ModelQuirks resolve(String shapeId, ModelQuirksConfig overrides, Boolean thinking) {
        return resolve(shapeId, overrides, thinking, null);
    }

    /**
     * The same, with what the live server said about itself slotted in between.
     *
     * <p><b>The order is shape, then server, then operator, and it is the whole point.</b> A shape
     * is a starting point somebody typed, so a figure read off the running server beats it. But an
     * operator who wrote {@code servedContextTokens} into their config did that deliberately —
     * perhaps to hold a model below what the box would allow — so their number beats the server's.
     * Nothing discovered can overwrite something explicitly stated.
     *
     * @param discovered what the endpoint reported, or null / {@link ServerCapabilities#NOTHING}
     *                   when it was never asked or did not answer
     */
    public static ModelQuirks resolve(String shapeId, ModelQuirksConfig overrides, Boolean thinking,
                                      ServerCapabilities discovered) {
        ModelQuirks base = ModelShapes.get(shapeId);
        if (discovered != null) {
            base = discovered.applyTo(base);
        }
        if (shapeId != null && !shapeId.isBlank() && !ModelShapes.known(shapeId)) {
            // Not fatal: config outlives a catalogue. But it must never pass unremarked, because
            // the operator believes they picked a shape and they are on the generic one.
            base = base.withLabel("UNKNOWN shape '" + shapeId + "' — using " + ModelShapes.GENERIC);
        }
        ModelQuirks q = base;
        if (overrides != null) {
            q = new ModelQuirks(
                base.label(),
                overrides.maxOutputTokens != null && overrides.maxOutputTokens > 0
                    ? overrides.maxOutputTokens : base.maxOutputTokens(),
                base.thinking(),
                resolveMechanism(overrides.thinkingKwarg, base.thinkingKwarg()),
                resolveMechanism(overrides.noThinkDirective, base.noThinkDirective()),
                overrides.textualToolHistory != null ? overrides.textualToolHistory : base.textualToolHistory(),
                overrides.jsonResponseFormat != null ? overrides.jsonResponseFormat : base.jsonResponseFormat(),
                overrides.httpVersion != null && !overrides.httpVersion.isBlank()
                    ? overrides.httpVersion.trim().startsWith("2") : base.http2(),
                overrides.servedContextTokens != null && overrides.servedContextTokens > 0
                    ? overrides.servedContextTokens : base.servedContextTokens(),
                overrides.workingContextTokens != null && overrides.workingContextTokens > 0
                    ? overrides.workingContextTokens : base.workingContextTokens(),
                overrides.kvBytesPerToken != null && overrides.kvBytesPerToken > 0
                    ? overrides.kvBytesPerToken : base.kvBytesPerToken(),
                overrides.maxConcurrentSequences != null && overrides.maxConcurrentSequences > 0
                    ? overrides.maxConcurrentSequences : base.maxConcurrentSequences(),
                overrides.verified != null ? overrides.verified : base.verified(),
                base.loadedTokensPerSecond());
        }
        if (thinking != null) {
            q = q.withThinking(thinking);
        }
        return q;
    }

    /** Blank = inherit the shape; the literal "none" = this model has no such mechanism. */
    private static String resolveMechanism(String stated, String inherited) {
        if (stated == null || stated.isBlank()) {
            return inherited;
        }
        return NONE.equalsIgnoreCase(stated.trim()) ? null : stated.trim();
    }

    /** Writes a resolved set back out as an explicit block, for the settings screen's round trip. */
    public static ModelQuirksConfig of(ModelQuirks q) {
        return new ModelQuirksConfig(
            q.maxOutputTokens(),
            q.thinkingKwarg() == null ? NONE : q.thinkingKwarg(),
            q.noThinkDirective() == null ? NONE : q.noThinkDirective(),
            q.textualToolHistory(),
            q.jsonResponseFormat(),
            q.http2() ? "2" : "1.1",
            q.servedContextTokens(),
            q.workingContextTokens(),
            q.kvBytesPerToken(),
            q.maxConcurrentSequences(),
            q.verified());
    }
}
