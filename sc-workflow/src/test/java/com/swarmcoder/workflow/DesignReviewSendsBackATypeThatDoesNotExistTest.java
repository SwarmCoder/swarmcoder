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
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.CriterionStatus;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness runs 44/45, 2026-09-27, through the real workflow: the architect's design gives the page
 * contract a {@code com.zeroz4j.ui.ListView<Book>} member, and the reference root (a miniature of
 * ZeroZ Stack's checkout) has only {@code com.zeroz4j.ui.component.KeyedList}. DESIGN_REVIEW now
 * sends that back to the architect naming the type and the real one nearest to it, through the
 * same revision loop and cap as a rule conflict, and parks when the cap is spent. No model is
 * called except {@link ScriptedLlm}.
 */
class DesignReviewSendsBackATypeThatDoesNotExistTest {

    @TempDir
    Path storeDir;

    @TempDir
    Path reference;

    @TempDir
    Path primerCache;

    private static final String REQUIREMENT = "The user can add, edit and remove books";

    private static String design(String listType) {
        return """
            {"requirements":[{"text":"%s","priority":"HIGH"}],
             "decisions":[{"decision":"A browser page calls BookListService","rationale":"rules"}],
             "contracts":[
               {"name":"BookListService","description":"the service the page calls","signature":"",
                "type":"com.swarmcoder.demo.bookshelf.BookListService",
                "members":["java.util.List<Book> getBooks()","void addBook(Book book)"]},
               {"name":"BookListPage","description":"the page","signature":"",
                "type":"com.swarmcoder.demo.bookshelf.client.BookListPage",
                "members":["public %s<Book> bookList;",
                  "public void bind(com.swarmcoder.demo.bookshelf.BookListService service);"]}],
             "risks":[],"missingRequirements":[]}
            """.formatted(REQUIREMENT, listType);
    }

    @Test
    void theArchitectIsToldListViewDoesNotExistAndThatKeyedListDoesAndItsFixIsKept() throws Exception {
        List<String> revisions = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    revisions.add(conversation);
                    return design("com.zeroz4j.ui.component.KeyedList");
                }
                if (conversation.contains("requirements are FIXED")) {
                    return design("com.zeroz4j.ui.ListView");
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\": true, \"objections\": []}";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            WorkflowEngine engine = engine(llm, store);
            Story story = seedStory(store);
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                story.projectId(), story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Add, edit, and remove books")));

            Run parked = awaitParked(store, runId);

            assertThat(revisions).as("one revision was enough").hasSize(1);
            assertThat(revisions.get(0))
                .contains("names com.zeroz4j.ui.ListView")
                .contains("has no ListView in com.zeroz4j.ui")
                .contains("com.zeroz4j.ui.component.KeyedList");
            assertThat(parked.state())
                .as("past DESIGN_REVIEW: this scripted planner answers nothing, so PLAN parks")
                .isEqualTo(RunState.PLAN);
            DesignDocument stored = store.root().designs.get(parked.designId());
            ApiContract page = stored.contracts().stream()
                .filter(c -> c.typeName().endsWith("BookListPage")).findFirst().orElseThrow();
            assertThat(page.members()).contains("public com.zeroz4j.ui.component.KeyedList<Book> bookList;");
        }
    }

    @Test
    void aDesignThatKeepsNamingListViewParksAtDesignReviewSayingWhy() throws Exception {
        List<String> revisions = new CopyOnWriteArrayList<>();
        UUID runId = UUID.randomUUID();
        try (ScriptedLlm llm = new ScriptedLlm(conversation -> {
                if (conversation.contains("revising a design against review objections")) {
                    revisions.add(conversation);
                    return design("com.zeroz4j.ui.ListView");
                }
                if (conversation.contains("requirements are FIXED")) {
                    return design("com.zeroz4j.ui.ListView");
                }
                if (conversation.contains("Critique the design against this rubric")) {
                    return "{\"approved\": true, \"objections\": []}";
                }
                return "I decline to produce JSON.";
            });
             ArtifactStore store = new ArtifactStore(storeDir)) {
            WorkflowEngine engine = engine(llm, store);
            Story story = seedStory(store);
            engine.advance(new Run(runId, WorkflowKind.GREENFIELD, RunState.INTAKE,
                story.projectId(), story.id(), null, null, null, Instant.now(),
                new RunReport(runId, "Add, edit, and remove books")));

            Run parked = awaitParked(store, runId);

            assertThat(revisions).hasSize(GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS);
            assertThat(parked.state()).isEqualTo(RunState.DESIGN_REVIEW);
            assertThat(parked.parkReason())
                .contains("The design's contracts still name types that do not exist, or that no task can create, after "
                    + GreenfieldWorkflow.MAX_DESIGN_RULE_REVISIONS + " revision(s)")
                .contains("com.zeroz4j.ui.ListView")
                .contains("Fix the contract in the design yourself")
                .doesNotContain("conflicts with the project's stated rules");
        }
    }

    private WorkflowEngine engine(ScriptedLlm llm, ArtifactStore store) throws IOException {
        Path ui = reference.resolve("zerozstack-ui-components/src/main/java/com/zeroz4j/ui/component");
        Files.createDirectories(ui);
        Files.writeString(ui.resolve("KeyedList.java"), """
            package com.zeroz4j.ui.component;

            /** Binds a list signal to a container's children. The standard dynamic list. */
            public final class KeyedList<T> {}
            """);
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(reference), null, null, primerCache);
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        CloudGate gate = new CloudGate(1_000_000, null);
        AgentRuntime unusedRuntime = spec -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new WorkflowEngine(unusedRuntime,
            run -> {
                throw new AssertionError("a swarm was dispatched — this test never expects EXECUTING");
            },
            client, store, gate, null, CloudRoles.allOn(client, gate), null, librarian);
    }

    private static Story seedStory(ArtifactStore store) {
        BrdRequirement requirement = new BrdRequirement(UUID.randomUUID(), "R2",
            "Add, edit, and remove books", REQUIREMENT, Priority.HIGH, RequirementStatus.ACTIVE,
            null);
        AcceptanceCriterion criterion = new AcceptanceCriterion(UUID.randomUUID(),
            "A new book can be added through the UI.", "swarm.accept.BookListTest#addsNewBook");
        criterion.setStatus(CriterionStatus.ACCEPTED);
        requirement.setCriteria(new ArrayList<>(List.of(criterion)));
        Brd brd = new Brd(UUID.randomUUID(), UUID.randomUUID(), 1, "Bookshelf",
            new ArrayList<>(List.of(requirement)), new ArrayList<>(), Instant.now(), Instant.now());
        store.saveBrd(brd);
        Story story = new Story(UUID.randomUUID(), brd.projectId(), "S2", StoryKind.DELIVERY,
            "Add, edit, and remove books", null, StoryState.READY, new ArrayList<>(),
            new ArrayList<>(List.of(criterion.id())), null, 0, StoryOrigin.BACKLOG, null, null,
            "human", new ArrayList<>(), null, null, null, null, Instant.now(), Instant.now());
        store.saveStory(story);
        return story;
    }

    private static Run awaitParked(ArtifactStore store, UUID runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = store.root().runs.get(runId);
            if (run != null && run.parkedAt() != null) {
                return run;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " never parked");
    }
}
