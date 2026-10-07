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

import com.swarmcoder.console.api.GraphCandidateDto;
import com.swarmcoder.console.api.GraphRequirementDto;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.GraphTestDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.AuthoredTest;
import com.swarmcoder.domain.AuthoredTests;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Requirement;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.WorkerHealth;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.serializer.reference.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.swarmcoder.console.api.SessionSummaryDto;
import java.util.Comparator;

@ApplicationScoped
public class GraphServiceImpl implements GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphServiceImpl.class);
    private static final long POLL_MS = 700;
    private static final long WATCH_CEILING_MS = 60 * 60_000;
    /** One publisher per watched run, JVM-wide (the service bean is per-container). */
    private static final Map<UUID, Thread> publishers = new ConcurrentHashMap<>();
    private static final AtomicLong seq = new AtomicLong();

    /**
     * The run's graph as it is right now.
     *
     * <p>It is stamped with a fresh sequence number, and that is not decoration. The browser drops
     * a frame whose sequence is not newer than the one it already holds — the rule that keeps a
     * late push from overwriting a newer one. A snapshot fetched by hand used to carry no sequence
     * at all, i.e. zero, so from the moment the first pushed frame arrived for a run, EVERY
     * snapshot the browser fetched afterwards was thrown away as stale. Opening the graph again
     * could not recover it; only reloading the page could. That is how a Console can hold correct
     * data and show an empty box: the frame on screen was assembled before the swarm was
     * dispatched, and the fresh one that had the workers in it was discarded on arrival.
     */
    @Override
    public RunGraphDto snapshot(String runId) {
        RunGraphDto graph = assemble(UUID.fromString(runId));
        graph.setSeq(seq.incrementAndGet());
        return graph;
    }

    @Override
    public List<SessionSummaryDto> runSessions(String runId) {
        UUID id = UUID.fromString(runId);
        List<SessionSummaryDto> sessions = new ArrayList<>();
        for (Lazy<Object> lazy : ConsoleContext.get().store().root().agentSessions().values()) {
            if (Lazy.get(lazy) instanceof AgentSessionRecord record && id.equals(record.runId())) {
                sessions.add(Dtos.of(record));
            }
        }
        sessions.sort(Comparator.comparingLong(
            SessionSummaryDto::getOpenedAtMillis));
        return sessions;
    }

    /**
     * The text of one test file the author wrote for a task - and only one the task's own record
     * names, read from the project's checkout.
     *
     * <p>The names and the checks they prove ride on every frame; the source is fetched here, on
     * the click that opens the panel, because a file is kilobytes and a frame arrives about once a
     * second while a swarm runs. Restricting the path to what the record names is what makes this
     * a "show me that test" call and not a "read any file" call. Every failure is a sentence, not
     * an exception: the panel is already open and the operator is looking at it.
     */
    @Override
    public String testSource(String runId, String taskId, String path) {
        ConsoleContext context = ConsoleContext.get();
        Run run = context.store().root().runs.get(UUID.fromString(runId));
        if (run == null) {
            return "This run is not in the store any more.";
        }
        TaskGraph taskGraph = run.taskGraphId() == null
            ? null : context.store().root().taskGraphs.get(run.taskGraphId());
        Task task = null;
        if (taskGraph != null && taskGraph.tasks() != null) {
            for (Task candidate : taskGraph.tasks()) {
                if (candidate.id() != null && candidate.id().toString().equals(taskId)) {
                    task = candidate;
                }
            }
        }
        if (task == null || task.authoredTests() == null
                || !task.authoredTests().files().contains(path)) {
            return "This task's record does not name a test file at " + path + ".";
        }
        Project project = run.projectId() == null
            ? null : context.store().root().projects.get(run.projectId());
        if (project == null || project.primaryPath() == null || project.primaryPath().isBlank()) {
            return "The project this run belongs to has no folder configured, so the file cannot "
                + "be read.";
        }
        try {
            Path file = Path.of(project.primaryPath()).resolve(path.replace('\\', '/')).normalize();
            if (!file.startsWith(Path.of(project.primaryPath()).toAbsolutePath().normalize())
                    && !file.startsWith(Path.of(project.primaryPath()).normalize())) {
                return "That path leads outside the project's folder, so it is not read.";
            }
            return WireBudget.clamp(Files.readString(file), "This test file");
        } catch (Exception e) {
            return "The file " + path + " could not be read from the project's folder: "
                + e.getMessage();
        }
    }

    @Override
    public void watch(String runId) {
        UUID id = UUID.fromString(runId);
        publishers.computeIfAbsent(id, key -> {
            Thread publisher = new Thread(() -> publishLoop(key), "run-graph-" + runId.substring(0, 8));
            publisher.setDaemon(true);
            publisher.start();
            return publisher;
        });
    }

    /** Pushes a fresh snapshot whenever the graph changes; ends when the run parks. */
    private static void publishLoop(UUID runId) {
        try {
            String lastFingerprint = "";
            long deadline = System.currentTimeMillis() + WATCH_CEILING_MS;
            while (System.currentTimeMillis() < deadline) {
                RunGraphDto graph = assemble(runId);
                String fingerprint = fingerprint(graph);
                if (!fingerprint.equals(lastFingerprint)) {
                    lastFingerprint = fingerprint;
                    graph.setSeq(seq.incrementAndGet());
                    ConsoleContext.get().push("run-graph", graph);
                    // Task states move inside the swarm engine, which knows nothing about the
                    // Console. Rather than plumb a callback down through sc-swarm, republish the
                    // backlog off the same change detection — the panel's task badges are exactly
                    // the states this fingerprint already covers.
                    ConsoleContext context = ConsoleContext.get();
                    BacklogPublisher.publish(context.store(), context.currentProjectId());
                }
                if (isTerminal(graph.getRunState())) {
                    return;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("run-graph publisher for {} died: {}", runId, e.getMessage());
        } finally {
            publishers.remove(runId);
        }
    }

    private static boolean isTerminal(String state) {
        // APPROVAL was counted here because a run stopping there was as final as one delivered. It no
        // longer exists as a resting place (UX v3 §2.3), but a run persisted in it by an older build
        // still stops publishing, so it is kept as a terminal state rather than left to poll for ever.
        return "APPROVAL".equals(state) || "DELIVERED".equals(state) || "ABORTED".equals(state);
    }

    /** Cheap change detector over everything the graph renders. */
    static String fingerprint(RunGraphDto graph) {
        StringBuilder sb = new StringBuilder(graph.getRunState());
        for (GraphTaskDto task : graph.getTasks()) {
            sb.append('|').append(task.getTaskId()).append(':').append(task.getState())
                // Where the test author is with this task, and what it wrote. A task state does
                // not move during TEST_AUTHORING - every task is PENDING from the start of the
                // stage to the end of it - so without these the stage produced no new frame at
                // all, and a badge the server knew about would never have reached the screen
                // until the run moved on. That is the same defect as the chips that vanished
                // between finishing and verification: a fact the server holds and a frame the
                // browser never gets.
                .append(':').append(task.getTestsPhase())
                .append(':').append(task.getTestsWritten())
                .append(':').append(task.getChecksProved())
                .append(':').append(task.getTestProblems());
        }
        for (GraphCandidateDto candidate : graph.getCandidates()) {
            sb.append('|').append(candidate.getTaskId()).append('#').append(candidate.getWorkerIndex())
                .append(':').append(candidate.getState()).append(':').append(candidate.getTurns())
                .append(':').append(candidate.getTokens()).append(':').append(candidate.getJudgeScore())
                // The step a live worker is on. Without it a worker thinking for four minutes
                // produced no change to publish, so the screen had no way to say it was alive —
                // and the moment it does take a step, the screen says so.
                .append(':').append(candidate.getLastStepAtMillis())
                // How long a live worker has been quiet, to the minute, and whether that has
                // crossed into trouble. Silence is the ONLY thing on this screen that changes
                // while nothing happens, and nothing else here changes with it: a worker that goes
                // quiet takes no step, so it produced no new frame, so the age of its last step
                // froze on the screen at whatever it read when the worker was last alive — and the
                // colour that says it is dying could never appear at all. To the minute rather
                // than to the second because that is the granularity the age is written in above a
                // minute, and a frame a second per worker for the length of a run buys nothing.
                .append(':').append(quietMinutes(candidate, graph.getAtMillis()))
                .append(':').append(health(candidate, graph.getAtMillis()));
        }
        return sb.toString();
    }

    /** Whole minutes since a running worker last did anything; -1 for one that is not running. */
    private static long quietMinutes(GraphCandidateDto candidate, long nowMillis) {
        if (!"RUNNING".equals(candidate.getState()) || candidate.getLastStepAtMillis() <= 0) {
            return -1;
        }
        return Math.max(0, (nowMillis - candidate.getLastStepAtMillis()) / 60_000);
    }

    /** The one derivation of whether this worker is heading for trouble — see {@link WorkerHealth}. */
    private static WorkerHealth.Kind health(GraphCandidateDto candidate, long nowMillis) {
        if (!"RUNNING".equals(candidate.getState())) {
            return WorkerHealth.Kind.UNKNOWN;
        }
        return WorkerHealth.of(candidate.getTokens(), candidate.getContextBudgetTokens(),
            candidate.getLastStepKind(), candidate.getLastStepAtMillis(), nowMillis);
    }

    // --- assembly --------------------------------------------------------------------------------

    private static RunGraphDto assemble(UUID runId) {
        ConsoleContext context = ConsoleContext.get();
        RunGraphDto graph = new RunGraphDto();
        graph.setRunId(runId.toString());
        // The server's clock travels with the frame: every duration the operator reads is measured
        // against it, so a browser whose clock is minutes out cannot turn a healthy worker into a
        // hung one.
        graph.setAtMillis(System.currentTimeMillis());
        Run run = context.store().root().runs.get(runId);
        if (run == null) {
            graph.setRunState("UNKNOWN");
            graph.setGoal("");
            return graph;
        }
        graph.setRunState(run.state() == null ? "" : run.state().name());
        graph.setGoal(run.report() == null || run.report().summary() == null
            ? "" : run.report().summary());

        DesignDocument design = run.designId() == null
            ? null : context.store().root().designs.get(run.designId());

        TaskGraph taskGraph = run.taskGraphId() == null
            ? null : context.store().root().taskGraphs.get(run.taskGraphId());
        Set<UUID> taskIds = new HashSet<>();
        // requirementId -> ids of tasks that satisfy it (traceability for the plan view).
        Map<UUID, List<String>> coverage = new HashMap<>();
        if (taskGraph != null && taskGraph.tasks() != null) {
            Map<UUID, List<String>> dependsOn = new HashMap<>();
            if (taskGraph.dependencies() != null) {
                for (TaskEdge edge : taskGraph.dependencies()) {
                    dependsOn.computeIfAbsent(edge.to(), k -> new ArrayList<>())
                        .add(edge.from().toString());
                }
            }
            for (Task task : taskGraph.tasks()) {
                taskIds.add(task.id());
                GraphTaskDto dto = new GraphTaskDto();
                dto.setTaskId(task.id().toString());
                dto.setTitle(task.title() == null ? "" : task.title());
                dto.setState(task.state() == null ? "" : task.state().name());
                dto.setWriteSetCsv(task.writeSet() == null ? ""
                    : String.join(",", task.writeSet().stream().sorted().toList()));
                dto.setDependsOnCsv(String.join(",",
                    dependsOn.getOrDefault(task.id(), List.of())));
                dto.setCriteria(task.criteria() == null ? 0 : task.criteria().size());
                // The story this task belongs to. A task title is written for a worker; the story
                // is the work item a person recognises, and it is the only thing about a task the
                // browser could not already work out from this frame.
                com.swarmcoder.domain.Story story = task.storyId() == null
                    ? null : context.store().root().stories().get(task.storyId());
                dto.setStoryKey(story == null || story.key() == null ? "" : story.key());
                dto.setStoryTitle(story == null || story.title() == null ? "" : story.title());
                describeTests(dto, task.authoredTests());
                Set<UUID> reqIds = task.requirementIds();
                if (reqIds != null && !reqIds.isEmpty()) {
                    dto.setRequirementIdsCsv(String.join(",",
                        reqIds.stream().map(UUID::toString).sorted().toList()));
                    for (UUID rid : reqIds) {
                        coverage.computeIfAbsent(rid, k -> new ArrayList<>())
                            .add(task.id().toString());
                    }
                } else {
                    dto.setRequirementIdsCsv("");
                }
                graph.getTasks().add(dto);
            }
        }

        // Requirements (static for the run) with their covering tasks — the plan view's spine.
        if (design != null && design.requirements() != null) {
            List<Requirement> requirements = design.requirements();
            for (int i = 0; i < requirements.size(); i++) {
                Requirement r = requirements.get(i);
                GraphRequirementDto rdto = new GraphRequirementDto();
                rdto.setRequirementId(r.id().toString());
                rdto.setHandle("R" + (i + 1));
                rdto.setText(r.text() == null ? "" : r.text());
                rdto.setPriority(r.priority() == null ? "" : r.priority().name());
                rdto.setCoveredByCsv(String.join(",", coverage.getOrDefault(r.id(), List.of())));
                graph.getRequirements().add(rdto);
            }
        }

        // THREE sources, merged by taskId#workerIndex, in this order of authority:
        // the archived candidate (judged), the live session (running now), the closed session
        // (stopped, and nothing has judged it yet).
        //
        // The third one is why a worker no longer disappears. A worker leaves the hub's live map
        // the instant its session closes, and its candidate is not archived until verification has
        // run — which runs a build, so that is minutes, not milliseconds. For that whole window a
        // finished worker was in neither of the first two collections and had no chip at all. The
        // operator watched four chips become two and read the two that had gone as failures; both
        // had in fact succeeded. A status display may change what it says about something; it may
        // never stop saying anything about it.
        Map<String, GraphCandidateDto> byKey = new HashMap<>();
        Map<UUID, AgentSessionRecord> sessionByCandidate = new HashMap<>();
        Map<String, AgentSessionRecord> closedWorkers = new HashMap<>();
        for (Map.Entry<UUID, Lazy<Object>> entry : context.store().root().agentSessions().entrySet()) {
            if (!(Lazy.get(entry.getValue()) instanceof AgentSessionRecord record)) {
                continue;
            }
            if (record.candidateId() != null) {
                sessionByCandidate.put(record.candidateId(), record);
            }
            // Only worker sessions carry a taskId — the analyst, architect and judge sessions are
            // opened with none — so this is exactly the workers of this run. The newest session
            // per worker wins, for a task that has been attempted more than once.
            if (runId.equals(record.runId()) && record.taskId() != null
                    && taskIds.contains(record.taskId()) && record.closedAt() != null) {
                String key = record.taskId() + "#" + record.workerIndex();
                AgentSessionRecord held = closedWorkers.get(key);
                if (held == null || isNewer(record, held)) {
                    closedWorkers.put(key, record);
                }
            }
        }
        for (Lazy<Object> lazy : context.store().root().candidateArchives.values()) {
            if (Lazy.get(lazy) instanceof CandidateSolution candidate
                    && taskIds.contains(candidate.taskId())) {
                byKey.put(candidate.taskId() + "#" + candidate.workerIndex(),
                    of(candidate, sessionByCandidate.get(candidate.id())));
            }
        }
        for (AgentSessionRecord session : context.traceHub().activeSessions()) {
            if (runId.equals(session.runId()) && session.taskId() != null
                    && taskIds.contains(session.taskId())) {
                byKey.putIfAbsent(session.taskId() + "#" + session.workerIndex(), of(session));
            }
        }
        for (Map.Entry<String, AgentSessionRecord> entry : closedWorkers.entrySet()) {
            byKey.putIfAbsent(entry.getKey(), ofClosed(entry.getValue()));
        }
        graph.getCandidates().addAll(byKey.values().stream()
            .sorted(Comparator.comparing(GraphCandidateDto::getTaskId)
                .thenComparingInt(GraphCandidateDto::getWorkerIndex))
            .toList());
        graph.setLiveWorkers((int) graph.getCandidates().stream()
            .filter(candidate -> "RUNNING".equals(candidate.getState()))
            .count());
        return graph;
    }

    /**
     * What the test author did for this task, onto its node.
     *
     * <p>Three states, from what the record has set - see {@link AuthoredTests}. No record at all
     * says nothing: the stage has not reached the task, or the run predates the record. A record
     * with no checks offered is a task that claims none, and the browser deliberately draws nothing
     * for it rather than a "0 tests" badge - that is the enabler case, and the distinction from
     * "not authored yet" is exactly {@code checksClaimed} against {@code testsPhase}.
     */
    private static void describeTests(GraphTaskDto dto, AuthoredTests record) {
        if (record == null) {
            dto.setTestsPhase(GraphTaskDto.TESTS_UNKNOWN);
            return;
        }
        dto.setChecksClaimed(record.checksOffered());
        if (record.inProgress()) {
            dto.setTestsPhase(GraphTaskDto.TESTS_WRITING);
            return;
        }
        if (!record.written()) {
            dto.setTestsPhase(GraphTaskDto.TESTS_UNKNOWN);
            return;
        }
        dto.setTestsPhase(GraphTaskDto.TESTS_WRITTEN);
        dto.setTestsWritten(record.tests().size());
        dto.setChecksProved(record.checksProved());
        dto.setTestProblems(record.problems().size());
        dto.setTestFilesCsv(String.join(",", record.files()));
        List<GraphTestDto> tests = new ArrayList<>();
        for (AuthoredTest test : record.tests()) {
            GraphTestDto tdto = new GraphTestDto();
            tdto.setTestRef(test.testRef() == null ? "" : test.testRef());
            tdto.setPath(test.path() == null ? "" : test.path());
            tdto.setProvesRef(test.provesRef());
            tdto.setProvesText(test.provesText());
            tests.add(tdto);
        }
        dto.setTests(tests);
    }

    /** Which of two sessions for the same worker happened later. */
    private static boolean isNewer(AgentSessionRecord candidate, AgentSessionRecord held) {
        if (candidate.openedAt() == null) {
            return false;
        }
        return held.openedAt() == null || candidate.openedAt().isAfter(held.openedAt());
    }

    /**
     * A worker whose session has closed and whose candidate is not archived yet.
     *
     * <p>Its state comes from the outcome the runtime recorded: a worker that was stopped, or that
     * broke, says so at once and with its reason rather than waiting for verification to notice;
     * one that simply finished is FINISHED — "waiting to be checked" — which is the truth, and is
     * neither running nor judged.
     *
     * <p>It carries the turns and the token total it ended on, because those are the numbers that
     * were on the chip a second earlier. A chip that changes state must not read as a different
     * worker.
     */
    private static GraphCandidateDto ofClosed(AgentSessionRecord session) {
        GraphCandidateDto dto = new GraphCandidateDto();
        dto.setCandidateId(session.candidateId() == null
            ? session.id().toString() : session.candidateId().toString());
        dto.setSessionId(session.id().toString());
        dto.setTaskId(session.taskId().toString());
        dto.setWorkerIndex(session.workerIndex());
        dto.setModel(session.modelProfileId() == null ? "" : session.modelProfileId());
        dto.setTemperature(session.temperature());
        dto.setOpenedAtMillis(session.openedAt() == null ? 0 : session.openedAt().toEpochMilli());
        dto.setState(switch (session.outcome() == null ? "" : session.outcome()) {
            case "KILLED" -> CandidateState.KILLED.name();
            case "FAILED" -> CandidateState.FAILED.name();
            default -> CandidateState.FINISHED.name();
        });
        dto.setKillReason(session.killReason() == null ? "" : session.killReason().name());
        dto.setFailReason(session.killReason() == null ? "" : session.killReason().sentence());
        dto.setTurns(session.turns());
        dto.setTokens(session.totalTokens());
        dto.setJudgeScore(-1);
        dto.setClusterHash("");
        return dto;
    }

    /**
     * An archived attempt - and the numbers it ended on, taken from the session that produced it.
     *
     * <p>{@code CandidateSolution} holds no turn count and no token total; they live on the
     * session record. Without them a chip lost both the moment its attempt was archived: an
     * operator watching "21t 41k" climb saw it replaced by a temperature, next to a chip that had
     * also changed colour, and the pair reads as a different worker rather than as the same one a
     * step further on.
     *
     * @param session the worker session that produced this candidate, or null when none is known
     */
    private static GraphCandidateDto of(CandidateSolution candidate, AgentSessionRecord session) {
        GraphCandidateDto dto = new GraphCandidateDto();
        dto.setCandidateId(candidate.id().toString());
        dto.setSessionId(session == null ? "" : session.id().toString());
        if (session != null) {
            dto.setTurns(session.turns());
            dto.setTokens(session.totalTokens());
            dto.setOpenedAtMillis(
                session.openedAt() == null ? 0 : session.openedAt().toEpochMilli());
        }
        dto.setTaskId(candidate.taskId().toString());
        dto.setWorkerIndex(candidate.workerIndex());
        dto.setModel(candidate.sampling() == null || candidate.sampling().modelProfileId() == null
            ? "" : candidate.sampling().modelProfileId());
        dto.setTemperature(candidate.sampling() == null ? 0 : candidate.sampling().temperature());
        dto.setState(candidate.state() == null ? "" : candidate.state().name());
        dto.setKillReason(candidate.killReason() == null ? "" : candidate.killReason().name());
        dto.setFailReason(failWords(candidate));
        dto.setOutOfWriteSetPaths(String.join(", ", candidate.outOfWriteSetPaths()));
        dto.setDocsDeadEndEvidence(String.join("\n", candidate.docsDeadEndEvidence()));
        dto.setHelpSummary(candidate.helpSummary());
        String diff = candidate.diffUnified();
        dto.setDiffEmpty(diff == null || diff.isBlank());
        dto.setChangedFiles(changedFiles(diff));
        dto.setJudgeScore(candidate.judge() == null ? -1 : candidate.judge().score());
        dto.setClusterHash(candidate.cluster() == null || candidate.cluster().behavioralHash() == null
            ? "" : candidate.cluster().behavioralHash());
        dto.setVerified(candidate.verification() != null);
        dto.setCompiles(candidate.verification() != null && candidate.verification().compiles());
        return dto;
    }

    /**
     * Why an archived attempt died, worked out once, on the server.
     *
     * <p>The kill reason’s own sentence when there is a kill reason; otherwise the verdict the
     * verification stage already wrote onto the report. Neither is re-worded here. That verdict can
     * run to a paragraph — the one about a task whose claimed checks nothing tested names every
     * check — and a hover card is not the place for a paragraph, so only its first line travels.
     * The whole of it is on the report the Gallery shows.
     */
    private static String failWords(CandidateSolution candidate) {
        if (candidate.killReason() != null) {
            return candidate.killReason().sentence();
        }
        if (candidate.state() != CandidateState.FAILED || candidate.verification() == null) {
            return "";
        }
        String tail = candidate.verification().logTail();
        if (tail == null) {
            return "";
        }
        int at = tail.indexOf(VERDICT_MARK);
        if (at < 0) {
            return "";
        }
        String line = tail.substring(at + VERDICT_MARK.length());
        int end = line.indexOf(NEWLINE);
        return (end < 0 ? line : line.substring(0, end)).trim();
    }

    /**
     * How many files a unified diff adds or changes: one {@code +++ b/...} line per file that
     * still exists afterwards. Deletions carry {@code +++ /dev/null} and are not counted.
     *
     * <p>{@code UnifiedDiffPaths} in sc-verify already knows which paths those are, but this
     * module does not depend on sc-verify and a count needs no path parsing. The frame only has
     * to say "wrote two files", not which two — the diff itself is on the Gallery.
     */
    static int changedFiles(String diff) {
        if (diff == null || diff.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : diff.split("\r?\n")) {
            if (line.startsWith("+++ ") && !"/dev/null".equals(line.substring(4).trim())) {
                count++;
            }
        }
        return count;
    }

    /** What {@code SwarmEngineImpl.recordVerdict} writes onto a failed candidate’s report. */
    private static final String VERDICT_MARK = "[verdict] NOT SURVIVED \u2014 ";

    private static final char NEWLINE = '\n';

    /**
     * A worker that is running right now.
     *
     * <p>It carries when it opened and the step it is on, because "a worker exists" and "a worker
     * is working" are different facts and the graph could only show the first. Both come from the
     * same place the MCP {@code swarm_status} tool reads them.
     */
    private static GraphCandidateDto of(AgentSessionRecord session) {
        GraphCandidateDto dto = new GraphCandidateDto();
        dto.setOpenedAtMillis(session.openedAt() == null ? 0 : session.openedAt().toEpochMilli());
        var step = LiveWork.lastStep(session);
        if (step != null) {
            dto.setLastStepKind(step.getKind() == null ? "" : step.getKind());
            dto.setLastStepLabel(step.getLabel() == null ? "" : step.getLabel());
            dto.setLastStepAtMillis(step.getAtMillis());
        }
        dto.setCandidateId(session.candidateId() == null
            ? session.id().toString() : session.candidateId().toString());
        dto.setSessionId(session.id().toString());
        dto.setTaskId(session.taskId().toString());
        dto.setWorkerIndex(session.workerIndex());
        dto.setModel(session.modelProfileId() == null ? "" : session.modelProfileId());
        dto.setTemperature(session.temperature());
        dto.setState("RUNNING");
        dto.setKillReason("");
        dto.setTurns(session.turns());
        dto.setTokens(session.totalTokens());
        // What the token count is measured against. Asked of the hub rather than of the session
        // record, because it is the live model profile's budget and only means anything while a
        // conversation is still growing against it.
        dto.setContextBudgetTokens(
            ConsoleContext.get().traceHub().contextBudgetTokens(session.id()));
        // And how much of that count is the fixed head of the prompt, which is where the wall-clock
        // time actually goes. From the same place and for the same reason: it is measured once when
        // the session opens and only means anything while the conversation is still being sent.
        // It rides on this frame like everything else here — a hover costs no request.
        dto.setPrefillTokens(
            ConsoleContext.get().traceHub().prefillTokens(session.id()));
        dto.setJudgeScore(-1);
        dto.setClusterHash("");
        return dto;
    }
}
