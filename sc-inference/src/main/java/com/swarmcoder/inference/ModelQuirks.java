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

import java.net.http.HttpClient;

/**
 * Everything about ONE model that is a property of that model rather than of SwarmCoder.
 *
 * <p>Every field here was, at some point, a hardcoded constant or a JVM-wide system property that
 * could not differ between two models running at once. They were all correct for Qwen 3.6 27B and
 * for nothing else: {@code enable_thinking} is a Qwen chat-template argument, {@code /no_think} is
 * a Qwen in-prompt directive, HTTP/1.1 is pinned because ONE server build mis-parsed Java's HTTP/2
 * upgrade, and the memory-per-token and concurrent-sequence figures were measured on one 27B dense
 * model on one box. A new model inherits none of that automatically — it gets a
 * {@link ModelShapes} starting profile and the operator adjusts it.
 *
 * <p><b>Reading the defaults.</b> {@link #DEFAULTS} is deliberately the behaviour SwarmCoder
 * shipped with, so a config that sets nothing behaves exactly as before. But "the old behaviour"
 * and "the right behaviour for your model" are different claims, and {@link #verified} is how they
 * are told apart: false means nobody has measured these numbers against this model, and startup
 * says so out loud. Never flip it to true to quiet the warning — flip it when the measurement
 * exists. {@code thinking} is the one deliberate exception, changed 2026-09-04: the old default
 * (off) was itself the thing a measurement disproved — see {@link #thinking} — so keeping it would
 * have meant "behaves exactly as before" winning over "known to be wrong".
 *
 * @param label                  human name for logs and the settings screen
 * @param maxOutputTokens        hard cap on generated tokens per call. Without one, a rambling
 *                               generation streams toward the served context ceiling and stalls
 *                               the stage (this cost a full 15-minute run ceiling once).
 * @param thinking               whether the model should reason before answering. ON is the
 *                               default since 2026-09-04: a controlled experiment
 *                               ({@code dev/experiment/plain-loop} on branch
 *                               {@code experiment/plain-loop-ceiling}) ran the SAME plain-Java
 *                               task on the SAME model twice, thinking on vs off, nothing else
 *                               different. Thinking ON finished green in 10 turns. Thinking OFF
 *                               wrote the same code, then could not fix one NullPointerException
 *                               in 110 shell commands and ran out its full 120-turn cap. The
 *                               earlier "off by default" reasoning — thinking cost 20s vs 4.5s per
 *                               call on Qwen 3.6 — was a real number for a real cost, but it
 *                               traded correctness for latency without ever measuring the
 *                               correctness side, and the measurement above says that trade was
 *                               wrong. Turned off per role by setting {@code thinking: false} in
 *                               that role's config.
 * @param thinkingKwarg          the chat-template argument that switches reasoning off, sent inside
 *                               {@code chat_template_kwargs} (Qwen: {@code enable_thinking}).
 *                               {@code null} = this model has no such argument, send nothing.
 * @param noThinkDirective       text appended to the system prompt to switch reasoning off, for
 *                               transports that cannot send chat-template arguments (Qwen:
 *                               {@code /no_think}). {@code null} = this model has no such
 *                               directive, append nothing.
 * @param textualToolHistory     send tool calls and results in the conversation history as
 *                               plain-text JSON instead of native tool-call messages. Required by
 *                               chat templates that iterate tool-call arguments as a mapping while
 *                               OpenAI-spec clients send a string — the second turn dies with
 *                               HTTP 400 "Can only get item pairs from a mapping". Tool
 *                               *definitions* stay native either way, so the model still calls tools.
 *                               This was forced for every model because Koog 1.0.0 double-encoded
 *                               tool-call arguments on replay ({@code sc-runtime/NOTES.md}); Koog
 *                               1.2.0 fixed that (verified live 2026-09-04 against SGLang/Qwen
 *                               3.8), so native history is now the per-model default for a shape
 *                               that names an OpenAI-compatible server with native tool calling,
 *                               and this stays {@code true} only where it is still needed.
 * @param jsonResponseFormat     whether the endpoint honours {@code response_format:
 *                               {"type":"json_object"}}. When false, a JSON schema is sent as
 *                               vLLM's {@code guided_json} instead.
 * @param http2                  use HTTP/2. Default false: Java's h2c upgrade made one vLLM/uvicorn
 *                               build receive requests with an EMPTY body (HTTP 400 "body Field
 *                               required"), so HTTP/1.1 is pinned until a server is known good.
 * @param servedContextTokens    the context length the server is actually started with. This is
 *                               headroom to avoid a near-limit crash, not a target.
 * @param workingContextTokens   the context SwarmCoder will let one session occupy. Kept below the
 *                               served ceiling on purpose.
 * @param kvBytesPerToken        bytes of key/value cache one token costs — the scheduler's
 *                               admission-control currency. MUST be measured per model: it does not
 *                               follow from parameter count, and a mixture-of-experts model does
 *                               not behave like a dense one of the same size.
 * @param maxConcurrentSequences how many requests the server will genuinely serve at once. Also a
 *                               per-model measurement, and the number that decides how many workers
 *                               can attack a task in parallel.
 * @param verified               whether the numbers above were measured against THIS model on THIS
 *                               box. False makes startup say so.
 * @param loadedTokensPerSecond  completion tokens a second ONE request gets while the server is
 *                               serving as many requests as it takes at once - measured, per
 *                               model and box. 0 means not measured, and then nothing is derived
 *                               from it. It bounds how long an answer one turn may ask for: see
 *                               {@link #turnOutputTokens(int)} (live run 74, 2026-10-03).
 */
public record ModelQuirks(
    String label,
    int maxOutputTokens,
    boolean thinking,
    String thinkingKwarg,
    String noThinkDirective,
    boolean textualToolHistory,
    boolean jsonResponseFormat,
    boolean http2,
    int servedContextTokens,
    int workingContextTokens,
    int kvBytesPerToken,
    int maxConcurrentSequences,
    boolean verified,
    double loadedTokensPerSecond
) {

    /** A shape whose loaded rate was never measured. */
    public ModelQuirks(String label, int maxOutputTokens, boolean thinking, String thinkingKwarg,
                       String noThinkDirective, boolean textualToolHistory,
                       boolean jsonResponseFormat, boolean http2, int servedContextTokens,
                       int workingContextTokens, int kvBytesPerToken, int maxConcurrentSequences,
                       boolean verified) {
        this(label, maxOutputTokens, thinking, thinkingKwarg, noThinkDirective, textualToolHistory,
            jsonResponseFormat, http2, servedContextTokens, workingContextTokens, kvBytesPerToken,
            maxConcurrentSequences, verified, 0);
    }

    /**
     * The share of a request's time allowed that is counted on for writing the answer. The rest
     * is for the server reading the prompt first and for a rate lower than the measured one.
     */
    public static final double SHARE_OF_TIMEOUT_FOR_OUTPUT = 0.8;

    /** No turn is held below this, whatever the rate: a tool call with a file in it needs room. */
    public static final int LEAST_TURN_OUTPUT_TOKENS = 2048;

    /**
     * How many output tokens one turn may ask for when the client stops waiting after
     * {@code requestTimeoutSeconds}: what this model writes in that time on a loaded server, and
     * never more than {@link #maxOutputTokens}.
     *
     * <p>Live run 74, 2026-10-03: worker calls are not streamed, so an answer still being written
     * when the client gives up is thrown away whole, and the worker with it. At about 11 tokens a
     * second a turn allowed 32,768 tokens cannot finish in 900 seconds once it passes about 9,900;
     * five did not (75 slot-minutes). An answer cut off at an allowance the server can meet in
     * time comes back, and the session goes on.
     *
     * <p>Unchanged when the rate was not measured or no timeout is given.
     */
    public int turnOutputTokens(int requestTimeoutSeconds) {
        if (loadedTokensPerSecond <= 0 || requestTimeoutSeconds <= 0) {
            return maxOutputTokens;
        }
        long fits = (long) Math.floor(loadedTokensPerSecond * requestTimeoutSeconds
            * SHARE_OF_TIMEOUT_FOR_OUTPUT);
        return (int) Math.max(Math.min(LEAST_TURN_OUTPUT_TOKENS, maxOutputTokens),
            Math.min(maxOutputTokens, fits));
    }

    public ModelQuirks withLoadedTokensPerSecond(double rate) {
        return new ModelQuirks(label, maxOutputTokens, thinking, thinkingKwarg, noThinkDirective,
            textualToolHistory, jsonResponseFormat, http2, servedContextTokens,
            workingContextTokens, kvBytesPerToken, maxConcurrentSequences, verified, rate);
    }

    /**
     * The behaviour SwarmCoder shipped with, so nothing changes for anyone who edits nothing.
     * Marked UNVERIFIED because it is only known to be right for one model — see the class note.
     */
    public static final ModelQuirks DEFAULTS = new ModelQuirks(
        "unnamed model",
        32768,          // maxOutputTokens — the by-design ceiling since 2026-07-12
        true,           // thinking on — see the class note; a role sets thinking:false to opt out
        "enable_thinking",
        "/no_think",
        true,           // textual tool history
        true,           // json_object honoured
        false,          // HTTP/1.1
        65536,
        32768,
        1024,
        16,
        false);

    public ModelQuirks {
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive, was " + maxOutputTokens);
        }
        if (servedContextTokens <= 0) {
            throw new IllegalArgumentException("servedContextTokens must be positive, was " + servedContextTokens);
        }
        if (workingContextTokens <= 0) {
            throw new IllegalArgumentException("workingContextTokens must be positive, was " + workingContextTokens);
        }
        if (kvBytesPerToken <= 0) {
            throw new IllegalArgumentException("kvBytesPerToken must be positive, was " + kvBytesPerToken);
        }
        if (maxConcurrentSequences <= 0) {
            throw new IllegalArgumentException("maxConcurrentSequences must be positive, was " + maxConcurrentSequences);
        }
        if (loadedTokensPerSecond < 0 || Double.isNaN(loadedTokensPerSecond)) {
            throw new IllegalArgumentException(
                "loadedTokensPerSecond must not be negative, was " + loadedTokensPerSecond);
        }
        label = label == null || label.isBlank() ? "unnamed model" : label;
        thinkingKwarg = blankToNull(thinkingKwarg);
        noThinkDirective = blankToNull(noThinkDirective);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public HttpClient.Version httpVersion() {
        return http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
    }

    /** True when this model needs a chat-template argument sent to suppress reasoning. */
    public boolean sendsThinkingKwarg() {
        return !thinking && thinkingKwarg != null;
    }

    /** True when this model needs an in-prompt directive appended to suppress reasoning. */
    public boolean appendsNoThinkDirective() {
        return !thinking && noThinkDirective != null;
    }

    /**
     * One line naming every setting, for the startup announcement and the run log. An operator
     * reading a puzzling run must be able to see what the model was actually told.
     */
    public String describe() {
        return "maxOutputTokens=" + maxOutputTokens
            + " thinking=" + (thinking ? "on" : "off")
            + " thinkingKwarg=" + (thinkingKwarg == null ? "(none)" : thinkingKwarg)
            + " noThinkDirective=" + (noThinkDirective == null ? "(none)" : noThinkDirective)
            + " toolHistory=" + (textualToolHistory ? "text" : "native")
            + " jsonResponseFormat=" + jsonResponseFormat
            + " http=" + (http2 ? "2" : "1.1")
            + " servedContext=" + servedContextTokens
            + " workingContext=" + workingContextTokens
            + " kvBytesPerToken=" + kvBytesPerToken
            + " maxConcurrentSequences=" + maxConcurrentSequences
            + (loadedTokensPerSecond > 0 ? " loadedTokensPerSecond=" + loadedTokensPerSecond : "")
            + (verified ? " [measured]" : " [UNVERIFIED]");
    }

    // --- narrow overrides, so a caller can change one thing without respelling twelve ---------

    public ModelQuirks withLabel(String newLabel) {
        return new ModelQuirks(newLabel, maxOutputTokens, thinking, thinkingKwarg, noThinkDirective,
            textualToolHistory, jsonResponseFormat, http2, servedContextTokens, workingContextTokens,
            kvBytesPerToken, maxConcurrentSequences, verified, loadedTokensPerSecond);
    }

    public ModelQuirks withThinking(boolean newThinking) {
        return new ModelQuirks(label, maxOutputTokens, newThinking, thinkingKwarg, noThinkDirective,
            textualToolHistory, jsonResponseFormat, http2, servedContextTokens, workingContextTokens,
            kvBytesPerToken, maxConcurrentSequences, verified, loadedTokensPerSecond);
    }

    /**
     * The same model with a different room per session — how {@link AdaptiveConcurrency} hands a
     * wave the room its smaller count earned. Everything else, the served ceiling included, is
     * untouched: the ceiling is a fact about the server and this is a fact about the wave.
     */
    public ModelQuirks withWorkingContextTokens(int newWorkingContextTokens) {
        return new ModelQuirks(label, maxOutputTokens, thinking, thinkingKwarg, noThinkDirective,
            textualToolHistory, jsonResponseFormat, http2, servedContextTokens,
            newWorkingContextTokens, kvBytesPerToken, maxConcurrentSequences, verified, loadedTokensPerSecond);
    }

    public ModelQuirks withJsonResponseFormat(boolean honoured) {
        return new ModelQuirks(label, maxOutputTokens, thinking, thinkingKwarg, noThinkDirective,
            textualToolHistory, honoured, http2, servedContextTokens, workingContextTokens,
            kvBytesPerToken, maxConcurrentSequences, verified, loadedTokensPerSecond);
    }
}
