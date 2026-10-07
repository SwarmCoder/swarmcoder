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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of harness run 21 (2026-09-04): DESIGN_REVIEW asked the architect to revise a
 * design that conflicted with a stated rule, the revision came back empty, and it replaced the
 * design outright — the SECOND review then saw "The design section is empty" as though that were a
 * new problem, instead of the rule conflict ever actually getting fixed.
 *
 * <p>The rule-conflict revision loop now discards a revision that says less than the design it was
 * meant to fix, keeps the original, and still counts the attempt — so a design that never
 * genuinely gets revised burns through {@link GreenfieldWorkflow#MAX_DESIGN_RULE_REVISIONS} real
 * attempts and then parks with BOTH the rule objection and the reason the last revision could not
 * even be reviewed.
 */
class DesignReviewDiscardsAnEmptyRevisionTest {

    @TempDir
    Path storeDir;

    private static final String DESIGN_WITH_A_DECISION_AND_A_CONTRACT = """
        {"decisions":[{"decision":"Keep ratings in browser storage until the server is wired up",
           "rationale":"fastest to ship"}],
         "contracts":[{"name":"RatingStore","description":"rating persistence","signature":"",
           "type":"com.acme.demo.RatingStore","members":["int rating"]}],
         "risks":[],"missingRequirements":[]}
        """;

    private static final String RULES = "- Storage is an object graph\n"
        + "  Persistence uses EclipseStore; the server keeps the live object graph and nothing "
        + "is kept in the browser.\n";

    @Test
    void twoDiscardedRevisionsInARowParkWithTheRuleObjectionAndTheDiscardReason() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger reviseCalls = new AtomicInteger();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    reviseCalls.incrementAndGet();
                    // Parses fine, but says LESS than the design it was meant to fix: drops the
                    // requirement AND the contract the original had.
                    return "{\"decisions\":[],\"contracts\":[],\"risks\":[]}";
                }
                if (conversation.contains("requirements are FIXED")) {
                    return DESIGN_WITH_A_DECISION_AND_A_CONTRACT;
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    // Approved on the rubric — this test is about the RULE-CONFLICT loop only.
                    return "{\"approved\": true, \"objections\": []}";
                }
                if (conversation.contains("checking a DESIGN against this project's standing rules")) {
                    return "{\"approved\": false, \"objections\": [\"decision 'Keep ratings in "
                        + "browser storage' conflicts with rule 'Storage is an object graph': it "
                        + "keeps data in the browser\"]}";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fixture = fixture(store);
            WorkflowEngine engine = engine(llm, store);
            engine.setProjectRules(() -> RULES);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                fixture.story.projectId(), fixture.story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Guest can pay"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(reviseCalls.get())
                .as("both of MAX_DESIGN_RULE_REVISIONS' real attempts happened — no early exit, "
                    + "and no attempt skipped just because the previous one was discarded")
                .isEqualTo(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS);
            assertThat(parked.state())
                .as("a park does not move the run's recorded state — stays resumable at DESIGN_REVIEW")
                .isEqualTo(RunState.DESIGN_REVIEW);
            assertThat(parked.parkReason())
                .as("the original rule objection is still there — the design was never actually fixed")
                .contains("conflicts with rule 'Storage is an object graph'")
                .as("and the reason the revision itself could not be trusted is also there")
                .contains("revision discarded: it dropped 1 requirement(s) and all contracts")
                .contains("A design that breaks a stated rule cannot be planned around");
        }
    }

    private static WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store) {
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new WorkflowEngine(
            unusedRuntime,
            run -> {
                throw new AssertionError("a swarm was dispatched — this test never expects EXECUTING");
            },
            new VllmClient(llm.baseUrl(), "", "test-model", true),
            store);
    }

    private static void awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }

    private record Fixture(BrdRequirement checkout, AcceptanceCriterion criterion, Story story) {}

    private static Fixture fixture(ArtifactStore store) {
        BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        checkout.setCriteria(new ArrayList<>(List.of(criterion)));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(List.of(checkout)), new ArrayList<>(), Instant.now(), Instant.now());
        store.saveBrd(brd);

        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        store.saveStory(story);

        return new Fixture(checkout, criterion, story);
    }
}
