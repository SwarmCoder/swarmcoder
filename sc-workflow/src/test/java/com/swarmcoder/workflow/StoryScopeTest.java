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
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run is allowed to build. This is the boundary that stops the Architect inventing
 * requirements, so the interesting cases are the ones about what is EXCLUDED and what is
 * INHERITED.
 */
class StoryScopeTest {

    @Test
    void aStoryResolvesToExactlyTheCriteriaItDelivers() {
        BrdRequirement checkout = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("empty cart is rejected", "T#empty");
        AcceptanceCriterion c2 = accepted("order is confirmed", "T#confirmed");
        AcceptanceCriterion c3 = accepted("order is emailed", "T#email");
        checkout.setCriteria(new ArrayList<>(List.of(c1, c2, c3)));

        BrdRequirement other = requirement("R8", "Saved cards", RequirementKind.FUNCTIONAL);
        other.setCriteria(new ArrayList<>(List.of(accepted("card is stored", "T#card"))));

        Brd brd = brd(List.of(checkout, other), List.of());
        Story story = story(List.of(c1.id(), c3.id()));

        StoryScope scope = StoryScope.resolve(brd, story);

        // The slice, not the whole requirement: C2 belongs to R7 but this story does not deliver it.
        assertThat(scope.criteria()).containsExactly(c1, c3);
        assertThat(scope.criterionRefs()).containsExactly("R7:C1", "R7:C3");
        assertThat(scope.requirements()).containsExactly(checkout);
        assertThat(scope.idForRef("R7:C3")).isEqualTo(c3.id());
        assertThat(scope.refFor(c1.id())).isEqualTo("R7:C1");
        assertThat(scope.requirementOf(c1.id())).isEqualTo(checkout);
    }

    @Test
    void proposedAndRetiredCriteriaAreExcluded() {
        BrdRequirement r = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion agreed = accepted("empty cart is rejected", "T#empty");
        AcceptanceCriterion proposed = new AcceptanceCriterion(UUID.randomUUID(), "maybe", "T#maybe");
        proposed.setStatus(CriterionStatus.PROPOSED);
        AcceptanceCriterion retired = new AcceptanceCriterion(UUID.randomUUID(), "old", "T#old");
        retired.setStatus(CriterionStatus.RETIRED);
        r.setCriteria(new ArrayList<>(List.of(agreed, proposed, retired)));

        StoryScope scope = StoryScope.resolve(brd(List.of(r), List.of()),
            story(List.of(agreed.id(), proposed.id(), retired.id())));

        // Building against a criterion nobody accepted would let an agent's proposal become work.
        assertThat(scope.criteria()).containsExactly(agreed);
    }

    @Test
    void nfrGatesAreInheritedThroughTheRefinesHierarchy() {
        BrdRequirement epic = requirement("R2", "Checkout", RequirementKind.FUNCTIONAL);
        BrdRequirement guest = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("empty cart is rejected", "T#empty");
        guest.setCriteria(new ArrayList<>(List.of(c1)));

        BrdRequirement latency = requirement("R12", "Latency", RequirementKind.NON_FUNCTIONAL);
        latency.setNfrCategory(NfrCategory.PERFORMANCE);
        latency.setCriteria(new ArrayList<>(List.of(accepted("p95 < 200ms", "PerfTest#p95"))));

        BrdRequirement unrelated = requirement("R13", "Retention", RequirementKind.NON_FUNCTIONAL);
        unrelated.setNfrCategory(NfrCategory.COMPLIANCE);

        Brd brd = brd(List.of(epic, guest, latency, unrelated), List.of(
            new BrdEdge(guest.id(), epic.id(), RequirementRelation.REFINES),   // guest refines epic
            new BrdEdge(latency.id(), epic.id(), RequirementRelation.GATES),   // NFR gates the epic
            new BrdEdge(unrelated.id(), requirement("R99", "elsewhere",
                RequirementKind.FUNCTIONAL).id(), RequirementRelation.GATES)));

        StoryScope scope = StoryScope.resolve(brd, story(List.of(c1.id())));

        // The gate is attached to the parent, never restated on the child — inheriting it is the
        // whole point, otherwise every requirement under an area has to repeat every constraint.
        assertThat(scope.gatingNfrs()).containsExactly(latency);
    }

    @Test
    void aRetiredQualityRequirementGatesNothing() {
        BrdRequirement epic = requirement("R2", "Checkout", RequirementKind.FUNCTIONAL);
        BrdRequirement guest = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("empty cart is rejected", "T#empty");
        guest.setCriteria(new ArrayList<>(List.of(c1)));
        BrdRequirement speed = requirement("R12", "p95 under 200ms",
            RequirementKind.NON_FUNCTIONAL);
        speed.setNfrCategory(NfrCategory.PERFORMANCE);

        Brd brd = brd(List.of(epic, guest, speed), List.of(
            new BrdEdge(guest.id(), epic.id(), RequirementRelation.REFINES),
            new BrdEdge(speed.id(), epic.id(), RequirementRelation.GATES)));

        // As it stands it gates, inherited down the hierarchy.
        assertThat(StoryScope.resolve(brd, story(List.of(c1.id()))).gatingNfrs())
            .containsExactly(speed);

        // Taken out of scope, it stops. There was no status test here at all, so a quality bar the
        // operator had explicitly retired went on constraining every requirement under it, for
        // every run, for ever - and the rows on screen went on saying "constrains R2".
        speed.setStatus(RequirementStatus.DEPRECATED);
        assertThat(StoryScope.resolve(brd, story(List.of(c1.id()))).gatingNfrs()).isEmpty();
    }

    @Test
    void aRetiredRequirementIsNotBuilt() {
        BrdRequirement r = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("empty cart is rejected", "T#empty");
        r.setCriteria(new ArrayList<>(List.of(c1)));
        Brd brd = brd(List.of(r), List.of());
        Story story = story(List.of(c1.id()));

        assertThat(StoryScope.resolve(brd, story).criteria()).containsExactly(c1);

        // Retiring keeps the check and keeps the story's claim on it - that claim is the record of
        // what the story was undertaken to deliver. What it stops is anybody being sent to build it.
        r.setStatus(RequirementStatus.DEPRECATED);
        assertThat(StoryScope.resolve(brd, story).criteria()).isEmpty();
        assertThat(r.criteria()).containsExactly(c1);
        assertThat(story.criterionIds()).containsExactly(c1.id());
    }

    @Test
    void aFunctionalRequirementCannotGate() {
        BrdRequirement target = requirement("R7", "Guest checkout", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("empty cart is rejected", "T#empty");
        target.setCriteria(new ArrayList<>(List.of(c1)));
        BrdRequirement functional = requirement("R3", "Something", RequirementKind.FUNCTIONAL);

        Brd brd = brd(List.of(target, functional),
            List.of(new BrdEdge(functional.id(), target.id(), RequirementRelation.GATES)));

        // The editor refuses to create such an edge, but an older store or a hand-edit could hold
        // one; honouring it would apply a gate with no fitness criterion that nothing can satisfy.
        assertThat(StoryScope.resolve(brd, story(List.of(c1.id()))).gatingNfrs()).isEmpty();
    }

    @Test
    void aRefinesCycleDoesNotHangTheResolver() {
        BrdRequirement a = requirement("R1", "A", RequirementKind.FUNCTIONAL);
        BrdRequirement b = requirement("R2", "B", RequirementKind.FUNCTIONAL);
        AcceptanceCriterion c1 = accepted("something", "T#x");
        a.setCriteria(new ArrayList<>(List.of(c1)));

        Brd brd = brd(List.of(a, b), List.of(
            new BrdEdge(a.id(), b.id(), RequirementRelation.REFINES),
            new BrdEdge(b.id(), a.id(), RequirementRelation.REFINES)));

        assertThat(StoryScope.resolve(brd, story(List.of(c1.id()))).gatingNfrs()).isEmpty();
    }

    @Test
    void anAdHocStoryWithNoCriteriaResolvesEmpty() {
        StoryScope scope = StoryScope.resolve(brd(List.of(), List.of()), story(List.of()));
        assertThat(scope.isEmpty()).isTrue();
        assertThat(StoryScope.resolve(null, null).isEmpty()).isTrue();
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static BrdRequirement requirement(String handle, String title, RequirementKind kind) {
        BrdRequirement r = new BrdRequirement(UUID.randomUUID(), handle, title, title + " statement",
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        r.setKind(kind);
        r.setCriteria(new ArrayList<>());
        return r;
    }

    private static AcceptanceCriterion accepted(String text, String test) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text, test);
        c.setStatus(CriterionStatus.ACCEPTED);
        return c;
    }

    private static Brd brd(List<BrdRequirement> requirements, List<BrdEdge> edges) {
        return new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Business Requirements",
            new ArrayList<>(requirements), new ArrayList<>(edges), Instant.now(), Instant.now());
    }

    private static Story story(List<UUID> criterionIds) {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), "S1", StoryKind.DELIVERY,
            "Guest can pay", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(criterionIds), null, 0, StoryOrigin.BACKLOG, null, null, "human",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
    }
}
