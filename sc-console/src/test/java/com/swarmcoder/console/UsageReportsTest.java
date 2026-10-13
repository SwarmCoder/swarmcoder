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
package com.swarmcoder.console;

import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CloudSpendRecord;
import com.swarmcoder.domain.LookupUsageRecord;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.StoryKind;
import com.swarmcoder.domain.StoryOrigin;
import com.swarmcoder.domain.StoryState;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 93: what a run, a story and a project cost in model tokens is reported per role, paid
 * and local apart, input and output apart, from what was stored with each run.
 */
class UsageReportsTest {

    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        ConsoleContext.set(null);
    }

    private static ModelUsageRecord row(String role, String endpointClass, int calls, long input,
                                        long output) {
        ModelUsageRecord row = new ModelUsageRecord(role, null, "a-model", endpointClass);
        for (int i = 0; i < calls; i++) {
            row.addCall(input / calls, output / calls, -1, 2_000, false, false);
        }
        return row;
    }

    private Story story(ArtifactStore store, String key, String title, UUID... runs) {
        Story story = new Story(UUID.randomUUID(), projectId, key, StoryKind.DELIVERY, title, null,
            StoryState.REVIEW, new ArrayList<>(), new ArrayList<>(), null, 0, StoryOrigin.BACKLOG,
            null, null, "agent", new ArrayList<>(), null, null, null, null, Instant.now(),
            Instant.now());
        story.setRunIds(new ArrayList<>(List.of(runs)));
        store.saveStory(story);
        return story;
    }

    private UUID run(ArtifactStore store, UUID storyId, Instant startedAt) throws Exception {
        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, WorkflowKind.GREENFIELD, RunState.DELIVERED, projectId, storyId,
            null, UUID.randomUUID(), null, startedAt, new RunReport(runId, "goal"));
        run.setHeartbeatAt(startedAt.plusSeconds(600));
        store.append(() -> {
            store.root().runs.put(runId, run);
            return null;
        }).get();
        return runId;
    }

    private void usage(ArtifactStore store, UUID runId, UUID storyId, List<ModelUsageRecord> rows,
                       List<LookupUsageRecord> lookups) throws Exception {
        store.recordRunUsage(new RunUsageRecord(runId, storyId, projectId, rows, lookups));
        store.append(() -> null).get();
    }

    @Test
    void aRunsReportIsTheTablePerRoleAndFitsAToolReply(@TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID runId = run(store, null, Instant.now());
            usage(store, runId, null, List.of(
                    row("architect", ModelUsageRecord.CLOUD, 51, 1_020_000, 20_400),
                    row("planner", ModelUsageRecord.CLOUD, 12, 120_000, 2_400),
                    row("test author", ModelUsageRecord.CLOUD, 30, 300_000, 9_000),
                    row("reviewer", ModelUsageRecord.CLOUD, 4, 40_000, 800),
                    row("expert", ModelUsageRecord.CLOUD, 9, 45_000, 900),
                    row("judge", ModelUsageRecord.LOCAL, 6, 18_000, 300),
                    row("worker", ModelUsageRecord.LOCAL, 800, 8_000_000, 400_000)),
                List.of(new LookupUsageRecord("architect", "TREE", "tree and graph queries", true,
                        40, 36_000),
                    new LookupUsageRecord("architect", "WHOLE_FILE", "whole files", false, 3,
                        12_000),
                    new LookupUsageRecord("worker", "SHELL_READ", "shell reads (find, grep, cat)",
                        false, 90, 45_000)));

            String report = UsageReports.ofRun(store, runId.toString());

            assertThat(report)
                .contains("Paid cloud: 1525000 in / 33500 out over 106 calls; local: 8018000 in "
                    + "/ 400300 out.")
                .contains("| architect | paid cloud | 51 | 1020000 | 20000 | 20400 | not recorded "
                    + "| 102.0 |")
                .contains("| worker | local | 800 | 8000000 | 10000 | 400000 | not recorded "
                    + "| 1600.0 |")
                .contains("| **all** |  | 912 | 9543000 | 10463 | 433800 |")
                .contains("- architect: tree and graph queries 40/36000; whole files 3/12000; "
                    + "structured 75%")
                .contains("- worker: shell reads (find, grep, cat) 90/45000; structured 0%");
            assertThat(report.length())
                .as("seven roles, each one's middle and largest call (section 98) and their "
                    + "lookups, in a reply a supervisor can afford to read")
                .isLessThan(3_000);

            assertThat(UsageReports.evidenceOf(store, runId))
                .isEqualTo("Paid cloud: 1525000 in / 33500 out over 106 calls; local: 8018000 in "
                    + "/ 400300 out");
            assertThat(UsageReports.ofRun(store, "not-an-id")).contains("is not a run id");
            assertThat(UsageReports.ofRun(store, UUID.randomUUID().toString()))
                .contains("There is no run");
        }
    }

    @Test
    void aProjectsReportTotalsEachStoryAndListsRunsWithInputAndOutputApart(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), (goal, kind) -> null,
                runId -> { }, runId -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            Story lend = story(store, "S1", "Lend a book");
            Story give = story(store, "S2", "Return a book");
            UUID first = run(store, lend.id(), Instant.now().minusSeconds(7_200));
            UUID second = run(store, lend.id(), Instant.now().minusSeconds(3_600));
            UUID third = run(store, give.id(), Instant.now());
            UUID before = run(store, give.id(), Instant.now().minusSeconds(90_000));
            lend.setRunIds(new ArrayList<>(List.of(first, second)));
            give.setRunIds(new ArrayList<>(List.of(third, before)));
            store.saveStory(lend);
            store.saveStory(give);
            usage(store, first, lend.id(), List.of(row("architect", ModelUsageRecord.CLOUD, 10,
                100_000, 2_000), row("worker", ModelUsageRecord.LOCAL, 50, 500_000, 25_000)),
                List.of());
            usage(store, second, lend.id(), List.of(row("architect", ModelUsageRecord.CLOUD, 5,
                50_000, 1_000)), List.of());
            usage(store, third, give.id(), List.of(row("planner", ModelUsageRecord.CLOUD, 4,
                40_000, 800)), List.of());

            String report = new ObserverServiceImpl().projectReport();

            assertThat(report)
                .contains("over 3 run(s)")
                .contains("1 earlier run(s) are not in these figures")
                .contains("- Lend a book (2 run(s)): Paid cloud: 150000 in / 3000 out over 15 "
                    + "calls; local: 500000 in / 25000 out")
                .contains("- Return a book (1 run(s)): Paid cloud: 40000 in / 800 out over 4 "
                    + "calls; local: 0 in / 0 out")
                .contains("Whole project:")
                .contains("Paid cloud: 190000 in / 3800 out over 19 calls; local: 500000 in / "
                    + "25000 out.")
                .contains("| architect | paid cloud | 15 | 150000 | 10000 | 3000 |");

            RunSummaryDto listed = new ObserverServiceImpl().listRuns().stream()
                .filter(run -> run.getRunId().equals(first.toString())).findFirst().orElseThrow();
            assertThat(listed.getCloudTokensIn()).isEqualTo(100_000);
            assertThat(listed.getCloudTokensOut()).isEqualTo(2_000);
            assertThat(listed.getCloudCalls()).isEqualTo(10);
            assertThat(listed.getLocalTokensIn()).isEqualTo(500_000);
            assertThat(listed.getLocalTokensOut()).isEqualTo(25_000);
            assertThat(new ObserverServiceImpl().runReport(first.toString()))
                .contains("| worker | local | 50 | 500000 | 10000 | 25000 |");
        }
    }

    /**
     * A build that finished before calls were counted one by one: its stored sessions still hold
     * the size of every call, input and output together.
     */
    @Test
    void aRunFromBeforeIsReportedFromItsStoredSessionsAndSaysWhatThatIs(@TempDir Path dir)
            throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            Instant started = Instant.now().minusSeconds(3_600);
            UUID runId = run(store, null, started);
            store.append(() -> {
                store.root().cloudSpend().put(runId,
                    new CloudSpendRecord(runId, "RUN", 1_163_749, 2_369, 0));
                // A worker's session names its run.
                UUID worker = UUID.randomUUID();
                store.root().agentSessions().put(worker, Lazy.Reference(new AgentSessionRecord(
                    worker, runId, UUID.randomUUID(), null, 0, "worker-0", "local-model", 0.2,
                    started.plusSeconds(60), started.plusSeconds(120), "COMPLETED", null, 2, 9_500,
                    List.of(
                        new TraceEvent(0, started.plusSeconds(61), TraceEventKind.LLM_RESPONSE, "",
                            "x".repeat(400), null, 9_000),
                        new TraceEvent(1, started.plusSeconds(62), TraceEventKind.TOOL_CALL,
                            "write_file", "y".repeat(1_600), null, 9_000),
                        new TraceEvent(2, started.plusSeconds(63), TraceEventKind.TOOL_RESULT,
                            "write_file", "ok", null, 9_000),
                        new TraceEvent(3, started.plusSeconds(64), TraceEventKind.LLM_RESPONSE, "",
                            "done", null, 9_500)))));
                // A planning role's session named no run; it was opened while the run was going.
                UUID architect = UUID.randomUUID();
                store.root().agentSessions().put(architect, Lazy.Reference(new AgentSessionRecord(
                    architect, null, null, null, 0, "architect", "cloud-model", 0.2,
                    started.plusSeconds(10), started.plusSeconds(50), "COMPLETED", null, 1, 30_000,
                    List.of(new TraceEvent(0, started.plusSeconds(11),
                        TraceEventKind.LLM_RESPONSE, "", "z".repeat(800), null, 30_000)))));
                // And one from another day, which is not this run's.
                UUID other = UUID.randomUUID();
                store.root().agentSessions().put(other, Lazy.Reference(new AgentSessionRecord(
                    other, null, null, null, 0, "planner", "cloud-model", 0.2,
                    started.minusSeconds(86_400), started.minusSeconds(86_000), "COMPLETED", null,
                    1, 7_000, List.of(new TraceEvent(0, started.minusSeconds(86_300),
                        TraceEventKind.LLM_RESPONSE, "", "no", null, 7_000)))));
                return null;
            }).get();

            String report = UsageReports.ofRun(store, runId.toString());

            assertThat(report)
                .contains("were not recorded one by one")
                .contains("The cloud gate counted 1163749 in / 2369 out for it. That figure is "
                    + "an estimate and is too low")
                .contains("| worker | local-model | this run's | 2 | 18500 | 501 |")
                .contains("| architect | cloud-model | no run named, opened while it ran | 1 "
                    + "| 30000 | 200 |")
                .doesNotContain("planner");
            assertThat(UsageReports.evidenceOf(store, runId)).isNull();
        }
    }

    private static com.swarmcoder.domain.CallUsageRecord call(String role, String purpose,
                                                              String candidate, long input,
                                                              long output, long thinking) {
        return new com.swarmcoder.domain.CallUsageRecord(role, null, null, "a-model",
            ModelUsageRecord.LOCAL, purpose, candidate, 1_760_000_000_000L, 2_000, input, -1,
            output, thinking, false, false);
    }

    /** A run's record as the ledger writes it: the sums per role and every call one by one. */
    private void usageWithCalls(ArtifactStore store, UUID key, UUID runId, boolean outsideRuns,
                                List<com.swarmcoder.domain.CallUsageRecord> calls,
                                List<LookupUsageRecord> lookups) throws Exception {
        Map<String, ModelUsageRecord> rows = new java.util.LinkedHashMap<>();
        com.swarmcoder.domain.CallLog log = new com.swarmcoder.domain.CallLog();
        for (com.swarmcoder.domain.CallUsageRecord call : calls) {
            ModelUsageRecord row = rows.computeIfAbsent(call.role(),
                role -> new ModelUsageRecord(role, null, "a-model", ModelUsageRecord.LOCAL));
            row.addCall(call.input(), call.output(), -1, call.millis(), false, false);
            row.addThinking(call.thinking(), false);
            log.add(call);
        }
        store.recordUsage(key, new RunUsageRecord(runId, null, projectId,
            new ArrayList<>(rows.values()), lookups, log.stored(),
            new com.swarmcoder.domain.AskedQuestions(), outsideRuns));
        store.append(() -> null).get();
    }

    private void candidate(ArtifactStore store, UUID id,
                           com.swarmcoder.domain.CandidateState state) throws Exception {
        store.append(() -> {
            store.root().candidateArchives.put(id, org.eclipse.serializer.reference.Lazy.Reference(
                new com.swarmcoder.domain.CandidateSolution(id, UUID.randomUUID(), 0, "swarm/x",
                    new com.swarmcoder.domain.SamplingConfig("a-model", 0.2, 0, "p", "s"), "diff",
                    null, null, null, state, null)));
            return null;
        }).get();
    }

    private void setState(ArtifactStore store, UUID runId, RunState state) throws Exception {
        store.append(() -> {
            store.root().runs.get(runId).setState(state);
            return null;
        }).get();
    }

    /**
     * Section 98: the report of a local build shows per role the middle and the largest call,
     * the thinking, the repeated lookups and how much of the work was used - and the Console's
     * table is made from the same figures as the text the supervisor's tools return.
     */
    @Test
    void aLocalRunsReportShowsCallsThinkingRepeatsAndWhatWasUsedAndTheConsoleShowsTheSame(
            @TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), (goal, kind) -> null,
                id -> { }, id -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            UUID runId = run(store, null, Instant.now());
            UUID chosen = UUID.randomUUID();
            UUID lost = UUID.randomUUID();
            candidate(store, chosen, com.swarmcoder.domain.CandidateState.SELECTED);
            candidate(store, lost, com.swarmcoder.domain.CandidateState.FAILED);
            List<com.swarmcoder.domain.CallUsageRecord> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                calls.add(call("worker", "a turn of the session worker-" + (i % 2),
                    (i % 4 == 0 ? chosen : lost).toString(), i == 39 ? 80_000 : 40_000, 500,
                    200));
            }
            calls.add(call("judge", "judging one candidate", null, 3_000, 60, -1));
            calls.add(call("answer check", "checking stored answers against a question", null,
                2_000, 10, -1));
            LookupUsageRecord tree = new LookupUsageRecord("worker", "TREE",
                "tree and graph queries", true, 50, 45_000);
            tree.addRepeat(39, 35_000);
            usageWithCalls(store, runId, runId, false, calls, List.of(tree));

            String report = new ObserverServiceImpl().runReport(runId.toString());

            assertThat(report)
                .contains("WARNING: the worker was sent 41000 input tokens a call on the local "
                    + "server (40 calls, the largest 80000)")
                .contains("WARNING: 78% of what the worker looked up on the local server had "
                    + "already been answered earlier in the run (39 of 50 lookups")
                .contains("| worker | local | 40 of 40 | 40000 | 80000 | a turn of the session "
                    + "worker-1 | 8000 | 0 |")
                .contains("| judge | local | 1 of 1 | 3000 | 3000 | judging one candidate | not "
                    + "recorded | 0 |")
                .contains("| answer check | local | 1 of 1 | 2000 | 2000 | checking stored "
                    + "answers against a question | not recorded | 0 |")
                .contains("Work that was used: the 1 candidate(s) that were chosen took 400000 "
                    + "input / 5000 output tokens in 10 calls; all 2 candidate(s) took 1640000 "
                    + "/ 20000 in 40 calls. 24% of the candidates' input tokens went into work "
                    + "that was chosen.")
                .contains("This run was delivered.")
                .contains("- worker: tree and graph queries 50/45000; structured 100%; repeats "
                    + "39/35000 (78% of its 50 lookups)");
            assertThat(report.indexOf("WARNING:")).as("the warnings stand above the table")
                .isBetween(0, report.indexOf("| role |"));

            com.swarmcoder.console.api.UsageTableDto table =
                new ObserverServiceImpl().usageTable(runId.toString());
            assertThat(table.getNote()).isEmpty();
            assertThat(table.getTotals()).isEqualTo("Paid cloud: 0 in / 0 out over 0 calls; "
                + "local: 1645000 in / 20070 out.");
            assertThat(report).as("the Console's totals and warnings are the report's")
                .contains(table.getTotals()).contains(table.getWarnings());
            assertThat(table.getWarnings().lines()).hasSize(2);
            assertThat(table.getRoleRows().lines().toList()).containsExactly(
                "worker\tlocal\t40\t1640000\tnot recorded\t20000\t8000\t40000\t80000\t"
                    + "a turn of the session worker-1\t80\t0",
                "judge\tlocal\t1\t3000\tnot recorded\t60\tnot recorded\t3000\t3000\t"
                    + "judging one candidate\t2\t0",
                "answer check\tlocal\t1\t2000\tnot recorded\t10\tnot recorded\t2000\t2000\t"
                    + "checking stored answers against a question\t2\t0");
            assertThat(table.getLookupRows())
                .isEqualTo("worker\ttree and graph queries\t50\t45000\t39\t35000\t0\n");
            assertThat(report).contains(table.getUsed());

            setState(store, runId, RunState.ABORTED);
            assertThat(UsageReports.ofRun(store, runId.toString()))
                .contains("This run ended early (ABORTED): nothing of its 1645000 input / 20070 "
                    + "output tokens in 42 calls was delivered.");
        }
    }

    @Test
    void aProjectsReportHasWhatWasCalledForNoRunAndWhichRunsWereDeliveredOrEndedEarly(
            @TempDir Path dir) throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null), (goal, kind) -> null,
                id -> { }, id -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            UUID delivered = run(store, null, Instant.now().minusSeconds(7_200));
            UUID early = run(store, null, Instant.now().minusSeconds(3_600));
            UUID open = run(store, null, Instant.now());
            setState(store, early, RunState.ABORTED);
            setState(store, open, RunState.EXECUTING);
            List<com.swarmcoder.domain.CallUsageRecord> twenty = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                twenty.add(call("architect", "a turn of the session architect", null, 10_000,
                    100, 50));
            }
            usageWithCalls(store, delivered, delivered, false, twenty, List.of());
            usageWithCalls(store, early, early, false, twenty.subList(0, 15), List.of());
            usageWithCalls(store, open, open, false, twenty.subList(0, 10), List.of());
            // What the wizards and the chat called while this project was open.
            usageWithCalls(store, projectId, null, true, List.of(
                call("analyst", "the requirements wizard", null, 4_000, 300, -1),
                call("chat", "the Console's chat", null, 2_000, 100, -1)), List.of());

            String report = new ObserverServiceImpl().projectReport();

            assertThat(report)
                .contains("Project: model tokens per story and per role, over 3 run(s).")
                .contains("- calls for no run (the wizards, the chat, knowledge): Paid cloud: 0 "
                    + "in / 0 out over 0 calls; local: 6000 in / 400 out")
                .contains("Runs: 1 delivered (200000 in / 2000 out), 1 ended early (150000 in / "
                    + "1500 out, none of it delivered), 1 not ended (100000 in / 1000 out).")
                .contains("| architect | local | 45 of 45 | 10000 | 10000 | a turn of the "
                    + "session architect | 2250 | 0 |")
                .contains("| analyst | local | 1 of 1 | 4000 | 4000 | the requirements wizard |")
                .contains("| chat | local | 1 of 1 | 2000 | 2000 | the Console's chat |");
            assertThat(report).as("45 architect calls over three runs are 15 a run")
                .doesNotContain("WARNING");

            com.swarmcoder.console.api.UsageTableDto table =
                new ObserverServiceImpl().usageTable("");
            assertThat(table.getTitle()).isEqualTo("Whole project:");
            assertThat(table.getRoleRows().lines().map(line -> line.split("\t")[0]).toList())
                .containsExactly("architect", "analyst", "chat");
            assertThat(table.getRoleRows()).startsWith("architect\tlocal\t45\t450000\t");
            assertThat(table.getUsed()).startsWith("Runs: 1 delivered");
        }
    }
}
