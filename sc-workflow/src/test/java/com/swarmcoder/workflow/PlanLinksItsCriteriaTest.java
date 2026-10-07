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
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real case: a plan whose tasks are answerable for nothing.
 *
 * <p>Run {@code c7d6bcad} on 2026-08-30 planned the bookshelf demo into three sensible tasks and
 * parked at TEST_AUTHORING having written not one test. Every task reached the store with no
 * acceptance checks and no requirement links, against a story that answers for three checks
 * (R1:C1, R1:C2, R2:C1). The test author had nothing to write tests from, the acceptance stage ran
 * nothing, and the red-check correctly refused to dispatch a swarm that would pick a winner on no
 * evidence.
 *
 * <p>The planner was not at fault. It was asked for the links, its response carried them, and the
 * validator accepted the graph. {@link GreenfieldWorkflow} then attached a knowledge brief to each
 * task by rebuilding it through a twelve-argument constructor, and the four fields that
 * constructor does not take — including the criterion links — were gone from the graph that was
 * actually stored. The fix is that attaching a brief no longer copies the task, and that the
 * stored plan is checked as well as the accepted one.
 */
class PlanLinksItsCriteriaTest {

    private final TaskGraphValidator validator = new TaskGraphValidator();

    /** The story from the run: R1 with two checks, R2 with one. */
    private final StoryScope bookshelf = bookshelfStory();

    // ---------------------------------------------------------------- the plan must carry the links

    @Test
    void theBookshelfPlanThatShippedWithNoChecksIsRejected() {
        TaskGraph parked = new TaskGraph(UUID.randomUUID(), 1, null, List.of(
            task("Create shared Book model and BookLibraryService contract",
                "bookshelf-demo-shared/src/main/java"),
            task("Implement server-side BookLibraryService",
                "bookshelf-demo-server/src/main/java"),
            task("Build add-book form and book list view",
                "bookshelf-demo-client/src/main/java")),
            List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(parked, bookshelf, null);

        assertThat(verdict.ok()).isFalse();
        // One line naming the shape of the mistake, not three identical ones: no task in the plan
        // is linked to anything, which is a different fault from one task having missed a check.
        assertThat(verdict.violations()).hasSize(1);
        assertThat(verdict.violations().get(0))
            .contains("not one of the 3 task(s)")
            .contains("R1:C1")
            .contains("R1:C2")
            .contains("R2:C1");
    }

    @Test
    void theSameThreeTasksWithTheirChecksAssignedAreAccepted() {
        Task model = task("Create shared Book model and BookLibraryService contract",
            "bookshelf-demo-shared/src/main/java");
        Task service = task("Implement server-side BookLibraryService",
            "bookshelf-demo-server/src/main/java");
        Task ui = task("Build add-book form and book list view",
            "bookshelf-demo-client/src/main/java");
        service.setCriterionIds(Set.of(criterion("R1:C1")));
        ui.setCriterionIds(Set.of(criterion("R1:C2"), criterion("R2:C1")));

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(model, service, ui),
                List.of(new TaskEdge(model.id(), service.id()), new TaskEdge(model.id(), ui.id()))),
            bookshelf, null);

        assertThat(verdict.ok()).isTrue();
        // An enabler task that claims nothing is fine as long as something is built on it — what
        // may not happen is a CHECK claimed by nobody, or an enabler nothing depends on. The
        // model/contract task is exactly the first shape, wired to both tasks that use it.
        assertThat(model.criterionIds()).isEmpty();
    }

    @Test
    void aPlanThatLeavesOneCheckUnclaimedIsRejectedToo() {
        Task a = task("Implement server-side BookLibraryService",
            "bookshelf-demo-server/src/main/java");
        Task b = task("Build add-book form and book list view",
            "bookshelf-demo-client/src/main/java");
        a.setCriterionIds(Set.of(criterion("R1:C1")));
        b.setCriterionIds(Set.of(criterion("R1:C2")));

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(a, b), List.of()), bookshelf, null);

        // Partial coverage is a violation, not a warning: R2:C1 would never get a test written for
        // it, so it could only ever come out UNKNOWN, so the story could not honestly be delivered.
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations())
            .anyMatch(v -> v.contains("no task delivers criterion") && v.contains("R2:C1"));
    }

    // --------------------------------------------------------------- one check, exactly one task

    /**
     * Run {@code ba04471f}, 2026-09-03: one agreed check (R4:C1, "a rating can be assigned to a
     * book") was split by the planner across three tasks — model, server, client — because
     * nothing told it not to. The test author writes one file per check, and a task is verified
     * against exactly the files it claims, so two of the three tasks answered for a check with no
     * test file of their own; the run parked at TEST_AUTHORING. The validator now normalises this
     * away instead of rejecting the plan: the check stays on the leaf of the three — the client
     * task, since nothing else claiming the check depends on it — and the other two become plain
     * enablers.
     */
    @Test
    void aCheckSplitAcrossAChainOfThreeTasksIsNormalisedToTheLastOne() {
        BrdRequirement r4 = requirement("R4", "Rate a book", "A reader can rate a book",
            "a rating can be assigned to a book");
        List<UUID> sliced = new ArrayList<>();
        r4.criteria().forEach(c -> sliced.add(c.id()));
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(r4)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "A reader can rate a book", null, StoryState.READY, new ArrayList<>(), sliced, null, 0,
            StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        StoryScope scope = StoryScope.resolve(brd, story);
        UUID checkId = r4.criteria().get(0).id();

        Task model = task("Implement Book data model and RatingService",
            "bookshelf-demo-shared/src/main/java");
        Task server = task("Implement server-side RatingService and persistence",
            "bookshelf-demo-server/src/main/java");
        Task client = task("Implement client-side UI for rating books",
            "bookshelf-demo-client/src/main/java");
        model.setCriterionIds(Set.of(checkId));
        server.setCriterionIds(Set.of(checkId));
        client.setCriterionIds(Set.of(checkId));

        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null, List.of(model, server, client),
            List.of(new TaskEdge(model.id(), server.id()), new TaskEdge(server.id(), client.id())));

        TaskGraphValidator.Verdict verdict = validator.validate(graph, scope, null);

        assertThat(verdict.ok()).isTrue();
        assertThat(client.criterionIds()).containsExactly(checkId);
        assertThat(model.criterionIds()).isEmpty();
        assertThat(server.criterionIds()).isEmpty();
    }

    // ---------------------------------------------- the links must survive the rest of PLAN

    @Test
    void attachingAKnowledgeBriefDoesNotStripTheLinksOffATask() {
        Task planned = task("Implement server-side BookLibraryService",
            "bookshelf-demo-server/src/main/java");
        UUID requirement = UUID.randomUUID();
        UUID story = UUID.randomUUID();
        planned.setCriterionIds(Set.of(criterion("R1:C1")));
        planned.setRequirementIds(new java.util.HashSet<>(Set.of(requirement)));
        planned.setStoryId(story);

        UUID briefId = UUID.randomUUID();
        Task attached = GreenfieldWorkflow.withKnowledgeBrief(planned, briefId);

        assertThat(attached.knowledgeBriefId()).isEqualTo(briefId);
        // The four fields the old twelve-argument copy silently dropped.
        assertThat(attached.criterionIds()).containsExactly(criterion("R1:C1"));
        assertThat(attached.requirementIds()).containsExactly(requirement);
        assertThat(attached.storyId()).isEqualTo(story);
        assertThat(attached.acceptanceTestDir()).isEqualTo(planned.acceptanceTestDir());
    }

    @Test
    void aPlanStillPassesTheCheckAfterEveryTaskHasHadABriefAttached() {
        Task service = task("Implement server-side BookLibraryService",
            "bookshelf-demo-server/src/main/java");
        Task ui = task("Build add-book form and book list view",
            "bookshelf-demo-client/src/main/java");
        service.setCriterionIds(Set.of(criterion("R1:C1")));
        ui.setCriterionIds(Set.of(criterion("R1:C2"), criterion("R2:C1")));

        List<Task> stored = new ArrayList<>();
        for (Task task : List.of(service, ui)) {
            stored.add(GreenfieldWorkflow.withKnowledgeBrief(task, UUID.randomUUID()));
        }

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, stored, List.of()), bookshelf, null);

        assertThat(verdict.ok()).isTrue();
    }

    // ------------------------------------------------------------------ the diagnostic

    @Test
    void nothingWrittenAndNoChecksBlamesThePlan() {
        String reason = GreenfieldWorkflow.noTestsWrittenReason(3, 0, bookshelf, List.of());

        assertThat(reason)
            .contains("not one of the 3 task(s)")
            .contains("R1:C1, R1:C2, R2:C1")
            .contains("PLAN defect")
            .doesNotContain("unavailable");
    }

    /**
     * No per-task failure was recorded (the author simply wrote nothing) — the message must stay
     * honest rather than guess at a cause, which is what "check the testAuthor endpoint" used to
     * do until 2026-09-03 showed a case where the endpoint was never at fault.
     */
    @Test
    void nothingWrittenDespiteChecksAndNoRecordedFailureStaysHonest() {
        String reason = GreenfieldWorkflow.noTestsWrittenReason(3, 3, bookshelf, List.of());

        assertThat(reason)
            .contains("gave no reason why")
            .contains("The plan is fine")
            .doesNotContain("PLAN defect")
            .doesNotContain("testAuthor endpoint")
            .doesNotContain("endpoint outage");
    }

    /** The author's own failure reason is quoted, not replaced by a guess about the endpoint. */
    @Test
    void nothingWrittenDespiteChecksQuotesTheAuthorsOwnFailure() {
        String reason = GreenfieldWorkflow.noTestsWrittenReason(3, 3, bookshelf,
            List.of("Task 'add book': the test author's reply was not valid JSON (twice); "
                + "its reply is kept as blob abc123"));

        assertThat(reason)
            .contains("The plan is fine")
            .contains("its reply is kept as blob abc123")
            .doesNotContain("testAuthor endpoint")
            .doesNotContain("endpoint outage");
    }

    /**
     * The red-check message when the same check was written for a DIFFERENT task than the one
     * being asked about — the honest replacement for "Check the testAuthor endpoint", which was
     * wrong at least once (2026-09-03: the endpoint answered every time, the plan was the cause).
     * Asserted verbatim, since this is the exact wording an operator reads when a run parks.
     */
    @Test
    void theRedCheckMessageNamesBothTasksWhenTheSameCheckWasWrittenForAnotherTask() {
        Task client = task("Build add-book form and book list view",
            "bookshelf-demo-client/src/main/java");
        Task model = task("Create shared Book model and BookLibraryService contract",
            "bookshelf-demo-shared/src/main/java");
        client.setCriterionIds(Set.of(criterion("R1:C1")));
        model.setAuthoredTestPaths(
            List.of("bookshelf-demo-server/src/test/java/swarm/accept/R1C1AcceptTest.java"));

        String reason = GreenfieldWorkflow.noTestFileReason(client, List.of(criterionObj("R1:C1")),
            bookshelf, List.of(client, model));

        assertThat(reason).isEqualTo("Task 'Build add-book form and book list view' answers for "
            + "1 check(s) — R1:C1 — but the test file for R1:C1 was written for task 'Create "
            + "shared Book model and BookLibraryService contract' instead. The plan put this "
            + "check on more than one task; a check is answered by exactly one task, so only that "
            + "task ever gets a test file. Fix the plan, then resume the run.");
        assertThat(reason).doesNotContain("testAuthor endpoint");
    }

    /** No other task wrote the file either — a genuinely empty test-authoring outcome. */
    @Test
    void theRedCheckMessageStaysHonestWhenNoTaskWroteAnything() {
        Task service = task("Implement server-side BookLibraryService",
            "bookshelf-demo-server/src/main/java");
        service.setCriterionIds(Set.of(criterion("R1:C1")));

        String reason = GreenfieldWorkflow.noTestFileReason(service,
            List.of(criterionObj("R1:C1")), bookshelf, List.of(service));

        assertThat(reason)
            .contains("Implement server-side BookLibraryService")
            .contains("wrote no test file for it")
            .contains("TEST_AUTHORING log")
            .doesNotContain("testAuthor endpoint")
            .doesNotContain("Check the testAuthor");
    }

    // ------------------------------------------------------------------------------ fixture

    private UUID criterion(String ref) {
        UUID id = bookshelf.idForRef(ref);
        assertThat(id).as("fixture has %s", ref).isNotNull();
        return id;
    }

    private AcceptanceCriterion criterionObj(String ref) {
        UUID id = criterion(ref);
        return bookshelf.criteria().stream().filter(c -> c.id().equals(id)).findFirst()
            .orElseThrow();
    }

    private static Task task(String title, String writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, Set.of(writeSet), Set.of(),
            List.of(), ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    /** The bookshelf demo's story: R1 "add a book" with two checks, R2 "list books" with one. */
    private static StoryScope bookshelfStory() {
        BrdRequirement r1 = requirement("R1", "Add a book",
            "A reader can add a book to the shelf",
            "a book with a title and author is stored",
            "adding a book with no title is refused");
        BrdRequirement r2 = requirement("R2", "See the shelf",
            "A reader can see every book on the shelf",
            "every stored book appears in the list");

        List<UUID> sliced = new ArrayList<>();
        r1.criteria().forEach(c -> sliced.add(c.id()));
        r2.criteria().forEach(c -> sliced.add(c.id()));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(r1, r2)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "A reader can add books and see the shelf", null, StoryState.READY,
            new ArrayList<>(), sliced, null, 0, StoryOrigin.BACKLOG, null, null, "human",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }

    private static BrdRequirement requirement(String handle, String title, String statement,
                                              String... criteriaText) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title, statement,
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (String text : criteriaText) {
            // One test class per check. The plans below split a requirement's checks across
            // tasks, and checks that share a test class must be claimed by one task
            // (OneTestClassOneTaskTest) — so a shared class would fail these plans for a reason
            // that has nothing to do with the links they are about.
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text,
                "swarm.accept." + handle + "C" + (criteria.size() + 1) + "AcceptTest#proves");
            criterion.setStatus(CriterionStatus.ACCEPTED);
            criteria.add(criterion);
        }
        requirement.setCriteria(criteria);
        return requirement;
    }
}
