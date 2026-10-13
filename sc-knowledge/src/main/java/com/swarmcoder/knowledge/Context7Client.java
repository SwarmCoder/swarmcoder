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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.OutsideLookups;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context7 MCP client for version-accurate library docs (spec §13). Connects lazily on first
 * use and fails QUIETLY when the MCP server is unreachable — Context7 is a best-effort
 * fall-through for {@code lookup_api}, never a hard dependency (the reference folders'
 * own documentation is searched first and works without it).
 *
 * <h2>Two transports, because there are two kinds of server</h2>
 *
 * <p>A LOCAL server ({@code http://localhost:3000/sse}) speaks the older SSE transport and needs
 * no credentials; the MCP SDK handles it. The HOSTED server ({@code https://mcp.context7.com/mcp})
 * speaks Streamable HTTP and requires {@code Authorization: Bearer}. The pinned MCP SDK (0.7.0)
 * can do neither: its SSE transport has no way to set a request header and it has no Streamable
 * HTTP transport at all. Rather than move the SDK version under another module that depends on
 * it, the hosted path is a direct JSON-RPC-over-HTTPS client, which is a few dozen lines and
 * confined to this class.
 *
 * <h2>The key is never in the settings file</h2>
 *
 * <p>It is read from the environment ({@code CONTEXT7_API_KEY}) and passed in here. The settings
 * file is edited through the Console, read back onto a screen, logged and copied around; a
 * credential in it reaches all four. The key is never logged, never echoed and never put in an
 * error message — a rejected key is reported as "rejected", nothing more.
 *
 * <h2>Switched off unless the owner allowed outside lookups</h2>
 *
 * <p>Every call carries words about the project to another host. With {@link OutsideLookups} off
 * this client connects to nothing and answers nothing, whatever the address - a documentation
 * server on this computer included, since such a server passes the question on. With it on, each
 * call is written down before it is made: the tool and its arguments, never the answer.
 */
public class Context7Client {

    private static final Logger log = LoggerFactory.getLogger(Context7Client.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** What the operator needs to know about this client, without ever seeing the key. */
    public enum Posture {
        /** Answering. */
        READY,
        /** No key set — the hosted server needs one. Not an error; the fall-through is just off. */
        NO_KEY,
        /** A key is set and the server refused it. */
        KEY_REJECTED,
        /** Nothing answered at the address. */
        UNREACHABLE,
        /** Outside lookups are off in the settings; nothing is asked and nothing is sent. */
        SWITCHED_OFF
    }

    private final String endpointUrl;
    private final String apiKey;             // never logged, never echoed
    private final boolean operatorConfigured;
    private final boolean hosted;            // Streamable HTTP + bearer, vs local SSE
    private final OutsideLookups lookups;

    private final McpSyncClient mcpClient;   // null for the hosted path
    private final HttpClient http;           // null for the local path
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong requestId = new AtomicLong();
    private final AtomicBoolean connected = new AtomicBoolean();
    private volatile boolean unreachable = false;
    private volatile boolean keyRejected = false;
    /** True once a real call has come back with real content — the key is known good. */
    private volatile boolean credentialProven = false;
    private volatile String sessionId;

    public Context7Client(String sseEndpointUrl) {
        this(sseEndpointUrl, null, true);
    }

    /**
     * @param endpointUrl        where the server is
     * @param apiKey             the bearer key, or null/blank when none is set. Absent is NOT an
     *                           error: a project with good reference folders does not need
     *                           Context7 at all, so a missing key turns the fall-through off and
     *                           says so once, quietly.
     * @param operatorConfigured false when this address is the built-in fallback rather than one
     *                           the operator named. Kept because a default that silently points
     *                           at nothing is how a whole run's worth of missing documentation
     *                           stayed invisible (§32): the address is still tried, but the
     *                           startup report can say "you never named one" instead of "yours
     *                           did not answer" — different problems, different fixes.
     */
    public Context7Client(String endpointUrl, String apiKey, boolean operatorConfigured) {
        this(endpointUrl, apiKey, operatorConfigured, OutsideLookups.on(log::info));
    }

    /**
     * @param lookups the owner's decision on outside lookups. The application always passes it;
     *                the shorter constructors are for callers that name a server deliberately
     *                (tests against an address nothing listens on) and stand for "allowed".
     */
    public Context7Client(String endpointUrl, String apiKey, boolean operatorConfigured,
                          OutsideLookups lookups) {
        this.lookups = lookups == null ? OutsideLookups.off() : lookups;
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
        this.operatorConfigured = operatorConfigured;
        this.hosted = endpointUrl != null && endpointUrl.toLowerCase(java.util.Locale.ROOT)
            .startsWith("https://");
        if (!this.lookups.enabled()) {
            // Switched off: no client of either kind is built, so nothing here can connect.
            this.mcpClient = null;
            this.http = null;
        } else if (hosted) {
            this.mcpClient = null;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        } else {
            this.http = null;
            this.mcpClient = McpClient.sync(new HttpClientSseClientTransport(endpointUrl))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        }
    }

    /** The address being tried. Safe to log — it never contains the key. */
    public String endpointUrl() {
        return endpointUrl;
    }

    /** False when nobody chose this address — see the constructor. */
    public boolean isOperatorConfigured() {
        return operatorConfigured;
    }

    /** Whether a key is set at all. Never reveals its value. */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    /**
     * Whether a question could be sent at all: outside lookups are allowed, and a hosted server
     * has a key. Asked before starting work whose only purpose is to ask. Connects to nothing.
     */
    public boolean canAsk() {
        return !isSwitchedOff() && !(hosted && apiKey == null) && !unreachable && !keyRejected;
    }

    /** True when outside lookups are off: this client will never send anything. */
    public boolean isSwitchedOff() {
        return !lookups.enabled();
    }

    /** The owner's decision this client was built with. */
    public OutsideLookups outsideLookups() {
        return lookups;
    }

    /**
     * What to tell the operator, established by actually asking the server once.
     *
     * <p>A real call is needed, not just a connection. Measured against the live service: a
     * wrong key still completes {@code initialize} with HTTP 200 and is only refused when a tool
     * is called — and even then as a 200 whose content says the key is invalid, not as a 401. A
     * posture based on connecting alone therefore reported a bad key as "ready", which is exactly
     * the kind of confident-and-wrong startup line this whole change exists to remove.
     */
    public Posture posture() {
        if (isSwitchedOff()) {
            return Posture.SWITCHED_OFF;
        }
        if (hosted && apiKey == null) {
            return Posture.NO_KEY;
        }
        if (!connect()) {
            return keyRejected ? Posture.KEY_REJECTED : Posture.UNREACHABLE;
        }
        if (hosted && !credentialProven) {
            // One cheap round trip at project open; the answer is remembered for the process.
            resolveLibrary("jackson", "ObjectMapper");
        }
        if (keyRejected) {
            return Posture.KEY_REJECTED;
        }
        return unreachable ? Posture.UNREACHABLE : Posture.READY;
    }

    /**
     * Whether an answer is the server refusing the credential. Narrow on purpose: it must not
     * catch a document that happens to discuss API keys.
     */
    static boolean isCredentialRejection(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("invalid api key") || lower.contains("unauthorized")
            || lower.contains("missing api key");
    }

    /** Idempotent, best-effort. Returns false (and marks unreachable) if the server is down. */
    public boolean connect() {
        if (isSwitchedOff() || unreachable || keyRejected) {
            return false;
        }
        if (connected.get()) {
            return true;
        }
        try {
            if (hosted) {
                if (apiKey == null) {
                    unreachable = true;   // a hosted server without a key can never answer
                    log.info("Context7 is configured at {} but no API key is set, so published "
                        + "library documentation is not available. Set CONTEXT7_API_KEY to turn "
                        + "it on. Reference folders are unaffected.", endpointUrl);
                    return false;
                }
                initializeHosted();
            } else {
                mcpClient.initialize();
            }
            connected.set(true);
            return true;
        } catch (KeyRejected e) {
            keyRejected = true;
            // The key itself is NEVER included here — only the fact that it was refused.
            log.warn("The documentation server at {} refused the API key (CONTEXT7_API_KEY). "
                + "Published library documentation is unavailable until it is corrected; your "
                + "reference folders are unaffected.", endpointUrl);
            return false;
        } catch (Exception e) {
            unreachable = true;
            log.info("Context7 MCP unavailable ({}); lookup_api will use local sources only",
                e.getMessage());
            return false;
        }
    }

    /** Documentation for a resolved Context7 library id, scoped to what is being asked. */
    public Optional<String> fetchDocs(String libraryId, String query) {
        // Real parameter names, taken from the server's own tool schema. They used to be
        // "library" for a tool that wants "libraryId", so every call failed schema validation
        // and returned nothing — silently, because this client is built to fail quietly.
        return callTool("query-docs", ordered("libraryId", libraryId,
            "query", query == null || query.isBlank() ? libraryId : query));
    }

    /** @deprecated the server requires a query; kept so older call sites still compile. */
    @Deprecated
    public Optional<String> fetchDocs(String libraryId) {
        return fetchDocs(libraryId, libraryId);
    }

    /** Resolves a package name to a Context7 library id. */
    public Optional<String> resolveLibrary(String libraryName, String query) {
        return callTool("resolve-library-id", ordered("libraryName", libraryName,
            "query", query == null || query.isBlank() ? libraryName : query));
    }

    /** @deprecated the server requires a query; kept so older call sites still compile. */
    @Deprecated
    public Optional<String> resolveLibrary(String libraryName) {
        return resolveLibrary(libraryName, libraryName);
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(k1, v1);
        args.put(k2, v2);
        return args;
    }

    /**
     * The candidates in the server's answer to {@code resolve-library-id}: one per entry, its
     * title and its id, in the server's order. Read by the two field labels of the server's own
     * answer format; an entry without an id is not a candidate.
     */
    public static List<DeclaredLibraries.Candidate> candidatesOf(String listing) {
        List<DeclaredLibraries.Candidate> candidates = new ArrayList<>();
        if (listing == null) {
            return candidates;
        }
        String title = "";
        for (String raw : listing.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("-")) {
                line = line.substring(1).strip();
            }
            if (line.startsWith(TITLE_LABEL)) {
                title = line.substring(TITLE_LABEL.length()).strip();
            } else if (line.startsWith(ID_LABEL)) {
                String id = line.substring(ID_LABEL.length()).strip();
                if (!id.isEmpty()) {
                    candidates.add(new DeclaredLibraries.Candidate(title, id));
                }
                title = "";
            }
        }
        return candidates;
    }

    private static final String TITLE_LABEL = "Title:";
    private static final String ID_LABEL = "Context7-compatible library ID:";

    private Optional<String> callTool(String tool, Map<String, Object> arguments) {
        if (isSwitchedOff()) {
            return Optional.empty();
        }
        if ((hosted && apiKey == null) || unreachable || keyRejected) {
            connect();   // says why, once; sends nothing
            return Optional.empty();
        }
        // Written down before it is sent, and whether or not the server then answers: the audit
        // is of what left this computer. The arguments are the tool's own; the key is not one.
        lookups.sent("documentation server", endpointUrl, tool + " " + arguments);
        return transport(tool, arguments);
    }

    /** One tool call on the wire. Protected so a test can stand in for the server. */
    protected Optional<String> transport(String tool, Map<String, Object> arguments) {
        if (!connect()) {
            return Optional.empty();
        }
        try {
            if (hosted) {
                JsonNode result = rpc("tools/call",
                    ordered("name", tool, "arguments", arguments));
                JsonNode content = result == null ? null : result.get("content");
                if (content == null || content.isNull()) {
                    return Optional.empty();
                }
                String text = textOf(content);
                if (isCredentialRejection(text)) {
                    if (!keyRejected) {
                        keyRejected = true;
                        // The key is NEVER in this message — only the fact that it was refused.
                        log.warn("The documentation server at {} refused the key in "
                            + "CONTEXT7_API_KEY. Published library documentation is unavailable "
                            + "until it is corrected; your reference folders are unaffected.",
                            endpointUrl);
                    }
                    return Optional.empty();
                }
                credentialProven = true;
                return text.isBlank() ? Optional.empty() : Optional.of(text);
            }
            CallToolResult result = mcpClient.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
            return Optional.ofNullable(result.content()).map(Object::toString);
        } catch (Exception e) {
            log.debug("Context7 {} failed: {}", tool, e.getMessage());
            return Optional.empty();
        }
    }

    // --- the hosted (Streamable HTTP) transport ------------------------------------------------

    /** Thrown when the server refuses the credential — kept distinct so the message can be exact. */
    private static final class KeyRejected extends RuntimeException {
        KeyRejected() {
            super("credential refused", null, false, false);
        }
    }

    private void initializeHosted() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "swarmcoder", "version", "1.0"));
        rpc("initialize", params);
        notifyHosted();
    }

    private void notifyHosted() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", "notifications/initialized");
        send(json.writeValueAsString(body));
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", requestId.incrementAndGet());
        body.put("method", method);
        body.put("params", params);
        String responseText = send(json.writeValueAsString(body));
        JsonNode envelope = parse(responseText);
        if (envelope == null) {
            return null;
        }
        JsonNode error = envelope.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("MCP error: " + error.path("message").asText());
        }
        return envelope.get("result");
    }

    private String send(String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        String session = this.sessionId;
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        HttpResponse<String> response = http.send(request.build(),
            HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> this.sessionId = id);
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new KeyRejected();
        }
        if (status >= 400) {
            // The body is the server's text, not ours, and it is not echoed to any agent.
            throw new IllegalStateException("documentation server returned HTTP " + status);
        }
        return response.body();
    }

    /**
     * Streamable HTTP answers either as plain JSON or as an SSE frame carrying the same JSON on
     * {@code data:} lines. Both are accepted; anything else parses to null and is treated as a
     * miss, never as a failure.
     */
    private JsonNode parse(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        String text = responseText.strip();
        if (!text.startsWith("{")) {
            StringBuilder data = new StringBuilder();
            for (String line : text.split("\\R")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            text = data.toString();
        }
        try {
            return text.isBlank() ? null : json.readTree(text);
        } catch (Exception e) {
            log.debug("Context7 response was not JSON ({} chars)", text.length());
            return null;
        }
    }

    /** MCP content blocks flattened to their text, which is all the Librarian indexes. */
    private static String textOf(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode text = block.get("text");
            if (text != null && !text.isNull()) {
                sb.append(text.asText()).append('\n');
            }
        }
        return sb.toString().strip();
    }
}
