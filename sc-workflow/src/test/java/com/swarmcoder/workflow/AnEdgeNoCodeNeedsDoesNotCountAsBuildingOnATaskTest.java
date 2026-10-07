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
import com.swarmcoder.domain.DesignDocument;
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
import com.swarmcoder.verify.BrowserOnlyCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness runs 44/45, 2026-09-27, in their exact shape. The story "Add, edit, and remove books"
 * had its checks on one task, "Implement BookListService on the server with EclipseStore
 * persistence". "Create BookListPage UI component in client module" claimed no check and wrote
 * only {@code BookListPage.java} in {@code bookshelf-demo-client}, a TeaVM module no acceptance
 * test can run. The planner made the server task depend on the page task anyway — the run log's
 * own "told '…server…' where the 2 type(s) it uses from other tasks live: Book, BookListService"
 * shows the server code used nothing of the page's. That edge satisfied "every unchecked task is
 * built on by a checked one", and it also meant that when the page task could not be finished
 * (its contract named {@code com.zeroz4j.ui.ListView}, which does not exist), the only task that
 * proved the story could never start.
 *
 * <p>Such an edge first stopped counting (the page task was an enabler nothing builds on). Harness
 * run 49, 2026-09-30 then showed that rule and "every contract must be delivered" contradicting
 * each other for a client task, so a task that writes only browser-only code is now exempt
 * altogether: the run 44 plan below is accepted, with or without the edge, and no task is dropped.
 * Everything that is not browser-only code keeps the rule as it was — see {@link
 * TaskGraphValidator#unusedEnablers(TaskGraph, DesignDocument)} for why.
 */
class AnEdgeNoCodeNeedsDoesNotCountAsBuildingOnATaskTest {

    private static final String SHARED =
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/";
    private static final String CLIENT =
        "bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/";
    private static final String SERVER =
        "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/";

    private static final String PAGE_TITLE = "Create BookListPage UI component in client module";
    private static final String SERVER_TITLE =
        "Implement BookListService on the server with EclipseStore persistence";

    /** What BrowserOnlyCode.survey reads off the Bookshelf demo's build. */
    private static final BrowserOnlyCode.Survey BOOKSHELF = new BrowserOnlyCode.Survey(
        List.of(new BrowserOnlyCode.Module("bookshelf-demo-client",
            "declares org.teavm:teavm-classlib", "TeaVM, which compiles Java to JavaScript",
            List.of("com.swarmcoder.demo.bookshelf.client"))),
        List.of("bookshelf-demo-shared", "bookshelf-demo-server"),
        Map.of("com.swarmcoder.demo.bookshelf", List.of("bookshelf-demo-shared"),
            "com.swarmcoder.demo.bookshelf.client", List.of("bookshelf-demo-client")));

    @Test
    void run44sPageTaskIsNotAnOrphanBecauseNoAcceptanceTestCouldEverProveIt() {
        Run44 plan = run44(true);

        assertThat(new TaskGraphValidator(BOOKSHELF)
            .validate(plan.graph, plan.scope, null, plan.design).violations()).isEmpty();

        // ... and it does not need the edge the planner added only to satisfy the old rule.
        plan.graph.dependencies().removeIf(e -> e.from().equals(plan.page.id())
            && e.to().equals(plan.server.id()));
        assertThat(new TaskGraphValidator(BOOKSHELF)
            .validate(plan.graph, plan.scope, null, plan.design).violations()).isEmpty();
    }

    @Test
    void withoutKnowingWhichModulesAreBrowserOnlyTheRuleIsWhatItWas() {
        Run44 plan = run44(true);

        assertThat(new TaskGraphValidator().validate(plan.graph, plan.scope, null, plan.design)
            .violations()).isEmpty();
    }

    @Test
    void aCheckedTaskWhoseCodeUsesThePageBuildsOnIt() {
        Run44 plan = run44(true);
        Task app = task("Wire the BookListPage into the client entry point",
            "Create BookshelfApp, which builds a BookListPage and binds it to the service.",
            Set.of("bookshelf-demo-client/pom.xml", CLIENT + "BookshelfApp.java"));
        app.setDeliveredContracts(List.of(contract(
            "com.swarmcoder.demo.bookshelf.client.BookshelfApp",
            "public com.swarmcoder.demo.bookshelf.client.BookListPage page;")));
        app.setCriterionIds(Set.of(plan.scope.criteria().get(0).id()));
        plan.server.setCriterionIds(Set.of());
        plan.graph.tasks().add(app);
        plan.graph.dependencies().add(new TaskEdge(plan.page.id(), app.id()));
        plan.graph.dependencies().add(new TaskEdge(plan.server.id(), app.id()));

        assertThat(new TaskGraphValidator(BOOKSHELF)
            .validate(plan.graph, plan.scope, null, plan.design).violations())
            .as("the app's contract names BookListPage: that is code building on the page")
            .isEmpty();
    }

    @Test
    void aJvmEnablerStillCountsAsBuiltOnThroughAnyEdge() {
        // Run 40's service implementation, made a dependency of the checked task although that
        // task's contract never names it: its acceptance test may construct it (run 38), so the
        // edge is not evidence of nothing, and the rule stays as it was.
        Run44 plan = run44(true);
        Task impl = task("Implement BookListServiceImpl",
            "Implement the service interface.",
            Set.of("bookshelf-demo-server/pom.xml", SERVER + "BookListServiceImpl2.java"));
        plan.graph.tasks().add(impl);
        plan.graph.dependencies().add(new TaskEdge(impl.id(), plan.server.id()));
        plan.graph.tasks().remove(plan.page);
        plan.graph.dependencies().removeIf(e -> e.from().equals(plan.page.id())
            || e.to().equals(plan.page.id()));
        plan.design.contracts().removeIf(c -> c.typeName().endsWith("BookListPage"));

        assertThat(new TaskGraphValidator(BOOKSHELF)
            .validate(plan.graph, plan.scope, null, plan.design).violations()).isEmpty();
    }

    @Test
    void anUncontractedPageTaskIsAcceptedAndSoNeverReachesTheLastAttemptDrop() {
        Run44 plan = run44(false);
        TaskGraphValidator validator = new TaskGraphValidator(BOOKSHELF);
        TaskGraphValidator.Verdict verdict =
            validator.validate(plan.graph, plan.scope, null, plan.design);
        assertThat(verdict.violations()).isEmpty();

        UnusedEnablers.Outcome drop =
            UnusedEnablers.dropIfSafe(plan.graph, verdict, plan.scope, plan.design, validator);

        assertThat(drop.droppedAny()).isFalse();
        assertThat(plan.graph.tasks()).extracting(Task::title).contains(PAGE_TITLE);
    }

    // --- the plan as run 44 stored it ----------------------------------------------------------

    private record Run44(TaskGraph graph, StoryScope scope, DesignDocument design, Task page,
                         Task server) {}

    private static Run44 run44(boolean pageDeliversAContract) {
        StoryScope scope = scope();
        ApiContract bookContract = contract("com.swarmcoder.demo.bookshelf.Book",
            "public String id;", "public String title;", "public String author;", "public Book();");
        ApiContract serviceContract = contract("com.swarmcoder.demo.bookshelf.BookListService",
            "java.util.List<Book> getBooks();", "void addBook(Book book);",
            "void updateBook(Book book);", "void removeBook(String id);");
        ApiContract pageContract = contract("com.swarmcoder.demo.bookshelf.client.BookListPage",
            "public com.zeroz4j.ui.component.TextField titleInput;",
            "public com.zeroz4j.ui.component.KeyedList<Book> bookList;",
            "public void bind(com.swarmcoder.demo.bookshelf.BookListService service);",
            "public void render(java.util.List<Book> books);");

        Task book = task("Create Book @DataModel in shared module",
            "Create the Book @DataModel class with public fields id, title, author.",
            Set.of("bookshelf-demo-shared/pom.xml", SHARED + "Book.java"));
        book.setDeliveredContracts(List.of(bookContract));
        Task service = task("Create BookListService interface in shared module",
            "Create the BookListService interface the browser calls: getBooks, addBook, "
                + "updateBook, removeBook.",
            Set.of("bookshelf-demo-shared/pom.xml", SHARED + "BookListService.java"));
        service.setDeliveredContracts(List.of(serviceContract));
        Task page = task(PAGE_TITLE,
            "In the client module create public class BookListPage composed from com.zeroz4j.ui "
                + "components. bind stores the BookListService; render fills the list from the "
                + "passed Book objects; onAddClick builds a Book and calls service.addBook.",
            Set.of("bookshelf-demo-client/pom.xml", CLIENT + "BookListPage.java"));
        if (pageDeliversAContract) {
            page.setDeliveredContracts(List.of(pageContract));
        }
        Task server = task(SERVER_TITLE,
            "Implement BookListService in BookListServiceImpl, keeping every Book in a BooksRoot "
                + "stored with EclipseStore; store each changed Book explicitly.",
            Set.of("bookshelf-demo-server/pom.xml", SERVER + "BookListServiceImpl.java",
                SERVER + "BooksRoot.java"));
        server.setCriterionIds(Set.of(scope.criteria().get(0).id()));

        List<TaskEdge> edges = new ArrayList<>(List.of(
            new TaskEdge(book.id(), service.id()),
            new TaskEdge(book.id(), page.id()),
            new TaskEdge(service.id(), page.id()),
            new TaskEdge(book.id(), server.id()),
            new TaskEdge(service.id(), server.id()),
            // The edge no code needs: the server task waits for the page.
            new TaskEdge(page.id(), server.id())));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(book, service, page, server)), edges);
        List<ApiContract> contracts = new ArrayList<>(List.of(bookContract, serviceContract));
        if (pageDeliversAContract) {
            contracts.add(pageContract);
        }
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1,
            "Add, edit, and remove books", List.of(), List.of(), contracts, List.of(), null,
            Instant.now());
        return new Run44(graph, scope, design, page, server);
    }

    private static ApiContract contract(String type, String... members) {
        return new ApiContract(UUID.randomUUID(), type.substring(type.lastIndexOf('.') + 1),
            "", "", type, List.of(members));
    }

    private static Task task(String title, String instructions, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static StoryScope scope() {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R2",
            "Add, edit, and remove books", "The user can add, edit and remove books",
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "A new book can be added through the UI.", "swarm.accept.BookListTest#addsNewBook");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S2", StoryKind.DELIVERY,
            "Add, edit, and remove books", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }
}
