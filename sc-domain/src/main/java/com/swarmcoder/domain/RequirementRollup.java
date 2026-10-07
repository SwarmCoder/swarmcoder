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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Check coverage per requirement, on its own and rolled up over everything beneath it
 * (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.4).
 *
 * <p><b>Why this exists.</b> It is the one thing a hierarchy buys that a flat list cannot: "Checkout
 * is 12 of 20 verified" with nobody maintaining that number. And it is computed in exactly one place
 * because a coverage figure shown on a tree row, in a requirement's own header, and in a filter must
 * be the same figure — a second computation is a defect even while it agrees (UX v3 rule 2).
 *
 * <p><b>Own and subtree are different numbers and are never conflated.</b> A coarse requirement
 * usually carries no checks of its own; reporting its subtree's figure as its own would claim it is
 * directly verified when nothing verifies it. Callers render both, labelled.
 *
 * <p><b>Deliberately not here: "has unclaimed checks."</b> §3.4 lists it, but whether a check is
 * claimed is a property of the <em>backlog</em>, not of the requirement graph — answering it needs the
 * stories. It belongs to the paged query of §5 step 3, where the store has both. Taking a claimed-set
 * argument here would mean every caller without the backlog silently passing "nothing is claimed",
 * which reports every check as unclaimed and looks like data rather than a missing argument.
 */
public final class RequirementRollup {

    private final RequirementTree tree;
    private final Map<UUID, CheckCounts> own = new HashMap<>();
    private final Map<UUID, CheckCounts> subtree = new HashMap<>();
    private final Map<UUID, Integer> descendants = new HashMap<>();

    private RequirementRollup(RequirementTree tree) {
        this.tree = tree;
    }

    /** Resolves the hierarchy and rolls up in one pass. */
    public static RequirementRollup of(Brd brd) {
        return of(brd, RequirementTree.of(brd));
    }

    /**
     * Rolls up against an already-resolved hierarchy — the form to use when the caller also needs the
     * tree, so it is resolved once rather than twice.
     */
    public static RequirementRollup of(Brd brd, RequirementTree tree) {
        RequirementRollup rollup = new RequirementRollup(
            tree == null ? RequirementTree.of(brd) : tree);
        if (brd == null || brd.requirements() == null) {
            return rollup;
        }
        List<BrdRequirement> reqs = brd.requirements();
        List<UUID> ids = new ArrayList<>();
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null) {
                rollup.own.put(r.id(), CheckCounts.of(r));
                rollup.subtree.put(r.id(), CheckCounts.of(r));
                rollup.descendants.put(r.id(), 0);
                ids.add(r.id());
            }
        }
        // Deepest first, so a node's total is complete before it is added into its parent. Bottom-up
        // rather than descendantsOf() per node: that would re-walk the same subtrees once per
        // ancestor, and at a few thousand requirements the difference is a visible pause.
        Collections.sort(ids, (a, b) ->
            Integer.compare(rollup.tree.depthOf(b), rollup.tree.depthOf(a)));
        for (UUID id : ids) {
            UUID parent = rollup.tree.parentOf(id);
            if (parent == null || !rollup.subtree.containsKey(parent)) {
                continue;
            }
            // The tree has already cut cycles and dropped every second parent, so each node adds into
            // exactly one ancestor chain and nothing can be counted twice.
            rollup.subtree.put(parent, rollup.subtree.get(parent).plus(rollup.subtree.get(id)));
            rollup.descendants.put(parent,
                rollup.descendants.get(parent) + rollup.descendants.get(id) + 1);
        }
        return rollup;
    }

    /** The hierarchy these counts were rolled up over. */
    public RequirementTree tree() {
        return tree;
    }

    /** This requirement's own checks — what verifies IT, not what verifies its parts. */
    public CheckCounts own(UUID requirementId) {
        CheckCounts counts = own.get(requirementId);
        return counts == null ? CheckCounts.EMPTY : counts;
    }

    /** This requirement's checks plus every check beneath it. */
    public CheckCounts subtree(UUID requirementId) {
        CheckCounts counts = subtree.get(requirementId);
        return counts == null ? CheckCounts.EMPTY : counts;
    }

    /** How many requirements sit beneath this one, at any depth. */
    public int descendants(UUID requirementId) {
        Integer count = descendants.get(requirementId);
        return count == null ? 0 : count;
    }

    /** True when this requirement has parts, so its subtree figure is worth showing beside its own. */
    public boolean hasChildren(UUID requirementId) {
        return descendants(requirementId) > 0;
    }
}
