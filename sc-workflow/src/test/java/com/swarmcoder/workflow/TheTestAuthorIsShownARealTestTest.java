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
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worked example of a test reaches the test author on its first prompt and on every repair
 * prompt, and the planner on its plan prompt.
 *
 * <p>Live harness runs 56 to 60 (2026-10-01): four of eight failed runs were an acceptance test
 * that guessed how the framework is tested. The author had never been shown a test.
 */
class TheTestAuthorIsShownARealTestTest {

    private static final String TEST_PATH = "src/test/java/swarm/accept/EditOrderTest.java";
    private static final String EXAMPLE = "\n\nA REAL TEST THAT PASSES TODAY — PayslipServiceImplTest\n"
        + "try (TestLedger ledger = TestLedger.start(PayslipServiceImpl.class)) { }";
    private static final String SOURCE = "package swarm.accept;\nclass EditOrderTest { }\n";

    @TempDir
    Path repo;

    @Test
    void theFirstPromptAndEveryRepairPromptCarryTheExample() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompts.add(conversation);
            return reply();
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));
            author.setTestExamples((task, design) -> EXAMPLE);
            Task task = task();

            author.authorTests(repo, task, null, task.criteria(), "");
            author.repairFailingTest(repo, task, null, TEST_PATH, SOURCE, "NullPointerException", "");
            author.repairRuleBreakingTest(repo, task, null, Map.of(TEST_PATH, SOURCE),
                "the test constructs the service by hand");

            assertThat(prompts).hasSizeGreaterThanOrEqualTo(3);
            assertThat(prompts.get(0)).as("first authoring").contains("TestLedger.start(");
            assertThat(prompts).as("the failing-test repair")
                .anyMatch(p -> p.contains("failed inside its OWN code") && p.contains("TestLedger.start("));
            assertThat(prompts).as("the rule-break repair, which shares its path with the others")
                .anyMatch(p -> p.contains("break this project's standing rules")
                    && p.contains("TestLedger.start("));
        }
    }

    @Test
    void unwiredEveryPromptIsWhatItWas() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompts.add(conversation);
            return reply();
        })) {
            TestAuthorClient author = new TestAuthorClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null));
            Task task = task();

            author.authorTests(repo, task, null, task.criteria(), "");

            assertThat(prompts.get(0)).doesNotContain("A REAL TEST");
        }
    }

    @Test
    void thePlannerIsShownTheExamplesItsWorkersWillGet() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompts.add(conversation);
            return "{\"tasks\":[],\"edges\":[]}";
        })) {
            ArchitectResearch research = new ArchitectResearch() {
                @Override public String reference(int maxChars) { return ""; }
                @Override public String lookupApi(String query) { return ""; }
                @Override public String searchCode(String query) { return ""; }
                @Override public String readFile(String address) { return ""; }
                @Override public String listFolder(String address) { return ""; }
                @Override public String examples(List<ApiContract> contracts, String goal,
                                                 int maxChars) {
                    return contracts.isEmpty() ? "" : EXAMPLE;
                }
            };
            ArchitectClient architect = new ArchitectClient(
                new VllmClient(llm.baseUrl(), "", "scripted", true), new CloudGate(1_000_000, null),
                null, research);
            DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "orders", List.of(),
                List.of(), List.of(new ApiContract(UUID.randomUUID(), "OrderService", "orders", "",
                    "com.acme.orders.OrderService", List.of("String save(String order)"))),
                List.of(), null, null);

            architect.plan(design, "Keep a list of orders");

            assertThat(prompts).isNotEmpty();
            assertThat(prompts.get(0)).contains("WORKED EXAMPLES").contains("TestLedger.start(");
        }
    }

    private static String reply() {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                "files", List.of(Map.of("path", TEST_PATH, "content", SOURCE)),
                "wrote", List.of()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Implement OrderServiceImpl", "edit an order",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "an order can be edited",
                "swarm.accept.EditOrderTest")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
