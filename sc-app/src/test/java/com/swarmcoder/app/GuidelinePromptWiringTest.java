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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.ModelProfile;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.swarm.FakeVllm;
import com.swarmcoder.swarm.SwarmDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does an operator's house rule reach the bytes the worker is actually sent?
 *
 * <p>Nothing here is mocked between the STORE and the WIRE. A rule is stated into the project's
 * rules exactly as the Console's Apply does, {@link ProjectRules} renders it exactly as the app
 * wires it (per prefix assembly), the real {@link SwarmDispatcher} builds the real shared prefix,
 * the real worker loop opens a real agent session over real HTTP — and the request body that
 * arrives at the endpoint is what is asserted on. The only simulated thing is who answers.
 *
 * <p>Four separate claims, each on its own evidence:
 * <ol>
 *   <li>a rule stated mid-life is picked up with no restart — the same live objects see it;</li>
 *   <li>its text lands in the PROJECT_CONSTRAINTS segment, before the task instructions;</li>
 *   <li>every worker of the group gets a byte-identical prefix (prefix-cache reuse);</li>
 *   <li>retiring the rule removes it from the very next prompt.</li>
 * </ol>
 *
 * <p>The checkout is never touched: rules are store objects, not files (2026-09-02).
 */
@ModelCodeOnThisPc
class GuidelinePromptWiringTest {

    private static final String RULE =
        "Never declare a Java record. Use a final class with a constructor and explicit "
        + "accessor methods instead.";

    @TempDir
    Path work;

    @Test
    void aStatedRuleReachesTheWorkersPromptAndLeavesWhenRetired() throws Exception {
        Path repo = DemoRepo.create(work.resolve("repo"));

        try (ArtifactStore store = new ArtifactStore(work.resolve("store"));
             FakeVllm endpoint = new FakeVllm(conversation ->
                 FakeVllm.Reply.toolCall("report_done", "{\"summary\": \"nothing to do\"}"))) {

            ProjectRules rules = new ProjectRules(store, UUID.randomUUID());
            // Exactly how ProjectContext wires it: rendered from the store at every assembly.
            Supplier<String> guidelines = () -> rules.renderActive(12_000);

            GitService git = new GitService(repo);
            SwarmDispatcher dispatcher = dispatcher(endpoint, git);

            // --- 1. no rules: no guidelines segment at all -------------------------------------
            dispatch(dispatcher, git, guidelines);
            String before = lastPrompt(endpoint);
            assertThat(before)
                .as("with no rule, the prompt carries no PROJECT_CONSTRAINTS segment")
                .doesNotContain("## PROJECT_CONSTRAINTS");
            int promptsBefore = endpoint.requests.size();

            // --- 2. the operator states the rule, mid-life, no restart -------------------------
            assertThat(rules.stateRule("No Java records", RULE, null)).isEmpty();

            dispatch(dispatcher, git, guidelines);
            List<String> withRule = endpoint.requests.subList(promptsBefore, endpoint.requests.size());
            assertThat(withRule).as("both workers of the group were dispatched").hasSizeGreaterThanOrEqualTo(2);
            for (String prompt : withRule) {
                assertThat(prompt)
                    .as("the operator's own words are in the bytes the model receives")
                    .contains(RULE);
                assertThat(prompt.indexOf("## PROJECT_CONSTRAINTS"))
                    .as("and they sit in the PROJECT_CONSTRAINTS segment")
                    .isGreaterThanOrEqualTo(0);
                assertThat(prompt.indexOf("## PROJECT_CONSTRAINTS"))
                    .as("which comes before the task instructions (spec 6.3 order)")
                    .isLessThan(prompt.indexOf("## TASK_INSTRUCTIONS"));
            }
            // Prefix reuse depends on byte-identity up to the persona; assert the shared half.
            String sharedA = withRule.get(0).substring(0, withRule.get(0).indexOf("## PERSONA"));
            String sharedB = withRule.get(1).substring(0, withRule.get(1).indexOf("## PERSONA"));
            assertThat(sharedA)
                .as("every worker of the group gets the same prefix, byte for byte")
                .isEqualTo(sharedB);
            int promptsWith = endpoint.requests.size();

            // --- 3. the operator retires it: gone from the very next prompt --------------------
            UUID ruleId = rules.activeRules().get(0).id();
            assertThat(rules.setStatus(ruleId, GuidelineStatus.RETIRED)).isEmpty();
            dispatch(dispatcher, git, guidelines);
            for (String prompt : endpoint.requests.subList(promptsWith, endpoint.requests.size())) {
                assertThat(prompt)
                    .as("a retired rule stops reaching the model immediately")
                    .doesNotContain(RULE)
                    .doesNotContain("## PROJECT_CONSTRAINTS");
            }
        }
    }

    /** One two-worker dispatch on a fresh task id, worktrees cleaned up as the engine does. */
    private static void dispatch(SwarmDispatcher dispatcher, GitService git,
                                 Supplier<String> guidelines) {
        for (var result : dispatcher.dispatch(task(2), UUID.randomUUID(), null, guidelines.get())) {
            if (result.workspace() != null) {
                git.removeWorktree(result.workspace());
            }
        }
    }

    private static String lastPrompt(FakeVllm endpoint) {
        assertThat(endpoint.requests).isNotEmpty();
        return endpoint.requests.get(endpoint.requests.size() - 1);
    }

    private static SwarmDispatcher dispatcher(FakeVllm endpoint, GitService git) {
        String model = "fake-vllm";
        return new SwarmDispatcher(
            new InferenceScheduler(16, 1024L * 1024 * 1024, 1024),
            git,
            new KoogAgentRuntime(),
            new ModelProfileRegistry(List.of(new ModelProfile(model,
                new AgentRuntime.ModelEndpoint(endpoint.baseUrl(), "", model, 65536),
                ModelProfile.Kind.WORKER, 0, 0))));
    }

    private static Task task(int workers) {
        return new Task(UUID.randomUUID(), 1, "Add a Money value type",
            "Add src/main/java/com/example/calc/Money.java holding an amount and a currency code.",
            Set.of("src/main"), Set.of("src"), List.of(), "src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 6),
            new SwarmPolicy(workers, false, 0.2, 0.8, List.of("minimal-diff", "defensive-edges")),
            TaskState.READY);
    }
}
