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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swarmcoder.app.EnvironmentChecks;
import com.swarmcoder.knowledge.Context7Client;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing leaves the computer for a lookup unless the settings file says so (section 90).
 *
 * <p>The default is the whole point: a settings file written before the switch existed, or one
 * that says nothing about it, must mean "off". A default that drifted to "on" would send words
 * from a private project to other hosts on every machine, silently.
 */
class OutsideLookupsSettingTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void aSettingsFileThatSaysNothingAllowsNothing() throws Exception {
        assertThat(YAML.readValue("repoPath: /work/project\n", SwarmConfig.class)
            .outsideLookupsAllowed()).isFalse();
        assertThat(YAML.readValue("outsideLookups: false\n", SwarmConfig.class)
            .outsideLookupsAllowed()).isFalse();
        assertThat(new SwarmConfig(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null).outsideLookupsAllowed())
            .describedAs("the shape from before the switch existed")
            .isFalse();
    }

    @Test
    void onlyTheWordTrueAllowsThem() throws Exception {
        SwarmConfig allowed = YAML.readValue("outsideLookups: true\n", SwarmConfig.class);

        assertThat(allowed.outsideLookupsAllowed()).isTrue();
        assertThat(allowed.withBudgets(null).outsideLookupsAllowed())
            .describedAs("a copy of the settings keeps the owner's decision")
            .isTrue();
    }

    @Test
    void aProjectIsToldOnceThatPublishedDocumentationIsNotAskedFor() {
        List<String> lines = EnvironmentChecks.reportKnowledgeSources("demo", List.of(), 0,
            Context7Client.Posture.SWITCHED_OFF, "https://docs.example.com/mcp");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("outside lookups are off")
            .contains("nothing is sent to https://docs.example.com/mcp")
            .contains("`outsideLookups: true`");
    }
}
