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

import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build of 2026-10-12 (DEVELOPER_CORRECTIONS section 86), with other names: a
 * test's author corrected its test and changed how it starts; from then on every test method
 * errored in the test's own set-up, for all four candidates, and each candidate read "compiles,
 * nothing passed, nothing failed". That is a broken test, not a red one.
 */
class ATestThatErrorsForEveryCandidateIsBrokenTest {

    private static final Set<String> PROJECT = Set.of("org.example.shop.server");
    private static final String SET_UP = "swarm.accept.OrderStoreTest#storesAnOrder";

    @Test
    void theSameErrorInTheTestsOwnSetUpForEveryCandidateIsABrokenTest() {
        ErroredForEveryCandidate.Finding finding = ErroredForEveryCandidate.find(List.of(
            errored(4, setUpError("port 40112")), errored(4, setUpError("port 40977")),
            errored(4, setUpError("port 41003")), errored(4, setUpError("port 41250"))), PROJECT);

        assertThat(finding).isNotNull();
        assertThat(finding.testId()).isEqualTo(SET_UP);
        assertThat(finding.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(finding.candidates()).isEqualTo(4);
        assertThat(finding.reason())
            .contains("all 4 candidate(s) compiled and none of their code ran")
            .contains("4 acceptance test(s) errored, none passed and none failed")
            .contains(SET_UP).contains("java.lang.IllegalStateException")
            .contains("no frame of the project's own code");
        assertThat(finding.failureText()).startsWith(SET_UP)
            .contains("at org.framework.Container.start(Container.java:88)");
    }

    @Test
    void anErrorThrownFromTheTestsOwnLinesCountsHereThoughNotOnATreeWithNothingBuilt() {
        TestFailure own = new TestFailure(SET_UP, "container is null",
            "java.lang.NullPointerException: container is null\n"
                + "\tat swarm.accept.OrderStoreTest.setUp(OrderStoreTest.java:21)");

        assertThat(BrokenAtStartup.find(errored(1, own), PROJECT))
            .as("before anything is built, a stub that returns null looks the same").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(1, own), errored(1, own)),
            PROJECT)).as("with every candidate's code in the tree it is the test's").isNotNull();
    }

    @Test
    void anythingACandidateTookPartInIsNotABrokenTest() {
        TestFailure setUp = setUpError("x");

        assertThat(ErroredForEveryCandidate.find(List.of(errored(4, setUp),
            new TestResults(1, 0, 3, 0, List.of(setUp), List.of("swarm.accept.OrderStoreTest#a"),
                null, false)), PROJECT)).as("a test passed for one candidate").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(4, setUp),
            new TestResults(0, 1, 3, 0, List.of(setUp))), PROJECT))
            .as("a test failed an assertion for one candidate").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(1, new TestFailure(SET_UP, "x",
            "java.lang.IllegalStateException: x\n"
                + "\tat org.example.shop.server.OrderStore.open(OrderStore.java:14)\n"
                + "\tat swarm.accept.OrderStoreTest.setUp(OrderStoreTest.java:21)"))), PROJECT))
            .as("the candidate's own code is on the stack").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(1, new TestFailure(SET_UP,
            "no bean of type org.example.shop.server.OrderStore",
            "java.lang.IllegalStateException: no bean\n"
                + "\tat org.framework.Container.start(Container.java:88)"))), PROJECT))
            .as("the framework fails about a type the plan writes").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(1, new TestFailure(SET_UP,
            "expected: <1> but was: <0>", "org.opentest4j.AssertionFailedError: expected\n"
                + "\tat swarm.accept.OrderStoreTest.storesAnOrder(OrderStoreTest.java:30)"))),
            PROJECT)).as("an assertion ran").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(errored(4, setUp),
            errored(4, new TestFailure(SET_UP, "x", "java.io.IOException: x\n"
                + "\tat org.framework.Container.start(Container.java:88)"))), PROJECT))
            .as("not the same exception for every candidate").isNull();
        assertThat(ErroredForEveryCandidate.find(List.of(), PROJECT)).isNull();
        assertThat(ErroredForEveryCandidate.find(java.util.Arrays.asList(errored(4, setUp), null),
            PROJECT)).as("a candidate with no acceptance stage").isNull();
    }

    @Test
    void theSameErrorIsToldApartFromDifferentOnesWithoutAskingWhoseCodeThrewIt() {
        assertThat(ErroredForEveryCandidate.sameError(List.of(errored(2, setUpError("a")),
            errored(2, setUpError("b"))))).as("messages are not compared").isTrue();
        assertThat(ErroredForEveryCandidate.sameError(List.of(errored(2, setUpError("a")),
            new TestResults(0, 1, 0, 0, List.of(setUpError("a")))))).isFalse();
        assertThat(ErroredForEveryCandidate.exceptionType(setUpError("a")))
            .isEqualTo("java.lang.IllegalStateException");
        assertThat(ErroredForEveryCandidate.exceptionType(new TestFailure(SET_UP, "m", "")))
            .isEmpty();
    }

    /** The container the test now builds by hand fails to start; nothing of the project ran. */
    private static TestFailure setUpError(String detail) {
        return new TestFailure(SET_UP, "container not started: " + detail,
            "java.lang.IllegalStateException: container not started: " + detail + "\n"
                + "\tat org.framework.Container.start(Container.java:88)\n"
                + "\tat swarm.accept.OrderStoreTest.setUp(OrderStoreTest.java:21)");
    }

    private static TestResults errored(int count, TestFailure first) {
        List<TestFailure> failures = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            failures.add(i == 0 ? first : new TestFailure(
                "swarm.accept.OrderStoreTest#other" + i, first.message(), first.truncatedTrace()));
        }
        return new TestResults(0, 0, count, 0, failures);
    }
}
