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
package com.swarmcoder.console;

import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 80 (live run 102): the story planner is told that every story must be provable in a
 * browser starting from an empty application, and reads what a build found about a story that
 * was not - also on a story dropped for it, which is how its checks come back to be planned.
 */
class TheStoryPlannerPlansStoriesProvableOnAnEmptyApplicationTest {

    @TempDir
    Path storeDir;

    @Test
    void thePlannerIsToldTheRule() {
        assertThat(BacklogPlanning.proposalPrompt("R1"))
            .contains("EVERY STORY MUST BE PROVABLE IN A BROWSER STARTING FROM AN EMPTY "
                + "APPLICATION")
            .contains("Put the displaying and the creating in ONE story")
            .contains("make the display-only story depend on the creating one")
            .contains("COULD NOT BE PROVEN ON AN EMPTY APPLICATION");
    }

    @Test
    void theBacklogThePlannerReadsCarriesWhatABuildFound() throws Exception {
        UUID project = UUID.randomUUID();
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Story listing = story(project, "S1", "Keep a list of books and show it",
                StoryState.CANCELLED);
            listing.setNothingCreates("a book in the\n list");
            store.saveStory(listing);
            store.saveStory(story(project, "S2", "Add a book", StoryState.READY));
            Story delivered = story(project, "S3", "Sign in", StoryState.DONE);
            delivered.setNothingCreates("an account");
            store.saveStory(delivered);

            String brief = BacklogPlanning.brief(store, project);

            assertThat(brief).contains("Keep a list of books and show it\n"
                + "    COULD NOT BE PROVEN ON AN EMPTY APPLICATION: it shows \"a book in the "
                + "list\" and nothing on screen lets a person create that\n");
            assertThat(brief).as("only on the story it was found on, and not a delivered one")
                .containsOnlyOnce("COULD NOT BE PROVEN ON AN EMPTY APPLICATION");
            assertThat(ArtifactStore.copyOf(listing).nothingCreates())
                .as("a published copy carries it").isEqualTo(listing.nothingCreates());
        }
    }

    private static Story story(UUID project, String key, String title, StoryState state) {
        return new Story(UUID.randomUUID(), project, key, StoryKind.DELIVERY, title, null, state,
            List.of(), List.of(), null, 0, StoryOrigin.BACKLOG, null, null, null, List.of(),
            null, null, null, null, Instant.now(), Instant.now());
    }
}
