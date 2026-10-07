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

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner guesses the order before any code exists, so it will sometimes be wrong. These are the
 * facts the system is allowed to use to correct itself, and the guesses it is not.
 */
class MissingPieceDetectorTest {

    private static final String JAVAC_MISSING_CLASS = """
        /work/src/main/java/app/SearchService.java:14: error: cannot find symbol
            private final BookRepository repository;
                          ^
          symbol:   class BookRepository
          location: class SearchService
        1 error
        """;

    private static final String JAVAC_MISSING_PACKAGE = """
        /work/src/main/java/app/SearchService.java:3: error: package app.books does not exist
        import app.books.Book;
                        ^
        """;

    private static CandidateSolution candidate(boolean compiles, String log) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, compiles,
            null, null, null, null, null, log, null);
        return new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "swarm/t/0",
            new SamplingConfig("worker", 0.5, 0L, "minimal-diff", "full-files"), "",
            report, null, null, CandidateState.FAILED, null);
    }

    private static Story story(String key, String title, String narrative, StoryState state) {
        return new Story(UUID.randomUUID(), UUID.randomUUID(), key, StoryKind.DELIVERY, title,
            narrative, state, new ArrayList<>(), new ArrayList<>(), null, 0, StoryOrigin.BACKLOG,
            null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
    }

    @Test
    void readsTheNameJavacCouldNotFind() {
        assertThat(MissingPieceDetector.namesIn(JAVAC_MISSING_CLASS)).contains("BookRepository");
        assertThat(MissingPieceDetector.namesIn(JAVAC_MISSING_PACKAGE)).contains("app.books");
    }

    @Test
    void oneWorkerAgreeingWithItselfIsNotAgreement() {
        // Eight candidates, one of which failed on a name of its own invention: that is one worker
        // misspelling something, not a fact about the code.
        List<CandidateSolution> candidates = new ArrayList<>();
        candidates.add(candidate(false, JAVAC_MISSING_CLASS));
        for (int i = 0; i < 7; i++) {
            candidates.add(candidate(false, "some other failure entirely"));
        }
        assertThat(MissingPieceDetector.examine(candidates, List.of(), null).any()).isFalse();
    }

    @Test
    void independentWorkersFailingOnTheSameNameIsAFact() {
        List<CandidateSolution> candidates = List.of(
            candidate(false, JAVAC_MISSING_CLASS),
            candidate(false, JAVAC_MISSING_CLASS),
            candidate(false, JAVAC_MISSING_CLASS));
        MissingPieceDetector.Finding finding =
            MissingPieceDetector.examine(candidates, List.of(), null);
        assertThat(finding.any()).isTrue();
        assertThat(finding.missing()).contains("BookRepository");
    }

    @Test
    void aRunThatCompiledIsNotThisCaseAtAll() {
        List<CandidateSolution> green = List.of(candidate(true, ""), candidate(true, ""));
        assertThat(MissingPieceDetector.examine(green, List.of(), null).any()).isFalse();
    }

    @Test
    void namesTheStoryThatWasGoingToProvideTheMissingThing() {
        Story provider = story("S1", "Store a book record",
            "Adds the BookRepository that everything else reads from", StoryState.READY);
        Story unrelated = story("S9", "Send a newsletter", "Emails subscribers", StoryState.READY);

        MissingPieceDetector.Finding finding = MissingPieceDetector.examine(
            List.of(candidate(false, JAVAC_MISSING_CLASS), candidate(false, JAVAC_MISSING_CLASS)),
            List.of(provider, unrelated), null);

        assertThat(finding.hasProvider()).isTrue();
        assertThat(finding.providers()).containsExactly(provider);
        assertThat(finding.explanation()).contains("S1").contains("Store a book record");
    }

    @Test
    void anAlreadyDeliveredStoryIsNotAProvider() {
        // If it is already accepted, its code is on the delivery branch; the missing name is
        // something else, and waiting for a finished story would be waiting for ever.
        Story delivered = story("S1", "Store a book record",
            "Adds the BookRepository", StoryState.DONE);
        MissingPieceDetector.Finding finding = MissingPieceDetector.examine(
            List.of(candidate(false, JAVAC_MISSING_CLASS), candidate(false, JAVAC_MISSING_CLASS)),
            List.of(delivered), null);
        assertThat(finding.any()).isTrue();
        assertThat(finding.hasProvider()).isFalse();
        assertThat(finding.explanation()).contains("nothing else that is planned");
    }

    @Test
    void aPartialWordIsNotAMatch() {
        // "Bookmark" is not "Book". An edge inferred from a substring stops a story for a reason
        // that is not true, and nobody would ever work out why.
        Story bookmarks = story("S1", "Bookmarking", "Lets a reader keep bookmarks",
            StoryState.READY);
        String log = """
            error: cannot find symbol
              symbol:   class Book
            """;
        MissingPieceDetector.Finding finding = MissingPieceDetector.examine(
            List.of(candidate(false, log), candidate(false, log)), List.of(bookmarks), null);
        assertThat(finding.any()).isTrue();
        assertThat(finding.hasProvider()).isFalse();
    }
}
