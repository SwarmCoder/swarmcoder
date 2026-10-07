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

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.RequirementKind;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent-facing half of BRD intake: what the BRD author and backlog author can and — more
 * importantly — CANNOT do. Several of these guard rules that only matter because an agent is on the
 * other end: it may not promote its own extractions, and it may not put requirement content in a
 * story.
 */
class BrdIntakeAndBacklogTest {

    // --- intake ---------------------------------------------------------------------------------

    @Test
    void aTextDocumentIsExtractedPersistedAndDeduplicated(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        byte[] bytes = "# Checkout\n\nA guest must be able to pay.\n".getBytes(StandardCharsets.UTF_8);

        try (ArtifactStore store = new ArtifactStore(dir)) {
            DocumentIngest.Result first =
                DocumentIngest.ingest(store, null, projectId, "spec.md", "text/markdown", bytes);
            assertThat(first.failed()).isFalse();
            SourceDocument document = first.document();
            assertThat(document.extractedBy()).isEqualTo("passthrough");
            assertThat(document.extractedText()).contains("A guest must be able to pay.");
            assertThat(document.sha256()).isNotBlank();
            assertThat(document.byteSize()).isEqualTo(bytes.length);

            // Re-dropping the same file is a common accident; re-extracting it is wasted work, and
            // for a vision upload it would be a wasted model call.
            DocumentIngest.Result again =
                DocumentIngest.ingest(store, null, projectId, "spec-copy.md", "text/markdown", bytes);
            assertThat(again.document().id()).isEqualTo(document.id());
            assertThat(store.listSourceDocuments(projectId)).hasSize(1);
        }
    }

    @Test
    void anImageIsRefusedWithNamedConfigurationWhenNoVisionModelExists(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            DocumentIngest.Result result = DocumentIngest.ingest(store, null, UUID.randomUUID(),
                "wireframe.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

            // Refusing is the only safe behaviour: a text model handed an image does not fail, it
            // invents a plausible description, and those become fake requirements.
            assertThat(result.failed()).isTrue();
            assertThat(result.error()).contains("roles.vision");
            assertThat(store.listSourceDocuments(UUID.randomUUID())).isEmpty();
        }
    }

    @Test
    void anImageIsReadByTheVisionModelAndLabelledAsSuch(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        ConsoleContext.VisionModel vision = new ConsoleContext.VisionModel() {
            @Override
            public String describe(String prompt, String imageDataUri) {
                assertThat(imageDataUri).startsWith("data:image/png;base64,");
                return "A checkout wireframe with a Pay as guest button.";
            }

            @Override
            public String modelName() {
                return "test-vision";
            }
        };

        try (ArtifactStore store = new ArtifactStore(dir)) {
            DocumentIngest.Result result = DocumentIngest.ingest(store, vision, projectId,
                "wireframe.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

            assertThat(result.failed()).isFalse();
            // The provenance must survive: an operator reading these requirements has to know they
            // came from a model looking at a picture.
            assertThat(result.document().extractedBy()).isEqualTo("vision:test-vision");
            assertThat(result.document().extractedText()).contains("Pay as guest");
        }
    }

    @Test
    void anOversizedUploadIsRejectedBeforeExtraction(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            byte[] huge = new byte[DocumentIngest.MAX_UPLOAD_BYTES + 1];
            DocumentIngest.Result result =
                DocumentIngest.ingest(store, null, UUID.randomUUID(), "big.txt", "text/plain", huge);
            assertThat(result.failed()).isTrue();
            assertThat(result.error()).contains("limit");
        }
    }

    // --- BRD authoring --------------------------------------------------------------------------

    @Test
    void agentEditsAppearInTheRevisionHistory(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            store.ensureBrd(projectId);
            int before = store.listBrdRevisions(projectId).size();

            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can complete a purchase", "HIGH", null, null, null, null);

            // Regression guard: agent mutations used to call the one-arg saveBrd, which bumped the
            // revision but wrote NO snapshot — so agent changes were invisible in history/restore.
            List<com.swarmcoder.domain.BrdRevision> history = store.listBrdRevisions(projectId);
            assertThat(history).hasSizeGreaterThan(before);
            assertThat(history.get(history.size() - 1).author()).isEqualTo("agent");
            assertThat(history.get(history.size() - 1).summary()).contains("Guest checkout");
            // …and it is journalled for audit alongside the snapshot.
            assertThat(store.listChangeEvents(projectId)).isNotEmpty();
        }
    }

    @Test
    void theAgentCannotPromoteItsOwnExtractions(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH",
                null, null, null, null);

            String result = BrdAuthoring.updateRequirement(store, projectId, "R1",
                null, null, null, "ACTIVE");

            // Promotion is the operator accepting the requirement into scope. An agent that could
            // promote its own extraction would make the DRAFT gate decorative.
            assertThat(result).startsWith("error:").contains("operator");
            assertThat(requirement(store, projectId, "R1").status()).isEqualTo(RequirementStatus.DRAFT);
        }
    }

    @Test
    void criteriaLandProposedAndTheAgentCannotAcceptThem(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH",
                null, null, null, null);

            String added = BrdAuthoring.addCriterion(store, projectId, "R1",
                "an empty cart is rejected", "GuestCheckoutTest#emptyCart");
            assertThat(added).startsWith("R1:C1").contains("PROPOSED");
            assertThat(requirement(store, projectId, "R1").criteria().get(0).status())
                .isEqualTo(CriterionStatus.PROPOSED);

            String accept = BrdAuthoring.updateCriterion(store, projectId, "R1:C1",
                null, null, "ACCEPTED");
            assertThat(accept).startsWith("error:").contains("operator");
            assertThat(requirement(store, projectId, "R1").criteria().get(0).status())
                .isEqualTo(CriterionStatus.PROPOSED);
        }
    }

    @Test
    void aNonFunctionalRequirementNeedsACategory(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            String refused = BrdAuthoring.addRequirement(store, projectId, "Latency",
                "p95 under 200ms", "HIGH", null, "non_functional", null, null);
            assertThat(refused).startsWith("error:").contains("category");

            String added = BrdAuthoring.addRequirement(store, projectId, "Latency",
                "p95 API latency < 200ms at 100 rps", "HIGH", null, "non_functional", "performance", null);
            assertThat(added).startsWith("R1");
            BrdRequirement nfr = requirement(store, projectId, "R1");
            assertThat(nfr.kind()).isEqualTo(RequirementKind.NON_FUNCTIONAL);
            assertThat(nfr.isNonFunctional()).isTrue();
        }
    }

    @Test
    void onlyANonFunctionalRequirementMayGateAnother(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Checkout", "…", "HIGH", null, null, null, null);
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH", null, null, null, null);
            BrdAuthoring.addRequirement(store, projectId, "Latency", "p95 < 200ms", "HIGH",
                null, "non_functional", "performance", null);

            assertThat(BrdAuthoring.addEdge(store, projectId, "R2", "gates", "R1"))
                .startsWith("error:").contains("non-functional");
            assertThat(BrdAuthoring.addEdge(store, projectId, "R3", "gates", "R1"))
                .contains("gates");
        }
    }

    @Test
    void rewordingARequirementStalesEvidenceGatheredAgainstTheOldWording(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout",
                "A guest can pay", "HIGH", null, null, null, null);
            long before = requirement(store, projectId, "R1").contentRevision();

            BrdAuthoring.updateRequirement(store, projectId, "R1", null,
                "A guest can pay by card without creating an account", null, null);

            assertThat(requirement(store, projectId, "R1").contentRevision()).isGreaterThan(before);
        }
    }

    // --- backlog authoring ----------------------------------------------------------------------

    @Test
    void aStoryIsASliceOfCriteriaAndCannotInventRequirementContent(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "empty cart rejected", "T#empty");
            BrdAuthoring.addCriterion(store, projectId, "R1", "order confirmed", "T#confirmed");
            // Nothing is built from a requirement the operator has not agreed, so agree it first.
            promote(store, projectId);

            // A story with no criteria would be a free-text description of work — the exact second
            // source of truth this design exists to prevent.
            assertThat(BacklogAuthoring.proposeStory(store, projectId, "Do checkout", "", null))
                .startsWith("error:").contains("propose the requirement");
            assertThat(BacklogAuthoring.proposeStory(store, projectId, "Guest checkout", "R1:C9", null))
                .startsWith("error:").contains("does not exist");

            String created = BacklogAuthoring.proposeStory(store, projectId,
                "Guest can pay", "R1:C1,R1:C2", "as a guest I want to pay so that I need no account");
            assertThat(created).startsWith("S1").contains("DRAFT");

            List<Story> stories = store.listStories(projectId);
            assertThat(stories).hasSize(1);
            Story story = stories.get(0);
            assertThat(story.kind()).isEqualTo(StoryKind.DELIVERY);
            assertThat(story.state()).isEqualTo(StoryState.DRAFT);
            assertThat(story.criterionIds()).hasSize(2);
            assertThat(story.requirementIds()).hasSize(1);
            assertThat(story.author()).isEqualTo("agent");
        }
    }

    @Test
    void anEnablerLinksToWhatItUnblocksAndDeliversNothing(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH", null, null, null, null);
            promote(store, projectId);   // an enabler answers to an agreed requirement too

            String created = BacklogAuthoring.proposeEnabler(store, projectId,
                "extract cart module", "R1", "the cart is entangled with the session");
            assertThat(created).startsWith("S1");

            Story story = store.listStories(projectId).get(0);
            assertThat(story.kind()).isEqualTo(StoryKind.ENABLER);
            assertThat(story.criterionIds()).isEmpty();
            assertThat(story.requirementIds()).hasSize(1);
        }
    }

    @Test
    void coverageNamesUnclaimedCriteriaAndUnverifiableRequirements(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "empty cart rejected", "T#empty");
            BrdAuthoring.addCriterion(store, projectId, "R1", "order confirmed", "T#confirmed");
            BrdAuthoring.addRequirement(store, projectId, "Saved cards", "…", "LOW", null, null, null, null);
            // Coverage only speaks about ACTIVE requirements, so promote them as the operator would.
            promote(store, projectId);

            BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1", null);

            String coverage = BacklogAuthoring.coverage(store, projectId);
            assertThat(coverage).contains("R1").contains("C2");   // claimed C1, so only C2 is a gap
            assertThat(coverage).doesNotContain("C1 ");
            assertThat(coverage).contains("Saved cards");          // ACTIVE but has no criteria at all
        }
    }

    @Test
    void iterationsAreNamedOrderedBatchesAndStoriesScheduleIntoThem(@TempDir Path dir) throws Exception {
        UUID projectId = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(dir)) {
            BrdAuthoring.addRequirement(store, projectId, "Guest checkout", "…", "HIGH", null, null, null, null);
            BrdAuthoring.addCriterion(store, projectId, "R1", "empty cart rejected", "T#empty");
            promote(store, projectId);
            BacklogAuthoring.proposeStory(store, projectId, "Guest can pay", "R1:C1", null);

            assertThat(BacklogAuthoring.addIteration(store, projectId, "MVP checkout", "prove a guest can pay"))
                .contains("MVP checkout");
            assertThat(BacklogAuthoring.addIteration(store, projectId, "mvp checkout", "again"))
                .startsWith("error:");

            assertThat(BacklogAuthoring.scheduleStory(store, projectId, "S1", "MVP checkout", "1"))
                .contains("MVP checkout");
            assertThat(store.listStories(projectId).get(0).iterationId()).isNotNull();

            assertThat(BacklogAuthoring.scheduleStory(store, projectId, "S1", "backlog", null))
                .contains("unscheduled");
            assertThat(store.listStories(projectId).get(0).iterationId()).isNull();
        }
    }

    private static BrdRequirement requirement(ArtifactStore store, UUID projectId, String handle) {
        Brd brd = store.getBrd(projectId);
        for (BrdRequirement r : brd.requirements()) {
            if (handle.equalsIgnoreCase(r.handle())) {
                return r;
            }
        }
        throw new AssertionError("no requirement " + handle);
    }

    /** Stands in for the operator promoting everything in the Requirements tab. */
    private static void promote(ArtifactStore store, UUID projectId) {
        Brd brd = store.getBrd(projectId);
        for (BrdRequirement r : brd.requirements()) {
            r.setStatus(RequirementStatus.ACTIVE);
        }
        store.saveBrd(brd, "human", "promoted all");
    }
}
