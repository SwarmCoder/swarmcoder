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
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design (harness run 38, 2026-09-25): <b>a type whose source file sits in some
 * task's write set is delivered by the plan, exactly as a design contract is</b> — see the "run
 * this also exists because of" section of {@link AcceptanceTestVocabulary}'s javadoc.
 *
 * <h2>The run this is a copy of</h2>
 *
 * <p>Run 38, story "a bookshelf demo". The design fixed four contracts, all interfaces or plain
 * data: {@code Book}, {@code Rating}, {@code BookshelfService}, {@code BookshelfStore}. The plan's
 * task 5, "Implement BookshelfServiceImpl coordinating service and store", had the write set entry
 * {@code bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/
 * BookshelfServiceImpl.java}. The test author, correctly forbidden from implementing
 * {@code BookshelfService} itself, tried to name the concrete class task 5 was about to write and
 * was refused: "nothing in this plan delivers" it, though task 5's own write set said otherwise.
 *
 * <h2>What is asserted here</h2>
 *
 * <ol>
 *   <li>a test naming a task's write-set-delivered implementation class is accepted, even though
 *       no design contract names it, and the vocabulary shown to the author says which task
 *       delivers it;</li>
 *   <li>the run-13 shape is unchanged: a type in NEITHER a contract NOR any task's write set is
 *       still refused;</li>
 *   <li>a bare (unqualified) simple name shared by two different tasks' write sets in different
 *       packages is NOT resolved automatically — it stays subject to the ordinary checks — while a
 *       fully-qualified import naming one of the two is still accepted;</li>
 *   <li>the self-implementation rule's wiring sentence points at a task's planned implementation
 *       class even before that file exists anywhere in the checkout.</li>
 * </ol>
 */
class WriteSetTypesAreDeliveredTest {

    @TempDir
    Path repo;

    private static final String IMPL_TASK_TITLE =
        "Implement BookshelfServiceImpl coordinating service and store";

    @Test
    void aTasksWriteSetDeliveredImplementationIsAcceptedEvenThoughNoContractNamesIt()
            throws Exception {
        String path = "src/test/java/swarm/accept/BookshelfServiceTest.java";
        write(path, """
            package swarm.accept;
            import com.swarmcoder.demo.bookshelf.Book;
            import com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl;
            class BookshelfServiceTest {
                void addsABook() {
                    BookshelfServiceImpl service = new BookshelfServiceImpl();
                    Book book = new Book();
                }
            }
            """);

        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(repo, design(), List.of(path), List.of(implTask()));

        assertThat(check.ok())
            .as("BookshelfServiceImpl's own file is task 5's write set; the plan delivers it")
            .isTrue();
        assertThat(check.vocabulary())
            .as("the author is told up front which concrete class task 5 delivers")
            .anyMatch(line -> line.contains("BookshelfServiceImpl")
                && line.contains("com.swarmcoder.demo.bookshelf.server.BookshelfServiceImpl")
                && line.contains(IMPL_TASK_TITLE));
    }

    @Test
    void aTypeInNeitherAContractNorAnyWriteSetIsStillRefused() throws Exception {
        // The run-13 shape: nobody's write set and no contract names this type.
        String path = "src/test/java/swarm/accept/RatingTest.java";
        write(path, """
            package swarm.accept;
            import com.swarmcoder.demo.bookshelf.Rating;
            class RatingTest {
                void assignsRating() {
                    Rating rating = new Rating(4);
                }
            }
            """);

        AcceptanceTestVocabulary.Check check =
            AcceptanceTestVocabulary.check(repo, design(), List.of(path), List.of(implTask()));

        assertThat(check.ok()).isFalse();
        assertThat(check.unknowns()).extracting(AcceptanceTestVocabulary.Unknown::typeName)
            .containsExactly("com.swarmcoder.demo.bookshelf.Rating");
    }

    @Test
    void aBareSimpleNameSharedByTwoTasksWriteSetsIsNotResolvedAutomatically() throws Exception {
        Task moduleA = task("Build module A",
            Set.of("mod-a/src/main/java/com/acme/a/Widget.java"));
        Task moduleB = task("Build module B",
            Set.of("mod-b/src/main/java/com/acme/b/Widget.java"));

        String bare = "src/test/java/swarm/accept/BareWidgetTest.java";
        write(bare, """
            package swarm.accept;
            class BareWidgetTest {
                void usesWidget() {
                    Widget w = new Widget();
                }
            }
            """);
        AcceptanceTestVocabulary.Check bareCheck = AcceptanceTestVocabulary.check(
            repo, design(), List.of(bare), List.of(moduleA, moduleB));
        assertThat(bareCheck.ok())
            .as("two different planned types answer to the bare name 'Widget'; guessing which one "
                + "the author meant is not this check's job")
            .isFalse();
        assertThat(bareCheck.unknowns()).extracting(AcceptanceTestVocabulary.Unknown::typeName)
            .containsExactly("Widget");

        String qualified = "src/test/java/swarm/accept/QualifiedWidgetTest.java";
        write(qualified, """
            package swarm.accept;
            import com.acme.a.Widget;
            class QualifiedWidgetTest {
                void usesWidget() {
                    Widget w = new Widget();
                }
            }
            """);
        AcceptanceTestVocabulary.Check qualifiedCheck = AcceptanceTestVocabulary.check(
            repo, design(), List.of(qualified), List.of(moduleA, moduleB));
        assertThat(qualifiedCheck.ok())
            .as("a fully-qualified import names exactly one of the two planned types, unambiguously")
            .isTrue();
    }

    @Test
    void theWiringSentencePointsAtAPlannedImplementationBeforeItExistsInTheTree() throws Exception {
        // Only the interface is agreed as a contract; BookServiceImpl is not in the checkout yet —
        // it is only a promise in a task's write set, exactly as at TEST_AUTHORING before any wave
        // has run.
        String path = "src/test/java/swarm/accept/BookManagementTest.java";
        write(path, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            class BookManagementTest {
                void ratesABook() {
                    BookService service = new BookService() {
                        public void rate(Book b, int stars) { }
                    };
                }
            }
            """);
        Task implTask = task("Implement BookServiceImpl",
            Set.of("src/main/java/com/acme/shop/BookServiceImpl.java"));

        SelfImplementedContract.Check check = SelfImplementedContract.check(
            repo, bookDesign(), List.of(path), List.of(implTask));

        assertThat(check.ok()).isFalse();
        assertThat(check.wiring())
            .as("the wiring sentence names the implementation a task's write set promises, though "
                + "no such file exists anywhere in the checkout")
            .contains("com.acme.shop.BookServiceImpl");
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Task implTask() {
        return task(IMPL_TASK_TITLE, Set.of(
            "bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/"
                + "BookshelfServiceImpl.java"));
    }

    private static Task task(String title, Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "bookshelf-demo-server/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** Four interfaces/data contracts, none of them the server's implementation — the run-38 shape. */
    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "a bookshelf demo", List.of(), List.of(),
            List.of(
                new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
                    "com.swarmcoder.demo.bookshelf.Book", List.of("String title()")),
                new ApiContract(UUID.randomUUID(), "BookshelfService", "coordinates the shelf",
                    "BookshelfService", "com.swarmcoder.demo.bookshelf.server.BookshelfService",
                    List.of("void add(Book)")),
                new ApiContract(UUID.randomUUID(), "BookshelfStore", "stores books",
                    "BookshelfStore", "com.swarmcoder.demo.bookshelf.server.BookshelfStore",
                    List.of("void save(Book)"))),
            List.of(), null, Instant.now());
    }

    private static DesignDocument bookDesign() {
        return new DesignDocument(UUID.randomUUID(), 1, "rate a book", List.of(), List.of(),
            List.of(
                new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
                    "com.acme.shop.Book", List.of("int rating")),
                new ApiContract(UUID.randomUUID(), "BookService", "rates books", "BookService",
                    "com.acme.shop.BookService", List.of("void rate(Book, int)"))),
            List.of(), null, Instant.now());
    }
}
