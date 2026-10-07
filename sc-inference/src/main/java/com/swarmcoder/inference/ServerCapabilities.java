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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a model server says about its own limits, asked at startup instead of guessed in a constant.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every capacity figure in {@link ModelQuirks} used to be a number somebody typed. They were
 * measured once, on one box, against a model that has since been replaced, and they then travelled
 * forward untouched: the served context said 65536 while the box actually serves 262144, and the
 * concurrency said 16 while the server refuses to run more than 8 at a time. Both are printed by
 * the server itself on two endpoints that cost nothing to read and involve no inference.
 *
 * <h2>The two endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET {base}/v1/models} — the OpenAI-standard model list. SGLang and vLLM both add
 *       {@code max_model_len} to each entry: the longest single request the server will accept.
 *   <li>{@code GET {root}/get_server_info} — SGLang only, and the interesting one. It reports
 *       {@code max_running_requests} (real concurrency), {@code max_total_num_tokens} (the size of
 *       the key/value cache, in tokens, after the weights were loaded) and
 *       {@code max_req_input_len}.
 * </ul>
 *
 * <h2>Three rules it must not break</h2>
 *
 * <p><b>A server that is down must not stop SwarmCoder starting.</b> Discovery is best effort with
 * a short timeout. Nothing here throws; a failed probe returns {@link #NOTHING} and the configured
 * or default figures stand, with a log line saying so. The operator restarts model servers
 * independently of SwarmCoder all day.
 *
 * <p><b>Not every endpoint is SGLang.</b> A cloud API answers {@code /v1/models} with no
 * {@code max_model_len} and has no {@code /get_server_info} at all. That is normal, not a fault:
 * the missing figures are simply not discovered, and it is logged as an ordinary fact rather than
 * an alarm.
 *
 * <p><b>An operator's explicit setting always wins.</b> This class only ever supplies a figure the
 * config did not state — see {@link #applyTo(ModelQuirks)} and the resolution order in the
 * settings layer: shape, then discovery, then whatever the operator wrote.
 */
public record ServerCapabilities(
    /** The server root that was probed, for the log. Null when nothing was probed. */
    String endpoint,
    /** Longest single request the server accepts, in tokens. 0 = not discovered. */
    int servedContextTokens,
    /** Requests the server will genuinely run at once. 0 = not discovered. */
    int maxConcurrentSequences,
    /** Size of the key/value cache in tokens — the pool the running requests share. 0 = not discovered. */
    int kvCachePoolTokens,
    /** One plain sentence about what happened, always present. */
    String note
) {

    /** Nothing was learned. Every consumer treats this as "leave the configured values alone". */
    public static final ServerCapabilities NOTHING =
        new ServerCapabilities(null, 0, 0, 0, "nothing was asked");

    /**
     * Fraction of a sequence's fair share of the key/value cache that a session may occupy.
     *
     * <p>Not a fudge factor for its own sake. A server whose cache is exactly full stalls: SGLang
     * reserves decode tokens per running request, the radix cache holds shared prefixes, and a
     * request that cannot get a page waits. {@link ModelQuirks#workingContextTokens()} is
     * documented as headroom below the ceiling rather than a target, and this is the headroom.
     */
    private static final int WORKING_CONTEXT_PERCENT = 90;

    /** Long enough for a busy server to answer, short enough that a dead one costs a moment. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** True when at least one real figure came back. */
    public boolean discoveredAnything() {
        return servedContextTokens > 0 || maxConcurrentSequences > 0 || kvCachePoolTokens > 0;
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Asks one endpoint what it can do. Never throws, never blocks for long, never sends a prompt.
     *
     * @param http      the client to ask with — injectable so tests need no server
     * @param baseUrl   the configured endpoint, with or without {@code /v1} or a trailing slash
     * @param modelName which entry of the model list to read, or blank for the only/first one
     */
    public static ServerCapabilities discover(HttpClient http, String baseUrl, String modelName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return NOTHING;
        }
        String root = root(baseUrl);
        int servedContext = 0;
        int maxRunning = 0;
        int kvPoolTokens = 0;
        boolean modelsAnswered = false;
        boolean serverInfoAnswered = false;

        JsonNode models = getJson(http, root + "/v1/models");
        if (models != null) {
            modelsAnswered = true;
            JsonNode entry = modelEntry(models, modelName);
            if (entry != null) {
                servedContext = positive(entry, "max_model_len", "max_context_length",
                    "context_length", "max_seq_len");
            }
        }

        JsonNode info = getJson(http, root + "/get_server_info");
        if (info != null) {
            serverInfoAnswered = true;
            maxRunning = positive(info, "max_running_requests");
            kvPoolTokens = positive(info, "max_total_num_tokens", "max_total_tokens");
            if (servedContext == 0) {
                servedContext = positive(info, "max_req_input_len", "context_length",
                    "max_model_len");
            }
        }

        String note;
        if (!modelsAnswered && !serverInfoAnswered) {
            note = "the server did not answer, so nothing was discovered";
        } else if (!serverInfoAnswered) {
            // The ordinary shape of a cloud API, and of any non-SGLang server. Not a fault.
            note = "answered the model list but has no server-info page, so only the context "
                + "length could be read";
        } else {
            note = "answered both the model list and the server-info page";
        }
        return new ServerCapabilities(root, servedContext, maxRunning, kvPoolTokens, note);
    }

    // ---------------------------------------------------------------- the derivation

    /**
     * How much context one session may occupy, given what the server reported.
     *
     * <h2>Why this is not "served context divided by sessions"</h2>
     *
     * <p>That division looks right and is wrong. {@code max_model_len} is a ceiling on ONE request
     * — the longest prompt plus answer the server will accept from anybody. It is not a pool of
     * tokens that the running requests share out, so dividing it by the concurrency divides the
     * wrong quantity. On this box it gives 262144 / 8 = 32768, which lands close to the old
     * hardcoded figure by coincidence and is a quarter of what the hardware can actually hold.
     *
     * <p>What genuinely gets shared is the key/value cache, and SGLang reports its size directly:
     * {@code max_total_num_tokens}, already worked out from the graphics memory left after the
     * weights were loaded. Divide THAT by the concurrency and the answer means something — it is
     * one running request's fair share of the cache. Cap it at the single-request ceiling, because
     * a fair share larger than what one request may be is not usable, and keep
     * {@link #WORKING_CONTEXT_PERCENT} of it as headroom.
     *
     * <p>On the 2026-08 box: 462103 cache tokens / 8 running requests = 57762 each, 90% of that is
     * 51985, rounded down to a whole 1024 = 51200 — well under the 262144 single-request ceiling.
     *
     * <p>When the cache size is unknown (any server that is not SGLang) there is nothing honest to
     * divide, so this returns 0 and the configured working figure stands untouched.
     *
     * @return the derived working budget in tokens, or 0 when it cannot be derived honestly
     */
    public int derivedWorkingContextTokens() {
        return derivedWorkingContextTokens(maxConcurrentSequences);
    }

    /**
     * The same derivation with the divisor chosen by the caller — the pool shared by {@code
     * concurrency} sessions rather than by the number the server would run at once.
     *
     * <p>This is the one place the count and the room meet, and {@link AdaptiveConcurrency} exists
     * to turn it: 462103 tokens shared 8 ways is 51200 each, shared 4 ways it is 103424, shared 2
     * ways it is 207872 (still under the 262144 single-request ceiling). Nothing else about the
     * arithmetic changes with the divisor.
     *
     * @return the derived working budget in tokens, or 0 when the pool is unknown
     */
    public int derivedWorkingContextTokens(int concurrency) {
        if (kvCachePoolTokens <= 0 || concurrency <= 0) {
            return 0;
        }
        long fairShare = (long) kvCachePoolTokens / concurrency;
        long withHeadroom = fairShare * WORKING_CONTEXT_PERCENT / 100;
        if (servedContextTokens > 0) {
            withHeadroom = Math.min(withHeadroom, servedContextTokens);
        }
        long rounded = withHeadroom / 1024 * 1024;
        return (int) Math.max(1024, Math.min(rounded, Integer.MAX_VALUE));
    }

    // ---------------------------------------------------------------- merging

    /**
     * The starting profile with every figure this discovered written over it.
     *
     * <p>This is applied to the SHAPE, before the operator's own overrides — a shape is documented
     * as a starting point rather than an assertion, so a measurement from the live server beats it.
     * Anything not discovered is left exactly as it was.
     */
    public ModelQuirks applyTo(ModelQuirks base) {
        if (base == null || !discoveredAnything()) {
            return base;
        }
        int served = servedContextTokens > 0 ? servedContextTokens : base.servedContextTokens();
        int concurrency = maxConcurrentSequences > 0
            ? maxConcurrentSequences : base.maxConcurrentSequences();
        int working = derivedWorkingContextTokens();
        if (working <= 0) {
            working = base.workingContextTokens();
        }
        // The working budget can never exceed what one request may be. Nothing derived here can
        // break that, but a shape's own figure combined with a smaller discovered ceiling can.
        working = Math.min(working, served);
        return new ModelQuirks(base.label(), base.maxOutputTokens(), base.thinking(),
            base.thinkingKwarg(), base.noThinkDirective(), base.textualToolHistory(),
            base.jsonResponseFormat(), base.http2(),
            served, working, base.kvBytesPerToken(), concurrency, base.verified(),
            base.loadedTokensPerSecond());
    }

    /**
     * One line for the startup log, naming what the server said against what was written down.
     *
     * <p>Written for somebody who has spent a week unable to tell what the system thinks it is
     * doing, so it says the old number and the new one every time either moves.
     *
     * @param declared what the configuration resolved to before discovery ran
     * @param finalQuirks what is actually in force, after the operator's own settings won back
     */
    public String describeAgainst(ModelQuirks declared, ModelQuirks finalQuirks) {
        StringBuilder out = new StringBuilder();
        out.append(endpoint == null ? "no endpoint" : endpoint).append(" — ").append(note).append('.');
        if (!discoveredAnything()) {
            out.append(" Keeping the settings already in force: context ")
                .append(finalQuirks.servedContextTokens())
                .append(", ").append(finalQuirks.maxConcurrentSequences())
                .append(" request(s) at once, ").append(finalQuirks.workingContextTokens())
                .append(" tokens per session.");
            return out.toString();
        }
        out.append(" It reports");
        out.append(servedContextTokens > 0
            ? " a longest single request of " + servedContextTokens + " tokens"
            : " no request length");
        out.append(maxConcurrentSequences > 0
            ? ", " + maxConcurrentSequences + " request(s) at once"
            : ", no concurrency figure");
        out.append(kvCachePoolTokens > 0
            ? ", and room for " + kvCachePoolTokens + " tokens of memory shared between them."
            : ", and no memory figure.");
        out.append(" In force: context ").append(field(declared.servedContextTokens(),
            finalQuirks.servedContextTokens()));
        out.append(", ").append(field(declared.maxConcurrentSequences(),
            finalQuirks.maxConcurrentSequences())).append(" request(s) at once");
        out.append(", ").append(field(declared.workingContextTokens(),
            finalQuirks.workingContextTokens())).append(" tokens per session.");
        return out.toString();
    }

    private static String field(int declared, int inForce) {
        return declared == inForce ? String.valueOf(inForce)
            : inForce + " (was " + declared + ")";
    }

    // ---------------------------------------------------------------- plumbing

    /** The server root: no trailing slash, and no {@code /v1} — {@code get_server_info} sits above it. */
    static String root(String baseUrl) {
        String root = baseUrl.trim().replaceAll("/+$", "");
        if (root.endsWith("/v1")) {
            root = root.substring(0, root.length() - "/v1".length());
        }
        return root.replaceAll("/+$", "");
    }

    /** A GET that answers with parsed JSON, or null for anything else at all. Never throws. */
    private static JsonNode getJson(HttpClient http, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 || response.body() == null || response.body().isBlank()) {
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Unreachable, malformed URL, HTML error page, anything: not discovered, not a fault.
            return null;
        }
    }

    /** The named model's entry in an OpenAI-style list, or the first entry when the name misses. */
    private static JsonNode modelEntry(JsonNode models, String modelName) {
        JsonNode data = models.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        if (modelName != null && !modelName.isBlank()) {
            for (JsonNode entry : data) {
                JsonNode id = entry.get("id");
                if (id != null && modelName.trim().equals(id.asText())) {
                    return entry;
                }
            }
        }
        return data.get(0);
    }

    /** The first of these keys holding a positive whole number, or 0. */
    private static int positive(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isIntegralNumber() && value.asLong() > 0
                    && value.asLong() <= Integer.MAX_VALUE) {
                return value.asInt();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- caching

    /**
     * Discovers once per endpoint per SwarmCoder start, so eight worker families on one box cost
     * one pair of requests rather than sixteen.
     */
    public static final class Cache {
        private final HttpClient http;
        private final Map<String, ServerCapabilities> byEndpoint = new ConcurrentHashMap<>();

        public Cache() {
            this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        }

        /** For tests: a client whose behaviour the test controls. */
        public Cache(HttpClient http) {
            this.http = http;
        }

        public ServerCapabilities forEndpoint(String baseUrl, String modelName) {
            if (baseUrl == null || baseUrl.isBlank()) {
                return NOTHING;
            }
            String key = root(baseUrl) + "|" + (modelName == null ? "" : modelName.trim());
            return byEndpoint.computeIfAbsent(key, ignored -> discover(http, baseUrl, modelName));
        }
    }
}
