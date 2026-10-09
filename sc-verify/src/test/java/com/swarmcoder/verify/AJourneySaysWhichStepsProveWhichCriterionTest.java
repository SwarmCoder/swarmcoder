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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Live run 103 (section 76): a story about editing and removing records was delivered and
 * accepted on a journey that entered a record a second time with a corrected spelling and never
 * used the screen's edit control. Which steps exercise which criterion is now a field of the
 * journey file: read with no model, checked for form, and shown to whoever accepts the story.
 */
class AJourneySaysWhichStepsProveWhichCriterionTest {

    private static final String PATH = "app/src/test/java/swarm/accept/records.journey.yaml";

    private static final List<String> CRITERIA = List.of(
        "An existing record's fields can be edited and the change is shown in the list.",
        "A record can be removed and it no longer appears in the list.");

    /** The shape of the journey that passed in run 103: "edit" is a second add. */
    private static final String STEPS = """
        journey: The person adds a record, corrects its name, and removes it.
        steps:
          - expectVisible: "role=textbox[name=\\"Name\\"]"
          - fill: "role=textbox[name=\\"Name\\"]"
            value: "Hemmingway"
          - click: "role=button[name=\\"Add\\"]"
          - expectVisible: "text=Hemmingway"
          - fill: "role=textbox[name=\\"Name\\"]"
            value: "Hemingway"
          - click: "role=button[name=\\"Add\\"]"
          - expectVisible: "text=Hemingway"
          - click: "role=button[name=\\"Remove\\"]"
          - expectHidden: "text=Hemmingway"
        """;

    @Test
    void aJourneyWithoutProvesIsReadAsBeforeAndIsAskedForItWhereCriteriaAreKnown() {
        JourneyFile.Read read = JourneyFile.read(PATH, STEPS);

        assertThat(read.ok()).isTrue();
        assertThat(read.journey().proves()).isEmpty();
        assertThat(read.journey().proofLines(CRITERIA)).isEmpty();
        assertThat(JourneyFile.coverageObjection(read.journey(), List.of())).isNull();
        assertThat(JourneyFile.coverageObjection(read.journey(), CRITERIA))
            .contains("the file has no `proves`").contains("1. An existing record's fields")
            .contains("2. A record can be removed");
    }

    @Test
    void theEntriesAreReadAndShownWithTheirStepsAndTheCriterionsWords() {
        JourneyFile.Read read = JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "5-7"
              - criterion: 2
                steps: "8-9"
            """);

        assertThat(read.problems()).isEmpty();
        assertThat(read.journey().proves()).containsExactly(
            new JourneyFile.Proof(1, 5, 7, null), new JourneyFile.Proof(2, 8, 9, null));
        assertThat(JourneyFile.coverageObjection(read.journey(), CRITERIA)).isNull();
        List<String> lines = read.journey().proofLines(CRITERIA);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith(PATH + ": criterion 1 \"An existing record's fields")
            .contains("by steps 5-7: ").contains("Add");
        assertThat(lines.get(1)).contains("criterion 2").contains("by steps 8-9: ")
            .contains("Remove").doesNotContain("NOTE");
        assertThat(read.journey().describe()).contains("criterion 1: said to be proved by steps 5-7");
    }

    @Test
    void stepsThatUseNoControlOfTheirOwnAreAQuestionAndAFlagNeverARefusal() {
        JourneyFile.Journey journey = JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "5-7"
              - criterion: 2
                steps: "8-9"
            """).journey();

        // "Edit" fills the field and clicks the button the add already used; "remove" clicks
        // a button nothing else does. Compared as selectors - no word is read.
        List<String> borrowed = JourneyFile.borrowedControls(journey);

        assertThat(borrowed).hasSize(1);
        assertThat(borrowed.get(0)).startsWith("criterion 1 (steps 5-7) uses only ")
            .contains("role=button[name=\"Add\"]");
        assertThat(JourneyFile.borrowedQuestion(borrowed)).contains("no control of their own");
        assertThat(journey.proofLines(CRITERIA).get(0)).contains("[NOTE: these steps use no control");
    }

    @Test
    void aCriterionMayBeSaidNotToBeOnAScreenAndEveryCriterionNeedsItsEntry() {
        JourneyFile.Read read = JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 2
                notOnScreen: "The record is removed by a nightly job; no screen shows it."
            """);

        assertThat(read.problems()).isEmpty();
        assertThat(read.journey().proofLines(CRITERIA).get(0))
            .contains("criterion 2").contains("said not to be on a screen: The record is removed");
        assertThat(JourneyFile.coverageObjection(read.journey(), CRITERIA))
            .contains("`proves` has no entry for criterion 1");
        assertThat(JourneyFile.coverageObjection(read.journey(), CRITERIA.subList(0, 1)))
            .contains("`proves` names criterion 2; there are 1");
    }

    @Test
    void anEntryThatIsNotWellFormedIsAProblemOfTheFile() {
        assertThat(JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "5-40"
            """).objection()).contains("within this journey's 9 step(s)");
        assertThat(JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "1"
            """).objection()).contains("only look");
        assertThat(JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "2-3"
            """).objection()).contains("never look");
        assertThat(JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "5-7"
              - criterion: 1
                steps: "8-9"
            """).objection()).contains("a second entry for criterion 1");
        assertThat(JourneyFile.read(PATH, STEPS + """
            proves:
              - criterion: 1
                steps: "5-7"
                notOnScreen: "both"
            """).objection()).contains("exactly one of `steps` and `notOnScreen`");
        assertThat(JourneyFile.read(PATH, STEPS + "proves: yes\n").objection())
            .contains("`proves` must be a list");
    }
}
