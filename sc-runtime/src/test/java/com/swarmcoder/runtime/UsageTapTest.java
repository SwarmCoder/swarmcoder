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
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.reflect.KClass;
import kotlinx.coroutines.flow.Flow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 80, 2026-10-04: one story was sent 2.7 million prompt tokens on a paid server and the
 * run's record could not say how many of them the server had taken from its prompt cache - the
 * agent framework reads two numbers of a response's usage and drops the rest.
 */
class UsageTapTest {

    /** Answers each post with the next scripted body; at once, or later through the continuation. */
    private static final class Scripted implements KoogHttpClient {
        final List<String> answers = new ArrayList<>();
        boolean later;

        @Override
        public String getClientName() {
            return "scripted";
        }

        @Override
        public <R> Object get(String path, KClass<R> type, Map<String, String> parameters,
                              Map<String, String> headers, Continuation<? super R> continuation) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, R> Object post(String path, T request, KClass<T> requestType,
                                  KClass<R> responseType, Map<String, String> parameters,
                                  Map<String, String> headers,
                                  Continuation<? super R> continuation) {
            String answer = answers.remove(0);
            if (later) {
                ((Continuation<Object>) continuation).resumeWith(answer);
                return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
            }
            return answer;
        }

        @Override
        public <T, R, O> Flow<O> sse(String path, T request, KClass<T> requestType,
                                     Function1<? super String, Boolean> dataFilter,
                                     Function1<? super String, ? extends R> decode,
                                     Function1<? super R, ? extends O> process,
                                     Map<String, String> parameters, Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Flow<String> lines(String path, T request, KClass<T> requestType,
                                      Map<String, String> parameters,
                                      Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static final class Caught implements Continuation<String> {
        Object resumedWith;

        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object result) {
            resumedWith = result;
        }
    }

    private static KoogHttpClient tapped(UsageTap tap) {
        return tap.create("c", "http://model.test", Map.of(), Map.of(), 1, 1, 1, null);
    }

    @Test
    void whatTheServerTookFromItsCacheIsReadFromTheResponseTheFrameworkDrops() {
        Scripted server = new Scripted();
        UsageTap tap = new UsageTap((n, u, h, p, a, b, c, j) -> server);
        KoogHttpClient client = tapped(tap);
        String first = "{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\","
            + "\"content\":\"the task\"}]}";
        String second = first.substring(0, first.length() - 2)
            + ",{\"role\":\"tool\",\"content\":\"a file\"}]}";

        server.answers.add("{\"choices\":[],\"usage\":{\"prompt_tokens\":900,"
            + "\"completion_tokens\":10,\"prompt_cache_hit_tokens\":0,"
            + "\"prompt_cache_miss_tokens\":900}}");
        Object answered = client.post("v1/chat/completions", first, null, null, Map.of(),
            Map.of(), new Caught());

        assertThat(answered).as("the response is handed on untouched").isInstanceOf(String.class);
        assertThat(tap.last().cachedPromptTokens()).isZero();
        assertThat(tap.last().requestChars()).isEqualTo(first.length());
        assertThat(tap.last().samePrefixChars()).as("a first call repeats nothing").isEqualTo(-1);
        assertThat(tap.serverCaches()).as("no hit reported yet").isFalse();

        server.answers.add("{\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":10,"
            + "\"prompt_cache_hit_tokens\":832,\"prompt_cache_miss_tokens\":368}}");
        client.post("v1/chat/completions", second, null, null, Map.of(), Map.of(), new Caught());

        assertThat(tap.last().cachedPromptTokens()).isEqualTo(832);
        assertThat(tap.last().samePrefixChars())
            .as("everything up to the end of the earlier messages is the same text again")
            .isEqualTo(first.length() - 2);
        assertThat(tap.serverCaches()).isTrue();
    }

    @Test
    void anAnswerThatArrivesLaterIsReadTooAndTheCallerIsStillResumedWithIt() {
        Scripted server = new Scripted();
        server.later = true;
        UsageTap tap = new UsageTap((n, u, h, p, a, b, c, j) -> server);
        String body = "{\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":1,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":40}}}";
        server.answers.add(body);
        Caught caller = new Caught();

        tapped(tap).post("v1/chat/completions", "{}", null, null, Map.of(), Map.of(), caller);

        assertThat(caller.resumedWith).isEqualTo(body);
        assertThat(tap.last().cachedPromptTokens())
            .as("the OpenAI and vLLM spelling of the same count").isEqualTo(40);
    }

    @Test
    void aServerThatSaysNothingOfItsCacheIsRecordedAsNotHavingSaid() {
        Scripted server = new Scripted();
        UsageTap tap = new UsageTap((n, u, h, p, a, b, c, j) -> server);
        server.answers.add("{\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":1}}");

        tapped(tap).post("v1/chat/completions", "{}", null, null, Map.of(), Map.of(), new Caught());

        assertThat(tap.last().cachedPromptTokens()).isEqualTo(-1);
        assertThat(tap.serverCaches()).isFalse();
    }

    @Test
    void aRewrittenHistoryShowsAsAShortSharedHead() {
        assertThat(UsageTap.samePrefix("abc-OLD-RESULT-xyz", "abc-[cut]-xyz")).isEqualTo(4);
        assertThat(UsageTap.samePrefix("abc", "abcdef")).isEqualTo(3);
    }
}
