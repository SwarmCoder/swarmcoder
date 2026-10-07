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

import com.swarmcoder.domain.ConstraintBrief;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.LearnedGuideline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of the project's house rules the judge is shown, and what it is told about the rest.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>The judge's brief is capped, and the cap is right: the judge is reading a DIFF, and a long
 * rulebook that crowds the diff out of the window makes the judge worse at the only thing it is
 * for. What was wrong was not the size, it was the SELECTION. The rules arrived as one already
 * concatenated string and the first {@code n} characters of it were taken — so which rules the
 * judge enforced was decided by the order the rules happened to be rendered in, which is scope
 * then confidence then filename. Measured on the operator's live project: 28,441 characters of
 * active rules and a 3,000-character window, so the judge was shown about a tenth of the rules
 * and then instructed, in the same prompt, to "check the diff against every rule".
 *
 * <p>Raising the cap alone only moves the line. A project can always have more rules than fit, so
 * something has to decide which ones go in, and "whatever sorted first" is not a decision.
 *
 * <h2>What decides now</h2>
 *
 * <p>In order, each tier beating everything below it:
 *
 * <ol>
 *   <li><b>A person said it.</b> A rule whose provenance is {@code stated} (read out of a document
 *       the operator ticked) or {@code human} (written into the file by hand) outranks one a model
 *       proposed after reading a transcript. The operator's own words are the rules the judge
 *       exists to enforce; a machine proposal is a suggestion that has not decayed yet.</li>
 *   <li><b>It is about this diff.</b> A rule that shares a distinctive word with the change in
 *       front of the judge outranks one that does not. A rule about the browser client is not what
 *       decides a diff that only touches storage, and spending the window on it costs a rule that
 *       would have decided it.</li>
 *   <li><b>Scope, then confidence, then slug</b> — the same order the workers' copy is built in, so
 *       the judge and the worker read the same rules in the same order, and the result is
 *       deterministic rather than dependent on map iteration.</li>
 * </ol>
 *
 * <h2>And what it is told about anything dropped</h2>
 *
 * <p>Never a bare "[truncated]". A rule that did not fit is still IN FORCE, and a judge that does
 * not know it exists cannot even ask. Every rule left out is NAMED — its title, one per line — with
 * a sentence saying they were left out for space and not because they stopped applying. A name is
 * enough for the judge to notice that a diff is walking into something it cannot read, and to say
 * so in the rationale the operator reads.
 */
final class JudgeRules {

    /** Names alone are cheap; past this many the notice itself starts crowding the diff. */
    private static final int MAX_NAMES_LISTED = 60;

    /** A word shorter than this carries no signal about which rule a diff is about. */
    private static final int MIN_SIGNIFICANT_WORD = 4;

    /**
     * Words that appear in almost every Java diff and almost every rule, so a match on one of them
     * says nothing at all about whether this rule is about this change.
     */
    private static final Set<String> NOISE = Set.copyOf(List.of(
        "java", "class", "code", "file", "files", "this", "that", "then", "than", "with", "from",
        "into", "must", "never", "always", "when", "what", "where", "which", "will", "would",
        "should", "public", "private", "static", "final", "void", "return", "import", "package",
        "project", "repository", "build", "test", "tests", "using", "used", "use", "make", "made",
        "does", "doing", "have", "here", "there", "they", "them", "your", "you", "not", "and",
        "the", "for", "are", "its", "it's", "new", "one", "two", "all", "any", "each", "every",
        "only", "also", "same", "other", "than", "over", "under", "before", "after", "instead",
        "rather", "because", "about", "line", "lines", "add", "adds", "added", "change", "changed",
        "changes", "work", "works", "run", "runs", "diff", "task", "rule", "rules"));

    private JudgeRules() {}

    /**
     * The rule section of the judge's brief, or "" when there are no rules.
     *
     * @param rules       the project's ACTIVE rules, already resolved by {@code ProjectRules}
     * @param diff        the candidate's unified diff — what the judge is actually reading
     * @param maxChars    the window the rules may occupy
     */
    static String render(List<LearnedGuideline> rules, String diff, int maxChars) {
        List<LearnedGuideline> ranked = ranked(rules, significantWords(diff));
        if (ranked.isEmpty()) {
            return "";
        }
        List<LearnedGuideline> shown = new ArrayList<>();
        List<LearnedGuideline> dropped = new ArrayList<>();
        String rendered = "";
        for (LearnedGuideline rule : ranked) {
            if (!dropped.isEmpty()) {
                dropped.add(rule); // once one has not fitted, order is what decides the rest
                continue;
            }
            List<LearnedGuideline> attempt = new ArrayList<>(shown);
            attempt.add(rule);
            String next = ConstraintBrief.render(attempt, Integer.MAX_VALUE);
            if (next.length() > maxChars && !shown.isEmpty()) {
                dropped.add(rule);
                continue;
            }
            shown = attempt;
            rendered = next;
        }
        if (rendered.isEmpty()) {
            // One rule alone is longer than the whole window. Showing it truncated is still better
            // than showing nothing — but say that it was cut, so nobody reads half a rule as whole.
            String first = ConstraintBrief.render(List.of(ranked.get(0)), maxChars);
            if (first.isEmpty()) {
                return ""; // rules with nothing in them: an empty heading is worse than no heading
            }
            rendered = first
                + "  (this rule is longer than the space available and is cut off here)\n";
            dropped.clear();
            dropped.addAll(ranked.subList(1, ranked.size()));
        }
        return rendered + notShown(dropped);
    }

    /** The rules, best first. See the class javadoc for what "best" means and why. */
    static List<LearnedGuideline> ranked(List<LearnedGuideline> rules, Set<String> diffWords) {
        List<LearnedGuideline> ranked = new ArrayList<>();
        for (LearnedGuideline rule : rules == null ? List.<LearnedGuideline>of() : rules) {
            if (rule != null) {
                ranked.add(rule);
            }
        }
        ranked.sort(Comparator
            .comparing((LearnedGuideline r) -> !statedByAPerson(r))          // people first
            .thenComparing(r -> !touchesThisDiff(r, diffWords))              // relevant first
            .thenComparing(Comparator.comparingInt((LearnedGuideline r) -> scopeRank(r.scope()))
                .reversed())
            .thenComparing(Comparator.comparingDouble(LearnedGuideline::confidence).reversed())
            .thenComparing(r -> r.slug() == null ? "" : r.slug()));
        return ranked;
    }

    /**
     * The sentence naming what the judge cannot see. Empty when everything fitted.
     *
     * <p>It says the rules are still in force, because the failure mode of any "truncated" marker
     * is a reader concluding that what was cut did not matter.
     */
    private static String notShown(List<LearnedGuideline> dropped) {
        if (dropped.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nALSO IN FORCE, but there was no room to print the "
            + "wording — these rules apply to this change exactly as much as the ones above, and "
            + "they were left out for space alone. If the diff looks like it is walking into one "
            + "of them, say so in your rationale and say that you could not read its wording:\n");
        int shown = 0;
        for (LearnedGuideline rule : dropped) {
            if (shown++ >= MAX_NAMES_LISTED) {
                sb.append("  - and ").append(dropped.size() - MAX_NAMES_LISTED)
                    .append(" more, also not named here\n");
                break;
            }
            sb.append("  - ").append(name(rule)).append('\n');
        }
        return sb.toString();
    }

    private static String name(LearnedGuideline rule) {
        if (rule.title() != null && !rule.title().isBlank()) {
            return rule.title().strip();
        }
        if (rule.slug() != null && !rule.slug().isBlank()) {
            return rule.slug().strip();
        }
        String body = rule.markdownBody() == null ? "" : rule.markdownBody().strip();
        return body.length() <= 80 ? body : body.substring(0, 80) + "…";
    }

    /**
     * Whether a person decided this rule. {@code stated} is a rule read out of a document the
     * operator ticked; {@code human} is one written into the file by hand. Anything else — in
     * practice {@code extraction} — is a model's proposal, and a proposal loses to a decision.
     */
    static boolean statedByAPerson(LearnedGuideline rule) {
        String source = rule.provenance() == null ? null : rule.provenance().source();
        return source == null || !"extraction".equalsIgnoreCase(source.strip());
    }

    /** Whether this rule and this diff share a word distinctive enough to mean anything. */
    static boolean touchesThisDiff(LearnedGuideline rule, Set<String> diffWords) {
        if (diffWords.isEmpty()) {
            return false;
        }
        for (String word : significantWords(rule.title() + " " + rule.markdownBody())) {
            if (diffWords.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The words of a text that could identify what it is about: four letters or more, lower case,
     * with the vocabulary every Java diff and every rule shares thrown away. Identifiers are split
     * on case as well as punctuation, so {@code BookServiceImpl} contributes "book" and "service".
     */
    static Set<String> significantWords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        String split = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String token : split.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= MIN_SIGNIFICANT_WORD && !NOISE.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    /** More specific scopes rank higher, matching how the workers' copy is ordered. */
    private static int scopeRank(GuidelineScope scope) {
        if (scope == null) {
            return 1;
        }
        return switch (scope) {
            case TASK_FAMILY -> 2;
            case PROJECT -> 1;
            case GLOBAL -> 0;
        };
    }

}
