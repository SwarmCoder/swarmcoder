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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch for everything sent to a host that is not a model endpoint or this computer
 * (section 90): off until allowed, every sending written down when on, and "this computer"
 * decided from the address as written.
 */
class OutsideLookupsSwitchTest {

    @Test
    void theProcessIsOffUntilTheApplicationSaysOtherwise() {
        assertThat(OutsideLookups.process().enabled()).isFalse();
        assertThat(OutsideLookups.off().enabled()).isFalse();
    }

    @Test
    void offNothingMaySendAndTheStartLineSaysSo() {
        OutsideLookups off = OutsideLookups.off();

        assertThat(off.sent("documentation server", "https://docs.example.com/mcp", "words"))
            .isFalse();
        assertThat(off.sentSoFar()).isEmpty();
        assertThat(off.startLine(List.of("the documentation server (docs.example.com)",
            "web search"), "config.yaml"))
            .startsWith("Outside lookups are off: nothing is sent to the documentation server "
                + "(docs.example.com); web search.")
            .contains("only the model endpoints in your settings and this computer itself")
            .contains("`outsideLookups: true` in config.yaml");
    }

    @Test
    void onEachSendingIsWrittenDownWithItsHostAndItsWords() {
        List<String> log = new ArrayList<>();
        OutsideLookups on = OutsideLookups.on(log::add);

        assertThat(on.sent("documentation server", "https://docs.example.com/mcp?x=1",
            "resolve-library-id {libraryName=jackson-databind}")).isTrue();

        assertThat(log).containsExactly("Outside lookup sent to docs.example.com (documentation "
            + "server): resolve-library-id {libraryName=jackson-databind}");
        assertThat(on.sentSoFar()).isEqualTo(log);
        assertThat(on.startLine(List.of("web search"), "config.yaml"))
            .startsWith("Outside lookups are ON");
    }

    @Test
    void thisComputerIsDecidedFromTheAddressAsWritten() {
        assertThat(OutsideLookups.isLoopback("http://localhost:8080/app")).isTrue();
        assertThat(OutsideLookups.isLoopback("http://app.localhost:3000")).isTrue();
        assertThat(OutsideLookups.isLoopback("http://127.0.0.1:9090/")).isTrue();
        assertThat(OutsideLookups.isLoopback("ws://127.4.5.6/socket")).isTrue();
        assertThat(OutsideLookups.isLoopback("http://[::1]:8080/")).isTrue();

        assertThat(OutsideLookups.isLoopback("https://cdn.example.com/script.js")).isFalse();
        assertThat(OutsideLookups.isLoopback("http://localhost.example.com/")).isFalse();
        assertThat(OutsideLookups.isLoopback("http://127.0.0.1.example.com/")).isFalse();
        assertThat(OutsideLookups.isLoopback("http://128.0.0.1/")).isFalse();
        assertThat(OutsideLookups.isLoopback("http://203.0.113.7/")).isFalse();
        assertThat(OutsideLookups.isLoopback("")).isFalse();
    }
}
