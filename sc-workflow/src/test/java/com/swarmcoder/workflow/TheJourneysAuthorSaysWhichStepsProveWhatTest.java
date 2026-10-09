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

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.verify.JourneyFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live run 103 (section 76): {@code check_journey} asks the author of a journey which steps
 * prove which criterion, and asks once about steps that use nothing of their own.
 */
class TheJourneysAuthorSaysWhichStepsProveWhatTest {

    private static final String PATH = "app/src/test/java/swarm/accept/records.journey.yaml";

    private static final List<String> CRITERIA = List.of(
        "An existing record's fields can be edited and the change is shown in the list.",
        "A record can be removed and it no longer appears in the list.");

    private static final String STEPS = """
        journey: The person adds a record, corrects its name, and removes it.
        steps:
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

    private static final String SECOND_ADD_AS_EDIT = STEPS + """
        proves:
          - criterion: 1
            steps: "4-6"
          - criterion: 2
            steps: "7-8"
        """;

    @TempDir
    Path world;

    private TestAuthorTools tools() {
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", world, "local")), null,
            world.resolve("cache"));
        ExpertTools session = new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("test author", "hand in", 0, null, 0, 0));
        return new TestAuthorTools(session, "app/src/test/java/swarm",
            "app/src/test/java/swarm/accept",
            (Map<String, String> files) -> new TestAuthorTools.Verdict(true, "HEALTHY"))
            .expectingAJourney(null).provingCriteria(CRITERIA);
    }

    @Test
    void aJourneyThatDoesNotSayIsNotKeptAndIsToldTheCriteriaByNumber() {
        String answer = tools().checkJourney(PATH, STEPS);

        assertThat(answer).startsWith("NOT A VALID JOURNEY")
            .contains("the file has no `proves`")
            .contains("1. An existing record's fields can be edited")
            .contains("2. A record can be removed");
    }

    @Test
    void stepsWithNoControlOfTheirOwnAreAskedAboutOnceAndKeptOnTheAuthorsWord() {
        TestAuthorTools tools = tools();

        String asked = tools.checkJourney(PATH, SECOND_ADD_AS_EDIT);

        assertThat(asked).startsWith("NOT KEPT YET - one question first.")
            .contains("criterion 1 (steps 4-6) uses only")
            .contains("not entering it again");

        String again = tools.checkJourney(PATH, SECOND_ADD_AS_EDIT);

        assertThat(again).startsWith("VALID")
            .contains("criterion 1: said to be proved by steps 4-6");
    }

    @Test
    void aJourneyThatUsesTheControlForEachCriterionIsKeptAtOnce() {
        String edits = """
            journey: The person adds a record, corrects its name, and removes it.
            steps:
              - fill: "role=textbox[name=\\"Name\\"]"
                value: "Hemmingway"
              - click: "role=button[name=\\"Add\\"]"
              - click: "role=button[name=\\"Edit\\"]"
              - fill: "role=textbox[name=\\"Edit name\\"]"
                value: "Hemingway"
              - click: "role=button[name=\\"Save\\"]"
              - expectVisible: "text=Hemingway"
              - click: "role=button[name=\\"Remove\\"]"
              - expectHidden: "text=Hemingway"
            proves:
              - criterion: 1
                steps: "3-6"
              - criterion: 2
                steps: "7-8"
            """;

        assertThat(tools().checkJourney(PATH, edits)).startsWith("VALID");
        assertThat(JourneyFile.read(PATH, edits).journey().proofLines(CRITERIA))
            .noneMatch(line -> line.contains("NOTE"));
    }
}
