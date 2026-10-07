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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code REFINES} hierarchy of a {@link Brd}, resolved into a tree — and the one place that
 * decides whether a new edge may be added (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §3.1).
 *
 * <p><b>Why a tree and not the general graph the edges can express.</b> {@code REFINES} means "the
 * source decomposes the target", and a requirement with two parents has no answer to "which parent's
 * scope is this part of?" — so it cannot be drawn, and more importantly cannot be reasoned about.
 * {@link RequirementRelation#DERIVED_FROM} already expresses "this came from that" without claiming
 * containment, so nothing is lost by refusing the second parent.
 *
 * <p><b>Why this lives in sc-domain.</b> Both the server (validating an edit, computing roll-ups)
 * and the browser client (drawing the tree) need the same parent/child/depth answers, and a second
 * derivation that merely happens to agree is a defect — the {@link BuildHealth} pattern.
 *
 * <p><b>Tolerant on read, strict on write.</b> Nothing prevented a second parent or a cycle before
 * this class existed, so a real store may contain either. Resolution therefore never fails and never
 * silently picks: the chosen parent is the lowest by handle order, the losers are reported by
 * {@link #extraParentsOf}, and an edge that closes a cycle is cut with the node it pointed from
 * becoming a root and appearing in {@link #inCycle}. The operator sees a badge and a fix; the
 * document still loads.
 */
public final class RequirementTree {

    private final Map<UUID, UUID> parent = new HashMap<>();
    private final Map<UUID, List<UUID>> children = new HashMap<>();
    private final Map<UUID, List<UUID>> extraParents = new HashMap<>();
    private final Map<UUID, Integer> depth = new HashMap<>();
    private final Set<UUID> cycles = new LinkedHashSet<>();
    private final List<UUID> roots = new ArrayList<>();

    private RequirementTree() {}

    /** Resolves {@code brd}'s REFINES edges into a tree. Never throws; null-safe throughout. */
    public static RequirementTree of(Brd brd) {
        RequirementTree tree = new RequirementTree();
        if (brd == null) {
            return tree;
        }
        List<BrdRequirement> reqs = brd.requirements() == null ? List.of() : brd.requirements();
        Map<UUID, String> handles = new HashMap<>();
        List<UUID> ordered = new ArrayList<>();
        for (BrdRequirement r : reqs) {
            if (r != null && r.id() != null) {
                handles.put(r.id(), r.handle());
                ordered.add(r.id());
            }
        }
        // Handle order, not list order: the list order is however the store happened to append, so
        // using it would make the chosen parent of a two-parent requirement depend on edit history.
        // The tree a violating document renders as has to be stable across reloads or the warning
        // badge moves around on its own.
        Collections.sort(ordered, (a, b) -> compareHandles(handles.get(a), handles.get(b)));

        // Every REFINES parent each requirement claims, in handle order.
        Map<UUID, List<UUID>> claimed = new HashMap<>();
        for (BrdEdge edge : brd.edges() == null ? List.<BrdEdge>of() : brd.edges()) {
            if (edge == null || edge.relation() != RequirementRelation.REFINES) {
                continue;
            }
            UUID child = edge.from();
            UUID up = edge.to();
            if (child == null || up == null || child.equals(up)
                    || !handles.containsKey(child) || !handles.containsKey(up)) {
                continue; // dangling or self-referencing: not this class's problem to report
            }
            List<UUID> list = claimed.computeIfAbsent(child, k -> new ArrayList<>());
            if (!list.contains(up)) {
                list.add(up);
            }
        }
        for (Map.Entry<UUID, List<UUID>> e : claimed.entrySet()) {
            List<UUID> ups = e.getValue();
            Collections.sort(ups, (a, b) -> compareHandles(handles.get(a), handles.get(b)));
            tree.parent.put(e.getKey(), ups.get(0));
            if (ups.size() > 1) {
                tree.extraParents.put(e.getKey(), List.copyOf(ups.subList(1, ups.size())));
            }
        }

        // Break cycles BEFORE anything reads depth, so no walk can loop. A node whose ancestor
        // chain returns to itself loses its parent edge and becomes a root.
        for (UUID id : ordered) {
            Set<UUID> path = new LinkedHashSet<>();
            UUID at = id;
            while (at != null && path.add(at)) {
                at = tree.parent.get(at);
            }
            if (at != null) {
                // `at` is where the chain closed on itself: cut there.
                tree.cycles.add(at);
                tree.parent.remove(at);
            }
        }

        for (UUID id : ordered) {
            UUID up = tree.parent.get(id);
            if (up == null) {
                tree.roots.add(id);
            } else {
                tree.children.computeIfAbsent(up, k -> new ArrayList<>()).add(id);
            }
        }
        for (UUID id : ordered) {
            tree.depth.put(id, tree.computeDepth(id));
        }
        return tree;
    }

    private int computeDepth(UUID id) {
        int d = 0;
        UUID at = parent.get(id);
        // Bounded by the node count: cycles are already cut, so this is belt-and-braces against a
        // future edit path that mutates `parent` after construction.
        int guard = parent.size() + 1;
        while (at != null && d <= guard) {
            d++;
            at = parent.get(at);
        }
        return d;
    }

    /** The requirement this one is part of, or null when it is a root. */
    public UUID parentOf(UUID id) {
        return parent.get(id);
    }

    /** Direct children in handle order; empty for a leaf. */
    public List<UUID> childrenOf(UUID id) {
        List<UUID> kids = children.get(id);
        return kids == null ? List.of() : List.copyOf(kids);
    }

    /** Top-level requirements in handle order. */
    public List<UUID> roots() {
        return List.copyOf(roots);
    }

    /** 0 for a root, 1 for its children, and so on. */
    public int depthOf(UUID id) {
        Integer d = depth.get(id);
        return d == null ? 0 : d;
    }

    /**
     * Parents this requirement claims but was NOT placed under, because a requirement belongs in one
     * place. Empty for every well-formed requirement; non-empty is what the warning badge renders.
     */
    public List<UUID> extraParentsOf(UUID id) {
        List<UUID> extra = extraParents.get(id);
        return extra == null ? List.of() : List.copyOf(extra);
    }

    /** Requirements whose parent edge was cut because it closed a cycle. */
    public List<UUID> inCycle() {
        return List.copyOf(cycles);
    }

    /** Every requirement carrying a shape warning — a second parent, or a cut cycle. */
    public List<UUID> withViolations() {
        Set<UUID> all = new LinkedHashSet<>(extraParents.keySet());
        all.addAll(cycles);
        return List.copyOf(all);
    }

    /** True when the document is a clean tree and no badge needs rendering anywhere. */
    public boolean isClean() {
        return extraParents.isEmpty() && cycles.isEmpty();
    }

    /** Every descendant of {@code id}, depth-first in handle order, excluding {@code id} itself. */
    public List<UUID> descendantsOf(UUID id) {
        List<UUID> out = new ArrayList<>();
        collect(id, out, new HashSet<>());
        return out;
    }

    private void collect(UUID id, List<UUID> into, Set<UUID> seen) {
        for (UUID kid : childrenOf(id)) {
            if (seen.add(kid)) {
                into.add(kid);
                collect(kid, into, seen);
            }
        }
    }

    // --- the write gate --------------------------------------------------------------------------

    /**
     * Why {@code from --relation--> to} may not be added, or null when it may.
     *
     * <p>This is the single decision point for every edge write path: the agent's
     * {@code BrdAuthoring.addEdge} and the operator's {@code BrdServiceImpl.saveEdge} both call it.
     * They previously validated differently — the agent path refused a functional requirement as a
     * {@code GATES} source and the operator path did not — which is exactly the asymmetry a shared
     * gate exists to prevent. Restoring an old revision deliberately does NOT call this: history is
     * reproduced as it was, and whatever shape it had shows up as a badge.
     *
     * <p>Messages are written for whoever reads them — an operator in a toast, or a model deciding
     * what to do next — so they name the handles and say what to do instead.
     */
    public static String rejectionFor(Brd brd, UUID from, UUID to, RequirementRelation relation) {
        if (brd == null || from == null || to == null || relation == null) {
            return null; // the callers already reject these; nothing to add
        }
        if (relation == RequirementRelation.GATES) {
            BrdRequirement source = requirement(brd, from);
            if (source != null && !source.isNonFunctional()) {
                // The words the pickers on screen actually use. This said "non_functional", which
                // is the Java constant and appears nowhere the operator can see, so the one
                // instruction in the sentence named a setting that does not exist by that name.
                return "error: only a quality requirement can constrain another — "
                    + handle(brd, from) + " is functional. Set its kind to non-functional first, "
                    + "or choose a different relation.";
            }
            return null;
        }
        if (relation != RequirementRelation.REFINES) {
            return null;
        }
        RequirementTree tree = of(brd);
        UUID existing = tree.parentOf(from);
        if (existing != null && !existing.equals(to)) {
            return "error: " + handle(brd, from) + " is already part of " + handle(brd, existing)
                + " — a requirement belongs in one place. Remove that link first, or use"
                // The words the picker on screen actually offers, from RequirementRelation. This
                // named "depends on" and "derived from", which are the Java constants read aloud
                // and are not what the control says.
                + " \"" + RequirementRelation.DEPENDS_ON.label() + "\" or \""
                + RequirementRelation.DERIVED_FROM.label()
                + "\" to express a looser connection.";
        }
        // A cycle is "to is already somewhere below from": making from a child of to would close it.
        if (tree.descendantsOf(from).contains(to)) {
            return "error: " + handle(brd, to) + " is already part of " + handle(brd, from)
                + ", so making " + handle(brd, from) + " part of it would create a loop.";
        }
        return null;
    }

    private static BrdRequirement requirement(Brd brd, UUID id) {
        for (BrdRequirement r : brd.requirements() == null ? List.<BrdRequirement>of()
                : brd.requirements()) {
            if (r != null && id.equals(r.id())) {
                return r;
            }
        }
        return null;
    }

    private static String handle(Brd brd, UUID id) {
        BrdRequirement r = requirement(brd, id);
        return r != null && r.handle() != null ? r.handle() : String.valueOf(id);
    }

    /**
     * Compares {@code R2} and {@code R10} as an operator reads them — 2 before 10, not "10" before
     * "2". Handles are minted {@code R1, R2, …}, so a plain string sort would order the tree by
     * digit characters and look arbitrary to anyone with more than nine requirements.
     */
    static int compareHandles(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        int na = trailingNumber(a);
        int nb = trailingNumber(b);
        if (na >= 0 && nb >= 0) {
            String pa = a.substring(0, a.length() - String.valueOf(na).length());
            String pb = b.substring(0, b.length() - String.valueOf(nb).length());
            int byPrefix = pa.compareTo(pb);
            if (byPrefix != 0) {
                return byPrefix;
            }
            return Integer.compare(na, nb);
        }
        return a.compareTo(b);
    }

    /** The number a handle ends with ({@code R12} → 12), or -1 when it does not end in digits. */
    private static int trailingNumber(String handle) {
        int end = handle.length();
        int start = end;
        while (start > 0 && handle.charAt(start - 1) >= '0' && handle.charAt(start - 1) <= '9') {
            start--;
        }
        if (start == end || end - start > 9) {
            return -1; // no digits, or too many to hold in an int
        }
        return Integer.parseInt(handle.substring(start, end));
    }
}
