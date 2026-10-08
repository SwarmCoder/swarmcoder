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
package com.swarmcoder.app;

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CarriedWarning;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.TokenBudget;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.runtime.ExpertAnswerLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The run report says what a run cost and whether the swarm helped, from what was recorded — and
 * says "not recorded" where nothing was, rather than leaving the number out or guessing it.
 */
class HarnessRunReportTest {

    private static final long T0 = 1_000_000_000_000L;

    private static final String DIFF = """
        diff --git a/app/src/main/java/A.java b/app/src/main/java/A.java
        --- a/app/src/main/java/A.java
        +++ b/app/src/main/java/A.java
        @@ -1 +1,3 @@
         class A {
        +  int x;
        +  int y;
        -}
        """;

    private static Task task(String title) {
        return new Task(UUID.randomUUID(), 1, title, "do it", Set.of("app"), Set.of(), List.of(),
            null, null, new TokenBudget(32000, 4000, 100000, 10),
            new SwarmPolicy(2, false, 0.2, 0.8, List.of()), TaskState.SELECTED);
    }

    private static CandidateSolution candidate(Task task, int worker, CandidateState state,
                                               double score) {
        JudgeScore judge = state == CandidateState.FAILED ? null : new JudgeScore(score, "ok", "m");
        return new CandidateSolution(UUID.randomUUID(), task.id(), worker, "b" + worker, null, DIFF,
            null, null, judge, state, null);
    }

    private static AgentSessionRecord session(Task task, CandidateSolution candidate,
                                              long openedAfter, long seconds) {
        AgentSessionRecord record = new AgentSessionRecord();
        record.setId(UUID.randomUUID());
        record.setTaskId(task.id());
        record.setCandidateId(candidate.id());
        record.setWorkerIndex(candidate.workerIndex());
        record.setRole("worker-" + candidate.workerIndex());
        record.setOpenedAt(Instant.ofEpochMilli(T0 + openedAfter));
        record.setClosedAt(Instant.ofEpochMilli(T0 + openedAfter + seconds * 1000));
        record.setOutcome("COMPLETED");
        record.setTurns(5);
        return record;
    }

    private static RunMeter.Call call(String role, Task task, String worker, long startAfter,
                                      long millis, int prompt, int completion) {
        return new RunMeter.Call(role, task == null ? null : task.id().toString(), worker, "m",
            T0 + startAfter, T0 + startAfter + millis, prompt, completion);
    }

    @Test
    void theReportCarriesEverySectionFromWhatWasRecorded() {
        Task rescued = task("Rescued task");
        Task easy = task("Easy task");
        // Section 69: a failed journey went back to its author first, then to the workers.
        rescued.setJourneySentBack(true);
        rescued.setJourneyReviewNote("The journey's author answered that the journey is right "
            + "and the screen is wrong: the box has no label.");
        rescued.setJourneyRepairAttempted(true);
        CandidateSolution r0 = candidate(rescued, 0, CandidateState.FAILED, 0);
        CandidateSolution r1 = candidate(rescued, 1, CandidateState.SELECTED, 0.8);
        CandidateSolution e0 = candidate(easy, 0, CandidateState.SELECTED, 0.9);
        CandidateSolution e1 = candidate(easy, 1, CandidateState.SURVIVED, 0.6);
        List<AgentSessionRecord> sessions = List.of(
            session(rescued, r0, 0, 60), session(rescued, r1, 0, 60),
            session(easy, e0, 120_000, 60), session(easy, e1, 120_000, 60));
        List<RunMeter.Call> calls = List.of(
            call("architect", null, null, 0, 1000, 500, 100),
            call("worker", rescued, "0", 1000, 10_000, 1000, 100),
            call("worker", rescued, "1", 1000, 10_000, 1000, 200),
            call("worker", easy, "0", 121_000, 10_000, 1000, 300),
            call("worker", easy, "1", 121_000, 10_000, 1000, 400),
            call("judge", easy, "0", 190_000, 1000, -1, -1));

        String report = HarnessRunReport.render(new HarnessRunReport.Input(calls,
            List.of(new RunMeter.Span("wave 1 of 1", T0, T0 + 200_000),
                // What the engine and the dispatcher record at a dispatch (section 65).
                new RunMeter.Span("worker rules|" + rescued.id() + "|12|38", T0, T0),
                new RunMeter.Span("worker opening|" + rescued.id() + "|first|5200|SYSTEM_ROLE=31 "
                    + "WORKFLOW_RULES=1010 PROJECT_CONSTRAINTS=950 KNOWLEDGE_BRIEF=1992 "
                    + "TASK_INSTRUCTIONS=563", T0, T0),
                new RunMeter.Span("worker rules|" + rescued.id() + "|12|38", T0 + 9, T0 + 9),
                new RunMeter.Span("worker opening|" + rescued.id() + "|repair|5900|SYSTEM_ROLE=31",
                    T0 + 9, T0 + 9)),
            List.of(rescued, easy), List.of(), List.of(r0, r1, e0, e1), sessions, List.of(),
            List.of(new CarriedWarning("check", "EXECUTING", "an objection", Instant.now())),
            List.of(new HarnessRunReport.Seen(T0, "EXECUTING", false, null),
                new HarnessRunReport.Seen(T0 + 200_000, "EXECUTING", true, "stuck on a question"),
                new HarnessRunReport.Seen(T0 + 260_000, "DELIVERED", false, null)),
            List.of(new HarnessRunReport.HarnessStage("requirements (analyst wizard)", T0 - 60_000, T0)),
            T0 - 60_000, T0 + 300_000,
            List.of(new HarnessRunReport.FileStat("app/src/main/java/A.java", 20, 1),
                new HarnessRunReport.FileStat("app/src/test/java/ATest.java", 10, 0),
                new HarnessRunReport.FileStat("app/pom.xml", 2, 0)),
            "integration commit abc against the run base def", null, "reached DELIVERED", null));

        assertThat(report)
            .contains("| architect | 1 | 500 | 500 | 100 | 1.0 |")
            .contains("| worker | 4 | 4000 | 1000 | 1000 | 40.0 |")
            .as("a role whose calls reported no usage says so").contains("| judge | 1 | not recorded | not recorded | not recorded | 1.0 |")
            .contains("| analyst | 0 | 0 | 0 | 0 |")
            .contains("Candidates dispatched 2, survived verification 1, selected 1")
            .contains("Journey sent back to its author before any worker repair (it failed in "
                + "the browser at final integration): The journey's author answered that the "
                + "journey is right and the screen is wrong: the box has no label.")
            .contains("Returned to the workers once from final integration: its journey failed "
                + "in the browser\n")
            .contains("| run stage EXECUTING | 4.3 | 1.0 |")
            .contains("| wave 1 of 1 | 3.3 | 0.0 |")
            .contains("main code: 1 file(s) +20 -1; test code: 1 file(s) +10 -0; build files: 1 file(s) +2 -0")
            .contains("| Rescued task | 1 file(s) +2 -1 | 2 |")
            .contains("Total tokens: 5600 (prompt 4500, completion 1100)")
            .contains("Completion tokens per delivered line: 34.4")
            .as("prompt and completion apart, wherever tokens are totalled")
            .contains("Share of tokens spent on discarded candidates: 45% (prompt 2000 of 4500, "
                + "completion 500 of 1100)")
            .as("per task: rules sent against rules in force, and the opening's size")
            .contains("Rules sent to the workers: 12 of the 38 in force in the project")
            .contains("Worker opening, sent again on every call of every worker: first dispatch "
                + "about 5200 tokens (SYSTEM_ROLE=31 WORKFLOW_RULES=1010 PROJECT_CONSTRAINTS=950 "
                + "KNOWLEDGE_BRIEF=1992 TASK_INSTRUCTIONS=563); repair dispatch about 5900 tokens")
            .as("a task nothing was recorded for says so")
            .contains("Rules sent to the workers: not recorded")
            .doesNotContain("| worker rules").doesNotContain("| worker opening")
            .contains("Parked in EXECUTING").contains("for 1.0 min: stuck on a question")
            .contains("Carried warnings: 1")
            .contains("only a later candidate passed: the swarm rescued this task")
            .contains("several passed and the judge chose | 0.30 (best 0.90, worst 0.60)")
            .contains("Tasks a single worker would have delivered: 1 of 2")
            .contains("Tasks only delivered because of a second candidate: 1")
            .contains("Extra tokens and minutes spent on redundant candidates (every first-dispatch "
                + "worker other than worker 0): prompt 2000 tokens, completion 600 tokens, "
                + "2.0 minutes");
    }

    @Test
    void twoCallsSideBySideShowAsConcurrencyTwoWithTheirCombinedRate() {
        // 10 s alone at 10 tokens/s, then 10 s of two calls at 10 tokens/s each.
        List<RunMeter.Call> calls = List.of(
            new RunMeter.Call("worker", "t", "0", "m", T0, T0 + 20_000, 10, 200),
            new RunMeter.Call("worker", "t", "1", "m", T0 + 10_000, T0 + 20_000, 10, 100));

        HarnessRunReport.Concurrency c = HarnessRunReport.concurrencyOver(calls, T0, T0 + 40_000);

        assertThat(c.peak()).isEqualTo(2);
        assertThat(c.average()).isEqualTo(0.75);
        assertThat(c.share()[0]).isEqualTo(0.5);
        assertThat(c.share()[1]).isEqualTo(0.25);
        assertThat(c.share()[2]).isEqualTo(0.25);
        assertThat(c.tokenSec()[1]).isCloseTo(10.0, within(1e-6));
        assertThat(c.tokenSec()[2]).isCloseTo(20.0, within(1e-6));
        assertThat(c.tokenSec()[3]).as("no time at three in flight").isEqualTo(-1);
        assertThat(c.overallTokenSec()).isCloseTo(15.0, within(1e-6));
    }

    /**
     * Live run 74: five worker calls timed out after fifteen minutes each with no token count,
     * and their time was counted in the rates with none of their tokens.
     */
    @Test
    void aCallThatGaveNoAnswerHoldsAPlaceButIsInNoRate() {
        // 20 s at 10 tokens/s; beside its second half, a call that timed out with no answer.
        List<RunMeter.Call> calls = List.of(
            new RunMeter.Call("worker", "t", "0", "m", T0, T0 + 20_000, 10, 200),
            new RunMeter.Call("worker", "t", "1", "m", T0 + 10_000, T0 + 40_000, -1, -1, true, -1));

        HarnessRunReport.Concurrency c = HarnessRunReport.concurrencyOver(calls, T0, T0 + 40_000);

        assertThat(c.peak()).as("it held a place").isEqualTo(2);
        assertThat(c.share()[2]).isEqualTo(0.25);
        assertThat(c.share()[1]).isEqualTo(0.75);
        assertThat(c.failedCalls()).isEqualTo(1);
        assertThat(c.failedSlotMinutes()).isCloseTo(0.5, within(1e-6));
        assertThat(c.tokenSec()[1]).as("only the stretch with no unanswered call in flight")
            .isCloseTo(10.0, within(1e-6));
        assertThat(c.tokenSec()[2]).as("what the server wrote then is not known").isEqualTo(-1);
        assertThat(c.overallTokenSec()).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void aStreamedCallsTokensAreSpreadFromItsFirstTokenToItsEnd() {
        // 10 s reading the prompt, then 10 s writing 200 tokens: 20 tokens/s of generation.
        List<RunMeter.Call> calls = List.of(new RunMeter.Call("judge", "t", "0", "m", T0,
            T0 + 20_000, 10, 200, false, T0 + 10_000));

        HarnessRunReport.Concurrency c = HarnessRunReport.concurrencyOver(calls, T0, T0 + 20_000);

        assertThat(c.ratedFromFirstToken()).isEqualTo(1);
        assertThat(c.tokenSec()[1]).as("200 tokens over the 20 s one call was in flight")
            .isCloseTo(10.0, within(1e-6));
        // And spread over the writing half only: the first half carries none of them.
        HarnessRunReport.Concurrency writing =
            HarnessRunReport.concurrencyOver(calls, T0 + 10_000, T0 + 20_000);
        assertThat(writing.tokenSec()[1]).isCloseTo(20.0, within(1e-6));
        HarnessRunReport.Concurrency reading =
            HarnessRunReport.concurrencyOver(calls, T0, T0 + 10_000);
        assertThat(reading.tokenSec()[1]).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void everyExpertAnswerIsListedWithWhatItCostAndWhatBecameOfTheWorkThatFollowedIt() {
        ExpertAnswerLog.reset();
        ExpertAnswerLog log = ExpertAnswerLog.forRun(UUID.randomUUID());
        ExpertAnswerLog.Asker worker0 = ExpertAnswerLog.Asker.worker(UUID.randomUUID(), "Build the screen", 0);
        ExpertAnswerLog.Asker worker1 = ExpertAnswerLog.Asker.worker(UUID.randomUUID(), "Build the store", 1);
        ExpertAnswerLog.Asker architect = ExpertAnswerLog.Asker.role("architect");
        ExpertAnswerLog.Handle first = log.record(worker0, "How do I create a text field?",
            ExpertAnswerLog.From.EXPERT_RESEARCH, 3, 4, 21_000, 900, 95.0);
        log.record(worker0, "How do I create a labelled text field | with a pipe?",
            ExpertAnswerLog.From.REUSED, 0, 0, 0, 0, 0.5);
        log.askedAgain(first);
        log.workFollowed(worker0, true);
        log.record(worker1, "How is the root object created?",
            ExpertAnswerLog.From.EXPERT_RESEARCH, 2, 1, 9_000, 400, 40.0);
        log.workFollowed(worker1, false);
        log.record(architect, "Which module holds the services?",
            ExpertAnswerLog.From.PROJECT_CODE, 0, 0, 0, 0, 0.2);
        log.record(architect, "What does nobody know?", ExpertAnswerLog.From.NO_ANSWER, 0, 0, 0, 0,
            0.1);

        String report = HarnessRunReport.render(new HarnessRunReport.Input(List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            T0, T0 + 60_000, null, "nothing was delivered", null, null, null, log.entries()));

        assertThat(report)
            .contains("## 9. Expert answers")
            .as("an answer the same asker had to ask again did not help, whatever came after")
            .contains("| worker 0 of 'Build the screen' | How do I create a text field? | expert "
                + "research | 3 | 4 | 21000 | 900 | 95.0 | did not help | the asker asked the same "
                + "thing again |")
            .as("a reused answer whose asker's work then passed its checks helped")
            .contains("| reused | 0 | 0 | 0 | 0 | 0.5 | helped | the asker's work passed its checks |")
            .as("a pipe in a question cannot break the table")
            .contains("text field / with a pipe?")
            .contains("| worker 1 of 'Build the store' | How is the root object created? | expert "
                + "research | 2 | 1 | 9000 | 400 | 40.0 | did not help | the asker's work failed "
                + "its checks |")
            .as("nothing was said about the architect's work, so nothing is claimed")
            .contains("| architect | Which module holds the services? | project code | 0 | 0 | 0 "
                + "| 0 | 0.2 | unknown |")
            .contains("| no answer | 0 | 0 | 0 | 0 | 0.1 | did not help | no answer was given |")
            .contains("Totals: 5 answer(s), 2 expert research, 1 reused, 1 project code, 1 no "
                + "answer; helped 1, did not help 3, unknown 1.")
            .contains("135.0 s of it for expert research")
            .contains("was sent 30000 prompt tokens and wrote 1300")
            .as("the outcome is labelled as evidence")
            .contains("The outcome is evidence, not proof");
    }

    @Test
    void aRunThatAskedTheExpertNothingSaysSo() {
        String report = HarnessRunReport.render(new HarnessRunReport.Input(List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            T0, T0 + 60_000, null, "nothing was delivered", null, null, null, List.of()));

        assertThat(report).contains("## 9. Expert answers").contains("No question was put to the expert.");
    }

    @Test
    void aRunThatRecordedNothingSaysNotRecordedInsteadOfLeavingItOut() {
        String report = HarnessRunReport.render(new HarnessRunReport.Input(List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            T0, T0 + 60_000, null, "nothing was delivered and no winner was merged",
            "resumed from the snapshot saved at the plan", null, null));

        assertThat(report)
            .contains("Outcome: not recorded")
            .contains("RESUMED RUN: resumed from the snapshot saved at the plan")
            .contains("Not measured (restored from a snapshot)")
            .contains("Delivered: not recorded (nothing was delivered and no winner was merged)")
            .contains("Total tokens: not recorded")
            .contains("Delivered lines per hour: not recorded")
            .contains("No plan was stored")
            .contains("not recorded: no finished model call was recorded")
            .contains("Parks: none observed")
            .contains("not recorded: this run kept no record of expert answers.")
            .contains("not recorded: this run left no record of how its candidates were started.")
            .contains("not recorded: no worker call reported its prompt tokens.");
    }

    /** Runs 79 and 80: nobody measured which roles asked the tree and which read files. */
    @Test
    void whatEachRoleLookedUpIsShownByKindWithTheShareThatCameFromTheTree() {
        String table = HarnessRunReport.lookupsByKind(List.of(
            new com.swarmcoder.inference.LookupMeter.Count("planner",
                com.swarmcoder.inference.LookupMeter.Kind.TREE, 5, 1000),
            new com.swarmcoder.inference.LookupMeter.Count("planner",
                com.swarmcoder.inference.LookupMeter.Kind.SEARCH, 2, 3000),
            new com.swarmcoder.inference.LookupMeter.Count("planner",
                com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER, 3, 1000),
            new com.swarmcoder.inference.LookupMeter.Count("architect",
                com.swarmcoder.inference.LookupMeter.Kind.DOCUMENT, 4, 3000),
            new com.swarmcoder.inference.LookupMeter.Count("architect",
                com.swarmcoder.inference.LookupMeter.Kind.WHOLE_FILE, 1, 1000),
            new com.swarmcoder.inference.LookupMeter.Count("worker",
                com.swarmcoder.inference.LookupMeter.Kind.SHELL_READ, 7, 900)));

        assertThat(table)
            .as("language-server queries and document sections are kinds of their own, the "
                + "last columns of kinds")
            .contains("| language-server queries | document outlines and sections | acceptance-test reads | journey checks | share of "
                + "characters from the tree, the language server and document sections |")
            .contains("| planner | 5 call(s), 1000 chars | 2 call(s), 3000 chars | 0 | 0 | 0 | 0 "
                + "| 3 call(s), 1000 chars | 0 | 0 | 0 | 40% |")
            .as("a document read by section counts as structured, a whole file does not")
            .contains("| architect | 0 | 0 | 1 call(s), 1000 chars | 0 | 0 | 0 | 0 "
                + "| 4 call(s), 3000 chars | 0 | 0 | 75% |")
            .contains("| worker | 0 | 0 | 0 | 0 | 0 | 7 call(s), 900 chars | 0 | 0 | 0 | 0 | 0% |");
        assertThat(HarnessRunReport.lookupsByKind(List.of())).contains("not recorded");
    }

    /** Run 80: 2.7 million prompt tokens on a paid server, and no record of what was cached. */
    @Test
    void whatTheServerTookFromItsPromptCacheIsShownPerRole() {
        List<RunMeter.Call> calls = List.of(
            new RunMeter.Call("planner", null, null, "m", T0, T0 + 1000, 1000, 10, false, -1,
                0, 4000, -1),
            new RunMeter.Call("planner", null, null, "m", T0 + 2000, T0 + 3000, 3000, 10, false,
                -1, 900, 12000, 9000),
            new RunMeter.Call("judge", null, null, "m", T0, T0 + 1000, 500, 10));
        String report = HarnessRunReport.render(new HarnessRunReport.Input(calls, List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            T0, T0 + 60_000, null, "nothing was delivered and no winner was merged",
            null, null, null));

        assertThat(report)
            .contains("### What the prompt tokens cost")
            .contains("| planner | 2 | 4000 | 900 | 3100 | 0 | 75% |")
            .contains("| judge | 1 | 500 | not recorded | not recorded | 1 | not recorded |");
    }

    /**
     * What it takes to see whether the server's places went to different work first: per wave,
     * how each candidate came to be started, which were never started or were stopped, and how
     * many judge calls were not made - and what a worker's turn cost in prompt tokens.
     */
    @Test
    void thePlacesSectionCountsHowCandidatesWereStartedPerWaveAndPromptTokensPerTurn() {
        Task a = task("Task a");
        Task b = task("Task b");
        Task c = task("Task c");
        CandidateSolution a0 = candidate(a, 0, CandidateState.SELECTED, 0.9);
        CandidateSolution b0 = candidate(b, 0, CandidateState.SELECTED, 0.9);
        CandidateSolution b1 = new CandidateSolution(UUID.randomUUID(), b.id(), 1, "b1", null, "",
            null, null, null, CandidateState.KILLED, com.swarmcoder.domain.KillReason.SUPERSEDED);
        CandidateSolution c0 = candidate(c, 0, CandidateState.FAILED, 0);
        CandidateSolution c1 = candidate(c, 1, CandidateState.SELECTED, 0.7);
        List<RunMeter.Span> spans = List.of(
            new RunMeter.Span("wave 1 of 2", T0, T0 + 100_000),
            new RunMeter.Span("candidate started|first|" + a.id() + "|0", T0, T0),
            new RunMeter.Span("candidate started|first|" + b.id() + "|0", T0, T0),
            new RunMeter.Span("candidate started|spare|" + b.id() + "|1", T0 + 5_000, T0 + 5_000),
            new RunMeter.Span("candidate not started|" + a.id() + "|1", T0 + 50_000, T0 + 50_000),
            new RunMeter.Span("judge skipped|" + a.id() + "|0", T0 + 51_000, T0 + 51_000),
            new RunMeter.Span("judge skipped|" + b.id() + "|0", T0 + 90_000, T0 + 90_000),
            // Stopped behind its task: it ended after the wave did, and still counts for wave 1.
            new RunMeter.Span("candidate cancelled|SUPERSEDED|" + b.id() + "|1", T0 + 120_000,
                T0 + 120_000),
            new RunMeter.Span("wave 2 of 2", T0 + 110_000, T0 + 200_000),
            new RunMeter.Span("candidate started|first|" + c.id() + "|0", T0 + 110_000, T0 + 110_000),
            new RunMeter.Span("candidate started|needed|" + c.id() + "|1", T0 + 150_000, T0 + 150_000),
            new RunMeter.Span("judge skipped|" + c.id() + "|1", T0 + 190_000, T0 + 190_000),
            new RunMeter.Span("stopped candidate verified|TURN_CAP|" + c.id() + "|0|failed",
                T0 + 140_000, T0 + 140_000),
            new RunMeter.Span("stopped candidate verified|NO_PROGRESS|" + c.id() + "|1|survived",
                T0 + 180_000, T0 + 180_000));
        List<RunMeter.Call> calls = List.of(
            call("worker", a, "0", 1_000, 10_000, 8_000, 100),
            call("worker", a, "0", 12_000, 10_000, 12_000, 100),
            call("worker", b, "0", 1_000, 10_000, 9_000, 100),
            call("worker", b, "1", 6_000, 10_000, 9_000, 100),
            call("worker", c, "0", 111_000, 10_000, 20_000, 100),
            call("worker", c, "1", 151_000, 10_000, 14_000, 100));

        String report = HarnessRunReport.render(new HarnessRunReport.Input(calls, spans,
            List.of(a, b, c), List.of(List.of(a.id(), b.id()), List.of(c.id())),
            List.of(a0, b0, b1, c0, c1), List.of(), List.of(), List.of(), List.of(), List.of(),
            T0, T0 + 200_000, null, "nothing to compare", null, "reached DELIVERED", null));

        assertThat(report)
            .contains("## 10. Places: did different work come first?")
            .as("wave 1: two firsts, one spare; a's second never started; b's second stopped")
            .contains("| wave 1 of 2 | 2 | 2 | 0 | 1 | 1 | 1 | 0 | 0 | 2 |")
            .as("wave 2: the second candidate was started because the first failed")
            .contains("| wave 2 of 2 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 1 |")
            .as("two stopped workers left a change; both were verified and one passed")
            .contains("the change was verified like any other: 2; of those, passed verification: 1.")
            .contains("### Prompt tokens per worker turn")
            .contains("| Task a | 2 | 20000 | 10000 | 12000 |")
            .contains("| Task c | 2 | 34000 | 17000 | 20000 |")
            .contains("| **all workers** | 6 | 72000 | 12000 | 20000 |")
            .contains("harness run 66, before it, averaged 24744 per turn")
            .as("a second candidate that was stopped is not counted as one that failed")
            .contains("| Task b | 2 | 1 | yes | worker 0 passed; no other candidate was needed |")
            .contains("only a later candidate passed: the swarm rescued this task");
    }

    /**
     * Section 73: per task, how many of the architect's findings its workers were given and
     * their size, how many of its files were computed, and which the selected candidate took
     * beyond the plan.
     */
    @Test
    void aTaskShowsTheArchitectsFindingsItsWorkersWereGivenAndTheFilesTakenBeyondThePlan() {
        Task task = task("Order service");
        task.setWriteSet(Set.of("server/src/main/java/OrderService.java",
            "server/src/main/java/OrderServiceImpl.java", "server/src/main/java/OrderIds.java"));
        task.setComputedReservation(List.of("server/src/main/java/OrderService.java",
            "server/src/main/java/OrderServiceImpl.java"));
        task.setTakenBeyondPlan(List.of("server/src/main/java/OrderIds.java"));
        Task other = task("Order screen");

        String shown = HarnessRunReport.handoverOf(List.of(
            new RunMeter.Span("worker handover|" + task.id() + "|first|3|2400", T0, T0),
            new RunMeter.Span("worker handover|" + task.id() + "|repair|3|2400", T0 + 9, T0 + 9),
            new RunMeter.Span("worker handover|" + other.id() + "|first|0|0", T0, T0)), task);

        assertThat(shown)
            .contains("Architect's findings given to the workers: first dispatch 3 finding(s), "
                + "2400 characters, about 600 tokens; repair dispatch 3 finding(s), 2400 "
                + "characters, about 600 tokens")
            .contains("Files reserved for the task: 3, 2 of them computed from its contracts "
                + "and the project's types")
            .contains("Files taken beyond the plan: server/src/main/java/OrderIds.java");

        assertThat(HarnessRunReport.handoverOf(List.of(), other))
            .contains("Architect's findings given to the workers: not recorded")
            .contains("Files taken beyond the plan: none");
    }
}
