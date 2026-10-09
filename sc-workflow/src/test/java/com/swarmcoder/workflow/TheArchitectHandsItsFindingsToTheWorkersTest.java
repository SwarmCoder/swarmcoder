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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect hands what it established to the workers (owner's decision, 2026-10-08; section
 * 73). It keeps part of a lookup as it makes it - the lines are copied by the tool, never typed
 * by the model; the findings are in its design; each task is given, with no model, the findings
 * about the contracts it delivers and the project's, whole or not at all; and the test author of
 * a task is given the same.
 *
 * <p>The model is scripted; the agent runtime, the lookups and the tool are the real ones.
 */
class TheArchitectHandsItsFindingsToTheWorkersTest {

    @TempDir
    Path world;

    // --- the architect keeps a finding as it works ---------------------------------------------

    @Test
    void theArchitectKeepsLinesOfALookupAndTheyAreInItsDesignWordForWord() throws Exception {
        Path app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"), """
            package com.acme.shop;

            import java.util.List;

            public class Catalog {
                public List<String> titles() { return List.of(); }
                public void add(String title) { }
            }
            """);
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), app, null, world.resolve("cache"));
        String lookup = "project/src/main/java/com/acme/shop/Catalog.java";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> designJson(),
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file", Map.of("path", lookup));
                    case 2 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "Rating", "lookup", "read_file " + lookup, "lines", "",
                        "note", "The catalog is a plain class with a public method per use."));
                    case 3 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "", "lookup", "body_of com.acme.Nowhere", "lines", "none",
                        "note", "Never looked up."));
                    case 4 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "", "lookup", "read_file " + lookup, "lines", "none",
                        "note", "Everything lives in com.acme.shop."));
                    case 5 -> ScriptedAgentLlm.Turn.call("check_design", Map.of("design", designJson()));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
                new KoogAgentRuntime(), null, null));

            DesignDocument design = architect.design("Let a customer rate a title");

            assertThat(llm.sessionRequests.get(0))
                .as("told to keep what the workers will need, and offered the tool")
                .contains("KEEP WHAT THE WORKERS WILL NEED").contains("keep_for_workers");
            assertThat(llm.sessionRequests.get(2))
                .contains("KEPT for the tasks that build or change Rating")
                .contains("read_file " + lookup).contains("1 kept so far");
            assertThat(llm.sessionRequests.get(3))
                .as("a finding must name a lookup that was made")
                .contains("no lookup of this session matches 'body_of com.acme.Nowhere'")
                .contains("Your latest: read_file " + lookup);
            assertThat(llm.sessionRequests.get(4)).contains("KEPT for every task")
                .contains("the sentence alone");

            assertThat(design).isNotNull();
            assertThat(design.findings()).hasSize(2);
            DesignFinding code = design.findings().get(0);
            assertThat(code.about()).isEqualTo("Rating");
            assertThat(code.source()).isEqualTo("read_file " + lookup);
            assertThat(code.note()).startsWith("The catalog is a plain class");
            assertThat(code.snippet())
                .as("the lines are the lookup's own, not the model's")
                .contains("public List<String> titles() { return List.of(); }")
                .contains("public void add(String title) { }");
            for (String line : code.snippet().split("\n")) {
                assertThat(llm.sessionRequests.get(1)).contains(line.strip());
            }
            DesignFinding sentence = design.findings().get(1);
            assertThat(sentence.wholeProject()).isTrue();
            assertThat(sentence.hasSnippet()).isFalse();

            assertThat(ArchitectClient.designSummary(design))
                .as("the planner and the reviewer read what was established, without the code")
                .contains("FACT [Rating] The catalog is a plain class with a public method per "
                    + "use. (from read_file " + lookup + ")")
                .contains("FACT [the whole project] Everything lives in com.acme.shop.")
                .doesNotContain("return List.of()");
        }
    }

    @Test
    void aRevisionKeepsEveryFindingTheDesignHadAndAddsTheNewOnes() {
        DesignFinding first = finding("Rating", "body_of A", "one", "a();");
        DesignFinding again = new DesignFinding(UUID.randomUUID(), "Rating", "body_of A", "one",
            "a();");
        DesignFinding added = finding(null, "docs_for stores", "two", null);

        assertThat(ArchitectClient.mergedFindings(List.of(first), List.of(again, added)))
            .containsExactly(first, added);
        assertThat(ArchitectClient.mergedFindings(null, null)).isEmpty();
    }

    // --- which task is given what ---------------------------------------------------------------

    private final ApiContract service = contract("OrderService", "com.shop.OrderService",
        "void place(Order order)");
    private final ApiContract order = contract("Order", "com.shop.Order", "String id()");
    private final ApiContract screen = contract("OrderScreen", "com.shop.ui.OrderScreen",
        "void show()");

    private final DesignFinding aboutService = finding("OrderService", "body_of LedgerStore",
        "A service is found by @Service.", "@Service\nclass LedgerStore {}");
    private final DesignFinding aboutOrder = finding("com.shop.Order", "shape_of Entry",
        "A stored type is a record.", "record Entry(String what) {}");
    private final DesignFinding aboutScreen = finding("orderscreen", "body_of MainView#init",
        "A screen is added to the entry page in init().", "add(new LedgerScreen());");
    private final DesignFinding aboutProject = finding(null, "docs_for stores",
        "Every store is opened once.", null);
    private final DesignFinding aboutNothingBuilt = finding("Invoice", "docs_for billing",
        "Invoices are numbered per year.", null);

    private DesignDocument design(DesignFinding... findings) {
        DesignDocument design = new DesignDocument(UUID.randomUUID(), 1, "orders", List.of(),
            List.of(), List.of(service, order, screen), List.of(), null, null);
        design.setFindings(new ArrayList<>(List.of(findings)));
        return design;
    }

    @Test
    void aTaskIsGivenTheFindingsAboutItsContractsThenTheProjectsThenWhatItsContractsUse() {
        Task task = task("Order service", Set.of(), service);

        ArchitectHandover.Given given = ArchitectHandover.forTask(task,
            design(aboutScreen, aboutProject, aboutOrder, aboutService, aboutNothingBuilt),
            ArchitectHandover.DEFAULT_MAX_CHARS);

        assertThat(given.findings())
            .as("its own contract first, then the whole project, then Order - a type a member "
                + "of its contract names; not the screen's, and nothing about what no task of "
                + "its builds")
            .containsExactly(aboutService, aboutProject, aboutOrder);
        assertThat(given.leftOut()).isZero();
        assertThat(given.withoutCode()).isZero();
    }

    @Test
    void aFindingAboutATypeWhoseFileTheTaskReservesIsGivenToo() {
        Task task = task("Wire the screen in",
            Set.of("client/src/main/java/com/shop/ui/OrderScreen.java"));

        assertThat(ArchitectHandover.forTask(task, design(aboutService, aboutScreen),
            ArchitectHandover.DEFAULT_MAX_CHARS).findings()).containsExactly(aboutScreen);
    }

    @Test
    void aTaskThatWaitsForAnotherIsGivenWhatWasEstablishedAboutWhatThatOneDelivers() {
        // Live run 100: the facts about how a screen is written against a service were kept
        // as facts about the service's contract. The task that wrote the screen delivered no
        // contract and was given none of them.
        Task model = task("Order type", Set.of(), order);
        Task api = task("Order service", Set.of(), service);
        Task view = task("The screen that lists orders", Set.of("client/src/main/java/Main.java"));
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(model, api, view)), new ArrayList<>(List.of(
                new TaskEdge(model.id(), api.id()), new TaskEdge(api.id(), view.id()))));
        DesignDocument design = design(aboutOrder, aboutService, aboutProject, aboutNothingBuilt);

        assertThat(ArchitectHandover.forTask(view, design, ArchitectHandover.DEFAULT_MAX_CHARS)
            .findings()).as("taken alone it delivers nothing and reserves no such type")
            .containsExactly(aboutProject);
        assertThat(ArchitectHandover.forTask(view, graph, design,
            ArchitectHandover.DEFAULT_MAX_CHARS).findings())
            .as("the whole project first, then the task it waits for directly, then the one "
                + "that one waits for; nothing about what no task builds")
            .containsExactly(aboutProject, aboutService, aboutOrder);
        assertThat(ArchitectHandover.forTask(model, graph, design,
            ArchitectHandover.DEFAULT_MAX_CHARS).findings())
            .as("a task is not given what the tasks AFTER it are about")
            .containsExactly(aboutOrder, aboutProject);

        view.setArchitectFindings(new ArrayList<>(List.of(aboutProject)));
        model.setArchitectFindings(new ArrayList<>(List.of(aboutOrder, aboutProject)));
        api.setArchitectFindings(new ArrayList<>(List.of(aboutService, aboutProject, aboutOrder)));
        assertThat(ArchitectHandover.stale(graph, design))
            .as("a plan accepted before this rule is seen to be behind it").isTrue();
        ArchitectHandover.attach(graph, design);
        assertThat(view.architectFindings())
            .containsExactly(aboutProject, aboutService, aboutOrder);
        assertThat(ArchitectHandover.stale(graph, design)).isFalse();
    }

    @Test
    void aRangeLongerThanAFindingCarriesIsSentBackAndNeverKeptAsItsFirstLines()
            throws Exception {
        // Live run 100: five of thirteen findings named a file from the line after its
        // header to its end and were kept as the first twenty lines - package and imports.
        Path app = world.resolve("app");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        StringBuilder source = new StringBuilder("package com.acme.shop;\n\n");
        for (int i = 0; i < DraftTools.FINDING_LINES + 5; i++) {
            source.append("import java.util.Thing").append(i).append(";\n");
        }
        source.append("\npublic class Catalog {\n    public void register() { Registry.add(this); }\n}\n");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"), source.toString());
        int registers = (int) source.toString().lines().takeWhile(l -> !l.contains("register()"))
            .count() + 1;
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), app, null, world.resolve("cache"));
        String lookup = "project/src/main/java/com/acme/shop/Catalog.java";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> designJson(),
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file", Map.of("path", lookup));
                    case 2 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "Rating", "lookup", "read_file " + lookup, "lines", "",
                        "note", "A catalog registers itself."));
                    case 3 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "Rating", "lookup", "read_file " + lookup,
                        "lines", "1-" + (DraftTools.FINDING_LINES + 1),
                        "note", "A catalog registers itself."));
                    case 4 -> ScriptedAgentLlm.Turn.call("keep_for_workers", Map.of(
                        "about", "Rating", "lookup", "read_file " + lookup,
                        "lines", (registers - 3) + "-" + (registers + 3),
                        "note", "A catalog registers itself."));
                    case 5 -> ScriptedAgentLlm.Turn.call("check_design", Map.of("design", designJson()));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate);
            architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
                new KoogAgentRuntime(), null, null));

            DesignDocument design = architect.design("Let a customer rate a title");

            assertThat(llm.sessionRequests.get(0))
                .as("told what a finding is for, in words that name no framework")
                .contains("with the tasks built on it")
                .contains("what the compiler will not tell a worker")
                .contains("never a file's package line and imports");
            assertThat(llm.sessionRequests.get(2)).as("a whole long result is not kept")
                .contains("NOT KEPT: that lookup's result has")
                .contains("a finding carries at most " + DraftTools.FINDING_LINES)
                .contains("Name the lines that DO the thing");
            assertThat(llm.sessionRequests.get(3)).as("nor a named range that is too long")
                .contains("NOT KEPT: 1-" + (DraftTools.FINDING_LINES + 1) + " is "
                    + (DraftTools.FINDING_LINES + 1) + " lines");
            assertThat(design.findings()).hasSize(1);
            assertThat(design.findings().get(0).snippet())
                .as("what is kept are the lines the architect named, not the head of the file")
                .contains("public void register() { Registry.add(this); }")
                .doesNotContain("package com.acme.shop");
        }
    }

    @Test
    void whatDoesNotFitLosesItsLinesBeforeItIsLeftOutAndIsNeverCutInTheMiddle() {
        Task task = task("Order service", Set.of(), service);
        DesignDocument design = design(aboutService, aboutProject, aboutOrder);
        int room = aboutService.size() + aboutProject.size() + aboutOrder.withoutSnippet().size();

        ArchitectHandover.Given tight = ArchitectHandover.forTask(task, design, room);
        assertThat(tight.findings()).hasSize(3);
        assertThat(tight.findings().get(0)).isEqualTo(aboutService);
        assertThat(tight.findings().get(2).hasSnippet())
            .as("the least relevant gives up its lines first").isFalse();
        assertThat(tight.findings().get(2).note()).isEqualTo(aboutOrder.note());
        assertThat(tight.findings().get(2).source())
            .as("and still says which lookup reads the rest").isEqualTo(aboutOrder.source());
        assertThat(tight.withoutCode()).isEqualTo(1);
        assertThat(DesignFinding.sizeOf(tight.findings())).isLessThanOrEqualTo(room);

        ArchitectHandover.Given tighter = ArchitectHandover.forTask(task, design,
            aboutService.size() + aboutProject.size());
        assertThat(tighter.findings()).containsExactly(aboutService, aboutProject);
        assertThat(tighter.leftOut()).isEqualTo(1);

        ArchitectHandover.Given none = ArchitectHandover.forTask(task, design, 10);
        assertThat(none.findings()).isEmpty();
        assertThat(none.leftOut()).isEqualTo(3);
    }

    @Test
    void anAcceptedPlansTasksCarryTheirFindingsAndTheLogSaysWhatEachWasGiven() {
        Task builds = task("Order service", Set.of(), service, order);
        Task shows = task("Order screen", Set.of(), screen);
        Task glue = task("Something else", Set.of());
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(builds, shows, glue)), new ArrayList<>());

        List<String> lines = ArchitectHandover.attach(graph,
            design(aboutService, aboutOrder, aboutScreen, aboutProject, aboutNothingBuilt));

        assertThat(builds.architectFindings())
            .containsExactly(aboutService, aboutOrder, aboutProject);
        assertThat(shows.architectFindings()).containsExactly(aboutScreen, aboutProject);
        assertThat(glue.architectFindings()).containsExactly(aboutProject);
        assertThat(lines)
            .anyMatch(l -> l.startsWith("'Order service' is given 3 of the architect's 5 "
                + "finding(s), " + DesignFinding.sizeOf(builds.architectFindings())
                + " characters"))
            .anyMatch(l -> l.contains("built or changed by no task of this plan")
                && l.contains("Invoice"));
    }

    @Test
    void aDesignWithNoFindingsLeavesEveryTaskAsItWas() {
        Task builds = task("Order service", Set.of(), service);
        TaskGraph graph = new TaskGraph(UUID.randomUUID(), 1, null,
            new ArrayList<>(List.of(builds)), new ArrayList<>());

        assertThat(ArchitectHandover.attach(graph, design()))
            .containsExactly("the architect kept no findings for the workers; every task "
                + "starts from the librarian's brief");
        assertThat(builds.architectFindings()).isEmpty();
    }

    @Test
    void theTestAuthorOfATaskIsGivenTheSameFindingsAsItsWorkers() {
        Task task = task("Order service", Set.of(), service);
        assertThat(TestAuthorClient.established(task)).isEmpty();

        task.setArchitectFindings(List.of(aboutService, aboutProject));

        assertThat(TestAuthorClient.established(task))
            .isEqualTo("\n\n" + DesignFinding.renderAll(List.of(aboutService, aboutProject)))
            .contains("    @Service").contains("    class LedgerStore {}")
            .contains("(from body_of LedgerStore)");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static DesignFinding finding(String about, String source, String note,
                                         String snippet) {
        return new DesignFinding(UUID.randomUUID(), about, source, note, snippet);
    }

    private static ApiContract contract(String name, String type, String... members) {
        return new ApiContract(UUID.randomUUID(), name, "NEW: " + name, "class " + name, type,
            List.of(members));
    }

    private static Task task(String title, Set<String> writeSet, ApiContract... delivers) {
        Task task = new Task(UUID.randomUUID(), 1, title, "do it", writeSet, Set.of(), List.of(),
            "src/test/java/swarm", null, null, new SwarmPolicy(2, false, 0.2, 0.2, List.of()),
            TaskState.PENDING);
        task.setDeliveredContracts(List.of(delivers));
        return task;
    }

    private static String designJson() {
        return """
            {"requirements":[{"text":"A customer can rate a title","priority":"HIGH"}],
             "decisions":[{"decision":"Keep ratings beside the catalog","rationale":"one module"}],
             "contracts":[{"name":"Rating","description":"NEW: what the story adds",
               "signature":"class Rating","type":"com.acme.shop.Rating","members":["int stars()"]}],
             "risks":[]}
            """;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
