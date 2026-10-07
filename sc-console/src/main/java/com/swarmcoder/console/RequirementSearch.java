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
import java.util.List;

/**
 * The analyst's one tool: look up requirements in the graph it was only shown part of
 * (DEVELOPER_CORRECTIONS.md §21).
 *
 * <p><b>Why the analyst gets a tool and the workers do not.</b> N-way dispatch depends on all N
 * workers receiving a byte-identical prompt prefix so the model server's prefix cache is paid for
 * once; a per-worker tool call is divergent by nature and would cost that. A worker also has no use
 * for this — its scope is one task, one write set and named checks. The analyst is a single
 * instance making a handful of calls with no prefix cache to protect, and it is the role that
 * drowns. This is the same split the project already made for the code analyser.
 *
 * <p><b>In-process, not a protocol.</b> One Java method over the graph already in memory. Not MCP —
 * descoped by author decision, both its intended consumers gone.
 *
 * <p><b>Read-only, and that is a rule, not an implementation detail (R11, §20).</b> Retrieval must
 * never become a route by which an agent writes. Everything here takes a {@link Brd} and returns a
 * string; there is no store, no project id and no mutation to reach.
 */
final class RequirementSearch {

    /** Enough to answer "does this already exist?"; short enough not to undo the bounded prompt. */
    static final int MAX_RESULTS = 12;

    /**
     * Below this the match is not worth printing. Lower than the flagging threshold on purpose: the
     * analyst asked a question and deserves the near misses, whereas an automatic un-tick has to be
     * more sure of itself.
     */
    private static final double FLOOR = 0.20;

    private RequirementSearch() {}

    /**
     * The requirements that best answer {@code query}, rendered for the model.
     *
     * <p>A handle typed as the query (R42) answers with that requirement exactly, because "what does
     * R42 actually say?" is the other question this tool gets asked and word-similarity is a poor
     * way to answer it.
     */
    static String search(Brd brd, String query) {
        int total = BrdSubset.size(brd);
        if (total == 0) {
            return "The BRD is empty — nothing exists yet, so everything in the documents is new.";
        }
        String wanted = query == null ? "" : query.strip();
        if (wanted.isEmpty()) {
            return "Search for what? Give the words of the requirement you are checking, "
                + "for example: SEARCH refund within 30 days.";
        }
        List<BrdRequirement> all = brd.requirements();

        BrdRequirement byHandle = null;
        for (BrdRequirement requirement : all) {
            if (wanted.equalsIgnoreCase(requirement.handle())) {
                byHandle = requirement;
                break;
            }
        }
        if (byHandle != null) {
            return "SEARCH RESULT for \"" + wanted + "\" — that handle exists:\n"
                + BrdAuthoring.render(brd, List.of(byHandle));
        }

        List<RequirementSimilarity.Hit> hits =
            RequirementSimilarity.nearest(wanted, "", all, FLOOR, MAX_RESULTS);
        if (hits.isEmpty()) {
            return "SEARCH RESULT for \"" + wanted + "\": nothing in the " + total
                + " requirements of this BRD resembles that. It is new ground.";
        }
        List<BrdRequirement> found = new ArrayList<>();
        StringBuilder scores = new StringBuilder();
        for (RequirementSimilarity.Hit hit : hits) {
            found.add(hit.requirement());
            scores.append("  ").append(hit.requirement().handle()).append(" — closeness ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", hit.score())).append('\n');
        }
        return "SEARCH RESULT for \"" + wanted + "\" — " + hits.size() + " of " + total
            + " requirements resemble it, closest first:\n" + scores
            + BrdAuthoring.render(brd, found)
            + "Closeness is word overlap, not judgement. A high number means READ IT and decide "
            + "whether the document is saying the same thing; it does not decide for you.\n";
    }
}
