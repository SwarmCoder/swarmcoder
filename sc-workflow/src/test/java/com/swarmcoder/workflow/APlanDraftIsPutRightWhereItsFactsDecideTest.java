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

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live run 103 (section 76): a planner wrote thirteen plan drafts, about 93,000 output tokens,
 * and ended with two tasks. Of the 63 objections its drafts got, one was a decision. The rest
 * were edges written the wrong way round, nested types no task was said to deliver, and a new
 * interface held to be unreachable although a new class of the same plan implemented it. What
 * the plan's own facts decide is now put right by the checks and told as a note.
 */
class APlanDraftIsPutRightWhereItsFactsDecideTest {

    private static final BuildLayout.Layout LAYOUT = new BuildLayout.Layout("maven",
        List.of("shared/src/main/java", "server/src/main/java", "app/src/main/java"),
        List.of("shared", "server", "app"), List.of(), "");

    private static final ApiContract ITEM = contract("com.shop.model.Item", "public String name;");
    private static final ApiContract ITEMS = contract("com.shop.api.Items",
        "List<Item> all();", "Item add(Item item);");

    @TempDir
    Path repo;

    // --- the order -----------------------------------------------------------------------------

    @Test
    void anEdgeWrittenAgainstAContractWithNoFactOfItsOwnIsTurnedRound() {
        Task model = task("model", Set.of("shared/src/main/java/com/shop/model/Item.java"), ITEM);
        Task api = task("api", Set.of("shared/src/main/java/com/shop/api/Items.java"), ITEMS);
        // the planner's edge: from the task that waits to the task it waits for
        TaskGraph plan = graph(List.of(model, api), new TaskEdge(api.id(), model.id()));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(plan, null);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.turned()).hasSize(1);
        assertThat(outcome.turned().get(0)).contains("'model' now finishes before 'api'")
            .contains("Item").contains("turned round");
        assertThat(outcome.corrections()).isEqualTo(outcome.turned());
        assertThat(plan.dependencies()).hasSize(1);
        assertThat(plan.dependencies().get(0).from()).isEqualTo(model.id());
        assertThat(plan.dependencies().get(0).to()).isEqualTo(api.id());
    }

    @Test
    void aReadSetThatNamesAFileToComeTurnsTheEdgeToo() {
        Task model = task("model", Set.of("shared/src/main/java/com/shop/model/Item.java"), ITEM);
        Task screen = new Task(UUID.randomUUID(), 1, "screen", "Show the items.",
            Set.of("app/src/main/java/com/shop/app/ItemsView.java"),
            Set.of("shared/src/main/java/com/shop/model/Item.java"), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        TaskGraph plan = graph(List.of(model, screen), new TaskEdge(screen.id(), model.id()));

        TypeDependencyOrder.Outcome outcome = TypeDependencyOrder.apply(plan, null);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.turned()).hasSize(1);
        assertThat(plan.dependencies().get(0).from()).isEqualTo(model.id());
    }

    @Test
    void aFactEachWayOrAnOrderThroughAThirdTaskStillGoesBackToThePlanner() {
        ApiContract left = contract("com.shop.a.Left", "Right other();");
        ApiContract right = contract("com.shop.b.Right", "Left other();");
        Task a = task("a", Set.of("shared/src/main/java/com/shop/a/Left.java"), left);
        Task b = task("b", Set.of("shared/src/main/java/com/shop/b/Right.java"), right);
        TaskGraph eachWay = graph(List.of(a, b), new TaskEdge(a.id(), b.id()));

        TypeDependencyOrder.Outcome both = TypeDependencyOrder.apply(eachWay, null);

        assertThat(both.turned()).isEmpty();
        assertThat(both.violations()).hasSize(1);

        Task model = task("model", Set.of("shared/src/main/java/com/shop/model/Item.java"), ITEM);
        Task api = task("api", Set.of("shared/src/main/java/com/shop/api/Items.java"), ITEMS);
        Task between = task("between", Set.of("server/src/main/resources/app.properties"));
        TaskGraph through = graph(List.of(model, api, between),
            new TaskEdge(api.id(), between.id()), new TaskEdge(between.id(), model.id()));

        TypeDependencyOrder.Outcome indirect = TypeDependencyOrder.apply(through, null);

        assertThat(indirect.turned()).as("which of two edges is wrong is the planner's call")
            .isEmpty();
        assertThat(indirect.violations()).hasSize(1);
        assertThat(through.dependencies()).hasSize(2);
    }

    // --- nested types --------------------------------------------------------------------------

    @Test
    void aNestedTypeGoesToTheTaskAndTheFileOfTheTypeItIsDeclaredIn() {
        ApiContract commands = contract("com.shop.server.Commands", "public Commands();");
        ApiContract create = contract("com.shop.server.Commands.Create",
            "public Item run(Item item);");
        DesignDocument design = design(ITEM, commands, create);
        Task model = task("model", Set.of(), ITEM);
        Task server = task("server",
            Set.of("server/src/main/java/com/shop/server/Wiring.java"), commands);
        TaskGraph plan = graph(List.of(model, server));

        ComputedReservation.apply(plan, LAYOUT, repo);
        TaskGraphValidator.Verdict verdict =
            new TaskGraphValidator().validate(plan, null, LAYOUT, design, repo);

        assertThat(verdict.violations()).noneMatch(v -> v.contains("no task delivers"));
        assertThat(server.deliveredContracts()).extracting(ApiContract::typeName)
            .containsExactly("com.shop.server.Commands", "com.shop.server.Commands.Create");
        assertThat(verdict.corrections())
            .anyMatch(line -> line.contains("'server' also delivers com.shop.server.Commands.Create"))
            .anyMatch(line -> line.contains("'server' now depends on 'model'"));

        // and its file is the outer type's, when the plan is computed with the contract on it
        ComputedReservation.apply(plan, LAYOUT, repo);
        assertThat(server.writeSet()).noneMatch(path -> path.contains("Commands/Create"));
        assertThat(server.computedReservation())
            .containsExactly("server/src/main/java/com/shop/server/Commands.java");
        assertThat(server.writeSet()).hasSize(2);
    }

    // --- what the plan adds is reached through what else it adds ------------------------------

    @Test
    void aNewFileAnotherConnectedNewFileUsesIsNotCalledUnreachable() throws Exception {
        write("app/src/main/java/com/desk/Desk.java", """
            package com.desk;
            public class Desk {
                public static void main(String[] args) { System.out.println(new com.shared.Note()); }
            }
            """);
        write("shared/src/main/java/com/shared/Note.java", """
            package com.shared;
            public class Note {
            }
            """);
        write("server/src/main/java/com/back/Server.java", """
            package com.back;
            public class Server {
                public static void main(String[] args) { System.out.println(new Util().text()); }
            }
            """);
        write("server/src/main/java/com/back/Util.java", """
            package com.back;
            public class Util {
                public String text() { return "up"; }
            }
            """);
        ReachableCode.Graph start = ReachableCode.of(repo);
        // The server does not use the shared module yet; a new class of the plan will.
        String portFile = "shared/src/main/java/com/shared/Port.java";
        String implFile = "server/src/main/java/com/back/PortImpl.java";
        Task api = task("api", Set.of(portFile), contract("com.shared.Port", "String name();"));
        ApiContract impl = new ApiContract(UUID.randomUUID(), "PortImpl", "the implementation",
            "public class PortImpl implements Port", "com.back.PortImpl",
            List.of("public String name();"));
        Task server = task("server", Set.of(implFile, "server/src/main/java/com/back/Util.java"),
            impl);
        Task serverSayingNothing = task("server",
            Set.of(implFile, "server/src/main/java/com/back/Util.java"));

        // Nothing of the plan is known to use the interface: as before, an objection.
        assertThat(PlanConnectsWhatItAdds.objection(graph(List.of(api, serverSayingNothing)),
            start, repo)).contains(portFile).doesNotContain(implFile);
        // The new server class implements it and is connected from an existing file: reached.
        assertThat(PlanConnectsWhatItAdds.objection(graph(List.of(api, server)), start, repo))
            .isNull();

        // Two new files that only use each other are still nowhere.
        Task loose = task("loose", Set.of("shared/src/main/java/com/shared/Loose.java"),
            contract("com.shared.Loose", "Port port();"));
        Task apiBack = task("api", Set.of(portFile),
            contract("com.shared.Port", "Loose loose();"));
        assertThat(PlanConnectsWhatItAdds.objection(graph(List.of(apiBack, loose)), start, repo))
            .contains(portFile).contains("Loose.java");
    }

    // --- told as a note ------------------------------------------------------------------------

    @Test
    void whatWasPutRightIsANoteAndADraftWithNotesAloneIsClean() {
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null,
            repo.resolve("cache"));
        ExpertTools session = new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("planner", "hand in", 0, null, 0, 0));
        List<String> objections = new ArrayList<>();
        DraftTools tools = new DraftTools(session, "plan", 3, json -> new DraftTools.Checked(true,
            List.copyOf(objections), List.of("'model' now finishes before 'api'")));

        String clean = tools.checkPlan("{\"tasks\":[]}");

        assertThat(clean).startsWith("NO OBJECTIONS").contains("NOTES")
            .contains("nothing here needs an answer")
            .contains("- 'model' now finishes before 'api'");
        assertThat(tools.submission()).isEqualTo("{\"tasks\":[]}");

        objections.add("task graph has no tasks");
        String sentBack = tools.checkPlan("{\"tasks\":[]}");

        assertThat(sentBack).startsWith("1 OBJECTION(S)")
            .contains("- task graph has no tasks").contains("NOTES")
            .contains("- 'model' now finishes before 'api'");
    }

    @Test
    void aTaskWithTypesInTwoPartsOfTheBuildIsProposedAsTwoAndNeverRefused() {
        Task wide = task("everything", Set.of(), ITEM,
            contract("com.shop.server.Store", "public Store();"));
        Task narrow = task("api", Set.of(), ITEMS);
        wide.setComputedReservation(List.of("shared/src/main/java/com/shop/model/Item.java",
            "server/src/main/java/com/shop/server/Store.java"));
        narrow.setComputedReservation(List.of("shared/src/main/java/com/shop/api/Items.java"));

        List<String> proposals = PlanSizeGuide.proposals(graph(List.of(wide, narrow)), LAYOUT);

        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0)).startsWith("'everything' delivers types in 2 parts")
            .contains("shared/src/main/java: Item").contains("server/src/main/java: Store")
            .contains("a proposal, not an objection");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ApiContract contract(String type, String... members) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return new ApiContract(UUID.randomUUID(), simple, simple + " contract", "", type,
            List.of(members));
    }

    private static DesignDocument design(ApiContract... contracts) {
        return new DesignDocument(UUID.randomUUID(), 1, "a story", List.of(), List.of(),
            List.of(contracts), List.of(), null, Instant.now());
    }

    private static Task task(String title, Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, "Deliver it.", writeSet, Set.of(),
            List.of(), null, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()),
            TaskState.PENDING);
        task.setDeliveredContracts(new ArrayList<>(List.of(delivers)));
        return task;
    }

    private static TaskGraph graph(List<Task> tasks, TaskEdge... edges) {
        return new TaskGraph(UUID.randomUUID(), 1, null, new ArrayList<>(tasks),
            new ArrayList<>(List.of(edges)));
    }
}
