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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The door stays shut unless somebody opens it.
 *
 * <p>The MCP server has no password on it. Everything that keeps it safe is the two facts asserted
 * here — it is off unless the settings file turns it on, and the settings file has no way to ask
 * for a non-loopback address — plus the transport binding, which
 * {@code SwarmMcpServerTest} proves against a real socket. A default that drifted to true would
 * open a port on every machine that has ever run SwarmCoder, silently.
 */
class McpApiConfigTest {

    @Test
    void aSettingsFileThatSaysNothingOpensNoPort() {
        SwarmConfig silent = new SwarmConfig(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);

        assertThat(silent.mcpApi().isEnabled())
            .describedAs("no mcpApi block means no port, on every machine that has ever run this")
            .isFalse();
        assertThat(McpApiConfig.off().isEnabled()).isFalse();
    }

    @Test
    void anEmptyBlockIsStillOff() {
        assertThat(new McpApiConfig(null, null, null).isEnabled())
            .describedAs("writing 'mcpApi:' and nothing under it is not consent")
            .isFalse();
    }

    @Test
    void turningItOnGetsTheDefaultPortAndTheFullToolset() {
        McpApiConfig on = new McpApiConfig(true, null, null);

        assertThat(on.isEnabled()).isTrue();
        assertThat(on.portOrDefault()).isEqualTo(McpApiConfig.DEFAULT_PORT);
        assertThat(on.isReadOnly())
            .describedAs("turning the server on is already the deliberate act; an agent that can "
                + "see a run but not answer its question is half a tool")
            .isFalse();
    }

    @Test
    void readOnlyIsAvailableForSomebodyWhoWantsLookingWithoutTouching() {
        assertThat(new McpApiConfig(true, 9000, true).isReadOnly()).isTrue();
        assertThat(new McpApiConfig(true, 9000, true).portOrDefault()).isEqualTo(9000);
    }

    @Test
    void theStarterFileExplainsTheDoorAndLeavesItShut() {
        String starter = ConfigLoader.starterConfigText();

        assertThat(starter)
            .describedAs("a newcomer is told what it is and how to switch it on")
            .contains("THIS MACHINE ONLY")
            .describedAs("and told to use the transport that survives SwarmCoder restarting; "
                + "the event-stream one loses every tool the moment this process stops")
            .contains("claude mcp add --transport http swarmcoder")
            .contains("/mcp");
        assertThat(starter)
            .describedAs("but every mcpApi line is commented out, so nothing is listening")
            .doesNotContain("\nmcpApi:")
            .contains("# mcpApi:")
            .contains("#   enabled: true");
    }
}
