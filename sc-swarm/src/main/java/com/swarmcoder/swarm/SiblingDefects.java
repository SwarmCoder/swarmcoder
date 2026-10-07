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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Task;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.UnifiedDiffPaths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When workers dispute a rule and their evidence names a file an EARLIER task of the same run
 * delivered, the obstacle is that task's output, not the rule.
 *
 * <p><b>Why</b> (harness run 65, 2026-10-02, HamBook). An early task delivered the screen-label
 * class as a final class with a private constructor and instance methods only, so nothing could
 * call it; it had no acceptance check and merged on "compiles". A later screen task, bound by
 * "screen text lives in the text class", could not obey: both its workers disputed the rule, each
 * quoting that file and saying it was outside their write set. The unattended policy then reworded
 * the RULE for the whole project. The rule was fine. One file was defective, and the task that met
 * it was not allowed to touch it.
 *
 * <p>So the question "is this about the rule?" is asked of the evidence first. A file counts as an
 * earlier task's output when that task already has a winner, that winner's diff added or changed
 * the file (or, with no diff to read, the task's write set names it), and the disputing task may
 * not write it. Then the remedy is to let the disputing task repair it — see
 * {@code SwarmEngineImpl.widenForSiblingRepair} — once per task; only when that fails does the
 * question about the rule go ahead as before.
 *
 * <p>Nothing here knows any language or library: it reads paths out of the workers' own words and
 * compares them with what earlier winners changed.
 */
final class SiblingDefects {

    /** An earlier task of the run that has a winner. */
    record Delivered(Task task, CandidateSolution winner) {}

    /**
     * The files at fault and who delivered them.
     *
     * @param files       repo-relative paths, in the order the evidence named them
     * @param deliveredBy path → the title of the task whose winner delivered it
     * @param disputes    the disputes whose evidence named them
     */
    record Defect(List<String> files, Map<String, String> deliveredBy, List<RuleDispute> disputes) {

        /** "a/B.java (delivered by 'Task one')", comma separated. */
        String named() {
            List<String> parts = new ArrayList<>();
            for (String file : files) {
                parts.add(file + " (delivered by '" + deliveredBy.get(file) + "')");
            }
            return String.join(", ", parts);
        }

        /** What the task is told when it is built again with these files in its write set. */
        String instructions() {
            StringBuilder sb = new StringBuilder("\n\nREPAIR FIRST — a file an earlier task of "
                + "this run delivered is in the way: ").append(named()).append(". It has been "
                + "added to the paths you may modify. Fix it so this task can follow the project's "
                + "rules as written; do not work around it and do not dispute a rule over it. "
                + "Change only what is needed, and keep everything that already uses it compiling. "
                + "What the previous attempt at this task found:");
            Set<String> said = new LinkedHashSet<>();
            for (RuleDispute dispute : disputes) {
                said.add(oneLine(dispute.reason()));
            }
            for (String reason : said) {
                sb.append("\n  - ").append(cut(reason, EVIDENCE_IN_NOTE));
            }
            return sb.toString();
        }
    }

    /** More than this many files is no longer "one defective file": leave it to the question. */
    static final int MAX_FILES = 3;
    private static final int EVIDENCE_IN_NOTE = 600;

    /** A path-like token: optional directories, then a file name with an extension. */
    private static final Pattern PATH = Pattern.compile(
        "(?:[A-Za-z0-9_.$-]+[/\\\\])*[A-Za-z0-9_$-]+\\.[A-Za-z][A-Za-z0-9]{0,7}");

    private SiblingDefects() {}

    /**
     * The earlier task's output these disputes point at, or null when they point at none.
     *
     * <p>Every dispute must name at least one such file — one worker blaming a file and another
     * blaming the library is not agreement about anything. The files reported are the ones every
     * dispute names; when no single file is named by all, the ones any names. A dispute that gives
     * a full path is read by its paths alone; only one that gives none is read by bare file names.
     *
     * @param task      the disputing task
     * @param delivered earlier tasks of this run that have a winner
     * @param disputes  the disputes to read; empty means there is nothing to read
     */
    static Defect find(Task task, List<Delivered> delivered, List<RuleDispute> disputes) {
        if (task == null || delivered == null || delivered.isEmpty() || disputes == null
                || disputes.isEmpty() || !task.siblingRepairPaths().isEmpty()) {
            return null; // bounded: a task is widened this way once
        }
        // Every file an earlier winner delivered that this task may not write, with its owner.
        Map<String, String> owners = new LinkedHashMap<>();
        for (Delivered earlier : delivered) {
            if (earlier.task() == null || earlier.task().id().equals(task.id())) {
                continue;
            }
            for (String path : deliveredFiles(earlier)) {
                if (!mayWrite(task, path)
                        && PathPolicy.check(path, Set.of(path), task.acceptanceTestDir(), null)
                            .allowed()) {
                    owners.putIfAbsent(path, earlier.task().title());
                }
            }
        }
        if (owners.isEmpty()) {
            return null;
        }
        Set<String> inAll = null;
        Set<String> inAny = new LinkedHashSet<>();
        for (RuleDispute dispute : disputes) {
            Set<String> named = namedBy(dispute, owners.keySet());
            if (named.isEmpty()) {
                return null;
            }
            inAny.addAll(named);
            if (inAll == null) {
                inAll = new LinkedHashSet<>(named);
            } else {
                inAll.retainAll(named);
            }
        }
        Set<String> files = inAll == null || inAll.isEmpty() ? inAny : inAll;
        if (files.isEmpty() || files.size() > MAX_FILES) {
            return null;
        }
        Map<String, String> by = new LinkedHashMap<>();
        for (String file : files) {
            by.put(file, owners.get(file));
        }
        return new Defect(List.copyOf(files), by, List.copyOf(disputes));
    }

    /** Every dispute any of these candidates raised. */
    static List<RuleDispute> disputesOf(List<CandidateSolution> candidates) {
        List<RuleDispute> all = new ArrayList<>();
        if (candidates != null) {
            for (CandidateSolution candidate : candidates) {
                all.addAll(candidate.ruleDisputes());
            }
        }
        return all;
    }

    /** What the earlier winner changed; with no diff to read, the literal files of its write set. */
    private static Set<String> deliveredFiles(Delivered earlier) {
        Set<String> files = new LinkedHashSet<>();
        String diff = earlier.winner() == null ? null : earlier.winner().diffUnified();
        for (String path : UnifiedDiffPaths.addedOrChanged(diff)) {
            files.add(path.replace('\\', '/'));
        }
        if (files.isEmpty() && earlier.task().writeSet() != null) {
            for (String entry : earlier.task().writeSet()) {
                if (entry == null) {
                    continue;
                }
                String path = entry.replace('\\', '/').strip();
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.indexOf('.') > 0) { // a file, not a directory: only a file can be named
                    files.add(path);
                }
            }
        }
        return files;
    }

    private static boolean mayWrite(Task task, String path) {
        // An empty write set is unrestricted: such a task could have fixed the file itself.
        return PathPolicy.check(path, task.writeSet(), null, null).allowed();
    }

    /** The delivered files this dispute's reason or evidence names. */
    private static Set<String> namedBy(RuleDispute dispute, Set<String> deliveredFiles) {
        Set<String> byPath = new LinkedHashSet<>();
        Set<String> byName = new LinkedHashSet<>();
        Matcher m = PATH.matcher(dispute.reason() + "\n" + dispute.evidence());
        while (m.find()) {
            String token = m.group().replace('\\', '/');
            while (token.startsWith("./")) {
                token = token.substring(2);
            }
            boolean hasDirectory = token.indexOf('/') >= 0;
            for (String file : deliveredFiles) {
                if (hasDirectory) {
                    if (file.equals(token) || file.endsWith("/" + token)
                            || token.endsWith("/" + file)) {
                        byPath.add(file);
                    }
                } else if (file.equals(token) || file.endsWith("/" + token)) {
                    byName.add(file);
                }
            }
        }
        return byPath.isEmpty() ? byName : byPath;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
