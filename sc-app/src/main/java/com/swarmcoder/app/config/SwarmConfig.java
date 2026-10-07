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
import java.util.Map;
import java.util.List;

public record SwarmConfig(
    @JsonProperty("repoPath") String repoPath,
    @JsonProperty("contextPaths") List<String> contextPaths,
    @JsonProperty("protectedPaths") List<String> protectedPaths,
    @JsonProperty("consolePort") Integer consolePort,
    @JsonProperty("spark") SparkConfig spark,
    @JsonProperty("cloud") CloudConfig cloud,
    @JsonProperty("roles") RolesConfig roles,
    @JsonProperty("swarm") SwarmEngineConfig swarm,
    @JsonProperty("budgets") BudgetsConfig budgets,
    @JsonProperty("guidelines") GuidelinesConfig guidelines,
    @JsonProperty("sandbox") SandboxConfig sandbox,
    @JsonProperty("overnight") OvernightConfig overnight,
    @JsonProperty("telemetry") TelemetryConfig telemetry,
    @JsonProperty("mcpServers") List<McpServerConfig> mcpServers,
    @JsonProperty("mcpApi") McpApiConfig mcpApi,
    @JsonProperty("tools") ToolsConfig tools
) {
    /** The shape before the {@code tools:} block existed. */
    public SwarmConfig(String repoPath, List<String> contextPaths, List<String> protectedPaths,
                       Integer consolePort, SparkConfig spark, CloudConfig cloud, RolesConfig roles,
                       SwarmEngineConfig swarm, BudgetsConfig budgets, GuidelinesConfig guidelines,
                       SandboxConfig sandbox, OvernightConfig overnight, TelemetryConfig telemetry,
                       List<McpServerConfig> mcpServers, McpApiConfig mcpApi) {
        this(repoPath, contextPaths, protectedPaths, consolePort, spark, cloud, roles, swarm,
            budgets, guidelines, sandbox, overnight, telemetry, mcpServers, mcpApi, null);
    }

    /** Where the Java language server is, as the file says; null when it does not. */
    public String jdtLsHome() {
        return tools == null ? null : tools.jdtLsHome();
    }

    /** Read-only context folders (e.g. a shared library like zeroz4j); never empty-null-safe. */
    public List<String> contextPaths() {
        return contextPaths == null ? List.of() : contextPaths;
    }
    /**
     * Locked-down modules: repo-relative folders/files NO worker may modify, whatever its task's
     * write set says. Enforced mechanically by {@code PathPolicy} — in the worker's tools and
     * again against the winning diff at integration — not by asking the model nicely. Null-safe.
     */
    public List<String> protectedPaths() {
        return protectedPaths == null ? List.of() : protectedPaths;
    }
    /**
     * Context7's hosted documentation server. It replaced a built-in {@code localhost:3000}
     * address that nobody ran and nothing was listening on, so every documentation lookup failed
     * quietly and workers went off to decompile libraries instead (§32). The hosted service needs
     * a key, which is read from the environment variable {@code CONTEXT7_API_KEY} and never from
     * this file.
     */
    public static final String DEFAULT_CONTEXT7_URL = "https://mcp.context7.com/mcp";

    /** Configured MCP servers, defaulting to the hosted Context7 when unset (null-safe). */
    public List<McpServerConfig> mcpServers() {
        return mcpServers == null
            ? List.of(new McpServerConfig("context7", DEFAULT_CONTEXT7_URL, true))
            : mcpServers;
    }
    /**
     * The MCP server SwarmCoder exposes, defaulting to OFF (null-safe). Not {@link #mcpServers()},
     * which is the list of servers it calls out to.
     */
    public McpApiConfig mcpApi() {
        return mcpApi == null ? McpApiConfig.off() : mcpApi;
    }

    /**
     * Whether the settings file actually NAMES a documentation server, as opposed to the default
     * above standing in for one. The distinction exists because that default silently pointed at
     * a port nothing was listening on, so every documentation lookup failed, nothing said so, and
     * a whole run of workers spent its budget decompiling a library instead (§32).
     */
    public boolean hasConfiguredMcpServers() {
        return mcpServers != null && !mcpServers.isEmpty();
    }
    public SwarmConfig withCloud(CloudConfig newCloud) {
        return new SwarmConfig(repoPath, contextPaths, protectedPaths, consolePort, spark, newCloud, roles, swarm, budgets, guidelines, sandbox, overnight, telemetry, mcpServers, mcpApi, tools);
    }
    public SwarmConfig withRoles(RolesConfig newRoles) {
        return new SwarmConfig(repoPath, contextPaths, protectedPaths, consolePort, spark, cloud, newRoles, swarm, budgets, guidelines, sandbox, overnight, telemetry, mcpServers, mcpApi, tools);
    }
    public SwarmConfig withBudgets(BudgetsConfig newBudgets) {
        return new SwarmConfig(repoPath, contextPaths, protectedPaths, consolePort, spark, cloud, roles, swarm, newBudgets, guidelines, sandbox, overnight, telemetry, mcpServers, mcpApi, tools);
    }
}
