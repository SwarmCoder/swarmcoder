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
package com.swarmcoder.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * An in-process OpenAI-compatible endpoint for a role that works as an agent: a request that
 * offers tools is one turn of a lookup session and is answered from a script by turn, as a tool
 * call or as text; every other request is one of the role's ordinary one-reply calls and is
 * answered by conversation text, exactly as {@link ScriptedLlm} does. So the REAL stack runs - the
 * real agent runtime, the real tool-call parsing, the real lookups on real files - and nothing
 * here can reach a model.
 */
final class ScriptedAgentLlm implements AutoCloseable {

    /** One scripted assistant turn of a session: a tool call, or plain text. */
    record Turn(String text, String tool, String argsJson) {

        static Turn text(String text) {
            return new Turn(text, null, null);
        }

        static Turn call(String tool, Map<String, String> args) {
            try {
                return new Turn(null, tool, MAPPER.writeValueAsString(args));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Turn number (1-based, counted over the session's requests) and conversation so far. */
    interface Session {
        Turn turn(int number, String conversation);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    /** Every session request, as the conversation text it carried, in order. */
    final List<String> sessionRequests = new CopyOnWriteArrayList<>();
    /** Every one-reply request, as the conversation text it carried, in order. */
    final List<String> oneReplyRequests = new CopyOnWriteArrayList<>();

    ScriptedAgentLlm(Function<String, String> oneReply, Session session) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body;
            String contentType;
            try {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                StringBuilder conversation = new StringBuilder();
                for (JsonNode message : request.path("messages")) {
                    conversation.append(message.path("content").asText("")).append('\n');
                    for (JsonNode call : message.path("tool_calls")) {
                        conversation.append(call.path("function").path("name").asText(""))
                            .append(' ').append(call.path("function").path("arguments").asText(""))
                            .append('\n');
                    }
                }
                boolean isSession = request.path("tools").isArray() && !request.path("tools").isEmpty();
                if (isSession) {
                    int number;
                    synchronized (sessionRequests) {
                        sessionRequests.add(conversation.toString());
                        number = sessionRequests.size();
                    }
                    body = completion(session.turn(number, conversation.toString()));
                    contentType = "application/json";
                } else {
                    oneReplyRequests.add(conversation.toString());
                    ObjectNode chunk = MAPPER.createObjectNode();
                    ObjectNode choice = chunk.putArray("choices").addObject();
                    choice.put("index", 0);
                    choice.putObject("delta").put("content", oneReply.apply(conversation.toString()));
                    body = ("data: " + MAPPER.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                    contentType = "text/event-stream";
                }
            } catch (Exception e) {
                byte[] err = ("{\"error\":\"" + e + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(err);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static byte[] completion(Turn turn) throws IOException {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        if (turn.tool() != null) {
            message.putNull("content");
            ObjectNode call = message.putArray("tool_calls").addObject();
            call.put("id", "call_" + Math.abs(turn.argsJson().hashCode()));
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", turn.tool());
            function.put("arguments", turn.argsJson());
        } else {
            message.put("content", turn.text() == null ? "" : turn.text());
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-scripted");
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", "scripted");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", turn.tool() != null ? "tool_calls" : "stop");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 20);
        usage.put("total_tokens", 120);
        return MAPPER.writeValueAsBytes(root);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
