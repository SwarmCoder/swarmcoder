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
package com.swarmcoder.console;

import com.zeroz4j.server.Zeroz4jServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The O0 go/no-go (docs/OBSERVABILITY_DESIGN.md §6): the zeroz4j server backend must run
 * embedded in a plain JVM — servlet container, WebSocket upgrade, CDI service discovery —
 * because EclipseStore forces the Console into the orchestrator process.
 */
class ConsoleServerSpikeTest {

    @Test
    void embeddedConsoleAcceptsWebSocketRmiConnections() throws Exception {
        // Port 0, not 8080: a fixed port makes the build fail whenever anything else on the
        // machine happens to be listening there, which says nothing about the Console.
        try (Zeroz4jServer server = Zeroz4jServer.start(0, "Test Console")) {
            CompletableFuture<WebSocket> connected = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/wasm-rmi"),
                    new WebSocket.Listener() { });

            WebSocket webSocket = connected.get(30, TimeUnit.SECONDS);
            assertThat(webSocket).isNotNull();
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "spike done").get(10, TimeUnit.SECONDS);

            // The browser client is served from the same process (O1): shell + compiled app.
            HttpClient http = HttpClient.newHttpClient();
            var index = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(index.statusCode()).isEqualTo(200);
            assertThat(index.body()).contains("SwarmCoder Console").contains("js/classes.js");

            var js = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/js/classes.js")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(js.statusCode()).isEqualTo(200);
            assertThat(js.body()).contains("function");
        }
    }
}
