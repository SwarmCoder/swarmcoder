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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * SwarmCoder's MCP transport: the modern <b>Streamable HTTP</b> endpoint, and the deprecated
 * 2024-11-05 <b>HTTP with SSE</b> endpoints beside it, both served by the JDK's own
 * {@code com.sun.net.httpserver} and bound to the loopback interface.
 *
 * <p><b>Why the endpoint list changed (2026-09-01).</b> The SSE transport cannot survive this
 * process restarting. Its reply path is a long-lived stream identified by a session id the server
 * holds in memory: when SwarmCoder stops, the stream breaks and the session id dies with it, and
 * the client has no way to get another one without being restarted itself. Measured repeatedly —
 * every restart of SwarmCoder cost the operator every SwarmCoder tool in their running Claude Code
 * session, and only restarting Claude Code brought them back. Streamable HTTP removes the cause
 * rather than papering over it: one tool call is one self-contained POST whose JSON-RPC reply comes
 * back in that same POST's response body. There is no stream to break and no server-held session to
 * lose, so a call made while SwarmCoder is down fails immediately with "connection refused" and the
 * next call after it comes back simply works.
 *
 * <p><b>No session id, deliberately.</b> The spec makes {@code Mcp-Session-Id} optional ("a server
 * ... MAY assign a session ID"), and this server does not, which is the whole point: a session id
 * is state that a restart destroys. One is accepted and ignored if a client sends one, so a client
 * that talked to an older build is never told 404 and never has to re-handshake. The SDK is
 * likewise not an obstacle — {@code McpAsyncServer} registers its {@code tools/list} and
 * {@code tools/call} handlers unconditionally and does not require {@code initialize} to have
 * happened first, so a client whose handshake happened before the restart is answered normally
 * after it.
 *
 * <p><b>Why this is hand-written and not the SDK's.</b> The pinned SDK (0.7.0) ships only
 * {@code StdioServerTransport} and {@code HttpServletSseServerTransport}, so it has no Streamable
 * HTTP server transport at all. Nor does upgrading supply one that fits: the newest SDK's
 * {@code HttpServletStreamableServerTransportProvider} is, as the name says, a servlet, and
 * dragging a servlet container into the orchestrator to serve one endpoint is not a thin adapter.
 * Stdio is out for a different reason — it requires the CLIENT to have launched the process, but
 * the orchestrator is already running and holding a store nobody else may open, which is the whole
 * reason an outside agent needs a way in.
 *
 * <p><b>Endpoints, under a configurable base path:</b>
 * <ul>
 *   <li>{@code POST <base>/mcp} — Streamable HTTP. One JSON-RPC message per request. A
 *       <i>request</i> is answered 200 with the JSON-RPC reply in the body; a <i>notification</i>
 *       or <i>response</i> is answered 202 with no body, as the spec requires.</li>
 *   <li>{@code GET <base>/mcp} — 405. This server sends nothing the client did not ask for, and
 *       the spec's answer for a server that offers no server-to-client stream is exactly 405.</li>
 *   <li>{@code GET  <base>/sse} — the deprecated transport: opens an event stream and immediately
 *       sends an {@code endpoint} event naming the message URL, session id included.</li>
 *   <li>{@code POST <base>/message?sessionId=…} — the deprecated transport's message channel;
 *       answered 202, with the real reply delivered down that client's stream.</li>
 * </ul>
 *
 * <p>Both transports are kept because the spec's own backwards-compatibility guidance says to keep
 * them ("continue to host both the SSE and POST endpoints of the old transport, alongside the new
 * MCP endpoint"), and because removing a working transport to add another one breaks whatever was
 * already pointed at it for no gain.
 *
 * <p><b>What was got right the first time and is kept.</b> On the deprecated transport the POST is
 * answered <b>202 Accepted</b> and the JSON-RPC reply comes back down the client's own SSE stream.
 * The SDK's servlet transport writes the reply into the POST body instead, which the reference
 * clients ignore, so every request against a server built that way hangs.
 *
 * <p>A stream costs one thread for as long as it is open, because a {@code HttpExchange} closes the
 * moment its handler returns. That is affordable at this scale and bounded by {@link #MAX_STREAMS}:
 * the Console is a single-operator tool (design §7) and this serves the same one operator's agent,
 * not a fleet. A Streamable HTTP POST costs a thread only while its answer is being computed.
 */
public final class LoopbackHttpTransport implements ServerMcpTransport {

    private static final Logger log = LoggerFactory.getLogger(LoopbackHttpTransport.class);

    /** Beyond this many simultaneous streams the transport refuses rather than growing threads. */
    static final int MAX_STREAMS = 8;

    private static final long KEEPALIVE_SECONDS = 20;

    /**
     * How long a Streamable HTTP POST waits for its answer before giving the caller a JSON-RPC
     * error instead of holding the socket open forever. Every tool here reads the Console's own
     * in-memory services and answers in milliseconds, so reaching this is a bug, not slowness —
     * but a hung request that never answers is exactly the failure this whole change exists to
     * remove, so it is bounded.
     */
    static final long REPLY_TIMEOUT_SECONDS = 120;

    /**
     * The mapper the SDK reads every inbound message with, deliberately TOLERANT of fields it does
     * not know.
     *
     * <p>This is not a nicety. The pinned SDK's {@code ClientCapabilities} record knows three
     * fields — {@code experimental}, {@code roots}, {@code sampling} — and, unlike most of its
     * sibling records, is not annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}. Claude
     * Code sends a fourth, {@code elicitation}. With a default mapper Jackson treats that as a hard
     * error, the error surfaces as {@code -32603} on the {@code initialize} reply, and since
     * nothing is usable before the handshake completes, NO TOOL IS EVER REACHABLE. Every unit test
     * passed because every unit test spoke the version the SDK expects.
     *
     * <p>The protocol will keep growing fields, and a client newer than the server it is talking to
     * is the normal case, not the exception — MCP's own spec says an unrecognised field is to be
     * ignored. Tolerating them is therefore the correct behaviour and not merely a workaround for
     * one field: bumping the SDK would fix {@code elicitation} and leave the next one to break the
     * same way. (A bump is also not the small change it sounds like: {@code ServerMcpTransport},
     * the interface this class implements, no longer exists after 0.7.0 — 0.10.0 replaced it with
     * {@code McpServerTransportProvider} and a session-per-transport model, and 1.x moved the
     * classes into different artifacts again. Either would mean rewriting this transport, on a
     * module several recently-merged features already depend on.)
     *
     * <p>What is NOT relaxed: a message that is not JSON-RPC at all is still refused with 400, and
     * a field the server does know still has to have the right shape.
     */
    private final ObjectMapper json = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    /** JSON-RPC request id → the stream that asked, so its answer goes back only to it. */
    private final Map<String, Stream> awaitingReply = new ConcurrentHashMap<>();
    /** Internal request token → the Streamable HTTP POST holding its socket open for the answer. */
    private final Map<String, Pending> awaitingPost = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    private final String basePath;
    private final int requestedPort;

    private HttpServer http;
    private ExecutorService streamThreads;
    private ScheduledExecutorService keepalive;
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

    /**
     * @param basePath      URL prefix for the endpoints, e.g. {@code ""} or {@code "/swarmcoder"}
     * @param requestedPort the loopback port to bind, or 0 to let the OS choose (tests)
     */
    public LoopbackHttpTransport(String basePath, int requestedPort) {
        this.basePath = basePath == null || basePath.equals("/") ? "" : basePath;
        this.requestedPort = requestedPort;
    }

    /**
     * The tools that change something, and the secret a caller must present to run one.
     *
     * <p>Empty by default, which guards nothing: a server offering only read tools behaves exactly
     * as it always did. When tools are named here and the secret is null, every one of them is
     * refused. There is no setting that offers a write tool on an open port.
     */
    private volatile java.util.Set<String> guardedTools = java.util.Set.of();
    private volatile String writeSecret;

    /**
     * Names the tools that need the secret. Called once, before {@link #start()}.
     *
     * @param tools  tool names that change something
     * @param secret what {@code Authorization: Bearer} must carry; null refuses them all
     */
    public void guardTools(java.util.Set<String> tools, String secret) {
        this.guardedTools = tools == null ? java.util.Set.of() : java.util.Set.copyOf(tools);
        this.writeSecret = secret;
    }

    /**
     * Why this message may not be run, or null when it may.
     *
     * <p>Only a {@code tools/call} naming a guarded tool is ever refused. Everything else, the
     * handshake, the tool list and every read tool, passes with no header at all.
     */
    String refusal(McpSchema.JSONRPCMessage message, String authorization) {
        if (guardedTools.isEmpty() || !(message instanceof McpSchema.JSONRPCRequest request)
                || !"tools/call".equals(request.method())
                || !(request.params() instanceof Map<?, ?> params)) {
            return null;
        }
        Object name = params.get("name");
        if (name == null || !guardedTools.contains(String.valueOf(name))) {
            return null;
        }
        if (McpSecret.matches(writeSecret, authorization)) {
            return null;
        }
        return "Refused: " + name + " changes something, and tools that change something need "
            + "this installation's MCP secret. Send it as the header 'Authorization: Bearer "
            + "<secret>'. The secret is in the file '" + McpSecret.FILE_NAME + "' in the "
            + "SwarmCoder home folder, beside the settings file. Tools that only read need "
            + "nothing.";
    }

    /** One open event stream: the client's output, and the latch its serving thread waits on. */
    private static final class Stream {
        final String id;
        final OutputStream out;
        final CountDownLatch closed = new CountDownLatch(1);

        Stream(String id, OutputStream out) {
            this.id = id;
            this.out = out;
        }

        synchronized void event(String type, String data) throws IOException {
            out.write(("event: " + type + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void comment() throws IOException {
            out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * A Streamable HTTP request in flight: the id its client used, and the future its serving
     * thread is blocked on.
     *
     * <p>The id is remembered because the one handed to the SDK is NOT the client's. Every inbound
     * request is given a fresh internal token and the client's own id is put back on the way out.
     * Without that, two clients that both number their requests {@code 1} — which is what every
     * client does — would be told each other's answers, because the SDK's session hands the
     * transport a response carrying an id and no idea who it belongs to.
     */
    private record Pending(Object clientId, CompletableFuture<String> reply) {
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Binds the loopback port and starts serving. Never binds a wildcard address: the address is
     * {@link InetAddress#getLoopbackAddress()} and there is no setting that changes it.
     *
     * @return the port actually bound
     */
    public int start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        streamThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcp-http");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(streamThreads);
        http.createContext(basePath + "/mcp", this::handleStreamable);
        http.createContext(basePath + "/sse", this::handleSse);
        http.createContext(basePath + "/message", this::handleMessage);
        http.start();

        keepalive = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        keepalive.scheduleWithFixedDelay(this::pingAll,
            KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        return http.getAddress().getPort();
    }

    /** The bound port, or -1 before {@link #start()}. */
    public int port() {
        return http == null ? -1 : http.getAddress().getPort();
    }

    /**
     * The address actually bound. Exposed so a test can prove it is a loopback address rather than
     * take the constructor's word for it — that property is the whole of this server's security.
     */
    public InetAddress boundAddress() {
        return http == null ? null : http.getAddress().getAddress();
    }

    /** The URL prefix the endpoints sit under, normalised ({@code ""} for the root). */
    public String basePathForClients() {
        return basePath;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.handler = handler;
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::shutdown);
    }

    @Override
    public void close() {
        shutdown();
    }

    private void shutdown() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        // Anyone blocked on a POST is told now rather than waiting out the timeout on a server that
        // is already gone. Shutting down is the one case where a fast, honest failure is the goal.
        for (Pending pending : awaitingPost.values()) {
            pending.reply().completeExceptionally(
                new IllegalStateException("SwarmCoder's MCP server is shutting down."));
        }
        awaitingPost.clear();
        for (Stream stream : streams.values()) {
            drop(stream);
        }
        streams.clear();
        if (keepalive != null) {
            keepalive.shutdownNow();
        }
        if (http != null) {
            http.stop(0);
        }
        if (streamThreads != null) {
            streamThreads.shutdownNow();
        }
    }

    // --- outbound --------------------------------------------------------------------------------

    /**
     * Everything the server sends out: replies to requests, and notifications.
     *
     * <p>A reply goes to the ONE caller who asked. On Streamable HTTP that is the POST still
     * holding its socket open, found by the internal token this transport put on the request; its
     * client's own id is restored before the JSON goes out. On the deprecated transport it is the
     * stream whose client asked, looked up by the request's JSON-RPC id, which
     * {@link #handleMessage} noted on the way in. A notification, which nobody asked for, goes to
     * every open stream.
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            if (message instanceof McpSchema.JSONRPCResponse response) {
                Pending pending = awaitingPost.remove(String.valueOf(response.id()));
                if (pending != null) {
                    try {
                        pending.reply().complete(json.writeValueAsString(
                            new McpSchema.JSONRPCResponse(response.jsonrpc(), pending.clientId(),
                                response.result(), response.error())));
                    } catch (Exception e) {
                        pending.reply().completeExceptionally(e);
                    }
                    return;
                }
            }
            String text;
            try {
                text = json.writeValueAsString(message);
            } catch (Exception e) {
                log.warn("MCP: could not serialise an outgoing message: {}", e.toString());
                return;
            }
            Stream addressed = message instanceof McpSchema.JSONRPCResponse response
                ? awaitingReply.remove(String.valueOf(response.id())) : null;
            Collection<Stream> recipients =
                addressed != null ? List.of(addressed) : streams.values();
            for (Stream stream : recipients) {
                try {
                    stream.event("message", text);
                } catch (IOException e) {
                    drop(stream);
                }
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return json.convertValue(data, typeRef);
    }

    // --- the Streamable HTTP endpoint --------------------------------------------------------------

    /**
     * The single MCP endpoint, {@code <base>/mcp}, as the spec requires ("the server MUST provide a
     * single HTTP endpoint path ... that supports both POST and GET methods").
     */
    private void handleStreamable(HttpExchange exchange) throws IOException {
        if (!originIsLocal(exchange)) {
            plain(exchange, 403, "Refused: this MCP server only answers requests from this "
                + "machine, and that request claimed to come from a web page somewhere else.");
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "POST" -> handleStreamablePost(exchange);
            case "GET" -> {
                // The spec's own answer for a server that offers no server-to-client stream. It is
                // also what keeps this transport restart-proof: nothing long-lived to break.
                exchange.getResponseHeaders().set("Allow", "POST");
                plain(exchange, 405, "This MCP server never speaks first, so there is no event "
                    + "stream to open here. Send each JSON-RPC message as its own POST to this "
                    + "same URL and read the answer from the response.");
            }
            case "DELETE" -> {
                // "The server MAY respond to this request with HTTP 405 ..., indicating that the
                // server does not allow clients to terminate sessions." There are no sessions.
                exchange.getResponseHeaders().set("Allow", "POST");
                plain(exchange, 405, "This MCP server keeps no session, so there is none to end.");
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "POST");
                plain(exchange, 405, "Only POST is supported at this URL.");
            }
        }
    }

    private void handleStreamablePost(HttpExchange exchange) throws IOException {
        if (closing.get()) {
            plain(exchange, 503, "SwarmCoder's MCP server is shutting down.");
            return;
        }
        // The client "MUST include an Accept header, listing both application/json and
        // text/event-stream". Only the first matters here, because application/json is what this
        // endpoint returns; a client that will not take it gets told so instead of being sent it.
        if (!acceptsJson(exchange.getRequestHeaders().getFirst("Accept"))) {
            plain(exchange, 406, "This endpoint answers with application/json, which that request "
                + "said it would not accept. Send: Accept: application/json, text/event-stream");
            return;
        }
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> current = handler;
        if (current == null) {
            plain(exchange, 503, "SwarmCoder's MCP server is not accepting messages yet.");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.stripLeading().startsWith("[")) {
            // Batching was allowed in 2025-03-26 and removed again in 2025-06-18. Saying so beats
            // a Jackson stack trace, and no current client sends one.
            plain(exchange, 400, "Send one JSON-RPC message per request. Batches (a JSON array) "
                + "were removed from the MCP specification in 2025-06-18.");
            return;
        }
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(json, body);
        } catch (Exception e) {
            plain(exchange, 400, "That is not a JSON-RPC message: " + e.getMessage());
            return;
        }

        if (!(message instanceof McpSchema.JSONRPCRequest request)) {
            // "If the input is a JSON-RPC response or notification: if the server accepts the
            // input, the server MUST return HTTP status code 202 Accepted with no body."
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            current.apply(Mono.just(message)).subscribe(
                ignored -> { },
                error -> log.warn("MCP: notification failed: {}", error.toString()));
            return;
        }

        String refused = refusal(message, exchange.getRequestHeaders().getFirst("Authorization"));
        if (refused != null) {
            // A JSON-RPC error on a 200, not a 401: a 401 tells an MCP client the whole server
            // wants a login, and the read tools must keep working with no header.
            byte[] bytes = errorReply(request.id(), -32001, refused).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }

        // The client's id is set aside and a token of ours goes out in its place, so an answer can
        // never be handed to the wrong caller. See Pending.
        String token = "sc-" + UUID.randomUUID();
        CompletableFuture<String> reply = new CompletableFuture<>();
        awaitingPost.put(token, new Pending(request.id(), reply));

        // The handler is a doOnNext pass-through: it re-emits what it was given and delivers the
        // real answer through sendMessage. Subscribing is what drives it; what it emits is ignored.
        current.apply(Mono.just(new McpSchema.JSONRPCRequest(
                request.jsonrpc(), request.method(), token, request.params())))
            .subscribe(
                ignored -> { },
                error -> failPending(token, error));

        String answer;
        try {
            answer = reply.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            awaitingPost.remove(token);
            answer = errorReply(request.id(), -32001,
                "SwarmCoder did not answer within " + REPLY_TIMEOUT_SECONDS + " seconds.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            awaitingPost.remove(token);
            answer = errorReply(request.id(), -32603, "SwarmCoder's MCP server was interrupted.");
        } catch (Exception e) {
            awaitingPost.remove(token);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            answer = errorReply(request.id(), -32603, String.valueOf(cause.getMessage()));
        }

        // "If the input is a JSON-RPC request, the server MUST either return
        // Content-Type: text/event-stream ... or application/json, to return one JSON object."
        // One JSON object, in this response, is the whole reason this transport survives a restart.
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void failPending(String token, Throwable error) {
        Pending pending = awaitingPost.remove(token);
        if (pending != null) {
            pending.reply().completeExceptionally(error);
        }
        log.warn("MCP: request failed: {}", error.toString());
    }

    private String errorReply(Object id, int code, String message) {
        try {
            return json.writeValueAsString(new McpSchema.JSONRPCResponse("2.0", id, null,
                new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null)));
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                + "\"message\":\"SwarmCoder could not describe what went wrong.\"}}";
        }
    }

    // --- the deprecated HTTP+SSE endpoints ---------------------------------------------------------

    private void handleSse(HttpExchange exchange) throws IOException {
        if (!originIsLocal(exchange)) {
            plain(exchange, 403, "Refused: this MCP server only answers requests from this "
                + "machine, and that request claimed to come from a web page somewhere else.");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (closing.get()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        if (streams.size() >= MAX_STREAMS) {
            // Said out loud rather than dropped: a client that cannot connect deserves the reason.
            plain(exchange, 429, "SwarmCoder's MCP server already has " + MAX_STREAMS
                + " open connections, which is all it allows. Close one and try again.");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked, stays open

        String id = UUID.randomUUID().toString();
        Stream stream = new Stream(id, exchange.getResponseBody());
        streams.put(id, stream);
        log.info("MCP: client connected to the deprecated SSE transport ({} open)", streams.size());
        try {
            stream.event("endpoint", basePath + "/message?sessionId=" + id);
            stream.closed.await();
        } catch (IOException e) {
            log.debug("MCP: stream {} failed on open: {}", id, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(id);
            exchange.close();
            log.info("MCP: client disconnected ({} open)", streams.size());
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        if (!originIsLocal(exchange)) {
            plain(exchange, 403, "Refused: this MCP server only answers requests from this "
                + "machine, and that request claimed to come from a web page somewhere else.");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String sessionId = queryParam(exchange.getRequestURI().getQuery(), "sessionId");
        Stream stream = sessionId == null ? null : streams.get(sessionId);
        if (stream == null) {
            plain(exchange, 404, "No open event stream with that sessionId. Reconnect to "
                + basePath + "/sse first.");
            return;
        }
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> current = handler;
        if (current == null) {
            plain(exchange, 503, "SwarmCoder's MCP server is not accepting messages yet.");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(json, body);
        } catch (Exception e) {
            plain(exchange, 400, "That is not a JSON-RPC message: " + e.getMessage());
            return;
        }

        String refused = refusal(message, exchange.getRequestHeaders().getFirst("Authorization"));
        if (refused != null && message instanceof McpSchema.JSONRPCRequest request) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            try {
                stream.event("message", errorReply(request.id(), -32001, refused));
            } catch (IOException e) {
                drop(stream);
            }
            return;
        }

        // Note who to answer BEFORE handing the message over: the SDK's session handles it inline
        // and calls sendMessage with a response that carries an id and nothing else.
        if (message instanceof McpSchema.JSONRPCRequest request && request.id() != null) {
            awaitingReply.put(String.valueOf(request.id()), stream);
        }

        // 202 first: the reply travels on the SSE stream, not in this response body. The SDK's own
        // servlet transport writes it into this body instead, which the reference clients ignore —
        // a server built that way answers nothing and every call hangs.
        exchange.sendResponseHeaders(202, -1);
        exchange.close();

        current.apply(Mono.just(message)).subscribe(
            ignored -> { },
            error -> log.warn("MCP: request failed: {}", error.toString()));
    }

    // --- shared plumbing ---------------------------------------------------------------------------

    /**
     * The spec's first security requirement: "servers MUST validate the {@code Origin} header on
     * all incoming connections to prevent DNS rebinding attacks". Without it a page on any website
     * the operator happens to have open could drive this server through their browser.
     *
     * <p>An absent header is allowed, because that is what a real MCP client sends — only browsers
     * set {@code Origin}, and a browser that sets it to anything but this machine is the attack.
     * The host is compared literally and never resolved: resolving it is the attack.
     */
    static boolean isLocalOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.equals("null")) {
            return true;
        }
        String host;
        try {
            host = URI.create(origin.trim()).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.equalsIgnoreCase("localhost")
            || host.equals("127.0.0.1") || host.startsWith("127.")
            || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
    }

    private boolean originIsLocal(HttpExchange exchange) {
        return isLocalOrigin(exchange.getRequestHeaders().getFirst("Origin"));
    }

    /** True when the caller will take {@code application/json}, which is all this endpoint sends. */
    static boolean acceptsJson(String accept) {
        if (accept == null || accept.isBlank()) {
            return true;
        }
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") || lower.contains("application/*")
            || lower.contains("*/*");
    }

    private static void plain(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private void pingAll() {
        for (Stream stream : streams.values()) {
            try {
                stream.comment();
            } catch (IOException e) {
                drop(stream);
            }
        }
    }

    private void drop(Stream stream) {
        streams.remove(stream.id);
        awaitingReply.values().removeIf(waiting -> waiting == stream);
        stream.closed.countDown();
    }
}
