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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CloudSpendRecord;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.domain.UsageReport;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The report of one run; a sentence saying why not when the id names none. */
    static String ofRun(ArtifactStore store, String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId == null ? "" : runId.trim());
        } catch (IllegalArgumentException e) {
            return "'" + runId + "' is not a run id. Take one from list_runs.";
        }
        Run run = store.root().runs.get(id);
        if (run == null) {
            return "There is no run " + id + ". Take a run id from list_runs.";
        }
        String heading = "Run " + id + " (" + (run.kind() == null ? "?" : run.kind().name())
            + ", " + (run.state() == null ? "?" : run.state().name())
            + "): model tokens per role.";
        RunUsageRecord usage = store.root().runUsage().get(id);
        if (usage != null && !(usage.models().isEmpty() && usage.lookups().isEmpty())) {
            return UsageReport.render(heading, usage.models(), usage.lookups());
        }
        return heading + "\n" + beforeCallsWereCounted(store, run);
    }

    /**
     * The report of one project: a totals sentence per story, then the table per role and the
     * lookups for all of its runs together.
     */
    static String ofProject(ArtifactStore store, UUID projectId) {
        if (projectId == null) {
            return "No project is selected.";
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
            UUID story = run.storyId() != null ? run.storyId() : storyOfRun.get(run.id());
            if (story == null) {
                noStory.add(usage);
            } else {
                byStory.computeIfAbsent(story, k -> new ArrayList<>()).add(usage);
            }
        }
        StringBuilder sb = new StringBuilder("Project: model tokens per story and per role, over "
            + all.size() + " run(s).\n");
        if (unrecorded > 0) {
            sb.append(unrecorded).append(" earlier run(s) are not in these figures: they made no "
                + "model call or ran before calls were counted one by one (run_report on one of "
                + "them says what is still known).\n");
        }
        if (all.isEmpty()) {
            return sb.append("No model call was recorded for this project yet.\n").toString();
        }
        sb.append('\n');
        byStory.forEach((story, runs) -> sb.append("- ")
            .append(cut(titles.getOrDefault(story, "story " + story), 60)).append(" (")
            .append(runs.size()).append(" run(s)): ")
            .append(UsageReport.spendLine(UsageReport.modelsOf(runs))).append('\n'));
        if (!noStory.isEmpty()) {
            sb.append("- runs of no story (").append(noStory.size()).append("): ")
                .append(UsageReport.spendLine(UsageReport.modelsOf(noStory))).append('\n');
        }
        sb.append('\n').append(UsageReport.render("Whole project:", UsageReport.modelsOf(all),
            UsageReport.lookupsAdded(all)));
        return sb.toString();
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
