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
import com.swarmcoder.domain.Waves;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task whose code uses a type another task of the same plan writes runs after that task — and
 * its worker is told where that type lives.
 *
 * <p>Harness run 39, 2026-09-25 (run {@code d51ee25e}, DeepSeek V4 Flash, Bookshelf demo). The
 * fixture below is that plan's shape, with its real titles, write sets and contracts. Task B's
 * contract was {@code BooksService{List<Book> getBooks(); List<Rating> getRatings(); void
 * saveBook(Book book); void saveRating(Rating rating); }}, task A wrote {@code Book} and {@code
 * Rating}, and nothing ordered them: both went out in the first wave, and B's workers could not
 * compile a line. See {@link TypeDependencyOrder} for the rule and its limits.
 */
class PlanOrdersTasksByTheirTypesTest {

    private static final String SHARED =
        "bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/shared/";
    private static final String SERVER =
        "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/";
    private static final String PKG = "com.swarmcoder.demo.bookshelf.shared.";

    private static final ApiContract BOOK = contract("Book", PKG + "Book",
        List.of("String id", "String title", "String author"));
    private static final ApiContract RATING = contract("Rating", PKG + "Rating",
        List.of("String bookId", "int stars"));
    private static final ApiContract BOOKS_SERVICE = contract("BooksService", PKG + "BooksService",
        List.of("List<Book> getBooks()", "List<Rating> getRatings()", "void saveBook(Book book)",
            "void saveRating(Rating rating)"));

    private final TaskGraphValidator validator = new TaskGraphValidator();

    // --- run 39 --------------------------------------------------------------------------------

    @Test
    void run39sInterfaceTaskNowWaitsForTheTaskThatWritesItsTypes() {
        Run39 plan = run39();

        TaskGraphValidator.Verdict verdict =
            validator.validate(plan.graph, null, null, plan.design);

        assertThat(verdict.violations()).isEmpty();
        assertThat(plan.graph.dependencies())
            .as("the edge the planner left out is now there")
            .contains(new TaskEdge(plan.model.id(), plan.service.id()));
        assertThat(verdict.warnings())
            .as("and the run log says why, naming the types that forced it")
            .anySatisfy(w -> assertThat(w)
                .startsWith("added dependency:")
                .contains("'Create shared BooksService interface' now depends on "
                    + "'Create shared data model classes (Book and Rating)'")
                .contains("Book, Rating"));

        List<List<Task>> waves = Waves.of(plan.graph.tasks(), Task::id, edges(plan.graph)).waves();
        assertThat(titles(waves.get(0)))
            .containsExactly("Create shared data model classes (Book and Rating)");
        assertThat(titles(waves.get(1))).containsExactly("Create shared BooksService interface");
        assertThat(titles(waves.get(2)))
            .containsExactly("Implement server-side BooksService with EclipseStore");
        assertThat(titles(waves.get(3))).containsExactly("Implement server-side BooksServiceFactory");
    }

    @Test
    void run39sInterfaceWorkerIsToldTheExactPackageOfBookAndRatingAndWhoWritesThem() {
        Run39 plan = run39();
        validator.validate(plan.graph, null, null, plan.design);
        String modelBefore = plan.model.instructions();

        List<String> logged = TypeDependencyOrder.annotate(plan.graph, plan.design);

        String brief = plan.service.instructions();
        assertThat(brief).contains(TypeDependencyOrder.BRIEF_HEADING);
        assertThat(brief).contains("  - " + PKG + "Book{String id; String title; String author; }"
            + " — written by task 'Create shared data model classes (Book and Rating)', which "
            + "finishes before yours starts, so it is already in your checkout");
        assertThat(brief).contains(PKG + "Rating{");
        assertThat(plan.model.instructions())
            .as("a task that uses nothing from another task is told nothing new")
            .isEqualTo(modelBefore);
        assertThat(logged).anySatisfy(line -> assertThat(line)
            .contains("'Create shared BooksService interface'").contains("Book").contains("Rating"));

        String once = plan.service.instructions();
        TypeDependencyOrder.annotate(plan.graph, plan.design);
        assertThat(plan.service.instructions()).as("idempotent").isEqualTo(once);
    }

    @Test
    void aTaskThatDeliversNoContractIsToldTheTypesOfTheTasksItWaitsFor() {
        // Live run 100: the planner now writes what a task delivers in a sentence or two, and
        // a task with no contract of its own - a screen - showed no use of any type. Its
        // workers asked for the model type's shape six times.
        Task model = task("Model", "Write the model.", Set.of(SHARED + "Book.java"), BOOK);
        Task service = task("Service", "Write the service.",
            Set.of(SHARED + "BooksService.java"), BOOKS_SERVICE);
        Task view = task("View", "Show what is stored and let a person add to it.",
            Set.of("client/src/main/java/com/acme/client/MainView.java"));
        Task alone = task("Notes", "Write the release notes.", Set.of("NOTES.md"));
        TaskGraph graph = graph(List.of(model, service, view, alone), List.of(
            new TaskEdge(model.id(), service.id()), new TaskEdge(service.id(), view.id())));
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "books", List.of(),
            List.of(), List.of(BOOK, BOOKS_SERVICE), List.of(), null, null);
        String aloneBefore = alone.instructions();

        List<String> logged = TypeDependencyOrder.annotate(graph, design);

        assertThat(view.instructions()).contains(TypeDependencyOrder.BRIEF_HEADING)
            .as("the type of the task it waits for, with its members and where it lives")
            .contains("  - " + PKG + "BooksService{")
            .contains("written by task 'Service', which finishes before yours starts")
            .as("and the type that one names in its members, written by a task before both")
            .contains("  - " + PKG + "Book{")
            .contains("written by task 'Model'");
        assertThat(view.instructions().indexOf(PKG + "BooksService{"))
            .as("nearest first").isLessThan(view.instructions().indexOf(PKG + "Book{"));
        assertThat(alone.instructions())
            .as("a task that waits for nothing is told nothing").isEqualTo(aloneBefore);
        assertThat(logged).anySatisfy(line -> assertThat(line).contains("'View'"));

        String once = view.instructions();
        TypeDependencyOrder.annotate(graph, design);
        assertThat(view.instructions()).as("idempotent").isEqualTo(once);
    }

    @Test
    void validatingTheStoredPlanAgainAddsNothingMore() {
        Run39 plan = run39();
        validator.validate(plan.graph, null, null, plan.design);
        List<TaskEdge> after = new ArrayList<>(plan.graph.dependencies());

        TaskGraphValidator.Verdict again = validator.validate(plan.graph, null, null, plan.design);

        assertThat(plan.graph.dependencies()).containsExactlyElementsOf(after);
        assertThat(again.warnings()).noneMatch(w -> w.startsWith("added dependency:"));
    }

    // --- when it does not add the edge ---------------------------------------------------------

    @Test
    void aPlanThatOrdersTheUserBeforeTheWriterGoesBackToThePlanner() {
        Task model = task("Model", "Write Book.", Set.of(SHARED + "Book.java"), BOOK);
        Task service = task("Service", "Write the service.", Set.of(SHARED + "BooksService.java"),
            contract("BooksService", PKG + "BooksService", List.of("List<Book> getBooks()")));
        TaskGraph graph = graph(List.of(model, service),
            List.of(new TaskEdge(service.id(), model.id())));

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(verdict.violations()).anySatisfy(v -> assertThat(v)
            .contains("'Service' uses Book").contains("must depend on 'Model'"));
        assertThat(graph.dependencies()).containsExactly(new TaskEdge(service.id(), model.id()));
    }

    @Test
    void twoTasksWhoseContractsNameEachOthersTypesGoBackToThePlanner() {
        Task a = task("A", "Write Book.", Set.of(SHARED + "Book.java"),
            contract("Book", PKG + "Book", List.of("Shelf shelf()")));
        Task b = task("B", "Write Shelf.", Set.of(SHARED + "Shelf.java"),
            contract("Shelf", PKG + "Shelf", List.of("List<Book> books()")));
        TaskGraph graph = graph(List.of(a, b), List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(verdict.violations()).hasSize(1);
        assertThat(verdict.violations().get(0)).contains("Deliver those types in one task");
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test
    void instructionsAloneNamingEachOtherAreLeftAsPlanned() {
        Task a = task("A", "Create Book. It is later read by Shelf.",
            Set.of(SHARED + "Book.java"), contract("Book", PKG + "Book", List.of()));
        Task b = task("B", "Create Shelf, which holds a list of Book.",
            Set.of(SHARED + "Shelf.java"), contract("Shelf", PKG + "Shelf", List.of()));
        TaskGraph graph = graph(List.of(a, b), List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(verdict.violations()).isEmpty();
        assertThat(graph.dependencies()).isEmpty();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
            .startsWith("dependency not added:").contains("left as planned"));
    }

    @Test
    void instructionsNamingAnotherTasksTypeOneWayAddTheEdge() {
        // The contract said nothing (no members), but the instructions name the type and nothing
        // points the other way — the weaker evidence is still enough.
        Task a = task("A", "Create the Book class.", Set.of(SHARED + "Book.java"),
            contract("Book", PKG + "Book", List.of()));
        Task c = task("C", "Build the screen that lists every Book.",
            Set.of("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/"),
            null);
        TaskGraph graph = graph(List.of(a, c), List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(verdict.violations()).isEmpty();
        assertThat(graph.dependencies()).containsExactly(new TaskEdge(a.id(), c.id()));
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
            .contains("its instructions name Book"));
    }

    @Test
    void contractsThatSayNothingLeaveThePlanAlone() {
        Task a = task("A", "Create the model.", Set.of(SHARED + "Book.java"),
            contract("Book", PKG + "Book", List.of()));
        Task b = task("B", "Create the service interface.", Set.of(SHARED + "BooksService.java"),
            contract("BooksService", PKG + "BooksService", List.of()));
        TaskGraph graph = graph(List.of(a, b), List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(verdict.violations()).isEmpty();
        assertThat(graph.dependencies()).as("nothing to read, so nothing invented").isEmpty();
    }

    @Test
    void aSimpleNameTwoPlannedTypesShareIsNotGuessed() {
        Task a = task("A", "Write the shared Book.", Set.of(SHARED + "Book.java"), null);
        Task b = task("B", "Write the server Book.", Set.of(SERVER + "Book.java"), null);
        Task c = task("C", "Use Book somewhere.",
            Set.of("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/"),
            null);
        TaskGraph graph = graph(List.of(a, b, c), List.of());

        TaskGraphValidator.Verdict verdict = validator.validate(graph, null, null, null);

        assertThat(graph.dependencies()).isEmpty();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
            .contains("not guessing which one it means"));
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** Run 39's plan exactly as the architect produced it: B depends on nothing. */
    private record Run39(TaskGraph graph, DesignDocument design, Task model, Task service) {}

    private static Run39 run39() {
        Task model = task("Create shared data model classes (Book and Rating)",
            "Create the Book and Rating data classes in the shared module.",
            Set.of("bookshelf-demo-shared/pom.xml", SHARED + "Book.java", SHARED + "Rating.java"),
            BOOK, RATING);
        Task service = task("Create shared BooksService interface",
            "Create the `BooksService` interface in the shared module under package "
                + "com.swarmcoder.demo.bookshelf.shared. It must declare the methods: "
                + "`List<Book> getBooks()`, `List<Rating> getRatings()`, `void saveBook(Book book)`, "
                + "and `void saveRating(Rating rating)`. This is an enabler task; it does not "
                + "claim the acceptance criterion.",
            Set.of("bookshelf-demo-shared/pom.xml", SHARED + "BooksService.java"), BOOKS_SERVICE);
        Task impl = task("Implement server-side BooksService with EclipseStore",
            "Implement BooksService on the server, storing Book and Rating objects in EclipseStore.",
            Set.of("bookshelf-demo-server/pom.xml", SERVER + "BooksServiceImpl.java",
                SERVER + "BookshelfData.java"));
        Task factory = task("Implement server-side BooksServiceFactory",
            "Create BooksServiceFactory, which returns a BooksServiceImpl.",
            Set.of("bookshelf-demo-server/pom.xml", SERVER + "BooksServiceFactory.java"));
        List<TaskEdge> edges = List.of(
            new TaskEdge(model.id(), impl.id()),
            new TaskEdge(service.id(), impl.id()),
            new TaskEdge(impl.id(), factory.id()));
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1,
            "Persist books and ratings across browser restarts", List.of(), List.of(),
            List.of(BOOK, RATING, BOOKS_SERVICE), List.of(), null, Instant.now());
        return new Run39(graph(List.of(model, service, impl, factory), edges), design, model, service);
    }

    private static ApiContract contract(String name, String type, List<String> members) {
        return new ApiContract(UUID.randomUUID(), name, name + " contract", "", type, members);
    }

    private static Task task(String title, String instructions, Set<String> writeSet,
                             ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, instructions, writeSet, Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        List<ApiContract> contracts = new ArrayList<>();
        for (ApiContract contract : delivers == null ? new ApiContract[0] : delivers) {
            if (contract != null) {
                contracts.add(contract);
            }
        }
        task.setDeliveredContracts(contracts);
        return task;
    }

    private static TaskGraph graph(List<Task> tasks, List<TaskEdge> edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, edges);
    }

    private static List<Waves.Edge> edges(TaskGraph graph) {
        return graph.dependencies().stream().map(e -> new Waves.Edge(e.from(), e.to())).toList();
    }

    private static List<String> titles(List<Task> tasks) {
        return tasks.stream().map(Task::title).toList();
    }
}
