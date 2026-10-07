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
package com.swarmcoder.verify;

import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.domain.PageCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 63: a journey written for a story is a file beside its acceptance tests. Reading it,
 * saying what is wrong with it, deciding whether a task has a screen at all, and turning the
 * browser's result into the sentence a worker repairs from are all done with no model.
 */
class AJourneyFileIsReadAndJudgedWithNoModelTest {

    private static final String PATH = "app/src/test/java/swarm/accept/add-contact.journey.yaml";

    private static final String JOURNEY = """
        journey: A contact is added from the logbook and shown in the list
        steps:
          - click: "role=link[name=\\"Logbook\\"]"
          - click: "role=button[name=\\"Add contact\\"]"
          - fill: "role=textbox[name=\\"Call\\"]"
            value: "DL1ABC"
          - press: "Enter"
          - expectVisible: "text=DL1ABC"
        """;

    @Test
    void aWellFormedJourneyBecomesABrowserCheckThatStartsOnTheEntryPageAndLoadsNoAddress() {
        JourneyFile.Read read = JourneyFile.read(PATH, JOURNEY);

        assertThat(read.ok()).as(read.objection()).isTrue();
        assertThat(read.journey().name())
            .isEqualTo("A contact is added from the logbook and shown in the list");
        assertThat(read.journey().steps()).extracting(VerifySpec.StepSpec::describe)
            .containsExactly("click role=link[name=\"Logbook\"]",
                "click role=button[name=\"Add contact\"]", "fill role=textbox[name=\"Call\"]",
                "press Enter", "expect visible text=DL1ABC");
        VerifySpec.PageCheckSpec check = read.journey().toCheck("/");
        assertThat(check.url()).isEqualTo("/");
        assertThat(check.isJourney()).isTrue();
        assertThat(JourneyFile.isJourney(PATH)).isTrue();
        assertThat(JourneyFile.isJourney("app/src/test/java/swarm/accept/LogbookTest.java"))
            .isFalse();
    }

    @Test
    void whatIsWrongWithADraftIsSaidInWordsTheAuthorCanActOn() {
        assertThat(JourneyFile.read(PATH, """
            journey: Opens the new screen
            url: /logbook/new
            steps:
              - expectVisible: "text=New contact"
            """).objection())
            .as("an address is the one thing a journey may not have")
            .contains("`url` is not a key of a journey").contains("it has no address");

        assertThat(JourneyFile.read(PATH, """
            journey: Two things at once
            steps:
              - click: "text=Save"
                press: "Enter"
              - expectVisible: "text=Saved"
            """).objection()).contains("step 1 must do exactly one thing");

        assertThat(JourneyFile.read(PATH, """
            journey: A value nobody types
            steps:
              - click: "text=Save"
                value: "x"
              - fill: "#call"
              - goto: "/logbook"
              - expectVisible: "text=Saved"
            """).objection())
            .contains("step 1 has a `value` but does not `fill`")
            .contains("step 2 fills a field and gives no `value`")
            .contains("step 3 has `goto`, which no step has")
            .contains("No step loads an address");

        assertThat(JourneyFile.read(PATH, """
            journey: Ends on an action
            steps:
              - click: "text=Save"
            """).objection())
            .contains("A journey ends by looking");

        assertThat(JourneyFile.read(PATH, "steps: [").ok()).isFalse();
        assertThat(JourneyFile.read(PATH, "- just\n- a list\n").objection())
            .contains("must be a YAML mapping");
        assertThat(JourneyFile.read(PATH, "journey: Nothing to do\n").objection())
            .contains("`steps` is missing or empty");
        assertThat(JourneyFile.read("app/src/test/java/swarm/accept/journey.yaml", JOURNEY)
            .objection()).contains("must be named <name>.journey.yaml");
    }

    @Test
    void theBrowsersResultNamesTheStepThatFailedAndTheOnesDoneBeforeIt() {
        JourneyFile.Journey journey = JourneyFile.read(PATH, JOURNEY).journey();
        PageCheck page = new PageCheck("/", true, List.of(), List.of(
            new AssertionResult("step 1: click role=link[name=\"Logbook\"]", true, "done"),
            new AssertionResult("step 2: click role=button[name=\"Add contact\"]", false,
                "locator.click: Timeout 10000ms exceeded.\nCall log: ... (the browser was at "
                    + "/logbook)"),
            new AssertionResult("step 3: fill role=textbox[name=\"Call\"]", false, "not reached"),
            new AssertionResult("step 4: press Enter", false, "not reached"),
            new AssertionResult("step 5: expect visible text=DL1ABC", false, "not reached")),
            null);

        JourneyFile.Result result = JourneyFile.resultOf(journey, page);

        assertThat(result.passed()).isFalse();
        assertThat(result.failure())
            .startsWith("step 2 of 5 failed: `click role=button[name=\"Add contact\"]` - "
                + "locator.click: Timeout 10000ms exceeded.")
            .contains("The 1 step(s) before it were done: click role=link[name=\"Logbook\"].")
            .doesNotContain("not reached");

        PageCheck green = new PageCheck("/", true, List.of(), List.of(
            new AssertionResult("step 1: click x", true, "done")), null);
        assertThat(JourneyFile.resultOf(journey, green).passed()).isTrue();

        PageCheck neverLoaded = new PageCheck("/", false, List.of(), List.of(
            new AssertionResult("page-load", false, "net::ERR_CONNECTION_REFUSED")), null);
        assertThat(JourneyFile.resultOf(journey, neverLoaded).failure())
            .contains("entry page / did not load").contains("ERR_CONNECTION_REFUSED");
    }

    @Test
    void theEntryPageIsTheContractsFirstPageCheckOrTheRoot() throws Exception {
        VerifySpec served = VerifySpecLoader.parse("""
            toolchain: maven
            browser:
              serve: "java -jar app.jar --port {PORT}"
              checks:
                - url: "/app/"
                  assertVisible: ["body"]
            """);
        assertThat(JourneyFile.canRun(served)).isTrue();
        assertThat(JourneyFile.entryUrl(served.browser())).isEqualTo("/app/");

        VerifySpec bare = VerifySpecLoader.parse("""
            toolchain: maven
            browser:
              serve: "java -jar app.jar --port {PORT}"
              checks: []
            """);
        assertThat(JourneyFile.entryUrl(bare.browser())).isEqualTo("/");

        VerifySpec none = VerifySpecLoader.parse("toolchain: maven\n");
        assertThat(JourneyFile.canRun(none)).isFalse();
        JourneyRunner.Outcome outcome = JourneyRunner.run(null, none,
            List.of(JourneyFile.read(PATH, JOURNEY).journey()), BlobSink.NONE, new StringBuilder());
        assertThat(outcome.made()).isFalse();
        assertThat(outcome.couldNotRun())
            .as("a journey that cannot be made is said, never passed over")
            .contains("no `browser.serve` line");
    }

    @Test
    void aTaskHasAScreenWhenItWritesShippedCodeOfABrowserOnlyModuleOrAPageOfAServedApp() {
        BrowserOnlyCode.Survey survey = new BrowserOnlyCode.Survey(
            List.of(new BrowserOnlyCode.Module("shop-client", "declares a browser runtime",
                "compiled to JavaScript", List.of("com.shop.client"))),
            List.of("shop-shared", "shop-server"), Map.of());

        assertThat(ScreenChange.screenPaths(List.of(
                "shop-client/src/main/java/com/shop/client/screen/OrderScreen.java",
                "shop-server/src/main/java/com/shop/server/OrderServiceImpl.java"), survey, false))
            .containsExactly("shop-client/src/main/java/com/shop/client/screen/OrderScreen.java");
        assertThat(ScreenChange.browserOnlyModulesOf(List.of(
                "shop-client/src/main/java/com/shop/client/screen/OrderScreen.java"), survey))
            .containsExactly("shop-client");

        assertThat(ScreenChange.screenPaths(List.of("shop-client/pom.xml",
                "shop-client/src/test/java/com/shop/client/OrderScreenTest.java",
                "shop-server/src/main/java/com/shop/server/OrderServiceImpl.java",
                "shop-clientele/src/main/java/com/shop/Report.java"), survey, false))
            .as("a build file, a test and a module that only starts with the same letters are "
                + "not a screen")
            .isEmpty();

        List<String> page = List.of("web/src/main/resources/static/orders.html",
            "web/src/main/java/com/shop/web/OrderController.java");
        assertThat(ScreenChange.screenPaths(page, BrowserOnlyCode.Survey.NONE, true))
            .as("a page of an application the contract starts")
            .containsExactly("web/src/main/resources/static/orders.html");
        assertThat(ScreenChange.screenPaths(page, BrowserOnlyCode.Survey.NONE, false))
            .as("without a served application a page file alone decides nothing")
            .isEmpty();
    }
}
