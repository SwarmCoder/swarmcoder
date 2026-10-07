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
package com.swarmcoder.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

/**
 * An in-process OpenAI-compatible chat endpoint that answers a scripted sequence — the fake every
 * expert test runs against, so that the REAL stack runs: the real {@code KoogAgentRuntime}, the
 * real tool-call parsing, the real {@link ExpertTools} executing on real files, the real cloud
 * gate. Nothing here reaches a paid model, and no test in this module ever may.
 *
 * <p>The script is by TURN — reply 1, reply 2, reply 3 — because that is what an expert session is:
 * an ordered investigation, and the thing under test is what happens after each step. Routing on
 * the conversation text (as {@code FakeVllm} does for the swarm's concurrent workers) would be
 * indirection with nothing to buy here, since one expert session is one caller in order.
 *
 * <p>Both response shapes are served: plain JSON for the agent runtime, and SSE for anything that
 * streams, since the same endpoint stands in for both in a couple of tests.
 */
final class ScriptedExpertEndpoint implements AutoCloseable {

    /** One scripted assistant turn: a tool call, or plain text. */
    record Reply(String text, String toolName, String toolArgsJson, List<String> moreArgsJson) {

        static Reply text(String text) {
            return new Reply(text, null, null, List.of());
        }

        static Reply toolCall(String toolName, String argsJson) {
            return new Reply(null, toolName, argsJson, List.of());
        }

        /** One turn that calls the same tool several times at once, once per argument set. */
        static Reply toolCalls(String toolName, String firstArgsJson, String... moreArgsJson) {
            return new Reply(null, toolName, firstArgsJson, List.of(moreArgsJson));
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final IntFunction<Reply> script;
    /** Every request body, in order — what actually went on the wire. */
    final List<String> requests = new ArrayList<>();

    /** @param script turn number (1-based) to the reply for it */
    ScriptedExpertEndpoint(IntFunction<Reply> script) throws IOException {
        this.script = script;
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                int turn;
                synchronized (requests) {
                    requests.add(new String(raw, StandardCharsets.UTF_8));
                    turn = requests.size();
                }
                JsonNode request = MAPPER.readTree(raw);
                Reply reply = script.apply(turn);
                boolean stream = request.path("stream").asBoolean(false);
                byte[] body = stream ? sse(reply) : completion(reply);
                exchange.getResponseHeaders().set("Content-Type",
                    stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (Exception e) {
                byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(err);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    int turnsServed() {
        synchronized (requests) {
            return requests.size();
        }
    }

    /** Everything that went to the endpoint, concatenated — for "did the question reach it?". */
    String everythingSent() {
        synchronized (requests) {
            return String.join("\n", requests);
        }
    }

    private static byte[] completion(Reply reply) throws IOException {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        if (reply.toolName() != null) {
            message.putNull("content");
            ArrayNode toolCalls = message.putArray("tool_calls");
            ObjectNode call = toolCalls.addObject();
            call.put("id", "call_" + Math.abs(reply.toolArgsJson().hashCode()));
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", reply.toolName());
            function.put("arguments", reply.toolArgsJson());
            for (String more : reply.moreArgsJson()) {
                ObjectNode another = toolCalls.addObject();
                another.put("id", "call_" + Math.abs(more.hashCode()));
                another.put("type", "function");
                ObjectNode anotherFunction = another.putObject("function");
                anotherFunction.put("name", reply.toolName());
                anotherFunction.put("arguments", more);
            }
        } else {
            message.put("content", reply.text() == null ? "" : reply.text());
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-expert-fake");
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", "expert-fake");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", reply.toolName() != null ? "tool_calls" : "stop");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 20);
        usage.put("total_tokens", 120);
        return MAPPER.writeValueAsBytes(root);
    }

    private static byte[] sse(Reply reply) throws IOException {
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("id", "chatcmpl-expert-fake");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", "expert-fake");
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("content", reply.text() == null ? "" : reply.text());
        choice.putNull("finish_reason");
        return ("data: " + MAPPER.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
