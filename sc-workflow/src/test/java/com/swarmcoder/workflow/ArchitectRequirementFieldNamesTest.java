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
package com.swarmcoder.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.inference.LlmJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 49, 2026-09-30: design review objected "REQ entries are all null" and earlier runs
 * rendered the design's requirements as "[MEDIUM] null" — the model wrote the requirement's text
 * under another field name than the {@code text} the prompt asks for, and unknown fields are
 * ignored.
 */
class ArchitectRequirementFieldNamesTest {

    @Test
    void aRequirementWrittenUnderAnotherFieldNameStillGetsItsText() throws Exception {
        String reply = "{\"requirements\":["
            + "{\"text\":\"plain\",\"priority\":\"HIGH\"},"
            + "{\"requirement\":\"first\",\"importance\":\"LOW\"},"
            + "{\"description\":\"second\",\"priority\":\"MEDIUM\"},"
            + "{\"statement\":\"third\"},{\"name\":\"fourth\"}],"
            + "\"decisions\":[],\"contracts\":[],\"risks\":[]}";

        ArchitectClient.LlmDesign parsed =
            LlmJson.parse(new ObjectMapper(), reply, ArchitectClient.LlmDesign.class);

        assertThat(parsed.requirements).extracting(r -> r.text)
            .containsExactly("plain", "first", "second", "third", "fourth");
        assertThat(parsed.requirements).extracting(r -> r.priority)
            .containsExactly("HIGH", "LOW", "MEDIUM", null, null);
    }
}
