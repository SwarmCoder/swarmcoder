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
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server that counts the model's earlier thinking as input again on every later call of the
 * same user message is made to let go of it by beginning a new user message (section 94), on
 * the real agent runtime against an endpoint scripted to count as the paid server was measured
 * to: input = what the request holds + the thinking of every assistant turn since the request's
 * last user message.
 *
 * <p>The session decides from what it observes - the server's input count against the size of
 * what it sent - and from nothing else: the same session against a server that counts only what
 * was sent begins no message, and against a server that goes on counting after a new message it
 * begins one and then stops.
 */
class ThinkingTheServerCountsAgainIsLetGoTest {

    /** How the scripted server counts the thinking of earlier turns. */
    enum Counts { ONLY_WHAT_WAS_SENT, THINKING_SINCE_THE_LAST_USER_MESSAGE, ALL_THINKING_EVER }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int THINKING_TOKENS = 1_600;
    private static final int LOOKUPS = 12;
    private static final int TIDY_MARK = 6_000;

    public static final class Tools {
        public String look(String what) {
            return ("about " + what + " ").repeat(200);
        }
    }

    /** Serves {@link #LOOKUPS} turns that think and call the tool, then plain answers. */
    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final List<JsonNode> requests = new ArrayList<>();
        final List<Integer> counted = new ArrayList<>();

        Server(Counts counts) throws Exception {
            http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            http.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                int turn;
                int input = 500;
                int assistantTurns = 0;
                int sinceUser = 0;
                for (JsonNode message : request.path("messages")) {
                    input += (message.path("content").asText("").length()
                        + message.path("tool_calls").toString().length()) / 4 + 4;
                    String role = message.path("role").asText();
                    if (role.equals("assistant")) {
                        assistantTurns++;
                        sinceUser++;
                    } else if (role.equals("user")) {
                        sinceUser = 0;
                    }
                }
                input += THINKING_TOKENS * switch (counts) {
                    case ONLY_WHAT_WAS_SENT -> 0;
                    case THINKING_SINCE_THE_LAST_USER_MESSAGE -> sinceUser;
                    case ALL_THINKING_EVER -> assistantTurns;
                };
                synchronized (requests) {
                    requests.add(request);
                    counted.add(input);
                    turn = requests.size();
                }
                ObjectNode root = JSON.createObjectNode();
                root.put("id", "chatcmpl-fake").put("object", "chat.completion")
                    .put("created", 1).put("model", "fake");
                ObjectNode choice = root.putArray("choices").addObject();
                choice.put("index", 0);
                ObjectNode message = choice.putObject("message");
                message.put("role", "assistant");
                message.put("reasoning_content", "THOUGHT-" + turn + " " + "x".repeat(200));
                if (turn <= LOOKUPS) {
                    message.putNull("content");
                    ObjectNode call = message.putArray("tool_calls").addObject();
                    call.put("id", "call_" + turn).put("type", "function");
                    call.putObject("function").put("name", "look")
                        .put("arguments", "{\"what\":\"thing " + turn + "\"}");
                    choice.put("finish_reason", "tool_calls");
                } else {
                    message.put("content", "That is all.");
                    choice.put("finish_reason", "stop");
                }
                ObjectNode usage = root.putObject("usage");
                usage.put("prompt_tokens", input);
                usage.put("completion_tokens", THINKING_TOKENS + 20);
                usage.put("total_tokens", input + THINKING_TOKENS + 20);
                usage.putObject("completion_tokens_details")
                    .put("reasoning_tokens", THINKING_TOKENS);
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

        /** The role of the last message of request {@code turn} (1-based), and its text. */
        String lastOf(int turn) {
            JsonNode messages = requests.get(turn - 1).path("messages");
            JsonNode last = messages.get(messages.size() - 1);
            return last.path("role").asText() + ":" + last.path("content").asText();
        }

        /** The requests (1-based) that end on the user message the session begins by itself. */
        List<Integer> requestsBeginningAMessage() {
            List<Integer> turns = new ArrayList<>();
            for (int turn = 1; turn <= requests.size(); turn++) {
                if (lastOf(turn).equals("user:" + KoogAgentRuntime.GO_ON)) {
                    turns.add(turn);
                }
            }
            return turns;
        }

        long countedInAll() {
            return counted.stream().mapToLong(Integer::longValue).sum();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    private static Server run(Counts counts) throws Exception {
        Server server = new Server(counts);
        // Tool calls in the history natively, as the hosted model's shape sends them; a room
        // nothing here comes near, so no compaction; results tidied above TIDY_MARK tokens.
        ModelQuirks quirks = new ModelQuirks("scripted", 4_096, true, null, null, false, true,
            false, 1_000_000, 900_000, 1_024, 4, false);
        AgentRuntime.ToolBinding look = new AgentRuntime.ToolBinding("look", "Looks one thing up.",
            new Tools(), Tools.class.getMethod("look", String.class));
        AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec("architect",
            "You look things up.", new AgentRuntime.ModelEndpoint(
                "http://localhost:" + server.http.getAddress().getPort(), "", "fake", 1_000_000,
                quirks),
            0.2, 40, List.of(look), info -> Optional.empty())
            .with(new AgentRuntime.SessionOptions(TIDY_MARK, TIDY_MARK / 2, 200, null));
        try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
            AgentRuntime.SessionResult result = session.run("Begin.");
            assertThat(result.completed()).isTrue();
            assertThat(session.promptTokensSent()).isEqualTo(server.countedInAll());
        }
        return server;
    }

    @Test
    void aNewUserMessageIsBegunWhenTheCountedThinkingPassesTheTidyMark() throws Exception {
        long left;
        try (Server server = run(Counts.THINKING_SINCE_THE_LAST_USER_MESSAGE)) {
            List<Integer> begun = server.requestsBeginningAMessage();
            assertThat(begun).as("the thinking of four calls is 6,400 tokens, over the mark of "
                + "6,000: the fifth call shows it and the sixth request begins a message; "
                + "the count starts again from there").containsExactly(6, 11);
            for (int turn : begun) {
                JsonNode messages = server.requests.get(turn - 1).path("messages");
                assertThat(messages.get(messages.size() - 2).path("role").asText())
                    .as("the results of the turn's calls are sent first, as always")
                    .isEqualTo("tool");
                assertThat(messages.get(messages.size() - 2).path("content").asText())
                    .contains("about thing " + (turn - 1));
                assertThat(server.counted.get(turn - 1))
                    .as("request %s is counted without the earlier thinking", turn)
                    .isLessThan(server.counted.get(turn - 2));
            }
            assertThat(server.lastOf(5)).as("a request before that ends on its results")
                .startsWith("tool:");
            assertThat(server.requests.toString()).doesNotContain("THOUGHT-");
            left = server.countedInAll();
        }

        String before = System.getProperty("swarmcoder.countedNotSent");
        System.setProperty("swarmcoder.countedNotSent", "false");
        try (Server server = run(Counts.THINKING_SINCE_THE_LAST_USER_MESSAGE)) {
            assertThat(server.requestsBeginningAMessage()).isEmpty();
            assertThat(server.counted.get(LOOKUPS)).as("the last call of the lookups carries the "
                + "thinking of all twelve").isGreaterThan(LOOKUPS * THINKING_TOKENS);
            assertThat(left).as("counted with the behaviour (%s) and without (%s)", left,
                server.countedInAll()).isLessThan(server.countedInAll() * 3 / 4);
        } finally {
            if (before == null) {
                System.clearProperty("swarmcoder.countedNotSent");
            } else {
                System.setProperty("swarmcoder.countedNotSent", before);
            }
        }
    }

    @Test
    void aServerThatCountsOnlyWhatWasSentIsLeftAlone() throws Exception {
        try (Server server = run(Counts.ONLY_WHAT_WAS_SENT)) {
            assertThat(server.requestsBeginningAMessage()).isEmpty();
        }
    }

    @Test
    void aServerThatGoesOnCountingAfterANewMessageIsToldOnce() throws Exception {
        try (Server server = run(Counts.ALL_THINKING_EVER)) {
            assertThat(server.requestsBeginningAMessage())
                .as("one message is begun; the count does not fall, so no other is")
                .containsExactly(6);
        }
    }

    @Test
    void theRuleOnItsOwn() {
        CountedNotSent counted = new CountedNotSent(true, 1_000, 10);
        counted.called(2_000, 7_600);            // the first call: 5,600 counted on top
        assertThat(counted.shown()).isFalse();
        counted.called(10_000, 16_400);          // 800 not sent: under the least
        assertThat(counted.excessTokens()).isEqualTo(800);
        assertThat(counted.newMessageDue(true, 20_000)).isFalse();
        counted.called(20_000, 27_500);          // 1,900: under a tenth of what was sent
        assertThat(counted.newMessageDue(true, 20_000)).isFalse();
        counted.called(20_000, 31_600);          // 6,000: shown
        assertThat(counted.newMessageDue(false, 20_000)).as("no tidy, under the mark").isFalse();
        assertThat(counted.newMessageDue(true, 20_000)).as("a tidy on this turn").isTrue();
        assertThat(counted.newMessageDue(true, 0)).as("a session that never tidies").isFalse();
        counted.called(22_000, 49_000);          // 21,400: over the mark by itself
        assertThat(counted.newMessageDue(false, 20_000)).isTrue();
        counted.beginsNewMessage();
        counted.called(24_000, 29_700);          // 100 left: it helped
        assertThat(counted.noUse()).isFalse();
        assertThat(counted.begun()).isEqualTo(1);

        CountedNotSent off = new CountedNotSent(false, 1_000, 10);
        off.called(2_000, 7_600);
        off.called(22_000, 49_000);
        assertThat(off.newMessageDue(true, 20_000)).isFalse();

        CountedNotSent silent = new CountedNotSent(true, 1_000, 10);
        silent.called(2_000, -1);                // a server that reports no usage
        silent.called(22_000, -1);
        assertThat(silent.shown()).isFalse();
    }
}
