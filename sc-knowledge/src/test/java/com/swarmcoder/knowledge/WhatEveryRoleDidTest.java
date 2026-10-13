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
package com.swarmcoder.knowledge;

import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import ai.koog.prompt.message.RequestMetaInfo;
import ai.koog.prompt.message.ResponseMetaInfo;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.runtime.HistoryTrim;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads EVERY stored session of a COPY of an instance's store, the workers' included, and says
 * per role what its calls were: how many and how large, what each was for, what it looked up by
 * kind and how often it or another role had looked the same thing up before, what a request was
 * made of, and what came of the session. Then the same across roles and stories. No model,
 * nothing written to the store.
 *
 * <p>What is exact and what is not: a call's size (input and output together) is the server's
 * own count, kept on each stored answer. The output is estimated from what the model wrote (its
 * thinking as the server counted it, its text and its tool arguments at four characters a
 * token); the time of a call is the time between the stored event before the answer and the
 * answer. A planning session names no run and is given to the run that had started last when it
 * opened. What a request was made of is rebuilt by replaying the stored calls through the
 * product's tidy with today's settings, as {@link WhatARolesCallsWereForTest} does.
 *
 * <p>Does nothing unless told where the copy is ({@code swarmcoder.sessions.copy});
 * {@code swarmcoder.sessions.referenceRoots} and {@code swarmcoder.sessions.report} as in
 * {@link WhatStoredSessionsLookedUpTest}; {@code swarmcoder.sessions.projectPackage} is the
 * project's own Java package, so that a type outside it counts as reference material. Never
 * point it at a live store. The figures of one build are in docs/AGENT_USAGE_AUDIT.md.
 */
class WhatEveryRoleDidTest {

    private static final Pattern FIRST_ARGUMENT = Pattern.compile("\"[A-Za-z_]+\"\\s*:\\s*\"");
    private static final Pattern THOUGHT = Pattern.compile("^\\[thought ~(\\d+) tokens]\\n?");
    private static final Pattern VERDICT = Pattern.compile(
        "\"(approved|verdict)\"\\s*:\\s*\"([A-Za-z_]+)\"");
    private static final Pattern QUALIFIED = Pattern.compile(
        "[a-z]\\w*(\\.[\\w$]+)+(#.*)?");
    private static final Pattern PART = Pattern.compile(
        "^(.*?)(?::(\\d{1,7})(?:-(\\d{1,7}))?|#([A-Za-z_$][\\w$]*)(?:\\(.*\\))?)$");
    private static final Pattern NUMBER_ARGUMENT = Pattern.compile(
        "\"(?:from|to|start|end|offset|limit|lines|line|member)\"\\s*:\\s*\"?[\\w$]");
    private static final Set<String> WORDS = Set.of("search", "docs_for", "doc_search",
        "find_example", "lookup_docs", "library_docs", "find_symbol", "types_annotated_with",
        "files_using", "call_chain", "list_files", "ask_expert", "dependency_declaring",
        "lookup_api", "exec");
    private static final Set<String> CHECKS = Set.of("check_design", "check_plan", "compile_test",
        "check_journey", "problems_in");
    private static final Set<String> WRITES = Set.of("write_file", "apply_diff", "add_member",
        "replace_member", "remove_member", "organize_imports", "keep_for_workers");
    private static final Set<String> WORKER_TREE = Set.of("shape_of", "body_of", "types_in",
        "usages_of", "implementations_of", "build_of", "resources_of");
    private static final Set<String> WORKER_SERVER = Set.of("find_symbol", "supertypes_of",
        "doc_of");
    private static final String[] GROUPS = {"learning the reference material",
        "reading the project", "reading its own earlier output", "drafting or editing",
        "checking a draft", "revising after an objection", "running builds or tests",
        "waiting on a question", "handing in", "no tool call"};
    private static final String[] KINDS = {"tree and graph", "language server",
        "document sections", "search", "whole files", "parts of files", "folder listings",
        "shell reads", "acceptance test", "known findings"};
    private static final String[] BEFORE = {"first time", "again in the same session",
        "a sibling candidate of the same task", "another session of the same role, same run",
        "another role in the same run", "an earlier run of the same story", "an earlier story"};

    private static final class Call {
        String tool;
        String arguments;
        String argument;
        String result;
        int kind = -1;
        boolean reference;
        String target;
        long waitedMs;
    }

    private static final class Turn {
        Instant at;
        long millis;
        long tokens;
        long thought;
        long written;
        String text = "";
        final List<Call> calls = new ArrayList<>();
        boolean newMessage;
        boolean continued;
        int group;
    }

    private static final class Session {
        String id;
        String raw;
        String role;
        boolean worker;
        boolean planning;
        Instant opened;
        UUID run;
        UUID task;
        UUID candidate;
        String outcome;
        String kill;
        String opening = "";
        String story = "?";
        String runState = "?";
        String candidateState = "";
        final List<Turn> turns = new ArrayList<>();

        long tokens() {
            return turns.stream().mapToLong(t -> t.tokens).sum();
        }
    }

    private static final class Sum {
        long sessions;
        long calls;
        long tokens;
        long output;
        long thought;
        long millis;
        final List<Long> sizes = new ArrayList<>();
        final long[][] groups = new long[GROUPS.length][2];
        final long[][] kinds = new long[KINDS.length][4];
        final long[] sameCall = new long[3];
        final List<Long> firstCalls = new ArrayList<>();
        final long[][] before = new long[BEFORE.length][4];
        long afterCut;
        long afterCutChars;
        long whileWhole;
        long whileWholeChars;
        final long[] parts = new long[11];
        long replayedCalls;
        long tidies;
        final Map<String, long[]> verdicts = new TreeMap<>();
        final long[] asked = new long[3];
        long openingChars;
        long systemChars;
        final Set<String> systems = new HashSet<>();
        final Map<String, long[]> outcomes = new TreeMap<>();
        final Map<String, long[]> tools = new TreeMap<>();
    }

    @Test
    void report() throws Exception {
        String copy = setting("swarmcoder.sessions.copy");
        if (copy.isBlank()) {
            return;
        }
        List<String> reference = new ArrayList<>();
        for (String label : setting("swarmcoder.sessions.referenceRoots").split(",")) {
            if (!label.isBlank()) {
                reference.add(label.strip());
            }
        }
        Path dir = Path.of(copy);
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        List<Session> sessions = new ArrayList<>();
        Map<UUID, String> storyOfRun = new HashMap<>();
        Map<UUID, String> stateOfRun = new HashMap<>();
        TreeMap<Instant, UUID> runsByStart = new TreeMap<>();
        StringBuilder out = new StringBuilder();
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            Map<UUID, String> keyOfStory = new HashMap<>();
            for (Story story : store.root().stories().values()) {
                keyOfStory.put(story.getId(), story.getKey());
            }
            for (Run run : store.root().runs.values()) {
                storyOfRun.put(run.getId(), keyOfStory.getOrDefault(run.getStoryId(), "?"));
                stateOfRun.put(run.getId(), String.valueOf(run.getState()));
                if (run.getStartedAt() != null) {
                    runsByStart.put(run.getStartedAt(), run.getId());
                }
            }
            Map<UUID, String> stateOfCandidate = new HashMap<>();
            for (Lazy<Object> lazy : new ArrayList<>(store.root().candidateArchives.values())) {
                if (Lazy.get(lazy) instanceof CandidateSolution candidate) {
                    stateOfCandidate.put(candidate.getId(), candidate.getState()
                        + (candidate.getKillReason() == null ? ""
                            : " " + candidate.getKillReason()));
                }
            }
            for (Lazy<Object> lazy : new ArrayList<>(store.root().agentSessions().values())) {
                if (Lazy.get(lazy) instanceof AgentSessionRecord record && record.events() != null
                        && record.role() != null) {
                    Session session = read(record, blobs);
                    UUID run = session.run;
                    if (run == null) {
                        Map.Entry<Instant, UUID> last = runsByStart.floorEntry(session.opened);
                        run = last == null ? null : last.getValue();
                        session.run = run;
                    }
                    session.story = storyOfRun.getOrDefault(run, "before any run");
                    session.runState = stateOfRun.getOrDefault(run, "?");
                    session.candidateState = session.candidate == null ? ""
                        : stateOfCandidate.getOrDefault(session.candidate, "not kept");
                    sessions.add(session);
                }
            }
        }
        sessions.sort(Comparator.comparing(s -> s.opened));
        for (Session session : sessions) {
            classify(session, reference);
        }
        samples(out, sessions);
        Map<String, Sum> byRole = new LinkedHashMap<>();
        Map<String, long[]> byRoleStory = new TreeMap<>();
        Map<String, long[]> byRun = new LinkedHashMap<>();
        repeats(sessions);
        Map<String, Sum> order = new TreeMap<>();
        for (Session session : sessions) {
            Sum sum = order.computeIfAbsent(session.role, r -> new Sum());
            add(sum, session);
            long[] cell = byRoleStory.computeIfAbsent(session.role + " | " + session.story,
                k -> new long[3]);
            cell[0]++;
            cell[1] += session.turns.size();
            cell[2] += session.tokens();
            long[] run = byRun.computeIfAbsent(session.story + " run " + shortId(session.run)
                + " " + session.runState, k -> new long[3]);
            run[0]++;
            run[1] += session.turns.size();
            run[2] += session.tokens();
        }
        byRole.putAll(order);
        for (Map.Entry<String, Sum> e : byRole.entrySet()) {
            role(out, e.getKey(), e.getValue());
        }
        out.append("\n# role by story: sessions, calls, tokens\n");
        byRoleStory.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2]).append('\n'));
        out.append("\n# by run: sessions, calls, tokens\n");
        byRun.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2]).append('\n'));
        openings(out, sessions);
        workers(out, sessions);
        experts(out, sessions);
        facts(out, sessions);
        places(out, sessions);
        String report = setting("swarmcoder.sessions.report");
        if (!report.isBlank()) {
            Files.writeString(Path.of(report), out.toString());
        } else {
            System.out.println(out);
        }
    }

    // ------------------------------------------------------------------ naming and grouping

    /** The role as the audit names it, from the stored role and the first words it was given. */
    static String roleOf(String raw, int workerIndex, String opening) {
        String start = opening.length() > 400 ? opening.substring(0, 400) : opening;
        if (raw.startsWith("worker")) {
            return workerIndex >= 100 ? "worker, repair round" : "worker, first round";
        }
        if (raw.startsWith("expert")) {
            String asker = raw.contains(" for ") ? raw.substring(raw.indexOf(" for ") + 5)
                .replace("-closing", "") : "worker";
            return "expert for " + asker + (raw.endsWith("-closing") ? " (closing turn)" : "");
        }
        if (raw.startsWith("reviewer")) {
            return start.contains("design reviewer") ? "design reviewer" : "test reviewer";
        }
        if (raw.startsWith("test author")) {
            return start.contains("A journey") ? "test author, journey review"
                : start.contains("Write exe") ? "test author, writing" : "test author, repair";
        }
        return raw.replaceAll("-.*", "");
    }

    private static void classify(Session session, List<String> reference) {
        Set<String> own = new HashSet<>();
        boolean objected = false;
        for (Turn turn : session.turns) {
            long ref = 0;
            long project = 0;
            long ownChars = 0;
            Set<String> tools = new HashSet<>();
            boolean build = false;
            for (Call call : turn.calls) {
                tools.add(call.tool);
                call.kind = kindOf(session.worker, call);
                if (call.tool.equals("exec") && call.kind < 0) {
                    build = true;
                }
                if (call.kind < 0) {
                    continue;
                }
                call.target = targetOf(call.tool, call.argument);
                call.reference = ofReference(call, reference);
                if (own.contains(call.target)) {
                    ownChars += call.result.length();
                } else if (call.reference) {
                    ref += call.result.length();
                } else {
                    project += call.result.length();
                }
            }
            boolean writes = tools.stream().anyMatch(WRITES::contains);
            boolean checks = tools.stream().anyMatch(CHECKS::contains);
            int group;
            if (tools.isEmpty()) {
                group = 9;
            } else if (objected && session.planning) {
                group = 5;
            } else if (writes) {
                group = 3;
            } else if (checks) {
                group = 4;
            } else if (build) {
                group = 6;
            } else if (tools.contains("ask_expert")) {
                group = 7;
            } else if (tools.contains("report_done")) {
                group = 8;
            } else if (ownChars > ref + project) {
                group = 2;
            } else {
                group = ref > project ? 0 : 1;
            }
            turn.group = group;
            objected |= turn.continued;
            for (Call call : turn.calls) {
                if (WRITES.contains(call.tool) && !call.tool.equals("keep_for_workers")) {
                    own.add(targetOf("read_file", call.argument));
                }
            }
        }
    }

    /** The kind of a lookup as an index of KINDS; -1 when the call is not a lookup. */
    private static int kindOf(boolean worker, Call call) {
        LookupMeter.Kind kind = ExpertTools.kindOf(call.tool, call.argument);
        if (kind == null && call.tool.equals("read")) {
            Matcher part = PART.matcher(call.argument.strip());
            boolean isPart = part.matches() && (part.group(2) != null || part.group(4) != null)
                || NUMBER_ARGUMENT.matcher(call.arguments).find();
            return isPart ? 5 : 4;
        }
        if (kind == null && call.tool.equals("exec")) {
            return LookupMeter.ofShellCommand(call.argument) != null ? 7 : -1;
        }
        if (kind == null && worker) {
            return WORKER_TREE.contains(call.tool) ? 0 : WORKER_SERVER.contains(call.tool) ? 1
                : call.tool.equals("lookup_api") ? 3 : call.tool.equals("acceptance_test") ? 8
                : -1;
        }
        if (kind == null) {
            return -1;
        }
        return switch (kind) {
            case TREE -> 0;
            case LANGUAGE_SERVER -> 1;
            case DOCUMENT -> 2;
            case SEARCH -> 3;
            case WHOLE_FILE -> 4;
            case FILE_PART -> 5;
            case LISTING -> 6;
            case SHELL_READ -> 7;
            case KNOWN -> 9;
            default -> -1;
        };
    }

    /** What a lookup was about: a type, a file, a document, or the words of a question. */
    static String targetOf(String tool, String argument) {
        String a = argument == null ? "" : argument.strip();
        if (a.isEmpty()) {
            return "call: " + tool + " with no argument";
        }
        if (WORDS.contains(tool)) {
            return "words: " + a.toLowerCase(Locale.ROOT);
        }
        int comma = a.indexOf(',');
        a = comma > 0 ? a.substring(0, comma) : a;
        a = a.replaceAll(":\\d+(-\\d+)?$", "");
        int hash = a.indexOf('#');
        a = hash >= 0 ? a.substring(0, hash) : a;
        if (tool.startsWith("doc_") || a.endsWith(".md")) {
            return "document: " + a;
        }
        if (a.contains("/")) {
            String name = a.substring(a.lastIndexOf('/') + 1);
            return name.endsWith(".java") ? "type: " + name.substring(0, name.length() - 5)
                : "file: " + name;
        }
        for (String segment : a.split("\\.")) {
            if (!segment.isEmpty() && Character.isUpperCase(segment.charAt(0))) {
                return "type: " + segment;
            }
        }
        return "module: " + a;
    }

    private static boolean ofReference(Call call, List<String> reference) {
        String argument = call.argument;
        for (String label : reference) {
            if (argument.startsWith(label + "/") || argument.equals(label)) {
                return true;
            }
        }
        if (argument.contains("/") || argument.startsWith("project")) {
            return false;
        }
        String ours = setting("swarmcoder.sessions.projectPackage");
        if (!ours.isBlank() && QUALIFIED.matcher(argument).matches()) {
            return !argument.startsWith(ours);
        }
        int ofReference = 0;
        int ofProject = count(call.result, "project/");
        for (String label : reference) {
            ofReference += count(call.result, label + "/");
        }
        return ofReference > ofProject;
    }

    // ----------------------------------------------------------- what had been looked up

    /** Marks every lookup with where its target had been looked up before, nearest first. */
    private static void repeats(List<Session> sessions) {
        List<String> stories = new ArrayList<>();
        for (Session session : sessions) {
            if (!stories.contains(session.story)) {
                stories.add(session.story);
            }
        }
        Map<String, Set<String>> byTask = new HashMap<>();
        Map<String, Set<String>> byRoleStory = new HashMap<>();
        Map<String, Map<String, Set<String>>> byStory = new HashMap<>();
        Map<String, Set<String>> ofStory = new HashMap<>();
        Map<String, Set<String>> ofRunLater = new LinkedHashMap<>();
        String runNow = null;
        for (Session session : sessions) {
            String run = String.valueOf(session.run);
            if (!run.equals(runNow)) {
                // A run's targets are known to its story once the next run has begun.
                for (Map.Entry<String, Set<String>> done : ofRunLater.entrySet()) {
                    ofStory.computeIfAbsent(done.getKey(), k -> new HashSet<>())
                        .addAll(done.getValue());
                }
                ofRunLater.clear();
                runNow = run;
            }
            Set<String> mine = new HashSet<>();
            String roleStory = session.role + "|" + session.run;
            Set<String> task = byTask.computeIfAbsent(String.valueOf(session.task),
                k -> new HashSet<>());
            Set<String> sameRole = byRoleStory.computeIfAbsent(roleStory, k -> new HashSet<>());
            Map<String, Set<String>> story = byStory.computeIfAbsent(
                String.valueOf(session.run), k -> new HashMap<>());
            Set<String> earlierRuns = ofStory.computeIfAbsent(session.story,
                k -> new HashSet<>());
            Set<String> earlierStories = new HashSet<>();
            for (String earlier : stories) {
                if (earlier.equals(session.story)) {
                    break;
                }
                earlierStories.addAll(ofStory.getOrDefault(earlier, Set.of()));
            }
            List<String> seen = new ArrayList<>();
            for (int t = 0; t < session.turns.size(); t++) {
                for (Call call : session.turns.get(t).calls) {
                    if (call.kind < 0) {
                        continue;
                    }
                    int where = 0;
                    if (mine.contains(call.target)) {
                        where = 1;
                    } else if (session.worker && session.task != null
                            && task.contains(call.target)) {
                        where = 2;
                    } else if (sameRole.contains(call.target)) {
                        where = 3;
                    } else if (story.getOrDefault(call.target, Set.of()).stream()
                            .anyMatch(role -> !role.equals(session.role))) {
                        where = 4;
                    } else if (earlierRuns.contains(call.target)) {
                        where = 5;
                    } else if (earlierStories.contains(call.target)) {
                        where = 6;
                    }
                    call.waitedMs = call.waitedMs + ((long) where << 40);
                    mine.add(call.target);
                    seen.add(call.target);
                }
            }
            // Told to the others only once the session is over: siblings run side by side.
            for (String target : seen) {
                task.add(target);
                sameRole.add(target);
                story.computeIfAbsent(target, k -> new HashSet<>()).add(session.role);
                ofRunLater.computeIfAbsent(session.story, k -> new HashSet<>()).add(target);
            }
        }
    }

    private static int whereOf(Call call) {
        return (int) (call.waitedMs >> 40);
    }

    private static long waitOf(Call call) {
        return call.waitedMs & ((1L << 40) - 1);
    }

    /** How many later calls of its session a result was certainly sent again in: until a tidy. */
    private static int resent(Session session, int turn) {
        int later = 0;
        for (int i = turn + 1; i < session.turns.size(); i++) {
            if (session.turns.get(i).tokens < session.turns.get(i - 1).tokens - 4_000) {
                break;
            }
            later++;
        }
        return later;
    }

    // ----------------------------------------------------------------------- adding up

    private static void add(Sum sum, Session session) throws Exception {
        sum.sessions++;
        int input = session.opening.indexOf("\nINPUT:\n");
        String system = input < 0 ? session.opening : session.opening.substring(0, input);
        sum.openingChars += session.opening.length();
        sum.systemChars += system.length();
        sum.systems.add(system);
        String used = session.worker ? session.candidateState
            : session.runState + (session.kill == null ? "" : ", session " + session.kill);
        long[] outcome = sum.outcomes.computeIfAbsent(used, k -> new long[3]);
        outcome[0]++;
        outcome[1] += session.turns.size();
        outcome[2] += session.tokens();
        Set<String> made = new HashSet<>();
        if (!session.turns.isEmpty()) {
            sum.firstCalls.add(session.turns.get(0).tokens);
        }
        for (int t = 0; t < session.turns.size(); t++) {
            Turn turn = session.turns.get(t);
            sum.calls++;
            sum.tokens += turn.tokens;
            sum.output += turn.thought + turn.written / 4;
            sum.thought += turn.thought;
            sum.millis += turn.millis;
            sum.sizes.add(turn.tokens);
            sum.groups[turn.group][0]++;
            sum.groups[turn.group][1] += turn.tokens;
            int certain = resent(session, t);
            int most = session.turns.size() - 1 - t;
            for (Call call : turn.calls) {
                if (call.tool.equals("report_done")) {
                    Matcher verdict = VERDICT.matcher(call.arguments);
                    if (verdict.find()) {
                        long[] v = sum.verdicts.computeIfAbsent(verdict.group(1) + " "
                            + verdict.group(2), k -> new long[2]);
                        v[0]++;
                        v[1] += call.arguments.length();
                    }
                }
                if (call.tool.equals("ask_expert")) {
                    sum.asked[0]++;
                    boolean wrote = false;
                    boolean askedAgain = false;
                    for (int n = t + 1; n < Math.min(session.turns.size(), t + 6); n++) {
                        for (Call next : session.turns.get(n).calls) {
                            wrote |= WRITES.contains(next.tool) || CHECKS.contains(next.tool);
                            askedAgain |= !wrote && next.tool.equals("ask_expert");
                        }
                    }
                    sum.asked[1] += wrote ? 1 : 0;
                    sum.asked[2] += askedAgain ? 1 : 0;
                }
                long[] tool = sum.tools.computeIfAbsent(call.tool, k -> new long[3]);
                tool[0]++;
                tool[1] += call.result.length();
                tool[2] += waitOf(call);
                if (call.kind < 0) {
                    continue;
                }
                int length = call.result.length();
                sum.kinds[call.kind][0]++;
                sum.kinds[call.kind][1] += length;
                sum.kinds[call.kind][2] += call.reference ? length : 0;
                sum.kinds[call.kind][3] += (long) length * (1 + certain) / 4;
                if (!made.add(call.tool + "\u0000" + call.arguments)) {
                    sum.sameCall[0]++;
                    sum.sameCall[1] += length;
                    sum.sameCall[2] += (long) length * (1 + certain) / 4;
                }
                long[] before = sum.before[whereOf(call)];
                before[0]++;
                before[1] += length;
                before[2] += (long) length * (1 + certain) / 4;
                before[3] += (long) length * (1 + most) / 4;
            }
        }
        if (!session.turns.isEmpty()) {
            replay(sum, session);
        }
    }

    private static void role(StringBuilder out, String name, Sum sum) {
        List<Long> sizes = new ArrayList<>(sum.sizes);
        Collections.sort(sizes);
        long median = sizes.isEmpty() ? 0 : sizes.get(sizes.size() / 2);
        long largest = sizes.isEmpty() ? 0 : sizes.get(sizes.size() - 1);
        out.append("\n## ").append(name).append('\n');
        out.append("sessions ").append(sum.sessions).append(", calls ").append(sum.calls)
            .append(", tokens (input and output, exact) ").append(sum.tokens)
            .append(", a call: mean ").append(sum.tokens / Math.max(1, sum.calls))
            .append(", median ").append(median).append(", largest ").append(largest)
            .append("; output (estimated) ").append(sum.output).append(", of it thinking ")
            .append(sum.thought).append("; time in calls (estimated) ")
            .append(sum.millis / 60_000).append(" min\n");
        out.append("calls by what they were for: calls, tokens\n");
        for (int g = 0; g < GROUPS.length; g++) {
            if (sum.groups[g][0] > 0) {
                out.append("  ").append(GROUPS[g]).append(": ").append(sum.groups[g][0])
                    .append(", ").append(sum.groups[g][1]).append(" (")
                    .append(100 * sum.groups[g][1] / Math.max(1, sum.tokens)).append("%)\n");
            }
        }
        List<Long> firsts = new ArrayList<>(sum.firstCalls);
        Collections.sort(firsts);
        out.append("the first call of a session, median: ")
            .append(firsts.isEmpty() ? 0 : firsts.get(firsts.size() / 2)).append(" tokens\n");
        out.append("lookups by kind: calls, characters, of them about the reference material, "
            + "tokens carried until the next tidy (least)\n");
        long[] all = new long[4];
        for (int k = 0; k < KINDS.length; k++) {
            if (sum.kinds[k][0] > 0) {
                out.append("  ").append(KINDS[k]).append(": ").append(sum.kinds[k][0])
                    .append(", ").append(sum.kinds[k][1]).append(", ").append(sum.kinds[k][2])
                    .append(", ").append(sum.kinds[k][3]).append('\n');
            }
            for (int i = 0; i < 4; i++) {
                all[i] += sum.kinds[k][i];
            }
        }
        out.append("  all: ").append(all[0]).append(", ").append(all[1]).append(", ")
            .append(all[2]).append(", ").append(all[3]).append('\n');
        out.append("the very same call made again in a session: ").append(sum.sameCall[0])
            .append(", ").append(sum.sameCall[1]).append(" characters, ")
            .append(sum.sameCall[2]).append(" tokens carried (least)\n");
        out.append("lookups by where the target had been looked up before: calls, characters, "
            + "tokens carried until the next tidy (least), to the end of the session (most)\n");
        for (int b = 0; b < BEFORE.length; b++) {
            out.append("  ").append(BEFORE[b]).append(": ").append(sum.before[b][0])
                .append(", ").append(sum.before[b][1]).append(", ").append(sum.before[b][2])
                .append(", ").append(sum.before[b][3]).append('\n');
        }
        if (sum.replayedCalls > 0) {
            out.append("  tidies in the replay: ").append(sum.tidies).append((char) 10);
            out.append("  again in the session while an earlier answer was whole: ")
                .append(sum.whileWhole).append(", ").append(sum.whileWholeChars)
                .append("; after every earlier answer had been shortened: ")
                .append(sum.afterCut).append(", ").append(sum.afterCutChars).append('\n');
            String[] names = {"system prompt", "opening (map, rules, task)", "its own text",
                "arguments of lookups", "arguments of its own tools (drafts, findings)",
                "results still whole", "results as first lines", "results as one line",
                "results answered 'unchanged'", "results of its own tools",
                "new-message lines"};
            out.append("an average request, characters (replayed):\n");
            long request = 0;
            for (int i = 0; i < names.length; i++) {
                out.append("  ").append(names[i]).append(": ")
                    .append(sum.parts[i] / sum.replayedCalls).append('\n');
                request += sum.parts[i] / sum.replayedCalls;
            }
            out.append("  all: ").append(request).append('\n');
        }
        out.append("opening: ").append(sum.openingChars / Math.max(1, sum.sessions))
            .append(" characters a session, of them system text ")
            .append(sum.systemChars / Math.max(1, sum.sessions)).append("; distinct system "
                + "texts ").append(sum.systems.size()).append("; opening sent again on every "
                + "call: ").append(sum.openingChars / Math.max(1, sum.sessions) * sum.calls / 4)
            .append(" tokens (estimated)\n");
        out.append("sessions by what came of them: sessions, calls, tokens\n");
        sum.outcomes.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2]).append('\n'));
        if (!sum.verdicts.isEmpty()) {
            out.append("what it handed in: times, characters\n");
            sum.verdicts.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
                .append(", ").append(v[1]).append((char) 10));
        }
        if (sum.asked[0] > 0) {
            out.append("questions to the expert: ").append(sum.asked[0]).append("; followed "
                + "within five calls by writing or checking: ").append(sum.asked[1])
                .append("; by another question first: ").append(sum.asked[2])
                .append((char) 10);
        }
        out.append("tools: calls, characters returned, seconds waited\n");
        sum.tools.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2] / 1000).append('\n'));
    }

    // ------------------------------------------------------- what a request was made of

    private static void replay(Sum sum, Session session) throws Exception {
        Method unchanged = HistoryTrim.class.getDeclaredMethod("unchangedRepeat", List.class,
            String.class, String.class, String.class);
        unchanged.setAccessible(true);
        // The marks each kind of session is opened with: a role's, an expert's, a worker's.
        boolean expert = session.raw.startsWith("expert");
        int above = session.worker ? 12_000 : expert ? 6_000 : LookupAgent.TIDY_ABOVE_TOKENS;
        int to = session.worker ? 3_000 : expert ? 1_500
            : Math.min(LookupAgent.TIDY_TO_TOKENS, above);
        int askedAgain = session.worker || expert ? -1
            : Math.min(LookupAgent.ASKED_AGAIN_TOKENS, to);
        Set<String> lookupIds = new HashSet<>();
        String opening = session.opening;
        int input = opening.indexOf("\nINPUT:\n");
        String system = input < 0 ? "" : opening.substring(0, input);
        String task = input < 0 ? opening : opening.substring(input + 8);
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text(system, null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        Message openingMessage = new Message.User(List.of(new MessagePart.Text(task, null)),
            RequestMetaInfo.Companion.getEmpty(), null);
        messages.add(openingMessage);
        Map<String, String> original = new HashMap<>();
        Map<String, List<String>> idsOfTarget = new HashMap<>();
        int id = 0;
        for (Turn turn : session.turns) {
            for (Message message : messages) {
                for (MessagePart part : message.getParts()) {
                    if (part instanceof MessagePart.Text text) {
                        int which = message instanceof Message.System ? 0
                            : message instanceof Message.Assistant ? 2
                            : message == openingMessage ? 1 : 10;
                        sum.parts[which] += text.getText().length();
                    } else if (part instanceof MessagePart.Tool.Call call) {
                        sum.parts[lookupIds.contains(call.getId()) ? 3 : 4]
                            += call.getArgs().length();
                    } else if (part instanceof MessagePart.Tool.Result result) {
                        String now = result.getOutput();
                        int which = !lookupIds.contains(result.getId()) ? 9
                            : now.equals(original.get(result.getId())) ? 5
                            : now.startsWith("unchanged;") ? 8
                            : now.contains("; only its beginning is kept (") ? 6 : 7;
                        sum.parts[which] += now.length();
                    }
                }
            }
            sum.replayedCalls++;
            List<MessagePart.ResponsePart> said = new ArrayList<>();
            if (!turn.text.isBlank()) {
                said.add(new MessagePart.Text(turn.text, null));
            }
            List<String> ids = new ArrayList<>();
            for (Call call : turn.calls) {
                String callId = "c" + (id++);
                ids.add(callId);
                if (call.kind >= 0) {
                    lookupIds.add(callId);
                }
                said.add(new MessagePart.Tool.Call(callId, call.tool, call.arguments));
            }
            if (said.isEmpty()) {
                said.add(new MessagePart.Text(" ", null));
            }
            messages.add(new Message.Assistant(said, ResponseMetaInfo.Companion.getEmpty(),
                null, null, null));
            if (turn.calls.isEmpty()) {
                continue;
            }
            HistoryTrim.Result tidied = HistoryTrim.tidy(messages, above, to,
                LookupAgent.DIGEST_CHARS, askedAgain);
            if (tidied.changed()) {
                messages = new ArrayList<>(tidied.messages());
                sum.tidies++;
            }
            Set<String> whole = new HashSet<>();
            for (Message message : messages) {
                for (MessagePart part : message.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result
                            && result.getOutput().equals(original.get(result.getId()))) {
                        whole.add(result.getId());
                    }
                }
            }
            List<MessagePart.RequestPart> results = new ArrayList<>();
            for (int c = 0; c < turn.calls.size(); c++) {
                Call call = turn.calls.get(c);
                if (call.kind >= 0) {
                    List<String> before = idsOfTarget.computeIfAbsent(call.target,
                        k -> new ArrayList<>());
                    if (!before.isEmpty()) {
                        boolean held = before.stream().anyMatch(whole::contains);
                        if (held) {
                            sum.whileWhole++;
                            sum.whileWholeChars += call.result.length();
                        } else {
                            sum.afterCut++;
                            sum.afterCutChars += call.result.length();
                        }
                    }
                    before.add(ids.get(c));
                }
                String stub = (String) unchanged.invoke(null, messages, call.tool,
                    call.arguments, call.result);
                original.put(ids.get(c), call.result);
                results.add(new MessagePart.Tool.Result(ids.get(c), call.tool,
                    stub == null ? call.result : stub, false));
            }
            messages.add(new Message.User(results, RequestMetaInfo.Companion.getEmpty(), null));
            if (turn.newMessage) {
                messages.add(new Message.User(List.of(new MessagePart.Text(
                    "Those are the results of your calls. Go on with your work.", null)),
                    RequestMetaInfo.Companion.getEmpty(), null));
            }
        }
    }

    // -------------------------------------------------------------------- across roles

    /** Which blocks of an opening several roles are given word for word. */
    private static void openings(StringBuilder out, List<Session> sessions) {
        Map<String, Set<String>> rolesOfBlock = new HashMap<>();
        Map<String, Session> example = new LinkedHashMap<>();
        for (Session session : sessions) {
            example.putIfAbsent(session.role, session);
        }
        for (Session session : example.values()) {
            for (String block : session.opening.split("\n\\s*\n")) {
                if (block.length() >= 200) {
                    rolesOfBlock.computeIfAbsent(block, k -> new HashSet<>()).add(session.role);
                }
            }
        }
        out.append("\n# openings: one session of each role, characters; in blocks of 200 or "
            + "more that another role's opening holds word for word; in how many roles\n");
        for (Session session : example.values()) {
            long shared = 0;
            int most = 1;
            for (String block : session.opening.split("\n\\s*\n")) {
                Set<String> roles = rolesOfBlock.get(block);
                if (roles != null && roles.size() > 1) {
                    shared += block.length();
                    most = Math.max(most, roles.size());
                }
            }
            out.append("  ").append(session.role).append(": ").append(session.opening.length())
                .append(", ").append(shared).append(", ").append(most).append('\n');
        }
    }

    private static void workers(StringBuilder out, List<Session> sessions) {
        Map<String, long[]> byStoryState = new TreeMap<>();
        Map<String, Instant> firstPassing = new HashMap<>();
        for (Session session : sessions) {
            if (session.worker && session.task != null && !session.turns.isEmpty()
                    && (session.candidateState.startsWith("SELECTED")
                        || session.candidateState.startsWith("SURVIVED"))) {
                Instant closed = session.turns.get(session.turns.size() - 1).at;
                firstPassing.merge(session.task + "|" + session.run, closed,
                    (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        long[] after = new long[3];
        long[] streaks = new long[3];
        long[] sameCommand = new long[2];
        for (Session session : sessions) {
            if (!session.worker) {
                continue;
            }
            String state = session.candidateState.startsWith("SELECTED") ? "selected"
                : session.candidateState.startsWith("SURVIVED") ? "passed, not chosen"
                : session.candidateState.startsWith("KILLED") ? "stopped: "
                    + session.candidateState.substring(7).toLowerCase(Locale.ROOT)
                : session.candidateState.toLowerCase(Locale.ROOT);
            long[] cell = byStoryState.computeIfAbsent(session.story + " | " + session.role
                + " | " + state, k -> new long[3]);
            cell[0]++;
            cell[1] += session.turns.size();
            cell[2] += session.tokens();
            Instant passed = firstPassing.get(session.task + "|" + session.run);
            boolean counted = false;
            int streak = 0;
            String lastCommand = null;
            for (Turn turn : session.turns) {
                if (passed != null && turn.at.minusMillis(turn.millis).isAfter(passed)
                        && !session.candidateState.startsWith("SELECTED")) {
                    after[0] += counted ? 0 : 1;
                    counted = true;
                    after[1]++;
                    after[2] += turn.tokens;
                }
                if (turn.group <= 2) {
                    streak++;
                    if (streak > 8) {
                        streaks[1]++;
                        streaks[2] += turn.tokens;
                    }
                } else {
                    streak = 0;
                }
                for (Call call : turn.calls) {
                    if (WRITES.contains(call.tool)) {
                        lastCommand = null;
                    } else if (call.tool.equals("exec")) {
                        if (call.argument.equals(lastCommand)) {
                            sameCommand[0]++;
                            sameCommand[1] += turn.tokens;
                        }
                        lastCommand = call.argument;
                    }
                }
            }
        }
        out.append("\n# workers by story and what came of the candidate: sessions, calls, "
            + "tokens\n");
        byStoryState.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2]).append('\n'));
        out.append("calls a candidate that was not chosen began after a candidate of its task "
            + "and run had finished and passed: sessions ").append(after[0]).append(", calls ")
            .append(after[1]).append(", tokens ").append(after[2]).append('\n');
        out.append("calls that only looked things up, the ninth and later in a row: calls ")
            .append(streaks[1]).append(", tokens ").append(streaks[2]).append('\n');
        out.append("the same shell command again with nothing written in between: commands ")
            .append(sameCommand[0]).append(", tokens of those calls ").append(sameCommand[1])
            .append('\n');
    }

    private static void experts(StringBuilder out, List<Session> sessions) {
        Map<String, long[]> asked = new TreeMap<>();
        for (Session session : sessions) {
            for (Turn turn : session.turns) {
                for (Call call : turn.calls) {
                    if (call.tool.equals("ask_expert")) {
                        long[] sum = asked.computeIfAbsent(session.role + " | "
                            + (session.worker ? session.candidateState : session.runState),
                            k -> new long[4]);
                        sum[0]++;
                        sum[1] += call.arguments.length();
                        sum[2] += call.result.length();
                        sum[3] += waitOf(call);
                    }
                }
            }
        }
        out.append("\n# questions to the expert by who asked and what came of the asker: "
            + "questions, characters asked, characters answered, seconds waited\n");
        asked.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v[0])
            .append(", ").append(v[1]).append(", ").append(v[2]).append(", ")
            .append(v[3] / 1000).append('\n'));
    }

    /** The targets several roles looked up, by what it cost to look them up again. */
    private static void facts(StringBuilder out, List<Session> sessions) {
        Map<String, long[]> byTarget = new HashMap<>();
        Map<String, Set<String>> roles = new HashMap<>();
        Map<String, Set<String>> stories = new HashMap<>();
        Map<String, Set<String>> inSessions = new HashMap<>();
        for (Session session : sessions) {
            for (int t = 0; t < session.turns.size(); t++) {
                int certain = resent(session, t);
                for (Call call : session.turns.get(t).calls) {
                    if (call.kind < 0 || call.target.startsWith("words: ")) {
                        continue;
                    }
                    long[] sum = byTarget.computeIfAbsent(call.target, k -> new long[4]);
                    sum[0]++;
                    sum[1] += call.result.length();
                    sum[2] += (long) call.result.length() * (1 + certain) / 4;
                    sum[3] += call.reference ? 1 : 0;
                    roles.computeIfAbsent(call.target, k -> new HashSet<>()).add(session.role);
                    stories.computeIfAbsent(call.target, k -> new HashSet<>())
                        .add(session.story);
                    inSessions.computeIfAbsent(call.target, k -> new HashSet<>())
                        .add(session.id);
                }
            }
        }
        long[] several = new long[4];
        int[] targets = new int[2];
        for (Map.Entry<String, long[]> e : byTarget.entrySet()) {
            targets[0]++;
            if (roles.get(e.getKey()).size() >= 3) {
                targets[1]++;
                for (int i = 0; i < 4; i++) {
                    several[i] += e.getValue()[i];
                }
            }
        }
        out.append("\n# the same target looked up by several roles\n  targets named by a "
            + "lookup: ").append(targets[0]).append("; looked up by three roles or more: ")
            .append(targets[1]).append(", in ").append(several[0]).append(" lookups, ")
            .append(several[1]).append(" characters, ").append(several[2])
            .append(" tokens carried (least)\n  the largest: lookups, sessions, roles, stories, "
                + "characters, tokens carried (least), R when mostly reference\n");
        byTarget.entrySet().stream()
            .sorted((x, y) -> Long.compare(y.getValue()[2], x.getValue()[2])).limit(30)
            .forEach(e -> out.append("    ").append(e.getValue()[0]).append(", ")
                .append(inSessions.get(e.getKey()).size()).append(", ")
                .append(roles.get(e.getKey()).size()).append(", ")
                .append(stories.get(e.getKey()).size()).append(", ").append(e.getValue()[1])
                .append(", ").append(e.getValue()[2]).append(' ')
                .append(e.getValue()[3] * 2 > e.getValue()[0] ? "R" : "-").append("  ")
                .append(e.getKey()).append('\n'));
    }

    /** How many conversations the server had at once, from the stored times of the calls. */
    private static void places(StringBuilder out, List<Session> sessions) {
        Map<String, List<long[]>> byRun = new LinkedHashMap<>();
        for (Session session : sessions) {
            for (Turn turn : session.turns) {
                long end = turn.at.toEpochMilli();
                byRun.computeIfAbsent(session.story + " run " + shortId(session.run),
                    k -> new ArrayList<>()).add(new long[] {end - turn.millis, end});
            }
        }
        out.append("\n# conversations at once, by run (estimated from the stored times): hours "
            + "from the first call to the last; share of that time with no call running; "
            + "calls running on average while any was; share of the busy time with 1, 2, 3, "
            + "4 or more\n");
        for (Map.Entry<String, List<long[]>> e : byRun.entrySet()) {
            List<long[]> points = new ArrayList<>();
            long first = Long.MAX_VALUE;
            long last = 0;
            for (long[] call : e.getValue()) {
                points.add(new long[] {call[0], 1});
                points.add(new long[] {call[1], -1});
                first = Math.min(first, call[0]);
                last = Math.max(last, call[1]);
            }
            points.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0])
                : Long.compare(a[1], b[1]));
            long[] at = new long[6];
            int running = 0;
            long previous = first;
            long work = 0;
            for (long[] point : points) {
                at[Math.min(running, 4)] += point[0] - previous;
                work += (point[0] - previous) * running;
                previous = point[0];
                running += (int) point[1];
            }
            long span = Math.max(1, last - first);
            long busy = Math.max(1, span - at[0]);
            out.append("  ").append(e.getKey()).append(": ")
                .append(String.format(Locale.ROOT, "%.1f", span / 3_600_000.0)).append(" h, ")
                .append(100 * at[0] / span).append("% empty, ")
                .append(String.format(Locale.ROOT, "%.2f", work / (double) busy))
                .append(" at once; ").append(100 * at[1] / busy).append("% / ")
                .append(100 * at[2] / busy).append("% / ").append(100 * at[3] / busy)
                .append("% / ").append(100 * at[4] / busy).append("%\n");
        }
    }

    private static void samples(StringBuilder out, List<Session> sessions) {
        Map<String, Integer> labels = new TreeMap<>();
        Set<String> shown = new HashSet<>();
        out.append("# samples (to check the reading of the store; not part of the audit)\n");
        for (Session session : sessions) {
            for (Turn turn : session.turns) {
                for (Call call : turn.calls) {
                    int slash = call.argument.indexOf('/');
                    if (slash > 0 && slash < 40 && call.kind >= 0) {
                        labels.merge(call.argument.substring(0, slash), 1, Integer::sum);
                    }
                    if ((call.tool.equals("read") || call.tool.equals("exec")
                            || call.tool.equals("report_done") || call.tool.equals("shape_of")
                            || call.tool.equals("check_design"))
                            && shown.add(session.role + call.tool)) {
                        out.append("  ").append(session.role).append(' ').append(call.tool)
                            .append(": ").append(cut(call.arguments, 260)).append(" => ")
                            .append(cut(call.result, 160)).append('\n');
                    }
                }
            }
            if (shown.add(session.role) && !session.turns.isEmpty()) {
                out.append("  ").append(session.role).append(" opens: ")
                    .append(cut(session.opening, 300)).append('\n');
            }
        }
        out.append("  first path segments of lookups: ").append(labels).append('\n');
    }

    // ------------------------------------------------------------------- reading the store

    private static Session read(AgentSessionRecord record, BlobStore blobs) {
        Session session = new Session();
        session.id = String.valueOf(record.id());
        session.raw = record.role();
        session.worker = record.role().startsWith("worker");
        session.planning = !session.worker;
        session.opened = record.openedAt() == null ? Instant.EPOCH : record.openedAt();
        session.run = record.runId();
        session.task = record.taskId();
        session.candidate = record.candidateId();
        session.outcome = record.outcome();
        session.kill = record.killReason() == null ? null : String.valueOf(record.killReason());
        Map<String, List<Object[]>> pending = new HashMap<>();
        Turn turn = null;
        Instant previous = session.opened;
        for (TraceEvent event : record.events()) {
            String payload = payloadOf(event, blobs);
            Instant at = event.at() == null ? previous : event.at();
            if (event.kind() == TraceEventKind.SESSION_OPENED) {
                session.opening = payload.startsWith("SYSTEM:\n") ? payload.substring(8) : payload;
            } else if (event.kind() == TraceEventKind.LLM_RESPONSE) {
                turn = new Turn();
                turn.at = at;
                turn.millis = Math.max(0, Duration.between(previous, at).toMillis());
                turn.tokens = event.tokensUsed();
                Matcher thought = THOUGHT.matcher(payload);
                String text = payload;
                if (thought.find()) {
                    turn.thought = Long.parseLong(thought.group(1));
                    text = payload.substring(thought.end());
                }
                int tools = text.lastIndexOf("tool calls: ");
                turn.text = (tools >= 0 ? text.substring(0, tools) : text).strip();
                turn.written = turn.text.length();
                session.turns.add(turn);
            } else if (event.kind() == TraceEventKind.TOOL_CALL) {
                pending.computeIfAbsent(event.label(), k -> new ArrayList<>())
                    .add(new Object[] {payload, at});
                if (turn != null) {
                    turn.written += payload.length();
                }
            } else if (event.kind() == TraceEventKind.TOOL_RESULT && turn != null) {
                List<Object[]> made = pending.get(event.label());
                if (made != null && !made.isEmpty()) {
                    Object[] first = made.remove(0);
                    Call call = new Call();
                    call.tool = event.label();
                    call.arguments = (String) first[0];
                    call.argument = firstArgument(call.arguments);
                    call.result = text(payload);
                    call.waitedMs = Math.max(0, Duration.between((Instant) first[1], at)
                        .toMillis());
                    turn.calls.add(call);
                }
            } else if (event.kind() == TraceEventKind.NUDGE && turn != null) {
                turn.newMessage |= payload.contains("begins a new user message");
                turn.continued |= payload.startsWith("The conversation continues with");
            }
            previous = at;
        }
        session.role = roleOf(session.raw, record.workerIndex(), session.opening);
        return session;
    }

    private static String firstArgument(String arguments) {
        Matcher first = FIRST_ARGUMENT.matcher(arguments);
        if (!first.find()) {
            return "";
        }
        int end = first.end();
        while (end < arguments.length() && arguments.charAt(end) != '"') {
            end += arguments.charAt(end) == '\\' ? 2 : 1;
        }
        return unescape(arguments.substring(first.end(), Math.min(end, arguments.length())));
    }

    private static int count(String text, String word) {
        int found = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            found++;
        }
        return found;
    }

    private static String cut(String text, int most) {
        String line = text.strip().replace('\n', ' ');
        return line.length() <= most ? line : line.substring(0, most);
    }

    private static String shortId(UUID id) {
        return id == null ? "none" : id.toString().substring(0, 8);
    }

    private static String setting(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return value == null ? "" : value.strip();
    }

    private static String payloadOf(TraceEvent event, BlobStore blobs) {
        String payload = event.payload() == null ? "" : event.payload();
        if (event.payloadRef() != null && !event.payloadRef().isBlank()) {
            try {
                return new String(blobs.getBlob(event.payloadRef()), StandardCharsets.UTF_8);
            } catch (Exception gone) {                                     // noqa
                return payload;
            }
        }
        return payload;
    }

    private static String text(String payload) {
        String stored = payload.strip();
        return stored.length() >= 2 && stored.startsWith("\"") && stored.endsWith("\"")
            ? unescape(stored.substring(1, stored.length() - 1)) : payload;
    }

    private static String unescape(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\' || i + 1 >= json.length()) {
                sb.append(c);
                continue;
            }
            char next = json.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < json.length()) {
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
