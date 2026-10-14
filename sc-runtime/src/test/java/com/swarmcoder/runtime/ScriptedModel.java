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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.swarmcoder.inference.ModelQuirks;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * A model server on this machine that answers each request of a session with the next step of a
 * script: a call of one tool, or plain text. It keeps every request it was sent, so a test can
 * read what the real agent runtime put in each one. No model is involved.
 */
final class ScriptedModel implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One step: a tool call when {@code tool} is set, plain text otherwise. */
    record Step(String tool, String arguments, String text) {
        static Step call(String tool, String arguments) {
            return new Step(tool, arguments, null);
        }

        static Step says(String text) {
            return new Step(null, null, text);
        }
    }

    final HttpServer http;
    final List<JsonNode> requests = new ArrayList<>();
    /** The requests answered with an error because they named the one tool to call. */
    final List<JsonNode> refused = new ArrayList<>();
    /** True for a server that answers a request naming the one tool to call with an error. */
    volatile boolean refusesANamedTool;

    /** Answers with {@code steps} in order, and with the last of them for ever after. */
    ScriptedModel(List<Step> steps) throws Exception {
        http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        http.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            if (refusesANamedTool && request.path("tool_choice").isObject()) {
                synchronized (requests) {
                    refused.add(request);
                }
                byte[] error = "{\"error\":{\"message\":\"tool_choice is not supported\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            int turn;
            synchronized (requests) {
                requests.add(request);
                turn = requests.size();
            }
            Step step = steps.get(Math.min(turn, steps.size()) - 1);
            ObjectNode root = JSON.createObjectNode();
            root.put("id", "chatcmpl-fake").put("object", "chat.completion")
                .put("created", 1).put("model", "fake");
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");
            if (step.tool() != null) {
                message.putNull("content");
                ObjectNode call = message.putArray("tool_calls").addObject();
                call.put("id", "call_" + turn).put("type", "function");
                call.putObject("function").put("name", step.tool())
                    .put("arguments", step.arguments());
                choice.put("finish_reason", "tool_calls");
            } else {
                message.put("content", step.text());
                choice.put("finish_reason", "stop");
            }
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", 100);
            usage.put("completion_tokens", 20);
            usage.put("total_tokens", 120);
            byte[] body = JSON.writeValueAsBytes(root);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
    }

    /** The messages of request {@code turn} (1-based). */
    JsonNode messagesOf(int turn) {
        return requests.get(turn - 1).path("messages");
    }

    /** The role of the last message of request {@code turn} (1-based), and its text. */
    String lastOf(int turn) {
        JsonNode messages = messagesOf(turn);
        JsonNode last = messages.get(messages.size() - 1);
        return last.path("role").asText() + ":" + last.path("content").asText();
    }

    /** A session of the real runtime against this server, with {@code tools}. */
    AgentRuntime.AgentSession open(List<AgentRuntime.ToolBinding> tools) {
        return open(tools, null);
    }

    /** The same, run with {@code options}; null is a session run as every session was. */
    AgentRuntime.AgentSession open(List<AgentRuntime.ToolBinding> tools,
                                   AgentRuntime.SessionOptions options) {
        // Tool calls in the history natively; a room nothing here comes near, so no compaction.
        ModelQuirks quirks = new ModelQuirks("scripted", 4_096, true, null, null, false, true,
            false, 1_000_000, 900_000, 1_024, 4, false);
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("worker",
            "You do the work.", new AgentRuntime.ModelEndpoint(
                "http://localhost:" + http.getAddress().getPort(), "", "fake", 1_000_000, quirks),
            0.2, 40, tools, info -> Optional.empty());
        return new KoogAgentRuntime().open(options == null ? spec : spec.with(options));
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
