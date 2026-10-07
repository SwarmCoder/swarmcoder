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
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.CriterionState;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of this feature, in one test: an uploaded document becomes requirements, a slice
 * of them becomes a story, a run delivers it, and the requirement ends up IMPLEMENTED carrying the
 * commit that proved it — with every step attributed in the audit journal.
 *
 * <p>The model is not involved. Everything an LLM would produce is written directly, so what is
 * under test is the CHAIN — the links between document, requirement, criterion, story, run and
 * commit — rather than an agent's ability to fill it in. A live run proves the other half.
 */
class RequirementToCommitTraceTest {

    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    @Test
    void aDocumentBecomesARequirementThatEndsUpImplementedByACommit(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID runId = UUID.randomUUID();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> {
                    Run run = new Run(runId, WorkflowKind.valueOf(kind), RunState.INTAKE,
                        projectId, null, null, null, null, Instant.now(),
                        new RunReport(runId, goal));
                    try {
                        store.append(() -> store.root().runs.put(runId, run)).get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return runId;
                }, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            BacklogServiceImpl backlog = new BacklogServiceImpl();

            // 1. INTAKE — the operator drops a requirements document.
            DocumentIngest.Result ingest = DocumentIngest.ingest(store, null, projectId,
                "checkout.md", "text/markdown",
                ("# Checkout\n\nA guest must be able to complete a purchase without creating "
                    + "an account.\n").getBytes(StandardCharsets.UTF_8));
            assertThat(ingest.failed()).isFalse();
            SourceDocument source = ingest.document();

            // 2. EXTRACTION — the BRD author writes a DRAFT requirement with a criterion.
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can complete a purchase without an account", "HIGH", "Checkout",
                null, null, new com.swarmcoder.domain.SourceRef(source.id(), "# Checkout"));
            BrdAuthoring.addCriterion(store, projectId, "R1",
                "a purchase completes with no account", "GuestCheckoutTest#noAccount");

            // Everything the agent wrote is provisional until the operator says otherwise.
            assertThat(requirement(store).status()).isEqualTo(RequirementStatus.DRAFT);
            assertThat(requirement(store).criteria().get(0).status())
                .isEqualTo(CriterionStatus.PROPOSED);
            assertThat(requirement(store).sourceRef().documentId()).isEqualTo(source.id());

            // 3. PROMOTION — the operator accepts the requirement and its criterion into scope.
            promote(store);
            UUID criterionId = requirement(store).criteria().get(0).id();

            // 4. PLANNING — a story slices exactly that criterion out of the requirement.
            BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1",
                "as a guest I want to pay so that I need no account");
            Story story = store.listStories(projectId).get(0);
            assertThat(story.state()).isEqualTo(StoryState.DRAFT);
            assertThat(story.criterionIds()).containsExactly(criterionId);

            assertThat(backlog.promoteStory(story.id().toString())).isEmpty();

            // 5. EXECUTION — a run is started, bound to the story.
            assertThat(backlog.startSession(story.id().toString())).isEqualTo(runId.toString());
            assertThat(store.root().runs.get(runId).storyId()).isEqualTo(story.id());
            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.RUNNING);

            // 6. DELIVERY — stands in for the workflow: criteria satisfied, gates held, commit
            // recorded. (GreenfieldWorkflow.recordDelivery does this for real; it needs a repo and
            // a swarm, so the chain either side of it is what this test pins.)
            Story running = store.getStory(story.id());
            running.setDeliveredCommit("a1b2c3d4e5f6");
            running.setIntegrationCommit("f6e5d4c3b2a1");
            running.setState(StoryState.REVIEW);
            store.saveStory(running);

            // 7. ACCEPTANCE — the operator's definition-of-done gate.
            assertThat(backlog.acceptStory(story.id().toString())).isEmpty();

            // --- the trace, end to end -------------------------------------------------------
            BrdRequirement finalRequirement = requirement(store);
            assertThat(store.getStory(story.id()).state()).isEqualTo(StoryState.DONE);
            assertThat(finalRequirement.status()).isEqualTo(RequirementStatus.IMPLEMENTED);

            AcceptanceCriterion criterion = finalRequirement.criteria().get(0);
            assertThat(criterion.verification()).isEqualTo(CriterionState.PASSING);
            assertThat(criterion.lastVerifiedCommit()).isEqualTo("a1b2c3d4e5f6");
            assertThat(criterion.lastVerifiedRunId()).isEqualTo(runId);

            // The journal is the truth, not the cached head.
            List<CriterionVerification> verifications = store.listVerifications(criterionId);
            assertThat(verifications).hasSize(1);
            assertThat(verifications.get(0).commitSha()).isEqualTo("a1b2c3d4e5f6");
            assertThat(verifications.get(0).testRef()).isEqualTo("GuestCheckoutTest#noAccount");

            // Document → requirement → criterion → story → run → commit, all reachable.
            assertThat(store.getSourceDocument(finalRequirement.sourceRef().documentId()).filename())
                .isEqualTo("checkout.md");
            assertThat(store.getStory(story.id()).runIds()).containsExactly(runId);

            // …and every step is attributed, so who decided what is answerable later.
            List<ChangeEvent> journal = store.listChangeEvents(projectId);
            assertThat(journal).extracting(ChangeEvent::actor).contains("agent", "human");
            // The words the operator actually reads. This assertion was left behind when the
            // status labels stopped being enum names, and has been red on master since.
            assertThat(journal).anyMatch(e -> "R1 went from agreed to delivered on accepting S1"
                .equals(e.summary()));
        }
    }

    @Test
    void rewordingARequirementAfterDeliveryStalesTheEvidence(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> null, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));

            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can pay", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "a purchase completes", "T#pay");
            promote(store);
            BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1", null);

            Story story = store.listStories(projectId).get(0);
            story.setState(StoryState.REVIEW);
            story.setDeliveredCommit("a1b2c3d");
            store.saveStory(story);
            assertThat(new BacklogServiceImpl().acceptStory(story.id().toString())).isEmpty();
            assertThat(requirement(store).status()).isEqualTo(RequirementStatus.IMPLEMENTED);

            // The operator changes what the requirement actually asks for.
            BrdAuthoring.updateRequirement(store, projectId, "R1", null,
                "A guest can pay by card without creating an account", null, null);

            // The old pass no longer vouches for the new wording: it renders STALE, and the
            // requirement stops claiming to be implemented. This is the living-document rule —
            // evidence is tied to the text it was gathered against.
            BrdRequirement after = requirement(store);
            assertThat(after.criteria().get(0).effectiveState(after.contentRevision()))
                .isEqualTo(CriterionState.STALE);
            assertThat(after.statusFromEvidence()).isEqualTo(RequirementStatus.ACTIVE);
        }
    }

    private BrdRequirement requirement(ArtifactStore store) {
        return store.getBrd(projectId).requirements().get(0);
    }

    /** Stands in for the operator promoting in the Requirements tab. */
    private void promote(ArtifactStore store) {
        Brd brd = store.getBrd(projectId);
        for (BrdRequirement r : brd.requirements()) {
            r.setStatus(RequirementStatus.ACTIVE);
            for (AcceptanceCriterion c : r.criteria()) {
                c.setStatus(CriterionStatus.ACCEPTED);
            }
        }
        store.saveBrd(brd, "human", "promoted");
    }
}
