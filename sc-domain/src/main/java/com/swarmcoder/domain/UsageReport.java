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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The report of what a run's model calls cost, per role: the one place its tables are written.
 *
 * <p>The test harness printed this table from its own code for months and the application had
 * none (2026-10-13: a supervised build could only be told "1163749 in, 2369 out", a figure that
 * counted an agent session's output as input). The harness's run report, the application's
 * {@code run_report} and {@code project_report} tools and the delivery evidence all call this
 * class, so there is one table and one way of summing it.
 *
 * <p>Input and output tokens are separate columns everywhere and are never added together. Every
 * figure is the model server's own count for a call; where a server gave none, the cell says so.
 */
public final class UsageReport {

    public static final String NOT_RECORDED = "not recorded";
    /** What a paid server is called in the server column. */
    public static final String PAID = "paid cloud";
    /** What the operator's own server is called in the server column. */
    public static final String LOCAL = "local";

    private UsageReport() {}

    /**
     * One line of the table.
     *
     * @param role   the role, as the calls were tagged
     * @param server where the calls went, for the server column; may be empty
     * @param sum    the calls of that role on that server, added up
     */
    public record Row(String role, String server, ModelUsageRecord sum) {}

    /** A kind of lookup, for the columns of the full lookup table. */
    public record Kind(String name, String label, boolean structured) {}

    // --- model calls ----------------------------------------------------------------------------

    /**
     * One row per role and class of server, roles in the order they first appear, and within a
     * role the paid server first.
     */
    public static List<Row> rowsOf(Collection<ModelUsageRecord> records) {
        Map<String, ModelUsageRecord> paid = new LinkedHashMap<>();
        Map<String, ModelUsageRecord> local = new LinkedHashMap<>();
        Set<String> roles = new LinkedHashSet<>();
        for (ModelUsageRecord record : records) {
            String role = record.role() == null ? "untagged" : record.role();
            roles.add(role);
            (record.paid() ? paid : local)
                .computeIfAbsent(role, r -> new ModelUsageRecord(r, null, null,
                    record.paid() ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL))
                .add(record);
        }
        List<Row> rows = new ArrayList<>();
        for (String role : roles) {
            if (paid.containsKey(role)) {
                rows.add(new Row(role, PAID, paid.get(role)));
            }
            if (local.containsKey(role)) {
                rows.add(new Row(role, LOCAL, local.get(role)));
            }
        }
        return rows;
    }

    /** The calls of {@code records} added up: all of them, or only the paid or only the local. */
    public static ModelUsageRecord total(Collection<ModelUsageRecord> records, Boolean paid) {
        ModelUsageRecord sum = new ModelUsageRecord("all", null, null,
            paid == null ? null : paid ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL);
        for (ModelUsageRecord record : records) {
            if (paid == null || paid == record.paid()) {
                sum.add(record);
            }
        }
        return sum;
    }

    /**
     * The table: per role its calls, input tokens, input tokens per call, output tokens, input
     * tokens taken from the server's prompt cache and seconds spent in calls, and a last row
     * for all of them.
     */
    public static String roleTable(List<Row> rows, boolean serverColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append(serverColumn ? "| role | server | calls |" : "| role | calls |")
            .append(" input tokens | input tokens per call | output tokens | cached input tokens "
                + "| seconds in calls |\n");
        sb.append(serverColumn ? "|---|---|---|---|---|---|---|---|\n" : "|---|---|---|---|---|---|---|\n");
        ModelUsageRecord all = new ModelUsageRecord("all", null, null, null);
        for (Row row : rows) {
            sb.append("| ").append(row.role()).append(" | ");
            if (serverColumn) {
                sb.append(row.server() == null ? "" : row.server()).append(" | ");
            }
            cells(sb, row.sum());
            all.add(row.sum());
        }
        sb.append("| **all** | ");
        if (serverColumn) {
            sb.append(" | ");
        }
        cells(sb, all);
        return sb.toString();
    }

    private static void cells(StringBuilder sb, ModelUsageRecord sum) {
        sb.append(sum.calls()).append(" | ")
            .append(tokens(sum.calls(), sum.inputCalls(), sum.inputTokens())).append(" | ")
            .append(sum.calls() == 0 ? "0" : sum.inputCalls() == 0 ? NOT_RECORDED
                : String.valueOf(sum.inputTokens() / sum.inputCalls())).append(" | ")
            .append(tokens(sum.calls(), sum.outputCalls(), sum.outputTokens())).append(" | ")
            .append(sum.calls() == 0 ? "0" : sum.cachedCalls() == 0 ? NOT_RECORDED
                : String.valueOf(sum.cachedInputTokens())).append(" | ")
            .append(seconds(sum)).append(" |\n");
    }

    /** The sum the server reported, saying so when some or all calls reported nothing. */
    private static String tokens(int calls, int reported, long sum) {
        if (calls == 0) {
            return "0";
        }
        if (reported == 0) {
            return NOT_RECORDED;
        }
        int without = calls - reported;
        return sum + (without <= 0 ? "" : " (+" + without + " call(s) " + NOT_RECORDED + ")");
    }

    private static String seconds(ModelUsageRecord sum) {
        if (sum.calls() == 0) {
            return "0";
        }
        if (sum.finishedCalls() == 0) {
            return NOT_RECORDED;
        }
        int unfinished = sum.calls() - sum.finishedCalls();
        return String.format(Locale.ROOT, "%.1f", sum.millisInCalls() / 1000.0)
            + (unfinished <= 0 ? "" : " (+" + unfinished + " call(s) with no recorded end)");
    }

    /**
     * One sentence of totals, paid and local apart, input and output apart:
     * "Paid cloud: N in / N out over K calls; local: N in / N out".
     */
    public static String spendLine(Collection<ModelUsageRecord> records) {
        ModelUsageRecord paid = total(records, true);
        ModelUsageRecord local = total(records, false);
        return "Paid cloud: " + paid.inputTokens() + " in / " + paid.outputTokens() + " out over "
            + paid.calls() + " call" + (paid.calls() == 1 ? "" : "s") + "; local: "
            + local.inputTokens() + " in / " + local.outputTokens() + " out";
    }

    // --- lookups --------------------------------------------------------------------------------

    /**
     * What each role looked up, by kind, as a table with one column per kind: how often, and how
     * many characters came back into its conversation. A role whose input is mostly text search
     * and whole files is not learning the project from its syntax tree (CLAUDE.md section 1).
     */
    public static String lookupTable(List<LookupUsageRecord> counts, List<Kind> kinds) {
        StringBuilder sb = new StringBuilder("\n### What each role looked up, by kind\n\n");
        if (counts.isEmpty()) {
            return sb.append(NOT_RECORDED).append(": no lookup was recorded in this run.\n")
                .toString();
        }
        sb.append("| role |");
        for (Kind kind : kinds) {
            sb.append(' ').append(kind.label()).append(" |");
        }
        sb.append(" share of characters from the tree, the language server and document "
            + "sections |\n|---|");
        sb.append("---|".repeat(kinds.size() + 1)).append("\n");
        Set<String> roles = new LinkedHashSet<>();
        counts.forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            sb.append("| ").append(role).append(" |");
            long all = 0;
            long tree = 0;
            for (Kind kind : kinds) {
                int calls = 0;
                long chars = 0;
                for (LookupUsageRecord count : counts) {
                    if (count.role().equals(role) && kind.name().equals(count.kind())) {
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
     * The same counts as one short line per role, naming only the kinds the role used:
     * "planner: tree and graph queries 5/1000; whole files 2/3000; structured 25%".
     */
    public static String lookupLines(List<LookupUsageRecord> counts) {
        if (counts.isEmpty()) {
            return "Lookups: " + NOT_RECORDED + ".\n";
        }
        StringBuilder sb = new StringBuilder("Lookups per role, by kind, as calls/characters "
            + "returned; structured is the share of characters from the tree, the language "
            + "server and document sections:\n");
        Set<String> roles = new LinkedHashSet<>();
        counts.forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            Map<String, long[]> byLabel = new LinkedHashMap<>();
            long all = 0;
            long structured = 0;
            for (LookupUsageRecord count : counts) {
                if (!count.role().equals(role)) {
                    continue;
                }
                long[] sums = byLabel.computeIfAbsent(
                    count.label() == null ? String.valueOf(count.kind()) : count.label(),
                    k -> new long[2]);
                sums[0] += count.calls();
                sums[1] += count.chars();
                all += count.chars();
                structured += count.structured() ? count.chars() : 0;
            }
            sb.append("- ").append(role).append(": ");
            byLabel.forEach((label, sums) -> sb.append(label).append(' ').append(sums[0])
                .append('/').append(sums[1]).append("; "));
            sb.append("structured ").append(all == 0 ? 0 : structured * 100 / all).append("%\n");
        }
        return sb.toString();
    }

    // --- one run, compact -----------------------------------------------------------------------

    /**
     * The whole report of one run, or of several runs added up, as compact text: the totals
     * sentence, the table per role split by class of server, and the lookups per role.
     *
     * @param heading what this is a report of, one line
     */
    public static String render(String heading, Collection<ModelUsageRecord> models,
                                List<LookupUsageRecord> lookups) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append('\n');
        if (models.isEmpty()) {
            sb.append("No model call was recorded: it made none, or it ran before calls were "
                + "recorded one by one.\n");
        } else {
            sb.append(spendLine(models)).append(".\n\n");
            sb.append(roleTable(rowsOf(models), true));
            sb.append("\nCounts are each call's own usage as the model server reported it; a "
                + "session's calls are counted one by one. Input per call is what one call was "
                + "sent on average: the whole conversation goes again on every call.\n");
            int estimated = total(models, null).estimatedCalls();
            if (estimated > 0) {
                sb.append(estimated).append(" call(s) were counted from the size of their text "
                    + "(characters / 4) because the server reported no usage.\n");
            }
        }
        sb.append('\n').append(lookupLines(lookups));
        return sb.toString();
    }

    /** The lookups of several runs added up, one record per role and kind. */
    public static List<LookupUsageRecord> lookupsAdded(Collection<RunUsageRecord> runs) {
        Map<String, LookupUsageRecord> sums = new LinkedHashMap<>();
        for (RunUsageRecord run : runs) {
            for (LookupUsageRecord lookup : run.lookups()) {
                LookupUsageRecord sum = sums.computeIfAbsent(lookup.role() + "\u0000"
                    + lookup.kind(), k -> new LookupUsageRecord(lookup.role(), lookup.kind(),
                        lookup.label(), lookup.structured(), 0, 0));
                sum.add(lookup.calls(), lookup.chars());
            }
        }
        return new ArrayList<>(sums.values());
    }

    /** The model rows of several runs, as one list. */
    public static List<ModelUsageRecord> modelsOf(Collection<RunUsageRecord> runs) {
        List<ModelUsageRecord> all = new ArrayList<>();
        runs.forEach(run -> all.addAll(run.models()));
        return all;
    }
}
