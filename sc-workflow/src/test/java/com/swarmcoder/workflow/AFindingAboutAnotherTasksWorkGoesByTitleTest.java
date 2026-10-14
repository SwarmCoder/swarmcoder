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

import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 114, finding 3 (the selection): a task was sent 8.1K characters of the architect's
 * findings about types other tasks build, on all 86 of its calls. A finding about the whole
 * project whose lookup is about another task's work now goes by its sentence and its lookup;
 * one about the task's own work or what it waits for, and one whose lookup names nothing of
 * the plan, go whole as before. Decided from the recorded source and the plan, no model.
 */
class AFindingAboutAnotherTasksWorkGoesByTitleTest {

    private static Task task(String title, String... writes) {
        return new Task(UUID.randomUUID(), 1, title, "Do " + title + ".",
            new LinkedHashSet<>(List.of(writes)), Set.of(), List.of(), null, null, null, null,
            TaskState.READY);
    }

    private static DesignFinding whole(String source) {
        return new DesignFinding(UUID.randomUUID(), null, source, "How the example does it.",
            "        line one of the code;\n".repeat(30).stripTrailing());
    }

    @Test
    void aFindingWhoseLookupIsAboutAnotherTasksWorkIsGivenByTitle() {
        Task shared = task("shared", "shared/pom.xml", "shared/src/main/java/app/Book.java");
        Task server = task("server", "server/pom.xml", "server/src/main/java/app/Api.java");
        Task client = task("client", "client/pom.xml",
            "client/src/main/java/app/BookScreen.java");
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            List.of(shared, server, client), new ArrayList<>(List.of(
                new TaskEdge(shared.id(), server.id()), new TaskEdge(shared.id(), client.id()))));

        DesignFinding screen = whole("body_of example/client/src/main/java/app/Shelf.java#build");
        DesignFinding screenByName = whole("body_of BookScreen#build");
        DesignFinding book = whole("shape_of example/shared/src/main/java/app/Book.java");
        DesignFinding guide = whole("doc_section guide.md#3");
        DesignDocument design = new DesignDocument();
        design.setFindings(List.of(screen, screenByName, book, guide));

        ArchitectHandover.Given forServer = ArchitectHandover.forTask(server, graph, design,
            ArchitectHandover.DEFAULT_MAX_CHARS);
        assertThat(forServer.byTitle()).isEqualTo(2);
        List<DesignFinding> given = forServer.findings();
        assertThat(given).hasSize(4);
        assertThat(given.stream().filter(DesignFinding::hasSnippet).map(DesignFinding::source))
            .as("what it waits for, and what names nothing of the plan, go whole")
            .containsExactly(book.source(), guide.source());
        assertThat(given.subList(2, 4)).allMatch(f -> !f.hasSnippet())
            .extracting(DesignFinding::source)
            .containsExactlyInAnyOrder(screen.source(), screenByName.source());

        ArchitectHandover.Given forClient = ArchitectHandover.forTask(client, graph, design,
            ArchitectHandover.DEFAULT_MAX_CHARS);
        assertThat(forClient.byTitle()).as("the client's own work goes whole to the client")
            .isZero();
        assertThat(forClient.findings()).allMatch(DesignFinding::hasSnippet);
        assertThat(DesignFinding.sizeOf(forServer.findings()))
            .isLessThan(DesignFinding.sizeOf(forClient.findings()));
    }
}
