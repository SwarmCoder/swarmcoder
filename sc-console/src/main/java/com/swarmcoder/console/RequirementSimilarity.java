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
package com.swarmcoder.console;

import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How alike two pieces of requirement wording are — in code, deterministically, without asking a
 * model (DEVELOPER_CORRECTIONS.md §21).
 *
 * <p><b>Why this exists.</b> The analyst is told, at length, not to re-propose a requirement the
 * BRD already holds, and it does it anyway. The mechanism meant to catch that compared the two
 * titles for EXACT equality after stripping punctuation, so "Multiply two whole numbers" and
 * "Multiplication of two integers" were not duplicates to it — which is the case that actually
 * happens. This replaces equality with similarity. It is bounded by the number of proposals in one
 * reply times the number of requirements in the graph, all in memory, and it gives the same answer
 * every time it is asked.
 *
 * <p><b>Why an analyser and not an index.</b> A BRD is thousands of short records already in this
 * process, so scanning them is instant; an index would have to be kept in step with a graph that is
 * edited constantly, and a stale index answers confidently and wrongly with nothing to show that it
 * did. Lucene's ENGLISH ANALYSIS is used — its tokeniser, its stop words, its English stemmer —
 * and none of its indexing.
 *
 * <p><b>Why stemming alone is not enough, measured.</b> Lucene's English stemmer reduces
 * "multiply" to {@code multipli} and "multiplication" to {@code multipl}: close, but not equal, so
 * exact stem comparison still misses the pair that motivated this work. The same happens for
 * notify/notification ({@code notifi}/{@code notif}), verify/verification and deliver/delivery. So
 * two stems also count as the same word when the shorter is a prefix of the longer AND is at least
 * {@link #MIN_PREFIX} characters long. The length floor is what keeps "data" from matching
 * "database" and "sign" from matching "signal".
 *
 * <p><b>Why no inverse-document-frequency weighting.</b> It was built and measured against the same
 * pairs and it did not separate them better — and it has a cost this does not: a score that depends
 * on the rest of the graph changes when unrelated requirements are added, so the same two sentences
 * would be flagged one day and not the next, for reasons nobody can see. The score here depends on
 * nothing but the two pieces of text.
 */
final class RequirementSimilarity {

    /**
     * Reusable and thread-safe: {@link Analyzer} keeps its token stream per thread, which matters
     * because intake runs on its own worker thread while RMI calls arrive on others.
     */
    private static final Analyzer ENGLISH = new EnglishAnalyzer();

    /**
     * The shortest stem that may match by prefix. Five is what the measurements settled on:
     * {@code multipl} (7), {@code notif} (5), {@code verif} (5) and {@code deliv} (5) are the
     * true matches that need it, and four would let {@code data} match {@code databas}.
     */
    private static final int MIN_PREFIX = 5;

    /**
     * At or above this, the proposal is shown to the operator against the requirement it resembles
     * and left unticked.
     *
     * <p>Measured, not guessed. Over fifteen hand-labelled pairs the true near-duplicates score
     * 0.54 to 1.00 and the plainly-different pairs 0.00 to 0.60, so no threshold separates them
     * perfectly. 0.50 catches seven of the eight true pairs; the three "different" pairs it also
     * catches are requirements that really do sit next to each other in the same area
     * ("Multiply two whole numbers" against "Divide two whole numbers"), and telling the operator
     * "this resembles R13" about those is information, not noise. Erring this way is deliberate: a
     * flag costs one tick to undo, a missed duplicate costs a requirement written twice.
     */
    static final double NEAR_MATCH = 0.50;

    /**
     * At or above this, the proposal is not merely similar — it is the same requirement said again,
     * and it is re-cast as a change to the one that exists rather than a second copy of it.
     *
     * <p>Set well clear of the worst false positive measured (0.60), because this tier does more
     * than flag: it points the proposal at an existing handle. Getting that wrong would offer the
     * operator a rewrite of the wrong requirement.
     */
    static final double SAME_REQUIREMENT = 0.85;

    /**
     * How much of a QUESTION's wording a proposal has to reuse before it counts as drafted from it.
     *
     * <p>A different measure from {@link #NEAR_MATCH}, because it is a different question. Two
     * requirements are alike when they overlap BOTH ways; a proposal drafted from an unanswered
     * question absorbs the question's wording while adding a good deal of its own, and overlap
     * measured both ways punishes it for that. So this asks only one thing: how much of what the
     * question was about does the proposal now say?
     *
     * <p>Measured on the run this rule was written for. The proposal "Handle multiplication at the
     * boundaries of the supported integer range" reuses every content word of the sentence its
     * question quoted — "Multiplication has to behave sensibly at the edges of the range we
     * support" — and scores 1.00. The other proposal from the same reply, the requirement the
     * document genuinely stated, scores 0.17 against the same sentence.
     */
    static final double DRAWN_FROM = 0.60;

    private RequirementSimilarity() {}

    /** One existing requirement that resembles a proposal, and how much. */
    record Hit(BrdRequirement requirement, double score) {}

    /**
     * How alike a proposed requirement and an existing one are, from 0 (nothing in common) to 1.
     *
     * <p>Scored twice — titles alone, then title and statement together — and the better of the two
     * is taken. Titles are short enough that one different word swings the number a long way, and
     * statements are where a reworded restatement shows its overlap; neither is reliable on its own.
     */
    static double score(String titleA, String textA, String titleB, String textB) {
        double titles = overlap(stems(titleA), stems(titleB));
        double whole = overlap(stems(join(titleA, textA)), stems(join(titleB, textB)));
        return Math.max(titles, whole);
    }

    /**
     * How much of {@code wording} is said again by {@code title} and {@code text}, from 0 to 1.
     *
     * <p>One-directional on purpose — see {@link #DRAWN_FROM}. Too little wording to judge (fewer
     * than three content words) scores 0 rather than 1: a two-word question would otherwise be
     * "absorbed" by anything mentioning either word.
     */
    static double containment(String title, String text, String wording) {
        Set<String> of = stems(wording);
        if (of.size() < 3) {
            return 0;
        }
        Set<String> said = stems(join(title, text));
        if (said.isEmpty()) {
            return 0;
        }
        List<String> unclaimed = new ArrayList<>(said);
        int matched = 0;
        for (String word : of) {
            for (int i = 0; i < unclaimed.size(); i++) {
                if (sameWord(word, unclaimed.get(i))) {
                    unclaimed.remove(i);
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / of.size();
    }

    /**
     * The existing requirements that most resemble the given wording, best first, scoring at or
     * above {@code threshold}.
     *
     * <p>A plain scan. Bounded by the size of the graph per call and by the number of calls the
     * caller makes — which is the point: the cost is the operator's proposals, not the document's
     * history.
     */
    static List<Hit> nearest(String title, String text, List<BrdRequirement> existing,
                             double threshold, int max) {
        List<Hit> hits = new ArrayList<>();
        for (BrdRequirement requirement : existing == null ? List.<BrdRequirement>of() : existing) {
            double score = score(title, text, requirement.title(), requirement.text());
            if (score >= threshold) {
                hits.add(new Hit(requirement, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
            // Ties broken by handle so the same graph always produces the same order — a list that
            // reorders itself between two identical runs is a list nobody can write a test against.
            .thenComparing(h -> h.requirement().handle() == null ? "" : h.requirement().handle()));
        return hits.size() <= max ? hits : hits.subList(0, max);
    }

    /** Every requirement in the graph, ranked against some wording. Ties broken by handle. */
    static List<Hit> rank(String title, String text, Brd brd) {
        return nearest(title, text, brd == null || brd.requirements() == null
            ? List.of() : brd.requirements(), 0.0, Integer.MAX_VALUE);
    }

    /**
     * The distinct English stems of a piece of text: tokenised, lower-cased, stripped of possessives
     * and stop words, and stemmed, all by Lucene.
     */
    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        try (TokenStream stream = ENGLISH.tokenStream("requirement", new StringReader(text))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                out.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            // A StringReader cannot fail. If Lucene ever does, an empty stem set means "no match
            // found", which leaves the proposal ticked and visible — never silently dropped.
            return out;
        }
        return out;
    }

    /**
     * Whether two stems stand for the same word. Equal, or the shorter is a prefix of the longer
     * and long enough for that to mean something.
     */
    static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= MIN_PREFIX && longer.startsWith(shorter);
    }

    /**
     * Jaccard overlap over stems, with {@link #sameWord} standing in for equality: how much of
     * everything either side says is said by both.
     */
    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        // One-to-one: a stem on the left may account for at most one stem on the right, so a
        // proposal repeating a word cannot inflate its own score.
        List<String> unclaimed = new ArrayList<>(b);
        int matched = 0;
        for (String left : a) {
            for (int i = 0; i < unclaimed.size(); i++) {
                if (sameWord(left, unclaimed.get(i))) {
                    unclaimed.remove(i);
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / (a.size() + b.size() - matched);
    }

    private static String join(String title, String text) {
        return (title == null ? "" : title) + " " + (text == null ? "" : text);
    }
}
