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

import com.swarmcoder.domain.InstructedRetest;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the author of an acceptance test is told when the test comes back after candidates
 * exist (DEVELOPER_CORRECTIONS section 86): the error the build measured, the instruction of
 * whoever answered the stopped build's question, and which tests pass - by name, to be looked
 * up, never as pasted files.
 */
class ATestSentBackIsToldWhatWasMeasuredTest {

    private static final String ERROR = "swarm.accept.OrderStoreTest#storesAnOrder - container "
        + "not started\njava.lang.IllegalStateException: container not started\n"
        + "\tat org.framework.Container.start(Container.java:88)";

    @Test
    void aBrokenCorrectionGoesBackWithItsErrorAndWithWhichTestsPass() {
        String setups = SentBackTest.setupLookups(
            List.of("swarm.accept.OrderStoreTest#listsOrders"),
            List.of("swarm.accept.CustomerStoreTest#storesACustomer",
                "swarm.accept.CustomerStoreTest#findsACustomer", "swarm.accept.InvoiceTest",
                "swarm.accept.OrderStoreTest#other"));
        String ask = SentBackTest.brokenCorrectionReask("every test errored before any code under "
            + "test ran", ERROR, true, setups);

        assertThat(ask).startsWith("The corrected test you handed in is broken, not red: every "
                + "test errored before any code under test ran.")
            .contains(ERROR)
            .contains("The version before your correction did start")
            .contains("Do not change what a check proves.");
        assertThat(ask).as("this task's own tests that passed, as a fact")
            .contains("This task's own test(s) swarm.accept.OrderStoreTest#listsOrders passed "
                + "for at least one candidate as the test stood before it was last changed");
        assertThat(ask).as("the project's passing tests by class, once each, as a lookup")
            .contains("Passing acceptance tests of this project: "
                + "swarm.accept.CustomerStoreTest, swarm.accept.InvoiceTest.")
            .contains("is a lookup: body_of <Class>");
        assertThat(ask).as("no file is pasted").doesNotContain("class ").doesNotContain("import ");
        assertThat(SentBackTest.brokenCorrectionReask("r", ERROR, false, ""))
            .as("nothing is claimed about an earlier version that is not on record")
            .doesNotContain("did start");
    }

    @Test
    void theListOfPassingTestsIsBoundedAndEmptyWhenNoneIsOnRecord() {
        assertThat(SentBackTest.setupLookups(List.of(), null)).isEmpty();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("swarm.accept.Test" + i + "#proves");
        }
        String setups = SentBackTest.setupLookups(many, many);
        assertThat(setups).contains("swarm.accept.Test" + (SentBackTest.MOST_NAMES - 1) + "#proves")
            .doesNotContain("swarm.accept.Test" + SentBackTest.MOST_NAMES + "#proves");
        assertThat(SentBackTest.setupLookups(null, many).length()).isLessThan(600);
    }

    @Test
    void aTestSentBackOnAnInstructionReadsTheInstructionBeforeTheFailure() {
        String ask = SentBackTest.instructedReask("  Keep the container annotations of the first "
            + "version; give each test its own store. ", "Why: acceptance test "
            + "swarm.accept.OrderStoreTest#storesAnOrder errored before it could fail or pass",
            "");

        assertThat(ask.indexOf("Keep the container annotations"))
            .isGreaterThan(ask.indexOf("Do what it says:"))
            .isLessThan(ask.indexOf("WHAT THE BUILD STOPPED ON:"));
        assertThat(ask).contains("errored before it could fail or pass")
            .contains("still fails on code that does not implement it");
        assertThat(SentBackTest.instructedReask("do it", " ", ""))
            .contains("(no failure is on record)");
    }

    @Test
    void theStopSaysTheTestIsBrokenAndThatNoRepairRoundWasStarted() {
        assertThat(SentBackTest.stop("Store orders", "every test errored", ERROR,
            "The second correction is still broken."))
            .startsWith("The acceptance test for task 'Store orders' is broken, not red: every "
                + "test errored.")
            .contains(ERROR).contains("The second correction is still broken.")
            .endsWith("No repair round was started: no worker may change an acceptance test, so "
                + "no candidate can make this one pass.");
    }

    @Test
    void theAnswerIsCountedPerTaskAndCappedBySetting() {
        Task task = new Task(UUID.randomUUID(), 1, "Store orders", "do it",
            Set.of("src/main/OrderStore.java"), Set.of(), List.of(), "src/test/java/swarm", null,
            null, new SwarmPolicy(4, false, 0.2, 0.2, List.of()), TaskState.BLOCKED);

        assertThat(InstructedRetest.hasATest(task)).as("no test file of its own").isFalse();
        task.setAuthoredTestPaths(List.of("src/test/java/swarm/accept/OrderStoreTest.java"));
        assertThat(InstructedRetest.hasATest(task)).isTrue();
        assertThat(InstructedRetest.limit()).isEqualTo(1);
        assertThat(InstructedRetest.left(task)).isTrue();
        task.setInstructedRetests(1);
        assertThat(InstructedRetest.left(task)).isFalse();
        System.setProperty(InstructedRetest.LIMIT_PROPERTY, "99");
        try {
            assertThat(InstructedRetest.limit()).isEqualTo(InstructedRetest.MOST);
            assertThat(InstructedRetest.left(task)).isTrue();
        } finally {
            System.clearProperty(InstructedRetest.LIMIT_PROPERTY);
        }
    }
}
