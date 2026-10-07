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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A registry of user-configured MCP servers (author requirement 2026-07-14): the operator
 * adds servers in config ({@code mcpServers}) and the Researcher can list and call their
 * tools. Each server connects lazily and degrades quietly when unreachable — MCP is an
 * enhancer, never a hard dependency (the built-in web + local sources work without any
 * server running). This generalizes the single-purpose {@link Context7Client}.
 */
public final class McpServers {

    /** One configured server (name is how agents address it). */
    public record Server(String name, String url) {}

    private static final Logger log = LoggerFactory.getLogger(McpServers.class);

    private final Map<String, Connection> connections = new LinkedHashMap<>();

    private static final class Connection {
        final Server server;
        final McpSyncClient client;
        final AtomicBoolean connected = new AtomicBoolean();
        volatile boolean unreachable;

        Connection(Server server) {
            this.server = server;
            this.client = McpClient.sync(new HttpClientSseClientTransport(server.url()))
                .requestTimeout(Duration.ofSeconds(15))
                .build();
        }

        boolean connect() {
            if (unreachable) {
                return false;
            }
            if (connected.get()) {
                return true;
            }
            try {
                client.initialize();
                connected.set(true);
                return true;
            } catch (Exception e) {
                unreachable = true;
                log.info("MCP server '{}' ({}) unavailable: {}", server.name(), server.url(), e.getMessage());
                return false;
            }
        }
    }

    public McpServers(List<Server> servers) {
        for (Server server : servers == null ? List.<Server>of() : servers) {
            if (server != null && server.name() != null && server.url() != null) {
                connections.put(server.name(), new Connection(server));
            }
        }
    }

    public boolean isEmpty() {
        return connections.isEmpty();
    }

    /** All reachable servers' tools as "server.tool — description" lines (for the agent prompt). */
    public String listTools() {
        StringBuilder sb = new StringBuilder();
        for (Connection connection : connections.values()) {
            if (!connection.connect()) {
                continue;
            }
            try {
                for (McpSchema.Tool tool : connection.client.listTools().tools()) {
                    sb.append(connection.server.name()).append('.').append(tool.name());
                    if (tool.description() != null) {
                        sb.append(" — ").append(tool.description());
                    }
                    sb.append('\n');
                }
            } catch (Exception e) {
                log.debug("listTools failed for '{}': {}", connection.server.name(), e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Calls {@code server.tool} with {@code arguments} (parsed as {@code key=value} pairs or a
     * bare value under the "query" key). Returns the result text or an error string.
     */
    public String call(String serverAndTool, String arguments) {
        int dot = serverAndTool == null ? -1 : serverAndTool.indexOf('.');
        if (dot <= 0) {
            return "error: address the tool as <server>.<tool> (servers: "
                + String.join(", ", connections.keySet()) + ")";
        }
        Connection connection = connections.get(serverAndTool.substring(0, dot));
        if (connection == null) {
            return "error: no configured MCP server '" + serverAndTool.substring(0, dot) + "'";
        }
        if (!connection.connect()) {
            return "error: MCP server '" + connection.server.name() + "' is unreachable";
        }
        try {
            McpSchema.CallToolResult result = connection.client.callTool(
                new McpSchema.CallToolRequest(serverAndTool.substring(dot + 1), parseArgs(arguments)));
            return result.content() == null ? "(no content)" : result.content().toString();
        } catch (Exception e) {
            return "error: MCP call failed: " + e.getMessage();
        }
    }

    private static Map<String, Object> parseArgs(String arguments) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return args;
        }
        if (!arguments.contains("=")) {
            args.put("query", arguments.strip());
            return args;
        }
        for (String pair : arguments.split("\\s+")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                args.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return args;
    }

    static List<Server> fromCoordinates(List<String> nameUrlPairs) {
        List<Server> servers = new ArrayList<>();
        for (String pair : nameUrlPairs) {
            int at = pair.indexOf('@');
            if (at > 0) {
                servers.add(new Server(pair.substring(0, at), pair.substring(at + 1)));
            }
        }
        return servers;
    }
}
