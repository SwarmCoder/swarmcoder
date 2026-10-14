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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.runtime.AgentRuntime.TurnOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 106, findings A and D, on a role's toolbox with no model: which
 * turn is followed by the role's draft check, decided from what its calls were and what came
 * back; and what a role keeps with a tool of its own is answered by {@code known_about} at once.
 */
class ATurnOfRepeatsIsFollowedByTheDraftCheckTest {

    @TempDir
    Path app;
    @TempDir
    Path cache;

    private KnowledgeCurator curator;

    private ExpertTools tools() throws Exception {
        return tools("architect");
    }

    private ExpertTools tools(String role) throws Exception {
        if (curator == null) {
            Files.writeString(app.resolve("notes.txt"), "the shelf lists its titles\n");
            Files.writeString(app.resolve("other.txt"), "the catalog lists its shelves\n");
            curator = new KnowledgeCurator(
                List.of(new KnowledgeCurator.Root("project", app, "local")), null, cache);
        }
        return new ExpertTools(curator, null, null, null, MaterialBudget.BASELINE, 120,
            new ExpertTools.User(role, "Hand in.", 300, null, 0, 3));
    }

    private static TurnOrder.Made made(String tool, boolean heldWhole) {
        return new TurnOrder.Made(tool, "{}", heldWhole);
    }

    @Test
    void aTurnWhoseLookupsWereAllRefusedAsRepeatsOrStillHeldWholeIsFollowedByTheCheck()
            throws Exception {
        ExpertTools tools = tools();
        tools.draftCheck("check_design", "design", () -> true);
        for (int turn = 1; turn <= 3; turn++) {
            tools.onTurn(turn);
            tools.readFile("project/notes.txt");
            assertThat(tools.afterTurn(List.of(made("read_file", false))))
                .as("turn %d: the answer was not held whole, so nothing is ordered", turn)
                .isEmpty();
        }

        tools.onTurn(4);
        String refused = tools.readFile("project/notes.txt");
        tools.readFile("project/other.txt");
        Optional<TurnOrder.Ordered> ordered = tools.afterTurn(
            List.of(made("read_file", false), made("read_file", true)));

        assertThat(refused).as("the fourth time the same call is the existing safety stop")
            .startsWith("error: you have made exactly this call");
        assertThat(ordered).isPresent();
        assertThat(ordered.get().tool()).isEqualTo("check_design");
        assertThat(ordered.get().notice()).contains("check_design")
            .contains("1 you had made too often").contains("1 answered exactly what is still")
            .contains("After it every lookup is open to you again");

        tools.onTurn(5);
        assertThat(tools.readFile("project/other.txt")).as("the rule refuses no lookup: one "
            + "made in or after the ordered turn is answered").contains("catalog");
    }

    @Test
    void aTurnThatBroughtSomethingNewOrDidWorkOfItsOwnOrdersNothing() throws Exception {
        ExpertTools tools = tools();
        tools.draftCheck("check_plan", "plan", () -> true);

        tools.onTurn(1);
        tools.readFile("project/notes.txt");
        tools.readFile("project/other.txt");
        assertThat(tools.afterTurn(List.of(made("read_file", true), made("read_file", false))))
            .as("one of the two lookups returned something the session did not hold").isEmpty();

        tools.onTurn(2);
        tools.readFile("project/notes.txt");
        tools.runOwnTool("check_plan", "12 chars", () -> "NO OBJECTIONS");
        assertThat(tools.afterTurn(List.of(made("read_file", true), made("check_plan", true))))
            .as("a turn that also checked a draft is the role working").isEmpty();

        tools.onTurn(3);
        tools.readFile("project/notes.txt");
        assertThat(tools.afterTurn(List.of(made("read_file", true), made("report_done", false))))
            .as("the calls the runtime saw are not the calls this toolbox ran: no order")
            .isEmpty();

        tools.onTurn(4);
        tools.readFile("project/other.txt");
        assertThat(tools.afterTurn(List.of(made("read_file", true))))
            .as("and the plain case, for the planner").map(TurnOrder.Ordered::tool)
            .contains("check_plan");
    }

    @Test
    void aRoleWithNoDraftCheckOrNoCheckLeftIsOrderedNothing() throws Exception {
        ExpertTools testAuthor = tools();
        testAuthor.onTurn(1);
        testAuthor.readFile("project/notes.txt");
        assertThat(testAuthor.afterTurn(List.of(made("read_file", true)))).isEmpty();

        ExpertTools architect = tools();
        AtomicBoolean left = new AtomicBoolean(false);
        architect.draftCheck("check_design", "design", left::get);
        architect.onTurn(1);
        architect.readFile("project/notes.txt");
        assertThat(architect.afterTurn(List.of(made("read_file", true))))
            .as("no check left: the role is not sent to a tool that would not check").isEmpty();
    }

    @Test
    void whatARoleKeepsWithAToolOfItsOwnIsAnsweredByKnownAboutFromThatMoment() throws Exception {
        UUID run = UUID.randomUUID();
        ExpertTools architect = tools();
        architect.know(KnownFacts.none());
        architect.keptInRun(KnownFacts.keptInRun(run));
        List<DesignFinding> kept = new ArrayList<>();
        architect.keptBy(() -> List.copyOf(kept));

        assertThat(architect.knownBinding()).as("offered on a project that holds nothing yet")
            .isNotNull();
        assertThat(architect.knownAbout("Shelf")).contains("Nothing is recorded yet");

        architect.readFile("project/notes.txt");
        architect.runOwnTool("keep_for_workers", "read_file project/notes.txt", () -> {
            kept.add(new DesignFinding(UUID.randomUUID(), "com.acme.shop.Shelf",
                "read_file project/notes.txt", "A shelf lists its titles.",
                "the shelf lists its titles"));
            return "KEPT";
        });

        assertThat(architect.knownAbout("Shelf")).contains("A shelf lists its titles.")
            .contains("from: read_file project/notes.txt").contains("found by: the architect")
            .contains("no delivered build has proved it");

        // A later session of the same run: the planner's.
        ExpertTools planner = tools("planner");
        planner.know(KnownFacts.none().and(KnownFacts.keptInRun(run).facts()));
        planner.keptInRun(KnownFacts.keptInRun(run));
        assertThat(planner.knownAbout("notes.txt")).contains("A shelf lists its titles.");
        assertThat(KnownFacts.none().and(KnownFacts.keptInRun(run).facts()).index(2_400))
            .as("by title, with what it was copied from, and no line of it")
            .contains("- Shelf (1): from notes.txt").doesNotContain("the shelf lists");

        // Another run's sessions are not told this run's unproved facts.
        assertThat(KnownFacts.keptInRun(UUID.randomUUID()).facts()).isEmpty();
    }
}
