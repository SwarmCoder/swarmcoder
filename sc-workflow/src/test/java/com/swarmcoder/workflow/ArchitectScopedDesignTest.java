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
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.EndpointOutage;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Architect no longer authors requirements.
 *
 * <p>Before this, {@code design()} asked the model for a requirement list invented from the goal
 * string, which then had no relationship to the BRD the operator actually maintains. These tests
 * pin the replacement: requirements come from the requirement graph with their own ids, the model
 * is asked only HOW, and anything it thinks is missing becomes a proposal rather than a fact.
 */
class ArchitectScopedDesignTest {

    @Test
    void theDesignCarriesTheBrdsRequirementsNotInventedOnes() throws Exception {
        AtomicReference<String> prompt = new AtomicReference<>("");
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            prompt.set(conversation);
            return """
                {"decisions":[{"decision":"Keep the cart in the session","rationale":"simplest"}],
                 "contracts":[{"name":"CartApi","description":"cart ops","signature":"POST /cart"}],
                 "risks":[{"description":"session loss","severity":"MEDIUM","mitigation":"cookie"}],
                 "missingRequirements":[]}
                """;
        })) {
            Fixture fixture = fixture();
            ArchitectClient architect = architect(llm);

            ArchitectClient.ScopedDesign scoped =
                architect.design("Guest can pay", fixture.scope);
            DesignDocument design = scoped.design();

            // The design REFERENCES the requirement graph — same ids, so a task tracing to a
            // requirement traces to the BRD, not to a parallel identity that would start drifting.
            assertThat(design.requirements()).hasSize(1);
            assertThat(design.requirements().get(0).id()).isEqualTo(fixture.checkout.id());
            assertThat(design.requirements().get(0).text()).isEqualTo(fixture.checkout.text());
            assertThat(design.brdRequirementIds()).containsExactly(fixture.checkout.id());

            // The model contributed only what it is for.
            assertThat(design.decisions()).hasSize(1);
            assertThat(design.contracts()).hasSize(1);
            assertThat(design.risks()).hasSize(1);

            // …and it was told the requirements are fixed, shown the exact criteria, and given the
            // inherited quality gate it must honour.
            assertThat(prompt.get()).contains("requirements are FIXED");
            assertThat(prompt.get()).contains("R7:C1").contains("empty cart is rejected");
            assertThat(prompt.get()).contains("R12").contains("p95 under 200ms");
            assertThat(prompt.get()).doesNotContain("\"requirements\":[{\"text\"");
        }
    }

    /**
     * The distinction UX v3 §2.4 is built on: an endpoint that is not there is not an architect with
     * nothing to say.
     *
     * <p>This test previously asserted the opposite — that an unreachable architect still produced a
     * requirements-only design — and the run carried on to plan and swarm against no technical
     * reasoning at all. That is the behaviour the redesign removes: a dead endpoint has told us
     * nothing, so the caller must be able to wait for it rather than build on its silence.
     */
    @Test
    void anUnreachableArchitectIsAnOutage_notADesignWithoutReasoning() {
        Fixture fixture = fixture();
        CloudGate gate = new CloudGate(1_000_000, null);
        // Nothing listening on port 1: the endpoint is genuinely dead.
        ArchitectClient architect = new ArchitectClient(
            new VllmClient("http://localhost:1", null, "test-model", true), gate,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));

        assertThatThrownBy(() -> architect.design("Guest can pay", fixture.scope))
            .isInstanceOf(EndpointOutage.class)
            .hasMessageContaining("localhost:1");

        // And it cost nothing. The prompt is charged before sending so a runaway is stopped before it
        // spends; a call that never reached a model must be given back, or the retries the workflow
        // makes while waiting would eat the run's budget without generating a token.
        assertThat(gate.used()).isZero();
    }

    /**
     * The requirements-only fallback still exists — but only for its real case: the architect
     * answered, and the answer was unusable.
     */
    @Test
    void anArchitectThatAnswersUnusablyStillProducesTheRequirements() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "I would rather not, thanks.")) {
            Fixture fixture = fixture();

            DesignDocument design =
                architect(llm).design("Guest can pay", fixture.scope).design();

            // The requirements are known without the model — losing the run because the technical
            // reasoning is unavailable would be worse than proceeding with the reasoning missing.
            assertThat(design.requirements()).hasSize(1);
            assertThat(design.brdRequirementIds()).containsExactly(fixture.checkout.id());
            assertThat(design.decisions()).isEmpty();
        }
    }

    @Test
    void missingRequirementsComeBackAsProposalsNotAsDesignContent() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"decisions":[],"contracts":[],"risks":[],
                 "missingRequirements":[{"title":"Guest session tokens",
                   "statement":"A guest must get a short-lived session token",
                   "why":"the cart cannot be attributed without one"}]}
                """)) {
            Fixture fixture = fixture();

            ArchitectClient.ScopedDesign scoped =
                architect(llm).design("Guest can pay", fixture.scope);

            // Surfaced for triage…
            assertThat(scoped.missing()).hasSize(1);
            assertThat(scoped.missing().get(0).title).isEqualTo("Guest session tokens");
            // …but NOT smuggled into the design as though it were agreed.
            assertThat(scoped.design().requirements()).hasSize(1);
            assertThat(scoped.design().requirements().get(0).id()).isEqualTo(fixture.checkout.id());
        }
    }

    @Test
    void plannedTasksClaimCriteriaFromTheSlice() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> """
                {"tasks":[{"id":"t1","title":"Add GuestCart","instructions":"…",
                   "writeSet":["src/cart"],"readSet":[],"criteria":[],
                   "criterionRefs":["R7:C1","R9:C1"]}],
                 "edges":[]}
                """)) {
            Fixture fixture = fixture();

            TaskGraph graph = architect(llm).plan(null, "Guest can pay", fixture.scope);

            assertThat(graph).isNotNull();
            var task = graph.tasks().get(0);
            // Only the ref inside the slice is honoured; R9:C1 is not this story's to deliver, so
            // claiming it is the planner widening its own scope and is dropped.
            assertThat(task.criterionIds()).containsExactly(fixture.criterion.id());
            assertThat(task.requirementIds()).containsExactly(fixture.checkout.id());
            assertThat(task.storyId()).isEqualTo(fixture.scope.story().id());
        }
    }

    /** A real CloudGate: {@code call()} charges it, so a null one would NPE into the fallback. */
    private static ArchitectClient architect(String baseUrl) {
        return new ArchitectClient(
            new VllmClient(baseUrl, null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    private static ArchitectClient architect(ScriptedLlm llm) {
        return architect(llm.baseUrl());
    }

    private record Fixture(BrdRequirement checkout, AcceptanceCriterion criterion, StoryScope scope) {}

    /** One functional requirement with one accepted criterion, gated by an NFR through REFINES. */
    private static Fixture fixture() {
        BrdRequirement epic = new BrdRequirement(UUID.randomUUID(), "R2", "Checkout",
            "Customers can pay", Priority.HIGH, RequirementStatus.ACTIVE, null);
        epic.setCriteria(new ArrayList<>());

        BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        checkout.setCriteria(new ArrayList<>(List.of(criterion)));

        BrdRequirement latency = new BrdRequirement(UUID.randomUUID(), "R12", "Latency",
            "p95 under 200ms at 100 rps", Priority.HIGH, RequirementStatus.ACTIVE, null);
        latency.setKind(RequirementKind.NON_FUNCTIONAL);
        latency.setNfrCategory(NfrCategory.PERFORMANCE);
        latency.setCriteria(new ArrayList<>());

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(List.of(epic, checkout, latency)),
            new ArrayList<>(List.of(
                new BrdEdge(checkout.id(), epic.id(), RequirementRelation.REFINES),
                new BrdEdge(latency.id(), epic.id(), RequirementRelation.GATES))),
            Instant.now(), Instant.now());

        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        return new Fixture(checkout, criterion, StoryScope.resolve(brd, story));
    }
}
