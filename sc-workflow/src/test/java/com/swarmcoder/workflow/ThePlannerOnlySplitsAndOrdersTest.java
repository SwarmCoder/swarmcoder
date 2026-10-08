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

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task planner splits a design into tasks and orders them, and nothing else (owner's
 * decision, 2026-10-08; section 73): it is told to write what a task delivers and not how, it is
 * sent no framework reference and no example code, it reads the architect's findings as one line
 * each, and it can be put on a model of its own while the architect keeps a stronger one.
 */
class ThePlannerOnlySplitsAndOrdersTest {

    private static final String PLAN = """
        {"tasks":[{"id":"A","title":"Order service",
          "instructions":"Delivers OrderService and check R1:C1; the screen is a later task.",
          "writeSet":[],"readSet":[],"criterionRefs":["R1:C1"],
          "deliversContracts":["OrderService"]}],
         "edges":[]}
        """;

    private static final ArchitectResearch FULL_OF_MATERIAL = new ArchitectResearch() {
        @Override public String reference(int maxChars) { return "PRIMER: how the ledger works"; }
        @Override public String lookupApi(String query) { return ""; }
        @Override public String searchCode(String query) { return ""; }
        @Override public String readFile(String address) { return ""; }
        @Override public String listFolder(String address) { return ""; }
        @Override public String examples(List<ApiContract> contracts, String goal, int maxChars) {
            return "class LedgerStoreExample { }";
        }
    };

    private static DesignDocument design() {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "orders", List.of(),
            List.of(), List.of(new ApiContract(UUID.randomUUID(), "OrderService", "NEW: orders",
                "interface OrderService", "com.shop.OrderService",
                List.of("void place(String id)"))), List.of(), null, null);
        design.setFindings(List.of(new DesignFinding(UUID.randomUUID(), "OrderService",
            "body_of com.ledgerworks.LedgerStore", "A service is found by @Service.",
            "@Service\nclass LedgerStore {}")));
        return design;
    }

    private static StoryScope scope() {
        return new StoryScope(null, List.of(), List.of(new AcceptanceCriterion(
            UUID.randomUUID(), "an order can be placed", "swarm.accept.OrderTest")),
            List.of("R1:C1"), List.of(), "");
    }

    @Test
    void itIsToldToSayWhatATaskDeliversAndIsSentNoFrameworkMaterial() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompts.add(conversation);
            return PLAN;
        })) {
            ArchitectClient planner = new ArchitectClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null),
                null, FULL_OF_MATERIAL);

            TaskGraph graph = planner.plan(design(), "Take orders", scope(), "");

            assertThat(prompts).hasSize(1);
            assertThat(prompts.get(0))
                .contains("YOU SPLIT AND ORDER; YOU DO NOT SAY HOW")
                .contains("say WHAT the task delivers")
                .contains("YOU DO NOT HAVE TO WORK OUT A TASK'S FILES")
                .as("the architect's findings, one line each and without their code")
                .contains("FACT [OrderService] A service is found by @Service. "
                    + "(from body_of com.ledgerworks.LedgerStore)")
                .doesNotContain("class LedgerStore {}")
                .as("no framework reference and no example code")
                .doesNotContain("PRIMER:").doesNotContain("FRAMEWORK REFERENCE")
                .doesNotContain("WORKED EXAMPLES").doesNotContain("LedgerStoreExample")
                .as("and no longer told to make instructions agree with example code")
                .doesNotContain("so that they agree with how this code does it");

            assertThat(graph).isNotNull();
            Task task = graph.tasks().get(0);
            assertThat(task.instructions())
                .as("what the planner wrote, and the contract it must deliver stated exactly")
                .startsWith("Delivers OrderService and check R1:C1")
                .contains("YOU MUST DELIVER THESE TYPES EXACTLY AS WRITTEN")
                .contains("com.shop.OrderService{void place(String id); }");
            assertThat(task.writeSet())
                .as("it named no file; the reservation is computed after this").isEmpty();
        }
    }

    @Test
    void theOldPlannersPromptWithoutAStoryIsSentNoExamplesEither() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompts.add(conversation);
            return PLAN;
        })) {
            ArchitectClient planner = new ArchitectClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null),
                null, FULL_OF_MATERIAL);

            planner.plan(design(), "Take orders");

            assertThat(prompts.get(0)).contains("You are an AI planner")
                .contains("FACT [OrderService]")
                .doesNotContain("WORKED EXAMPLES").doesNotContain("LedgerStoreExample");
        }
    }

    @Test
    void itRunsOnItsOwnModelWhenOneIsNamedAndOnTheArchitectsOtherwise() throws Exception {
        List<String> toTheArchitect = new CopyOnWriteArrayList<>();
        List<String> toThePlanner = new CopyOnWriteArrayList<>();
        try (ScriptedLlm strong = new ScriptedLlm(conversation -> {
                 toTheArchitect.add(conversation);
                 return conversation.contains("You are an AI planner") ? PLAN
                     : "{\"requirements\":[],\"decisions\":[],\"contracts\":[],\"risks\":[]}";
             });
             ScriptedLlm modest = new ScriptedLlm(conversation -> {
                 toThePlanner.add(conversation);
                 return PLAN;
             })) {
            ArchitectClient client = new ArchitectClient(
                new VllmClient(strong.baseUrl(), "", "strong", true),
                new CloudGate(1_000_000, null));

            client.plan(design(), "Take orders", scope(), "");
            assertThat(toTheArchitect).as("no planner named: the architect's model, as before")
                .hasSize(1);
            assertThat(toThePlanner).isEmpty();

            client.setPlannerClient(new VllmClient(modest.baseUrl(), "", "modest", true));
            client.plan(design(), "Take orders", scope(), "");
            client.design("Take orders");

            assertThat(toThePlanner).as("the plan went to the planner's own model").hasSize(1);
            assertThat(toThePlanner.get(0)).contains("You are an AI planner");
            assertThat(toTheArchitect).as("and the design still goes to the architect's")
                .hasSize(2);
            assertThat(toTheArchitect.get(1)).contains("You are a software architect");
        }
    }
}
