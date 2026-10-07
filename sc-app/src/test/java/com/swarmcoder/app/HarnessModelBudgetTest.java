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
package com.swarmcoder.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cheap, model-free proof of {@link HarnessModelBudget} — the seam {@link EndToEndLoopTest} uses
 * so its workers get the same working-context budget {@code DependencyGraph} discovers for the
 * product, instead of the {@code ModelQuirks.DEFAULTS} 32768-token fallback that starved harness
 * run 9's persistence-task workers into compacting at turn 15 and 17.
 *
 * <p>No model, no GPU: a plain {@code com.sun.net.httpserver.HttpServer} stands in for the SGLang
 * endpoint, answering exactly the two pages {@code ServerCapabilities} reads — same shape as
 * {@code ServerCapabilitiesTest} in sc-inference, whose bodies these are cut down from.
 */
class HarnessModelBudgetTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesTheSameWorkingContextServerCapabilitiesWouldDerive() throws Exception {
        // 462103 shared tokens / 8 running requests = 57762 each; 90% of that is 51985, floored to
        // a whole 1024 = 51200 — the same arithmetic ServerCapabilitiesTest proves in isolation.
        startFakeServer(8, 462_103, 262_144);

        HarnessModelBudget.Discovery discovery = HarnessModelBudget.discover(
            HttpClient.newHttpClient(), baseUrl() + "/v1", "qwen3.8-27b");

        assertThat(discovery.quirks().servedContextTokens()).isEqualTo(262_144);
        assertThat(discovery.quirks().maxConcurrentSequences()).isEqualTo(8);
        assertThat(discovery.quirks().workingContextTokens())
            .as("the pool divided by the server's own reported concurrency, with headroom — not "
                + "ModelQuirks.DEFAULTS' hardcoded 32768")
            .isEqualTo(51_200);
    }

    @Test
    void aSmallerPoolOrFewerRunningRequestsChangesTheBudget() throws Exception {
        // Half the pool, half the concurrency: the fair share (and so the working context) is
        // unchanged by concurrency alone, but a smaller pool alone shrinks it proportionally.
        startFakeServer(8, 231_051, 262_144);

        HarnessModelBudget.Discovery discovery = HarnessModelBudget.discover(
            HttpClient.newHttpClient(), baseUrl(), "qwen3.8-27b");

        assertThat(discovery.quirks().workingContextTokens())
            .as("231051 / 8 = 28881, 90% of that is 25992, floored to a whole 1024")
            .isEqualTo(25_600);
    }

    @Test
    void theLedgerLineNamesServedContextWorkingContextConcurrencyAndTheCompactionTrigger()
            throws Exception {
        startFakeServer(8, 462_103, 262_144);

        HarnessModelBudget.Discovery discovery = HarnessModelBudget.discover(
            HttpClient.newHttpClient(), baseUrl() + "/v1", "qwen3.8-27b");

        String line = discovery.describe();
        assertThat(line)
            .as("a future reader must see every number without opening a log")
            .contains("262144")   // served context
            .contains("51200")   // working context per worker
            .contains("8 request(s) at once")   // the server's own concurrency
            .contains("38400");  // 75% of 51200 — HistoryTrim's high-water compaction trigger
    }

    @Test
    void discoveryFailsLoudlyRatherThanSilentlyRunningTheStarvedDefault() {
        // No server started: the endpoint answers nothing at all.
        assertThatThrownBy(() -> HarnessModelBudget.discover(
            HttpClient.newHttpClient(), "http://127.0.0.1:1/v1", "anything"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("127.0.0.1:1")
            .hasMessageContaining("32768")
            .hasMessageContaining("must not silently fall back");
    }

    // --- fixture ------------------------------------------------------------------------------

    private void startFakeServer(int maxRunningRequests, long maxTotalNumTokens, int maxModelLen)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/v1/models", 200, """
            {"object":"list","data":[{"id":"qwen3.8-27b","object":"model","owned_by":"sglang",
             "max_model_len":%d}]}""".formatted(maxModelLen));
        route("/get_server_info", 200, """
            {"max_running_requests":%d,"max_total_num_tokens":%d}"""
            .formatted(maxRunningRequests, maxTotalNumTokens));
        server.start();
    }

    private void route(String path, int status, String body) {
        server.createContext(path, exchange -> {
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
