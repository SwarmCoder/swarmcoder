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
import com.swarmcoder.domain.BrdEdge;
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
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.SourceRef;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.domain.VerificationResult;
import com.zeroz4j.api.BinaryRegistry;
import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.GrowableBuffer;
import com.zeroz4j.api.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backlog and requirement objects travel the wire AS THE DOMAIN MODEL — no DTOs (the
 * direct-model pattern). This guards the round trip through the zeroz4j binary serializer for every
 * type the Console will render, including the UUID/Instant/enum fields that have broken before.
 *
 * <p>The client half of the same guarantee is the TeaVM build of {@code sc-console-ui}: if a domain
 * type used a construct TeaVM cannot emulate, that module fails to compile. Both halves are needed —
 * this test alone would not catch it.
 */
class BacklogWireRoundTripTest {

    @BeforeAll
    static void loadRegistrars() {
        // Discovers the APT-generated registrar via ServiceLoader. Each module emits a uniquely
        // named registrar; a shared name would mean only one module's types were ever registered.
        BinaryRegistry.init();
    }

    @Test
    void storyRoundTripsWithItsIdsStateAndGitLinks() {
        UUID requirementId = UUID.randomUUID();
        UUID criterionId = UUID.randomUUID();
        Story story = new Story(UUID.randomUUID(), UUID.randomUUID(), "S3", StoryKind.DELIVERY,
            "Guest checkout", "as a guest I want to pay so that I need no account",
            StoryState.REVIEW, new ArrayList<>(List.of(requirementId)),
            new ArrayList<>(List.of(criterionId)), UUID.randomUUID(), 2, StoryOrigin.DISCOVERED,
            UUID.randomUUID(), "worker hit a missing guest session token", "agent",
            new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID())),
            "a1b2c3d", "e4f5a6b", 148, "https://example.invalid/pr/148",
            Instant.now(), Instant.now());

        Story back = roundTrip(story);

        assertThat(back).isEqualTo(story);
        assertThat(back.kind()).isEqualTo(StoryKind.DELIVERY);
        assertThat(back.state()).isEqualTo(StoryState.REVIEW);
        assertThat(back.origin()).isEqualTo(StoryOrigin.DISCOVERED);
        assertThat(back.criterionIds()).containsExactly(criterionId);
        assertThat(back.runIds()).hasSize(2);
        assertThat(back.prNumber()).isEqualTo(148);
        assertThat(back.deliveredCommit()).isEqualTo("a1b2c3d");
    }

    /**
     * The five fields added after the all-args constructor was already twenty-two arguments long
     * (dependsOnStoryIds, discoveredDependsOnStoryIds, dependencyRetries, waitingReason,
     * workersPerTask — see {@code Story}'s own javadoc on why they are set-only) sit on the wire
     * BETWEEN {@code order} and {@code origin}, declared in that position because that is where
     * they were added to the class. Every field the generated (de)serializer writes is purely
     * positional with no per-field tag, so this is exactly the shape of change that shifts every
     * field after it if the reader and writer ever disagree on the count — which is what happened
     * on 2026-08-29: a browser bundle built between two of these fields being added read every
     * later field, including this same {@code origin}, off by one slot, and one story's empty
     * {@code dependsOnStoryIds} landed a plain empty string where {@code StoryOrigin.valueOf}
     * expected a real constant name. A same-JVM round trip like this one cannot reproduce a
     * cross-build schema mismatch — writer and reader are always the same generated code here —
     * but it does pin that these five fields are actually on the wire at all, which the OTHER two
     * story tests in this file never exercised (both use the pre-existing 22-arg constructor and
     * leave every one of these five at its Java default).
     */
    @Test
    void storyRoundTripsTheFieldsAddedAfterTheAllArgsConstructor() {
        UUID dependsOn = UUID.randomUUID();
        UUID discovered = UUID.randomUUID();
        Story story = new Story(UUID.randomUUID(), UUID.randomUUID(), "S9", StoryKind.ENABLER,
            "extract cart module", "narrative", StoryState.READY, null, null, null, 1,
            StoryOrigin.BACKLOG, null, null, "human", null, null, null, null, null,
            Instant.now(), Instant.now());
        story.setDependsOnStoryIds(new ArrayList<>(List.of(dependsOn)));
        story.setDiscoveredDependsOnStoryIds(new ArrayList<>(List.of(discovered)));
        story.setDependencyRetries(2);
        story.setWaitingReason("waiting on S3 to deliver");
        story.setWorkersPerTask(6);

        Story back = roundTrip(story);

        assertThat(back).isEqualTo(story);
        assertThat(back.declaredDependsOn()).containsExactly(dependsOn);
        assertThat(back.discoveredDependsOn()).containsExactly(discovered);
        assertThat(back.dependencyRetries()).isEqualTo(2);
        assertThat(back.waitingReason()).isEqualTo("waiting on S3 to deliver");
        assertThat(back.workersPerTask()).isEqualTo(6);
        // The field immediately after all five, on the wire — proves nothing shifted.
        assertThat(back.origin()).isEqualTo(StoryOrigin.BACKLOG);
    }

    @Test
    void anEmptyStoryRoundTripsWithoutNullBlowingUp() {
        // Null enums, null ids and null lists are the normal state of a freshly drafted story.
        Story story = new Story(UUID.randomUUID(), UUID.randomUUID(), "S1", StoryKind.ENABLER,
            "extract cart module", null, StoryState.DRAFT, null, null, null, 0,
            StoryOrigin.BACKLOG, null, null, "human", null, null, null, null, null,
            Instant.now(), Instant.now());

        Story back = roundTrip(story);

        assertThat(back.key()).isEqualTo("S1");
        assertThat(back.kind()).isEqualTo(StoryKind.ENABLER);
        assertThat(back.narrative()).isNull();
        assertThat(back.prNumber()).isNull();
        assertThat(back.criterionIds()).isEmpty();
    }

    @Test
    void aBrdWithCriteriaAndAnNfrGateRoundTripsWhole() {
        UUID functionalId = UUID.randomUUID();
        UUID nfrId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "empty cart is rejected", "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        criterion.setVerification(CriterionState.PASSING);
        criterion.setLastVerifiedRunId(UUID.randomUUID());
        criterion.setLastVerifiedCommit("a1b2c3d");
        criterion.setLastVerifiedAt(Instant.now());
        criterion.setVerifiedAgainstContentRevision(3);

        BrdRequirement functional = new BrdRequirement(functionalId, "R7", "Guest checkout",
            "A guest can complete a purchase", Priority.HIGH, RequirementStatus.ACTIVE, "Checkout");
        functional.setCriteria(new ArrayList<>(List.of(criterion)));
        functional.setContentRevision(3);
        functional.setSourceRef(new SourceRef(documentId, "page 4, §2.1"));

        BrdRequirement nfr = new BrdRequirement(nfrId, "R12", "Latency",
            "p95 API latency < 200ms at 100 rps", Priority.HIGH, RequirementStatus.ACTIVE, null);
        nfr.setKind(RequirementKind.NON_FUNCTIONAL);
        nfr.setNfrCategory(NfrCategory.PERFORMANCE);

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 7, "Business Requirements",
            new ArrayList<>(List.of(functional, nfr)),
            new ArrayList<>(List.of(new BrdEdge(nfrId, functionalId, RequirementRelation.GATES))),
            Instant.now(), Instant.now());

        Brd back = roundTrip(brd);

        assertThat(back).isEqualTo(brd);
        BrdRequirement backFunctional = back.requirements().get(0);
        assertThat(backFunctional.criteria()).hasSize(1);
        assertThat(backFunctional.criteria().get(0).status()).isEqualTo(CriterionStatus.ACCEPTED);
        assertThat(backFunctional.criteria().get(0).verification()).isEqualTo(CriterionState.PASSING);
        assertThat(backFunctional.criteria().get(0).lastVerifiedCommit()).isEqualTo("a1b2c3d");
        assertThat(backFunctional.sourceRef().documentId()).isEqualTo(documentId);
        assertThat(back.requirements().get(1).nfrCategory()).isEqualTo(NfrCategory.PERFORMANCE);
        // GATES is the newest relation constant — an unregistered enum value fails here, not in the UI.
        assertThat(back.edges().get(0).relation()).isEqualTo(RequirementRelation.GATES);
    }

    @Test
    void iterationSourceDocumentAndHistoryTypesRoundTrip() {
        Iteration iteration = new Iteration(UUID.randomUUID(), UUID.randomUUID(), "MVP checkout",
            "prove a guest can pay", 3, IterationState.ACTIVE, Instant.now(), null);
        assertThat(roundTrip(iteration)).isEqualTo(iteration);

        SourceDocument document = new SourceDocument(UUID.randomUUID(), UUID.randomUUID(),
            "requirements.pdf", "application/pdf", "9f86d081884c7d65", "Extracted text…",
            "pdfbox", 24_576L, Instant.now());
        SourceDocument backDocument = roundTrip(document);
        assertThat(backDocument).isEqualTo(document);
        assertThat(backDocument.byteSize()).isEqualTo(24_576L);

        ChangeEvent event = new ChangeEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
            "agent", ChangeEntityType.STORY, UUID.randomUUID(), ChangeKind.STATE_CHANGED,
            "state", "DRAFT", "READY", "promoted from triage", UUID.randomUUID());
        ChangeEvent backEvent = roundTrip(event);
        assertThat(backEvent).isEqualTo(event);
        assertThat(backEvent.kind()).isEqualTo(ChangeKind.STATE_CHANGED);

        CriterionVerification verification = new CriterionVerification(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "e4f5a6b", VerificationResult.FAILING,
            "GuestCheckoutTest#email", Instant.now());
        assertThat(roundTrip(verification)).isEqualTo(verification);
    }

    /**
     * A Task, with the three Sets that used to keep it off the wire entirely.
     *
     * <p>Until ZeroZ Stack 0.4.0 the serializer had no tag for a Set, and a Task holds three of
     * them — the files a worker may write, the files it may read, and the requirements it answers.
     * The console shipped a five-field {@code BacklogTask} projection instead. That projection is
     * deleted and the backlog now carries this object, so this is the test that says the reason it
     * existed is really gone.
     */
    @Test
    void aTaskRoundTripsWithAllThreeOfItsSets() {
        UUID requirementId = UUID.randomUUID();
        UUID criterionId = UUID.randomUUID();
        Task task = new Task(UUID.randomUUID(), 4, "Add a guest checkout endpoint",
            "add POST /checkout/guest and its handler",
            new LinkedHashSet<>(List.of("src/checkout/Guest.java", "src/checkout/Routes.java")),
            new LinkedHashSet<>(List.of("src/cart/Cart.java")),
            new ArrayList<>(List.of(criterionWith("an empty cart is refused"))),
            "src/test/checkout", UUID.randomUUID(),
            new TokenBudget(120_000, 4_000, 400_000, 30),
            new SwarmPolicy(4, true, 0.2, 0.8, new ArrayList<>(List.of("careful", "fast"))),
            TaskState.DISPATCHED, new LinkedHashSet<>(List.of(requirementId)));
        task.setStoryId(UUID.randomUUID());
        task.setCriterionIds(new LinkedHashSet<>(List.of(criterionId)));
        task.setSelectedCandidateId(UUID.randomUUID());
        task.setCommitSha("a1b2c3d");

        Task back = roundTrip(task);

        assertThat(back).isEqualTo(task);
        assertThat(back.writeSet()).containsExactly(
            "src/checkout/Guest.java", "src/checkout/Routes.java");
        assertThat(back.readSet()).containsExactly("src/cart/Cart.java");
        assertThat(back.requirementIds()).containsExactly(requirementId);
        assertThat(back.criterionIds()).containsExactly(criterionId);
        assertThat(back.state()).isEqualTo(TaskState.DISPATCHED);
        assertThat(back.budget().maxToolTurns()).isEqualTo(30);
        assertThat(back.swarmPolicy().personaIds()).containsExactly("careful", "fast");
        assertThat(back.criteria().get(0).text()).isEqualTo("an empty cart is refused");
    }

    /**
     * What the test author did for a task travels with the task.
     *
     * <p>The backlog carries the task itself, so the record of its authored tests - the badge on
     * the run graph - crosses the wire inside it. Every field the generated serializer writes is
     * positional, so this pins that the record and the tests inside it are on the wire at all,
     * with their Instants, and that the deep copy the backlog signal publishes carries it too.
     */
    @Test
    void aTaskCarriesTheRecordOfItsAuthoredTests() {
        Task task = new Task(UUID.randomUUID(), 1, "Store a book", "",
            new LinkedHashSet<>(List.of("src/Book.java")), new LinkedHashSet<>(),
            new ArrayList<>(List.of(criterionWith("a book keeps its fields"))),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.1, 0.8, new ArrayList<>()), TaskState.PENDING);
        com.swarmcoder.domain.AuthoredTests record =
            com.swarmcoder.domain.AuthoredTests.started(1, Instant.now().minusSeconds(30));
        record.setWrittenAt(Instant.now());
        record.setFiles(new ArrayList<>(List.of("src/test/java/swarm/accept/BookTest.java")));
        record.setTests(new ArrayList<>(List.of(
            new com.swarmcoder.domain.AuthoredTest("swarm.accept.BookTest#storesAllBookFields",
                "src/test/java/swarm/accept/BookTest.java", "R1:C1", "a book keeps its fields"),
            new com.swarmcoder.domain.AuthoredTest("swarm.accept.BookTest#extra",
                "src/test/java/swarm/accept/BookTest.java", "", ""))));
        record.setProblems(new ArrayList<>());
        task.setAuthoredTests(record);

        Task back = roundTrip(task);

        assertThat(back).isEqualTo(task);
        assertThat(back.authoredTests().written()).isTrue();
        assertThat(back.authoredTests().checksOffered()).isEqualTo(1);
        assertThat(back.authoredTests().checksProved()).isEqualTo(1);
        assertThat(back.authoredTests().tests()).hasSize(2);
        assertThat(back.authoredTests().tests().get(0).provesText())
            .isEqualTo("a book keeps its fields");
        assertThat(back.authoredTests().tests().get(1).provesACheck()).isFalse();

        // In progress - the state the graph draws as "writing its tests" - round-trips too.
        Task writing = ArtifactStore.copyOf(task);
        writing.setAuthoredTests(com.swarmcoder.domain.AuthoredTests.started(2, Instant.now()));
        assertThat(roundTrip(writing).authoredTests().inProgress()).isTrue();

        // And a task from before this record existed comes back without one, not with an empty one.
        task.setAuthoredTests(null);
        assertThat(roundTrip(task).authoredTests()).isNull();
        assertThat(ArtifactStore.copyOf(back).authoredTests())
            .as("the deep copy the backlog signal publishes carries the record")
            .isEqualTo(record);
    }

    /**
     * A criterion with every field set.
     *
     * <p>Deliberately not the three-argument constructor. {@code AcceptanceCriterion.status()} and
     * {@code verification()} are NULL-SAFE READS — a null status means ACCEPTED — and the generated
     * serializer writes what those accessors return rather than the raw field. So a criterion left
     * with nulls comes back with them filled in, which is the same meaning but not the same object,
     * and an equality assertion on it fails for a reason that has nothing to do with what is being
     * tested. Noted here because it is easy to trip over twice.
     */
    private static AcceptanceCriterion criterionWith(String text) {
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text,
            "GuestCheckoutTest#emptyCart");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        criterion.setVerification(CriterionState.UNVERIFIED);
        return criterion;
    }

    /** The deep copy the backlog signal is published with must not share the task's sets. */
    @Test
    void copyOfATaskSharesNoCollectionWithTheOriginal() {
        Task task = new Task(UUID.randomUUID(), 1, "t", "i",
            new LinkedHashSet<>(List.of("a.java")), new LinkedHashSet<>(List.of("b.java")),
            new ArrayList<>(), null, null, null, null, TaskState.PENDING,
            new LinkedHashSet<>(List.of(UUID.randomUUID())));
        task.setCriterionIds(new LinkedHashSet<>(List.of(UUID.randomUUID())));

        Task copy = ArtifactStore.copyOf(task);

        assertThat(copy).isEqualTo(task);
        assertThat(copy.writeSet()).isNotSameAs(task.writeSet());
        assertThat(copy.readSet()).isNotSameAs(task.readSet());
        assertThat(copy.requirementIds()).isNotSameAs(task.requirementIds());
        assertThat(copy.criterionIds()).isNotSameAs(task.criterionIds());

        // The signal drops a publish whose value equals the retained one, so a copy that shared the
        // canonical task's sets would leave the board frozen with no error anywhere.
        task.writeSet().add("c.java");
        assertThat(copy.writeSet()).doesNotContain("c.java");
        assertThat(copy).isNotEqualTo(task);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) {
        GrowableBuffer out = new GrowableBuffer();
        ObjectMapper mapper = new ObjectMapper();
        BinarySerializer.writeValue(out, value, mapper);
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        return (T) BinarySerializer.readValue(in, new ObjectMapper());
    }
}
