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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan is the planner's work. Its lookup session is tagged "planner" on the run's cost record
 * and so is the one-shot reply it falls back to; it used to be tagged "architect", which put the
 * planner's tokens on the architect's line.
 */
class ThePlansOneShotReplyIsTaggedPlannerTest {

    @Test
    void theOneShotPlanIsOnThePlannersLineAndTheDesignStaysOnTheArchitects() throws Exception {
        assertThat(ArchitectClient.meterRole(ArchitectClient.LlmPlan.class)).isEqualTo("planner");
        assertThat(ArchitectClient.meterRole(ArchitectClient.LlmDesign.class)).isEqualTo("architect");

        RunMeter.enable();
        RunMeter.reset();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{\"tasks\":[],\"edges\":[]}")) {
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(0, null));
            DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "a goal", List.of(),
                List.of(), List.of(), List.of(), null, Instant.now());

            architect.plan(design, "a goal");

            assertThat(RunMeter.calls()).as("the plan call was recorded").isNotEmpty();
            assertThat(RunMeter.calls()).extracting(RunMeter.Call::role).containsOnly("planner");
        } finally {
            RunMeter.disable();
        }
    }
}
