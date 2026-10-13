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
package com.swarmcoder.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 102: an audit of a five-story local build (87.2 million tokens, 4,065 calls) found
 * that the product's own report would have shown almost nothing of it. The report had sums and
 * no row per call, so no middle call, no largest call and no "what was this call for"; it
 * dropped the thinking; it counted no repeated lookups; it did not set tokens against what came
 * of the work; and it warned of nothing on a local server.
 */
class EachRolesCallsAreShownOneByOneTest {

    private static CallUsageRecord call(String role, boolean paid, String purpose,
                                        String candidate, long input, long output,
                                        long thinking) {
        return new CallUsageRecord(role, "t1", "0", "a-model",
            paid ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL, purpose, candidate,
            1_760_000_000_000L, 1_500, input, input / 2, output, thinking, false, false);
    }

    private static ModelUsageRecord rowOf(List<CallUsageRecord> calls, String role, boolean paid) {
        ModelUsageRecord row = new ModelUsageRecord(role, null, "a-model",
            paid ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL);
        for (CallUsageRecord call : calls) {
            if (call.role().equals(role) && call.paid() == paid) {
                row.addCall(call.input(), call.output(), call.cachedInput(), call.millis(),
                    call.failed(), false);
                row.addThinking(call.thinking(), false);
            }
        }
        return row;
    }

    @Test
    void aCallIsKeptWithItsFourCountsAndItsPurposeAndAFullPageIsWrittenOnce() {
        CallLog log = new CallLog();
        for (int i = 0; i < 600; i++) {
            log.add(new CallUsageRecord("worker", "task-" + (i % 3), String.valueOf(i % 4),
                "a-model", ModelUsageRecord.LOCAL, "a turn of the session worker-" + (i % 4),
                "candidate-" + (i % 5), 1_760_000_000_000L + i * 1_000L, 900 + i, 10_000 + i,
                8_000 + i, 300 + i, i % 2 == 0 ? 120 + i : -1, i == 7, i == 9));
        }
        CallLog first = log.stored();
        log.add(call("judge", false, "judging one candidate", null, 3_000, 40, 0));
        CallLog second = log.stored();

        assertThat(first.size()).as("a stored copy is not changed by what is added later")
            .isEqualTo(600);
        assertThat(second.size()).isEqualTo(601);
        assertThat(second.page(0)).as("a full page is the same object in every later copy, so "
            + "the store writes it once").isSameAs(first.page(0));
        assertThat(second.page(1)).isSameAs(first.page(1));
        assertThat(second.page(2)).as("only the page being filled is written again")
            .isNotSameAs(first.page(2));
        assertThat(first.page(2)).hasSize((600 % CallLog.PAGE) * CallLog.WIDTH);

        CallUsageRecord kept = first.calls().get(321);
        assertThat(kept.role()).isEqualTo("worker");
        assertThat(kept.task()).isEqualTo("task-0");
        assertThat(kept.worker()).isEqualTo("1");
        assertThat(kept.purpose()).isEqualTo("a turn of the session worker-1");
        assertThat(kept.candidate()).isEqualTo("candidate-1");
        assertThat(kept.startedAt()).isEqualTo(1_760_000_000_000L + 321_000L);
        assertThat(kept.millis()).isEqualTo(1_221);
        assertThat(kept.input()).isEqualTo(10_321);
        assertThat(kept.cachedInput()).isEqualTo(8_321);
        assertThat(kept.output()).isEqualTo(621);
        assertThat(kept.thinking()).as("a count the server did not give stays not recorded")
            .isEqualTo(-1);
        assertThat(first.calls().get(320).thinking()).isEqualTo(440);
        assertThat(first.calls().get(7).failed()).isTrue();
        assertThat(first.calls().get(9).estimated()).isTrue();
        assertThat(kept.paid()).isFalse();

        CallLog goneOn = first.continued();
        goneOn.add(call("worker", false, "x", null, 1, 1, 1));
        assertThat(goneOn.size()).isEqualTo(601);
        assertThat(first.size()).as("going on from a stored copy leaves the copy alone")
            .isEqualTo(600);
        assertThat(first.calls()).hasSize(600);
        assertThat(goneOn.calls().get(600).purpose()).isEqualTo("x");
    }

    @Test
    void theReportGivesEachRoleItsMiddleCallItsLargestWhatThatWasForAndItsThinking() {
        List<CallUsageRecord> calls = new ArrayList<>();
        for (long input : new long[] {4_000, 5_000, 6_000, 7_000, 90_000}) {
            calls.add(call("architect", false, input == 90_000
                ? "a turn of the session architect" : "one question", null, input, 400, 250));
        }
        calls.add(call("judge", false, "judging one candidate", null, 3_000, 60, -1));
        List<ModelUsageRecord> models = List.of(rowOf(calls, "architect", false),
            rowOf(calls, "judge", false));

        List<UsageReport.RoleFigures> figures = UsageReport.figuresOf(models, calls);
        UsageReport.RoleFigures architect = figures.get(0);
        assertThat(architect.calls()).isEqualTo(5);
        assertThat(architect.input()).isEqualTo(112_000);
        assertThat(architect.medianInput()).as("the middle call, not the average of 22,400")
            .isEqualTo(6_000);
        assertThat(architect.largestInput()).isEqualTo(90_000);
        assertThat(architect.largestPurpose()).isEqualTo("a turn of the session architect");
        assertThat(architect.thinking()).isEqualTo(1_250);
        assertThat(architect.output()).as("thinking is part of the output, never added to it")
            .isEqualTo(2_000);
        assertThat(figures.get(1).thinking()).as("the judge's server gave no count")
            .isEqualTo(-1);

        String report = UsageReport.render("Run x.", models, List.of(), calls, "", 1);
        assertThat(report)
            .contains("| architect | local | 5 of 5 | 6000 | 90000 | a turn of the session "
                + "architect | 1250 | 0 |")
            .contains("| judge | local | 1 of 1 | 3000 | 3000 | judging one candidate | not "
                + "recorded | 0 |");

        assertThat(UsageReport.render("Run x.", models, List.of()))
            .as("a run stored before calls were kept one by one says so, and shows no 0")
            .contains("| architect | local | 0 of 5 | not recorded | 90000 | not recorded | "
                + "1250 | 0 |");
    }

    @Test
    void aLocalServerIsWarnedAboutByTheSameRulesInWordsOfPlacesAndTime() {
        ModelUsageRecord worker = new ModelUsageRecord("worker", null, "m", ModelUsageRecord.LOCAL);
        ModelUsageRecord architect =
            new ModelUsageRecord("architect", null, "m", ModelUsageRecord.LOCAL);
        ModelUsageRecord small = new ModelUsageRecord("judge", null, "m", ModelUsageRecord.LOCAL);
        for (int i = 0; i < 100; i++) {
            worker.addCall(41_000, 300, -1, 1_000, false, false);
            architect.addCall(9_000, 300, -1, 1_000, false, false);
            small.addCall(3_000, 50, -1, 1_000, false, false);
        }
        String warnings = UsageReport.warnings(List.of(worker, architect, small));

        assertThat(warnings)
            .contains("WARNING: the worker was sent 41000 input tokens a call on the local "
                + "server (100 calls, the largest 41000); above 30000 a conversation is "
                + "carrying what it no longer needs, and every call holds one of the server's "
                + "places for longer.")
            .contains("WARNING: the architect made 100 calls on the local server; its work in "
                + "one run needs at most 40. Local tokens are not money: what was lost is the "
                + "places and the time those calls took.");
        assertThat(warnings.lines().filter(line -> line.startsWith("WARNING: "))).hasSize(2);
        assertThat(warnings).doesNotContain("judge").doesNotContain("paid");

        assertThat(UsageReport.warnings(List.of(architect), 3))
            .as("100 calls are the work of three runs: 40 a run").isEmpty();
        assertThat(UsageReport.warnings(List.of(architect), 2))
            .contains("needs at most 40, and these are 2 runs");
    }

    @Test
    void aLookupThatAsksWhatWasAlreadyAnsweredIsShownAsARepeatAndWarnedAbout() {
        LookupUsageRecord tree = new LookupUsageRecord("worker", "TREE", "tree and graph queries",
            true, 40, 36_000);
        tree.addRepeat(30, 27_000);
        LookupUsageRecord files = new LookupUsageRecord("worker", "WHOLE_FILE", "whole files",
            false, 10, 4_000);
        files.addRepeat(1, 400);
        LookupUsageRecord few = new LookupUsageRecord("planner", "TREE", "tree and graph queries",
            true, 6, 6_000);
        few.addRepeat(5, 5_000);
        ModelUsageRecord worker = new ModelUsageRecord("worker", null, "m", ModelUsageRecord.LOCAL);
        worker.addCall(9_000, 300, -1, 1_000, false, false);
        ModelUsageRecord planner =
            new ModelUsageRecord("planner", null, "m", ModelUsageRecord.LOCAL);
        planner.addCall(9_000, 300, -1, 1_000, false, false);
        List<LookupUsageRecord> lookups = List.of(tree, files, few);

        assertThat(UsageReport.lookupLines(lookups))
            .contains("- worker: tree and graph queries 40/36000; whole files 10/4000; "
                + "structured 90%; repeats 31/27400 (62% of its 50 lookups)")
            .contains("- planner: tree and graph queries 6/6000; structured 100%; repeats "
                + "5/5000 (83% of its 6 lookups)");

        String warnings = UsageReport.warnings(List.of(worker, planner), lookups);
        assertThat(warnings)
            .contains("WARNING: 62% of what the worker looked up on the local server had "
                + "already been answered earlier in the run (31 of 50 lookups, 27400 "
                + "characters); above 40% the roles are not handing on what they find.");
        assertThat(warnings).as("six lookups are too few to judge a share by")
            .doesNotContain("planner");

        List<LookupUsageRecord> added = UsageReport.lookupsAdded(List.of(
            new RunUsageRecord(null, null, null, List.of(), List.of(tree)),
            new RunUsageRecord(null, null, null, List.of(), List.of(tree))));
        assertThat(added.get(0).repeatCalls()).as("a project's repeats are its runs' added up")
            .isEqualTo(60);
    }

    @Test
    void theTokensOfTheCandidatesThatWereChosenAreSetAgainstThoseOfAllCandidates() {
        List<CallUsageRecord> calls = List.of(
            call("worker", false, "w", "chosen", 10_000, 500, 0),
            call("worker", false, "w", "chosen", 20_000, 700, 0),
            call("worker", false, "w", "lost", 30_000, 900, 0),
            call("worker", false, "w", "lost", 30_000, 100, 0),
            call("worker", false, "w", "running", 10_000, 100, 0),
            call("judge", false, "j", null, 99_000, 99, 0));

        assertThat(UsageReport.usedLine(calls, Set.of("chosen"), Set.of("chosen", "lost")))
            .isEqualTo("Work that was used: the 1 candidate(s) that were chosen took 30000 "
                + "input / 1200 output tokens in 2 calls; all 3 candidate(s) took 100000 / 2300 "
                + "in 5 calls. 30% of the candidates' input tokens went into work that was "
                + "chosen. 1 candidate(s) have no known end yet.\n");
        assertThat(UsageReport.usedLine(List.of(calls.get(5)), Set.of(), Set.of()))
            .as("no call named a candidate: nothing is claimed").isEmpty();
    }
}
