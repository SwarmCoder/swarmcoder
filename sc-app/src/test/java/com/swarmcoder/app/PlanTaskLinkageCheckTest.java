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
package com.swarmcoder.app;

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
import com.swarmcoder.workflow.StoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four plan shapes {@link PlanTaskLinkageCheck} must tell apart. Written after link 7 of
 * {@code EndToEndLoopTest} (L_TASKS) rejected a real, legitimate plan on 2026-09-02 for requiring
 * every task to carry a check — which the product's own enabler-task shape never does.
 */
class PlanTaskLinkageCheckTest {

    /** R1 "add a book" with two checks, R2 "list books" with one — same shape as the demo run. */
    private final StoryScope bookshelf = bookshelfStory();

    @Test
    void everyTaskCheckedPasses() {
        Task service = task("Implement server-side BookLibraryService", checks("R1:C1"));
        Task ui = task("Build add-book form and book list view", checks("R1:C2", "R2:C1"));

        PlanTaskLinkageCheck.Verdict verdict =
            PlanTaskLinkageCheck.evaluate(graph(List.of(service, ui), List.of()), bookshelf);

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void anEnablerSomethingDependsOnPasses() {
        Task model = task("Create shared Book model and BookLibraryService contract", Set.of());
        Task service = task("Implement server-side BookLibraryService", checks("R1:C1"));
        Task ui = task("Build add-book form and book list view", checks("R1:C2", "R2:C1"));

        TaskGraph graph = graph(List.of(model, service, ui),
            List.of(new TaskEdge(model.id(), service.id()), new TaskEdge(model.id(), ui.id())));

        PlanTaskLinkageCheck.Verdict verdict = PlanTaskLinkageCheck.evaluate(graph, bookshelf);

        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void anEnablerNothingDependsOnFailsNamingTheTask() {
        Task model = task("Create shared Book model and BookLibraryService contract", Set.of());
        Task service = task("Implement server-side BookLibraryService",
            checks("R1:C1", "R1:C2", "R2:C1"));
        // No edge from model to anything: nothing in this plan is built on top of it.

        TaskGraph graph = graph(List.of(model, service), List.of());

        PlanTaskLinkageCheck.Verdict verdict = PlanTaskLinkageCheck.evaluate(graph, bookshelf);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.detail())
            .contains("Create shared Book model and BookLibraryService contract")
            .contains("nothing that depends on it claims one either");
    }

    @Test
    void aCheckNoTaskReferencesFailsNamingTheCheck() {
        Task service = task("Implement server-side BookLibraryService", checks("R1:C1"));
        Task ui = task("Build add-book form and book list view", checks("R1:C2"));
        // R2:C1 is never claimed by anyone.

        TaskGraph graph = graph(List.of(service, ui), List.of());

        PlanTaskLinkageCheck.Verdict verdict = PlanTaskLinkageCheck.evaluate(graph, bookshelf);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.detail())
            .contains("no task delivers criterion")
            .contains("R2:C1");
    }

    // ------------------------------------------------------------------------------ fixture

    private Set<UUID> checks(String... refs) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (String ref : refs) {
            UUID id = bookshelf.idForRef(ref);
            assertThat(id).as("fixture has %s", ref).isNotNull();
            ids.add(id);
        }
        return ids;
    }

    private static Task task(String title, Set<UUID> criterionIds) {
        Task t = new Task(UUID.randomUUID(), 1, title, "do " + title, Set.of("src/main/java"),
            Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        t.setCriterionIds(criterionIds);
        return t;
    }

    private static TaskGraph graph(List<Task> tasks, List<TaskEdge> edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, tasks, edges);
    }

    /** The bookshelf demo's story: R1 "add a book" with two checks, R2 "list books" with one. */
    private static StoryScope bookshelfStory() {
        BrdRequirement r1 = requirement("R1", "Add a book",
            "A reader can add a book to the shelf",
            "a book with a title and author is stored",
            "adding a book with no title is refused");
        BrdRequirement r2 = requirement("R2", "See the shelf",
            "A reader can see every book on the shelf",
            "every stored book appears in the list");

        List<UUID> sliced = new ArrayList<>();
        r1.criteria().forEach(c -> sliced.add(c.id()));
        r2.criteria().forEach(c -> sliced.add(c.id()));

        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(r1, r2)), new ArrayList<>(), Instant.now(), Instant.now());
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S1", StoryKind.DELIVERY,
            "A reader can add books and see the shelf", null, StoryState.READY,
            new ArrayList<>(), sliced, null, 0, StoryOrigin.BACKLOG, null, null, "human",
            new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        return StoryScope.resolve(brd, story);
    }

    private static BrdRequirement requirement(String handle, String title, String statement,
                                              String... criteriaText) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), handle, title, statement,
            Priority.HIGH, RequirementStatus.ACTIVE, null);
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (String text : criteriaText) {
            AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(), text,
                "swarm.accept." + handle + "C" + (criteria.size() + 1) + "AcceptTest#proves");
            criterion.setStatus(CriterionStatus.ACCEPTED);
            criteria.add(criterion);
        }
        requirement.setCriteria(criteria);
        return requirement;
    }
}
