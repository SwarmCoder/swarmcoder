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

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.store.BlobStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the stored sessions of a COPY of an instance's store and says, per planning role, which
 * whole files it read and what the answer shapes of section 95 would have returned instead. No
 * model, nothing written to the store, nothing read but the store and blob folders.
 *
 * <p>Does nothing unless told where the copy is:
 * {@code swarmcoder.sessions.copy=<folder holding store/ and blobs/>}; the labels of the
 * reference roots, comma separated, in {@code swarmcoder.sessions.referenceRoots}; a file to
 * write the report to in {@code swarmcoder.sessions.report}. Each is a system property or,
 * since the build sets the test JVM's arguments itself, the environment variable of the same
 * name in capitals with underscores ({@code SWARMCODER_SESSIONS_COPY}). Never point it at a
 * live store: opening one takes its lock and runs its housekeeping.
 *
 * <p>The figures are computed on what the session was really sent, with the product's own
 * outlines. Two things it cannot see make its "after" figures slightly low: a build file's
 * {@code build_of} answer and a document's best-matching section need the project's index, so
 * a build file is outlined as XML and a document without a quoted section.
 */
class WhatStoredSessionsLookedUpTest {

    private static final Pattern PART = Pattern.compile(
        "^(.*?)(?::(\\d{1,7})(?:-(\\d{1,7}))?|#([A-Za-z_$][\\w$]*)(?:\\(.*\\))?)$");
    private static final Pattern FIRST_ARGUMENT = Pattern.compile(
        "\"(?:path|where|file|type|section|document|what|dir|query|words|typeOrMethod|name|"
            + "artifact|lookup)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern DECLARES = Pattern.compile("declares (\\d+) type\\(s\\) in (\\d+) package");

    private record Call(int turn, String tool, String arguments, String argument, String result) {}

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
        Map<String, List<List<Call>>> byRole = new LinkedHashMap<>();
        Map<String, List<long[]>> tokensByRole = new LinkedHashMap<>();
        try (ArtifactStore store = new ArtifactStore(dir.resolve("store"))) {
            List<AgentSessionRecord> sessions = new ArrayList<>();
            for (Lazy<Object> lazy : new ArrayList<>(store.root().agentSessions().values())) {
                if (Lazy.get(lazy) instanceof AgentSessionRecord session
                        && session.events() != null && session.role() != null
                        && !session.role().startsWith("worker")) {
                    sessions.add(session);
                }
            }
            sessions.sort(Comparator.comparing(s -> String.valueOf(s.openedAt())));
            for (AgentSessionRecord session : sessions) {
                List<Call> calls = new ArrayList<>();
                List<Long> perTurn = new ArrayList<>();
                List<String[]> pending = new ArrayList<>();
                for (TraceEvent event : session.events()) {
                    String payload = payloadOf(event, blobs);
                    if (event.kind() == TraceEventKind.LLM_RESPONSE) {
                        perTurn.add(event.tokensUsed());
                    } else if (event.kind() == TraceEventKind.TOOL_CALL) {
                        pending.add(new String[] {event.label(), payload,
                            String.valueOf(perTurn.size())});
                    } else if (event.kind() == TraceEventKind.TOOL_RESULT) {
                        for (int i = 0; i < pending.size(); i++) {
                            if (pending.get(i)[0].equals(event.label())) {
                                String[] made = pending.remove(i);
                                Matcher first = FIRST_ARGUMENT.matcher(made[1]);
                                calls.add(new Call(Integer.parseInt(made[2]), made[0], made[1],
                                    first.find() ? unescape(first.group(1)) : made[1],
                                    text(payload)));
                                break;
                            }
                        }
                    }
                }
                String role = session.role().replaceAll("-.*", "");
                byRole.computeIfAbsent(role, r -> new ArrayList<>()).add(calls);
                long[] tokens = new long[perTurn.size()];
                for (int i = 0; i < tokens.length; i++) {
                    tokens[i] = perTurn.get(i);
                }
                tokensByRole.computeIfAbsent(role, r -> new ArrayList<>()).add(tokens);
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<List<Call>>> entry : byRole.entrySet()) {
            role(out, entry.getKey(), entry.getValue(), tokensByRole.get(entry.getKey()), reference);
        }
        String report = setting("swarmcoder.sessions.report");
        if (!report.isBlank()) {
            Files.writeString(Path.of(report), out.toString());
        } else {
            System.out.println(out);
        }
    }

    /** A system property, or the environment variable of the same name in capitals. */
    private static String setting(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name.toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
        }
        return value == null ? "" : value.strip();
    }

    private static boolean lookup(String tool) {
        return ExpertTools.kindOf(tool, "") != null;
    }

    private static boolean ofReference(Call call, List<String> reference) {
        for (String label : reference) {
            if (call.argument().startsWith(label + "/") || call.argument().equals(label)) {
                return true;
            }
        }
        if (call.argument().contains("/") || call.argument().startsWith("project")) {
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

    /** How many later calls of its session a result was certainly sent again in: until a tidy. */
    private static int resent(long[] tokens, int turn) {
        int later = 0;
        for (int i = turn; i < tokens.length; i++) {
            if (i > turn && tokens[i] < tokens[i - 1] - 4_000) {
                break;
            }
            later++;
        }
        return later;
    }

    private static void role(StringBuilder out, String role, List<List<Call>> sessions,
                             List<long[]> tokens, List<String> reference) {
        long all = 0;
        long fromReference = 0;
        int lookups = 0;
        long[] removed = new long[5];      // outline, listing, long lines, asked twice, thrice
        long[] removedTokens = new long[5];
        long[] toTheEnd = new long[5];
        Map<String, long[]> kinds = new LinkedHashMap<>();
        StringBuilder files = new StringBuilder();
        for (int s = 0; s < sessions.size(); s++) {
            List<Call> calls = sessions.get(s);
            long[] perTurn = tokens.get(s);
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (int c = 0; c < calls.size(); c++) {
                Call call = calls.get(c);
                if (!lookup(call.tool())) {
                    continue;
                }
                lookups++;
                int length = call.result().length();
                all += length;
                boolean ref = ofReference(call, reference);
                fromReference += ref ? length : 0;
                int resent = resent(perTurn, call.turn());
                int later = Math.max(0, perTurn.length - call.turn());
                Matcher part = PART.matcher(call.argument().strip());
                boolean isPart = part.matches() && (part.group(2) != null || part.group(4) != null);
                String key = call.tool() + "\u0000" + call.arguments();
                int times = seen.merge(key, 1, Integer::sum);
                if (times > 1 && !call.result().startsWith("unchanged;")) {
                    // What a tidy held whole and never counted: the results asked for twice,
                    // which the bound applies to, and those asked for three times or more,
                    // which always stay.
                    int which = times == 2 ? 3 : 4;
                    removed[which] += length;
                    removedTokens[which] += (long) length * resent / 4;
                    toTheEnd[which] += (long) length * later / 4;
                }
                if (call.tool().equals("read_file") && !isPart) {
                    String address = call.argument().strip();
                    String outline = WholeFileAnswer.outlineOf(null, address, call.result(),
                        "public_shape", null);
                    long saved = outline == null ? 0 : length - outline.length();
                    removed[0] += saved;
                    removedTokens[0] += saved * resent / 4;
                    toTheEnd[0] += saved * later / 4;
                    String name = address.substring(address.lastIndexOf('/') + 1);
                    String kind = (ref ? "reference " : "project ")
                        + (name.endsWith(".java") ? (address.contains("/src/test/")
                            ? "test source" : "source")
                        : name.equals("pom.xml") ? "build file"
                        : DocumentOutline.isDocument(address) ? "document"
                        : "configuration, resource or data file");
                    long[] sums = kinds.computeIfAbsent(kind, k -> new long[4]);
                    sums[0]++;
                    sums[1] += length;
                    sums[2] += outline == null ? length : outline.length();
                    sums[3] += times > 1 ? 1 : 0;
                    files.append("  ").append(length).append(" -> ")
                        .append(outline == null ? "whole" : String.valueOf(outline.length()))
                        .append(times > 1 ? "  (again)" : "").append("  turn ").append(call.turn())
                        .append("  ").append(address).append(usedLater(calls, c, address))
                        .append('\n');
                } else if (call.tool().equals("read_file") && part.group(2) != null
                        && part.group(3) != null && !part.group(2).equals(part.group(3))) {
                    long cut = 0;
                    String[] lines = call.result().split("\n", -1);
                    for (int i = 1; i < lines.length; i++) {
                        cut += Math.max(0, lines[i].length() - ExpertTools.RANGE_LINE_CHARS);
                    }
                    removed[2] += cut;
                    removedTokens[2] += cut * resent / 4;
                    toTheEnd[2] += cut * later / 4;
                } else if (call.tool().equals("types_in")) {
                    Matcher declares = DECLARES.matcher(call.result());
                    if (declares.find()
                            && Integer.parseInt(declares.group(1)) > TreeQueries.TYPES_WITH_SHAPES) {
                        long paged = 300;
                        int listed = 0;
                        for (String line : call.result().split("\n")) {
                            if (line.startsWith("  ") && listed < TreeQueries.TYPE_NAMES_A_PAGE) {
                                int end = line.indexOf(" { ");
                                end = end < 0 ? line.indexOf("  ", 2) : end;
                                paged += (end < 0 ? line.length() : end) + 14;
                                listed++;
                            } else if (!line.startsWith("  ")) {
                                paged += 2 * (line.length() + 1);
                            }
                        }
                        long saved = Math.max(0, length - paged);
                        removed[1] += saved;
                        removedTokens[1] += saved * resent / 4;
                        toTheEnd[1] += saved * later / 4;
                    }
                }
            }
        }
        out.append("\n## ").append(role).append(": ").append(sessions.size())
            .append(" session(s), ").append(lookups).append(" lookups, ").append(all)
            .append(" characters returned, ").append(fromReference)
            .append(" of them about the reference material\n");
        out.append("whole files by kind: files, characters as sent, characters as outlines, "
            + "of which read a second time\n");
        kinds.forEach((kind, sums) -> out.append("  ").append(kind).append(": ").append(sums[0])
            .append(", ").append(sums[1]).append(", ").append(sums[2]).append(", ")
            .append(sums[3]).append('\n'));
        out.append("characters removed; tokens at 4 characters each, counted on every later "
            + "call until the next tidy (the least); and on every later call of the session "
            + "(the most):\n");
        String[] names = {"whole file answered with its outline", "listing paged by name",
            "long lines of a range cut", "held whole because asked for twice (not removed: "
                + "what the bound applies to)", "held whole because asked for three times or "
                + "more (not removed: always kept)"};
        for (int i = 0; i < names.length; i++) {
            out.append("  ").append(names[i]).append(": ").append(removed[i]).append(" chars, ")
                .append(removedTokens[i]).append(" to ").append(toTheEnd[i])
                .append(" tokens\n");
        }
        out.append("each whole file: characters -> outline, turn, address, what was used of it "
            + "later\n").append(files);
    }

    /** The parts of a whole file the session asked for or kept afterwards. */
    private static String usedLater(List<Call> calls, int from, String address) {
        int parts = 0;
        int kept = 0;
        int keptLines = 0;
        for (int i = from + 1; i < calls.size(); i++) {
            Call later = calls.get(i);
            if (later.tool().equals("read_file") && later.argument().startsWith(address + ":")) {
                parts++;
            } else if (later.tool().equals("keep_for_workers")
                    && later.arguments().contains(address)) {
                kept++;
                Matcher lines = Pattern.compile("\"lines\"\\s*:\\s*\"(\\d+)-(\\d+)\"")
                    .matcher(later.arguments());
                if (lines.find()) {
                    keptLines += Integer.parseInt(lines.group(2))
                        - Integer.parseInt(lines.group(1)) + 1;
                }
            }
        }
        return (parts == 0 ? "" : "  | " + parts + " part(s) read later")
            + (kept == 0 ? "" : "  | " + kept + " finding(s) kept, " + keptLines + " lines");
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

    /** A tool result as the model was sent it: stored as a JSON string. */
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
