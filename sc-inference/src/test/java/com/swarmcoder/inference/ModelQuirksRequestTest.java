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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually goes on the wire, per model.
 *
 * <p>Every assertion here used to be a constant. {@code enable_thinking} is a Qwen chat-template
 * argument that was sent to whatever model happened to be configured; the 32k output cap was a
 * JVM-wide system property shared by every endpoint in the process; and the JSON response format
 * was assumed honoured. A model that needs different answers to any of those is exactly the case
 * these tests pin.
 */
class ModelQuirksRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theOutputCapComesFromTheModelsOwnProfile() throws Exception {
        try (Recorder recorder = new Recorder()) {
            ModelQuirks quirks = quirks(4096, "enable_thinking", null, true);
            drain(new VllmClient(recorder.baseUrl(), null, "m", quirks));

            assertThat(recorder.body().at("/max_tokens").asInt()).isEqualTo(4096);
        }
    }

    @Test
    void twoModelsInOneProcessGetTheirOwnOutputCaps() throws Exception {
        // The point of the whole change: this could not be expressed before, because the cap was
        // -Dswarmcoder.llm.maxTokens for the entire JVM.
        try (Recorder small = new Recorder(); Recorder large = new Recorder()) {
            drain(new VllmClient(small.baseUrl(), null, "small", quirks(1024, null, null, true)));
            drain(new VllmClient(large.baseUrl(), null, "large", quirks(60000, null, null, true)));

            assertThat(small.body().at("/max_tokens").asInt()).isEqualTo(1024);
            assertThat(large.body().at("/max_tokens").asInt()).isEqualTo(60000);
        }
    }

    @Test
    void theReasoningSwitchUsesThisModelsOwnArgumentName() throws Exception {
        try (Recorder recorder = new Recorder()) {
            drain(new VllmClient(recorder.baseUrl(), null, "m",
                quirks(4096, "reasoning_enabled", null, true)));

            JsonNode kwargs = recorder.body().at("/chat_template_kwargs");
            assertThat(kwargs.has("reasoning_enabled")).isTrue();
            assertThat(kwargs.get("reasoning_enabled").asBoolean()).isFalse();
            assertThat(kwargs.has("enable_thinking")).isFalse();
        }
    }

    @Test
    void aModelWithNoReasoningSwitchIsSentNothingAtAll() throws Exception {
        // Sending a chat-template argument a template does not know is noise at best and a
        // template error at worst. This is the vision endpoint's case, which used to be an `if`
        // living inside the shared client.
        try (Recorder recorder = new Recorder()) {
            drain(new VllmClient(recorder.baseUrl(), null, "m", quirks(4096, null, null, true)));

            assertThat(recorder.body().has("chat_template_kwargs")).isFalse();
        }
    }

    @Test
    void reasoningLeftOnSendsNothing_theModelsOwnDefaultStands() throws Exception {
        try (Recorder recorder = new Recorder()) {
            drain(new VllmClient(recorder.baseUrl(), null, "m",
                quirks(4096, "enable_thinking", null, true).withThinking(true)));

            assertThat(recorder.body().has("chat_template_kwargs")).isFalse();
        }
    }

    @Test
    void aModelThatHonoursJsonGetsResponseFormat_oneThatDoesNotGetGuidedJson() throws Exception {
        try (Recorder honours = new Recorder(); Recorder doesNot = new Recorder()) {
            drainWithSchema(new VllmClient(honours.baseUrl(), null, "m", quirks(4096, null, null, true)));
            drainWithSchema(new VllmClient(doesNot.baseUrl(), null, "m", quirks(4096, null, null, false)));

            assertThat(honours.body().at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(honours.body().has("extra_body")).isFalse();
            assertThat(doesNot.body().has("response_format")).isFalse();
            assertThat(doesNot.body().at("/extra_body/guided_json").isMissingNode()).isFalse();
        }
    }

    @Test
    void theVisionCallAlsoUsesTheProfilesCapAndReasoningSwitch() throws Exception {
        try (Recorder recorder = new Recorder(false)) {
            new VllmClient(recorder.baseUrl(), null, "m", quirks(2048, "enable_thinking", null, true))
                .describeImage("what is this", "data:image/png;base64,AA==", 0.1);

            assertThat(recorder.body().at("/max_tokens").asInt()).isEqualTo(2048);
            assertThat(recorder.body().at("/chat_template_kwargs/enable_thinking").asBoolean()).isFalse();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static ModelQuirks quirks(int maxOutputTokens, String thinkingKwarg,
                                      String noThinkDirective, boolean jsonResponseFormat) {
        return new ModelQuirks("test", maxOutputTokens, false, thinkingKwarg, noThinkDirective,
            true, jsonResponseFormat, false, 65536, 32768, 1024, 16, false);
    }

    private static void drain(VllmClient client) throws Exception {
        client.chatCompletionStream(List.of(Map.of("role", "user", "content", "hi")), null, 0.2)
            .collect(Collectors.joining());
    }

    private static void drainWithSchema(VllmClient client) throws Exception {
        client.chatCompletionStream(List.of(Map.of("role", "user", "content", "hi")),
            Verdict.class, 0.2).collect(Collectors.joining());
    }

    /** A schema target, so the JSON-format branch is exercised. */
    public static class Verdict {
        public double score;
    }

    /** Answers every request with one empty streamed chunk and keeps the request body. */
    private static final class Recorder implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> captured = new AtomicReference<>();

        Recorder() throws IOException {
            this(true);
        }

        Recorder(boolean streaming) throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = (streaming
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        JsonNode body() throws IOException {
            return MAPPER.readTree(captured.get());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
