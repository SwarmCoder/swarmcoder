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
import com.swarmcoder.domain.DesignDocument;
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
 * Pins the fix for harness run 21 (2026-09-04): a design with 1 requirement and 0 contracts
 * reached PLAN for a story whose only check needs a real type. Nothing in the design named one, so
 * no task's {@code deliversContracts} could ever name it, and the plan attempts that followed
 * failed for reasons that had nothing to do with the real problem. DESIGN now asks the architect
 * once more, naming the story's own checks, before it will let a design with checks and zero typed
 * contracts through — and parks, with that same sentence, if the second reply is still empty.
 */
class DesignRequiresTypedContractsForChecksTest {

    @TempDir
    Path storeDir;

    private static final String DESIGN_WITH_NO_CONTRACTS = """
        {"decisions":[{"decision":"Keep the cart in the session","rationale":"simplest"}],
         "contracts":[],"risks":[],"missingRequirements":[]}
        """;

    private static final String REVISION_STILL_NO_CONTRACTS = """
        {"requirements":[{"text":"Guest can pay","priority":"HIGH"}],
         "decisions":[{"decision":"Keep the cart in the session","rationale":"simplest"}],
         "contracts":[],"risks":[]}
        """;

    private static final String REVISION_WITH_A_TYPED_CONTRACT = """
        {"requirements":[{"text":"Guest can pay","priority":"HIGH"}],
         "decisions":[{"decision":"Keep the cart in the session","rationale":"simplest"}],
         "contracts":[{"name":"CartApi","description":"cart ops","signature":"",
           "type":"com.acme.shop.Cart","members":["int total"]}],
         "risks":[]}
        """;

    @Test
    void stillNoContractsAfterTheReAskParksAtDesignWithTheSentence() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger designCalls = new AtomicInteger();
        AtomicInteger reviseCalls = new AtomicInteger();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    reviseCalls.incrementAndGet();
                    return REVISION_STILL_NO_CONTRACTS;
                }
                if (conversation.contains("requirements are FIXED")) {
                    designCalls.incrementAndGet();
                    return DESIGN_WITH_NO_CONTRACTS;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fixture = fixture(store);
            WorkflowEngine engine = engine(llm, store);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                fixture.story.projectId(), fixture.story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Guest can pay"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(designCalls.get()).isEqualTo(1);
            assertThat(reviseCalls.get())
                .as("exactly one re-ask, naming the checks")
                .isEqualTo(1);
            assertThat(parked.state())
                .as("parks at DESIGN itself — the design never even reached DESIGN_REVIEW")
                .isEqualTo(RunState.DESIGN);
            assertThat(parked.parkReason())
                .contains("Every type an acceptance test will touch must be a contract with its "
                    + "exact package and members; you gave none.")
                .contains("The checks are:")
                .contains(fixture.criterion.testClassOrFile())
                .contains(fixture.criterion.text())
                .endsWith("Name the types.");
        }
    }

    @Test
    void aSecondReplyWithATypedContractIsAcceptedAndTheRunProceedsPastDesign() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger reviseCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    reviseCalls.incrementAndGet();
                    return REVISION_WITH_A_TYPED_CONTRACT;
                }
                if (conversation.contains("requirements are FIXED")) {
                    return DESIGN_WITH_NO_CONTRACTS;
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\": true, \"objections\": []}";
                }
                if (conversation.contains("AI planner")) {
                    plannerCalls.incrementAndGet();
                    return "I decline to produce JSON.";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            Fixture fixture = fixture(store);
            WorkflowEngine engine = engine(llm, store);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                fixture.story.projectId(), fixture.story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Guest can pay"));
            engine.advance(run);

            awaitParked(store, runId);
            Run parked = store.root().runs.get(runId);

            assertThat(reviseCalls.get()).isEqualTo(1);
            assertThat(parked.state())
                .as("DESIGN accepted the second reply and let the run all the way through to PLAN, "
                    + "which then parks on its own terms (the planner declines) — proof DESIGN "
                    + "itself never parked")
                .isEqualTo(RunState.PLAN);
            assertThat(plannerCalls.get()).isGreaterThan(0);

            DesignDocument persisted = store.root().designs.get(parked.designId());
            assertThat(persisted.contracts()).hasSize(1);
            assertThat(persisted.contracts().get(0).typeName()).isEqualTo("com.acme.shop.Cart");
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
