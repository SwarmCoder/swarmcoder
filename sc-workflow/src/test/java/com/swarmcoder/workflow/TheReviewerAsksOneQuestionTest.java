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

import com.swarmcoder.domain.ArchDecision;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.ReviewerMode;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.CloudGate.Where;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design reviewer asks ONE QUESTION a reading, with no tools, by default (section 96).
 * No model is called: the endpoint is scripted.
 */
class TheReviewerAsksOneQuestionTest {

    private static final String RULES = "RULE 'No hand-built services' [HARD]: a test never "
        + "constructs a service by hand.";

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    @AfterEach
    void backToTheDefault() {
        ReviewerMode.configure(null);
    }

    private static DesignDocument design() {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "add a book", List.of(),
            List.of(new ArchDecision(UUID.randomUUID(), "inject the service", "", List.of())),
            List.of(), List.of(), null, Instant.EPOCH);
        design.setFindings(List.of(new DesignFinding(UUID.randomUUID(), "BookService",
            "public_shape BookService", "the service takes its database in its constructor",
            "public BookService(Db db)")));
        return design;
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1L, "T", "Inject the service.", Set.of(), Set.of(),
            List.of(), null, null, null, null, TaskState.PENDING);
    }

    private LookupAgent agent(CloudGate gate) {
        return new LookupAgent(new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache),
            null, repo, gate, new KoogAgentRuntime(), null, null);
    }

    private static ScriptedAgentLlm llm() throws Exception {
        return new ScriptedAgentLlm(
            conversation -> "{\"approved\": true, \"objections\": []}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                Map.of("approved", "true", "objections", "")));
    }

    @Test
    void byDefaultEachReadingIsOneCallWithNoToolsAndTheArchitectsFactsAreInIt() throws Exception {
        try (ScriptedAgentLlm llm = llm()) {
            CloudGate gate = new CloudGate(0, null);
            DesignReviewerClient reviewer = new DesignReviewerClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), gate);
            // Wired as the application and the harness wire it, with an agent at hand.
            reviewer.useMode(ReviewerMode.configure(null), agent(gate));

            var designReading = reviewer.reviewDesign(RULES, design());
            var planReading = reviewer.reviewPlan(RULES, List.of(task()));

            assertThat(designReading.approved).isTrue();
            assertThat(planReading.approved).isTrue();
            assertThat(llm.sessionRequests).as("no tools were offered, no session opened")
                .isEmpty();
            assertThat(llm.oneReplyRequests).as("one call for each reading").hasSize(2);
            assertThat(llm.oneReplyRequests.get(0))
                .contains(RULES)
                .contains(DesignReviewerClient.FACTS_HEADING.strip())
                .contains("the service takes its database in its constructor")
                .contains("(from public_shape BookService)")
                .contains("inject the service");
            assertThat(llm.oneReplyRequests.get(1)).contains("The plan's tasks:");
        }
    }

    @Test
    void lookupsModeIsTheAgentOfSection94() throws Exception {
        try (ScriptedAgentLlm llm = llm()) {
            CloudGate gate = new CloudGate(0, null);
            DesignReviewerClient reviewer = new DesignReviewerClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), gate);
            reviewer.useMode(ReviewerMode.configure("lookups"), agent(gate));
            try (var in = CloudGate.enter(
                    new Where(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))) {
                reviewer.reviewDesign(RULES, design());
            }

            assertThat(llm.oneReplyRequests).isEmpty();
            assertThat(llm.sessionRequests).hasSize(1);
            assertThat(llm.sessionRequests.get(0))
                .contains(DesignReviewerClient.FACTS_HEADING.strip())
                .contains("Look a fact up only when an objection you are about to raise depends "
                    + "on it");
        }
    }
}
