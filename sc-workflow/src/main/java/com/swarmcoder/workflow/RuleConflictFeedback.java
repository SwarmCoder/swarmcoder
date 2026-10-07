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

import com.swarmcoder.knowledge.Librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bare "conflicts with rule '&lt;title&gt;'" objection — the one shape both
 * {@link ForbiddenTechGuard} and {@link DesignReviewerClient#reviewPlan}/{@code reviewDesign}
 * ever raise — into something an architect can actually act on: the rule's own wording, and the
 * nearest existing code in this codebase that does the thing the rule asks for instead.
 *
 * <p><b>Why this exists</b> (harness run 19, 2026-09-04). A design that named client-side
 * {@code localStorage} was sent back with nothing but "task 'Implement Client-Side Persistence'
 * conflicts with rule 'EclipseStore object graph persistence'" — three times running, at PLAN,
 * because the design itself was never sent back and kept saying the same thing. The bare sentence
 * says WHAT is wrong and never what to do instead, and an architect with no rule text and no
 * example in front of it has nothing to revise FROM except its own first guess, worded
 * differently. This is the one place that gap is closed: both the DESIGN_REVIEW rule check and the
 * PLAN-versus-rules retry feed every objection through {@link #enrich} before it is ever shown to
 * a model again.
 *
 * <p>An objection that is not rule-conflict shaped — a completeness note, a shape-validator
 * violation — is carried through byte for byte. This only ever adds to a rule-conflict objection,
 * never invents one.
 */
final class RuleConflictFeedback {

    private RuleConflictFeedback() {}

    /** The one shape every rule-conflict objection shares, from either detector. */
    private static final Pattern RULE_TITLE = Pattern.compile("conflicts with rule '([^']+)'");

    /** The rule's own title and body, quoted back — not the whole standing rules brief. */
    private static final int RULE_EXCERPT_CHARS = 700;

    /** The nearest example: a path and enough of it to see the idiom, not the whole file. */
    private static final int EXAMPLE_CHARS = 600;

    /** Whether an objection names a rule it conflicts with, in the shared detection shape. */
    static boolean isRuleConflict(String objection) {
        return objection != null && RULE_TITLE.matcher(objection).find();
    }

    /** The rule's title as the objection itself names it, or null when it names none. */
    static String ruleTitleOf(String objection) {
        if (objection == null) {
            return null;
        }
        Matcher matcher = RULE_TITLE.matcher(objection);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    /**
     * @param objections the raw objections a reviewer or {@link ForbiddenTechGuard} returned;
     *                    anything not rule-conflict shaped is returned unchanged
     * @param rulesBrief  the project's rendered rules — where the named rule's own wording is read
     *                    from
     * @param librarian   nullable; no example is appended when there is none wired in
     * @return a new list, same size and order as {@code objections}
     */
    static List<String> enrich(List<String> objections, String rulesBrief, Librarian librarian) {
        if (objections == null || objections.isEmpty()) {
            return objections == null ? List.of() : objections;
        }
        List<String> enriched = new ArrayList<>(objections.size());
        for (String objection : objections) {
            enriched.add(enrichOne(objection, rulesBrief, librarian));
        }
        return enriched;
    }

    private static String enrichOne(String objection, String rulesBrief, Librarian librarian) {
        String title = ruleTitleOf(objection);
        if (title == null) {
            return objection;
        }
        StringBuilder sb = new StringBuilder(objection);
        String chunk = ForbiddenTechGuard.chunkFor(rulesBrief, title);
        if (!chunk.isBlank()) {
            sb.append("\n  THE RULE, IN FULL — '").append(title).append("': ")
                .append(trim(chunk, RULE_EXCERPT_CHARS));
        }
        String example = nearestExample(librarian, title);
        if (!example.isBlank()) {
            sb.append("\n  THE NEAREST EXISTING EXAMPLE OF DOING THIS RIGHT: ")
                .append(trim(example, EXAMPLE_CHARS));
        }
        return sb.toString();
    }

    /**
     * The nearest existing code for the rule's subject, via the Librarian's own public lookup
     * ({@link Librarian#lookupApi}) — the same call a stuck worker makes, asked with the rule's
     * title as the question. "" when nothing is wired, nothing was found, or the lookup itself
     * failed — this must never be the reason a revision or a retry cannot be attempted.
     */
    private static String nearestExample(Librarian librarian, String subject) {
        if (librarian == null || subject == null || subject.isBlank()) {
            return "";
        }
        try {
            String answer = librarian.lookupApi(subject);
            return answer == null ? "" : sourceSnippet(answer);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The first {@code ## Source: <path>} block of a {@code lookup_api} answer — a real file's
     * address and its shaped body — trimmed to a quote. "" when the answer was documentation only
     * and named no file, which is an honest empty, not a failure.
     */
    private static String sourceSnippet(String answer) {
        int at = answer.indexOf("## Source: ");
        return at < 0 ? "" : answer.substring(at).strip();
    }

    private static String trim(String text, int max) {
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
