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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphCandidateDto;
import com.swarmcoder.console.api.GraphRequirementDto;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.InsightsDto;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.console.api.SessionSummaryDto;
import com.swarmcoder.console.api.SupervisorService;
import com.swarmcoder.console.api.TraceEventDto;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.KillReason;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every tool this MCP server offers, and the whole of the adapter behind them.
 *
 * <p><b>It adapts; it never re-derives.</b> Every number and every state word here came out of
 * {@link ObserverService}, {@link GraphService} or {@link ControlService} — the same three surfaces
 * the Console reads. Nothing reaches around them into the store. If the Console and an outside
 * agent ever disagree about what a run is doing, that is a bug in one shared implementation, not a
 * difference of opinion between two.
 *
 * <p>The tools are ordered by what an operator actually needed at two in the morning:
 * <ol>
 *   <li><b>What is happening right now</b> — {@code swarm_status} answers it in one call.</li>
 *   <li><b>What went wrong</b> — {@code run_diagnosis} gathers every worker's verdict, kill reason
 *       and last few steps for one run, which is the thing that was previously only readable by
 *       scrolling a log file.</li>
 *   <li><b>Everything else</b> — listings, evidence windows, and last of all the four verbs that
 *       change something.</li>
 * </ol>
 *
 * <p>What it cannot do, said plainly: it cannot pause or resume a run (nothing in the engine
 * exposes that — a run is stopped by rejecting it, and resumed only by restarting the process); it
 * cannot edit requirements, the backlog or settings; it cannot upload documents; it cannot read
 * another project's runs, because the read surfaces are scoped to whichever project the Console
 * currently has selected; and it does not stream — every tool is a question and an answer.
 */
public final class SwarmMcpTools {

    private static final Logger log = LoggerFactory.getLogger(SwarmMcpTools.class);

    /** A run whose last heartbeat is older than this is reported as nobody driving it. */
    private static final long STALLED_AFTER_MILLIS = 5 * 60_000L;

    /** How many pages of 500 events {@link #lastEvents} will walk before it gives up. */
    private static final int MAX_EVENT_PAGES = 8;

    private static final Set<String> ENDED_RUN_STATES = Set.of("DELIVERED", "ABORTED");

    private final ObserverService observer;
    private final GraphService graph;
    private final ControlService control;
    private final boolean readOnly;
    /** The supervisor's tools, or null when this server was built without them. */
    private final SupervisorMcpTools supervisor;
    private final ObjectMapper json = new ObjectMapper();

    /** The three tools that changed something before the supervisor's were added. */
    private static final Set<String> OWN_WRITE_TOOLS =
        Set.of("start_run", "decide_run", "answer_decision");

    public SwarmMcpTools(ObserverService observer, GraphService graph, ControlService control,
                         boolean readOnly) {
        this(observer, graph, control, readOnly, null);
    }

    /**
     * @param supervisor the service behind the supervisor's tools, or null to leave them out
     */
    public SwarmMcpTools(ObserverService observer, GraphService graph, ControlService control,
                         boolean readOnly, SupervisorService supervisor) {
        this.observer = observer;
        this.graph = graph;
        this.control = control;
        this.readOnly = readOnly;
        this.supervisor = supervisor == null ? null : new SupervisorMcpTools(supervisor, control);
    }

    /**
     * The name of every tool that changes something. The transport refuses each of these unless
     * the caller presents the installation's MCP secret; see {@link McpSecret}.
     */
    public static Set<String> writeToolNames() {
        Set<String> names = new LinkedHashSet<>(OWN_WRITE_TOOLS);
        names.addAll(SupervisorMcpTools.WRITE_TOOLS);
        return names;
    }

    // --- registration ----------------------------------------------------------------------------

    /** Every tool, in the order they are worth reaching for. */
    public List<SyncToolRegistration> registrations() {
        List<SyncToolRegistration> tools = new ArrayList<>();

        tools.add(tool("swarm_status",
            "START HERE. What SwarmCoder is doing right now, in one call: the selected project, "
                + "every run that has not finished, how long each has been going, whether anything "
                + "is actually driving it or it has stalled, what it has cost, and every live "
                + "worker with the step it is on. Also how many questions are waiting for an "
                + "answer. No arguments.",
            schema(), args -> swarmStatus()));

        tools.add(tool("run_diagnosis",
            "WHY A RUN STOPPED OR WENT WRONG. For one run: how far it got, every worker's verdict "
                + "in plain words (killed and why, ran out of turns, or finished having changed "
                + "nothing at all), a tally of the kill reasons, and the last few steps each "
                + "worker took — which is where the last command it ran is. This is the call that "
                + "replaces reading the log file.",
            schema("""
                {"run_id": {"type":"string","description":"The run's id, from swarm_status or list_runs."},
                 "steps_per_worker": {"type":"integer","description":"Last steps to show per worker (default 6, max 30)."},
                 "max_workers": {"type":"integer","description":"Workers to include, worst first (default 20)."}}""",
                "run_id"),
            args -> runDiagnosis(str(args, "run_id"), num(args, "steps_per_worker", 6),
                num(args, "max_workers", 20))));

        tools.add(tool("run_detail",
            "The whole shape of one run: its state and goal, the requirements it answers, the task "
                + "graph with each task's state and what it depends on, and every candidate "
                + "attempt with its model, temperature, state, judge score and whether it compiled "
                + "and was verified.",
            schema("""
                {"run_id": {"type":"string","description":"The run's id."}}""", "run_id"),
            args -> runDetail(str(args, "run_id"))));

        tools.add(tool("list_runs",
            "Every run in the selected project, newest first: id, kind, state, goal, when it "
                + "started and what it cost. Use it to find a run id; use run_detail or "
                + "run_diagnosis for anything more.",
            schema("""
                {"limit": {"type":"integer","description":"How many runs (default 25, max 200)."}}"""),
            args -> listRuns(num(args, "limit", 25))));

        tools.add(tool("session_events",
            "One worker's steps, oldest first, one page at a time: what it said, every tool it "
                + "called and every result it got back. Payloads come as short snippets with a "
                + "blob_ref; fetch a full one with event_payload or blob_text.",
            schema("""
                {"session_id": {"type":"string","description":"The worker session's id."},
                 "from_seq": {"type":"integer","description":"Start at this step number (default 0)."},
                 "limit": {"type":"integer","description":"Steps per page (default 50, max 200)."},
                 "kinds": {"type":"string","description":"Optional comma-separated filter, e.g. TOOL_CALL,ERROR. Kinds: SESSION_OPENED, LLM_RESPONSE, TOOL_CALL, TOOL_RESULT, NUDGE, KILLED, DONE, SESSION_CLOSED, ERROR."}}""",
                "session_id"),
            args -> sessionEvents(str(args, "session_id"), num(args, "from_seq", 0),
                num(args, "limit", ReplyBudget.DEFAULT_EVENTS_PER_PAGE), str(args, "kinds"))));

        tools.add(tool("event_payload",
            "The full text of one step — the whole tool call, the whole tool output — a window at "
                + "a time. Returns the total length and the next offset.",
            schema("""
                {"session_id": {"type":"string"},
                 "seq": {"type":"integer","description":"The step number from session_events."},
                 "offset": {"type":"integer","description":"Start character (default 0)."},
                 "max_chars": {"type":"integer","description":"Characters to return (default and max 20000)."}}""",
                "session_id", "seq"),
            args -> textWindow("event_payload",
                () -> observer.eventPayload(str(args, "session_id"), num(args, "seq", 0)),
                args, "session_id=" + str(args, "session_id") + " seq=" + num(args, "seq", 0))));

        tools.add(tool("session_prompt",
            "The exact instructions one worker was given — the rendered prompt bundle it opened "
                + "with. The first thing to read when a worker did something inexplicable.",
            schema("""
                {"session_id": {"type":"string"},
                 "offset": {"type":"integer","description":"Start character (default 0)."},
                 "max_chars": {"type":"integer","description":"Characters to return (default and max 20000)."}}""",
                "session_id"),
            args -> textWindow("session_prompt",
                () -> observer.sessionPrompt(str(args, "session_id")),
                args, "session_id=" + str(args, "session_id"))));

        tools.add(tool("blob_text",
            "The full text behind a blob_ref — a payload that was too big to inline, a "
                + "verification log. A window at a time.",
            schema("""
                {"ref": {"type":"string","description":"The blob_ref from an event."},
                 "offset": {"type":"integer","description":"Start character (default 0)."},
                 "max_chars": {"type":"integer","description":"Characters to return (default and max 20000)."}}""",
                "ref"),
            args -> textWindow("blob_text", () -> observer.blobText(str(args, "ref")),
                args, "ref=" + str(args, "ref"))));

        tools.add(tool("run_diff",
            "The code one run actually produced: its winning candidates' diffs, in task order, a "
                + "window at a time. Empty when nothing has been selected yet.",
            schema("""
                {"run_id": {"type":"string"},
                 "offset": {"type":"integer","description":"Start character (default 0)."},
                 "max_chars": {"type":"integer","description":"Characters to return (default and max 20000)."}}""",
                "run_id"),
            args -> textWindow("run_diff", () -> control.integratedDiff(str(args, "run_id")),
                args, "run_id=" + str(args, "run_id"))));

        tools.add(tool("pending_decisions",
            "Every question the swarm has stopped to ask and nobody has answered: what kind, which "
                + "run, when it was raised and the question itself. No arguments.",
            schema(), args -> pendingDecisions()));

        tools.add(tool("search_history",
            "Past worker sessions whose transcripts match a phrase — 'have we hit this before?'. "
                + "Returns session summaries; read one with session_events.",
            schema("""
                {"query": {"type":"string","description":"Words to look for in past transcripts."},
                 "limit": {"type":"integer","description":"How many (default 10, max 50)."}}""",
                "query"),
            args -> searchHistory(str(args, "query"), num(args, "limit", 10))));

        tools.add(tool("insights",
            "Totals across the selected project's finished work: runs, candidates, how many "
                + "survived and were selected, tokens spent, how each model family did, and how "
                + "often each kill reason fired. No arguments.",
            schema(), args -> insights()));

        tools.add(tool("list_projects",
            "Every project SwarmCoder knows, and which one is selected. Everything else here reads "
                + "the selected project only. No arguments.",
            schema(), args -> listProjects()));

        if (supervisor != null) {
            // First, not last: a supervising model is told to start with wait_for_attention, and
            // the tools it loops on should be the ones it meets first.
            tools.addAll(0, supervisor.readTools());
        }

        if (readOnly) {
            log.info("MCP: read-only — the tools that change something are not offered.");
            return tools;
        }

        tools.add(tool("start_run",
            "CHANGES SOMETHING. Starts a build in the selected project and returns its run id. "
                + "Kind must be GREENFIELD, ENHANCEMENT, BUGFIX or REFACTOR.",
            schema("""
                {"goal": {"type":"string","description":"What to build, in plain words."},
                 "kind": {"type":"string","description":"GREENFIELD | ENHANCEMENT | BUGFIX | REFACTOR"}}""",
                "goal", "kind"),
            args -> startRun(str(args, "goal"), str(args, "kind"))));

        tools.add(tool("decide_run",
            "CHANGES SOMETHING. Approves a run parked for approval — which merges its work — or "
                + "rejects it, which stops it. Read run_diff first.",
            schema("""
                {"run_id": {"type":"string"},
                 "decision": {"type":"string","description":"approve | reject"}}""",
                "run_id", "decision"),
            args -> decideRun(str(args, "run_id"), str(args, "decision"))));

        tools.add(tool("answer_decision",
            "CHANGES SOMETHING. Writes an answer to one question in the queue. It records the "
                + "answer and nothing else: it does not restart a run or retry a task. To answer "
                + "and restart the stopped build, use answer_question.",
            schema("""
                {"decision_id": {"type":"string"},
                 "answer": {"type":"string","description":"The answer to record."}}""",
                "decision_id", "answer"),
            args -> answerDecision(str(args, "decision_id"), str(args, "answer"))));

        if (supervisor != null) {
            tools.addAll(supervisor.writeTools());
        }
        return tools;
    }

    // --- 1. what is happening right now -----------------------------------------------------------

    String swarmStatus() {
        ObjectNode root = json.createObjectNode();
        long now = System.currentTimeMillis();
        root.put("at", Instant.ofEpochMilli(now).toString());

        ProjectDto current = currentProject();
        ObjectNode project = root.putObject("selectedProject");
        if (current == null) {
            project.putNull("id");
            project.put("note", "No project is selected, so there is nothing to report.");
        } else {
            project.put("id", current.getProjectId());
            project.put("name", nn(current.getName()));
            project.put("path", nn(current.getPrimaryPath()));
        }

        List<RunSummaryDto> runs = safeList(observer::listRuns, "runs");
        List<SessionSummaryDto> live = safeList(observer::activeSessions, "live workers");
        Map<String, List<SessionSummaryDto>> liveByRun = new LinkedHashMap<>();
        for (SessionSummaryDto session : live) {
            liveByRun.computeIfAbsent(nn(session.getRunId()), key -> new ArrayList<>()).add(session);
        }

        List<RunSummaryDto> inFlight = runs.stream()
            .filter(run -> !ENDED_RUN_STATES.contains(nn(run.getState())))
            .toList();

        int stalled = 0;
        int paused = 0;
        ArrayNode flight = root.putArray("runsInFlight");
        for (RunSummaryDto run : inFlight) {
            ObjectNode node = flight.addObject();
            List<SessionSummaryDto> workers =
                liveByRun.getOrDefault(nn(run.getRunId()), List.of());
            boolean isStalled = writeRun(node, run, now, workers.size());
            if (isStalled) {
                stalled++;
            }
            if (run.getPausedSinceMillis() > 0) {
                paused++;
            }
            ArrayNode workerNodes = node.putArray("liveWorkers");
            for (SessionSummaryDto worker : workers) {
                ObjectNode workerNode = workerNodes.addObject();
                writeSession(workerNode, worker, now);
                TraceEventDto last = lastEvent(worker.getSessionId());
                if (last == null) {
                    workerNode.put("currentStep", "not reported yet");
                } else {
                    ObjectNode step = workerNode.putObject("currentStep");
                    step.put("seq", last.getSeq());
                    step.put("kind", nn(last.getKind()));
                    step.put("label", nn(last.getLabel()));
                    step.put("ago", elapsed(now - last.getAtMillis()));
                    step.put("payload", ReplyBudget.snippet(last.getPayloadSnippet()));
                }
            }
        }

        ArrayNode ended = root.putArray("recentlyFinished");
        runs.stream()
            .filter(run -> ENDED_RUN_STATES.contains(nn(run.getState())))
            .limit(5)
            .forEach(run -> {
                ObjectNode node = ended.addObject();
                node.put("runId", nn(run.getRunId()));
                node.put("kind", nn(run.getKind()));
                node.put("state", nn(run.getState()));
                node.put("goal", ReplyBudget.snippet(run.getGoal()));
                node.put("tokensUsed", run.getTokensUsed());
            });

        int questions = pendingDecisionList().size();
        root.put("questionsWaitingForAnAnswer", questions);
        root.put("summary", statusSentence(inFlight, live.size(), stalled, paused, questions, now));
        return finish(root, "swarm_status", "run_diagnosis for one run");
    }

    private String statusSentence(List<RunSummaryDto> inFlight, int liveWorkers, int stalled,
                                  int paused, int questions, long now) {
        if (inFlight.isEmpty()) {
            return "Nothing is running. " + (questions == 0
                ? "Nothing is waiting for an answer either."
                : questions + " question(s) are waiting for an answer.");
        }
        StringBuilder sentence = new StringBuilder();
        sentence.append(inFlight.size()).append(" run(s) have not finished, with ")
            .append(liveWorkers).append(" worker(s) actually running right now.");
        RunSummaryDto oldest = inFlight.stream()
            .filter(run -> run.getStartedAtMillis() > 0)
            .min(Comparator.comparingLong(RunSummaryDto::getStartedAtMillis))
            .orElse(null);
        if (oldest != null) {
            sentence.append(" The oldest has been going ")
                .append(elapsed(now - oldest.getStartedAtMillis()))
                .append(" and is at the ").append(nn(oldest.getState())).append(" stage.");
        }
        if (stalled > 0) {
            sentence.append(' ').append(stalled).append(" of them have not reported in for over ")
                .append(elapsed(STALLED_AFTER_MILLIS))
                .append(" — nothing appears to be driving them. Run run_diagnosis on each.");
        }
        if (paused > 0) {
            sentence.append(' ').append(paused)
                .append(" are waiting for a model endpoint to come back and will resume by "
                    + "themselves.");
        }
        if (liveWorkers == 0 && stalled == 0 && paused == 0) {
            sentence.append(" No workers are running, so the run is between stages — planning, "
                + "verifying, judging or waiting at a gate.");
        }
        if (questions > 0) {
            sentence.append(' ').append(questions)
                .append(" question(s) are waiting for an answer (pending_decisions).");
        }
        return sentence.toString();
    }

    /** @return true when nothing appears to be driving this run */
    private boolean writeRun(ObjectNode node, RunSummaryDto run, long now, int liveWorkers) {
        node.put("runId", nn(run.getRunId()));
        node.put("kind", nn(run.getKind()));
        node.put("state", nn(run.getState()));
        node.put("goal", ReplyBudget.snippet(run.getGoal()));
        node.put("startedAt", run.getStartedAtMillis() == 0 ? ""
            : Instant.ofEpochMilli(run.getStartedAtMillis()).toString());
        node.put("runningFor", run.getStartedAtMillis() == 0 ? "unknown"
            : elapsed(now - run.getStartedAtMillis()));
        node.put("tokensUsed", run.getTokensUsed());
        node.put("liveWorkerCount", liveWorkers);

        boolean stalled = false;
        if (run.getHeartbeatAtMillis() == 0) {
            node.put("driving", "unknown");
            node.put("drivingExplained",
                "This run predates the heartbeat, so there is no way to tell whether a process is "
                    + "still on it.");
        } else {
            long since = now - run.getHeartbeatAtMillis();
            stalled = since > STALLED_AFTER_MILLIS;
            node.put("driving", stalled ? "nothing" : "a live process");
            node.put("lastReportedIn", elapsed(since));
            if (stalled) {
                node.put("drivingExplained",
                    "Nothing has touched this run for " + elapsed(since)
                        + ". Its state still says " + nn(run.getState())
                        + ", but that is only the last thing it wrote down — the process driving "
                        + "it is gone.");
            }
        }
        if (run.getPausedSinceMillis() > 0) {
            ObjectNode pause = node.putObject("paused");
            pause.put("since", elapsed(now - run.getPausedSinceMillis()) + " ago");
            pause.put("reason", nn(run.getPauseReason()));
        }
        return stalled;
    }

    private void writeSession(ObjectNode node, SessionSummaryDto session, long now) {
        node.put("sessionId", nn(session.getSessionId()));
        node.put("taskId", nn(session.getTaskId()));
        node.put("role", nn(session.getRole()));
        node.put("model", nn(session.getModel()));
        node.put("workerIndex", session.getWorkerIndex());
        node.put("turns", session.getTurns());
        node.put("tokens", session.getTokens());
        node.put("outcome", nn(session.getOutcome()));
        if (!nn(session.getKillReason()).isEmpty()) {
            node.put("killReason", session.getKillReason());
        }
        if (session.getOpenedAtMillis() > 0) {
            long until = session.getClosedAtMillis() > 0 ? session.getClosedAtMillis() : now;
            node.put(session.getClosedAtMillis() > 0 ? "ranFor" : "openFor",
                elapsed(until - session.getOpenedAtMillis()));
        }
    }

    // --- 2. what went wrong -----------------------------------------------------------------------

    String runDiagnosis(String runId, int stepsPerWorker, int maxWorkers) {
        if (blank(runId)) {
            return problem("run_diagnosis needs a run_id. Get one from swarm_status or list_runs.");
        }
        RunGraphDto snapshot;
        try {
            snapshot = graph.snapshot(runId);
        } catch (Exception e) {
            return problem("There is no run with id " + runId + " in the selected project, or it "
                + "could not be read: " + e.getMessage());
        }
        if (snapshot == null) {
            return problem("There is no run with id " + runId + " in the selected project.");
        }
        int steps = Math.max(1, Math.min(stepsPerWorker <= 0 ? 6 : stepsPerWorker, 30));
        int workerCap = ReplyBudget.rows(maxWorkers, 20);
        long now = System.currentTimeMillis();

        ObjectNode root = json.createObjectNode();
        root.put("runId", nn(snapshot.getRunId()));
        root.put("state", nn(snapshot.getRunState()));
        root.put("goal", ReplyBudget.snippet(snapshot.getGoal()));

        RunSummaryDto summary = runSummary(runId);
        if (summary != null) {
            ObjectNode driving = root.putObject("isAnythingDrivingIt");
            writeRun(driving, summary, now, 0);
            driving.remove("goal");
        }

        // Tasks: how far the plan got.
        Map<String, Integer> taskStates = new TreeMap<>();
        for (GraphTaskDto task : snapshot.getTasks()) {
            taskStates.merge(nn(task.getState()), 1, Integer::sum);
        }
        ObjectNode tasks = root.putObject("tasks");
        tasks.put("total", snapshot.getTasks().size());
        taskStates.forEach(tasks::put);

        // Candidates: every attempt and how it ended. A FAILED attempt with no kill reason ended
        // one of three ways, told apart by what is on the candidate and never by the state: it
        // changed nothing; it wrote code that verification turned down; or it wrote code that
        // nobody verified. See failedReading, which says the same three things for one worker.
        Map<String, Integer> candidateStates = new TreeMap<>();
        Map<String, Integer> killTally = new TreeMap<>();
        int producedNothing = 0;
        int failedVerification = 0;
        int neverVerified = 0;
        for (GraphCandidateDto candidate : snapshot.getCandidates()) {
            candidateStates.merge(nn(candidate.getState()), 1, Integer::sum);
            if (!nn(candidate.getKillReason()).isEmpty()) {
                killTally.merge(candidate.getKillReason(), 1, Integer::sum);
            } else if ("FAILED".equals(nn(candidate.getState()))) {
                if (candidate.isDiffEmpty()) {
                    producedNothing++;
                } else if (candidate.isVerified()) {
                    failedVerification++;
                } else {
                    neverVerified++;
                }
            }
        }
        ObjectNode candidates = root.putObject("candidates");
        candidates.put("total", snapshot.getCandidates().size());
        candidateStates.forEach(candidates::put);
        ObjectNode kills = root.putObject("killReasons");
        killTally.forEach(kills::put);
        if (producedNothing > 0) {
            kills.put("(no kill reason — the worker ran to the end and changed nothing)",
                producedNothing);
        }
        if (failedVerification > 0) {
            kills.put("(no kill reason — the worker wrote code that failed verification)",
                failedVerification);
        }
        if (neverVerified > 0) {
            kills.put("(no kill reason — the worker wrote code that was never verified)",
                neverVerified);
        }

        root.put("summary", diagnosisSentence(snapshot, summary, candidateStates, killTally,
            producedNothing, failedVerification, neverVerified, now));

        // Sessions, worst first, each with its last few steps.
        List<SessionSummaryDto> sessions = safeList(() -> graph.runSessions(runId), "sessions");
        Map<String, GraphCandidateDto> bySession = new LinkedHashMap<>();
        for (GraphCandidateDto candidate : snapshot.getCandidates()) {
            if (!nn(candidate.getSessionId()).isEmpty()) {
                bySession.put(candidate.getSessionId(), candidate);
            }
        }
        List<SessionSummaryDto> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparingInt(session -> worrySort(session, bySession)));

        ArrayNode workers = root.putArray("workers");
        int shown = 0;
        for (SessionSummaryDto session : ordered) {
            if (shown++ >= workerCap) {
                break;
            }
            ObjectNode node = workers.addObject();
            writeSession(node, session, now);
            GraphCandidateDto candidate = bySession.get(nn(session.getSessionId()));
            if (candidate != null) {
                node.put("candidateState", nn(candidate.getState()));
                node.put("compiled", candidate.isCompiles());
                node.put("verified", candidate.isVerified());
                if (candidate.getJudgeScore() >= 0) {
                    node.put("judgeScore", candidate.getJudgeScore());
                }
            }
            node.put("reading", reading(session, candidate));
            ArrayNode stepNodes = node.putArray("lastSteps");
            LastEvents last = lastEvents(nn(session.getSessionId()), steps);
            for (TraceEventDto event : last.events()) {
                writeEvent(stepNodes.addObject(), event);
            }
            if (last.cappedAt() > 0) {
                node.put("lastStepsNote", "Only the last " + stepNodes.size() + " of at least "
                    + last.cappedAt() + " steps are here. Read the rest with session_events on "
                    + "session_id " + nn(session.getSessionId()) + ".");
            }
        }
        if (ordered.size() > workerCap) {
            root.put("workersNotShown", ordered.size() - workerCap);
            root.put("workersNotShownNote", "The " + workerCap + " most worrying are here, worst "
                + "first. Ask again with a bigger max_workers for the rest.");
        }

        ArrayNode questions = root.putArray("questionsRaisedByThisRun");
        for (Decision decision : pendingDecisionList()) {
            if (runId.equals(String.valueOf(decision.runId()))) {
                writeDecision(questions.addObject(), decision);
            }
        }
        root.put("toDigDeeper",
            "session_events for a worker's whole transcript; event_payload or blob_text for the "
                + "full text of one step; session_prompt for the instructions it was given; "
                + "run_detail for the task graph.");
        return finish(root, "run_diagnosis",
            "a smaller steps_per_worker or max_workers, or session_events for one worker");
    }

    private String diagnosisSentence(RunGraphDto snapshot, RunSummaryDto summary,
                                     Map<String, Integer> candidateStates,
                                     Map<String, Integer> killTally, int producedNothing,
                                     int failedVerification, int neverVerified, long now) {
        StringBuilder sentence = new StringBuilder();
        String state = nn(snapshot.getRunState());
        int total = snapshot.getCandidates().size();
        int survived = candidateStates.getOrDefault("SURVIVED", 0)
            + candidateStates.getOrDefault("SELECTED", 0);
        int running = candidateStates.getOrDefault("RUNNING", 0);

        sentence.append("The run is at the ").append(state).append(" stage.");
        if (summary != null && summary.getHeartbeatAtMillis() > 0
                && now - summary.getHeartbeatAtMillis() > STALLED_AFTER_MILLIS) {
            sentence.append(" Nothing has touched it for ")
                .append(elapsed(now - summary.getHeartbeatAtMillis()))
                .append(", so it is stopped, not working.");
        }
        if (summary != null && summary.getPausedSinceMillis() > 0) {
            sentence.append(" It is waiting for a model endpoint: ")
                .append(nn(summary.getPauseReason()));
        }
        if (total == 0) {
            sentence.append(" No worker has been dispatched yet, so nothing has been attempted.");
            return sentence.toString();
        }
        sentence.append(' ').append(survived).append(" of ").append(total)
            .append(" attempts produced usable work");
        if (running > 0) {
            sentence.append(" and ").append(running).append(" are still going");
        }
        sentence.append('.');
        if (producedNothing > 0) {
            sentence.append(' ').append(producedNothing)
                .append(" worker(s) ran to the end and changed no code at all — look at their last "
                    + "steps below for what they were doing instead.");
        }
        if (failedVerification > 0) {
            sentence.append(' ').append(failedVerification)
                .append(" worker(s) wrote code that failed verification — each one's reading "
                    + "below says what the verifier found.");
        }
        if (neverVerified > 0) {
            sentence.append(' ').append(neverVerified)
                .append(" worker(s) wrote code that was never verified, which counts as failed "
                    + "by rule.");
        }
        if (!killTally.isEmpty()) {
            sentence.append(" Workers were stopped early for: ");
            sentence.append(String.join(", ", killTally.entrySet().stream()
                .map(entry -> entry.getValue() + "× " + entry.getKey()).toList()));
            sentence.append('.');
        }
        return sentence.toString();
    }

    /** Lower sorts first: the workers most worth looking at. */
    private static int worrySort(SessionSummaryDto session, Map<String, GraphCandidateDto> bySession) {
        GraphCandidateDto candidate = bySession.get(nn(session.getSessionId()));
        String state = candidate == null ? nn(session.getOutcome()) : nn(candidate.getState());
        if (!nn(session.getKillReason()).isEmpty()
            || (candidate != null && !nn(candidate.getKillReason()).isEmpty())) {
            return 0;
        }
        return switch (state) {
            case "FAILED", "KILLED" -> 1;
            case "RUNNING" -> 2;
            case "SUPERSEDED" -> 4;
            case "SELECTED", "SURVIVED", "COMPLETED" -> 5;
            default -> 3;
        };
    }

    /** One worker's outcome, said the way a person would say it. */
    private static String reading(SessionSummaryDto session, GraphCandidateDto candidate) {
        String kill = !nn(session.getKillReason()).isEmpty() ? session.getKillReason()
            : candidate == null ? "" : nn(candidate.getKillReason());
        if (!kill.isEmpty()) {
            String base = "Stopped early after " + session.getTurns() + " turns: " + killExplained(kill);
            // The evidence behind DOCS_DEAD_END — every lookup_api question this worker asked and
            // the section each one answered with — travels on the candidate rather than only in a
            // log line, so the owner reads it here without opening session_events.
            String evidence = candidate == null ? "" : nn(candidate.getDocsDeadEndEvidence());
            return evidence.isEmpty() ? base
                : base + " Its lookup_api questions and the section each one returned: "
                    + evidence.replace("\n", "  ");
        }
        String state = candidate == null ? nn(session.getOutcome()) : nn(candidate.getState());
        return switch (state) {
            case "RUNNING" -> "Still working, " + session.getTurns() + " turns so far.";
            case "FAILED" -> failedReading(session, candidate);
            case "SELECTED" -> "Won: this is the work that was kept.";
            case "SURVIVED" -> "Produced working code that was not the one chosen.";
            case "SUPERSEDED" -> "Abandoned because another worker on the same task finished first.";
            case "COMPLETED" -> "Finished normally.";
            default -> state.isEmpty() ? "No outcome recorded." : "Ended as " + state + ".";
        };
    }

    /**
     * A FAILED attempt with no kill reason, read from what is on the candidate and not from the
     * state. Three things can have happened and only one of them is "it wrote nothing":
     *
     * <ul>
     *   <li>its diff is empty — it ran to the end and changed no code;</li>
     *   <li>it wrote code and verification turned it down — the verdict the engine recorded on
     *       the report says why, in the same words the graph's hover card uses;</li>
     *   <li>it wrote code and nobody verified it — which counts as failed by rule.</li>
     * </ul>
     *
     * <p>Until 2026-09-02 every one of these was "its diff was empty", and four workers that had
     * each written their two files and then failed test-compile on stale acceptance tests were
     * reported as having written nothing. An hour went on a git failure that never happened.
     */
    private static String failedReading(SessionSummaryDto session, GraphCandidateDto candidate) {
        String ran = "Ran to the end after " + session.getTurns() + " turns";
        if (candidate == null) {
            return ran + " and ended as FAILED. No candidate record is attached to this session, "
                + "so whether it changed any code is not known here — its steps below are the "
                + "evidence.";
        }
        if (candidate.isDiffEmpty()) {
            return ran + " and changed no code at all — its diff was empty. Its own last words "
                + "are in the steps below and are the best evidence of why.";
        }
        String wrote = candidate.getChangedFiles() > 0
            ? ran + " and wrote " + candidate.getChangedFiles() + " changed file(s)"
            : ran + " and produced a diff that adds or changes no file";
        if (!candidate.isVerified()) {
            return wrote + ", but was never verified — no verification report was recorded, and "
                + "an attempt nobody verified counts as failed by rule. Its steps are below.";
        }
        String verdict = nn(candidate.getFailReason());
        if (verdict.isEmpty()) {
            return wrote + " but failed verification. The report carries no verdict line, so "
                + "read the verification report itself for why.";
        }
        return wrote + " but failed verification: " + verdict + ".";
    }

    private static String killExplained(String reason) {
        return switch (reason) {
            case "PARSE_FAIL" -> "PARSE_FAIL — the model's replies could not be read as tool calls.";
            case "TOOLCALL_MALFORMED" -> "TOOLCALL_MALFORMED — it kept calling tools wrongly.";
            case "BUDGET_EXCEEDED" -> "BUDGET_EXCEEDED — it used up the tokens allowed for one attempt.";
            case "WRITESET_VIOLATION" -> "WRITESET_VIOLATION — it kept trying to edit protected "
                + "files: the tests that judge it, or the settings that decide whether it passed.";
            case "COMPILE_FAIL_TWICE" -> "COMPILE_FAIL_TWICE — its code failed to compile twice running.";
            case "TIMEOUT" ->
                "TIMEOUT — its own request to the model took longer than the client would wait, "
                    + "while the same model server kept answering other workers normally. So this "
                    + "is not an outage and waiting will not help: the conversation it sent had "
                    + "grown too large to answer in time. Give this model a smaller working "
                    + "context, so histories are compacted sooner, or a lower turn allowance.";
            case "SUPERSEDED" -> "SUPERSEDED — another worker on the same task finished first.";
            case "SANDBOX_UNAVAILABLE" ->
                "SANDBOX_UNAVAILABLE — the sandbox is required and Docker did not answer, so it was "
                    + "refused rather than run loose on the machine.";
            case "ENDPOINT_OUTAGE" ->
                "ENDPOINT_OUTAGE — the model endpoint never answered. This says nothing about the "
                    + "work; the run waits the outage out.";
            case "NO_ACCEPTANCE_EVIDENCE" ->
                "NO_ACCEPTANCE_EVIDENCE — it compiled and broke nothing, but no test was actually "
                    + "run, so there is no evidence it did the job. Usually the project's own "
                    + "verification contract selects no tests, or the tests were never written.";
            case "WORKER_ERROR" ->
                "WORKER_ERROR — it died before or around the model session for a mechanical reason: "
                    + "a git worktree could not be created, a sandbox could not be attached, or "
                    + "something else in the local setup failed. Not a timeout — nothing here waits "
                    + "on a clock.";
            case "DOCS_DEAD_END" -> "DOCS_DEAD_END — " + KillReason.DOCS_DEAD_END.sentence() + ".";
            case "TURN_CAP" -> "TURN_CAP — " + KillReason.TURN_CAP.sentence() + ".";
            default -> reason;
        };
    }

    // --- 3. the rest ------------------------------------------------------------------------------

    String runDetail(String runId) {
        if (blank(runId)) {
            return problem("run_detail needs a run_id.");
        }
        RunGraphDto snapshot;
        try {
            snapshot = graph.snapshot(runId);
        } catch (Exception e) {
            return problem("Could not read run " + runId + ": " + e.getMessage());
        }
        if (snapshot == null) {
            return problem("There is no run with id " + runId + " in the selected project.");
        }
        long now = System.currentTimeMillis();
        ObjectNode root = json.createObjectNode();
        root.put("runId", nn(snapshot.getRunId()));
        root.put("state", nn(snapshot.getRunState()));
        root.put("goal", ReplyBudget.snippet(snapshot.getGoal()));

        ArrayNode requirements = root.putArray("requirements");
        for (GraphRequirementDto requirement : capped(snapshot.getRequirements(), root,
                "requirements")) {
            ObjectNode node = requirements.addObject();
            node.put("id", nn(requirement.getRequirementId()));
            node.put("handle", nn(requirement.getHandle()));
            node.put("text", ReplyBudget.snippet(requirement.getText()));
            node.put("priority", nn(requirement.getPriority()));
            node.put("coveredByTasks", nn(requirement.getCoveredByCsv()));
        }
        ArrayNode tasks = root.putArray("tasks");
        for (GraphTaskDto task : capped(snapshot.getTasks(), root, "tasks")) {
            ObjectNode node = tasks.addObject();
            node.put("taskId", nn(task.getTaskId()));
            node.put("title", ReplyBudget.snippet(task.getTitle()));
            node.put("state", nn(task.getState()));
            node.put("dependsOn", nn(task.getDependsOnCsv()));
            node.put("mayWriteTo", nn(task.getWriteSetCsv()));
            node.put("acceptanceCriteria", task.getCriteria());
            node.put("answersRequirements", nn(task.getRequirementIdsCsv()));
            // What the test author did for this task - the same three facts the run graph's
            // badge shows, so an outside agent watching a run can tell a test-authoring stage
            // that is working from one that is hung.
            node.put("testsPhase", nn(task.getTestsPhase()));
            node.put("testsWritten", task.getTestsWritten());
            node.put("checksProved", task.getChecksProved());
        }
        ArrayNode candidates = root.putArray("candidates");
        for (GraphCandidateDto candidate : capped(snapshot.getCandidates(), root, "candidates")) {
            ObjectNode node = candidates.addObject();
            node.put("candidateId", nn(candidate.getCandidateId()));
            node.put("sessionId", nn(candidate.getSessionId()));
            node.put("taskId", nn(candidate.getTaskId()));
            node.put("workerIndex", candidate.getWorkerIndex());
            node.put("model", nn(candidate.getModel()));
            node.put("temperature", candidate.getTemperature());
            node.put("state", nn(candidate.getState()));
            node.put("killReason", nn(candidate.getKillReason()));
            node.put("turns", candidate.getTurns());
            node.put("tokens", candidate.getTokens());
            if (candidate.getJudgeScore() < 0) {
                node.putNull("judgeScore");
            } else {
                node.put("judgeScore", candidate.getJudgeScore());
            }
            node.put("compiled", candidate.isCompiles());
            node.put("verified", candidate.isVerified());
        }
        ArrayNode sessions = root.putArray("sessions");
        for (SessionSummaryDto session
                : capped(safeList(() -> graph.runSessions(runId), "sessions"), root, "sessions")) {
            writeSession(sessions.addObject(), session, now);
        }
        return finish(root, "run_detail", "run_diagnosis, which is smaller and says what went wrong");
    }

    String listRuns(int limit) {
        int cap = ReplyBudget.rows(limit, 25);
        long now = System.currentTimeMillis();
        List<RunSummaryDto> runs = safeList(observer::listRuns, "runs");
        ObjectNode root = json.createObjectNode();
        root.put("totalInThisProject", runs.size());
        root.put("shown", Math.min(cap, runs.size()));
        ArrayNode rows = root.putArray("runs");
        runs.stream().limit(cap).forEach(run -> writeRun(rows.addObject(), run, now, 0));
        if (runs.size() > cap) {
            root.put("note", "The newest " + cap + " of " + runs.size()
                + " are here. Ask again with a bigger limit for more.");
        }
        return finish(root, "list_runs", "a smaller limit");
    }

    String sessionEvents(String sessionId, int fromSeq, int limit, String kinds) {
        if (blank(sessionId)) {
            return problem("session_events needs a session_id, from swarm_status or run_diagnosis.");
        }
        int cap = Math.max(1, Math.min(limit <= 0 ? ReplyBudget.DEFAULT_EVENTS_PER_PAGE : limit,
            ReplyBudget.MAX_EVENTS_PER_PAGE));
        Set<String> wanted = new LinkedHashSet<>();
        if (!blank(kinds)) {
            for (String kind : kinds.toUpperCase().replace(" ", "").split(",")) {
                if (!kind.isBlank()) {
                    wanted.add(kind);
                }
            }
        }

        List<TraceEventDto> page;
        try {
            // Over-fetch when filtering, so a page of TOOL_CALLs is not one event long.
            page = observer.sessionEvents(sessionId, Math.max(0, fromSeq),
                wanted.isEmpty() ? cap : Math.min(500, cap * 5));
        } catch (Exception e) {
            return problem("Could not read session " + sessionId + ": " + e.getMessage());
        }
        List<TraceEventDto> kept = new ArrayList<>();
        long lastSeqSeen = Math.max(0, fromSeq) - 1;
        for (TraceEventDto event : page) {
            lastSeqSeen = event.getSeq();
            if (wanted.isEmpty() || wanted.contains(nn(event.getKind()))) {
                kept.add(event);
                if (kept.size() >= cap) {
                    break;
                }
            }
        }
        ObjectNode root = json.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("fromSeq", Math.max(0, fromSeq));
        root.put("returned", kept.size());
        if (!wanted.isEmpty()) {
            root.put("filteredTo", String.join(",", wanted));
        }
        ArrayNode events = root.putArray("events");
        for (TraceEventDto event : kept) {
            writeEvent(events.addObject(), event);
        }
        if (page.isEmpty()) {
            root.put("note", "No steps at or after that number. Either the session has none, or "
                + "you have reached the end.");
        } else {
            root.put("nextFromSeq", lastSeqSeen + 1);
            root.put("note", "Payloads here are the first " + ReplyBudget.SNIPPET_CHARS
                + " characters only. Use event_payload for the whole of one, and nextFromSeq for "
                + "the next page.");
        }
        return finish(root, "session_events", "a smaller limit");
    }

    private void writeEvent(ObjectNode node, TraceEventDto event) {
        node.put("seq", event.getSeq());
        node.put("at", event.getAtMillis() == 0 ? ""
            : Instant.ofEpochMilli(event.getAtMillis()).toString());
        node.put("kind", nn(event.getKind()));
        node.put("label", nn(event.getLabel()));
        node.put("tokens", event.getTokens());
        node.put("payload", ReplyBudget.snippet(event.getPayloadSnippet()));
        if (!nn(event.getPayloadRef()).isEmpty()) {
            node.put("blobRef", event.getPayloadRef());
            node.put("payloadIsCut", true);
        }
    }

    String pendingDecisions() {
        List<Decision> pending = pendingDecisionList();
        ObjectNode root = json.createObjectNode();
        root.put("waiting", pending.size());
        root.put("summary", pending.isEmpty()
            ? "Nothing is waiting for an answer."
            : pending.size() + " question(s) are waiting. Answering one records the answer and "
                + "nothing else — it does not restart a run or retry a task.");
        ArrayNode rows = root.putArray("questions");
        pending.stream().limit(ReplyBudget.MAX_ROWS)
            .forEach(decision -> writeDecision(rows.addObject(), decision));
        return finish(root, "pending_decisions", "nothing — this list is already capped");
    }

    private void writeDecision(ObjectNode node, Decision decision) {
        node.put("decisionId", String.valueOf(decision.id()));
        node.put("runId", String.valueOf(decision.runId()));
        node.put("kind", decision.kind() == null ? "" : decision.kind().name());
        node.put("raisedAt", decision.createdAt() == null ? "" : decision.createdAt().toString());
        node.put("question", ReplyBudget.snippet(decision.briefMarkdown()));
        if (decision.kind() != null && decision.kind().name().equals("APPROVAL")) {
            node.put("howToAnswer",
                "An approval is answered with decide_run on the run, not with answer_decision.");
        }
    }

    String searchHistory(String query, int limit) {
        if (blank(query)) {
            return problem("search_history needs a query.");
        }
        int cap = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
        long now = System.currentTimeMillis();
        List<SessionSummaryDto> hits;
        try {
            hits = observer.searchHistory(query, cap);
        } catch (Exception e) {
            return problem("The history search failed: " + e.getMessage());
        }
        ObjectNode root = json.createObjectNode();
        root.put("query", query);
        root.put("found", hits.size());
        ArrayNode rows = root.putArray("sessions");
        for (SessionSummaryDto session : hits) {
            writeSession(rows.addObject(), session, now);
        }
        root.put("note", "Read one with session_events on its sessionId.");
        return finish(root, "search_history", "a smaller limit");
    }

    String insights() {
        InsightsDto dto;
        try {
            dto = observer.insights();
        } catch (Exception e) {
            return problem("The totals could not be worked out: " + e.getMessage());
        }
        ObjectNode root = json.createObjectNode();
        root.put("runs", dto.getTotalRuns());
        root.put("candidates", dto.getTotalCandidates());
        root.put("candidatesSelected", dto.getSelectedCandidates());
        root.put("sessions", dto.getTotalSessions());
        root.put("tokens", dto.getTotalTokens());
        ArrayNode families = root.putArray("byModelFamily");
        for (String row : rows(dto.getFamilyRows())) {
            String[] cells = row.split("\t");
            if (cells.length >= 4) {
                ObjectNode node = families.addObject();
                node.put("family", cells[0]);
                node.put("dispatched", cells[1]);
                node.put("survived", cells[2]);
                node.put("selected", cells[3]);
            }
        }
        ObjectNode kills = root.putObject("killReasons");
        for (String row : rows(dto.getKillReasonRows())) {
            String[] cells = row.split("\t");
            if (cells.length >= 2) {
                kills.put(cells[0], cells[1]);
            }
        }
        root.put("note", "Scoped to the selected project. The per-candidate temperature scatter is "
            + "left out on purpose: it is one line per candidate ever run and belongs on a chart, "
            + "not in a tool reply.");
        return finish(root, "insights", "nothing — this reply is already bounded");
    }

    String listProjects() {
        ObjectNode root = json.createObjectNode();
        ArrayNode rows = root.putArray("projects");
        for (ProjectDto project : safeList(control::projects, "projects")) {
            ObjectNode node = rows.addObject();
            node.put("id", nn(project.getProjectId()));
            node.put("name", nn(project.getName()));
            node.put("path", nn(project.getPrimaryPath()));
            node.put("selected", project.isCurrent());
        }
        root.put("note", supervisor == null
            ? "Every other tool here reads the selected project only. This server "
                + "cannot switch projects — do that in the Console."
            : "Every other tool here works on the selected project only. switch_project selects "
                + "another.");
        return finish(root, "list_projects", "nothing — this reply is already bounded");
    }

    // --- 4. the verbs that change something --------------------------------------------------------

    String startRun(String goal, String kind) {
        if (blank(goal)) {
            return problem("start_run needs a goal.");
        }
        String normalised = nn(kind).trim().toUpperCase();
        if (!Set.of("GREENFIELD", "ENHANCEMENT", "BUGFIX", "REFACTOR").contains(normalised)) {
            return problem("kind must be GREENFIELD, ENHANCEMENT, BUGFIX or REFACTOR. "
                + "DOCS and ANALYSIS are refused on purpose: they used to produce a run that "
                + "walked through its stages and reported success without building anything.");
        }
        try {
            String runId = control.submitIntake(goal, normalised);
            ObjectNode root = json.createObjectNode();
            root.put("started", true);
            root.put("runId", runId);
            root.put("note", "Watch it with swarm_status, or run_diagnosis on this run id.");
            return finish(root, "start_run", "nothing");
        } catch (Exception e) {
            return problem("The run could not be started: " + e.getMessage());
        }
    }

    String decideRun(String runId, String decision) {
        if (blank(runId) || blank(decision)) {
            return problem("decide_run needs a run_id and a decision of approve or reject.");
        }
        String word = decision.trim().toLowerCase();
        try {
            if (word.equals("approve")) {
                control.approveRun(runId);
            } else if (word.equals("reject")) {
                control.rejectRun(runId);
            } else {
                return problem("decision must be approve or reject.");
            }
        } catch (Exception e) {
            return problem("The run could not be " + word + "d: " + e.getMessage());
        }
        ObjectNode root = json.createObjectNode();
        root.put("runId", runId);
        root.put("decision", word);
        root.put("done", true);
        return finish(root, "decide_run", "nothing");
    }

    String answerDecision(String decisionId, String answer) {
        if (blank(decisionId) || blank(answer)) {
            return problem("answer_decision needs a decision_id and an answer.");
        }
        try {
            control.resolveDecision(decisionId, answer);
        } catch (Exception e) {
            return problem("The answer could not be recorded: " + e.getMessage());
        }
        ObjectNode root = json.createObjectNode();
        root.put("decisionId", decisionId);
        root.put("recorded", true);
        root.put("note", "The answer is written down. Nothing restarts: no run is resumed and no "
            + "task is retried by this.");
        return finish(root, "answer_decision", "nothing");
    }

    // --- shared plumbing ---------------------------------------------------------------------------

    /** A window over a long text, with the arithmetic to ask for the next one. */
    private String textWindow(String toolName, ThrowingSupplier source, Map<String, Object> args,
                              String what) {
        String whole;
        try {
            whole = source.get();
        } catch (Exception e) {
            return problem(toolName + " could not read " + what + ": " + e.getMessage());
        }
        if (whole == null || whole.isEmpty()) {
            ObjectNode empty = json.createObjectNode();
            empty.put("of", what);
            empty.put("totalChars", 0);
            empty.put("text", "");
            empty.put("note", "There is nothing there — either it was never recorded, or the id is "
                + "wrong.");
            return finish(empty, toolName, "nothing");
        }
        ReplyBudget.Window window = ReplyBudget.window(whole, num(args, "offset", 0),
            num(args, "max_chars", ReplyBudget.MAX_WINDOW_CHARS));
        ObjectNode root = json.createObjectNode();
        root.put("of", what);
        root.put("totalChars", window.total());
        root.put("offset", window.offset());
        root.put("returnedChars", window.returned());
        root.put("text", window.text());
        if (window.more()) {
            root.put("nextOffset", window.nextOffset());
            root.put("note", "CUT. This is characters " + window.offset() + " to "
                + window.nextOffset() + " of " + window.total()
                + ". Call again with offset=" + window.nextOffset() + " for the next piece.");
        } else {
            root.put("note", "Complete — this is the whole text.");
        }
        return finish(root, toolName, "a smaller max_chars");
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private record LastEvents(List<TraceEventDto> events, long cappedAt) { }

    /**
     * The last few steps of a session, found by walking forward and keeping a tail. The design note
     * puts a 30-turn worker at 60–100 events, so {@link #MAX_EVENT_PAGES} pages of 500 covers every
     * real session with room to spare — and when it does not, the caller is told.
     */
    private LastEvents lastEvents(String sessionId, int keep) {
        Deque<TraceEventDto> tail = new ArrayDeque<>();
        long from = 0;
        long seen = 0;
        boolean capped = false;
        for (int page = 0; page < MAX_EVENT_PAGES; page++) {
            List<TraceEventDto> events;
            try {
                events = observer.sessionEvents(sessionId, from, 500);
            } catch (Exception e) {
                log.debug("MCP: could not read events of session {}: {}", sessionId, e.toString());
                break;
            }
            if (events.isEmpty()) {
                break;
            }
            seen += events.size();
            for (TraceEventDto event : events) {
                tail.addLast(event);
                if (tail.size() > keep) {
                    tail.removeFirst();
                }
            }
            if (events.size() < 500) {
                break;
            }
            from = events.get(events.size() - 1).getSeq() + 1;
            capped = page == MAX_EVENT_PAGES - 1;
        }
        return new LastEvents(new ArrayList<>(tail), seen > keep || capped ? seen : 0);
    }

    /**
     * What this worker is doing right now.
     *
     * <p>Asked of the Console rather than worked out here. It used to page every event of the
     * session from the front and keep the last, which is the same question the run graph now also
     * asks — and two walks of the same list, written twice, are two chances to disagree about
     * whether a worker is alive.
     */
    private TraceEventDto lastEvent(String sessionId) {
        List<TraceEventDto> tail = safeList(() -> observer.lastStep(sessionId), "the last step");
        return tail.isEmpty() ? null : tail.get(0);
    }

    private RunSummaryDto runSummary(String runId) {
        for (RunSummaryDto run : safeList(observer::listRuns, "runs")) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    private ProjectDto currentProject() {
        for (ProjectDto project : safeList(control::projects, "projects")) {
            if (project.isCurrent()) {
                return project;
            }
        }
        return null;
    }

    private List<Decision> pendingDecisionList() {
        List<Decision> all = safeList(control::decisions, "questions");
        List<Decision> pending = new ArrayList<>();
        for (Decision decision : all) {
            if (decision.state() == null || decision.state() == DecisionState.PENDING) {
                pending.add(decision);
            }
        }
        return pending;
    }

    private <T> List<T> safeList(ThrowingList<T> source, String what) {
        try {
            List<T> list = source.get();
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("MCP: could not read {}: {}", what, e.toString());
            return List.of();
        }
    }

    private interface ThrowingList<T> {
        List<T> get() throws Exception;
    }

    /** Cuts a list to {@link ReplyBudget#MAX_ROWS} and records on the reply that it did. */
    private <T> List<T> capped(List<T> list, ObjectNode root, String what) {
        if (list == null) {
            return List.of();
        }
        if (list.size() <= ReplyBudget.MAX_ROWS) {
            return list;
        }
        root.put(what + "NotShown", list.size() - ReplyBudget.MAX_ROWS);
        root.put(what + "NotShownNote", "CUT. " + list.size() + " " + what + " exist and the first "
            + ReplyBudget.MAX_ROWS + " are here.");
        return list.subList(0, ReplyBudget.MAX_ROWS);
    }

    private static List<String> rows(String tabDelimited) {
        if (tabDelimited == null || tabDelimited.isBlank()) {
            return List.of();
        }
        return List.of(tabDelimited.strip().split("\n"));
    }

    private String finish(ObjectNode root, String toolName, String narrowerCall) {
        return ReplyBudget.clampReply(root.toString(), narrowerCall + " (from " + toolName + ")");
    }

    private String problem(String plainEnglish) {
        ObjectNode root = json.createObjectNode();
        root.put("problem", plainEnglish);
        return root.toString();
    }

    // --- MCP scaffolding ---------------------------------------------------------------------------

    static SyncToolRegistration tool(
            String name, String description, String inputSchema,
            java.util.function.Function<Map<String, Object>, String> body) {
        return new SyncToolRegistration(
            new McpSchema.Tool(name, description, inputSchema),
            args -> {
                try {
                    return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(null, null, null,
                            body.apply(args == null ? Map.of() : args))), false);
                } catch (Exception e) {
                    log.warn("MCP: tool {} failed: {}", name, e.toString(), e);
                    return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(null, null, null,
                            "{\"problem\":\"" + name + " failed: "
                                + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}")), true);
                }
            });
    }

    /** A tools/list input schema; {@code properties} is the raw JSON of the properties object. */
    static String schema(String properties, String... required) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":");
        sb.append(properties).append(",\"required\":[");
        for (int i = 0; i < required.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(required[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    static String schema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static int num(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }

    /**
     * Durations the way a person says them.
     *
     * <p>The wording moved to {@link com.swarmcoder.domain.Elapsed} so the run graph in the browser
     * says "3m" about a worker at the same moment this tool does. It is the same fact.
     */
    static String elapsed(long millis) {
        return com.swarmcoder.domain.Elapsed.words(millis);
    }
}
