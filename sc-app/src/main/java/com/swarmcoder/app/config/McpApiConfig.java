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
package com.swarmcoder.app.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The MCP server SwarmCoder ITSELF runs, so an outside agent (Claude Code, an IDE, CI) can see what
 * a run is doing (config block {@code mcpApi}).
 *
 * <p>Not to be confused with {@link McpServerConfig} and the {@code mcpServers} list, which is the
 * opposite direction: servers SwarmCoder calls OUT to, such as Context7.
 *
 * <p><b>Off unless the operator turns it on.</b> A port that opens itself changes what the product
 * exposes, and that is a decision, not a default. When it is on it binds the loopback interface and
 * nothing else — there is deliberately no host setting. Tools that only read need no credential.
 * Every tool that changes something needs the secret kept in the file {@code mcp-secret} beside the
 * settings file, sent as {@code Authorization: Bearer}; see {@code McpSecret}.
 *
 * @param enabled  turn the server on; absent or false means no port is opened
 * @param port     the loopback port; absent means {@value #DEFAULT_PORT}
 * @param readOnly leave out the four tools that change something (start a run, approve, reject,
 *                 answer a question). Absent means false: turning the server on is already the
 *                 deliberate act, and an agent that can see a run but not answer its question is
 *                 half a tool.
 */
public record McpApiConfig(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("port") Integer port,
    @JsonProperty("readOnly") Boolean readOnly
) {
    /** Not near the console's port, and not a port anything common already wants. */
    public static final int DEFAULT_PORT = 8931;

    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public int portOrDefault() {
        return port == null || port <= 0 ? DEFAULT_PORT : port;
    }

    public boolean isReadOnly() {
        return readOnly != null && readOnly;
    }

    /** What an unconfigured machine gets: nothing listening. */
    public static McpApiConfig off() {
        return new McpApiConfig(false, DEFAULT_PORT, false);
    }
}
