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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sentence an operator reads before they change something somebody is already building, and the
 * rule that nothing in the requirements is destroyed.
 *
 * <p>Both halves are here because they are the same decision seen twice: retiring is what "delete"
 * now does, and the sentence is what makes retiring or rewording a choice rather than a surprise.
 */
class RequirementImpactTest {

    @Test
    void aRequirementNobodyHasCommittedToSaysNothingAboutEditing() {
        BrdRequirement r = requirement("R4", "Audit log");
        r.setCriteria(new ArrayList<>(List.of(proposed("every attempt is recorded"))));

        RequirementImpact impact = RequirementImpact.of(r, List.of());

        // A proposed check is a suggestion nobody agreed to, so there is nothing to warn about and
        // the editor saves without asking. A dialog here would be the "are you sure" that teaches
        // people to click through without reading.
        assertThat(impact.isEmpty()).isTrue();
        assertThat(impact.editSentence()).isEmpty();
    }

    @Test
    void editingWorkAStoryIsBuildingNamesTheCostAndTheStory() {
        BrdRequirement r = requirement("R2", "Guest checkout");
        AcceptanceCriterion passing = agreed("an empty basket is refused");
        passing.setVerification(CriterionState.PASSING);
        passing.setVerifiedAgainstContentRevision(r.contentRevision());
        passing.setLastVerifiedAt(Instant.now());
        AcceptanceCriterion failing = agreed("a guest order is confirmed");
        failing.setVerification(CriterionState.FAILING);
        r.setCriteria(new ArrayList<>(List.of(passing, failing)));

        Story building = story("S1", StoryState.RUNNING, List.of(passing.id(), failing.id()));

        String sentence = RequirementImpact.of(r, List.of(building)).editSentence();

        assertThat(sentence)
            .isEqualTo("Editing R2 affects 2 agreed checks and 1 story. 1 test that passes today "
                + "will stop counting and has to be run again against the new wording. S1 will be "
                + "sent back to be built again.");
    }

    @Test
    void aStoryNobodyHasStartedIsNotSentBack() {
        BrdRequirement r = requirement("R2", "Guest checkout");
        AcceptanceCriterion c = agreed("an empty basket is refused");
        r.setCriteria(new ArrayList<>(List.of(c)));

        // Ready, delivered and dropped are all left alone. A story nobody has started has nothing
        // to be sent back from; delivered work is never churned by somebody typing in a text box,
        // and its evidence going stale is already visible on its own.
        for (StoryState state : List.of(StoryState.DRAFT, StoryState.READY, StoryState.DONE)) {
            RequirementImpact impact =
                RequirementImpact.of(r, List.of(story("S1", state, List.of(c.id()))));
            assertThat(impact.sentBackKeys()).describedAs(state.name()).isEmpty();
            assertThat(impact.editSentence()).doesNotContain("sent back");
        }
        for (StoryState state : List.of(StoryState.RUNNING, StoryState.REVIEW, StoryState.BLOCKED)) {
            assertThat(RequirementImpact.of(r, List.of(story("S1", state, List.of(c.id()))))
                .sentBackKeys()).describedAs(state.name()).containsExactly("S1");
        }
        // A dropped story is not counted at all: it claims nothing any more.
        assertThat(RequirementImpact.of(r,
                List.of(story("S1", StoryState.CANCELLED, List.of(c.id())))).storyKeys()).isEmpty();
    }

    @Test
    void retiringAlwaysSaysThatNothingIsLost() {
        BrdRequirement r = requirement("R2", "Guest checkout");
        AcceptanceCriterion c = agreed("an empty basket is refused");
        r.setCriteria(new ArrayList<>(List.of(c)));

        String sentence = RequirementImpact.of(r,
            List.of(story("S1", StoryState.RUNNING, List.of(c.id())))).retireSentence();

        assertThat(sentence)
            .contains("Retiring R2 takes 1 agreed check and 1 story.")
            .contains("Nothing is deleted")
            .contains("bring it back by setting it back to a draft")
            .contains("S1 will be sent back to be built again.");

        // …and it says it even when there is nothing downstream, because the control it explains
        // used to say Delete and used to mean it.
        BrdRequirement bare = requirement("R9", "Something nobody uses");
        assertThat(RequirementImpact.of(bare, List.of()).retireSentence())
            .contains("takes it out of the list and out of every count")
            .contains("Nothing is deleted");
    }

    @Test
    void aRetiredRequirementStopsCountingAndKeepsEverything() {
        BrdRequirement r = requirement("R2", "Guest checkout");
        AcceptanceCriterion passing = agreed("an empty basket is refused");
        passing.setVerification(CriterionState.PASSING);
        passing.setVerifiedAgainstContentRevision(r.contentRevision());
        r.setCriteria(new ArrayList<>(List.of(passing)));

        assertThat(r.gatingCriteria()).containsExactly(passing);
        assertThat(CheckCounts.of(r).gating()).isEqualTo(1);

        r.setStatus(RequirementStatus.DEPRECATED);

        // Out of every count and out of every gate…
        assertThat(r.isRetired()).isTrue();
        assertThat(r.gatingCriteria()).isEmpty();
        assertThat(CheckCounts.of(r)).isEqualTo(CheckCounts.EMPTY);
        // …and nothing is gone: the check, its wording and the evidence gathered against it are all
        // still there, which is the whole reason retiring replaced deleting.
        assertThat(r.criteria()).containsExactly(passing);
        assertThat(r.criteria().get(0).effectiveState(r.contentRevision()))
            .isEqualTo(CriterionState.PASSING);
    }

    private static BrdRequirement requirement(String handle, String title) {
        return new BrdRequirement(UUID.randomUUID(), handle, title, title,
            Priority.MEDIUM, RequirementStatus.ACTIVE, null);
    }

    private static AcceptanceCriterion agreed(String text) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text, "T#" + text.hashCode());
        c.setStatus(CriterionStatus.ACCEPTED);
        return c;
    }

    private static AcceptanceCriterion proposed(String text) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text, null);
        c.setStatus(CriterionStatus.PROPOSED);
        return c;
    }

    private static Story story(String key, StoryState state, List<UUID> criterionIds) {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), key, StoryKind.DELIVERY,
            "a story", null, state, new ArrayList<>(), new ArrayList<>(criterionIds), null, 0,
            StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
    }
}
