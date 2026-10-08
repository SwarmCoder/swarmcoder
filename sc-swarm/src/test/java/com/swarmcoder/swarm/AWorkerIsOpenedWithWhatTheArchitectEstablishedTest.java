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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect's findings reach the worker word for word (owner's decision, 2026-10-08; section
 * 73): the lines its lookup returned are in the worker's opening as they were found, with the
 * lookup they came from, under the task - and the task's files are said to be a reservation.
 */
class AWorkerIsOpenedWithWhatTheArchitectEstablishedTest {

    private static final String SNIPPET = "@Service\npublic class LedgerStore {\n"
        + "    private final LedgerSession session = LedgerSession.open(\"orders\");\n}";

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Order service",
            "Delivers OrderService and its check R1:C1; the screen is a later task.",
            Set.of("server/src/main/java/com/shop/OrderService.java"), Set.of(), List.of(),
            "server/src/test/java/swarm", null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    @Test
    void theFindingsAreInTheOpeningAsTheArchitectFoundThem() {
        Task task = task();
        DesignFinding code = new DesignFinding(UUID.randomUUID(), "OrderService",
            "body_of com.ledgerworks.LedgerStore", "A service is found by @Service and opens "
            + "its session by name.", SNIPPET);
        DesignFinding sentence = new DesignFinding(UUID.randomUUID(), null,
            "docs_for persistence", "Every store is opened once and kept.", null);
        task.setArchitectFindings(List.of(code, sentence));

        String opening = SwarmDispatcher.buildBundle(task, null, null, null, List.of())
            .sharedText();

        assertThat(opening).contains("WHAT THE ARCHITECT ESTABLISHED FOR THIS TASK")
            .contains("- [OrderService] A service is found by @Service and opens its session "
                + "by name. (from body_of com.ledgerworks.LedgerStore)")
            .contains("- [the whole project] Every store is opened once and kept. "
                + "(from docs_for persistence)");
        for (String line : SNIPPET.split("\n")) {
            assertThat(opening).as("each line of the lookup, unchanged").contains("    " + line);
        }
        assertThat(opening.indexOf("Delivers OrderService"))
            .as("under what the task delivers")
            .isLessThan(opening.indexOf("WHAT THE ARCHITECT ESTABLISHED"));
    }

    @Test
    void aTaskTheArchitectKeptNothingForIsOpenedAsBefore() {
        String opening = SwarmDispatcher.buildBundle(task(), null, null, null, List.of())
            .sharedText();

        assertThat(opening).doesNotContain("WHAT THE ARCHITECT ESTABLISHED");
    }

    @Test
    void theTasksFilesAreSaidToBeAReservationThatCanGrow() {
        String opening = SwarmDispatcher.buildBundle(task(), null, null, null, List.of())
            .sharedText();

        assertThat(opening)
            .contains("These paths are reserved for this task: "
                + "[server/src/main/java/com/shop/OrderService.java]")
            .contains("a file no other task holds becomes this task's and is recorded")
            .contains("a file another task holds is refused")
            .doesNotContain("You may ONLY modify");
    }
}
