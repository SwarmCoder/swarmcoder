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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.ExpertDesk;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architect and the planner as agents (owner decision, 2026-10-02): each looks a fact up in a
 * real checkout and a real reference tree before it writes, gives its draft to the build's own
 * mechanical check, is told what that check objects to, corrects the draft and hands it in - and
 * a question one of them put to the expert is not researched a second time for the other.
 *
 * <p>The model is scripted ({@link ScriptedAgentLlm}); the agent runtime, the lookups, the
 * Librarian, the help desk with its remembered answers and the mechanical checks are the real
 * ones. The checks given to the drafts here are the very ones the workflow gives them:
 * {@link ExistingProjectTypes#duplicateObjections} and {@link TaskGraphValidator}.
 */
class TheArchitectAndThePlannerLookThingsUpAndCheckTheirDraftsTest {

    private static final String HOW_TO = "qqzzx wibble frobnicate the whatsit ledgerwise";

    @TempDir
    Path world;

    private Path app;
    private Librarian librarian;
    private final AtomicInteger expertSessions = new AtomicInteger();

    /** A project that already has a {@code Catalog}, and a reference library beside it. */
    @BeforeEach
    void world() throws Exception {
        app = world.resolve("app");
        Path reference = world.resolve("ledgerworks");
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"), """
            package com.acme.shop;

            import java.util.List;

            public class Catalog {
                public List<String> titles() { return List.of(); }
                public void add(String title) { }
            }
            """);
        write(reference.resolve("pom.xml"),
            "<project><groupId>com.ledgerworks</groupId><artifactId>ledgerworks</artifactId>"
                + "</project>");
        write(reference.resolve("src/main/java/com/ledgerworks/LedgerSession.java"), """
            package com.ledgerworks;

            /** A session against the ledger. */
            public class LedgerSession {
                public void append(Entry entry) { }
                public void commit() { }
            }
            """);
        write(reference.resolve("src/main/java/com/ledgerworks/Entry.java"), """
            package com.ledgerworks;

            public record Entry(String what) { }
            """);
        librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(reference), app, null, world.resolve("cache"));
    }

    // -------------------------------------------------------------------------------------------

    @Test
    void theArchitectReadsTheCheckoutIsToldItsDraftDuplicatesATypeAndHandsInTheCorrection()
            throws Exception {
        AtomicInteger oneReplies = new AtomicInteger();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> {
                    oneReplies.incrementAndGet();
                    return design("com.acme.oneshot.Wrong");
                },
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("read_file",
                        Map.of("path", "project/src/main/java/com/acme/shop/Catalog.java"));
                    // A second Catalog, in a new package: live run 68's fault.
                    case 2 -> ScriptedAgentLlm.Turn.call("check_design",
                        Map.of("design", design("com.acme.ratings.Catalog")));
                    case 3 -> ScriptedAgentLlm.Turn.call("check_design",
                        Map.of("design", design("com.acme.shop.Rating")));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            ArchitectClient architect = architect(llm);
            DesignDocument design;
            try (ArchitectClient.Scope checking = architect.checkingDraftsWith(checks())) {
                design = architect.design("Let a customer rate a title in the catalog");
            }

            assertThat(llm.sessionRequests).as("a lookup, two drafts, the hand-in").hasSize(4);
            assertThat(llm.sessionRequests.get(0))
                .as("told to verify, to ask rather than guess, and which tool is for what")
                .contains("CHECK EVERY FACT BEFORE YOU RELY ON IT")
                .contains("WHICH TOOL FOR WHAT")
                .contains("never guess")
                .contains("ask_expert")
                .contains("find_example")
                .contains("docs_for");
            assertThat(llm.sessionRequests.get(1))
                .as("the lookup returned the real class from the checkout")
                .contains("public List<String> titles()");
            assertThat(llm.sessionRequests.get(2))
                .as("the mechanical check's own objection came back before hand-in")
                .contains("OBJECTION(S)")
                .contains("com.acme.ratings.Catalog duplicates a type this project already has")
                .contains("com.acme.shop.Catalog already exists");
            assertThat(llm.sessionRequests.get(3)).contains("NO OBJECTIONS");

            assertThat(design).isNotNull();
            assertThat(design.contracts()).extracting(c -> c.typeName())
                .as("what was handed in is the corrected draft")
                .containsExactly("com.acme.shop.Rating");
            assertThat(oneReplies.get()).as("the one-reply path was not needed").isZero();
        }
    }

    @Test
    void thePlannerReadsTheDocumentationIsToldItsDraftWritesTheProtectedTestsAndHandsInTheCorrection()
            throws Exception {
        AtomicInteger oneReplies = new AtomicInteger();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> {
                    oneReplies.incrementAndGet();
                    return plan("src/main/java/com/acme/oneshot");
                },
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("list_files", Map.of("dir", "project"));
                    case 2 -> ScriptedAgentLlm.Turn.call("docs_for", Map.of("query", "LedgerSession"));
                    case 3 -> ScriptedAgentLlm.Turn.call("check_plan",
                        Map.of("plan", plan("src/test/java/swarm/accept")));
                    case 4 -> ScriptedAgentLlm.Turn.call("check_plan",
                        Map.of("plan", plan("src/main/java/com/acme/shop")));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("finalJson", ""));
                })) {
            ArchitectClient architect = architect(llm);
            TaskGraph graph;
            try (ArchitectClient.Scope checking = architect.checkingDraftsWith(checks())) {
                graph = architect.plan(null, "Let a customer rate a title in the catalog");
            }

            assertThat(llm.sessionRequests).hasSize(5);
            assertThat(llm.sessionRequests.get(0)).contains("You are an AI planner")
                .as("told what it is for, and what it is not (section 73)")
                .contains("YOU SPLIT THE DESIGN INTO TASKS AND ORDER THEM. NOTHING ELSE.")
                .contains("check_plan").doesNotContain("keep_for_workers");
            assertThat(llm.sessionRequests.get(1))
                .as("the listing is the real checkout").contains("Catalog.java");
            assertThat(llm.sessionRequests.get(2))
                .as("the librarian answered from the reference tree")
                .contains("LedgerSession").contains("append(Entry entry)");
            assertThat(llm.sessionRequests.get(3))
                .as("the plan validator's own objection came back before hand-in")
                .contains("OBJECTION(S)").contains("src/test/java/swarm");
            assertThat(llm.sessionRequests.get(4)).contains("NO OBJECTIONS");

            assertThat(graph).isNotNull();
            assertThat(graph.tasks()).hasSize(1);
            assertThat(graph.tasks().get(0).writeSet())
                .as("what was handed in is the corrected draft")
                .containsExactly("src/main/java/com/acme/shop");
            assertThat(oneReplies.get()).as("the one-reply path was not needed").isZero();
        }
    }

    @Test
    void aQuestionTheArchitectPutToTheExpertIsNotResearchedAgainForThePlanner() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "I decline to produce JSON.",
                (turn, conversation) -> switch (turn) {
                    // the architect's session
                    case 1 -> ScriptedAgentLlm.Turn.call("ask_expert", Map.of("question", HOW_TO));
                    case 2 -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("finalJson", design("com.acme.shop.Rating")));
                    // the planner's session, in the same run
                    case 3 -> ScriptedAgentLlm.Turn.call("ask_expert", Map.of("question", HOW_TO));
                    default -> ScriptedAgentLlm.Turn.call("report_done",
                        Map.of("finalJson", plan("src/main/java/com/acme/shop")));
                })) {
            ArchitectClient architect = architect(llm);
            try (LookupAgent.Scope inRun = LookupAgent.inRun(UUID.randomUUID())) {
                DesignDocument design = architect.design("Let a customer rate a title");
                TaskGraph graph = architect.plan(design, "Let a customer rate a title");

                assertThat(design.contracts()).hasSize(1);
                assertThat(graph.tasks()).hasSize(1);
            }
            assertThat(llm.sessionRequests.get(1))
                .as("the architect was given the expert's answer")
                .contains("Call session.commit() after the last append.");
            assertThat(llm.sessionRequests.get(3))
                .as("and so was the planner")
                .contains("Call session.commit() after the last append.");
            assertThat(expertSessions.get())
                .as("but the expert investigated it once: the run remembered the answer")
                .isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Harness run 79 (2026-10-04): the planner's opening, sent again on each of 102 calls, held
     * the framework reference and the worked examples, which it can fetch with one lookup.
     */
    @Test
    void aPlannerWithLookupsIsNotSentTheReferenceItCanLookUpAndTheReplyWithoutToolsStillIs()
            throws Exception {
        ArchitectResearch research = new ArchitectResearch() {
            @Override
            public String reference(int maxChars) {
                return "PRIMER: a LedgerSession is committed after the last append.";
            }

            @Override
            public String lookupApi(String query) {
                return "";
            }

            @Override
            public String searchCode(String query) {
                return "";
            }

            @Override
            public String readFile(String address) {
                return "";
            }

            @Override
            public String listFolder(String address) {
                return "";
            }
        };
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> plan("src/main/java/com/acme/shop"),
                (turn, conversation) -> ScriptedAgentLlm.Turn.text("I cannot decide."))) {
            VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
            CloudGate gate = new CloudGate(10_000_000, null);
            ArchitectClient architect = new ArchitectClient(client, gate, null, research);
            architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
                new KoogAgentRuntime(), null, null));
            StoryScope scope = new StoryScope(null, List.of(), List.of(new AcceptanceCriterion(
                UUID.randomUUID(), "a title can be rated", "swarm.accept.RatingTest")),
                List.of("R1:C1"), List.of(), "");
            try {
                architect.plan(null, "Let a customer rate a title in the catalog", scope, "");
            } catch (RuntimeException e) {
                // What the plan is judged to be is another test's subject; this one reads what
                // each call was sent.
            }

            assertThat(llm.sessionRequests).isNotEmpty();
            assertThat(llm.sessionRequests.get(0))
                .as("the session is told it has no framework material and needs none")
                .contains("is attached here, and you need neither")
                .doesNotContain("FRAMEWORK REFERENCE").doesNotContain("PRIMER:");
            assertThat(llm.oneReplyRequests).isNotEmpty();
            assertThat(llm.oneReplyRequests.get(0))
                .as("nor is the reply without tools: the planner writes no how-to (section "
                    + "73), so the framework reference has nothing to inform")
                .doesNotContain("FRAMEWORK REFERENCE").doesNotContain("PRIMER:");
        }
    }

    private ArchitectClient architect(ScriptedAgentLlm llm) {
        VllmClient client = new VllmClient(llm.baseUrl(), null, "scripted", true);
        CloudGate gate = new CloudGate(10_000_000, null);
        ArchitectClient architect = new ArchitectClient(client, gate);
        architect.setLookupAgent(new LookupAgent(librarian.curator(), librarian, app, gate,
            new KoogAgentRuntime(), null, null)
            // The desk the workers ask, with a stand-in for the expert's investigation.
            .withExpert(() -> new ExpertDesk(librarian.curator(), app, List.of(),
                (question, context) -> {
                    expertSessions.incrementAndGet();
                    return "Call session.commit() after the last append.";
                })));
        return architect;
    }

    /** The checks the workflow gives a draft, on this fixture's checkout. */
    private ArchitectClient.DraftChecks checks() {
        return new ArchitectClient.DraftChecks() {
            @Override
            public List<String> design(DesignDocument draft) {
                return ExistingProjectTypes.of(app).duplicateObjections(draft);
            }

            @Override
            public List<String> plan(TaskGraph draft, DesignDocument design) {
                return new TaskGraphValidator().validate(draft, null, null, design, app).violations();
            }
        };
    }

    private static String design(String contractType) {
        String simple = contractType.substring(contractType.lastIndexOf('.') + 1);
        return """
            {"requirements":[{"text":"A customer can rate a title","priority":"HIGH"}],
             "decisions":[{"decision":"Keep ratings beside the catalog","rationale":"one module"}],
             "contracts":[{"name":"%s","description":"NEW: what the story adds",
               "signature":"class %s","type":"%s","members":["int stars()"]}],
             "risks":[]}
            """.formatted(simple, simple, contractType);
    }

    private static String plan(String writeDir) {
        return """
            {"tasks":[{"id":"A","title":"Ratings","instructions":"Add ratings to the catalog",
              "writeSet":["%s"],"readSet":[],
              "criteria":[{"text":"a title can be rated","testClassOrFile":"swarm.accept.RatingTest#rates"}],
              "requirementRefs":["R1"]}],
             "edges":[]}
            """.formatted(writeDir);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
