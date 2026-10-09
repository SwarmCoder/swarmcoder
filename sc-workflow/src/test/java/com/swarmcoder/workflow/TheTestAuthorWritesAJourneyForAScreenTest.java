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
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.verify.BrowserOnlyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 63: for a task that changes a screen the test author also writes a journey - a file
 * beside its test, checked by {@code check_journey} with no model and handed in with the test.
 * The model is scripted ({@link ScriptedAgentLlm}); the agent runtime, the lookups and the tools
 * are the real ones.
 */
class TheTestAuthorWritesAJourneyForAScreenTest {

    private static final String DIR = "src/test/java/swarm";
    private static final String TEST_FILE = DIR + "/accept/OrdersTest.java";
    private static final String JOURNEY_FILE = DIR + "/accept/add-order.journey.yaml";
    private static final String EARLIER_JOURNEY = DIR + "/accept/catalog.journey.yaml";

    private static final String TEST = """
        package swarm.accept;

        import com.acme.shop.Catalog;
        import org.junit.jupiter.api.Assertions;
        import org.junit.jupiter.api.Test;

        public class OrdersTest {
            @Test
            public void addsAnOrder() {
                Assertions.assertNotNull(new Catalog());
            }
        }
        """;

    private static final String JOURNEY = """
        journey: An order is added from the orders screen
        steps:
          - click: "role=link[name=\\"Orders\\"]"
          - click: "role=button[name=\\"Add order\\"]"
          - expectVisible: "role=textbox[name=\\"Customer\\"]"
        """;

    private static final String CLAIM =
        "an order can be added => swarm.accept.OrdersTest#addsAnOrder";

    @TempDir
    Path app;
    @TempDir
    Path cache;

    @BeforeEach
    void aProjectWithAnEarlierStorysJourney() throws Exception {
        write(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(app.resolve("src/main/java/com/acme/shop/Catalog.java"),
            "package com.acme.shop;\n\npublic class Catalog {\n}\n");
        write(app.resolve(EARLIER_JOURNEY), JOURNEY.replace("An order", "A catalog entry"));
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "earlier story");
        EarlierAcceptanceTests.forget();
        EarlierAcceptanceTests.pin(app, "HEAD");
    }

    @Test
    void aHandInWithoutAJourneyIsAskedForItInTheSameConversationAndTheJourneyIsClaimed()
            throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", TEST_FILE, "content", TEST));
                    // The mistake: the test alone, as before section 63.
                    case 2 -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", CLAIM));
                    // Asked for the journey: first with an address, which a journey may not have,
                    case 3 -> ScriptedAgentLlm.Turn.call("check_journey",
                        Map.of("path", JOURNEY_FILE,
                            "content", JOURNEY.replace("steps:", "url: /orders/new\nsteps:")));
                    // then over an earlier story's journey, which is never changed,
                    case 4 -> ScriptedAgentLlm.Turn.call("check_journey",
                        Map.of("path", EARLIER_JOURNEY, "content", JOURNEY));
                    // then as it should be.
                    case 5 -> ScriptedAgentLlm.Turn.call("check_journey",
                        Map.of("path", "add-order.journey.yaml", "content", JOURNEY));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", CLAIM));
                })) {
            TestAuthorClient author = author(llm);

            TestAuthorClient.Authored authored = author.authorTests(app, task(), null,
                task().criteria(), "", true, null, List.of(), BrowserOnlyCode.Survey.NONE,
                List.of(), true);

            assertThat(authored.failureReason()).isNull();
            assertThat(llm.sessionRequests.get(0))
                .as("the session is told a journey is due and has the two tools for it")
                .contains("THIS TASK CHANGES A SCREEN")
                .contains("check_journey").contains("texts_of");
            assertThat(llm.sessionRequests.get(2))
                .as("the hand-in without a journey goes back to the conversation that made it")
                .contains("THIS IS THE SAME CONVERSATION")
                .contains("What is missing is the JOURNEY");
            assertThat(llm.sessionRequests.get(3))
                .contains("NOT A VALID JOURNEY").contains("`url` is not a key of a journey")
                .as("a draft sent back is told the steps there are")
                .contains("The steps a journey has, and nothing else:")
                .contains(com.swarmcoder.verify.JourneyFile.VOCABULARY);
            assertThat(llm.sessionRequests.get(4))
                .contains("already holds an earlier story's journey");
            assertThat(llm.sessionRequests.get(5)).contains("VALID - kept");
            assertThat(authored.paths())
                .as("the tests the build compiles: the journey is not among them")
                .containsExactly(TEST_FILE);
            assertThat(authored.journeys()).containsExactly(JOURNEY_FILE);
        }
        assertThat(Files.readString(app.resolve(JOURNEY_FILE))).isEqualTo(JOURNEY);
        assertThat(Files.readString(app.resolve(EARLIER_JOURNEY)))
            .as("the earlier story's journey is exactly as it was").contains("A catalog entry");
        assertThat(Files.readString(app.resolve(TEST_FILE))).contains("class OrdersTest");
    }

    /**
     * Section 64 (live run 89): in a project whose application is started for a browser every
     * story is asked for a journey; where the graph shows no browser code using the change the
     * author may answer {@code noVisibleEffect} in place of one. Recorded, and no file written.
     */
    @Test
    void whereTheGraphLeavesRoomTheAuthorMayPutOnRecordThatNothingOnAScreenDiffers()
            throws Exception {
        String none = DIR + "/accept/none.journey.yaml";
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", TEST_FILE, "content", TEST));
                    case 2 -> ScriptedAgentLlm.Turn.call("check_journey", Map.of("path", none,
                        "content", "noVisibleEffect: the ledger's file format changes\n"));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", CLAIM));
                })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(app, task(), null,
                task().criteria(), "", true, null, List.of(), BrowserOnlyCode.Survey.NONE,
                List.of(), new TestAuthorClient.JourneyAsk(true, true, List.of()));

            assertThat(llm.sessionRequests.get(0))
                .contains("THIS APPLICATION IS USED IN A BROWSER")
                .contains("IN PLACE OF A JOURNEY").contains("noVisibleEffect: <")
                .contains("check_journey");
            assertThat(llm.sessionRequests.get(2)).contains("RECORDED in place of a journey");
            assertThat(llm.sessionRequests).as("not asked again for a journey").hasSize(3);
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(authored.journeys()).isEmpty();
            assertThat(authored.journeyWaiver()).isEqualTo("the ledger's file format changes");
        }
        assertThat(app.resolve(none)).as("an answer, not a file of the project").doesNotExist();
    }

    @Test
    void whereBrowserCodeUsesTheChangeThatAnswerIsNotTakenAndTheJourneyIsWritten()
            throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> switch (turn) {
                    case 1 -> ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", TEST_FILE, "content", TEST));
                    case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                        Map.of("path", JOURNEY_FILE, "content", "noVisibleEffect: server only\n"));
                    case 3 -> ScriptedAgentLlm.Turn.call("check_journey",
                        Map.of("path", JOURNEY_FILE, "content", JOURNEY));
                    default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", CLAIM));
                })) {
            TestAuthorClient.Authored authored = author(llm).authorTests(app, task(), null,
                task().criteria(), "", true, null, List.of(), BrowserOnlyCode.Survey.NONE,
                List.of(), new TestAuthorClient.JourneyAsk(true, false,
                    List.of("OrdersScreen.java uses OrderService, which OrderServiceImpl is")));

            assertThat(llm.sessionRequests.get(0))
                .contains("CODE THAT RUNS IN THE BROWSER USES WHAT THIS TASK CHANGES "
                    + "(OrdersScreen.java uses OrderService, which OrderServiceImpl is)")
                .doesNotContain("IN PLACE OF A JOURNEY");
            assertThat(llm.sessionRequests.get(2)).contains("NOT TAKEN");
            assertThat(llm.sessionRequests.get(3)).contains("VALID - kept");
            assertThat(authored.journeys()).containsExactly(JOURNEY_FILE);
            assertThat(authored.journeyWaiver()).isNull();
        }
    }

    @Test
    void aTaskThatChangesNoScreenIsOfferedNoJourneyToolAndToldNothingAboutOne() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(
                conversation -> "{\"files\":[],\"wrote\":[]}",
                (turn, conversation) -> turn == 1
                    ? ScriptedAgentLlm.Turn.call("compile_test",
                        Map.of("path", TEST_FILE, "content", TEST))
                    : ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", CLAIM)))) {
            TestAuthorClient author = author(llm);

            TestAuthorClient.Authored authored = author.authorTests(app, task(), null,
                task().criteria(), "", true, null, List.of(), BrowserOnlyCode.Survey.NONE,
                List.of(), false);

            assertThat(llm.sessionRequests).hasSize(2);
            assertThat(llm.sessionRequests.get(0))
                .doesNotContain("check_journey").doesNotContain("THIS TASK CHANGES A SCREEN");
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(authored.journeys()).isEmpty();
        }
    }

    @Test
    void inOneReplyTheJourneyIsOneMoreFileAndOnlyAWellFormedOneIsKept() throws Exception {
        String reply = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "files", List.of(
                Map.of("path", TEST_FILE, "content", TEST),
                Map.of("path", JOURNEY_FILE, "content", JOURNEY),
                Map.of("path", DIR + "/accept/broken.journey.yaml",
                    "content", "journey: No steps at all\n")),
            "wrote", List.of(Map.of("criterion", "an order can be added",
                "test", "swarm.accept.OrdersTest#addsAnOrder"))));
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> reply,
                (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("wrote", "")))) {
            VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
            TestAuthorClient author = new TestAuthorClient(client, new CloudGate(0, null));

            TestAuthorClient.Authored authored = author.authorTests(app, task(), null,
                task().criteria(), "", true, null, List.of(), BrowserOnlyCode.Survey.NONE,
                List.of(), true);

            assertThat(authored.failureReason()).isNull();
            assertThat(authored.paths()).containsExactly(TEST_FILE);
            assertThat(authored.journeys()).containsExactly(JOURNEY_FILE);
        }
        assertThat(app.resolve(DIR + "/accept/broken.journey.yaml")).doesNotExist();
        assertThat(TestAuthorClient.journeyBrief(DIR + "/accept", false))
            .contains("No step loads an address")
            .contains("It must FAIL on the application as it is today")
            .contains("one more entry")
            .doesNotContain("check_journey")
            .as("every step there is, each with what it is for, and how to choose (run 100)")
            .contains(com.swarmcoder.verify.JourneyFile.VOCABULARY)
            .contains(com.swarmcoder.verify.JourneyFile.CHOOSING)
            .contains("`value` only with fill, select and expectValue");
        assertThat(TestAuthorClient.agentSystem("You are a test author.", DIR + "/accept", true))
            .contains("4. THE JOURNEY.").contains("check_journey");
        assertThat(TestAuthorClient.agentSystem("You are a test author.", DIR + "/accept"))
            .doesNotContain("JOURNEY");
    }

    // -------------------------------------------------------------------------------------------

    private TestAuthorClient author(ScriptedAgentLlm llm) throws Exception {
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        CloudGate gate = new CloudGate(0, null);
        TestAuthorClient author = new TestAuthorClient(client, gate);
        author.setLookupAgent(new LookupAgent(new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local")), null, cache),
            null, app, gate, new KoogAgentRuntime(), null, null));
        return author;
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1, "Orders screen", "An order can be added.",
            Set.of("src/main"), Set.of(),
            List.of(new AcceptanceCriterion(UUID.randomUUID(), "an order can be added",
                "swarm.accept.OrdersTest#addsAnOrder")),
            DIR, null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of("minimal-diff")), TaskState.READY);
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", app.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
