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
package com.swarmcoder.swarm;

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
import java.util.function.Function;
import java.util.concurrent.Executors;

/**
 * The spec §20 FakeVllm: an in-process OpenAI-compatible chat-completions server with
 * scripted responses, so swarm-engine tests run the REAL stack — Koog HTTP client, tool-call
 * parsing, worker loop, kill paths — deterministically and offline. Responses are chosen by
 * a routing function over the full request conversation, which keeps concurrent workers
 * deterministic without shared ordering state. Supports both plain JSON and SSE streaming
 * (the judge/planner client streams; Koog does not).
 */
public final class FakeVllm implements AutoCloseable {

    /** A scripted assistant reply: either a tool call or plain text. */
    public record Reply(String text, String toolName, String toolArgsJson) {
        public static Reply text(String text) {
            return new Reply(text, null, null);
        }

        public static Reply toolCall(String toolName, String argsJson) {
            return new Reply(null, toolName, argsJson);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Function<String, Reply> router;
    public final List<String> requests = new ArrayList<>();
    /**
     * Bytes of each request body, in order — the real size of what went on the wire.
     *
     * <p>{@link #requests} is a reconstruction for ROUTING, and a lossy one: it keeps only what a
     * scripted reply needs to branch on, and a textified tool history does not survive it intact.
     * Anything measuring how large a conversation has actually grown must use this instead. Kept in
     * step with {@link #requests}, index for index.
     */
    public final List<Integer> requestBytes = new ArrayList<>();

    /** @param router maps the concatenated conversation text of a request to the reply */
    public FakeVllm(Function<String, Reply> router) throws IOException {
        this.router = router;
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                JsonNode request = MAPPER.readTree(raw);
                String conversation = conversationText(request);
                synchronized (requests) {
                    requests.add(conversation);
                    requestBytes.add(raw.length);
                }
                Reply reply = router.apply(conversation);
                boolean stream = request.path("stream").asBoolean(false);
                byte[] body = stream ? sse(reply) : completion(reply);
                exchange.getResponseHeaders().set("Content-Type",
                    stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception e) {
                byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static String conversationText(JsonNode request) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode message : request.path("messages")) {
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                sb.append(content.asText()).append('\n');
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    sb.append(part.path("text").asText("")).append('\n');
                }
            }
            // Tool results may arrive as dedicated fields depending on the client.
            if (message.has("tool_call_id")) {
                sb.append("[tool-result] ").append(message.path("content").asText("")).append('\n');
            }
            if (message.has("tool_calls")) {
                for (JsonNode call : message.path("tool_calls")) {
                    sb.append("[tool-call] ").append(call.path("function").path("name").asText()).append('\n');
                }
            }
        }
        return sb.toString();
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
        } else {
            message.put("content", reply.text());
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-fake");
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", "fake-vllm");
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

    /** SSE framing for streaming clients (only text replies stream in practice). */
    private static byte[] sse(Reply reply) throws IOException {
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("id", "chatcmpl-fake");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", "fake-vllm");
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("content", reply.text() == null ? "" : reply.text());
        choice.putNull("finish_reason");
        String body = "data: " + MAPPER.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
