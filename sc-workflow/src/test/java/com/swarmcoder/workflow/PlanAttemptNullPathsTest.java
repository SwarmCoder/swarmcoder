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
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the last gap in "a plan attempt always says what threw" (2026-09-04, harness run 21):
 * {@code ArchitectClient#plan} sets its thread-local failure reason on every {@code throw}, but
 * {@code toTaskGraph} used to return {@code null} directly — reached from INSIDE {@code plan}'s own
 * try block, never through its catch — whenever the reply parsed as valid JSON but never named a
 * task. That path left {@link ArchitectClient#planAttempt} with nothing but the canned "the
 * architect answered unusably", indistinguishable from the model genuinely refusing. That fallback
 * text is gone: every return-null path now sets a reason, so {@code planAttempt} always has one.
 */
class PlanAttemptNullPathsTest {

    private static ArchitectClient architect(ScriptedLlm llm) {
        return new ArchitectClient(
            new VllmClient(llm.baseUrl(), null, "test-model", true),
            new CloudGate(1_000_000, null),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()));
    }

    @Test
    void aScopedReplyWithNoTasksReportsWhyRatherThanTheCannedFallback() throws Exception {
        Fixture fixture = fixture();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{\"edges\":[]}")) {

            ArchitectClient.PlanAttempt result = architect(llm).planAttempt(
                null, "Guest can pay", fixture.scope, "", null, List.of());

            assertThat(result.graph()).isNull();
            assertThat(result.failureReason())
                .as("never the canned fallback — an unexplained null is a bug, not a refusal")
                .isEqualTo("the reply had no tasks");
            assertThat(result.rawReply())
                .as("the reply DID parse — it is kept for the operator, not thrown away")
                .isEqualTo("{\"edges\":[]}");
        }
    }

    @Test
    void anUnscopedReplyWithNoTasksReportsWhyRatherThanTheCannedFallback() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{\"edges\":[]}")) {

            ArchitectClient.PlanAttempt result = architect(llm).planAttempt(
                null, "Guest can pay", null, "", null, List.of());

            assertThat(result.graph()).isNull();
            assertThat(result.failureReason()).isEqualTo("the reply had no tasks");
        }
    }

    @Test
    void aScopedReplyWithAnEmptyTasksArrayAlsoReportsWhy() throws Exception {
        Fixture fixture = fixture();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> "{\"tasks\":[],\"edges\":[]}")) {

            ArchitectClient.PlanAttempt result = architect(llm).planAttempt(
                null, "Guest can pay", fixture.scope, "", null, List.of());

            assertThat(result.graph()).isNull();
            assertThat(result.failureReason()).isEqualTo("the reply had no tasks");
        }
    }

    private record Fixture(BrdRequirement checkout, AcceptanceCriterion criterion, StoryScope scope) {}

    private static Fixture fixture() {
        BrdRequirement checkout = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        checkout.setCriteria(new ArrayList<>(List.of(criterion)));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(List.of(checkout)), new ArrayList<>(), Instant.now(), Instant.now());

        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());

        return new Fixture(checkout, criterion, StoryScope.resolve(brd, story));
    }
}
