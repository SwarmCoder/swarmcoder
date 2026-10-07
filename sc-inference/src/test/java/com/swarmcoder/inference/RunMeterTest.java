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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A metered run keeps every model call with the role that made it and the token counts the server
 * itself reported; a server that reports none leaves "not recorded" (-1), never a guess.
 */
class RunMeterTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile String reply;

    @BeforeEach
    void start() throws Exception {
        RunMeter.enable();
        RunMeter.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        RunMeter.disable();
        RunMeter.reset();
    }

    private VllmClient client() {
        return new VllmClient("http://127.0.0.1:" + server.getAddress().getPort(), "", "fake", true);
    }

    @Test
    void aStreamedCallIsRecordedWithItsRoleAndTheServersOwnUsage() throws Exception {
        reply = "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":7}}\n\n"
            + "data: [DONE]\n\n";

        String answer = client().as("judge", "task-1", "3")
            .chatCompletionStream(List.of(Map.of("role", "user", "content", "hi")), null, 0.0)
            .collect(Collectors.joining());

        assertThat(answer).isEqualTo("hello");
        assertThat(lastRequest.get()).as("the usage chunk is asked for while metering")
            .contains("\"include_usage\":true");
        assertThat(RunMeter.calls()).singleElement().satisfies(call -> {
            assertThat(call.role()).isEqualTo("judge");
            assertThat(call.task()).isEqualTo("task-1");
            assertThat(call.worker()).isEqualTo("3");
            assertThat(call.promptTokens()).isEqualTo(120);
            assertThat(call.completionTokens()).isEqualTo(7);
            assertThat(call.finished()).isTrue();
        });
        assertThat(RunMeter.currentTag()).as("the tag does not leak past the call")
            .isEqualTo(RunMeter.Tag.UNTAGGED);
    }

    @Test
    void aServerThatReportsNoUsageLeavesTheCountsNotRecorded() throws Exception {
        reply = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n";

        client().chatCompletionStream(List.of(Map.of("role", "user", "content", "hi")), null, 0.0)
            .collect(Collectors.joining());

        assertThat(RunMeter.calls()).singleElement().satisfies(call -> {
            assertThat(call.role()).isEqualTo("untagged");
            assertThat(call.usageRecorded()).isFalse();
            assertThat(call.promptTokens()).isEqualTo(-1);
            assertThat(call.finished()).isTrue();
        });
    }

    @Test
    void spansAreKeptInOrder() {
        long start = System.currentTimeMillis() - 50;
        RunMeter.span("wave 1 of 2", start);
        assertThat(RunMeter.timed("integration after wave 1 of 2", () -> "x")).isEqualTo("x");

        assertThat(RunMeter.spans()).extracting(RunMeter.Span::name)
            .containsExactly("wave 1 of 2", "integration after wave 1 of 2");
        assertThat(RunMeter.spans().get(0).millis()).isGreaterThanOrEqualTo(50);
    }
}
