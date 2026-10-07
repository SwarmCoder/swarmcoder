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

import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.console.api.SessionSummaryDto;
import com.swarmcoder.console.api.TraceEventDto;
import com.swarmcoder.domain.AgentSessionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.serializer.reference.Lazy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.swarmcoder.console.api.InsightsDto;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.TreeMap;

@ApplicationScoped
public class ObserverServiceImpl implements ObserverService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ObserverServiceImpl.class);


    @Override
    public List<RunSummaryDto> listRuns() {
        UUID current = ConsoleContext.get().currentProjectId();
        // Runs are claimed by projectId OR by one of this project's stories naming them. The second
        // join is not belt-and-braces: a run persisted before Run carried a projectId — or by any
        // path that failed to set it — has null there, so an equals() test drops it, and the Runs
        // rail said "No runs yet" while the guidance one line above it said three were in flight.
        // Readiness has always used both joins; this is the surface that did not.
        java.util.Set<UUID> viaStories = new java.util.HashSet<>();
        try {
            for (com.swarmcoder.domain.Story story
                    : ConsoleContext.get().store().listStories(current)) {
                viaStories.addAll(story.runIds());
            }
        } catch (Exception e) {
            // A backlog that cannot be read must not empty the rail; the projectId match stands.
            log.warn("Could not read this project's stories while listing runs: {}", e.toString());
        }
        Map<UUID, Long> tokens = tokensByRun();
        return ConsoleContext.get().store().root().runs.values().stream()
            .filter(run -> current == null || current.equals(run.projectId())
                || viaStories.contains(run.id()))
            .sorted(Comparator.comparing(run -> run.startedAt() == null
                ? Instant.EPOCH : run.startedAt(), Comparator.reverseOrder()))
            .map(run -> Dtos.of(run, tokens.getOrDefault(run.id(), 0L)))
            .toList();
    }

    /**
     * What each run has spent, in one pass over the session records.
     *
     * <p>One pass rather than a lookup per run: this is called on every runs refresh, and asking
     * "which sessions belong to run X" once per run would walk every session record N times for no
     * reason. A session whose tokens were never reported contributes 0, which reads as "not known
     * yet" on the card rather than as "free".
     */
    private static Map<UUID, Long> tokensByRun() {
        Map<UUID, Long> tokens = new java.util.HashMap<>();
        try {
            for (Lazy<Object> lazy : ConsoleContext.get().store().root().agentSessions().values()) {
                if (Lazy.get(lazy) instanceof AgentSessionRecord record && record.runId() != null) {
                    tokens.merge(record.runId(), Math.max(0, record.totalTokens()), Long::sum);
                }
            }
        } catch (Exception e) {
            // A cost the card cannot show is a missing number; a runs list that cannot be read is a
            // pipeline board with nothing on it. Never trade the second for the first.
            log.warn("Could not total the token spend per run: {}", e.toString());
        }
        return tokens;
    }

    @Override
    public List<SessionSummaryDto> activeSessions() {
        Set<UUID> projectRuns = projectRunIds();
        return ConsoleContext.get().traceHub().activeSessions().stream()
            .filter(session -> inProject(session, projectRuns))
            .map(Dtos::of)
            .toList();
    }

    @Override
    public List<TraceEventDto> sessionEvents(String sessionId, long fromSeq, int max) {
        UUID id = UUID.fromString(sessionId);
        int cap = Math.max(1, Math.min(max, 500));

        AgentSessionRecord record = ConsoleContext.get().traceHub().activeSessions().stream()
            .filter(session -> session.id().equals(id))
            .findFirst()
            .orElseGet(() -> persisted(id));
        if (record == null || record.events() == null) {
            return List.of();
        }
        List<TraceEventDto> events = new ArrayList<>();
        for (TraceEvent event : record.events()) {
            if (event.seq() >= fromSeq) {
                events.add(Dtos.of(id, event));
                if (events.size() >= cap) {
                    break;
                }
            }
        }
        return events;
    }

    @Override
    public List<TraceEventDto> lastStep(String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        TraceEventDto last = LiveWork.lastStep(id);
        return last == null ? List.of() : List.of(last);
    }

    @Override
    public List<SessionSummaryDto> recentSessions(int max) {
        int cap = Math.max(1, Math.min(max, 200));
        Set<UUID> projectRuns = projectRunIds();
        return ConsoleContext.get().store().root().agentSessions().values().stream()
            .map(lazy -> (AgentSessionRecord) Lazy.get(lazy))
            .filter(session -> inProject(session, projectRuns))
            .sorted(Comparator.comparing(AgentSessionRecord::openedAt, Comparator.reverseOrder()))
            .limit(cap)
            .map(Dtos::of)
            .toList();
    }

    @Override
    public InsightsDto insights() {
        var store = ConsoleContext.get().store();
        var insights = new InsightsDto();
        Set<UUID> projectRuns = projectRunIds();               // null = all projects
        Set<UUID> projectTasks = projectTaskIds(projectRuns);  // null = all projects
        insights.setTotalRuns(projectRuns == null ? store.root().runs.size() : projectRuns.size());

        // Per-family dispatched/survived/selected + kill-reason tally over archived candidates.
        Map<String, int[]> byFamily = new TreeMap<>(); // [dispatched, survived, selected]
        Map<String, Integer> byKill = new TreeMap<>();
        StringBuilder scatter = new StringBuilder(); // "temperature\tsurvived\tfamily" per candidate
        int totalCandidates = 0;
        int selected = 0;
        for (Lazy<Object> lazy : store.root().candidateArchives.values()) {
            if (!(Lazy.get(lazy) instanceof CandidateSolution c)) {
                continue;
            }
            if (projectTasks != null && !projectTasks.contains(c.taskId())) {
                continue; // candidate belongs to another project's task graph
            }
            totalCandidates++;
            String family = c.sampling() != null && c.sampling().modelProfileId() != null
                ? c.sampling().modelProfileId() : "unknown";
            int[] stat = byFamily.computeIfAbsent(family, k -> new int[3]);
            stat[0]++;
            boolean survived = c.state() == CandidateState.SURVIVED
                || c.state() == CandidateState.SELECTED;
            if (survived) {
                stat[1]++;
            }
            if (c.state() == CandidateState.SELECTED) {
                stat[2]++;
                selected++;
            }
            if (c.killReason() != null) {
                byKill.merge(c.killReason().name(), 1, Integer::sum);
            }
            if (c.sampling() != null) {
                scatter.append(c.sampling().temperature()).append('\t')
                    .append(survived ? 1 : 0).append('\t').append(family).append('\n');
            }
        }
        // One line per archived candidate, with no upper bound on how many there are. Clamped for
        // the same reason as everything else here: over 4 MB the connection closes (WireBudget).
        insights.setScatterRows(WireBudget.clamp(scatter.toString(), "This chart's data"));
        insights.setTotalCandidates(totalCandidates);
        insights.setSelectedCandidates(selected);

        long totalTokens = 0;
        int sessionCount = 0;
        for (Lazy<Object> lazy : store.root().agentSessions().values()) {
            if (Lazy.get(lazy) instanceof AgentSessionRecord record) {
                if (projectRuns != null && (record.runId() == null || !projectRuns.contains(record.runId()))) {
                    continue; // session belongs to another project
                }
                sessionCount++;
                totalTokens += record.totalTokens();
            }
        }
        insights.setTotalSessions(sessionCount);
        insights.setTotalTokens(totalTokens);

        StringBuilder families = new StringBuilder();
        byFamily.forEach((family, stat) ->
            families.append(family).append('\t').append(stat[0]).append('\t')
                .append(stat[1]).append('\t').append(stat[2]).append('\n'));
        insights.setFamilyRows(families.toString());

        StringBuilder kills = new StringBuilder();
        byKill.forEach((reason, count) -> kills.append(reason).append('\t').append(count).append('\n'));
        insights.setKillReasonRows(kills.toString());
        return insights;
    }

    @Override
    public List<SessionSummaryDto> searchHistory(String query, int max) {
        int cap = Math.max(1, Math.min(max, 50));
        List<SessionSummaryDto> results = new ArrayList<>();
        for (String sessionId : ConsoleContext.get().searchHistory(query, cap)) {
            try {
                AgentSessionRecord record = persisted(UUID.fromString(sessionId));
                if (record != null) {
                    results.add(Dtos.of(record));
                }
            } catch (IllegalArgumentException ignored) {
                // skip malformed id
            }
        }
        return results;
    }

    @Override
    public String blobText(String ref) {
        var blobStore = ConsoleContext.get().blobStore();
        if (blobStore == null || ref == null || ref.isBlank()) {
            return "";
        }
        try {
            // Clamped: a blob is where a payload went precisely BECAUSE it was too big to inline,
            // and one over 4 MB closes the connection rather than failing the call (WireBudget).
            return WireBudget.clamp(new String(blobStore.getBlob(ref), StandardCharsets.UTF_8),
                "This payload");
        } catch (Exception e) {
            return "[blob " + ref + " unavailable: " + e.getMessage() + "]";
        }
    }

    @Override
    public String eventPayload(String sessionId, long seq) {
        UUID id = UUID.fromString(sessionId);
        AgentSessionRecord record = ConsoleContext.get().traceHub().activeSessions().stream()
            .filter(session -> session.id().equals(id))
            .findFirst()
            .orElseGet(() -> persisted(id));
        if (record == null || record.events() == null) {
            return "";
        }
        for (TraceEvent event : record.events()) {
            if (event.seq() == seq) {
                // A blob-backed payload holds the full text; otherwise the event's own payload is it.
                if (event.payloadRef() != null && !event.payloadRef().isBlank()) {
                    return blobText(event.payloadRef());
                }
                return WireBudget.clamp(event.payload(), "This payload");
            }
        }
        return "";
    }

    @Override
    public String sessionPrompt(String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "";
        }
        AgentSessionRecord record = ConsoleContext.get().traceHub().activeSessions().stream()
            .filter(session -> session.id().equals(id))
            .findFirst()
            .orElseGet(() -> persisted(id));
        if (record == null || record.events() == null) {
            return "";
        }
        for (TraceEvent event : record.events()) {
            if (event.kind() == TraceEventKind.SESSION_OPENED) {
                if (event.payloadRef() != null && !event.payloadRef().isBlank()) {
                    return blobText(event.payloadRef());
                }
                return WireBudget.clamp(event.payload(), "This prompt");
            }
        }
        return "";
    }

    private static AgentSessionRecord persisted(UUID id) {
        Lazy<Object> lazy = ConsoleContext.get().store().root().agentSessions().get(id);
        return lazy == null ? null : (AgentSessionRecord) Lazy.get(lazy);
    }

    // --- Project scoping (multi-project) --------------------------------------------------------
    // A null current project (an un-wired context, e.g. some tests) means "no scope — show all".

    /** Run ids belonging to the current project, or {@code null} when there is no scope. */
    private static Set<UUID> projectRunIds() {
        UUID current = ConsoleContext.get().currentProjectId();
        if (current == null) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        for (Run run : ConsoleContext.get().store().root().runs.values()) {
            if (current.equals(run.projectId())) {
                ids.add(run.id());
            }
        }
        return ids;
    }

    /** Task ids in the current project's task graphs, or {@code null} when there is no scope. */
    private static Set<UUID> projectTaskIds(Set<UUID> projectRunIds) {
        if (projectRunIds == null) {
            return null;
        }
        var store = ConsoleContext.get().store();
        Set<UUID> taskIds = new HashSet<>();
        for (Run run : store.root().runs.values()) {
            if (projectRunIds.contains(run.id()) && run.taskGraphId() != null) {
                TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
                if (graph != null && graph.tasks() != null) {
                    for (Task task : graph.tasks()) {
                        taskIds.add(task.id());
                    }
                }
            }
        }
        return taskIds;
    }

    /** A session is in scope when there is no scope, or its run is one of the project's runs. */
    private static boolean inProject(AgentSessionRecord session, Set<UUID> projectRunIds) {
        return projectRunIds == null
            || (session.runId() != null && projectRunIds.contains(session.runId()));
    }
}
