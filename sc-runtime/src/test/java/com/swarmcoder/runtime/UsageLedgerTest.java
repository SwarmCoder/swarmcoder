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
package com.swarmcoder.runtime;

import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.domain.UsageReport;
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.runtime.CloudGate.Breach;
import com.swarmcoder.runtime.CloudGate.BudgetExhaustedException;
import com.swarmcoder.runtime.CloudGate.Cap;
import com.swarmcoder.runtime.CloudGate.Limits;
import com.swarmcoder.runtime.CloudGate.Where;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 93: every model call is recorded with the server's own input and output counts, per
 * run and role, paid and local apart, and the cloud budgets are held against those figures.
 */
class UsageLedgerTest {

    private static final String CLOUD = "https://models.example.com/v1";
    private static final String LOCAL = "http://spark.example.org:8000";

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @AfterEach
    void noRecorderIsLeftBehind() {
        RunMeter.recordTo(null);
    }

    private static UsageLedger ledgerOver(CloudGate gate, ArtifactStore store) {
        UsageLedger ledger = new UsageLedger(gate, store,
            UsageLedger.normalized(LOCAL)::equals);
        RunMeter.recordTo(ledger);
        return ledger;
    }

    /** One call, made and ended the way the clients make and end theirs. */
    private static void call(String role, String task, String endpoint, int input, int output,
                             int cached) {
        RunMeter.Open open = RunMeter.begin(new RunMeter.Tag(role, task, null), "a-model",
            endpoint, null);
        open.end();
        open.usage(input, output);
        open.cached(cached);
        open.done();
    }

    @Test
    void paidAndLocalCallsAreCountedApartAndOnlyPaidOnesAreChargedToTheGate() {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        UsageLedger ledger = ledgerOver(gate, null);

        try (var in = CloudGate.enter(new Where(project, story, run))) {
            call("architect", null, CLOUD, 20_000, 400, 15_000);
            call("architect", null, CLOUD, 24_000, 600, -1);
            call("worker", "t1", LOCAL, 9_000, 1_200, -1);
            call("worker", "t2", LOCAL, 11_000, 800, -1);
            call("judge", null, LOCAL, 3_000, 50, -1);
        }

        RunUsageRecord usage = ledger.usageOf(run);
        assertThat(usage.storyId()).isEqualTo(story);
        assertThat(usage.projectId()).isEqualTo(project);
        ModelUsageRecord paid = UsageReport.total(usage.models(), true);
        ModelUsageRecord local = UsageReport.total(usage.models(), false);
        assertThat(paid.calls()).isEqualTo(2);
        assertThat(paid.inputTokens()).isEqualTo(44_000);
        assertThat(paid.outputTokens()).isEqualTo(1_000);
        assertThat(paid.cachedInputTokens()).isEqualTo(15_000);
        assertThat(paid.cachedCalls()).as("one of the two said what it took from its cache")
            .isEqualTo(1);
        assertThat(local.calls()).isEqualTo(3);
        assertThat(local.inputTokens()).isEqualTo(23_000);
        assertThat(local.outputTokens()).isEqualTo(2_050);
        assertThat(usage.models()).as("a worker's calls are kept per task")
            .filteredOn(row -> "worker".equals(row.role())).hasSize(2);

        assertThat(gate.spendOfRun(run))
            .as("the gate holds the paid calls' true figures and nothing of the local ones")
            .isEqualTo(new CloudGate.Spend(44_000, 1_000));
        assertThat(gate.spendOfStory(story)).isEqualTo(new CloudGate.Spend(44_000, 1_000));
        assertThat(gate.spendOfProject(project)).isEqualTo(new CloudGate.Spend(44_000, 1_000));
    }

    @Test
    void theReportsTotalsAreTheSumOfTheCalls() {
        UsageLedger ledger = ledgerOver(null, null);
        long in = 0;
        long out = 0;
        try (var entered = CloudGate.enter(new Where(project, story, run))) {
            for (int i = 1; i <= 7; i++) {
                String role = i % 2 == 0 ? "planner" : "test author";
                call(role, null, i % 3 == 0 ? LOCAL : CLOUD, 1_000 * i, 10 * i, -1);
                in += 1_000 * i;
                out += 10 * i;
            }
            LookupMeter.record("planner", LookupMeter.Kind.TREE, 900);
            LookupMeter.record("planner", LookupMeter.Kind.TREE, 100);
            LookupMeter.record("planner", LookupMeter.Kind.WHOLE_FILE, 3_000);
        }

        RunUsageRecord usage = ledger.usageOf(run);
        ModelUsageRecord all = UsageReport.total(usage.models(), null);
        assertThat(all.calls()).isEqualTo(7);
        assertThat(all.inputTokens()).isEqualTo(in);
        assertThat(all.outputTokens()).isEqualTo(out);

        String report = UsageReport.render("Run x.", usage.models(), usage.lookups());
        assertThat(report)
            .contains("| role | server | calls | input tokens | input tokens per call | output "
                + "tokens | cached input tokens | seconds in calls |")
            .as("the last row is every call added up").contains("| **all** |  | 7 | " + in + " | "
                + (in / 7) + " | " + out + " |")
            .contains("| planner | paid cloud | 2 | 6000 | 3000 | 60 |")
            .contains("| planner | local | 1 | 6000 | 6000 | 60 |")
            .contains("| test author | paid cloud | 3 | 13000 | 4333 | 130 |")
            .contains("| test author | local | 1 | 3000 | 3000 | 30 |")
            .contains("Paid cloud: 19000 in / 190 out over 5 calls; local: 9000 in / 90 out.")
            .contains("- planner: tree and graph queries 2/1000; whole files 1/3000; "
                + "structured 25%");
        assertThat(report.length()).as("compact enough for a tool reply").isLessThan(2_500);
    }

    @Test
    void aBudgetIsHeldAgainstTheTrueFiguresAndTheRunStopsBeforeItsNextCall() {
        List<Breach> raised = new ArrayList<>();
        // No more than 1,000 output tokens per run: a limit estimates of a session never reached,
        // because a session's output was counted as input.
        CloudGate gate = new CloudGate(new Limits(new Cap(0, 0, 1_000), Cap.NONE, Cap.NONE),
            raised::add);
        gate.countFromUsage();
        ledgerOver(gate, null);

        try (var in = CloudGate.enter(new Where(project, story, run))) {
            gate.charge(50_000); // an estimate adds nothing now: it only asks "may I?"
            call("architect", null, CLOUD, 30_000, 700, -1);
            gate.charge(50_000);
            gate.chargeOutput(9_999);
            assertThat(gate.spendOfRun(run)).isEqualTo(new CloudGate.Spend(30_000, 700));
            assertThat(raised).isEmpty();

            call("architect", null, CLOUD, 31_000, 400, -1); // 1,100 written: over
            assertThat(raised).as("counting a call that has happened stops nothing and asks "
                + "nothing").isEmpty();
            assertThat(gate.standing(new Where(project, story, run))).isPresent();
            assertThatThrownBy(() -> gate.charge(1))
                .as("the run is refused its next call")
                .isInstanceOf(BudgetExhaustedException.class);
            assertThat(raised).as("and that is when its question is raised, once").hasSize(1);
            assertThat(raised.get(0).direction()).isEqualTo(CloudGate.Direction.OUTPUT);
            assertThat(raised.get(0).used()).isEqualTo(new CloudGate.Spend(61_000, 1_100));
            assertThatThrownBy(() -> gate.charge(1)).isInstanceOf(BudgetExhaustedException.class);

            call("worker", "t1", LOCAL, 500_000, 90_000, -1);
            assertThat(gate.spendOfRun(run)).as("local tokens are never held against it")
                .isEqualTo(new CloudGate.Spend(61_000, 1_100));

            assertThat(gate.extendForRun(run)).isPresent();
            gate.charge(1); // extended: it may go on
        }
        assertThat(raised).hasSize(1);
    }

    @Test
    void aServerThatReportsNoUsageIsCountedFromTheSizeOfItsTextAndSaidSo() {
        CloudGate gate = new CloudGate(Limits.NONE, b -> { });
        gate.countFromUsage();
        UsageLedger ledger = ledgerOver(gate, null);

        try (var in = CloudGate.enter(new Where(project, story, run))) {
            RunMeter.Open open = RunMeter.begin(new RunMeter.Tag("reviewer", null, null),
                "a-model", CLOUD, null);
            open.request(8_000, -1);
            open.answered(400);
            open.done();
        }

        ModelUsageRecord row = ledger.usageOf(run).models().get(0);
        assertThat(row.inputTokens()).isEqualTo(2_000);
        assertThat(row.outputTokens()).isEqualTo(100);
        assertThat(row.estimatedCalls()).isEqualTo(1);
        assertThat(gate.spendOfRun(run)).isEqualTo(new CloudGate.Spend(2_000, 100));
        assertThat(UsageReport.render("Run x.", ledger.usageOf(run).models(), List.of()))
            .contains("1 call(s) were counted from the size of their text");
    }

    @Test
    void aCallMadeForNoRunIsCountedOutsideRunsAndASessionThatNamesItsRunIsCountedThere() {
        UsageLedger ledger = ledgerOver(null, null);

        call("utility", null, CLOUD, 100, 10, -1);
        RunMeter.Open open = RunMeter.begin(new RunMeter.Tag("worker", "t1", "0"), "a-model",
            LOCAL, run.toString());
        open.usage(700, 70);
        open.done();
        open.done(); // every way a call ends may say so; it is counted once

        assertThat(ledger.outsideRuns().models()).hasSize(1);
        assertThat(ledger.usageOf(run).models()).hasSize(1);
        assertThat(ledger.usageOf(run).models().get(0).calls()).isEqualTo(1);
        assertThat(ledger.usageOf(run).models().get(0).inputTokens()).isEqualTo(700);
    }

    @Test
    void whatWasCountedIsStoredWithTheRunAndGoesOnAfterARestart(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            assertThat(store.root().runUsage()).as("a store from before has none").isEmpty();
            UsageLedger ledger = ledgerOver(null, store);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                call("architect", null, CLOUD, 5_000, 300, 4_000);
                call("worker", "t1", LOCAL, 9_000, 900, -1);
                LookupMeter.record("architect", LookupMeter.Kind.SEARCH, 1_200);
            }
            ledger.flush();
            store.append(() -> null).get(); // the writes above are queued; wait for them
        }
        RunMeter.recordTo(null);
        try (ArtifactStore store = new ArtifactStore(dir)) {
            RunUsageRecord stored = store.root().runUsage().get(run);
            assertThat(stored).isNotNull();
            assertThat(stored.storyId()).isEqualTo(story);
            assertThat(stored.projectId()).isEqualTo(project);
            assertThat(UsageReport.spendLine(stored.models()))
                .isEqualTo("Paid cloud: 5000 in / 300 out over 1 call; local: 9000 in / 900 out");
            assertThat(stored.lookups()).hasSize(1);
            assertThat(stored.lookups().get(0).chars()).isEqualTo(1_200);

            UsageLedger ledger = ledgerOver(null, store);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                call("architect", null, CLOUD, 6_000, 200, -1);
            }
            assertThat(UsageReport.total(ledger.usageOf(run).models(), true).inputTokens())
                .as("the run's earlier calls are still in its total").isEqualTo(11_000);
            assertThat(UsageReport.total(ledger.usageOf(run).models(), true).calls())
                .isEqualTo(2);
        }
    }

    @Test
    void anAddressOnThisMachineOrAPrivateNetworkIsLocalAndANameIsNeverLookedUp() {
        assertThat(UsageLedger.onThisNetwork("http://localhost:8000")).isTrue();
        assertThat(UsageLedger.onThisNetwork("http://127.0.0.1:8000")).isTrue();
        assertThat(UsageLedger.onThisNetwork("http://[::1]:8000")).isTrue();
        assertThat(UsageLedger.onThisNetwork("http://192.168.0.10:8000")).isTrue();
        assertThat(UsageLedger.onThisNetwork("https://models.example.com")).isFalse();
        assertThat(UsageLedger.onThisNetwork("https://8.8.8.8")).isFalse();
        assertThat(UsageLedger.normalized("HTTP://Spark.Example.org:8000/v1/"))
            .isEqualTo("http://spark.example.org:8000");
    }
}
