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
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A journey can choose an option and read what a control holds (live run 100, section 74).
 *
 * <p>The journey of that run had to give a record one of three states. Its steps were click,
 * fill, press, expectVisible and expectHidden, so it clicked the list and then clicked the
 * option's text. The options of a closed drop-down list are in the document and not on the
 * page: the click waited ten seconds and failed, on a screen that was right. The author's
 * correction opened the list with the keyboard and then expected the option's text to be
 * visible; the first element with that text was again the option, so that failed too, although
 * the row the journey had just added showed it. Its draft with a {@code select} step had been
 * refused: there was no such step.
 *
 * <p>The file is read and judged with no model; the last test uses the real checker in the
 * browser image on a static page.
 */
class AJourneyChoosesInAListAndReadsWhatAControlHoldsTest {

    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";
    private static final String PATH = "app/src/test/java/swarm/accept/shelf.journey.yaml";

    private static final String CHOOSES = """
        journey: A record is added with a state chosen from the list, and is shown with it
        steps:
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "Dune"
          - select: "role=combobox[name=\\"State\\"]"
            value: "Being read"
          - expectValue: "role=combobox[name=\\"State\\"]"
            value: "Being read"
          - click: "role=button[name=\\"Add\\"]"
          - expectVisible: "text=Being read"
        """;

    @TempDir
    Path tree;

    // ---- the file ---------------------------------------------------------------------------

    @Test
    void aJourneyMaySelectAnOptionAndExpectWhatAControlHolds() {
        JourneyFile.Read read = JourneyFile.read(PATH, CHOOSES);

        assertThat(read.ok()).as(read.objection()).isTrue();
        assertThat(read.journey().steps()).extracting(VerifySpec.StepSpec::describe)
            .containsExactly("fill role=textbox[name=\"Title\"]",
                "select \"Being read\" in role=combobox[name=\"State\"]",
                "expect value \"Being read\" in role=combobox[name=\"State\"]",
                "click role=button[name=\"Add\"]", "expect visible text=Being read");
        assertThat(read.journey().steps().get(1).select())
            .isEqualTo("role=combobox[name=\"State\"]");
        assertThat(read.journey().steps().get(1).value()).isEqualTo("Being read");
        assertThat(read.journey().steps().get(2).looks()).isTrue();
        assertThat(BrowserVerifier.checkerScript())
            .as("this build's checker travels with the request and knows both steps")
            .contains("step.select").contains("step.expectValue");
    }

    @Test
    void aJourneyMayEndOnWhatAControlHolds() {
        JourneyFile.Read read = JourneyFile.read(PATH, """
            journey: The state is chosen
            steps:
              - select: "#state"
                value: "Done"
              - expectValue: "#state"
                value: "Done"
            """);

        assertThat(read.ok()).as(read.objection()).isTrue();
    }

    @Test
    void aSelectOrAnExpectedValueWithoutItsValueIsSentBackAndAValueElsewhereIsToo() {
        assertThat(JourneyFile.read(PATH, """
            journey: x
            steps:
              - select: "#state"
              - expectVisible: "text=Done"
            """).objection())
            .contains("step 1 selects in a control and gives no `value`");
        assertThat(JourneyFile.read(PATH, """
            journey: x
            steps:
              - click: "#add"
              - expectValue: "#state"
            """).objection())
            .contains("step 2 expects a control to hold something and gives no `value`");
        assertThat(JourneyFile.read(PATH, """
            journey: x
            steps:
              - click: "#add"
                value: "Done"
              - expectVisible: "text=Done"
            """).objection())
            .contains("step 1 has a `value` but is a click")
            .contains("select (the option chosen)");
    }

    @Test
    void theVocabularySaysInOneLineEachWhatEveryStepIsForAndAnUnknownStepIsToldTheSteps() {
        for (String action : List.of("click", "fill", "select", "press", "expectVisible",
                "expectHidden", "expectValue")) {
            assertThat(JourneyFile.VOCABULARY).contains("  - " + action + ": ");
        }
        assertThat(JourneyFile.VOCABULARY.lines().filter(line -> line.contains("#")).count())
            .as("a line of what it is for, for each of the seven").isEqualTo(7);
        assertThat(JourneyFile.CHOOSING).contains("never click the option")
            .contains("expectValue");
        assertThat(JourneyFile.read(PATH, """
            journey: x
            steps:
              - choose: "#state"
              - expectVisible: "text=Done"
            """).objection())
            .contains("A step is one of click, fill, select, press, expectVisible, "
                + "expectHidden, expectValue");
    }

    // ---- what a journey expects ----------------------------------------------------------------

    @Test
    void whatAStepChoosesIsEnteredByTheJourneyAsMuchAsWhatItTypes() {
        JourneyFile.Journey journey = JourneyFile.read(PATH, CHOOSES).journey();

        assertThat(JourneyExpectations.unentered(journey, text -> false))
            .as("the option's text was chosen by step 2, so nobody is asked about it")
            .isEmpty();
    }

    @Test
    void aCorrectionThatTurnsTwoClicksIntoOneSelectUsesTheScreenNoLess() {
        JourneyFile.Journey written = JourneyFile.read(PATH, """
            journey: A record is added with a state
            steps:
              - fill: "role=textbox[name=\\"Title\\"]"
                value: "Dune"
              - click: "role=combobox[name=\\"State\\"]"
              - click: "text=Being read"
              - click: "role=button[name=\\"Add\\"]"
              - expectVisible: "text=Dune"
            """).journey();
        JourneyFile.Journey corrected = JourneyFile.read(PATH, CHOOSES).journey();

        assertThat(JourneyExpectations.changing(written)).isEqualTo(4);
        assertThat(JourneyExpectations.changing(corrected))
            .as("a fill, a select that stands for two, a click").isEqualTo(4);
        assertThat(JourneyExpectations.weakened(written, corrected)).isEmpty();
        assertThat(JourneyExpectations.lastExpectationAlone(JourneyFile.read(PATH, """
            journey: x
            steps:
              - select: "#state"
                value: "Done"
              - expectValue: "#state"
                value: "Done"
            """).journey()).steps())
            .as("an ending on what a control holds is tried alone on the start tree too")
            .hasSize(1);
    }

    // ---- in a real browser ------------------------------------------------------------------

    @Test
    @RunsWhen(value = Need.DOCKER, image = UI_IMAGE)
    void inABrowserAnOptionIsChosenWhatAControlHoldsIsReadAndTheReadingNamesTheOptions()
            throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Path site = Files.createDirectories(tree.resolve("site"));
        // A list of the browser's own, a combobox the page draws itself, and a row that reads
        // the same as an option once it is added.
        Files.writeString(site.resolve("index.html"), """
            <html><body><h1>Shelf</h1>
            <label for="title">Title</label><input id="title">
            <label for="state">State</label>
            <select id="state"><option>Not started</option><option>Being read</option>
              <option>Finished</option></select>
            <div id="kind" role="combobox" aria-label="Kind" tabindex="0"
                 onclick="document.getElementById('kinds').hidden=false">Paper</div>
            <ul id="kinds" role="listbox" hidden>
              <li role="option" onclick="document.getElementById('kind').textContent='Audio';
                  this.parentNode.hidden=true">Audio</li></ul>
            <button onclick="var d=document.createElement('div');
                d.textContent=document.getElementById('title').value+' / '
                  +document.getElementById('state').value+' / '
                  +document.getElementById('kind').textContent;
                document.getElementById('rows').appendChild(d)">Add</button>
            <div id="rows"></div></body></html>
            """);
        String state = "role=combobox[name=\"State\"]";
        VerifySpec.PageCheckSpec chooses = journey(
            fill("role=textbox[name=\"Title\"]", "Dune"),
            select(state, "Being read"),
            expectValue(state, "Being read"),
            select("role=combobox[name=\"Kind\"]", "Audio"),
            click("role=button[name=\"Add\"]"),
            expectValue("role=textbox[name=\"Title\"]", "Dune"),
            expectVisible("text=Being read"),
            expectVisible("#rows >> text=Dune / Being read / Audio"));
        VerifySpec.PageCheckSpec clicksTheOption = journey(
            click(state), click("text=Finished"), expectVisible("h1"));
        VerifySpec.PageCheckSpec noSuchOption = journey(
            select(state, "Abandoned"), expectVisible("h1"));
        VerifySpec.PageCheckSpec holdsSomethingElse = journey(
            expectValue(state, "Finished"));
        VerifySpec.BrowserSpec spec = new VerifySpec.BrowserSpec("static:site", null, 60,
            List.of(chooses, clicksTheOption, noSuchOption, holdsSomethingElse), 0);
        StringBuilder log = new StringBuilder();

        BrowserCheckResults results;
        try (BuildBoxes.Box box = BuildBoxes.of(manager()).open(tree, "Final integration", true)) {
            results = new BrowserVerifier(BlobSink.NONE).run(box.target(), spec, log);
        }

        assertThat(results.couldNotTry()).as(log.toString()).isFalse();
        assertThat(results.checks()).hasSize(4);
        assertThat(steps(results.checks().get(0)))
            .as("chosen in the browser's own list and in a drawn one, read back, and the row "
                + "is seen although an option reads the same and comes first: " + log)
            .hasSize(8).allMatch(AssertionResult::passed);

        JourneyFile.Result clicked = JourneyFile.resultOf(
            new JourneyFile.Journey(PATH, "old", clicksTheOption.steps()),
            results.checks().get(1));
        assertThat(clicked.passed()).isFalse();
        assertThat(clicked.step()).as("an option is not on the page for a click").isEqualTo(2);
        assertThat(clicked.seen()).as("the reading names the list, what it shows and offers")
            .contains("drop-down lists")
            .contains("\"State\" shows \"Not started\"; it offers \"Not started\", "
                + "\"Being read\", \"Finished\"");

        JourneyFile.Result missing = JourneyFile.resultOf(
            new JourneyFile.Journey(PATH, "none", noSuchOption.steps()), results.checks().get(2));
        assertThat(missing.step()).isEqualTo(1);
        assertThat(missing.failure()).contains("the list has no option \"Abandoned\"")
            .contains("it offers: \"Not started\", \"Being read\", \"Finished\"");

        JourneyFile.Result other = JourneyFile.resultOf(
            new JourneyFile.Journey(PATH, "holds", holdsSomethingElse.steps()),
            results.checks().get(3));
        assertThat(other.failure()).contains("it holds \"Not started\", not \"Finished\"");
    }

    private static List<AssertionResult> steps(PageCheck check) {
        return check.assertions().stream().filter(a -> a.selector().startsWith("step ")).toList();
    }

    private static VerifySpec.PageCheckSpec journey(VerifySpec.StepSpec... steps) {
        return new VerifySpec.PageCheckSpec("/", false, List.of(), false, List.of(steps));
    }

    private static VerifySpec.StepSpec click(String selector) {
        return new VerifySpec.StepSpec(selector, null, null, null, null, null);
    }

    private static VerifySpec.StepSpec fill(String selector, String value) {
        return new VerifySpec.StepSpec(null, selector, value, null, null, null);
    }

    private static VerifySpec.StepSpec expectVisible(String selector) {
        return new VerifySpec.StepSpec(null, null, null, null, selector, null);
    }

    private static VerifySpec.StepSpec select(String selector, String value) {
        return new VerifySpec.StepSpec(null, null, value, null, null, null, selector, null);
    }

    private static VerifySpec.StepSpec expectValue(String selector, String value) {
        return new VerifySpec.StepSpec(null, null, value, null, null, null, null, selector);
    }

    private static DockerSandboxManager manager() {
        return new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            Path.of(System.getProperty("user.home"), ".m2").toString());
    }
}
