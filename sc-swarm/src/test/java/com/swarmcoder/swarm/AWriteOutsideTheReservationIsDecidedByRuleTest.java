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
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.runtime.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task's files are a reservation, extended by rule (owner's decision, 2026-10-08; section 73).
 * When a worker writes a file outside what the plan reserved for its task, the decision is made
 * at once, from the plan, with no model: nobody else holds it - it is the task's; a task built at
 * the same time holds it - refused, naming that task; a task not yet run holds it - refused, and
 * the plan is at fault; protected - refused whoever holds what.
 */
class AWriteOutsideTheReservationIsDecidedByRuleTest {

    private static final String SERVICE = "server/src/main/java/com/shop/OrderService.java";
    private static final String SCREEN = "client/src/main/java/com/shop/OrderScreen.java";
    private static final String REPORT = "server/src/main/java/com/shop/OrderReport.java";
    private static final String HELPER = "server/src/main/java/com/shop/OrderIds.java";
    private static final String TESTS = "server/src/test/java/swarm";

    private final Task service = task("Order service", Set.of(), SERVICE);
    private final Task screen = task("Order screen", Set.of(), SCREEN);
    private final Task report = task("Order report", Set.of(), REPORT);
    /** The service and the screen are built at the same time; the report after them. */
    private final List<List<Task>> waves = List.of(List.of(service, screen), List.of(report));

    @TempDir
    Path checkout;

    // --- the rule itself -----------------------------------------------------------------------

    @Test
    void aFileNoOtherTaskHoldsBecomesTheTasksAndIsThenHeldAgainstTheOthers() {
        ReservationBook book = ReservationBook.of(waves);

        assertThat(book.take(service, "0", HELPER)).as("nobody reserved it: allowed").isNull();
        assertThat(book.takenBy(service.id())).containsExactly(HELPER);
        assertThat(book.take(service, "1", HELPER))
            .as("another candidate of the same task may take it too").isNull();
        assertThat(book.take(screen, "0", HELPER))
            .as("the reservation grew: a task built at the same time is refused it")
            .contains("held by the task 'Order service'")
            .contains("being built at the same time");
        assertThat(book.take(report, "0", HELPER))
            .as("a later task builds on the merged result, so the file is free for it")
            .isNull();
    }

    @Test
    void aFileOfATaskBuiltAtTheSameTimeIsRefusedNamingThatTask() {
        ReservationBook book = ReservationBook.of(waves);

        assertThat(book.take(service, "0", SCREEN))
            .contains(SCREEN).contains("held by the task 'Order screen'")
            .contains("was not written").contains("a fault in the plan");
        assertThat(book.takenBy(service.id())).isEmpty();
        assertThat(book.refusedForLater(service.id()))
            .as("that is a collision, not a wrong order").isEmpty();
    }

    @Test
    void aFileOfATaskNotYetRunIsRefusedAndRememberedAsThePlansFault() {
        ReservationBook book = ReservationBook.of(waves);

        assertThat(book.take(service, "0", REPORT))
            .contains("is to be written by the task 'Order report'")
            .contains("runs after yours").contains("wrong order")
            .contains("Do not make your own copy");
        assertThat(book.take(service, "1", REPORT)).isNotNull();

        assertThat(book.refusedForLater(service.id()).get(REPORT))
            .isEqualTo(new ReservationBook.RefusedForLater("Order report", Set.of("0", "1")));
    }

    @Test
    void whatIsNotSourceAndWhatIsABuildFileIsNobodys() {
        Task module = task("The whole server", Set.of(), "server");
        ReservationBook book = ReservationBook.of(List.of(List.of(service, module)));

        assertThat(book.take(service, "0", "server/pom.xml")).isNull();
        assertThat(book.take(service, "0", "server/notes.txt")).isNull();
        assertThat(book.takenBy(service.id())).as("neither is recorded as taken").isEmpty();
        assertThat(book.take(service, "0", "server/src/main/java/com/shop/Other.java"))
            .as("a directory another task reserved holds every source file under it")
            .contains("'The whole server'");
    }

    @Test
    void aWriteSetTheProductWidenedIsReadAsItIsNow() {
        ReservationBook book = ReservationBook.of(waves);
        assertThat(book.take(service, "0", HELPER)).isNull();

        screen.setWriteSet(Set.of(SCREEN, "client/src/main/java/com/shop/Widened.java"));

        assertThat(book.take(service, "0", "client/src/main/java/com/shop/Widened.java"))
            .contains("'Order screen'");
    }

    @Test
    void aTaskOfNoPlanAndAPlanThatCannotBeReadHoldNothing() {
        assertThat(ReservationBook.of(null).take(service, "0", SCREEN)).isNull();
        Task stranger = task("Not of this plan", Set.of(), "x/Y.java");
        assertThat(ReservationBook.of(waves).take(stranger, "0", SCREEN)).isNull();
    }

    // --- containment: what is protected is refused before anybody is asked ---------------------

    @Test
    void whatIsProtectedIsRefusedWhoeverHoldsWhat() {
        List<String> asked = new ArrayList<>();
        PathPolicy.OtherTasks free = path -> {
            asked.add(path);
            return null;
        };
        Set<String> reserved = Set.of(SERVICE);

        assertThat(PathPolicy.check(".swarmcoder/verify.yaml", reserved, TESTS, List.of(), free)
            .lethal()).isTrue();
        assertThat(PathPolicy.check(".git/hooks/pre-commit", reserved, TESTS, List.of(), free)
            .lethal()).isTrue();
        assertThat(PathPolicy.check(TESTS + "/accept/OrderTest.java", reserved, TESTS, List.of(),
            free).lethal()).as("the acceptance tests").isTrue();
        assertThat(PathPolicy.check(TESTS + "/accept/order.journey.yaml", reserved, TESTS,
            List.of(), free).lethal()).as("a journey").isTrue();
        assertThat(PathPolicy.check("billing/src/main/java/Ledger.java", reserved, TESTS,
            List.of("billing"), free).lethal()).as("a locked module").isTrue();
        assertThat(PathPolicy.check(null, reserved, TESTS, List.of(), free).lethal())
            .as("a path that left the repository").isTrue();
        assertThat(asked).as("none of these was ever put to the plan").isEmpty();

        assertThat(PathPolicy.check(SERVICE, reserved, TESTS, List.of(), free).allowed()).isTrue();
        assertThat(asked).as("nor is a file of the task's own reservation").isEmpty();
    }

    @Test
    void outsideTheReservationIsWrittenAndRecordedOrRefusedForTheTaskThatHoldsIt() {
        Set<String> reserved = Set.of(SERVICE);

        PathPolicy.Verdict taken = PathPolicy.check(HELPER, reserved, TESTS, List.of(),
            path -> null);
        assertThat(taken.allowed()).as("still told apart from the task's own files").isFalse();
        assertThat(taken.lethal()).isFalse();
        assertThat(taken.heldByAnotherTask()).isFalse();

        PathPolicy.Verdict held = PathPolicy.check(SCREEN, reserved, TESTS, List.of(),
            path -> path + " is held by the task 'Order screen'");
        assertThat(held.heldByAnotherTask()).isTrue();
        assertThat(held.lethal()).as("a worker is not stopped for asking").isFalse();
        assertThat(held.reason()).contains("'Order screen'");

        assertThat(PathPolicy.check(SCREEN, reserved, TESTS, List.of(), null))
            .as("with no plan to ask, exactly the decision there was before")
            .isEqualTo(PathPolicy.check(SCREEN, reserved, TESTS, List.of()));
    }

    // --- at the worker's tools -----------------------------------------------------------------

    @Test
    void aWorkersWriteIsDecidedWhenItIsMade() throws Exception {
        ReservationBook book = ReservationBook.of(waves);
        WorkerToolbox toolbox = new WorkerToolbox(checkout, service);
        toolbox.setOtherTasks(book.forWorker(service, "0"));

        String own = toolbox.writeFile(SERVICE, "class OrderService {}");
        assertThat(own).startsWith("wrote ").doesNotContain("[write policy]");

        String free = toolbox.writeFile(HELPER, "class OrderIds {}");
        assertThat(free).startsWith("wrote ").contains("[write policy]").contains("KEPT")
            .contains("no other task holds that file");
        assertThat(Files.exists(checkout.resolve(HELPER))).isTrue();
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly(HELPER);

        String beside = toolbox.writeFile(SCREEN, "class OrderScreen {}");
        assertThat(beside).startsWith("error:").contains("'Order screen'");
        assertThat(Files.exists(checkout.resolve(SCREEN))).as("it was not written").isFalse();

        String later = toolbox.writeFile(REPORT, "class OrderReport {}");
        assertThat(later).startsWith("error:").contains("'Order report'")
            .contains("runs after yours");
        assertThat(Files.exists(checkout.resolve(REPORT))).isFalse();

        String tests = toolbox.writeFile(TESTS + "/accept/OrderTest.java", "class OrderTest {}");
        assertThat(tests).startsWith("error:").contains("acceptance tests are protected");
        String contract = toolbox.writeFile(".swarmcoder/verify.yaml", "compile: true");
        assertThat(contract).startsWith("error:").contains("protected");

        assertThat(toolbox.outOfWriteSetPaths())
            .as("only what was written is on the candidate").containsExactly(HELPER);
        assertThat(toolbox.blockingViolations())
            .as("the two protected writes count toward the stop; the two held ones do not")
            .isEqualTo(2);
    }

    @Test
    void withNoPlanToAskAWriteOutsideIsWrittenAndRecordedAsItAlwaysWas() throws Exception {
        WorkerToolbox toolbox = new WorkerToolbox(checkout, service);

        assertThat(toolbox.writeFile(SCREEN, "class OrderScreen {}")).startsWith("wrote ");
        assertThat(toolbox.outOfWriteSetPaths()).containsExactly(SCREEN);
    }

    // --- a file of a task not yet run is the plan's fault, not the workers' --------------------

    @Test
    void twoWorkersRefusedTheFileOfATaskNotYetRunStopTheTaskBeforeARepairRound() {
        ReservationBook book = ReservationBook.of(waves);
        book.take(service, "0", REPORT);
        book.take(service, "1", REPORT);

        FileOfATaskNotYetRun.Finding finding = FileOfATaskNotYetRun.find(service,
            List.of(failed(service, 0), failed(service, 1)), waves, book);

        assertThat(finding).isNotNull();
        assertThat(finding.refused()).isTrue();
        assertThat(finding.files()).containsEntry(REPORT, "Order report");
        assertThat(FileOfATaskNotYetRun.planBlame(service, finding))
            .startsWith("Task BLOCKED by its plan, not by its candidates")
            .contains("2 worker(s) of this task tried to write a source file")
            .contains("were refused")
            .contains("'Order service' depends on 'Order report'");
    }

    @Test
    void oneWorkerRefusedOrACandidateThatPassedIsLeftToTheOrdinaryPath() {
        ReservationBook one = ReservationBook.of(waves);
        one.take(service, "0", REPORT);
        assertThat(FileOfATaskNotYetRun.find(service, List.of(failed(service, 0),
            failed(service, 1)), waves, one))
            .as("one worker reaching for another task's file is that worker's mistake").isNull();

        Task told = task("Order service", Set.of(REPORT), SERVICE);
        List<List<Task>> toldWaves = List.of(List.of(told, screen), List.of(report));
        ReservationBook named = ReservationBook.of(toldWaves);
        named.take(told, "0", REPORT);
        assertThat(FileOfATaskNotYetRun.find(told, List.of(failed(told, 0)), toldWaves, named))
            .as("unless the task's own read set names the file").isNotNull();

        ReservationBook two = ReservationBook.of(waves);
        two.take(service, "0", REPORT);
        two.take(service, "1", REPORT);
        CandidateSolution passed = new CandidateSolution(UUID.randomUUID(), service.id(), 2, "b2",
            null, "diff", compiled(), null, null, CandidateState.SURVIVED, null);
        assertThat(FileOfATaskNotYetRun.find(service, List.of(failed(service, 0), passed), waves,
            two)).as("a candidate did it without the file").isNull();

        ReservationBook incidental = ReservationBook.of(waves);
        incidental.take(service, "0", REPORT);
        incidental.take(service, "1", REPORT);
        assertThat(FileOfATaskNotYetRun.find(service, List.of(failed(service, 0),
            failed(service, 1), failed(service, 2)), waves, incidental))
            .as("a third candidate failed without ever reaching for the file: not every "
                + "candidate, so the repair round is not skipped").isNull();
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static Task task(String title, Set<String> readSet, String... writeSet) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of(writeSet), readSet,
            List.of(), TESTS, null, null,
            new SwarmPolicy(2, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static VerificationReport compiled() {
        return new VerificationReport(UUID.randomUUID(), true, true, null, null, null, null,
            Duration.ZERO, "", null);
    }

    private static CandidateSolution failed(Task task, int worker) {
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null,
            "diff", compiled(), null, null, CandidateState.FAILED, null);
    }
}
