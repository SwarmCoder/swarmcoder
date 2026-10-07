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

import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cheap, no-model check for the one shape of rule violation a model call is never needed to
 * catch: a task instruction that names, in a "use/add/implement/via/with X" position, one of the
 * specific technologies the project's stated rules forbid.
 *
 * <p><b>Why this runs before {@link DesignReviewerClient#reviewPlan}.</b> Harness run 10
 * (2026-09-03) parked a wave on a plan whose second task instructed "implement client-side
 * localStorage persistence for books and ratings" against rules that forbid REST and JSON, among
 * others. A plan naming the same forbidden word the rule names, in a sentence that adds it, needs
 * no judgement — it needs a word search. Running that first, for free, and calling the model only
 * when it finds nothing is what keeps the reviewer to one call per plan attempt.
 *
 * <p><b>The forbidden names come from the project's rules, never from this class</b> (owner
 * decision after the audit of 2026-10-02). Until then a fixed list of about ten product names
 * lived here, so a project that forbade anything else was not served and the product carried
 * one project's stack in its code. A rule that forbids a technology names it; {@link
 * #forbiddenTerms} reads the names out of the rule's own prohibition and nothing else. A project
 * with no such rule forbids nothing.
 *
 * <p><b>Deliberately narrow.</b> A false positive parks a run over nothing that ever needed
 * re-planning, so a name is taken only from the list a prohibition governs ("do not use A, B or
 * C", "none of them will be added: A; B", "A and B are forbidden"), only when it leads its list
 * item ("REST endpoints" names REST; "any other web UI framework" names nothing), and never from
 * a prohibition the rule confines to a place ("not allowed in a field of ..."). Missing a vaguer
 * violation is the safe failure - {@link DesignReviewerClient#reviewPlan} is what catches it;
 * flagging a task that never asked for a forbidden thing is not.
 *
 * <p><b>The cue-word position is also the precision rule.</b> A task instruction that quotes the
 * rule back at the worker — "do not use REST for this, use the WebSocket transport" — contains the
 * same "use REST" substring a genuine violation would. Matching only "introduces-a-thing" cue
 * words immediately before the term, and then checking a short window before that match for a
 * negation ("not", "never", "don't", …), is what tells "the task says not to" from "the task says
 * to".
 */
final class ForbiddenTechGuard {

    private ForbiddenTechGuard() {}

    /**
     * A prohibition whose object is what follows it: "do not use A, B or C". The verb is part of
     * the phrase on purpose - "must not be renamed" forbids no technology.
     */
    private static final Pattern FORBIDS_WHAT_FOLLOWS = Pattern.compile(
        "\\b(?:do not|don't|never|must not|must never|may not|shall not|cannot|can not)\\s+use\\b"
            + "|\\bnone of (?:them|these|those)\\b",
        Pattern.CASE_INSENSITIVE);

    /** A prohibition stated about a list: "A and B are forbidden", "Forbidden: A, B". */
    private static final Pattern FORBIDDEN_WORD = Pattern.compile(
        "\\b(?:forbidden|prohibited|banned|not permitted|not allowed)\\b",
        Pattern.CASE_INSENSITIVE);

    /** What makes a prohibition a local one: "not allowed in a field of ...". */
    private static final Pattern CONFINED = Pattern.compile(
        "^\\s*(?:in|inside|within|for|on|when|where|between|outside)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern COPULA_AT_END = Pattern.compile(
        "\\b(?:is|are|was|were|remains?|stays?)(?:\\s+\\w+ly)?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ITEM_SEPARATOR = Pattern.compile("[;,]|\\s+(?:or|and|nor)\\s+");

    private static final Pattern LEADING_FILLER = Pattern.compile(
        "^(?:(?:a|an|the|any|all|other|or|and|nor|of|these|those|following)\\s+)+",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern HEAD_TOKEN = Pattern.compile("^@?[A-Za-z][\\w.+#-]*");

    /** Words that can lead a list item with a capital and name nothing. */
    private static final Set<String> NOT_A_NAME = Set.of(
        "nothing", "none", "this", "that", "these", "those", "it", "they", "them", "everything",
        "anything", "something", "what", "which", "such", "both", "each", "every", "no", "not",
        "never", "do", "if", "when", "where", "use", "there", "here", "also", "only");

    /** {@code %s} is the quoted term; formatted per term in {@link #introducesPattern}. */
    private static final String INTRODUCE_CUE =
        "(?i:\\b(?:use|uses|using|add|adds|adding|implement|implements|implementing|via|with)\\s+"
            + "(?:an?\\s+|the\\s+)?)%s\\b";

    private static final Pattern NEGATION = Pattern.compile(
        "\\b(not|never|no|don't|doesn't|cannot|can't|won't|without)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @param rulesBrief the project's stated rules, rendered exactly as every worker reads them
     *                   ({@link com.swarmcoder.domain.ConstraintBrief}) — "" or blank finds
     *                   nothing, because nothing was stated to check the plan against
     * @param tasks      the candidate plan's tasks
     * @return one objection per task/term pair found, worded to match
     *         {@link DesignReviewerClient#reviewPlan}'s own shape — the two feed the same re-ask
     *         path and must read as one voice; empty when nothing was found
     */
    static List<String> check(String rulesBrief, List<Task> tasks) {
        List<String> objections = new ArrayList<>();
        if (rulesBrief == null || rulesBrief.isBlank() || tasks == null || tasks.isEmpty()) {
            return objections;
        }
        for (String chunk : ruleChunks(rulesBrief)) {
            String ruleTitle = titleOf(chunk);
            for (String term : forbiddenTerms(chunk)) {
                Pattern introduces = introducesPattern(term);
                for (Task task : tasks) {
                    if (nameableInInstructions(introduces, task.instructions())) {
                        String title = task.title() == null ? "" : task.title();
                        objections.add("task '" + title + "' conflicts with rule '" + ruleTitle
                            + "': its instructions call for " + term
                            + ", which this rule forbids");
                    }
                }
            }
        }
        return objections;
    }

    /** The number of rule entries a rendered brief carries — for a "reviewed against N rule(s)"
     * log line shared with the reviewer path, so both count rules the same way. */
    static int ruleCount(String rulesBrief) {
        return ruleChunks(rulesBrief).size();
    }

    /**
     * The rendered chunk (title and body, as the brief itself states them) of the one rule named
     * {@code ruleTitle} — what {@link RuleConflictFeedback} feeds back to an architect that has
     * just been told its design or plan conflicts with a rule it was only ever handed by name.
     *
     * <p>Matched loosely on purpose: a reviewer echoes the rule's title back inside a sentence it
     * composed itself ({@code DesignReviewerClient#reviewPlan}/{@code reviewDesign}'s own JSON
     * shape asks for it, never for a verbatim copy), so an exact match is the common case and a
     * title one contains the other is the fallback for when it is not quite verbatim.
     *
     * @return the chunk, or "" when the brief has no rule by that name (a blank title, a blank
     *         brief, or a name the reviewer paraphrased past recognition)
     */
    static String chunkFor(String rulesBrief, String ruleTitle) {
        if (rulesBrief == null || rulesBrief.isBlank() || ruleTitle == null || ruleTitle.isBlank()) {
            return "";
        }
        String wanted = ruleTitle.strip().toLowerCase(Locale.ROOT);
        for (String chunk : ruleChunks(rulesBrief)) {
            String title = titleOf(chunk).toLowerCase(Locale.ROOT);
            if (!title.isEmpty()
                    && (title.equals(wanted) || title.contains(wanted) || wanted.contains(title))) {
                return chunk;
            }
        }
        return "";
    }

    private static boolean nameableInInstructions(Pattern introduces, String instructions) {
        if (instructions == null || instructions.isBlank()) {
            return false;
        }
        Matcher matcher = introduces.matcher(instructions);
        while (matcher.find()) {
            if (!isNegated(instructions, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNegated(String text, int matchStart) {
        int windowStart = Math.max(0, matchStart - 24);
        return NEGATION.matcher(text.substring(windowStart, matchStart)).find();
    }

    /**
     * The names one rule forbids, in the order the rule names them - read from the rule's own
     * prohibitions and from nowhere else. Empty for a rule that prohibits nothing by name.
     *
     * @param chunk one rule as rendered (title line, then its wording)
     */
    static List<String> forbiddenTerms(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : chunk.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("- ")) {
                line = line.substring(2);
            }
            // What the rule is for and how it is checked are not its wording.
            if (line.startsWith("Why it exists:") || line.startsWith("This rule is CHECKED")
                    || line.startsWith("A HARD rule:") || line.startsWith("A preference:")) {
                continue;
            }
            // A remark in brackets qualifies an item; it is never an item.
            String previous;
            do {
                previous = line;
                line = line.replaceAll("\\([^()]*\\)", " ");
            } while (!line.equals(previous));
            for (String sentence : line.split("(?<=[.!?])\\s+(?=[A-Z@])")) {
                termsOfSentence(sentence, terms);
            }
        }
        return List.copyOf(terms);
    }

    private static void termsOfSentence(String sentence, Set<String> terms) {
        Matcher follows = FORBIDS_WHAT_FOLLOWS.matcher(sentence);
        int lastEnd = -1;
        while (follows.find()) {
            lastEnd = follows.end();
        }
        if (lastEnd >= 0) {
            String after = sentence.substring(lastEnd);
            int colon = after.indexOf(':');
            namesIn(colon >= 0 ? after.substring(colon + 1) : after, terms);
            return;
        }
        Matcher word = FORBIDDEN_WORD.matcher(sentence);
        if (!word.find()) {
            return;
        }
        String after = sentence.substring(word.end());
        if (CONFINED.matcher(after).find()) {
            return;
        }
        int colon = after.indexOf(':');
        if (colon >= 0) {
            namesIn(after.substring(colon + 1), terms);
            return;
        }
        String before = sentence.substring(0, word.start());
        Matcher copula = COPULA_AT_END.matcher(before);
        if (copula.find()) {
            namesIn(before.substring(0, copula.start()), terms);
        }
    }

    private static void namesIn(String list, Set<String> terms) {
        for (String item : ITEM_SEPARATOR.split(list)) {
            String stripped = LEADING_FILLER.matcher(item.strip()).replaceFirst("");
            Matcher head = HEAD_TOKEN.matcher(stripped);
            if (!head.find()) {
                continue;
            }
            String name = head.group().replaceAll("[.+#-]+$", "");
            if (name.startsWith("@")) {
                name = name.substring(1);
            }
            if (isAName(name)) {
                terms.add(name);
            }
        }
    }

    /**
     * A name is written like one: with a capital somewhere (Hibernate, JSON, TypeScript), or as
     * an identifier (maven-shade-plugin, log4j). An ordinary lower-case word names nothing.
     */
    private static boolean isAName(String token) {
        if (token.length() < 2 || NOT_A_NAME.contains(token.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (token.chars().anyMatch(Character::isUpperCase)) {
            return true;
        }
        return token.length() >= 3 && token.matches(".*[a-z][-._\\d]+[a-z\\d].*");
    }

    private static Pattern introducesPattern(String term) {
        return Pattern.compile(String.format(INTRODUCE_CUE, termPattern(term)));
    }

    /**
     * How a term is matched. A name is matched whatever its case ({@code hibernate}); an acronym
     * only as written ({@code REST}, {@code JSON}), because in lower case it is an ordinary word:
     * "submit it with the rest of the form" is not a plan to use REST, and read as one it parked
     * a run over nothing (audit of 2026-10-02).
     */
    private static String termPattern(String term) {
        boolean acronym = term.equals(term.toUpperCase(Locale.ROOT));
        return acronym ? Pattern.quote(term) : "(?i:" + Pattern.quote(term) + ")";
    }

    /**
     * Splits a rendered rules brief back into one chunk per rule — the same "- " bullet
     * convention {@code RulesVersusManifest#splitRules} reads, re-implemented here because that
     * method is package-private to {@code sc-knowledge}. A brief with no bullets (just the
     * standing header, or blank) yields no chunks: there is nothing to call a forbidding rule.
     */
    static List<String> ruleChunks(String rulesBrief) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean sawBullet = false;
        for (String line : rulesBrief.split("\n", -1)) {
            if (line.startsWith("- ")) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                }
                current = new StringBuilder(line);
                sawBullet = true;
            } else if (sawBullet) {
                current.append('\n').append(line);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    static String titleOf(String chunk) {
        int newline = chunk.indexOf('\n');
        String firstLine = newline < 0 ? chunk : chunk.substring(0, newline);
        String title = firstLine.startsWith("- ") ? firstLine.substring(2) : firstLine;
        return title.strip();
    }
}
