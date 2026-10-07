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
package com.swarmcoder.server.mcp;

import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphCandidateDto;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.console.api.SessionSummaryDto;
import com.swarmcoder.console.api.TraceEventDto;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question this whole server exists to answer: "do you have any insights into what it is doing?"
 *
 * <p>On the night of 2026-08-28 a nine-story run went wrong and the answer was buried in a log
 * file: ten workers had each finished without producing any change, with the last command each of
 * them ran on the line above. These tests put exactly that shape behind the tools and assert the
 * answer comes back in one call, in words.
 */
class SwarmMcpToolsTest {

    private static final String RUN = "11111111-1111-1111-1111-111111111111";

    // --- what is happening right now --------------------------------------------------------------

    @Test
    void swarmStatusSaysWhatIsRunningRightNow() {
        long now = System.currentTimeMillis();
        RunSummaryDto run = run(RUN, "EXECUTING", now - 12 * 60_000, now - 3_000, 41_000);

        SessionSummaryDto workerA = session("s-a", RUN, "worker", "RUNNING", "", 14, now - 200_000, 0);
        SessionSummaryDto workerB = session("s-b", RUN, "worker", "RUNNING", "", 9, now - 100_000, 0);

        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of(run))
            .returning("activeSessions", List.of(workerA, workerB))
            // The step a live worker is on is its own call now, shared with the run graph, so the
            // browser and this tool cannot disagree about whether a worker is alive.
            .answer("lastStep", args -> List.of(
                event(1, "TOOL_CALL", "run_command", "mvn -o -q -pl sc-server test", now - 4_000)))
            .build();

        String reply = tools(observer, Stubs.of(GraphService.class).build(), control()).swarmStatus();

        assertThat(reply)
            .describedAs("the summary alone must answer 'what is it doing'")
            .contains("1 run(s) have not finished")
            .contains("2 worker(s) actually running")
            .contains("12m")
            .contains("EXECUTING");
        assertThat(reply)
            .describedAs("and each worker must carry the step it is on")
            .contains("run_command")
            .contains("mvn -o -q -pl sc-server test")
            .contains("\"driving\":\"a live process\"");
    }

    @Test
    void aRunNobodyIsDrivingIsCalledStopped() {
        long now = System.currentTimeMillis();
        RunSummaryDto run = run(RUN, "EXECUTING", now - 15 * 3_600_000L, now - 40 * 60_000L, 900_000);

        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of(run))
            .returning("activeSessions", List.of())
            .build();

        String reply = tools(observer, Stubs.of(GraphService.class).build(), control()).swarmStatus();

        assertThat(reply)
            .describedAs("a state of EXECUTING with no heartbeat is a stopped run, and must say so")
            .contains("\"driving\":\"nothing\"")
            .contains("have not reported in for over")
            .contains("the process driving it is gone");
    }

    @Test
    void nothingRunningIsSaidPlainly() {
        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of())
            .returning("activeSessions", List.of())
            .build();

        assertThat(tools(observer, Stubs.of(GraphService.class).build(), control()).swarmStatus())
            .contains("Nothing is running.");
    }

    // --- what went wrong --------------------------------------------------------------------------

    @Test
    void tenWorkersThatChangedNothingAreExplainedInOneCall() {
        long now = System.currentTimeMillis();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(RUN);
        graph.setRunState("EXECUTING");
        graph.setGoal("Add a bookshelf page");
        graph.setTasks(List.of(task("t-1", "Add the page", "IN_PROGRESS")));

        List<GraphCandidateDto> candidates = new ArrayList<>();
        List<SessionSummaryDto> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // The WorkerLoop shape: ran to the end, empty diff, FAILED with no kill reason.
            GraphCandidateDto candidate = candidate("c-" + i, "s-" + i, "FAILED", "", 30);
            candidate.setDiffEmpty(true);
            candidates.add(candidate);
            sessions.add(session("s-" + i, RUN, "worker", "COMPLETED", "", 30,
                now - 600_000, now - 60_000));
        }
        graph.setCandidates(candidates);

        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of(run(RUN, "EXECUTING", now - 900_000, now - 5_000, 800_000)))
            .answer("sessionEvents", args -> List.of(
                event(28, "TOOL_CALL", "run_command", "git status --short", now - 70_000),
                event(29, "TOOL_RESULT", "run_command", "(no output)", now - 69_000),
                event(30, "DONE", "report_done",
                    "The change was already present, so I made no edits.", now - 60_000)))
            .build();
        GraphService graphService = Stubs.of(GraphService.class)
            .returning("snapshot", graph)
            .returning("runSessions", sessions)
            .build();

        String reply = tools(observer, graphService, control()).runDiagnosis(RUN, 6, 20);

        assertThat(reply)
            .describedAs("the headline must be the finding, not a table to read")
            .contains("10 worker(s) ran to the end and changed no code at all");
        assertThat(reply)
            .describedAs("each worker's verdict must be in words, and the kill tally must own up "
                + "to having no kill reason")
            .contains("changed no code at all — its diff was empty")
            .contains("(no kill reason — the worker ran to the end and changed nothing)");
        assertThat(reply)
            .describedAs("and the last command each worker ran must be right there")
            .contains("git status --short")
            .contains("The change was already present");
    }

    /**
     * The night of 2026-09-02: four workers on "Create shared Book data model and ReadingStatus
     * enum" each wrote exactly their two files, then failed verification because stale acceptance
     * tests on master broke test-compile. The diagnosis called all four "its diff was empty",
     * and an hour went on a git failure that never happened. FAILED is a state; the diff is a
     * fact; the reading must come from the fact.
     */
    @Test
    void aWorkerThatWroteCodeAndFailedVerificationIsNotCalledAnEmptyDiff() {
        long now = System.currentTimeMillis();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(RUN);
        graph.setRunState("EXECUTING");
        graph.setGoal("Add a bookshelf page");
        graph.setTasks(List.of(task("t-1", "Create shared Book data model and ReadingStatus enum",
            "IN_PROGRESS")));

        List<GraphCandidateDto> candidates = new ArrayList<>();
        List<SessionSummaryDto> sessions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // Wrote Book.java and ReadingStatus.java, verified, did not compile, verdict recorded.
            GraphCandidateDto wrote = candidate("c-" + i, "s-" + i, "FAILED", "", 9);
            wrote.setDiffEmpty(false);
            wrote.setChangedFiles(2);
            wrote.setVerified(true);
            wrote.setCompiles(false);
            wrote.setFailReason("the candidate does not compile");
            candidates.add(wrote);
            sessions.add(session("s-" + i, RUN, "worker", "COMPLETED", "", 9,
                now - 600_000, now - 60_000));
        }
        // One that wrote a file and was never verified at all - failed by rule, not by verdict.
        GraphCandidateDto unverified = candidate("c-4", "s-4", "FAILED", "", 7);
        unverified.setDiffEmpty(false);
        unverified.setChangedFiles(1);
        candidates.add(unverified);
        sessions.add(session("s-4", RUN, "worker", "COMPLETED", "", 7, now - 600_000, now - 60_000));
        // And one that really did change nothing, which keeps the old sentence.
        GraphCandidateDto empty = candidate("c-5", "s-5", "FAILED", "", 30);
        empty.setDiffEmpty(true);
        candidates.add(empty);
        sessions.add(session("s-5", RUN, "worker", "COMPLETED", "", 30, now - 600_000, now - 60_000));
        graph.setCandidates(candidates);

        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("listRuns", List.of(run(RUN, "EXECUTING", now - 900_000, now - 5_000, 800_000)))
            .answer("sessionEvents", args -> List.of(
                event(8, "TOOL_CALL", "write_file", "wrote shared/Book.java (260 chars)", now - 70_000),
                event(9, "DONE", "report_done", "Both files written.", now - 60_000)))
            .build();
        GraphService graphService = Stubs.of(GraphService.class)
            .returning("snapshot", graph)
            .returning("runSessions", sessions)
            .build();

        String reply = tools(observer, graphService, control()).runDiagnosis(RUN, 6, 20);

        assertThat(reply)
            .describedAs("a worker that wrote files and failed verification is read from the "
                + "verdict the engine recorded, in the graph's own words")
            .contains("wrote 2 changed file(s) but failed verification: the candidate does not "
                + "compile");
        assertThat(reply)
            .describedAs("a worker nobody verified is said to have been left unverified")
            .contains("wrote 1 changed file(s)")
            .contains("was never verified");
        assertThat(reply)
            .describedAs("only the one whose diff really is empty is counted as changing nothing")
            .contains("1 worker(s) ran to the end and changed no code at all")
            .doesNotContain("6 worker(s) ran to the end and changed no code at all")
            .contains("4 worker(s) wrote code that failed verification")
            .contains("1 worker(s) wrote code that was never verified");
        assertThat(reply.split("its diff was empty", -1).length - 1)
            .describedAs("the empty-diff sentence appears once, for the one empty diff")
            .isEqualTo(1);
    }

    @Test
    void killReasonsAreTranslatedOutOfTheirEnumNames() {
        long now = System.currentTimeMillis();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(RUN);
        graph.setRunState("EXECUTING");
        graph.setCandidates(List.of(
            candidate("c-0", "s-0", "KILLED", "NO_ACCEPTANCE_EVIDENCE", 12),
            candidate("c-1", "s-1", "KILLED", "ENDPOINT_OUTAGE", 2)));

        GraphService graphService = Stubs.of(GraphService.class)
            .returning("snapshot", graph)
            .returning("runSessions", List.of(
                session("s-0", RUN, "worker", "KILLED", "NO_ACCEPTANCE_EVIDENCE", 12,
                    now - 300_000, now - 200_000),
                session("s-1", RUN, "worker", "KILLED", "ENDPOINT_OUTAGE", 2,
                    now - 300_000, now - 290_000)))
            .build();

        String reply = tools(Stubs.of(ObserverService.class).build(), graphService, control())
            .runDiagnosis(RUN, 3, 20);

        assertThat(reply)
            .contains("no test was actually run, so there is no evidence it did the job")
            .contains("the model endpoint never answered");
    }

    /**
     * The 2026-09-02 finding this whole module exists to surface: a worker killed as
     * {@code DOCS_DEAD_END} is not a worker that misbehaved, it is evidence that the documentation
     * search answered several different questions with the same section. Until now that evidence
     * lived only in a WARN line inside a JUnit temp store the test harness deletes when it ends.
     * It must reach the same operator sentence's diagnosis without them reading a log.
     */
    @Test
    void docsDeadEndDiagnosisCarriesTheQuestionsAndTheSectionTheyWereAnsweredWith() {
        long now = System.currentTimeMillis();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(RUN);
        graph.setRunState("EXECUTING");
        GraphCandidateDto docsDeadEnd = candidate("c-0", "s-0", "KILLED", "DOCS_DEAD_END", 20);
        docsDeadEnd.setDocsDeadEndEvidence(String.join("\n",
            "1. \"EclipseStore root store setup\" → docs/guides/persistence.md › Saving data",
            "2. \"how to get storage instance\" → docs/guides/persistence.md › Saving data",
            "3. \"ZeroZDbNode embedded factory\" → docs/guides/persistence.md › Saving data"));
        graph.setCandidates(List.of(docsDeadEnd));

        GraphService graphService = Stubs.of(GraphService.class)
            .returning("snapshot", graph)
            .returning("runSessions", List.of(
                session("s-0", RUN, "worker", "KILLED", "DOCS_DEAD_END", 20,
                    now - 300_000, now - 200_000)))
            .build();

        String reply = tools(Stubs.of(ObserverService.class).build(), graphService, control())
            .runDiagnosis(RUN, 3, 20);

        assertThat(reply)
            .describedAs("the operator sentence for DOCS_DEAD_END must actually be said, not just "
                + "the raw enum name")
            .contains("this is the search failing, not the worker")
            .describedAs("and the questions it asked, in order, each with the section it got back")
            .contains("EclipseStore root store setup")
            .contains("how to get storage instance")
            .contains("ZeroZDbNode embedded factory")
            .contains("docs/guides/persistence.md › Saving data");
    }

    /**
     * The other half of the 2026-09-03 finding: a worker that ran out of TURNS and a worker whose
     * conversation no longer FITS its room are different failures, and until {@code TURN_CAP}
     * existed both were reported as {@code BUDGET_EXCEEDED} — harness run 11 killed two workers at
     * exactly turn 25 that way, with no compaction anywhere in either log. The diagnosis must say
     * which one this is, in words, not the raw constant.
     */
    @Test
    void turnCapDiagnosisSaysWhichReasonItWasNotTheRoomOne() {
        long now = System.currentTimeMillis();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(RUN);
        graph.setRunState("EXECUTING");
        graph.setCandidates(List.of(candidate("c-0", "s-0", "KILLED", "TURN_CAP", 25)));

        GraphService graphService = Stubs.of(GraphService.class)
            .returning("snapshot", graph)
            .returning("runSessions", List.of(
                session("s-0", RUN, "worker", "KILLED", "TURN_CAP", 25, now - 300_000, now - 200_000)))
            .build();

        String reply = tools(Stubs.of(ObserverService.class).build(), graphService, control())
            .runDiagnosis(RUN, 3, 20);

        assertThat(reply)
            .describedAs("the operator sentence for TURN_CAP must actually be said, not just the "
                + "raw enum name")
            .contains("it used all of its turns without finishing")
            .describedAs("and it must say what to do about it")
            .contains("budgets.maxToolTurnsPerWorker")
            .describedAs("and never the room-death sentence, which is a different fact")
            .doesNotContain("conversation outgrew what it is allowed");
    }

    @Test
    void anUnknownRunIsRefusedInPlainWords() {
        GraphService graphService = Stubs.of(GraphService.class)
            .answer("snapshot", args -> { throw new IllegalArgumentException("no such run"); })
            .build();

        assertThat(tools(Stubs.of(ObserverService.class).build(), graphService, control())
            .runDiagnosis("not-a-run", 6, 20))
            .contains("\"problem\"")
            .contains("There is no run with id not-a-run");
    }

    // --- what gets cut, and whether the caller is told ---------------------------------------------

    @Test
    void aLongPayloadComesBackAsAWindowThatSaysItWasCut() {
        String huge = "x".repeat(ReplyBudget.MAX_WINDOW_CHARS * 3);
        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("blobText", huge)
            .build();

        SyncToolRegistration blobText = named(
            tools(observer, Stubs.of(GraphService.class).build(), control()), "blob_text");
        String reply = firstText(blobText, java.util.Map.of("ref", "abc"));

        assertThat(reply)
            .contains("\"totalChars\":" + huge.length())
            .contains("\"nextOffset\":" + ReplyBudget.MAX_WINDOW_CHARS)
            .contains("CUT.")
            .contains("Call again with offset=");
        assertThat(reply.length())
            .describedAs("and the reply itself stays inside the budget")
            .isLessThan(ReplyBudget.MAX_REPLY_CHARS);
    }

    @Test
    void aPageOfStepsIsCappedAndSaysHowToGetTheNextOne() {
        List<TraceEventDto> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(event(i, "TOOL_CALL", "read_file", "src/main/java/Thing.java", 0));
        }
        ObserverService observer = Stubs.of(ObserverService.class)
            .returning("sessionEvents", many)
            .build();

        String reply = tools(observer, Stubs.of(GraphService.class).build(), control())
            .sessionEvents("s-0", 0, 9_999, null);

        assertThat(reply)
            .describedAs("a caller asking for ten thousand steps gets a page, and is told where "
                + "the next one starts")
            .contains("\"returned\":" + ReplyBudget.MAX_EVENTS_PER_PAGE)
            .contains("\"nextFromSeq\":" + ReplyBudget.MAX_EVENTS_PER_PAGE)
            .contains("Payloads here are the first 400 characters only");
        assertThat(reply.length()).isLessThan(ReplyBudget.MAX_REPLY_CHARS);
    }

    @Test
    void aFatPayloadRidesAlongAsASnippetWithTheRefToFetchTheRest() {
        ObserverService observer = Stubs.of(ObserverService.class)
            .answer("sessionEvents", args -> {
                TraceEventDto event = event(7, "TOOL_RESULT", "run_command", "z".repeat(50_000), 0);
                event.setPayloadRef("blob-cafe");
                return List.of(event);
            })
            .build();

        String reply = tools(observer, Stubs.of(GraphService.class).build(), control())
            .sessionEvents("s-0", 0, 50, null);

        assertThat(reply)
            .contains("\"blobRef\":\"blob-cafe\"")
            .contains("\"payloadIsCut\":true")
            .contains("z".repeat(ReplyBudget.SNIPPET_CHARS) + " …");
        assertThat(reply).doesNotContain("z".repeat(ReplyBudget.SNIPPET_CHARS + 1));
    }

    // --- the tool list itself ----------------------------------------------------------------------

    @Test
    void theToolListIsExactlyWhatIsClaimed() {
        List<String> names = tools(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), control()).registrations().stream()
            .map(registration -> registration.tool().name()).toList();

        assertThat(names).containsExactly(
            "swarm_status", "run_diagnosis", "run_detail", "list_runs", "session_events",
            "event_payload", "session_prompt", "blob_text", "run_diff", "pending_decisions",
            "search_history", "insights", "list_projects",
            "start_run", "decide_run", "answer_decision");
    }

    @Test
    void readOnlyLeavesOutEveryToolThatChangesSomething() {
        List<String> names = new SwarmMcpTools(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), control(), true).registrations().stream()
            .map(registration -> registration.tool().name()).toList();

        assertThat(names).doesNotContain("start_run", "decide_run", "answer_decision");
        assertThat(names).contains("swarm_status", "run_diagnosis");
    }

    @Test
    void aRunKindThatQuietlyDeliversNothingIsRefused() {
        String reply = tools(Stubs.of(ObserverService.class).build(),
            Stubs.of(GraphService.class).build(), control()).startRun("write the docs", "DOCS");

        assertThat(reply)
            .contains("\"problem\"")
            .contains("without building anything");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static SwarmMcpTools tools(ObserverService observer, GraphService graph,
                                       ControlService control) {
        return new SwarmMcpTools(observer, graph, control, false);
    }

    private static ControlService control() {
        ProjectDto project = new ProjectDto();
        project.setProjectId("p-1");
        project.setName("bookshelf");
        project.setPrimaryPath("G:/code/bookshelf");
        project.setCurrent(true);
        return Stubs.of(ControlService.class)
            .returning("projects", List.of(project))
            .returning("decisions", List.of())
            .build();
    }

    private static SyncToolRegistration named(SwarmMcpTools tools, String name) {
        return tools.registrations().stream()
            .filter(registration -> registration.tool().name().equals(name))
            .findFirst().orElseThrow();
    }

    private static String firstText(SyncToolRegistration registration,
                                    java.util.Map<String, Object> args) {
        var result = registration.call().apply(args);
        return ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0)).text();
    }

    private static RunSummaryDto run(String id, String state, long startedAt, long heartbeat,
                                     long tokens) {
        RunSummaryDto run = new RunSummaryDto();
        run.setRunId(id);
        run.setKind("ENHANCEMENT");
        run.setState(state);
        run.setGoal("Add a bookshelf page");
        run.setStartedAtMillis(startedAt);
        run.setHeartbeatAtMillis(heartbeat);
        run.setTokensUsed(tokens);
        return run;
    }

    private static SessionSummaryDto session(String id, String runId, String role, String outcome,
                                             String killReason, int turns, long openedAt,
                                             long closedAt) {
        SessionSummaryDto session = new SessionSummaryDto();
        session.setSessionId(id);
        session.setRunId(runId);
        session.setTaskId("t-1");
        session.setRole(role);
        session.setModel("qwen3-coder");
        session.setOutcome(outcome);
        session.setKillReason(killReason);
        session.setTurns(turns);
        session.setTokens(12_000);
        session.setOpenedAtMillis(openedAt);
        session.setClosedAtMillis(closedAt);
        return session;
    }

    private static GraphCandidateDto candidate(String id, String sessionId, String state,
                                               String killReason, int turns) {
        GraphCandidateDto candidate = new GraphCandidateDto();
        candidate.setCandidateId(id);
        candidate.setSessionId(sessionId);
        candidate.setTaskId("t-1");
        candidate.setModel("qwen3-coder");
        candidate.setState(state);
        candidate.setKillReason(killReason);
        candidate.setTurns(turns);
        candidate.setJudgeScore(-1);
        return candidate;
    }

    private static GraphTaskDto task(String id, String title, String state) {
        GraphTaskDto task = new GraphTaskDto();
        task.setTaskId(id);
        task.setTitle(title);
        task.setState(state);
        task.setDependsOnCsv("");
        task.setWriteSetCsv("src/**");
        return task;
    }

    private static TraceEventDto event(long seq, String kind, String label, String payload,
                                       long at) {
        TraceEventDto event = new TraceEventDto();
        event.setSeq(seq);
        event.setKind(kind);
        event.setLabel(label);
        event.setPayloadSnippet(payload);
        event.setPayloadRef("");
        event.setAtMillis(at);
        return event;
    }
}
