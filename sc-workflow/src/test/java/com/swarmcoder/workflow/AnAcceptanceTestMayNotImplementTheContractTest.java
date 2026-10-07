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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decided design: <b>an acceptance test may not supply its own implementation of a
 * contract.</b> Naming a contract is required; being one is forbidden. A test that writes the
 * behaviour it is supposed to be measuring goes green with nothing delivered at all.
 *
 * <h2>The run this is a copy of</h2>
 *
 * <p>Harness run 30, 2026-09-05, 13:04. Story "rate a book", check "A user can assign a rating to a
 * book", test {@code swarm.accept.BookManagementTest#assignsRatingToBook}. The test author wrote an
 * anonymous {@code BookService} inside the test and asserted against that. It failed on both
 * wave-3 candidates — on its own field-initialisation order, nothing to do with either candidate —
 * was "repaired" by moving three lines, and then passed for both. No candidate's code was executed
 * at any point. At 13:10 the story was stamped delivered.
 *
 * <p>Every existing gate said something true and useless. The vocabulary check
 * ({@link AcceptanceTestVocabulary}) waved it through because {@code BookService} and {@code Book}
 * are contract types, which is exactly what that check demands.
 *
 * <h2>What is asserted here</h2>
 *
 * <ol>
 *   <li>the run-30 test verbatim is rejected at authoring, and the author is told the sentence:
 *       its test implements the contract itself, so it can pass with no delivered code at all, and
 *       here is how the application obtains a real one;</li>
 *   <li>its corrected reply — which constructs the delivered implementation — is what ends up on
 *       disk, and the run carries on;</li>
 *   <li>the first attempt's file does not survive anywhere;</li>
 *   <li>an author that will not correct itself parks the run, with a brief that quotes its own
 *       code back to it;</li>
 *   <li>the other stand-in shapes are caught too — a lambda, a nested class implementing the
 *       contract, and a Mockito mock of it;</li>
 *   <li>and an ordinary honest test, which names the contracts and constructs what the plan
 *       delivers, is not accused.</li>
 * </ol>
 */
class AnAcceptanceTestMayNotImplementTheContractTest {

    private static final String DIR = "src/test/java/swarm/accept";
    private static final String TEST_FILE = DIR + "/BookManagementTest.java";

    @TempDir
    Path repo;

    /**
     * The checkout the tests are written into already holds the delivered implementation, the way a
     * later wave's tree does. Without it the correction's import of {@code BookServiceImpl} would
     * be a name nobody delivers, which is a different fault with its own gate.
     */
    @org.junit.jupiter.api.BeforeEach
    void deliveredCodeIsInTheTree() throws Exception {
        write("src/main/java/com/acme/shop/Book.java", """
            package com.acme.shop;
            public class Book { public String title; public int rating; }
            """);
        write("src/main/java/com/acme/shop/BookService.java", """
            package com.acme.shop;
            public interface BookService { void rate(Book book, int stars); }
            """);
        write("src/main/java/com/acme/shop/BookServiceImpl.java", """
            package com.acme.shop;
            public class BookServiceImpl implements BookService {
                public void rate(Book book, int stars) { book.rating = stars; }
            }
            """);
    }

    @Test
    void theRun30TestIsRejectedAtAuthoringAndItsCorrectionIsWhatSurvives() throws Exception {
        List<String> conversations = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return replies.getAndIncrement() == 0 ? run30Test() : usesTheDeliveredService();
        })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(repo, task(), design());

            // --- 1. the author was asked again, with the sentence ------------------------------
            assertThat(conversations).as("one first attempt and one re-ask").hasSize(2);
            String reask = conversations.get(1);
            assertThat(reask)
                .as("the sentence the author is told, verbatim in its parts")
                .contains("Your test implements `BookService` itself, so it can pass with no "
                    + "delivered code at all.")
                .contains("Obtain the real one the way the application does")
                .contains("and assert on what it does.");
            assertThat(reask)
                .as("and it names how the application gets one, and quotes the offending line")
                .contains("BookServiceImpl")
                .contains("new BookService()");

            // --- 2. the corrected reply is what is on disk -------------------------------------
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(Files.readString(repo.resolve(TEST_FILE)))
                .contains("new BookServiceImpl()")
                .doesNotContain("new BookService() {");

            // --- 3. and the run carries on -----------------------------------------------------
            assertThat(authored.provesDeliveredCode()).isTrue();
            assertThat(authored.vocabularyIsAnswerable()).isTrue();
        }
    }

    @Test
    void anAuthorThatWillNotCorrectItselfParksTheRunQuotingItsOwnCode() throws Exception {
        AtomicInteger replies = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            replies.incrementAndGet();
            return run30Test();
        })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(repo, task(), design());

            assertThat(replies).as("asked once more and no more than once").hasValue(2);
            assertThat(authored.provesDeliveredCode()).isFalse();
            assertThat(authored.selfImplemented().contractTypes()).containsExactly("BookService");

            String brief = SelfImplementedContract.brief("Rate a book",
                authored.selfImplemented());
            assertThat(brief)
                .contains("Rate a book")
                .contains("`BookService`")
                .contains("new BookService()")
                .contains("passes with no delivered code at all")
                .contains("BookServiceImpl");
        }
    }

    @Test
    void anHonestTestThatUsesTheDeliveredImplementationIsNotAccused() throws Exception {
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> usesTheDeliveredService())) {
            TestAuthorClient.Authored authored = author(llm).authorTests(repo, task(), design());

            assertThat(authored.provesDeliveredCode())
                .as("constructing what the plan delivers is exactly what a test SHOULD do")
                .isTrue();
            assertThat(authored.paths()).containsExactly(TEST_FILE);
        }
    }

    @Test
    void theOtherStandInShapesAreCaughtToo() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.BookService;
            import static org.mockito.Mockito.mock;
            class BookManagementTest {
                // implements BookService — this comment must not be evidence
                void lambda() { BookService s = (b, r) -> { }; }
                void mocked() { BookService s = mock(BookService.class); }
                static class Stub implements BookService { }
            }
            """);
        SelfImplementedContract.Check check =
            SelfImplementedContract.check(repo, design(), List.of(TEST_FILE));

        assertThat(check.ok()).isFalse();
        assertThat(check.contractTypes()).containsExactly("BookService");
        assertThat(check.findings()).extracting(SelfImplementedContract.SelfImplementation::how)
            .as("the lambda, the mock and the nested implementation are each their own finding")
            .contains("the test writes a lambda that IS the contract",
                "the test mocks or stubs the contract",
                "a class in the test implements or extends the contract");
    }

    @Test
    void aCommentAndAPlainConstructionAreNotEvidence() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import com.acme.shop.BookServiceImpl;
            /** This test never implements BookService itself; it extends nothing. */
            class BookManagementTest {
                void rates() {
                    Book book = new Book();
                    BookService service = new BookServiceImpl();
                    service.rate(book, 5);
                }
            }
            """);
        assertThat(SelfImplementedContract.check(repo, design(), List.of(TEST_FILE)).ok())
            .as("the word 'implements' in prose, and constructing the delivered type, are fine")
            .isTrue();
    }

    /**
     * Harness run 42, 2026-09-26, live run. The test author wrote ordinary use of the delivered
     * {@code Book} class — filtering a list it got from the delivered service — and the old
     * {@code LAMBDA} pattern read the arrow inside the {@code filter} predicate, three method calls
     * after the "=", as {@code existing}'s own initializer. The run parked before a single worker
     * ran, on both of its two authoring attempts, quoting a line that never implemented anything.
     * {@code Book} is a {@code @DataModel} class with public fields, not an interface, and the
     * lambda belongs to {@code stream().filter(...)}, not to the declaration.
     */
    @Test
    void theRunFortyTwoStreamLambdaOverADeliveredClassIsNotEvidence() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import com.acme.shop.BookServiceImpl;
            class BookshelfCrudTest {
                void updatesABook() {
                    BookService service = new BookServiceImpl();
                    Book existing = service.getBooks().stream()
                            .filter(b -> "The Hobbit".equals(b.title))
                            .findFirst()
                            .orElseThrow();
                    Book updated = new Book();
                    updated.title = existing.title;
                }
            }
            """);
        assertThat(SelfImplementedContract.check(repo, design(), List.of(TEST_FILE)).ok())
            .as("a stream predicate three calls into the initializer is not the declaration's own "
                + "lambda, and `Book` is a class no lambda could implement anyway")
            .isTrue();
    }

    /**
     * The general form of the run-42 fix: a lambda can never implement a class, so {@code LAMBDA}
     * must not fire on a contract the checkout confirms is one — even for text shaped exactly like
     * the pattern it looks for. (This snippet would not compile; the scanner reads text, not
     * bytecode, so this isolates the interface-confirmation half of the fix from the "whole
     * initializer" half covered above.)
     */
    @Test
    void aLambdaShapedInitializerOnAConfirmedClassIsNotEvidence() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            class BookManagementTest {
                void rates() {
                    Book b = x -> x;
                }
            }
            """);
        assertThat(SelfImplementedContract.check(repo, design(), List.of(TEST_FILE)).ok())
            .as("`Book` is declared as a class in the checkout; a lambda can never implement one")
            .isTrue();
    }

    /**
     * A genuine bare-arrow implementation is still caught: the whole right-hand side is the
     * lambda, and the contract is a real interface. Companion to the two "not evidence" tests
     * above, so the fix is pinned from both sides.
     */
    @Test
    void aBareLambdaThatIsTheWholeInitializerIsStillCaught() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.BookService;
            class BookManagementTest {
                void rates() {
                    BookService s = x -> { };
                }
            }
            """);
        SelfImplementedContract.Check check =
            SelfImplementedContract.check(repo, design(), List.of(TEST_FILE));
        assertThat(check.ok()).isFalse();
        assertThat(check.contractTypes()).containsExactly("BookService");
        assertThat(check.findings()).extracting(SelfImplementedContract.SelfImplementation::how)
            .containsExactly("the test writes a lambda that IS the contract");
    }

    /**
     * Found auditing the sibling checks for the run-42 weakness (same brief): {@code SUBTYPE}
     * matched {@code extends} inside a type parameter's bound or a wildcard bound — ordinary,
     * common Java that names a contract type without any class standing in for it.
     */
    @Test
    void aGenericBoundOnTheContractIsNotEvidenceOfExtendingIt() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import java.util.List;
            class BookManagementTest {
                <T extends Book> void generic(T t) { }
                void wildcard(List<? extends Book> books) { }
            }
            """);
        assertThat(SelfImplementedContract.check(repo, design(), List.of(TEST_FILE)).ok())
            .as("a type parameter bound and a wildcard bound both use 'extends' without any class "
                + "in the test implementing or extending the contract")
            .isTrue();
    }

    /** The real case {@code SUBTYPE} exists for still fires next to the bound shapes above. */
    @Test
    void anActualSubclassNextToAGenericBoundIsStillCaught() throws Exception {
        write(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import java.util.List;
            class BookManagementTest {
                void wildcard(List<? extends Book> books) { }
                static class Stub implements BookService { }
            }
            """);
        SelfImplementedContract.Check check =
            SelfImplementedContract.check(repo, design(), List.of(TEST_FILE));
        assertThat(check.ok()).isFalse();
        assertThat(check.contractTypes()).containsExactly("BookService");
    }

    /** The run-30 test, as the author actually wrote it. */
    private static String run30Test() {
        return json("""
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BookManagementTest {
                @Test void assignsRatingToBook() {
                    BookService service = new BookService() {
                        private final Book book = new Book();
                        public void rate(Book b, int stars) { b.rating = stars; }
                    };
                    Book book = new Book();
                    book.title = "Dune";
                    book.rating = 0;
                    service.rate(book, 5);
                    assertEquals(5, book.rating);
                }
            }
            """);
    }

    /** The same check, proved through the implementation the plan delivers. */
    private static String usesTheDeliveredService() {
        return json("""
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import com.acme.shop.BookServiceImpl;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BookManagementTest {
                @Test void assignsRatingToBook() {
                    BookService service = new BookServiceImpl();
                    Book book = new Book();
                    book.title = "Dune";
                    service.rate(book, 5);
                    assertEquals(5, book.rating);
                }
            }
            """);
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String json(String content) {
        return "{\"files\":[{\"path\":\"" + TEST_FILE + "\",\"content\":" + quote(content)
            + "}],\"wrote\":[{\"criterion\":\"A user can assign a rating to a book\","
            + "\"test\":\"swarm.accept.BookManagementTest#assignsRatingToBook\"}]}";
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static TestAuthorClient author(ScriptedLlm llm) {
        return new TestAuthorClient(new VllmClient(llm.baseUrl(), "", "scripted", true),
            new CloudGate(1_000_000, null));
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Rate a book",
            "Let a reader give a book a rating.", Set.of("src/main/java/com/acme/shop"),
            Set.of(), List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "A user can assign a rating to a book",
                "swarm.accept.BookManagementTest#assignsRatingToBook")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /**
     * The same two contracts run 30 had. {@code BookServiceImpl} is a third contract rather than a
     * file in the checkout so that the wiring sentence has something concrete to name — which is
     * how a real design that fixes an implementation type reads.
     */
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
