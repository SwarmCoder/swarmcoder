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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Is a model endpoint answering? One cached {@code GET /v1/models} per endpoint, shared by everything
 * that needs the answer.
 *
 * <p><b>Why this is shared rather than local to a caller.</b> Two places need it and they must not
 * disagree: the swarm, which will not dispatch eight workers at a box that is not there, and the
 * Console's health indicator, which tells the operator the same thing. A health dot reading "up" beside
 * a build paused for an outage — or the reverse — is worse than either being wrong alone, because the
 * operator cannot tell which to believe. One derivation, per UX v3 rule 2.
 *
 * <p><b>Three answers, not two.</b> {@link Status#UP}, {@link Status#DOWN}, and {@link Status#UNKNOWN}
 * for "there is nothing configured to probe". The distinction is load-bearing: a build must pause when a
 * configured endpoint is unreachable, and must NOT pause when no endpoint is configured at all, because
 * some setups drive workers through something this cannot see. Collapsing unknown into down would hang
 * every such run for ever, waiting for a box nobody ever named.
 *
 * <p><b>Any HTTP response means reachable.</b> A 404 or a 500 is a box that answered — it is running,
 * just not serving what was asked for. Only a connection-level failure is DOWN. Treating an error status
 * as down would pause builds against an endpoint that is plainly alive.
 *
 * <p><b>It fails open.</b> An unexpected fault inside the probe itself reports UNKNOWN, so the worst a
 * bug here can do is return the behaviour to what it was before this class existed — a dispatched swarm
 * that discovers the outage the expensive way. Failing closed would let a probe defect stop every build
 * in the product.
 */
public final class EndpointReachability {

    /** Long enough that status polling never hammers a box, short enough to notice a restart. */
    private static final long TTL_MS = 4_000;

    /** Bounded: an endpoint answers in about a second or it is not answering. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    public enum Status {
        UP,
        DOWN,
        /** Nothing configured to probe — not an outage, and must never be treated as one. */
        UNKNOWN;

        /** True only for a definite, configured-but-unreachable endpoint. */
        public boolean isOutage() {
            return this == DOWN;
        }
    }

    /** One probe result plus what it was measured against, so "down" is never unactionable. */
    public record Probe(Status status, String endpoint, String models, String detail) {

        static Probe unknown(String detail) {
            return new Probe(Status.UNKNOWN, null, null, detail);
        }
    }

    private record Cached(Probe probe, long at) {}

    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EndpointReachability() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** For tests: an HTTP client whose behaviour the test controls. */
    public EndpointReachability(HttpClient http) {
        this.http = http;
    }

    /**
     * Probes {@code baseUrl}, or returns the cached answer when one is fresh.
     *
     * @param baseUrl the endpoint root, with or without a trailing slash or {@code /v1}; blank or null
     *                yields {@link Status#UNKNOWN}
     */
    public Probe probe(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Probe.unknown("No model endpoint is configured, so there is nothing to probe.");
        }
        String key = baseUrl.trim();
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < TTL_MS) {
            return cached.probe();
        }
        Probe fresh = measure(key);
        cache.put(key, new Cached(fresh, now));
        return fresh;
    }

    /**
     * The strongest answer across several endpoints — UP if any answers, DOWN only if at least one is
     * configured and none answer, UNKNOWN when none are configured.
     *
     * <p>ANY, not ALL, because a swarm split across two model families still has somewhere to run when
     * one of them is down. Requiring all of them would pause a build that could have proceeded at
     * reduced width, which is a worse outcome than a narrower swarm.
     */
    public Probe probeAny(Iterable<String> baseUrls) {
        Probe firstDown = null;
        for (String baseUrl : baseUrls) {
            Probe probe = probe(baseUrl);
            if (probe.status() == Status.UP) {
                return probe;
            }
            if (probe.status() == Status.DOWN && firstDown == null) {
                firstDown = probe;
            }
        }
        return firstDown != null ? firstDown
            : Probe.unknown("No model endpoint is configured, so there is nothing to probe.");
    }

    private Probe measure(String baseUrl) {
        String endpoint = baseUrl.replaceAll("/+$", "");
        if (!endpoint.endsWith("/v1")) {
            endpoint = endpoint + "/v1";
        }
        endpoint = endpoint + "/models";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Probe(Status.UP, endpoint, modelIds(response.body()),
                response.statusCode() >= 400
                    ? "The endpoint answered HTTP " + response.statusCode()
                        + " — reachable, but it is not serving a model list."
                    : null);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Probe(Status.DOWN, endpoint, null,
                e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (RuntimeException e) {
            // A malformed URL, a security manager, anything unforeseen: UNKNOWN, never DOWN. This is
            // the fail-open path, and it exists so a defect here cannot stop every build in the product.
            return Probe.unknown("Could not probe " + endpoint + ": " + e);
        }
    }

    /** The {@code id} values out of an OpenAI-style {@code /v1/models} body, best effort. */
    private static String modelIds(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        StringBuilder ids = new StringBuilder();
        int at = 0;
        while (true) {
            int key = body.indexOf("\"id\"", at);
            if (key < 0) {
                break;
            }
            int open = body.indexOf('"', body.indexOf(':', key) + 1);
            int close = open < 0 ? -1 : body.indexOf('"', open + 1);
            if (open < 0 || close < 0) {
                break;
            }
            if (ids.length() > 0) {
                ids.append(", ");
            }
            ids.append(body, open + 1, close);
            at = close + 1;
        }
        return ids.length() == 0 ? null : ids.toString();
    }
}
