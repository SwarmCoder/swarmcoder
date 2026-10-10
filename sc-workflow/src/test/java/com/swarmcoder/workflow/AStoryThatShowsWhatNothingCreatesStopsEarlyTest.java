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

import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.verify.JourneyFile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 80 (live run 102): a story that shows a kind of thing, planned before the story that
 * lets a person add one, cannot be proven on an empty application. Its test author says so in a
 * field of its own, and the run stops when the tests are written with a question that names the
 * finding and what can be done. Nothing here reads wording; no model is called.
 */
class AStoryThatShowsWhatNothingCreatesStopsEarlyTest {

    private static final UUID PROJECT = UUID.randomUUID();

    @Test
    void theAnswerIsAFieldOfItsOwnAndNothingElseIsReadAsIt() {
        assertThat(JourneyFile.nothingCreatesOf("nothingCreates: a book in the list\n"))
            .isEqualTo("a book in the list");
        assertThat(JourneyFile.nothingCreatesOf("nothingCreates: \"\"\n")).isNull();
        assertThat(JourneyFile.nothingCreatesOf("noVisibleEffect: server only\n")).isNull();
        assertThat(JourneyFile.nothingCreatesOf(
            "journey: nothing creates a book\nsteps:\n  - expectVisible: \"text=Books\"\n"))
            .as("a journey whose words say it is still a journey").isNull();
        assertThat(JourneyFile.nothingCreatesOf("nothingCreates: a book\nsteps: []\n"))
            .as("the key alone, or it is a draft").isNull();
        assertThat(JourneyFile.waiverOf("nothingCreates: a book\n"))
            .as("not the answer that nothing on a screen differs").isNull();
    }

    @Test
    void itStandsUnlessATaskOfTheSameStoryClaimsAJourney() {
        Task list = task("List of books");
        Task form = task("Form to add a book");
        assertThat(NothingCreatesIt.standing("a book in the list", List.of(list, form)))
            .isEqualTo("a book in the list");
        assertThat(NothingCreatesIt.standing(null, List.of(list, form))).isNull();

        form.setJourneyPaths(List.of("src/test/java/swarm/accept/add-book.journey.yaml"));
        assertThat(NothingCreatesIt.standing("a book in the list", List.of(list, form)))
            .as("the story has a journey: it is made in the browser, and that decides").isNull();
    }

    @Test
    void theQuestionNamesTheFindingFirstAndTheStoriesNotDeliveredYet() {
        Story listing = story("S1", "Keep a list of books and show it", StoryState.RUNNING);
        Story adding = story("S2", "Add a book", StoryState.READY);
        adding.setDependsOnStoryIds(List.of(listing.id()));
        Story search = story("S3", "Search the books", StoryState.DRAFT);
        Story done = story("S4", "Sign in", StoryState.DONE);
        Story dropped = story("S5", "Export", StoryState.CANCELLED);

        List<NothingCreatesIt.Candidate> candidates = NothingCreatesIt.candidates(listing,
            List.of(listing, adding, search, done, dropped));

        assertThat(candidates).extracting(one -> one.story().key()).containsExactly("S2", "S3");
        assertThat(candidates.get(0).comesAfter()).isTrue();
        assertThat(candidates.get(1).comesAfter()).isFalse();

        String brief = NothingCreatesIt.brief("S1 \"Keep a list of books and show it\"",
            "a book in the list", candidates);
        assertThat(brief).startsWith("This story cannot be proven on an empty application: it "
            + "shows \"a book in the list\" and nothing on screen lets a person create that. "
            + "Merge it with, or order it after, the story that creates it.");
        assertThat(brief)
            .contains("- S2 \"Add a book\" - comes AFTER this story today")
            .contains("- S3 \"Search the books\"\n")
            .doesNotContain("Sign in").doesNotContain("Export")
            .contains("MERGE").contains("ORDER").contains("answer retry");
    }

    @Test
    void withNoOtherStoryWaitingItSaysSo() {
        Story listing = story("S1", "Keep a list of books and show it", StoryState.RUNNING);
        assertThat(NothingCreatesIt.brief("S1", "a book", NothingCreatesIt.candidates(listing,
            List.of(listing)))).contains("No other story of this project is waiting to be built");
    }

    private static Story story(String key, String title, StoryState state) {
        return new Story(UUID.randomUUID(), PROJECT, key, StoryKind.DELIVERY, title, null, state,
            List.of(), List.of(), null, 0, StoryOrigin.BACKLOG, null, null, null, List.of(),
            null, null, null, null, Instant.now(), Instant.now());
    }

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, title, Set.of("src/main"), Set.of(),
            List.of(), "src/test/java/swarm", null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }
}
