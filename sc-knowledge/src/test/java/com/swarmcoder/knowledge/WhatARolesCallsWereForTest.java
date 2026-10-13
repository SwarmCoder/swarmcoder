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
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.runtime.HistoryTrim;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads ONE stored planning session of a COPY of an instance's store and says what its calls
 * were for (section 97): the calls in groups with their tokens, what it looked up that an
 * earlier session of the same store had looked up or that a stored design had kept as a
 * finding, what each request was made of, and its reads of parts of files. No model, nothing
 * written to the store.
 *
 * <p>The requests are rebuilt by replaying the session's stored calls through the product's own
 * tidy ({@link HistoryTrim#tidy}) with the roles' settings, in the order the runtime applies
 * them; the report prints each tidy's before and after figure to hold against the instance's
 * log.
 *
 * <p>Does nothing unless told where the copy is ({@code swarmcoder.sessions.copy}, as
 * {@link WhatStoredSessionsLookedUpTest}); {@code swarmcoder.sessions.session} is the first
 * characters of the session's id (default: the latest architect session);
 * {@code swarmcoder.sessions.referenceRoots} and {@code swarmcoder.sessions.report} as there.
 */
class WhatARolesCallsWereForTest {

    private static final Pattern FIRST_ARGUMENT = Pattern.compile("\"[A-Za-z_]+\"\\s*:\\s*\"");
    private static final Pattern THOUGHT = Pattern.compile("^\\[thought ~(\\d+) tokens]\\n?");
    private static final Pattern RANGE = Pattern.compile("^(.*?):(\\d{1,7})(?:-(\\d{1,7}))?$");
    private static final Pattern OUTLINE_LINES = Pattern.compile(" characters, (\\d+) lines");
    private static final Set<String> WORDS = Set.of("search", "docs_for", "doc_search",
        "find_example", "lookup_docs", "library_docs", "find_symbol", "types_annotated_with",
        "files_using", "call_chain", "list_files", "ask_expert", "dependency_declaring");
    private static final Set<String> CHECKS = Set.of("check_design", "check_plan");

    private record Call(String tool, String arguments, String argument, String result) {}

    private static final class Turn {
        long tokens;
        long thought;
        String text = "";
        final List<Call> calls = new ArrayList<>();
        boolean newMessage;
        boolean continued;
    }

    private record Session(String id, String role, Instant opened, String opening,
                           List<Turn> turns) {}

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
        Map<Instant, List<DesignFinding>> findings = new TreeMap<>();
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            for (Lazy<Object> lazy : new ArrayList<>(store.root().agentSessions().values())) {
                if (Lazy.get(lazy) instanceof AgentSessionRecord record && record.events() != null
                        && record.role() != null && !record.role().startsWith("worker")) {
                    sessions.add(read(record, blobs));
                }
            }
            for (DesignDocument design : store.root().designs.values()) {
                if (design.findings() != null && !design.findings().isEmpty()
                        && design.createdAt() != null) {
                    findings.put(design.createdAt(), List.copyOf(design.findings()));
                }
            }
        }
        sessions.sort(Comparator.comparing(Session::opened));
        String wanted = setting("swarmcoder.sessions.session");
        Session subject = null;
        for (Session session : sessions) {
            if (wanted.isBlank() ? session.role().startsWith("architect")
                    : session.id().startsWith(wanted)) {
                subject = session;
            }
        }
        if (subject == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("# session ").append(subject.id()).append(", ").append(subject.role())
            .append(", ").append(subject.turns().size()).append(" calls\n");
        groups(out, subject, reference);
        repeats(out, subject, sessions, findings, reference);
        replay(out, subject);
        parts(out, subject);
        String report = setting("swarmcoder.sessions.report");
        if (!report.isBlank()) {
            Files.writeString(Path.of(report), out.toString());
        } else {
            System.out.println(out);
        }
    }

    // ---------------------------------------------------------------- 1. the calls in groups

    /** What one call was for, from the tools it called and nothing else. */
    static String groupOf(List<String> tools, long referenceChars, long projectChars,
                          boolean afterHandIn) {
        if (tools.stream().anyMatch(CHECKS::contains)) {
            return afterHandIn ? "revising after it was sent back" : "checking a draft";
        }
        if (tools.contains("keep_for_workers")) {
            return "writing findings";
        }
        if (tools.contains("report_done")) {
            return "handing in";
        }
        if (tools.isEmpty()) {
            return "no tool call";
        }
        if (afterHandIn) {
            return "revising after it was sent back";
        }
        return referenceChars > projectChars ? "learning the reference material"
            : "reading the project";
    }

    private static void groups(StringBuilder out, Session session, List<String> reference) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        StringBuilder order = new StringBuilder();
        String running = null;
        int since = 1;
        boolean afterHandIn = false;
        long all = 0;
        long thought = 0;
        for (int t = 0; t < session.turns().size(); t++) {
            Turn turn = session.turns().get(t);
            long ref = 0;
            long project = 0;
            List<String> tools = new ArrayList<>();
            for (Call call : turn.calls) {
                tools.add(call.tool());
                if (isLookup(call.tool())) {
                    if (ofReference(call, reference)) {
                        ref += call.result().length();
                    } else {
                        project += call.result().length();
                    }
                }
            }
            String group = groupOf(tools, ref, project, afterHandIn);
            long[] sum = sums.computeIfAbsent(group, g -> new long[4]);
            sum[0]++;
            sum[1] += turn.tokens;
            sum[2] += turn.thought;
            sum[3] += ref + project;
            all += turn.tokens;
            thought += turn.thought;
            if (!group.equals(running)) {
                if (running != null) {
                    order.append("  calls ").append(since).append('-').append(t).append(": ")
                        .append(running).append('\n');
                }
                running = group;
                since = t + 1;
            }
            afterHandIn |= turn.continued;
            for (Call call : turn.calls) {
                if (CHECKS.contains(call.tool())) {
                    out.append("draft check at call ").append(t + 1).append(": draft of ")
                        .append(call.arguments().length()).append(" characters, answer of ")
                        .append(call.result().length()).append(": ")
                        .append(firstLine(call.result(), 300)).append('\n');
                }
            }
        }
        order.append("  calls ").append(since).append('-').append(session.turns().size())
            .append(": ").append(running).append('\n');
        out.append("\n## 1. calls by what they were for (tokens are each call's input and "
            + "output as the server counted them)\n");
        for (Map.Entry<String, long[]> e : sums.entrySet()) {
            out.append("  ").append(e.getKey()).append(": ").append(e.getValue()[0])
                .append(" calls, ").append(e.getValue()[1]).append(" tokens (")
                .append(100 * e.getValue()[1] / Math.max(1, all)).append("%), of them thought ")
                .append(e.getValue()[2]).append("; lookups returned ").append(e.getValue()[3])
                .append(" characters\n");
        }
        out.append("  all: ").append(all).append(" tokens, thought ").append(thought)
            .append("\nin order:\n").append(order);
    }

    // ------------------------------------------------- 2. what had been looked up before

    /** What a lookup was about: a type, a file, a document, or the words of a question. */
    static String targetOf(String tool, String argument) {
        String a = argument == null ? "" : argument.strip();
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

    private static void repeats(StringBuilder out, Session subject, List<Session> sessions,
                                Map<Instant, List<DesignFinding>> findings,
                                List<String> reference) {
        Set<String> sameRole = new HashSet<>();
        Set<String> anyRole = new HashSet<>();
        for (Session earlier : sessions) {
            if (!earlier.opened().isBefore(subject.opened())) {
                continue;
            }
            for (Turn turn : earlier.turns()) {
                for (Call call : turn.calls) {
                    if (isLookup(call.tool())) {
                        String target = targetOf(call.tool(), call.argument());
                        anyRole.add(target);
                        if (earlier.role().equals(subject.role())) {
                            sameRole.add(target);
                        }
                    }
                }
            }
        }
        Map<String, Integer> kept = new HashMap<>();
        int stored = 0;
        for (Map.Entry<Instant, List<DesignFinding>> design : findings.entrySet()) {
            if (design.getKey().isBefore(subject.opened())) {
                for (DesignFinding finding : design.getValue()) {
                    String source = finding.source() == null ? "" : finding.source().strip();
                    int space = source.indexOf(' ');
                    if (space > 0) {
                        kept.merge(targetOf(source.substring(0, space),
                            source.substring(space + 1)), finding.size(), Integer::sum);
                        stored++;
                    }
                }
            }
        }
        // by target: lookups, characters, reference characters, size of the first answer
        Map<String, long[]> byTarget = new LinkedHashMap<>();
        Map<String, Integer> exact = new HashMap<>();
        long[] total = new long[3];
        long[] again = new long[3];
        long[] againExact = new long[3];
        long[] first = new long[3];
        for (Turn turn : subject.turns()) {
            for (Call call : turn.calls) {
                if (!isLookup(call.tool())) {
                    continue;
                }
                String target = targetOf(call.tool(), call.argument());
                boolean ref = ofReference(call, reference);
                int length = call.result().length();
                add(total, length, ref);
                boolean seen = byTarget.containsKey(target);
                long[] sum = byTarget.computeIfAbsent(target, k -> new long[4]);
                sum[0]++;
                sum[1] += length;
                sum[2] += ref ? length : 0;
                if (seen && !target.startsWith("words: ")) {
                    add(again, length, ref);
                } else {
                    add(first, length, ref);
                    sum[3] = length;
                }
                if (exact.merge(call.tool() + "\u0000" + call.arguments(), 1, Integer::sum) > 1) {
                    add(againExact, length, ref);
                }
            }
        }
        long[] wasSame = new long[4];
        long[] wasAny = new long[4];
        long[] wasKept = new long[4];
        long keptSize = 0;
        for (Map.Entry<String, long[]> e : byTarget.entrySet()) {
            long[] s = e.getValue();
            if (sameRole.contains(e.getKey())) {
                addAll(wasSame, s);
            }
            if (anyRole.contains(e.getKey())) {
                addAll(wasAny, s);
            }
            if (kept.containsKey(e.getKey())) {
                addAll(wasKept, s);
                keptSize += kept.get(e.getKey());
            }
        }
        out.append("\n## 2. what had been looked up before (a target is the type, file or "
            + "document a lookup names; a question in words is its own target)\n");
        out.append("  lookups: ").append(line(total)).append("; distinct targets ")
            .append(byTarget.size()).append('\n');
        out.append("  first lookup of each target: ").append(line(first)).append('\n');
        out.append("  a target of this session looked up AGAIN in it: ").append(line(again))
            .append("; of them the very same call: ").append(line(againExact)).append('\n');
        out.append("  targets an earlier session of the same role had looked up: ")
            .append(wasSame[3]).append(" targets, ").append(line(wasSame)).append('\n');
        out.append("  targets any earlier planning session had looked up: ").append(wasAny[3])
            .append(" targets, ").append(line(wasAny)).append('\n');
        out.append("  targets an earlier design holds a finding from (").append(stored)
            .append(" findings stored, ").append(kept.size()).append(" source targets): ")
            .append(wasKept[3]).append(" targets, ").append(line(wasKept))
            .append("; the findings from those targets are ").append(keptSize)
            .append(" characters\n  largest targets (lookups, characters; R reference, "
                + "S same role before, A any role before, F a finding held):\n");
        byTarget.entrySet().stream()
            .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(24)
            .forEach(e -> out.append("    ").append(e.getValue()[0]).append(", ")
                .append(e.getValue()[1]).append(' ')
                .append(e.getValue()[2] > 0 ? "R" : "-")
                .append(sameRole.contains(e.getKey()) ? "S" : "-")
                .append(anyRole.contains(e.getKey()) ? "A" : "-")
                .append(kept.containsKey(e.getKey()) ? "F" : "-").append("  ")
                .append(e.getKey()).append('\n'));
    }

    private static void add(long[] sum, int length, boolean ref) {
        sum[0]++;
        sum[1] += length;
        sum[2] += ref ? length : 0;
    }

    private static void addAll(long[] sum, long[] of) {
        sum[0] += of[0];
        sum[1] += of[1];
        sum[2] += of[2];
        sum[3]++;
    }

    private static String line(long[] sum) {
        return sum[0] + " lookups, " + sum[1] + " characters, " + sum[2] + " of them reference";
    }

    // --------------------------------------------------- 3. what each request was made of

    private static void replay(StringBuilder out, Session session) throws Exception {
        Method unchanged = HistoryTrim.class.getDeclaredMethod("unchangedRepeat", List.class,
            String.class, String.class, String.class);
        unchanged.setAccessible(true);
        int above = Integer.getInteger("swarmcoder.sessions.tidyAbove",
            LookupAgent.TIDY_ABOVE_TOKENS);
        int to = Math.min(LookupAgent.TIDY_TO_TOKENS, above);
        int askedAgain = Math.min(LookupAgent.ASKED_AGAIN_TOKENS, to);
        String opening = session.opening();
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
        String[] names = {"system prompt", "opening (map, rules, task)", "the role's own text",
            "arguments of lookups", "arguments of its own tools (drafts, findings)",
            "results still whole", "results as first lines", "results as one line",
            "results answered 'unchanged'", "results of its own tools", "new-message lines"};
        long[] sum = new long[names.length];
        long[] largest = new long[names.length];
        long largestTokens = -1;
        int largestAt = 0;
        long sent = 0;
        long excess = 0;
        long firstOverhead = -1;
        long[] reAsk = new long[4];     // again while whole: n, chars; again after a cut: n, chars
        StringBuilder tidies = new StringBuilder();
        int id = 0;
        for (int t = 0; t < session.turns().size(); t++) {
            Turn turn = session.turns().get(t);
            long[] parts = new long[names.length];
            for (Message message : messages) {
                for (MessagePart part : message.getParts()) {
                    if (part instanceof MessagePart.Text text) {
                        int which = message instanceof Message.System ? 0
                            : message instanceof Message.Assistant ? 2
                            : message == openingMessage ? 1 : 10;
                        parts[which] += text.getText().length();
                    } else if (part instanceof MessagePart.Tool.Call call) {
                        parts[isLookup(call.getTool()) ? 3 : 4] += call.getArgs().length();
                    } else if (part instanceof MessagePart.Tool.Result result) {
                        String now = result.getOutput();
                        int which = !isLookup(result.getTool()) ? 9
                            : now.equals(original.get(result.getId())) ? 5
                            : now.startsWith("unchanged;") ? 8
                            : now.contains("; only its beginning is kept (") ? 6 : 7;
                        parts[which] += now.length();
                    }
                }
            }
            long chars = 0;
            for (int i = 0; i < parts.length; i++) {
                sum[i] += parts[i];
                chars += parts[i];
            }
            sent += chars;
            long estimate = HistoryTrim.estimateTokens(messages);
            long inputTokens = Math.max(0, turn.tokens - turn.thought);
            if (firstOverhead < 0) {
                firstOverhead = Math.max(0, inputTokens - estimate);
            }
            excess += Math.max(0, inputTokens - estimate - firstOverhead);
            if (turn.tokens > largestTokens) {
                largestTokens = turn.tokens;
                largestAt = t + 1;
                largest = parts;
            }
            // The answer, then the tidy, then this turn's results: the runtime's order.
            List<MessagePart.ResponsePart> said = new ArrayList<>();
            if (!turn.text.isBlank()) {
                said.add(new MessagePart.Text(turn.text, null));
            }
            List<String> ids = new ArrayList<>();
            for (Call call : turn.calls) {
                String callId = "c" + (id++);
                ids.add(callId);
                said.add(new MessagePart.Tool.Call(callId, call.tool(), call.arguments()));
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
                tidies.append("  at call ").append(t + 1).append(": ").append(tidied.dropped())
                    .append(" result(s) shortened, ").append(tidied.tokensBefore())
                    .append(" -> ").append(tidied.tokensAfter()).append(" tokens (estimated)\n");
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
                if (isLookup(call.tool())) {
                    String target = targetOf(call.tool(), call.argument());
                    List<String> before = idsOfTarget.computeIfAbsent(target,
                        k -> new ArrayList<>());
                    if (!before.isEmpty() && !target.startsWith("words: ")) {
                        boolean held = before.stream().anyMatch(whole::contains);
                        reAsk[held ? 0 : 2]++;
                        reAsk[held ? 1 : 3] += call.result().length();
                    }
                    before.add(ids.get(c));
                }
                String stub = (String) unchanged.invoke(null, messages, call.tool(),
                    call.arguments(), call.result());
                original.put(ids.get(c), call.result());
                results.add(new MessagePart.Tool.Result(ids.get(c), call.tool(),
                    stub == null ? call.result() : stub, false));
            }
            messages.add(new Message.User(results, RequestMetaInfo.Companion.getEmpty(), null));
            if (turn.newMessage) {
                messages.add(new Message.User(List.of(new MessagePart.Text(
                    "Those are the results of your calls. Go on with your work.", null)),
                    RequestMetaInfo.Companion.getEmpty(), null));
            }
        }
        int calls = session.turns().size();
        out.append("\n## 3. what a request was made of (characters of the conversation as "
            + "replayed; the tool descriptions are not in the store)\n");
        out.append("  the first call was counted ").append(firstOverhead).append(" tokens above "
            + "its conversation: the tool descriptions and the framing\n");
        out.append("  part: characters in an average request; in the largest call (call ")
            .append(largestAt).append(", ").append(largestTokens).append(" tokens)\n");
        for (int i = 0; i < names.length; i++) {
            out.append("    ").append(names[i]).append(": ").append(sum[i] / Math.max(1, calls))
                .append("; ").append(largest[i]).append('\n');
        }
        out.append("  conversation sent in all: ").append(sent).append(" characters, ")
            .append(sent / Math.max(1, calls)).append(" a call\n");
        out.append("  counted by the server above the conversation and the first call's "
            + "overhead (earlier thinking, section 94): ").append(excess).append(" tokens in "
            + "all\n");
        out.append("  a target looked up again while an earlier answer about it was still "
            + "whole: ").append(reAsk[0]).append(" lookups, ").append(reAsk[1])
            .append(" characters; after every earlier answer about it had been shortened: ")
            .append(reAsk[2]).append(" lookups, ").append(reAsk[3]).append(" characters\n");
        out.append("tidies (mark ").append(above).append(", down to ").append(to)
            .append(" tokens of old results):\n").append(tidies);
    }

    // ------------------------------------------------------------- 4. parts of files

    private static void parts(StringBuilder out, Session session) {
        Map<String, Integer> linesOf = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        long[] whole = new long[2];
        long[] document = new long[2];
        long[] source = new long[2];
        long[] other = new long[2];
        long[] second = new long[2];
        StringBuilder each = new StringBuilder();
        int t = 0;
        for (Turn turn : session.turns()) {
            t++;
            for (Call call : turn.calls) {
                if (!call.tool().equals("read_file")) {
                    continue;
                }
                String address = call.argument().strip();
                Matcher range = RANGE.matcher(address);
                if (!range.matches()) {
                    Matcher lines = OUTLINE_LINES.matcher(firstLine(call.result(), 400));
                    if (lines.find()) {
                        linesOf.put(targetOf("read_file", address),
                            Integer.parseInt(lines.group(1)));
                    }
                    continue;
                }
                int from = Integer.parseInt(range.group(2));
                int to = range.group(3) == null ? from : Integer.parseInt(range.group(3));
                String target = targetOf("read_file", address);
                Integer known = linesOf.get(target);
                int length = call.result().length();
                boolean again = seen.merge(target + ":" + from + "-" + to, 1, Integer::sum) > 1;
                long[] kind = known != null && from <= 1 && to >= known - 1 ? whole
                    : range.group(1).endsWith(".md") ? document
                    : range.group(1).endsWith(".java") ? source : other;
                kind[0]++;
                kind[1] += length;
                if (again) {
                    second[0]++;
                    second[1] += length;
                }
                each.append("    call ").append(t).append(": ").append(to - from + 1)
                    .append(" lines, ").append(length).append(" characters")
                    .append(kind == whole ? ", ALL of its " + known + " lines" : "")
                    .append(again ? ", the same range again" : "").append("  ")
                    .append(target).append(':').append(from).append('-').append(to).append('\n');
            }
        }
        out.append("\n## 4. parts of files by line range\n  all the lines of a file whose "
            + "outline had been given: ").append(whole[0]).append(" reads, ").append(whole[1])
            .append(" characters\n  a range of a document: ").append(document[0])
            .append(", ").append(document[1]).append("\n  a range of a Java source: ")
            .append(source[0]).append(", ").append(source[1])
            .append("\n  a range of another file: ").append(other[0]).append(", ")
            .append(other[1]).append("\n  of all of them the same range a second time: ")
            .append(second[0]).append(", ").append(second[1]).append('\n').append(each);
    }

    // ------------------------------------------------------------------- reading the store

    private static Session read(AgentSessionRecord record, BlobStore blobs) {
        List<Turn> turns = new ArrayList<>();
        List<String[]> pending = new ArrayList<>();
        String opening = "";
        Turn turn = null;
        for (TraceEvent event : record.events()) {
            String payload = payloadOf(event, blobs);
            if (event.kind() == TraceEventKind.SESSION_OPENED) {
                opening = payload.startsWith("SYSTEM:\n") ? payload.substring(8) : payload;
            } else if (event.kind() == TraceEventKind.LLM_RESPONSE) {
                turn = new Turn();
                turn.tokens = event.tokensUsed();
                Matcher thought = THOUGHT.matcher(payload);
                String text = payload;
                if (thought.find()) {
                    turn.thought = Long.parseLong(thought.group(1));
                    text = payload.substring(thought.end());
                }
                int tools = text.lastIndexOf("tool calls: ");
                turn.text = (tools >= 0 ? text.substring(0, tools) : text).strip();
                turns.add(turn);
            } else if (event.kind() == TraceEventKind.TOOL_CALL) {
                pending.add(new String[] {event.label(), payload});
            } else if (event.kind() == TraceEventKind.TOOL_RESULT && turn != null) {
                for (int i = 0; i < pending.size(); i++) {
                    if (pending.get(i)[0].equals(event.label())) {
                        String[] made = pending.remove(i);
                        turn.calls.add(new Call(made[0], made[1], firstArgument(made[1]),
                            text(payload)));
                        break;
                    }
                }
            } else if (event.kind() == TraceEventKind.NUDGE && turn != null) {
                turn.newMessage |= payload.contains("begins a new user message");
                turn.continued |= payload.startsWith("The conversation continues with");
            }
        }
        return new Session(String.valueOf(record.id()), record.role(),
            record.openedAt() == null ? Instant.EPOCH : record.openedAt(), opening, turns);
    }

    /** The first string argument of a call, read by hand: a draft is too long for a pattern. */
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

    private static boolean isLookup(String tool) {
        return ExpertTools.kindOf(tool, "") != null;
    }

    private static boolean ofReference(Call call, List<String> reference) {
        String argument = call.argument();
        for (String label : reference) {
            if (argument.startsWith(label + "/") || argument.equals(label)) {
                return true;
            }
        }
        if (argument.contains("/") || argument.startsWith("project")) {
            return false;
        }
        int ofReference = 0;
        int ofProject = count(call.result(), "project/");
        for (String label : reference) {
            ofReference += count(call.result(), label + "/");
        }
        return ofReference > ofProject;
    }

    private static int count(String text, String word) {
        int found = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            found++;
        }
        return found;
    }

    private static String firstLine(String text, int most) {
        String line = text.strip();
        int end = line.indexOf('\n');
        line = end < 0 ? line : line.substring(0, end);
        return line.length() <= most ? line : line.substring(0, most);
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
