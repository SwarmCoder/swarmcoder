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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan invariant behind "a task is verified against the tests it claims": checks that name
 * the same test class must be claimed by the same task, because a test class is one file and a
 * file is placed into one task's worktree whole.
 *
 * <p>Without it the two checks of {@code BookshelfAcceptTest} split across two tasks would put the
 * whole class into both worktrees, and the task that only stores a book would be failed by the
 * test that lists them — or, since the test author writes per task, the second author's file
 * would silently replace the first's.
 */
class OneTestClassOneTaskTest {

    private final TaskGraphValidator validator = new TaskGraphValidator();

    private final AcceptanceCriterion stores = criterion("a book with a title and author is stored",
        "swarm.accept.BookshelfAcceptTest#storesABook");
    private final AcceptanceCriterion lists = criterion("every stored book appears in the list",
        "swarm.accept.BookshelfAcceptTest#listsEveryBook");
    private final AcceptanceCriterion refuses = criterion("adding a book with no title is refused",
        "swarm.accept.ValidationAcceptTest#refusesAnUntitledBook");
    private final StoryScope scope = scope(stores, lists, refuses);

    @Test
    void twoChecksOfOneClassOnTwoTasksIsRejectedAndNamesBoth() {
        Task store = task("Store a book", Set.of("src/main/java/store"), stores);
        Task list = task("List the shelf", Set.of("src/main/java/list"), lists, refuses);

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(store, list),
                List.of(new TaskEdge(store.id(), list.id()))), scope);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).hasSize(1);
        assertThat(verdict.violations().get(0))
            .contains("BookshelfAcceptTest")
            .contains("R1:C1").contains("R1:C2")
            .contains("Store a book").contains("List the shelf")
            .contains("one file");
    }

    @Test
    void theSameChecksOnOneTaskAreAccepted() {
        Task shelf = task("Store and list", Set.of("src/main/java/shelf"), stores, lists);
        Task validation = task("Refuse untitled", Set.of("src/main/java/validation"), refuses);

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(shelf, validation),
                List.of(new TaskEdge(shelf.id(), validation.id()))), scope);

        assertThat(verdict.violations()).isEmpty();
    }

    @Test
    void aClassClaimedTwiceByTheSameTwoTasksIsNotASplit() {
        // Both tasks claim BOTH checks of the class: whichever file wins, each task must pass all
        // of it, which is consistent — the file serves the same claim on both sides.
        Task first = task("First go", Set.of("src/main/java/a"), stores, lists);
        Task second = task("Second go", Set.of("src/main/java/b"), stores, lists, refuses);

        TaskGraphValidator.Verdict verdict = validator.validate(
            new TaskGraph(UUID.randomUUID(), 1, null, List.of(first, second),
                List.of(new TaskEdge(first.id(), second.id()))), scope);

        assertThat(verdict.violations()).isEmpty();
    }

    // --- fixtures --------------------------------------------------------------------------------

    private static AcceptanceCriterion criterion(String text, String testRef) {
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text, testRef);
        criterion.setStatus(CriterionStatus.ACCEPTED);
        return criterion;
    }

    private static StoryScope scope(AcceptanceCriterion... criteria) {
        BrdRequirement r1 = new BrdRequirement(UUID.randomUUID(), "R1", "Bookshelf",
            "A reader keeps books on a shelf", Priority.HIGH, RequirementStatus.ACTIVE, null);
        r1.setCriteria(new ArrayList<>(List.of(criteria)));
        List<UUID> sliced = new ArrayList<>();
        for (AcceptanceCriterion criterion : criteria) {
            sliced.add(criterion.id());
        }
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(r1)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "The shelf", null, StoryState.READY, new ArrayList<>(), sliced, null, 0,
            StoryOrigin.BACKLOG, null, null, "human", new ArrayList<>(), null, null, null, null,
            Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }

    private static Task task(String title, Set<String> writeSet, AcceptanceCriterion... claims) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do " + title, writeSet, Set.of(),
            List.of(), "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        Set<UUID> ids = new java.util.HashSet<>();
        for (AcceptanceCriterion claim : claims) {
            ids.add(claim.id());
        }
        task.setCriterionIds(ids);
        return task;
    }
}
