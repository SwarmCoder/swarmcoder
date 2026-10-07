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
 * The wording sent to the test author when its test does not compile for a reason no task can
 * ever fix — and the wording a run parks with when a second attempt is still broken. See harness
 * run 26 on {@link BrokenAcceptanceTest}'s own javadoc for the failure this answers.
 */
class BrokenAcceptanceTestTest {

    @Test
    void theReaskNamesTheOffenderTheModuleAndItsClasspath() {
        String message = BrokenAcceptanceTest.reask(List.of("com.zeroz4j.ui"),
            "bookshelf-demo-server", List.of("bookshelf-demo-shared", "junit-jupiter"));

        assertThat(message)
            .as("says WHY this is not a healthy red state")
            .contains("not because the code it needs has not been written yet")
            .as("names the offending package")
            .contains("`com.zeroz4j.ui`")
            .as("names the module the test lives in")
            .contains("The acceptance module is bookshelf-demo-server")
            .as("names what that module's classpath actually holds")
            .contains("bookshelf-demo-shared, junit-jupiter")
            .as("asks for a corrected reply in the same shape as every other re-ask")
            .contains("Reply with the same JSON object, with the corrected file(s).");
    }

    @Test
    void theRootModuleReadsAsTheRepositoryRoot() {
        String message = BrokenAcceptanceTest.reask(List.of("com.acme.ui"), "", List.of());

        assertThat(message).contains("The acceptance module is the repository root");
        assertThat(message).contains("no declared dependencies");
    }

    @Test
    void theParkBriefSaysTheAuthorWasAlreadyAskedOnce() {
        String brief = BrokenAcceptanceTest.park("Show the book's rating",
            List.of("com.zeroz4j.ui"), "bookshelf-demo-server", List.of("bookshelf-demo-shared"),
            "the correction still imports com.zeroz4j.ui");

        assertThat(brief)
            .contains("Show the book's rating")
            .contains("`com.zeroz4j.ui`")
            .as("says the author already had its one chance")
            .contains("was asked once to correct this and did not")
            .contains("the correction still imports com.zeroz4j.ui")
            .as("says this is not TDD, so nothing downstream can tell the two apart")
            .contains("not a healthy red state")
            .as("gives the operator the two ways out")
            .contains("add the missing type to the design as a contract")
            .contains("Then resume the run.");
    }
}
