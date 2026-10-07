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
package com.swarmcoder.inference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every role and every worker looked up in a run, by kind: how often, and how many
 * characters came back into its conversation (2026-10-04).
 *
 * <p><b>Why it exists.</b> For about eighty live runs the roles learned the project from text
 * searches and whole files and hardly asked the syntax tree and the object graph anything, and
 * nothing recorded it: a role's lookups were log lines, a worker's were not even that, and a
 * worker's {@code find}, {@code grep} and {@code cat} in its container looked like any other
 * command. The run report now has one table of this per role.
 *
 * <p>Kept in memory while {@link RunMeter} is on, and never otherwise. Nothing is persisted.
 */
public final class LookupMeter {

    /** What a lookup was answered from. */
    public enum Kind {
        /** The syntax tree or the object graph: a shape, a body, usages, a module, a build. */
        TREE("tree and graph queries"),
        /** A text search over code or documentation, a worked example, a documentation page. */
        SEARCH("text search and documentation"),
        /** A file read whole. */
        WHOLE_FILE("whole files"),
        /** Part of a file: a range of lines, or one member taken out by its name. */
        FILE_PART("parts of files"),
        /** A folder listing. */
        LISTING("folder listings"),
        /** A shell command that only reads: find, grep, cat, ls and their like. */
        SHELL_READ("shell reads (find, grep, cat)"),
        /**
         * The Java language server: references, hierarchy, callers, a library type's members,
         * a symbol's documentation, a file's problems, a refactoring. Appended 2026-10-04.
         */
        LANGUAGE_SERVER("language-server queries"),
        /**
         * A document's structure: its outline, one section by number or heading, the sections
         * about a subject - with no model, as the tree answers about code. Appended 2026-10-04.
         */
        DOCUMENT("document outlines and sections"),
        /**
         * The acceptance test methods a worker's task claims, with the helpers they use, read
         * with no model from the run's tests commit. Appended 2026-10-05.
         */
        ACCEPTANCE_TEST("acceptance-test reads"),
        /**
         * The test author's {@code check_journey}: a draft journey read and checked with no
         * model (section 63). A worker reading a journey is an acceptance-test read. Appended
         * 2026-10-05.
         */
        JOURNEY_CHECK("journey checks");

        /** True for what CLAUDE.md section 1 puts first: a structured query, not text or a file. */
        public boolean structured() {
            return this == TREE || this == LANGUAGE_SERVER || this == DOCUMENT
                || this == ACCEPTANCE_TEST || this == JOURNEY_CHECK;
        }

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One role's lookups of one kind. */
    public record Count(String role, Kind kind, int calls, long chars) {}

    private static final Map<String, long[]> COUNTS = new LinkedHashMap<>();

    private LookupMeter() {}

    /**
     * One lookup was answered.
     *
     * @param role  "planner", "expert", "worker", ... - as on the run's cost record
     * @param chars how many characters came back
     */
    public static void record(String role, Kind kind, int chars) {
        if (!RunMeter.enabled() || kind == null) {
            return;
        }
        String key = (role == null || role.isBlank() ? "untagged" : role) + "\u0000" + kind.name();
        synchronized (COUNTS) {
            long[] sums = COUNTS.computeIfAbsent(key, k -> new long[2]);
            sums[0]++;
            sums[1] += Math.max(0, chars);
        }
    }

    /** Everything counted so far, in the order each role and kind first appeared. */
    public static List<Count> counts() {
        List<Count> all = new ArrayList<>();
        synchronized (COUNTS) {
            for (Map.Entry<String, long[]> entry : COUNTS.entrySet()) {
                String[] key = entry.getKey().split("\u0000");
                all.add(new Count(key[0], Kind.valueOf(key[1]), (int) entry.getValue()[0],
                    entry.getValue()[1]));
            }
        }
        return all;
    }

    public static void reset() {
        synchronized (COUNTS) {
            COUNTS.clear();
        }
    }

    /**
     * The kind of a shell command, when all it does is read: its first word is one of the
     * programs that list, find or print files. Null for anything else - a build, a test, a
     * command that writes.
     */
    public static Kind ofShellCommand(String command) {
        if (command == null) {
            return null;
        }
        String first = command.strip();
        int end = 0;
        while (end < first.length() && !Character.isWhitespace(first.charAt(end))) {
            end++;
        }
        first = first.substring(0, end);
        first = first.substring(first.lastIndexOf('/') + 1);
        return READ_PROGRAMS.contains(first) && !writesAFile(command) ? Kind.SHELL_READ : null;
    }

    /**
     * True when the command's first step sends its output to a file: {@code cat > Probe.java
     * <<EOF} writes a file, it reads nothing (run 88 counted two such commands among the
     * workers' 28 shell reads). {@code 2>...}, {@code >&2} and {@code >/dev/null} are not files.
     */
    private static boolean writesAFile(String command) {
        String step = command;
        for (String end : new String[] {"|", ";", "&&", "\n"}) {
            int at = step.indexOf(end);
            if (at >= 0 && !(end.equals("|") && step.startsWith("|", at + 1))) {
                step = step.substring(0, at);
            }
        }
        for (int at = step.indexOf('>'); at >= 0; at = step.indexOf('>', at + 1)) {
            if (at > 0 && (step.charAt(at - 1) == '2' || step.charAt(at - 1) == '>')) {
                continue; // 2> and the second character of >>, judged at its first
            }
            String target = step.substring(at + 1);
            target = (target.startsWith(">") ? target.substring(1) : target).stripLeading();
            if (!target.startsWith("&") && !target.startsWith("/dev/null")) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> READ_PROGRAMS = java.util.Set.of("find", "grep",
        "egrep", "fgrep", "rg", "cat", "ls", "head", "tail", "tree", "less", "more", "wc", "awk");
}
