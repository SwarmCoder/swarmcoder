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
    /** The journey as written, under another title: a draft that is not the original again. */
    private static final String RETITLED =
        WRITTEN.replace("A book is found by its title", "A book is found by title");
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
                default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("verdict",
                    "JOURNEY_WRONG", "reason",
                    "The journey named the search box by words the criteria do not fix and "
                        + "expected a book no step had added."));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.verdict()).as(reviewed.reason())
                .isEqualTo(TestAuthorClient.JourneyVerdict.CORRECTED);
            assertThat(reviewed.journeyIsWrong()).isTrue();
            assertThat(reviewed.corrected()).isEqualTo(CORRECTED);
            assertThat(reviewed.reason()).startsWith("The journey named the search box");
            assertThat(llm.sessionRequests.get(0)).as("the journey, the failing step, what the "
                + "page showed and both ways to answer are in the opening")
                .contains("A book is found by its title", "step 2 of 3 failed",
                    "WHAT THE PAGE SHOWED AT THAT STEP", "Search books...", "No books yet",
                    "HOW TO ANSWER", "THE JOURNEY IS WRONG", "THE JOURNEY IS RIGHT")
                .as("the question is one, the review is short, and what the lookups read is said")
                .contains(TestAuthorClient.JOURNEY_REVIEW_TURNS + " turns in all",
                    "the one from BEFORE the story", "verdict JOURNEY_WRONG",
                    "verdict SCREEN_WRONG")
                .as("the steps a journey has are in the opening, so none is looked up (run "
                    + "100: five of ten turns went on searching the project for them)")
                .contains("THE STEPS A JOURNEY HAS", JourneyFile.VOCABULARY,
                    JourneyFile.CHOOSING, "what each drop-down list offers");
            assertThat(llm.sessionRequests.get(1)).as("only the failed journey may be corrected")
                .contains("This review is about `" + PATH + "` and no other file");
            assertThat(llm.sessionRequests.get(2)).contains("VALID - kept",
                "The steps a journey has:", JourneyFile.VOCABULARY,
                "Typing a text into a search or filter box does not create it");
            assertThat(Files.readString(repo.resolve(PATH))).as("nothing is written by the "
                + "review: whether the correction is taken is decided in a browser")
                .isEqualTo(WRITTEN);
        }
    }

    @Test
    void anAuthorThatNamesTheScreenAsWrongStandsByItsJourney() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                Map.of("verdict", "SCREEN_WRONG", "reason",
                    "The criterion names the box; the screen gave it no label.")))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.verdict()).isEqualTo(TestAuthorClient.JourneyVerdict.STANDS_BY);
            assertThat(reviewed.journeyIsWrong()).isFalse();
            assertThat(reviewed.corrected()).isNull();
            assertThat(reviewed.reason())
                .isEqualTo("The criterion names the box; the screen gave it no label.");
            assertThat(llm.sessionRequests).as("one call, and nothing asked again").hasSize(1);
        }
    }

    /**
     * Section 70, live run 95. The author wrote "the browser journey was wrong ... I replaced
     * it with a server-side test", kept no journey, and the product recorded "stands by it" and
     * started the workers. An admission with nothing handed in is asked about once more in the
     * same conversation, and is then "could not correct" - never "stands by it".
     */
    @Test
    void anAuthorThatCallsItsJourneyWrongAndHandsInNoneIsAskedOnceMoreAndNeverStandsByIt()
            throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("report_done",
                Map.of("verdict", "JOURNEY_WRONG", "reason", "The browser journey was wrong. "
                    + "It typed into a text box this task does not build. I replaced it with "
                    + "a server-side test.")))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.verdict())
                .isEqualTo(TestAuthorClient.JourneyVerdict.COULD_NOT_CORRECT);
            assertThat(reviewed.journeyIsWrong()).isTrue();
            assertThat(reviewed.corrected()).isNull();
            assertThat(reviewed.reason()).startsWith("The browser journey was wrong.");
            assertThat(llm.sessionRequests).as("asked once more, and only once").hasSize(2);
            assertThat(llm.sessionRequests.get(1)).as("in the same conversation, for the file")
                .contains("THIS IS THE SAME REVIEW", "has kept no corrected journey at `" + PATH,
                    "no other kind of test does", "WHAT THE PAGE SHOWED AT THAT STEP");
        }
    }

    @Test
    void theCorrectedFileHandedInWhenAskedOnceMoreIsTheCorrection() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "It expects a book nobody adds."));
                case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", CORRECTED));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "journey wrong", "reason", "It now adds the book first."));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.verdict()).isEqualTo(TestAuthorClient.JourneyVerdict.CORRECTED);
            assertThat(reviewed.corrected()).isEqualTo(CORRECTED);
            assertThat(reviewed.reason()).isEqualTo("It now adds the book first.");
        }
    }

    /** The journey as it failed, given to check_journey again, is not a correction of it. */
    @Test
    void theSameJourneyGivenAgainIsNotACorrection() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> turn % 3 == 0
                ? ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "It is wrong."))
                : ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", WRITTEN)))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(reviewed.verdict())
                .isEqualTo(TestAuthorClient.JourneyVerdict.COULD_NOT_CORRECT);
            assertThat(reviewed.corrected()).isNull();
        }
    }

    /**
     * Section 70: run 95's review made 22 calls under the ordinary stop of 120 turns. A review
     * that only looks things up is ended after its own small number of turns.
     */
    @Test
    void aReviewThatOnlyLooksThingsUpIsEndedAfterItsOwnFewTurns() throws Exception {
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> ScriptedAgentLlm.Turn.call("texts_of",
                Map.of("type", "Screen" + turn)))) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(TestAuthorClient.JOURNEY_REVIEW_TURNS).isEqualTo(10);
            assertThat(llm.sessionRequests.size())
                .isLessThanOrEqualTo(TestAuthorClient.JOURNEY_REVIEW_TURNS + 1);
            assertThat(reviewed.verdict()).isEqualTo(TestAuthorClient.JourneyVerdict.UNANSWERED);
            assertThat(reviewed.reason()).contains("TURN_CAP");
        }
    }

    /**
     * Section 70: the author's lookups read the project as it was before the story, where the
     * screen did not exist, and run 95's author concluded nobody had built it. In a review
     * texts_of answers from the tree the run built. The task that owns the journey writes the
     * screen; its own acceptance directory need not be the one the journey is in.
     */
    @Test
    void theAuthorCanAskWhatTheBuiltScreenHoldsAndCorrectAJourneyKeptInAnotherTasksDirectory(
            @TempDir Path built) throws Exception {
        Files.createDirectories(built.resolve("client/src/main/java/com/f/client"));
        Files.writeString(built.resolve("client/src/main/java/com/f/client/BooksScreen.java"),
            "package com.f.client;\npublic class BooksScreen {\n"
                + "    String placeholder() { return \"Search books...\"; }\n}\n");
        Task owner = new Task(UUID.randomUUID(), 1, "the books screen", "draw the screen",
            Set.of("client/src/main/java/com/f/client/BooksScreen.java"), Set.of(), List.of(),
            "client/src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
        owner.setJourneyPaths(List.of(PATH));
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("texts_of", Map.of("type", "BooksScreen"));
                case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", CORRECTED));
                default -> ScriptedAgentLlm.Turn.call("report_done", Map.of("verdict",
                    "JOURNEY_WRONG", "reason", "The box is named by its placeholder."));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                built, owner, null, List.of("a book is found by its title or its author"),
                PATH, WRITTEN, whatHappened());

            assertThat(llm.sessionRequests.get(0)).as("the story's criteria and where the "
                + "screen is written, not one task's checks")
                .contains("a book is found by its title or its author",
                    "It may write: client/src/main/java/com/f/client/BooksScreen.java");
            assertThat(llm.sessionRequests.get(1)).as("texts_of read the built tree")
                .contains("Texts in `BooksScreen`", "Search books...");
            assertThat(llm.sessionRequests.get(2)).contains("VALID - kept");
            assertThat(reviewed.verdict()).isEqualTo(TestAuthorClient.JourneyVerdict.CORRECTED);
            assertThat(reviewed.corrected()).isEqualTo(CORRECTED);
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
                    Map.of("path", PATH, "content", RETITLED));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "kept"));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(llm.sessionRequests.get(1)).as("asked while the journey is written, "
                + "not after the last merge").contains("NOT KEPT YET", "NO DATA of its own",
                    "step 3", "A Wizard of Earthsea");
            assertThat(llm.sessionRequests.get(2)).as("the same file again is the author's "
                + "answer that the new screen shows the text by itself").contains("VALID - kept");
            assertThat(reviewed.corrected()).isEqualTo(RETITLED);
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
        assertThat(evidence).as("the journey itself, step by step: no lookup stands between "
            + "a repair worker and what failed (section 70)")
            .contains("3 step(s) from the application's entry page", "1. click", "It failed: ");
    }

    /** Section 70: what the run stops on when its author disowns a journey. */
    @Test
    void aJourneyItsAuthorDisownsStopsTheRunWithAPlainMessageAndNoWorker() {
        String brief = JourneysOfAPlan.disowned(task(), List.of(PATH),
            List.of("The journey's author answered that the journey is WRONG and, asked "
                + "twice, handed in no corrected journey: it types into a box nobody built."));

        assertThat(brief).contains("its author says the journey itself is wrong",
            "'search the books'", PATH, "it types into a box nobody built.",
            "No worker was started", "correct the journey by hand and resume",
            "the author is then asked again");
    }

    /** Section 71: a second review only for a failure at a later step, and never a third. */
    private static JourneyFile.Result failedAt(int step) {
        return new JourneyFile.Result(journey(WRITTEN), false, "step " + step + " of 3 failed",
            null, step);
    }

    @Test
    void aJourneyThatFailsAtALaterStepAfterTheRepairRoundIsAskedAgain() {
        Task task = task();
        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(1))).as("never asked").isTrue();
        JourneysOfAPlan.recordReview(task, PATH, 1);
        task.setJourneySentBack(true);

        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(3))).isTrue();
        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(2))).isTrue();
    }

    @Test
    void aJourneyThatFailsAtTheSameOrAnEarlierStepIsNotAskedAgain() {
        Task task = task();
        JourneysOfAPlan.recordReview(task, PATH, 2);
        task.setJourneySentBack(true);

        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(2))).isFalse();
        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(1))).isFalse();
        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(0))).as("not a step's failure")
            .isFalse();
    }

    @Test
    void aJourneyIsAskedAtMostTwiceInARun() {
        Task task = task();
        JourneysOfAPlan.recordReview(task, PATH, 1);
        JourneysOfAPlan.recordReview(task, PATH, 2);
        task.setJourneySentBack(true);

        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(3))).isFalse();
        assertThat(JourneysOfAPlan.reviewsOf(task, PATH)).isEqualTo(2);
    }

    @Test
    void aRunMarkedAsAskedBeforeStepsWereKeptIsNotAskedAgain() {
        Task task = task();
        task.setJourneySentBack(true);

        assertThat(JourneysOfAPlan.goesToItsAuthor(task, failedAt(3))).isFalse();
    }

    @Test
    void theStopAfterTheSecondReviewCarriesBothReasons() {
        Task task = task();
        task.setJourneyReviewNote("first reason: the page never mounted. "
            + "SECOND REVIEW: second reason: no step adds a book.");

        assertThat(JourneysOfAPlan.reviewedTwice(task, List.of(PATH))).contains(
            "The second review gave no corrected journey that could be taken",
            "first reason: the page never mounted.", "second reason: no step adds a book.",
            "No worker was started");
    }

    // ---- section 75, live run 101: a correction that gets further is taken ----------------

    /** Run 101's journey, in shape: its first step expects what only the start page shows. */
    private static final String STARTS_ON_THE_OLD_PAGE = """
        journey: A book is added and listed
        steps:
          - expectVisible: "text=Nothing here yet"
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - click: "role=button[name=\\"Save\\"]"
          - expectVisible: "text=The Hobbit"
        """;
    /** Its author's correction: the first step now expects the new screen's heading. */
    private static final String STARTS_ON_THE_NEW_SCREEN = STARTS_ON_THE_OLD_PAGE
        .replace("text=Nothing here yet", "role=heading[name=\\\"Books\\\"]");

    private static JourneyFile.Result replacedAt(String yaml, int step) {
        return new JourneyFile.Result(journey(yaml), false, "step " + step + " failed", null,
            step);
    }

    /** A browser whose n-th journey fails at {@code steps[n]}; 0 means it passes. */
    private static Function<List<JourneyFile.Journey>, JourneyRunner.Outcome> failingAt(
            List<List<JourneyFile.Journey>> asked, int... steps) {
        return journeys -> {
            asked.add(journeys);
            List<JourneyFile.Result> results = new ArrayList<>();
            for (int i = 0; i < journeys.size(); i++) {
                int step = steps[Math.min(i, steps.length - 1)];
                results.add(new JourneyFile.Result(journeys.get(i), step == 0,
                    step == 0 ? null : "step " + step + " of " + journeys.get(i).steps().size()
                        + " failed: Timeout.", step == 0 ? null : "button \"Add\"", step));
            }
            return new JourneyRunner.Outcome(null, null, results);
        };
    }

    @Test
    void aCorrectionThatFailsAtALaterStepThanTheJourneyItReplacesIsTaken() {
        List<List<JourneyFile.Journey>> merged = new ArrayList<>();
        List<List<JourneyFile.Journey>> start = new ArrayList<>();

        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(
            replacedAt(STARTS_ON_THE_OLD_PAGE, 1), PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(merged, 2), failingAt(start, 1, 1, 1));

        assertThat(judged.refused()).isNull();
        assertThat(judged.taken()).isTrue();
        assertThat(judged.getsFurther()).isTrue();
        assertThat(judged.further().step()).isEqualTo(2);
        assertThat(judged.further().seen()).as("the page at the new failing step is known")
            .isEqualTo("button \"Add\"");
        assertThat(start.get(0)).as("the other guards are still asked on the start tree: the "
            + "journey, its last expectation alone, and what it expects before doing anything")
            .hasSize(3);
    }

    @Test
    void aCorrectionThatPassesIsTakenAsBeforeAndIsNotOneThatGetsFurther() {
        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(
            replacedAt(STARTS_ON_THE_OLD_PAGE, 1), PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(new ArrayList<>(), 0), failingAt(new ArrayList<>(), 1, 1, 1));

        assertThat(judged.taken()).isTrue();
        assertThat(judged.getsFurther()).isFalse();
        assertThat(judged.further()).isNull();
    }

    @Test
    void aCorrectionThatFailsAtTheSameOrAnEarlierStepIsRefusedAndTheStartTreeIsNotBuilt() {
        assertThat(JourneysOfAPlan.judgeCorrection(replacedAt(STARTS_ON_THE_OLD_PAGE, 2), PATH,
            STARTS_ON_THE_NEW_SCREEN, failingAt(new ArrayList<>(), 2), NEVER).refused())
            .startsWith("it fails on the merged tree too, and no later than the journey it "
                + "replaces (at step 2; that one failed at step 2)");
        assertThat(JourneysOfAPlan.judgeCorrection(replacedAt(STARTS_ON_THE_OLD_PAGE, 4), PATH,
            STARTS_ON_THE_NEW_SCREEN, failingAt(new ArrayList<>(), 2), NEVER).refused())
            .startsWith("it fails on the merged tree too");
        assertThat(JourneysOfAPlan.judgeCorrection(replacedAt(STARTS_ON_THE_OLD_PAGE, 0), PATH,
            STARTS_ON_THE_NEW_SCREEN, failingAt(new ArrayList<>(), 3), NEVER).refused())
            .as("a failure that was not a step's is never 'earlier'")
            .startsWith("it fails on the merged tree too - ");
        assertThat(JourneysOfAPlan.judgeCorrection(replacedAt(STARTS_ON_THE_OLD_PAGE, 1), PATH,
            STARTS_ON_THE_NEW_SCREEN, failingAt(new ArrayList<>(), -1), NEVER).refused())
            .as("nor is a correction's failure that names no step 'later'")
            .startsWith("it fails on the merged tree too");
    }

    @Test
    void aCorrectionThatGetsFurtherIsStillHeldToTheOtherGuards() {
        JourneyFile.Result replaced = replacedAt(STARTS_ON_THE_OLD_PAGE, 1);
        List<List<JourneyFile.Journey>> merged = new ArrayList<>();

        assertThat(JourneysOfAPlan.judgeCorrection(replaced, PATH, ONLY_LOOKS, NEVER, NEVER)
            .refused()).startsWith("it asks for less than the journey it replaces");
        assertThat(JourneysOfAPlan.judgeCorrection(replaced, PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(merged, 2), failingAt(new ArrayList<>(), 0, 1, 1)).refused())
            .contains("passes on the application as it was before the story");
        assertThat(JourneysOfAPlan.judgeCorrection(replaced, PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(merged, 2), failingAt(new ArrayList<>(), 1, 0, 1)).refused())
            .contains("what its last step expects", "already on the entry page");
        assertThat(JourneysOfAPlan.judgeCorrection(replaced, PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(merged, 2), journeys -> new JourneyRunner.Outcome("no browser", null,
                List.of())).refused())
            .startsWith("it could not be made on the tree the run started from");
    }

    /** Item 2 of section 75, for a correction: its first look must not be at the old page. */
    @Test
    void aCorrectionThatBeginsByExpectingWhatTheStartingApplicationShowsIsRefused() {
        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(
            replacedAt(STARTS_ON_THE_OLD_PAGE, 1), PATH, STARTS_ON_THE_NEW_SCREEN,
            failingAt(new ArrayList<>(), 0), failingAt(new ArrayList<>(), 2, 1, 0));

        assertThat(judged.taken()).isFalse();
        assertThat(judged.refused()).contains("step 1 (`expect visible role=heading[name=\"Books"
            + "\"]`) is already true on the entry page of the application as it is BEFORE the "
            + "story");
    }

    @Test
    void afterACorrectionThatGetsFurtherTheWorkersAreNextThenTheAuthorThenTheStop() {
        Task task = task();
        JourneyFile.Result further = replacedAt(STARTS_ON_THE_NEW_SCREEN, 2);
        JourneysOfAPlan.recordReview(task, PATH, 1);
        task.setJourneySentBack(true);

        assertThat(JourneysOfAPlan.afterFurther(task, further))
            .as("the repair round is unused: no model is asked").isEqualTo(
                JourneysOfAPlan.NextMove.WORKERS);
        task.setJourneyRepairAttempted(true);
        assertThat(JourneysOfAPlan.afterFurther(task, further))
            .as("the round is used; one review is left and the step is later than the one "
                + "its author saw").isEqualTo(JourneysOfAPlan.NextMove.AUTHOR_AGAIN);
        assertThat(JourneysOfAPlan.afterFurther(task, replacedAt(STARTS_ON_THE_NEW_SCREEN, 1)))
            .as("not later than at its last review").isEqualTo(JourneysOfAPlan.NextMove.PARK);
        JourneysOfAPlan.recordReview(task, PATH, 2);
        assertThat(JourneysOfAPlan.afterFurther(task, replacedAt(STARTS_ON_THE_NEW_SCREEN, 4)))
            .as("reviewed twice").isEqualTo(JourneysOfAPlan.NextMove.PARK);
        assertThat(JourneysOfAPlan.afterFurther(task,
            List.of(replacedAt(STARTS_ON_THE_NEW_SCREEN, 4)))).isEqualTo(
                JourneysOfAPlan.NextMove.PARK);
    }

    /**
     * The whole table of section 75 for run 101, walked with no model and no browser: every
     * move uses up a mark, so the walk ends, whatever the browser says next.
     */
    @Test
    void theRunOfSection75EndsAfterTwoReviewsAndOneRepairRoundWhateverFails() {
        Task task = task();
        List<String> moves = new ArrayList<>();
        JourneyFile.Result fails = replacedAt(STARTS_ON_THE_OLD_PAGE, 1);
        int step = 1;
        for (int integration = 0; integration < 10; integration++) {
            if (JourneysOfAPlan.goesToItsAuthor(task, fails)) {
                // The author corrects, and its correction always gets one step further.
                JourneysOfAPlan.recordReview(task, PATH, fails.step());
                task.setJourneySentBack(true);
                fails = replacedAt(STARTS_ON_THE_NEW_SCREEN, ++step);
                JourneysOfAPlan.NextMove move = JourneysOfAPlan.afterFurther(task, fails);
                moves.add("review:" + move);
                if (move == JourneysOfAPlan.NextMove.PARK) {
                    break;
                }
                if (move == JourneysOfAPlan.NextMove.AUTHOR_AGAIN) {
                    continue;
                }
            } else if (task.journeyRepairAttempted()) {
                moves.add("stop");
                break;
            }
            // The workers' round: it moves the failing step on by one as well.
            task.setJourneyRepairAttempted(true);
            fails = replacedAt(STARTS_ON_THE_NEW_SCREEN, ++step);
            moves.add("repair");
        }

        assertThat(moves).containsExactly("review:WORKERS", "repair", "review:PARK");
        assertThat(JourneysOfAPlan.reviewsOf(task, PATH)).isEqualTo(2);
    }

    @Test
    void whatIsSaidAboutACorrectionThatGetsFurther() {
        JourneyFile.Result replaced = replacedAt(STARTS_ON_THE_OLD_PAGE, 1);
        JourneyFile.Result further = new JourneyFile.Result(journey(STARTS_ON_THE_NEW_SCREEN),
            false, "step 2 of 5 failed: `click role=button[name=\"Add book\"]` - Timeout.",
            "heading \"Books\"", 2);
        Task task = task();
        task.setJourneyReviewNote(JourneysOfAPlan.furtherNote(replaced, further,
            "The first step looked at the old page."));

        assertThat(task.journeyReviewNote()).contains("The first step looked at the old page.",
            "at step 2 of 5 where the journey it replaces failed at step 1 of 5",
            "it is taken and replaces the journey", "step 2 of 5 failed");
        assertThat(JourneysOfAPlan.repairEvidenceAfterCorrection(List.of(further)))
            .as("the workers get the corrected journey, step by step, and the new step")
            .contains("1. expect visible role=heading[name=\"Books\"]",
                "It failed: step 2 of 5 failed", "as its author CORRECTED it",
                "must be on the screen exactly as the journey writes it")
            .doesNotContain("Nothing here yet");
        assertThat(JourneysOfAPlan.furtherAndNothingLeft(task, List.of(further)))
            .contains("gets further than the journey it replaces and still fails",
                "committed with the run's tests", PATH, "step 2 of 5 failed",
                "The first step looked at the old page.", "No worker was started",
                "one repair round is used");
        FinalIntegrator.SentBack back = FinalIntegrator.SentBack.toTheWorkers(List.of(further));
        assertThat(back.corrected()).as("no integration in between").isFalse();
        assertThat(back.disowned()).isNull();
        assertThat(back.further()).containsExactly(further);
        assertThat(FinalIntegrator.SentBack.STANDS.further()).isEmpty();
    }

    // ---- section 75: a first step that asserts the starting application -------------------

    @Test
    void aDraftThatBeginsByExpectingWhatTheStartPageShowsIsNotKeptAndIsTriedOnce()
            throws Exception {
        List<List<JourneyFile.Journey>> tried = new ArrayList<>();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1, 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", STARTS_ON_THE_OLD_PAGE));
                case 3 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", STARTS_ON_THE_NEW_SCREEN));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "It looked at the old page."));
            })) {
            TestAuthorClient author = author(llm);
            TestAuthorClient.JourneyReviewed reviewed;
            // On the start tree the old page's text is there and the new heading is not.
            try (TestAuthorClient.Scope startPage = author.tryingOnTheStartTreeWith(journeys -> {
                tried.add(journeys);
                return new JourneyRunner.Outcome(null, null, journeys.stream().map(one ->
                    new JourneyFile.Result(one, one.steps().get(0).describe()
                        .equals("expect visible text=Nothing here yet"), null)).toList());
            })) {
                reviewed = author.reviewFailedJourney(repo, task(), null, PATH, WRITTEN,
                    whatHappened());
            }

            assertThat(llm.sessionRequests.get(1)).as("said in one line, from the real page")
                .contains("NOT KEPT. step 1 (`expect visible text=Nothing here yet`) is already "
                    + "true on the entry page of the application as it is BEFORE the story",
                    "Take it out, or expect what the story adds");
            assertThat(llm.sessionRequests.get(2)).as("the same draft again is not an answer")
                .contains("NOT KEPT. step 1");
            assertThat(llm.sessionRequests.get(3)).contains("VALID - kept");
            assertThat(reviewed.corrected()).isEqualTo(STARTS_ON_THE_NEW_SCREEN);
            assertThat(tried).as("one browser run per expectation, not per draft").hasSize(2);
            assertThat(tried.get(0)).hasSize(1);
            assertThat(tried.get(0).get(0).steps()).hasSize(1);
        }
    }

    @Test
    void aDraftIsKeptWhenTheStartPageCouldNotBeReadOrItBeginsByDoingSomething()
            throws Exception {
        List<List<JourneyFile.Journey>> tried = new ArrayList<>();
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", CORRECTED));
                case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", STARTS_ON_THE_NEW_SCREEN));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "kept"));
            })) {
            TestAuthorClient author = author(llm);
            try (TestAuthorClient.Scope startPage = author.tryingOnTheStartTreeWith(journeys -> {
                tried.add(journeys);
                return new JourneyRunner.Outcome("no browser in the container", null, List.of());
            })) {
                author.reviewFailedJourney(repo, task(), null, PATH, WRITTEN, whatHappened());
            }

            assertThat(llm.sessionRequests.get(1)).as("it begins with a click: nothing is tried")
                .contains("VALID - kept");
            assertThat(llm.sessionRequests.get(2)).as("nothing is concluded from a page that "
                + "was not read").contains("VALID - kept");
            assertThat(tried).hasSize(1);
        }
    }

    /** Section 75: the journey of run 101 named roles without `role=` from its third step on. */
    @Test
    void aDraftThatNamesARoleWithoutItsPrefixIsNotKept() throws Exception {
        String bare = CORRECTED.replace("role=textbox[name=\\\"Title\\\"]",
            "textbox[name=\\\"Title\\\"]");
        try (ScriptedAgentLlm llm = new ScriptedAgentLlm(conversation -> "{}",
            (turn, conversation) -> switch (turn) {
                case 1 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", bare));
                case 2 -> ScriptedAgentLlm.Turn.call("check_journey",
                    Map.of("path", PATH, "content", CORRECTED));
                default -> ScriptedAgentLlm.Turn.call("report_done",
                    Map.of("verdict", "JOURNEY_WRONG", "reason", "The role had no prefix."));
            })) {
            TestAuthorClient.JourneyReviewed reviewed = author(llm).reviewFailedJourney(repo,
                task(), null, PATH, WRITTEN, whatHappened());

            assertThat(llm.sessionRequests.get(1)).contains("NOT A VALID JOURNEY - not kept.",
                "step 2: `textbox[name=", "names a role without `role=`",
                "Write `role=textbox[name=");
            assertThat(reviewed.corrected()).isEqualTo(CORRECTED);
        }
    }

    @Test
    void theRedCheckNotesAJourneyThatBeginsOnWhatWasThere() {
        JourneyFile.Journey journey = journey(STARTS_ON_THE_OLD_PAGE);
        List<JourneyFile.Journey> alone =
            com.swarmcoder.verify.JourneyExpectations.leadingAlone(journey);

        assertThat(JourneysOfAPlan.startsOnWhatWasThere(journey,
            List.of(new JourneyFile.Result(alone.get(0), true, null))))
            .startsWith("NOTE - \"A book is added and listed\": step 1 (`expect visible "
                + "text=Nothing here yet`) is already true on the entry page")
            .contains("goes back to its author then");
        assertThat(JourneysOfAPlan.startsOnWhatWasThere(journey,
            List.of(new JourneyFile.Result(alone.get(0), false, "Timeout")))).isNull();
    }

    @Test
    void theBrowsersResultNamesTheStepThatFailed() {
        com.swarmcoder.domain.PageCheck check = new com.swarmcoder.domain.PageCheck("http://x/",
            true, List.of(), List.of(new com.swarmcoder.domain.AssertionResult("step 2: fill",
                false, "timeout")), null);

        assertThat(JourneyFile.resultOf(journey(WRITTEN), check).step()).isEqualTo(2);
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
