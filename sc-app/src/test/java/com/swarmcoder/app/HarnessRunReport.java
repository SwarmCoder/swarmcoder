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
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.KillReason;
import com.swarmcoder.domain.RepairIndex;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.runtime.ExpertAnswerLog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one harness run cost and what it delivered, as one page: tokens and time per role, per task
 * and per worker; time per stage; lines delivered and lines thrown away; how many model calls ran
 * at once and what that bought; and whether the extra candidates were worth having.
 *
 * <p>Asked for on 2026-10-02 after run 65: a run printed which links of its chain held and nothing
 * about what the run spent, so "does the swarm help, and what does a delivered line cost" could not
 * be answered from any run's output.
 *
 * <p>Pure: {@link #render} turns {@link Input} into text and touches nothing. The harness gathers
 * the input (see {@code EndToEndLoopTest.writeRunReport}). A number that was not measured is
 * written as "not recorded" — never left out and never guessed.
 */
final class HarnessRunReport {

    static final String NOT_RECORDED = "not recorded";

    private HarnessRunReport() {}

    // --- what the harness itself times ---------------------------------------------------------

    /** The run's state as the store last saw it change: the stage, and whether it was parked. */
    record Seen(long atMillis, String state, boolean parked, String parkReason) {}

    /** A stage the harness walks before the run exists (the two wizards). */
    record HarnessStage(String name, long startMillis, long endMillis) {}

    /** Records stage changes and parks as the run is persisted, and the wizard stages. */
    static final class Clock {
        final long startMillis = System.currentTimeMillis();
        private final List<Seen> seen = new ArrayList<>();
        private final List<HarnessStage> stages = new ArrayList<>();

        /** Called for every persisted run; keeps only a change of stage or of parked/not parked. */
        synchronized void seen(Run run) {
            if (run == null || run.state() == null) {
                return;
            }
            boolean parked = run.parkedAt() != null;
            Seen last = seen.isEmpty() ? null : seen.get(seen.size() - 1);
            if (last != null && last.state().equals(run.state().name()) && last.parked() == parked) {
                return;
            }
            seen.add(new Seen(System.currentTimeMillis(), run.state().name(), parked,
                parked ? run.parkReason() : null));
        }

        synchronized void stage(String name, long startMillis) {
            stages.add(new HarnessStage(name, startMillis, System.currentTimeMillis()));
        }

        synchronized List<Seen> seen() {
            return List.copyOf(seen);
        }

        synchronized List<HarnessStage> stages() {
            return List.copyOf(stages);
        }
    }

    // --- the input ------------------------------------------------------------------------------

    /** One file of a diff: lines added and removed. */
    record FileStat(String path, int added, int removed) {}

    /**
     * Everything the report is made from.
     *
     * @param waves          the plan's waves as task ids, in order; empty when there is no plan
     * @param delivered      the delivered commit against the run's base, one row per file; null
     *                       when nothing was delivered or the diff could not be read
     * @param deliveredNote  which two commits were compared, or why there is nothing to compare
     * @param restoredNote   on a resumed run, what was restored from a snapshot and therefore not
     *                       measured; null for a full walk
     * @param coordinator    what the coordinator standing in for the operator did; null when none
     * @param expertAnswers  every answer the expert desk gave in the run, with what it cost and
     *                       what became of the asker's work; null when the run kept no such record
     */
    record Input(List<RunMeter.Call> calls, List<RunMeter.Span> spans, List<Task> tasks,
                 List<List<UUID>> waves, List<CandidateSolution> candidates,
                 List<AgentSessionRecord> sessions, List<Decision> decisions,
                 List<CarriedWarning> warnings, List<Seen> stageLog, List<HarnessStage> harnessStages,
                 long startMillis, long endMillis, List<FileStat> delivered, String deliveredNote,
                 String restoredNote, String outcome, String coordinator,
                 List<ExpertAnswerLog.Entry> expertAnswers, Servers servers) {

        /** A run whose roles all ran on the one local server. */
        Input(List<RunMeter.Call> calls, List<RunMeter.Span> spans, List<Task> tasks,
              List<List<UUID>> waves, List<CandidateSolution> candidates,
              List<AgentSessionRecord> sessions, List<Decision> decisions,
              List<CarriedWarning> warnings, List<Seen> stageLog, List<HarnessStage> harnessStages,
              long startMillis, long endMillis, List<FileStat> delivered, String deliveredNote,
              String restoredNote, String outcome, String coordinator,
              List<ExpertAnswerLog.Entry> expertAnswers) {
            this(calls, spans, tasks, waves, candidates, sessions, decisions, warnings, stageLog,
                harnessStages, startMillis, endMillis, delivered, deliveredNote, restoredNote,
                outcome, coordinator, expertAnswers, Servers.ALL_LOCAL);
        }

        /** The same run with another set of calls (the local ones, for the server's own figures). */
        Input withCalls(List<RunMeter.Call> other) {
            return new Input(other, spans, tasks, waves, candidates, sessions, decisions, warnings,
                stageLog, harnessStages, startMillis, endMillis, delivered, deliveredNote,
                restoredNote, outcome, coordinator, expertAnswers, servers);
        }

        /** A run with no record of expert answers. */
        Input(List<RunMeter.Call> calls, List<RunMeter.Span> spans, List<Task> tasks,
              List<List<UUID>> waves, List<CandidateSolution> candidates,
              List<AgentSessionRecord> sessions, List<Decision> decisions,
              List<CarriedWarning> warnings, List<Seen> stageLog, List<HarnessStage> harnessStages,
              long startMillis, long endMillis, List<FileStat> delivered, String deliveredNote,
              String restoredNote, String outcome, String coordinator) {
            this(calls, spans, tasks, waves, candidates, sessions, decisions, warnings, stageLog,
                harnessStages, startMillis, endMillis, delivered, deliveredNote, restoredNote,
                outcome, coordinator, null);
        }
    }

    /**
     * Which server each role ran on, when some ran off the local one.
     *
     * @param local   what the local server is called in the report
     * @param byRole  meter role tag to the label of the other server that role was given
     * @param byModel model name to the label of the other server serving it (a call is told
     *                apart by its model when that differs from the local model)
     */
    record Servers(String local, Map<String, String> byRole, Map<String, String> byModel) {

        static final Servers ALL_LOCAL = new Servers("local", Map.of(), Map.of());

        /** The label of the server the call went to. */
        String labelOf(RunMeter.Call call) {
            String byModelLabel = byModel.get(call.model());
            if (byModelLabel != null) {
                return byModelLabel;
            }
            String byRoleLabel = byRole.get(tagOf(call.role()));
            return byRoleLabel != null ? byRoleLabel : local;
        }

        /**
         * The role tag a server was given for: an expert session opened for a planning role is
         * tagged "expert for architect" and runs on the same server as "expert" (harness run 77:
         * with the same model name on both servers, those calls were reported as local).
         */
        static String tagOf(String role) {
            return role != null && role.startsWith("expert for ") ? "expert" : role;
        }

        boolean isLocal(RunMeter.Call call) {
            return labelOf(call).equals(local);
        }

        boolean anyElsewhere() {
            return !byRole.isEmpty() || !byModel.isEmpty();
        }
    }

    private static final List<String> ROLES = List.of("architect", "analyst", "planner",
        "test author", "reviewer", "judge", "expert", "worker");

    private static final Set<String> EXPERT_TOOLS = Set.of("ask_expert", "request_skeleton");

    // --- rendering ------------------------------------------------------------------------------

    static String render(Input in) {
        StringBuilder sb = new StringBuilder();
        double wallMinutes = minutes(in.endMillis() - in.startMillis());
        sb.append("# Run report\n\n");
        sb.append("Outcome: ").append(in.outcome() == null ? NOT_RECORDED : in.outcome()).append('\n');
        sb.append("Wall clock: ").append(fmt(wallMinutes)).append(" min\n");
        if (in.restoredNote() != null) {
            sb.append("RESUMED RUN: ").append(in.restoredNote()).append('\n');
        }
        roles(sb, in);
        tasks(sb, in);
        stages(sb, in);
        output(sb, in);
        totals(sb, in);
        parks(sb, in);
        concurrency(sb, in);
        redundancy(sb, in);
        expertAnswers(sb, in);
        places(sb, in);
        return sb.toString();
    }

    // 1 ---------------------------------------------------------------------------------------

    private static void roles(StringBuilder sb, Input in) {
        sb.append("\n## 1. Model usage per role\n\n");
        Servers servers = in.servers() == null ? Servers.ALL_LOCAL : in.servers();
        boolean split = servers.anyElsewhere();
        sb.append(split
            ? "| role | server | calls | prompt tokens | prompt tokens per call | completion tokens | seconds in calls |\n"
            : "| role | calls | prompt tokens | prompt tokens per call | completion tokens | seconds in calls |\n");
        sb.append(split ? "|---|---|---|---|---|---|---|\n" : "|---|---|---|---|---|---|\n");
        Set<String> roles = new LinkedHashSet<>(ROLES);
        in.calls().forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            List<RunMeter.Call> mine = in.calls().stream().filter(c -> c.role().equals(role)).toList();
            sb.append("| ").append(role).append(" | ");
            if (split) {
                Set<String> where = new LinkedHashSet<>();
                mine.forEach(c -> where.add(servers.labelOf(c)));
                if (where.isEmpty()) {
                    where.add(servers.byRole().getOrDefault(role, servers.local()));
                }
                sb.append(String.join(" and ", where)).append(" | ");
            }
            sb.append(mine.size()).append(" | ")
                .append(tokens(mine, true)).append(" | ").append(promptPerCall(mine))
                .append(" | ").append(tokens(mine, false)).append(" | ")
                .append(seconds(mine)).append(" |\n");
        }
        sb.append("| **all** | ");
        if (split) {
            sb.append(" | ");
        }
        sb.append(in.calls().size()).append(" | ")
            .append(tokens(in.calls(), true)).append(" | ").append(promptPerCall(in.calls()))
            .append(" | ").append(tokens(in.calls(), false))
            .append(" | ").append(seconds(in.calls())).append(" |\n");
        if (split) {
            sb.append("\nThe local server is `").append(servers.local()).append("`. Every other "
                + "server is one the operator's config gave a role; section 7 counts only the "
                + "local server's calls.\n");
        }
        sb.append("\nPrompt tokens per call is what one call was sent on average, over the calls "
            + "that reported it: the whole conversation is sent again on every call, so this is "
            + "the figure that shows a role whose conversation is not being kept small (a worker "
            + "is sent about 10,000).\n");
        sb.append("\nToken counts are the model server's own `usage` per call. Test author, "
            + "architect and reviewer calls are tagged by role only, not by task.\n");
        promptCache(sb, in, roles);
        sb.append(lookupsByKind(com.swarmcoder.inference.LookupMeter.counts()));
    }

    /**
     * What each role looked up, by kind: how often, and how many characters came back into its
     * conversation. A role whose input is mostly text search and whole files is not learning
     * the project from its syntax tree (CLAUDE.md section 1).
     */
    static String lookupsByKind(List<com.swarmcoder.inference.LookupMeter.Count> counts) {
        StringBuilder sb = new StringBuilder("\n### What each role looked up, by kind\n\n");
        if (counts.isEmpty()) {
            return sb.append(NOT_RECORDED).append(": no lookup was recorded in this run.\n")
                .toString();
        }
        com.swarmcoder.inference.LookupMeter.Kind[] kinds =
            com.swarmcoder.inference.LookupMeter.Kind.values();
        sb.append("| role |");
        for (com.swarmcoder.inference.LookupMeter.Kind kind : kinds) {
            sb.append(' ').append(kind.label()).append(" |");
        }
        sb.append(" share of characters from the tree, the language server and document "
            + "sections |\n|---|");
        sb.append("---|".repeat(kinds.length + 1)).append("\n");
        Set<String> roles = new LinkedHashSet<>();
        counts.forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            sb.append("| ").append(role).append(" |");
            long all = 0;
            long tree = 0;
            for (com.swarmcoder.inference.LookupMeter.Kind kind : kinds) {
                int calls = 0;
                long chars = 0;
                for (com.swarmcoder.inference.LookupMeter.Count count : counts) {
                    if (count.role().equals(role) && count.kind() == kind) {
                        calls += count.calls();
                        chars += count.chars();
                    }
                }
                all += chars;
                tree += kind.structured() ? chars : 0;
                sb.append(' ').append(calls == 0 ? "0" : calls + " call(s), " + chars + " chars")
                    .append(" |");
            }
            sb.append(' ').append(all == 0 ? "0%" : (tree * 100 / all) + "%").append(" |\n");
        }
        sb.append("\nCharacters are what came back into the role's conversation, where every "
            + "later call sends them again. Workers are one row together. An acceptance-test read is a worker's acceptance_test call. A journey check is the test author's check_journey call. A shell read is a "
            + "worker's exec command that begins with find, grep, cat, ls or their like.\n");
        return sb.toString();
    }

    /**
     * What the prompt tokens cost: how many the server took from its prompt prefix cache (billed
     * at a fraction of the price) and how many it read in full, per role - and, measured on our
     * side, how much of each request repeated the request before it, which is what a prefix
     * cache can reuse at all.
     */
    private static void promptCache(StringBuilder sb, Input in, Set<String> roles) {
        sb.append("\n### What the prompt tokens cost\n\n");
        boolean any = in.calls().stream().anyMatch(c -> c.cacheReported() || c.requestChars() >= 0);
        if (!any) {
            sb.append(NOT_RECORDED).append(": no call reported what its server took from its "
                + "prompt cache, and no request was measured.\n");
            return;
        }
        sb.append("| role | calls | prompt tokens | taken from the server's prompt cache | read "
            + "in full | calls whose server said nothing of its cache | share of each request "
            + "that repeated the request before it |\n|---|---|---|---|---|---|---|\n");
        for (String role : roles) {
            List<RunMeter.Call> mine = in.calls().stream().filter(c -> c.role().equals(role)).toList();
            if (mine.isEmpty()) {
                continue;
            }
            long cached = 0;
            long full = 0;
            int silent = 0;
            long chars = 0;
            long same = 0;
            for (RunMeter.Call call : mine) {
                if (call.cacheReported()) {
                    cached += call.cachedPromptTokens();
                    full += Math.max(0, call.promptTokens() - call.cachedPromptTokens());
                } else {
                    silent++;
                }
                if (call.requestChars() > 0 && call.samePrefixChars() >= 0) {
                    chars += call.requestChars();
                    same += call.samePrefixChars();
                }
            }
            boolean reported = silent < mine.size();
            sb.append("| ").append(role).append(" | ").append(mine.size()).append(" | ")
                .append(tokens(mine, true)).append(" | ")
                .append(reported ? String.valueOf(cached) : NOT_RECORDED).append(" | ")
                .append(reported ? String.valueOf(full) : NOT_RECORDED).append(" | ")
                .append(silent).append(" | ")
                .append(chars == 0 ? NOT_RECORDED : (same * 100 / chars) + "%").append(" |\n");
        }
        sb.append("\nTaken from the cache is the server's own count (`prompt_cache_hit_tokens`, "
            + "or `prompt_tokens_details.cached_tokens`); a server bills those at a fraction of "
            + "the price of the ones it read in full. The last column is measured here, not by "
            + "the server: of all the characters a role's sessions sent after their first call, "
            + "how many were the unchanged head of the session's previous request. A low figure "
            + "means the conversation was rewritten between calls (old results shortened, "
            + "history compacted), which makes a cache start again from the rewrite.\n");
    }

    /** The average prompt size of the calls that reported one; "0" for no calls. */
    private static String promptPerCall(List<RunMeter.Call> calls) {
        long sum = 0;
        int counted = 0;
        for (RunMeter.Call call : calls) {
            if (call.promptTokens() >= 0) {
                sum += call.promptTokens();
                counted++;
            }
        }
        return calls.isEmpty() ? "0" : counted == 0 ? NOT_RECORDED : String.valueOf(sum / counted);
    }

    /** The sum the server reported, saying so when some or all calls reported nothing. */
    private static String tokens(List<RunMeter.Call> calls, boolean prompt) {
        if (calls.isEmpty()) {
            return "0";
        }
        long sum = 0;
        int without = 0;
        for (RunMeter.Call call : calls) {
            int value = prompt ? call.promptTokens() : call.completionTokens();
            if (value < 0) {
                without++;
            } else {
                sum += value;
            }
        }
        if (without == calls.size()) {
            return NOT_RECORDED;
        }
        return sum + (without == 0 ? "" : " (+" + without + " call(s) " + NOT_RECORDED + ")");
    }

    private static String seconds(List<RunMeter.Call> calls) {
        if (calls.isEmpty()) {
            return "0";
        }
        long millis = 0;
        int unfinished = 0;
        for (RunMeter.Call call : calls) {
            if (call.finished()) {
                millis += call.millis();
            } else {
                unfinished++;
            }
        }
        if (unfinished == calls.size()) {
            return NOT_RECORDED;
        }
        return fmt(millis / 1000.0) + (unfinished == 0 ? ""
            : " (+" + unfinished + " call(s) with no recorded end)");
    }

    // 2 ---------------------------------------------------------------------------------------

    private static void tasks(StringBuilder sb, Input in) {
        sb.append("\n## 2. Per task\n");
        if (in.tasks().isEmpty()) {
            sb.append("\nNo plan was stored, so there are no tasks to report.\n");
            return;
        }
        for (Task task : in.tasks()) {
            List<CandidateSolution> mine = candidatesOf(in, task);
            long survived = mine.stream().filter(HarnessRunReport::passed).count();
            long selected = mine.stream().filter(c -> c.state() == CandidateState.SELECTED).count();
            long repairRounds = in.spans().stream()
                .filter(s -> s.name().endsWith("repair round|" + task.id())).count();
            long repairWorkers = mine.stream().filter(c -> RepairIndex.isRepair(c.workerIndex()))
                .count();
            sb.append("\n### ").append(task.title()).append("\n\n");
            sb.append("Candidates dispatched ").append(mine.size()).append(", survived verification ")
                .append(survived).append(", selected ").append(selected).append("; repair rounds ")
                .append(repairRounds).append(" (").append(repairWorkers).append(" repair worker(s))")
                .append("; state ").append(task.state()).append('\n');
            if (task.authoredTests() != null && task.authoredTests().reviewNote() != null
                    && !task.authoredTests().reviewNote().isBlank()) {
                sb.append("Acceptance test sent back to its author before the repair round "
                    + "(every first candidate failed it the same way): ")
                    .append(task.authoredTests().reviewNote().strip()).append('\n');
            }
            sb.append(openingOf(in.spans(), task.id()));
            List<AgentSessionRecord> sessions = in.sessions().stream()
                .filter(s -> task.id().equals(s.taskId()))
                .sorted(Comparator.comparing(AgentSessionRecord::openedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            if (sessions.isEmpty()) {
                sb.append("Workers: ").append(NOT_RECORDED).append(" (no worker session of this task "
                    + "is in the store)\n");
            } else {
                sb.append("\n| worker | turns | prompt tokens | completion tokens | tool calls | "
                    + "minutes | outcome | expert questions | minutes waiting for the expert |\n");
                sb.append("|---|---|---|---|---|---|---|---|---|\n");
                for (AgentSessionRecord session : sessions) {
                    List<RunMeter.Call> calls = callsOf(in, session);
                    CandidateSolution candidate = mine.stream()
                        .filter(c -> c.id().equals(session.candidateId())).findFirst().orElse(null);
                    long[] expert = expertWaits(session);
                    sb.append("| ").append(session.workerIndex()).append(" | ")
                        .append(session.turns()).append(" | ")
                        .append(calls.isEmpty() ? NOT_RECORDED : tokens(calls, true)).append(" | ")
                        .append(calls.isEmpty() ? NOT_RECORDED : tokens(calls, false)).append(" | ")
                        .append(session.events() == null ? NOT_RECORDED : String.valueOf(
                            session.events().stream()
                                .filter(e -> e.kind() == TraceEventKind.TOOL_CALL).count()))
                        .append(" | ").append(sessionMinutes(session)).append(" | ")
                        .append(outcomeOf(session, candidate)).append(" | ")
                        .append(expert == null ? NOT_RECORDED : String.valueOf(expert[0]))
                        .append(" | ")
                        .append(expert == null ? NOT_RECORDED : fmt(minutes(expert[1])))
                        .append(" |\n");
                }
            }
            List<RunMeter.Call> judge = in.calls().stream().filter(c -> c.role().equals("judge")
                && task.id().toString().equals(c.task())).toList();
            sb.append("Judge: ").append(judge.size()).append(" call(s), prompt ")
                .append(tokens(judge, true)).append(", completion ").append(tokens(judge, false));
            List<RunMeter.Call> ended = judge.stream().filter(RunMeter.Call::finished).toList();
            if (ended.size() > 1) {
                // Whether the calls ran side by side or one after another, said in figures
                // (live run 74: 5.6 minutes that were one long call, not two in a row).
                long first = ended.stream().mapToLong(RunMeter.Call::startMillis).min().orElse(0);
                long last = ended.stream().mapToLong(RunMeter.Call::endMillis).max().orElse(0);
                long longest = ended.stream().mapToLong(RunMeter.Call::millis).max().orElse(0);
                long sum = ended.stream().mapToLong(RunMeter.Call::millis).sum();
                sb.append("; from the first call's start to the last one's end ")
                    .append(fmt((last - first) / 1000.0)).append(" s, longest call ")
                    .append(fmt(longest / 1000.0)).append(" s, all calls together ")
                    .append(fmt(sum / 1000.0)).append(" s (")
                    .append(last - first < sum * 0.9 ? "they ran side by side"
                        : "they ran one after another, or waited for a place").append(')');
            }
            sb.append('\n');
        }
    }

    /**
     * What the workers of one task were opened with (section 65): how many of the project's
     * rules they were sent, and the size of the opening that is sent again on every call. Read
     * from the spans the engine and the dispatcher record at each dispatch.
     */
    static String openingOf(List<RunMeter.Span> spans, UUID taskId) {
        String rules = null;
        List<String> openings = new ArrayList<>();
        for (RunMeter.Span span : spans) {
            String name = span.name();
            if (name.startsWith(com.swarmcoder.swarm.SwarmEngineImpl.WORKER_RULES_SPAN + taskId + "|")) {
                String[] parts = name.split("\\|");
                if (parts.length >= 4 && rules == null) {
                    rules = parts[2] + " of the " + parts[3] + " in force in the project";
                }
            } else if (name.startsWith(
                    com.swarmcoder.swarm.SwarmDispatcher.WORKER_OPENING_SPAN + taskId + "|")) {
                String[] parts = name.split("\\|");
                if (parts.length >= 5) {
                    openings.add(parts[2] + " dispatch about " + parts[3] + " tokens ("
                        + parts[4] + ")");
                }
            }
        }
        StringBuilder sb = new StringBuilder("Rules sent to the workers: ")
            .append(rules == null ? NOT_RECORDED : rules).append('\n');
        sb.append("Worker opening, sent again on every call of every worker: ")
            .append(openings.isEmpty() ? NOT_RECORDED : String.join("; ", openings)
                + ". Estimated at 4 characters a token, per part of the opening; the tool "
                + "definitions are sent beside it and are not in this figure.")
            .append('\n');
        return sb.toString();
    }

    private static List<CandidateSolution> candidatesOf(Input in, Task task) {
        return in.candidates().stream().filter(c -> task.id().equals(c.taskId())).toList();
    }

    /** Passed verification: it survived, or went on to be selected. */
    private static boolean passed(CandidateSolution candidate) {
        return candidate.state() == CandidateState.SURVIVED
            || candidate.state() == CandidateState.SELECTED;
    }

    /** The model calls a worker session made: same task and worker, sent while it was open. */
    private static List<RunMeter.Call> callsOf(Input in, AgentSessionRecord session) {
        String task = session.taskId() == null ? null : session.taskId().toString();
        String worker = String.valueOf(session.workerIndex());
        long from = session.openedAt() == null ? Long.MIN_VALUE : session.openedAt().toEpochMilli() - 1000;
        long to = session.closedAt() == null ? Long.MAX_VALUE : session.closedAt().toEpochMilli() + 1000;
        return in.calls().stream().filter(c -> c.role().equals("worker")
            && worker.equals(c.worker()) && task != null && task.equals(c.task())
            && c.startMillis() >= from && c.startMillis() <= to).toList();
    }

    private static String sessionMinutes(AgentSessionRecord session) {
        if (session.openedAt() == null || session.closedAt() == null) {
            return NOT_RECORDED;
        }
        return fmt(minutes(session.closedAt().toEpochMilli() - session.openedAt().toEpochMilli()));
    }

    private static String outcomeOf(AgentSessionRecord session, CandidateSolution candidate) {
        String outcome = session.outcome() == null ? "still running" : session.outcome();
        if (session.killReason() != null) {
            outcome += "/" + session.killReason();
        }
        return outcome + (candidate == null ? "" : ", candidate " + candidate.state());
    }

    /** {questions asked, millis waited}; null when the session's events were not kept. */
    private static long[] expertWaits(AgentSessionRecord session) {
        List<TraceEvent> events = session.events();
        if (events == null) {
            return null;
        }
        long asked = 0;
        long waited = 0;
        for (int i = 0; i < events.size(); i++) {
            TraceEvent event = events.get(i);
            if (event.kind() != TraceEventKind.TOOL_CALL || !EXPERT_TOOLS.contains(event.label())) {
                continue;
            }
            asked++;
            for (int j = i + 1; j < events.size(); j++) {
                TraceEvent answer = events.get(j);
                if (answer.kind() == TraceEventKind.TOOL_RESULT
                        && event.label().equals(answer.label())) {
                    if (event.at() != null && answer.at() != null) {
                        waited += answer.at().toEpochMilli() - event.at().toEpochMilli();
                    }
                    break;
                }
            }
        }
        return new long[] {asked, waited};
    }

    // 3 ---------------------------------------------------------------------------------------

    /** A named window of the run, for the stage table and for concurrency per stage. */
    private record Window(String name, long from, long to, long parkedMillis) {}

    private static List<Window> windows(Input in) {
        List<Window> out = new ArrayList<>();
        for (HarnessStage stage : in.harnessStages()) {
            out.add(new Window(stage.name(), stage.startMillis(), stage.endMillis(), 0));
        }
        // One window per run state, in the order first entered; a state entered twice (a resume)
        // is one row whose time is the sum, shown from its first entry to its last exit.
        Map<String, long[]> byState = new LinkedHashMap<>();
        List<Seen> log = in.stageLog();
        for (int i = 0; i < log.size(); i++) {
            Seen seen = log.get(i);
            long until = i + 1 < log.size() ? log.get(i + 1).atMillis() : in.endMillis();
            long[] row = byState.computeIfAbsent(seen.state(),
                k -> new long[] {seen.atMillis(), until, 0, 0});
            row[1] = Math.max(row[1], until);
            row[2] += until - seen.atMillis();
            if (seen.parked()) {
                row[3] += until - seen.atMillis();
            }
        }
        for (Map.Entry<String, long[]> entry : byState.entrySet()) {
            if (isTerminal(entry.getKey())) {
                continue;
            }
            long[] row = entry.getValue();
            out.add(new Window("run stage " + entry.getKey(), row[0], row[0] + row[2], row[3]));
        }
        for (RunMeter.Span span : in.spans()) {
            if (!span.name().contains("|")) { // "repair round|<task>" is counted per task instead
                out.add(new Window(span.name(), span.startMillis(), span.endMillis(), 0));
            }
        }
        return out;
    }

    private static boolean isTerminal(String state) {
        return state.equals(RunState.DELIVERED.name()) || state.equals(RunState.ABORTED.name())
            || state.equals(RunState.ABANDONED.name());
    }

    private static void stages(StringBuilder sb, Input in) {
        sb.append("\n## 3. Wall clock per stage\n\n");
        List<Window> windows = windows(in);
        if (windows.isEmpty()) {
            sb.append(NOT_RECORDED).append(": no stage was observed.\n");
        } else {
            sb.append("| stage | minutes | of which parked |\n|---|---|---|\n");
            for (Window window : windows) {
                sb.append("| ").append(window.name()).append(" | ")
                    .append(fmt(minutes(window.to() - window.from()))).append(" | ")
                    .append(fmt(minutes(window.parkedMillis()))).append(" |\n");
            }
            sb.append("\nWaves and the integrations between them happen inside run stage "
                + "EXECUTING; the last wave has no integration row because the run's final "
                + "integration is its own stage.\n");
        }
        if (in.restoredNote() != null) {
            sb.append("Not measured (restored from a snapshot): ").append(in.restoredNote())
                .append('\n');
        }
    }

    // 4 ---------------------------------------------------------------------------------------

    private static String kindOf(String path) {
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.equals("pom.xml") || name.startsWith("build.gradle")
                || name.startsWith("settings.gradle") || name.equals("build.xml")) {
            return "build files";
        }
        return p.contains("src/test/") || p.contains("/test/") || p.startsWith("test/")
            ? "test code" : "main code";
    }

    /** Files and lines added and removed by a unified diff, one row per file. */
    static List<FileStat> statsOf(String unifiedDiff) {
        List<FileStat> out = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return out;
        }
        String path = null;
        int added = 0;
        int removed = 0;
        for (String line : unifiedDiff.split("\r?\n")) {
            if (line.startsWith("diff --git ")) {
                if (path != null) {
                    out.add(new FileStat(path, added, removed));
                }
                int b = line.lastIndexOf(" b/");
                path = b < 0 ? line.substring("diff --git ".length()) : line.substring(b + 3);
                added = 0;
                removed = 0;
            } else if (line.startsWith("+++") || line.startsWith("---")) {
                if (path == null && line.startsWith("+++ ") && !line.endsWith("/dev/null")) {
                    path = line.substring(4).replaceFirst("^b/", "");
                }
            } else if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        if (path != null) {
            out.add(new FileStat(path, added, removed));
        }
        return out;
    }

    /** {@code git diff --numstat} output as rows; a binary file counts as zero lines. */
    static List<FileStat> statsOfNumstat(String numstat) {
        List<FileStat> out = new ArrayList<>();
        for (String line : numstat == null ? new String[0] : numstat.split("\r?\n")) {
            String[] parts = line.strip().split("\t");
            if (parts.length < 3) {
                continue;
            }
            out.add(new FileStat(parts[2], number(parts[0]), number(parts[1])));
        }
        return out;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String split(List<FileStat> stats) {
        StringBuilder sb = new StringBuilder();
        for (String kind : List.of("main code", "test code", "build files")) {
            List<FileStat> mine = stats.stream().filter(s -> kindOf(s.path()).equals(kind)).toList();
            sb.append(sb.length() == 0 ? "" : "; ").append(kind).append(": ").append(mine.size())
                .append(" file(s) +").append(mine.stream().mapToInt(FileStat::added).sum())
                .append(" -").append(mine.stream().mapToInt(FileStat::removed).sum());
        }
        return sb.toString();
    }

    private static int addedLines(List<FileStat> stats) {
        return stats.stream().mapToInt(FileStat::added).sum();
    }

    private static void output(StringBuilder sb, Input in) {
        sb.append("\n## 4. Output\n\n");
        sb.append("Delivered: ");
        if (in.delivered() == null) {
            sb.append(NOT_RECORDED);
        } else {
            sb.append(in.delivered().size()).append(" file(s), +").append(addedLines(in.delivered()))
                .append(" -").append(in.delivered().stream().mapToInt(FileStat::removed).sum())
                .append(" line(s) — ").append(split(in.delivered()));
        }
        sb.append(in.deliveredNote() == null ? "" : " (" + in.deliveredNote() + ")").append("\n\n");
        sb.append("| task | winner's diff | lines written by discarded candidates |\n|---|---|---|\n");
        int discardedTotal = 0;
        for (Task task : in.tasks()) {
            List<CandidateSolution> mine = candidatesOf(in, task);
            CandidateSolution winner = mine.stream()
                .filter(c -> c.state() == CandidateState.SELECTED).findFirst().orElse(null);
            int discarded = mine.stream().filter(c -> c.state() != CandidateState.SELECTED)
                .mapToInt(c -> addedLines(statsOf(c.diffUnified()))).sum();
            discardedTotal += discarded;
            sb.append("| ").append(task.title()).append(" | ");
            if (winner == null) {
                sb.append("no winner");
            } else {
                List<FileStat> stats = statsOf(winner.diffUnified());
                sb.append(stats.size()).append(" file(s) +").append(addedLines(stats)).append(" -")
                    .append(stats.stream().mapToInt(FileStat::removed).sum());
            }
            sb.append(" | ").append(discarded).append(" |\n");
        }
        sb.append("\nWasted work: ").append(discardedTotal)
            .append(" line(s) were written by candidates that were not selected.\n");
    }

    // 5 ---------------------------------------------------------------------------------------

    private static long sumTokens(List<RunMeter.Call> calls, boolean prompt, boolean completion) {
        long sum = 0;
        for (RunMeter.Call call : calls) {
            if (prompt && call.promptTokens() > 0) {
                sum += call.promptTokens();
            }
            if (completion && call.completionTokens() > 0) {
                sum += call.completionTokens();
            }
        }
        return sum;
    }

    private static boolean anyUsage(List<RunMeter.Call> calls) {
        return calls.stream().anyMatch(RunMeter.Call::usageRecorded);
    }

    /** Worker calls and sessions of candidates that were not selected. */
    private static List<AgentSessionRecord> discardedSessions(Input in) {
        Set<UUID> selected = new LinkedHashSet<>();
        in.candidates().stream().filter(c -> c.state() == CandidateState.SELECTED)
            .forEach(c -> selected.add(c.id()));
        return in.sessions().stream()
            .filter(s -> s.taskId() != null && !selected.contains(s.candidateId())).toList();
    }

    private static long sessionMillis(List<AgentSessionRecord> sessions) {
        long millis = 0;
        for (AgentSessionRecord session : sessions) {
            if (session.openedAt() != null && session.closedAt() != null) {
                millis += session.closedAt().toEpochMilli() - session.openedAt().toEpochMilli();
            }
        }
        return millis;
    }

    private static void totals(StringBuilder sb, Input in) {
        sb.append("\n## 5. Totals and ratios\n\n");
        boolean usage = anyUsage(in.calls());
        long total = sumTokens(in.calls(), true, true);
        long completion = sumTokens(in.calls(), false, true);
        int deliveredLines = in.delivered() == null ? -1 : addedLines(in.delivered());
        double hours = (in.endMillis() - in.startMillis()) / 3_600_000.0;
        sb.append("- Total tokens: ").append(usage ? total + " (prompt "
            + sumTokens(in.calls(), true, false) + ", completion " + completion + ")"
            : NOT_RECORDED).append('\n');
        sb.append("- Completion tokens per delivered line: ")
            .append(usage && deliveredLines > 0 ? fmt((double) completion / deliveredLines)
                : NOT_RECORDED).append('\n');
        sb.append("- Delivered lines per hour: ")
            .append(deliveredLines >= 0 && hours > 0 ? fmt(deliveredLines / hours) : NOT_RECORDED)
            .append('\n');
        List<AgentSessionRecord> discarded = discardedSessions(in);
        List<RunMeter.Call> discardedCalls = new ArrayList<>();
        discarded.forEach(s -> discardedCalls.addAll(callsOf(in, s)));
        long discardedTokens = sumTokens(discardedCalls, true, true);
        sb.append("- Share of tokens spent on discarded candidates: ")
            .append(usage && total > 0 ? percent(discardedTokens, total) + " (prompt "
                + sumTokens(discardedCalls, true, false) + " of "
                + sumTokens(in.calls(), true, false) + ", completion "
                + sumTokens(discardedCalls, false, true) + " of " + completion + ")"
                : NOT_RECORDED).append('\n');
        long allWorker = sessionMillis(in.sessions().stream().filter(s -> s.taskId() != null).toList());
        long lost = sessionMillis(discarded);
        sb.append("- Share of worker time spent on discarded candidates: ")
            .append(allWorker > 0 ? percent(lost, allWorker) + " (" + fmt(minutes(lost)) + " of "
                + fmt(minutes(allWorker)) + " worker-minutes)" : NOT_RECORDED).append('\n');
    }

    // 6 ---------------------------------------------------------------------------------------

    private static void parks(StringBuilder sb, Input in) {
        sb.append("\n## 6. Parks, operator questions and carried warnings\n\n");
        List<Seen> log = in.stageLog();
        int parks = 0;
        for (int i = 0; i < log.size(); i++) {
            Seen seen = log.get(i);
            if (!seen.parked()) {
                continue;
            }
            parks++;
            long until = i + 1 < log.size() ? log.get(i + 1).atMillis() : in.endMillis();
            sb.append("- Parked in ").append(seen.state()).append(" at ").append(clock(seen.atMillis()))
                .append(" for ").append(fmt(minutes(until - seen.atMillis()))).append(" min")
                .append(i + 1 < log.size() ? "" : " (still parked when the report was written)")
                .append(": ").append(firstLine(seen.parkReason())).append('\n');
        }
        if (parks == 0) {
            sb.append("- Parks: none observed\n");
        }
        if (in.decisions().isEmpty()) {
            sb.append("- Operator questions: none\n");
        }
        for (Decision decision : in.decisions()) {
            sb.append("- Question (").append(decision.kind()).append(", ").append(decision.state())
                .append(decision.createdAt() == null ? "" : ", raised "
                    + clock(decision.createdAt().toEpochMilli())).append("): ")
                .append(firstLine(decision.briefMarkdown()));
            if (decision.humanResponse() != null && !decision.humanResponse().isBlank()) {
                sb.append(" — answered: ").append(firstLine(decision.humanResponse()));
            }
            sb.append('\n');
        }
        sb.append("- Carried warnings: ").append(in.warnings().isEmpty() ? "none"
            : String.valueOf(in.warnings().size())).append('\n');
        for (CarriedWarning warning : in.warnings()) {
            sb.append("  - ").append(cut(warning.oneLine(), 400)).append('\n');
        }
        if (in.coordinator() != null && !in.coordinator().isBlank()) {
            sb.append("- Coordinator standing in for the operator: ")
                .append(in.coordinator().strip().replace("\n", "\n  ")).append('\n');
        }
    }

    // 7 ---------------------------------------------------------------------------------------

    /**
     * How many calls were in flight over a window.
     *
     * @param share    share of the window with 0, 1, 2, 3 and 4-or-more calls in flight
     * @param tokenSec completion tokens per second while exactly 1, 2, 3 and 4-or-more calls were
     *                 in flight (index 0 unused); -1 when no time was spent at that level
     */
    record Concurrency(double average, int peak, double[] share, double[] tokenSec,
                       double overallTokenSec, boolean tokensKnown, int failedCalls,
                       double failedSlotMinutes, int ratedCalls, int ratedFromFirstToken) {}

    /** A call that gave no answer: it failed outright, or it ended with no token count. */
    static boolean gaveNoAnswer(RunMeter.Call call, boolean serverCountsTokens) {
        return call.failed()
            || (serverCountsTokens && call.finished() && !call.usageRecorded());
    }

    /** Whether any call came back with the server's token counts - a server that never sends
     *  them says nothing about a call by leaving them out. */
    static boolean serverCountsTokens(List<RunMeter.Call> calls) {
        return calls.stream().anyMatch(RunMeter.Call::usageRecorded);
    }

    /**
     * How many calls were in flight, and how fast tokens were written.
     *
     * <p><b>The rate method</b> (live run 74, 2026-10-03). Every finished call counts toward how
     * many were in flight - a call that timed out held a place for all of its fifteen minutes.
     * But a call that gave no answer has no token count, so every stretch of time in which one
     * was in flight is left out of the tokens-per-second figures altogether: what the server
     * wrote in that stretch is not known, and counting the time with none of the tokens made
     * the rates look lower than they were. A call's tokens are spread from its first token to
     * its end where the first token's time was recorded, and from its start otherwise - then
     * the time the server spent reading the prompt is inside the rate.
     */
    static Concurrency concurrencyOver(List<RunMeter.Call> calls, long from, long to) {
        record Edge(long at, int delta, double rate, int unknown) {}
        List<Edge> edges = new ArrayList<>();
        boolean tokensKnown = false;
        int failedCalls = 0;
        double failedMillis = 0;
        int rated = 0;
        int fromFirstToken = 0;
        boolean counts = serverCountsTokens(calls);
        for (RunMeter.Call call : calls) {
            if (!call.finished() || call.endMillis() <= from || call.startMillis() >= to) {
                continue;
            }
            long start = Math.max(from, call.startMillis());
            long end = Math.min(to, call.endMillis());
            if (gaveNoAnswer(call, counts)) {
                failedCalls++;
                failedMillis += end - start;
                edges.add(new Edge(start, 1, 0, 1));
                edges.add(new Edge(end, -1, 0, -1));
                continue;
            }
            tokensKnown |= call.completionTokens() >= 0;
            rated++;
            edges.add(new Edge(start, 1, 0, 0));
            edges.add(new Edge(end, -1, 0, 0));
            // A call's completion tokens are spread evenly over the time it was writing them.
            long writingFrom = call.startMillis();
            if (call.firstTokenKnown() && call.firstTokenMillis() < call.endMillis()) {
                writingFrom = call.firstTokenMillis();
                fromFirstToken++;
            }
            long writing = call.endMillis() - writingFrom;
            double rate = call.completionTokens() > 0 && writing > 0
                ? call.completionTokens() / (double) writing : 0;
            long rateStart = Math.max(from, writingFrom);
            if (rate > 0 && rateStart < end) {
                edges.add(new Edge(rateStart, 0, rate, 0));
                edges.add(new Edge(end, 0, -rate, 0));
            }
        }
        edges.sort(Comparator.comparingLong(Edge::at).thenComparingInt(Edge::delta));
        double[] millisAt = new double[5];
        double[] ratedMillisAt = new double[5];
        double[] tokensAt = new double[5];
        int active = 0;
        int unknown = 0;
        int peak = 0;
        double rate = 0;
        double callMillis = 0;
        long cursor = from;
        for (Edge edge : edges) {
            long span = edge.at() - cursor;
            if (span > 0) {
                int level = Math.min(active, 4);
                millisAt[level] += span;
                if (unknown == 0) {
                    ratedMillisAt[level] += span;
                    tokensAt[level] += rate * span;
                }
                callMillis += (double) active * span;
                cursor = edge.at();
            }
            active += edge.delta();
            unknown += edge.unknown();
            rate += edge.rate();
            peak = Math.max(peak, active);
        }
        if (to > cursor) {
            millisAt[Math.min(active, 4)] += to - cursor;
        }
        double window = Math.max(1, to - from);
        double[] share = new double[5];
        double[] tokenSec = new double[5];
        double busy = 0;
        double tokens = 0;
        for (int level = 0; level < 5; level++) {
            share[level] = millisAt[level] / window;
            tokenSec[level] = level == 0 || ratedMillisAt[level] <= 0 ? -1
                : tokensAt[level] / (ratedMillisAt[level] / 1000.0);
            if (level > 0) {
                busy += ratedMillisAt[level];
                tokens += tokensAt[level];
            }
        }
        return new Concurrency(callMillis / window, peak, share, tokenSec,
            busy <= 0 ? -1 : tokens / (busy / 1000.0), tokensKnown, failedCalls,
            failedMillis / 60_000.0, rated, fromFirstToken);
    }

    /**
     * Section 7 measures the local server only: calls to any other server (a role taken from the
     * operator's config) neither count as in flight on it nor feed its tokens-per-second. Those
     * calls get their own small table underneath.
     */
    private static void concurrency(StringBuilder sb, Input in) {
        Servers servers = in.servers() == null ? Servers.ALL_LOCAL : in.servers();
        if (!servers.anyElsewhere()) {
            concurrencyOfLocal(sb, in);
            return;
        }
        List<RunMeter.Call> local = in.calls().stream().filter(servers::isLocal).toList();
        concurrencyOfLocal(sb, in.withCalls(local));
        Map<String, List<RunMeter.Call>> elsewhere = new LinkedHashMap<>();
        in.calls().stream().filter(c -> !servers.isLocal(c))
            .forEach(c -> elsewhere.computeIfAbsent(servers.labelOf(c), k -> new ArrayList<>()).add(c));
        sb.append("\n### Calls to other servers (not in the figures above)\n\n");
        sb.append("The figures above are for `").append(servers.local()).append("` alone. These "
            + "calls went elsewhere; no in-flight count or rate is given for them because the "
            + "local server's places do not apply to them.\n\n");
        sb.append("| server | calls | prompt tokens | completion tokens | seconds in calls |\n");
        sb.append("|---|---|---|---|---|\n");
        elsewhere.forEach((label, calls) -> sb.append("| ").append(label).append(" | ")
            .append(calls.size()).append(" | ").append(tokens(calls, true)).append(" | ")
            .append(tokens(calls, false)).append(" | ").append(seconds(calls)).append(" |\n"));
    }

    private static void concurrencyOfLocal(StringBuilder sb, Input in) {
        sb.append("\n## 7. Concurrency: does running calls side by side help?\n\n");
        if (in.calls().stream().noneMatch(RunMeter.Call::finished)) {
            sb.append(NOT_RECORDED).append(": no finished model call was recorded.\n");
            return;
        }
        sb.append("| window | average in flight | peak | time with 0 | 1 | 2 | 3 | 4+ | "
            + "completion tokens/s overall | at 1 | at 2 | at 3 | at 4+ |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        Concurrency whole = concurrencyOver(in.calls(), in.startMillis(), in.endMillis());
        concurrencyRow(sb, "whole run", whole);
        for (Window window : windows(in)) {
            concurrencyRow(sb, window.name(), concurrencyOver(in.calls(), window.from(), window.to()));
        }
        long unfinished = in.calls().stream().filter(c -> !c.finished()).count();
        sb.append("\n\"At 1\" is the single-stream rate; a higher rate at 2, 3 or 4+ is the gain "
            + "from running calls side by side. How many calls were in flight counts every call; "
            + "the tokens-per-second figures leave out every stretch of time in which a call "
            + "that gave no answer was in flight, because what the server wrote then is not "
            + "known.");
        if (whole.ratedCalls() > 0 && whole.ratedFromFirstToken() == whole.ratedCalls()) {
            sb.append(" Every call's tokens are spread from its first token to its end, so the "
                + "rates are generation alone.");
        } else if (whole.ratedFromFirstToken() > 0) {
            sb.append(" The first token's time was recorded for ")
                .append(whole.ratedFromFirstToken()).append(" of ").append(whole.ratedCalls())
                .append(" calls (the streamed ones); their tokens are spread from first token to "
                    + "end. For the other ").append(whole.ratedCalls() - whole.ratedFromFirstToken())
                .append(" - worker calls are not streamed - the tokens are spread over the whole "
                    + "call, so the time the server spent reading the prompt is inside the rate "
                    + "and the rate reads lower than generation alone.");
        } else {
            sb.append(" No call's first-token time was recorded, so each call's tokens are spread "
                + "over the whole call: the time the server spent reading the prompt is inside "
                + "every rate, which therefore reads lower than generation alone.");
        }
        if (unfinished > 0) {
            sb.append(' ').append(unfinished).append(" call(s) with no recorded end are left out.");
        }
        sb.append('\n');
        failedCalls(sb, in);
    }

    /** The calls that gave no answer, each with the place-time it held. */
    private static void failedCalls(StringBuilder sb, Input in) {
        boolean counts = serverCountsTokens(in.calls());
        List<RunMeter.Call> failed = in.calls().stream()
            .filter(c -> c.finished() && gaveNoAnswer(c, counts))
            .sorted(Comparator.comparingLong(RunMeter.Call::startMillis)).toList();
        sb.append("\n### Calls that gave no answer\n\n");
        if (failed.isEmpty()) {
            sb.append("None: every finished call came back with an answer.\n");
            return;
        }
        double total = failed.stream().mapToLong(RunMeter.Call::millis).sum() / 60_000.0;
        sb.append(failed.size()).append(" call(s) failed, timed out or were abandoned, holding ")
            .append(fmt(total)).append(" slot-minute(s) of the model server in all. They are in "
                + "the in-flight counts above and in none of the rates.\n\n");
        sb.append("| role | task | worker | started (minutes into the run) | slot-minutes |\n"
            + "|---|---|---|---|---|\n");
        for (RunMeter.Call call : failed) {
            String title = in.tasks().stream()
                .filter(t -> t.id().toString().equals(call.task())).map(Task::title)
                .findFirst().orElse(call.task() == null ? "" : call.task());
            sb.append("| ").append(call.role()).append(" | ").append(title).append(" | ")
                .append(call.worker() == null ? "" : call.worker()).append(" | ")
                .append(fmt((call.startMillis() - in.startMillis()) / 60_000.0)).append(" | ")
                .append(fmt(call.millis() / 60_000.0)).append(" |\n");
        }
    }

    private static void concurrencyRow(StringBuilder sb, String name, Concurrency c) {
        sb.append("| ").append(name).append(" | ").append(fmt(c.average())).append(" | ")
            .append(c.peak());
        for (int level = 0; level < 5; level++) {
            sb.append(" | ").append(percent(c.share()[level]));
        }
        sb.append(" | ").append(rate(c.overallTokenSec(), c.tokensKnown()));
        for (int level = 1; level < 5; level++) {
            sb.append(" | ").append(rate(c.tokenSec()[level], c.tokensKnown()));
        }
        sb.append(" |\n");
    }

    private static String rate(double tokensPerSecond, boolean tokensKnown) {
        if (tokensPerSecond < 0) {
            return "no time at this level";
        }
        return tokensKnown ? fmt(tokensPerSecond) : NOT_RECORDED;
    }

    // 8 ---------------------------------------------------------------------------------------

    private static void redundancy(StringBuilder sb, Input in) {
        sb.append("\n## 8. Redundancy: were the extra candidates worth having?\n\n");
        if (in.tasks().isEmpty()) {
            sb.append(NOT_RECORDED).append(": no plan was stored.\n");
            return;
        }
        sb.append("| task | candidates | passed verification | worker 0 alone passed | what "
            + "happened | judge score spread among survivors |\n|---|---|---|---|---|---|\n");
        int built = 0;
        int single = 0;
        int rescued = 0;
        int judgeChose = 0;
        int neededRepair = 0;
        List<AgentSessionRecord> redundant = new ArrayList<>();
        for (Task task : in.tasks()) {
            List<CandidateSolution> all = candidatesOf(in, task);
            if (all.isEmpty()) {
                sb.append("| ").append(task.title()).append(" | 0 | 0 | ").append(NOT_RECORDED)
                    .append(" | never dispatched | ").append(NOT_RECORDED).append(" |\n");
                continue;
            }
            built++;
            List<CandidateSolution> first = all.stream()
                .filter(c -> !RepairIndex.isRepair(c.workerIndex())).toList();
            List<CandidateSolution> passedFirst = first.stream()
                .filter(HarnessRunReport::passed).toList();
            boolean workerZero = passedFirst.stream().anyMatch(c -> c.workerIndex() == 0);
            boolean delivered = all.stream().anyMatch(c -> c.state() == CandidateState.SELECTED);
            boolean repaired = all.stream().anyMatch(c -> RepairIndex.isRepair(c.workerIndex()));
            String what;
            if (passedFirst.isEmpty()) {
                what = repaired ? "none passed first time; a repair round was needed"
                    + (delivered ? "" : " and still nothing was delivered")
                    : "none passed and nothing was delivered";
                neededRepair += repaired ? 1 : 0;
            } else if (!workerZero) {
                what = "only a later candidate passed: the swarm rescued this task";
                rescued += delivered ? 1 : 0;
            } else if (passedFirst.size() > 1) {
                what = "several passed and the judge chose";
            } else if (first.stream().anyMatch(c -> c.workerIndex() != 0 && !passed(c)
                    && c.killReason() != KillReason.SUPERSEDED
                    && c.killReason() != KillReason.PLACE_NEEDED)) {
                what = "worker 0 passed, the others did not";
            } else {
                what = "worker 0 passed; no other candidate was needed";
            }
            if (passedFirst.size() > 1) {
                judgeChose++;
            }
            if (workerZero) {
                single++;
            }
            sb.append("| ").append(task.title()).append(" | ").append(all.size()).append(" | ")
                .append(all.stream().filter(HarnessRunReport::passed).count()).append(" | ")
                .append(workerZero ? "yes" : "no").append(" | ").append(what).append(" | ")
                .append(spread(all)).append(" |\n");
            for (AgentSessionRecord session : in.sessions()) {
                if (task.id().equals(session.taskId()) && session.workerIndex() != 0
                        && !RepairIndex.isRepair(session.workerIndex())) {
                    redundant.add(session);
                }
            }
        }
        List<RunMeter.Call> redundantCalls = new ArrayList<>();
        redundant.forEach(s -> redundantCalls.addAll(callsOf(in, s)));
        sb.append("\nA worker \"passed\" when its candidate survived verification; the first "
            + "candidate is worker 0 of the first dispatch.\n\n");
        sb.append("- Tasks a single worker would have delivered: ").append(single).append(" of ")
            .append(built).append('\n');
        sb.append("- Tasks only delivered because of a second candidate: ").append(rescued)
            .append('\n');
        sb.append("- Tasks where several passed and the judge chose: ").append(judgeChose).append('\n');
        sb.append("- Tasks where none passed first time and a repair round was needed: ")
            .append(neededRepair).append('\n');
        sb.append("- Extra tokens and minutes spent on redundant candidates (every first-dispatch "
            + "worker other than worker 0): ")
            .append(anyUsage(redundantCalls)
                ? "prompt " + sumTokens(redundantCalls, true, false) + " tokens, completion "
                    + sumTokens(redundantCalls, false, true) + " tokens"
                : "tokens " + NOT_RECORDED)
            .append(", ").append(redundant.isEmpty() && in.sessions().isEmpty()
                ? "minutes " + NOT_RECORDED : fmt(minutes(sessionMillis(redundant))) + " minutes")
            .append('\n');
    }

    /** Best minus worst judge score among the candidates that passed verification. */
    private static String spread(List<CandidateSolution> candidates) {
        List<Double> scores = candidates.stream().filter(HarnessRunReport::passed)
            .filter(c -> c.judge() != null).map(c -> c.judge().score()).toList();
        if (scores.size() < 2) {
            return scores.isEmpty() ? NOT_RECORDED : "one survivor scored " + fmt2(scores.get(0));
        }
        double best = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double worst = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        return fmt2(best - worst) + " (best " + fmt2(best) + ", worst " + fmt2(worst) + ")";
    }

    // 10 --------------------------------------------------------------------------------------

    /** Harness run 66's prompt tokens per worker call, before old tool results were cut. */
    private static final int RUN_66_PROMPT_TOKENS_PER_WORKER_TURN = 24_744;

    /** The marks the dispatcher and the engine leave on the run's record; see their classes. */
    private static final String STARTED = "candidate started|";
    private static final String NOT_STARTED = "candidate not started|";
    private static final String CANCELLED = "candidate cancelled|";
    private static final String JUDGE_SKIPPED = "judge skipped|";
    private static final String STOPPED_VERIFIED = "stopped candidate verified|";

    /**
     * Whether the model server's places went to different work first, and what a worker's turn
     * cost in prompt tokens (2026-10-02).
     *
     * <p>Counted from marks left on the run's own record as things happened: each candidate that
     * started says how (first of its task, on a spare place, or because the earlier ones failed),
     * each one that was never started or was stopped says so, and each judge call not made says
     * so. How many calls were in flight over each wave is section 7.
     */
    private static void places(StringBuilder sb, Input in) {
        sb.append("\n## 10. Places: did different work come first?\n\n");
        List<RunMeter.Span> marks = in.spans().stream().filter(s -> s.name().startsWith(STARTED)
            || s.name().startsWith(NOT_STARTED) || s.name().startsWith(CANCELLED)
            || s.name().startsWith(JUDGE_SKIPPED)).toList();
        if (marks.isEmpty()) {
            sb.append(NOT_RECORDED).append(": this run left no record of how its candidates were "
                + "started.\n");
        } else {
            sb.append("| wave | tasks | first candidates started | started because earlier ones "
                + "failed | started on a spare place | never started: the task already had a "
                + "passing candidate | stopped: the task already had a passing candidate | "
                + "stopped: the place was needed by another task | judge calls made | judge calls "
                + "skipped |\n|---|---|---|---|---|---|---|---|---|---|\n");
            Set<String> placed = new java.util.HashSet<>();
            for (int w = 0; w < in.waves().size(); w++) {
                Set<String> tasks = new java.util.HashSet<>();
                in.waves().get(w).forEach(id -> tasks.add(id.toString()));
                placed.addAll(tasks);
                placesRow(sb, "wave " + (w + 1) + " of " + in.waves().size(), tasks.size(), marks,
                    in.calls(), tasks);
            }
            Set<String> every = new java.util.HashSet<>();
            in.tasks().forEach(task -> every.add(task.id().toString()));
            if (in.waves().isEmpty() || !placed.containsAll(every)) {
                placesRow(sb, in.waves().isEmpty() ? "whole run (waves not recorded)"
                    : "tasks in no recorded wave", -1, marks, in.calls(),
                    in.waves().isEmpty() ? null : without(every, placed));
            }
            sb.append("\nA task's first candidate waits its turn for a place on the model "
                + "server; every further candidate starts only on a place nothing else is "
                + "waiting for, and not at all once its task has a candidate that passed. How "
                + "many calls were in flight over each wave is in section 7.\n");
        }

        // "stopped candidate verified|<why>|<task>|<worker>|<survived or failed>"
        List<RunMeter.Span> stopped = in.spans().stream()
            .filter(s -> s.name().startsWith(STOPPED_VERIFIED)).toList();
        long stoppedSurvived = stopped.stream().filter(s -> s.name().endsWith("|survived")).count();
        sb.append("\nWorkers that were stopped (no progress, turn cap, out of room, place needed) "
            + "and had left a change, so the change was verified like any other: ")
            .append(stopped.size()).append("; of those, passed verification: ")
            .append(stoppedSurvived).append(".\n");

        sb.append("\n### Prompt tokens per worker turn\n\n");
        List<RunMeter.Call> workerCalls = in.calls().stream()
            .filter(c -> c.role().equals("worker") && c.promptTokens() >= 0).toList();
        if (workerCalls.isEmpty()) {
            sb.append(NOT_RECORDED).append(": no worker call reported its prompt tokens.\n");
            return;
        }
        sb.append("| task | worker turns | prompt tokens | per turn | largest single turn |\n"
            + "|---|---|---|---|---|\n");
        for (Task task : in.tasks()) {
            List<RunMeter.Call> mine = workerCalls.stream()
                .filter(c -> task.id().toString().equals(c.task())).toList();
            if (!mine.isEmpty()) {
                promptRow(sb, cell(task.title()), mine);
            }
        }
        promptRow(sb, "**all workers**", workerCalls);
        sb.append("\nA turn is one model call; its prompt tokens are the server's own count of "
            + "what it was sent. Every turn resends the conversation, so this is the figure that "
            + "cutting old tool results down to their first lines lowers. What these same turns "
            + "would have cost without that cannot be measured after the fact; for comparison, "
            + "harness run 66, before it, averaged ").append(RUN_66_PROMPT_TOKENS_PER_WORKER_TURN)
            .append(" per turn.\n");
    }

    private static Set<String> without(Set<String> all, Set<String> taken) {
        Set<String> rest = new java.util.HashSet<>(all);
        rest.removeAll(taken);
        return rest;
    }

    /** One row of the places table, for the marks and judge calls of {@code tasks} (null = all). */
    private static void placesRow(StringBuilder sb, String name, int taskCount,
                                  List<RunMeter.Span> marks, List<RunMeter.Call> calls,
                                  Set<String> tasks) {
        long first = 0;
        long needed = 0;
        long spare = 0;
        long notStarted = 0;
        long superseded = 0;
        long placeNeeded = 0;
        long skipped = 0;
        for (RunMeter.Span mark : marks) {
            String[] parts = mark.name().split("\\|");
            // "candidate started|<how>|<task>|<worker>", "candidate cancelled|<why>|<task>|<worker>",
            // "candidate not started|<task>|<worker>", "judge skipped|<task>|<worker>"
            boolean qualified = mark.name().startsWith(STARTED) || mark.name().startsWith(CANCELLED);
            String task = parts.length > (qualified ? 2 : 1) ? parts[qualified ? 2 : 1] : "";
            if (tasks != null && !tasks.contains(task)) {
                continue;
            }
            if (mark.name().startsWith(STARTED)) {
                switch (parts.length > 1 ? parts[1] : "") {
                    case "first" -> first++;
                    case "needed" -> needed++;
                    default -> spare++;
                }
            } else if (mark.name().startsWith(NOT_STARTED)) {
                notStarted++;
            } else if (mark.name().startsWith(CANCELLED)) {
                if (parts.length > 1 && parts[1].equals(KillReason.PLACE_NEEDED.name())) {
                    placeNeeded++;
                } else {
                    superseded++;
                }
            } else {
                skipped++;
            }
        }
        long judged = calls.stream().filter(c -> c.role().equals("judge")
            && (tasks == null || c.task() != null && tasks.contains(c.task()))).count();
        sb.append("| ").append(name).append(" | ").append(taskCount < 0 ? "-" : taskCount)
            .append(" | ").append(first).append(" | ").append(needed).append(" | ").append(spare)
            .append(" | ").append(notStarted).append(" | ").append(superseded).append(" | ")
            .append(placeNeeded).append(" | ").append(judged).append(" | ").append(skipped)
            .append(" |\n");
    }

    private static void promptRow(StringBuilder sb, String name, List<RunMeter.Call> calls) {
        long total = calls.stream().mapToLong(RunMeter.Call::promptTokens).sum();
        int largest = calls.stream().mapToInt(RunMeter.Call::promptTokens).max().orElse(0);
        sb.append("| ").append(name).append(" | ").append(calls.size()).append(" | ")
            .append(total).append(" | ").append(total / calls.size()).append(" | ")
            .append(largest).append(" |\n");
    }

    // --- small formatting ----------------------------------------------------------------------

    // 9 ---------------------------------------------------------------------------------------

    /**
     * Every answer the expert desk gave: who asked, what it cost, and what became of the work that
     * followed it. The outcome column is evidence and is labelled as such.
     */
    private static void expertAnswers(StringBuilder sb, Input in) {
        sb.append("\n## 9. Expert answers\n\n");
        List<ExpertAnswerLog.Entry> answers = in.expertAnswers();
        if (answers == null) {
            sb.append(NOT_RECORDED).append(": this run kept no record of expert answers.\n");
            return;
        }
        if (answers.isEmpty()) {
            sb.append("No question was put to the expert.\n");
            return;
        }
        sb.append("| asker | question | source | turns | lookups | prompt tokens | completion "
            + "tokens | seconds | outcome (evidence, not proof) | because |\n"
            + "|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (ExpertAnswerLog.From from : ExpertAnswerLog.From.values()) {
            bySource.put(from.words(), 0);
        }
        int helped = 0;
        int didNot = 0;
        int unknown = 0;
        long prompt = 0;
        long completion = 0;
        boolean tokensKnown = true;
        double researchSeconds = 0;
        double allSeconds = 0;
        for (ExpertAnswerLog.Entry answer : answers) {
            bySource.merge(answer.from().words(), 1, Integer::sum);
            switch (answer.outcome()) {
                case "helped" -> helped++;
                case "did not help" -> didNot++;
                default -> unknown++;
            }
            allSeconds += answer.seconds();
            if (answer.from() == ExpertAnswerLog.From.EXPERT_RESEARCH) {
                researchSeconds += answer.seconds();
            }
            if (answer.promptTokens() < 0 || answer.completionTokens() < 0) {
                tokensKnown = false;
            } else {
                prompt += answer.promptTokens();
                completion += answer.completionTokens();
            }
            sb.append("| ").append(cell(answer.asker().words())).append(" | ")
                .append(cell(cut(firstLine(answer.question()), 90))).append(" | ")
                .append(answer.from().words()).append(" | ").append(answer.turns()).append(" | ")
                .append(answer.lookups()).append(" | ")
                .append(answer.promptTokens() < 0 ? NOT_RECORDED : String.valueOf(answer.promptTokens()))
                .append(" | ")
                .append(answer.completionTokens() < 0 ? NOT_RECORDED
                    : String.valueOf(answer.completionTokens()))
                .append(" | ").append(fmt(answer.seconds())).append(" | ")
                .append(answer.outcome()).append(" | ").append(answer.outcomeBecause())
                .append(" |\n");
        }
        sb.append("\nTotals: ").append(answers.size()).append(" answer(s)");
        for (Map.Entry<String, Integer> source : bySource.entrySet()) {
            sb.append(", ").append(source.getValue()).append(' ').append(source.getKey());
        }
        sb.append("; helped ").append(helped).append(", did not help ").append(didNot)
            .append(", unknown ").append(unknown).append(".\n");
        sb.append("Askers waited ").append(fmt(allSeconds)).append(" s in all, ")
            .append(fmt(researchSeconds)).append(" s of it for expert research; the expert's "
                + "model was sent ").append(tokensKnown ? String.valueOf(prompt) : NOT_RECORDED
                + " (at least " + prompt + ")")
            .append(" prompt tokens and wrote ").append(tokensKnown ? String.valueOf(completion)
                : NOT_RECORDED + " (at least " + completion + ")").append(".\n");
        sb.append(ExpertAnswerLog.OUTCOME_CAVEAT).append('\n');
    }

    /** Text safe inside one table cell. */
    private static String cell(String text) {
        return text == null ? "" : text.replace('|', '/').replace('\n', ' ');
    }

    private static double minutes(long millis) {
        return millis / 60_000.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String fmt2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    private static String percent(long part, long whole) {
        return percent(whole <= 0 ? 0 : (double) part / whole);
    }

    private static final DateTimeFormatter CLOCK =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis));
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(no text)";
        }
        return cut(text.strip().lines().findFirst().orElse(""), 300);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
