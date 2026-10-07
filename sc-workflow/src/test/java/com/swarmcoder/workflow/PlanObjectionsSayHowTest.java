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
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plan objection says how to satisfy it; the planner is told what earlier attempts were rejected
 * for; and an orphaned task that is the last attempt's only fault is dropped rather than parking the
 * run.
 *
 * <p>Harness run 40, 2026-09-26 (DeepSeek V4 Flash, Bookshelf demo, story "Books and ratings
 * persist across restart", one check R6:C1). Attempt 1: "task 'Implement BookshelfServiceImpl
 * delegating to BookshelfStore' claims no check, and nothing that depends on it claims one either —
 * it is an enabler nothing builds on". Attempt 2: the Book and Rating contracts delivered by no
 * task. Attempt 3: attempt 1's objection again, word for word. Parked in PLAN. The check sat on the
 * BookshelfStore task, whose acceptance test drives the store.
 *
 * <p>The run's own plan was not kept (no reply blob was stored), so the fixture below is a
 * reconstruction from the run log: a shared task writing Book, Rating and the BookshelfService
 * interface whose instructions mention the store; the store task claiming the check; and the
 * service implementation, a leaf nothing depends on. No model is called except {@link
 * ScriptedLlm}.
 */
class PlanObjectionsSayHowTest {

    private static final String SHARED =
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared/";
    private static final String SERVER =
        "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/";

    private static final String SHARED_TITLE =
        "Create shared @DataModel classes Book, Rating and the BookshelfService interface";
    private static final String STORE_TITLE =
        "Implement BookshelfStore with EclipseStore persistence";
    private static final String SERVICE_TITLE =
        "Implement BookshelfServiceImpl delegating to BookshelfStore";

    @TempDir
    Path storeDir;

    private final TaskGraphValidator validator = new TaskGraphValidator();

    // --- 1. the objection names the ways out ---------------------------------------------------

    @Test
    void run40sOrphanObjectionSaysHowToFixItAndOffersOnlyTheWaysThatExist() {
        Run40 plan = run40();

        TaskGraphValidator.Verdict verdict = validator.validate(plan.graph, plan.scope, null, null);

        assertThat(verdict.violations()).hasSize(1);
        String objection = verdict.violations().get(0);
        assertThat(objection)
            .as("the first sentence is what it always was, so every reader matching on it still does")
            .startsWith("task '" + SERVICE_TITLE + "' claims no check, and nothing that depends on "
                + "it claims one either — it is an enabler nothing builds on")
            .contains("(1) drop the task — every check of this story is already claimed without "
                + "it (R6:C1 by '" + STORE_TITLE + "'), so nothing a check proves is lost")
            .contains("(2) if this task's own code is what a check proves, move that check's ref "
                + "out of the criterionRefs of the task that claims it now and into this task's");
        assertThat(objection)
            .as("the only checked task is the store this service delegates to — an edge back to it "
                + "is a cycle, so that way out is not offered")
            .doesNotContain("add an edge from");
    }

    @Test
    void anOrphanACheckedTaskCouldWaitForIsOfferedThatEdge() {
        StoryScope scope = scope();
        Task checked = task("Build the reading list view", "Show the books.",
            Set.of("bookshelf-demo-client/src/main/java/"));
        checked.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        Task orphan = task("Add a book importer", "Import books from a file.",
            Set.of("bookshelf-demo-server/src/main/java/"));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(checked, orphan)), new ArrayList<>());

        List<String> violations = validator.checkEnablersAreUsed(graph, scope);

        assertThat(violations).singleElement().asString()
            .contains("(2) if a task that claims a check really needs this task's code, add an "
                + "edge from 'Add a book importer' to that task (the tasks that claim checks and "
                + "could wait for it: 'Build the reading list view')")
            .contains("(3) if this task's own code is what a check proves");
    }

    @Test
    void otherObjectionsNowCarryTheirRemedyToo() {
        StoryScope scope = scope();
        Task a = task("A", "a", Set.of("m/src/main/java/a"));
        Task b = task("B", "b", Set.of("m/src/main/java/a"));
        TaskGraph overlap = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(a, b)), new ArrayList<>());
        assertThat(validator.validate(overlap, scope, null, null).violations())
            .anySatisfy(v -> assertThat(v).contains("overlap on write path")
                .contains("give that path to only one of them"))
            .anySatisfy(v -> assertThat(v).contains("is linked to any of the story's 1 check(s)")
                .contains("Put each ref in the criterionRefs of the one task"));

        Task c = task("C", "c", Set.of("m/src/main/java/c"));
        Task d = task("D", "d", Set.of("m/src/main/java/d"));
        TaskGraph cyclic = new TaskGraph(UUID.randomUUID(), 1, null, new ArrayList<>(List.of(c, d)),
            new ArrayList<>(List.of(new TaskEdge(c.id(), d.id()), new TaskEdge(d.id(), c.id()))));
        assertThat(validator.validate(cyclic).violations())
            .anySatisfy(v -> assertThat(v)
                .contains("dependency cycle among (or behind) these tasks: 'C', 'D'")
                .contains("remove whichever edge"));
    }

    // --- 2. the harmless ordering note --------------------------------------------------------

    @Test
    void theSharedTaskMentioningTheStoreIsLoggedAsTheNormalShapeItIs() {
        Run40 plan = run40();

        TaskGraphValidator.Verdict verdict = validator.validate(plan.graph, plan.scope, null, null);

        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
            .startsWith("dependency not added: task '" + SHARED_TITLE + "' mentions BookshelfStore")
            .contains("which the plan runs after it")
            .contains("which is normal; the planner's order is kept"));
        assertThat(plan.graph.dependencies())
            .as("and no edge was added from the store back to the shared task")
            .doesNotContain(new TaskEdge(plan.store.id(), plan.shared.id()));
    }

    // --- 3. dropping the orphan on the last attempt -------------------------------------------

    @Test
    void run40sOrphanIsDroppedAndWhatRemainsPassesEveryCheck() {
        Run40 plan = run40();
        TaskGraphValidator.Verdict verdict = validator.validate(plan.graph, plan.scope, null, null);

        UnusedEnablers.Outcome drop =
            UnusedEnablers.dropIfSafe(plan.graph, verdict, plan.scope, null, validator);

        assertThat(drop.droppedAny()).isTrue();
        assertThat(drop.graph().tasks()).extracting(Task::title)
            .containsExactly(SHARED_TITLE, STORE_TITLE);
        assertThat(drop.graph().dependencies())
            .noneMatch(e -> e.from().equals(plan.service.id()) || e.to().equals(plan.service.id()));
        assertThat(validator.validate(drop.graph(), plan.scope, null, null).ok()).isTrue();
        assertThat(drop.dropped()).singleElement().asString()
            .startsWith("dropped task '" + SERVICE_TITLE + "'")
            .contains("(R6:C1 by '" + STORE_TITLE + "')")
            .contains("the story is missing a check that proves it");
    }

    @Test
    void notDroppedWhenThePlanHasAnotherProblemToo() {
        Run40 plan = run40();
        plan.store.setCriterionIds(Set.of()); // R6:C1 now unclaimed
        plan.shared.setCriterionIds(Set.of(UUID.randomUUID())); // keep "something claims"
        TaskGraphValidator.Verdict verdict = validator.validate(plan.graph, plan.scope, null, null);

        UnusedEnablers.Outcome drop =
            UnusedEnablers.dropIfSafe(plan.graph, verdict, plan.scope, null, validator);

        assertThat(drop.droppedAny()).isFalse();
        assertThat(drop.refusal()).contains("the plan has other problems too");
    }

    @Test
    void notDroppedWhenItDeliversAContract() {
        Run40 plan = run40();
        plan.service.setDeliveredContracts(List.of(new ApiContract(UUID.randomUUID(),
            "BookshelfServiceImpl", "the service", "",
            "com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl", List.of())));
        TaskGraphValidator.Verdict verdict = validator.validate(plan.graph, plan.scope, null, null);

        UnusedEnablers.Outcome drop =
            UnusedEnablers.dropIfSafe(plan.graph, verdict, plan.scope, null, validator);

        assertThat(drop.droppedAny()).isFalse();
        assertThat(drop.refusal()).contains("delivers the contract");
    }

    @Test
    void notDroppedWhenATaskThatStaysUsesATypeItWrites() {
        StoryScope scope = scope();
        Task checked = task("Implement the store",
            "Keep books in EclipseStore; register StoreHooks on startup.",
            Set.of(SERVER + "BookshelfStore.java"));
        checked.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        Task orphan = task("Write the startup hooks",
            "Write the StoreHooks class, which opens BookshelfStore.",
            Set.of(SERVER + "StoreHooks.java"));
        // Planned the wrong way round: the orphan after the task that uses its type. Each task's
        // wording names the other's new type, which cannot say which way round the dependency
        // runs, so the planner's order stands and it stays an orphan in name only. (With the
        // hooks task naming nothing of the store's, the order itself is objected to since live
        // run 90 - APlanDoesNotRunATaskBeforeTheTypeItUsesExistsTest.)
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(checked, orphan)),
            new ArrayList<>(List.of(new TaskEdge(checked.id(), orphan.id()))));
        TaskGraphValidator.Verdict verdict = validator.validate(graph, scope, null, null);
        assertThat(verdict.violations()).singleElement().asString()
            .contains("it is an enabler nothing builds on");

        UnusedEnablers.Outcome drop = UnusedEnablers.dropIfSafe(graph, verdict, scope, null, validator);

        assertThat(drop.droppedAny()).isFalse();
        assertThat(drop.refusal()).contains("uses com.swarmcoder.demo.bookshelf.server.StoreHooks");
    }

    // --- 4. what the planner is told on a retry -----------------------------------------------

    @Test
    void run40sThirdAttemptIsToldNotToBringBackTheFirstAttemptsObjection() {
        String orphan = "task '" + SERVICE_TITLE + "' claims no check, and nothing that depends "
            + "on it claims one either — it is an enabler nothing builds on";
        String contract = "no task delivers the contract com.swarmcoder.demo.bookshelf.shared.Book";

        String third = ArchitectClient.retryFeedback("{\"tasks\":[]}", List.of(contract),
            List.of(orphan));

        assertThat(third)
            .contains("Your previous plan was rejected for these reasons:\n- " + contract)
            .contains("An earlier plan of yours was also rejected for the following. Your previous "
                + "plan had fixed them; your new plan must not bring any of them back:\n- " + orphan)
            .contains("Your previous plan, verbatim:\n{\"tasks\":[]}")
            .doesNotContain("[AGAIN]");
    }

    @Test
    void anObjectionSeenBeforeIsMarkedAsARepeat() {
        String orphan = "task 'X' claims no check";

        String feedback = ArchitectClient.retryFeedback("{}", List.of(orphan),
            List.of(orphan, "something else"));

        assertThat(feedback)
            .contains("- [AGAIN] " + orphan)
            .contains("You fixed them once; a change you made for another objection brought them "
                + "back")
            .contains("must not bring any of them back:\n- something else");
    }

    @Test
    void aFirstAttemptIsToldNothing() {
        assertThat(ArchitectClient.retryFeedback(null, List.of(), List.of())).isEmpty();
    }

    // --- 5. end to end through PLAN, scripted --------------------------------------------------

    /**
     * The planner returns run 40's orphaned plan on every attempt. Before, the run parked in PLAN.
     * Now the third request marks the repeat, and the third plan is accepted without the orphan.
     */
    @Test
    void aPlannerThatKeepsTheOrphanNoLongerParksTheRun() throws Exception {
        List<String> plannerRequests = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();

        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("AI planner")) {
                    plannerRequests.add(conversation);
                    return RUN_40_PLAN_JSON;
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID[] ids = seedStory(store);
            AgentRuntime unusedRuntime = spec -> {
                throw new UnsupportedOperationException("not exercised by this test");
            };
            WorkflowEngine engine = new WorkflowEngine(unusedRuntime, run -> run,
                new VllmClient(llm.baseUrl(), "", "test-model", true), store);
            engine.setEventLogger(events::add);

            Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.PLAN, ids[0], ids[1],
                null, null, null, Instant.now(),
                new RunReport(runId, "Books and ratings persist across restart"));
            engine.advance(run);

            awaitPastPlan(store, runId);
            Run after = store.root().runs.get(runId);

            assertThat(plannerRequests).hasSize(GreenfieldWorkflow.MAX_PLAN_ATTEMPTS);
            assertThat(plannerRequests.get(2))
                .as("the third request says the objection is one it was already given")
                .contains("- [AGAIN] task '" + SERVICE_TITLE + "' claims no check")
                .contains("(1) drop the task");
            assertThat(events).as(String.join("\n", events))
                .anyMatch(e -> e.contains("PLAN attempt 3: dropped task '" + SERVICE_TITLE + "'"));
            assertThat(after.taskGraphId())
                .as("the plan was accepted and stored — the run did not park in PLAN")
                .isNotNull();
            TaskGraph stored = store.root().taskGraphs.get(after.taskGraphId());
            assertThat(stored.tasks()).extracting(Task::title)
                .containsExactly(SHARED_TITLE, STORE_TITLE);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static final String RUN_40_PLAN_JSON = """
        {"tasks":[
          {"id":"t1","title":"%s",
           "instructions":"Create Book and Rating as @DataModel classes and the BookshelfService \
        interface in the shared module. BookshelfStore on the server will persist them.",
           "writeSet":["%sBook.java","%sRating.java","%sBookshelfService.java"],"readSet":[],
           "criteria":[],"criterionRefs":[]},
          {"id":"t2","title":"%s",
           "instructions":"Implement BookshelfStore, keeping Book and Rating objects in EclipseStore \
        so they survive a restart.",
           "writeSet":["%sBookshelfStore.java"],"readSet":[],
           "criteria":[{"text":"books and ratings are still there after a restart",
             "testClassOrFile":"swarm.accept.R6C1AcceptTest#proves"}],
           "criterionRefs":["R6:C1"]},
          {"id":"t3","title":"%s",
           "instructions":"Implement BookshelfService by delegating every call to BookshelfStore.",
           "writeSet":["%sBookshelfServiceImpl.java"],"readSet":[],
           "criteria":[],"criterionRefs":[]}],
         "edges":[{"from":"t1","to":"t2"},{"from":"t1","to":"t3"}]}
        """.formatted(SHARED_TITLE, SHARED, SHARED, SHARED, STORE_TITLE, SERVER, SERVICE_TITLE,
            SERVER);

    private record Run40(TaskGraph graph, StoryScope scope, Task shared, Task store, Task service) {}

    /** Run 40's plan as the log describes it: the check on the store, the service a leaf. */
    private static Run40 run40() {
        StoryScope scope = scope();
        Task shared = task(SHARED_TITLE,
            "Create Book and Rating as @DataModel classes and the BookshelfService interface in "
                + "the shared module. BookshelfStore on the server will persist them.",
            Set.of(SHARED + "Book.java", SHARED + "Rating.java", SHARED + "BookshelfService.java"));
        Task store = task(STORE_TITLE,
            "Implement BookshelfStore, keeping Book and Rating objects in EclipseStore so they "
                + "survive a restart.",
            Set.of(SERVER + "BookshelfStore.java"));
        store.setCriterionIds(Set.of(scope.criteria().get(0).id()));
        // The store's contract uses Book and Rating — the contract evidence that, in run 40, made
        // the shared task's prose mention of BookshelfStore the "planner's order is kept" case.
        store.setDeliveredContracts(List.of(new ApiContract(UUID.randomUUID(), "BookshelfStore",
            "BookshelfStore contract", "",
            "com.swarmcoder.demo.bookshelf.server.BookshelfStore",
            List.of("List<Book> loadBooks()", "List<Rating> loadRatings()",
                "void save(Book book)", "void save(Rating rating)"))));
        Task service = task(SERVICE_TITLE,
            "Implement BookshelfService by delegating every call to BookshelfStore.",
            Set.of(SERVER + "BookshelfServiceImpl.java"));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(shared, store, service)),
            new ArrayList<>(List.of(new TaskEdge(shared.id(), store.id()),
                new TaskEdge(shared.id(), service.id()))));
        return new Run40(graph, scope, shared, store, service);
    }

    private static Task task(String title, String instructions, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static BrdRequirement requirement() {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R6",
            "Remember everything between visits", "Books and ratings survive a restart",
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "After the app is restarted, books and their ratings are still there",
            "swarm.accept.R6C1AcceptTest#proves");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        return requirement;
    }

    private static Brd brd(BrdRequirement requirement) {
        return new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
    }

    private static Story story(Brd brd, BrdRequirement requirement) {
        return new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "Books and ratings persist across restart", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(requirement.criteria().get(0).id())), null, 0,
            StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
    }

    private static StoryScope scope() {
        BrdRequirement requirement = requirement();
        Brd brd = brd(requirement);
        return StoryScope.resolve(brd, story(brd, requirement));
    }

    /** @return the project id and the story id */
    private static UUID[] seedStory(ArtifactStore store) {
        BrdRequirement requirement = requirement();
        Brd brd = brd(requirement);
        Story story = story(brd, requirement);
        store.saveBrd(brd);
        store.saveStory(story);
        return new UUID[] {brd.projectId(), story.id()};
    }

    /** A plan stored, or a park — whichever comes first; the assertions say which it had to be. */
    private static void awaitPastPlan(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && (run.taskGraphId() != null || run.parkedAt() != null)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never got past PLAN or parked");
    }
}
