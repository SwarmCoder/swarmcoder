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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 75, 2026-10-03: the rubric review of a story about what the logbook does NOT offer
 * objected that a standing rule about server-side filtering "requires the service to offer list
 * with a filter and sorting", and the architect added a filter type and a service method to a
 * story that lists nothing. See {@link RubricObjections}.
 */
class ARubricObjectionThatRestsOnARuleIsNotSentToTheArchitectTest {

    private static final String RULES = "HOW THIS PROJECT MUST BE BUILT\n\n"
        + "- Server-side filtering and sorting\n  A list is filtered and sorted on the server.\n"
        + "- Fixed module and package layout\n  Three modules, three packages.\n";

    private static final String FILTERING = "The LogbookService contract shown in the design lists "
        + "only getLogbook() with no parameters, but the standing rule 'Server-side filtering and "
        + "sorting' requires the service to offer list with a filter and sorting.";
    private static final String PACKAGES = "The acceptance test is named swarm.accept.LogbookTest, "
        + "which is outside the fixed layout. Tests must reside within those packages per the "
        + "'Fixed module and package layout' rule.";
    private static final String PARTITION = "The change set is not partitionable into tasks with "
        + "disjoint file ownership: removing a field from 'Qso' breaks compilation of its callers.";

    @Test
    void theObjectionsOfRun75AreSplitIntoTheRubricsOwnAndTheRulesChecks() {
        RubricObjections.Split split =
            RubricObjections.split(List.of(FILTERING, PACKAGES, PARTITION), RULES);

        assertThat(split.restingOnARule()).containsExactly(FILTERING, PACKAGES);
        assertThat(split.forTheArchitect()).containsExactly(PARTITION);
    }

    @Test
    void aQuotedNameThatIsNotARuleTitleLeavesTheObjectionWithTheRubric() {
        assertThat(RubricObjections.restsOnARule(
            "The requirement 'rate a book' has no check a test could run.", RULES)).isFalse();
        assertThat(RubricObjections.restsOnARule(PARTITION, RULES)).isFalse();
    }

    @Test
    void withNoRulesEveryObjectionStands() {
        RubricObjections.Split split = RubricObjections.split(List.of(FILTERING, PARTITION), "");

        assertThat(split.restingOnARule()).isEmpty();
        assertThat(split.forTheArchitect()).containsExactly(FILTERING, PARTITION);
    }

    @Test
    void theReviewersAreToldARuleIsInQuestionOnlyWhereTheChangeFallsUnderIt() {
        assertThat(DesignReviewerClient.RULES_OUTRANK_THE_GOAL)
            .contains("never object that the design lacks, omits or fails to deliver something "
                + "a rule describes");
        assertThat(DesignReviewerClient.ONLY_WHAT_THE_CHANGE_TOUCHES)
            .contains("ONLY where what is being added or changed here falls under it")
            .contains("Code that existed before this work and is not changed by it is not this "
                + "work's violation");
    }
}
