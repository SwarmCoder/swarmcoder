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
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 67, 2026-10-02: two planner calls in a row failed with "the reply had no tasks" and
 * nothing recorded what was sent. A reply whose tasks are there in another shape is now read.
 */
class APlanReplyInAnotherShapeStillHasItsTasksTest {

    private static final String TASK_A = """
        {"id":"A","title":"Shared types","instructions":"Create Contact","writeSet":["shared/src/main/java"],
         "criteria":[{"text":"contact exists","testClassOrFile":"ContactTest"}]}""";
    private static final String TASK_B = """
        {"id":"B","title":"Service","instructions":"Create the service","writeSet":["server/src/main/java"],
         "criteria":[{"text":"service adds","testClassOrFile":"ServiceTest"}]}""";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aBareArrayOfTasksIsThePlan() {
        ArchitectClient.LlmPlan plan = ArchitectClient.recoverPlan(mapper,
            "```json\n[" + TASK_A + "," + TASK_B + "]\n```\nThat is the plan.");

        assertThat(plan).isNotNull();
        assertThat(plan.tasks).extracting(t -> t.title).containsExactly("Shared types", "Service");
    }

    @Test
    void tasksUnderAnotherKeyOrInsideAWrapperAreFoundWithTheirEdges() {
        ArchitectClient.LlmPlan wrapped = ArchitectClient.recoverPlan(mapper,
            "{\"plan\":{\"tasks\":[" + TASK_A + "," + TASK_B + "],"
                + "\"edges\":[{\"from\":\"A\",\"to\":\"B\"}]}}");
        assertThat(wrapped.tasks).hasSize(2);
        assertThat(wrapped.edges).hasSize(1);

        ArchitectClient.LlmPlan otherKey = ArchitectClient.recoverPlan(mapper,
            "{\"taskGraph\":[" + TASK_A + "],\"dependencies\":[]}");
        assertThat(otherKey.tasks).extracting(t -> t.id).containsExactly("A");
    }

    @Test
    void aReplyWithNoTaskInItIsNotInventedIntoOne() {
        assertThat(ArchitectClient.recoverPlan(mapper, "{\"tasks\":[],\"edges\":[]}")).isNull();
        assertThat(ArchitectClient.recoverPlan(mapper, "{\"note\":\"cannot plan this\"}")).isNull();
        assertThat(ArchitectClient.recoverPlan(mapper, "I cannot plan this.")).isNull();
    }

    @Test
    void thePlannerCallAcceptsABareArray() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "[" + TASK_A + "," + TASK_B + "]")) {
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), null, "test-model", true),
                new CloudGate(1_000_000, null));

            TaskGraph graph = architect.plan(null, "Add a contact");

            assertThat(graph).isNotNull();
            assertThat(graph.tasks()).hasSize(2);
        }
    }
}
