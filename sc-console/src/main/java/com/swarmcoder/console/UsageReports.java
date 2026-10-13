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

import com.swarmcoder.console.api.UsageTableDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CallUsageRecord;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.CloudSpendRecord;
import com.swarmcoder.domain.LookupUsageRecord;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.domain.UsageReport;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a run, a story and a project cost in model tokens, as text: read from what the usage
 * ledger stored with each run and written by {@link UsageReport}. Behind the {@code run_report}
 * and {@code project_report} tools and the totals sentence of the delivery evidence.
 *
 * <p>A run that finished before calls were counted one by one has no stored usage. For such a
 * run {@link #ofRun} says so and shows what can still be read from its stored sessions: every
 * turn of a session kept the size of its call as the server counted it, input and output
 * together.
 */
final class UsageReports {

    private UsageReports() { }

    /**
     * Everything a report is written from, gathered once: the text of {@code run_report} and
     * {@code project_report} and the rows of the Console's tables are both made from one of
     * these, so they show the same figures.
     *
     * @param before  what stands above the report (a project's stories), or ""
     * @param instead what is said when there is no report to write, or null
     * @param used    what came of the work, as lines
     * @param runs    how many runs the figures are of
     */
    private record Gathered(String before, String heading, String instead,
                            List<ModelUsageRecord> models, List<LookupUsageRecord> lookups,
                            List<CallUsageRecord> calls, String used, int runs) {

        static Gathered none(String heading, String instead) {
            return new Gathered("", heading, instead, List.of(), List.of(), List.of(), "", 0);
        }

        String text() {
            return instead != null ? instead
                : before + UsageReport.render(heading, models, lookups, calls, used, runs);
        }

        UsageTableDto table() {
            UsageTableDto dto = new UsageTableDto();
            dto.setTitle(heading);
            dto.setNote(instead == null ? "" : instead);
            dto.setTotals(models.isEmpty() ? "" : UsageReport.spendLine(models) + ".");
            dto.setWarnings(UsageReport.warnings(models, lookups, runs));
            StringBuilder roles = new StringBuilder();
            for (UsageReport.RoleFigures row : UsageReport.figuresOf(models, calls)) {
                roles.append(row.role()).append('\t').append(row.server()).append('\t')
                    .append(row.calls()).append('\t').append(orNot(row.input())).append('\t')
                    .append(orNot(row.cachedInput())).append('\t').append(orNot(row.output()))
                    .append('\t').append(orNot(row.thinking())).append('\t')
                    .append(orNot(row.medianInput())).append('\t')
                    .append(orNot(row.largestInput())).append('\t')
                    .append(row.largestPurpose().isEmpty() ? UsageReport.NOT_RECORDED
                        : row.largestPurpose().replace('\t', ' ').replace('\n', ' '))
                    .append('\t').append(row.seconds() < 0 ? UsageReport.NOT_RECORDED
                        : String.valueOf(Math.round(row.seconds())))
                    .append('\t').append(row.failed()).append('\n');
            }
            dto.setRoleRows(roles.toString());
            StringBuilder looked = new StringBuilder();
            for (LookupUsageRecord row : lookups) {
                looked.append(row.role()).append('\t')
                    .append(row.label() == null ? row.kind() : row.label()).append('\t')
                    .append(row.calls()).append('\t').append(row.chars()).append('\t')
                    .append(row.repeatCalls()).append('\t').append(row.repeatChars())
                    .append('\t').append(row.referenceCalls()).append('\n');
            }
            dto.setLookupRows(looked.toString());
            dto.setUsed(used == null ? "" : used);
            return dto;
        }

        private static String orNot(long figure) {
            return figure < 0 ? UsageReport.NOT_RECORDED : String.valueOf(figure);
        }
    }

    /** The report of one run; a sentence saying why not when the id names none. */
    static String ofRun(ArtifactStore store, String runId) {
        return gatherRun(store, runId).text();
    }

    /** The figures of {@link #ofRun} as rows for the Console's tables. */
    static UsageTableDto tableOfRun(ArtifactStore store, String runId) {
        return gatherRun(store, runId).table();
    }

    private static Gathered gatherRun(ArtifactStore store, String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId == null ? "" : runId.trim());
        } catch (IllegalArgumentException e) {
            return Gathered.none("", "'" + runId + "' is not a run id. Take one from list_runs.");
        }
        Run run = store.root().runs.get(id);
        if (run == null) {
            return Gathered.none("", "There is no run " + id + ". Take a run id from list_runs.");
        }
        String heading = "Run " + id + " (" + (run.kind() == null ? "?" : run.kind().name())
            + ", " + (run.state() == null ? "?" : run.state().name())
            + "): model tokens per role.";
        RunUsageRecord usage = store.root().runUsage().get(id);
        if (usage != null && !(usage.models().isEmpty() && usage.lookups().isEmpty())) {
            List<CallUsageRecord> calls = usage.calls();
            String used = usedOf(store, calls) + endOf(run, usage.models());
            return new Gathered("", heading, null, usage.models(), usage.lookups(), calls, used,
                1);
        }
        return Gathered.none(heading, heading + "\n" + beforeCallsWereCounted(store, run));
    }

    /**
     * What came of a run, in one sentence: delivered, ended early with what it had taken by
     * then, or not ended. A run that ended early delivered nothing of what its tokens made.
     */
    private static String endOf(Run run, List<ModelUsageRecord> models) {
        ModelUsageRecord all = UsageReport.total(models, null);
        if (run.state() == RunState.DELIVERED) {
            return "This run was delivered.\n";
        }
        if (endedEarly(run)) {
            return "This run ended early (" + run.state().name() + "): nothing of its "
                + all.inputTokens() + " input / " + all.outputTokens() + " output tokens in "
                + all.calls() + " calls was delivered.\n";
        }
        return "This run has not ended (" + (run.state() == null ? "?" : run.state().name())
            + ").\n";
    }

    private static boolean endedEarly(Run run) {
        return run.state() == RunState.ABORTED || run.state() == RunState.ABANDONED;
    }

    /**
     * {@link UsageReport#usedLine} for {@code calls}, with what became of each candidate they
     * name read from the candidates the store keeps.
     */
    private static String usedOf(ArtifactStore store, List<CallUsageRecord> calls) {
        Set<String> named = new HashSet<>();
        for (CallUsageRecord call : calls) {
            if (call.candidate() != null) {
                named.add(call.candidate());
            }
        }
        if (named.isEmpty()) {
            return "";
        }
        Set<String> chosen = new HashSet<>();
        Set<String> judged = new HashSet<>();
        try {
            // A candidate is kept under its own id by one writer and under its task's id by
            // another, so every kept candidate is looked at once; only the ids named count.
            for (Lazy<Object> lazy : new ArrayList<>(store.root().candidateArchives.values())) {
                if (Lazy.get(lazy) instanceof CandidateSolution candidate
                        && candidate.id() != null && named.contains(candidate.id().toString())) {
                    if (candidate.state() != null && candidate.state() != CandidateState.RUNNING) {
                        judged.add(candidate.id().toString());
                    }
                    if (candidate.state() == CandidateState.SELECTED) {
                        chosen.add(candidate.id().toString());
                    }
                }
            }
        } catch (RuntimeException e) {                                     // noqa
            // Without the candidates nothing is known of their ends; the sentence says so.
        }
        return UsageReport.usedLine(calls, chosen, judged);
    }

    /**
     * The report of one project: a totals sentence per story, then the table per role and the
     * lookups for all of its runs together.
     */
    static String ofProject(ArtifactStore store, UUID projectId) {
        return gatherProject(store, projectId).text();
    }

    /** The figures of {@link #ofProject} as rows for the Console's tables. */
    static UsageTableDto tableOfProject(ArtifactStore store, UUID projectId) {
        return gatherProject(store, projectId).table();
    }

    private static Gathered gatherProject(ArtifactStore store, UUID projectId) {
        if (projectId == null) {
            return Gathered.none("", "No project is selected.");
        }
        Map<UUID, String> titles = new LinkedHashMap<>();
        Map<UUID, UUID> storyOfRun = new LinkedHashMap<>();
        try {
            for (Story story : store.listStories(projectId)) {
                titles.put(story.id(), story.title());
                story.runIds().forEach(run -> storyOfRun.put(run, story.id()));
            }
        } catch (RuntimeException e) {                                     // noqa
            // Without the backlog the stories are named by id; the figures are the same.
        }
        Map<UUID, List<RunUsageRecord>> byStory = new LinkedHashMap<>();
        List<RunUsageRecord> noStory = new ArrayList<>();
        List<RunUsageRecord> all = new ArrayList<>();
        // What came of the runs: delivered, ended early, not ended - each with its tokens.
        List<RunUsageRecord> delivered = new ArrayList<>();
        List<RunUsageRecord> early = new ArrayList<>();
        List<RunUsageRecord> open = new ArrayList<>();
        int unrecorded = 0;
        for (Run run : new ArrayList<>(store.root().runs.values())) {
            boolean mine = projectId.equals(run.projectId()) || storyOfRun.containsKey(run.id());
            if (!mine) {
                continue;
            }
            RunUsageRecord usage = store.root().runUsage().get(run.id());
            if (usage == null || usage.models().isEmpty()) {
                unrecorded++;
                continue;
            }
            all.add(usage);
            (run.state() == RunState.DELIVERED ? delivered : endedEarly(run) ? early : open)
                .add(usage);
            UUID story = run.storyId() != null ? run.storyId() : storyOfRun.get(run.id());
            if (story == null) {
                noStory.add(usage);
            } else {
                byStory.computeIfAbsent(story, k -> new ArrayList<>()).add(usage);
            }
        }
        // What was called for no run while this project was open: the wizards, the chat,
        // knowledge read at start (section 102). Kept under the project's own id.
        RunUsageRecord outside = store.root().runUsage().get(projectId);
        if (outside != null && !(outside.outsideRuns() && !outside.models().isEmpty())) {
            outside = null;
        }
        int runs = all.size();
        StringBuilder sb = new StringBuilder("Project: model tokens per story and per role, over "
            + runs + " run(s).\n");
        if (unrecorded > 0) {
            sb.append(unrecorded).append(" earlier run(s) are not in these figures: they made no "
                + "model call or ran before calls were counted one by one (run_report on one of "
                + "them says what is still known).\n");
        }
        if (all.isEmpty() && outside == null) {
            return Gathered.none("Project: model tokens per story and per role.",
                sb.append("No model call was recorded for this project yet.\n").toString());
        }
        sb.append('\n');
        byStory.forEach((story, ofStory) -> sb.append("- ")
            .append(cut(titles.getOrDefault(story, "story " + story), 60)).append(" (")
            .append(ofStory.size()).append(" run(s)): ")
            .append(UsageReport.spendLine(UsageReport.modelsOf(ofStory))).append('\n'));
        if (!noStory.isEmpty()) {
            sb.append("- runs of no story (").append(noStory.size()).append("): ")
                .append(UsageReport.spendLine(UsageReport.modelsOf(noStory))).append('\n');
        }
        if (outside != null) {
            sb.append("- calls for no run (the wizards, the chat, knowledge): ")
                .append(UsageReport.spendLine(outside.models())).append('\n');
        }
        StringBuilder used = new StringBuilder(usedOf(store, UsageReport.callsOf(all)));
        if (runs > 0) {
            used.append("Runs: ").append(delivered.size()).append(" delivered (")
                .append(inOut(delivered)).append("), ").append(early.size())
                .append(" ended early (").append(inOut(early)).append(", none of it "
                    + "delivered), ").append(open.size()).append(" not ended (")
                .append(inOut(open)).append(").\n");
        }
        if (outside != null) {
            all.add(outside);
        }
        return new Gathered(sb.append('\n').toString(), "Whole project:", null,
            UsageReport.modelsOf(all), UsageReport.lookupsAdded(all), UsageReport.callsOf(all),
            used.toString(), Math.max(1, runs));
    }

    private static String inOut(List<RunUsageRecord> runs) {
        ModelUsageRecord sum = UsageReport.total(UsageReport.modelsOf(runs), null);
        return sum.inputTokens() + " in / " + sum.outputTokens() + " out";
    }

    /**
     * The totals sentence of one run for the delivery evidence, or null when nothing was stored
     * for it: "Paid cloud: N in / N out over K calls; local: N in / N out".
     */
    static String evidenceOf(ArtifactStore store, UUID runId) {
        RunUsageRecord usage = runId == null ? null : store.root().runUsage().get(runId);
        return usage == null || usage.models().isEmpty() ? null
            : UsageReport.spendLine(usage.models());
    }

    /** The paid and the local totals of one run; both empty when nothing was stored for it. */
    static ModelUsageRecord[] totalsOf(Map<UUID, RunUsageRecord> usage, UUID runId) {
        RunUsageRecord record = usage.get(runId);
        List<ModelUsageRecord> models = record == null ? List.of() : record.models();
        return new ModelUsageRecord[] {
            UsageReport.total(models, true), UsageReport.total(models, false)};
    }

    // --- a run from before ----------------------------------------------------------------------

    /**
     * What is still known of a run whose calls were not recorded one by one: the cloud gate's
     * estimate, and what its stored sessions show. A session kept, with every turn, the size of
     * that turn's call as the server counted it - input and output together - so the sum over a
     * session's turns is what the session really used; the split is estimated from the length
     * of what the model wrote.
     */
    private static String beforeCallsWereCounted(ArtifactStore store, Run run) {
        StringBuilder sb = new StringBuilder("This run's calls were not recorded one by one "
            + "(it ran before that, or made none).\n");
        CloudSpendRecord gate = store.root().cloudSpend().get(run.id());
        if (gate != null) {
            sb.append("The cloud gate counted ").append(gate.input()).append(" in / ")
                .append(gate.output()).append(" out for it. That figure is an estimate and is "
                    + "too low: a session that works with tools was counted by the growth of its "
                    + "conversation, all as input.\n");
        }
        Instant from = run.startedAt();
        Instant to = run.heartbeatAt();
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (Lazy<Object> lazy : new ArrayList<>(store.root().agentSessions().values())) {
            if (!(Lazy.get(lazy) instanceof AgentSessionRecord session)
                    || session.events() == null) {
                continue;
            }
            boolean ofRun = run.id().equals(session.runId());
            boolean during = session.runId() == null && from != null && to != null
                && session.openedAt() != null && !session.openedAt().isBefore(from)
                && !session.openedAt().isAfter(to);
            if (!ofRun && !during) {
                continue;
            }
            String role = session.role() == null ? "?" : session.role();
            int dash = role.indexOf('-');
            String key = (dash > 0 ? role.substring(0, dash) : role) + " | "
                + (session.modelProfileId() == null ? "?" : session.modelProfileId()) + " | "
                + (ofRun ? "this run's" : "no run named, opened while it ran");
            long[] sums = rows.computeIfAbsent(key, k -> new long[3]);
            // A turn is the model's answer and the tool calls it made in it; what the model
            // wrote in a turn is the answer's text and those calls' arguments.
            boolean inTurn = false;
            for (TraceEvent event : session.events()) {
                if (event.kind() == TraceEventKind.LLM_RESPONSE) {
                    sums[0]++;
                    sums[1] += Math.max(0, event.tokensUsed());
                    sums[2] += chars(event) / 4;
                    inTurn = true;
                } else if (event.kind() == TraceEventKind.TOOL_CALL) {
                    sums[2] += inTurn ? chars(event) / 4 : 0;
                } else if (event.kind() != TraceEventKind.TOOL_RESULT) {
                    inTurn = false;
                }
            }
        }
        if (rows.isEmpty()) {
            return sb.append("No stored session belongs to it.\n").toString();
        }
        sb.append("\nFrom its stored sessions (tokens are each call's input and output together, "
            + "as the server counted them; output is estimated from the length of what the model "
            + "wrote and is a lower bound; calls that asked one question without tools left no "
            + "session and are not here):\n"
            + "| role | model | whose | calls | tokens | of which output, estimated |\n"
            + "|---|---|---|---|---|---|\n");
        rows.forEach((key, sums) -> sb.append("| ").append(key).append(" | ").append(sums[0])
            .append(" | ").append(sums[1]).append(" | ").append(Math.min(sums[1], sums[2]))
            .append(" |\n"));
        return sb.toString();
    }

    private static long chars(TraceEvent event) {
        return event.payload() == null ? 0 : event.payload().length();
    }

    private static String cut(String text, int max) {
        String one = text == null ? "" : text.replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
