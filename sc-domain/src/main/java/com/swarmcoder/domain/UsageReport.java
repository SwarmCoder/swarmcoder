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
    public static final class Row {
        private final String role;
        private final String server;
        private final ModelUsageRecord sum;

        public Row(String role, String server, ModelUsageRecord sum) {
            this.role = role;
            this.server = server;
            this.sum = sum;
        }

        public String role() { return role; }
        public String server() { return server; }
        public ModelUsageRecord sum() { return sum; }
    }

    /** A kind of lookup, for the columns of the full lookup table. */
    public static final class Kind {
        private final String name;
        private final String label;
        private final boolean structured;

        public Kind(String name, String label, boolean structured) {
            this.name = name;
            this.label = label;
            this.structured = structured;
        }

        public String name() { return name; }
        public String label() { return label; }
        public boolean structured() { return structured; }
    }

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

    // --- each role's calls, one by one ----------------------------------------------------------

    /**
     * What one role's calls on one class of server came to: the figures of the per-role table
     * of the report and of the Console, worked out in one place so the two cannot differ.
     * A figure nothing recorded is -1.
     *
     * @param medianInput    the input tokens of the role's middle call, from the calls kept one
     *                       by one; -1 when none were
     * @param largestInput   the most input tokens one call was sent
     * @param largestPurpose what that call was for, or "" when calls were not kept one by one
     * @param thinking       how many of {@code output} were thinking
     * @param oneByOne       how many of {@code calls} were kept one by one
     */
    public static final class RoleFigures {
        private final String role;
        private final String server;
        private final int calls;
        private final int failed;
        private final long input;
        private final long cachedInput;
        private final long output;
        private final long thinking;
        private final long medianInput;
        private final long largestInput;
        private final String largestPurpose;
        private final int oneByOne;
        private final double seconds;

        public RoleFigures(String role, String server, int calls, int failed, long input, long cachedInput, long output, long thinking, long medianInput, long largestInput, String largestPurpose, int oneByOne, double seconds) {
            this.role = role;
            this.server = server;
            this.calls = calls;
            this.failed = failed;
            this.input = input;
            this.cachedInput = cachedInput;
            this.output = output;
            this.thinking = thinking;
            this.medianInput = medianInput;
            this.largestInput = largestInput;
            this.largestPurpose = largestPurpose;
            this.oneByOne = oneByOne;
            this.seconds = seconds;
        }

        public String role() { return role; }
        public String server() { return server; }
        public int calls() { return calls; }
        public int failed() { return failed; }
        public long input() { return input; }
        public long cachedInput() { return cachedInput; }
        public long output() { return output; }
        public long thinking() { return thinking; }
        public long medianInput() { return medianInput; }
        public long largestInput() { return largestInput; }
        public String largestPurpose() { return largestPurpose; }
        public int oneByOne() { return oneByOne; }
        public double seconds() { return seconds; }
    }

    /** One {@link RoleFigures} per role and class of server, in the order of {@link #rowsOf}. */
    public static List<RoleFigures> figuresOf(Collection<ModelUsageRecord> models,
                                              List<CallUsageRecord> calls) {
        List<RoleFigures> figures = new ArrayList<>();
        for (Row row : rowsOf(models)) {
            ModelUsageRecord sum = row.sum();
            List<Long> inputs = new ArrayList<>();
            long largest = sum.inputCalls() == 0 ? -1 : sum.largestInput();
            String purpose = "";
            long largestSeen = -1;
            int oneByOne = 0;
            for (CallUsageRecord call : calls == null ? List.<CallUsageRecord>of() : calls) {
                String role = call.role() == null ? "untagged" : call.role();
                if (!role.equals(row.role()) || call.paid() != sum.paid()) {
                    continue;
                }
                oneByOne++;
                if (call.input() >= 0) {
                    inputs.add(call.input());
                    if (call.input() > largestSeen) {
                        largestSeen = call.input();
                        purpose = call.purpose() == null ? "" : call.purpose();
                    }
                }
            }
            inputs.sort(null);
            figures.add(new RoleFigures(row.role(), row.server(), sum.calls(), sum.failedCalls(),
                sum.inputCalls() == 0 ? -1 : sum.inputTokens(),
                sum.cachedCalls() == 0 ? -1 : sum.cachedInputTokens(),
                sum.outputCalls() == 0 ? -1 : sum.outputTokens(),
                sum.thinkingCalls() == 0 ? -1 : sum.thinkingTokens(),
                inputs.isEmpty() ? -1 : inputs.get((inputs.size() - 1) / 2),
                Math.max(largest, largestSeen), purpose, oneByOne,
                sum.finishedCalls() == 0 ? -1 : sum.millisInCalls() / 1000.0));
        }
        return figures;
    }

    /**
     * The second table of the report: per role its middle call and its largest, what the
     * largest was for, and how much of what it wrote was thinking. "" when there is no row.
     */
    public static String callTable(List<RoleFigures> figures) {
        if (figures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("| role | server | calls kept one by one | median "
            + "input tokens of a call | largest input of one call | the largest call was for | "
            + "thinking tokens (part of output) | calls without an answer |\n"
            + "|---|---|---|---|---|---|---|---|\n");
        for (RoleFigures row : figures) {
            sb.append("| ").append(row.role()).append(" | ").append(row.server()).append(" | ")
                .append(row.oneByOne()).append(" of ").append(row.calls()).append(" | ")
                .append(orNot(row.medianInput())).append(" | ")
                .append(orNot(row.largestInput())).append(" | ")
                .append(row.largestPurpose().isEmpty() ? NOT_RECORDED : row.largestPurpose())
                .append(" | ").append(orNot(row.thinking())).append(" | ")
                .append(row.failed()).append(" |\n");
        }
        return sb.toString();
    }

    private static String orNot(long figure) {
        return figure < 0 ? NOT_RECORDED : String.valueOf(figure);
    }

    /**
     * How much of the candidates' work was used: the tokens of the calls made for a candidate
     * that was chosen, against the tokens of the calls made for any candidate. One sentence,
     * or "" when no call named a candidate.
     *
     * <p>It is a measure of what the work came to, not of waste: a candidate that was not
     * chosen is not waste if its place on the server would otherwise have been empty
     * (CLAUDE.md section 0).
     *
     * @param chosen the ids, as text, of the candidates that were chosen
     * @param judged the ids of every candidate whose end is known, the chosen among them
     */
    public static String usedLine(List<CallUsageRecord> calls, Set<String> chosen,
                                  Set<String> judged) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> used = new LinkedHashSet<>();
        Set<String> open = new LinkedHashSet<>();
        long[] ofAll = new long[3];
        long[] ofUsed = new long[3];
        for (CallUsageRecord call : calls == null ? List.<CallUsageRecord>of() : calls) {
            if (call.candidate() == null) {
                continue;
            }
            all.add(call.candidate());
            add(ofAll, call);
            if (chosen.contains(call.candidate())) {
                used.add(call.candidate());
                add(ofUsed, call);
            } else if (!judged.contains(call.candidate())) {
                open.add(call.candidate());
            }
        }
        if (all.isEmpty()) {
            return "";
        }
        return "Work that was used: the " + used.size() + " candidate(s) that were chosen took "
            + ofUsed[1] + " input / " + ofUsed[2] + " output tokens in " + ofUsed[0]
            + " calls; all " + all.size() + " candidate(s) took " + ofAll[1] + " / " + ofAll[2]
            + " in " + ofAll[0] + " calls. " + (ofAll[1] == 0 ? 0 : ofUsed[1] * 100 / ofAll[1])
            + "% of the candidates' input tokens went into work that was chosen."
            + (open.isEmpty() ? "" : " " + open.size() + " candidate(s) have no known end yet.")
            + "\n";
    }

    private static void add(long[] sums, CallUsageRecord call) {
        sums[0]++;
        sums[1] += Math.max(0, call.input());
        sums[2] += Math.max(0, call.output());
    }

    // --- warnings -------------------------------------------------------------------------------

    /**
     * A paid role is warned about when its calls were sent more input tokens than this each, on
     * average. A role's opening, its tool descriptions and the lookups it is working from fit in
     * well under this; above it the conversation is carrying what it no longer needs
     * (section 94: 75,500 and 83,600 a call went unnoticed for a whole build).
     */
    public static final int WARN_INPUT_PER_CALL = 30_000;

    /**
     * How many calls a role's work in one run needs at most, for the roles whose work has a
     * known size; a paid role above its figure is warned about. The architect designs once and
     * answers a few revisions; the planner splits and orders a design; the reviewer reads a
     * design, a plan and tests against the rules; the test author writes and compiles a test a
     * task. The reviewer's figure here is that of {@link ReviewerMode#LOOKUPS}; the warning uses
     * {@link ReviewerMode#expectedCalls()} of the mode in force (section 96). Roles not named here (workers, the expert, the judge) make as many calls as there
     * is work, and are judged by input per call alone.
     */
    public static final Map<String, Integer> WARN_CALLS = Map.of(
        "architect", 40, "planner", 15, "reviewer", 16, "test author", 40);

    /**
     * Fewer characters of request than this for each input token the server counted, and the
     * server counted more than the request holds. English prose and source code run at three to
     * four characters a token and a request's JSON adds to the characters, not to the tokens.
     */
    public static final double WARN_CHARS_PER_INPUT_TOKEN = 2.5;

    /**
     * One line per thing a reader must not miss, each beginning "WARNING:", or "" when there is
     * none: a role whose calls were too large, a role that made more calls than its work needs,
     * and a server that counted more input than its requests held.
     *
     * <p>The same rules hold on a paid server and on the operator's own (section 102: a local
     * build of 87 million tokens was never warned of anything). On the local server the words
     * say what is lost there: not money, but the server's places and the time the calls took
     * (CLAUDE.md section 0).
     */
    public static String warnings(Collection<ModelUsageRecord> models) {
        return warnings(models, 1);
    }

    /**
     * As {@link #warnings(Collection)} for the rows of {@code runs} runs added up: the calls a
     * role's work needs are counted once for each run.
     */
    public static String warnings(Collection<ModelUsageRecord> models, int runs) {
        StringBuilder sb = new StringBuilder();
        for (Row row : rowsOf(models)) {
            ModelUsageRecord sum = row.sum();
            boolean paid = sum.paid();
            String where = paid ? "a paid server" : "the local server";
            long perCall = sum.inputCalls() == 0 ? 0 : sum.inputTokens() / sum.inputCalls();
            if (perCall > WARN_INPUT_PER_CALL) {
                sb.append("WARNING: the ").append(row.role()).append(" was sent ").append(perCall)
                    .append(" input tokens a call on ").append(where).append(" (")
                    .append(sum.inputCalls()).append(" calls, the largest ")
                    .append(sum.largestInput()).append("); above ").append(WARN_INPUT_PER_CALL)
                    .append(" a conversation is carrying what it no longer needs")
                    .append(paid ? "" : ", and every call holds one of the server's places "
                        + "for longer").append(".\n");
            }
            Integer needs = "reviewer".equals(row.role())
                ? Integer.valueOf(ReviewerMode.current().expectedCalls())
                : WARN_CALLS.get(row.role());
            if (needs != null && sum.calls() > (long) needs * Math.max(1, runs)) {
                sb.append("WARNING: the ").append(row.role()).append(" made ").append(sum.calls())
                    .append(" calls on ").append(where).append("; its work in one run needs at "
                        + "most ").append(needs)
                    .append(runs > 1 ? ", and these are " + runs + " runs" : "")
                    .append(paid ? "" : ". Local tokens are not money: what was lost is the "
                        + "places and the time those calls took").append(".\n");
            }
            if (sum.sizedCalls() > 0 && sum.sizedInputTokens() > 0
                    && sum.requestChars() < WARN_CHARS_PER_INPUT_TOKEN * sum.sizedInputTokens()) {
                sb.append("WARNING: the ").append(paid ? "paid" : "local")
                    .append(" server counted ").append(sum.sizedInputTokens())
                    .append(" input tokens for the ").append(row.role())
                    .append("'s requests of ").append(sum.requestChars())
                    .append(" characters in all (").append(perToken(sum))
                    .append(" characters a token): it counts something the requests do not "
                        + "hold, such as the model's own earlier thinking.\n");
            }
        }
        return sb.toString();
    }

    /**
     * A paid role is warned about when whole files were more than this share, in percent, of
     * the characters its lookups returned. A whole file is the last resort (CLAUDE.md section
     * 1); in the build of section 94 whole files were 51% of the architect's characters.
     */
    public static final int WARN_WHOLE_FILE_SHARE = 25;

    /**
     * A paid role is warned about when lookups about the reference material - the source and
     * documents of what the project builds on - returned more than this share, in percent, of
     * its characters: it is learning the framework, not designing or checking this project. In
     * the build of section 94 it was 63% for the architect and 61% for the reviewer.
     */
    public static final int WARN_REFERENCE_SHARE = 50;

    /** Below this many characters looked up, a role's shares say nothing and are not judged. */
    public static final int WARN_SHARES_FROM_CHARS = 20_000;

    /** The kind whose characters are whole files, as {@code LookupMeter} names it. */
    public static final String WHOLE_FILE_KIND = "WHOLE_FILE";

    /**
     * A role is warned about when more than this share, in percent, of its lookups asked a
     * question a lookup had already answered earlier in the same run, to it or to another
     * role. In the five-story local build of section 102, 78% of all lookups were such repeats.
     */
    public static final int WARN_REPEAT_SHARE = 40;

    /** Below this many lookups, a role's share of repeats says nothing and is not judged. */
    public static final int WARN_REPEATS_FROM_LOOKUPS = 20;

    /**
     * {@link #warnings(Collection)} and, for each role, one line when whole files, reference
     * material or repeated questions were more than their stated share of what its lookups
     * returned (sections 95 and 98). A role on the local server is judged as one on a paid
     * server is.
     */
    public static String warnings(Collection<ModelUsageRecord> models,
                                  List<LookupUsageRecord> lookups) {
        return warnings(models, lookups, 1);
    }

    /** As {@link #warnings(Collection, List)} for the rows of {@code runs} runs added up. */
    public static String warnings(Collection<ModelUsageRecord> models,
                                  List<LookupUsageRecord> lookups, int runs) {
        StringBuilder sb = new StringBuilder(warnings(models, runs));
        Map<String, Boolean> paidOf = new LinkedHashMap<>();
        for (Row row : rowsOf(models)) {
            paidOf.merge(row.role(), row.sum().paid(), (a, b) -> a || b);
        }
        for (Map.Entry<String, Boolean> entry : paidOf.entrySet()) {
            String role = entry.getKey();
            String where = entry.getValue() ? "a paid server" : "the local server";
            long all = 0;
            int allCalls = 0;
            long whole = 0;
            int wholeCalls = 0;
            long reference = 0;
            int referenceCalls = 0;
            int repeats = 0;
            long repeatChars = 0;
            for (LookupUsageRecord count : lookups == null ? List.<LookupUsageRecord>of() : lookups) {
                if (!role.equals(count.role())) {
                    continue;
                }
                all += count.chars();
                allCalls += count.calls();
                reference += count.referenceChars();
                referenceCalls += count.referenceCalls();
                repeats += count.repeatCalls();
                repeatChars += count.repeatChars();
                if (WHOLE_FILE_KIND.equals(count.kind())) {
                    whole += count.chars();
                    wholeCalls += count.calls();
                }
            }
            if (allCalls >= WARN_REPEATS_FROM_LOOKUPS
                    && repeats * 100L > (long) allCalls * WARN_REPEAT_SHARE) {
                sb.append("WARNING: ").append(repeats * 100L / allCalls).append("% of what the ")
                    .append(role).append(" looked up on ").append(where)
                    .append(" had already been answered earlier in the run (").append(repeats)
                    .append(" of ").append(allCalls).append(" lookups, ").append(repeatChars)
                    .append(" characters); above ").append(WARN_REPEAT_SHARE)
                    .append("% the roles are not handing on what they find.\n");
            }
            if (all < WARN_SHARES_FROM_CHARS) {
                continue;
            }
            if (whole * 100 > all * WARN_WHOLE_FILE_SHARE) {
                sb.append("WARNING: whole files were ").append(whole * 100 / all)
                    .append("% of what the ").append(role).append(" looked up on ").append(where)
                    .append(" (").append(wholeCalls).append(" files, ").append(whole)
                    .append(" of ").append(all).append(" characters); above ")
                    .append(WARN_WHOLE_FILE_SHARE)
                    .append("% a query is missing or is not being used.\n");
            }
            if (reference * 100 > all * WARN_REFERENCE_SHARE) {
                sb.append("WARNING: reference material was ").append(reference * 100 / all)
                    .append("% of what the ").append(role).append(" looked up on ").append(where)
                    .append(" (").append(referenceCalls).append(" lookups, ").append(reference)
                    .append(" of ").append(all).append(" characters); above ")
                    .append(WARN_REFERENCE_SHARE)
                    .append("% the role is learning what the project builds on, not the "
                        + "project.\n");
            }
        }
        return sb.toString();
    }

    private static String perToken(ModelUsageRecord sum) {
        return String.format(Locale.ROOT, "%.1f",
            sum.requestChars() / (double) sum.sizedInputTokens());
    }

    /**
     * What the requests of each role were made of, one line a role, for the rows that recorded
     * it: characters sent for each input token counted, and how much of each request repeated
     * the session's request before it from its first character - the part a server's prompt
     * cache can serve. "" when no row recorded a request's size.
     */
    public static String requestLines(Collection<ModelUsageRecord> models) {
        StringBuilder sb = new StringBuilder();
        for (Row row : rowsOf(models)) {
            ModelUsageRecord sum = row.sum();
            if (sum.sizedCalls() == 0 || sum.sizedInputTokens() == 0) {
                continue;
            }
            sb.append("- ").append(row.role()).append(" (").append(row.server()).append("): ")
                .append(sum.requestChars()).append(" characters sent for ")
                .append(sum.sizedInputTokens()).append(" input tokens counted (")
                .append(perToken(sum)).append(" a token)");
            if (sum.repeatCalls() > 0 && sum.repeatRequestChars() > 0) {
                sb.append("; ").append(sum.samePrefixChars() * 100 / sum.repeatRequestChars())
                    .append("% of a request repeated the one before it from the start");
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? "" : "Requests per role, as they went out:\n" + sb;
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
            + "sections | of all these, about the reference material |\n|---|");
        sb.append("---|".repeat(kinds.size() + 2)).append("\n");
        Set<String> roles = new LinkedHashSet<>();
        counts.forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            sb.append("| ").append(role).append(" |");
            long all = 0;
            long tree = 0;
            int referenceCalls = 0;
            long reference = 0;
            for (Kind kind : kinds) {
                int calls = 0;
                long chars = 0;
                for (LookupUsageRecord count : counts) {
                    if (count.role().equals(role) && kind.name().equals(count.kind())) {
                        calls += count.calls();
                        chars += count.chars();
                        referenceCalls += count.referenceCalls();
                        reference += count.referenceChars();
                    }
                }
                all += chars;
                tree += kind.structured() ? chars : 0;
                sb.append(' ').append(calls == 0 ? "0" : calls + " call(s), " + chars + " chars")
                    .append(" |");
            }
            sb.append(' ').append(all == 0 ? "0%" : (tree * 100 / all) + "%").append(" | ")
                .append(referenceCalls == 0 ? "0" : referenceCalls + " call(s), " + reference
                    + " chars, " + (all == 0 ? 0 : reference * 100 / all) + "%").append(" |\n");
        }
        sb.append("\nCharacters are what came back into the role's conversation, where every "
            + "later call sends them again. The last column is the lookups of every kind that "
            + "were about the reference material - the source and documents of what the "
            + "project builds on - and not about the project; a worker's are not told apart. "
            + "Workers are one row together. An acceptance-test read is a worker's acceptance_test call. A journey check is the test author's check_journey call. A shell read is a "
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
            + "server and document sections; reference is what was about the reference "
            + "material and not the project; repeats are lookups that asked what a lookup had "
            + "already answered earlier in the run, to this role or another:\n");
        Set<String> roles = new LinkedHashSet<>();
        counts.forEach(c -> roles.add(c.role()));
        for (String role : roles) {
            Map<String, long[]> byLabel = new LinkedHashMap<>();
            long all = 0;
            long structured = 0;
            int referenceCalls = 0;
            long reference = 0;
            int calls = 0;
            int repeats = 0;
            long repeatChars = 0;
            for (LookupUsageRecord count : counts) {
                if (!count.role().equals(role)) {
                    continue;
                }
                calls += count.calls();
                repeats += count.repeatCalls();
                repeatChars += count.repeatChars();
                referenceCalls += count.referenceCalls();
                reference += count.referenceChars();
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
            sb.append("structured ").append(all == 0 ? 0 : structured * 100 / all).append('%');
            if (referenceCalls > 0) {
                sb.append("; reference ").append(referenceCalls).append('/').append(reference)
                    .append(" (").append(all == 0 ? 0 : reference * 100 / all).append("%)");
            }
            if (repeats > 0) {
                sb.append("; repeats ").append(repeats).append('/').append(repeatChars)
                    .append(" (").append(calls == 0 ? 0 : repeats * 100L / calls)
                    .append("% of its ").append(calls).append(" lookups)");
            }
            sb.append('\n');
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
        return render(heading, models, lookups, List.of(), "", 1);
    }

    /**
     * As {@link #render(String, Collection, List)}, with what the calls kept one by one add:
     * each role's middle and largest call and its thinking, and how much of the work was used.
     *
     * @param calls the calls one by one, or empty when they were not kept
     * @param used  what came of the work, as lines ({@link #usedLine} and the like), or ""
     * @param runs  how many runs these figures are of
     */
    public static String render(String heading, Collection<ModelUsageRecord> models,
                                List<LookupUsageRecord> lookups, List<CallUsageRecord> calls,
                                String used, int runs) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append('\n');
        if (models.isEmpty()) {
            sb.append("No model call was recorded: it made none, or it ran before calls were "
                + "recorded one by one.\n");
        } else {
            sb.append(spendLine(models)).append(".\n\n");
            sb.append(warnings(models, lookups, runs));
            sb.append(roleTable(rowsOf(models), true));
            sb.append("\nCounts are each call's own usage as the model server reported it; a "
                + "session's calls are counted one by one. Input per call is what one call was "
                + "sent on average: the whole conversation goes again on every call.\n");
            int estimated = total(models, null).estimatedCalls();
            if (estimated > 0) {
                sb.append(estimated).append(" call(s) were counted from the size of their text "
                    + "(characters / 4) because the server reported no usage.\n");
            }
            sb.append('\n').append(callTable(figuresOf(models, calls)));
            int guessed = total(models, null).estimatedThinkingCalls();
            if (guessed > 0) {
                sb.append("The thinking of ").append(guessed).append(" call(s) is the length of "
                    + "the thinking text / 4: the server gave no count of it.\n");
            }
            if (used != null && !used.isBlank()) {
                sb.append('\n').append(used);
            }
            String requests = requestLines(models);
            if (!requests.isEmpty()) {
                sb.append('\n').append(requests);
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
                sum.addReference(lookup.referenceCalls(), lookup.referenceChars());
                sum.addRepeat(lookup.repeatCalls(), lookup.repeatChars());
            }
        }
        return new ArrayList<>(sums.values());
    }

    /** The calls of several runs one by one, as one list. */
    public static List<CallUsageRecord> callsOf(Collection<RunUsageRecord> runs) {
        List<CallUsageRecord> all = new ArrayList<>();
        runs.forEach(run -> all.addAll(run.calls()));
        return all;
    }

    /** The model rows of several runs, as one list. */
    public static List<ModelUsageRecord> modelsOf(Collection<RunUsageRecord> runs) {
        List<ModelUsageRecord> all = new ArrayList<>();
        runs.forEach(run -> all.addAll(run.models()));
        return all;
    }
}
