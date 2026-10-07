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
package com.swarmcoder.store;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.IterationState;
import com.swarmcoder.domain.NfrCategory;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v7 backlog schema: stories, iterations, the task index, and the two append-only histories.
 * Guards the load-bearing claims of docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md — criteria belong to the
 * requirement and survive snapshotting, the task index does not duplicate tasks, nothing is ever
 * deleted, and history is genuinely append-only across a reopen (the EclipseStore lazy-store trap).
 */
class BacklogStoreTest {

    @Test
    void storiesAndIterationsSurviveReopenAndKeysStayUnique(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID iterationId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            store.saveIteration(new Iteration(iterationId, projectId, "MVP checkout",
                "prove a guest can pay", 1, IterationState.ACTIVE, Instant.now(), null));

            assertThat(store.nextStoryKey(projectId)).isEqualTo("S1");
            store.saveStory(story(projectId, "S1", "Guest checkout", iterationId, 1));
            assertThat(store.nextStoryKey(projectId)).isEqualTo("S2");
            store.saveStory(story(projectId, "S2", "Saved cards", iterationId, 2));
            // Unscheduled work sorts after everything in an iteration.
            store.saveStory(story(projectId, "S3", "Promo codes", null, 1));
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            List<Story> stories = reopened.listStories(projectId);
            assertThat(stories).extracting(Story::key).containsExactly("S1", "S2", "S3");
            assertThat(stories.get(0).state()).isEqualTo(StoryState.READY);
            assertThat(reopened.listIterations(projectId)).hasSize(1);
            assertThat(reopened.listIterations(projectId).get(0).name()).isEqualTo("MVP checkout");
            // Key minting is derived from what is stored, so it stays unique across a restart.
            assertThat(reopened.nextStoryKey(projectId)).isEqualTo("S4");
        }
    }

    @Test
    void taskIndexHoldsTheSameInstancesAsTheTaskGraph(@TempDir Path dir) throws Exception {
        UUID graphId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            Task task = new Task(taskId, 1, "Add GuestCart", "…", new HashSet<>(), new HashSet<>(),
                new ArrayList<>(), "src/test/java/swarm", null, null, null, TaskState.PENDING);
            task.setStoryId(storyId);
            TaskGraph graph = new TaskGraph(graphId, 1, UUID.randomUUID(),
                new ArrayList<>(List.of(task)), new ArrayList<>());
            store.append(() -> store.root().taskGraphs.put(graphId, graph)).get();
            store.indexTasks(graph.tasks());
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            Task fromIndex = reopened.getTask(taskId);
            Task fromGraph = reopened.root().taskGraphs.get(graphId).tasks().get(0);
            assertThat(fromIndex).isNotNull();
            // The point of the index: EclipseStore restores ONE object, referenced from both places.
            // If this ever becomes a copy, task state would diverge between the graph and the panel.
            assertThat(fromIndex).isSameAs(fromGraph);
            assertThat(reopened.storyTasks(storyId)).containsExactly(fromIndex);
        }
    }

    @Test
    void requirementCriteriaSurviveReopenAndSnapshotsDoNotAlias(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID criterionId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            Brd brd = store.ensureBrd(projectId);
            BrdRequirement req = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
                "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, "Checkout");
            AcceptanceCriterion c1 = new AcceptanceCriterion(criterionId, "empty cart is rejected",
                "GuestCheckoutTest#emptyCart");
            c1.setStatus(CriterionStatus.ACCEPTED);
            req.setCriteria(new ArrayList<>(List.of(c1)));
            brd.setRequirements(new ArrayList<>(List.of(req)));
            store.saveBrd(brd, "human", "added R7 with C1");

            // A second criterion arrives later; the FIRST snapshot must not gain it.
            BrdRequirement live = brd.requirements().get(0);
            List<AcceptanceCriterion> grown = new ArrayList<>(live.criteria());
            grown.add(new AcceptanceCriterion(UUID.randomUUID(), "guest order confirmed",
                "GuestCheckoutTest#confirmed"));
            live.setCriteria(grown);
            store.saveBrd(brd, "human", "added C2 to R7");
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            BrdRequirement loaded = reopened.getBrd(projectId).requirements().get(0);
            assertThat(loaded.criteria()).hasSize(2);
            assertThat(loaded.criteria().get(0).testClassOrFile()).isEqualTo("GuestCheckoutTest#emptyCart");
            assertThat(loaded.criteria().get(0).status()).isEqualTo(CriterionStatus.ACCEPTED);

            // The history snapshot is a deep copy: revision 2 still has exactly one criterion.
            Brd firstSnapshot = reopened.brdRevisionSnapshot(projectId, 2);
            assertThat(firstSnapshot.requirements().get(0).criteria()).hasSize(1);
        }
    }

    @Test
    void nonFunctionalRequirementsKeepTheirKindAndCategory(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Brd brd = store.ensureBrd(projectId);
            BrdRequirement functional = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
                "…", Priority.HIGH, RequirementStatus.ACTIVE, null);
            BrdRequirement nfr = new BrdRequirement(UUID.randomUUID(), "R12", "Latency",
                "p95 API latency < 200ms at 100 rps", Priority.HIGH, RequirementStatus.ACTIVE, null);
            nfr.setKind(RequirementKind.NON_FUNCTIONAL);
            nfr.setNfrCategory(NfrCategory.PERFORMANCE);
            brd.setRequirements(new ArrayList<>(List.of(functional, nfr)));
            store.saveBrd(brd);
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            List<BrdRequirement> reqs = reopened.getBrd(projectId).requirements();
            // A requirement with no recorded kind reads as functional, so pre-v7 rows stay sane.
            assertThat(reqs.get(0).kind()).isEqualTo(RequirementKind.FUNCTIONAL);
            assertThat(reqs.get(0).isNonFunctional()).isFalse();
            assertThat(reqs.get(1).isNonFunctional()).isTrue();
            assertThat(reqs.get(1).nfrCategory()).isEqualTo(NfrCategory.PERFORMANCE);
        }
    }

    @Test
    void changeJournalIsAppendOnlyAndSurvivesReopen(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            store.recordChange(projectId, "human", ChangeEntityType.STORY, storyId,
                ChangeKind.CREATED, "created S1 (Guest checkout)");
            store.recordChange(projectId, "agent", ChangeEntityType.STORY, storyId,
                ChangeKind.STATE_CHANGED, "state", "DRAFT", "READY", "promoted", null);
            store.recordChange(projectId, "system", ChangeEntityType.REQUIREMENT, UUID.randomUUID(),
                ChangeKind.STATE_CHANGED, "R7 → IMPLEMENTED");
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            // Three appends to an already-known list: without an explicit store() of the LIST this
            // reads back as one entry (the lazy-store trap that bit BrdRevision).
            List<ChangeEvent> journal = reopened.listChangeEvents(projectId);
            assertThat(journal).hasSize(3);
            assertThat(journal.get(0).actor()).isEqualTo("human");
            assertThat(journal.get(1).before()).isEqualTo("DRAFT");
            assertThat(journal.get(1).after()).isEqualTo("READY");

            // The trail of one entity is filterable out of the project journal.
            assertThat(reopened.changeHistory(projectId, storyId)).hasSize(2);
        }
    }

    @Test
    void criterionVerificationHistoryRecordsRegressionsNotJustTheLatest(@TempDir Path dir) throws Exception {
        UUID criterionId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            store.recordVerification(new CriterionVerification(UUID.randomUUID(), criterionId,
                requirementId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a1b2c3d", VerificationResult.PASSING, "GuestCheckoutTest#emptyCart", Instant.now()));
            store.recordVerification(new CriterionVerification(UUID.randomUUID(), criterionId,
                requirementId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "e4f5a6b", VerificationResult.FAILING, "GuestCheckoutTest#emptyCart", Instant.now()));
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            List<CriterionVerification> history = reopened.listVerifications(criterionId);
            // Both are kept: "when did this start passing, and when did it regress?" is the whole
            // reason this is a journal rather than a mutable status field.
            assertThat(history).hasSize(2);
            assertThat(history.get(0).result()).isEqualTo(VerificationResult.PASSING);
            assertThat(history.get(0).commitSha()).isEqualTo("a1b2c3d");
            assertThat(history.get(1).result()).isEqualTo(VerificationResult.FAILING);
        }
    }

    @Test
    void cancelledStoriesAreTombstonesNotDeletions(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();

        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story s = story(projectId, "S1", "Promo codes", null, 1);
            s.setId(storyId);
            store.saveStory(s);
            s.setState(StoryState.CANCELLED);
            store.saveStory(s);
        }

        try (ArtifactStore reopened = new ArtifactStore(dir)) {
            // Nothing is ever removed from a store map — the record stays, carrying its state.
            assertThat(reopened.getStory(storyId)).isNotNull();
            assertThat(reopened.listStories(projectId)).hasSize(1);
            assertThat(reopened.listStories(projectId).get(0).state()).isEqualTo(StoryState.CANCELLED);
        }
    }

    @Test
    void aPassRecordedAgainstOlderWordingReadsStaleNotGreen() {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), "guest can pay", "T#pay");
        c.setVerification(CriterionState.PASSING);
        c.setVerifiedAgainstContentRevision(3);

        assertThat(c.effectiveState(3)).isEqualTo(CriterionState.PASSING);
        // The requirement's text moved on: the pass is no longer evidence for the current wording.
        assertThat(c.effectiveState(4)).isEqualTo(CriterionState.STALE);
    }

    @Test
    void requirementStatusFollowsTheEvidenceAsTheBrdLives() {
        BrdRequirement req = new BrdRequirement(UUID.randomUUID(), "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, null);

        // No executable criteria: nothing can prove it, so it cannot claim to be implemented.
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.ACTIVE);

        AcceptanceCriterion c1 = passing("empty cart is rejected");
        req.setCriteria(new ArrayList<>(List.of(c1)));
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.IMPLEMENTED);
        req.setStatus(RequirementStatus.IMPLEMENTED);

        // THE LIVING-DOCUMENT RULE: a criterion discovered later means the requirement is no longer
        // fully satisfied, so it reverts rather than continuing to claim it is done.
        AcceptanceCriterion c2 = new AcceptanceCriterion(UUID.randomUUID(), "order is emailed", "T#email");
        c2.setStatus(CriterionStatus.ACCEPTED);
        req.setCriteria(new ArrayList<>(List.of(c1, c2)));
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.ACTIVE);

        // Satisfied again once the new criterion passes too.
        c2.setVerification(CriterionState.PASSING);
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.IMPLEMENTED);

        // A PROPOSED criterion is advisory: failing, it must not hold the requirement back.
        AcceptanceCriterion proposed = new AcceptanceCriterion(UUID.randomUUID(), "maybe", "T#maybe");
        proposed.setStatus(CriterionStatus.PROPOSED);
        req.setCriteria(new ArrayList<>(List.of(c1, c2, proposed)));
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.IMPLEMENTED);

        // Editing the requirement's wording stales the evidence — verified, but against older text.
        req.setContentRevision(req.contentRevision() + 1);
        assertThat(req.statusFromEvidence()).isEqualTo(RequirementStatus.ACTIVE);

        // DRAFT and DEPRECATED are operator-owned; evidence never promotes or resurrects them.
        BrdRequirement draft = new BrdRequirement(UUID.randomUUID(), "R8", "Saved cards", "…",
            Priority.LOW, RequirementStatus.DRAFT, null);
        draft.setCriteria(new ArrayList<>(List.of(passing("card is stored"))));
        assertThat(draft.statusFromEvidence()).isEqualTo(RequirementStatus.DRAFT);
    }

    private static AcceptanceCriterion passing(String text) {
        AcceptanceCriterion c = new AcceptanceCriterion(UUID.randomUUID(), text, "T#" + text.hashCode());
        c.setStatus(CriterionStatus.ACCEPTED);
        c.setVerification(CriterionState.PASSING);
        return c;
    }

    @Test
    void storyCopiesDoNotAliasTheCanonicalInstance() {
        Story canonical = story(UUID.randomUUID(), "S1", "Guest checkout", null, 1);
        canonical.setCriterionIds(new ArrayList<>(List.of(UUID.randomUUID())));

        Story copy = ArtifactStore.copyOf(canonical);
        assertThat(copy).isEqualTo(canonical);
        assertThat(copy).isNotSameAs(canonical);

        // The shared signal dedups by equals(), so a publish must carry a distinct object whose
        // collections cannot be mutated out from under it by the next edit.
        canonical.setCriterionIds(new ArrayList<>());
        assertThat(copy.criterionIds()).hasSize(1);
        assertThat(copy).isNotEqualTo(canonical);
    }

    private static Story story(UUID projectId, String key, String title, UUID iterationId, int order) {
        return new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title, null,
            StoryState.READY, new ArrayList<>(), new ArrayList<>(), iterationId, order,
            StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
    }
}
