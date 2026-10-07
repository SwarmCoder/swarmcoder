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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named starting points for {@link ModelQuirks}, so adding a model is "pick the shape it is, then
 * adjust", not "fill in twelve blank fields and hope".
 *
 * <p>A shape is a STARTING POINT, never an assertion. Only entries whose
 * {@link ModelQuirks#verified()} is true carry numbers somebody actually measured against that
 * model on that box; everything else is the safe default carried forward, and startup says so.
 *
 * <p>Adding a shape here is cheap and is the right move whenever a family's conventions are known
 * (which chat-template argument switches reasoning off, whether the chat template survives native
 * tool-call history). Putting a number here that nobody measured is the wrong move: leave the
 * default and let the warning stand until the measurement exists.
 */
public final class ModelShapes {

    /** Fallback shape id — used when a role names no shape at all. */
    public static final String GENERIC = "generic-openai";

    private static final Map<String, ModelQuirks> SHAPES = new LinkedHashMap<>();

    static {
        // The conservative shape for a model nobody has characterised. No reasoning switch is sent
        // at all (an unknown chat template may reject an argument it does not know) — but thinking
        // itself defaults ON (2026-09-04): that is the server's own default for any model that can
        // reason at all, and the plain-loop experiment (see ModelQuirks) showed forcing it off
        // trades away correctness for a latency saving nobody asked for. Tool history is textified
        // (the safe direction: it works on templates that accept native history too), and HTTP/1.1
        // is pinned.
        put(GENERIC, new ModelQuirks(
            "Generic OpenAI-compatible endpoint (nothing measured)",
            32768, true, null, null, true, true, false,
            65536, 32768, 1024, 16, false));

        // What the system actually ran on from 2026-07-10 to 2026-08-27. Every value here was
        // established the hard way and is written up in docs/DEVELOPER_CORRECTIONS.md §8 and §10:
        // enable_thinking:false (20s -> 4.5s per call), /no_think on the worker path because the
        // agent framework cannot send chat-template arguments, textual tool history (HTTP 400
        // "Can only get item pairs from a mapping" on the second turn without it), HTTP/1.1
        // (h2c upgrade produced empty request bodies), 64k served / 32k working, and the
        // scheduler figures the box was configured with.
        //
        // thinking flipped to ON 2026-09-04 (see ModelQuirks): the 20s-vs-4.5s figure above is
        // still a real number, but it was never weighed against correctness, and the plain-loop
        // experiment against this model's 2026-08 replacement showed thinking-off cannot fix a
        // NullPointerException it wrote, in 110 shell commands, where thinking-on fixed the
        // equivalent in 10 turns. The chat-template argument and directive stay named so a role
        // can still opt out with thinking: false.
        put("qwen36-27b", new ModelQuirks(
            "Qwen 3.6 27B (dense) — the measured 2026-07 roster",
            32768, true, "enable_thinking", "/no_think", true, true, false,
            65536, 32768, 1024, 16, true));

        // The 2026-08 replacement, as it ACTUALLY IS (measured 2026-08-28, docs §S1). The comment
        // that stood here said 125 billion parameters, mixture-of-experts, ~6B active per token.
        // That was written from planning notes and never from the box, and it was wrong: the box
        // serves a 27B model, id "qwen3.8-27b", on SGLANG rather than vLLM, with a served context
        // of 262144 and about 29 tokens/second single-stream.
        //
        // THE ID IS A MISNOMER AND IS KEPT ONLY SO SAVED CONFIGURATIONS KEEP RESOLVING. Renaming
        // a shape id is a config migration; the label carries the truth instead.
        //
        // Reasoning is ON by default on this server. A note that stood here said it "must be
        // switched off": left on, a request for the single word "OK" came back with empty content
        // and the whole budget spent deliberating. That was a real observation about a TRIVIAL
        // reply racing its own token cap, and it is superseded, not wrong: the plain-loop control
        // experiment (dev/experiment/plain-loop, 2026-09-04, this exact model/endpoint) ran a real
        // coding task thinking-on vs thinking-off with nothing else different. Thinking ON finished
        // green in 10 turns; thinking OFF wrote the same code and then could not fix one
        // NullPointerException in 110 shell commands before hitting its turn cap. Thinking is back
        // ON here because that trade — a rare empty trivial reply against a worker that cannot fix
        // its own bugs — favours thinking, and {@code KoogAgentRuntime}'s per-worker
        // {@code maxOutputTokens} cap is the thing to widen if the empty-reply pattern reappears,
        // not a reason to force reasoning off by default again. enable_thinking:false stays
        // available for a role that opts out with {@code thinking: false}.
        //
        // Native tool calling is also verified here — correct JSON arguments, no double-encoding —
        // and as of Koog 1.2.0 (2026-09-04) that is true for tool-call HISTORY too, not just
        // definitions: Koog 1.0.0's AbstractOpenAILLMClient double-encoded replayed tool-call
        // arguments (sc-runtime/NOTES.md), 1.2.0 fixed it, and a live three-turn probe against
        // this endpoint with native history round-tripped clean. textualToolHistory is therefore
        // false here — the one shape where it is, so far.
        //
        // Three of the four scheduler figures were read straight off the server on 2026-09-01 and
        // no longer have to be guessed at all — SwarmCoder now asks every endpoint the same two
        // questions at startup (ServerCapabilities), and these literals are only the fallback for
        // when the box is switched off. What it answered:
        //   GET /v1/models        max_model_len         = 262144  (longest single request)
        //   GET /get_server_info  max_running_requests  = 8       (was written down as 16)
        //                         max_total_num_tokens  = 462103  (the shared key/value cache)
        // The working budget is that cache divided by the 8 concurrent requests, with 10% kept back
        // as headroom and rounded down: 462103/8 = 57762, 90% = 51985, floor to 51200. It is NOT
        // 262144/8 — the longest allowed request is a per-request ceiling, not a pool to share out.
        //
        // Still UNVERIFIED for one reason only: what a token costs in key/value cache bytes has
        // never been measured here, and sglang schedules differently from vLLM so it cannot be
        // inherited from the previous model.
        put("qwen38-flash-next-125b", new ModelQuirks(
            "Qwen 3.8 27B on sglang (id \"qwen3.8-27b\") — the shape id says 125B and is a "
                + "misnomer kept for config stability; memory per token not measured",
            32768, true, "enable_thinking", "/no_think", false, true, false,
            262144, 51200, 1024, 8, false));

        // The 2026-09 replacement: DeepSeek V4 Flash served by "ds4" (neither vLLM nor sglang) on
        // the Spark, port 8000, id "deepseek-v4-flash". The only model on the box — it only just
        // fits — so there is no second worker family. Measured 2026-09-25 against the live server:
        //   - It reasons by default, and enable_thinking:false in chat_template_kwargs is accepted
        //     and IGNORED (610 characters of reasoning for "Say OK"). There is no known switch, so
        //     none is named: thinking stays on, which is what every role wants anyway.
        //   - response_format json_object AND json_schema both fail HTTP 400 "structured output is
        //     unsupported". guided_json is silently ignored and the prompt's own "JSON only" is
        //     what gets JSON back. jsonResponseFormat must be false or every JSON call dies.
        //   - Native tool calls are clean JSON, and native tool-call history round-trips (a
        //     read_file call replayed with its result produced the right answer), so history is
        //     native, as for the Qwen 3.8 shape.
        //   - /v1/models reports context_length 1048576 and no get_server_info page exists, so
        //     the concurrency figure is not discoverable. Measured instead: ~33 tokens/s single
        //     stream, ~47 aggregate at 4 parallel, ~46 at 8 — nothing gained past 4.
        //   - Text only (input_modalities: ["text"]): never use it as the vision role.
        //   - Memory is not the limit. ds4 maps context on demand and its own proving run held
        //     2.26 million tokens resident at once on one 128 GB Spark, at about 4.3 KiB a token.
        //     Four workers at 262144 each is about 1M tokens, about 4.5 GB. The working room is
        //     that large on purpose: on Qwen at 51200 the workers thrashed, compacting and
        //     re-reading, and the zeroz4j reference material a worker needs is big.
        // Still UNVERIFIED: kvBytesPerToken stays the old 1024 guess, and 262144 is a chosen room
        // per worker, not a figure the server gave. Watch decode speed at depth before raising it.
        put("deepseek-v4-flash-ds4", new ModelQuirks(
            "DeepSeek V4 Flash on ds4 (id \"deepseek-v4-flash\") — no structured output, "
                + "reasoning cannot be switched off, 4 at once; memory per token not measured",
            32768, true, null, null, false, false, false,
            1048576, 262144, 1024, 4, false,
            // Live run 74, 2026-10-03: about 11 completion tokens a second for one request
            // while four are being served (worker turns of 2,500-4,600 reasoning tokens).
            11.0));

        // The same model on DeepSeek's HOSTED API (api.deepseek.com), picked by host + model when a
        // role names no shape (see inferredFor). It is NOT the Spark: no 4-at-once limit, no
        // 11 tokens/s, and the hosted API does answer response_format json_object. Run 79: with no
        // shape the cloud roles fell to the generic 32,768 room, the planner's fixed opening
        // (14-19k) filled it, its history was cut constantly and it needed 102 calls.
        // THE CONTEXT FIGURES ARE CHOSEN, NOT MEASURED: served 1,048,576, working room 983,040 (owner decision 2026-10-04: the hosted model may use up to 1M)
        // The hosted /v1/models gives no context length, so discovery has
        // nothing to say here; if it ever does, a discovered figure beats this one.
        put("deepseek-v4-flash-api", new ModelQuirks(
            "DeepSeek V4 Flash on the hosted API (api.deepseek.com) - context figures chosen, "
                + "not measured",
            32768, true, null, null, false, true, false,
            1048576, 983040, 1024, 16, false));

        // Vision endpoints get NO reasoning switch. Sending a chat-template argument a vision
        // template does not read is noise at best and a template error at worst, and this used to
        // be an `if` inside the shared client — a per-model rule living in code that every model
        // had to run through.
        put("vision", new ModelQuirks(
            "Vision-capable endpoint (no reasoning switch, no tool use)",
            32768, false, null, null, true, true, false,
            65536, 32768, 1024, 16, false));
    }

    private ModelShapes() {}

    private static void put(String id, ModelQuirks quirks) {
        SHAPES.put(id, quirks);
    }

    /** Shape ids in catalogue order — what the settings screen offers. */
    public static List<String> ids() {
        return List.copyOf(SHAPES.keySet());
    }

    public static boolean known(String id) {
        return id != null && SHAPES.containsKey(id);
    }

    /**
     * The named shape, or the generic one when the name is blank or unknown. An unknown name is
     * NOT an error: config outlives this catalogue, and a run that refuses to start because a
     * shape was renamed is worse than one that starts on conservative settings and says so.
     */
    public static ModelQuirks get(String id) {
        if (id == null || id.isBlank()) {
            return SHAPES.get(GENERIC);
        }
        ModelQuirks quirks = SHAPES.get(id);
        return quirks != null ? quirks : SHAPES.get(GENERIC);
    }

    /**
     * The shape a role gets when its config names none: chosen by the server's host and the model
     * name, or null when nothing matches (then the generic shape applies, as before). A named
     * shape always wins; this only fills a blank.
     */
    public static String inferredFor(String baseUrl, String model) {
        if (baseUrl == null || model == null) {
            return null;
        }
        String host;
        try {
            host = java.net.URI.create(baseUrl.trim()).getHost();
        } catch (RuntimeException e) {
            return null;
        }
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        if ((host.equals("api.deepseek.com") || host.endsWith(".deepseek.com"))
                && model.trim().equalsIgnoreCase("deepseek-v4-flash")) {
            return "deepseek-v4-flash-api";
        }
        return null;
    }

    /** One line per shape, for the startup log and the settings screen's help text. */
    public static List<String> descriptions() {
        return SHAPES.entrySet().stream()
            .map(e -> e.getKey() + " — " + e.getValue().label())
            .toList();
    }
}
