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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 69, after live run 93: a journey that fails in the browser after the last merge goes
 * back to its author before any worker is sent after it. The journey of that run could not be
 * repaired by a worker: it found a text box by a name the screen did not use, and expected a
 * book nobody had entered.
 *
 * <p>The model is scripted; the agent runtime, the lookups and the tools are real. No browser is
 * started here: what a browser would say on the two trees is given to the guard as a function.
 */
class AFailedJourneyGoesBackToItsAuthorTest {

    private static final String DIR = "src/test/java/swarm";
    private static final String PATH = DIR + "/accept/search.journey.yaml";
    private static final String WRITTEN = """
        journey: A book is found by its title
        steps:
          - click: "role=link[name=\\"Books\\"]"
          - fill: "role=textbox[name=\\"Search books by title or author\\"]"
            value: "hobbit"
          - expectVisible: "text=A Wizard of Earthsea"
        """;
    private static final String CORRECTED = """
        journey: A book is added and then found by its title
        steps:
          - click: "role=link[name=\\"Books\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Search books...\\"]"
            value: "hobbit"
          - expectVisible: "text=The Hobbit"
        """;
    private static final String ONLY_LOOKS = """
        journey: The books screen is there
        steps:
          - expectVisible: "role=heading[name=\\"Books\\"]"
        """;

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    private static JourneyFile.Journey journey(String yaml) {
        return JourneyFile.read(PATH, yaml).journey();
    }

    private static String whatHappened() {
        return JourneysOfAPlan.sendBackEvidence(new JourneyFile.Result(journey(WRITTEN), false,
            "step 2 of 3 failed: `fill role=textbox[name=\"Search books by title or author\"]` "
                + "- locator.fill: Timeout 10000ms exceeded.",
            "elements, as role \"accessible name\":\nlink \"Books\"\ntextbox \"Search books...\""
                + "\nvisible text: Bookshelf Books No books yet"));
    }

    // ---- the author, in its session -------------------------------------------------------

    @Test
    void theAuthorIsShownThePageAndACorrectionAtTheJourneysOwnPathIsWhatItHandsIn()
            throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", DIR + "/accept/another.journey.yaml", "content", CORRECTED));
                case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", CORRECTED));
                default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote",
                    "The journey named the search box by words the criteria do not fix and "
                        + "expected a book no step had added."));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.answered()).as(reviewed.reason()).isTrue();
            assertThat(reviewed.journeyIsWrong()).isTrue();
            assertThat(reviewed.corrected()).isEqualTo(CORRECTED);
            assertThat(reviewed.reason()).startsWith("The journey named the search box");
            assertThat(llm.sessionRequests.get(0)).as("the journey, the failing step, what the "
                + "page showed and both ways to answer are in the opening")
                .contains("A book is found by its title", "step 2 of 3 failed",
                    "WHAT THE PAGE SHOWED AT THAT STEP", "Search books...", "No books yet",
                    "HOW TO ANSWER", "THE JOURNEY IS WRONG", "THE JOURNEY IS RIGHT");
            assertThat(llm.sessionRequests.get(1)).as("only the failed journey may be corrected")
                .contains("This review is about `" + PATH + "` and no other file");
            assertThat(llm.sessionRequests.get(2)).contains("VALID - kept");
            assertThat(Files.readString(repo.resolve(PATH))).as("nothing is written by the "
                + "review: whether the correction is taken is decided in a browser")
                .isEqualTo(WRITTEN);
        }
    }

    @Test
    void anAuthorThatHandsInWithNothingCheckedStandsByItsJourney() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                Map.of("wrote", "The criterion names the box; the screen gave it no label.")))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.answered()).isTrue();
            assertThat(reviewed.journeyIsWrong()).isFalse();
            assertThat(reviewed.corrected()).isNull();
            assertThat(reviewed.reason())
                .isEqualTo("The criterion names the box; the screen gave it no label.");
        }
    }

    @Test
    void aSessionThatEndsOnWordsAloneIsNoUsableAnswer() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.text("Hard to say."))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.answered()).isFalse();
            assertThat(reviewed.reason())
                .contains("neither a corrected journey nor a hand-in", "Hard to say.");
        }
    }

    @Test
    void aJourneyThatExpectsATextNobodyEntersIsAskedAboutOnceAndKeptWhenGivenAgain()
            throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1, 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", WRITTEN));
                default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("wrote", "kept"));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(llm.sessionRequests.get(1)).as("asked while the journey is written, "
                + "not after the last merge").contains("NOT KEPT YET", "NO DATA of its own",
                    "step 3", "A Wizard of Earthsea");
            assertThat(llm.sessionRequests.get(2)).as("the same file again is the author's "
                + "answer that the new screen shows the text by itself").contains("VALID - kept");
            assertThat(reviewed.corrected()).isEqualTo(WRITTEN);
        }
    }

    // ---- the guard against a correction that asks for less --------------------------------

    private static Function<List<JourneyFile.Journey>, JourneyRunner.Outcome> browser(
            List<List<JourneyFile.Journey>> asked, boolean... passes) {
        return journeys -> {
            asked.add(journeys);
            List<JourneyFile.Result> results = new ArrayList<>();
            for (int i = 0; i < journeys.size(); i++) {
                boolean passed = passes[Math.min(i, passes.length - 1)];
                results.add(new JourneyFile.Result(journeys.get(i), passed,
                    passed ? null : "step 1 of 1 failed: `x` - Timeout."));
            }
            return new JourneyRunner.Outcome(null, null, results);
        };
    }

    private static final Function<List<JourneyFile.Journey>, JourneyRunner.Outcome> NEVER =
        journeys -> {
            throw new AssertionError("no browser is started for this correction");
        };

    @Test
    void aCorrectionThatOnlyLooksIsRefusedBeforeAnyBrowserIsStarted() {
        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, ONLY_LOOKS, NEVER,
            NEVER)).startsWith("it asks for less than the journey it replaces")
            .contains("no step that changes anything");
        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, "steps: nothing",
            NEVER, NEVER)).startsWith("it is not a well-formed journey");
    }

    @Test
    void aCorrectionThatStillFailsOnTheMergedTreeIsRefusedAndTheStartTreeIsNotBuilt() {
        List<List<JourneyFile.Journey>> merged = new ArrayList<>();

        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            browser(merged, false), NEVER)).startsWith("it fails on the merged tree too");
        assertThat(merged).hasSize(1);
    }

    @Test
    void aCorrectionThatPassesBeforeTheStoryOrEndsOnWhatWasAlreadyThereIsRefused() {
        List<List<JourneyFile.Journey>> asked = new ArrayList<>();

        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            browser(asked, true), browser(asked, true, false)))
            .contains("passes on the application as it was before the story");
        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            browser(asked, true), browser(asked, false, true)))
            .contains("already on the entry page", "expect visible text=The Hobbit");
    }

    @Test
    void aCorrectionThatPassesAfterAndFailsBeforeWithAnEndingThatIsNewIsTaken() {
        List<List<JourneyFile.Journey>> merged = new ArrayList<>();
        List<List<JourneyFile.Journey>> start = new ArrayList<>();

        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            browser(merged, true), browser(start, false, false))).isNull();
        assertThat(merged.get(0)).hasSize(1);
        assertThat(start.get(0)).as("the journey, and its last expectation alone").hasSize(2);
        assertThat(start.get(0).get(1).steps()).hasSize(1);
    }

    @Test
    void aCorrectionThatCouldNotBeTriedIsNotTaken() {
        Function<List<JourneyFile.Journey>, JourneyRunner.Outcome> noBrowser =
            journeys -> new JourneyRunner.Outcome("no browser in the container", null, List.of());

        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            noBrowser, NEVER)).isEqualTo("it could not be made on the merged tree: no browser "
                + "in the container");
        assertThat(JourneysOfAPlan.correctionRefused(journey(WRITTEN), PATH, CORRECTED,
            browser(new ArrayList<>(), true), noBrowser))
            .startsWith("it could not be made on the tree the run started from");
    }

    @Test
    void theWorkersOfTheRepairRoundAreToldWhatTheAuthorAnswered() {
        String evidence = JourneysOfAPlan.repairEvidence(List.of(new JourneyFile.Result(
            journey(WRITTEN), false, "step 2 of 3 failed")));

        assertThat(JourneysOfAPlan.repairEvidence(evidence, null)).isEqualTo(evidence);
        assertThat(JourneysOfAPlan.repairEvidence(evidence, "The journey's author answered that "
            + "the journey is right and the screen is wrong: the box has no label."))
            .startsWith(evidence)
            .contains("THE JOURNEY'S AUTHOR WAS ASKED FIRST", "the box has no label.");
        assertThat(evidence).contains("every role, accessible name and text a selector of the "
            + "journey uses must be on the screen exactly as the journey writes it");
    }

    // ---------------------------------------------------------------------------------------

    private TestAuthorClient author(ScriptedAgentLlm llm) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/com/f"));
        Files.writeString(repo.resolve("src/main/java/com/f/Shelf.java"),
            "package com.f;\npublic class Shelf { String title() { return \"Bookshelf\"; } }\n");
        Files.createDirectories(repo.resolve(PATH).getParent());
        Files.writeString(repo.resolve(PATH), WRITTEN);
        VllmClient client = new VllmClient(llm.baseUrl(), "", "scripted", true);
        CloudGate gate = new CloudGate(0, null);
        TestAuthorClient author = new TestAuthorClient(client, gate);
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", repo, "local")), null, cache);
        author.setLookupAgent(new LookupAgent(curator, null, repo, gate, new KoogAgentRuntime(),
            null, null));
        return author;
    }

    private static Task task() {
        Task task = new Task(UUID.randomUUID(), 1, "search the books", "add a search box",
            Set.of("src/main/java"), Set.of(), List.of(), DIR, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        task.setJourneyPaths(List.of(PATH));
        return task;
    }
}
