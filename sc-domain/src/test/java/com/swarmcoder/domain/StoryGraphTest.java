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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure of 2026-08-28, expressed as tests: nine stories planned from one document and started
 * together, eight of them each inventing their own version of a domain model the first had not
 * finished writing.
 */
class StoryGraphTest {

    private static Story story(String key, String title, StoryState state, Story... after) {
        Story story = new Story(UUID.randomUUID(), UUID.randomUUID(), key, StoryKind.DELIVERY,
            title, null, state, new ArrayList<>(), new ArrayList<>(), null, 0,
            StoryOrigin.BACKLOG, null, null, "agent", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        List<UUID> dependsOn = new ArrayList<>();
        for (Story predecessor : after) {
            dependsOn.add(predecessor.id());
        }
        story.setDependsOnStoryIds(dependsOn);
        return story;
    }

    @Test
    void aStoryWithNoDependenciesCanStart() {
        Story alone = story("S1", "Store a book record", StoryState.READY);
        assertThat(StoryGraph.isStartable(alone, List.of(alone))).isTrue();
        assertThat(StoryGraph.blockedBy(alone, List.of(alone)).reason()).isNull();
    }

    @Test
    void aStoryDoesNotStartUntilTheOneItBuildsOnIsAccepted() {
        Story first = story("S1", "Store a book record", StoryState.RUNNING);
        Story second = story("S2", "Search the shelf", StoryState.READY, first);
        List<Story> all = List.of(first, second);

        assertThat(StoryGraph.isStartable(second, all)).isFalse();
        // Named, in words, with what is happening to the thing in the way — never a bare code.
        assertThat(StoryGraph.blockedBy(second, all).reason())
            .contains("S1").contains("Store a book record").contains("being built now");

        first.setState(StoryState.DONE);
        assertThat(StoryGraph.isStartable(second, all)).isTrue();
    }

    @Test
    void comingBackForAVerdictIsNotDelivered() {
        // REVIEW means the code is still only on its own branch. Accepting is what puts it where
        // the next story's workers will find it, so REVIEW must not release a dependent.
        Story first = story("S1", "Store a book record", StoryState.REVIEW);
        Story second = story("S2", "Search the shelf", StoryState.READY, first);
        assertThat(StoryGraph.isStartable(second, List.of(first, second))).isFalse();
    }

    @Test
    void wavesGroupWhatCanBeBuiltAtTheSameTime() {
        Story model = story("S1", "Store a book record", StoryState.READY);
        Story search = story("S2", "Search the shelf", StoryState.READY, model);
        Story lend = story("S3", "Lend a book", StoryState.READY, model);
        Story report = story("S4", "Report on lending", StoryState.READY, lend);

        List<List<Story>> waves = StoryGraph.waves(List.of(model, search, lend, report));
        assertThat(waves).hasSize(3);
        assertThat(waves.get(0)).containsExactly(model);
        assertThat(waves.get(1)).containsExactlyInAnyOrder(search, lend);
        assertThat(waves.get(2)).containsExactly(report);
    }

    @Test
    void aCircleIsRejected() {
        Story a = story("S1", "One", StoryState.READY);
        Story b = story("S2", "Two", StoryState.READY, a);
        a.setDependsOnStoryIds(List.of(b.id()));

        StoryGraph.Verdict verdict = StoryGraph.validate(List.of(a, b));
        assertThat(verdict.ok()).isFalse();
        assertThat(String.join(" ", verdict.violations())).contains("circle");
    }

    @Test
    void anEdgeToAStoryThatDoesNotExistIsRejected() {
        Story orphan = story("S1", "One", StoryState.READY);
        orphan.setDependsOnStoryIds(List.of(UUID.randomUUID()));

        StoryGraph.Verdict verdict = StoryGraph.validate(List.of(orphan));
        assertThat(verdict.ok()).isFalse();
        assertThat(String.join(" ", verdict.violations()))
            .contains("does not have");
    }

    @Test
    void waitingForADroppedStoryIsRejectedRatherThanWaitedOutForEver() {
        Story dropped = story("S1", "One", StoryState.CANCELLED);
        Story waiting = story("S2", "Two", StoryState.READY, dropped);

        StoryGraph.Verdict verdict = StoryGraph.validate(List.of(dropped, waiting));
        assertThat(verdict.ok()).isFalse();
        assertThat(String.join(" ", verdict.violations())).contains("dropped");
        // …and it is not held up by the tombstone, which would be a story stuck for ever.
        assertThat(StoryGraph.isStartable(waiting, List.of(dropped, waiting))).isTrue();
    }

    @Test
    void aStoryCannotWaitForItself() {
        Story self = story("S1", "One", StoryState.READY);
        self.setDependsOnStoryIds(List.of(self.id()));

        assertThat(StoryGraph.validate(List.of(self)).ok()).isFalse();
        // And it is still startable, because a story frozen by its own typo is the worse failure.
        assertThat(StoryGraph.isStartable(self, List.of(self))).isTrue();
    }

    @Test
    void anEdgeTheSystemWorkedOutSaysSo() {
        Story model = story("S1", "Store a book record", StoryState.READY);
        Story search = story("S2", "Search the shelf", StoryState.READY);
        search.setDiscoveredDependsOnStoryIds(List.of(model.id()));

        StoryGraph.Blocked blocked = StoryGraph.blockedBy(search, List.of(model, search));
        assertThat(blocked.any()).isTrue();
        assertThat(blocked.reason()).contains("nobody planned this");
    }

    @Test
    void theNineStoryFailureCannotHappenAgain() {
        // The shape of the real incident: one story writing the domain model, eight built on it.
        Story model = story("S1", "Build the book record", StoryState.RUNNING);
        List<Story> all = new ArrayList<>();
        all.add(model);
        for (int i = 2; i <= 9; i++) {
            all.add(story("S" + i, "Feature " + i, StoryState.READY, model));
        }
        long startable = all.stream().filter(s -> StoryGraph.isStartable(s, all)).count();
        assertThat(startable).isEqualTo(1);   // only the one already building
    }
}
