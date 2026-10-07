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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code tools:} block of the settings file: where the Java language server is. */
class ToolsConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theSettingIsReadAndAFileWithoutTheBlockSaysNothing() throws Exception {
        SwarmConfig set = YAML.readValue("""
            consolePort: 9090
            tools:
              jdtLsHome: 'D:/tools/jdt ls'
            """, SwarmConfig.class);
        SwarmConfig unset = YAML.readValue("consolePort: 9090", SwarmConfig.class);

        assertThat(set.jdtLsHome()).isEqualTo("D:/tools/jdt ls");
        assertThat(unset.jdtLsHome()).isNull();
        assertThat(set.withBudgets(null).jdtLsHome()).as("kept when the file is written back")
            .isEqualTo("D:/tools/jdt ls");
    }
}
