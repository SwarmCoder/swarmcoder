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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 69, after live run 93. The journey there typed into a search box and expected a
 * book's title, on a store that starts empty; and it found the box by a name the screen did
 * not use. What can be said about that with no model: which expected texts nobody enters, what
 * makes a correction a weaker journey, and what the page showed at the failing step.
 */
class WhatAJourneyExpectsIsJudgedWithNoModelTest {

    private static final String PATH = "src/test/java/swarm/accept/search.journey.yaml";

    /** Run 93's journey, in shape. */
    private static final String SEARCH = """
        journey: A book is found by its title
        steps:
          - click: "role=link[name=\\"Books\\"]"
          - fill: "role=textbox[name=\\"Search books by title or author\\"]"
            value: "hobbit"
          - expectVisible: "text=A Wizard of Earthsea"
        """;

    private static JourneyFile.Journey journey(String yaml) {
        JourneyFile.Read read = JourneyFile.read(PATH, yaml);
        assertThat(read.ok()).as(read.objection()).isTrue();
        return read.journey();
    }

    @Test
    void aTextNoStepTypesAndTheProjectDoesNotHoldIsNamedWithItsStep() {
        Set<String> held = Set.of("books", "bookshelf");

        List<JourneyExpectations.Unentered> found = JourneyExpectations.unentered(journey(SEARCH),
            text -> held.contains(text.toLowerCase()));

        assertThat(found).containsExactly(
            new JourneyExpectations.Unentered(3, "A Wizard of Earthsea"));
        assertThat(JourneyExpectations.question(found))
            .contains("NO DATA of its own", "step 3", "A Wizard of Earthsea",
                "add the steps that enter it", "the journey is right as it is");
    }

    @Test
    void whatAStepTypedOrTheProjectHoldsIsNotNamed() {
        JourneyFile.Journey typed = journey("""
            journey: A book is added and then found
            steps:
              - fill: "role=textbox[name=\\"Title\\"]"
                value: "The Hobbit"
              - click: "role=button[name=\\"Add\\"]"
              - expectVisible: "text=the hobbit"
              - expectVisible: "tr:has-text(\\"Hobbit\\")"
              - expectVisible: "text=Bookshelf"
              - expectVisible: "role=heading[name=\\"Results\\"]"
              - expectVisible: "text=/\\\\d+ books/"
            """);

        assertThat(JourneyExpectations.unentered(typed, text -> text.equals("Bookshelf")))
            .as("typed (in either case, whole or part), held by the project, a role's name "
                + "and a pattern are none of them a text nobody entered")
            .isEmpty();
        assertThat(JourneyExpectations.unentered(typed, null))
            .as("with nothing to compare against nothing is concluded").isEmpty();
    }

    @Test
    void theTextsOfASelectorAreReadFromItsFormOnly() {
        assertThat(JourneyExpectations.textsOf("text=Monthly report"))
            .containsExactly("Monthly report");
        assertThat(JourneyExpectations.textsOf("text=\"Monthly report\""))
            .containsExactly("Monthly report");
        assertThat(JourneyExpectations.textsOf("#list >> li:has-text('Dune') >> text=Open"))
            .containsExactly("Dune", "Open");
        assertThat(JourneyExpectations.textsOf("role=button[name=\"Save\"]")).isEmpty();
        assertThat(JourneyExpectations.textsOf("#total")).isEmpty();
    }

    @Test
    void aCorrectionThatOnlyLooksOrUsesLessIsWeaker() {
        JourneyFile.Journey original = journey(SEARCH);

        assertThat(JourneyExpectations.weakened(original, journey("""
            journey: The books screen is there
            steps:
              - expectVisible: "text=Books"
            """))).anySatisfy(why -> assertThat(why).contains("no step that changes anything"));
        assertThat(JourneyExpectations.weakened(original, journey("""
            journey: The books screen opens
            steps:
              - click: "role=link[name=\\"Books\\"]"
              - click: "role=button[name=\\"Search\\"]"
              - expectVisible: "role=heading[name=\\"Books\\"]"
            """))).as("as many steps, but it no longer types anything")
            .containsExactly("it fills 0 field(s) where the journey it replaces fills 1");
    }

    @Test
    void aCorrectionThatRenamesASelectorOrAddsTheMissingStepsIsNotWeaker() {
        JourneyFile.Journey original = journey(SEARCH);

        assertThat(JourneyExpectations.weakened(original, journey("""
            journey: A book is added and then found by its title
            steps:
              - click: "role=link[name=\\"Books\\"]"
              - fill: "role=textbox[name=\\"Title\\"]"
                value: "The Hobbit"
              - click: "role=button[name=\\"Add book\\"]"
              - fill: "role=textbox[name=\\"Search books...\\"]"
                value: "hobbit"
              - expectVisible: "text=The Hobbit"
            """))).isEmpty();
    }

    @Test
    void theLastExpectationAloneIsAJourneyOfOneStepAndThereIsNoneForSomethingHidden() {
        JourneyFile.Journey alone = JourneyExpectations.lastExpectationAlone(journey(SEARCH));

        assertThat(alone.steps()).hasSize(1);
        assertThat(alone.steps().get(0).expectVisible()).isEqualTo("text=A Wizard of Earthsea");
        assertThat(JourneyExpectations.lastExpectationAlone(journey("""
            journey: The banner goes away
            steps:
              - click: "role=button[name=\\"Dismiss\\"]"
              - expectHidden: "text=Welcome"
            """))).isNull();
    }

    /** Section 75, live run 101: the journey began by expecting the start page's own text. */
    private static final String STARTS_BY_LOOKING = """
        journey: A book is added from the shelf
        steps:
          - expectVisible: "text=Nothing here yet"
          - expectHidden: "text=Loading"
          - expectValue: "role=combobox[name=\\"Shelf\\"]"
            value: "All"
          - click: "role=button[name=\\"Add book\\"]"
          - fill: "role=textbox[name=\\"Title\\"]"
            value: "The Hobbit"
          - expectVisible: "role=heading[name=\\"Saved\\"]"
          - expectVisible: "text=The Hobbit"
        """;

    @Test
    void whatAJourneyExpectsBeforeItDoesAnythingIsTriedAloneOnTheEntryPage() {
        JourneyFile.Journey journey = journey(STARTS_BY_LOOKING);

        List<JourneyExpectations.Leading> leading = JourneyExpectations.leading(journey);
        assertThat(leading).as("the looks before the first click; expectHidden is not one, "
            + "and nothing after a step that changes something").extracting(
                JourneyExpectations.Leading::step).containsExactly(1, 3);
        List<JourneyFile.Journey> alone = JourneyExpectations.leadingAlone(journey);
        assertThat(alone).hasSize(2);
        assertThat(alone.get(0).steps()).containsExactly(journey.steps().get(0));
        assertThat(alone.get(1).steps()).containsExactly(journey.steps().get(2));
        assertThat(alone.get(0).path()).isEqualTo(PATH);
        assertThat(JourneyExpectations.leading(journey(SEARCH))).as("a journey that begins by "
            + "doing something has none, and nothing is tried for it").isEmpty();
    }

    @Test
    void anExpectationTheStartingApplicationAlreadyMeetsIsNamedWithItsStep() {
        JourneyFile.Journey journey = journey(STARTS_BY_LOOKING);
        List<JourneyFile.Journey> alone = JourneyExpectations.leadingAlone(journey);

        List<JourneyExpectations.Leading> there = JourneyExpectations.alreadyThere(journey,
            List.of(new JourneyFile.Result(alone.get(0), true, null),
                new JourneyFile.Result(alone.get(1), false, "step 1 of 1 failed")));

        assertThat(there).extracting(JourneyExpectations.Leading::step).containsExactly(1);
        assertThat(JourneyExpectations.alreadyThereObjection(there))
            .contains("step 1 (`expect visible text=Nothing here yet`) is already true on the "
                + "entry page of the application as it is BEFORE the story",
                "tried there in a real browser", "Take it out, or expect what the story adds");
        assertThat(JourneyExpectations.alreadyThere(journey, List.of()))
            .as("a page that was not read establishes nothing").isEmpty();
        assertThat(JourneyExpectations.alreadyThere(journey, null)).isEmpty();
    }

    @Test
    void theNamesAJourneysSelectorsUseAreReadWithoutWhatItTypes() {
        JourneyFile.Journey journey = journey(STARTS_BY_LOOKING);

        assertThat(JourneyExpectations.namesUsed(journey)).as("texts and accessible names of "
            + "what is clicked, filled, chosen in and expected; not what expectHidden names, "
            + "and not the title the journey typed itself")
            .containsExactly("Nothing here yet", "Shelf", "Add book", "Title", "Saved");
        Set<String> held = Set.of("shelf", "title", "nothing here yet");
        assertThat(JourneyExpectations.namesNotHeld(journey,
            text -> held.contains(text.toLowerCase()))).containsExactly("Add book", "Saved");
        assertThat(JourneyExpectations.namesNotHeld(journey, null))
            .as("a checkout that cannot be read establishes nothing").isEmpty();
    }

    /** Section 75: live run 101's journey filled `textbox[name="Title"]`. */
    @Test
    void aRoleWrittenWithoutItsPrefixIsNamedWithItsStepAndHowToWriteIt() {
        JourneyFile.Journey journey = journey("""
            journey: A book is added
            steps:
              - click: "button[name=\\"add\\"]"
              - fill: "textbox[name=\\"Title\\"]"
                value: "Dune"
              - select: "role=group[name=\\"Book\\"] >> combobox[name=\\"Status\\"]"
                value: "read"
              - expectVisible: "role=row[name=\\"Dune\\"]"
            """);

        assertThat(JourneyExpectations.rolesWithoutPrefix(journey)).as("a role that is no "
            + "element's name; `button[...]` is a real stylesheet selector and is left alone")
            .containsExactly(
                "step 2: `textbox[name=\"Title\"]` names a role without `role=`, so the browser "
                    + "looks for an element <textbox>, which no page has. Write "
                    + "`role=textbox[name=\"Title\"]`",
                "step 3: `combobox[name=\"Status\"]` names a role without `role=`, so the "
                    + "browser looks for an element <combobox>, which no page has. Write "
                    + "`role=combobox[name=\"Status\"]`");
        assertThat(JourneyExpectations.rolesWithoutPrefix(journey(SEARCH))).isEmpty();
    }

    @Test
    void whatThePageShowedAtTheFailingStepTravelsWithTheResultAndIsNotAFailure() {
        JourneyFile.Journey journey = journey(SEARCH);
        String seen = "elements, as role \"accessible name\":\nlink \"Books\"\n"
            + "textbox \"Search books...\"\nvisible text: Bookshelf Books No books yet";
        PageCheck page = new PageCheck("/", true, List.of(), List.of(
            new AssertionResult("step 1: click role=link[name=\"Books\"]", true, "done"),
            new AssertionResult("step 2: fill role=textbox", false,
                "locator.fill: Timeout 10000ms exceeded. (the browser was at /)"),
            new AssertionResult("page-seen", true, seen),
            new AssertionResult("step 3: expect visible text=A Wizard of Earthsea", false,
                "not reached")), null);

        JourneyFile.Result result = JourneyFile.resultOf(journey, page);

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).startsWith("step 2 of 3 failed");
        assertThat(result.seen()).isEqualTo(seen);

        PageCheck passed = new PageCheck("/", true, List.of(), List.of(
            new AssertionResult("step 1: click", true, "done")), null);
        assertThat(JourneyFile.resultOf(journey, passed).seen()).isNull();
        PageCheck huge = new PageCheck("/", true, List.of(), List.of(
            new AssertionResult("step 1: click", false, "Timeout"),
            new AssertionResult("page-seen", true, "x".repeat(JourneyFile.MAX_SEEN + 500))), null);
        assertThat(JourneyFile.resultOf(journey, huge).seen()).hasSize(JourneyFile.MAX_SEEN);
    }
}
