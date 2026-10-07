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
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking a model server what it can do, against a real local HTTP server — the interesting failures
 * are about sockets and missing pages, and a mock would answer them away.
 *
 * <p>The bodies below are cut down from what the 2026-08 box actually returned on 2026-09-01, keys
 * and values unchanged, so a server-side rename fails here rather than in production.
 */
class ServerCapabilitiesTest {

    /** Exactly what {@code GET /v1/models} answered on the box, trimmed to the fields read. */
    private static final String SGLANG_MODELS = """
        {"object":"list","data":[{"id":"qwen3.8-27b","object":"model","owned_by":"sglang",
         "root":"qwen3.8-27b","parent":null,"max_model_len":262144}]}""";

    /** Cut from the real {@code GET /get_server_info}, which returns about 45KB of settings. */
    private static final String SGLANG_SERVER_INFO = """
        {"model_path":"RadixArk/Qwen3.8-27B-NVFP4","mem_fraction_static":0.62,
         "max_running_requests":8,"max_total_tokens":null,"chunked_prefill_size":8192,
         "max_prefill_tokens":16384,"context_length":null,"page_size":1,
         "max_total_num_tokens":462103,"max_req_input_len":262138}""";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- what the box says --------------------------------------------------------------------

    @Test
    void readsBothPagesOffAnSglangServer() throws Exception {
        startSglang();

        ServerCapabilities caps = ServerCapabilities.discover(
            java.net.http.HttpClient.newHttpClient(), baseUrl() + "/v1", "qwen3.8-27b");

        assertThat(caps.servedContextTokens()).isEqualTo(262144);
        assertThat(caps.maxConcurrentSequences()).isEqualTo(8);
        assertThat(caps.kvCachePoolTokens()).isEqualTo(462103);
        assertThat(caps.discoveredAnything()).isTrue();
    }

    @Test
    void theNullsInServerInfoAreNotMistakenForFigures() throws Exception {
        // max_total_tokens and context_length both come back as JSON null on this build. Reading
        // either as zero-or-anything would poison the derivation with a value nobody set.
        startSglang();

        ServerCapabilities caps = ServerCapabilities.discover(
            java.net.http.HttpClient.newHttpClient(), baseUrl(), "qwen3.8-27b");

        assertThat(caps.kvCachePoolTokens())
            .describedAs("max_total_num_tokens is the real one; max_total_tokens is null")
            .isEqualTo(462103);
    }

    @Test
    void theNamedModelIsTheOneRead() throws Exception {
        start("/v1/models", 200, """
            {"data":[{"id":"small","max_model_len":8192},
                     {"id":"big","max_model_len":262144}]}""");

        var http = java.net.http.HttpClient.newHttpClient();
        assertThat(ServerCapabilities.discover(http, baseUrl(), "big").servedContextTokens())
            .isEqualTo(262144);
        assertThat(ServerCapabilities.discover(http, baseUrl(), "small").servedContextTokens())
            .isEqualTo(8192);
    }

    // --- endpoints that were never going to answer ---------------------------------------------

    @Test
    void aCloudApiWithNoServerInfoPageDegradesQuietly() throws Exception {
        // DeepSeek's shape: a model list with no length figure and no /get_server_info at all.
        // Nothing here is a fault, and nothing may be raised as one.
        start("/v1/models", 200, """
            {"object":"list","data":[{"id":"deepseek-chat","object":"model","owned_by":"deepseek"}]}""");

        ServerCapabilities caps = ServerCapabilities.discover(
            java.net.http.HttpClient.newHttpClient(), baseUrl() + "/v1", "deepseek-chat");

        assertThat(caps.discoveredAnything()).isFalse();
        assertThat(caps.servedContextTokens()).isZero();
        assertThat(caps.maxConcurrentSequences()).isZero();
        assertThat(caps.note()).contains("no server-info page");
    }

    @Test
    void aServerThatIsNotThereYieldsNothingAndDoesNotThrow() {
        ServerCapabilities caps = ServerCapabilities.discover(
            java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:1/v1", "anything");

        assertThat(caps.discoveredAnything()).isFalse();
        assertThat(caps.note()).contains("did not answer");
        assertThat(caps.endpoint()).contains("127.0.0.1:1");
    }

    @Test
    void anHtmlErrorPageIsNotAFigure() throws Exception {
        start("/v1/models", 200, "<html><body>Gateway timeout</body></html>");

        assertThat(ServerCapabilities.discover(
            java.net.http.HttpClient.newHttpClient(), baseUrl(), "x").discoveredAnything())
            .isFalse();
    }

    // --- the derivation -------------------------------------------------------------------------

    @Test
    void theWorkingBudgetIsAShareOfTheCacheNotAShareOfTheRequestCeiling() {
        ServerCapabilities caps = new ServerCapabilities(
            "http://box:8002", 262144, 8, 462103, "measured");

        // The simple division would be 262144 / 8 = 32768. That divides a per-request ceiling,
        // which is not a pool, and it happens to land on the old hardcoded figure — which is how
        // being wrong went unnoticed. The cache is what is shared: 462103 / 8 = 57762, 90% of that
        // is 51985, floored to a whole 1024.
        assertThat(caps.derivedWorkingContextTokens()).isEqualTo(51200);
        assertThat(caps.derivedWorkingContextTokens()).isNotEqualTo(32768);
    }

    @Test
    void theWorkingBudgetNeverExceedsWhatOneRequestMayBe() {
        // A big cache and a small request ceiling: a fair share larger than one legal request
        // cannot be occupied by one session, so the ceiling wins.
        ServerCapabilities caps = new ServerCapabilities(
            "http://box", 16384, 2, 4_000_000, "measured");

        assertThat(caps.derivedWorkingContextTokens()).isEqualTo(16384);
    }

    @Test
    void withNoCacheFigureThereIsNothingHonestToDivide() {
        ServerCapabilities caps = new ServerCapabilities("http://cloud", 262144, 0, 0, "partial");

        assertThat(caps.derivedWorkingContextTokens())
            .describedAs("a cloud API reports no cache size; guessing one would be inventing it")
            .isZero();
    }

    // --- merging into a profile -----------------------------------------------------------------

    @Test
    void discoveredFiguresBeatTheShapeAndTheRestIsLeftAlone() {
        ModelQuirks shape = ModelShapes.get("generic-openai");
        ServerCapabilities caps = new ServerCapabilities(
            "http://box:8002", 262144, 8, 462103, "measured");

        ModelQuirks merged = caps.applyTo(shape);

        assertThat(merged.servedContextTokens()).isEqualTo(262144);
        assertThat(merged.maxConcurrentSequences()).isEqualTo(8);
        assertThat(merged.workingContextTokens()).isEqualTo(51200);
        // Everything the server said nothing about survives untouched.
        assertThat(merged.kvBytesPerToken()).isEqualTo(shape.kvBytesPerToken());
        assertThat(merged.maxOutputTokens()).isEqualTo(shape.maxOutputTokens());
        assertThat(merged.thinkingKwarg()).isEqualTo(shape.thinkingKwarg());
        assertThat(merged.verified())
            .describedAs("memory per token is still unmeasured, so this is still not verified")
            .isFalse();
    }

    @Test
    void discoveringNothingChangesNothing() {
        ModelQuirks shape = ModelShapes.get("qwen36-27b");

        assertThat(ServerCapabilities.NOTHING.applyTo(shape)).isEqualTo(shape);
    }

    @Test
    void aPartialAnswerOnlyFillsInTheHalfItKnows() {
        ModelQuirks shape = ModelShapes.get("generic-openai");
        ServerCapabilities caps = new ServerCapabilities("http://cloud", 131072, 0, 0, "partial");

        ModelQuirks merged = caps.applyTo(shape);

        assertThat(merged.servedContextTokens()).isEqualTo(131072);
        assertThat(merged.maxConcurrentSequences()).isEqualTo(shape.maxConcurrentSequences());
        assertThat(merged.workingContextTokens()).isEqualTo(shape.workingContextTokens());
    }

    // --- plumbing ---------------------------------------------------------------------------------

    @Test
    void everySpellingOfAnEndpointResolvesToTheSameRoot() {
        assertThat(ServerCapabilities.root("http://box:8002/v1"))
            .isEqualTo(ServerCapabilities.root("http://box:8002/v1/"))
            .isEqualTo(ServerCapabilities.root("http://box:8002/"))
            .isEqualTo("http://box:8002");
    }

    @Test
    void eachEndpointIsAskedOnceHoweverManyFamiliesShareIt() throws Exception {
        AtomicInteger hits = startSglang();
        ServerCapabilities.Cache cache = new ServerCapabilities.Cache();

        for (int i = 0; i < 5; i++) {
            assertThat(cache.forEndpoint(baseUrl() + "/v1", "qwen3.8-27b").servedContextTokens())
                .isEqualTo(262144);
        }

        assertThat(hits.get())
            .describedAs("eight worker families on one box must cost one pair of requests")
            .isEqualTo(2);
    }

    // --- fixture ------------------------------------------------------------------------------

    private AtomicInteger startSglang() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route(hits, "/v1/models", 200, SGLANG_MODELS);
        route(hits, "/get_server_info", 200, SGLANG_SERVER_INFO);
        server.start();
        return hits;
    }

    private void start(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route(new AtomicInteger(), path, status, body);
        server.start();
    }

    private void route(AtomicInteger hits, String path, int status, String body) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
