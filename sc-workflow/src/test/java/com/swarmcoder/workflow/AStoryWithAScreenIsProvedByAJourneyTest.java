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
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.JourneyFile;
import com.swarmcoder.verify.JourneyRunner;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 63, the part that needs no model: which tasks of a plan change a screen, what happens
 * when the project cannot start its application, the backstop when no journey was written, and
 * the journeys' red check.
 */
class AStoryWithAScreenIsProvedByAJourneyTest {

    private static final String DIR = "shop-server/src/test/java/swarm";
    private static final String JOURNEY_PATH = DIR + "/accept/add-order.journey.yaml";
    private static final String JOURNEY = """
        journey: An order is added from the orders screen
        steps:
          - click: "role=link[name=\\"Orders\\"]"
          - click: "role=button[name=\\"Add order\\"]"
          - expectVisible: "role=textbox[name=\\"Customer\\"]"
        """;

    private static final BrowserOnlyCode.Survey SURVEY = new BrowserOnlyCode.Survey(
        List.of(new BrowserOnlyCode.Module("shop-client", "declares a browser runtime",
            "compiled to JavaScript", List.of("com.shop.client"))),
        List.of("shop-shared", "shop-server"), Map.of());

    private final Task service = task("Order service",
        Set.of("shop-server/src/main/java/com/shop/server/OrderServiceImpl.java"), true);
    private final Task screen = task("Orders screen",
        Set.of("shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java",
            "shop-client/pom.xml"), true);

    @AfterEach
    void switchBackOn() {
        System.clearProperty(JourneyFile.SWITCH);
    }

    private static VerifySpec contract(boolean serves) throws Exception {
        return VerifySpecLoader.parse("toolchain: maven\n" + (serves
            ? "browser:\n  serve: \"java -jar shop.jar --port {PORT}\"\n  checks: []\n" : ""));
    }

    @Test
    void theTaskThatWritesIntoTheBrowserOnlyModuleIsTheOneAskedForAJourney() throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service, screen),
            task -> !task.criteria().isEmpty(), SURVEY, contract(true));

        assertThat(decision.stop()).isNull();
        assertThat(decision.storyHasAScreen()).isTrue();
        assertThat(decision.screens()).extracting(s -> s.task().title())
            .containsExactly("Orders screen");
        assertThat(decision.authoring()).containsExactly(screen);
    }

    // --- section 64, live run 89: a server-only write set, the screen drawn in the browser ----

    /** What the object graph answers for run 89's shape: the client calls the written service. */
    private static final java.util.function.Function<String, List<String>> CLIENT_CALLS_THE_SERVICE =
        path -> path.endsWith("OrderServiceImpl.java")
            ? List.of("shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java uses "
                + "OrderService, which OrderServiceImpl is")
            : List.of();

    @Test
    void aServerOnlyPlanWhoseCodeTheBrowserCodeUsesIsAskedForAJourneyAndCannotAnswerOtherwise()
            throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service),
            task -> true, SURVEY, contract(true), CLIENT_CALLS_THE_SERVICE);

        assertThat(decision.stop()).isNull();
        assertThat(decision.storyHasAScreen()).as("the graph says a screen shows it").isTrue();
        assertThat(decision.authoring()).containsExactly(service);
        assertThat(decision.mayWaive()).isFalse();
        assertThat(decision.through(service)).containsExactly(
            "shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java uses "
                + "OrderService, which OrderServiceImpl is");

        service.setJourneyWaiver("only the server changes");
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY))
            .as("'no visible effect' is not an answer where browser code uses the change")
            .contains("no journey was written")
            .contains("task 'Order service' may write "
                + "shop-server/src/main/java/com/shop/server/OrderServiceImpl.java")
            .contains("which code that runs only in a browser uses")
            .contains("uses OrderService, which OrderServiceImpl is");

        service.setJourneyPaths(List.of(JOURNEY_PATH));
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY)).isNull();
    }

    @Test
    void theSameServerOnlyPlanStopsBeforeAnyTestWhenTheContractCannotStartTheApplication()
            throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service),
            task -> true, SURVEY, contract(false), CLIENT_CALLS_THE_SERVICE);

        assertThat(decision.authoring()).isEmpty();
        assertThat(decision.stop())
            .contains("task 'Order service' may write")
            .contains("which code that runs only in a browser uses")
            .contains("has no `browser.serve` line");
    }

    @Test
    void inAProjectWhoseApplicationIsStartedEveryStoryIsAskedAndTheOnlyWayOutIsOnRecord()
            throws Exception {
        // No task writes a screen and the graph shows no browser code using what is written.
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service),
            task -> true, SURVEY, contract(true), path -> List.of());

        assertThat(decision.storyHasAScreen()).isFalse();
        assertThat(decision.journeyAsked()).isTrue();
        assertThat(decision.authoring()).containsExactly(service);
        assertThat(decision.mayWaive()).isTrue();
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY))
            .as("neither a journey nor the recorded answer: the run does not go on")
            .contains("every story is proved by a journey")
            .contains("'Order service'")
            .contains("noVisibleEffect: <why>")
            .contains("-Dswarmcoder.verify.journeys=off");

        service.setJourneyWaiver("the ledger's file format changes; no screen reads it");
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY)).isNull();

        service.setJourneyWaiver(null);
        service.setJourneyPaths(List.of(JOURNEY_PATH));
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY)).isNull();
    }

    @Test
    void withNoApplicationToStartAndNoBrowserCodeUsingTheChangeNothingIsAsked() throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service),
            task -> true, SURVEY, contract(false), path -> List.of());

        assertThat(decision.stop()).isNull();
        assertThat(decision.journeyAsked()).isFalse();
        assertThat(JourneysOfAPlan.missing(decision, List.of(service), SURVEY)).isNull();

        JourneysOfAPlan.Decision noChecks = JourneysOfAPlan.decide(List.of(service),
            task -> false, SURVEY, contract(true), path -> List.of());
        assertThat(noChecks.journeyAsked()).as("no task has tests written: nobody to ask")
            .isFalse();
    }

    @Test
    void aScreenTaskWithNoCheckOfItsOwnLeavesTheJourneyToTheTasksThatAnswerForTheStory()
            throws Exception {
        Task enabler = task("Orders screen",
            Set.of("shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java"), false);

        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service, enabler),
            task -> !task.criteria().isEmpty(), SURVEY, contract(true));

        assertThat(decision.authoring()).containsExactly(service);
    }

    @Test
    void aProjectWhoseContractCannotStartTheApplicationStopsBeforeAnyTestIsWritten()
            throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service, screen),
            task -> true, SURVEY, contract(false));

        assertThat(decision.authoring()).isEmpty();
        assertThat(decision.stop())
            .as("a plain stop that says what to add, not a silent skip")
            .contains("task 'Orders screen' may write "
                + "shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java")
            .contains("shop-client runs only in a browser")
            .contains("has no `browser.serve` line")
            .contains("No test was written and no worker was started")
            .contains("-Dswarmcoder.verify.journeys=off")
            .doesNotContain("shop-client/pom.xml");
    }

    @Test
    void switchedOffNothingIsAskedAndNothingStops() throws Exception {
        System.setProperty(JourneyFile.SWITCH, "off");

        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service, screen),
            task -> true, SURVEY, contract(false));

        assertThat(decision.stop()).isNull();
        assertThat(decision.storyHasAScreen()).isFalse();
    }

    @Test
    void aPlanThatChangesAScreenAndClaimsNoJourneyDoesNotGoOn() throws Exception {
        JourneysOfAPlan.Decision decision = JourneysOfAPlan.decide(List.of(service, screen),
            task -> true, SURVEY, contract(true));

        assertThat(JourneysOfAPlan.missing(decision, List.of(service, screen), SURVEY))
            .contains("no journey was written")
            .contains("task 'Orders screen' may write")
            .contains("'Orders screen'");

        screen.setJourneyPaths(List.of(JOURNEY_PATH));
        assertThat(JourneysOfAPlan.missing(decision, List.of(service, screen), SURVEY))
            .as("one task of the plan claims a journey").isNull();
    }

    @Test
    void theClaimedJourneysAreReadFromTheTestsCommitAndAMissingOneIsNamed() {
        screen.setJourneyPaths(List.of(JOURNEY_PATH, DIR + "/accept/gone.journey.yaml"));
        List<String> unreadable = new ArrayList<>();

        List<JourneysOfAPlan.Claimed> claimed = JourneysOfAPlan.claimed(List.of(service, screen),
            path -> path.equals(JOURNEY_PATH) ? JOURNEY : null, unreadable);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).task()).isSameAs(screen);
        assertThat(claimed.get(0).journey().steps()).hasSize(3);
        assertThat(unreadable).containsExactly(DIR + "/accept/gone.journey.yaml");
    }

    @Test
    void aJourneyMustFailOnTheStartTreeAndOneThatCannotBeMadeStopsTheRunThere() {
        screen.setJourneyPaths(List.of(JOURNEY_PATH));
        List<JourneysOfAPlan.Claimed> claimed = JourneysOfAPlan.claimed(List.of(screen),
            path -> JOURNEY, new ArrayList<>());
        JourneyFile.Journey journey = claimed.get(0).journey();

        JourneyRunner.Outcome red = new JourneyRunner.Outcome(null, null, List.of(
            new JourneyFile.Result(journey, false, "step 2 of 3 failed: `click "
                + "role=button[name=\"Add order\"]` - Timeout 10000ms exceeded.")));
        assertThat(JourneysOfAPlan.notRed(claimed, red, null))
            .as("failing before the story is built is what a journey must do").isNull();

        JourneyRunner.Outcome green = new JourneyRunner.Outcome(null, null,
            List.of(new JourneyFile.Result(journey, true, null)));
        assertThat(JourneysOfAPlan.notRed(claimed, green, null))
            .contains("A journey must FAIL on the application as it is before the story is built")
            .contains("An order is added from the orders screen");

        JourneyRunner.Outcome noBrowser = new JourneyRunner.Outcome(
            "the container this tree was checked in has no browser in it", null, List.of());
        assertThat(JourneysOfAPlan.notRed(claimed, noBrowser, null))
            .contains("could not be made on the code the run starts from")
            .contains("has no browser in it")
            .contains("Nothing was run on this PC instead");

        JourneyRunner.Outcome dead = new JourneyRunner.Outcome(null,
            "the application did not start: nothing answered http://127.0.0.1:8080/", List.of());
        assertThat(JourneysOfAPlan.notRed(claimed, dead, "$ mvn -o verify (exit 1)\nBUILD FAILURE"))
            .contains("The application did not start on the code the run starts from")
            .contains("which FAILED on that tree").contains("BUILD FAILURE");
    }

    @Test
    void aRepairWorkerIsGivenTheFailingStepAndToldWhereToLook() {
        JourneyFile.Journey journey = JourneyFile.read(JOURNEY_PATH, JOURNEY).journey();

        String evidence = JourneysOfAPlan.repairEvidence(List.of(new JourneyFile.Result(journey,
            false, "step 2 of 3 failed: `click role=button[name=\"Add order\"]` - Timeout "
                + "10000ms exceeded. The 1 step(s) before it were done: click "
                + "role=link[name=\"Orders\"].")));

        assertThat(evidence)
            .contains("a journey failed in a real browser")
            .contains("journey \"An order is added from the orders screen\" (" + JOURNEY_PATH + ")")
            .contains("step 2 of 3 failed: `click role=button[name=\"Add order\"]`")
            .contains("acceptance_test")
            .contains("you cannot change it");
    }

    private static Task task(String title, Set<String> writeSet, boolean withACheck) {
        return new Task(UUID.randomUUID(), 1, title, title, writeSet, Set.of(),
            withACheck ? List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "an order can be added", "swarm.accept.OrdersTest#addsAnOrder")) : List.of(),
            DIR, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
