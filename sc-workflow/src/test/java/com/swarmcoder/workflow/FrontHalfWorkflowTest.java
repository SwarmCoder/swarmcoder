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
import com.swarmcoder.domain.ReviewVerdict;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.domain.SwarmPolicy;
import java.util.List;

/**
 * M3 front half over scripted role responses: DESIGN produces a persisted DesignDocument,
 * DESIGN_REVIEW loops once on objections and records the verdict, PLAN yields a TaskGraph
 * with write sets and acceptance criteria.
 */
class FrontHalfWorkflowTest {

    @TempDir
    Path storeDir;

    private static final String DESIGN_JSON = """
        {"requirements":[{"text":"multiply two ints","priority":"HIGH"}],
         "decisions":[{"decision":"pure function","rationale":"stateless"}],
         "contracts":[{"name":"Calculator.multiply","description":"product","signature":"int multiply(int,int)"}],
         "risks":[{"description":"overflow","severity":"LOW","mitigation":"document"}]}
        """;

    private static final String PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Implement multiply","instructions":"add multiply",
          "writeSet":["src/main/java/com/example/calc"],"readSet":["src"],
          "criteria":[{"text":"multiply(3,4)==12","testClassOrFile":"swarm.accept.MultiplyAcceptTest"}]}],
         "edges":[]}
        """;

    @Test
    void designReviewPlanProduceTypedArtifacts() throws Exception {
        AtomicInteger reviewCalls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("design reviewer")) {
                    return reviewCalls.incrementAndGet() == 1
                        ? "{\"approved\": false, \"objections\": [\"no overflow contract\"]}"
                        : "{\"approved\": true, \"objections\": []}";
                }
                if (conversation.contains("revising a design")) {
                    return DESIGN_JSON;
                }
                if (conversation.contains("software architect")) {
                    return DESIGN_JSON;
                }
                if (conversation.contains("AI planner")) {
                    return PLAN_JSON;
                }
                return "{}";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {

            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            AgentRuntime unused = spec -> {
                throw new UnsupportedOperationException();
            };
            WorkflowEngine engine = new WorkflowEngine(unused, run -> run, client, store,
                new CloudGate(1_000_000, null), null,
                CloudRoles.allOn(client, new CloudGate(1_000_000, null)), null);

            UUID runId = UUID.randomUUID();
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                null, null, null, null, null, Instant.now(),
                new RunReport(runId, "add multiply to Calculator")));

            // DELIVERED, not APPROVAL: the build finishes on its own (UX v3 2.3).
            awaitState(store, runId, RunState.DELIVERED);

            Run run = store.root().runs.get(runId);
            DesignDocument design = store.root().designs.get(run.designId());
            assertThat(design).isNotNull();
            assertThat(design.requirements()).hasSize(1);
            assertThat(design.contracts().get(0).name()).isEqualTo("Calculator.multiply");
            assertThat(design.review()).isEqualTo(ReviewVerdict.APPROVED);
            assertThat(design.revision()).as("one revision loop ran").isEqualTo(2);

            TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
            assertThat(graph.tasks()).hasSize(1);
            assertThat(graph.tasks().get(0).writeSet()).contains("src/main/java/com/example/calc");
            assertThat(graph.tasks().get(0).criteria()).hasSize(1);
            assertThat(graph.tasks().get(0).criteria().get(0).testClassOrFile())
                .isEqualTo("swarm.accept.MultiplyAcceptTest");
            assertThat(reviewCalls.get()).isEqualTo(2);
        }
    }

    @Test
    void plannedTasksCarryTheConfiguredSwarmPolicy() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> PLAN_JSON)) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            SwarmPolicy policy =
                new SwarmPolicy(10, true, 0.1, 0.8, List.of("minimal-diff"));
            ArchitectClient architect =
                new ArchitectClient(client, new CloudGate(1_000_000, null), policy);

            TaskGraph graph = architect.plan(null, "add multiply to Calculator");

            assertThat(graph.tasks()).isNotEmpty();
            SwarmPolicy stamped = graph.tasks().get(0).swarmPolicy();
            assertThat(stamped.n()).isEqualTo(10);
            assertThat(stamped.splitAcrossFamilies()).isTrue();
            assertThat(stamped.tempMin()).isEqualTo(0.1);
            assertThat(stamped.tempMax()).isEqualTo(0.8);

            // Without an injected policy the architect must never swarm by surprise.
            TaskGraph defaulted = new ArchitectClient(client, new CloudGate(1_000_000, null))
                .plan(null, "add multiply to Calculator");
            assertThat(defaulted.tasks().get(0).swarmPolicy().n()).isEqualTo(1);
            assertThat(defaulted.tasks().get(0).swarmPolicy().splitAcrossFamilies()).isFalse();
        }
    }

    private static void awaitState(ArtifactStore store, UUID runId, RunState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.state() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        Run last = store.root().runs.get(runId);
        throw new AssertionError("Run never reached " + expected + "; last: "
            + (last == null ? "never persisted" : last.state()));
    }
}
