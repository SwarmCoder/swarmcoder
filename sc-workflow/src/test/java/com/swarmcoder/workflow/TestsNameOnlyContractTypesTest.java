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
 * The decided design: <b>an acceptance test may name only the types the design agreed and the
 * types the checkout already has.</b> A test naming anything else is sent back to its author once,
 * with the list, and a run that still gets one stops there.
 *
 * <h2>The run this is a copy of</h2>
 *
 * <p>2026-09-03, run 13, story "Assign a rating to a book". The test author wrote its acceptance
 * test against {@code com.swarmcoder.demo.bookshelf.Rating}. Nobody had agreed that type: the
 * design's contracts were a {@code Book} carrying a rating and a {@code BookService}, and those
 * are what the first two waves delivered. The third wave's task writes only the client module, so
 * it could not have created a shared type even if it had understood that it was expected to.
 *
 * <p>Both of its candidates died with the same sentence — the tree does not compile, because the
 * acceptance test refers to a class that does not exist — and the sentence was honest, and useless.
 * Three waves of real work, and nothing delivered.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>A design with exactly two contracts, and a scripted author that does what the real one did:
 * its first reply names {@code Rating}. Then
 *
 * <ol>
 *   <li>the author is asked again, and the question names the offending type AND lists the whole
 *       vocabulary it may use;</li>
 *   <li>its corrected reply, which names only the contracts, is what ends up on disk;</li>
 *   <li>the first attempt's file does not survive anywhere — a superseded test naming an
 *       undeliverable type must not be committed with the run;</li>
 *   <li>the result reports no naming problem, so the run carries on.</li>
 * </ol>
 *
 * <p>And the other half: an author that will not correct itself parks the run, with a brief that
 * names the type and the contracts.
 */
class TestsNameOnlyContractTypesTest {

    private static final String DIR = "src/test/java/swarm/accept";
    private static final String TEST_FILE = DIR + "/BookRatingTest.java";
    /** The file the first, wrong attempt writes — deliberately a different name from the second. */
    private static final String FIRST_FILE = DIR + "/RatingTest.java";

    @TempDir
    Path repo;

    @Test
    void anAuthorThatNamesATypeNobodyDeliversIsAskedAgainAndItsCorrectionIsWhatSurvives()
            throws Exception {
        List<String> conversations = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            conversations.add(conversation);
            return replies.getAndIncrement() == 0 ? namesRating() : namesOnlyContracts();
        })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(repo, task(), design());

            // --- 1. the author was asked again, and told exactly what was wrong ----------------
            assertThat(conversations).as("one first attempt and one re-ask").hasSize(2);
            String reask = conversations.get(1);
            assertThat(reask)
                .as("the complaint names the type nobody delivers")
                .contains("com.acme.shop.Rating")
                .contains("is not a contract and does not exist in this project");
            assertThat(reask)
                .as("and then gives the whole vocabulary it may use, with members")
                .contains("com.acme.shop.Book{int rating; String title(); }")
                .contains("com.acme.shop.BookService{void rate(Book, int); }");

            // --- 2. the corrected reply is what is on disk ------------------------------------
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(Files.readString(repo.resolve(TEST_FILE)))
                .contains("com.acme.shop.Book")
                .contains("com.acme.shop.BookService")
                .doesNotContain("com.acme.shop.Rating");

            // --- 3. the first attempt left nothing behind -------------------------------------
            assertThat(Files.exists(repo.resolve(FIRST_FILE)))
                .as("a superseded test naming an undeliverable type must not be committed")
                .isFalse();

            // --- 4. the run carries on ---------------------------------------------------------
            assertThat(authored.vocabularyIsAnswerable()).isTrue();
        }
    }

    @Test
    void anAuthorThatWillNotCorrectItselfParksTheRunNamingTheTypeAndTheContracts() throws Exception {
        AtomicInteger replies = new AtomicInteger();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
            replies.incrementAndGet();
            return namesRating();
        })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(repo, task(), design());

            assertThat(replies).as("asked once more and no more than once").hasValue(2);
            assertThat(authored.vocabularyIsAnswerable()).isFalse();
            assertThat(authored.vocabulary().unknowns()).extracting(
                    AcceptanceTestVocabulary.Unknown::typeName)
                .containsExactly("com.acme.shop.Rating");

            String brief = AcceptanceTestVocabulary.brief("Show a book's rating",
                authored.vocabulary());
            assertThat(brief)
                .contains("Show a book's rating")
                .contains("com.acme.shop.Rating")
                .contains("com.acme.shop.Book{int rating; String title(); }")
                .contains("The design's contracts are the only new types this run creates");
        }
    }

    /**
     * A test naming a type that is neither a contract nor in the tree, and nothing else out of the
     * ordinary: it uses JUnit and {@code java.util}, which must not be reported.
     */
    private static String namesRating() {
        return json(FIRST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.Rating;
            import java.util.List;
            import org.junit.jupiter.api.Test;
            class RatingTest {
                @Test void assignsRatingToBook() {
                    Book book = new Book();
                    Rating rating = new Rating(4);
                    List<Book> all = List.of(book);
                }
            }
            """);
    }

    private static String namesOnlyContracts() {
        return json(TEST_FILE, """
            package swarm.accept;
            import com.acme.shop.Book;
            import com.acme.shop.BookService;
            import java.util.List;
            import org.junit.jupiter.api.Test;
            class BookRatingTest {
                @Test void assignsRatingToBook() {
                    Book book = new Book();
                    BookService service = new BookService();
                    service.rate(book, 4);
                    List<Book> all = List.of(book);
                }
            }
            """);
    }

    private static String json(String path, String content) {
        return "{\"files\":[{\"path\":\"" + path + "\",\"content\":"
            + quote(content) + "}],\"wrote\":[{\"criterion\":\"a rating can be assigned\","
            + "\"test\":\"swarm.accept."
            + path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length())
            + "#assignsRatingToBook\"}]}";
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
        return new Task(UUID.randomUUID(), 1, "Show a book's rating",
            "Render the rating on the book row.", Set.of("src/main/java/com/acme/shop/client"),
            Set.of(), List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "a rating can be assigned", "swarm.accept.BookRatingTest#assignsRatingToBook")),
            "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    /** Two contracts and no third: exactly the vocabulary the author is held to. */
    private static DesignDocument design() {
        return new DesignDocument(UUID.randomUUID(), 1, "rate a book", List.of(), List.of(),
            List.of(
                new ApiContract(UUID.randomUUID(), "Book", "a book on the shelf", "Book",
                    "com.acme.shop.Book", List.of("int rating", "String title()")),
                new ApiContract(UUID.randomUUID(), "BookService", "rates books", "BookService",
                    "com.acme.shop.BookService", List.of("void rate(Book, int)"))),
            List.of(), null, Instant.now());
    }
}
