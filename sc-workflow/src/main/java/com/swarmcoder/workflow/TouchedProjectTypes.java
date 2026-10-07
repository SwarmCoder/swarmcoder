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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.knowledge.ProjectTypes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The public constructors and methods of the project types an acceptance test touches, read off a
 * checkout — what a test author is shown when its test is sent back mid-run.
 *
 * <p>Live harness run 54, 2026-10-01: a test needed a server-side {@code Store} and had no idea how
 * to build one. At TEST_AUTHORING that was forgivable — the {@code Store} did not exist yet. By the
 * time the test failed at EXECUTING, an earlier task had written it, and the repair prompt carried
 * only the test and the error. The author guessed again. The tree the wave was cut from holds the
 * answer; this reads it.
 *
 * <p>One hop: the types the test names, plus the project types those types' public members mention
 * (a constructor taking an {@code HamBookRoot} is useless without {@code HamBookRoot}).
 */
final class TouchedProjectTypes {

    private TouchedProjectTypes() {}

    private static final int MAX_LINES = 60;
    private static final int MAX_LINES_PER_TYPE = 20;

    private static final Pattern CAPITALISED = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");

    /**
     * @param tree       a checkout of the code as it stands now (the tree the wave was cut from)
     * @param testSource the failing test's source
     * @return one {@code Type: header} line per public member, or "" when nothing could be read
     */
    static String signatures(Path tree, String testSource) {
        if (tree == null || testSource == null || testSource.isBlank()) {
            return "";
        }
        try {
            ProjectTypes types = ProjectTypes.of(tree);
            Set<String> touched = typesNamedIn(types, SelfImplementedContract.strip(testSource));
            Set<String> hop = new LinkedHashSet<>(touched);
            for (String type : touched) {
                for (JavaSourceFacts.Exposed member : types.exposedMembers(type)) {
                    hop.addAll(typesNamedIn(types, member.header()));
                }
            }
            List<String> out = new ArrayList<>();
            for (String type : hop) {
                List<JavaSourceFacts.Exposed> members = types.exposedMembers(type);
                int shown = 0;
                for (JavaSourceFacts.Exposed member : members) {
                    if (out.size() >= MAX_LINES) {
                        return String.join("\n", out) + "\n  (more members exist; only the first "
                            + MAX_LINES + " are shown)";
                    }
                    if (shown++ >= MAX_LINES_PER_TYPE) {
                        out.add("  " + type + ": (more members exist)");
                        break;
                    }
                    out.add("  " + type + ": " + member.header());
                }
            }
            return String.join("\n", out);
        } catch (RuntimeException e) {
            return ""; // a tree that could not be read establishes nothing; the prompt is as before
        }
    }

    /** The paragraph appended to the repair prompt; empty when there is nothing to show. */
    static String section(String signatures) {
        if (signatures == null || signatures.isBlank()) {
            return "";
        }
        return "\n\nWhat the project's own code declares right now for the types this test "
            + "touches (earlier tasks have already written them; call exactly these members):\n"
            + signatures;
    }

    private static Set<String> typesNamedIn(ProjectTypes types, String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = CAPITALISED.matcher(text);
        Set<String> simples = new LinkedHashSet<>();
        while (m.find()) {
            if (types.hasSimpleName(m.group(1))) {
                simples.add(m.group(1));
            }
        }
        if (simples.isEmpty()) {
            return found;
        }
        for (String full : new TreeSet<>(types.fullNames())) {
            String simple = full.substring(full.lastIndexOf('.') + 1);
            if (simples.contains(simple)) {
                found.add(full);
            }
        }
        return found;
    }
}
