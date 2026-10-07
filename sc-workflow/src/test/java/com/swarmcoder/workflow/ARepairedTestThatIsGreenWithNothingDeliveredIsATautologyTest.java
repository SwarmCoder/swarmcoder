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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design: <b>a repaired acceptance test that is green with no candidate applied is a
 * tautology</b>, and it is sent back once before the run parks.
 *
 * <h2>The run this is a copy of</h2>
 *
 * <p>Harness run 30, 2026-09-05. The acceptance test wrote its own anonymous {@code BookService}.
 * It failed for both wave-3 candidates on its own field-initialisation order, was classified as
 * "the test's own fault" and sent to its author, and the author "repaired" it by MOVING THREE
 * LINES — the anonymous implementation stayed exactly where it was. Both candidates then passed.
 * No candidate's code was executed at any point, and the story was stamped delivered.
 *
 * <p>Two gates were missing at that moment, and both are asserted here. The repair path never
 * asked whether the correction still implemented the contract itself, and it never re-asked
 * whether a test that is now green on the untouched tree can prove anything at all.
 */
class ARepairedTestThatIsGreenWithNothingDeliveredIsATautologyTest {

    private static final String TEST_FILE = "src/test/java/swarm/accept/BookManagementTest.java";

    @TempDir
    Path repo;

    @BeforeEach
    void deliveredCodeIsInTheTree() throws Exception {
        write("src/main/java/com/acme/shop/Book.java", """
            package com.acme.shop;
            public class Book { public String title; public int rating; }
            """);
        write("src/main/java/com/acme/shop/BookServiceImpl.java", """
            package com.acme.shop;
            public class BookServiceImpl implements BookService {
                public void rate(Book book, int stars) { book.rating = stars; }
            }
            """);
    }

    /**
     * The repair run 30 actually accepted: the three lines that set up the book were moved above
     * the anonymous service, and nothing else changed. It compiles, it is green, and it proves
     * nothing.
     */
    @Test
    void theRepairThatOnlyMovedThreeLinesIsStillTheTestImplementingTheContract() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BookManagementTest {
                @Test void assignsRatingToBook() {
                    Book book = new Book();
                    book.title = "Dune";
                    book.rating = 0;
                    BookService service = new BookService() {
                        public void rate(Book b, int stars) { b.rating = stars; }
                    };
                    service.rate(book, 5);
                    assertEquals(5, book.rating);
                }
            }
            """);

        SelfImplementedContract.Check check =
            SelfImplementedContract.check(repo, design(), List.of(TEST_FILE));

        assertThat(check.ok())
            .as("moving lines does not stop a test from being its own implementation")
            .isFalse();
        assertThat(check.contractTypes()).containsExactly("BookService");
        assertThat(SelfImplementedContract.brief("Rate a book", check))
            .contains("passes with no delivered code at all")
            .contains("stamped delivered")
            .contains("BookServiceImpl");
    }

    /** What the author is told when its repaired test turns out to be green on the bare tree. */
    @Test
    void theTautologyReAskSaysWhatIsWrongAndHowTheApplicationGetsTheRealThing() {
        String wiring = SelfImplementedContract.wiringFor(repo, design());
        assertThat(wiring).contains("com.acme.shop.BookServiceImpl");

        assertThat(SelfImplementedContract.tautologyReask(wiring))
            .contains("passes on the tree as it was BEFORE any of this work")
            .contains("with nothing delivered at all")
            .contains("it measures only itself")
            .contains("Obtain the delivered implementation the way the application does")
            .contains("com.acme.shop.BookServiceImpl")
            .contains("must FAIL until the code exists")
            .contains("Reply with the same JSON object");
    }

    /** And the brief the run parks with when the second attempt is green too. */
    @Test
    void theParkBriefNamesTheTestTheTaskAndTheWiring() {
        String wiring = SelfImplementedContract.wiringFor(repo, design());
        String brief = SelfImplementedContract.tautologyBrief("Rate a book",
            "swarm.accept.BookManagementTest", wiring,
            "the first repair was green on that tree and so is the second");

        assertThat(brief)
            .contains("swarm.accept.BookManagementTest")
            .contains("Rate a book")
            .contains("PASSES on the tree as it was before any work started")
            .contains("without any candidate's code being executed")
            .contains("asked once to correct this and did not")
            .contains("com.acme.shop.BookServiceImpl")
            .contains("the first repair was green on that tree and so is the second");
    }

    /** A design that wires through CDI is told back in the project's own terms, not a guess. */
    @Test
    void aProjectThatWiresThroughCdiIsToldToAskTheContainer() throws Exception {
        write("src/main/java/com/acme/shop/BookService.java", """
            package com.acme.shop;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public interface BookService { void rate(Book book, int stars); }
            """);

        assertThat(SelfImplementedContract.wiringFor(repo, design()))
            .contains("CDI.current().select(")
            .contains("@Inject");
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "rate a book", List.of(), List.of(),
            List.of(
                new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
                    "com.acme.shop.Book", List.of("int rating", "String title")),
                new ApiContract(UUID.randomUUID(), "BookService", "rates books", "BookService",
                    "com.acme.shop.BookService", List.of("void rate(Book, int)"))),
            List.of(), null, Instant.now());
    }
}
