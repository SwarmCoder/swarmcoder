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
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArchitectClient#planAttempt} is what {@link GreenfieldWorkflow}'s PLAN retry loop calls on
 * every one of its (now three) real tries — see {@link GreenfieldWorkflowPlanRetryTest} for the
 * loop's own behaviour. These two tests are about the two things that live entirely inside ONE
 * {@code planAttempt} call: the already-existing malformed-reply retry from {@code callForJson}
 * (2026-09-03) must not be mistaken for a second PLAN attempt, and a retry the WORKFLOW asks for must
 * actually carry what it was asked to carry — the previous reply, verbatim, and exactly why it was
 * rejected.
 */
class ArchitectClientPlanAttemptTest {

    private static final String VALID_PLAN_JSON = """
        {"tasks":[{"id":"t1","title":"Implement it","instructions":"do it",
          "writeSet":["src/main/java/com/example"],"readSet":[],
          "criteria":[],"criterionRefs":["R1:C1"]}],
         "edges":[]}
        """;

    @Test
    void aMalformedReplyThenAGoodOneIsOnePlanAttemptNotTwo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation ->
                calls.incrementAndGet() == 1 ? "not json at all" : VALID_PLAN_JSON)) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "test-model", true);
            ArchitectClient architect = new ArchitectClient(client, new CloudGate(1_000_000, null));

            ArchitectClient.PlanAttempt result = architect.planAttempt(
                null, "add multiply", scopeWithOneCriterion(), "", null, List.of());

            assertThat(result.failureReason())
                .as("the malformed reply's own retry succeeded, so this is a SUCCESS, not a failure")
                .isNull();
            assertThat(result.graph()).isNotNull();
            assertThat(calls.get())
                .as("the malformed first reply and its retry are ONE plan attempt, not two — both "
                    + "HTTP calls happened inside this single planAttempt() invocation")
                .isEqualTo(2);
        }
    }

    @Test
    void aRetryCarriesThePreviousReplyAndTheExactObjections() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondRequest = new AtomicReference<>();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (calls.incrementAndGet() == 2) {
                    secondRequest.set(conversation);
                }
                return VALID_PLAN_JSON;
            })) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "test-model", true);
            ArchitectClient architect = new ArchitectClient(client, new CloudGate(1_000_000, null));
            StoryScope scope = scopeWithOneCriterion();

            ArchitectClient.PlanAttempt first =
                architect.planAttempt(null, "add multiply", scope, "", null, List.of());
            assertThat(first.rawReply())
                .as("a reply that parsed must be captured, win or lose, so it can be fed back")
                .isNotNull();

            architect.planAttempt(null, "add multiply", scope, "", first.rawReply(),
                List.of("task 'Implement it' claims no check"));

            assertThat(secondRequest.get())
                .as("the second request must name exactly why the first was rejected and carry "
                    + "the first reply back, verbatim, so the planner can fix what was wrong "
                    + "instead of being asked the same question again")
                // One objection per line since 2026-09-26 (harness run 40): the objections now
                // carry their remedies as full sentences, which a "; "-joined line garbles.
                .contains("Your previous plan was rejected for these reasons:\n- task "
                    + "'Implement it' claims no check")
                .contains("Your previous plan, verbatim:")
                .contains(first.rawReply());
        }
    }

    private static StoryScope scopeWithOneCriterion() {
        AcceptanceCriterion criterion =
            new AcceptanceCriterion(UUID.randomUUID(), "it works", "swarm.accept.ItWorksAcceptTest");
        return new StoryScope(null, List.of(), List.of(criterion), List.of("R1:C1"), List.of(), "");
    }
}
