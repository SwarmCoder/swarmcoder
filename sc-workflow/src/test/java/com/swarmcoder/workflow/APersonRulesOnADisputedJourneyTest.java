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

import com.swarmcoder.domain.InstructedRepair;
import com.swarmcoder.domain.JourneyVerdict;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 88, after the live supervised build of 2026-10-12: a person or the supervisor can
 * rule on a journey a failed final integration stopped on.
 *
 * <p>A story added a rating to books. Its journey typed the rating into a text box and then
 * typed one out of range to see an error; the screen was built as a group of radio buttons.
 * The journey's author called the journey wrong and handed in a correction that clicked a radio
 * button and left the out-of-range case out. That correction was rightly not taken, no worker
 * was started, and the only answer offered was {@code retry}. In the same stop a journey
 * delivered with an earlier story failed too, and nothing could be offered for it either.
 *
 * <p>What is checked here is everything that needs no browser and no model: how a correction
 * is judged with and without a person's authority and what is recorded as dropped, which task
 * is named for an earlier story's journey, and what the stop then offers. What a browser would
 * say on the two trees is given as a function. The answers themselves are checked in sc-console
 * ({@code AStoppedBuildCanBeAnsweredScreenOrJourneyTest}).
 */
class APersonRulesOnADisputedJourneyTest {

    private static final String PATH = "src/test/java/swarm/accept/rating.journey.yaml";
    private static final String EARLIER = "src/test/java/swarm/accept/add-book.journey.yaml";

    /** The journey as written: a rating is typed, and one that is no rating is refused. */
    private static final String TYPED = """
        journey: A book is rated, and a rating that is none is refused
        steps:
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - fill: "role=textbox[name=\\"Rating\\"]"
            value: "4"
          - click: "role=button[name=\\"Save\\"]"
          - expectVisible: "text=4 of 5"
          - fill: "role=textbox[name=\\"Rating\\"]"
            value: "9"
          - click: "role=button[name=\\"Save\\"]"
          - expectVisible: "text=A rating is a number from 1 to 5"
        proves:
          - criterion: 1
            steps: "1-5"
          - criterion: 2
            steps: "6-8"
        """;
    /** Its author's correction: a radio button is clicked, the out-of-range case is gone. */
    private static final String CLICKED = """
        journey: A book is rated
        steps:
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - click: "role=radio[name=\\"4 stars\\"]"
          - click: "role=button[name=\\"Save\\"]"
          - expectVisible: "text=4 of 5"
        proves:
          - criterion: 1
            steps: "1-5"
          - criterion: 2
            notOnScreen: "the radio group cannot enter a rating that is none"
        """;
    private static final List<String> CRITERIA = List.of("A person types a rating for a book",
        "A person is told when what they typed is not a rating");

    @AfterEach
    void clearSettings() {
        System.clearProperty(InstructedRepair.LIMIT_PROPERTY);
        System.clearProperty(JourneyVerdict.LIMIT_PROPERTY);
    }

    // ---- a correction that asks for less ---------------------------------------------------

    @Test
    void withoutAPersonsAuthorityACorrectionThatAsksForLessIsStillNotTaken() {
        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(failedAt(3), PATH,
            CLICKED, NEVER, NEVER);

        assertThat(judged.taken()).isFalse();
        assertThat(judged.refused()).startsWith("it asks for less than the journey it replaces")
            .contains("4 step(s) that change something where the journey it replaces has 6")
            .contains("fills 1 field(s) where the journey it replaces fills 3");
        assertThat(judged.dropped()).isEmpty();
    }

    @Test
    void onTheAnswerJourneyItIsTakenWhenTheBrowserBearsItOutAndWhatItDroppedIsOnTheResult() {
        List<List<JourneyFile.Journey>> merged = new ArrayList<>();
        List<List<JourneyFile.Journey>> start = new ArrayList<>();

        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(failedAt(3), PATH,
            CLICKED, failingAt(merged, 0), failingAt(start, 1, 1), true, CRITERIA);

        assertThat(judged.refused()).isNull();
        assertThat(judged.taken()).isTrue();
        assertThat(judged.asksForLess()).isTrue();
        assertThat(merged).as("the same browser run on the merged tree").hasSize(1);
        assertThat(start).as("and the same one on the tree the run started from").hasSize(1);
        assertThat(start.get(0)).as("the journey and its last expectation alone").hasSize(2);
        assertThat(judged.dropped()).containsExactly(
            "step 3 is no longer made: fill role=textbox[name=\"Rating\"] = \"4\"",
            "step 6 is no longer made: fill role=textbox[name=\"Rating\"] = \"9\"",
            "step 7 is no longer made: click role=button[name=\"Save\"]",
            "the field role=textbox[name=\"Rating\"] is no longer filled",
            "criterion 2 \"A person is told when what they typed is not a rating\" is no longer "
                + "proved on screen (it was by steps 6-8; the correction says it is not on a "
                + "screen: the radio group cannot enter a rating that is none)");

        String recorded = JourneysOfAPlan.lesserCorrection(PATH, "supervisor",
            "drop the out-of-range case\nthe radios cannot enter it", judged.dropped());
        assertThat(recorded).contains(PATH).contains("instruction of the supervisor")
            .contains("asks for LESS").contains("step 6 is no longer made")
            .contains("criterion 2").contains("no longer proved on screen")
            .contains("drop the out-of-range case the radios cannot enter it");
    }

    @Test
    void theAuthorityCoversAskingForLessAndNothingElse() {
        assertThat(JourneysOfAPlan.judgeCorrection(failedAt(3), PATH, CLICKED,
                failingAt(new ArrayList<>(), 0), failingAt(new ArrayList<>(), 0, 0), true,
                CRITERIA).refused())
            .as("it passes before the story: it shows nothing about what the story adds")
            .startsWith("it passes on the application as it was before the story");
        assertThat(JourneysOfAPlan.judgeCorrection(failedAt(3), PATH, CLICKED,
                failingAt(new ArrayList<>(), 3), NEVER, true, CRITERIA).refused())
            .as("it gets no further on the merged tree than the journey it replaces")
            .startsWith("it fails on the merged tree too");
        assertThat(JourneysOfAPlan.judgeCorrection(failedAt(3), PATH, "steps: nothing", NEVER,
                NEVER, true, CRITERIA).refused())
            .startsWith("it is not a well-formed journey");
        assertThat(JourneysOfAPlan.judgeCorrection(failedAt(3), PATH, CLICKED,
                failingAt(new ArrayList<>(), 4), failingAt(new ArrayList<>(), 1, 1), true,
                CRITERIA))
            .as("one that gets further and still fails is taken, as section 75 takes any")
            .satisfies(judged -> {
                assertThat(judged.getsFurther()).isTrue();
                assertThat(judged.asksForLess()).isTrue();
            });
    }

    @Test
    void aCorrectionThatKeepsEveryUseDropsNothingAlsoOnAnInstruction() {
        JourneysOfAPlan.Correction judged = JourneysOfAPlan.judgeCorrection(failedAt(3), PATH,
            TYPED.replace("role=button[name=\\\"Add book\\\"]", "role=button[name=\\\"New\\\"]")
                .replace("A book is rated,", "A book is given a rating,"),
            failingAt(new ArrayList<>(), 0), failingAt(new ArrayList<>(), 1, 1), true, CRITERIA);

        assertThat(judged.taken()).isTrue();
        assertThat(judged.dropped()).as("a step written differently is listed: the record errs "
            + "towards saying more").containsExactly(
                "step 1 is no longer made: click role=button[name=\"Add book\"]");
    }

    @Test
    void theAuthorIsToldTheQuestionIsAnsweredAndWhatTheInstructionIs() {
        String shown = JourneysOfAPlan.instructedEvidence("leave the out-of-range case out",
            "supervisor", "journey ... step 3 of 8 failed");

        assertThat(shown).startsWith("THE QUESTION OF THIS REVIEW HAS ALREADY BEEN ANSWERED by "
            + "the supervisor").contains("THE JOURNEY IS WRONG")
            .contains("THE INSTRUCTION:\nleave the out-of-range case out")
            .contains("must still fail on the application as it was before the story")
            .endsWith("journey ... step 3 of 8 failed");
        assertThat(JourneysOfAPlan.instructionNotMet(task("Rate a book"), List.of(PATH),
            List.of("its correction was not taken"))).contains("No worker was started")
            .contains("'Rate a book'").contains("its correction was not taken")
            .contains(JourneyVerdict.LIMIT_PROPERTY);
    }

    // ---- a journey delivered with an earlier story -----------------------------------------

    private static final String ADD_A_BOOK = """
        journey: A book is added and listed
        steps:
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - click: "role=button[name=\\"Save\\"]"
          - expectVisible: "text=The Hobbit"
        """;

    @Test
    void theOneTaskWhoseChangeWroteAFileHoldingANameTheJourneyLooksForIsNamed() {
        Task server = task("Store a rating");
        Task screen = task("Rate a book on the form");
        Map<Task, List<String>> changed = Map.of(
            server, List.of("server/src/main/java/app/Ratings.java", "pom.xml"),
            screen, List.of("web/src/main/java/app/BookForm.java",
                "web/src/test/java/app/BookFormTest.java"));
        Map<String, List<String>> texts = Map.of(
            "server/src/main/java/app/Ratings.java", List.of("rating", "a rating is 1 to 5"),
            "web/src/main/java/app/BookForm.java", List.of("title", "add book", "save"));

        JourneysOfAPlan.ScreenTask named = JourneysOfAPlan.screenTaskOf(
            JourneyFile.read(EARLIER, ADD_A_BOOK).journey(), List.of(server, screen),
            changed::get, file -> texts.getOrDefault(file, List.of()), file -> false);

        assertThat(named.task()).isSameAs(screen);
        assertThat(named.why()).contains("the one task of this build")
            .contains("web/src/main/java/app/BookForm.java holds \"add book\"");
    }

    @Test
    void ofSeveralTasksHoldingANameTheOnlyOneThatAlsoWroteAScreenIsNamed() {
        Task server = task("Store a rating");
        Task screen = task("Rate a book on the form");
        Map<Task, List<String>> changed = Map.of(
            server, List.of("server/src/main/java/app/Messages.java"),
            screen, List.of("web/src/main/resources/book.html"));
        Function<String, List<String>> texts = file -> List.of("save", "title");

        JourneysOfAPlan.ScreenTask named = JourneysOfAPlan.screenTaskOf(
            JourneyFile.read(EARLIER, ADD_A_BOOK).journey(), List.of(server, screen),
            changed::get, texts, file -> file.endsWith(".html"));
        JourneysOfAPlan.ScreenTask neither = JourneysOfAPlan.screenTaskOf(
            JourneyFile.read(EARLIER, ADD_A_BOOK).journey(), List.of(server, screen),
            changed::get, texts, file -> false);

        assertThat(named.task()).isSameAs(screen);
        assertThat(named.why()).contains("of the 2 tasks").contains("only one that also wrote "
            + "a screen");
        assertThat(neither.task()).as("two tasks and nothing to tell them apart").isNull();
        assertThat(neither.why()).contains("2 tasks of this build wrote files that hold names")
            .contains("'Store a rating'").contains("'Rate a book on the form'")
            .contains("no one task can be named");
    }

    @Test
    void withNoNameHeldTheOnlyTaskThatWroteAScreenIsNamedAndOtherwiseNobody() {
        Task server = task("Store a rating");
        Task screen = task("Rate a book on the form");
        Map<Task, List<String>> changed = Map.of(
            server, List.of("server/src/main/java/app/Ratings.java"),
            screen, List.of("web/src/main/resources/book.html"));
        JourneyFile.Journey journey = JourneyFile.read(EARLIER, ADD_A_BOOK).journey();

        JourneysOfAPlan.ScreenTask byScreen = JourneysOfAPlan.screenTaskOf(journey,
            List.of(server, screen), changed::get, file -> List.of("stars"),
            file -> file.endsWith(".html"));
        JourneysOfAPlan.ScreenTask nobody = JourneysOfAPlan.screenTaskOf(journey,
            List.of(server, screen), changed::get, file -> List.of("stars"), file -> false);
        JourneysOfAPlan.ScreenTask noChange = JourneysOfAPlan.screenTaskOf(journey,
            List.of(server, screen), task -> List.of(), file -> List.of("save"), file -> true);

        assertThat(byScreen.task()).isSameAs(screen);
        assertThat(byScreen.why()).contains("only task of this build whose chosen change wrote "
            + "a screen (web/src/main/resources/book.html)");
        assertThat(nobody.task()).isNull();
        assertThat(nobody.why()).isEqualTo("no task of this build wrote a screen or a file that "
            + "holds a name the journey looks for");
        assertThat(noChange.task()).as("a task with no chosen change wrote nothing").isNull();
    }

    // ---- what the stop offers --------------------------------------------------------------

    @Test
    void aJourneyItsAuthorDisownedOffersBothVerdictsAndTheEarlierJourneyNamesItsTask() {
        Task server = chosen(task("Store a rating"));
        Task screen = chosen(task("Rate a book on the form"));
        screen.setJourneyPaths(List.of(PATH));
        FinalIntegrator.JourneyDispute dispute = new FinalIntegrator.JourneyDispute(screen.id(),
            List.of(PATH), List.of(new FinalIntegrator.EarlierJourney(EARLIER, screen.id(),
                "it is the one task of this build whose chosen change wrote a file that holds "
                    + "a name the journey looks for (web/BookForm.java holds \"add book\")")));

        JourneysOfAPlan.Offer offer =
            JourneysOfAPlan.offerFor(List.of(server, screen), dispute, false);

        assertThat(offer.screen()).containsOnlyKeys(screen.id());
        assertThat(offer.screen().get(screen.id())).containsExactly(PATH, EARLIER);
        assertThat(offer.journeyOf()).isEqualTo(screen.id());
        assertThat(offer.correctable()).containsExactly(PATH);
        assertThat(offer.disputedFor(screen)).containsExactly(PATH, EARLIER);
        assertThat(offer.disputedFor(server)).isEmpty();
        assertThat(offer.paragraph()).as("what no answer's own description carries comes first, "
                + "inside what a supervisor is shown of a long question")
            .startsWith("WHAT THIS QUESTION CAN BE ANSWERED. The journey " + EARLIER
                + ", delivered with an earlier story, is claimed by no task of this build. "
                + "`screen` with text gives task 'Rate a book on the form' one repair round "
                + "for it: it is the one task of this build")
            .contains(" `retry`: the stage runs again as it is.")
            .contains("`screen` with text: the journey is right and the screen is wrong - "
                + "task 'Rate a book on the form' gets one repair round")
            .contains("whatever the journey's author answered")
            .contains("`journey` with text: the journey is wrong - its author corrects it")
            .contains("may ask for less, which is recorded and shown with the delivery")
            .doesNotContain("\n");
    }

    @Test
    void anEarlierJourneyNoTaskCanBeNamedForSaysSoInOneSentence() {
        Task screen = chosen(task("Rate a book on the form"));
        FinalIntegrator.JourneyDispute dispute = new FinalIntegrator.JourneyDispute(null,
            List.of(), List.of(new FinalIntegrator.EarlierJourney(EARLIER, null,
                "no task of this build wrote a screen or a file that holds a name the journey "
                    + "looks for")));

        JourneysOfAPlan.Offer offer = JourneysOfAPlan.offerFor(List.of(screen), dispute, false);

        assertThat(offer.screen()).isEmpty();
        assertThat(offer.journeyOf()).isNull();
        assertThat(offer.paragraph()).contains("Nothing can be offered for it: no task of this "
            + "build wrote a screen or a file that holds a name the journey looks for. Only "
            + "sending the story back remains for it.");
    }

    @Test
    void whatIsUsedUpOrAlreadyOfferedIsNotOfferedAgain() {
        Task screen = chosen(task("Rate a book on the form"));
        screen.setJourneyPaths(List.of(PATH));
        FinalIntegrator.JourneyDispute dispute =
            new FinalIntegrator.JourneyDispute(screen.id(), List.of(PATH), List.of());

        assertThat(JourneysOfAPlan.offerFor(List.of(screen), dispute, true).screen())
            .as("the stop already offers the owner's workers a round: repair").isEmpty();
        assertThat(JourneysOfAPlan.offerFor(List.of(screen), dispute, true).journeyOf())
            .as("the journey can still be ruled wrong").isEqualTo(screen.id());

        screen.setInstructedRepairRounds(1);
        screen.setJourneyInstructionsGiven(List.of(PATH));
        JourneysOfAPlan.Offer used = JourneysOfAPlan.offerFor(List.of(screen), dispute, false);
        assertThat(used.screen()).isEmpty();
        assertThat(used.journeyOf()).isNull();
        assertThat(used.paragraph()).isEmpty();

        System.setProperty(InstructedRepair.LIMIT_PROPERTY, "2");
        System.setProperty(JourneyVerdict.LIMIT_PROPERTY, "2");
        JourneysOfAPlan.Offer raised = JourneysOfAPlan.offerFor(List.of(screen), dispute, false);
        assertThat(raised.screen()).containsOnlyKeys(screen.id());
        assertThat(raised.journeyOf()).isEqualTo(screen.id());

        assertThat(JourneysOfAPlan.offerFor(List.of(screen), null, false))
            .as("a stop that is not about a journey").isSameAs(JourneysOfAPlan.Offer.NONE);
        Task unchosen = task("Rate a book on the form");
        assertThat(JourneysOfAPlan.offerFor(List.of(unchosen),
            new FinalIntegrator.JourneyDispute(unchosen.id(), List.of(PATH), List.of()), false)
            .screen()).as("no chosen candidate to start a repair round from").isEmpty();
    }

    // ---- fixture ---------------------------------------------------------------------------

    private static JourneyFile.Result failedAt(int step) {
        return new JourneyFile.Result(JourneyFile.read(PATH, TYPED).journey(), false,
            "step " + step + " of 8 failed: `fill role=textbox[name=\"Rating\"]` - Timeout.",
            "radio \"4 stars\"", step);
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
                    step == 0 ? null : "step " + step + " failed: Timeout.", null, step));
            }
            return new JourneyRunner.Outcome(null, null, results);
        };
    }

    private static final Function<List<JourneyFile.Journey>, JourneyRunner.Outcome> NEVER =
        journeys -> {
            throw new AssertionError("no browser is started for this correction");
        };

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of("src/main/java"), Set.of(),
            List.of(), "src/test/java/swarm", null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.DONE);
    }

    private static Task chosen(Task task) {
        task.setSelectedCandidateId(UUID.randomUUID());
        return task;
    }
}
