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
package com.swarmcoder.console;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the requirements editor now costs, and what it says before it costs it.
 *
 * <p>Three things were silent and are not any more: rewording a requirement somebody is building,
 * taking a requirement out of scope, and taking a check out of the gate that a story was delivering.
 * Each is driven here through the same service the screens call, so the sentence shown to the
 * operator and the thing that actually happens are proved against each other.
 */
class RequirementEditImpactTest {

    @TempDir
    Path storeDir;

    @Test
    void rewordingWorkAStoryIsBuildingWarnsFirstAndThenSendsItBack() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = seed(store, storeDir);
            BrdServiceImpl brd = new BrdServiceImpl();
            BrdRequirement r2 = requirement(store, projectId, "R2");
            Story s1 = building(store, projectId, r2);

            // What the operator is shown BEFORE they confirm.
            String warning = brd.editImpact(r2.id().toString());
            assertThat(warning)
                .contains("Editing R2 affects 2 agreed checks and 1 story")
                .contains("1 test that passes today will stop counting")
                .contains("S1 will be sent back to be built again");

            // …and what confirming actually does.
            BrdRequirement edited = new BrdRequirement(r2.id(), r2.handle(), r2.title(),
                "A shopper can check out as a guest and is never asked to make an account",
                r2.priority(), r2.status(), null);
            edited.setCriteria(r2.getCriteria());
            assertThat(brd.saveRequirement(edited)).isEmpty();

            Story after = store.getStory(s1.id());
            assertThat(after.state())
                .describedAs("in-flight work is never churned SILENTLY - so it is churned LOUDLY")
                .isEqualTo(StoryState.READY);
            assertThat(after.deliveredCommit())
                .describedAs("the commit described an attempt against wording that no longer exists")
                .isNull();
            assertThat(store.changeHistory(projectId, after.id())
                    .stream().map(com.swarmcoder.domain.ChangeEvent::summary).toList())
                .describedAs("and the journal records WHY, not just that a card moved")
                .anyMatch(line -> line != null && line.contains("sent S1 back")
                    && line.contains("R2 changed while it was being built"));
        }
    }

    @Test
    void retiringARequirementSaysWhatItCostsAndDestroysNothing() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = seed(store, storeDir);
            BrdServiceImpl brd = new BrdServiceImpl();
            BrdRequirement r2 = requirement(store, projectId, "R2");
            building(store, projectId, r2);

            assertThat(brd.retireImpact(r2.id().toString()))
                .contains("Retiring R2 takes 2 agreed checks and 1 story.")
                .contains("Nothing is deleted")
                .contains("bring it back by setting it back to a draft")
                .contains("S1 will be sent back to be built again");

            assertThat(brd.retireRequirement(r2.id().toString())).isEmpty();

            BrdRequirement still = requirement(store, projectId, "R2");
            assertThat(still).isNotNull();
            assertThat(still.isRetired()).isTrue();
            assertThat(still.criteria())
                .describedAs("its checks, and the evidence on them, are the trail to the commit "
                    + "that satisfied it - which is the whole reason delete became retire")
                .hasSize(2);
            assertThat(still.criteria().get(0).lastVerifiedCommit()).isEqualTo("a1b2c3d");

            // And it is reachable again through the ordinary status picker - no extra concept.
            BrdRequirement back = new BrdRequirement(still.id(), still.handle(), still.title(),
                still.text(), still.priority(), RequirementStatus.DRAFT, null);
            back.setCriteria(still.getCriteria());
            assertThat(brd.saveRequirement(back)).isEmpty();
            assertThat(requirement(store, projectId, "R2").isRetired()).isFalse();
        }
    }

    @Test
    void retiringACheckNamesTheStoryThatWasDeliveringIt() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = seed(store, storeDir);
            BrdServiceImpl brd = new BrdServiceImpl();
            BrdRequirement r2 = requirement(store, projectId, "R2");
            building(store, projectId, r2);
            String requirementId = r2.id().toString();

            // The first of two: the story still has one to deliver, and is told so.
            String first = brd.retireCriterion(requirementId,
                r2.criteria().get(0).id().toString());
            assertThat(first)
                .contains("That check is retired and kept on the record")
                .contains("S1 now delivers 1 check");

            // The second leaves it with nothing, which is the case that used to happen silently and
            // leave a story pointing at something that was not there.
            String second = brd.retireCriterion(requirementId,
                r2.criteria().get(1).id().toString());
            assertThat(second)
                .contains("S1 has nothing left to deliver")
                .contains("give it another check, or drop it");

            // Nothing was removed: both checks are still on the requirement, retired.
            BrdRequirement after = requirement(store, projectId, "R2");
            assertThat(after.criteria()).hasSize(2);
            assertThat(after.criteria()).allMatch(c -> c.status() == CriterionStatus.RETIRED);
            // …and the story's claim is untouched, because it is the record of what it was for.
            assertThat(store.listStories(projectId).get(0).criterionIds()).hasSize(2);
        }
    }

    @Test
    void restoringSaysWhatLeavesTheDocumentAndThatNothingIsLost() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = seed(store, storeDir);
            BrdServiceImpl brd = new BrdServiceImpl();
            long before = brd.brd().revision();

            BrdAuthoring.addRequirement(store, projectId, "Refunds",
                "A shopper can ask for a refund", "MEDIUM", null, null, null, null);

            String summary = brd.restoreSummary(before);
            assertThat(summary)
                .describedAs("it names what leaves, and says plainly that it can be got back")
                .contains("revision " + before)
                .contains("1 requirement written since then leaves the list: R3")
                .contains("Nothing is deleted")
                .contains("restoring a later one brings everything back");

            assertThat(brd.restoreSummary(99_999))
                .describedAs("and a revision that is not there says so rather than saying nothing")
                .contains("no revision 99999");
        }
    }

    // --- fixture ------------------------------------------------------------------------------

    /** R1 Checkout and R2 Guest checkout, R2 with two agreed checks, one of them passing. */
    private static UUID seed(ArtifactStore store, Path dir) {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "storefront", dir.toString(), List.of(),
            Instant.now(), false);
        store.saveProject(project);
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
            (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
            .withProjects(() -> List.of(project), () -> projectId, (n, p, c) -> null, id -> { }));

        BrdAuthoring.addRequirement(store, projectId, "Checkout",
            "A shopper can pay for what is in their basket", "HIGH", null, null, null, null);
        BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A shopper can check out without creating an account", "HIGH", null, null, null, null);
        BrdAuthoring.addCriterion(store, projectId, "R2", "an empty basket is refused",
            "GuestCheckoutTest#emptyBasket");
        BrdAuthoring.addCriterion(store, projectId, "R2", "a guest order is confirmed on screen",
            "GuestCheckoutTest#confirmed");

        Brd brd = store.getBrd(projectId);
        BrdRequirement r2 = find(brd, "R2");
        r2.setStatus(RequirementStatus.ACTIVE);
        for (AcceptanceCriterion c : r2.criteria()) {
            c.setStatus(CriterionStatus.ACCEPTED);
        }
        AcceptanceCriterion passing = r2.criteria().get(0);
        passing.setVerification(CriterionState.PASSING);
        passing.setLastVerifiedCommit("a1b2c3d");
        passing.setLastVerifiedAt(Instant.now());
        passing.setVerifiedAgainstContentRevision(r2.contentRevision());
        store.saveBrd(brd, "human", "agreed the checkout requirements");
        return projectId;
    }

    /** A story claiming both of R2's checks, actually being built. */
    private static Story building(ArtifactStore store, UUID projectId, BrdRequirement r2) {
        BacklogAuthoring.proposeStory(store, projectId, "Guests can pay", "R2:C1,R2:C2", null);
        Story story = store.listStories(projectId).get(0);
        story.setState(StoryState.RUNNING);
        story.setDeliveredCommit("deadbee");
        store.saveStory(story);
        return story;
    }

    private static BrdRequirement requirement(ArtifactStore store, UUID projectId, String handle) {
        return find(store.getBrd(projectId), handle);
    }

    private static BrdRequirement find(Brd brd, String handle) {
        for (BrdRequirement r : brd.requirements()) {
            if (handle.equals(r.handle())) {
                return r;
            }
        }
        throw new IllegalStateException("no " + handle);
    }
}
