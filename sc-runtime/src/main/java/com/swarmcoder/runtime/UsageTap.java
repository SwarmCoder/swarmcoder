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

import ai.koog.http.client.KoogHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.inference.VllmClient;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.reflect.KClass;
import kotlinx.coroutines.flow.Flow;
import kotlinx.serialization.json.Json;

import java.util.Map;

/**
 * Reads, at the HTTP client, the two things about a session's model call that the agent framework
 * does not hand on (2026-10-04, live run 80: one story cost 2.7 million prompt tokens on a paid
 * server and nothing recorded how many of them were billed at the cached price).
 *
 * <ul>
 *   <li><b>What the server took from its prompt prefix cache.</b> The framework's OpenAI client
 *       keeps {@code prompt_tokens} and {@code completion_tokens} of a response's {@code usage}
 *       and drops the rest (its {@code createMetaInfo} is final). The response text passes
 *       through here first, so the cached count is read from it; see
 *       {@link VllmClient#cachedPromptTokens}.</li>
 *   <li><b>How much of the request repeated the one before.</b> A prefix cache can only reuse a
 *       prompt whose head is byte-for-byte the previous one's. The request body passes through
 *       here too, so the length of the head it shares with this session's previous request is
 *       measured on our side, whatever the server reports. A session whose history was rewritten
 *       (a tidy, a compaction) shows it as a short shared head on the next call.</li>
 * </ul>
 *
 * <p>One tap per session: a session makes one call at a time, and reads {@link #last()} after
 * each. Nothing sent or received is changed, and a body this cannot read is passed on untouched.
 */
final class UsageTap implements KoogHttpClient.Factory {

    /**
     * What the latest call showed.
     *
     * @param cachedPromptTokens the server's own count, or -1 when it gave none
     * @param requestChars       the request body's length, or -1 when it was not text
     * @param samePrefixChars    how much of its head the session's previous request shared; -1
     *                           on the session's first call
     */
    record Seen(int cachedPromptTokens, int requestChars, int samePrefixChars) {
        static final Seen NOTHING = new Seen(-1, -1, -1);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KoogHttpClient.Factory inner;
    private volatile String previousRequest;
    private volatile Seen last = Seen.NOTHING;
    /** Set once the server has reported a cache hit in this session. */
    private volatile boolean serverCaches;

    UsageTap(KoogHttpClient.Factory inner) {
        this.inner = inner;
    }

    /** What the latest call showed; {@link Seen#NOTHING} before the first. */
    Seen last() {
        return last;
    }

    /** True once a response of this session reported prompt tokens taken from the cache. */
    boolean serverCaches() {
        return serverCaches;
    }

    @Override
    public KoogHttpClient create(String clientName, String baseUrl, Map<String, String> headers,
                                 Map<String, String> parameters, long requestTimeout,
                                 long connectTimeout, long socketTimeout, Json json) {
        return new Tapped(inner.create(clientName, baseUrl, headers, parameters, requestTimeout,
            connectTimeout, socketTimeout, json));
    }

    void sending(Object body) {
        if (!(body instanceof String text)) {
            last = Seen.NOTHING;
            return;
        }
        String before = previousRequest;
        previousRequest = text;
        last = new Seen(-1, text.length(), before == null ? -1 : samePrefix(before, text));
    }

    void received(Object response) {
        if (!(response instanceof String text)) {
            return;
        }
        try {
            JsonNode usage = JSON.readTree(text).get("usage");
            int cached = VllmClient.cachedPromptTokens(usage);
            if (cached > 0) {
                serverCaches = true;
            }
            Seen sent = last;
            last = new Seen(cached, sent.requestChars(), sent.samePrefixChars());
        } catch (Exception notJson) {                                      // noqa
            // Not ours to judge: the framework's own client reports a body it cannot read.
        }
    }

    /** How many leading characters two texts share. */
    static int samePrefix(String a, String b) {
        int end = Math.min(a.length(), b.length());
        int i = 0;
        while (i < end && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** The real client, with every request and every plain response shown to the tap. */
    private final class Tapped implements KoogHttpClient {

        private final KoogHttpClient real;

        Tapped(KoogHttpClient real) {
            this.real = real;
        }

        @Override
        public String getClientName() {
            return real.getClientName();
        }

        @Override
        public <R> Object get(String path, KClass<R> responseType, Map<String, String> parameters,
                              Map<String, String> headers, Continuation<? super R> continuation) {
            return real.get(path, responseType, parameters, headers, continuation);
        }

        @Override
        public <T, R> Object post(String path, T request, KClass<T> requestType,
                                  KClass<R> responseType, Map<String, String> parameters,
                                  Map<String, String> headers,
                                  Continuation<? super R> continuation) {
            sending(request);
            // A suspend function seen from Java: it either returns its result at once or returns
            // the "suspended" marker and resumes the continuation later. Both ways are watched.
            Object now = real.post(path, request, requestType, responseType, parameters, headers,
                new Continuation<R>() {
                    @Override
                    public CoroutineContext getContext() {
                        return continuation.getContext();
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void resumeWith(Object result) {
                        received(result);
                        ((Continuation<Object>) continuation).resumeWith(result);
                    }
                });
            received(now);
            return now;
        }

        @Override
        public <T, R, O> Flow<O> sse(String path, T request, KClass<T> requestType,
                                     Function1<? super String, Boolean> dataFilter,
                                     Function1<? super String, ? extends R> decode,
                                     Function1<? super R, ? extends O> process,
                                     Map<String, String> parameters, Map<String, String> headers) {
            return real.sse(path, request, requestType, dataFilter, decode, process, parameters,
                headers);
        }

        @Override
        public <T> Flow<String> lines(String path, T request, KClass<T> requestType,
                                      Map<String, String> parameters,
                                      Map<String, String> headers) {
            return real.lines(path, request, requestType, parameters, headers);
        }

        @Override
        public void close() throws Exception {
            real.close();
        }
    }
}
