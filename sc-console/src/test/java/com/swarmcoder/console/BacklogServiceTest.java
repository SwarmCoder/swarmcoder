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

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.VerificationResult;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The operator-only actions over the backlog, and the publish contract the live panel depends on. */
class BacklogServiceTest {

    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @Test
    void aPublishedBacklogNeverAliasesTheStoredStories(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            seedStory(store);

            Backlog first = BacklogPublisher.build(store, projectId);
            // Unchanged state must produce an EQUAL value, or the signal would fire on every poll.
            assertThat(BacklogPublisher.build(store, projectId)).isEqualTo(first);

            // Now mutate the stored story IN PLACE, as every mutation path does.
            Story stored = store.listStories(projectId).get(0);
            stored.setState(StoryState.READY);
            store.saveStory(stored);

            Backlog second = BacklogPublisher.build(store, projectId);
            // If the published Backlog held the canonical Story instances, both snapshots would
            // contain the SAME object and compare equal — the signal dedups by equals(), so the
            // update would be silently dropped and the panel would show stale state forever.
            assertThat(second).isNotEqualTo(first);
            assertThat(first.stories().get(0).state()).isEqualTo(StoryState.DRAFT);
            assertThat(second.stories().get(0).state()).isEqualTo(StoryState.READY);
        }
    }

    @Test
    void promotingAcceptsAStoryIntoThePlanAndIsRecorded(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);
            setContext(store, (goal, kind) -> null);
            BacklogServiceImpl service = new BacklogServiceImpl();

            assertThat(service.promoteStory(story.id().toString())).isEmpty();
            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.READY);

            // Promotion is an operator decision, so it must be visible in the audit trail.
            List<ChangeEvent> history = service.history(story.id().toString());
            assertThat(history).isNotEmpty();
            assertThat(history.get(history.size() - 1).actor()).isEqualTo("human");

            // Promoting twice is a no-op the operator should be told about, not a silent success.
            assertThat(service.promoteStory(story.id().toString())).startsWith("error:");
        }
    }

    @Test
    void aStoryThatDeliversNothingCannotBePromoted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);
            story.setCriterionIds(List.of());
            store.saveStory(story);
            setContext(store, (goal, kind) -> null);

            assertThat(new BacklogServiceImpl().promoteStory(story.id().toString()))
                .startsWith("error:").contains("names nothing it would deliver");
        }
    }

    @Test
    void startingASessionBindsTheRunToTheStory(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);
            story.setState(StoryState.READY);
            store.saveStory(story);

            UUID runId = UUID.randomUUID();
            setContext(store, (goal, kind) -> {
                Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE, projectId,
                    null, null, null, null, Instant.now(), new RunReport(runId, goal));
                try {
                    // AWAIT, as the real intake does: startRun's contract is that the run is
                    // persisted by the time it returns an id. Returning before the write lands
                    // would leave callers racing a store they were told was ready.
                    store.append(() -> store.root().runs.put(runId, run)).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                // The goal must carry the slice, not just the story title, or the run is not
                // actually scoped to what the story delivers.
                assertThat(goal).contains("Guest can pay").contains("empty cart is rejected");
                return runId;
            });

            String result = new BacklogServiceImpl().startSession(story.id().toString());

            assertThat(result).isEqualTo(runId.toString());
            // The run is an ATTEMPT; the story is the durable work item. This link is what keeps
            // the requirement→commit trace intact when a story is retried.
            assertThat(store.root().runs.get(runId).storyId()).isEqualTo(story.id());
            Story after = store.getStory(story.id());
            assertThat(after.state()).isEqualTo(StoryState.RUNNING);
            assertThat(after.runIds()).containsExactly(runId);
        }
    }

    @Test
    void onlyAReadyStoryCanBeStarted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);   // DRAFT
            setContext(store, (goal, kind) -> UUID.randomUUID());

            // The refusal names the state in the operator's words, never READY (UX v3 §4).
            assertThat(new BacklogServiceImpl().startSession(story.id().toString()))
                .startsWith("error:").contains("ready to build").doesNotContain("READY");
        }
    }

    @Test
    void cancellingLeavesATombstoneRatherThanDeleting(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);
            setContext(store, (goal, kind) -> null);
            BacklogServiceImpl service = new BacklogServiceImpl();

            assertThat(service.cancelStory(story.id().toString())).isEmpty();

            // Still there, still listed, carrying its state and its history.
            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.CANCELLED);
            assertThat(store.listStories(projectId)).hasSize(1);
            assertThat(service.history(story.id().toString())).isNotEmpty();
        }
    }

    @Test
    void iterationsAreNamedBatchesAndDuplicatesAreRefused(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            setContext(store, (goal, kind) -> null);
            BacklogServiceImpl service = new BacklogServiceImpl();

            assertThat(service.addIteration("MVP checkout", "prove a guest can pay")).isEmpty();
            assertThat(service.addIteration("mvp checkout", "again")).startsWith("error:");
            assertThat(store.listIterations(projectId)).hasSize(1);

            Story story = seedStory(store);
            UUID iterationId = store.listIterations(projectId).get(0).id();
            assertThat(service.scheduleStory(story.id().toString(), iterationId.toString(), 3)).isEmpty();
            assertThat(store.getStory(story.id()).iterationId()).isEqualTo(iterationId);
            assertThat(store.getStory(story.id()).order()).isEqualTo(3);

            // A blank iteration id returns it to the unscheduled backlog.
            assertThat(service.scheduleStory(story.id().toString(), "", 0)).isEmpty();
            assertThat(store.getStory(story.id()).iterationId()).isNull();
        }
    }

    @Test
    void acceptingAStoryFlipsItsRequirementOnEvidence(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            // R1 with TWO accepted criteria; the story delivers only the first.
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can complete a purchase", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "empty cart is rejected", "T#empty");
            BrdAuthoring.addCriterion(store, projectId, "R1", "order is confirmed", "T#confirmed");
            acceptAllCriteria(store);
            var criteria = requirement(store).criteria();
            BacklogAuthoring.proposeStory(store, projectId, "Reject empty carts", "R1:C1", null);

            Story story = store.listStories(projectId).get(0);
            story.setState(StoryState.REVIEW);
            story.setDeliveredCommit("a1b2c3d");
            store.saveStory(story);
            setContext(store, (goal, kind) -> null);
            BacklogServiceImpl service = new BacklogServiceImpl();

            assertThat(service.acceptStory(story.id().toString())).isEmpty();

            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.DONE);
            // The delivered criterion carries its evidence: state, commit, and the wording it was
            // verified against.
            var after = requirement(store);
            assertThat(after.criteria().get(0).verification()).isEqualTo(CriterionState.PASSING);
            assertThat(after.criteria().get(0).lastVerifiedCommit()).isEqualTo("a1b2c3d");
            assertThat(after.criteria().get(1).verification()).isEqualTo(CriterionState.UNVERIFIED);

            // …but the REQUIREMENT is NOT implemented: one of its two accepted criteria is still
            // unproven. A requirement is not delivered because one slice of it was.
            assertThat(after.status()).isEqualTo(RequirementStatus.ACTIVE);

            // The append-only journal records the verification, not just the current state.
            assertThat(store.listVerifications(criteria.get(0).id())).hasSize(1);
            assertThat(store.listVerifications(criteria.get(0).id()).get(0).result())
                .isEqualTo(VerificationResult.PASSING);

            // Deliver the second criterion too — now the requirement is satisfied on evidence.
            BacklogAuthoring.proposeStory(store, projectId, "Confirm orders", "R1:C2", null);
            Story second = store.listStories(projectId).stream()
                .filter(s -> "S2".equals(s.key())).findFirst().orElseThrow();
            second.setState(StoryState.REVIEW);
            second.setDeliveredCommit("e4f5a6b");
            store.saveStory(second);

            assertThat(service.acceptStory(second.id().toString())).isEmpty();
            assertThat(requirement(store).status()).isEqualTo(RequirementStatus.IMPLEMENTED);
        }
    }

    @Test
    void onlyAStoryInReviewCanBeAccepted(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);   // DRAFT
            setContext(store, (goal, kind) -> null);

            // Accepting is the definition-of-done gate; it cannot be used to skip the work.
            assertThat(new BacklogServiceImpl().acceptStory(story.id().toString()))
                .startsWith("error:").contains("come back for your verdict")
                .doesNotContain("REVIEW");
        }
    }

    private com.swarmcoder.domain.BrdRequirement requirement(ArtifactStore store) {
        return store.getBrd(projectId).requirements().get(0);
    }

    /** Stands in for the operator accepting the agent's PROPOSED criteria in the editor. */
    private void acceptAllCriteria(ArtifactStore store) {
        var brd = store.getBrd(projectId);
        for (var r : brd.requirements()) {
            r.setStatus(RequirementStatus.ACTIVE);
            for (var c : r.criteria()) {
                c.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
            }
        }
        store.saveBrd(brd, "human", "accepted criteria");
    }

    /**
     * The most specific of the three sizing layers, set where the operator can see it: on the story.
     *
     * <p>Zero is what clears it, and clearing has to put the field back to "says nothing" rather
     * than to a written-down copy of the project's number — otherwise the run log would credit the
     * story for a decision nobody made.
     */
    @Test
    void aStoryCanAskForItsOwnNumberOfAttempts(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Story story = seedStory(store);
            setContext(store, (goal, kind) -> null);
            BacklogServiceImpl service = new BacklogServiceImpl();

            assertThat(store.getStory(story.id()).workersPerTask())
                .describedAs("a new story inherits").isNull();

            assertThat(service.setStoryWorkers(story.id().toString(), 8)).isEmpty();
            assertThat(store.getStory(story.id()).workersPerTask()).isEqualTo(8);

            assertThat(service.setStoryWorkers(story.id().toString(), 0)).isEmpty();
            assertThat(store.getStory(story.id()).workersPerTask())
                .describedAs("zero means inherit again, not zero attempts").isNull();

            // The change is on the record, in words, like every other operator decision.
            assertThat(service.history(story.id().toString()))
                .extracting(ChangeEvent::summary)
                .anyMatch(text -> text != null && text.contains("8 attempts"));

            // A typo guard, not a machine limit: the machine limit is the worker ceiling.
            assertThat(service.setStoryWorkers(story.id().toString(), 400))
                .startsWith("error:");
            assertThat(store.getStory(story.id()).workersPerTask()).isNull();
        }
    }

    private Story seedStory(ArtifactStore store) {
        BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
            "A guest can complete a purchase", "HIGH", null, null, null, null);
        BrdAuthoring.addCriterion(store, projectId, "R1", "empty cart is rejected", "T#empty");
        // The operator agrees it before anything is planned from it. Nothing is built from a
        // requirement nobody agreed, so a story on a DRAFT one is now refused outright — this
        // fixture used to skip the step and only worked because the refusal did not exist.
        acceptAllCriteria(store);
        BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1", null);
        return store.listStories(projectId).get(0);
    }

    private void setContext(ArtifactStore store,
                            java.util.function.BiFunction<String, String, UUID> intake) {
        ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), intake, r -> { }, r -> { })
            .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
    }
}
