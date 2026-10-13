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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CompileFailure;
import com.swarmcoder.domain.CompileFailureCause;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TestFailure;
import com.swarmcoder.domain.TestResults;
import com.swarmcoder.domain.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live supervised build of 2026-10-12 (DEVELOPER_CORRECTIONS section 83), with other names: the
 * first task's candidates compiled, the task's own protected test imported types the second
 * task writes, and four candidates and four repair workers failed "compiles=false". Such a
 * task stops on its first verified candidate, the question says on what, and the reason comes
 * before the list of workers.
 */
class NoRepairRoundWhenTheTasksOwnTestNeedsATaskNotYetRunTest {

    private static final String ORDER = "shop-shared/src/main/java/org/example/shop/shared/Order.java";
    private static final String STORE =
        "shop-server/src/main/java/org/example/shop/server/OrderStore.java";
    private static final String TEST = "shop-server/src/test/java/swarm/accept/OrderStoreTest.java";
    private static final String MESSAGE = "package org.example.shop.server does not exist";

    private final Task shared = task("Add the shared order types", ORDER);
    private final Task server = task("Store orders on the server", STORE);
    private final List<List<Task>> waves = List.of(List.of(shared), List.of(server));
    private final Function<String, String> tests = Map.of(TEST, """
        package swarm.accept;

        import org.example.shop.shared.Order;
        import org.example.shop.server.OrderStore;

        class OrderStoreTest { }
        """)::get;

    NoRepairRoundWhenTheTasksOwnTestNeedsATaskNotYetRunTest() {
        shared.setAuthoredTestPaths(List.of(TEST));
    }

    @Test
    void oneVerifiedCandidateIsEnoughAndTheMessageNamesTheTypeAndItsTask() {
        TestOfATaskNotYetRun.Finding finding = TestOfATaskNotYetRun.find(shared,
            List.of(failedIn(shared, 0, TEST, 4, MESSAGE, List.of(TEST))), waves, tests);

        assertThat(finding).isNotNull();
        assertThat(finding.type()).isEqualTo("org.example.shop.server.OrderStore");
        assertThat(finding.writer()).isEqualTo("Store orders on the server");
        String blame = TestOfATaskNotYetRun.planBlame(shared, finding);
        assertThat(blame)
            .startsWith("Task BLOCKED by its plan, not by its candidates")
            .contains(TEST + ":4 " + MESSAGE)
            .contains("this task's own protected acceptance test")
            .contains("`org.example.shop.server.OrderStore`, which task "
                + "'Store orders on the server' writes, and that task has not run yet")
            .contains("no repair round was started");
        assertThat(blame.length()).as("short: the supervisor is shown about 1,300 characters")
            .isLessThan(700);
    }

    @Test
    void anythingElseIsLeftToTheOrdinaryRound() {
        assertThat(TestOfATaskNotYetRun.find(shared, List.of(failedIn(shared, 0, ORDER, 7,
                "';' expected", List.of(ORDER))), waves, tests))
            .as("the candidate's own file does not compile").isNull();
        assertThat(TestOfATaskNotYetRun.find(shared, List.of(failedIn(shared, 0, TEST, 4, MESSAGE,
                List.of(TEST, ORDER))), waves, tests))
            .as("not its only failure: its own file fails too").isNull();
        assertThat(TestOfATaskNotYetRun.find(shared, List.of(failedIn(shared, 0, TEST, 9,
                "incompatible types: int cannot be converted to java.lang.String",
                List.of(TEST))), waves, tests))
            .as("the compiler's message is not about a type a later task writes").isNull();
        assertThat(TestOfATaskNotYetRun.find(server, List.of(failedIn(server, 0, TEST, 4, MESSAGE,
                List.of(TEST))), waves, tests))
            .as("not this task's test").isNull();
        server.setAuthoredTestPaths(List.of(TEST));
        assertThat(TestOfATaskNotYetRun.find(server, List.of(failedIn(server, 0, TEST, 4, MESSAGE,
                List.of(TEST))), waves, tests))
            .as("the task writes the type itself: its candidate did not").isNull();
        assertThat(TestOfATaskNotYetRun.find(shared, List.of(
                failedIn(shared, 0, TEST, 4, MESSAGE, List.of(TEST)), compiled(shared, 1)),
                waves, tests))
            .as("the test compiled for one candidate").isNull();
        assertThat(TestOfATaskNotYetRun.find(shared, List.of(failedIn(shared, 0, TEST, 4, MESSAGE,
                List.of(TEST))), null, tests))
            .as("a plan that cannot be read says nothing").isNull();
    }

    @Test
    void theQuestionOfABlockedTaskSaysOnWhatBeforeItListsTheWorkers() {
        String headline = "Task BLOCKED after swarm + repair round: 'Add the shared order types'\n\n";
        List<CandidateSolution> evidence = List.of(
            failedIn(shared, 0, TEST, 4, MESSAGE, List.of(TEST)),
            failedIn(shared, 1, TEST, 4, MESSAGE, List.of(TEST)));

        assertThat(BlockedReason.of(shared, evidence, headline))
            .isEqualTo("Why: it does not compile - " + TEST + ":4 " + MESSAGE + "\nThat file is "
                + "this task's own protected acceptance test; no worker may change it.\n\n");
        assertThat(BlockedReason.of(server, evidence, headline))
            .as("another task's file is not called this task's test")
            .isEqualTo("Why: it does not compile - " + TEST + ":4 " + MESSAGE + "\n\n");
        assertThat(BlockedReason.of(shared, evidence,
            TestOfATaskNotYetRun.planBlame(shared, TestOfATaskNotYetRun.find(shared, evidence,
                waves, tests))))
            .as("said once: the headline already has it").isEmpty();
        assertThat(BlockedReason.of(shared, List.of(compiled(shared, 0)), headline)).isEmpty();
        assertThat(BlockedReason.shortened("x".repeat(900)).length())
            .isEqualTo(BlockedReason.MESSAGE_LIMIT + 3);
    }

    @Test
    void aTestThatFailsIsNamedWhenEverythingCompiled() {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, true,
            new TestResults(0, 1, 0, 0, List.of(new TestFailure(
                "swarm.accept.OrderStoreTest#storesAnOrder", "expected: <1> but was: <0>", ""))),
            null, null, null, Duration.ZERO, "", null);
        CandidateSolution failed = new CandidateSolution(UUID.randomUUID(), shared.id(), 0, "b",
            null, "diff", report, null, null, CandidateState.KILLED, null);

        assertThat(BlockedReason.of(shared, List.of(failed), "Task BLOCKED\n\n"))
            .isEqualTo("Why: acceptance test swarm.accept.OrderStoreTest#storesAnOrder fails - "
                + "expected: <1> but was: <0>\n\n");
    }

    @Test
    void theStopReasonIsAppendedAndSaysNothingAboutTheWorkersCode() {
        assertThat(KillReason.PLAN_FAULT.ordinal())
            .as("it keeps its number: the enum is stored by ordinal, and later reasons "
                + "(section 91) are appended after it")
            .isEqualTo(17);
        assertThat(KillReason.ROUND_TIME_UP.ordinal()).isEqualTo(KillReason.PLAN_FAULT.ordinal() - 1);
        assertThat(KillReason.PLAN_FAULT.sentence()).contains("the plan gave its task a test");
        assertThat(StoppedWork.worthVerifying(KillReason.PLAN_FAULT, "diff --git a/x b/x\n+x\n"))
            .as("its task's test cannot compile for anybody").isFalse();
        assertThat(com.swarmcoder.inference.ContextDeath.saysNothingAboutRoom(KillReason.PLAN_FAULT))
            .isTrue();
    }

    private static Task task(String title, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), Set.of(),
            List.of(), "shop-server/src/test/java/swarm/accept", null, null,
            new SwarmPolicy(4, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static CandidateSolution failedIn(Task task, int worker, String file, int line,
                                              String message, List<String> failingFiles) {
        VerificationReport report = new VerificationReport(UUID.randomUUID(), true, false, null,
            null, null, null, Duration.ZERO, "", null);
        report.setCompileFailure(new CompileFailure(CompileFailureCause.PRE_EXISTING, file, line,
            message, failingFiles, file.contains("/src/test/"), file.contains("/swarm/accept/"),
            true, null));
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b", null, "diff",
            report, null, null, CandidateState.KILLED, null);
    }

    private static CandidateSolution compiled(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b", null, "diff",
            new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
                Duration.ZERO, "", null), null, null, CandidateState.SURVIVED, null);
    }
}
