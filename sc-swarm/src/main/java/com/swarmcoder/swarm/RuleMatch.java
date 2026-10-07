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

import com.swarmcoder.domain.LearnedGuideline;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which stated rule a sentence names — a judge's violation, a judge's "disputed" entry, or a
 * worker's dispute. Both arrive as free text, so this is the one place that turns "the rule a
 * model wrote about" into the rule object that says how hard it is and why it exists.
 *
 * <p>Deliberately literal. The judge is asked to begin every entry with the rule's name exactly
 * as its brief listed it, so the name is normally there verbatim; failing that, the words before
 * the first colon are compared with each rule's name. Anything still unmatched is NOT guessed at:
 * a sentence that cannot be pinned to a rule cannot be pinned to a HARD rule either, and only a
 * hard rule may stop a task (harness runs 53 and 55, 2026-10-01).
 */
final class RuleMatch {

    private RuleMatch() {}

    /** The rule this sentence names, or null when none can be told apart from the rest. */
    static LearnedGuideline named(String sentence, List<LearnedGuideline> rules) {
        if (sentence == null || sentence.isBlank() || rules == null || rules.isEmpty()) {
            return null;
        }
        String text = normalize(sentence);
        LearnedGuideline best = null;
        int bestLength = 0;
        for (LearnedGuideline rule : rules) {
            for (String name : namesOf(rule)) {
                if (name.length() > bestLength && text.contains(name)) {
                    best = rule;
                    bestLength = name.length();
                }
            }
        }
        if (best != null) {
            return best;
        }
        // The judge paraphrased the name: compare the words before the colon with each name's.
        Set<String> head = JudgeRules.significantWords(headOf(sentence));
        if (head.isEmpty()) {
            return null;
        }
        LearnedGuideline only = null;
        for (LearnedGuideline rule : rules) {
            Set<String> words = JudgeRules.significantWords(rule.title() + " "
                + (rule.slug() == null ? "" : rule.slug().replace('-', ' ')));
            if (!words.isEmpty() && words.containsAll(head)) {
                if (only != null) {
                    return null; // two rules fit equally well: not a match, a guess
                }
                only = rule;
            }
        }
        return only;
    }

    /**
     * Whether two sentences name the same rule. With rule objects, they must resolve to the same
     * one; without (no rule set was wired in), the words before the colon must agree.
     */
    static boolean sameRule(String a, String b, List<LearnedGuideline> rules) {
        if (rules != null && !rules.isEmpty()) {
            LearnedGuideline left = named(a, rules);
            return left != null && left == named(b, rules);
        }
        String left = normalize(headOf(a));
        String right = normalize(headOf(b));
        return !left.isEmpty() && !right.isEmpty()
            && (left.contains(right) || right.contains(left));
    }

    /** The rule's name as a person reads it: its title, else its slug, else the start of it. */
    static String nameOf(LearnedGuideline rule) {
        if (rule.title() != null && !rule.title().isBlank()) {
            return rule.title().strip();
        }
        if (rule.slug() != null && !rule.slug().isBlank()) {
            return rule.slug().strip();
        }
        String body = rule.markdownBody() == null ? "" : rule.markdownBody().strip();
        return body.length() <= 80 ? body : body.substring(0, 80) + "…";
    }

    /** The words before the first colon — where the judge is asked to put the rule's name. */
    static String headOf(String sentence) {
        if (sentence == null) {
            return "";
        }
        int colon = sentence.indexOf(':');
        return (colon > 0 ? sentence.substring(0, colon) : sentence).strip();
    }

    private static List<String> namesOf(LearnedGuideline rule) {
        String title = rule.title() == null ? "" : normalize(rule.title());
        String slug = rule.slug() == null ? "" : normalize(rule.slug());
        String spacedSlug = rule.slug() == null ? "" : normalize(rule.slug().replace('-', ' '));
        return List.of(title, slug, spacedSlug).stream()
            .filter(name -> name.length() >= 4)
            .toList();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replaceAll("[`'\"“”‘’]", "").replaceAll("\\s+", " ").strip();
    }
}
