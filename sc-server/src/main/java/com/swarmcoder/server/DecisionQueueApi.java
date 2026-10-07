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
package com.swarmcoder.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.store.ArtifactStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecisionQueueApi {

    private static final Logger log =
        LoggerFactory.getLogger(DecisionQueueApi.class);

    private final ArtifactStore store;
    private HttpServer server;

    public DecisionQueueApi(ArtifactStore store) {
        this.store = store;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/queue", new QueueHandler(store));
        server.setExecutor(null); // creates a default executor
        server.start();
        log.info("DecisionQueue API listening on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static class QueueHandler implements HttpHandler {
        private final ArtifactStore store;
        private final ObjectMapper mapper;

        public QueueHandler(ArtifactStore store) {
            this.store = store;
            this.mapper = new ObjectMapper();
            this.mapper.findAndRegisterModules();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                List<Run> approvalRuns = store.root().runs.values().stream()
                        .filter(r -> r.state() == RunState.APPROVAL)
                        .collect(Collectors.toList());
                
                String response = mapper.writeValueAsString(approvalRuns);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } else {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            }
        }
    }
}
