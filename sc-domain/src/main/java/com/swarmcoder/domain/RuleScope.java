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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which part of a project a rule applies to, and which rules therefore go to a worker (owner's
 * decision 2026-10-07, section 65).
 *
 * <p><b>Why.</b> Live run 89: two workers changed two lines of one server file and were sent the
 * project's 38 rules, the browser client's among them, on each of 27 calls - 2,883 tokens of
 * about 8,500 a call.
 *
 * <p><b>The shape.</b> A scope is a folder from the repository root, written with forward slashes
 * and no slash at either end. A module is its folder, so there is one shape and not two. A rule
 * with no scope is a rule of the whole project.
 *
 * <p><b>Covers.</b> A scope covers a path when the path is the folder or lies under it, or when
 * the path is a folder that contains the scope (a task allowed to write {@code server} may write
 * {@code server/src/main/java/app/store}). Whole path segments only: {@code client} does not
 * cover {@code client-api}.
 *
 * <p><b>Only what a worker is TOLD is narrowed.</b> The architect, the planner, the test author
 * and the reviewer decide which files a piece of work touches, so they read every rule; the judge
 * and the rules' own check commands hold a result to every rule. Nothing here looks at a rule's
 * words: a scope is recorded when the rule is stated and checked against the project's tree.
 */
public final class RuleScope {

    private RuleScope() {}

    /**
     * The rules for work on {@code paths}, out of the rules in force.
     *
     * @param sent    the rules with no scope and the rules whose scope covers one of the paths,
     *                in the order they were given
     * @param inForce how many rules there were to choose from
     */
    public static final class Selection {
        private final List<LearnedGuideline> sent;
        private final int inForce;

        public Selection(List<LearnedGuideline> sent, int inForce) {
            this.sent = sent;
            this.inForce = inForce;
        }

        public List<LearnedGuideline> sent() {
            return sent;
        }

        public int inForce() {
            return inForce;
        }

        /** True when at least one rule was left out. */
        public boolean narrowed() {
            return sent.size() < inForce;
        }
    }

    /**
     * What a worker is sent, and out of how many.
     *
     * @param text    the rules as the worker reads them; null when there are none
     * @param sent    how many rules that is
     * @param inForce how many rules the project has in force
     */
    public static final class Briefing {
        private final String text;
        private final int sent;
        private final int inForce;

        public Briefing(String text, int sent, int inForce) {
            this.text = text;
            this.sent = sent;
            this.inForce = inForce;
        }

        public String text() {
            return text;
        }

        public int sent() {
            return sent;
        }

        public int inForce() {
            return inForce;
        }
    }

    /** As {@link #forPaths(List, Collection, Predicate)}, taking every recorded scope as it is. */
    public static Selection forPaths(List<LearnedGuideline> rules, Collection<String> paths) {
        return forPaths(rules, paths, null);
    }

    /**
     * Chooses the rules for work that may write {@code paths}. No paths (a task with no write
     * set) is work that may be anywhere, and gets every rule.
     *
     * @param known whether a folder is still part of the project, from its tree; a rule with a
     *              recorded folder that is not goes to everyone. Null asks nothing.
     */
    public static Selection forPaths(List<LearnedGuideline> rules, Collection<String> paths,
                                     Predicate<String> known) {
        List<LearnedGuideline> all = new ArrayList<>();
        for (LearnedGuideline rule : rules == null ? List.<LearnedGuideline>of() : rules) {
            if (rule != null) {
                all.add(rule);
            }
        }
        List<String> written = new ArrayList<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            String clean = normalized(path);
            if (clean.isEmpty() && path != null && !path.isBlank()) {
                return new Selection(List.copyOf(all), all.size()); // the repository root itself
            }
            if (!clean.isEmpty()) {
                written.add(clean);
            }
        }
        if (written.isEmpty()) {
            return new Selection(List.copyOf(all), all.size());
        }
        List<LearnedGuideline> sent = new ArrayList<>();
        for (LearnedGuideline rule : all) {
            if (rule.appliesTo().isEmpty() || coversAny(rule.appliesTo(), written)
                    || known != null && !rule.appliesTo().stream()
                        .allMatch(scope -> known.test(normalized(scope)))) {
                sent.add(rule);
            }
        }
        return new Selection(List.copyOf(sent), all.size());
    }

    private static boolean coversAny(List<String> scopes, List<String> paths) {
        for (String scope : scopes) {
            String folder = normalized(scope);
            if (folder.isEmpty()) {
                return true; // a scope that names nothing narrows nothing
            }
            for (String path : paths) {
                if (covers(folder, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Both already {@link #normalized}. */
    static boolean covers(String scope, String path) {
        return path.equals(scope) || path.startsWith(scope + "/") || scope.startsWith(path + "/");
    }

    /**
     * A path as a scope is kept: forward slashes, no {@code ./} at the start, no slash at either
     * end, a trailing {@code /**} or {@code /*} dropped. Empty for nothing, for the repository
     * root, and for a path that climbs out of it.
     */
    public static String normalized(String path) {
        String clean = path == null ? "" : path.strip().replace('\\', '/');
        while (clean.endsWith("/**") || clean.endsWith("/*")) {
            clean = clean.substring(0, clean.lastIndexOf('/'));
        }
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.equals(".") || clean.equals("*") || clean.equals("**")) {
            return "";
        }
        for (String segment : clean.split("/")) {
            if (segment.equals("..")) {
                return "";
            }
        }
        return clean;
    }

    /**
     * The scope a rule is recorded with, from what whoever stated it asked for.
     *
     * <p><b>An unknown part drops the rule to "everyone"; it is not refused and not narrowed to
     * the rest.</b> A rule sent to too many workers costs tokens. A rule kept under a folder the
     * project does not have would be sent to nobody, and a rule narrowed to the entries that
     * happened to be spelled right would be missing from the part the mistyped entry meant.
     *
     * @param asked what was asked for; null or empty is the whole project
     * @param known whether a folder is part of the project, answered from its tree; null knows
     *              nothing, so nothing is kept
     * @return the folders to record, normalized, without repeats and sorted; empty for the whole
     *         project
     */
    public static List<String> checked(Collection<String> asked, Predicate<String> known) {
        Set<String> kept = new LinkedHashSet<>();
        for (String entry : asked == null ? List.<String>of() : asked) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String folder = normalized(entry);
            if (folder.isEmpty() || known == null || !known.test(folder)) {
                return List.of();
            }
            kept.add(folder);
        }
        return kept.stream().sorted().toList();
    }
}
