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
package com.swarmcoder.server.mcp;

import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.ObserverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real MCP client, over a real socket, against the real transport — both of them.
 *
 * <p>Worth its seconds because the transport is hand-written and the two halves fail in opposite
 * ways. On the deprecated event-stream transport the SDK's own servlet transport writes the reply
 * into the POST body, which the reference clients ignore, so a server built that way passes every
 * unit test and then hangs on the first request a real client makes. On the modern Streamable HTTP
 * transport the reply belongs in the POST body and nowhere else, and the property that matters is
 * that a client survives this process being restarted underneath it — which is a thing no
 * in-memory assertion can show. The only way to know is to speak the protocol to it.
 */
class SwarmMcpServerTest {

    private SwarmMcpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void aClientCanHandshakeListToolsAndCallOne() throws Exception {
        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of())
            .returning("activeSessions", List.of())
            .build();
        server = new SwarmMcpServer(observer, Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), 0, "", false);
        server.start();
        String url = server.sseUrl();

        assertThat(server.boundAddress().isLoopbackAddress())
            .describedAs("the port must never be reachable from off this machine")
            .isTrue();
        assertThat(url).startsWith("http://127.0.0.1:").endsWith("/sse");

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        Stream stream = new Stream(client, url);
        try {
            String messageUrl = "http://127.0.0.1:" + server.port() + stream.endpoint();

            post(client, messageUrl, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2024-11-05","capabilities":{},
                  "clientInfo":{"name":"test","version":"1"}}}""");
            String handshake = stream.nextMessage();
            assertThat(handshake).contains("\"serverInfo\"").contains(SwarmMcpServer.SERVER_NAME);

            post(client, messageUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            post(client, messageUrl, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            String toolList = stream.nextMessage();
            assertThat(toolList)
                .describedAs("the first two tools are the two that answer 'what is it doing' and "
                    + "'why did it stop'")
                .contains("swarm_status")
                .contains("run_diagnosis");

            post(client, messageUrl, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"swarm_status","arguments":{}}}""");
            String called = stream.nextMessage();
            assertThat(called).contains("Nothing is running.");
        } finally {
            stream.close();
        }
    }

    /**
     * The failure that made this server unreachable from Claude Code on 2026-08-29.
     *
     * <p>A real client announces capabilities the pinned SDK has never heard of —
     * {@code elicitation} is the one that bit — and the SDK's {@code ClientCapabilities} record is
     * one of the few in its schema that is NOT marked to ignore unknown fields. A strict mapper
     * turns that into {@code -32603} on the very first message, and because nothing works before
     * {@code initialize} completes, not one tool is reachable. Every other test here passes anyway,
     * because they all speak exactly the version the SDK expects; only this one does not.
     *
     * <p>So it sends what a newer client actually sends: an unknown capability, an unknown field
     * inside a known capability, an unknown field beside the known ones in the request, and a
     * protocol version later than the SDK's own. Then it insists a tool is reachable afterwards —
     * a handshake that "succeeds" but leaves nothing callable would be the same outage.
     */
    @Test
    void aNewerClientAnnouncingCapabilitiesTheSdkNeverHeardOfStillGetsItsTools() throws Exception {
        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of())
            .returning("activeSessions", List.of())
            .build();
        server = new SwarmMcpServer(observer, Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), 0, "", false);
        server.start();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        Stream stream = new Stream(client, server.sseUrl());
        try {
            String messageUrl = "http://127.0.0.1:" + server.port() + stream.endpoint();

            post(client, messageUrl, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{
                    "elicitation":{},
                    "roots":{"listChanged":true,"somethingAddedLater":"x"},
                    "sampling":{},
                    "somethingElseAddedLater":{"nested":[1,2,3]}},
                  "clientInfo":{"name":"claude-code","version":"9.9.9","title":"Claude Code"},
                  "aFieldBesideTheKnownOnes":true}}""");

            String handshake = stream.nextMessage();
            assertThat(handshake)
                .describedAs("an unknown capability field must not fail the handshake — when it "
                    + "did, the whole server was unreachable and no test noticed")
                .doesNotContain("\"error\"");
            assertThat(handshake).contains("\"serverInfo\"").contains(SwarmMcpServer.SERVER_NAME);

            post(client, messageUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            post(client, messageUrl, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            String toolList = stream.nextMessage();
            assertThat(toolList)
                .describedAs("the point of the handshake is that tools become reachable")
                .doesNotContain("\"error\"")
                .contains("swarm_status");

            post(client, messageUrl, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"swarm_status","arguments":{},"anUnknownArgumentEnvelope":1}}""");
            assertThat(stream.nextMessage()).contains("Nothing is running.");
        } finally {
            stream.close();
        }
    }

    /** Tolerating unknown FIELDS must not turn into tolerating things that are not messages. */
    @Test
    void somethingThatIsNotAJsonRpcMessageIsStillRefused() throws Exception {
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(),
            0, "", false);
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        Stream stream = new Stream(client, server.sseUrl());
        try {
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                        + stream.endpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"hello\":\"world\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        } finally {
            stream.close();
        }
    }

    @Test
    void aPostWithNoOpenStreamIsRefusedWithAReason() throws Exception {
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(),
            0, "", false);
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/message?sessionId=nobody"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Reconnect to /sse first.");
    }

    // --- the modern transport: one self-contained POST per call ------------------------------------

    /**
     * Streamable HTTP in one test: a tool call is a POST whose answer arrives in that same POST's
     * response body. Nothing long-lived is opened, so there is nothing for a restart to break.
     */
    @Test
    void aToolCallIsOnePostWithItsAnswerInTheResponseBody() throws Exception {
        server = new SwarmMcpServer(statusObserver(), Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), 0, "", false);
        String url = server.start();

        assertThat(url)
            .describedAs("the URL handed to an operator must be the transport that survives a "
                + "restart, not the event-stream one")
            .isEqualTo("http://127.0.0.1:" + server.port() + "/mcp");

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> handshake = rpc(client, url, INITIALIZE);
        assertThat(handshake.statusCode()).isEqualTo(200);
        assertThat(handshake.headers().firstValue("Content-Type").orElse(""))
            .describedAs("a request must be answered with one JSON object, not a stream")
            .startsWith("application/json");
        assertThat(handshake.body()).contains("\"serverInfo\"").contains(SwarmMcpServer.SERVER_NAME);
        assertThat(handshake.headers().firstValue("Mcp-Session-Id"))
            .describedAs("no session id is issued on purpose: a session id is state a restart "
                + "destroys, and losing it is the whole failure being fixed")
            .isEmpty();

        assertThat(rpc(client, url, INITIALIZED).statusCode())
            .describedAs("a notification carries no reply, so the spec's answer is 202 and no body")
            .isEqualTo(202);

        assertThat(rpc(client, url, TOOLS_LIST).body())
            .contains("swarm_status").contains("run_diagnosis");
        assertThat(rpc(client, url, STATUS_CALL).body()).contains("Nothing is running.");
    }

    /**
     * The failure this transport exists to remove, reproduced end to end.
     *
     * <p>With the deprecated event-stream transport, stopping SwarmCoder broke the client's stream
     * and killed the session id it needed to send anything, so every SwarmCoder tool went away in
     * the operator's running assistant and stayed away until the ASSISTANT was restarted too.
     * Here the same client object is used throughout, and step six sends no new handshake — if the
     * server had held anything about that client, this is where it would fail.
     */
    @Test
    void theSameClientKeepsWorkingAfterSwarmCoderIsRestartedUnderneathIt() throws Exception {
        server = new SwarmMcpServer(statusObserver(), Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), 0, "", false);
        server.start();
        int port = server.port();
        String url = "http://127.0.0.1:" + port + "/mcp";
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

        assertThat(rpc(client, url, INITIALIZE).body()).contains(SwarmMcpServer.SERVER_NAME);
        assertThat(rpc(client, url, INITIALIZED).statusCode()).isEqualTo(202);
        assertThat(rpc(client, url, STATUS_CALL).body()).contains("Nothing is running.");

        server.stop();
        server = null;

        long began = System.nanoTime();
        assertThatThrownBy(() -> rpc(client, url, STATUS_CALL))
            .describedAs("a call made while SwarmCoder is down must fail, not hang")
            .isInstanceOf(IOException.class);
        assertThat(System.nanoTime() - began)
            .describedAs("and fail promptly")
            .isLessThan(Duration.ofSeconds(15).toNanos());

        server = new SwarmMcpServer(statusObserver(), Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), port, "", false);
        server.start();

        assertThat(rpc(client, url, STATUS_CALL).body())
            .describedAs("the same client, with no new handshake, gets its tools back")
            .doesNotContain("\"error\"")
            .contains("Nothing is running.");
    }

    /**
     * A GET on the MCP endpoint is answered 405, which the spec names as the correct answer for a
     * server that offers no server-to-client stream. It is also what keeps this restart-proof: a
     * stream the client held open is a stream a restart can break.
     */
    @Test
    void theModernEndpointOpensNoStreamThatARestartCouldBreak() throws Exception {
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(),
            0, "", false);
        String url = server.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream").GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("POST");
    }

    /**
     * The spec's first security requirement: validate {@code Origin}, or a page on any website the
     * operator happens to have open can drive this server through their browser.
     */
    @Test
    void aRequestClaimingToComeFromAWebsiteIsRefused() throws Exception {
        server = new SwarmMcpServer(statusObserver(), Stubs.of(GraphService.class).build(),
            Stubs.of(ControlService.class).build(), 0, "", false);
        String url = server.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Origin", "https://not-this-machine.example")
                .POST(HttpRequest.BodyPublishers.ofString(STATUS_CALL))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(LoopbackHttpTransport.isLocalOrigin("http://localhost:5173")).isTrue();
        assertThat(LoopbackHttpTransport.isLocalOrigin("http://127.0.0.1:8931")).isTrue();
        assertThat(LoopbackHttpTransport.isLocalOrigin("https://evil.example")).isFalse();
    }

    /** Tolerating unknown fields must not turn into tolerating things that are not messages. */
    @Test
    void theModernEndpointStillRefusesSomethingThatIsNotAJsonRpcMessage() throws Exception {
        server = new SwarmMcpServer(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), Stubs.of(ControlService.class).build(),
            0, "", false);
        String url = server.start();

        HttpClient client = HttpClient.newHttpClient();
        assertThat(rpc(client, url, "{\"hello\":\"world\"}").statusCode()).isEqualTo(400);
        assertThat(rpc(client, url, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]")
            .statusCode())
            .describedAs("batches left the specification in 2025-06-18")
            .isEqualTo(400);
    }

    // --- shared fixtures ---------------------------------------------------------------------------

    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-06-18","capabilities":{"elicitation":{}},
          "clientInfo":{"name":"claude-code","version":"9.9.9"}}}""";
    private static final String INITIALIZED =
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String TOOLS_LIST =
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final String STATUS_CALL = """
        {"jsonrpc":"2.0","id":3,"method":"tools/call",
         "params":{"name":"swarm_status","arguments":{}}}""";

    private static ObserverService statusObserver() {
        return Stubs.of(ObserverService.class)
            .returning("listRuns", List.of())
            .returning("activeSessions", List.of())
            .build();
    }

    /** One JSON-RPC message, one POST, answer in the response — the whole modern transport. */
    private static HttpResponse<String> rpc(HttpClient client, String url, String body)
            throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    // --- a minimal SSE client ----------------------------------------------------------------------

    private static void post(HttpClient client, String url, String body) throws Exception {
        HttpResponse<Void> response = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        // 202: the reply comes down the event stream, not in this response. That is the protocol,
        // and getting it wrong is the failure this test exists to catch.
        assertThat(response.statusCode()).isEqualTo(202);
    }

    /** Reads {@code event:}/{@code data:} pairs off the stream on its own thread. */
    private static final class Stream implements AutoCloseable {
        private final HttpResponse<java.io.InputStream> response;
        private final Thread reader;
        private final BlockingQueue<String> endpoints = new ArrayBlockingQueue<>(4);
        private final BlockingQueue<String> messages = new ArrayBlockingQueue<>(64);

        Stream(HttpClient client, String sseUrl) throws Exception {
            this.response = client.send(HttpRequest.newBuilder(URI.create(sseUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            this.reader = new Thread(this::pump, "sse-test-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void pump() {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = null;
                List<String> data = new ArrayList<>();
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        event = line.substring(7);
                    } else if (line.startsWith("data: ")) {
                        data.add(line.substring(6));
                    } else if (line.isEmpty() && event != null) {
                        String payload = String.join("\n", data);
                        if (event.equals("endpoint")) {
                            endpoints.offer(payload);
                        } else {
                            messages.offer(payload);
                        }
                        event = null;
                        data = new ArrayList<>();
                    }
                }
            } catch (Exception ignored) {
                // the stream closing at the end of the test is not a failure
            }
        }

        String endpoint() throws InterruptedException {
            String value = endpoints.poll(10, TimeUnit.SECONDS);
            assertThat(value).describedAs("the server must announce its message endpoint")
                .isNotNull();
            return value;
        }

        String nextMessage() throws InterruptedException {
            String value = messages.poll(15, TimeUnit.SECONDS);
            assertThat(value).describedAs("a reply must arrive on the event stream").isNotNull();
            return value;
        }

        @Override
        public void close() {
            reader.interrupt();
        }
    }
}
