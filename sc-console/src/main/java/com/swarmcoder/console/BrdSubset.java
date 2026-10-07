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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What part of the requirement graph the analyst is shown, and the sentence that tells it so
 * (DEVELOPER_CORRECTIONS.md §21).
 *
 * <p><b>The problem.</b> Every analyst call rendered the ENTIRE BRD and appended it as
 * {@code CURRENT BRD:}. Nothing bounded it, so a project with a thousand requirements sent a
 * thousand requirements — three times over, since the question pass, the drafting pass and the
 * per-question side conversation each did it. The build path was never like this: a run is scoped
 * to its story's own requirements and checks, so it is bounded by the size of the story rather than
 * by the size of the document. This gives the analyst the same property.
 *
 * <p><b>What is chosen.</b> The requirements that most resemble the documents being read, by the
 * same in-code matcher that catches near-duplicates ({@link RequirementSimilarity}). That is exactly
 * the right ranking for what the analyst is about to do: decide whether each thing the document says
 * already exists, and what it relates to. Below {@link #MAX_REQUIREMENTS} nothing is chosen at all —
 * the whole graph goes, unchanged, which is every project this product has actually been run on.
 *
 * <p><b>And it is SAID.</b> A model shown part of a document and not told so will read an absent
 * requirement as a non-existent one, and propose it again. The header states the total, states how
 * many are shown, and says plainly that the rest exist. That sentence is the difference between a
 * bounded prompt and a lying one.
 */
final class BrdSubset {

    /**
     * How many requirements one analyst call may carry.
     *
     * <p>A rendered requirement with its checks is roughly 300 characters, so this is about 36,000
     * characters — a quarter of the default document budget and comfortably inside any model this
     * product runs. It is a bound on the PROMPT, and the graph it is drawn from may be any size.
     */
    static final int MAX_REQUIREMENTS = 120;

    private BrdSubset() {}

    /** How many requirements the graph holds. */
    static int size(Brd brd) {
        return brd == null || brd.requirements() == null ? 0 : brd.requirements().size();
    }

    /** True when this graph is too large to send whole, so a subset and a search tool are needed. */
    static boolean isTrimmed(Brd brd) {
        return size(brd) > MAX_REQUIREMENTS;
    }

    /**
     * The requirements to show, ranked against {@code focusText} and then put back into handle order
     * so the analyst reads a document rather than a scoreboard.
     */
    static List<BrdRequirement> choose(Brd brd, String focusText) {
        List<BrdRequirement> all = brd == null || brd.requirements() == null
            ? List.of() : brd.requirements();
        if (all.size() <= MAX_REQUIREMENTS) {
            return all;
        }
        List<BrdRequirement> chosen = new ArrayList<>();
        for (RequirementSimilarity.Hit hit
                : RequirementSimilarity.nearest(focusText, "", all, 0.0, MAX_REQUIREMENTS)) {
            chosen.add(hit.requirement());
        }
        chosen.sort(Comparator.comparingInt(BrdSubset::handleNumber)
            .thenComparing(r -> r.handle() == null ? "" : r.handle()));
        return chosen;
    }

    /**
     * The {@code CURRENT BRD:} block for one analyst call: the chosen requirements, under a heading
     * that never lets an absence be read as a non-existence.
     */
    static String render(Brd brd, String focusText) {
        int total = size(brd);
        if (total == 0) {
            return "CURRENT BRD:\n(the BRD is currently empty)\n";
        }
        List<BrdRequirement> chosen = choose(brd, focusText);
        if (chosen.size() == total) {
            return "CURRENT BRD (all " + total + " requirement(s)):\n"
                + BrdAuthoring.render(brd, chosen) + "\n";
        }
        return "CURRENT BRD — A SUBSET, NOT THE WHOLE DOCUMENT:\n"
            + "This project has " + total + " requirements. The " + chosen.size()
            + " below are the ones closest in wording to the documents you are reading; the other "
            + (total - chosen.size()) + " EXIST and are simply not printed here.\n"
            + "So: a requirement you cannot see below is NOT evidence that it does not exist. "
            + "Before you propose anything as new, SEARCH for it (see the tool described above). "
            + "If you cannot search, say in your rationale that you could not check.\n"
            + BrdAuthoring.render(brd, chosen) + "\n";
    }

    /** R7 sorts after R2 and before R11 — string order would put R11 in the middle. */
    private static int handleNumber(BrdRequirement requirement) {
        String handle = requirement == null || requirement.handle() == null
            ? "" : requirement.handle();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < handle.length(); i++) {
            if (Character.isDigit(handle.charAt(i))) {
                digits.append(handle.charAt(i));
            }
        }
        try {
            return digits.length() == 0 ? Integer.MAX_VALUE : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
