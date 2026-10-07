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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
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

class TaskGraphValidatorTest {

    private final TaskGraphValidator validator = new TaskGraphValidator();

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static TaskGraph graph(List<Task> tasks, List<TaskEdge> edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, edges);
    }

    private static Task protectedTask(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(), ArchitectClient.ACCEPTANCE_TEST_DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    /**
     * Seen live on 2026-08-28. The planner produced a task called "implement acceptance tests for
     * multiplication" whose write set was the acceptance-test file. Workers may not touch those —
     * that is what stops a worker making its own gate pass — so the task was impossible, and
     * nothing checked. Two workers were dispatched at it, every edit was reverted, and the run
     * spent its whole budget on work nobody was allowed to do.
     */
    @Test
    void rejectsATaskThatMayWriteNothing() {
        Task impossible = protectedTask("write the acceptance tests",
            Set.of("src/test/java/swarm/accept/MultiplyAcceptTest.java"));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(impossible), List.of()));

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations().get(0))
            .contains("may write nothing").contains("write the acceptance tests");
    }

    @Test
    void aTaskWithOneWritablePathIsFineEvenIfAnotherIsProtected() {
        // Partly protected is not impossible: the worker can still do the part it is allowed to,
        // and the path policy refuses the rest edit by edit. Only "nothing at all" is a violation.
        Task mixed = protectedTask("implement and test",
            Set.of("src/main/java/calc/Multiply.java", "src/test/java/swarm/accept/X.java"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(mixed), List.of()));

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void anUnrestrictedWriteSetIsStillAllowed() {
        // No write set means unrestricted, which is what the single-task fallback graph uses.
        Task open = protectedTask("do everything", Set.of());

        assertThat(validator.validate(graph(List.of(open), List.of())).ok()).isTrue();
    }

    @Test
    void acceptsDisjointConcurrentTasks() {
        Task a = task("frontend", Set.of("src/web"));
        Task b = task("backend", Set.of("src/api"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a, b), List.of()));

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void rejectsOverlappingWriteSetsOnConcurrentTasks() {
        Task a = task("frontend", Set.of("src/web", "src/shared"));
        Task b = task("backend", Set.of("src/shared/config"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a, b), List.of()));

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations().get(0)).contains("overlap").contains("src/shared");
    }

    @Test
    void allowsOverlapWhenTasksAreOrderedByDependency() {
        Task a = task("first", Set.of("src/shared"));
        Task b = task("second", Set.of("src/shared"));

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(List.of(a, b), List.of(new TaskEdge(a.id(), b.id()))));

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void allowsOverlapWhenOrderedTransitively() {
        Task a = task("first", Set.of("src/shared"));
        Task b = task("middle", Set.of("src/other"));
        Task c = task("third", Set.of("src/shared"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a, b, c),
            List.of(new TaskEdge(a.id(), b.id()), new TaskEdge(b.id(), c.id()))));

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void rejectsCycles() {
        Task a = task("a", Set.of("x"));
        Task b = task("b", Set.of("y"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a, b),
            List.of(new TaskEdge(a.id(), b.id()), new TaskEdge(b.id(), a.id()))));

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("cycle"));
    }

    @Test
    void rejectsEmptyGraphAndUnknownEdgeTargets() {
        assertThat(validator.validate(graph(List.of(), List.of())).ok()).isFalse();

        Task a = task("a", Set.of("x"));
        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a),
            List.of(new TaskEdge(a.id(), UUID.randomUUID()))));
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("unknown task"));
    }

    @Test
    void missingCriteriaIsAWarningNotAViolationForNow() {
        Task a = task("a", Set.of("x"));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a), List.of()));

        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.warnings()).anyMatch(w -> w.contains("acceptance criteria"));
    }

    // --- story-scoped coverage -----------------------------------------------------------------

    @Test
    void everyCriterionInTheSliceMustBeClaimedByATask() {
        StoryScope scope = scopeOf("empty cart is rejected", "order is confirmed");
        Task a = task("a", Set.of("src/cart"));
        a.setCriterionIds(Set.of(scope.criteria().get(0).id()));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a), List.of()), scope);

        // An unclaimed criterion means the story can never legitimately reach REVIEW, so accepting
        // this graph would guarantee a run that looks successful and delivers less than it promised.
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations())
            .anyMatch(v -> v.contains("no task delivers criterion") && v.contains("C2"));
    }

    @Test
    void aTaskMayNotClaimACriterionOutsideTheSlice() {
        StoryScope scope = scopeOf("empty cart is rejected");
        Task a = task("a", Set.of("src/cart"));
        a.setCriterionIds(Set.of(scope.criteria().get(0).id(), UUID.randomUUID()));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a), List.of()), scope);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("outside the story's slice"));
    }

    @Test
    void aFullyCoveredSlicePasses() {
        StoryScope scope = scopeOf("empty cart is rejected", "order is confirmed");
        Task a = task("a", Set.of("src/cart"));
        a.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        Task b = task("b", Set.of("src/order"));
        b.setCriterionIds(Set.of(scope.criteria().get(1).id()));

        TaskGraphValidator.Verdict verdict = validator.validate(graph(List.of(a, b), List.of()), scope);

        assertThat(verdict.ok()).isTrue();
        // Coverage replaces the task-local criteria warning: the criteria live on the requirement.
        assertThat(verdict.warnings()).noneMatch(w -> w.contains("acceptance criteria"));
    }

    @Test
    void anUnscopedRunIsUnaffected() {
        Task a = task("a", Set.of("x"));
        assertThat(validator.validate(graph(List.of(a), List.of()), null).ok()).isTrue();
    }

    // --- a contract naming the acceptance test class is not held to "delivered by a task" ------

    /**
     * Harness run 19, 2026-09-03. The architect listed the acceptance test class itself,
     * {@code swarm.accept.PersistenceTest}, as a contract. Nothing may deliver it — acceptance
     * tests are written only by the test author, never a task — so this rule must not demand a
     * task claim it, even though the plan below claims nothing at all. The REAL contract in the
     * same design, naming an actual type, is still required exactly as before.
     */
    @Test
    void aContractNamingTheAcceptanceTestClassIsExemptButARealContractIsStillRequired() {
        ApiContract real = new ApiContract(UUID.randomUUID(), "BookApi", "the book type", null,
            "com.acme.demo.bookshelf.Book", List.of("String title()"));
        ApiContract testClass = new ApiContract(UUID.randomUUID(), "PersistenceTest",
            "the acceptance test", null, "swarm.accept.PersistenceTest", List.of());
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(),
            new ArrayList<>(), List.of(real, testClass), List.of(), null, Instant.now());
        Task a = task("Implement Book", Set.of("src/main/java/bookshelf"));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(a), List.of()), null, null, design);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).hasSize(1);
        assertThat(verdict.violations().get(0)).contains("com.acme.demo.bookshelf.Book");
        assertThat(verdict.violations()).noneMatch(v -> v.contains("PersistenceTest"));
    }

    @Test
    void aDesignNamingOnlyTheAcceptanceTestClassAsAContractValidatesCleanly() {
        ApiContract testClass = new ApiContract(UUID.randomUUID(), "PersistenceTest",
            "the acceptance test", null, "swarm.accept.PersistenceTest", List.of());
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "goal", List.of(),
            new ArrayList<>(), List.of(testClass), List.of(), null, Instant.now());
        Task a = task("Implement the store", Set.of("src/main/java/store"));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(a), List.of()), null, null, design);

        assertThat(verdict.ok()).isTrue();
    }

    // --- one check, exactly one task ------------------------------------------------------------

    /**
     * The real case (run {@code ba04471f}, 2026-09-03): the planner split one agreed check across
     * three tasks — model, server, client — because nothing told it not to. The test author writes
     * one file per check, so two of the three tasks answered for a check with no test file of
     * their own. Rather than reject a plan a reasonable planner might produce, the validator
     * normalises it: the check stays on the leaf of the three (nothing else claiming it depends on
     * the client task), and is stripped from the other two, which become plain enablers.
     */
    @Test
    void oneCheckOnAChainOfThreeTasksIsNormalisedToTheLastOne() {
        StoryScope scope = scopeOf("a rating can be assigned to a book");
        UUID checkId = scope.criteria().get(0).id();
        Task model = task("Implement Book data model and RatingService",
            Set.of("bookshelf-demo-shared/src/main/java"));
        Task server = task("Implement server-side RatingService and persistence",
            Set.of("bookshelf-demo-server/src/main/java"));
        Task client = task("Implement client-side UI for rating books",
            Set.of("bookshelf-demo-client/src/main/java"));
        model.setCriterionIds(Set.of(checkId));
        server.setCriterionIds(Set.of(checkId));
        client.setCriterionIds(Set.of(checkId));

        TaskGraph graph = graph(List.of(model, server, client),
            List.of(new TaskEdge(model.id(), server.id()), new TaskEdge(server.id(), client.id())));

        TaskGraphValidator.Verdict verdict = validator.validate(graph, scope, null);

        assertThat(verdict.ok()).isTrue();
        assertThat(client.criterionIds()).containsExactly(checkId);
        assertThat(model.criterionIds()).isEmpty();
        assertThat(server.criterionIds()).isEmpty();
        // One log line, naming the check, all three original claimants and the winner.
        assertThat(verdict.warnings()).hasSize(1);
        assertThat(verdict.warnings().get(0))
            .contains("was on 3 task(s)")
            .contains("Implement Book data model and RatingService")
            .contains("Implement server-side RatingService and persistence")
            .contains("kept on 'Implement client-side UI for rating books'")
            .contains("removed from");
    }

    @Test
    void twoUnrelatedTasksClaimingTheSameCheckAreRejected() {
        StoryScope scope = scopeOf("a rating can be assigned to a book");
        UUID checkId = scope.criteria().get(0).id();
        Task a = task("Team A's rating implementation", Set.of("bookshelf-demo-a/src/main/java"));
        Task b = task("Team B's rating implementation", Set.of("bookshelf-demo-b/src/main/java"));
        a.setCriterionIds(Set.of(checkId));
        b.setCriterionIds(Set.of(checkId));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(a, b), List.of()), scope, null);

        // Neither task depends on the other, so nothing says which of them is more "finished" —
        // the plan is sent back naming both, the same re-validate path every other PLAN violation
        // uses.
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("Team A's rating implementation")
            && v.contains("Team B's rating implementation")
            && v.contains("no dependency relates them"));
        // Untouched: a rejected plan is regenerated, not silently repaired.
        assertThat(a.criterionIds()).containsExactly(checkId);
        assertThat(b.criterionIds()).containsExactly(checkId);
    }

    @Test
    void oneCheckOnOneTaskIsUntouchedByNormalisation() {
        StoryScope scope = scopeOf("a rating can be assigned to a book");
        UUID checkId = scope.criteria().get(0).id();
        Task a = task("Implement rating end to end", Set.of("bookshelf-demo-server/src/main/java"));
        a.setCriterionIds(Set.of(checkId));

        TaskGraphValidator.Verdict verdict =
            validator.validate(graph(List.of(a), List.of()), scope, null);

        assertThat(verdict.ok()).isTrue();
        assertThat(a.criterionIds()).containsExactly(checkId);
        assertThat(verdict.warnings()).noneMatch(w -> w.contains("was on"));
    }

    // --- an enabler nothing builds on -------------------------------------------------------------

    /**
     * Run {@code ede2068b}'s actual bookshelf plan, 2026-09-03: four tasks, one agreed check ("a
     * rating can be assigned to a book") sitting on the SERVER task rather than the client task
     * that delivers what the reader sees, and the client task depended on by nothing. The harness's
     * test helper caught this after a wave had already been spent on it; the validator now rejects
     * it at PLAN, before any worker runs.
     */
    @Test
    void aClientTaskNothingDependsOnAndThatClaimsNoCheckIsRejected() {
        StoryScope scope = scopeOf("a rating can be assigned to a book");
        UUID checkId = scope.criteria().get(0).id();
        Task model = task("Define Book Data Model",
            Set.of("bookshelf-demo-shared/src/main/java/book"));
        Task serviceInterface = task("Define BookService Interface",
            Set.of("bookshelf-demo-shared/src/main/java/service"));
        Task server = task("Implement BookService on Server",
            Set.of("bookshelf-demo-server/src/main/java"));
        Task client = task("Build Client UI for Rating",
            Set.of("bookshelf-demo-client/src/main/java"));
        server.setCriterionIds(Set.of(checkId));

        TaskGraph graph = graph(List.of(model, serviceInterface, server, client), List.of(
            new TaskEdge(model.id(), server.id()), new TaskEdge(serviceInterface.id(), server.id())));

        TaskGraphValidator.Verdict verdict = validator.validate(graph, scope, null);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).hasSize(1);
        assertThat(verdict.violations().get(0))
            .contains("Build Client UI for Rating")
            .contains("nothing that depends on it claims one either");
    }

    /**
     * The same four tasks, fixed the way the check itself says to fix it: the check moves to the
     * task that delivers the user-facing behaviour, and the client task is wired to depend on the
     * server work it needs. Nothing is orphaned any more.
     */
    @Test
    void movingTheCheckToTheClientTaskItDeliversAndWiringItInIsAccepted() {
        StoryScope scope = scopeOf("a rating can be assigned to a book");
        UUID checkId = scope.criteria().get(0).id();
        Task model = task("Define Book Data Model",
            Set.of("bookshelf-demo-shared/src/main/java/book"));
        Task serviceInterface = task("Define BookService Interface",
            Set.of("bookshelf-demo-shared/src/main/java/service"));
        Task server = task("Implement BookService on Server",
            Set.of("bookshelf-demo-server/src/main/java"));
        Task client = task("Build Client UI for Rating",
            Set.of("bookshelf-demo-client/src/main/java"));
        client.setCriterionIds(Set.of(checkId));

        TaskGraph graph = graph(List.of(model, serviceInterface, server, client), List.of(
            new TaskEdge(model.id(), server.id()), new TaskEdge(serviceInterface.id(), server.id()),
            new TaskEdge(server.id(), client.id())));

        TaskGraphValidator.Verdict verdict = validator.validate(graph, scope, null);

        assertThat(verdict.ok()).isTrue();
    }

    /**
     * Harness run 49, 2026-09-30 (Bookshelf on ZeroZ / TeaVM, "add, edit and remove books"): the
     * client task claimed no check and nothing depended on it, and was rejected as an enabler
     * nothing builds on; the plan without it was rejected for a contract nobody delivered; the
     * run parked. Code that runs only in a browser cannot be proved by an acceptance test, so
     * such a task needs no check and no dependent — compiling is its proof.
     */
    private static final com.swarmcoder.verify.BrowserOnlyCode.Survey BROWSER_CLIENT =
        new com.swarmcoder.verify.BrowserOnlyCode.Survey(
            List.of(new com.swarmcoder.verify.BrowserOnlyCode.Module("bookshelf-demo-client",
                "declares org.teavm:teavm-classlib", "TeaVM, which compiles Java to JavaScript",
                List.of("com.swarmcoder.demo.bookshelf.client"))),
            List.of("bookshelf-demo-shared", "bookshelf-demo-server"),
            java.util.Map.of("com.swarmcoder.demo.bookshelf.client",
                List.of("bookshelf-demo-client")));

    @Test
    void aBrowserOnlyTaskThatClaimsNoCheckAndNothingDependsOnIsNotAnEnabler() {
        StoryScope scope = scopeOf("a book can be added");
        Task server = task("Implement BookService on Server",
            Set.of("bookshelf-demo-server/src/main/java/server"));
        Task client = task("BookshelfClient over binary WebSocket",
            Set.of("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client"));
        server.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        TaskGraph graph = graph(List.of(server, client), List.of());

        assertThat(new TaskGraphValidator(BROWSER_CLIENT).unusedEnablers(graph)).isEmpty();
        assertThat(new TaskGraphValidator(BROWSER_CLIENT).validate(graph, scope, null).ok())
            .isTrue();
    }

    @Test
    void theSameTaskInAJvmModuleIsStillObjectedTo() {
        StoryScope scope = scopeOf("a book can be added");
        Task server = task("Implement BookService on Server",
            Set.of("bookshelf-demo-server/src/main/java/server"));
        Task helper = task("BookshelfClient over binary WebSocket",
            Set.of("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/client"));
        server.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        TaskGraph graph = graph(List.of(server, helper), List.of());

        TaskGraphValidator.Verdict verdict =
            new TaskGraphValidator(BROWSER_CLIENT).validate(graph, scope, null);

        assertThat(verdict.violations()).singleElement().asString()
            .contains("BookshelfClient over binary WebSocket")
            .contains("it is an enabler nothing builds on");
    }

    @Test
    void aTaskSpanningABrowserOnlyAndAJvmModuleIsStillObjectedTo() {
        StoryScope scope = scopeOf("a book can be added");
        Task server = task("Implement BookService on Server",
            Set.of("bookshelf-demo-server/src/main/java/server"));
        Task mixed = task("Client and shared helper",
            Set.of("bookshelf-demo-client/src/main/java/c", "bookshelf-demo-shared/src/main/java/s"));
        server.setCriterionIds(Set.of(scope.criteria().get(0).id()));

        assertThat(new TaskGraphValidator(BROWSER_CLIENT)
            .unusedEnablers(graph(List.of(server, mixed), List.of()))).containsExactly(mixed);
    }

    /** A one-requirement scope whose criteria are the given statements, all ACCEPTED. */
    private static StoryScope scopeOf(String... criteriaText) {
        var requirement = new com.swarmcoder.domain.BrdRequirement(UUID.randomUUID(), "R7",
            "Guest checkout", "A guest can pay", com.swarmcoder.domain.Priority.HIGH,
            com.swarmcoder.domain.RequirementStatus.ACTIVE, null);
        var criteria = new java.util.ArrayList<com.swarmcoder.domain.AcceptanceCriterion>();
        var ids = new java.util.ArrayList<UUID>();
        for (String text : criteriaText) {
            // One test class per check: checks sharing a class must be claimed by one task
            // (OneTestClassOneTaskTest), and this fixture spreads its checks over two tasks.
            var c = new com.swarmcoder.domain.AcceptanceCriterion(UUID.randomUUID(), text,
                "swarm.accept.Check" + criteria.size() + "Test#proves");
            c.setStatus(com.swarmcoder.domain.CriterionStatus.ACCEPTED);
            criteria.add(c);
            ids.add(c.id());
        }
        requirement.setCriteria(criteria);
        var brd = new com.swarmcoder.domain.Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "BRD",
            new java.util.ArrayList<>(List.of(requirement)), new java.util.ArrayList<>(),
            java.time.Instant.now(), java.time.Instant.now());
        var story = new com.swarmcoder.domain.Story(UUID.randomUUID(), brd.projectId(), "S1",
            com.swarmcoder.domain.StoryKind.DELIVERY, "Guest can pay", null,
            com.swarmcoder.domain.StoryState.READY, new java.util.ArrayList<>(), ids, null, 0,
            com.swarmcoder.domain.StoryOrigin.BACKLOG, null, null, "human",
            new java.util.ArrayList<>(), null, null, null, null,
            java.time.Instant.now(), java.time.Instant.now());
        return StoryScope.resolve(brd, story);
    }
}
