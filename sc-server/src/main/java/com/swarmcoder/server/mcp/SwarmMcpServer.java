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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * SwarmCoder's own MCP server: how an outside agent — Claude Code, an IDE, CI — sees what a run is
 * doing and, if allowed, acts on it.
 *
 * <p><b>Why this exists at all.</b> DEVELOPER_CORRECTIONS.md §9 item S7 deferred MCP on 2026-07-12
 * with one condition: build it when a concrete EXTERNAL consumer appears, and build it then as a
 * thin adapter over the Console's own surfaces. That consumer appeared on 2026-08-28, when a
 * nine-story run went wrong overnight and the only way to find out why was to read a log file by
 * hand. §32 records the reversal. The ACP half stays dropped.
 *
 * <p><b>Off by default, and only ever on loopback.</b> There is no setting that binds this to an
 * outside interface. The Console's own security note (OBSERVABILITY_DESIGN.md) says auth is out of
 * scope until the observer leaves the workstation — so it does not leave the workstation. Opening
 * a port changes what the product exposes, and that has to be the operator's decision, made once,
 * in the settings file.
 *
 * <p><b>It owns no data.</b> Every answer comes from {@link ObserverService}, {@link GraphService}
 * and {@link ControlService}, the same three the Console reads, so the Console and an outside agent
 * cannot disagree about what is true.
 */
public final class SwarmMcpServer {

    private static final Logger log = LoggerFactory.getLogger(SwarmMcpServer.class);

    /** How the server names itself in the MCP handshake. */
    public static final String SERVER_NAME = "swarmcoder";

    private final LoopbackHttpTransport transport;
    private final SwarmMcpTools tools;
    private McpSyncServer server;

    /**
     * @param readOnly when true the four tools that change something are not offered at all — the
     *                 caller cannot start a run, approve or reject one, or answer a question
     */
    public SwarmMcpServer(ObserverService observer, GraphService graph, ControlService control,
                          int port, String basePath, boolean readOnly) {
        this.transport = new LoopbackHttpTransport(basePath, port);
        this.tools = new SwarmMcpTools(observer, graph, control, readOnly);
    }

    /**
     * Binds the loopback port and starts answering.
     *
     * <p>Returns the <b>Streamable HTTP</b> URL, because that is the one an operator should point a
     * client at: a tool call over it is one self-contained HTTP request, so the client keeps
     * working when SwarmCoder is restarted underneath it. The deprecated event-stream URL is still
     * served, and still logged, for anything already pointed at it — but a client on that one loses
     * every SwarmCoder tool the moment this process stops, and only restarting the client gets them
     * back.
     *
     * @return the URL to give the MCP client
     */
    public String start() throws IOException {
        int bound = transport.start();
        server = McpServer.sync(transport)
            .serverInfo(SERVER_NAME, "1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools.registrations().toArray(
                new io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration[0]))
            .build();
        String url = "http://127.0.0.1:" + bound + transport.basePathForClients() + "/mcp";
        log.info("MCP server listening on {} (loopback only). Connect with: "
            + "claude mcp add --transport http {} {}", url, SERVER_NAME, url);
        log.info("MCP: the deprecated event-stream transport is still served at {}, but a client "
            + "using it stops working when SwarmCoder restarts.", sseUrl());
        return url;
    }

    /** The deprecated 2024-11-05 event-stream URL, still served beside the modern one. */
    public String sseUrl() {
        return "http://127.0.0.1:" + transport.port() + transport.basePathForClients() + "/sse";
    }

    /** The bound port, or -1 before {@link #start()}. */
    public int port() {
        return transport.port();
    }

    /** The address actually bound — always a loopback address. */
    public java.net.InetAddress boundAddress() {
        return transport.boundAddress();
    }

    public void stop() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception e) {
            log.debug("MCP: server close failed: {}", e.toString());
        }
        transport.close();
    }
}
