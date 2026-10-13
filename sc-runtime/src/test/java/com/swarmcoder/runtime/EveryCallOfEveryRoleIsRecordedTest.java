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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CallUsageRecord;
import com.swarmcoder.domain.LookupUsageRecord;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.domain.UsageReport;
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.RunMeter;
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

/**
 * Section 98: a five-story build on the local server (87.2 million tokens, 4,065 calls) left
 * no row in the usage ledger, and what the ledger would have kept had gaps of its own: a call
 * made for no run was stored nowhere, the thinking was dropped, nothing was kept per call, no
 * repeated lookup was counted, and a planning role's session named no run.
 */
class EveryCallOfEveryRoleIsRecordedTest {

    private static final String CLOUD = "https://models.example.com/v1";
    private static final String LOCAL = "http://spark.example.org:8000";

    private final UUID project = UUID.randomUUID();
    private final UUID story = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @AfterEach
    void noRecorderIsLeftBehind() {
        RunMeter.recordTo(null);
    }

    private static UsageLedger ledgerOver(ArtifactStore store) {
        UsageLedger ledger = new UsageLedger(null, store, UsageLedger.normalized(LOCAL)::equals);
        RunMeter.recordTo(ledger);
        return ledger;
    }

    private static void call(RunMeter.Tag tag, String endpoint, int input, int output, int cached,
                             int thinking) {
        RunMeter.Open open = RunMeter.begin(tag, "a-model", endpoint, null);
        open.end();
        open.usage(input, output);
        open.cached(cached);
        open.reasoning(thinking);
        open.done();
    }

    private static RunMeter.Tag tag(String role, String purpose) {
        return new RunMeter.Tag(role, null, null, purpose, null);
    }

    /** Every role a build uses, each on the local server, as the audited build ran them. */
    private static final List<String> ROLES = List.of("architect", "planner", "reviewer",
        "test author", "expert", "judge", "answer check", "worker");

    @Test
    void everyRolesCallOnALocalServerIsARowAndACallOfItsRunAndStory(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UsageLedger ledger = ledgerOver(store);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                int n = 0;
                for (String role : ROLES) {
                    n++;
                    call(new RunMeter.Tag(role, "worker".equals(role) ? "task-1" : null,
                        "worker".equals(role) ? "2" : null, "what the " + role + " does",
                        "worker".equals(role) ? "candidate-7" : null), LOCAL, 1_000 * n, 10 * n,
                        500 * n, n);
                }
            }
            ledger.flush();
            store.append(() -> null).get();

            RunUsageRecord stored = store.root().runUsage().get(run);
            assertThat(stored).as("a build on the local server is stored like any other")
                .isNotNull();
            assertThat(stored.runId()).isEqualTo(run);
            assertThat(stored.storyId()).isEqualTo(story);
            assertThat(stored.projectId()).isEqualTo(project);
            assertThat(stored.models()).extracting(ModelUsageRecord::role)
                .containsExactlyElementsOf(ROLES);
            assertThat(stored.models()).allMatch(row -> !row.paid() && row.calls() == 1);

            List<CallUsageRecord> calls = stored.calls();
            assertThat(calls).as("and every call is kept one by one").hasSize(ROLES.size());
            CallUsageRecord judge = calls.get(ROLES.indexOf("judge"));
            assertThat(judge.role()).isEqualTo("judge");
            assertThat(judge.purpose()).isEqualTo("what the judge does");
            assertThat(judge.input()).isEqualTo(6_000);
            assertThat(judge.cachedInput()).isEqualTo(3_000);
            assertThat(judge.output()).isEqualTo(60);
            assertThat(judge.thinking()).as("thinking is no longer dropped").isEqualTo(6);
            assertThat(calls.get(ROLES.indexOf("answer check")).input()).isEqualTo(7_000);
            CallUsageRecord worker = calls.get(ROLES.indexOf("worker"));
            assertThat(worker.task()).isEqualTo("task-1");
            assertThat(worker.worker()).isEqualTo("2");
            assertThat(worker.candidate()).isEqualTo("candidate-7");
            ModelUsageRecord all = UsageReport.total(stored.models(), false);
            assertThat(all.thinkingTokens()).isEqualTo(36);
            assertThat(all.thinkingCalls()).isEqualTo(8);
        }
    }

    @Test
    void aCallForNoRunIsKeptWithTheOpenProjectStoredAndGoesOnAfterARestart(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UsageLedger ledger = ledgerOver(store).forCallsOutsideRuns(() -> project);
            // The Console's wizards and its chat work for a project and for no run.
            call(tag("analyst", "the requirements wizard"), LOCAL, 4_000, 300, -1, 120);
            call(tag("story planner", "the planning wizard"), LOCAL, 5_000, 400, -1, -1);
            call(tag("chat", "the Console's chat"), CLOUD, 2_000, 100, -1, -1);
            LookupMeter.record("analyst", LookupMeter.Kind.LISTING, 300, false,
                LookupMeter.question("list_folder", "src"));

            assertThat(ledger.outsideRuns().models())
                .as("nothing is left under no project while one is open").isEmpty();
            ledger.flush();
            store.append(() -> null).get();
        }
        RunMeter.recordTo(null);
        try (ArtifactStore store = new ArtifactStore(dir)) {
            RunUsageRecord stored = store.root().runUsage().get(project);
            assertThat(stored).as("before, a call for no run was stored nowhere").isNotNull();
            assertThat(stored.outsideRuns()).isTrue();
            assertThat(stored.runId()).isNull();
            assertThat(stored.projectId()).isEqualTo(project);
            assertThat(stored.models()).extracting(ModelUsageRecord::role)
                .containsExactly("analyst", "story planner", "chat");
            assertThat(stored.calls()).extracting(CallUsageRecord::purpose).containsExactly(
                "the requirements wizard", "the planning wizard", "the Console's chat");
            assertThat(stored.lookups()).hasSize(1);
            assertThat(UsageReport.spendLine(stored.models())).isEqualTo(
                "Paid cloud: 2000 in / 100 out over 1 call; local: 9000 in / 700 out");

            UsageLedger ledger = ledgerOver(store).forCallsOutsideRuns(() -> project);
            call(tag("chat", "the Console's chat"), CLOUD, 3_000, 150, -1, -1);
            RunUsageRecord now = ledger.outsideRunsOf(project);
            assertThat(now.calls()).hasSize(4);
            assertThat(UsageReport.total(now.models(), true).inputTokens()).isEqualTo(5_000);
        }
    }

    @Test
    void thinkingTheServerGaveNoCountOfIsEstimatedFromItsLengthAndSaidToBe() {
        UsageLedger ledger = ledgerOver(null);
        try (var in = CloudGate.enter(new Where(project, story, run))) {
            call(tag("architect", "a"), LOCAL, 10_000, 900, -1, 600);
            RunMeter.Open silent = RunMeter.begin(tag("architect", "a"), "a-model", LOCAL, null);
            silent.end();
            silent.usage(10_000, 500);
            silent.thought(1_600); // characters of thinking read from the answer
            silent.done();
            call(tag("architect", "a"), LOCAL, 10_000, 50, -1, -1); // it did not think
        }
        RunUsageRecord usage = ledger.usageOf(run);
        ModelUsageRecord architect = usage.models().get(0);
        assertThat(architect.thinkingCalls()).isEqualTo(2);
        assertThat(architect.thinkingTokens()).isEqualTo(600 + 400);
        assertThat(architect.estimatedThinkingCalls()).isEqualTo(1);
        assertThat(architect.outputTokens()).as("thinking is part of the output").isEqualTo(1_450);
        assertThat(usage.calls()).extracting(CallUsageRecord::thinking)
            .containsExactly(600L, 400L, -1L);
        assertThat(UsageReport.render("Run x.", usage.models(), List.of(), usage.calls(), "", 1))
            .contains("| architect | local | 3 of 3 | 10000 | 10000 | a | 1000 | 0 |")
            .contains("The thinking of 1 call(s) is the length of the thinking text / 4");
    }

    @Test
    void theSameQuestionAskedAgainInARunIsARepeatWhoeverAsksAndAfterARestart(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UsageLedger ledger = ledgerOver(store);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                LookupMeter.record("architect", LookupMeter.Kind.TREE, 900, false,
                    LookupMeter.question("shape_of", "com.example.Book"));
                LookupMeter.record("planner", LookupMeter.Kind.TREE, 900, false,
                    LookupMeter.question("shape_of", " com.example.Book "));
                LookupMeter.record("worker", LookupMeter.Kind.TREE, 900, false,
                    LookupMeter.question("shape_of", "com.example.Book"));
                LookupMeter.record("worker", LookupMeter.Kind.TREE, 700, false,
                    LookupMeter.question("shape_of", "com.example.Shelf"));
                LookupMeter.record("worker", LookupMeter.Kind.TREE, 500); // no question said
            }
            try (var in = CloudGate.enter(new Where(project, story, UUID.randomUUID()))) {
                LookupMeter.record("architect", LookupMeter.Kind.TREE, 900, false,
                    LookupMeter.question("shape_of", "com.example.Book"));
            }
            List<LookupUsageRecord> lookups = ledger.usageOf(run).lookups();
            assertThat(repeats(lookups, "architect")).as("the first to ask").isZero();
            assertThat(repeats(lookups, "planner")).as("another role, the same question")
                .isEqualTo(1);
            assertThat(repeats(lookups, "worker")).isEqualTo(1);
            assertThat(lookups.stream().mapToLong(LookupUsageRecord::repeatChars).sum())
                .isEqualTo(1_800);
            ledger.flush();
            store.append(() -> null).get();
        }
        RunMeter.recordTo(null);
        try (ArtifactStore store = new ArtifactStore(dir)) {
            assertThat(store.root().runUsage().get(run).asked().size()).isEqualTo(2);
            UsageLedger ledger = ledgerOver(store);
            try (var in = CloudGate.enter(new Where(project, story, run))) {
                LookupMeter.record("test author", LookupMeter.Kind.TREE, 700, false,
                    LookupMeter.question("shape_of", "com.example.Shelf"));
                LookupMeter.record("test author", LookupMeter.Kind.TREE, 300, false,
                    LookupMeter.question("body_of", "com.example.Shelf#add"));
            }
            assertThat(repeats(ledger.usageOf(run).lookups(), "test author"))
                .as("what was answered before the restart is still known to have been").isEqualTo(1);
            assertThat(UsageReport.lookupLines(ledger.usageOf(run).lookups()))
                .contains("- test author: tree and graph queries 2/1000; structured 100%; "
                    + "repeats 1/700 (50% of its 2 lookups)");
        }
    }

    private static int repeats(List<LookupUsageRecord> lookups, String role) {
        return lookups.stream().filter(row -> role.equals(row.role()))
            .mapToInt(LookupUsageRecord::repeatCalls).sum();
    }

    @Test
    void aSessionThatNamesNoRunIsGivenTheRunItsOpeningThreadWorksFor() {
        List<AgentSessionRecord> started = new ArrayList<>();
        TraceHub hub = new TraceHub(null);
        hub.addListener(new TraceHub.Listener() {
            @Override
            public void sessionStarted(AgentSessionRecord session) {
                started.add(session);
            }
        });
        // A planning role opens its session without the observer's join keys.
        AgentRuntime.SessionSpec planning = new AgentRuntime.SessionSpec("architect", "You design.",
            new AgentRuntime.ModelEndpoint(LOCAL, "", "a-model", 32_000, ModelQuirks.DEFAULTS),
            0.2, 10, List.of(), null);
        UUID task = UUID.randomUUID();
        UUID own = UUID.randomUUID();

        try (var in = CloudGate.enter(new Where(project, story, run))) {
            hub.begin(planning).opened("You design.", "Begin.");
            hub.begin(new AgentRuntime.SessionSpec("worker-1", "You code.", planning.endpoint(),
                0.2, 10, List.of(), null,
                new AgentRuntime.SessionMeta(UUID.randomUUID(), own, task, null, 1)))
                .opened("You code.", "Begin.");
        }
        hub.begin(planning).opened("You design.", "Begin.");

        assertThat(started.get(0).runId())
            .as("the planning session's record names the run, so a listing by run shows it")
            .isEqualTo(run);
        assertThat(started.get(1).runId()).as("a session that names its run keeps it")
            .isEqualTo(own);
        assertThat(started.get(1).taskId()).isEqualTo(task);
        assertThat(started.get(2).runId()).as("opened for no run, it names none").isNull();
    }
}
